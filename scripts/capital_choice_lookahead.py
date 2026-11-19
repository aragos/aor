"""Train a small initial-capital scorer from matched full-game futures."""

import argparse
from itertools import combinations
import json
import math
from pathlib import Path
import statistics

import numpy as np

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/capital-choice-lookahead-20261119"
KEY = "capitalChoiceRollouts"
FEATURES = ("commodity", "reach", "capitalOpportunity", "initialCommodity",
            "capitalCommodityPotential", "capitalLeaderOpportunity")


def collect(stage, count):
    eligible, seen = [], set()
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        Campaign.validate_result(file.parent, file.parent.with_suffix(".json"))
        for line in file.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in seen:
                raise ValueError("Capital study needs one original game per declared index.")
            seen.add(index)
            record = row["diagnostics"]["CANDIDATE"][KEY]
            if record["status"] != "paired":
                continue
            ordinary = record["ordinary"].removeprefix("choose-capital ")
            options, arms = record["options"], record["tails"]
            if len(options) != 4 - record["capitalPosition"] or set(options) != set(arms):
                raise ValueError("The fork must cover every legal capital for this auction position.")
            seeds = [out["terminal"]["continuationSeed"] for out in arms[ordinary]]
            results = {}
            for name, tails in arms.items():
                terminal = [out["terminal"] for out in tails]
                if len(seeds) != 2 or [out["continuationSeed"] for out in terminal] != seeds:
                    raise ValueError("Each alternative must share both original future chance streams.")
                if set(options[name]) != set(FEATURES):
                    raise ValueError("Every option requires the same ordinary own-seat features.")
                results[name] = {field: statistics.mean(out[field] for out in terminal)
                                 for field in ("winShare", "rankReward")}
            eligible.append({
                "index": index, "seatOrder": record["capitalPosition"], "ordinary": ordinary,
                "features": options, "results": results,
            })
    if seen != set(range(count)):
        raise ValueError("Cannot evaluate capital selection without every scheduled origin.")
    return eligible


def vector(row, choice, capitals, context):
    source = row["features"][choice]
    indicators = [float(choice == capital) for capital in capitals]
    if not context:
        return np.array(indicators)
    ordered = [source[key] for key in FEATURES]
    earlier = float(row["seatOrder"] == 0)
    return np.array(indicators + ordered + [value * earlier for value in ordered])


def outcome(row, option, objective=False):
    observation = row["results"][option]
    win = observation["winShare"]
    return .8 * win + .2 * observation["rankReward"] if objective else win


def fit(training, capitals, regularization, context):
    features, labels = [], []
    for row in training:
        pairs = list(combinations(row["features"], 2))
        weight = 1 / math.sqrt(len(pairs))
        for a, b in pairs:
            features.append(weight * (vector(row, a, capitals, context)
                                      - vector(row, b, capitals, context)))
            labels.append(weight * (outcome(row, a, True) - outcome(row, b, True)))
    x = np.stack(features)
    scale = np.maximum(np.sqrt(np.mean(np.square(x), axis=0)), .05)
    x = x / scale
    coefficients = np.linalg.solve(x.T @ x + regularization * np.eye(x.shape[1]), x.T @ labels)
    return coefficients / scale


def decision(row, weights, capitals, context):
    scores = {name: float(vector(row, name, capitals, context) @ weights)
              for name in row["features"]}
    choice = max(scores, key=scores.__getitem__)
    original = row["ordinary"]
    return choice if scores[choice] > scores[original] + .025 else original


def evaluate(rows, choose):
    picked = [(row, choice) for row in rows for choice in (choose(row),)]
    samples = [outcome(row, choice) - outcome(row, row["ordinary"]) for row, choice in picked]
    return {
        "winDelta": interval(samples),
        "changedChoices": sum(name != row["ordinary"] for row, name in picked),
        "positionWinDelta": {str(pos): interval([
            delta for (row, _), delta in zip(picked, samples, strict=True) if row["seatOrder"] == pos
        ]) for pos in range(3) if sum(row["seatOrder"] == pos for row, _ in picked) > 1},
    }


def analyze(data):
    groups = [[], [], []]
    for row in data:
        stratum = (row["index"] // 4) % 5
        split = 0 if stratum < 3 else stratum - 2
        groups[split].append(row)
    training, tune, test = groups
    if min(map(len, groups)) < 30:
        raise ValueError("Each disjoint fit, tuning and test segment needs at least 30 positions.")
    capitals = tuple(sorted({city for row in training for city in row["features"]}))
    if any(city not in capitals for row in tune + test for city in row["features"]):
        raise ValueError("Holdout capital labels must be represented in training.")
    candidates, scores = {}, {}
    for context in (False, True):
        for penalty in (16, 64, 256):
            weights = fit(training, capitals, penalty, context)
            name = ("context-" if context else "city-") + str(penalty)
            candidates[name] = (weights, context)
            summary = evaluate(tune, lambda row: decision(row, weights, capitals, context))
            scores[name] = summary
    selected = max(scores, key=lambda name: scores[name]["winDelta"]["lower95"])
    weights, context = candidates[selected]
    fresh = evaluate(test, lambda row: decision(row, weights, capitals, context))
    ordinary = evaluate(test, lambda row: row["ordinary"])
    geography = evaluate(test, lambda row: max(
        row["features"], key=lambda name: row["features"][name]["capitalOpportunity"],
    ))
    names = list(capitals)
    if context:
        names += [*FEATURES, *(f"firstChooser.{feature}" for feature in FEATURES)]
    return {
        "positionsPerSplit": dict(zip(("fit", "tune", "test"), map(len, groups), strict=True)),
        "selected": selected, "weights": dict(zip(names, map(float, weights), strict=True)),
        "tuning": scores, "heldout": fresh, "ordinary": ordinary, "geographyOnly": geography,
        "nominateWholeGame": fresh["winDelta"]["lower95"] > 0 and fresh["changedChoices"] >= 12,
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if not anchor["passed"]:
        raise ValueError("The capital choices must use the exact qualified game continuation.")
    stage = simulated_positions(campaign, root, anchor["config"], count, capital_choices=True)
    positions = collect(stage, count)
    report = analyze(positions)
    write(root / "result.json", {"worlds": count, "eligible": len(positions), **report})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--count", default=512, choices=(384, 512, 768), type=int)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
