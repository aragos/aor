"""Evaluate opponents that learn from public opening reveals in four preceding games."""

import argparse
from collections import Counter
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/series-learning-rivals-20261119"
GAMES_PER_GROUP = 4
WORLDS = 512
FITTED = ("series-modal", "capital-modal", "capital-low")
VARIANTS = ("fixed-known", *FITTED)


def revealed_openings(folder, role, expected_worlds=640):
    for evidence in sorted(folder.glob(f"{role}-0-*/worlds.jsonl")):
        output = evidence.parent
        Campaign.validate_result(output, folder / f"{output.name}.json")
    records = load_games(folder, role)
    if expected_worlds < WORLDS or set(records) != set(range(expected_worlds)):
        raise ValueError("The series must learn from a complete, independently defined comparison.")
    result = []
    for index in sorted(records)[:WORLDS]:
        row = records[index]
        evidence = row["diagnostics"]["CANDIDATE"]
        opening = evidence["openingBid"]
        counter = evidence["publicTokenCounter"]["decisionProofs"]
        revealed = [proof for proof in counter if proof["phase"]=="order-revealed" and proof["turn"]==1]
        if len(revealed)!=1 or revealed[0]["actualTargetBid"]!=opening["bid"]:
            raise ValueError("A previous-game bid must be proved by the authoritative public reveal.")
        result.append({"seed":row["seed"],"bid":opening["bid"],
                       "laterCapitalChoice":opening["capitalPosition"]>=2})
    return result


def profiles(history):
    def latest_mode(values):
        counts = Counter(values)
        maximum = max(counts.values())
        return next(bid for bid in reversed(values) if counts[bid] == maximum)

    priors = {name: [] for name in FITTED}
    for start in range(0, len(history), GAMES_PER_GROUP):
        sample = history[start:start+GAMES_PER_GROUP]
        if len(sample) != GAMES_PER_GROUP:
            raise ValueError("Every series prior needs four earlier public games.")
        all_bids = [row["bid"] for row in sample]
        early = [row["bid"] for row in sample if not row["laterCapitalChoice"]] or all_bids
        late = [row["bid"] for row in sample if row["laterCapitalChoice"]] or all_bids
        series = latest_mode(all_bids)
        for name, first, last in (
            ("series-modal",series,series),
            ("capital-modal",latest_mode(early),latest_mode(late)),
            ("capital-low",min(early),min(late)),
        ):
            priors[name].extend([{"earlyBid":first,"lateBid":last}]*GAMES_PER_GROUP)
    return priors


def outcome(row, prior):
    seat = row["seat"]
    terminal = row["roles"]["CANDIDATE"]["wins"]
    evidence = row["diagnostics"]["CANDIDATE"]
    opening = evidence["openingBid"]
    revealed = next(proof for proof in evidence["publicTokenCounter"]["decisionProofs"]
                    if proof["phase"]=="order-revealed" and proof["turn"]==1)
    actual = revealed["actualTargetBid"]
    expected = (prior["lateBid"] if opening["capitalPosition"]>=2 else prior["earlyBid"]
                ) if prior is not None else None
    if actual != opening["bid"] or expected != revealed["prediction"]:
        raise ValueError("The first-turn rival must apply exactly its eligible public forecast.")
    return {"rival":terminal[(seat+1)%4],"target":terminal[seat],
            "exactForecast":int(confirmed(revealed)),
            "earlierOrder":int(revealed["rivalBeforeTarget"])}


def confirmed(proof):
    return proof["prediction"] is not None and proof["prediction"]==proof["actualTargetBid"]


def group_differences(treatment, control, outcome_name):
    changes = [a[outcome_name]-b[outcome_name] for a,b in zip(treatment,control,strict=True)]
    return interval([statistics.mean(changes[i:i+GAMES_PER_GROUP])
                     for i in range(0,len(changes),GAMES_PER_GROUP)])


def evaluate(campaign, label, policy, history, rivals):
    priors = profiles(history)
    style = "REVEALED_UNDERCUT"
    stage = f"series-fresh-{label}"
    if set(seed_bank(f"{stage}/{style}",WORLDS)) & {row["seed"] for row in history}:
        raise ValueError("The rival cannot learn from any world used for its evaluation.")
    variants = {name:{"minimumObservations":1,"lookbackTurns":1} for name in ("control",*VARIANTS)}
    variants["fixed-known"]["openingBidPrior"] = 19
    campaign.stage(stage,dict.fromkeys(VARIANTS,policy),WORLDS,(style,),shard=128,
                   baseline=policy, counter_variants=variants,lineup=lineup(rivals),
                   series_priors=priors)
    folder = campaign.root / stage
    arms = {}
    for role in ("control",*VARIANTS):
        seen = load_games(folder,role)
        if set(seen)!=set(range(WORLDS)):
            raise ValueError("An evaluated series needs every planned independent test world.")
        arms[role] = [
            outcome(seen[index],{"earlyBid":19,"lateBid":19} if role=="fixed-known"
                    else priors[role][index] if role in priors else None)
            for index in range(WORLDS)
        ]
    return {
        name:{
            "rivalVersusBlind":group_differences(arms[name],arms["control"],"rival"),
            "rivalVersusFixed":group_differences(arms[name],arms["fixed-known"],"rival"),
            "targetVersusBlind":group_differences(arms[name],arms["control"],"target"),
            "targetVersusFixed":group_differences(arms[name],arms["fixed-known"],"target"),
            "openingPredictionAccuracy":statistics.mean(row["exactForecast"] for row in arms[name]),
            "opensEarlier":statistics.mean(row["earlierOrder"] for row in arms[name]),
        } for name in VARIANTS
    }


def main(root,previous,resume):
    campaign=Campaign(root,remote=REMOTE,resume=resume,background=True,batch_size=2)
    campaign.validate_runtime()
    source=previous/"automata-opening-mix-fit-20261119"
    adaptive=source/"opening-mixture-evaluation-adaptive-table"
    fit=read(source/"fit.json")
    fitted_role="capital-priority" if "capital-priority" in fit["candidates"] else "global"
    anchor=fit["plan"]["baseline"]
    roster={int(offset):value for offset,value in fit["plan"]["rivals"].items() if int(offset)!=1}
    histories={"preview":revealed_openings(adaptive,"control"),
               "fitted":revealed_openings(adaptive,fitted_role)}
    plan={"sourceMethodSha256":sha(adaptive/"method.json"),"history":histories,
          "targetConfigurations":{"preview":anchor,"fitted":fit["candidates"][fitted_role]}}
    method=root/"plan.json"
    if not method.is_file():
        write(method,plan)
    elif read(method)!=plan:
        raise ValueError("Past public experience or the evaluated target changed after freezing.")
    results={label:evaluate(campaign,label,policy,histories[label],roster)
             for label,policy in plan["targetConfigurations"].items()}
    write(root/"result.json",{"worldsPerPolicy":WORLDS,"groups":WORLDS//GAMES_PER_GROUP,
                             "previousGamesPerGroup":GAMES_PER_GROUP,"outcomes":results})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
