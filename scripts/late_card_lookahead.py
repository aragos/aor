"""Compare token-funded cards and territory in the second and third epochs."""

import argparse
from collections import Counter
import json
from pathlib import Path

from commodity_rollout_campaign import simulated_positions, territory_report
from initiative_campaign import Campaign, read, write
from short_lookahead_campaign import report as lookahead_report


REMOTE = "/home/aragos/aor-worker/late-card-lookahead-20261119"
EPOCHS = (2, 3)


def coverage(stage):
    origins, eligible = Counter(), Counter()
    for path in sorted(stage.glob("root-*/worlds.jsonl")):
        with path.open() as stream:
            for line in stream:
                world = json.loads(line)
                expected = EPOCHS[(world["index"] // 4) % len(EPOCHS)]
                record = world["diagnostics"]["CANDIDATE"]["cardTerritoryRollouts"]
                if record["status"] == "paired":
                    if record["epoch"] != expected:
                        raise ValueError("A card tradeoff appeared in an unrequested epoch.")
                    eligible[expected] += 1
                elif record["requestedEpoch"] != expected:
                    raise ValueError("A missing card tradeoff must retain the requested epoch.")
                origins[expected] += 1
    return {str(epoch): {"requested": origins[epoch], "eligible": eligible[epoch]} for epoch in EPOCHS}


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if qualified["passed"] is not True:
        raise ValueError("Late card comparisons require the qualified mixed-table policy.")
    stage = simulated_positions(campaign, root, qualified["config"], count, territory=True,
                                lookahead=True, epochs=EPOCHS)
    write(root / "result.json", {"coverage": coverage(stage), "comparison": territory_report(stage),
                                 "checkpointAnalysis": lookahead_report(stage)})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=512, choices=(256, 512, 768))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
