"""Measure the win value retained by one-turn and three-turn rollout checkpoints."""

import argparse
import json
from pathlib import Path
import statistics

import numpy as np

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/short-lookahead-20261119"
FEATURES = ("cash", "advances", "misery", "chaos", "scoreMargin", "markers",
            "markerMargin", "handCount", "finished", "finishedWinShare")
HORIZONS = (1, 3)


def observations(stage):
    records = []
    for path in sorted(stage.glob("root-*/worlds.jsonl")):
        with path.open() as stream:
            for line in stream:
                world = json.loads(line)
                entry = world["diagnostics"]["CANDIDATE"]["cardTerritoryRollouts"]
                if entry["status"] != "paired":
                    continue
                buy, territory = entry["tails"]["buy"], entry["tails"]["territory"]
                pairs = list(zip(buy, territory, strict=True))
                if len(pairs) != 2:
                    raise ValueError("Short lookahead requires two matched futures per decision.")
                target = statistics.mean(a["terminal"]["winShare"] - b["terminal"]["winShare"]
                                         for a, b in pairs)
                features, cost = {}, {}
                tails = buy + territory
                terminal_steps = statistics.mean(tail["terminal"]["decisions"] - entry["decision"]
                                                  for tail in tails)
                for horizon in HORIZONS:
                    key = str(horizon)
                    features[horizon] = [
                        statistics.mean(float(a["checkpoints"][key][name])
                                        - float(b["checkpoints"][key][name]) for a, b in pairs)
                        for name in FEATURES
                    ]
                    cost[horizon] = statistics.mean(tail["checkpoints"][key]["decisions"] for tail in tails)
                if not 0 < cost[1] <= cost[3] <= terminal_steps:
                    raise ValueError("Checkpoint work must be a valid ordered prefix of the terminal pair.")
                # Blocks of four preserve each registered seat in every split.
                block = (world["index"] // 4) % 8
                split = "train" if block < 4 else "validation" if block < 6 else "test"
                records.append((split, features, target, cost, terminal_steps))
    return records


def fit(features, terminal):
    mean = features.mean(axis=0)
    scale = features.std(axis=0)
    constant = np.ptp(features, axis=0) == 0
    scale[constant] = 0
    normalized = np.divide(features - mean, scale, out=np.zeros_like(features), where=scale > 0)
    design = np.column_stack((np.ones(len(features)), normalized))
    penalty = np.diag([0.0] + [.08] * len(FEATURES))
    coefficients = np.linalg.solve(
        design.T @ design / len(design) + penalty,
        design.T @ terminal / len(design),
    )
    return mean, scale, coefficients


def predict(model, features):
    mean, scale, coefficients = model
    normalized = np.divide(features - mean, scale, out=np.zeros_like(features), where=scale > 0)
    return coefficients[0] + normalized @ coefficients[1:]


def measure(prediction, terminal, threshold):
    buy = prediction > threshold
    return {
        "terminalWinShareGain": interval((terminal * buy).tolist()),
        "overrideFraction": float(buy.mean()),
        "rmse": float(np.sqrt(np.mean((prediction - terminal) ** 2))),
    }


def report(stage):
    records = observations(stage)
    splits = {split: [row for row in records if row[0] == split]
              for split in ("train", "validation", "test")}
    counts = {split: len(rows) for split, rows in splits.items()}
    if min(counts.values()) < 32:
        return {"status": "insufficient-eligible-roots", "counts": counts}
    terminal = {split: np.asarray([row[2] for row in rows]) for split, rows in splits.items()}
    summaries = {}
    for horizon in HORIZONS:
        input_features = {split: np.asarray([row[1][horizon] for row in rows])
                          for split, rows in splits.items()}
        model = fit(input_features["train"], terminal["train"])
        validation = predict(model, input_features["validation"])
        threshold = max((.0, .03, .07), key=lambda cut: float(
                        (terminal["validation"] * (validation > cut)).mean()))
        test = predict(model, input_features["test"])
        raw_margin = input_features["test"][:, FEATURES.index("scoreMargin")]
        score_hint = (raw_margin > 0).astype(float)
        result = measure(test, terminal["test"], threshold)
        result["scoreMarginRuleWinShareGain"] = interval((terminal["test"] * score_hint).tolist())
        result["terminalDecisionFraction"] = sum(row[3][horizon] for row in splits["test"]) / sum(
            row[4] for row in splits["test"])
        result["validationWinShareGain"] = float((terminal["validation"] * (validation > threshold)).mean())
        result["threshold"] = threshold
        result["model"] = {key: value.tolist() for key, value in
                           zip(("mean", "scale", "coefficients"), model, strict=True)}
        summaries[str(horizon)] = result
    return {"status": "complete", "counts": counts, "features": list(FEATURES),
            "constantPredictionRmse": float(np.sqrt(np.mean((terminal["train"].mean() - terminal["test"]) ** 2))),
            "horizons": summaries}


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if qualified["passed"] is not True:
        raise ValueError("Lookahead checkpoints require the qualified mixed-table policy.")
    stage = simulated_positions(campaign, root, qualified["config"], count, territory=True, lookahead=True)
    write(root / "result.json", report(stage))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=512, choices=(256, 512, 768))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
