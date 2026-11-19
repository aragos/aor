"""Find opening mixtures that keep winning against deliberate first-turn counterbids."""

import argparse
from itertools import combinations, product
from pathlib import Path
import statistics
import time

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_mix_fit import cautious_mean
from initiative_opening_strategy import load_games, with_mix


REMOTE = "/home/aragos/aor-worker/informed-bid-matrix-20261119"
BIDS = tuple(range(15, 23))
RIVAL_BIDS = (14, 16, 18, 20, 22)
STYLE = "REVEALED_UNDERCUT"
DEVELOPMENT_WORLDS = 128
FRESH_WORLDS = 640


def config(amount):
    return {"minimumObservations": 1, "lookbackTurns": 1, "openingBidPrior": amount+1}


def chronological_rows(folder, name, rival_amount, target_amount=None):
    records = load_games(folder, name)
    results = []
    for index, row in sorted(records.items()):
        evidence = row["diagnostics"]["CANDIDATE"]
        proof = next(item for item in evidence["publicTokenCounter"]["decisionProofs"]
                     if item["phase"] == "order-revealed" and item["turn"] == 1)
        opening = evidence["openingBid"]
        if (
            proof["actualRivalBid"] != rival_amount or proof["prediction"] != rival_amount+1
            or proof["actualTargetBid"] != opening["bid"]
            or (target_amount is not None and opening["bid"] != target_amount)
        ):
            raise ValueError("The resolved opening must preserve both experimental bid amounts.")
        seat = row["seat"]
        wins = row["roles"]["CANDIDATE"]["wins"]
        results.append({
            "world": (index, row["seed"], seat, opening["capital"], opening["capitalPosition"]),
            "later": opening["capitalPosition"] >= 2,
            "own": wins[seat],
            "rival": wins[(seat+1) % 4],
        })
    return results


def market(campaign, phase, bids, policies, control, rivals, count):
    label = f"{phase}-counter-{bids}"
    names = ("control", *policies)
    tested = campaign.stage(
        label, policies, count, (STYLE,), baseline=control, shard=128,
        lineup=rivals, counter_variants=dict.fromkeys(names, config(bids)),
    )
    outcomes = {name: chronological_rows(campaign.root/label, name, bids,
                                        int(name[4:]) if name.startswith("bid-") else None)
                for name in names}
    identity = [row["world"] for row in outcomes["control"]]
    if (len(identity) != count
        or any([row["world"] for row in rows] != identity for rows in outcomes.values())):
        raise ValueError("A countermove column needs each original world with unchanged setup.")
    return tested, outcomes


def expected(columns, anchor):
    options = [bids for size in (2, 3, 4) for bids in combinations(BIDS, size)]
    by_group = {False:{}, True:{}}
    for later in by_group:
        for support in options:
            summary = {}
            for rival, outcomes in columns.items():
                included = [idx for idx,row in enumerate(outcomes["control"]) if row["later"]==later]
                if len(included) < 32:
                    raise ValueError("Both capital groups require at least 32 paired worlds.")
                rival_arms = {bid: outcomes[f"bid-{bid}"] for bid in BIDS}
                more, wins, other = [], [], []
                for idx in included:
                    target = statistics.mean(rival_arms[bid][idx]["own"] for bid in support)
                    anchor_point = statistics.mean(rival_arms[bid][idx]["own"]
                                                   for bid in anchor[int(later)])
                    wins.append(target)
                    more.append(target-anchor_point)
                    other.append(statistics.mean(rival_arms[bid][idx]["rival"] for bid in support))
                summary[rival] = {
                    "cautiousWin": cautious_mean(wins), "cautiousGain": cautious_mean(more),
                    "win": statistics.mean(wins), "rivalWin": statistics.mean(other),
                    "fraction": len(included)/len(outcomes["control"]),
                }
            by_group[later][support] = summary
    return by_group


def best(estimates, basis, anchor):
    def score(early, late):
        details = {str(rival): {
            metric: sum(row[metric]*row["fraction"] for row in
                        (estimates[False][early][rival], estimates[True][late][rival]))
            for metric in ("cautiousWin", "cautiousGain", "win", "rivalWin")
        } for rival in RIVAL_BIDS}
        strength = [point[basis] for point in details.values()]
        return .8*min(strength) + .2*statistics.mean(strength), details
    fits = []
    for early, late in product(estimates[False], estimates[True]):
        if len(set(early) | set(late)) < 3:
            continue
        utility, details = score(early, late)
        fits.append((utility, len(set(early) | set(late)), early, late, details))
    utility, _, early, late, details = max(fits, key=lambda row: row[:2])
    old_utility, _ = score(*anchor)
    return (early, late), {"utility": utility, "baselineUtility": old_utility, "opponents": details}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    opening = previous/"automata-opening-mix-fit-20261119"
    for _ in range(110):
        if (opening/"result.json").exists():
            break
        if campaign.remaining() < 3600:
            raise RuntimeError("The remaining window cannot support the informed bidding matrix.")
        time.sleep(30)
    fit = read(opening/"fit.json")
    control = fit["candidates"].get("capital-priority", fit["candidates"]["global"])
    opening_mix = control["openingMix"]
    anchor = tuple(opening_mix["bids"]), tuple(opening_mix.get("laterCapitalBids", opening_mix["bids"]))
    setup = lineup({int(offset): policy for offset,policy in fit["plan"]["rivals"].items()
                    if offset != "1"})
    amounts = {f"bid-{bid}": with_mix(control, [bid]) for bid in BIDS}
    plan = {"fitSha256": sha(opening/"fit.json"), "reference": control, "rivals": setup,
            "targetBids": list(BIDS), "rivalBids": list(RIVAL_BIDS)}
    plan_path = root/"plan.json"
    if not plan_path.exists():
        write(plan_path, plan)
    elif read(plan_path) != plan:
        raise ValueError("The informed bidding comparison must preserve its frozen reference.")
    table = {}
    for bid in RIVAL_BIDS:
        _, rows = market(campaign, "directions", bid, amounts, control, setup, DEVELOPMENT_WORLDS)
        table[bid] = rows
    estimates = expected(table, anchor)
    policies, fits = {}, {}
    for key, basis in (("maximin-win", "cautiousWin"), ("maximin-gain", "cautiousGain")):
        (early, later), fit = best(estimates, basis, anchor)
        fits[key] = dict(fit, early=early, later=later)
        policy = with_mix(control, early, later)
        if (policy != control and policy not in policies.values()
            and fit["utility"] - fit["baselineUtility"] >= .015):
            policies[key] = policy
    write(root/"screen.json", {"fit": fits, "nominations": policies})
    fresh = {}
    for bid in (14, 18, 22) if policies else ():
        tested, _ = market(campaign, "fresh", bid, policies, control, setup, FRESH_WORLDS)
        fresh[str(bid)] = {name: value["outcomes"][STYLE] for name,value in tested.items()}
    passed = [name for name in policies if len(fresh)==3
              and min(scope[name]["lower95"] for scope in fresh.values()) >= -.025
              and statistics.mean(scope[name]["mean"] for scope in fresh.values()) >= .025]
    write(root/"result.json", {"qualifiedForWiderTest": passed, "nominees": policies, "fresh": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
