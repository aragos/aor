"""Confirm the exploratory second-chooser signal with a new frozen game bank."""

import argparse
from pathlib import Path

import numpy as np

from capital_choice_lookahead import FEATURES, collect, decision, evaluate
from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, read, sha, write


REMOTE = "/home/aragos/aor-worker/second-capital-confirmation-20261119"
POSITION = 1


def geography(row):
    scores = row["features"]
    return max(scores, key=lambda name: scores[name]["capitalOpportunity"])


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    live_path = previous / "automata-heterogeneous-20261118/table-fresh.json"
    training_path = previous / "automata-capital-choice-lookahead-20261119/result.json"
    original, learned = read(live_path), read(training_path)
    earlier = learned["geographyOnly"]["positionWinDelta"][str(POSITION)]
    if not original["passed"] or earlier["lower95"] <= 0:
        raise ValueError("The confirmation must trace to the declared exploratory capital signal.")
    coefficients = learned["weights"]
    common = (*FEATURES, *(f"firstChooser.{feature}" for feature in FEATURES))
    capitals = tuple(sorted(set(coefficients) - set(common)))
    context = learned["selected"].startswith("context-")
    names = (*capitals, *common) if context else capitals
    weights = np.array([coefficients[name] for name in names])
    frozen = {
        "capitalPosition": POSITION, "worlds": count, "liveSha256": sha(live_path),
        "exploratoryEvidenceSha256": sha(training_path), "fittedCoefficients": coefficients,
    }
    plan = root / "plan.json"
    if not plan.exists():
        write(plan, frozen)
    elif read(plan) != frozen:
        raise ValueError("The second-chooser confirmation must keep its original frozen inputs.")
    stage = simulated_positions(campaign, root, original["config"], count,
                               capital_choices=True, capital_choice_position=POSITION)
    rows = collect(stage, count)
    if any(row["seatOrder"] != POSITION for row in rows):
        raise ValueError("Only the prospectively named capital-selection position may enter the test.")
    trials = {
        "all-affordable-nearby": evaluate(rows, geography),
        "frozen-regression": evaluate(rows, lambda row: decision(row, weights, capitals, context)),
    }
    write(root / "result.json", {
        "worlds": count, "eligible": len(rows), "outcomes": trials,
        "nominees": [name for name, summary in trials.items()
                     if summary["winDelta"]["lower95"] > 0 and summary["changedChoices"] >= 30],
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=1536, choices=(768, 1024, 1536))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
