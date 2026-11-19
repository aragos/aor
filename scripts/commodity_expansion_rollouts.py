"""Separate holding a commodity from pursuing and cashing in its territory plan."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/commodity-expansion-20261119"
ARMS = ("play","hold","hold-and-expand","hold-expand-play")
CONTRASTS = (
    ("hold","play"), ("hold-and-expand","hold"),
    ("hold-expand-play","hold-and-expand"), ("hold-expand-play","play"),
)


def evidence(folder, wanted):
    records, seen = [], set()
    for path in sorted(folder.glob("root-*/worlds.jsonl")):
        shard = path.parent
        Campaign.validate_result(shard,shard.with_suffix(".json"))
        for line in path.read_text().splitlines():
            row = json.loads(line)
            if row["index"] in seen:
                raise ValueError("A simulated origin must supply at most one commodity plan.")
            seen.add(row["index"])
            record = row["diagnostics"]["CANDIDATE"]["commodityExpansionRollouts"]
            if record["status"]!="paired":
                continue
            tails = record["tails"]
            if set(tails)!=set(ARMS) or any(len(group)!=2 for group in tails.values()):
                raise ValueError("Commodity plans need both matched futures for all four sequences.")
            if record["currentPayout"]>10 or record["projectedGrowth"]<8:
                raise ValueError("A commodity plan requires the predeclared growth and payout range.")
            points = {}
            seeds = [item["terminal"]["continuationSeed"] for item in tails["play"]]
            for arm, options in tails.items():
                if [item["terminal"]["continuationSeed"] for item in options]!=seeds:
                    raise ValueError("Every alternative needs the original paired chance streams.")
                markets = [item.get("plannedMarkets",0) for item in options]
                if any(count not in (0,1,2) for count in markets):
                    raise ValueError("A holding plan may prioritize at most two peaceful markets.")
                points[arm] = {
                    "win":statistics.mean(item["terminal"]["winShare"] for item in options),
                    "markets":statistics.mean(markets),
                    "nextTurnPlay":statistics.mean(item.get("plannedCardPlay",False)
                                                   for item in options),
                }
            records.append({"payout":record["currentPayout"],"arms":points})
    if seen!=set(range(wanted)):
        raise ValueError("The rollout study requires all original worlds in its frozen bank.")
    return records


def report(rows):
    if len(rows)<2:
        return {"eligible":len(rows)}
    return {
        "eligible":len(rows),
        "effects":{
            f"{after}-minus-{before}":interval([
                item["arms"][after]["win"]-item["arms"][before]["win"] for item in rows
            ]) for after,before in CONTRASTS
        },
        "planUse":{
            arm:{key:statistics.mean(item["arms"][arm][key] for item in rows)
                 for key in ("markets","nextTurnPlay")} for arm in ARMS[2:]
        },
    }


def main(root, previous, count, resume):
    campaign=Campaign(root,remote=REMOTE,resume=resume)
    campaign.validate_runtime()
    anchor=read(previous/"automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("Commodity sequence comparisons require the qualified preview.")
    stage=simulated_positions(campaign,root,anchor["config"],count,commodity_expansion=True)
    rows=evidence(stage,count)
    write(root/"result.json",{"worlds":count,"full":report(rows),
                             "zeroImmediatePayout":report([row for row in rows if row["payout"]==0])})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--count",type=int,default=384,choices=(384,512,768))
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.count,args.resume)
