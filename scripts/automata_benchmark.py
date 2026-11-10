#!/usr/bin/env python3
"""Package and measure frozen JVM simulation on macOS/Linux, without fitting or activation.

Bundle paths are relative; each execution separately pins its host JDK and Python. Reused
worlds measure worker/platform parity, never independent playing-strength evidence. The
existing campaign supervisor owns one-attempt deadlines, process groups and sampled limits.
"""

import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import time

sys.dont_write_bytecode = True
import automata_campaign as controller


GIB = 1024 ** 3
COUNTS = {"collection": 256, "arena": 128}
WORKERS = (4, 6, 8, 10)
SCRIPTS = ("automata_benchmark.py", "automata_campaign.py", "train-opponents.py")
read, sha, document = controller.read_json, controller.sha256, controller.atomic_json


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    allow_nan=False).encode()).hexdigest()


def relative(root, name):
    """Resolve a bundle/output member without allowing absolute paths or symlink escapes."""
    path = Path(name)
    require(not path.is_absolute() and ".." not in path.parts and path.parts,
            "Expected a confined relative file path: " + str(name))
    target = root / path
    require(root.resolve() in target.resolve().parents and not target.is_symlink(),
            "File escapes its declared root: " + str(name))
    return target


def tree_hashes(root):
    paths = sorted(root.rglob("*"))
    require(not any(path.is_symlink() for path in paths), "Evidence cannot contain symlinks.")
    return {str(path.relative_to(root)): sha(path) for path in paths if path.is_file()}


def verify_files(root, files):
    require(isinstance(files, dict) and files, "A nonempty file hash manifest is required.")
    for name, expected in files.items():
        require(sha(relative(root, name)) == expected, "Frozen file changed: " + name)


def worlds(offset, count):
    require(type(offset) is int and 0 <= offset <= 100000000,
            "Native seed offsets must be integers from zero through 100000000.")
    return [-1000000000 + offset + index * 8191 for index in range(count)]


def verify_audit(audit, offsets):
    require(audit.get("allIntersectionsZero") is True,
            "A completed historical seed audit is required before preparation.")
    banks = audit["seedBanks"]
    require(set(banks) == set(COUNTS), "Audit must reserve exactly the two benchmark banks.")
    all_worlds = []
    for name, count in COUNTS.items():
        bank = banks[name]
        require(bank["count"] == count and bank["nativeOffset"] == offsets[name]
                and bank["seeds"] == worlds(offsets[name], count)
                and bank["historicalIntersectionCount"] == 0
                and bank["otherChallengeBankIntersectionCount"] == 0,
                "Audit does not bind the exact fresh benchmark bank: " + name)
        all_worlds.extend(bank["seeds"])
    require(len(set(all_worlds)) == sum(COUNTS.values()), "Benchmark banks overlap.")


def sources(offsets, opponents):
    common = {"schemaVersion": 1, "opponents": opponents}
    configuration = "players-4/standard/no-crusades/deck-true/art-true"
    return {
        "collection": dict(common, configuration=configuration, games=COUNTS["collection"],
                           seed=offsets["collection"], baseline="../inputs/ordinary.json",
                           sharedPurchaseBaseline="../inputs/prior.json",
                           policy="../inputs/candidate.json",
                           opponentPurchasePolicy="../inputs/candidate.json"),
        "arena": dict(common, configurations=[configuration], pairsPerConfiguration=COUNTS["arena"],
                      seed=offsets["arena"], candidate="../inputs/ordinary.json",
                      incumbent="../inputs/ordinary.json", purpose="SCREENING",
                      frozenPurchase={"baseline": "../inputs/prior.json",
                                      "policy": "../inputs/candidate.json",
                                      "candidatePolicy": "../inputs/candidate.json",
                                      "incumbentPolicy": "../inputs/incumbent.json"}),
    }


def prepare(options):
    source, target = options.campaign.resolve(), options.output.resolve()
    require(not target.exists(), "Prepare into a new bundle directory.")
    protocol = read(source / "protocol.json")
    campaign = read(source / "campaign.json")
    state = read(source / "run/campaign-state.json")
    require(protocol.get("kind") == "automata-purchase-continuation"
            and state.get("status") == "completed"
            and all(state["stages"][name]["status"] == "passed"
                    for name in ("train", "confirm", "robustness")),
            "Use a completed qualified purchase generation as immutable source provenance.")
    require(sha(source / "protocol.json") == campaign["inputs"]["protocol.json"],
            "Source protocol changed.")
    offsets = {"collection": options.collection_offset, "arena": options.arena_offset}
    audit = read(options.seed_audit)
    verify_audit(audit, offsets)
    target.mkdir(parents=True)
    provenance = {}

    def frozen_copy(path, name, expected=None):
        path = Path(path).resolve(strict=True)
        actual = sha(path)
        require(expected is None or actual == expected, "Source file changed: " + str(path))
        destination = relative(target, name)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        require(sha(destination) == actual, "Copied file changed: " + name)
        provenance[name] = {"sourcePath": str(path), "sha256": actual}

    def source_copy(path, name):
        path = Path(path)
        key = str(path.relative_to(source))
        require(key in campaign["inputs"], "Source campaign did not freeze: " + key)
        frozen_copy(path, name, campaign["inputs"][key])

    for key, name in (("baseline", "ordinary"), ("prior", "prior"),
                      ("purchasePolicy", "incumbent")):
        source_copy(protocol[key], "inputs/" + name + ".json")
    selected = "selected/policy.json"
    candidate_sha = state["stages"]["train"]["artifacts"][selected]
    frozen_copy(source / "run" / selected, "inputs/candidate.json", candidate_sha)
    opponents = []
    for index, path in enumerate(protocol["opponents"]):
        name = f"inputs/opponent-{index}.json"
        source_copy(path, name)
        opponents.append("../" + name)
    jars = sorted((Path(protocol["runtime"]) / "lib").glob("*.jar"))
    require(any(path.name == "training.jar" for path in jars), "Frozen training JAR is missing.")
    for path in jars:
        source_copy(path, "runtime/lib/" + path.name)
    for name in SCRIPTS:
        frozen_copy(Path(__file__).with_name(name), "scripts/" + name)
    for name in ("protocol.json", "campaign.json", "provenance.json",
                 "run/campaign-state.json", "run/train-report.json",
                 "run/confirm-report.json", "run/robustness-report.json"):
        frozen_copy(source / name, "provenance/" + name)
    frozen_copy(options.seed_audit, "provenance/seed-audit.json")
    (target / "sources").mkdir()
    for name, value in sources(offsets, opponents).items():
        document(target / "sources" / (name + ".json"), value)
    document(target / "provenance/source-files.json", provenance)
    manifest = {"schemaVersion": 1, "kind": "automata-portable-benchmark", "files": tree_hashes(target),
                "sourceCampaignSha256": sha(source / "campaign.json"), "counts": COUNTS,
                "seedOffsets": offsets, "heapMiB": 1536, "workerSweep": list(WORKERS),
                "limits": {"maximumSeconds": 7200, "stageSeconds": 900,
                           "maximumRssBytes": 4 * GIB, "maximumDataBytes": 4 * GIB,
                           "minimumFreeBytes": 2 * GIB},
                "worldReuse": "Worker sweeps, platforms and two-world smoke intentionally reuse "
                              "these worlds for parity; never use them as qualification evidence.",
                "training": False, "activation": False}
    document(target / "manifest.json", manifest)
    print(json.dumps({"bundle": str(target), "manifestSha256": sha(target / "manifest.json")}))


def bundle_manifest(bundle):
    manifest = read(bundle / "manifest.json")
    require(manifest.get("schemaVersion") == 1
            and manifest.get("kind") == "automata-portable-benchmark", "Unknown benchmark bundle.")
    verify_files(bundle, manifest["files"])
    declared_jars = {name for name in manifest["files"] if name.startswith("runtime/lib/")}
    actual_jars = {str(path.relative_to(bundle)) for path in (bundle / "runtime/lib").iterdir()}
    require(actual_jars == declared_jars, "Runtime directory contains unpinned dependencies.")
    return manifest


def java_identity(value):
    executable = Path(shutil.which(str(value)) or str(value)).resolve(strict=True)
    version = subprocess.run([str(executable), "-version"], capture_output=True,
                             text=True, check=True, timeout=10)
    text = (version.stdout + version.stderr).strip()
    match = re.search(r'version "(\d+)', text)
    require(match and int(match.group(1)) >= 21, "Use a local JDK 21 or newer.")
    home = executable.parent.parent
    paths = (executable, home / "release", home / "lib/modules")
    require(all(path.is_file() for path in paths), "Use the JDK bin/java executable.")
    return {"executable": str(executable), "version": text,
            "files": {str(path): sha(path) for path in paths}}


def canonical_record(value, mode):
    """Ignore only collection identity and documented native wall-clock measurements."""
    result = copy.deepcopy(value)
    if mode == "collection":
        result.pop("signature", None)
    else:
        for side in ("candidate", "incumbent"):
            result[side].pop("elapsedMillis", None)
    return result


def behavior_records(output, mode, count, offset):
    prefix = "game" if mode == "collection" else "pair"
    paths = list(output.glob(prefix + "-*.json"))
    require(len(paths) == count, "Benchmark did not produce the exact declared world count.")
    records = []
    for index, seed in enumerate(worlds(offset, count)):
        value = read(output / f"{prefix}-{index}.json")
        require(value["index"] == index and value["gameSeed" if mode == "collection" else "seed"] == seed,
                "Benchmark record belongs to another world.")
        records.append({"index": index, "seed": seed, "sha256": digest(canonical_record(value, mode))})
    return records


def execute_stage(options):
    bundle, output = options.bundle.resolve(), options.output.resolve()
    manifest = bundle_manifest(bundle)
    source = bundle / "sources" / (options.mode + ".json")
    spec = read(source)
    count = 2 if options.smoke else COUNTS[options.mode]
    output.mkdir(parents=True)
    if options.smoke:
        # Preserve relative model resolution when writing the reduced source beside native output.
        for name in ("baseline", "sharedPurchaseBaseline", "policy", "opponentPurchasePolicy",
                     "candidate", "incumbent"):
            if name in spec:
                spec[name] = str((source.parent / spec[name]).resolve())
        spec["opponents"] = [str((source.parent / path).resolve()) for path in spec["opponents"]]
        if "frozenPurchase" in spec:
            spec["frozenPurchase"] = {name: str((source.parent / path).resolve())
                                      for name, path in spec["frozenPurchase"].items()}
        spec["games" if options.mode == "collection" else "pairsPerConfiguration"] = count
        source = output / "smoke-source.json"
        document(source, spec)
    native = output / "native"
    remaining = float(os.environ["AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH"]) - time.time()
    require(remaining > 0, "Original stage deadline has expired.")
    command = [options.java, f'-Xmx{manifest["heapMiB"]}m',
               f"-XX:ActiveProcessorCount={options.workers}", "-XX:ParallelGCThreads=1",
               "-XX:ConcGCThreads=1", "-XX:+ExitOnOutOfMemoryError",
               "-Xlog:gc:file=gc.log:time,uptime,level,tags", "-cp", str(bundle / "runtime/lib/*"),
               "dev.aragos.aor.training.TrainingMainKt", "--mode",
               "purchase-collect" if options.mode == "collection" else "arena",
               "--source", str(source), "--output", str(native),
               "--workers", str(options.workers), "--hours", str(min(12, remaining / 3600))]
    environment = dict(os.environ, AOR_TRAINING_MACHINE_CPUS=str(os.cpu_count() or 1))
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(name, None)
    started = time.monotonic()
    subprocess.run(command, cwd=output, env=environment, check=True)
    elapsed = time.monotonic() - started
    require((native / "finished.json").is_file(), "Native benchmark has no completion receipt.")
    records = behavior_records(native, options.mode, count, manifest["seedOffsets"][options.mode])
    result = {"schemaVersion": 1, "status": "passed", "summary": "Fixed simulation workload completed.",
              "metrics": {"mode": options.mode, "workers": options.workers, "worlds": count,
                          "games": count * (2 if options.mode == "arena" else 1),
                          "nativeSeconds": elapsed, "worldsPerSecond": count / elapsed,
                          "records": records},
              "artifacts": {str((output / name).relative_to(Path(os.environ["AOR_CAMPAIGN_OUTPUT"]))): value
                            for name, value in tree_hashes(output).items()}}
    document(output / "result.json", result)


def comparisons(rows, reference=None):
    expected = {} if reference is None else reference
    failures = []
    for name, row in rows.items():
        mode = row["mode"]
        if mode not in expected:
            expected[mode] = row["records"]
        elif expected[mode] != row["records"]:
            failures.append(name)
    return expected, failures


def completed_rows(state):
    return {name: dict(entry["metrics"], peakRssBytes=entry["peakRssBytes"])
            for name, entry in state["stages"].items() if entry["status"] == "passed"}


def verify_reference(path, bundle_hash, smoke=False):
    report = read(path / "report.json")
    require(report["bundleSha256"] == bundle_hash
            and report["status"] == "passed" and report["smoke"] == smoke,
            "Reference must be a successful run of the exact bundle and workload.")
    spec, state = read(path / "execution.json"), read(path / "run/campaign-state.json")
    controller.verify_completed(spec, state, path / "run")
    require(all(entry["status"] == "passed" for entry in state["stages"].values())
            and len(state["stages"]) == len(spec["stages"])
            and report["results"] == completed_rows(state)
            and report["environmentSha256"] == sha(path / "environment.json"),
            "Reference report no longer matches its completed execution evidence.")
    expected, failures = comparisons(report["results"])
    require(not failures and report["behaviors"] == expected,
            "Reference per-world behavior summary changed.")
    return report


def run(options):
    started = time.monotonic()
    bundle, output = options.bundle.resolve(), options.output.resolve()
    manifest = bundle_manifest(bundle)
    require(not output.exists() and bundle not in output.parents,
            "Use a new output directory outside the immutable bundle.")
    hardware = controller.processes.detect_hardware()
    maximum = controller.processes.resource_budget(hardware).workers
    requested = options.workers or list(WORKERS)
    require(len(requested) == len(set(requested)) and all(type(value) is int and value > 0 for value in requested),
            "Worker counts must be distinct positive integers.")
    workers = [value for value in requested if value <= maximum]
    require(workers, "None of the requested worker counts is supported by this host.")
    java = java_identity(options.java)
    output.mkdir(parents=True)
    environment = {"schemaVersion": 1, "platform": platform.platform(), "machine": platform.machine(),
                   "logicalCpus": hardware.cpus, "memoryBytes": hardware.memory_bytes,
                   "python": {"executable": str(Path(sys.executable).resolve()), "version": sys.version,
                              "sha256": sha(Path(sys.executable).resolve())}, "java": java,
                   "requestedWorkers": requested, "workers": workers,
                   "skippedWorkers": [value for value in requested if value not in workers]}
    document(output / "environment.json", environment)
    reference_path = options.reference
    bundled_reference = bundle / "reference/report.json"
    if reference_path is None and bundled_reference.exists() and not options.smoke:
        verify_files(bundle / "reference", read(bundle / "reference-manifest.json"))
        reference_path = bundled_reference
    reference = (verify_reference(Path(reference_path).parent, sha(bundle / "manifest.json"), options.smoke)
                 if reference_path else None)
    limits = {name: value for name, value in manifest["limits"].items() if name != "stageSeconds"}
    limits["maximumRssBytes"] = min(limits["maximumRssBytes"], int(hardware.memory_bytes * .8))
    stages = []
    for worker in workers:
        for mode in COUNTS:
            identifier = f"workers-{worker}-{mode}"
            command = [sys.executable, str(bundle / "scripts/automata_benchmark.py"), "_stage",
                       "--bundle", str(bundle), "--output", "{output}/" + identifier,
                       "--java", java["executable"], "--workers", str(worker), "--mode", mode]
            if options.smoke:
                command.append("--smoke")
            stages.append({"id": identifier, "command": command, "maximumAttempts": 1,
                           "dependsOn": [stages[-1]["id"]] if stages else [],
                           "maximumSeconds": manifest["limits"]["stageSeconds"],
                           "result": identifier + "/result.json"})
    inputs = {str(bundle / name): value for name, value in manifest["files"].items()}
    inputs.update(java["files"])
    inputs[str(bundle / "manifest.json")] = sha(bundle / "manifest.json")
    inputs[str(output / "environment.json")] = sha(output / "environment.json")
    if reference_path:
        inputs[str(Path(reference_path).resolve())] = sha(Path(reference_path))
    spec = {"schemaVersion": 1, "kind": "automata-campaign", "limits": limits,
            "inputs": inputs, "stages": stages}
    document(output / "execution.json", spec)
    code = controller.main(["--spec", str(output / "execution.json"), "--output", str(output / "run")])
    state = read(output / "run/campaign-state.json")
    rows = completed_rows(state)
    expected, failures = comparisons(rows, copy.deepcopy(reference["behaviors"]) if reference else None)
    passed = code == 0 and len(rows) == len(stages) and not failures
    report = {"schemaVersion": 1, "bundleSha256": sha(bundle / "manifest.json"),
              "status": "passed" if passed else "failed", "smoke": options.smoke,
              "totalSeconds": time.monotonic() - started,
              "environmentSha256": sha(output / "environment.json"), "results": rows,
              "behaviors": expected, "parityFailures": failures,
              "referenceSha256": sha(Path(reference_path)) if reference_path else None,
              "parityScope": "Complete controlled purchase trajectories and terminal results; "
                             "arena paired terminal outcomes and decision counts only.",
              "strengthClaim": False, "training": False, "activation": False}
    document(output / "report.json", report)
    print(json.dumps({"status": report["status"], "report": str(output / "report.json"),
                      "skippedWorkers": environment["skippedWorkers"], "parityFailures": failures}))
    return 0 if passed else 1


def export(options):
    bundle, reference, archive = options.bundle.resolve(), options.reference.resolve(), options.archive.resolve()
    require(bundle not in archive.parents and reference not in archive.parents,
            "Write the archive outside both its bundle and reference inputs.")
    require(not (bundle / "reference").exists(), "Export from the original bundle without embedded references.")
    bundle_manifest(bundle)
    verify_reference(reference, sha(bundle / "manifest.json"))
    require(not archive.exists(), "Archive already exists; preserve prior exports.")
    reference_files = tree_hashes(reference)
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "x:gz") as stream:
        stream.add(bundle, arcname="aor-benchmark", recursive=True)
        stream.add(reference, arcname="aor-benchmark/reference", recursive=True)
        payload = (json.dumps(reference_files, indent=2, sort_keys=True) + "\n").encode()
        info = tarfile.TarInfo("aor-benchmark/reference-manifest.json")
        info.size = len(payload)
        stream.addfile(info, io.BytesIO(payload))
    print(json.dumps({"archive": str(archive), "sha256": sha(archive), "bytes": archive.stat().st_size}))


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    for name in ("campaign", "seed-audit", "output"):
        prep.add_argument("--" + name, type=Path, required=True)
    prep.add_argument("--collection-offset", type=int, required=True)
    prep.add_argument("--arena-offset", type=int, required=True)
    for name in ("run", "_stage"):
        command = sub.add_parser(name)
        for field in ("bundle", "output"):
            command.add_argument("--" + field, type=Path, required=True)
        command.add_argument("--java", default="java")
        command.add_argument("--smoke", action="store_true")
        if name == "run":
            command.add_argument("--workers", type=int, nargs="+")
            command.add_argument("--reference", type=Path)
        else:
            command.add_argument("--workers", type=int, required=True)
            command.add_argument("--mode", choices=tuple(COUNTS), required=True)
    package = sub.add_parser("export")
    for name in ("bundle", "reference", "archive"):
        package.add_argument("--" + name, type=Path, required=True)
    options = parser.parse_args(arguments)
    return {"prepare": prepare, "run": run, "_stage": execute_stage, "export": export}[options.command](options) or 0


if __name__ == "__main__":
    raise SystemExit(main())
