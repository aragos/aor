"""Measure local token responses to trained opposition and verify the relevant information sets."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/adaptive-midgame-lookahead-20261119"
KEY = "midgameBidRollouts"
ARMS = ("earlier", "larger-pool", "public-response", "sealed-oracle")


def checked_records(folder, count):
    samples, seen = [], set()
    for p in sorted(folder.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(p.parent, p.parent.with_suffix(".json"))
        for line in p.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in seen:
                raise ValueError("Each declared game gets just one chosen turn and eligible root.")
            seen.add(index)
            diagnostic = row["diagnostics"]["CANDIDATE"]
            record = diagnostic[KEY]
            turn = 5 + (index // 4) % 4
            if record["requestedTurn"] != turn:
                raise ValueError("Bids may only use the prospectively assigned middle-game turn.")
            if record["status"] != "paired":
                continue
            reports = diagnostic["publicTokenCounter"]["decisionProofs"]
            rival_reports = {r["turn"]: r["actualRivalBid"] for r in reports
                             if r["phase"] == "order-revealed"}
            if record["actualRivalBid"] != rival_reports[turn]:
                raise ValueError("The experiment needs the rival's actual sealed purchase.")
            if record.get("publicRivalForecast") != rival_reports.get(turn - 1):
                raise ValueError("The public forecast may only use the most recent rival reveal.")
            arms = record["tails"]
            ordinary = arms["ordinary"]
            current = record["originalBid"]
            changes = {}
            for arm in ARMS:
                if arm not in arms:
                    continue
                outcomes = []
                for new, old in zip(arms[arm], ordinary, strict=True):
                    after, before = new["terminal"], old["terminal"]
                    if after["continuationSeed"] != before["continuationSeed"]:
                        raise ValueError("Every counterfactual pair must share its sampled future.")
                    limit = {
                        "earlier": current - 2, "larger-pool": current + 2,
                        "public-response": min(current - 1, record.get("publicRivalForecast", 0)-1),
                        "sealed-oracle": min(current - 1, record["actualRivalBid"] - 1),
                    }
                    if new["amount"] != limit[arm]:
                        raise ValueError("Every legal intervention must use the bound it declares.")
                    outcomes.append({
                        "win": after["winShare"] - before["winShare"],
                        "box": new["orderBox"] - old["orderBox"],
                        "pool": new["poolAfter"] - old["poolAfter"],
                        "movesBeforeRival": (int(new["beforeCounteringRival"])
                                             - int(old["beforeCounteringRival"])),
                    })
                if len(outcomes) != 2:
                    raise ValueError("Each bid comparison needs both sampled continuations.")
                changes[arm] = {key: statistics.mean(r[key] for r in outcomes) for key in outcomes[0]}
            samples.append({"changes": changes, "turn": turn, "peaceful": record["peacefulMarkets"],
                            "contested": record["contestedMarkets"],
                            "countered": not ordinary[0]["beforeCounteringRival"],
                            "forecastCorrect": record.get("publicRivalForecast") == record["actualRivalBid"]})
    if seen != set(range(count)):
        raise ValueError("Reporting requires every scheduled original game.")
    return samples


def summarize(rows, arm):
    values = [row["changes"][arm] for row in rows if arm in row["changes"]]
    if len(values) < 2:
        return {"eligible": len(values)}
    return {"eligible": len(values), **{
        name: interval([row[name] for row in values])
        for name in ("win", "box", "pool", "movesBeforeRival")
    }}


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("The bid comparison requires the qualified current policy.")
    bank = simulated_positions(campaign, root, anchor["config"], count, adaptive_midgame=True)
    data = checked_records(bank, count)
    write(root / "result.json", {
        "worlds": count, "eligible": len(data),
        "forecastAccuracy": statistics.mean(row["forecastCorrect"] for row in data),
        "effects": {name: {
            "all": summarize(data, name),
            "ordinarilyCountered": summarize([row for row in data if row["countered"]], name),
            "noPeacefulMarkets": summarize([row for row in data if row["peaceful"] == 0], name),
            "atLeastOnePeacefulMarket": summarize([row for row in data if row["peaceful"] > 0], name),
        } for name in ARMS},
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--count", type=int, choices=(384, 512, 768), default=512)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
