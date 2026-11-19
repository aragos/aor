"""Test an opponent that learns bids for their expected win share instead of a fixed offset."""

import argparse
from collections import Counter
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, seed_bank, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_informed_bid_matrix import (
    BIDS, DEVELOPMENT_WORLDS, RIVAL_BIDS, STYLE, chronological_rows,
)
from initiative_opening_strategy import load_games
from initiative_series_rivals import (
    GAMES_PER_GROUP, WORLDS, group_differences, profiles, revealed_openings,
)


REMOTE = "/home/aragos/aor-worker/payoff-trained-challenger-20261119"
PAYOFF = ("expected-win", "expected-win-and-denial")


def matrix(root):
    payoffs = {}
    methods = {}
    for opposite in RIVAL_BIDS:
        folder = root/f"directions-counter-{opposite}"
        methods[str(opposite)] = sha(folder/"method.json")
        plans = {}
        for bid in BIDS:
            arm = f"bid-{bid}"
            for evidence in folder.glob(f"{arm}-0-*/worlds.jsonl"):
                output = evidence.parent
                Campaign.validate_result(output,folder/f"{output.name}.json")
            rows = chronological_rows(folder,arm,opposite,bid)
            if len(rows)!=DEVELOPMENT_WORLDS:
                raise ValueError("Every response payoff needs its full independently validated bank.")
            points = {}
            for later in (False,True):
                group = [row for row in rows if row["later"]==later]
                if len(group)<32:
                    raise ValueError("The learning foe needs observed payoffs for both capital groups.")
                points[str(int(later))] = {
                    metric:.7*statistics.mean(row[metric] for row in group)
                           + .3*statistics.mean(row[metric] for row in rows)
                    for metric in ("own","rival")
                }
            plans[str(bid)] = points
        payoffs[str(opposite)] = plans
    return payoffs, methods


def choose(prior, later, payoff, penalty):
    pooled = Counter(row["bid"] for row in prior)
    group = Counter(row["bid"] for row in prior if row["laterCapitalChoice"]==later)
    if not pooled.keys() <= set(BIDS):
        raise ValueError("Prior opening bids must belong to the payoff matrix's supported strategies.")
    weights = {bid:.25+.5*pooled[bid]+group[bid] for bid in BIDS}
    def expected(response):
        values = payoff[str(response)]
        return sum(
            weight*(values[str(bid)][str(int(later))]["rival"]
                    - penalty*values[str(bid)][str(int(later))]["own"])
            for bid,weight in weights.items()
        )
    return max(RIVAL_BIDS,key=expected)


def trained_responses(history, payoff):
    result = {}
    for mode, penalty in zip(PAYOFF,(0.,.35),strict=True):
        plans = []
        for i in range(0,len(history),GAMES_PER_GROUP):
            prior = history[i:i+GAMES_PER_GROUP]
            if len(prior)!=GAMES_PER_GROUP:
                raise ValueError("Each challenger learns from four complete public matches.")
            response = {"earlyBid":choose(prior,False,payoff,penalty),
                        "lateBid":choose(prior,True,payoff,penalty)}
            plans.extend([response]*GAMES_PER_GROUP)
        result[mode] = plans
    return result


def freeze(previous):
    payoff, methods = matrix(previous/"automata-informed-bid-matrix-20261119")
    series_path = previous/"automata-series-learning-rivals-20261119/plan.json"
    opening = previous/"automata-opening-participation-20261119"
    expanded_method = opening/"opening-participation-fresh-known-16/method.json"
    participation = read(expanded_method)
    fit_path = previous/"automata-opening-mix-fit-20261119/fit.json"
    fitted = read(fit_path)
    series = read(series_path)
    policies = {"preview":participation["baseline"],
                "expanded":participation["configs"]["earlier-capital"]}
    if policies["preview"]!=series["targetConfigurations"]["preview"]:
        raise ValueError("The challenger needs previous games from the exact reference policy.")
    history = {"preview":series["history"]["preview"],
               "expanded":revealed_openings(expanded_method.parent,"earlier-capital",
                                           expected_worlds=1664)}
    return {
        "matrixMethods":methods,"sourceHistorySha256":sha(series_path),
        "expandedOpeningMethodSha256":sha(expanded_method),
        "fixedRivalFitSha256":sha(fit_path),
        "payoff":payoff,"targets":policies,"histories":history,
        "rivals":lineup({int(k):v for k,v in fitted["plan"]["rivals"].items() if k!="1"}),
    }


def outcomes(campaign, label, target, plan):
    stage = f"fresh-payoff-challenger-{label}"
    history = plan["histories"][label]
    if len(history)!=WORLDS or set(seed_bank(f"{stage}/{STYLE}",WORLDS)) & {row["seed"] for row in history}:
        raise ValueError("A challenger must learn from its independently completed prior games.")
    priors = profiles(history)["capital-modal"]
    responses = trained_responses(history,plan["payoff"])
    arms = ("control","modal",*PAYOFF)
    campaign.stage(
        stage,dict.fromkeys(arms[1:],target),WORLDS,(STYLE,),shard=128,baseline=target,
        counter_variants=dict.fromkeys(arms,{"minimumObservations":1,"lookbackTurns":1}),
        lineup=plan["rivals"],series_priors=dict.fromkeys(arms[1:],priors),
        series_responses=responses,
    )
    results = {}
    for arm in arms:
        raw = load_games(campaign.root/stage,arm)
        positions = []
        for index in range(WORLDS):
            row = raw[index]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            group = "lateBid" if diagnostic["openingBid"]["capitalPosition"]>=2 else "earlyBid"
            proofs = diagnostic["publicTokenCounter"]["decisionProofs"]
            phase = next(item for item in proofs if item["turn"]==1 and item["phase"]=="buy-tokens")
            reveal = next(item for item in proofs if item["turn"]==1 and item["phase"]=="order-revealed")
            if reveal["prediction"]!=(priors[index][group] if arm!="control" else None):
                raise ValueError("Every opening forecast must use only the four previous public bids.")
            if arm in PAYOFF and (
                phase["reason"]!="LEARNED_OPENING_RESPONSE"
                or reveal["actualRivalBid"]!=responses[arm][index][group]
            ):
                raise ValueError("The payoff response must use the chosen legal bid before the reveal.")
            own = row["seat"]
            wins = row["roles"]["CANDIDATE"]["wins"]
            positions.append({"rival":wins[(own+1)%4],"target":wins[own],
                              "bid":reveal["actualRivalBid"]})
        results[arm] = positions
    return {
        name:{
            "rivalVersusModal":group_differences(results[name],results["modal"],"rival"),
            "rivalVersusBlind":group_differences(results[name],results["control"],"rival"),
            "targetVersusModal":group_differences(results[name],results["modal"],"target"),
            "rivalAmounts":dict(sorted(Counter(row["bid"] for row in results[name]).items())),
        }
        for name in arms[1:]
    }


def main(root, previous, resume):
    campaign=Campaign(root,remote=REMOTE,background=True,batch_size=2,resume=resume)
    campaign.validate_runtime()
    plan=freeze(previous)
    method=root/"plan.json"
    if not method.exists():
        write(method,plan)
    elif read(method)!=plan:
        raise ValueError("The challenger needs unchanged prior experience and full-game payoffs.")
    study={label:outcomes(campaign,label,target,plan) for label,target in plan["targets"].items()}
    write(root/"result.json",{"worldsPerPolicy":WORLDS,"outcomes":study})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
