"""Qualify the fitted opening only where choosing a capital early supports it."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/early-capital-bids-20261119"
CANDIDATE = "earlier-capital"
ADAPTIVE = "REVEALED_UNDERCUT"
PRIMARY_WORLDS = 768
STRESS_WORLDS = 1536


def behavior(folder, label, size, bids=None):
    for name in ("control", label):
        for evidence in folder.glob(f"{name}-0-*/worlds.jsonl"):
            output = evidence.parent
            Campaign.validate_result(output, folder/f"{output.name}.json")
    control, tested = (load_games(folder, name) for name in ("control", label))
    if set(control) != set(tested) or set(control) != set(range(size)):
        raise ValueError("The compared capital groups require the complete paired world bank.")
    groups = {"earlier": [], "later": []}
    for index, row in sorted(control.items()):
        candidate = tested[index]
        seat = row["seat"]
        if (candidate["seed"], candidate["seat"]) != (row["seed"], seat):
            raise ValueError("The earlier-capital study must retain the same seat and setup.")
        reference = row["diagnostics"]["CANDIDATE"]
        choice = candidate["diagnostics"]["CANDIDATE"]
        opening = choice["openingBid"]
        fields = ("capitalPosition", "capital", "cash")
        if [opening[key] for key in fields] != [reference["openingBid"][key] for key in fields]:
            raise ValueError("A first-turn mix cannot change capital choice or initial cash.")
        later = opening["capitalPosition"] >= 2
        if bids is not None:
            if later and (row["roles"] != candidate["roles"]
                          or opening != reference["openingBid"]
                          or choice["chosenPolicyDecisionsSha256"] !=
                          reference["chosenPolicyDecisionsSha256"]):
                raise ValueError("Later capital choices must retain every reference decision.")
            if not later and opening["bid"] not in bids:
                raise ValueError("Earlier capital choices must apply the qualified bid mix.")
        a = candidate["roles"]["CANDIDATE"]["wins"][seat]
        b = row["roles"]["CANDIDATE"]["wins"][seat]
        groups["later" if later else "earlier"].append(a-b)
    return {group:interval(changes) for group,changes in groups.items()}


def freeze(previous):
    root = previous/"automata-opening-mix-fit-20261119"
    fit = read(root/"fit.json")
    sources = {label:root/f"opening-mixture-evaluation-{label}"
               for label in ("mixed-table", "adaptive-table", "familiar")}
    sources["known-16"] = root/"opening-mixture-stress-17"
    known_roles = {label:behavior(folder,"capital-priority",512 if label=="known-16" else 640)
                   for label,folder in sources.items()}
    bids = fit["candidates"]["capital-priority"]["openingMix"]["bids"]
    reference = fit["plan"]["baseline"]
    candidate = dict(reference, openingMix={
        "bids": bids, "retainOrdinaryForLaterCapital": True,
    })
    return {
        "fitSha256":sha(root/"fit.json"), "reference":reference, "candidate":candidate,
        "developmentGroupEffects":known_roles,
        "developmentMethods":{label:sha(folder/"method.json") for label,folder in sources.items()},
        "rivals":fit["plan"]["rivals"], "trainedCounter":fit["plan"]["counter"],
    }


def run(campaign, plan, label, style, size, **options):
    stage = f"early-capital-fresh-{label}"
    result = campaign.stage(stage,{CANDIDATE:plan["candidate"]},size,(style,),shard=128,
                            baseline=plan["reference"],**options)
    return {
        "all":result[CANDIDATE]["outcomes"][style],
        "capitalGroups":behavior(campaign.root/stage,CANDIDATE,size,
                                 plan["candidate"]["openingMix"]["bids"]),
    }


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, batch_size=2)
    campaign.validate_runtime()
    plan = freeze(previous)
    method = root/"plan.json"
    if not method.exists():
        write(method,plan)
    elif read(method) != plan:
        raise ValueError("The locked early-capital decisions must preserve their supporting evidence.")
    results = {}
    for actual in (16,20):
        label = f"known-{actual}"
        results[label] = run(campaign,plan,label,ADAPTIVE,STRESS_WORLDS,opening_bid_prior=actual+1)
        if results[label]["all"]["lower95"] < -.025:
            write(root/"result.json",{"passed":False,"candidate":plan["candidate"],"outcomes":results})
            return
    roster = {int(offset):policy for offset,policy in plan["rivals"].items()}
    training = dict.fromkeys(("control",CANDIDATE),plan["trainedCounter"])
    primary = (
        ("mixed-table","QUALIFIED",{"lineup":lineup(roster)}),
        ("adaptive-table",ADAPTIVE,{"lineup":lineup({k:v for k,v in roster.items() if k!=1}),
                                    "counter_variants":training}),
        ("familiar",ADAPTIVE,{"opening_bid_prior":19}),
    )
    for label,style,options in primary:
        results[label] = run(campaign,plan,label,style,PRIMARY_WORLDS,**options)
    passed = all(results[label]["all"]["lower95"] > 0 for label,_,_ in primary)
    write(root/"result.json",{"passed":passed,"candidate":plan["candidate"],"outcomes":results})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
