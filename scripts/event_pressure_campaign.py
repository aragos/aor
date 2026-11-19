"""Measure the causal effect of targeting a reachable score leader with the same card."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/event-pressure-20261119"
KEY = "eventTargetRollouts"


def collect(stage, count):
    paired, seen = [], set()
    for source in sorted(stage.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(source.parent, source.parent.with_suffix(".json"))
        for line in source.read_text().splitlines():
            world = json.loads(line)
            if world["index"] in seen:
                raise ValueError("A declared game may contribute only its first eligible event target.")
            seen.add(world["index"])
            root = world["diagnostics"]["CANDIDATE"][KEY]
            if root["status"] != "paired":
                continue
            if root["ordinaryTarget"] == root["target"] or not 1 <= root["behindBy"] <= 400:
                raise ValueError("The alternative event must select a different nearby score leader.")
            before, after = (root["tails"][name] for name in ("ordinary", "score-leader"))
            expected = [row["terminal"]["continuationSeed"] for row in before]
            if len(expected) != 2 or expected != [row["terminal"]["continuationSeed"] for row in after]:
                raise ValueError("Each event choice must complete both matched futures.")
            effect = statistics.mean(
                new["terminal"]["winShare"] - old["terminal"]["winShare"]
                for old, new in zip(before, after, strict=True)
            )
            paired.append({"event": root["eventId"], "gap": root["behindBy"], "delta": effect,
                           "damageDifference": root["leaderDisruption"] - root["ordinaryDisruption"]})
    if seen != set(range(count)):
        raise ValueError("Effect estimates must account for all prospectively sampled games.")
    return paired


def group(rows):
    return {
        "eligible": len(rows),
        "winDelta": interval([row["delta"] for row in rows]) if rows else None,
        "averagePublicDamageDelta": statistics.mean(row["damageDifference"] for row in rows)
        if rows else None,
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    base = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not base["passed"]:
        raise ValueError("Start from the frozen, independently evaluated reference.")
    stage = simulated_positions(campaign, root, base["config"], count, event_targets=True)
    rows = collect(stage, count)
    write(root / "result.json", {
        "worlds": count, "all": group(rows),
        "byEvent": {name: group([row for row in rows if row["event"] == name])
                    for name in ("alchemists-gold", "civil-war")},
        "within100Points": group([row for row in rows if row["gap"] <= 100]),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=512, choices=(256, 384, 512))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
