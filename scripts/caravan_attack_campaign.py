"""Measure attack sequencing through a contested Caravan gateway with paired futures."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/caravan-followthrough-20261119"
KEY = "caravanAttackRollouts"
ARMS = ("ordinary", "attack-gateway", "attack-and-place")


def collect(stage, count):
    rows, seen = [], set()
    for source in sorted(stage.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(source.parent, source.parent.with_suffix(".json"))
        for line in source.read_text().splitlines():
            world = json.loads(line)
            if world["index"] in seen:
                raise ValueError("Each initial seed may contribute only its first eligible gateway.")
            seen.add(world["index"])
            root = world["diagnostics"]["CANDIDATE"][KEY]
            if root["status"] != "paired":
                continue
            tails = root["tails"]
            seeds = [[sample["terminal"]["continuationSeed"] for sample in tails[arm]]
                     for arm in ARMS]
            if len(seeds[0]) != 2 or any(pair != seeds[0] for pair in seeds[1:]):
                raise ValueError("All legal alternatives must replay both matched futures.")
            if not .45 <= root["attackChance"] <= 1 or not 2 <= root["targetCost"] <= 6:
                raise ValueError("The selected gateway must meet the declared cost and risk range.")
            wins = [statistics.mean(sample["terminal"]["winShare"] for sample in tails[arm])
                    for arm in ARMS]
            rows.append({
                "turn": root["turn"],
                "chance": root["attackChance"],
                "followThrough": statistics.mean(
                    sample["followedThrough"] for sample in tails["attack-and-place"]),
                "gatewayEffect": wins[1] - wins[0],
                "planEffect": wins[2] - wins[0],
                "followThroughEffect": wins[2] - wins[1],
            })
    if seen != set(range(count)):
        raise ValueError("The report needs all scheduled worlds, including ineligible games.")
    return rows


def summary(rows):
    if not rows:
        return {"eligible": 0}
    return {
        "eligible": len(rows),
        "averageQuotedChance": statistics.mean(row["chance"] for row in rows),
        "followThroughRate": statistics.mean(row["followThrough"] for row in rows),
        **{name: interval([row[name] for row in rows])
           for name in ("gatewayEffect", "planEffect", "followThroughEffect")},
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not qualified["passed"]:
        raise ValueError("The paired play-out must retain the original qualified model.")
    stage = simulated_positions(campaign, root, qualified["config"], count, caravan_attacks=True)
    evidence = collect(stage, count)
    write(root / "result.json", {
        "worlds": count,
        "all": summary(evidence),
        "throughTurn6": summary([row for row in evidence if row["turn"] <= 6]),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=384, choices=(256, 384, 512))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
