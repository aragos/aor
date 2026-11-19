"""Measure the value of establishing a future expansion base and exploiting the route."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/next-turn-bases-20261119"
KEY = "nextTurnBaseRollouts"
COMPARISONS = {
    "move-base-versus-ordinary": ("move-base", "ordinary"),
    "new-route-plan-versus-ordinary": ("move-base-and-expand", "ordinary"),
    "planned-followthrough-versus-ordinary-expansion": ("move-base-and-expand", "move-base"),
}


def collect(stage, count):
    data, seen = [], set()
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(file.parent, file.parent.with_suffix(".json"))
        for line in file.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in seen:
                raise ValueError("Only the earliest eligible root of a declared game may enter.")
            seen.add(index)
            position = row["diagnostics"]["CANDIDATE"][KEY]
            if position["status"] != "paired":
                continue
            if position["newAccessibleMarkets"] < 2 or position["initialCommitment"] not in (1, 2):
                raise ValueError("A planned base must cheaply open two potential routes.")
            arms = position["tails"]
            seeds = [sample["terminal"]["continuationSeed"] for sample in arms["ordinary"]]
            for outcomes in arms.values():
                if len(seeds) != 2 or [
                    sample["terminal"]["continuationSeed"] for sample in outcomes
                ] != seeds:
                    raise ValueError("Each route needs both of the declared matched futures.")
            means = {label: statistics.mean(row["terminal"]["winShare"] for row in outcomes)
                     for label, outcomes in arms.items()}
            data.append({
                "turn": position["turn"], "routes": position["newAccessibleMarkets"],
                "followedThrough": statistics.mean(
                    sample["followedThrough"] for sample in arms["move-base-and-expand"]),
                "changes": {label: means[a] - means[b] for label, (a, b) in COMPARISONS.items()},
            })
    if seen != set(range(count)):
        raise ValueError("Reporting needs the complete original game bank.")
    return data


def summary(rows):
    if len(rows) < 2:
        return {"eligible": len(rows)}
    return {
        "eligible": len(rows),
        "followThroughRate": statistics.mean(row["followedThrough"] for row in rows),
        "winEffects": {label: interval([row["changes"][label] for row in rows])
                       for label in COMPARISONS},
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    live = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not live["passed"]:
        raise ValueError("The route probe requires the exact matched reference policy.")
    stage = simulated_positions(campaign, root, live["config"], count, next_turn_bases=True)
    worlds = collect(stage, count)
    write(root / "result.json", {
        "worlds": count,
        "all": summary(worlds),
        "early": summary([row for row in worlds if row["turn"] <= 4]),
        "opensThreeOrMore": summary([row for row in worlds if row["routes"] >= 3]),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=384, choices=(256, 384, 512))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
