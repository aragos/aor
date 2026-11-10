#!/usr/bin/env python3
"""Bounded P32 purchase PPO continuation over a qualified ordinary F1 policy.

Every seat retains the original F0 purchase prior. Only the controlled purchase
actor learns; rivals retain P32. Sixteen fresh on-policy batches carry Adam state,
three fixed checkpoints share development worlds, and at most one actor proceeds
to independent confirmation and held-out opponents. No production activation.
"""

import argparse
from datetime import datetime, timezone
import hashlib
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
import automata_experiments as experiments
import automata_policy_search as search


read, sha, document, require = experiments.read, experiments.sha, experiments.document, experiments.require
P32_SHA = "3802440bc84830724e32c8f32f273648f38128fc7fc1774a9c586e5b1b1b2aa4"
ADAM_SHA = "7c6df048edfceceb72cde3cf7dabdf94815fa0990bc1f831fa010c95ca9cec3e"
ACTOR = "aor-purchase-policy-1"
ENCODER = "aor-purchase-observation-1"
HYPERPARAMETERS = {"learningRate": .0003, "epochs": 4, "batchSize": 64, "clip": .2,
                   "valueCoefficient": .5, "entropyCoefficient": 0., "maxGradientNorm": .5,
                   "maxBehaviorKl": .01, "threads": 1, "maxSeconds": 900.}
PROFILES = {
    "smoke": {"batches": 1, "gamesPerBatch": 2, "checkpoints": [1], "developmentPairs": 4,
              "confirmationPairs": 4, "robustnessPairs": 4},
    "search": {"batches": 16, "gamesPerBatch": 256, "checkpoints": [4, 8, 16],
               "developmentPairs": 512, "confirmationPairs": 2048, "robustnessPairs": 4096},
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def checkpoint(path, model):
    wrapper = read(path)
    payload = wrapper.get("payload")
    require(isinstance(payload, dict) and wrapper.get("sha256") == digest(payload),
            "Purchase checkpoint checksum changed.")
    require(payload.get("schemaVersion") == 1 and payload.get("model") == read(model)
            and isinstance(payload.get("optimizer"), dict)
            and payload["optimizer"].get("state")
            and payload["optimizer"].get("param_groups")
            and all(group["lr"] == HYPERPARAMETERS["learningRate"] for group in payload["optimizer"]["param_groups"]),
            "Adam carry must belong to the exact input actor and original learning rate.")
    return payload


def seed_banks(seed, profile, reserved, offsets=(), pairs_per_offset=10000):
    banks = {f"training-{index:02}": experiments.arena_seeds(seed + index, profile["gamesPerBatch"])
             for index in range(1, profile["batches"] + 1)}
    banks.update({name: experiments.arena_seeds(seed + offset, profile[count])
                  for name, offset, count in (("development", 20, "developmentPairs"),
                                               ("confirmation", 21, "confirmationPairs"),
                                               ("robustness", 22, "robustnessPairs"))})
    experiments.validate_banks(banks)
    require(not set(reserved).intersection(value for bank in banks.values() for value in bank),
            "Purchase continuation worlds overlap a reserved source bank.")
    require(type(pairs_per_offset) is int and 1 <= pairs_per_offset <= 10000
            and all(type(offset) is int and 0 <= offset <= 100000000 for offset in offsets),
            "Conservative offset reservations require native offsets and one through 10000 pairs.")
    by_residue = {}
    for offset in offsets:
        by_residue.setdefault(offset % 8191, []).append(offset)
    require(not any(0 <= (value + 1000000000 - offset) // 8191 < pairs_per_offset
                    for bank in banks.values() for value in bank
                    for offset in by_residue.get((value + 1000000000) % 8191, ())),
            "Purchase continuation worlds overlap a conservatively reserved historical bank.")
    return banks


def qualified_source(source, baseline, prior, policy):
    campaign, protocol = read(source / "campaign.json"), read(source / "protocol.json")
    require(campaign.get("kind") == "automata-campaign", "Use an existing qualified campaign.")
    for name, expected in campaign["inputs"].items():
        require(sha(source / name) == expected, "Qualified source input changed: " + name)
    state = read(source / "run/campaign-state.json")
    require(state.get("status") == "completed"
            and all(state["stages"][stage]["status"] == "passed" for stage in ("confirm", "robustness")),
            "The source campaign must have completed both qualification stages.")
    controller.verify_completed(campaign, state, source / "run")
    final = read(source / "run/robustness-report.json")
    require(final.get("accepted") is True and final["metrics"]["candidateSha256"] == sha(baseline),
            "The ordinary F1 baseline must be the source campaign's qualified candidate.")
    require(read(protocol["frozenPurchase"]["baseline"])["weights"] == read(prior)["weights"]
            and read(protocol["frozenPurchase"]["policy"]) == read(policy),
            "Continuation must retain the qualified source's F0 purchase prior and P32 actor.")
    return protocol


def numerical_identity(executable, learner):
    """Pin the actual environment, including numerical package source and native libraries."""
    executable = Path(executable).expanduser().absolute()
    code = """
import hashlib,json,pathlib,sys,torch,numpy
sys.path.insert(0,sys.argv[1])
import purchase_policy
from dataclasses import asdict
files={}
for package in (torch,numpy):
    for path in sorted(pathlib.Path(package.__file__).parent.rglob('*')):
        if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
            files[str(path.resolve())]=hashlib.sha256(path.read_bytes()).hexdigest()
binary=pathlib.Path(sys.executable).resolve()
files[str(binary)]=hashlib.sha256(binary.read_bytes()).hexdigest()
venv=pathlib.Path(sys.prefix)/'pyvenv.cfg'
if venv.is_file():files[str(venv)]=hashlib.sha256(venv.read_bytes()).hexdigest()
print(json.dumps({'pythonVersion':sys.version,'torchVersion':torch.__version__,
                  'numpyVersion':numpy.__version__,'files':files,
                  'configDefaults':asdict(purchase_policy.UpdateConfig())}))
"""
    identity = json.loads(subprocess.check_output([str(executable), "-c", code, str(learner.parent)],
        text=True, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1")))
    return dict(identity, executable=str(executable))


def update_config(protocol, index):
    values = dict(protocol["numerical"]["configDefaults"], **HYPERPARAMETERS,
                  seed=protocol["seed"] + 1000 + index)
    if protocol["profile"] == "smoke":
        values.update(epochs=1, maxSeconds=120.)
    return values


def prepare(options):
    source = options.from_campaign.expanduser().resolve(strict=True)
    baseline, prior, policy, adam = [Path(path).expanduser().resolve(strict=True) for path in
                                     (options.baseline, options.prior, options.purchase_policy, options.optimizer_from)]
    require(sha(policy) == P32_SHA and sha(adam) == ADAM_SHA, "Start from the original P32 actor and Adam checkpoint bytes.")
    checkpoint(adam, policy)
    original = qualified_source(source, baseline, prior, policy)
    evolution.validate_model(read(baseline))
    evolution.validate_model(read(prior))
    runtime, root = options.runtime.expanduser().resolve(strict=True), options.output.expanduser().resolve()
    require((runtime / "lib/training.jar").is_file(), "Use a complete training runtime distribution.")
    require(all(root != path and root not in path.parents and path not in root.parents for path in (source, runtime)),
            "Prepared output must be separate from its source and runtime.")
    require(not root.exists() or not any(root.iterdir()), "Prepare a new empty continuation directory.")
    require(type(options.workers) is int and 1 <= options.workers <= (os.cpu_count() or 1),
            "Workers must fit the local machine's CPU allowance.")
    maximum = options.maximum_seconds
    require(type(maximum) in (int, float) and math.isfinite(maximum) and 30 <= maximum <= 28800,
            "Continuation allowance must be 30 seconds through eight hours.")
    source_config = read(options.update_config)
    require({key: value for key, value in source_config.items() if key != "seed"} == HYPERPARAMETERS,
            "Use the original P32 signal update hyperparameters; no extra objectives.")
    training = options.opponent or list(map(Path, original["opponents"]))
    robust = options.robust_opponent or list(map(Path, original["robustOpponents"]))
    train_keys, robust_keys = [evolution.model_key(path) for path in training], [evolution.model_key(path) for path in robust]
    require(1 <= len(training) <= 16 and 1 <= len(robust) <= 16
            and len(set(train_keys)) == len(train_keys) and len(set(robust_keys)) == len(robust_keys)
            and not set(train_keys).intersection(robust_keys), "Opponent pools must be distinct and held out from one another.")
    reserved = sorted({value for bank in original["seedBanks"].values() for value in bank})
    offsets, pairs_per_offset = [], 10000
    require(options.seed_audit or options.profile == "smoke", "Search requires a comprehensive frozen historical seed audit.")
    if options.seed_audit:
        audit = read(options.seed_audit)
        actual = audit.get("reservedSeeds", [])
        offsets = audit.get("conservativelyExpandedOffsets", [])
        pairs_per_offset = audit.get("conservativePairsPerOffset", 10000)
        require(isinstance(actual, list) and isinstance(offsets, list) and (actual or offsets)
                and all(type(value) is int for value in actual),
                "Seed audit must reserve actual seeds or conservative native offset banks.")
        reserved = sorted(set(reserved).union(actual))
    banks = seed_banks(options.seed, PROFILES[options.profile], reserved, offsets, pairs_per_offset)
    java = experiments.java_identity(original["java"]["executable"])
    require(java["files"] == original["java"]["files"], "Qualified source JDK changed.")
    scripts = Path(__file__).resolve().parent
    numerical = numerical_identity(options.numerical_python, scripts / "purchase_policy.py")
    hashes = experiments.distribution_hashes(runtime)
    root.mkdir(parents=True, exist_ok=True)
    provenance = []
    shutil.copytree(runtime, root / "runtime", symlinks=False)
    require(hashes == experiments.distribution_hashes(runtime) == experiments.distribution_hashes(root / "runtime"),
            "Runtime changed during freezing.")
    for name in ("automata_purchase_continuation.py", "automata_campaign.py", "automata_experiments.py",
                 "automata_evolution.py", "automata_policy_search.py", "purchase_policy.py",
                 "purchase_policy_toy.py", "train-opponents.py"):
        experiments.copy_input(scripts / name, root / "scripts" / name, provenance)
    def copied(path, name):
        return str(experiments.copy_input(path, root / "inputs" / name, provenance))
    protocol = {"schemaVersion": 1, "kind": "automata-purchase-continuation", "profile": options.profile,
        "preparedAt": datetime.now(timezone.utc).isoformat(), "configuration": experiments.CONFIGURATION,
        "baseline": copied(baseline, "ordinary-f1.json"), "prior": copied(prior, "purchase-prior-f0.json"),
        "purchasePolicy": copied(policy, "p32.json"), "optimizerFrom": copied(adam, "p32-checkpoint.json"),
        "originalUpdateConfig": copied(options.update_config, "original-update-config.json"),
        "opponents": [copied(path, f"opponent-{index}.json") for index, path in enumerate(training)],
        "robustOpponents": [copied(path, f"robust-opponent-{index}.json") for index, path in enumerate(robust)],
        "python": {"executable": str(Path(sys.executable).resolve()), "version": sys.version, "sha256": sha(sys.executable)},
        "java": java, "numerical": numerical, "runtime": str(root / "runtime"), "scripts": str(root / "scripts"),
        "seed": options.seed, "seedBanks": banks, "reservedSeeds": reserved,
        "reservedOffsets": offsets, "conservativePairsPerOffset": pairs_per_offset, **PROFILES[options.profile],
        "workers": options.workers, "heapMiB": 1536, "qualificationConfidence": .99,
        "collapseGuard": {"rewardMeanBelow": -.1, "rewardUpper95Below": 0.},
        "scope": "Purchase actor only; ordinary F1 and every seat's F0 purchase prior remain frozen; rivals retain P32.",
        "seedNote": "Every declared bank is disjoint from frozen source/audit worlds; checkpoint development intentionally shares one bank.",
        "intervalMethod": "95% paired normal intervals; independent final gates use 99% normal lower bounds.",
        "productionActivation": False, "humanStrengthVerified": False}
    if options.seed_audit:
        protocol["seedAudit"] = copied(options.seed_audit, "seed-audit.json")
    for index in range(1, protocol["batches"] + 1):
        document(root / f"configs/update-{index:02}.json", update_config(protocol, index))
    protocol["configDirectory"] = str(root / "configs")
    document(root / "protocol.json", protocol)
    document(root / "provenance.json", {"sourceCampaign": str(source), "sourceCampaignSha256": sha(source / "campaign.json"),
             "sourceProtocolSha256": sha(source / "protocol.json"), "copiedInputs": provenance, "runtimeSha256": hashes})
    inputs = {str(path.relative_to(root)): sha(path) for path in sorted(root.rglob("*")) if path.is_file()}
    inputs.update(java["files"])
    inputs.update(numerical["files"])
    inputs[str(Path(sys.executable).resolve())] = sha(sys.executable)
    names = ("train", "confirm", "robustness")
    campaign = {"schemaVersion": 1, "kind": "automata-campaign", "inputs": inputs,
        "limits": {"maximumSeconds": maximum, "maximumDataBytes": 4 * experiments.GIB,
                   "minimumFreeBytes": 2 * experiments.GIB, "maximumRssBytes": 4 * experiments.GIB},
        "stages": [{"id": name, "dependsOn": [] if index == 0 else [names[index - 1]],
                    "command": [protocol["python"]["executable"], str(root / "scripts" / Path(__file__).name),
                                "stage", "--protocol", str(root / "protocol.json"), "--output", "{output}", "--stage", name],
                    "result": f"results/{name}.json", "maximumSeconds": maximum * fraction, "maximumAttempts": 2}
                   for index, (name, fraction) in enumerate(zip(names, (.5, .25, .25)))]}
    document(root / "campaign.json", campaign)
    print(root / "campaign.json")
    return campaign


def collection_source(protocol, policy, index):
    return {"schemaVersion": 1, "configuration": protocol["configuration"], "games": protocol["gamesPerBatch"],
            "seed": protocol["seed"] + index, "baseline": protocol["baseline"], "opponents": protocol["opponents"],
            "policy": str(policy), "sharedPurchaseBaseline": protocol["prior"],
            "opponentPurchasePolicy": protocol["purchasePolicy"]}


def arena_source(protocol, policy, bank):
    count, offset, opponents = {"development": ("developmentPairs", 20, "opponents"),
                               "confirmation": ("confirmationPairs", 21, "opponents"),
                               "robustness": ("robustnessPairs", 22, "robustOpponents")}[bank]
    source = experiments.arena_source(protocol["baseline"], protocol[opponents], protocol["seed"] + offset,
        protocol[count], protocol["baseline"], "SCREENING" if bank == "development" else "CONFIRMATION")
    source["frozenPurchase"] = {"baseline": protocol["prior"], "policy": protocol["purchasePolicy"],
                                "candidatePolicy": str(policy), "incumbentPolicy": protocol["purchasePolicy"]}
    return source


def path_identity(path):
    return {"path": str(Path(path).resolve()), "sha256": sha(path)}


def verify_collection(protocol, source, directory):
    specification = read(source)
    manifest, state, finished = [read(directory / name) for name in ("collection.json", "state.json", "finished.json")]
    count, policy = specification["games"], specification["policy"]
    expected = {"source": str(source), "sourceSha256": sha(source), "configuration": protocol["configuration"],
        "games": count, "seed": specification["seed"], "seeds": experiments.arena_seeds(specification["seed"], count),
        "baseline": path_identity(protocol["baseline"]), "opponents": list(map(path_identity, protocol["opponents"])),
        "policy": policy, "modelSha256": sha(policy), "mode": "on-policy", "encoderVersion": ENCODER, "actorVersion": ACTOR,
        "sharedPurchaseBaseline": path_identity(protocol["prior"]),
        "opponentPurchasePolicy": path_identity(protocol["purchasePolicy"])}
    require(all(manifest.get(key) == value for key, value in expected.items()), "Collection differs from its frozen roles or game bank.")
    require(all(item.get("kind") == "purchase-collect" and item.get("signature") == manifest["signature"]
                and item.get("games") == count for item in (state, finished))
            and state.get("phase") == "complete" and state.get("completedGames") == count,
            "Purchase collection is incomplete or has inconsistent identities.")
    snapshots = {"source.json": source, "baseline.json": protocol["baseline"], "policy.json": policy,
                 "shared-purchase-baseline.json": protocol["prior"], "opponent-purchase-policy.json": protocol["purchasePolicy"],
                 **{f"opponent-{index}.json": path for index, path in enumerate(protocol["opponents"])}}
    require(all(sha(directory / name) == sha(path) for name, path in snapshots.items()), "Collection input snapshot changed.")
    files = [directory / f"game-{index}.json" for index in range(count)]
    require(sorted(directory.glob("game-*.json")) == sorted(files), "Collection must contain exactly the declared game files.")
    hashes = [sha(path) for path in files]
    require(finished.get("gameSha256") == hashes, "Collected game bytes differ from their completion manifest.")
    rewards, decisions = [], 0
    for index, path in enumerate(files):
        row = read(path)
        expected_row = {"index": index, "signature": manifest["signature"], "configuration": protocol["configuration"],
            "gameSeed": expected["seeds"][index], "controlledSeat": index % 4,
            "opponentIndices": [(index // 4 + seat) % len(protocol["opponents"]) for seat in range(4)],
            "mode": "on-policy", "modelSha256": sha(policy), "encoderVersion": ENCODER, "actorVersion": ACTOR}
        require(all(row.get(key) == value for key, value in expected_row.items()), "On-policy trajectory origin changed.")
        reward = row["terminal"]["reward"]
        require(type(reward) in (int, float) and math.isfinite(reward) and 0 <= reward <= 1,
                "Collected terminal reward must be finite and normalized.")
        rewards.append(reward)
        decisions += len(row["decisions"])
    return {"collectionSha256": sha(directory / "collection.json"), "collectionSignature": manifest["signature"],
            "gameHashes": hashes, "games": count, "gameMeanReward": sum(rewards) / count}, decisions


def verify_arena(protocol, source, directory):
    specification = read(source)
    experiments.verify_arena(directory, source)
    report, run, state = [read(directory / name) for name in ("evaluation.json", "run.json", "state.json")]
    expected = {name: dict(path_identity(path), snapshot="frozen-purchase-" + {
        "baseline": "baseline", "policy": "policy", "candidatePolicy": "candidate-policy",
        "incumbentPolicy": "incumbent-policy"}[name] + ".json") for name, path in specification["frozenPurchase"].items()}
    require(run["kind"] == state["kind"] == "strategy-arena" and run["signature"] == state["signature"]
            and run["source"] == str(source) and run["workers"] == protocol["workers"]
            and all(item.get("frozenPurchase") == expected for item in (report, run)), "Arena actor roles or identity changed.")
    require(all(sha(directory / value["snapshot"]) == value["sha256"] for value in expected.values()),
            "Arena purchase actor or prior snapshot changed.")
    for name, path in (("candidate", protocol["baseline"]), ("incumbent", protocol["baseline"]),
                       *[(f"opponent-{index}", path) for index, path in enumerate(specification["opponents"])]):
        evolution.verify_snapshot(read(path)["weights"], read(directory / f"{name}.json")["weights"])
    seeds = experiments.arena_seeds(specification["seed"], specification["pairsPerConfiguration"])
    pairs = [read(directory / f"pair-{index}.json") for index in range(len(seeds))]
    for index, pair in enumerate(pairs):
        search.validate_pair(pair, index, seeds[index], len(specification["opponents"]))
    metrics = search.comparison(pairs)
    require(report["overall"]["pairs"] == len(seeds), "Arena lacks complete paired-game coverage.")
    search.verify_metrics(report["overall"], metrics)
    return metrics


def update_binding(protocol, model, optimizer, config, data):
    checkpoint(optimizer, model)
    return {"schemaVersion": 1, "actorVersion": ACTOR, "modelSha256": sha(model), "data": data,
            "config": read(config), "learnerSha256": sha(Path(protocol["scripts"]) / "purchase_policy.py"),
            "torchVersion": protocol["numerical"]["torchVersion"], "pythonVersion": protocol["numerical"]["pythonVersion"],
            "optimizerFromSha256": sha(optimizer)}


def update_clock(path):
    """Keep the controller's original stage deadline across interrupted update attempts."""
    deadline = min(float(os.environ[name]) for name in
                   ("AOR_CAMPAIGN_DEADLINE_EPOCH", "AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH"))
    require(math.isfinite(deadline), "Update clock needs a finite controller deadline.")
    if not path.exists():
        document(path, {"startedEpoch": time.time(), "deadlineEpoch": deadline})
    clock = read(path)
    require(clock["deadlineEpoch"] == deadline and clock["startedEpoch"] <= time.time(),
            "Update cannot replace its original controller clock.")
    require(deadline > time.time(), "The original update-stage allowance has expired.")
    return deadline


def update(protocol, model, optimizer, config, collection, directory, data, decisions):
    binding = update_binding(protocol, model, optimizer, config, data)
    document(directory.with_suffix(".binding.json"), binding)
    deadline = update_clock(directory.with_suffix(".clock.json"))
    identity_path = directory / "identity.json"
    if identity_path.exists():
        identity = read(identity_path)
        require({key: value for key, value in identity.items() if key != "deadlineEpochSeconds"} == binding,
                "Cached learner identity changed.")
    if not (directory / "finished.json").exists():
        command = [protocol["numerical"]["executable"], str(Path(protocol["scripts"]) / "purchase_policy.py"),
                   "update", "--model", str(model), "--source", str(collection), "--config", str(config),
                   "--seed", str(read(config)["seed"]), "--output", str(directory), "--optimizer-from", str(optimizer)]
        if identity_path.exists():
            command.append("--resume")
        with directory.with_suffix(".log").open("ab") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False,
                timeout=max(.001, deadline - time.time()), env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1",
                    OMP_NUM_THREADS="1", OPENBLAS_NUM_THREADS="1", MKL_NUM_THREADS="1"))
        require(completed.returncode == 0, "Purchase PPO update failed; inspect its log before resuming.")
    identity, finished = read(identity_path), read(directory / "finished.json")
    payload = checkpoint(directory / "checkpoint.json", directory / "policy.json")
    require({key: value for key, value in identity.items() if key != "deadlineEpochSeconds"} == binding
            and payload["identitySha256"] == digest(identity)
            and payload["deadlineEpochSeconds"] == identity["deadlineEpochSeconds"], "Learner changed its input or original update identity.")
    expected = {"identitySha256": digest(identity), "inputModelSha256": sha(model),
        "outputModelSha256": sha(directory / "policy.json"), "games": data["games"], "decisions": decisions,
        "gameMeanReward": data["gameMeanReward"], "acceptedEpochs": payload["epoch"], "history": payload["history"],
        "stopReason": payload["stopReason"] or "epochs-complete", "elapsedSeconds": payload["elapsedSeconds"], "strengthClaim": False}
    require(all(finished.get(key) == value for key, value in expected.items())
            and payload["epoch"] == sum(row["accepted"] is True for row in payload["history"]),
            "Learner completion differs from its actor, Adam checkpoint or trajectories.")
    return finished


def collapsed(metrics):
    reward = metrics["rewardDifference"]
    return reward["mean"] < -.1 and reward["upper95"] is not None and reward["upper95"] < 0


def select_checkpoint(rows, checkpoints):
    require([row["batch"] for row in rows] == checkpoints, "Selection requires every declared checkpoint.")
    if any(collapsed(row["metrics"]) for row in rows):
        return None
    eligible = [row for row in rows if search.search_passes(row["metrics"])]
    return min(eligible, key=lambda row: (-row["metrics"]["winShareDifference"]["mean"], row["batch"])) if eligible else None


def stage_result(protocol, output, stage, passed, summary, metrics, artifacts):
    report = {"schemaVersion": 1, "stage": stage, "accepted": stage == "robustness" and passed,
              "summary": summary, "metrics": metrics, "profile": protocol["profile"],
              "qualificationConfidence": .99, "productionActivation": False, "humanStrengthVerified": False}
    path, text_path = output / f"{stage}-report.json", output / f"{stage}-report.txt"
    document(path, report)
    lines = [summary]
    for row in metrics.get("development", []):
        lines.append(f"Checkpoint {row['batch']}: development selection only.")
        lines.extend(evolution.estimates(row["metrics"]))
    if stage != "train":
        lines.extend(evolution.estimates(metrics))
    lines.append("No policy activated; human strength unverified. Ordinary F1, purchase prior F0 and rival P32 remain frozen.")
    content = "\n".join(lines) + "\n"
    require(not text_path.exists() or text_path.read_text() == content, "Completed purchase report changed.")
    text_path.write_text(content)
    return experiments.stage_result(output, stage, passed, summary, metrics, [*artifacts, path, text_path])


def train_stage(protocol, output):
    model, optimizer = protocol["purchasePolicy"], protocol["optimizerFrom"]
    development, artifacts = [], []
    for index in range(1, protocol["batches"] + 1):
        directory = output / "batches" / f"batch-{index:02}"
        receipt_path, source = directory / "receipt.json", directory / "collection-source.json"
        document(source, collection_source(protocol, model, index))
        descriptor = {"batch": index, "sourceSha256": sha(source), "policySha256": sha(model), "optimizerSha256": sha(optimizer)}
        if receipt_path.exists():
            receipt = read(receipt_path)
            require(receipt["descriptor"] == descriptor, "Cached continuation changed its actor/Adam chain.")
            evolution.verify_receipt(directory, receipt)
        else:
            experiments.native(protocol, "purchase-collect", source, directory / "collection", extra=("--workers", str(protocol["workers"])))
            data, decisions = verify_collection(protocol, source, directory / "collection")
            config = Path(protocol["configDirectory"]) / f"update-{index:02}.json"
            report = update(protocol, model, optimizer, config, directory / "collection", directory / "update", data, decisions)
            measured = None
            if index in protocol["checkpoints"]:
                arena = directory / "development-source.json"
                document(arena, arena_source(protocol, directory / "update/policy.json", "development"))
                experiments.native(protocol, "arena", arena, directory / "development")
                measured = {"batch": index, "policy": str(directory / "update/policy.json"),
                            "policySha256": sha(directory / "update/policy.json"),
                            "metrics": verify_arena(protocol, arena, directory / "development")}
            receipt = {"descriptor": descriptor, "update": report, "development": measured,
                       "artifacts": evolution.artifact_hashes(directory)}
            document(receipt_path, receipt)
        model, optimizer = str(directory / "update/policy.json"), str(directory / "update/checkpoint.json")
        artifacts.extend(sorted(path for path in directory.rglob("*") if path.is_file()))
        if receipt["development"] is not None:
            development.append(receipt["development"])
            if collapsed(development[-1]["metrics"]):
                return stage_result(protocol, output, "train", False,
                    "Development collapse guard stopped the whole continuation; no earlier actor qualifies.",
                    {"development": development, "collapsed": True}, artifacts)
        print(json.dumps({"batch": index, "batches": protocol["batches"], "acceptedEpochs": receipt["update"]["acceptedEpochs"],
                          "policySha256": sha(model), "developmentCheckpoint": receipt["development"] is not None}), flush=True)
    winner = select_checkpoint(development, protocol["checkpoints"])
    selection = {"development": development, "selected": winner, "productionActivation": False}
    if winner:
        selected = evolution.freeze_copy(winner["policy"], output / "selected/policy.json")
        selection.update(policy=str(selected), policySha256=sha(selected))
        artifacts.append(selected)
        for bank in ("confirmation", "robustness"):
            path = output / f"{bank}-source.json"
            document(path, arena_source(protocol, selected, bank))
            artifacts.append(path)
    document(output / "selection.json", selection)
    artifacts.append(output / "selection.json")
    return stage_result(protocol, output, "train", winner is not None,
        "One purchase actor frozen for independent F1+P32 confirmation." if winner else
        "No checkpoint passed both positive development means; final banks remain unopened.", selection, artifacts)


def final_stage(protocol, output, stage):
    selection = read(output / "selection.json")
    require(selection["selected"] and search.search_passes(selection["selected"]["metrics"])
            and sha(selection["policy"]) == selection["policySha256"], "Final evaluation requires the unchanged selected purchase actor.")
    if stage == "robustness":
        confirmation = read(output / "confirm-report.json")["metrics"]
        require(evolution.qualification_passes(protocol, confirmation, "confirm")
                and confirmation["policySha256"] == selection["policySha256"], "Robustness requires this actor's passed confirmation.")
    bank = "confirmation" if stage == "confirm" else "robustness"
    source, directory = output / f"{bank}-source.json", output / bank
    require(read(source) == arena_source(protocol, selection["policy"], bank), "Frozen final actor roles or bank changed.")
    experiments.native(protocol, "arena", source, directory)
    metrics = evolution.qualification_estimates(protocol, verify_arena(protocol, source, directory))
    passed = evolution.qualification_passes(protocol, metrics, stage)
    metrics.update(policySha256=selection["policySha256"], minimumPairs=evolution.minimum_pairs(protocol, stage))
    summary = (f"{bank.capitalize()} passed both 99% lower-bound gates versus F1+P32; no activation." if passed else
               f"{bank.capitalize()} did not qualify the purchase actor; retain F1+P32.")
    if protocol["profile"] == "smoke":
        summary = "Smoke completed; smoke never qualifies a purchase actor. Retain F1+P32."
    return stage_result(protocol, output, stage, passed, summary, metrics,
                        sorted(path for path in directory.rglob("*") if path.is_file()))


def run_stage(options):
    protocol = read(options.protocol)
    require(protocol.get("kind") == "automata-purchase-continuation" and protocol.get("schemaVersion") == 1,
            "Unsupported purchase continuation protocol.")
    require(protocol["seedBanks"] == seed_banks(protocol["seed"], PROFILES[protocol["profile"]], protocol["reservedSeeds"],
            protocol.get("reservedOffsets", []), protocol.get("conservativePairsPerOffset", 10000)),
            "Frozen continuation game banks changed.")
    require(Path(sys.executable).resolve() == Path(protocol["python"]["executable"])
            and sys.version == protocol["python"]["version"] and sha(sys.executable) == protocol["python"]["sha256"],
            "Prepared controller Python changed.")
    for path, expected in {**protocol["java"]["files"], **protocol["numerical"]["files"]}.items():
        require(sha(path) == expected, "Prepared numerical/JDK runtime changed: " + path)
    output = options.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    result = train_stage(protocol, output) if options.stage == "train" else final_stage(protocol, output, options.stage)
    print(json.dumps(result, sort_keys=True))
    return result


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    for name in ("from-campaign", "runtime", "baseline", "prior", "purchase-policy", "optimizer-from",
                 "numerical-python", "update-config", "output"):
        prep.add_argument("--" + name, type=Path, required=True)
    prep.add_argument("--opponent", type=Path, action="append", default=[])
    prep.add_argument("--robust-opponent", type=Path, action="append", default=[])
    prep.add_argument("--seed-audit", type=Path,
                      help="Required for search: frozen reservedSeeds and/or conservativelyExpandedOffsets with conservativePairsPerOffset.")
    prep.add_argument("--seed", type=int, required=True)
    prep.add_argument("--workers", type=int, default=6)
    prep.add_argument("--maximum-seconds", type=float, default=28800)
    prep.add_argument("--profile", choices=PROFILES, default="search")
    stage = commands.add_parser("stage")
    stage.add_argument("--protocol", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    stage.add_argument("--stage", choices=("train", "confirm", "robustness"), required=True)
    options = parser.parse_args(arguments)
    return {"prepare": prepare, "stage": run_stage}[options.command](options)


if __name__ == "__main__":
    main()
