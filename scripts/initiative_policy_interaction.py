"""Test first-turn and later-turn bidding separately and together against several tables."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from initiative_policy_portfolio import frozen_plan, strategy, tournament


REMOTE = "/home/aragos/aor-worker/initiative-bid-interaction-20261119"


def plan(previous):
    portfolio = frozen_plan(previous)
    current = portfolio["control"]["initiative"]
    specialist = portfolio["policies"]["adaptive-specialist"]["initiative"]
    fit_file = previous / "automata-opening-mix-fit-20261119/fit.json"
    fitted = read(fit_file)["candidates"]
    preferences = fitted.get("capital-priority", fitted["global"])["openingMix"]
    # The qualified preview uniformly samples the legal bids 18 through 21 on turn one.
    live_first = {"bids":[18,19,20,21]}
    choices = {
        "opening":strategy(dict(current, openingMix=preferences)),
        "later":strategy(dict(specialist, openingMix=live_first)),
        "combined":strategy(dict(specialist, openingMix=preferences)),
    }
    return dict(portfolio, policies=choices, openingFitSha256=sha(fit_file))


def first_bids(stage, role):
    return {index:(row["seed"], row["diagnostics"]["CANDIDATE"]["openingBid"])
            for index,row in load_games(stage,role).items()}


def compare_components(root, tables, label="bid-interaction-screen"):
    effects = {}
    for case, rows in tables.items():
        stage = root / f"{label}-{case}"
        if first_bids(stage,"control") != first_bids(stage,"later"):
            raise ValueError("The later-turn intervention must preserve the live first bids exactly.")
        if first_bids(stage,"opening") != first_bids(stage,"combined"):
            raise ValueError("The combined intervention must preserve the independently fitted first bids.")
        pairs = list(zip(rows["control"],rows["opening"],rows["later"],rows["combined"],strict=True))
        effects[case] = {
            "opening":interval([early-base for base,early,_,_ in pairs]),
            "later":interval([late-base for base,_,late,_ in pairs]),
            "combined":interval([both-base for base,_,_,both in pairs]),
            "interaction":interval([both-early-late+base for base,early,late,both in pairs]),
        }
    return effects


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,resume=resume,background=True,batch_size=2)
    campaign.validate_runtime()
    frozen = root/"plan.json"
    if resume and frozen.is_file():
        design = read(frozen)
    else:
        design = plan(previous)
        write(frozen,design)
    choices = design["policies"]
    table = tournament(campaign,"bid-interaction-screen",choices,192,design)
    effects = compare_components(root,table)
    nominees = []
    for name in choices:
        gains = [row[name]["mean"] for row in effects.values()]
        if min(gains)>=-.025 and statistics.mean(gains)>=.02:
            nominees.append((min(gains),statistics.mean(gains),name))
    record = {"effects":effects,"nominees":[name for _,_,name in sorted(nominees,reverse=True)],
              "configurations":choices}
    screen = root/"screen.json"
    if not screen.is_file():
        write(screen,record)
    elif read(screen)!=record:
        raise ValueError("The bidding interaction screen changed after its evidence was validated.")
    if not nominees or campaign.remaining()<3600:
        write(root/"result.json",{"status":"bid-interaction-screen-complete","nominees":record["nominees"]})
        return
    best = max(nominees)[-1]
    games = tournament(campaign,"bid-interaction-fresh",{best:choices[best]},768,design)
    audit = {case:interval([a-b for a,b in zip(rows[best],rows["control"],strict=True)])
             for case,rows in games.items()}
    write(root/"result.json",{"status":"bid-interaction-fresh-complete","choice":best,
        "configuration":choices[best]["initiative"],"fresh":audit,
        "passed":all(row["lower95"]>0 for row in audit.values())})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
