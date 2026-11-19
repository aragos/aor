"""Test a stronger hold preference when current commodity income is low and growth is feasible."""

import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write


REMOTE = "/home/aragos/aor-worker/commodity-retention-screen-20261119"


def setting(weight, minimum, payoff):
    return {"kind": "affordable-commodity-retention-1", "futureWeight": weight,
            "minimumGrowth": minimum, "currentPayoutLimit": payoff}


def digests(stage, name):
    evidence = {}
    for path in stage.glob(f"{name}-0-*/worlds.jsonl"):
        with path.open() as stream:
            for line in stream:
                world = json.loads(line)
                index = world["index"]
                if index in evidence:
                    raise ValueError("Commodity comparisons require one result for each original world.")
                evidence[index] = (world["seed"],
                    world["diagnostics"]["CANDIDATE"]["chosenPolicyDecisionsSha256"])
    return evidence


def screen(campaign, label, baseline, timing, worlds):
    choices = {name: {} for name in timing}
    for key, style, opening in (("ordinary","QUALIFIED",None),("prepared","REVEALED_UNDERCUT",19)):
        stage = f"{label}-{key}"
        evaluation = campaign.stage(
            stage, dict.fromkeys(timing, baseline), worlds, (style,), shard=96,
            baseline=baseline, card_timing=timing, opening_bid_prior=opening,
        )
        baseline_games = digests(campaign.root / stage, "control")
        for name, result in evaluation.items():
            observed = digests(campaign.root / stage, name)
            if observed.keys() != baseline_games.keys() or any(
                baseline_games[index][0] != values[0] for index, values in observed.items()
            ):
                raise ValueError("The commodity policies require complete common world banks.")
            affected = sum(baseline_games[index][1] != values[1] for index, values in observed.items())
            choices[name][key] = dict(result["outcomes"][style], changedWorlds=affected)
    return choices


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if qualified["passed"] is not True:
        raise ValueError("The commodity hold screen requires the qualified mixed-table policy.")
    baseline = qualified["config"]
    approaches = {"zero-payout":setting(.5,8,0), "large-growth":setting(.5,16,0),
                  "small-payout":setting(.25,12,5)}
    outcomes = screen(campaign, "hold-screen", baseline, approaches, 256)
    finalists = [
        name for name, rows in outcomes.items()
        if min(row["mean"] for row in rows.values()) >= 0
        and statistics.mean(row["mean"] for row in rows.values()) >= .02
        and min(row["changedWorlds"] for row in rows.values()) >= 12
    ]
    report = {"outcomes":outcomes,"nominees":finalists,"approaches":approaches}
    first = root / "screen.json"
    if not first.is_file():
        write(first,report)
    elif read(first) != report:
        raise ValueError("The commodity screening results changed after recording.")
    if not finalists or campaign.remaining() < 2400:
        write(root / "result.json",{"status":"hold-screen-complete","nominees":finalists})
        return
    finalist = max(finalists,key=lambda name: min(row["mean"] for row in outcomes[name].values()))
    fresh = screen(campaign,"hold-fresh",baseline,{finalist:approaches[finalist]},640)
    write(root / "result.json",{"status":"hold-fresh-complete","choice":finalist,"fresh":fresh,
        "passed":all(row["lower95"]>0 for row in fresh[finalist].values())})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
