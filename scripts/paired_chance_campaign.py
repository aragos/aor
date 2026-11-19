"""Measure whether aligning authority events reduces paired action variance."""

import argparse
import json
from pathlib import Path

import numpy as np

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/paired-chance-20261119"
MODES = {"sequential": "tails", "aligned": "alignedChanceTails"}


def observations(stage):
    results = []
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        with file.open() as stream:
            for line in stream:
                row = json.loads(line)["diagnostics"]["CANDIDATE"]["cardTerritoryRollouts"]
                if row["status"] != "paired":
                    continue
                modes = {}
                for mode, field in MODES.items():
                    tails = row[field]
                    buys = [trial["terminal"]["winShare"] for trial in tails["buy"]]
                    markets = [trial["terminal"]["winShare"] for trial in tails["territory"]]
                    if len(buys) != 2 or len(markets) != 2:
                        raise ValueError("Both chance modes require exactly two futures per action.")
                    modes[mode] = (buys, markets)
                results.append((row["epoch"], modes))
    return results


def contrast(rows):
    descriptions = {}
    noise = {}
    means = {}
    for mode in MODES:
        actions = np.asarray([world[mode] for _, world in rows], dtype=np.float64)
        gaps = actions[:, 0, :] - actions[:, 1, :]
        means[mode] = gaps.mean(axis=1)
        noise[mode] = .5 * np.square(gaps[:, 0] - gaps[:, 1])
        descriptions[mode] = {
            "winShareDelta": interval(means[mode].tolist()),
            "buyWinShare": float(actions[:, 0, :].mean()),
            "territoryWinShare": float(actions[:, 1, :].mean()),
            "withinRootVariance": float(noise[mode].mean()),
            "acrossRootVariance": float(means[mode].var(ddof=1)),
        }
    original = noise["sequential"].mean()
    ratio = None
    bounds = None
    if original > 0:
        ratio = float(noise["aligned"].mean() / original)
        random = np.random.default_rng(611194027)
        pair = random.integers(0, len(rows), size=(1600, len(rows)))
        same_world = noise["sequential"][pair].mean(axis=1)
        valid = same_world > 0
        if valid.any():
            paired_ratios = noise["aligned"][pair].mean(axis=1)[valid] / same_world[valid]
            bounds = np.quantile(paired_ratios, [.025, .975]).tolist()
    return {
        "pairedWorlds":len(rows), "modes":descriptions, "withinRootVarianceRatio":ratio,
        "varianceRatioBootstrap95":bounds,
        "pairedMeanDifference": interval((means["aligned"]-means["sequential"]).tolist()),
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if anchor["passed"] is not True:
        raise ValueError("The paired chance comparison requires the qualified mixed-table policy.")
    stage = simulated_positions(campaign, root, anchor["config"], count, territory=True,
                                epochs=(2,3), chance_trial=True)
    records = observations(stage)
    results = {}
    for label, selected in (("all", records), ("epoch-2", [row for row in records if row[0]==2]),
                            ("epoch-3", [row for row in records if row[0]==3])):
        if len(selected) >= 2:
            results[label] = contrast(selected)
    write(root / "result.json", {"origins":count, "eligible":len(records),
                                 "terminalRollouts":len(records)*8, "comparisons":results})


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--count",type=int,default=256,choices=(128,256,384))
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.count,args.resume)
