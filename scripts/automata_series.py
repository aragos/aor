#!/usr/bin/env python3
"""Run a finite series of fresh-seed campaign replications without an LLM.

All stages share one campaign controller and its process ownership, resource budget and
original deadline. A per-campaign confirmation pass stops for review, never promotion.
Repeated per-campaign intervals do not establish a series-wide significance claim.
"""

import argparse
import json
from pathlib import Path
import shutil
import sys
from types import SimpleNamespace

import automata_experiments as experiments


STAGES = ("diagnostic", "token-trials", "select", "confirm")
read, sha, document, require = experiments.read, experiments.sha, experiments.document, experiments.require


def validate_seed_reservations(source_banks, child_banks):
    """Compare actual world seeds; different numeric offsets alone are insufficient."""
    seen = {seed for bank in source_banks.values() for seed in bank}
    for banks in child_banks:
        experiments.validate_banks(banks)
        values = {seed for bank in banks.values() for seed in bank}
        require(not seen.intersection(values), "Series game banks overlap a reserved world.")
        seen.update(values)


def decision(outcomes, max_campaigns):
    require(type(max_campaigns) is int and 1 <= max_campaigns <= 10,
            "A series must contain one through ten campaigns.")
    require(len(outcomes) <= max_campaigns and all(type(value) is bool for value in outcomes),
            "Series outcomes must be confirmation booleans within the declared count.")
    if any(outcomes):
        return {"continue": False, "reason": "candidate-ready-for-review"}
    if len(outcomes) == max_campaigns:
        return {"continue": False, "reason": "declared-campaigns-exhausted"}
    return {"continue": True, "reason": "next-predeclared-replication"}


def rebase_result(result, child_id, child_result_path, output):
    """Wrap a child receipt without modifying its resume/checkpoint-relative paths."""
    artifacts = {str(Path(child_id) / path): digest
                 for path, digest in result.get("artifacts", {}).items()}
    artifacts[str(child_result_path.relative_to(output))] = sha(child_result_path)
    return dict(result, artifacts=artifacts)


def prepare(options):
    source = options.from_campaign.expanduser().resolve(strict=True)
    source_spec = read(source / "campaign.json")
    # Refuse a modified source campaign before copying any of its inputs.
    for name, digest in source_spec["inputs"].items():
        require(sha(source / name) == digest, "Source campaign input changed: " + name)
    original = read(source / "protocol.json")
    require(original["kind"] == "automata-token-experiment", "Use a prepared token campaign.")
    decision([], options.campaigns)
    require(30 <= options.maximum_seconds <= 72 * 3600, "Series allowance must be 30s through 72h.")
    root = options.output.expanduser().resolve()
    require(not root.exists() or not any(root.iterdir()), "Prepare a new empty series directory.")
    require(source not in root.parents and root not in source.parents and root != source,
            "The series output must be separate from its frozen source campaign.")
    mode = experiments.learning_mode(getattr(options, "learning_mode", None)
                                     or original.get("learningMode", "REPLACEMENT"))
    runtime_override = getattr(options, "runtime", None)
    require(mode != "CORRECTION" or original.get("learningMode", "REPLACEMENT") == "CORRECTION"
            or runtime_override is not None,
            "Upgrading a replacement source to correction requires --runtime with a correction-capable distribution.")
    runtime = (runtime_override or Path(original["runtime"])).expanduser().resolve(strict=True)
    runtime_hashes = experiments.distribution_hashes(runtime)
    selected_profile = experiments.profile_name(options.profile, mode, "overnight")
    profile = experiments.PROFILES[selected_profile]
    seeds = [options.seed + index * 10 for index in range(options.campaigns)]
    banks = [experiments.seed_banks(seed, profile) for seed in seeds]
    validate_seed_reservations(original["seedBanks"], banks)
    root.mkdir(parents=True, exist_ok=True)
    scripts = root / "scripts"
    scripts.mkdir()
    for name in ("automata_series.py", "automata_campaign.py", "automata_experiments.py", "train-opponents.py"):
        shutil.copy2(Path(__file__).with_name(name), scripts / name)
    children = []
    for index, seed in enumerate(seeds):
        child_id = f"campaign-{index + 1:02d}"
        child = root / "prepared" / child_id
        diagnostic = read(original["diagnosticSource"]) if index == 0 and original["diagnosticSource"] else None
        diagnostic_policy = (next(policy for policy in diagnostic["policies"].values()
                                  if policy["kind"] == "territory-value-exploration-bundle")
                             if diagnostic else None)
        experiments.prepare(SimpleNamespace(
            output=child, runtime=runtime, baseline=Path(original["baseline"]),
            java=original["java"]["executable"], opponent=[Path(path) for path in original["opponents"][1:]],
            territory_value_bundle=Path(diagnostic_policy["path"]) if diagnostic else None,
            frozen_card_purchase_bundle=Path(diagnostic_policy["frozenCardPurchaseBundle"]) if diagnostic else None,
            frozen_purchase_policy=Path(diagnostic["frozenPurchasePolicy"]) if diagnostic else None,
            profile=selected_profile, learning_mode=mode, seed=seed, workers=options.workers,
            maximum_seconds=options.maximum_seconds,
        ))
        require(experiments.distribution_hashes(child / "runtime") == runtime_hashes,
                "Series runtime changed during preparation.")
        children.append({"id": child_id, "directory": str(child), "seed": seed})
    plan = {"schemaVersion": 1, "kind": "automata-campaign-series", "children": children,
            "maximumCampaigns": options.campaigns, "sourceCampaign": str(source),
            "sourceCampaignSha256": sha(source / "campaign.json"),
            "sourceProtocolSha256": sha(source / "protocol.json"),
            "sourceLearningMode": original.get("learningMode", "REPLACEMENT"),
            "learningMode": mode, "profile": selected_profile,
            "baselineSha256": sha(original["baseline"]),
            "sourceRuntime": original["runtime"], "runtimeSource": str(runtime),
            "runtimeSha256": runtime_hashes,
            "stopRule": "First confirmation pass stops for review; otherwise exhaust the finite queue.",
            "interpretation": "Fresh-seed replications of fixed recipes; not autonomous algorithm invention.",
            "confirmationCaveat": "Per-campaign intervals are unadjusted for multiple campaigns; no series-wide strength claim.",
            "budget": "One series-wide deadline; child stage limits remain backstops, not renewed campaign allowances.",
            "productionActivation": False}
    document(root / "series-plan.json", plan)
    stages, pins, previous_gate = [], {}, None
    for child in children:
        child_root, child_id = Path(child["directory"]), child["id"]
        spec = read(child_root / "campaign.json")
        for name, digest in spec["inputs"].items():
            path = str((child_root / name).resolve())
            require(path not in pins or pins[path] == digest, "Conflicting frozen series inputs.")
            pins[path] = digest
        pins[str(child_root / "campaign.json")] = sha(child_root / "campaign.json")
        base = [sys.executable, str(scripts / "automata_series.py")]
        for stage in spec["stages"]:
            dependencies = [f'{child_id}-{name}' for name in stage["dependsOn"]]
            if previous_gate:
                dependencies.append(previous_gate)
            stages.append(dict(stage, id=f'{child_id}-{stage["id"]}', dependsOn=dependencies,
                command=base + ["stage", "--protocol", str(child_root / "protocol.json"),
                                "--child-id", child_id, "--stage", stage["id"], "--output", "{output}"],
                result=f'results/{child_id}-{stage["id"]}.json'))
        gate_id = child_id + "-gate"
        stages.append({"id": gate_id, "dependsOn": [previous_gate] if previous_gate else [],
            "command": base + ["gate", "--plan", str(root / "series-plan.json"),
                                "--child-id", child_id, "--output", "{output}"],
            "result": f"results/{gate_id}.json", "maximumSeconds": 60, "maximumAttempts": 2})
        previous_gate = gate_id
    for path in [root / "series-plan.json", *scripts.glob("*.py")]:
        pins[str(path)] = sha(path)
    limits = dict(spec["limits"], maximumSeconds=options.maximum_seconds)
    document(root / "series.json", {"schemaVersion": 1, "kind": "automata-campaign",
                                    "inputs": pins, "limits": limits, "stages": stages})
    print(root / "series.json")


def stage(options):
    output = options.output.resolve()
    require(options.child_id in [f"campaign-{index:02d}" for index in range(1, 11)],
            "A series child id must be campaign-01 through campaign-10.")
    child = output / options.child_id
    experiments.run_stage(SimpleNamespace(protocol=options.protocol, output=child, stage=options.stage))
    result_path = child / "results" / (options.stage + ".json")
    wrapped = rebase_result(read(result_path), options.child_id, result_path, output)
    document(output / "results" / f"{options.child_id}-{options.stage}.json", wrapped)


def gate(options):
    output = options.output.resolve()
    plan = read(options.plan)
    state = read(output / "campaign-state.json")
    children = plan["children"]
    index = next(i for i, child in enumerate(children) if child["id"] == options.child_id)
    outcomes, summaries = [], []
    for child in children[:index + 1]:
        child_id = child["id"]
        entries = {name: state["stages"][f"{child_id}-{name}"] for name in STAGES}
        require(all(entry["status"] in ("passed", "rejected", "skipped") for entry in entries.values()),
                "Only a completed scientific campaign may advance the series.")
        passed = entries["confirm"]["status"] == "passed"
        outcomes.append(passed)
        summaries.append({"campaign": child_id, "confirmationPassed": passed,
                          "stages": {name: entry["status"] for name, entry in entries.items()}})
    outcome = decision(outcomes, plan["maximumCampaigns"])
    report = dict(outcome, completedCampaigns=len(outcomes), campaigns=summaries,
                  productionActivation=False, interpretation=plan["confirmationCaveat"])
    receipt = output / "results" / f"{options.child_id}-gate.json"
    document(receipt, {"schemaVersion": 1, "status": "passed" if outcome["continue"] else "rejected",
                       "summary": outcome["reason"], "metrics": report})
    # Mutable convenience reports are not immutable receipts consumed by subsequent stages.
    document(output / "series-report.json", report, immutable=False)
    lines = ["Campaign series: " + outcome["reason"],
             f"Completed {len(outcomes)} of at most {plan['maximumCampaigns']} campaigns.",
             "No LLM calls or production activation.", plan["confirmationCaveat"]]
    lines += [row["campaign"] + ": confirmation " + ("passed; review needed" if row["confirmationPassed"] else "not passed")
              for row in summaries]
    (output / "series-report.txt").write_text("\n".join(lines) + "\n")


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("--from-campaign", type=Path, required=True)
    preparation.add_argument("--output", type=Path, required=True)
    preparation.add_argument("--seed", type=int, required=True)
    preparation.add_argument("--campaigns", type=int, default=3)
    preparation.add_argument("--profile", choices=tuple(experiments.PROFILES),
                             help="Defaults to overnight for replacement and correction for correction learning.")
    preparation.add_argument("--learning-mode", choices=("replacement", "correction"),
                             help="Inherit the source mode unless overridden; legacy sources use replacement.")
    preparation.add_argument("--runtime", type=Path,
                             help="Override the source distribution; required when upgrading replacement to correction.")
    preparation.add_argument("--workers", type=int, default=4)
    preparation.add_argument("--maximum-seconds", type=int, default=86400)
    execution = commands.add_parser("stage")
    execution.add_argument("--protocol", type=Path, required=True)
    execution.add_argument("--stage", choices=STAGES, required=True)
    review = commands.add_parser("gate")
    review.add_argument("--plan", type=Path, required=True)
    for command in (execution, review):
        command.add_argument("--child-id", required=True)
        command.add_argument("--output", type=Path, required=True)
    options = parser.parse_args(arguments)
    {"prepare": prepare, "stage": stage, "gate": gate}[options.operation](options)


if __name__ == "__main__":
    main()
