#!/usr/bin/env python3
"""Fixed-budget expansion-only evolution; selection is never production activation.

Four generations share fresh worlds within each generation. Mutation acts on the
eight pilot coordinates, uses frozen scales/bounds/seeds, and changes no other
ordinary model coefficient. A separate common-world archive race freezes at most
one candidate for independent confirmation and heldout opponents.
"""

import argparse
import copy
import json
import math
from pathlib import Path
import random
import sys

import automata_experiments as exp
import automata_policy_search as search
import expansion_arena as arena

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
VERSION = "expansion-evolution-1"
FEATURES = ("tokens", "progress", "reach", "commodity", "income", "risk",
            "action.expansion", "action.pass")
GENERATIONS = 4
POPULATION = 8
SIGMAS = (.25, .5, 1., 2.)
MAX_PROPOSALS = 4096
MAX_ARCHIVE = 11
COUNTS = {**{f"generation-{i}": 128 for i in range(GENERATIONS)},
          "selection": 512, "confirm": 2048, "heldout": 4096}
AUDIT_BANK_NAMES = {**{f"generation-{i}": f"generation{i}" for i in range(GENERATIONS)},
                    "selection": "selection", "confirm": "confirmation", "heldout": "heldout"}
CRITICAL_99 = 2.5758293035489004


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def weights(model):
    result = model.get("weights")
    require(model.get("schemaVersion") == 1 and isinstance(result, dict)
            and len(result) <= 4096 and all(isinstance(k, str) and 1 <= len(k) <= 120
            and finite(v) and abs(v) <= 1e6 for k, v in result.items()),
            "Expansion models require bounded finite schema-one weights.")
    return result


def model_key(model):
    # Explicit zero and absent zero have identical semantics after prior expansion.
    return json.dumps({k: float(v) for k, v in weights(model).items() if v != 0},
                      sort_keys=True, separators=(",", ":"), allow_nan=False)


def validate_normalization(normalization):
    require(set(normalization) == {"features", "temperature"}
            and len(normalization["features"]) == len(FEATURES)
            and all(finite(v) and v >= 1 for v in normalization["features"])
            and finite(normalization["temperature"]) and normalization["temperature"] >= 1,
            "Evolution requires the frozen training-only pilot normalization.")


def coordinates(model, baseline, normalization):
    validate_normalization(normalization)
    original, actual = weights(baseline), weights(model)
    require(all(actual.get(k, 0) == original.get(k, 0)
                for k in set(original) | set(actual) if k not in FEATURES),
            "Evolution may change only the eight declared expansion coefficients.")
    return [(actual.get(k, 0) - original.get(k, 0)) * scale / normalization["temperature"]
            for k, scale in zip(FEATURES, normalization["features"])]


def coordinate_bounds(initial, baseline, normalization):
    values = [coordinates(model, baseline, normalization) for model in initial]
    return [max([8.] + [abs(row[index]) for row in values]) for index in range(len(FEATURES))]


def export_model(values, baseline, normalization, bounds, target_rules):
    validate_normalization(normalization)
    require(len(values) == len(bounds) == len(FEATURES)
            and all(finite(b) and b >= 8 for b in bounds)
            and all(finite(v) and abs(v) <= b for v, b in zip(values, bounds)),
            "Mutation coordinates must stay within the precommitted finite bounds.")
    original = weights(baseline)
    result = {"schemaVersion": 1, "rulesVersion": target_rules,
              "dataVersion": baseline["dataVersion"], "weights": dict(original)}
    result["weights"].update({name: original.get(name, 0) + normalization["temperature"] * value / scale
                              for name, value, scale in zip(FEATURES, values, normalization["features"])})
    coordinates(result, baseline, normalization)
    return result


def population(baseline, parents, normalization, bounds, seed, target_rules):
    """Anchor, up to two exact parents, then fixed-sigma Gaussian children.

    A duplicate consumes its proposal attempt and RNG values, but not a child
    index. The child-index sigma/center schedule never reacts to game outcomes.
    Clipping is coordinatewise. Failure to make eight unique models within the
    fixed attempt ceiling fails closed without changing proposal mechanics.
    """
    require(type(seed) is int and seed >= 0 and len(parents) <= 2,
            "Each generation requires its declared seed and at most two prior elites.")
    anchor = export_model([0.] * len(FEATURES), baseline, normalization, bounds, target_rules)
    require(model_key(anchor) == model_key(baseline), "The anchor must preserve exact F1 weights.")
    require(len({model_key(model) for model in [anchor, *parents]}) == 1 + len(parents),
            "Initial elites must be distinct from one another and F1.")
    parent_coordinates = [[0.] * len(FEATURES)] + [coordinates(model, baseline, normalization) for model in parents]
    require(all(abs(v) <= bound + 1e-10 for row in parent_coordinates for v, bound in zip(row, bounds)),
            "An elite cannot enlarge the precommitted coordinate bounds.")
    # Undo only serialization-scale roundoff at a boundary; bounds never expand.
    parent_coordinates = [[max(-bound, min(bound, value)) for value, bound in zip(row, bounds)]
                          for row in parent_coordinates]
    result = [{"candidateIndex": index, "kind": "anchor" if index == 0 else "elite",
               "model": copy.deepcopy(model), "coordinates": parent_coordinates[index]}
              for index, model in enumerate([anchor, *parents])]
    seen = {model_key(row["model"]) for row in result}
    generator = random.Random(seed)
    child = 0
    for attempt in range(MAX_PROPOSALS):
        if len(result) == POPULATION:
            return result
        first = generator.randrange(len(parent_coordinates))
        parent_ids = [first]
        if child % 2 and len(parent_coordinates) > 1:
            second = generator.randrange(len(parent_coordinates) - 1)
            parent_ids.append(second + (second >= first))
        center = [sum(parent_coordinates[parent][index] for parent in parent_ids) / len(parent_ids)
                  for index in range(len(FEATURES))]
        sigma = SIGMAS[child % len(SIGMAS)]
        noise = [generator.gauss(0., 1.) for _ in FEATURES]
        values = [max(-bound, min(bound, mean + sigma * perturbation))
                  for mean, perturbation, bound in zip(center, noise, bounds)]
        model = export_model(values, baseline, normalization, bounds, target_rules)
        key = model_key(model)
        if key in seen:
            continue
        result.append({"candidateIndex": len(result), "kind": "mutation", "childIndex": child,
                       "attempt": attempt, "parentIndices": parent_ids, "sigma": sigma,
                       "center": center, "gaussianNoise": noise, "coordinates": values, "model": model})
        seen.add(key)
        child += 1
    raise ValueError("Generation exhausted its fixed proposal ceiling; no adaptive retry is permitted.")


def ranking(row):
    return (-row["metrics"]["rewardDifference"]["mean"],
            -row["metrics"]["winShareDifference"]["mean"], row["candidateIndex"])


def elites(rows):
    return sorted((row for row in rows if row["candidateIndex"] != 0
                   and row["metrics"]["rewardDifference"]["mean"] > 0), key=ranking)[:2]


def selected(rows):
    eligible = [row for row in rows if row["candidateIndex"] != 0 and all(
        row["metrics"][key]["mean"] > 0 for key in ("rewardDifference", "winShareDifference"))]
    return min(eligible, key=ranking) if eligible else None


def archive_append(archive, proposals):
    result, seen = list(archive), {model_key(read(path)) for path in archive}
    for path in proposals:
        key = model_key(read(path))
        if key not in seen:
            result.append(path)
            seen.add(key)
    require(len(result) <= MAX_ARCHIVE, "The frozen archive may contain at most eleven unique models.")
    return result


def qualification(metrics, stage):
    require(stage in ("confirm", "heldout"), "Qualification requires an independent final bank.")
    result = {name: dict(value, confidence=.99, lower99=(None if value["lower95"] is None else
              value["mean"] - (value["mean"] - value["lower95"]) / 1.96 * CRITICAL_99))
              for name, value in metrics.items()}
    passed = all(result[name]["count"] == COUNTS[stage]
                 and result[name]["lower99"] is not None and result[name]["lower99"] > 0
                 for name in ("rewardDifference", "winShareDifference"))
    return passed, result


def audited_reservations(audit):
    require(audit["allIntersectionsZero"] and set(audit["seedBanks"]) == set(AUDIT_BANK_NAMES.values()),
            "Evolution requires all seven independently audited world banks.")
    banks = {name: audit["seedBanks"][audit_name] for name, audit_name in AUDIT_BANK_NAMES.items()}
    seeds = []
    for index in range(GENERATIONS):
        proposal = banks[f"generation-{index}"]["proposalSeeds"]
        require(len(proposal) == 1 and type(proposal[0]) is int and proposal[0] >= 0,
                "Each generation requires exactly one independently audited proposal seed.")
        seeds.append(proposal[0])
    return banks, seeds


def source(protocol, model, bank):
    heldout = bank == "heldout"
    result = exp.arena_source(protocol["baseline"], protocol["heldoutOpponents" if heldout else "opponents"],
                              protocol["worldBanks"][bank]["nativeOffset"], COUNTS[bank], protocol["baseline"],
                              "CONFIRMATION" if bank in ("confirm", "heldout") else "SCREENING")
    result.update(experimentalRulesTransfer=protocol["experimentalRulesTransfer"],
                  frozenPurchase=protocol["frozenPurchase"],
                  expansionImitation={"candidateModel": str(model)})
    return result


def run_arena(protocol, directory, model, bank):
    # Shared verifier is packaged with the independently reviewed imitation screen.
    directory.mkdir(parents=True, exist_ok=False)
    source_path = directory / "source.json"
    document(source_path, source(protocol, model, bank))
    exp.native(protocol, "arena", source_path, directory / "arena")
    pairs, metrics = arena.verify_arena(protocol, source_path, directory / "arena", protocol["worldBanks"][bank])
    return metrics, pairs, source_path


def result(output, stage, passed, summary, metrics, paths):
    report = {"kind": VERSION, "stage": stage, "summary": summary, "metrics": metrics,
              "learningMethod": "bounded-whole-game-evolution",
              "qualificationPassed": passed if stage == "heldout" else False,
              "productionActivation": False, "humanStrengthVerified": False}
    path = output / (stage + "-report.json")
    document(path, report)
    return exp.stage_result(output, stage, passed, summary, metrics, [*paths, path])


def evolve(protocol, output):
    baseline = read(protocol["baseline"])
    normalization, bounds = protocol["normalization"], protocol["coordinateBounds"]
    parents = list(protocol["initialModels"])
    archive, generations, any_elite = [], [], False
    for generation in range(GENERATIONS):
        root = output / f"generation-{generation}"
        root.mkdir(parents=True, exist_ok=False)
        proposals = population(baseline, [read(path) for path in parents], normalization, bounds,
                               protocol["proposalSeeds"][generation], protocol["targetRulesVersion"])
        paths = []
        for proposal in proposals:
            path = root / f"model-{proposal['candidateIndex']}.json"
            document(path, proposal["model"])
            paths.append(str(path))
        document(root / "proposals.json", {"generation": generation, "proposalSeed": protocol["proposalSeeds"][generation],
            "parents": [{"path": path, "sha256": sha(path)} for path in parents], "bounds": bounds,
            "proposals": [dict({key: value for key, value in row.items() if key != "model"},
                model=paths[row["candidateIndex"]], modelSha256=sha(paths[row["candidateIndex"]])) for row in proposals]})
        if generation == 0:
            archive = archive_append([], [paths[0], *parents])
        rows, reference = [], None
        for index, path in enumerate(paths):
            metrics, pairs, _ = run_arena(protocol, root / f"evaluation-{index}", path, f"generation-{generation}")
            if reference is None:
                require(all(search.same_game(pair["candidate"], pair["incumbent"]) for pair in pairs),
                        "The exact F1 anchor failed candidate/incumbent gameplay parity.")
                reference = pairs
            else:
                arena.verify_common_baseline(pairs, reference)
            rows.append({"candidateIndex": index, "model": path, "modelSha256": sha(path), "metrics": metrics})
        winners = elites(rows)
        parents = [row["model"] for row in winners]
        archive = archive_append(archive, parents)
        any_elite = any_elite or bool(winners)
        receipt = {"generation": generation, "rows": rows, "eliteIndices": [row["candidateIndex"] for row in winners],
                   "archive": [{"path": path, "sha256": sha(path)} for path in archive]}
        document(root / "receipt.json", receipt)
        generations.append(receipt)
        print(f"Generation {generation + 1}/4 complete: {len(winners)} positive-reward elites; archive {len(archive)}.", flush=True)
    document(output / "archive.json", {"anyNonAnchorElite": any_elite,
             "models": [{"path": path, "sha256": sha(path)} for path in archive], "generations": generations})
    summary = ("Fixed four-generation evolution finished; freeze archive for independent selection." if any_elite else
               "No non-anchor positive-reward elite in any generation; stop negative without opening final banks.")
    return result(output, "evolve", any_elite, summary, {"generations": generations, "archiveSize": len(archive)},
                  [p for index in range(GENERATIONS) for p in (output / f"generation-{index}").rglob("*") if p.is_file()]
                  + [output / "archive.json"])


def select_archive(protocol, output):
    archive = read(output / "archive.json")
    require(archive["anyNonAnchorElite"] and 2 <= len(archive["models"]) <= MAX_ARCHIVE,
            "Selection requires the bounded archive and at least one generation with a non-anchor elite.")
    rows, reference = [], None
    for index, item in enumerate(archive["models"]):
        path = item["path"]
        require(sha(path) == item["sha256"], "Archived model bytes changed before independent selection.")
        metrics, pairs, _ = run_arena(protocol, output / "selection" / f"candidate-{index}", path, "selection")
        if reference is None:
            require(model_key(read(path)) == model_key(read(protocol["baseline"])) and
                    all(search.same_game(pair["candidate"], pair["incumbent"]) for pair in pairs),
                    "Archive selection must start with the exact F1 anchor.")
            reference = pairs
        else:
            arena.verify_common_baseline(pairs, reference)
        rows.append({"candidateIndex": index, "model": path, "modelSha256": item["sha256"], "metrics": metrics})
    winner = selected(rows)
    document(output / "selected.json", {"candidate": winner, "rows": rows})
    return result(output, "select", winner is not None,
                  "One candidate frozen for independent confirmation." if winner else "No archive candidate has both positive means; final banks remain unopened.",
                  {"candidate": winner, "rows": rows}, [p for p in (output / "selection").rglob("*") if p.is_file()] + [output / "selected.json"])


def final(protocol, output, stage):
    selection = read(output / "selected.json")
    candidate = selection["candidate"]
    require(candidate is not None and selected(selection["rows"]) == candidate
            and sha(candidate["model"]) == candidate["modelSha256"], "Final bank requires the unchanged selected model.")
    if stage == "heldout":
        confirmation = read(output / "confirm-report.json")
        require(confirmation["metrics"]["passed"] and qualification(confirmation["metrics"]["estimates"], "confirm")[0]
                and confirmation["metrics"]["candidateSha256"] == candidate["modelSha256"],
                "Heldout requires the same candidate's passed independent confirmation.")
    metrics, pairs, source_path = run_arena(protocol, output / stage, candidate["model"], stage)
    indices = sorted({0, len(pairs) // 3, 2 * len(pairs) // 3, len(pairs) - 1})
    receipts = arena.replays(protocol, source_path, output / stage / "arena", output / "replays" / stage, pairs, indices)
    require(len(receipts) == 8, "Every final bank requires eight canonical replay receipts.")
    passed, estimates = qualification(metrics, stage)
    payload = {"passed": passed, "candidateSha256": candidate["modelSha256"], "estimates": estimates, "replays": receipts}
    return result(output, stage, passed, f"{stage} {'passed' if passed else 'did not pass'} the dual 99% lower-bound requirement.",
                  payload, [p for p in (output / stage).rglob("*") if p.is_file()] +
                  [p for p in (output / "replays" / stage).rglob("*") if p.is_file()])


def validate_protocol(protocol):
    require(protocol.get("kind") == VERSION and protocol.get("schemaVersion") == 1
            and protocol["learningMethod"] == "bounded-whole-game-evolution"
            and protocol["workers"] == 6 and protocol["maximumSeconds"] == 28800
            and protocol["featureNames"] == list(FEATURES) and protocol["generations"] == GENERATIONS
            and protocol["populationSize"] == POPULATION and protocol["sigmas"] == list(SIGMAS)
            and protocol["maximumProposalAttempts"] == MAX_PROPOSALS
            and len(protocol["opponents"]) == 4 and len(protocol["heldoutOpponents"]) == 2,
            "Evolution protocol changed its fixed population, resource, feature or rival contract.")
    require(set(protocol["worldBanks"]) == set(COUNTS) and len(protocol["proposalSeeds"]) == GENERATIONS,
            "Evolution requires all seven predeclared world banks and four proposal seeds.")
    audited_banks, audited_seeds = audited_reservations(read(protocol["seedAudit"]))
    require(protocol["worldBanks"] == audited_banks and protocol["proposalSeeds"] == audited_seeds,
            "Evolution must use exactly its independent seed audit, including proposal streams.")
    banks = {}
    for name, count in COUNTS.items():
        bank = protocol["worldBanks"][name]
        require(bank["count"] == count and bank["seeds"] == exp.arena_seeds(bank["nativeOffset"], count),
                "Evolution world bank differs from its exact prospective origins.")
        banks[name] = bank["seeds"]
    exp.validate_banks(banks)
    all_worlds = {seed for values in banks.values() for seed in values}
    require(len(set(protocol["proposalSeeds"])) == GENERATIONS and not all_worlds.intersection(protocol["proposalSeeds"]),
            "Proposal streams must be distinct and separate from game worlds.")
    require(Path(sys.executable).resolve() == Path(protocol["python"]["executable"])
            and sys.version == protocol["python"]["version"], "Frozen interpreter identity changed.")
    for path, digest in protocol["frozenFiles"].items():
        require(sha(path) == digest, "Frozen evolution input changed: " + path)
    baseline = read(protocol["baseline"])
    require(len(protocol["initialModels"]) <= 2 and protocol["coordinateBounds"] == coordinate_bounds(
        [read(path) for path in protocol["initialModels"]], baseline, protocol["normalization"]),
        "Coordinate bounds must derive once from frozen accepted initial models.")


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage", choices=("evolve", "select", "confirm", "heldout"), required=True)
    options = parser.parse_args(arguments)
    protocol = read(options.protocol)
    validate_protocol(protocol)
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if options.stage == "evolve":
        return evolve(protocol, output)
    if options.stage == "select":
        return select_archive(protocol, output)
    return final(protocol, output, options.stage)


if __name__ == "__main__":
    main()
