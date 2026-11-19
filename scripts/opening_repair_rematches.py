"""Transfer a qualified opening mix to the wider attack rule and test learned replies."""

import argparse
from collections import Counter
from pathlib import Path
import statistics
import time

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_informed_bid_matrix import chronological_rows
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP, profiles, revealed_openings
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/opening-repair-rematches-20261119"
STYLE = "REVEALED_UNDERCUT"
ARMS = ("control", "repaired")
BIDS = range(13, 23)
REPLIES = (14, 20)
WORLDS = 1024


def payoff_table(lab):
    table, pins, seeds = {}, {}, set()
    for amount in REPLIES:
        fresh = lab / f"opening-repair-develop-known-{amount}"
        method = fresh / "method.json"
        seeds.update(read(method)["banks"][STYLE])
        pins[str(amount)] = sha(method)
        table[amount] = {}
        for bid in BIDS:
            name = f"bid-{bid}"
            for output in fresh.glob(f"{name}-0-*/worlds.jsonl"):
                Campaign.validate_result(output.parent, fresh / f"{output.parent.name}.json")
            rows = chronological_rows(fresh, name, amount, bid)
            if len(rows) != 384:
                raise ValueError("Every learned reply must use all frozen development payoff worlds.")
            full = statistics.mean(row["rival"] - .15 * row["own"] for row in rows)
            table[amount][bid] = {
                key: .30 * full + .70 * statistics.mean(
                    row["rival"] - .15 * row["own"] for row in rows if row["later"] == later
                )
                for key, later in (("earlyBid", False), ("lateBid", True))
            }
    return table, pins, seeds


def learned_replies(history, table):
    replies = []
    for start in range(0, len(history), GAMES_PER_GROUP):
        games = history[start:start + GAMES_PER_GROUP]
        if len(games) != GAMES_PER_GROUP:
            raise ValueError("A simulated adaptation group needs four completed public games.")
        overall = Counter(row["bid"] for row in games)
        response = {}
        for key, later in (("earlyBid", False), ("lateBid", True)):
            conditional = Counter(row["bid"] for row in games if row["laterCapitalChoice"] == later)
            weights = {bid: .25 + .50 * overall[bid] + conditional[bid] for bid in BIDS}
            response[key] = max(REPLIES, key=lambda amount: sum(
                weights[bid] * table[amount][bid][key] for bid in BIDS
            ))
        replies.extend([response] * GAMES_PER_GROUP)
    return replies


def compare(folder, replies):
    before, after = (load_games(folder, name) for name in ARMS)
    own, rival = [], []
    observed = {name: Counter() for name in ARMS}
    for i in range(WORLDS):
        a, b = before[i], after[i]
        seat = a["seat"]
        def setup(row):
            opened = row["diagnostics"]["CANDIDATE"]["openingBid"]
            return row["seed"], row["seat"], opened["capital"], opened["capitalPosition"], opened["cash"]
        if setup(a) != setup(b):
            raise ValueError("Opening mixes must preserve the paired setup that precedes the sealed bid.")
        for name, row in zip(ARMS, (a, b), strict=True):
            diagnostic = row["diagnostics"]["CANDIDATE"]
            opening = diagnostic["openingBid"]
            proof = next(entry for entry in diagnostic["publicTokenCounter"]["decisionProofs"]
                         if entry["turn"] == 1 and entry["phase"] == "order-revealed")
            key = "lateBid" if opening["capitalPosition"] >= 2 else "earlyBid"
            expected = replies[name][i][key] if replies else 14
            if proof["actualRivalBid"] != expected or proof["actualTargetBid"] != opening["bid"]:
                raise ValueError("Each rival must follow the frozen public reply in the sealed opening.")
            observed[name][opening["bid"]] += 1
        baseline, candidate = [row["roles"]["CANDIDATE"]["wins"] for row in (a, b)]
        own.append(candidate[seat] - baseline[seat])
        rival.append(candidate[(seat + 1) % 4] - baseline[(seat + 1) % 4])
    def summarize(values):
        return interval([statistics.mean(values[i:i + GAMES_PER_GROUP])
                         for i in range(0, WORLDS, GAMES_PER_GROUP)] if replies else values)
    return {"ownWin": summarize(own), "rivalWin": summarize(rival), "openingBids": dict(observed)}


def main(root, previous, resume, wait):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    repair = previous / "automata-opening-repair-20261119"
    source = repair / "result.json"
    while wait and not source.is_file() and campaign.remaining() > 3000:
        time.sleep(25)
    if not source.is_file() or campaign.remaining() < 3000 or not read(source)["passed"]:
        write(root / "result.json", {"passed": False, "status": "no-opening-qualification-at-cutoff"})
        return
    qualified = read(source)
    reference = read(previous / "automata-capital-evolved-targeting-20261119/result.json")
    if not reference["passed"]:
        raise ValueError("The repaired opening mix must be tested with independently qualified attacks.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    history_stage = repair / "opening-repair-fresh-experienced"
    histories = {
        name: revealed_openings(history_stage, origin, expected_worlds=1280)[:WORLDS]
        for name, origin in (("control", "control"), ("repaired", qualified["selected"]))
    }
    table, pins, used = payoff_table(repair)
    used |= {row["seed"] for records in histories.values() for row in records}
    replies = {name: learned_replies(records, table) for name, records in histories.items()}
    priors = {name: profiles(records)["capital-modal"] for name, records in histories.items()}
    plan = {"qualifiedMixSha256": sha(source), "payoffProof": pins, "attack": reference["configuration"],
            "histories": histories, "responses": replies, "predictions": priors}
    saved = root / "plan.json"
    if not saved.exists():
        write(saved, plan)
    elif read(saved) != plan:
        raise ValueError("A resumed rematch must preserve the exact observed games and scheduled replies.")
    outcomes = {}
    for case in ("known-14", "learned"):
        _, setup, _ = selection_context(anchor, "known-14", WORLDS, True)
        learned = case == "learned"
        stage = f"opening-repair-combined-fresh-{case}"
        if set(seed_bank(f"{stage}/{STYLE}", WORLDS)) & used:
            raise ValueError("Public training histories and scored rematches must be independent.")
        campaign.stage(
            stage, {"repaired": qualified["configuration"]}, WORLDS, (STYLE,), shard=128,
            baseline=anchor["baseline"], lineup=setup["lineup"], capital_geography_variants=ARMS,
            leading_rival_variants=dict.fromkeys(ARMS, reference["configuration"]),
            counter_variants=dict.fromkeys(ARMS, {"minimumObservations": 1, "lookbackTurns": 1}
                                         if learned else setup["counter"]),
            series_priors=priors if learned else None, series_responses=replies if learned else None,
        )
        outcomes[case] = compare(root / stage, replies if learned else None)
    scores = [value["ownWin"] for value in outcomes.values()]
    passed = (min(row["lower95"] for row in scores) > -.025
              and statistics.mean(row["mean"] for row in scores) >= .01
              and any(row["lower95"] > 0 for row in scores))
    write(root / "result.json", {"passed": passed, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--wait", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume, args.wait)
