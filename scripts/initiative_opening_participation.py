"""Qualify a larger opening menu by combining fitted and ordinary bids."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, read, sha, write
from initiative_early_capital import ADAPTIVE, CANDIDATE, freeze as old_plan, run
from initiative_heterogeneous_campaign import lineup


REMOTE = "/home/aragos/aor-worker/opening-participation-20261119"
PARTICIPATION = .40
STRESS_WORLDS = 1664
PRIMARY_WORLDS = 896


def freeze(previous):
    plan = old_plan(previous)
    early = previous/"automata-early-capital-bids-20261119/result.json"
    failed = read(early)
    score = failed["outcomes"]["known-16"]["all"]
    if failed["candidate"] != plan["candidate"] or score["worlds"] != 1536:
        raise ValueError("The expanded opening mix needs the exact earlier-capital test evidence.")
    opening = dict(plan["candidate"]["openingMix"],participation=PARTICIPATION)
    plan["candidate"] = dict(plan["candidate"],openingMix=opening)
    plan["sourceTestSha256"] = sha(early)
    plan["projectedKnown16"] = {
        key: score[key]*PARTICIPATION for key in ("mean","lower95","upper95")
    }
    return plan


def test(campaign, plan, label, style, count, **options):
    return run(campaign,plan,label,style,count,stage_prefix="opening-participation-fresh",
               allow_reference_opening=True,**options)


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,resume=resume,batch_size=2)
    campaign.validate_runtime()
    plan = freeze(previous)
    method = root/"plan.json"
    if not method.exists():
        write(method,plan)
    elif read(method)!=plan:
        raise ValueError("This opening mixture must keep its prospectively fixed action probability.")
    scores = {}
    for rival in (16,20):
        case = f"known-{rival}"
        scores[case] = test(campaign,plan,case,ADAPTIVE,STRESS_WORLDS,opening_bid_prior=rival+1)
        if scores[case]["all"]["lower95"] < -.025:
            write(root/"result.json",{"passed":False,"configuration":plan["candidate"],
                                      "outcomes":scores})
            return
    roster = {int(k):v for k,v in plan["rivals"].items()}
    counter = dict.fromkeys(("control",CANDIDATE),plan["trainedCounter"])
    primary = (
        ("mixed-table","QUALIFIED",{"lineup":lineup(roster)}),
        ("adaptive-table",ADAPTIVE,{"lineup":lineup({k:v for k,v in roster.items() if k!=1}),
                                    "counter_variants":counter}),
        ("familiar",ADAPTIVE,{"opening_bid_prior":19}),
    )
    for name,style,options in primary:
        scores[name] = test(campaign,plan,name,style,PRIMARY_WORLDS,**options)
    passed = all(scores[name]["all"]["lower95"] > 0 for name,_,_ in primary)
    write(root/"result.json",{"passed":passed,"configuration":plan["candidate"],"outcomes":scores})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
