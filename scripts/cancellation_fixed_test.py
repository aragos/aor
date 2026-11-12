"""Once-only sealed-origin evaluation of the unchanged cancellation ridge diagnostic.

The native prefix helper emits only actor-safe features/provenance and skips every
tail tree. Public predictions are persisted before this evaluator decodes returns.
There is no training, model export, threshold selection, or native policy execution.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time

import numpy as np
import cancellation_conditional_value as fixed

KIND = "cancellation-fixed-test-1"
MODEL_SHA = "a0a3ffbe55014f39bc467d53cf3b38e49c36f3f1a1e1907d2b6fd3e6bd9d5a12"
SPLIT_SHA = "ebfdd26872a597190beafe6ccc733a097b61da083c1bb5fc826cf2530a9d1b68"
SELECTION = "MAX_PREDICTED_CANCEL_STRICTLY_POSITIVE_ELSE_PASS_CANONICAL_TIES"
require, read, sha, write = fixed.require, fixed.read, fixed.sha, fixed.write


def validate_model(model):
    require(model["lambda"] == 10 and model["objective"] ==
            "weightedMeanMSE + lambda * sum(beta^2)", "The fixed original recipe changed.")
    for key in ("mean", "standardDeviation", "coefficients"):
        values = np.asarray(model[key], dtype=np.float64)
        require(values.shape == (58,) and np.isfinite(values).all(),
                "The original 58-column coefficients/scaling must be finite.")
    require(all(value >= 0 for value in model["standardDeviation"]) and
            math.isfinite(model["intercept"]), "Original scaling/intercept is invalid.")


def public_world(row, projection, catalog, model):
    """Only safe projection data enters features or choices; no terminal object is accepted."""
    require(row["index"] == projection["index"] and 224 <= row["index"] < 256
            and projection["split"] == row["split"] == "TEST"
            and projection["sourceSha256"] == row["sha256"], "Frozen TEST origin binding differs.")
    if projection["status"] == "MISSING":
        return None
    require(projection["status"] == "COLLECTED", "Unknown captured TEST status.")
    actor = projection["actorInput"]
    commands = [candidate["command"] for candidate in actor["observation"]["candidates"]]
    require("pass" in commands and 2 <= len(commands) <= 3 and len(set(commands)) == len(commands),
            "Retain every original first-action sibling including PASS.")
    incumbent = projection["provenance"]["incumbentCommand"]
    require(incumbent in commands and projection["provenance"]["exactPrefixAndIncumbentVerified"],
            "Original recorded normal incumbent binding changed.")
    features = {command: fixed.extract(actor, projection, command, catalog)
                for command in sorted(commands) if command != "pass"}
    predictions = dict(zip(features, map(float, fixed.ridge.predict(model, list(features.values())))))
    return {"index": row["index"], "split": "TEST", "sourceSha256": row["sha256"],
            "epoch": actor["view"]["currentEpoch"], "incumbent": incumbent,
            "features": features, "predictions": predictions, "selected": fixed.choose(predictions),
            "kinds": {quote["command"]: quote["adjustment"] for quote in projection["quotes"]
                      if quote["command"] != "pass"}}


def attach_labels(world, origin, projection):
    """Called only after the complete prediction file has been exclusively persisted."""
    require(origin["kind"] == "cancellation-origin-1" and origin["status"] == "COLLECTED"
            and origin["request"]["index"] == world["index"]
            and origin["actorInput"] == projection["actorInput"]
            and origin["provenance"] == projection["provenance"]
            and origin["prefix"]["canonicalSha256"] == projection["prefixSha256"],
            "Semantic returns differ from the already predicted exact original root.")
    world["labels"] = fixed.label_actions(origin)


def summarize(worlds, kind_means, missing):
    """One equal-weight sample per independent origin, never one sample per arm/tail."""
    losses = {name: [] for name in ("ridge", "zero", "kindMean")}
    contrasts = {name: {metric: [] for metric in fixed.METRICS} for name in ("pass", "incumbent")}
    retained = []
    for world in worlds:
        actions, local = [], {name: [] for name in losses}
        for command in sorted(world["features"]):
            target = fixed.mean_metric(world["labels"], command, "reward") - fixed.mean_metric(world["labels"], "pass", "reward")
            predictions = {"ridge": world["predictions"][command], "zero": 0.0,
                           "kindMean": kind_means[world["kinds"][command]]}
            for name, prediction in predictions.items():
                local[name].append((prediction - target) ** 2)
            actions.append(dict(command=command, kind=world["kinds"][command], target=target,
                features=world["features"][command].tolist(), predictions=predictions,
                pairedReturns=world["labels"][command]))
        for name, values in local.items():
            losses[name].append(float(np.mean(values)))
        differences = {}
        for baseline, metrics in contrasts.items():
            reference = "pass" if baseline == "pass" else world["incumbent"]
            differences[baseline] = {}
            for metric, values in metrics.items():
                delta = fixed.mean_metric(world["labels"], world["selected"], metric) - fixed.mean_metric(world["labels"], reference, metric)
                values.append(delta); differences[baseline][metric] = delta
        retained.append({key: world[key] for key in
            ("index", "split", "sourceSha256", "epoch", "incumbent", "selected")} |
            dict(actions=actions, passPairedReturns=world["labels"]["pass"], differences=differences))
    require(len(worlds) + len(missing) == 32 and len(worlds) >= 2,
            "Retain all prospective TEST origins; intervals need two captured roots.")
    summary = dict(mse={name: fixed.ridge.interval(values) for name, values in losses.items()},
        pairedMseDifference={name: fixed.ridge.interval(np.asarray(losses["ridge"]) - losses[name])
                             for name in ("zero", "kindMean")},
        selectionEffects={baseline: {metric: fixed.ridge.interval(values) for metric, values in metrics.items()}
                          for baseline, metrics in contrasts.items()},
        counts=dict(prospectiveOrigins=32, capturedOrigins=len(worlds), missingOrigins=len(missing),
            cancelActions=sum(len(w["features"]) for w in worlds),
            selectedCancel=sum(w["selected"] != "pass" for w in worlds),
            changedIncumbent=sum(w["selected"] != w["incumbent"] for w in worlds),
            byEpoch={str(epoch): sum(w["epoch"] == epoch for w in worlds) for epoch in (1, 2, 3)},
            selectedKind={kind: sum(w["selected"] != "pass" and w["kinds"][w["selected"]] == kind
                for w in worlds) for kind in ("SHORTAGE", "SURPLUS")}),
        missingIndices=missing, actualFits=0, newGames=0, productionActivation=False,
        limitations=["Once-only held-out-origin conditional first-action proxy, not public-student strength.",
            "Normal continuation retains old subsequent cancellation decisions.",
            "Two paired streams share hidden hands/topdeck; origin is the independent unit.",
            "Approximate normal intervals on a small fixed TEST set; no automatic arena admission.",
            "Positive Chaos difference means increased Chaos risk."])
    return summary, retained


def validate_protocol(protocol):
    require(protocol["kind"] == KIND and protocol["featureNames"] == list(fixed.FEATURE_NAMES)
            and protocol["selection"] == SELECTION, "Frozen feature/decision contract changed.")
    require(protocol["limits"] == dict(seconds=600, cpuThreads=1, attempts=1)
            and protocol["fits"] == protocol["newGames"] == 0, "Only one bounded no-fit evaluation.")
    require([row["index"] for row in protocol["origins"]] == list(range(224, 256))
            and all(row["split"] == "TEST" for row in protocol["origins"]), "Exact original TEST split required.")
    require(sha(protocol["model"]) == MODEL_SHA and sha(protocol["split"]) == SPLIT_SHA,
            "Only the unchanged original coefficients and prospective split are eligible.")
    for path, digest in protocol["inputFiles"].items():
        require(sha(path) == digest, "Frozen input changed: " + path)


def run(protocol_path, output):
    started = time.time(); protocol = read(protocol_path); validate_protocol(protocol)
    require(all(os.environ.get(name) == "1" for name in fixed.ridge.THREAD_ENV), "One CPU numerical thread required.")
    require(sys.version == protocol["pythonVersion"] and np.__version__ == protocol["numpyVersion"]
            and sha(sys.executable) == protocol["pythonSha256"], "Original numerical identity changed.")
    require(not output.exists(), "Exactly one evaluation attempt; output must be new.")
    output.mkdir(); write(output / "attempt.json", dict(startedEpoch=started, protocolSha256=sha(protocol_path)))
    model = read(protocol["model"]); validate_model(model); catalog = read(protocol["catalog"])
    projection_path = output / "public-projections.jsonl"
    command = [*protocol["projectionCommand"], protocol["prefixManifest"], str(projection_path)]
    with (output / "projection-proof.json").open("x") as receipt:
        subprocess.run(command, check=True, stdout=receipt, stderr=subprocess.PIPE, timeout=180)
    proof = read(output / "projection-proof.json")
    require(proof["passed"] and proof["origins"] == 32 and proof["tailTreesDecoded"] == 0
            and proof["newFits"] == proof["newGames"] == 0, "Prefix-only projection proof failed.")
    projections = [json.loads(line) for line in projection_path.read_text().splitlines()]
    require([row["index"] for row in projections] == list(range(224, 256)), "All original TEST prefixes required.")
    worlds, missing = [], []
    for row, projection in zip(protocol["origins"], projections):
        world = public_world(row, projection, catalog, model)
        if world is None:
            missing.append(row["index"])
        else:
            worlds.append(world)
    require(proof["captured"] == len(worlds) and proof["missing"] == len(missing),
            "Native prefix coverage and public feature coverage differ.")
    predictions = [{key: w[key] for key in
        ("index", "split", "sourceSha256", "incumbent", "predictions", "selected")} for w in worlds]
    write(output / "public-predictions.json", dict(modelSha256=MODEL_SHA, origins=predictions, missingIndices=missing))
    # Exclusive durable decision artifact precedes any semantic terminal decoding.
    decisions_sha = sha(output / "public-predictions.json")
    indexed = {world["index"]: world for world in worlds}
    for row, projection in zip(protocol["origins"], projections):
        require(sha(row["path"]) == row["sha256"], "Original source changed after prefix projection.")
        if row["index"] in indexed:
            attach_labels(indexed[row["index"]], read(row["path"]), projection)
    summary, retained = summarize(worlds, protocol["kindMeanBaseline"], missing)
    summary.update(kind=KIND, modelSha256=MODEL_SHA, protocolSha256=sha(protocol_path),
        predictionsSha256=decisions_sha, elapsedSeconds=time.time() - started)
    write(output / "test-origins.json", retained); write(output / "summary.json", summary)
    validate_protocol(protocol)
    require(sha(output / "public-predictions.json") == decisions_sha, "Predictions changed after labels.")
    write(output / "completion.json", dict(passed=True, actualFits=0, newGames=0,
        protocolSha256=sha(protocol_path), modelSha256=MODEL_SHA,
        files={p.name: sha(p) for p in sorted(output.iterdir()) if p.is_file()}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True); args = parser.parse_args()
    run(args.protocol, args.output)
