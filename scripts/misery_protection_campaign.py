"""Measure preventive grain and science choices when a corresponding misery event is outstanding."""

import argparse
from collections import Counter
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/preventive-misery-20261119"


def report(stage):
    origins, outcomes = Counter(), []
    for archive in sorted(stage.glob("root-*/worlds.jsonl")):
        with archive.open() as stream:
            for line in stream:
                world = json.loads(line)
                root = world["diagnostics"]["CANDIDATE"]["miseryProtectionRollouts"]
                origins[root["kind"]] += 1
                if root["status"] != "paired":
                    continue
                actor = f"p{world['seat'] + 1}"

                def chaos(row):
                    return next(score["total"] for score in row["scores"] if score["player"] == actor) is None

                pairs = [(a["terminal"], b["terminal"]) for a, b in
                         zip(root["tails"]["protect"], root["tails"]["ordinary"], strict=True)]
                if len(pairs) != 2:
                    raise ValueError("Every preventive root requires two matched futures.")
                outcomes.append((
                    root["kind"], root["stepsToChaos"],
                    statistics.mean(a["winShare"] - b["winShare"] for a, b in pairs),
                    statistics.mean(int(chaos(a)) - int(chaos(b)) for a, b in pairs),
                ))
    groups = {}
    for key in origins:
        eligible = [record for record in outcomes if record[0] == key]
        groups[key] = {"origins": origins[key], "eligible": len(eligible)}
        for label, rows in (("all",eligible),("withinFour", [row for row in eligible if row[1] <= 4])):
            if rows:
                groups[key][label] = {"winShare": interval([row[2] for row in rows]),
                                      "chaos": interval([row[3] for row in rows])}
    return {"terminalRollouts": len(outcomes) * 4, "groups": groups}


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if qualified["passed"] is not True:
        raise ValueError("The prevention study requires the qualified mixed-table policy.")
    stage = simulated_positions(campaign, root, qualified["config"], count, protection=True)
    write(root / "result.json", report(stage))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=512, choices=(256, 512, 768))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
