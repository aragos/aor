"""Measure the price of choosing a capital early under the current bidding policy."""
import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write
from initiative_heterogeneous_campaign import trained_counter
from initiative_league import rank


REMOTE = "/home/aragos/aor-worker/capital-auction-20261118"


def observations(stage, name):
    rows = []
    for file in stage.glob(f"{name}-0-*/worlds.jsonl"):
        with file.open() as stream:
            rows.extend(json.loads(line)["diagnostics"]["CANDIDATE"]["capitalAuction"] for line in stream)
    return {key:statistics.mean(row[key] for row in rows)
            for key in ("paid","choicePosition","cashBeforeFirstBid")}


def compare(campaign, label, variants, count, baseline, counter):
    outcomes = {name:{} for name in variants}
    bids = {name:{"kind":"capital-auction-study-1","bid":bid}
            for name,bid in {"control":None,**variants}.items()}
    for stage, style, options in (("ordinary","QUALIFIED",None),
                                  ("familiar","REVEALED_UNDERCUT",{name:counter for name in bids})):
        stage_label = f"{label}-{stage}"
        rows = campaign.stage(stage_label, dict.fromkeys(variants,baseline), count,(style,),
                              shard=64,baseline=baseline,capital_variants=bids,counter_variants=options)
        baseline_behavior = observations(campaign.root/stage_label,"control")
        for name, summary in rows.items():
            outcomes[name][stage] = dict(summary["outcomes"][style], setup={
                "control":baseline_behavior,"candidate":observations(campaign.root/stage_label,name),
            })
    return outcomes


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,resume=resume)
    campaign.validate_runtime()
    league = read(previous/"automata-extended-20261118/league-confirm.json")
    baseline = league["configs"][league["ranking"][0]]
    counter = trained_counter(previous,{"openingBidPrior":19,"minimumObservations":1,"lookbackTurns":1})
    plans = {f"bid-{value}":value for value in (0,1,4,7,10)}
    screen = compare(campaign,"capital-screen",plans,128,baseline,counter)
    nominee = rank(screen)[0]
    gains = [row["mean"] for row in screen[nominee].values()]
    eligible = min(gains) >= -.02 and statistics.mean(gains) >= .025
    payload = {"outcomes":screen,"bids":plans,"nominee":nominee,"eligible":eligible}
    file = root/"screen-result.json"
    if resume and file.exists():
        if read(file)!=payload:
            raise ValueError("The capital screen differs from its verified shards.")
    else:
        write(file,payload)
    if eligible and campaign.remaining()>=2400:
        fresh = compare(campaign,"capital-fresh",{nominee:plans[nominee]},384,baseline,counter)
        write(root/"result.json",{"outcomes":fresh,"bid":plans[nominee]})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
