#!/usr/bin/env python3
"""One bounded token-order pilot: prospective roots, fixed fitting grid, fresh paired gates.

The controller owns all clocks and attempts. Purchase actors, ordinary policies and
world banks never change. Proposal and feature changes remain confounded with the
learner until a separate ablation; no result activates a production policy.
"""

import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import automata_campaign as controller
import automata_evolution as evolution
import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as purchase
import purchase_storage as storage


read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
F1 = "0c24593a456816c9b6616b6ade2aa7b74ac3c41f0cbd0745b5b912675caa835b"
P48 = "eaf40a734c2c48e4a38c7e088b16783f5eb0ba8f759ae4c25caabeab45793296"
TRANSFER = purchase.RULES_TRANSFER
PROFILES = {
    "smoke": {"trainingRoots": 2, "validationRoots": 2, "screeningPairs": 4,
              "confirmationPairs": 4, "robustnessPairs": 4},
    "pilot": {"trainingRoots": 256, "validationRoots": 128, "screeningPairs": 512,
              "confirmationPairs": 2048, "robustnessPairs": 4096},
}
STAGES = ("collect", "fit", "screen", "confirm", "robustness")
CEILINGS = (7200, 1800, 1800, 7200, 10800)
FIT = {"epochs": 32, "batchSize": 32, "learningRate": .001, "maxGradientNorm": .5,
       "weightDecay": [0., .0001, .001], "maxSeconds": 1800.}
NOTE = ("Rules-4 evidence establishes historical initialization only. Current-rules gates compare against the transferred F1/P48 baseline, "
        "not the browser fallback. No activation or human-strength claim. Proposal/context and learning require a separate attribution ablation.")


def seed_banks(seed, profile, audit):
    require(type(seed) is int and 0 <= seed <= 99998999, "Token seed must leave room for all frozen banks and fitting seed.")
    fields = ("trainingRoots", "validationRoots", "screeningPairs", "confirmationPairs", "robustnessPairs")
    banks = {name: exp.arena_seeds(seed + index, profile[field])
             for index, (name, field) in enumerate(zip(("training", "validation", "screen", "confirm", "robustness"), fields))}
    exp.validate_banks(banks)
    prefix = "smoke-" if profile == PROFILES["smoke"] else ""
    audited = audit.get("seedBanks", {})
    require(audit.get("allIntersectionsZero") is True and all(
        audited.get(prefix + name, {}).get("seeds") == banks[bank]
        and audited[prefix + name].get("nativeOffset") == seed + index
        and audited[prefix + name].get("count") == len(banks[bank])
        for index, (name, bank) in enumerate(zip(
            ("training", "validation", "screening", "confirmation", "heldout"), banks))),
        "Use the exact five pre-audited world banks with all historical intersections zero.")
    # Root continuations have a separate deterministic chance-stream namespace.
    banks["continuations"] = [(world << 3) ^ replicate ^ 0x6f20a5319d
                              for name in ("training", "validation") for world in banks[name] for replicate in range(2)]
    exp.validate_banks(banks)
    reserved = set(audit.get("reservedSeeds", []))
    offsets = audit.get("conservativelyExpandedOffsets", [])
    count = audit.get("conservativePairsPerOffset", 10000)
    require((reserved or offsets) and type(count) is int and 1 <= count <= 10000
            and all(type(value) is int for value in reserved)
            and all(type(value) is int and 0 <= value <= 100000000 for value in offsets),
            "Use a nonempty comprehensive frozen historical seed audit.")
    by_residue = {}
    for offset in offsets:
        by_residue.setdefault(offset % 8191, []).append(offset)
    for values in banks.values():
        require(not reserved.intersection(values) and not any(
            0 <= (world + 1000000000 - offset) // 8191 < count
            for world in values for offset in by_residue.get((world + 1000000000) % 8191, ())),
            "Token worlds overlap a historical or reserved bank.")
    return banks


def qualified_hybrid(root):
    """Verify historical initialization evidence only; import no current-rules qualification."""
    root = Path(root).expanduser().resolve(strict=True)
    campaign, protocol, state = [read(root / name) for name in
                               ("campaign.json", "protocol.json", "run/campaign-state.json")]
    require(state.get("status") == "completed" and protocol.get("profile") == "search"
            and all(state["stages"][name]["status"] == "passed" for name in ("train", "confirm", "robustness")),
            "Use the completed qualified F1/P48 purchase campaign.")
    for path, expected in campaign["inputs"].items():
        require(sha(root / path) == expected, "Qualified hybrid input changed: " + path)
    train_path = root / "run/results/train.json"
    require(sha(train_path) == state["stages"]["train"]["resultSha256"], "Qualified training receipt changed.")
    archived, archive_files = storage.archived_parent_rows(root, read(train_path))
    for stage in ("train", "confirm", "robustness"):
        result = root / "run/results" / (stage + ".json")
        require(sha(result) == state["stages"][stage]["resultSha256"], "Qualified stage receipt changed.")
        for name, expected in read(result)["artifacts"].items():
            path = root / "run" / name
            require((path.is_file() and sha(path) == expected) or
                    (not path.exists() and stage == "train" and archived.get(name) == expected),
                    "Qualified hybrid evidence changed: " + name)
    selection = read(root / "run/selection.json")
    require(sha(root / "run/selection.json") == read(train_path)["artifacts"]["selection.json"]
            and selection["selected"] and sha(selection["policy"]) == selection["policySha256"] == P48
            and sha(protocol["baseline"]) == F1, "Freeze exactly the qualified F1 ordinary model and P48 purchase actor.")
    for stage in ("confirm", "robustness"):
        report = root / "run" / (stage + "-report.json")
        require(sha(report) == read(root / "run/results" / (stage + ".json"))["artifacts"][report.name]
                and read(report)["metrics"]["policySha256"] == P48
                and protocol.get("qualificationConfidence") == .99
                and evolution.qualification_passes(protocol, read(report)["metrics"], stage),
                "Both independent parent qualification gates must bind P48.")
    evidence = {str(path): sha(path) for path in [root / "campaign.json", root / "protocol.json",
                root / "run/campaign-state.json", root / "run/selection.json", train_path, *archive_files]}
    return protocol, selection["policy"], evidence


def collection_source(protocol):
    source = exp.arena_source(protocol["baseline"], protocol["opponents"], protocol["seed"],
                              protocol["trainingRoots"], protocol["baseline"], "SCREENING")
    return dict(source, **purchase.transfer_source(protocol), frozenPurchase=protocol["frozenPurchase"], trainingRoots=protocol["trainingRoots"],
                validationRoots=protocol["validationRoots"], continuationReplicates=2)


def arena_source(protocol, model, stage):
    offset, field = {"screen": (2, "screeningPairs"), "confirm": (3, "confirmationPairs"),
                     "robustness": (4, "robustnessPairs")}[stage]
    rivals = protocol["robustOpponents" if stage == "robustness" else "opponents"]
    source = exp.arena_source(protocol["baseline"], rivals, protocol["seed"] + offset,
        protocol[field], protocol["baseline"], "SCREENING" if stage == "screen" else "CONFIRMATION")
    return dict(source, **purchase.transfer_source(protocol), frozenPurchase=protocol["frozenPurchase"], tokenOrder={"candidateModel": str(model)})


def prepare(options):
    require(options.experimental_rules_transfer, "Rules-4 models are historical initialization only; explicitly request experimental rules transfer.")
    source = options.from_campaign.expanduser().resolve(strict=True)
    parent, actor, evidence = qualified_hybrid(source)
    root, runtime = options.output.expanduser().resolve(), options.runtime.expanduser().resolve(strict=True)
    require(not root.exists() or not any(root.iterdir()), "Prepare a new empty token pilot directory.")
    require(all(root != path and root not in path.parents and path not in root.parents for path in (source, runtime)),
            "Token pilot output must be separate from source and runtime.")
    require((runtime / "lib/training.jar").is_file(), "Use the verified complete token-order runtime.")
    require(1 <= options.workers <= (os.cpu_count() or 1), "Token workers must fit the local host.")
    maximum = options.maximum_seconds or (1800 if options.profile == "smoke" else 28800)
    require(math.isfinite(maximum) and 300 <= maximum <= 28800, "Token campaign allowance must be five minutes through eight hours.")
    audit = read(options.seed_audit)
    banks = seed_banks(options.seed, PROFILES[options.profile], audit)
    scripts = Path(__file__).resolve().parent
    numerical = purchase.numerical_identity(options.numerical_python, scripts / "purchase_policy.py")
    java = exp.java_identity(parent["java"]["executable"])
    require(java["files"] == parent["java"]["files"], "Qualified JDK bytes changed.")
    hashes = exp.distribution_hashes(runtime)
    root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(runtime, root / "runtime", symlinks=False)
    require(hashes == exp.distribution_hashes(runtime) == exp.distribution_hashes(root / "runtime"), "Runtime changed during freezing.")
    provenance = []
    for name in (Path(__file__).name, "automata_campaign.py", "automata_experiments.py", "automata_evolution.py",
                 "automata_policy_search.py", "automata_purchase_continuation.py", "purchase_storage.py",
                 "purchase_policy.py", "purchase_policy_toy.py", "territory_value.py", "token_order_policy.py",
                 "token_order_metadata.py", "token_order_fixed_reference.py", "train-opponents.py"):
        exp.copy_input(scripts / name, root / "scripts" / name, provenance)
    def copied(path, name):
        return str(exp.copy_input(path, root / "inputs" / name, provenance))
    prior, policy = copied(parent["prior"], "prior-f0.json"), copied(actor, "purchase-p48.json")
    protocol = {"schemaVersion": 1, "kind": "automata-token-order", "profile": options.profile,
        "preparedAt": datetime.now(timezone.utc).isoformat(), "configuration": exp.CONFIGURATION,
        **TRANSFER, **purchase.transfer_source({"experimentalRulesTransfer": TRANSFER}),
        "dataVersion": read(parent["baseline"])["dataVersion"], "historicalQualificationImported": False,
        "baseline": copied(parent["baseline"], "ordinary-f1.json"), "frozenPurchase": {"baseline": prior, "policy": policy},
        "frozenPurchaseSha256": {"baseline": sha(prior), "policy": sha(policy)},
        "opponents": [copied(path, f"opponent-{index}.json") for index, path in enumerate(parent["opponents"])],
        "robustOpponents": [copied(path, f"heldout-{index}.json") for index, path in enumerate(parent["robustOpponents"])],
        "java": java, "numerical": numerical, "runtime": str(root / "runtime"), "scripts": str(root / "scripts"),
        "python": {"executable": str(Path(sys.executable).resolve()), "version": sys.version, "sha256": sha(sys.executable)},
        "workers": options.workers, "heapMiB": 1536, "seed": options.seed, "seedBanks": banks,
        "seedAudit": copied(options.seed_audit, "seed-audit.json"), "qualificationConfidence": .99,
        "continuationReplicates": 2, **PROFILES[options.profile], "productionActivation": False,
        "humanStrengthVerified": False, "causalAttributionVerified": False, "scopeNote": NOTE}
    train_keys, heldout_keys = [[evolution.model_key(path) for path in protocol[key]] for key in ("opponents", "robustOpponents")]
    require(len(train_keys) == len(set(train_keys)) and len(heldout_keys) == len(set(heldout_keys))
            and train_keys and heldout_keys and not set(train_keys).intersection(heldout_keys), "Freeze distinct original and held-out ordinary pools.")
    config = dict(FIT, seed=options.seed + 1000)
    if options.profile == "smoke":
        config.update(epochs=2, maxSeconds=min(120., maximum / 16))
    document(root / "fit-config.json", config)
    protocol["fitConfig"] = str(root / "fit-config.json")
    protocol["collectionSource"] = str(root / "collection-source.json")
    document(root / "collection-source.json", collection_source(protocol))
    document(root / "protocol.json", protocol)
    document(root / "provenance.json", {"historicalInitializationSource": str(source), "sourceEvidence": evidence,
             "copiedInputs": provenance, "runtimeSha256": hashes, "scopeNote": NOTE})
    inputs = {str(path.relative_to(root)): sha(path) for path in root.rglob("*") if path.is_file()}
    inputs.update(java["files"])
    inputs.update(numerical["files"])
    inputs[protocol["python"]["executable"]] = protocol["python"]["sha256"]
    spec = {"schemaVersion": 1, "kind": "automata-campaign", "inputs": inputs,
        "limits": {"maximumSeconds": maximum, "maximumDataBytes": 4 * exp.GIB,
                   "maximumRssBytes": 4 * exp.GIB, "minimumFreeBytes": 2 * exp.GIB},
        "stages": [{"id": name, "dependsOn": [] if index == 0 else [STAGES[index - 1]],
            "maximumSeconds": ceiling * maximum / 28800, "maximumAttempts": 1 if name == "fit" else 2,
            "result": f"results/{name}.json", "command": [protocol["python"]["executable"], "-B",
            str(root / "scripts" / Path(__file__).name), "stage", "--protocol", str(root / "protocol.json"),
            "--output", "{output}", "--stage", name]} for index, (name, ceiling) in enumerate(zip(STAGES, CEILINGS))]}
    controller.validate_spec(spec, root / "run")
    document(root / "campaign.json", spec)
    print(root / "campaign.json")
    return spec


def native_once(protocol, mode, source, directory):
    pattern = "origin-*.json" if mode == "token-order-collect" else "pair-*.json"
    existing = {path: sha(path) for path in directory.glob(pattern)}
    existing.update({directory / name: sha(directory / name) for name in ("started-at.txt", "deadline-at.txt")
                     if (directory / name).exists()})
    if not (directory / "finished.json").exists():
        exp.native(protocol, mode, source, directory, extra=("--workers", str(protocol["workers"])) if mode == "token-order-collect" else ())
    require(all(sha(path) == digest for path, digest in existing.items()), "Native resume changed a cached world or original clock.")


def validate_collection(protocol, output):
    # This entry runs only in the frozen Torch environment; the fitter owns all root semantics.
    import token_order_policy as fitter
    model, rows, provenance = fitter.collection(output / "collection", read(protocol["fitConfig"])["seed"])
    source = read(output / "collection/source.json")
    require(source == collection_source(protocol), "Token collector source differs from frozen F1/F0/P48 roles or banks.")
    native = read(output / "collection/run.json")
    require(native["workers"] == protocol["workers"] and native["sourceSha256"] == sha(protocol["collectionSource"]),
            "Native collection worker count or source identity changed.")
    require({path.name for path in (output / "collection").glob("origin-*.json")} ==
            {f"origin-{index}.json" for index in range(protocol["trainingRoots"] + protocol["validationRoots"])},
            "Token collection must contain exactly its prospective origins, including skips.")
    verify_environment(protocol, source, output / "collection", (native, read(output / "collection/dataset.json")), "initial.json")
    document(output / "initializer.json", model)
    document(output / "collection-validation.json", {"provenance": provenance,
        "collected": {name: sum(row["split"] == name for row in rows) for name in ("TRAINING", "VALIDATION")},
        "initializerSha256": sha(output / "initializer.json"), "initializedModelDigest": fitter.b.digest(model)})


def collection_check(output):
    check = read(output / "collection-validation.json")
    require(sha(output / "initializer.json") == check["initializerSha256"], "Token initializer changed.")
    for name, expected in check["provenance"]["files"].items():
        require(sha(output / "collection" / name) == expected, "Completed token root evidence changed: " + name)
    return check


def result(protocol, output, stage, passed, summary, metrics, artifacts):
    qualified = stage == "robustness" and passed and protocol["profile"] != "smoke"
    report = {"stage": stage, "summary": summary, "metrics": metrics, "qualificationPassed": qualified,
              "productionActivation": False, "humanStrengthVerified": False, "causalAttributionVerified": False,
              "scopeNote": NOTE, "profile": protocol["profile"], **TRANSFER, "historicalQualificationImported": False}
    path = output / (stage + "-report.json")
    document(path, report)
    return exp.stage_result(output, stage, passed, summary, metrics, [*artifacts, path])


def collect_stage(protocol, output):
    source = Path(protocol["collectionSource"])
    require(read(source) == collection_source(protocol), "Frozen token collection source changed.")
    native_once(protocol, "token-order-collect", source, output / "collection")
    command = [protocol["numerical"]["executable"], "-B", str(Path(protocol["scripts"]) / Path(__file__).name),
               "validate-collection", "--protocol", str(Path(protocol["scripts"]).parent / "protocol.json"), "--output", str(output)]
    with (output / "collection-validation.log").open("ab") as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                   timeout=exp.remaining_seconds(), check=False)
    require(completed.returncode == 0, "Strict token collection validation failed; inspect collection-validation.log.")
    check = collection_check(output)
    enough = all(value >= 2 for value in check["collected"].values())
    passed = enough or protocol["profile"] == "smoke"
    artifacts = [path for path in (output / "collection").rglob("*") if path.is_file()]
    return result(protocol, output, "collect", passed, "Prospective roots verified." if enough else
                  "Insufficient collected roots; no replenishment. Smoke may use the unchanged initializer." if passed else
                  "Insufficient collected roots; stop without replenishment.", check,
                  [*artifacts, output / "initializer.json", output / "collection-validation.json"])


def verify_fit(protocol, output):
    directory = output / "fit"
    require((directory / "finished.json").is_file(), "A complete fit receipt is mandatory; candidate.json alone is insufficient.")
    finished = read(directory / "finished.json")
    require(finished["kind"] == "token-order-paired-fit" and finished["reportSha256"] == sha(directory / "fit.json")
            and finished["artifacts"] == {path.name: sha(path) for path in directory.iterdir()
                                         if path.is_file() and path.name != "finished.json"}, "Completed token fit artifacts changed.")
    report, identity, config, check = read(directory / "fit.json"), read(directory / "identity.json"), read(protocol["fitConfig"]), collection_check(output)
    require(report["identitySha256"] == sha(directory / "identity.json") and identity["config"] == config
            and identity["source"] == check["provenance"] and identity["initializedModelSha256"] == check["initializedModelDigest"]
            and identity["learnerSha256"] == sha(Path(protocol["scripts"]) / "token_order_policy.py")
            and identity["qBackendSha256"] == sha(Path(protocol["scripts"]) / "territory_value.py")
            and identity["inputBackendSha256"] == sha(Path(protocol["scripts"]) / "purchase_policy.py")
            and identity["pythonVersion"] == protocol["numerical"]["pythonVersion"]
            and identity["torchVersion"] == protocol["numerical"]["torchVersion"]
            and identity["minimumAdvantage"] == .02 and report["qualificationPassed"] is False
            and identity["deadlineEpoch"] == identity["startedEpoch"] + config["maxSeconds"]
            and 0 <= report["elapsedSeconds"] < config["maxSeconds"],
            "Token fit identity differs from its frozen data, objective or numerical environment.")
    require(len(report["fits"]) == len(config["weightDecay"]), "Complete the entire fixed fitting grid before selection.")
    for index, row in enumerate(report["fits"]):
        require(row == read(directory / f"fit-{index}.json") and row["index"] == index
                and row["weightDecay"] == config["weightDecay"][index]
                and row["modelSha256"] == sha(directory / f"model-{index}.json"), "Token fitting grid changed.")
    eligible = [row for row in report["fits"] if row["validation"]["pairedMse"] < report["initializerValidation"]["pairedMse"]]
    chosen = min(eligible, key=lambda row: (row["validation"]["pairedMse"], -row["weightDecay"])) if eligible else None
    require(report["selected"] == chosen, "Token selection must use the fixed validation-MSE rule.")
    values = chosen["validation"] if chosen else None
    scaling = bool(values and values["observedMeanDifference"] > 0 and values["overrides"] >= 8
                   and values["positiveOrigins"] >= 4 and values["largestPositiveFraction"] <= .5)
    require(report["scalingDiagnosticPassed"] == scaling, "Token scaling evidence differs from the declared gate.")
    if chosen:
        require(sha(directory / "candidate.json") == report["candidateSha256"] == chosen["modelSha256"], "Selected token candidate changed.")
    return report, bool(chosen and scaling)


def fit_stage(protocol, output):
    check = collection_check(output)
    enough = all(value >= 2 for value in check["collected"].values())
    report, admitted = None, False
    if enough:
        directory = output / "fit"
        if not (directory / "finished.json").exists():
            require(not directory.exists(), "An interrupted token fit cannot restart or renew its budget.")
            with (output / "fit.log").open("ab") as log:
                command = [protocol["numerical"]["executable"], "-B", str(Path(protocol["scripts"]) / "token_order_policy.py"),
                           "--source", str(output / "collection"), "--output", str(directory), "--config", protocol["fitConfig"]]
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                           timeout=exp.remaining_seconds(), check=False)
            require(completed.returncode == 0, "Token fitting failed; no partial candidate may advance.")
        report, admitted = verify_fit(protocol, output)
    smoke = protocol["profile"] == "smoke"
    require(enough or smoke, "Pilot fitting needs both collected banks; roots cannot be replenished.")
    selection = {"admitted": admitted, "smokeInitializer": smoke and not admitted,
                 "fitReportSha256": sha(output / "fit/fit.json") if report else None, "productionActivation": False}
    if admitted or smoke:
        model = output / "fit/candidate.json" if admitted else output / "initializer.json"
        selected = evolution.freeze_copy(model, output / "selected/token-order.json")
        selection.update(model=str(selected), modelSha256=sha(selected))
        for stage in STAGES[2:]:
            document(output / (stage + "-source.json"), arena_source(protocol, selected, stage))
    document(output / "selection.json", selection)
    artifacts = [path for directory in (output / "fit", output / "selected") for path in directory.rglob("*") if path.is_file()]
    artifacts += [path for path in output.glob("*-source.json")] + [output / "selection.json"]
    return result(protocol, output, "fit", admitted or smoke, "Selected token model passed validation and scaling admission." if admitted else
                  "Smoke initializer plumbing only; no learned model admitted." if smoke else
                  "No model passed both validation and scaling admission; final banks remain unopened.", selection, artifacts)


def verify_environment(protocol, source, directory, reports, candidate_snapshot="candidate.json"):
    """Bind experimental target rules to unchanged historical initialization bytes."""
    roles = {"candidate": source["candidate"], "incumbent": source["incumbent"],
             **{f"opponent-{index}": path for index, path in enumerate(source["opponents"])}}
    expected = {role: {"path": str(Path(path).resolve()), "sha256": sha(path),
                      "snapshot": candidate_snapshot if role == "candidate" else role + ".json"}
                for role, path in roles.items()}
    for report in reports:
        purchase.verify_transfer_metadata(source, report)
        require(report.get("rulesVersion") == protocol["targetRulesVersion"]
                and report.get("dataVersion") == protocol["dataVersion"]
                and report.get("ordinaryModelSources") == expected,
                "Native target rules or original ordinary model identities changed.")
    require(all(sha(directory / item["snapshot"]) == item["sha256"] for item in expected.values()),
            "Rules transfer must preserve original ordinary model snapshot bytes.")
    evolution.verify_purchase(protocol, directory, reports)


def verify_overlay(protocol, source, directory, reports):
    verify_environment(protocol, source, directory, reports)
    expected = {key: {"path": str(Path(path).resolve()), "sha256": sha(path),
                      "snapshot": "token-order-" + key.removesuffix("Model") + "-model.json"}
                for key, path in source["tokenOrder"].items()}
    require(all(report.get("tokenOrder") == expected for report in reports), "Native token roles changed.")
    require(all(sha(directory / item["snapshot"]) == item["sha256"] for item in expected.values()), "Native token model snapshot changed.")


def verify_arena(protocol, source_path, directory):
    source = read(source_path)
    exp.verify_arena(directory, source_path)
    native, report, state = [read(directory / name) for name in ("run.json", "evaluation.json", "state.json")]
    require(native["workers"] == protocol["workers"] and native["signature"] == state["signature"]
            and native["source"] == str(source_path) and state["phase"] == "complete", "Native token arena identity changed.")
    verify_overlay(protocol, source, directory, (native, report))
    count = source["pairsPerConfiguration"]
    require({path.name for path in directory.glob("pair-*.json")} == {f"pair-{index}.json" for index in range(count)}, "Token arena pair coverage changed.")
    pairs = [read(directory / f"pair-{index}.json") for index in range(count)]
    seeds = exp.arena_seeds(source["seed"], count)
    for index, pair in enumerate(pairs):
        search.validate_pair(pair, index, seeds[index], len(source["opponents"]))
    metrics = search.comparison(pairs)
    require(report["overall"]["pairs"] == count, "Token arena aggregate count changed.")
    search.verify_metrics(report["overall"], metrics)
    search.verify_metrics(report["byConfiguration"][protocol["configuration"]], metrics)
    return metrics, pairs


def replays(protocol, output, stage, source_path, pairs):
    source = read(source_path)
    indices = sorted({0, len(pairs) // 3, 2 * len(pairs) // 3, len(pairs) - 1})
    receipts = []
    for index in indices:
        for side in ("candidate", "incumbent"):
            directory = output / "replays" / stage / f"{index}-{side}"
            pair_path = output / stage / f"pair-{index}.json"
            if not (directory / "finished.json").exists():
                directory.mkdir(parents=True, exist_ok=True)
                remaining = exp.remaining_seconds()
                command = [protocol["java"]["executable"], "-Xmx1536m", f'-XX:ActiveProcessorCount={protocol["workers"]}',
                    "-XX:ParallelGCThreads=1", "-XX:ConcGCThreads=1", "-cp", str(Path(protocol["runtime"]) / "lib/*"),
                    "dev.aragos.aor.training.TrainingMainKt", "--mode", "strategy-replay", "--source", str(source_path),
                    "--output", str(directory), "--hours", str(min(1., remaining / 3600)), "--workers", "1",
                    "--index", str(index), "--side", side, "--pair", str(pair_path)]
                with directory.with_suffix(".log").open("ab") as log:
                    completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=remaining, check=False)
                require(completed.returncode == 0, "Token canonical replay failed.")
            record, native, state = [read(directory / name) for name in ("replay.json", "run.json", "state.json")]
            require(record["replayVerified"] and record["historicalPairVerified"] and record["index"] == index
                    and record["side"] == side.upper() and native["signature"] == state["signature"]
                    and all(record[key] == pairs[index][key] for key in ("configuration", "seed", "seat", "opponentIndices"))
                    and all(native[key] == record[key] for key in ("index", "configuration", "seed", "seat"))
                    and native["workers"] == 1 and native["side"] == side
                    and native["source"] == str(source_path) and native["recordedPair"] == str(pair_path)
                    and state["phase"] == "complete" and search.same_game(record["game"], pairs[index][side]),
                    "Canonical replay differs from the frozen paired game.")
            verify_overlay(protocol, source, directory, (native, state))
            receipts.append({"index": index, "side": side, "replaySha256": sha(directory / "replay.json"),
                             "finishedSha256": sha(directory / "finished.json")})
    return receipts


def qualification_passes(protocol, metrics, stage):
    count = protocol["confirmationPairs" if stage == "confirm" else "robustnessPairs"]
    values = evolution.qualification_estimates(protocol, metrics)
    return protocol["profile"] != "smoke" and all(values[name]["count"] == count
        and values[name]["lowerConfidence"] is not None and values[name]["lowerConfidence"] > 0
        for name in ("winShareDifference", "rewardDifference"))


def arena_stage(protocol, output, stage):
    selection = read(output / "selection.json")
    smoke = protocol["profile"] == "smoke"
    require(selection["admitted"] or (smoke and selection["smokeInitializer"]), "A token model must pass fitting admission before fresh arena worlds.")
    if selection["admitted"]:
        report, admitted = verify_fit(protocol, output)
        require(admitted and sha(output / "fit/fit.json") == selection["fitReportSha256"]
                and report["candidateSha256"] == selection["modelSha256"], "Frozen selected fitting evidence changed.")
    require(sha(selection["model"]) == selection["modelSha256"], "Selected token model changed.")
    if stage != "screen":
        previous = "screen" if stage == "confirm" else "confirm"
        receipt, prior = read(output / "results" / (previous + ".json")), read(output / (previous + "-report.json"))
        require(receipt["status"] == "passed" and receipt["artifacts"][previous + "-report.json"] == sha(output / (previous + "-report.json"))
                and prior["metrics"]["modelSha256"] == selection["modelSha256"]
                and (smoke or (search.search_passes(prior["metrics"]) if previous == "screen" else
                               qualification_passes(protocol, prior["metrics"], "confirm"))),
                "Fresh final worlds require this frozen model's passed preceding gate.")
    source, directory = output / (stage + "-source.json"), output / stage
    require(read(source) == arena_source(protocol, selection["model"], stage), "Frozen token arena source changed.")
    native_once(protocol, "arena", source, directory)
    metrics, pairs = verify_arena(protocol, source, directory)
    if selection["smokeInitializer"]:
        require(all(search.same_game(pair["candidate"], pair["incumbent"]) for pair in pairs), "Zero-output smoke initializer must reproduce the unchanged incumbent.")
    replay_receipts = replays(protocol, output, stage, source, pairs)
    qualified = not smoke and (search.search_passes(metrics) if stage == "screen" else
                               qualification_passes(protocol, metrics, stage))
    if stage != "screen":
        metrics = evolution.qualification_estimates(protocol, metrics)
    metrics.update(modelSha256=selection["modelSha256"], qualificationConfidence=.99,
                   qualificationEvidence=stage != "screen" and not smoke, replays=replay_receipts)
    summary = "Smoke paired worlds and canonical replays verified; never qualifies." if smoke else (
        "Screening admitted the frozen model on both positive means; selection evidence only." if stage == "screen" and qualified else
        "Both independent 99% lower-bound gates passed." if qualified else "Token model rejected; dependent banks remain unopened.")
    artifacts = [path for tree in (directory, output / "replays" / stage) for path in tree.rglob("*") if path.is_file()]
    return result(protocol, output, stage, smoke or qualified, summary, metrics, artifacts)


def run_stage(options):
    protocol = read(options.protocol)
    require(protocol.get("kind") == "automata-token-order" and protocol.get("schemaVersion") == 1, "Unsupported token pilot protocol.")
    require(protocol["seedBanks"] == seed_banks(protocol["seed"], PROFILES[protocol["profile"]], read(protocol["seedAudit"])), "Frozen token banks changed.")
    require(Path(sys.executable).resolve() == Path(protocol["python"]["executable"])
            and sys.version == protocol["python"]["version"], "Frozen controller Python changed.")
    for path, expected in {**protocol["java"]["files"], **protocol["numerical"]["files"]}.items():
        require(sha(path) == expected, "Frozen runtime or numerical environment changed: " + path)
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    operation = {"collect": collect_stage, "fit": fit_stage}.get(options.stage)
    value = operation(protocol, output) if operation else arena_stage(protocol, output, options.stage)
    print(json.dumps({"stage": options.stage, "status": value["status"], "summary": value["summary"]}), flush=True)
    return value


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    for name in ("from-campaign", "runtime", "numerical-python", "seed-audit", "output"):
        prep.add_argument("--" + name, type=Path, required=True)
    prep.add_argument("--seed", type=int, required=True)
    prep.add_argument("--workers", type=int, default=4)
    prep.add_argument("--profile", choices=PROFILES, default="pilot")
    prep.add_argument("--experimental-rules-transfer", action="store_true",
                      help="Use unchanged rules-4 F1/F0/P48 inputs as unqualified experimental initialization on rules-5.")
    prep.add_argument("--maximum-seconds", type=float)
    for name in ("stage", "validate-collection"):
        command = commands.add_parser(name)
        command.add_argument("--protocol", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
        if name == "stage":
            command.add_argument("--stage", choices=STAGES, required=True)
    options = parser.parse_args(arguments)
    if options.command == "validate-collection":
        return validate_collection(read(options.protocol), options.output.resolve())
    return prepare(options) if options.command == "prepare" else run_stage(options)


if __name__ == "__main__":
    main()
