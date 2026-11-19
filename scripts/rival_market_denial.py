"""Measure whether equal-cost pressure on a leading or exposed opponent changes wins."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/rival-denial-lookahead-20261119"
KEY = "rivalMarketDenialRollouts"
ARMS = ("score-leader", "commodity-pressure")


def leader_win(result, leaders):
    winners = [row for row in result["scores"] if row["winner"]]
    if not winners:
        return 0.0
    return sum(row["player"] in leaders for row in winners) / len(winners)


def outcomes(stage, count):
    samples = {name: [] for name in ARMS}
    observed = set()
    for worlds in sorted(stage.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(worlds.parent, worlds.parent.with_suffix(".json"))
        for line in worlds.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in observed:
                raise ValueError("One world can contribute at most one root and one pair per arm.")
            observed.add(index)
            result = row["diagnostics"]["CANDIDATE"][KEY]
            if result["status"] != "paired":
                continue
            ordinary = result["tails"]["ordinary"]
            for name in ARMS:
                if name not in result["tails"]:
                    continue
                chosen = result["offers"][name]
                baseline = result["offers"]["ordinary"]
                if (chosen["tokens"] > baseline["tokens"] + 2 or
                    chosen["winProbability"] + .15000001 < baseline["winProbability"]):
                    raise ValueError("Paired alternatives must meet the original cost and dice constraints.")
                rows = zip(result["tails"][name], ordinary, strict=True)
                changes = []
                for new, old in rows:
                    after, before = new["terminal"], old["terminal"]
                    if after["continuationSeed"] != before["continuationSeed"]:
                        raise ValueError("Both arms of each comparison must share its continuation seed.")
                    changes.append((after["winShare"] - before["winShare"],
                                    leader_win(after, result["leaders"]) -
                                    leader_win(before, result["leaders"])))
                if len(changes) != 2:
                    raise ValueError("Each comparison must include two prospective futures.")
                samples[name].append({
                    "own": statistics.mean(point[0] for point in changes),
                    "leaders": statistics.mean(point[1] for point in changes),
                    "gap": result["ownGap"], "epoch": result["epoch"],
                    "alternativeDenial": chosen["publicCommodityDenial"],
                    "targetsLeader": chosen["defender"] in result["leaders"],
                })
    if observed != set(range(count)):
        raise ValueError("The original seed bank must be complete before reporting.")
    return samples


def summarize(rows):
    if len(rows) < 2:
        return {"eligible": len(rows)}
    return {
        "eligible": len(rows),
        "ownWinShareDelta": interval([row["own"] for row in rows]),
        "leaderWinShareDelta": interval([row["leaders"] for row in rows]),
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("The denial study must use the qualified exact reference policy.")
    stage = simulated_positions(campaign, root, anchor["config"], count, rival_denial=True)
    cases = outcomes(stage, count)
    write(root / "result.json", {
        "worlds": count,
        "effects": {arm: {
            "all": summarize(rows),
            "finalEpoch": summarize([row for row in rows if row["epoch"] == 3]),
            "aheadBy100": summarize([row for row in rows if row["gap"] >= 100]),
            "targetsLeader": summarize([row for row in rows if row["targetsLeader"]]),
            "denies20": summarize([row for row in rows if row["alternativeDenial"] >= 20]),
        } for arm, rows in cases.items()},
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--count", default=384, choices=(256, 384, 512), type=int)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
