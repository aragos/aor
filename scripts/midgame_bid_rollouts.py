"""Compare paying for a larger expansion pool with buying earlier turn order."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/midgame-bid-lookahead-20261119"
ARMS = {"ordinary":0,"earlier":-2,"larger-pool":2}


def collect(folder, count):
    eligible, seen = [], set()
    for worlds in sorted(folder.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(worlds.parent,worlds.parent.with_suffix(".json"))
        for line in worlds.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in seen:
                raise ValueError("Each scheduled game gets one possible sealed midgame root.")
            seen.add(index)
            record = row["diagnostics"]["CANDIDATE"]["midgameBidRollouts"]
            turn = 5+(index//4)%4
            if record["requestedTurn"]!=turn:
                raise ValueError("Only the predeclared midgame turn can enter this sample.")
            if record["status"]!="paired":
                continue
            alternatives = record["tails"]
            if set(alternatives)!=set(ARMS) or any(len(group)!=2 for group in alternatives.values()):
                raise ValueError("A selected root requires all three bids and two paired futures.")
            seeds = [tail["terminal"]["continuationSeed"] for tail in alternatives["ordinary"]]
            summaries = {}
            for arm,tails in alternatives.items():
                if ([tail["terminal"]["continuationSeed"] for tail in tails]!=seeds
                    or any(tail["amount"]!=record["originalBid"]+ARMS[arm] for tail in tails)):
                    raise ValueError("Both action direction and independent future seeds must bind to the root.")
                summaries[arm] = {
                    "win":statistics.mean(tail["terminal"]["winShare"] for tail in tails),
                    "orderBox":statistics.mean(tail["orderBox"] for tail in tails),
                    "pool":statistics.mean(tail["poolAfter"] for tail in tails),
                    "cash":statistics.mean(tail["cashAfter"] for tail in tails),
                }
            eligible.append({"turn":turn,"arms":summaries,
                             "stock":record["availableStock"],"bid":record["originalBid"]})
    if seen!=set(range(count)):
        raise ValueError("The paired evidence must include every prospective original game.")
    return eligible


def report(rows):
    if len(rows)<2:
        return {"eligible":len(rows)}
    return {
        "eligible":len(rows),
        "effects":{
            arm:{metric:interval([row["arms"][arm][metric]-row["arms"]["ordinary"][metric]
                                  for row in rows])
                 for metric in ("win","orderBox","pool","cash")}
            for arm in ("earlier","larger-pool")
        }
    }


def main(root, previous, count, resume):
    campaign=Campaign(root,remote=REMOTE,resume=resume)
    campaign.validate_runtime()
    anchor=read(previous/"automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("Midgame lookahead must use the qualified current policy.")
    paths=simulated_positions(campaign,root,anchor["config"],count,midgame_bids=True)
    records=collect(paths,count)
    write(root/"result.json",{
        "worlds":count,"all":report(records),
        "byTurn":{str(turn):report([row for row in records if row["turn"]==turn])
                  for turn in (5,6,7,8)},
        "ableToUseTwoExtraTokens":report([row for row in records if row["stock"]>=row["bid"]+2]),
    })


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--count",type=int,default=384,choices=(384,512,768))
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.count,args.resume)
