"""One fixed, local conditional acquisition-value diagnostic; never an actor export.

The independent unit is the origin world. Two retained future streams estimate each
route's BUY-minus-incumbent return. Ridge minimizes weighted mean squared error plus
10 * ||beta||^2, with an unpenalized intercept and training-fold-only normalization.
This follows card_continuation_analysis's mean-loss convention, without its fixed
eight-feature critic dependency. No hidden-card identities enter this extractor.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import sys
import time

import numpy as np


KIND = "acquisition-conditional-value-1"
LAMBDA = 10.0
FOLDS = 5
Z95 = 1.959963984540054
ROUTES = ("CASH", "EXPANSION")
CANDIDATE_FEATURES = (
    "action.buy-card", "action.buy-expansion-card", "action.expansion", "action.pass",
    "cards", "cash", "commodity", "expansion.targetAdvanceShare",
    "expansion.targetCashShare", "expansion.targetIncomeShare", "expansion.targetMarket",
    "income", "misery", "progress", "reach", "reserveShortfall", "risk", "tokens",
    "planning.commodityDenial", "planning.foregoneInterest", "planning.heldCommodity",
    "planning.publicCommodity",
)
ADVANCES = (
    "THE_HEAVENS", "HUMAN_BODY", "LAWS_OF_MATTER", "ENLIGHTENMENT", "PATRONAGE",
    "HOLY_INDULGENCE", "PROSELYTISM", "CATHEDRAL", "CARAVAN", "WIND_WATERMILL",
    "IMPROVED_AGRICULTURE", "INTEREST_PROFIT", "INDUSTRY", "WRITTEN_RECORD",
    "PRINTED_WORD", "MASTER_ART", "RENAISSANCE", "OVERLAND_EAST", "SEAWORTHY_VESSELS",
    "OCEAN_NAVIGATION", "NEW_WORLD", "URBAN_ASCENDANCY", "NATIONALISM",
    "INSTITUTIONAL_RESEARCH", "COSMOPOLITAN", "MIDDLE_CLASS",
)
MISERY = dict(zip((
    "ZERO", "TEN", "TWENTY", "THIRTY", "FORTY", "FIFTY", "SIXTY", "SEVENTY",
    "EIGHTY", "NINETY", "HUNDRED", "HUNDRED_TWENTY_FIVE", "HUNDRED_FIFTY",
    "HUNDRED_SEVENTY_FIVE", "TWO_HUNDRED", "TWO_HUNDRED_FIFTY", "THREE_HUNDRED",
    "THREE_HUNDRED_FIFTY", "FOUR_HUNDRED", "FOUR_HUNDRED_FIFTY", "FIVE_HUNDRED",
    "SIX_HUNDRED", "SEVEN_HUNDRED", "EIGHT_HUNDRED", "NINE_HUNDRED", "THOUSAND",
    "CHAOS",
), (0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 125, 150, 175, 200, 250, 300,
    350, 400, 450, 500, 600, 700, 800, 900, 1000, 0)))
OWN_FIELDS = ("cash", "handCount", "stockTokens", "poolTokens", "boardMarkers", "stockMarkers")
INCOME_FIELDS = ("ordinary", "baseIncome", "middleClassBonus", "interestProfit", "reliefSpaces")
PUBLIC_FIELDS = ("cash", "handCount", "poolTokens", "boardMarkers", "income",
                 "advanceCount", "misery", "chaos")
FEATURE_NAMES = (
    tuple(f"{arm}.feature.{name}" for arm in ("BUY", "REFERENCE") for name in CANDIDATE_FEATURES)
    + tuple(f"{arm}.{name}" for arm in ("BUY", "REFERENCE") for name in ("cashCost", "tokenCost"))
    + tuple(f"route.{route}" for route in ROUTES)
    + ("epoch.1", "epoch.2", "epoch.3", "turn", "deckRemaining", "endingTriggered")
    + tuple(f"own.{name}" for name in OWN_FIELDS)
    + tuple(f"own.income.{name}" for name in INCOME_FIELDS)
    + ("own.misery", "own.chaos", "stabilizationCost", "stabilizationMisery")
    + tuple(f"own.advance.{name}" for name in ADVANCES)
    + tuple(f"rivals.mean.{name}" for name in PUBLIC_FIELDS)
)
THREAD_ENV = ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "VECLIB_MAXIMUM_THREADS")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    with Path(path).open("x") as output:
        json.dump(value, output, indent=2, sort_keys=True, allow_nan=False)
        output.write("\n")


def numeric(value):
    require(isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value), "An explicitly named numeric feature must be finite.")
    return float(value)


def extract(actor_input, route, reference_command):
    """Only actor-visible input is accepted; commands locate candidates, never become columns."""
    require(route in ROUTES, "Acquisition route must be CASH or EXPANSION.")
    view = actor_input["view"]
    observation = actor_input["observation"]
    require(observation["decision"] == ("buy-card" if route == "CASH" else "expand"),
            "Actor decision must match the frozen acquisition route.")
    commands = {"BUY": "buy-card" if route == "CASH" else "buy-expansion-card",
                "REFERENCE": reference_command}
    require(commands["BUY"] != reference_command, "The reference must be an acquisition decline.")
    if route == "CASH":
        require(reference_command == "pass", "A cash decline must be an explicit pass.")
    candidates = {}
    for arm, command in commands.items():
        offered = [candidate for candidate in observation["candidates"] if candidate["command"] == command]
        require(len(offered) == 1, "Both fixed commands must occur exactly once in actor candidates.")
        candidates[arm] = offered[0]
    own = [player for player in view["players"] if player["id"]["value"] == observation["playerId"]]
    require(len(own) == 1, "Actor identity must locate exactly one own public player view.")
    own = own[0]
    rivals = [player for player in view["players"] if player["id"]["value"] != observation["playerId"]]
    require(len(rivals) == 3, "The frozen pilot requires three public rival views.")
    require(view["currentEpoch"] in (1, 2, 3), "Recorded epoch must be 1, 2, or 3.")
    require(type(view["endingTriggered"]) is bool, "Ending state must be a boolean.")
    values = [numeric(candidates[arm]["features"].get(name, 0))
              for arm in ("BUY", "REFERENCE") for name in CANDIDATE_FEATURES]
    values += [numeric(candidates[arm][name]) for arm in ("BUY", "REFERENCE")
               for name in ("cashCost", "tokenCost")]
    values += [float(route == offered) for offered in ROUTES]
    values += [float(view["currentEpoch"] == epoch) for epoch in (1, 2, 3)]
    values += [numeric(view["turnNumber"]), numeric(view["deckRemaining"]), float(view["endingTriggered"])]
    values += [numeric(own[name]) for name in OWN_FIELDS]
    values += [numeric(own["income"][name]) for name in INCOME_FIELDS]
    require(own["misery"] in MISERY, "Own misery must use the canonical enum.")
    values += [float(MISERY[own["misery"]]), float(own["misery"] == "CHAOS"),
               numeric(view["stabilizationCost"]), numeric(view["stabilizationMisery"])]
    require(set(own["advances"]).issubset(ADVANCES), "Advance ownership must use the canonical enum.")
    values += [float(name in own["advances"]) for name in ADVANCES]
    public = []
    for rival in rivals:
        require(rival["misery"] in MISERY, "Rival misery must use the canonical enum.")
        public.append([numeric(rival[name]) for name in PUBLIC_FIELDS[:4]] + [
            numeric(rival["income"]["ordinary"]), float(len(rival["advances"])),
            float(MISERY[rival["misery"]]), float(rival["misery"] == "CHAOS")])
    values += np.mean(np.asarray(public, dtype=np.float64), axis=0).tolist()
    require(len(values) == len(FEATURE_NAMES) <= 128, "Frozen feature dictionary must match the extractor.")
    require(np.isfinite(values).all(), "All actor-visible features must be finite.")
    return values


def partition(rows, fold):
    train = np.array([i for i, row in enumerate(rows) if row["index"] % FOLDS != fold])
    heldout = np.array([i for i, row in enumerate(rows) if row["index"] % FOLDS == fold])
    require(len(train) > 0 and len(heldout) > 0, "Every fold requires training and held-out worlds.")
    require(not ({rows[i]["index"] for i in train} & {rows[i]["index"] for i in heldout}),
            "All routes of an origin must stay in the same fold.")
    return train, heldout


def fit_ridge(x, y, weights):
    """Training rows only: weighted population scaling and unpenalized intercept."""
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.float64)
    weights = np.asarray(weights, dtype=np.float64)
    require(x.ndim == 2 and y.shape == weights.shape == (len(x),) and len(x) > 0,
            "Ridge needs a matrix and one target/weight per training row.")
    require(np.isfinite(x).all() and np.isfinite(y).all() and np.isfinite(weights).all()
            and (weights > 0).all(), "Training values must be finite and weights positive.")
    weights = weights / weights.sum()
    center = weights @ x
    scale = np.sqrt(weights @ np.square(x - center))
    scale[np.ptp(x, axis=0) == 0] = 0
    normalized = np.divide(x - center, scale, out=np.zeros_like(x), where=scale != 0)
    design = np.column_stack((np.ones(len(x)), normalized))
    penalty = np.diag([0.0] + [LAMBDA] * x.shape[1])
    coefficients = np.linalg.solve(design.T @ (weights[:, None] * design) + penalty,
                                   design.T @ (weights * y))
    return {"mean": center.tolist(), "standardDeviation": scale.tolist(),
            "intercept": float(coefficients[0]), "coefficients": coefficients[1:].tolist(),
            "lambda": LAMBDA, "objective": "weightedMeanMSE + lambda * sum(beta^2)",
            "constantColumns": int((scale == 0).sum())}


def predict(fit, x):
    x = np.asarray(x, dtype=np.float64)
    center = np.asarray(fit["mean"])
    scale = np.asarray(fit["standardDeviation"])
    normalized = np.divide(x - center, scale, out=np.zeros_like(x), where=scale != 0)
    return float(fit["intercept"]) + normalized @ np.asarray(fit["coefficients"])


def route_means(rows, y, weights, indices):
    result = {}
    for route in ROUTES:
        selected = [i for i in indices if rows[i]["route"] == route]
        require(selected, "Each training fold must contain both routes for its route-mean baseline.")
        result[route] = float(np.average(y[selected], weights=weights[selected]))
    return result


def interval(values):
    require(len(values) >= 2, "A paired-origin interval requires at least two worlds.")
    mean = float(np.mean(values))
    se = float(np.std(values, ddof=1) / math.sqrt(len(values)))
    return {"origins": len(values), "mean": mean, "standardError": se,
            "lower95": mean - Z95 * se, "upper95": mean + Z95 * se}


def origin_values(rows, values, route=None):
    groups = {}
    for row, value in zip(rows, values):
        if route is None or row["route"] == route:
            groups.setdefault(row["index"], []).append(float(value))
    return [float(np.mean(groups[index])) for index in sorted(groups)]


def summarize(rows, y, fitted, baseline, route=None):
    errors = {"ridge": (fitted - y) ** 2, "zero": y ** 2, "routeMean": (baseline - y) ** 2}
    result = {"mse": {name: interval(origin_values(rows, values, route)) for name, values in errors.items()}}
    result["pairedMseDifference"] = {
        name: interval(origin_values(rows, errors["ridge"] - errors[name], route))
        for name in ("zero", "routeMean")}
    result["proxyIncrementalReturn"] = {
        "buyIfPredictionPositive": interval(origin_values(rows, np.where(fitted > 0, y, 0), route)),
        "alwaysBuy": interval(origin_values(rows, y, route)),
        "routeMeanPositive": interval(origin_values(rows, np.where(baseline > 0, y, 0), route)),
        "neverBuy": 0.0,
    }
    selected = [i for i, row in enumerate(rows) if route is None or row["route"] == route]
    result["offeredRoutes"] = len(selected)
    result["positivePredictions"] = int((fitted[selected] > 0).sum())
    return result


def prepare(dataset, output):
    require(not output.exists(), "A diagnostic preparation directory must be fresh.")
    inventory = read(dataset / "completion/retrieval-inventory.json")
    review = read(dataset / "completion/local-review.json")
    require(inventory["passed"] and review["passed"] and review["allOwnedProcessesExited"],
            "The acquisition dataset must have a passed completed audit.")
    paths = [dataset / f"completion/run/native/origin-{index}.json" for index in range(256)]
    pins = {str(path): sha(path) for path in paths}
    for path in paths:
        require(pins[str(path)] == inventory["files"][str(path.relative_to(dataset / "completion"))],
                "Every origin must equal its audited retrieved bytes.")
    for name in ("completion/retrieval-inventory.json", "completion/local-review.json",
                 "completion/completion-audit.json"):
        path = dataset / name
        pins[str(path)] = sha(path)
    script = Path(__file__).resolve()
    pins[str(script)] = sha(script)
    test = script.with_name("test_acquisition_conditional_value.py")
    pins[str(test)] = sha(test)
    output.mkdir(parents=True)
    plan = {"kind": KIND, "dataset": str(dataset), "inputFiles": pins,
            "featureNames": list(FEATURE_NAMES), "featureCount": len(FEATURE_NAMES),
            "folds": FOLDS, "fold": "origin.index % 5; all available routes stay together",
            "lambda": LAMBDA, "objective": "weightedMeanMSE + 10 * sum(beta^2); intercept unpenalized",
            "weights": "Each rooted world has weight 1; each of its available routes has weight 1/nRoutes",
            "target": "mean of two paired BUY-minus-REFERENCE composite reward differences",
            "standardization": "Weighted training-fold population mean/SD; constant columns always zero",
            "baselines": ["zero", "training-fold weighted route mean"],
            "interval": "95% normal paired-origin; average available routes inside each world first",
            "proxy": "BUY iff held-out prediction > 0; all thresholds fixed; no policy export",
            "fits": 5, "fullDataFit": False, "dtype": "float64", "cpuThreads": 1,
            "limits": {"seconds": 600, "attempts": 1},
            "exclusions": ["hidden hands or deck order", "card identities", "IDs and seeds",
                           "canonical histories", "future/outcome fields", "arbitrary unnamed features"],
            "limitations": ["Development-only cross-validation; not an independent final test",
                            "No full-game student strength or optimality claim",
                            "Two streams share origin hidden state and fixed top deck",
                            "Normal paired-origin intervals are descriptive; overlapping CV training sets are dependent",
                            "Proxy assumes current fixed continuation and route availability, not repeated deployment"]}
    write(output / "plan.json", plan)
    write(output / "freeze.json", {"planSha256": sha(output / "plan.json"), "frozenAtEpoch": time.time(),
                                  "scientificFitsBeforeFreeze": 0})
    return {"planSha256": sha(output / "plan.json"), "featureCount": len(FEATURE_NAMES), "inputPins": len(pins)}


def validate_plan(output):
    plan = read(output / "plan.json")
    require(sha(output / "plan.json") == read(output / "freeze.json")["planSha256"], "Frozen plan changed.")
    require(plan["kind"] == KIND and plan["featureNames"] == list(FEATURE_NAMES)
            and plan["lambda"] == LAMBDA and plan["fits"] == FOLDS, "Frozen scientific recipe changed.")
    for path, expected in plan["inputFiles"].items():
        require(sha(path) == expected, f"Pinned input changed: {path}")
    return plan


def origin_rows(origin, index, local_index, source_sha256):
    """Keep actor-only extraction and paired labels identical across single/multi-chunk corpora."""
    rows, features, labels, missing = [], [], [], []
    require(origin["request"]["index"] == local_index, "Origin file and recorded local index must agree.")
    collected = [root for root in origin["roots"] if root["status"] == "COLLECTED"]
    require(len({root["route"] for root in collected}) == len(collected), "Routes must be unique per origin.")
    if not collected:
        missing.append(index)
    for root in collected:
        route = root["route"]
        reference = root["provenance"]["incumbentCommand"]
        row_features = extract(root["actorInput"], route, reference)
        arms = {arm["role"]: arm for arm in root["arms"]}
        require(set(arms) == {"BUY", "REFERENCE"} and arms["REFERENCE"]["command"] == reference,
                "Labels must refer to the exact two prospectively fixed arms.")
        require(arms["BUY"]["command"] == ("buy-card" if route == "CASH" else "buy-expansion-card"),
                "The BUY outcome command must equal the offered acquisition command.")
        differences = []
        for replicate in range(2):
            outcomes = {}
            for arm, item in arms.items():
                matching = [outcome for outcome in item["outcomes"] if outcome["replicate"] == replicate]
                require(len(matching) == 1 and matching[0]["replayVerified"], "Each arm requires two audited outcomes.")
                outcomes[arm] = matching[0]["terminal"]
            require(outcomes["BUY"]["continuationSeed"] == outcomes["REFERENCE"]["continuationSeed"],
                    "Paired future streams must match between arms.")
            differences.append(numeric(outcomes["BUY"]["reward"]) - numeric(outcomes["REFERENCE"]["reward"]))
        rows.append({"index": index, "route": route, "fold": index % FOLDS,
                     "weight": 1.0 / len(collected), "sourceSha256": source_sha256})
        features.append(row_features)
        labels.append(float(np.mean(differences)))
    return rows, features, labels, missing


def load_rows(plan):
    """Feature extraction sees only actorInput; labels are read separately after its row is fixed."""
    rows, features, labels, missing = [], [], [], []
    dataset = Path(plan["dataset"])
    for index in range(256):
        path = dataset / f"completion/run/native/origin-{index}.json"
        batch = origin_rows(read(path), index, index, plan["inputFiles"][str(path)])
        for output, values in zip((rows, features, labels, missing), batch):
            output.extend(values)
    return rows, np.asarray(features, dtype=np.float64), np.asarray(labels, dtype=np.float64), missing


def cross_validate(rows, x, y):
    """Exactly five original ridge fits; each route remains with its independent origin."""
    weights = np.array([row["weight"] for row in rows], dtype=np.float64)
    fitted = np.full(len(y), np.nan)
    baseline = np.full(len(y), np.nan)
    folds = []
    for fold in range(FOLDS):
        train, heldout = partition(rows, fold)
        fit = fit_ridge(x[train], y[train], weights[train])
        means = route_means(rows, y, weights, train)
        fitted[heldout] = predict(fit, x[heldout])
        baseline[heldout] = [means[rows[i]["route"]] for i in heldout]
        folds.append({"fold": fold, "trainRows": train.tolist(),
              "heldoutRows": heldout.tolist(), "trainingWorlds": len({rows[i]["index"] for i in train}),
              "heldoutWorlds": len({rows[i]["index"] for i in heldout}), "fit": fit, "routeMeans": means})
    require(np.isfinite(fitted).all() and np.isfinite(baseline).all(), "Every retained route needs one out-of-origin prediction.")
    return fitted, baseline, folds


def run(output):
    require(all(os.environ.get(name) == "1" for name in THREAD_ENV), "All numerical thread limits must be one.")
    plan = validate_plan(output)
    write(output / "attempt.json", {"startedEpoch": time.time(), "pid": os.getpid(), "attempt": 1})
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError("The one diagnostic attempt exceeded 600 seconds.")))
    signal.alarm(plan["limits"]["seconds"])
    rows, x, y, missing = load_rows(plan)
    write(output / "features.json", {"featureNames": list(FEATURE_NAMES), "rows": rows, "values": x.tolist()})
    write(output / "labels.json", {"targets": y.tolist(), "target": plan["target"]})
    fitted, baseline, folds = cross_validate(rows, x, y)
    for fold in folds:
        write(output / f"fold-{fold['fold']}.json", fold)
    write(output / "predictions.json", {"rows": rows, "ridge": fitted.tolist(), "routeMean": baseline.tolist()})
    result = {"kind": KIND, "originWorlds": 256, "rootedWorlds": len({row["index"] for row in rows}),
              "noRootWorlds": missing, "rows": len(rows), "features": x.shape[1], "fits": FOLDS,
              "combined": summarize(rows, y, fitted, baseline),
              "routes": {route: summarize(rows, y, fitted, baseline, route) for route in ROUTES},
              "limitations": plan["limitations"]}
    write(output / "results.json", result)
    validate_plan(output)
    outputs = {path.name: sha(path) for path in sorted(output.iterdir()) if path.is_file()}
    write(output / "receipt.json", {"kind": KIND + "-receipt", "passed": True, "fits": FOLDS,
          "newGames": 0, "fullDataFit": False, "exportedActor": False, "inputFilesRehashed": len(plan["inputFiles"]),
          "outputs": outputs, "numpyVersion": np.__version__, "python": sys.version, "platform": platform.platform(),
          "threadEnvironment": {name: os.environ[name] for name in THREAD_ENV}, "completedAtEpoch": time.time()})
    signal.alarm(0)
    return {"receiptSha256": sha(output / "receipt.json"), "resultsSha256": sha(output / "results.json"),
            "combined": result["combined"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "run"))
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        require(args.dataset is not None, "Preparation requires the explicit completed dataset.")
        result = prepare(args.dataset.resolve(), args.output.resolve())
    else:
        result = run(args.output.resolve())
    print(json.dumps(result, sort_keys=True, allow_nan=False))


if __name__ == "__main__":
    main()
