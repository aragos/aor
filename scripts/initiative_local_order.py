"""Evaluate later bids that distinguish geographically relevant opponents."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/local-initiative-20261119"
ADAPTIVE = "REVEALED_UNDERCUT"
TABLES = ("mixed-table", "adaptive-table", "known-opening")


def evidence(folder, role, size):
    bank = load_games(folder, role)
    if set(bank) != set(range(size)):
        raise ValueError("The geography experiment requires a complete verified world bank.")
    openings = {}
    matched, exact, earlier = 0, 0, 0
    for index, world in bank.items():
        diagnostic = world["diagnostics"]["CANDIDATE"]
        openings[index] = (world["seed"], world["seat"], diagnostic["openingBid"])
        counter = diagnostic.get("publicTokenCounter", {})
        for proof in counter.get("decisionProofs", []):
            if proof["phase"] != "order-revealed" or not 5 <= proof["turn"] <= 8:
                continue
            prediction = proof["prediction"]
            if prediction is None:
                continue
            matched += 1
            exact += prediction == proof["actualTargetBid"]
            earlier += proof["rivalBeforeTarget"]
    return openings, (
        {"observations":matched, "rivalExactForecast":exact/matched,
         "rivalMovesEarlier":earlier/matched} if matched else None
    )


def table(campaign, label, case, size, plan, policies):
    style = "QUALIFIED" if case=="mixed-table" else ADAPTIVE
    roster = {int(offset): policy for offset,policy in plan["rivals"].items()}
    options = {}
    if case == "mixed-table":
        options["lineup"] = lineup(roster)
    elif case == "adaptive-table":
        options["lineup"] = lineup({k:v for k,v in roster.items() if k!=1})
        options["counter_variants"] = dict.fromkeys(("control",*policies), plan["counter"])
    else:
        options["opening_bid_prior"] = 17
    stage = f"local-order-{label}-{case}"
    results = campaign.stage(stage, policies, size, (style,), baseline=plan["baseline"],
                             shard=128, **options)
    folder = campaign.root/stage
    opening, reference = evidence(folder,"control",size)
    outputs = {}
    for name in policies:
        tested, counter = evidence(folder,name,size)
        if opening != tested:
            raise ValueError("The geography change must preserve the first turn's sealed bids.")
        outputs[name] = dict(results[name]["outcomes"][style],
                             middleTurns={"control":reference,"candidate":counter})
    return outputs


def freeze(previous):
    source = previous/"automata-opening-mix-fit-20261119/fit.json"
    anchor = read(source)["plan"]
    baseline = anchor["baseline"]
    budget = dict(baseline, maximumExtraTokens=4, planWeight=.04)
    return {
        "fitSha256":sha(source), "baseline":baseline, "rivals":anchor["rivals"],
        "counter":anchor["counter"],
        "policies":{
            "geography":dict(baseline,localOrderWeight=.65),
            "budget":budget,
            "combined":dict(budget,localOrderWeight=.65),
        },
    }


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, background=True, batch_size=2)
    campaign.validate_runtime()
    plan = freeze(previous)
    frozen = root/"plan.json"
    if not frozen.exists():
        write(frozen,plan)
    elif read(frozen)!=plan:
        raise ValueError("The geography experiment must preserve its first-turn and rival defaults.")
    results = {case:table(campaign,"screen",case,192,plan,plan["policies"]) for case in TABLES}
    nominees = []
    for name, policy in plan["policies"].items():
        effects = [result[name]["mean"] for result in results.values()]
        if min(effects)>=-.02 and statistics.mean(effects)>=.025:
            nominees.append((min(effects),statistics.mean(effects),name))
    write(root/"screen.json",{"outcomes":results,"nominees":[item[-1] for item in nominees]})
    if not nominees or campaign.remaining()<3600:
        write(root/"result.json",{"passed":False,"stage":"screen","outcomes":results})
        return
    winner = max(nominees)[-1]
    policy = plan["policies"][winner]
    fresh = {case:table(campaign,"fresh",case,768,plan,{winner:policy})[winner] for case in TABLES}
    write(root/"result.json",{"passed":all(row["lower95"]>0 for row in fresh.values()),
                              "winner":winner,"configuration":policy,"outcomes":fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
