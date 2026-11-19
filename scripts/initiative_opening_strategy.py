"""Measure whole-game returns from opening bids and train a capital-priority mixture."""

import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, write
from initiative_heterogeneous_campaign import compare, trained_counter
from initiative_league import rank


REMOTE = "/home/aragos/aor-worker/initiative-opening-strategy-20261119"
OPENINGS = range(16, 23)


def with_mix(policy, bids, later=()):
    mix = {"bids": sorted(bids)}
    if later:
        mix["laterCapitalBids"] = sorted(later)
    return dict(policy, openingMix=mix)


def measure(campaign, label, policies, count, plan):
    roster = {int(offset): policy for offset, policy in plan["rivals"].items()}
    outcomes = compare(campaign, label, policies, count, plan["baseline"], roster, plan["counter"])
    familiar = campaign.stage(
        f"{label}-familiar", policies, count, ("REVEALED_UNDERCUT",), shard=128,
        baseline=plan["baseline"], opening_bid_prior=19,
    )
    for name, scores in outcomes.items():
        scores["familiar"] = familiar[name]["outcomes"]["REVEALED_UNDERCUT"]
    return outcomes


def load_games(folder, name):
    games = {}
    for path in folder.glob(f"{name}-0-*/worlds.jsonl"):
        for line in path.open():
            row = json.loads(line)
            if row["index"] in games:
                raise ValueError(f"Duplicate opening experiment world in {path}.")
            games[row["index"]] = row
    if not games:
        raise ValueError(f"Missing opening experiment worlds in {folder}.")
    return games


def priority_effects(root):
    by_bid = {}
    for condition in ("mixed-table", "adaptive-table", "familiar"):
        folder = root / f"opening-screen-{condition}"
        controls = load_games(folder, "control")
        for amount in OPENINGS:
            games = load_games(folder, f"bid-{amount}")
            if games.keys() != controls.keys():
                raise ValueError(f"Opening bid {amount} lacks a complete paired world bank.")
            groups = {"early": [], "late": []}
            for index, row in games.items():
                baseline = controls[index]
                seat = row["seat"]
                opening = row["diagnostics"]["CANDIDATE"]["openingBid"]
                prior = baseline["diagnostics"]["CANDIDATE"]["openingBid"]
                if (
                    row["seed"] != baseline["seed"]
                    or seat != baseline["seat"]
                    or opening["capital"] != prior["capital"]
                    or opening["capitalPosition"] != prior["capitalPosition"]
                    or opening["bid"] != amount
                ):
                    raise ValueError("Paired opening must preserve its setup and apply the bid.")
                group = "early" if opening["capitalPosition"] < 2 else "late"
                groups[group].append(
                    row["roles"]["CANDIDATE"]["wins"][seat]
                    - baseline["roles"]["CANDIDATE"]["wins"][seat]
                )
            by_bid.setdefault(amount, {})[condition] = {
                name: interval(values) for name, values in groups.items()
            }
    return by_bid


def fit_mixes(baseline, grouped, overall):
    def utility(gains):
        return .65 * min(gains) + .35 * statistics.mean(gains)

    def global_score(amount):
        return utility([row["mean"] for row in overall[f"bid-{amount}"].values()])

    def group_score(amount, group):
        regional = utility([row[group]["mean"] for row in grouped[amount].values()])
        return .65 * regional + .35 * global_score(amount)

    def choose(score):
        ordered = sorted(OPENINGS, key=score, reverse=True)
        return [amount for amount in ordered[:3] if score(ordered[0]) - score(amount) <= .035]

    broad = choose(global_score)
    early = choose(lambda amount: group_score(amount, "early"))
    late = choose(lambda amount: group_score(amount, "late"))
    candidates = {"broad": with_mix(baseline, broad)}
    conditional = with_mix(baseline, early, late)
    if early != late and conditional not in candidates.values():
        candidates["capital-priority"] = conditional
    return candidates


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, background=True)
    campaign.validate_runtime()
    plan_path = root / "plan.json"
    if resume and plan_path.is_file():
        plan = read(plan_path)
    else:
        roster = read(previous / "automata-heterogeneous-20261118/roster.json")
        baseline = read(previous / "automata-initiative-20261118/result.json")["comparison"]["config"]
        plan = {"baseline": baseline, "rivals": roster["rivals"],
                "counter": trained_counter(previous, roster["counter"])}
        write(plan_path, plan)
    candidates = {f"bid-{bid}": with_mix(plan["baseline"], [bid]) for bid in OPENINGS}
    screen = measure(campaign, "opening-screen", candidates, 192, plan)
    groups = priority_effects(root)
    mixtures = fit_mixes(plan["baseline"], groups, screen)
    screen_report = {"candidates": candidates, "outcomes": screen,
                     "capitalPriority": {str(key): val for key, val in groups.items()},
                     "mixtures": mixtures}
    screen_path = root / "screen.json"
    if resume and screen_path.is_file():
        if read(screen_path) != screen_report:
            raise ValueError("The recorded opening screen differs from verified simulations.")
    else:
        write(screen_path, screen_report)
    if campaign.remaining() < 2400:
        write(root / "result.json", {"status": "screen-complete", "mixtures": mixtures})
        return
    fresh = measure(campaign, "opening-fresh", mixtures, 640, plan)
    best = rank(fresh)[0]
    passed = all(row["lower95"] > 0 for row in fresh[best].values())
    stress = {}
    if passed and campaign.remaining() >= 1800:
        for prior in (18, 20):
            outcome = campaign.stage(
                f"opening-stress-prior-{prior}", {best: mixtures[best]}, 512,
                ("REVEALED_UNDERCUT",), shard=128, baseline=plan["baseline"], opening_bid_prior=prior,
            )
            stress[str(prior)] = outcome[best]["outcomes"]["REVEALED_UNDERCUT"]
    write(root / "result.json", {"status": "fresh-complete", "selected": best,
                                 "configuration": mixtures[best], "fresh": fresh, "stress": stress,
                                 "passed": passed and len(stress) == 2 and
                                 min(row["lower95"] for row in stress.values()) > -.025})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    options = parser.parse_args()
    main(options.root.resolve(), options.previous.resolve(), options.resume)
