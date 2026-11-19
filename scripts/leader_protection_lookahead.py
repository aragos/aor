"""Measure protection escrow, actual Patronage claims and matched full-game outcomes."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/leader-protection-lookahead-20261119"
KEY = "leaderProtectionRollouts"
ARMS = ("moderate", "larger")


def collect(folder, count):
    data = {name: [] for name in ARMS}
    seen = set()
    for file in sorted(folder.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(file.parent, file.parent.with_suffix(".json"))
        for line in file.read_text().splitlines():
            event = json.loads(line)
            index = event["index"]
            if index in seen:
                raise ValueError("Only the first eligible protection opportunity may enter each world.")
            seen.add(index)
            row = event["diagnostics"]["CANDIDATE"][KEY]
            if row["status"] != "paired":
                continue
            fees = row["protectionAmounts"]
            offered = row["tails"]
            if set(fees) != set(offered):
                raise ValueError("Each alternative must bind its actual allowed escrow.")
            baseline = offered["ordinary"]
            for name in ARMS:
                if name not in offered:
                    continue
                if not fees["ordinary"] < fees[name] <= row["maximumProjectedRivalCredit"]:
                    raise ValueError("The alternative must increase the authorized protection fee.")
                samples = []
                for alternative, ordinary in zip(offered[name], baseline, strict=True):
                    a, b = alternative["terminal"], ordinary["terminal"]
                    if a["continuationSeed"] != b["continuationSeed"]:
                        raise ValueError("Both fee arms must retain the same future seed.")
                    ac, bc = alternative["claimsThisTurn"], ordinary["claimsThisTurn"]
                    samples.append({
                        "winDelta": a["winShare"] - b["winShare"],
                        "claimDelta": ac - bc,
                        "ordinaryClaims": bc,
                        "directCashDelta": fees[name] * (ac - 1) - fees["ordinary"] * (bc - 1),
                    })
                if len(samples) != 2:
                    raise ValueError("Each protection alternative needs two prospective futures.")
                data[name].append({
                    "rebate": row["printedWordRebate"], "eligiblePatrons": row["eligiblePatrons"],
                    **{key: statistics.mean(sample[key] for sample in samples) for key in samples[0]},
                })
    if seen != set(range(count)):
        raise ValueError("Every scheduled original game must be accounted for.")
    return data


def report(rows):
    if len(rows) < 2:
        return {"eligible": len(rows)}
    return {"eligible": len(rows), **{
        key: interval([row[key] for row in rows]) for key in
        ("winDelta", "claimDelta", "ordinaryClaims", "directCashDelta")
    }}


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("Leader protection must use the exact qualified card and purchase policy.")
    stage = simulated_positions(campaign, root, anchor["config"], count, leader_protection=True)
    examples = collect(stage, count)
    write(root / "result.json", {
        "worlds": count,
        "effects": {name: {
            "all": report(rows),
            "ordinaryClaimObserved": report([row for row in rows if row["ordinaryClaims"] > 0]),
            "withPrintedWordRebate": report([row for row in rows if row["rebate"] > 0]),
            "severalPatrons": report([row for row in rows if row["eligiblePatrons"] > 1]),
        } for name, rows in examples.items()},
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", default=768, choices=(384, 512, 768), type=int)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
