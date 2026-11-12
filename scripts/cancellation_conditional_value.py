"""One fixed TRAIN-only cancellation-value fit; held-out public-prediction proxy only.

The prior public projection owns all payout/removal arithmetic. This module only
aggregates exact quotes and predicts first-action returns under frozen continuation.
TEST origins are never admitted. No policy artifact or native game execution exists.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import signal
import sys
import time

import numpy as np
import acquisition_conditional_value as ridge

KIND = "cancellation-conditional-value-1"
COMMODITIES = ("STONE", "WOOL", "TIMBER", "GRAIN", "CLOTH", "WINE", "METAL",
               "FUR", "SILK", "SPICE", "GOLD", "IVORY")
FEATURE_NAMES = (
    ("epoch1", "epoch2", "epoch3", "turn", "deckRemaining", "endingTriggered",
     "cashBefore", "handCount", "boardMarkers")
    + ("isShortage", "isSurplus", "cost", "cashAfter", "costOverCashBefore",
       "affectedCommodityCount", "sharedGoldIvory")
    + ("queueLengthSum", "shortageCountSum", "surplusCountSum", "requestedKindCountSum",
       "requestedKindHeadCount", "requestedKindFirstIndexSum",
       "remainingRequestedKindCountSum", "unchangedHeadCount")
    + tuple(owner + "." + field for owner in ("own", "rival1", "rival2", "rival3")
            for field in ("marketCountSum", "industryBonusSum", "boxBeforeSum",
                          "payoutBeforeSum", "payoutDeltaSum"))
    + tuple("commodity." + commodity for commodity in COMMODITIES)
    + ("ownPhysicalMatchingCardCount", "ownPhysicalMatchingCardCountTimesOwnPayoutDeltaSum")
)
METRICS = ("reward", "winShare", "rankReward", "chaos")
require, read, sha, write = ridge.require, ridge.read, ridge.sha, ridge.write


def player_id(value):
    return value["value"]


def extract(actor_input, public, command, catalog):
    """Accept actor-safe fields and public quotes only, never an origin/terminal object."""
    view, observation = actor_input["view"], actor_input["observation"]
    context = public["context"]
    actor = player_id(context["actor"])
    require(observation["decision"] == "cancel-adjustment", "Require a cancellation actor.")
    order = [player_id(player) for player in view["turnOrder"]]
    require(len(order) == 4 and len(set(order)) == 4 and order[0] == actor,
            "Relative owners require the four-seat first-player order.")
    own = next(player for player in view["players"] if player_id(player["id"]) == actor)
    require(context["cash"] == own["cash"] and context["offers"] == view["adjustmentOffers"]
            and context["payouts"] == view["commodityPayouts"], "Original public context differs.")
    offered = [candidate["command"] for candidate in observation["candidates"]]
    require(len(set(offered)) == len(offered) and set(offered) ==
            {quote["command"] for quote in public["quotes"]}, "Retain every offered action.")
    require(command in offered and command != "pass", "A regression row requires offered CANCEL.")
    quote = next(quote for quote in public["quotes"] if quote["command"] == command)
    kind = quote["adjustment"]
    require(kind in ("SHORTAGE", "SURPLUS"), "Cancellation kind must be typed.")
    changed = quote["commodities"]
    affected = {change["commodity"] for change in changed}
    require(len(affected) == len(changed) and affected <= set(COMMODITIES),
            "Each affected physical commodity is quoted once.")
    require(quote["cashBefore"] > 0 and quote["cost"] > 0 and quote["cashAfter"] >= 0,
            "Retain the original affordable cancellation quote.")
    values = [float(view["currentEpoch"] == epoch) for epoch in (1, 2, 3)]
    values += [view["turnNumber"], view["deckRemaining"], int(view["endingTriggered"]),
               quote["cashBefore"], own["handCount"], own["boardMarkers"]]
    values += [int(kind == "SHORTAGE"), int(kind == "SURPLUS"), quote["cost"],
               quote["cashAfter"], quote["cost"] / quote["cashBefore"], len(affected),
               int(affected == {"GOLD", "IVORY"})]
    values += [sum(len(change["before"]) for change in changed),
               sum(change["before"].count("SHORTAGE") for change in changed),
               sum(change["before"].count("SURPLUS") for change in changed),
               sum(change["before"].count(kind) for change in changed),
               sum(change["before"][0] == kind for change in changed),
               sum(change["before"].index(kind) for change in changed),
               sum(change["after"].count(kind) for change in changed),
               sum(change["before"][:1] == change["after"][:1] for change in changed)]
    own_delta = 0
    for owner in order:
        quoted = [item for change in changed for item in change["owners"]
                  if player_id(item["player"]) == owner]
        delta = sum(item["payoutAfter"] - item["payoutBefore"] for item in quoted)
        values += [sum(item[field] for item in quoted)
                   for field in ("marketCount", "industryBonus", "boxBefore", "payoutBefore")]
        values.append(delta)
        if owner == actor:
            own_delta = delta
    values += [int(commodity in affected) for commodity in COMMODITIES]
    hand = view["hand"]
    require(len({card["reference"] for card in hand}) == len(hand),
            "Own physical cards require unique references.")
    require(all(card["definition"]["id"] in catalog for card in hand),
            "Each own card requires an authoritative static effect definition.")
    matching = sum(bool(set(catalog[card["definition"]["id"]]) & affected) for card in hand)
    values += [matching, matching * own_delta]
    require(len(values) == len(FEATURE_NAMES) == 58 and np.isfinite(values).all(),
            "The frozen 58-column public dictionary must be finite.")
    return np.asarray(values, dtype=np.float64)


def split_for(index):
    require(type(index) is int and 0 <= index < 224, "TEST or unknown origins are forbidden.")
    return "TRAIN" if index < 192 else "VALIDATION"


def choose(predictions):
    """No labels accepted. PASS wins zero ties; positive ties use canonical command order."""
    require("pass" not in predictions and predictions and
            all(math.isfinite(value) for value in predictions.values()),
            "Require finite offered CANCEL predictions only.")
    command = min(predictions, key=lambda key: (-predictions[key], key))
    return command if predictions[command] > 0 else "pass"


def label_actions(origin):
    """Returns stay separate from feature/selection functions and from full canonical strings."""
    actor = origin["provenance"]["actor"]
    commands = [candidate["command"] for candidate in origin["actorInput"]["observation"]["candidates"]]
    arms = origin["arms"]
    require(len(arms) == len(commands) and {arm["command"] for arm in arms} == set(commands),
            "Every original action must retain exactly two common-stream outcomes.")
    result = {}
    for arm in arms:
        command = arm["command"]
        require(arm["actionIndex"] == commands.index(command), "Original action index changed.")
        require([outcome["replicate"] for outcome in arm["outcomes"]] == [0, 1],
                "The two ordered paired streams must be retained.")
        rows = []
        for replicate, outcome in enumerate(arm["outcomes"]):
            terminal = outcome["terminal"]
            require(outcome["replayVerified"] and terminal["continuationSeed"] == origin["chanceSeeds"][replicate],
                    "Terminal return must retain its exact replay and shared chance stream.")
            row = {key: float(terminal[key]) for key in METRICS[:3]}
            require(all(math.isfinite(v) and 0 <= v <= 1 for v in row.values()) and
                    abs(row["reward"] - (.8 * row["winShare"] + .2 * row["rankReward"])) < 1e-12,
                    "Composite terminal reward identity changed.")
            scores = [score for score in terminal["scores"] if score["player"] == actor]
            require(len(scores) == 1 and (scores[0]["misery"] is None) ==
                    (scores[0]["total"] is None), "Actor Chaos receipt must be unambiguous.")
            row["chaos"] = float(scores[0]["misery"] is None)
            rows.append(row)
        result[command] = rows
    require("pass" in result, "Every root requires PASS labels.")
    return result


def mean_metric(labels, command, metric):
    return sum(row[metric] for row in labels[command]) / 2


def training_matrix(worlds):
    rows, targets, weights, kinds = [], [], [], []
    for world in worlds:
        require(world["split"] == "TRAIN", "Only TRAIN worlds may enter fit or scaling.")
        for command, features in world["features"].items():
            rows.append(features)
            targets.append(mean_metric(world["labels"], command, "reward") -
                           mean_metric(world["labels"], "pass", "reward"))
            weights.append(1 / len(world["features"]))
            kinds.append(world["kinds"][command])
    return np.asarray(rows), np.asarray(targets), np.asarray(weights), kinds


def kind_means(targets, weights, kinds):
    overall = float(np.average(targets, weights=weights))
    return {kind: float(np.average([y for y, k in zip(targets, kinds) if k == kind],
                                  weights=[w for w, k in zip(weights, kinds) if k == kind]))
            if kind in kinds else overall for kind in ("SHORTAGE", "SURPLUS")}


def report(worlds, baselines):
    """One independent observation per origin, including paired selection contrasts."""
    losses = {name: [] for name in ("ridge", "zero", "kindMean")}
    effects = {baseline: {metric: [] for metric in METRICS} for baseline in ("pass", "incumbent")}
    retained = []
    for world in worlds:
        require(world["split"] == "VALIDATION", "The semantic report uses VALIDATION only.")
        local = {name: [] for name in losses}
        actions = []
        for command in sorted(world["features"]):
            target = mean_metric(world["labels"], command, "reward") - mean_metric(world["labels"], "pass", "reward")
            predictions = {"ridge": world["predictions"][command], "zero": 0.0,
                           "kindMean": baselines[world["kinds"][command]]}
            for name in local:
                local[name].append((predictions[name] - target) ** 2)
            actions.append({"command": command, "kind": world["kinds"][command],
                            "target": target, "features": world["features"][command].tolist(), "predictions": predictions,
                            "pairedReturns": world["labels"][command]})
        for name in losses:
            losses[name].append(float(np.mean(local[name])))
        differences = {}
        for baseline in effects:
            reference = "pass" if baseline == "pass" else world["incumbent"]
            differences[baseline] = {}
            for metric in METRICS:
                difference = mean_metric(world["labels"], world["selected"], metric) - mean_metric(world["labels"], reference, metric)
                effects[baseline][metric].append(difference)
                differences[baseline][metric] = difference
        retained.append({key: world[key] for key in ("index", "epoch", "sourceSha256", "incumbent", "selected")} |
                        {"actions": actions, "passPairedReturns": world["labels"]["pass"],
                         "differences": differences})
    summary = {"mse": {name: ridge.interval(values) for name, values in losses.items()},
               "pairedMseDifference": {name: ridge.interval(np.asarray(losses["ridge"]) - losses[name])
                                       for name in ("zero", "kindMean")},
               "selectionEffects": {baseline: {metric: ridge.interval(values) for metric, values in metrics.items()}
                                    for baseline, metrics in effects.items()},
               "counts": {"origins": len(worlds), "selectedCancel": sum(w["selected"] != "pass" for w in worlds),
                          "changedIncumbent": sum(w["selected"] != w["incumbent"] for w in worlds),
                          "byEpoch": {str(epoch): sum(w["epoch"] == epoch for w in worlds) for epoch in (1, 2, 3)},
                          "selectedKind": {kind: sum(w["selected"] != "pass" and w["kinds"][w["selected"]] == kind for w in worlds)
                                           for kind in ("SHORTAGE", "SURPLUS")}},
               "limitations": ["Development-only first-action proxy, not whole-game student strength.",
                               "Normal frozen continuation includes old later cancellation decisions.",
                               "Origin is independent unit; paired streams share hidden hands/topdeck.",
                               "Thirty validation roots yield uncertain approximate normal intervals.",
                               "Positive Chaos differences mean increased Chaos risk."],
               "testOriginsOpened": 0, "fits": 1, "newGames": 0}
    return summary, retained


def load_world(row, public, catalog):
    index = row["index"]
    split = split_for(index)
    require(sha(row["path"]) == row["sha256"], "Original origin bytes differ.")
    origin = read(row["path"])
    require(origin["kind"] == "cancellation-origin-1" and origin["request"]["index"] == index,
            "Original origin identity differs.")
    if not row["captured"]:
        require(origin["status"] == "MISSING" and not origin["arms"], "Missing roots remain missing.")
        return None
    require(origin["status"] == "COLLECTED" and public["sourceSha256"] == row["sha256"]
            and public["split"] == split and public["prefixSha256"] == origin["prefix"]["canonicalSha256"]
            and origin["provenance"]["exactPrefixAndIncumbentVerified"], "Projection provenance differs.")
    actor_input = origin["actorInput"]
    commands = [candidate["command"] for candidate in actor_input["observation"]["candidates"]]
    features = {command: extract(actor_input, public, command, catalog) for command in sorted(commands) if command != "pass"}
    incumbent = origin["provenance"]["incumbentCommand"]
    require(incumbent in commands, "Normal incumbent must be an original offered first action.")
    return {"index": index, "split": split, "epoch": actor_input["view"]["currentEpoch"],
            "sourceSha256": row["sha256"], "features": features, "incumbent": incumbent,
            "kinds": {quote["command"]: quote["adjustment"] for quote in public["quotes"] if quote["command"] != "pass"},
            "origin": origin}


def validate_protocol(protocol):
    require(protocol["kind"] == KIND and protocol["featureNames"] == list(FEATURE_NAMES)
            and protocol["lambda"] == 10 and protocol["fits"] == 1, "Frozen diagnostic recipe differs.")
    require(protocol["selection"] == "MAX_PREDICTED_CANCEL_STRICTLY_POSITIVE_ELSE_PASS_CANONICAL_TIES",
            "Public selection rule differs.")
    require(protocol["limits"] == {"seconds": 600, "cpuThreads": 1, "attempts": 1}, "Bounded attempt differs.")
    require([row["index"] for row in protocol["origins"]] == list(range(224)),
            "Only all original TRAIN/VALIDATION worlds are admitted; TEST stays closed.")
    require(all(row["split"] == split_for(row["index"]) for row in protocol["origins"]),
            "Original whole-origin split changed.")
    for path, expected in protocol["inputFiles"].items():
        require(sha(path) == expected, "A frozen input changed: " + path)


def run(protocol_path, output):
    started = time.time()
    protocol = read(protocol_path)
    validate_protocol(protocol)
    require(all(os.environ.get(name) == "1" for name in ridge.THREAD_ENV), "Numerical libraries must use one CPU thread.")
    require(sys.version == protocol["pythonVersion"] and np.__version__ == protocol["numpyVersion"]
            and sha(sys.executable) == protocol["pythonSha256"], "Frozen numerical identity changed.")
    require(not output.exists(), "One attempt only; output must not exist.")
    output.mkdir()
    write(output / "attempt.json", {"started": started, "protocolSha256": sha(protocol_path)})
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError("Original 600-second limit reached.")))
    signal.alarm(600)
    public_rows = {row["index"]: row for row in (json.loads(line) for line in Path(protocol["publicContexts"]).read_text().splitlines())}
    catalog = read(protocol["catalog"])
    worlds = [world for row in protocol["origins"]
              if (world := load_world(row, public_rows.get(row["index"]), catalog)) is not None]
    train = [world for world in worlds if world["split"] == "TRAIN"]
    validation = [world for world in worlds if world["split"] == "VALIDATION"]
    require(len(train) == 182 and len(validation) == 30
            and sum(len(world["features"]) for world in validation) == 59,
            "Frozen capture and validation-action counts differ.")
    for world in train:
        world["labels"] = label_actions(world["origin"])
    x, y, weights, kinds = training_matrix(train)
    require(x.shape == (352, 58), "Frozen TRAIN action coverage differs.")
    fitted = ridge.fit_ridge(x, y, weights)
    write(output / "diagnostic-coefficients.json", fitted)
    means = kind_means(y, weights, kinds)
    for world in worlds:
        commands = list(world["features"])
        prediction = ridge.predict(fitted, [world["features"][command] for command in commands])
        world["predictions"] = dict(zip(commands, map(float, prediction)))
        world["selected"] = choose(world["predictions"])
    write(output / "public-predictions.json", [{key: world[key] for key in
          ("index", "split", "sourceSha256", "incumbent", "predictions", "selected")} for world in worlds])
    # Freeze decisions before reading any semantic VALIDATION return fields.
    for world in validation:
        world["labels"] = label_actions(world["origin"])
    summary, retained = report(validation, means)
    write(output / "validation-origins.json", retained)
    write(output / "training-origins.json", [{"index": w["index"], "sourceSha256": w["sourceSha256"],
          "incumbent": w["incumbent"], "predictions": w["predictions"], "selected": w["selected"],
          "actions": [{"command": c, "features": w["features"][c].tolist(), "pairedReturns": returns}
                      for c, returns in w["labels"].items() if c != "pass"],
          "passPairedReturns": w["labels"]["pass"]} for w in train])
    summary.update({"kind": KIND, "protocolSha256": sha(protocol_path), "elapsedSeconds": time.time() - started,
                    "trainOrigins": len(train), "validationOrigins": len(validation), "trainActions": len(x),
                    "missingOrigins": {"TRAIN": 10, "VALIDATION": 2},
                    "validationActions": sum(len(w["features"]) for w in validation), "kindMeanBaseline": means,
                    "predictionsSha256": sha(output / "public-predictions.json")})
    validate_protocol(protocol)
    write(output / "summary.json", summary)
    write(output / "completion.json", {"passed": True, "fits": 1, "testOriginsOpened": 0, "newGames": 0,
          "protocolSha256": sha(protocol_path), "files": {p.name: sha(p) for p in sorted(output.iterdir()) if p.is_file()}})
    signal.alarm(0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.protocol, args.output)


if __name__ == "__main__":
    main()
