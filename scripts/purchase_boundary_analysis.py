"""Frozen retrospective leaf comparison; never generates games, fits or chooses root actions.

Selection sees only one declared replicate half. Evaluation uses the other half's
terminal rewards. The same source game contributes one aggregate observation.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import statistics
import sys


ROOTS = 32
REPLICATES = 16
MARGIN = 0.02
CRITICAL = 2.131449545559323
IDENTITY = ("rootIndex", "world", "action", "replicate", "sourceTailSha256")
FEATURE_KEYS = set(IDENTITY) | {
    "kind", "encoderVersion", "boundaryTurn", "boundaryDecision", "stateFeatures"
}
LABEL_KEYS = set(IDENTITY) | {"reward", "winShare", "rankReward"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def pinned(path, sha256):
    require(digest(path) == sha256, f"Frozen input changed: {path}")
    return Path(path)


def write(path, value):
    with Path(path).open("x") as output:
        json.dump(value, output, indent=2, sort_keys=True, allow_nan=False)
        output.write("\n")


def key(row):
    return row["rootIndex"], row["action"], row["replicate"]


def feature_vector(row, names):
    """Only original actor state features enter the network, in its exact dictionary order."""
    require(set(row) == FEATURE_KEYS, "Feature rows must contain no labels or action features.")
    require(row["encoderVersion"] == "aor-purchase-observation-1", "Encoder changed.")
    if row["kind"] == "terminal":
        require(row["stateFeatures"] is None, "Terminal boundaries cannot fabricate features.")
        return None
    require(row["kind"] == "purchase", "Unknown boundary kind.")
    require(row["boundaryDecision"] in ("purchase-shipping", "purchase-advances"),
            "Boundary must be a retained purchase decision.")
    values = row["stateFeatures"]
    require(set(values) <= set(names), "Feature dictionary changed.")
    require(all(isinstance(v, (int, float)) and not isinstance(v, bool)
                and math.isfinite(v) and -16 <= v <= 16 for v in values.values()),
            "Features must preserve finite original encoder units in [-16,16].")
    return [values.get(name, 0.0) for name in names]


def load_rows(directory, complete, names):
    inputs = pinned(directory / "features.jsonl.gz", complete["featuresSha256"])
    outcomes = pinned(directory / "labels.jsonl.gz", complete["labelsSha256"])
    with gzip.open(inputs, "rt") as source:
        features = [json.loads(line) for line in source]
    with gzip.open(outcomes, "rt") as source:
        labels = [json.loads(line) for line in source]
    expected = {(root, action, rep) for root in range(ROOTS)
                for action in range(2) for rep in range(REPLICATES)}
    require(len(features) == len(labels) == 1024, "All frozen tails must be retained.")
    require({key(row) for row in features} == expected, "Feature identity coverage changed.")
    require({key(row) for row in labels} == expected, "Label identity coverage changed.")
    label_map = {key(row): row for row in labels}
    for row in features:
        label = label_map[key(row)]
        require(set(label) == LABEL_KEYS, "Label schema changed.")
        require(all(row[name] == label[name] for name in IDENTITY), "Source identities differ.")
        require(all(math.isfinite(label[name]) and 0 <= label[name] <= 1
                    for name in ("reward", "winShare", "rankReward")), "Invalid outcome label.")
        feature_vector(row, names)
    return features, label_map


def summarize(values):
    """One paired value per origin; t15 is the prospectively frozen conservative critical."""
    mean = statistics.mean(values)
    radius = CRITICAL * statistics.stdev(values) / math.sqrt(len(values))
    return {"origins": len(values), "mean": mean, "approximate95": [mean - radius, mean + radius]}


def choose(selection_values, selection):
    """The interface deliberately excludes evaluation predictions and terminal labels."""
    require(set(selection_values) == {(root, action, rep) for root in range(ROOTS)
                                      for action in range(2) for rep in selection},
            "Selection must receive exactly its predeclared half.")
    gaps = {
        root: statistics.mean(selection_values[root, 1, rep] - selection_values[root, 0, rep]
                              for rep in selection)
        for root in range(ROOTS)
    }
    return {root: int(gap > MARGIN) for root, gap in gaps.items()}, gaps


def comparison(values, labels, selection, evaluation):
    require(set(selection).isdisjoint(evaluation)
            and set(selection) | set(evaluation) == set(range(REPLICATES)),
            "Selection and evaluation must be complementary disjoint halves.")
    chosen, gaps = choose({k: v for k, v in values.items() if k[2] in selection}, selection)
    rows = []
    for root in range(ROOTS):
        differences = {
            name: statistics.mean(labels[root, chosen[root], rep][name]
                                  - labels[root, 0, rep][name] for rep in evaluation)
            for name in ("reward", "winShare", "rankReward")
        }
        rows.append({"rootIndex": root, "world": labels[root, 0, 0]["world"],
                     "selectedAction": chosen[root], "selectionPredictedGap": gaps[root],
                     "evaluationDifferenceVsBaseline": differences})
    return {"selectionReplicates": list(selection), "evaluationReplicates": list(evaluation),
            "optionSelectedOrigins": sum(chosen.values()), "roots": rows,
            "evaluationDifferenceVsBaseline": {
                name: summarize([row["evaluationDifferenceVsBaseline"][name] for row in rows])
                for name in ("reward", "winShare", "rankReward")}}


def import_inference(contract):
    """Load the exact audited owner, without copying its network equations."""
    paths = {name: pinned(value["path"], value["sha256"])
             for name, value in contract["files"].items()}
    sys.path.insert(0, str(paths["inferenceCode"].parent))
    spec = importlib.util.spec_from_file_location("audited_purchase_value", paths["inferenceCode"])
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    require(Path(module.data.__file__).resolve() == paths["encoderCacheCode"].resolve(),
            "The inference owner imported a different data module.")
    module.torch.set_num_threads(2)
    module.torch.set_num_interop_threads(1)
    module.torch.use_deterministic_algorithms(True)
    return module, paths


def score(features, labels, names, contract):
    module, paths = import_inference(contract)
    torch = module.torch
    models = {"c64": module.FrozenC64(read(paths["c64"])).eval()}
    for name in ("small", "large"):
        # Construction values are all overwritten by the complete pinned state_dict;
        # seed 0 here creates no experiment, simulation or analysis random stream.
        model = module.Critic(contract["models"][name]["widths"], seed=0, inputs=len(names))
        model.load_state_dict(torch.load(paths[name + "Checkpoint"], map_location="cpu",
                                         weights_only=True), strict=True)
        models[name] = model.eval()
    values = {name: {} for name in models}
    purchase = [row for row in features if row["kind"] == "purchase"]
    with torch.inference_mode():
        for start in range(0, len(purchase), 128):
            batch = purchase[start:start + 128]
            raw = torch.tensor([feature_vector(row, names) for row in batch], dtype=torch.float64)
            for name, model in models.items():
                dtype = torch.float64 if name == "c64" else torch.float32
                predictions = model(raw.to(dtype=dtype)).tolist()
                require(all(math.isfinite(v) and 0 <= v <= 1 for v in predictions),
                        "A frozen critic produced an invalid terminal-reward estimate.")
                values[name].update((key(row), value) for row, value in zip(batch, predictions))
    for row in features:
        if row["kind"] == "terminal":
            for name in models:
                values[name][key(row)] = labels[key(row)]["reward"]
    values["full-terminal-return"] = {k: row["reward"] for k, row in labels.items()}
    return values, {"python": sys.version, "torch": torch.__version__, "device": "cpu",
                    "threads": torch.get_num_threads(), "interopThreads": torch.get_num_interop_threads(),
                    "freshDtype": "float32", "c64Dtype": "float64", "inferenceMode": True,
                    "modelTraining": {name: model.training for name, model in models.items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("manifest_sha256")
    parser.add_argument("output", type=Path)
    parser.add_argument("--score", action="store_true", help="Requires separate launch review.")
    args = parser.parse_args()
    manifest = read(pinned(args.manifest, args.manifest_sha256))
    for name, sha256 in manifest["fileSha256"].items():
        pinned(name, sha256)
    require(digest(Path(__file__)) == manifest["adapterSha256"], "Analysis adapter changed.")
    plan = read(manifest["analysisPlan"])
    require(plan["optionMargin"] == MARGIN and plan["primary"] == "small",
            "The predeclared primary model and margin must remain fixed.")
    require(plan["primarySelectionReplicates"] == list(range(8))
            and plan["primaryEvaluationReplicates"] == list(range(8, 16)), "Halves changed.")
    require(plan["secondarySwapHalves"] and plan["newFits"] == plan["newGames"] == 0,
            "Only the fixed retrospective two-half diagnostic is supported.")
    contract = read(pinned(plan["modelContract"], plan["modelContractSha256"]))
    for item in contract["files"].values():
        pinned(item["path"], item["sha256"])
    names = read(contract["files"]["stateFeatureNames"]["path"])
    require(len(names) == len(set(names)) == 3556, "Frozen dictionary must have 3,556 names.")
    require(names == read(contract["files"]["c64"]["path"])["stateFeatureNames"],
            "C64 and fresh critics must consume the same ordered dictionary.")
    directory = Path(manifest["extractedDirectory"])
    complete = read(directory / "complete.json")
    require(complete["rows"] == 1024 and complete["criticScoresComputed"] == 0,
            "Expected the complete unscored extraction.")
    features, labels = load_rows(directory, complete, names)
    args.output.mkdir(mode=0o700)
    receipt = {"manifestSha256": args.manifest_sha256, "modelContractSha256": plan["modelContractSha256"],
               "rows": len(features), "terminalFallbacks": sum(r["kind"] == "terminal" for r in features),
               "newGames": 0, "newFits": 0, "policyCalls": 0, "productionActivation": False}
    write(args.output / "preparation.json", dict(receipt, criticScoresComputed=0))
    if not args.score:
        return
    values, environment = score(features, labels, names, contract)
    write(args.output / "environment.json", environment)
    write(args.output / "scores.json", [
        {**{name: row[name] for name in IDENTITY}, "kind": row["kind"],
         "values": {name: table[key(row)] for name, table in values.items()}}
        for row in features
    ])
    analyses = {name: {"primary": comparison(table, labels, range(8), range(8, 16)),
                       "secondarySwapped": comparison(table, labels, range(8, 16), range(8))}
                for name, table in values.items()}
    write(args.output / "analysis.json", {**receipt, "primaryModel": "small", "optionMargin": MARGIN,
           "intervalUnit": "one source origin; conservative approximate95 t15 interval on32 origin means",
           "models": analyses, "limitations": plan["limitations"], "runtimeSpeedupClaimed": False})
    for name, sha256 in manifest["fileSha256"].items():
        pinned(name, sha256)
    write(args.output / "complete.json", {**receipt, "status": "complete",
          "frozenInputsUnchanged": True, "criticScoresComputed": 3 * (1024 - receipt["terminalFallbacks"]),
          "outputSha256": {str(path.name): digest(path) for path in sorted(args.output.glob("*.json"))}})


if __name__ == "__main__":
    main()
