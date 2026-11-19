"""Compare buying the last normal card against the ordinary Expansion plan."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/ending-trigger-20261119"
KEY = "triggerEndingRollouts"


def collect(stage, count):
    game_ids, pairs = set(), []
    for source in sorted(stage.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(source.parent, source.parent.with_suffix(".json"))
        for line in source.read_text().splitlines():
            row = json.loads(line)
            if row["index"] in game_ids:
                raise ValueError("A game contributes only the earliest qualifying choice.")
            game_ids.add(row["index"])
            root = row["diagnostics"]["CANDIDATE"][KEY]
            if root["status"] != "paired":
                continue
            before, after = (root["tails"][label] for label in ("ordinary", "take-final-card"))
            matching = [[point["terminal"]["continuationSeed"] for point in arm]
                        for arm in (before, after)]
            if (len(matching[0]) != 2 or matching[0] != matching[1] or
                    any(not sample["endingAfterRoot"] for sample in after) or
                    root["ordinary"] == "buy-expansion-card" or root["epoch"] != 3):
                raise ValueError("Run both identical futures with an actual purchased ending trigger.")
            choices = [b["terminal"]["winShare"] - a["terminal"]["winShare"]
                       for a, b in zip(before, after, strict=True)]
            pairs.append({"margin": root["scoreMargin"], "cost": root["cardCost"],
                          "ordinaryAlsoEnded": statistics.mean(sample["endingAfterRoot"] for sample in before),
                          "winDelta": statistics.mean(choices)})
    if game_ids != set(range(count)):
        raise ValueError("The endgame sample must account for every declared game.")
    return pairs


def report(rows):
    return {"eligible": len(rows),
            "ordinaryEndedImmediately": statistics.mean(row["ordinaryAlsoEnded"] for row in rows)
            if rows else None,
            "winDelta": interval([row["winDelta"] for row in rows]) if rows else None}


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    reference = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not reference["passed"]:
        raise ValueError("The test requires the already independently qualified mixed-table reference.")
    stage = simulated_positions(campaign, root, reference["config"], count, trigger_ending=True)
    rows = collect(stage, count)
    write(root / "result.json", {
        "worlds": count, "all": report(rows),
        "publicLeader": report([row for row in rows if row["margin"] > 0]),
        "closeBehind": report([row for row in rows if -100 <= row["margin"] <= 0]),
        "firstTokenPrice": report([row for row in rows if row["cost"] == 3]),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=768, choices=(384, 512, 768))
    parser.add_argument("--resume", action="store_true")
    spec = parser.parse_args()
    main(spec.root.resolve(), spec.previous.resolve(), spec.count, spec.resume)
