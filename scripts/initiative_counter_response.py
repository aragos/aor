"""Switch to a later-bid specialist only when public behavior supports its premise."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from initiative_policy_portfolio import strategy, tournament
from initiative_series_rivals import GAMES_PER_GROUP, profiles


REMOTE = "/home/aragos/aor-worker/counter-response-20261119"
GATED = ("four-reveals","six-reveals")


def freeze(previous):
    source = previous/"automata-initiative-portfolio-20261119/plan.json"
    history_path = previous/"automata-series-learning-rivals-20261119/plan.json"
    portfolio = read(source)
    baseline = portfolio["control"]["initiative"]
    specialist = portfolio["policies"]["adaptive-specialist"]["initiative"]
    overrides = {key:specialist[key] for key in (
        "maximumTokenLoss","bidScoreTolerance","counterAnticipation",
    )}
    policies = {
        label:strategy(dict(baseline,revealedCounterResponse=dict(overrides,minimumReveals=count)))
        for count,label in zip((4,6),GATED,strict=True)
    }
    policies["always"] = strategy(dict(specialist,openingMix={"bids":[18,19,20,21]}))
    return dict(portfolio,policies=policies,portfolioSha256=sha(source),
                learnedHistory=read(history_path)["history"]["preview"],
                historySha256=sha(history_path))


def openings(folder, arm):
    return {i:(row["seed"],row["diagnostics"]["CANDIDATE"]["openingBid"])
            for i,row in load_games(folder,arm).items()}


def group_summary(table, arms, prefix, root):
    result = {}
    for case, games in table.items():
        folder = root/f"{prefix}-{case}"
        reference = openings(folder,"control")
        if any(openings(folder,arm)!=reference for arm in arms):
            raise ValueError("The public bid response may only alter later turns.")
        result[case] = {
            name:interval([new-old for new,old in zip(games[name],games["control"],strict=True)])
            for name in arms
        }
    return result


def experienced(campaign, plan, name):
    style = "REVEALED_UNDERCUT"
    stage = "counter-response-fresh-experienced"
    prior = profiles(plan["learnedHistory"])["capital-modal"]
    table = lineup({int(k):v for k,v in plan["rivals"].items() if k!="1"})
    policies = {name:plan["policies"][name]["initiative"]}
    campaign.stage(
        stage,policies,len(prior),(style,),shard=128,baseline=plan["control"]["initiative"],
        lineup=table,counter_variants=dict.fromkeys(("control",name),
            {"minimumObservations":1,"lookbackTurns":1}),series_priors=dict.fromkeys(("control",name),prior),
    )
    folder = campaign.root/stage
    if openings(folder,name)!=openings(folder,"control"):
        raise ValueError("The trained challenger must face the same opening in both arms.")
    raw = {arm:load_games(folder,arm) for arm in ("control",name)}
    scores = []
    for index in range(len(prior)):
        values = []
        for arm in ("control",name):
            row = raw[arm][index]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            group = "lateBid" if diagnostic["openingBid"]["capitalPosition"]>=2 else "earlyBid"
            revealed = next(item for item in diagnostic["publicTokenCounter"]["decisionProofs"]
                            if item["phase"]=="order-revealed" and item["turn"]==1)
            if revealed["prediction"]!=prior[index][group]:
                raise ValueError("The challenger must use the declared public previous-game forecast.")
            values.append(row["roles"]["CANDIDATE"]["wins"][row["seat"]])
        scores.append(values[1]-values[0])
    return interval([statistics.mean(scores[start:start+GAMES_PER_GROUP])
                     for start in range(0,len(scores),GAMES_PER_GROUP)])


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,background=True,batch_size=2,resume=resume)
    campaign.validate_runtime()
    plan = freeze(previous)
    path = root/"plan.json"
    if not path.exists():
        write(path,plan)
    elif read(path)!=plan:
        raise ValueError("The response study requires the frozen specialist and opponent histories.")
    prefix = "counter-response-screen"
    points = group_summary(tournament(campaign,prefix,plan["policies"],192,plan),
                           plan["policies"],prefix,root)
    nominees = []
    for name in GATED:
        effects = [case[name]["mean"] for case in points.values()]
        if min(effects)>=-.025 and statistics.mean(effects)>=.02:
            nominees.append((min(effects),statistics.mean(effects),name))
    write(root/"screen.json",{"outcomes":points,"nominees":[row[-1] for row in nominees]})
    if not nominees or campaign.remaining()<3600:
        write(root/"result.json",{"passed":False,"stage":"screen"})
        return
    name = max(nominees)[-1]
    policy = {name:plan["policies"][name]}
    prefix = "counter-response-fresh"
    points = group_summary(tournament(campaign,prefix,policy,640,plan),policy,prefix,root)
    fresh = {case:record[name] for case,record in points.items()}
    fresh["experienced"] = experienced(campaign,plan,name)
    write(root/"result.json",{
        "passed":all(value["lower95"]>0 for value in fresh.values()),
        "winner":name,"configuration":policy[name]["initiative"],"outcomes":fresh,
    })


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
