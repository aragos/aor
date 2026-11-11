#!/usr/bin/env python3
"""Fit paired terminal advantages at prospective token roots; no hidden-world argmax labels.

Every root has equal weight, regardless of its number of alternatives or chance
replicates. Validation selects one fixed model and is never qualification evidence.
The deployed selector compares the same bounded proposals against the unchanged
incumbent and requires strictly more than .02 predicted advantage.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import math
from pathlib import Path
import random
import statistics
import sys
import time

import purchase_policy as b
import territory_value as q
import token_order_metadata as metadata


KIND = metadata.KIND
PROPOSALS = metadata.PROPOSALS
MARGIN = .02
CONFIGURATION = metadata.CONFIGURATION
SELECTION = metadata.SELECTION
ARENA_CONTINUATION = metadata.ARENA_CONTINUATION
EXPANSION_FEATURES = metadata.EXPANSION_FEATURES
TOKEN_REFERENCE_VERSION = metadata.TOKEN_REFERENCE_VERSION
RULES_TRANSFER = metadata.RULES_TRANSFER
transfer_metadata = metadata.transfer_metadata
continuation_metadata = metadata.continuation_metadata
collection_metadata = metadata.collection_metadata
read, sha, require = b.read_json, b.file_digest, b.require


@dataclass(frozen=True)
class FitConfig:
    seed: int = 137
    epochs: int = 32
    batchSize: int = 32
    learningRate: float = .001
    maxGradientNorm: float = .5
    weightDecay: tuple[float, ...] = (0., .0001, .001)
    maxSeconds: float = 1800.

    def validate(self):
        b.integer(self.seed, "Fitting seed", 0, 100000000)
        b.integer(self.epochs, "Fixed epochs", 1, 256)
        b.integer(self.batchSize, "Roots per minibatch", 1, 128)
        b.number(self.learningRate, "Learning rate", .000001, .01)
        b.number(self.maxGradientNorm, "Gradient bound", .000001, 10)
        b.number(self.maxSeconds, "Original fitting allowance", 1, 7200)
        require(1 <= len(self.weightDecay) <= 8 and len(set(self.weightDecay)) == len(self.weightDecay),
                "Declare one through eight distinct regularization candidates before fitting.")
        for value in self.weightDecay:
            b.number(value, "Weight decay", 0, 1)
        return self


def proposal_indices(observation):
    """Mirror the native selector's baseline, offered neighbours, then legacy six proposals."""
    commands = [action["command"] for action in observation["actions"]]
    amounts = [int(command.removeprefix("buy-tokens ")) for command in commands]
    require(all(command == f"buy-tokens {amount}" for command, amount in zip(commands, amounts)),
            "Token observations require canonical signed purchase commands.")
    baseline = observation["baselineIndex"]
    amount = amounts[baseline]
    cash = round(observation["stateFeatures"].get("role.0.cash", 0.) * 200)
    stock = round(observation["stateFeatures"].get("role.0.stock", 0.) * 36)
    maximum, positive = min(cash, stock), max(amount, 0)
    proposed = [baseline] + [amounts.index(value) for value in (amount-1, amount+1) if value in amounts]
    for target in (amount, 0, -min(4, cash), positive//2, min(maximum, positive+8), maximum):
        proposed.append(min(range(len(amounts)), key=lambda index:
                            (abs(amounts[index]-target), amounts[index], commands[index])))
    return list(dict.fromkeys(proposed))


def validate_root(row, model, replicates):
    observation = row["observation"]
    b.validate_observation(observation, model, contract=q.TOKEN_CONTRACT_2)
    indices = row["proposedIndices"]
    baseline = observation["baselineIndex"]
    require(isinstance(indices, list) and 2 <= len(indices) <= 8
            and indices[0] == baseline and len(indices) == len(set(indices))
            and all(type(index) is int and 0 <= index < len(observation["actions"]) for index in indices),
            "A collected token root requires baseline-first distinct offered proposals.")
    require(indices == proposal_indices(observation), "Token root differs from the fixed proposal selector.")
    commands = [action["command"] for action in observation["actions"]]
    require(commands[baseline] == row["baselineCommand"], "Root baseline command differs from its observation.")
    amount = int(commands[baseline].removeprefix("buy-tokens "))
    neighbours = [commands.index(command) for command in
                  (f"buy-tokens {amount-1}", f"buy-tokens {amount+1}") if command in commands]
    require(all(index in indices for index in neighbours), "Token proposals omitted an offered one-token neighbour.")
    alternatives = row["alternatives"]
    require([alternative["index"] for alternative in alternatives] == indices,
            "Collected alternatives must retain proposal order.")
    streams = None
    returns = {}
    for alternative in alternatives:
        index = alternative["index"]
        require(alternative["command"] == commands[index]
                and len(alternative["outcomes"]) == replicates,
                "Alternative command or paired continuation count changed.")
        seeds = [outcome["continuationSeed"] for outcome in alternative["outcomes"]]
        require(len(seeds) == len(set(seeds)) and (streams is None or seeds == streams),
                "Every alternative must retain the same distinct chance streams.")
        streams = seeds
        values = []
        for outcome in alternative["outcomes"]:
            reward = b.number(outcome["reward"], "Terminal composite reward", 0, 1)
            win = b.number(outcome["winShare"], "Terminal win share", 0, 1)
            rank = b.number(outcome["rankReward"], "Terminal rank reward", 0, 1)
            require(abs(reward - (.8*win + .2*rank)) < 1e-10,
                    "Terminal reward must preserve its declared win/rank objective.")
            values.append(reward)
        returns[index] = values
    targets = {index: statistics.mean(after-before for after, before in
                zip(returns[index], returns[baseline])) for index in indices}
    return {"observation": observation, "indices": indices, "targets": targets,
            "origin": row["index"], "split": row["split"]}


def origin_request(manifest, index, opponents):
    """Check arithmetic bank identity; Kotlin's sampled ordinal comes from the prospective table."""
    validation = index >= manifest["trainingRoots"]
    local = index - manifest["trainingRoots"] if validation else index
    request = manifest["rootRequests"][index]
    identity = {"index": index, "split": "VALIDATION" if validation else "TRAINING",
                "gameSeed": -1000000000 + manifest["seed"] + int(validation) + local * 8191,
                "seat": local % 4, "opponentIndices": [(local // 4 + seat) % opponents for seat in range(4)],
                "requestedEpoch": local % 3 + 1}
    require(all(request[key] == value for key, value in identity.items()),
            "Prospective token request does not match its declared bank.")
    b.integer(request["requestedOrdinal"], "Prospective token ordinal", 1,
              4 if identity["requestedEpoch"] == 3 else 6)
    return {**identity, "requestedOrdinal": request["requestedOrdinal"]}


def collection(directory, seed):
    directory = Path(directory).resolve(strict=True)
    manifest, finished = read(directory / "dataset.json"), read(directory / "finished.json")
    source, pins = collection_metadata(directory, manifest, finished)
    model = q.initialize(manifest, seed, "token")
    count = manifest["trainingRoots"] + manifest["validationRoots"]
    require(manifest["originFiles"] == [f"origin-{index}.json" for index in range(count)]
            and len(finished["originSha256"]) == count and len(manifest["rootRequests"]) == count,
            "Collection completion must bind every prospective origin, including skips.")
    rows, skipped, worlds, streams = [], 0, set(), set()
    for index, (name, expected) in enumerate(zip(manifest["originFiles"], finished["originSha256"])):
        path = directory / name
        require(sha(path) == expected, "Completed token origin changed: " + name)
        pins[name] = expected
        row = read(path)
        request = origin_request(manifest, index, len(source["opponents"]))
        require(all(row[key] == value for key, value in request.items())
                and row["schemaVersion"] == 1 and row["signature"] == manifest["signature"]
                and row["configuration"] == manifest["configuration"],
                "Token origin identity differs from its prospective request.")
        chance = [(row["gameSeed"] << 3) ^ replicate ^ 0x6f20a5319d for replicate in range(2)]
        require(row["gameSeed"] not in worlds and not streams.intersection(chance),
                "Token origins and continuation worlds must be distinct across banks.")
        worlds.add(row["gameSeed"])
        streams.update(chance)
        if row["status"] == "SKIPPED":
            require(row.get("observation") is None and not row.get("alternatives")
                    and not row.get("proposedIndices") and row.get("skipReason") in
                    ("REQUESTED_ROOT_NOT_REACHED", "PRECOMMITTED_ROOT_INELIGIBLE"),
                    "Skipped origins cannot supply fitting labels.")
            skipped += 1
            continue
        require(row["status"] == "COLLECTED", "Unknown token origin completion status.")
        require(row["actualEpoch"] == row["requestedEpoch"]
                and row["rootDecision"] == row["originDecisions"],
                "Collected token root moved from its prospective position.")
        for alternative in row["alternatives"]:
            require([outcome["continuationSeed"] for outcome in alternative["outcomes"]] == chance,
                    "Token continuation worlds differ from the precommitted chance streams.")
            for outcome in alternative["outcomes"]:
                require(len(outcome["scores"]) == 4, "Token terminal outcome requires all four scores.")
                b.integer(outcome["decisions"], "Terminal decision count", row["rootDecision"], 12000)
        rows.append(validate_root(row, model, manifest["continuationReplicates"]))
    for name in ("dataset.json", "finished.json", "run.json"):
        pins[name] = sha(directory / name)
    provenance = {"source": str(directory), "signature": manifest["signature"],
                  "files": pins, "collected": len(rows), "skipped": skipped}
    if source.get("continuationProtocol") == ARENA_CONTINUATION:
        provenance["metadataValidatorSha256"] = sha(metadata.__file__)
    return model, rows, provenance


def select(values, row):
    baseline = row["observation"]["baselineIndex"]
    best = max(row["indices"], key=lambda index: values[index])
    return best if values[best] - values[baseline] > MARGIN else baseline


def paired_loss(values, row):
    baseline = row["observation"]["baselineIndex"]
    alternatives = [index for index in row["indices"] if index != baseline]
    return b.torch.stack([(values[index] - values[baseline] - row["targets"][index])**2
                          for index in alternatives]).mean()


def measure(actor, rows):
    require(bool(rows), "Evaluation requires independent collected origins.")
    errors, deltas, selected = [], [], []
    with b.torch.no_grad():
        for start in range(0, len(rows), 32):
            batch = rows[start:start+32]
            values = actor([row["observation"] for row in batch])
            for row, result in zip(batch, values):
                errors.append(float(paired_loss(result, row)))
                choice = select(result.tolist(), row)
                deltas.append(row["targets"][choice])
                selected.append({"origin": row["origin"], "index": choice,
                                 "baseline": row["observation"]["baselineIndex"],
                                 "observedDifference": row["targets"][choice]})
    margin = 1.96*statistics.stdev(deltas)/math.sqrt(len(deltas)) if len(deltas) > 1 else None
    positive = [value for value in deltas if value > 0]
    return {"origins": len(rows), "pairedMse": statistics.mean(errors),
            "observedMeanDifference": statistics.mean(deltas),
            "lower95": statistics.mean(deltas)-margin if margin is not None else None,
            "overrides": sum(row["index"] != row["baseline"] for row in selected),
            "positiveOrigins": len(positive),
            "largestPositiveFraction": max(positive)/sum(positive) if positive else None,
            "selections": selected}


def fit_one(model, rows, config, weight_decay, deadline):
    actor = q.ActionValue(model, "token")
    optimizer = b.torch.optim.Adam(actor.parameters(), lr=config.learningRate,
                                  weight_decay=weight_decay, foreach=False)
    rng = random.Random(config.seed)
    for _ in range(config.epochs):
        order = list(range(len(rows)))
        rng.shuffle(order)
        for start in range(0, len(order), config.batchSize):
            require(time.time() < deadline, "Original token fitting deadline expired; no model qualifies.")
            batch = [rows[index] for index in order[start:start+config.batchSize]]
            values = actor([row["observation"] for row in batch])
            loss = b.torch.stack([paired_loss(value, row) for value, row in zip(values, batch)]).mean()
            require(bool(b.torch.isfinite(loss)), "Token fitting produced a nonfinite loss.")
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            norm = b.torch.nn.utils.clip_grad_norm_(actor.parameters(), config.maxGradientNorm)
            require(bool(b.torch.isfinite(norm)), "Token fitting produced a nonfinite gradient.")
            optimizer.step()
    return actor


def fit(source, output, config):
    config.validate()
    output = Path(output).resolve()
    require(not output.exists(), "Use a fresh token fitting directory; incomplete fits never renew their budget.")
    started = time.time()
    model, rows, provenance = collection(source, config.seed)
    training = [row for row in rows if row["split"] == "TRAINING"]
    validation = [row for row in rows if row["split"] == "VALIDATION"]
    require(len(training) >= 2 and len(validation) >= 2,
            "Fitting needs at least two collected training and validation origins.")
    b.torch.set_num_threads(1)
    b.torch.use_deterministic_algorithms(True)
    output.mkdir(parents=True)
    deadline = started + config.maxSeconds
    identity = {"schemaVersion": 1, "kind": "token-order-paired-fit", "config": asdict(config),
                "source": provenance, "initializedModelSha256": b.digest(model),
                "learnerSha256": sha(__file__), "qBackendSha256": sha(q.__file__),
                "inputBackendSha256": sha(b.__file__), "pythonVersion": sys.version,
                "torchVersion": b.torch.__version__, "startedEpoch": started, "deadlineEpoch": deadline,
                "objective": "Mean across roots of mean squared paired terminal advantage errors across nonbaseline proposals.",
                "selection": "Lowest validation paired MSE including unchanged initializer; exact ties retain initializer then stronger regularization.",
                "minimumAdvantage": MARGIN, "selectionOnly": True, "productionActivation": False}
    b.atomic_json(output / "identity.json", identity)
    initial_validation = measure(q.ActionValue(model, "token"), validation)
    reports = []
    for index, decay in enumerate(config.weightDecay):
        actor = fit_one(model, training, config, decay, deadline)
        artifact = actor.artifact()
        report = {"index": index, "weightDecay": decay, "training": measure(actor, training),
                  "validation": measure(actor, validation)}
        b.atomic_json(output / f"model-{index}.json", artifact)
        report["modelSha256"] = sha(output / f"model-{index}.json")
        reports.append(report)
        b.atomic_json(output / f"fit-{index}.json", report)
        print(f"Completed fixed regularization candidate {index+1}/{len(config.weightDecay)}.", flush=True)
    eligible = [row for row in reports if row["validation"]["pairedMse"] < initial_validation["pairedMse"]]
    selected = min(eligible, key=lambda row: (row["validation"]["pairedMse"], -row["weightDecay"])) if eligible else None
    chosen = selected["validation"] if selected else initial_validation
    scaling = bool(selected and chosen["observedMeanDifference"] > 0 and chosen["overrides"] >= 8
                   and chosen["positiveOrigins"] >= 4 and chosen["largestPositiveFraction"] <= .5)
    report = {"schemaVersion": 1, "kind": "token-order-paired-fit", "identitySha256": sha(output / "identity.json"),
              "initializerValidation": initial_validation, "fits": reports, "selected": selected,
              "scalingDiagnosticPassed": scaling, "selectionOnly": True, "qualificationPassed": False,
              "humanStrengthVerified": False, "productionActivation": False,
              "elapsedSeconds": time.time()-started}
    if selected:
        b.atomic_json(output / "candidate.json", read(output / f"model-{selected['index']}.json"))
        report["candidateSha256"] = sha(output / "candidate.json")
    require(time.time() < deadline, "Original fitting allowance expired before completion.")
    b.atomic_json(output / "fit.json", report)
    b.atomic_json(output / "finished.json", {"kind": "token-order-paired-fit", "reportSha256": sha(output / "fit.json"),
        "artifacts": {path.name: sha(path) for path in sorted(output.iterdir()) if path.is_file()}})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    options = parser.parse_args()
    values = read(options.config)
    if "weightDecay" in values:
        values["weightDecay"] = tuple(values["weightDecay"])
    fit(options.source, options.output, FitConfig(**values))


if __name__ == "__main__":
    main()
