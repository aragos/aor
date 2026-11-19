"""Compare two opening policies against challengers with separate public experience."""

import argparse
from pathlib import Path
import statistics
import time

from initiative_campaign import Campaign, read, seed_bank, sha, write
from initiative_opening_strategy import load_games
from initiative_payoff_challenger import PAYOFF, STYLE, trained_responses
from initiative_series_rivals import WORLDS, group_differences, profiles


REMOTE = "/home/aragos/aor-worker/opening-repeated-qualification-20261119"
TARGETS = {"control":"preview", "expanded":"expanded"}


def qualification(campaign, case, plan):
    stage = f"opening-repeated-{case}"
    added = seed_bank(f"{stage}/{STYLE}",WORLDS)
    histories = plan["histories"]
    if any(set(added)&{row["seed"] for row in history} for history in histories.values()):
        raise ValueError("Repeated-game evaluation must use new held-out game seeds.")
    priors = {arm:profiles(histories[target])["capital-modal"] for arm,target in TARGETS.items()}
    responses = {
        arm:trained_responses(histories[target],plan["payoff"])[case]
        for arm,target in TARGETS.items()
    } if case in PAYOFF else {}
    treatment, control = (plan["targets"][key] for key in ("expanded","preview"))
    campaign.stage(
        stage,{"expanded":treatment},WORLDS,(STYLE,),shard=128,baseline=control,
        counter_variants=dict.fromkeys(TARGETS,{"minimumObservations":1,"lookbackTurns":1}),
        lineup=plan["rivals"],series_priors=priors,series_responses=responses,
    )
    metrics = {}
    for arm in TARGETS:
        worlds = load_games(campaign.root/stage,arm)
        if set(worlds)!=set(range(WORLDS)):
            raise ValueError("Every opening policy must share the complete held-out world bank.")
        values = []
        for index, row in sorted(worlds.items()):
            details = row["diagnostics"]["CANDIDATE"]
            opening = details["openingBid"]
            group = "lateBid" if opening["capitalPosition"]>=2 else "earlyBid"
            reveal = next(proof for proof in details["publicTokenCounter"]["decisionProofs"]
                          if proof["turn"]==1 and proof["phase"]=="order-revealed")
            if reveal["prediction"]!=priors[arm][index][group]:
                raise ValueError("A challenger must use the history of its own actual opponent.")
            if responses and reveal["actualRivalBid"]!=responses[arm][index][group]:
                raise ValueError("The trained challenger must exercise its frozen response bid.")
            seat = row["seat"]
            terminal = row["roles"]["CANDIDATE"]["wins"]
            values.append({
                "target":terminal[seat], "challenger":terminal[(seat+1)%4],
                "exactForecast":int(reveal["prediction"]==opening["bid"]),
                "identity":(row["seed"],seat,opening["capital"],opening["capitalPosition"]),
            })
        metrics[arm] = values
    a,b = metrics["expanded"],metrics["control"]
    if [x["identity"] for x in a]!=[y["identity"] for y in b]:
        raise ValueError("Repeated-game challengers must face the same original setup conditions.")
    return {key:group_differences(a,b,key) for key in ("target","challenger","exactForecast")}


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,background=True,batch_size=2,resume=resume)
    campaign.validate_runtime()
    opening = previous/"automata-opening-participation-20261119/result.json"
    challenger = previous/"automata-payoff-trained-challenger-20261119"
    for _ in range(250):
        if opening.is_file() and (challenger/"result.json").is_file():
            break
        if campaign.remaining()<2700:
            raise RuntimeError("Waiting for opening qualification consumed the reserved test window.")
        time.sleep(30)
    previous_opening = read(opening)
    if not previous_opening["passed"]:
        write(root/"result.json",{"status":"ordinary-qualification-did-not-pass","worlds":0})
        return
    plan = read(challenger/"plan.json")
    prior = read(challenger/"result.json")
    if previous_opening["configuration"]!=plan["targets"]["expanded"]:
        raise ValueError("The experienced challenge must score the same already qualified contender.")
    chosen = max(PAYOFF,key=lambda name:statistics.mean(
        row[name]["rivalVersusBlind"]["mean"] for row in prior["outcomes"].values()
    ))
    frozen = {"campaignPlanSha256":sha(challenger/"plan.json"),"qualifiedOpeningSha256":sha(opening),
              "challengerSelectionSha256":sha(challenger/"result.json"),"cases":["modal",chosen]}
    method = root/"plan.json"
    if not method.exists():
        write(method,frozen)
    elif read(method)!=frozen:
        raise ValueError("The challenger design must remain fixed after the first qualification.")
    results = {name:qualification(campaign,name,plan) for name in frozen["cases"]}
    write(root/"result.json",{"status":"finished","worldsPerCase":WORLDS,"outcomes":results})


if __name__=="__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
