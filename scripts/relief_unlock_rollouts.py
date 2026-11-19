"""Test whether unlocking and using a first misery-relief tier improves play."""

import argparse
import json
from pathlib import Path
import statistics

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/relief-unlock-20261119"
ARMS = ("pass","purchase","purchase-and-relieve")


def collect(stage):
    eligible = []
    seen = set()
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        source = file.parent.with_suffix(".json")
        Campaign.validate_result(file.parent,source)
        with file.open() as stream:
            for line in stream:
                row = json.loads(line)
                if row["index"] in seen:
                    raise ValueError("Each requested origin may supply only one relief root.")
                seen.add(row["index"])
                info = row["diagnostics"]["CANDIDATE"]["reliefUnlockRollouts"]
                if info["status"]!="paired":
                    continue
                seat = row["seat"]
                arms = info["tails"]
                if set(arms)!=set(ARMS) or any(len(items)!=2 for items in arms.values()):
                    raise ValueError("Every eligible relief root needs both paired seeds and all arms.")
                if info["ordinary"]!="pass" or info["misery"]<50 or info["availableRating"]<=0:
                    raise ValueError("The root must support the declared postponed relief opportunity.")
                seeds = [[item["terminal"]["continuationSeed"] for item in arms[arm]]
                         for arm in ARMS]
                if any(pair!=seeds[0] for pair in seeds) or len(set(seeds[0]))!=2:
                    raise ValueError("All actions must use the same two independently seeded futures.")
                results = {}
                for name in ARMS:
                    outcomes = arms[name]
                    spaces = [item["immediateReliefSpaces"] for item in outcomes]
                    if any(space < 0 for space in spaces) or (name=="pass" and any(spaces)):
                        raise ValueError("Immediate relief requires an owned advance tier.")
                    results[name] = {
                        "win":statistics.mean(item["terminal"]["winShare"] for item in outcomes),
                        "chaos":statistics.mean(item["terminal"]["scores"][seat]["misery"] is None
                                                for item in outcomes),
                        "relief":statistics.mean(spaces),
                    }
                eligible.append({"epoch":info["epoch"],"misery":info["misery"],"results":results})
    if seen != set(range(len(seen))):
        raise ValueError("The rollouts must use the complete intended origin set.")
    return eligible, len(seen)


def summarize(rows):
    if len(rows)<2:
        return {"eligible":len(rows)}
    comparisons = {}
    for first,second in (("purchase","pass"),("purchase-and-relieve","pass"),
                         ("purchase-and-relieve","purchase")):
        comparisons[f"{first}-minus-{second}"] = {
            key:interval([row["results"][first][key]-row["results"][second][key] for row in rows])
            for key in ("win","chaos","relief")
        }
    return {
        "eligible":len(rows),
        "meanReliefSpaces":{arm:statistics.mean(row["results"][arm]["relief"] for row in rows)
                            for arm in ARMS},
        "comparisons":comparisons,
    }


def main(root, previous, count, resume):
    campaign = Campaign(root,remote=REMOTE,resume=resume)
    campaign.validate_runtime()
    anchor = read(previous/"automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("Relief study requires the qualified installed reference policy.")
    stage = simulated_positions(campaign,root,anchor["config"],count,relief_unlock=True)
    rows, worlds = collect(stage)
    if worlds != count:
        raise ValueError("A completed relief experiment must validate the exact planned count.")
    summaries = {"all":summarize(rows), "misery-at-least-200":summarize(
        [row for row in rows if row["misery"]>=200]
    )}
    write(root/"result.json",{"worlds":worlds,"outcomes":summaries})


if __name__=="__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--count",type=int,default=512,choices=(384,512,768))
    parser.add_argument("--resume",action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.count,args.resume)
