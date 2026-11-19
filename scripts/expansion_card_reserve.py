"""Compare a cheaper market and card purchase with the ordinary full-cost expansion."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/expansion-card-reserve-lookahead-20261119"
KEY = "reserveExpansionCardRollouts"
COMPARISONS = {
    "sequence-versus-ordinary": ("reserve-and-buy", "ordinary"),
    "reserve-versus-ordinary": ("reserve-then-ordinary", "ordinary"),
    "buy-after-reserve-versus-ordinary-continuation": ("reserve-and-buy", "reserve-then-ordinary"),
    "sequence-versus-buy-first": ("reserve-and-buy", "buy-first"),
}


def collect(folder, count):
    examples, seen = [], set()
    for file in sorted(folder.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(file.parent, file.parent.with_suffix(".json"))
        for line in file.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in seen:
                raise ValueError("Only the earliest qualifying decision in a game may contribute.")
            seen.add(index)
            case = row["diagnostics"]["CANDIDATE"][KEY]
            if case["status"] != "paired":
                continue
            if not (case["ordinaryCommitment"] + case["price"] > case["pool"]
                    >= case["alternativeCommitment"] + case["price"]):
                raise ValueError("The alternative must fund both market and card within the original pool.")
            reference = case["tails"]["ordinary"]
            seeds = [record["terminal"]["continuationSeed"] for record in reference]
            stats = {}
            for name, outcomes in case["tails"].items():
                if len(seeds) != 2 or [
                    record["terminal"]["continuationSeed"] for record in outcomes
                ] != seeds:
                    raise ValueError("Each matched plan requires two identical prospective seeds.")
                stats[name] = {
                    "win": statistics.mean(out["terminal"]["winShare"] for out in outcomes),
                    "card": statistics.mean(out["cardBoughtThisTurn"] for out in outcomes),
                }
            if stats["reserve-and-buy"]["card"] != 1.0:
                raise ValueError("The protected continuation must actually buy a card this turn.")
            examples.append({"facts": case, "arms": stats})
    if seen != set(range(count)):
        raise ValueError("Reporting needs the original complete simulated game bank.")
    return examples


def summarize(rows):
    if len(rows) < 2:
        return {"eligible": len(rows)}
    return {
        "eligible": len(rows),
        "effects": {name: {
            metric: interval([row["arms"][a][metric] - row["arms"][b][metric] for row in rows])
            for metric in ("win", "card")
        } for name, (a, b) in COMPARISONS.items()},
        "cardAcquiredPerArm": {
            name: statistics.mean(row["arms"][name]["card"] for row in rows)
            for name in rows[0]["arms"]
        },
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("The sequence study requires the qualified reference policy.")
    folder = simulated_positions(campaign, root, anchor["config"], count, reserve_card=True)
    data = collect(folder, count)
    write(root / "result.json", {
        "worlds": count, "all": summarize(data),
        "shortHand": summarize([row for row in data if row["facts"]["ownHandSize"] <= 1]),
        "earlyGame": summarize([row for row in data if row["facts"]["epoch"] == 1]),
        "competesWithAttack": summarize([row for row in data if row["facts"]["ordinaryAttack"]]),
        "competesWithPeaceful": summarize([row for row in data if not row["facts"]["ordinaryAttack"]]),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, choices=(384, 512, 768), default=512)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
