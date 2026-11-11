#!/usr/bin/env python3
"""Prepare and execute a bounded local token experiment; no LLM or production writes.

Preparation freezes a training distribution, inputs, recipes, gates and disjoint game banks.
The campaign controller owns locking, retries, process groups and resource ceilings. Native
workers are direct children in that same process group and receive its remaining deadline.
Replacement fitting disables only the supplied baseline's token head for its paired reference;
opt-in correction fitting preserves the whole incumbent and learns a separate final token head.
Development/final games retain the original incumbent. The optional frozen neural
card/purchase/territory exploration diagnostic is separate. Expansion learning is not implemented.
"""

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time


CONFIGURATION = "players-4/standard/no-crusades/deck-true/art-true"
GIB = 1024 ** 3
INSUFFICIENT_ROOTS = "Token-value fitting needs at least two collected origins in each bank"
PROFILES = {
    "smoke": {
        "maximumSeconds": 1800,
        "diagnosticWorlds": 2,
        "developmentPairs": 4,
        "confirmationPairs": 4,
        "recipes": [(12, 8, 1), (16, 8, 2)],
        "stageSeconds": [240, 1140, 30, 360],
    },
    "overnight": {
        "maximumSeconds": 43200,
        "diagnosticWorlds": 32,
        "developmentPairs": 48,
        "confirmationPairs": 256,
        "recipes": [(32, 16, 1), (96, 32, 2), (96, 32, 4)],
        "stageSeconds": [1800, 32400, 30, 7200],
    },
    "correction": {
        "maximumSeconds": 43200,
        "diagnosticWorlds": 32,
        "developmentPairs": 64,
        "confirmationPairs": 256,
        "recipes": [(64, 32, 2), (128, 64, 4)],
        "stageSeconds": [1800, 32400, 30, 7200],
    },
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def learning_mode(value="REPLACEMENT"):
    mode = value.upper()
    require(mode in ("REPLACEMENT", "CORRECTION"), "Unknown token learning mode: " + value)
    return mode


def profile_name(requested, mode, replacement_default="smoke"):
    selected = requested or ("correction" if mode == "CORRECTION" else replacement_default)
    require(selected in PROFILES, "Unknown experiment profile: " + selected)
    require((selected == "correction") == (mode == "CORRECTION"),
            "Use --profile correction with --learning-mode correction; replacement profiles are separate.")
    return selected


def read(path):
    return json.loads(Path(path).read_bytes())


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def document(path, value, immutable=True):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    content = (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()
    if immutable and path.exists():
        require(path.read_bytes() == content, "Frozen document changed: " + str(path))
        return
    temporary = path.with_suffix(path.suffix + ".writing")
    temporary.write_bytes(content)
    temporary.replace(path)


def arena_seeds(offset, count):
    require(0 <= offset <= 100_000_000 and 1 <= count <= 10000,
            "Arena seed offset/count is outside its supported range.")
    return [-1_000_000_000 + offset + index * 8191 for index in range(count)]


def seed_banks(seed, profile):
    banks = {
        "diagnostic": arena_seeds(seed, profile["diagnosticWorlds"]),
        "training": arena_seeds(seed + 1, max(row[0] for row in profile["recipes"])),
        "validation": arena_seeds(seed + 2, max(row[1] for row in profile["recipes"])),
        "development": arena_seeds(seed + 3, profile["developmentPairs"]),
        "confirmation": arena_seeds(seed + 4, profile["confirmationPairs"]),
    }
    validate_banks(banks)
    return banks


def validate_banks(banks):
    seen = set()
    for name, seeds in banks.items():
        require(seeds and len(seeds) == len(set(seeds)), "Repeated seed in bank: " + name)
        require(not seen.intersection(seeds), "Game seed banks overlap: " + name)
        seen.update(seeds)


def copy_input(source, target, provenance):
    source = Path(source).expanduser().resolve(strict=True)
    require(source.is_file(), "A frozen input must be a file: " + str(source))
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    require(sha(source) == sha(target), "Input changed during preparation: " + str(source))
    provenance.append({"source": str(source), "sha256": sha(target), "copy": str(target)})
    return target


def distribution_hashes(directory):
    paths = sorted(directory.rglob("*"))
    require(not any(path.is_symlink() for path in paths),
            "Freeze a distribution with ordinary files, not symbolic links.")
    return {str(path.relative_to(directory)): sha(path) for path in paths if path.is_file()}


def freeze_bundle(source, target, provenance, frozen_hash=None):
    """Relocate verified component bytes; retain the original bundle for a provenance audit."""
    source = Path(source).expanduser().resolve(strict=True)
    original = copy_input(source, target.with_name(target.stem + "-original.json"), provenance)
    bundle = read(original)
    for name in ("card", "purchase", "token", "expansion"):
        component = bundle.get(name)
        if component is None:
            continue
        require(isinstance(component, dict) and set(component) == {"path", "sha256"},
                "Bundle components require exactly a path and SHA-256: " + name)
        component_path = (source.parent / component["path"]).resolve(strict=True)
        require(sha(component_path) == component["sha256"], "Bundle component hash differs: " + name)
        copied = copy_input(component_path, target.with_name(target.stem + "-" + name + ".json"),
                            provenance)
        bundle[name] = {"path": copied.name, "sha256": sha(copied)}
    if frozen_hash is not None:
        bundle["frozenCardPurchaseBundleSha256"] = frozen_hash
    document(target, bundle)
    return target


def java_identity(java):
    executable = Path(java).expanduser().resolve(strict=True)
    result = subprocess.run([str(executable), "-version"], capture_output=True, text=True, check=True)
    version = (result.stderr + result.stdout).strip()
    match = re.search(r'version "(\d+)', version)
    require(match and int(match.group(1)) >= 21, "Campaigns require JDK 21 or newer.")
    home = executable.parent.parent
    paths = [executable, home / "release", home / "lib/modules"]
    require(all(path.is_file() for path in paths),
            "Use the actual JDK bin/java, with release and lib/modules beside it.")
    return {"executable": str(executable), "version": version,
            "files": {str(path): sha(path) for path in paths}}


def default_java():
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin/java") if home else shutil.which("java")


def arena_source(baseline, opponents, seed, pairs, candidate=None, purpose="SCREENING"):
    return {"schemaVersion": 1, "purpose": purpose, "configurations": [CONFIGURATION],
            "candidate": str(candidate or baseline), "incumbent": str(baseline),
            "opponents": [str(path) for path in opponents],
            "pairsPerConfiguration": pairs, "seed": seed}


def diagnostic_source(baseline, purchase, joint, territory, seeds):
    policies = {"frozen": {"kind": "joint-bundle", "path": str(joint)}}
    for name, epsilon in (("epsilon-zero", 0.0), ("epsilon-low", 0.02), ("epsilon-high", 0.2)):
        policies[name] = {"kind": "territory-value-exploration-bundle", "path": str(territory),
                          "frozenCardPurchaseBundle": str(joint), "epsilon": epsilon}
    jobs = []
    for world, seed in enumerate(seeds):
        for arm in policies:
            jobs.append({"index": len(jobs), "seed": seed, "seat": world % 4, "arm": arm,
                         "policy": arm, "rivals": ["epsilon-zero"] * 4})
    return {"schemaVersion": 1, "configuration": CONFIGURATION, "baseline": str(baseline),
            "frozenPurchasePolicy": str(purchase), "policies": policies, "jobs": jobs}


def prepare(options):
    root = options.output.expanduser().resolve()
    require(not root.exists() or not any(root.iterdir()),
            "Preparation needs a new empty directory; never overwrite a frozen campaign.")
    runtime = options.runtime.expanduser().resolve(strict=True)
    require(runtime.is_dir() and (runtime / "lib/training.jar").is_file(),
            "--runtime must name the complete training distribution containing lib/training.jar.")
    require(root != runtime and runtime not in root.parents and root not in runtime.parents,
            "Prepared output and source runtime must not overlap.")
    mode = learning_mode(getattr(options, "learning_mode", "replacement"))
    selected_profile = profile_name(options.profile, mode)
    profile = PROFILES[selected_profile]
    require(1 <= options.workers <= 32, "Workers must be between one and 32.")
    maximum_seconds = options.maximum_seconds or profile["maximumSeconds"]
    require(30 <= maximum_seconds <= 259200, "Campaign allowance must be 30 seconds through 72 hours.")
    allocation_scale = maximum_seconds / profile["maximumSeconds"]
    stage_seconds = [max(1, int(seconds * allocation_scale)) for seconds in profile["stageSeconds"]]
    identity = java_identity(options.java or default_java())
    banks = seed_banks(options.seed, profile)
    require(isinstance(read(options.baseline).get("weights"), dict),
            "The supplied baseline must contain its model weights.")
    optional = [options.territory_value_bundle, options.frozen_card_purchase_bundle,
                options.frozen_purchase_policy]
    require(all(optional) or not any(optional), "The optional exploration diagnostic needs all three assets.")
    scripts = Path(__file__).resolve().parent
    required_scripts = [scripts / name for name in
                        ("automata_experiments.py", "automata_campaign.py", "train-opponents.py")]
    require(all(path.is_file() for path in required_scripts), "Campaign controller and adapter must be present.")
    root.mkdir(parents=True, exist_ok=True)
    provenance = []
    # Copy all distribution files: executable bytecode, launchers and their helper scripts stay frozen.
    runtime_hashes = distribution_hashes(runtime)
    shutil.copytree(runtime, root / "runtime", symlinks=False)
    require(runtime_hashes == distribution_hashes(runtime)
            == distribution_hashes(root / "runtime"),
            "Runtime files changed during preparation; use a fresh output directory.")
    for path in sorted(scripts.glob("*.py")):
        copy_input(path, root / "scripts" / path.name, provenance)
    baseline = copy_input(options.baseline, root / "inputs/baseline.json", provenance)
    training_baseline = root / "inputs/token-training-baseline.json"
    if mode == "CORRECTION":
        shutil.copy2(baseline, training_baseline)
    else:
        training_model = read(baseline)
        training_model["weights"]["planning.tokenValue.enabled"] = 0.0
        document(training_baseline, training_model)
    opponents = [baseline]
    for index, path in enumerate(options.opponent):
        opponents.append(copy_input(path, root / f"inputs/opponent-{index}.json", provenance))
    require(len(opponents) <= 16, "At most fifteen additional frozen opponents are supported.")
    diagnostic = None
    if all(optional):
        original_joint = read(options.frozen_card_purchase_bundle)
        original_territory = read(options.territory_value_bundle)
        require(original_joint.get("baselineSha256") == sha(baseline)
                and original_territory.get("baselineSha256") == sha(baseline)
                and original_territory.get("frozenCardPurchaseBundleSha256")
                == sha(options.frozen_card_purchase_bundle),
                "Optional exploration assets must bind the exact supplied baseline and joint bundle.")
        joint = freeze_bundle(options.frozen_card_purchase_bundle, root / "inputs/joint.json", provenance)
        territory = freeze_bundle(options.territory_value_bundle, root / "inputs/territory.json",
                                  provenance, sha(joint))
        purchase = copy_input(options.frozen_purchase_policy, root / "inputs/purchase.json", provenance)
        require(sha(purchase) == read(joint)["purchase"]["sha256"],
                "The diagnostic purchase policy must equal the frozen joint purchase component.")
        diagnostic = root / "sources/diagnostic.json"
        document(diagnostic, diagnostic_source(baseline, purchase, joint, territory, banks["diagnostic"]))
    recipes = []
    for index, (training, validation, replicates) in enumerate(profile["recipes"]):
        name = f"token-{index}"
        source = root / "sources" / (name + ".json")
        native = arena_source(training_baseline, opponents, options.seed + 1, profile["developmentPairs"])
        native.update(trainingRoots=training, validationRoots=validation,
                      continuationReplicates=replicates, regularization=[0.001, 0.01, 0.1, 1.0])
        if mode == "CORRECTION":
            native["learningMode"] = mode
        document(source, native)
        recipes.append({"id": name, "source": str(source), "trainingRoots": training,
                        "validationRoots": validation, "continuationReplicates": replicates})
    provenance_doc = {"copiedInputs": provenance, "runtimeSha256": runtime_hashes}
    source_root = scripts.parent
    if (source_root / "training/src").is_dir():
        provenance_doc["nativeSourceSha256"] = {
            str(path.relative_to(source_root)): sha(path)
            for directory in ("ai/src/commonMain", "core/src/commonMain", "training/src/main")
            for path in sorted((source_root / directory).rglob("*.kt"))
        }
    document(root / "provenance.json", provenance_doc)
    protocol = {
        "schemaVersion": 1, "kind": "automata-token-experiment", "profile": selected_profile,
        "learningMode": mode,
        "preparedAt": datetime.now(timezone.utc).isoformat(), "configuration": CONFIGURATION,
        "scope": ("Paired token correction ridge preserving the supplied incumbent; "
                  "development/confirmation against the supplied incumbent. No expansion learning."
                  if mode == "CORRECTION" else
                  "Paired token action-advantage ridge with head-disabled training reference; "
                  "development/confirmation against the supplied incumbent. No expansion learning."),
        "diagnosticScope": "Frozen card/purchase/territory epsilon diagnostic only; never fitted here.",
        "python": {"executable": str(Path(sys.executable).resolve()), "version": sys.version,
                   "sha256": sha(sys.executable)},
        "java": identity, "runtime": str(root / "runtime"), "baseline": str(baseline),
        "baselineSha256": sha(baseline),
        "trainingBaseline": str(training_baseline), "trainingBaselineSha256": sha(training_baseline),
        "trainingReference": ("The supplied incumbent bytes, including its original token head, remain "
                              "unchanged for collection, continuation and all comparisons. A separate "
                              "planning.tokenCorrection head learns the final token adjustment."
                              if mode == "CORRECTION" else
                              "Only planning.tokenValue.enabled is set to zero for paired collection "
                              "and the controlled seat's continuation; opponents retain supplied models. "
                              "The original incumbent bytes remain frozen for diagnostic/development/final games."),
        "opponents": [str(path) for path in opponents], "workers": options.workers,
        "heapMiB": 1536, "seed": options.seed, "seedBanks": banks,
        "seedNote": "Fresh namespace supplied at preparation; no claim about external historical banks.",
        "diagnosticSource": str(diagnostic) if diagnostic else None, "recipes": recipes,
        "developmentPairs": profile["developmentPairs"],
        "confirmationPairs": profile["confirmationPairs"],
        "gates": {
            "scale": "First pilot has nonzero training labels and positive validation selected mean.",
            "selection": "Highest development paired reward mean strictly above zero; ties recipe id.",
            "confirmation": "One fresh bank; reward lower95 > 0, mean win-share delta >= 0, at least 32 worlds.",
        },
        "minimumConfirmationPairs": 256 if mode == "CORRECTION" else 32, "productionActivation": False,
        "intervalMethod": "Approximate 95% normal interval over independent paired game worlds.",
        "confirmationReserveSeconds": stage_seconds[3],
        "minimumRecipeAdmissionSeconds": 30 if selected_profile == "smoke" else 300,
    }
    if mode == "CORRECTION":
        protocol["gates"].update(
            scale=("First pilot has nonzero training labels, positive validation selected mean, "
                   "positive independent development paired reward mean and nonnegative win-share mean "
                   "over at least 64 worlds."),
            selection=("Highest positive development paired reward mean with nonnegative win-share mean "
                       "over at least 64 worlds; ties recipe id."),
            confirmation=("One fresh bank; reward lower95 > 0, mean win-share delta >= 0, "
                          "at least 256 worlds."),
        )
    document(root / "protocol.json", protocol)
    inputs = {str(path.relative_to(root)): sha(path) for path in sorted(root.rglob("*")) if path.is_file()}
    inputs.update(identity["files"])
    inputs[str(Path(sys.executable).resolve())] = sha(sys.executable)
    stages = []
    for index, name in enumerate(("diagnostic", "token-trials", "select", "confirm")):
        stages.append({"id": name,
                       "command": [str(Path(sys.executable).resolve()),
                                   str(root / "scripts/automata_experiments.py"), "stage",
                                   "--protocol", str(root / "protocol.json"),
                                   "--output", "{output}", "--stage", name],
                       "result": f"results/{name}.json",
                       "maximumSeconds": stage_seconds[index],
                       "dependsOn": [] if index == 0 else [stages[index - 1]["id"]],
                       "maximumAttempts": 2})
    campaign = {"schemaVersion": 1, "kind": "automata-campaign", "inputs": inputs,
                "limits": {"maximumSeconds": maximum_seconds, "maximumDataBytes": 4 * GIB,
                           "minimumFreeBytes": 2 * GIB, "maximumRssBytes": 3 * GIB}, "stages": stages}
    document(root / "campaign.json", campaign)
    print(root / "campaign.json")
    return campaign


def remaining_seconds():
    values = [float(os.environ[name]) for name in
              ("AOR_CAMPAIGN_DEADLINE_EPOCH", "AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH")
              if name in os.environ]
    require(values and all(math.isfinite(value) for value in values),
            "Native stages must run under the campaign controller with its original deadlines.")
    remaining = min(values) - time.time()
    require(remaining > 0, "The original campaign or stage deadline has expired.")
    return remaining


def native(protocol, mode, source, output, extra=(), allow_insufficient=False, *, create_output=True):
    """No detached supervisor: the controller's process group contains Java and this adapter."""
    remaining = remaining_seconds()
    output = Path(output)
    if create_output:
        output.mkdir(parents=True, exist_ok=True)
    else:
        require(not output.exists(), "This native mode requires a fresh output directory.")
        output.parent.mkdir(parents=True, exist_ok=True)
    command = [protocol["java"]["executable"], f'-Xmx{protocol["heapMiB"]}m',
               f'-XX:ActiveProcessorCount={protocol["workers"]}', "-XX:ParallelGCThreads=1",
               "-XX:ConcGCThreads=1", "-cp", str(Path(protocol["runtime"]) / "lib/*"),
               "dev.aragos.aor.training.TrainingMainKt", "--mode", mode,
               "--source", str(source), "--output", str(output),
               "--hours", str(min(12.0, remaining / 3600))]
    if mode in ("token-value-pilot", "arena"):
        command += ["--workers", str(protocol["workers"])]
    command += list(extra)
    # Keep the log outside native output: initial native directories reject foreign artifacts.
    log = output.with_suffix(".log")
    start = log.stat().st_size if log.exists() else 0
    # The JVM processor cap controls its thread pools, not the host's worker allowance.
    # Tournaments reserves GC/coordinator capacity against the actual machine CPU count.
    environment = dict(os.environ, AOR_TRAINING_MACHINE_CPUS=str(os.cpu_count() or 1))
    with log.open("ab") as stream:
        completed = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                   env=environment, check=False)
    if completed.returncode == 0:
        return True
    if allow_insufficient and (output / "dataset.json").is_file():
        with log.open("rb") as stream:
            stream.seek(max(start, log.stat().st_size - 65536))
            diagnostic = stream.read().decode("utf-8", "replace")
        dataset = read(output / "dataset.json")
        counts = dataset.get("counts", {})
        specification = read(source)
        requested = specification["trainingRoots"] + specification["validationRoots"]
        files = [f"origin-{index}.json" for index in range(requested)]
        complete = (dataset.get("requestedOrigins") == requested
                    and dataset.get("originFiles") == files
                    and all((output / name).is_file() for name in files)
                    and all(counts.get(split, {}).get("requested") == count
                            and counts[split].get("collected", 0) + counts[split].get("skipped", 0)
                            == count for split, count in
                            (("TRAINING", specification["trainingRoots"]),
                             ("VALIDATION", specification["validationRoots"]))))
        insufficient = any(counts.get(split, {}).get("collected", requested) < 2
                           for split in ("TRAINING", "VALIDATION"))
        if INSUFFICIENT_ROOTS in diagnostic and complete and insufficient:
            return False
    raise RuntimeError(f"Native {mode} failed with exit {completed.returncode}; inspect {log}.")


def interval(values):
    require(values and all(math.isfinite(value) for value in values),
            "A paired interval requires finite world differences.")
    mean = statistics.mean(values)
    margin = 1.96 * statistics.stdev(values) / math.sqrt(len(values)) if len(values) > 1 else None
    return {"count": len(values), "mean": mean,
            "lower95": mean - margin if margin is not None else None,
            "upper95": mean + margin if margin is not None else None}


def stage_result(output, stage, passed, summary, metrics=None, artifacts=()):
    result = {"schemaVersion": 1, "status": "passed" if passed else "rejected", "summary": summary}
    if metrics is not None:
        result["metrics"] = metrics
    if artifacts:
        result["artifacts"] = {str(Path(path).relative_to(output)): sha(path) for path in artifacts}
    document(Path(output) / "results" / (stage + ".json"), result)
    return result


def diagnostic_stage(protocol, output):
    source = protocol["diagnosticSource"]
    if source is None:
        return stage_result(output, "diagnostic", True,
                            "Exploration diagnostic unavailable: optional assets omitted; token-only campaign.",
                            {"available": False, "explorationMeasured": False})
    specification = read(source)
    directory = output / "diagnostic"
    for start in range(0, len(specification["jobs"]), 64):
        indices = list(range(start, min(start + 64, len(specification["jobs"]))))
        native(protocol, "card-advantage-evaluate", source, directory,
               ["--indices", ",".join(map(str, indices))])
    worlds = {}
    hashes = {}
    for job in specification["jobs"]:
        path = directory / f'game-{job["index"]}.json.gz'
        with gzip.open(path, "rb") as stream:
            raw = stream.read(64 * 1024 * 1024 + 1)
        require(len(raw) <= 64 * 1024 * 1024, "Diagnostic game exceeds its document budget.")
        row = json.loads(raw)
        require((row["index"], row["seed"], row["controlledSeat"], row["arm"])
                == (job["index"], job["seed"], job["seat"], job["arm"]),
                "Exploration result changed its precommitted world, seat or arm.")
        worlds.setdefault(job["seed"], {})[job["arm"]] = row["terminal"]["reward"]
        hashes[path.name] = sha(path)
    comparisons = {arm: interval([row[arm] - row["epsilon-zero"] for row in worlds.values()])
                   for arm in ("frozen", "epsilon-low", "epsilon-high")}
    diagnostic = {"available": True, "explorationMeasured": True, "worlds": len(worlds),
                  "comparisonsAgainstEpsilonZero": comparisons, "gameSha256": hashes,
                  "interpretation": "Paired complete-game diagnostics; no strength claim or tuning gate."}
    document(output / "diagnostic.json", diagnostic)
    return stage_result(output, "diagnostic", True, "Frozen epsilon 0/.02/.2 diagnostic completed.",
                        diagnostic, [output / "diagnostic.json"])


def pilot_signal(fit):
    training = fit["labelDiagnostics"]["TRAINING"]
    return (training["nonbaselineAlternatives"] > training["zeroRewardAlternatives"]
            and fit["validationRewardDifference"]["mean"] > 0)


def development_signal(protocol, development):
    return (development["mean"] > 0
            and (protocol.get("learningMode", "REPLACEMENT") != "CORRECTION"
                 or (development["count"] >= 64 and development["winShareDifference"]["mean"] >= 0)))


def scale_signal(protocol, fit, development):
    return (pilot_signal(fit)
            and (protocol.get("learningMode", "REPLACEMENT") != "CORRECTION"
                 or development_signal(protocol, development)))


def recipe_admitted(protocol):
    minimum = protocol["minimumRecipeAdmissionSeconds"]
    return (remaining_seconds() >= minimum
            and float(os.environ["AOR_CAMPAIGN_DEADLINE_EPOCH"]) - time.time()
            >= minimum + protocol["confirmationReserveSeconds"])


def collection_effort(directory):
    dataset = read(directory / "dataset.json")
    counts = dataset.get("counts", {})
    outcomes = 0
    for name in dataset.get("originFiles", []):
        origin = read(directory / name)
        outcomes += sum(len(alternative["outcomes"]) for alternative in origin["alternatives"])
    return {"collectedOrigins": sum(row["collected"] for row in counts.values()),
            "pairedActionContinuationOutcomes": outcomes}


def verify_arena(directory, source):
    specification = read(source)
    report = read(directory / "evaluation.json")
    require(report["complete"] and report["purpose"] == specification["purpose"]
            and read(directory / "state.json")["phase"] == "complete"
            and (directory / "finished.json").is_file(), "Arena has not completed its frozen bank.")
    expected = arena_seeds(specification["seed"], specification["pairsPerConfiguration"])
    deltas = []
    win_deltas = []
    for index, seed in enumerate(expected):
        row = read(directory / f"pair-{index}.json")
        require(row["index"] == index and row["seed"] == seed and row["seat"] == index % 4
                and row["configuration"] == CONFIGURATION, "Arena pair differs from its world/seat bank.")
        seat = row["seat"]
        deltas.append(row["candidate"]["rewards"][seat] - row["incumbent"]["rewards"][seat])
        win_deltas.append(row["candidate"]["wins"][seat] - row["incumbent"]["wins"][seat])
    calculated = interval(deltas)
    reported = report["overall"]["rewardDifference"]
    require(reported["count"] == calculated["count"]
            and math.isclose(reported["mean"], calculated["mean"], abs_tol=1e-12),
            "Arena aggregate disagrees with its paired public returns.")
    calculated["winShareDifference"] = interval(win_deltas)
    require(math.isclose(report["overall"]["winShareDifference"]["mean"],
                         calculated["winShareDifference"]["mean"], abs_tol=1e-12),
            "Arena win-share aggregate disagrees with its paired public results.")
    return calculated


def trials_stage(protocol, output):
    trials = []
    artifacts = []
    scale = False
    for index, recipe in enumerate(protocol["recipes"]):
        trial_root = output / "trials" / recipe["id"]
        completed = trial_root / "trial.json"
        descriptor = dict(recipe, sourceSha256=sha(recipe["source"]))
        if completed.exists():
            trial = read(completed)
            require(trial.get("recipe") == descriptor,
                    "Cached trial differs from its frozen recipe/source: " + recipe["id"])
            for path, digest in trial.get("artifacts", {}).items():
                candidate = (output / path).resolve(strict=True)
                require(output in candidate.parents and sha(candidate) == digest,
                        "Cached trial artifact changed: " + path)
            if trial["status"] in ("evaluated", "duplicate"):
                require(sha(trial["candidate"]) == trial["candidateSha256"]
                        and sha(trial_root / "pilot/fit.json") == trial["fitSha256"]
                        and sha(trial_root / "pilot/dataset.json") == trial["datasetSha256"],
                        "Cached pilot candidate, fit or dataset changed.")
                if trial["status"] == "evaluated":
                    require(verify_arena(trial_root / "development", trial_root / "development-source.json")
                            == trial["development"], "Cached development results changed.")
                else:
                    original = next(row for row in trials if row["id"] == trial["duplicateOf"])
                    require(original["candidateSha256"] == trial["candidateSha256"]
                            and original["development"] == trial["development"],
                            "Cached duplicate must preserve the original paired development result.")
        elif index > 0 and (not scale or not recipe_admitted(protocol)):
            reason = ("The predeclared first-pilot paired validation signal gate failed."
                      if not scale else "Original remaining allowance preserves confirmation reserve.")
            if not scale and protocol.get("learningMode") == "CORRECTION":
                reason = "The predeclared first-pilot validation/development signal gate failed."
            trial = {"id": recipe["id"], "status": "not-admitted", "reason": reason,
                     "recipe": descriptor}
            document(completed, trial)
        else:
            pilot = trial_root / "pilot"
            started_file = trial_root / "trial-started.json"
            if not started_file.exists():
                document(started_file, {"epochSeconds": time.time()})
            okay = native(protocol, "token-value-pilot", recipe["source"], pilot,
                          allow_insufficient=True)
            if not okay:
                trial = {"id": recipe["id"], "status": "rejected",
                         "reason": "Fewer than two collected origins in a frozen train/validation bank.",
                         "datasetSha256": sha(pilot / "dataset.json")}
            else:
                fit = read(pilot / "fit.json")
                require(fit["complete"] and (pilot / "finished.json").is_file(),
                        "Token pilot must finish before development evaluation.")
                fingerprint = sha(pilot / "candidate.json")
                duplicate = next((row for row in trials if row.get("candidateSha256") == fingerprint
                                  and row["status"] == "evaluated"), None)
                if duplicate:
                    development = duplicate["development"]
                else:
                    source = trial_root / "development-source.json"
                    document(source, arena_source(protocol["baseline"], protocol["opponents"],
                                                  protocol["seed"] + 3, protocol["developmentPairs"],
                                                  pilot / "candidate.json"))
                    native(protocol, "arena", source, trial_root / "development")
                    development = verify_arena(trial_root / "development", source)
                trial = {"id": recipe["id"], "status": "duplicate" if duplicate else "evaluated",
                         "scaleSignal": scale_signal(protocol, fit, development), "development": development,
                         "candidate": str(pilot / "candidate.json"),
                         "candidateSha256": fingerprint,
                         "fitSha256": sha(pilot / "fit.json"),
                         "datasetSha256": sha(pilot / "dataset.json"),
                         "continuationReplicates": recipe["continuationReplicates"]}
                if duplicate:
                    trial.update(duplicateOf=duplicate["id"],
                                 reason="Identical candidate bytes; development games reused, not repeated.")
            trial["recipe"] = descriptor
            trial.update(collection_effort(pilot),
                         elapsedSeconds=time.time() - read(started_file)["epochSeconds"],
                         elapsedIncludesResumeDowntime=True)
            trial_files = ([pilot / "dataset.json"] if not okay else
                           [pilot / name for name in ("candidate.json", "fit.json", "dataset.json")])
            if trial["status"] == "evaluated":
                trial_files += [trial_root / "development-source.json",
                                trial_root / "development/evaluation.json"]
            trial_files.append(started_file)
            trial["artifacts"] = {str(path.relative_to(output)): sha(path) for path in trial_files}
            document(completed, trial)
        trials.append(trial)
        artifacts += [completed] + [output / path for path in trial.get("artifacts", {})]
        if index == 0:
            scale = trial.get("scaleSignal", False)
    document(output / "trials.json", {"schemaVersion": 1, "trials": trials})
    evaluated = sum(trial["status"] == "evaluated" for trial in trials)
    continuation = ("unchanged supplied incumbent" if protocol.get("learningMode") == "CORRECTION"
                    else "head-disabled training reference")
    return stage_result(output, "token-trials", evaluated > 0,
                        f"Completed {evaluated} token candidates; finite unpromising recipes stopped.",
                        {"trials": trials, "continuation": continuation,
                         "expansionLearning": False},
                        artifacts + [output / "trials.json"])


def select_stage(protocol, output):
    trials = read(output / "trials.json")["trials"]
    eligible = [trial for trial in trials if trial["status"] == "evaluated"
                and development_signal(protocol, trial["development"])]
    if not eligible:
        return stage_result(output, "select", False,
                            "No candidate passed the correction development reward/win-share gate; "
                            "independent confirmation not opened."
                            if protocol.get("learningMode") == "CORRECTION" else
                            "No positive development paired reward; independent confirmation not opened.")
    winner = min(eligible, key=lambda trial: (-trial["development"]["mean"], trial["id"]))
    require(sha(winner["candidate"]) == winner["candidateSha256"],
            "Selected candidate changed after development evaluation.")
    candidate = output / "selected/candidate.json"
    if candidate.exists():
        require(sha(candidate) == winner["candidateSha256"], "Frozen selected candidate changed.")
    else:
        candidate.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(winner["candidate"], candidate)
    selection = {"schemaVersion": 1, "selected": winner["id"], "candidate": str(candidate),
                 "candidateSha256": sha(candidate), "development": winner["development"],
                 "confirmationInspected": False, "productionActivation": False}
    document(output / "selection.json", selection)
    source = arena_source(protocol["baseline"], protocol["opponents"], protocol["seed"] + 4,
                          protocol["confirmationPairs"], candidate, "CONFIRMATION")
    document(output / "confirmation-source.json", source)
    return stage_result(output, "select", True, "Candidate frozen using development games only.", selection,
                        [candidate, output / "selection.json", output / "confirmation-source.json"])


def confirm_stage(protocol, output):
    selection = read(output / "selection.json")
    require(sha(selection["candidate"]) == selection["candidateSha256"],
            "The frozen candidate changed before independent confirmation.")
    source = output / "confirmation-source.json"
    specification = read(source)
    expected = arena_source(protocol["baseline"], protocol["opponents"], protocol["seed"] + 4,
                            protocol["confirmationPairs"], selection["candidate"], "CONFIRMATION")
    require(specification == expected, "Confirmation source differs from the predeclared frozen bank.")
    native(protocol, "arena", source, output / "confirmation")
    comparison = verify_arena(output / "confirmation", source)
    passed = (comparison["count"] >= protocol["minimumConfirmationPairs"]
              and comparison["lower95"] is not None and comparison["lower95"] > 0
              and comparison["winShareDifference"]["mean"] >= 0)
    metrics = {"rewardDifference": {key: comparison[key] for key in ("count", "mean", "lower95", "upper95")},
               "winShareDifference": comparison["winShareDifference"],
               "candidateSha256": selection["candidateSha256"],
               "accepted": passed, "productionActivation": False,
               "minimumConfirmationPairs": protocol["minimumConfirmationPairs"],
               "scope": protocol["scope"], "humanStrengthVerified": False,
               "interpretation": "Limited composite-reward gate with nonnegative mean win-share; not broad qualification.",
               "intervalMethod": "Approximate 95% normal interval over independent paired game worlds."}
    document(output / "confirmation.json", metrics)
    return stage_result(output, "confirm", passed,
                        "Independent paired reward gate passed; candidate remains an offline artifact."
                        if passed else "Independent confirmation rejected; no extra games or activation.", metrics,
                        [output / "confirmation.json", output / "confirmation/evaluation.json"])


def run_stage(options):
    protocol = read(options.protocol)
    require(protocol["schemaVersion"] == 1 and protocol["kind"] == "automata-token-experiment",
            "Unsupported prepared experiment protocol.")
    validate_banks(protocol["seedBanks"])
    require(Path(sys.executable).resolve() == Path(protocol["python"]["executable"])
            and sys.version == protocol["python"]["version"]
            and sha(sys.executable) == protocol["python"]["sha256"], "Prepared Python identity changed.")
    for path, digest in protocol["java"]["files"].items():
        require(sha(path) == digest, "Prepared Java identity changed: " + path)
    output = options.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    operations = {"diagnostic": diagnostic_stage, "token-trials": trials_stage,
                  "select": select_stage, "confirm": confirm_stage}
    result = operations[options.stage](protocol, output)
    print(json.dumps(result, sort_keys=True))


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare", help="Freeze a new campaign and emit campaign.json.")
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--runtime", type=Path, required=True)
    prep.add_argument("--baseline", type=Path, required=True)
    prep.add_argument("--java", help="Actual JDK 21+ bin/java; defaults to JAVA_HOME or PATH.")
    prep.add_argument("--opponent", type=Path, action="append", default=[])
    prep.add_argument("--territory-value-bundle", type=Path)
    prep.add_argument("--frozen-card-purchase-bundle", type=Path)
    prep.add_argument("--frozen-purchase-policy", type=Path)
    prep.add_argument("--profile", choices=PROFILES,
                      help="Defaults to smoke for replacement and correction for correction learning.")
    prep.add_argument("--learning-mode", choices=("replacement", "correction"), default="replacement")
    prep.add_argument("--seed", type=int, default=7_100_000,
                      help="Fresh arena offset namespace; caller must avoid previous experiments.")
    prep.add_argument("--workers", type=int, default=2)
    prep.add_argument("--maximum-seconds", type=int)
    stage = sub.add_parser("stage", help="Internal controller adapter; retains original deadlines.")
    stage.add_argument("--protocol", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    stage.add_argument("--stage", choices=("diagnostic", "token-trials", "select", "confirm"), required=True)
    options = parser.parse_args(arguments)
    if options.command == "prepare":
        prepare(options)
    else:
        run_stage(options)


if __name__ == "__main__":
    main()
