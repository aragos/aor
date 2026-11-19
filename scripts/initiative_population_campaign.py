"""Run a bounded search and independent qualification against several opponent lineups."""
import argparse
import json
from pathlib import Path
import random
import statistics

from initiative_campaign import Campaign, read, write
from initiative_heterogeneous_campaign import compare as compare_tables, trained_counter
from initiative_league import rank


REMOTE = "/home/aragos/aor-worker/initiative-population-20261118"
SEARCH_PARAMETERS = {
    "maximumTokenLoss": (2, 3, 4, 5, 6),
    "openingSpread": (0, 1, 2, 3, 4, 5),
    "historyDecay": (.25, .5, .75, 1.),
    "bidScoreTolerance": (.08, .2, .35, .55, .8),
    "tokenPenalty": (.15, .4, .7, 1.1),
    "maximumExtraTokens": (0, 1, 3, 5, 7),
    "planWeight": (0., .03, .07, .12),
    "extraCashPenalty": (.15, .35, .65),
    "counterAnticipation": (0., .25, .55, .85, 1.),
    "respectRivalCash": (False, True),
}


def immutable(path, payload, resume):
    if resume and path.exists():
        if read(path) != payload:
            raise ValueError(f"The completed phase differs from verified evidence at {path}.")
    else:
        write(path, payload)


def freeze_plan(root, previous, generations, resume):
    path = root / "population-plan.json"
    if resume and path.exists():
        plan = read(path)
        if plan["maximumGenerations"] != generations:
            raise ValueError("Resume must use the original maximum number of generations.")
        return plan
    initiative = read(previous / "automata-initiative-20261118/result.json")
    primary = read(previous / "automata-extended-20261118/league-confirm.json")
    deep = read(previous / "automata-initiative-mirror-20261118/extra-result.json")
    response = read(previous / "automata-initiative-crossplay-20261118/response-check/summary.json")
    aggressive = next(iter(response.values()))
    opening = read(previous / "automata-initiative-mix-20261118/series-fresh-recency.json")
    prior = min(int(amount) for amount in opening["openingBidCounts"])
    counter = trained_counter(previous, {"openingBidPrior":prior, "minimumObservations":1, "lookbackTurns":1})
    known = [primary["configs"][name] for name in primary["ranking"][:2]]
    economic_file = previous / "automata-auction-economy-20261118/economy-confirm.json"
    if economic_file.exists():
        economy = read(economic_file)
        proposed = economy["ranking"][0]
        if min(row["mean"] for row in economy["outcomes"][proposed].values()) >= -.025:
            known.insert(0,economy["configs"][proposed])
    table_file = previous / "automata-heterogeneous-20261118/table-fresh.json"
    if table_file.exists():
        qualified = read(table_file)
        if qualified["passed"]:
            known.insert(0,qualified["config"])
    known.append(deep["response"]["response"]["config"])
    plan = {"maximumGenerations": generations, "preview": initiative["comparison"]["config"],
            "start": known[0], "rivals": known[:2] + [known[-1]], "counter": counter, "prior":prior,
            "aggressiveRival": (aggressive["config"] if
                                aggressive["outcomes"]["QUALIFIED"]["lower95"] > 0 else None)}
    write(path, plan)
    return plan


def proposals(parent, generation):
    candidates = {
        "cash-forecast": dict(parent, respectRivalCash=True),
        "reactive": dict(parent, counterAnticipation=.55, respectRivalCash=True),
        "reactive-economy": dict(parent, counterAnticipation=.85, maximumExtraTokens=3,
                                  planWeight=.07, respectRivalCash=True),
    }
    randomizer = random.Random(617128 + generation)
    fingerprint = lambda policy: json.dumps(policy, sort_keys=True)
    seen = {fingerprint(parent)}
    distinct = {}
    for name, candidate in candidates.items():
        signature = fingerprint(candidate)
        if signature not in seen:
            distinct[name] = candidate
            seen.add(signature)
    for index in range(36):
        if len(distinct) == 8:
            break
        mutable = randomizer.sample(list(SEARCH_PARAMETERS), k=2 + index % 3)
        candidate = dict(parent, **{name:randomizer.choice(SEARCH_PARAMETERS[name]) for name in mutable})
        candidate["openingSpread"] = min(candidate.get("openingSpread",0),candidate["maximumTokenLoss"])
        signature = fingerprint(candidate)
        if signature not in seen:
            distinct[f"mutation-{index:02d}"] = candidate
            seen.add(signature)
    return distinct


def measurement(campaign, label, candidates, worlds, incumbent, plan):
    roster = {offset: policy for offset,policy in enumerate(plan["rivals"],start=1)}
    table = compare_tables(campaign,label,candidates,worlds,incumbent,roster,plan["counter"])
    familiar = campaign.stage(f"{label}-known-opening",candidates,worlds,("REVEALED_UNDERCUT",),
                             shard=128, baseline=incumbent, opening_bid_prior=plan["prior"])
    for name,scores in table.items():
        scores["known-opening"] = familiar[name]["outcomes"]["REVEALED_UNDERCUT"]
    return table


def advances(scores):
    gains = [outcome["mean"] for outcome in scores.values()]
    return min(gains) >= -.015 and statistics.mean(gains) >= .018


def main(root, previous, generations, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    plan = freeze_plan(root,previous,generations,resume)
    incumbent = plan["start"]
    rounds_without_upgrade = 0
    for generation in range(generations):
        if campaign.remaining() < 4200 or rounds_without_upgrade >= 2:
            break
        label = f"population-{generation:02d}"
        candidates = proposals(incumbent,generation)
        screen = measurement(campaign,label,candidates,128,incumbent,plan)
        nominees = {name:candidates[name] for name in rank(screen)[:2]}
        confirmation = measurement(campaign,label+"-confirm",nominees,256,incumbent,plan)
        selected = rank(confirmation)[0]
        better = advances(confirmation[selected])
        if better:
            incumbent = candidates[selected]
            rounds_without_upgrade = 0
        else:
            rounds_without_upgrade += 1
        immutable(root/f"{label}.json", {"proposals":candidates,"screen":screen,"confirm":confirmation,
                    "selected":selected,"advanced":better,"incumbent":incumbent},resume)
    if campaign.remaining() < 3000:
        write(root/"result.json",{"status":"search-complete","candidate":incumbent})
        return
    fresh = measurement(campaign,"population-fresh",{"candidate":incumbent},768,plan["preview"],plan)
    if plan["aggressiveRival"] is not None and campaign.remaining() >= 1800:
        extra = campaign.stage("population-fresh-aggressive", {"candidate":incumbent}, 768,
                               ("INDEPENDENT_INITIATIVE",), shard=128, baseline=plan["preview"],
                               rival=plan["aggressiveRival"], rival_offsets=(1,))
        fresh["candidate"]["aggressive"] = extra["candidate"]["outcomes"]["INDEPENDENT_INITIATIVE"]
    write(root/"result.json", {"status":"fresh-complete", "candidate":incumbent,"fresh":fresh,
         "passed":all(outcome["lower95"] > 0 for outcome in fresh["candidate"].values())})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--generations",type=int,choices=range(1,7),default=4)
    parser.add_argument("--resume",action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.generations,args.resume)
