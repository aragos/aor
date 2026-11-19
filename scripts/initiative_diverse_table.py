"""Measure whether heterogeneous automata hold up better against a familiar challenger."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games, with_mix
from initiative_series_rivals import GAMES_PER_GROUP, profiles


REMOTE = "/home/aragos/aor-worker/diverse-table-defense-20261119"
WORLDS = 512
STYLE = "REVEALED_UNDERCUT"
STYLES = ("preview","fitted","specialist")
ROSTERS = {
    "control": ("preview",)*3,
    "all-fitted": ("fitted",)*3,
    "all-specialist": ("specialist",)*3,
    "mixed-preview-focal": STYLES,
    "mixed-fitted-focal": STYLES[1:] + STYLES[:1],
    "mixed-specialist-focal": STYLES[2:] + STYLES[:2],
}


def freeze(previous):
    fit_path = previous/"automata-opening-mix-fit-20261119/fit.json"
    series_path = previous/"automata-series-learning-rivals-20261119/plan.json"
    portfolio_path = previous/"automata-initiative-portfolio-20261119/plan.json"
    fitted = read(fit_path)
    series = read(series_path)
    portfolio = read(portfolio_path)
    specialist = portfolio["policies"]["adaptive-specialist"]["initiative"]
    policies = {
        "preview":fitted["plan"]["baseline"],
        "fitted":fitted["candidates"]["capital-priority"],
        "specialist":with_mix(specialist, [18,19,20,21]),
    }
    histories = dict(series["history"],specialist=series["history"]["preview"])
    return {
        "policies":policies, "histories":histories,
        "openingFitSha256":sha(fit_path), "historySha256":sha(series_path),
        "specialistSelectionSha256":sha(portfolio_path),
        "challenger":"next-registered-seat-using-public-token-history",
        "rosters":{name:list(roster) for name,roster in ROSTERS.items()},
    }


def data(folder, arm, chosen, prior):
    rows = load_games(folder,arm)
    if len(rows) != chosen or set(rows) != set(range(chosen)):
        raise ValueError("Every table must contain the exact same complete game bank.")
    worlds = []
    for idx, row in sorted(rows.items()):
        seat = row["seat"]
        diagnostics = row["diagnostics"]["CANDIDATE"]
        initial = diagnostics["openingBid"]
        reveal = next(item for item in diagnostics["publicTokenCounter"]["decisionProofs"]
                      if item["phase"]=="order-revealed" and item["turn"]==1)
        predicted = 19 if prior is None else prior[idx][
            "lateBid" if initial["capitalPosition"]>=2 else "earlyBid"
        ]
        if reveal["prediction"]!=predicted or reveal["actualTargetBid"]!=initial["bid"]:
            raise ValueError("The challenger must use its declared eligible first-turn history.")
        terminal = row["roles"]["CANDIDATE"]["wins"]
        worlds.append({
            "opening":(idx,row["seed"],seat,initial["capital"],initial["capitalPosition"]),
            "openingBid":initial["bid"], "earlyPrediction":int(initial["bid"]==predicted),
            "challenger":terminal[(seat+1)%4], "target":terminal[seat],
        })
    return worlds


def paired_group_interval(values):
    if len(values) % GAMES_PER_GROUP:
        raise ValueError("One learned-opening history must govern a whole group of tests.")
    return interval([statistics.mean(values[i:i+GAMES_PER_GROUP])
                     for i in range(0,len(values),GAMES_PER_GROUP)])


def table(campaign, name, size, plan, *, learned):
    rosters = plan["rosters"]
    policies = plan["policies"]
    configurations = {arm:policies[roster[0]] for arm,roster in rosters.items()}
    alternatives = {arm:lineup({2:policies[roster[1]],3:policies[roster[2]]})
                    for arm,roster in rosters.items()}
    priors = {
        arm:profiles(plan["histories"][roster[0]])["capital-modal"][:size]
        for arm,roster in rosters.items()
    } if learned else {}
    rival = {"minimumObservations":1,"lookbackTurns":1}
    if not learned:
        rival["openingBidPrior"] = 19
    case = f"diverse-table-{name}"
    control = configurations.pop("control")
    campaign.stage(case,configurations,size,(STYLE,),shard=128,baseline=control,
                   counter_variants=dict.fromkeys(rosters,rival),
                   lineup_variants=alternatives,series_priors=priors)
    raw = {arm:data(campaign.root/case,arm,size,priors.get(arm)) for arm in rosters}
    checks = [[game["opening"] for game in worlds] for worlds in raw.values()]
    if any(identities!=checks[0] for identities in checks):
        raise ValueError("Changing table composition must preserve the original initial position.")
    if [row["openingBid"] for row in raw["all-specialist"]] != [
        row["openingBid"] for row in raw["control"]
    ]:
        raise ValueError("The specialist must preserve the preview's opening behavior exactly.")
    fixed, mixed = ("control","all-fitted","all-specialist"), tuple(
        key for key in rosters if key.startswith("mixed-")
    )
    def averaged(arms):
        return [statistics.mean(raw[arm][i]["challenger"] for arm in arms)
                for i in range(size)]
    pure, varied = averaged(fixed), averaged(mixed)
    summarize = paired_group_interval if learned else interval
    return {
        "challengerByTable":{
            arm:{"winShare":statistics.mean(row["challenger"] for row in games),
                 "exactPrediction":statistics.mean(row["earlyPrediction"] for row in games)}
            for arm,games in raw.items()
        },
        "diverseMinusSameRosterHomogeneous":summarize(
            [a-b for a,b in zip(varied,pure,strict=True)]
        ),
        "diverseMinusCurrentPreview":summarize(
            [value-row["challenger"] for value,row in zip(varied,raw["control"],strict=True)]
        ),
    }


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,background=True,batch_size=2,resume=resume)
    campaign.validate_runtime()
    design = freeze(previous)
    plan = root/"plan.json"
    if not plan.exists():
        write(plan,design)
    elif read(plan)!=design:
        raise ValueError("A diverse table comparison must keep its learned histories and rosters.")
    outcomes = {name:table(campaign,name,WORLDS,design,learned=learned)
                for name,learned in (("fixed",False),("experienced",True))}
    write(root/"result.json",{"worldsPerTable":WORLDS,"outcomes":outcomes})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
