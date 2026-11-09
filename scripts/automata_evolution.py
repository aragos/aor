#!/usr/bin/env python3
"""Freeze a finite adaptive whole-game campaign with a fixed P32 purchase actor.

At most four rounds explore five small policy modules around the current training
parent. Selection may replace that provisional parent, but only one final model is
opened against independent confirmation and held-out opponents. The existing
campaign controller owns the original deadlines, process group and resource caps.
Nothing in this program activates a policy or claims verified human strength.
"""

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import shutil
import sys

import automata_experiments as experiments
import automata_policy_search as search


read, sha, document, require = experiments.read, experiments.sha, experiments.document, experiments.require
PREFIX = "planning."
TOKEN = PREFIX + "tokenValue."
LEADER = PREFIX + "leaderTiming"
RETENTION = PREFIX + "retainedLeader"
TOKEN_GATES = (PREFIX + "completeTokenAmounts", PREFIX + "peacefulTokenMargin")
GATES = (TOKEN + "enabled", search.GATE, LEADER, PREFIX + "targetTieBreak", *TOKEN_GATES)
MODULES = {
    "commodity": tuple(PREFIX + name for name in (
        "heldCommodity", "publicCommodity", "retainedCommodity", "commodityOption",
        "reachableRetention", "reachableCommodityOption")),
    "expansion": tuple(PREFIX + name for name in (
        "heldCommodityBundle", "publicCommodityBundle", "commodityDenial")),
    "leader": (LEADER, RETENTION),
    "events": (PREFIX + "areaOwnLoss", PREFIX + "areaOpponentLoss"),
}
PROFILES = {
    "smoke": {"rounds": 1, "screeningPairs": 2, "selectionPairs": 4,
              "confirmationPairs": 4, "robustnessPairs": 4},
    "search": {"rounds": 4, "screeningPairs": 48, "selectionPairs": 192,
               "confirmationPairs": 1024, "robustnessPairs": 512},
}
BUNDLE_CONFIGURATION = {"playerCount": 4, "map": "STANDARD", "initialCard": "NO_CRUSADES",
                        "deckModification": True, "immediateMasterArtDiscard": True}


def validate_model(model):
    weights = model.get("weights")
    require(model.get("schemaVersion") == 1 and isinstance(weights, dict)
            and all(isinstance(name, str) and type(value) in (int, float)
                    and math.isfinite(value) and abs(value) <= 1e6
                    for name, value in weights.items()), "Policy weights must be finite schema-one numbers.")
    require(all(weights.get(name, 0) in (0, 1) for name in GATES),
            "Existing policy gates must remain exactly zero or one.")
    require(weights.get(search.GATE, 0) == 0, "Adaptive evolution requires tokenCorrection off.")
    return weights


def changed_weights(initial, candidate):
    return {name: value for name, value in candidate.items() if initial.get(name, 0) != value}


def proposals(model, radius=1.0):
    """Twenty local proposals plus an intact anchor; only approved modules may change.

    Radius starts at one (10–20% coefficient changes), halves after a failed
    round, and also scales small positive activations. Exact gates never scale.
    Leader-off ablation is included once the parent enables leader timing.
    """
    weights = validate_model(model)
    require(type(radius) in (int, float) and math.isfinite(radius) and 0 < radius <= 1,
            "Proposal radius must be finite and in (0, 1].")
    token_keys = tuple(sorted(name for name in weights if name.startswith(TOKEN)
                              and name != TOKEN + "enabled"))
    allowed = dict(MODULES, token=(*token_keys, *TOKEN_GATES))
    raw = [("anchor", {})]
    for index, delta in enumerate((-.2, -.1, .1, .2)):
        factor = 1 + delta * radius
        commodity = {name: weights[name] * factor for name in MODULES["commodity"]
                     if weights.get(name, 0) != 0}
        if index >= 2:
            commodity.update({name: (.025 if index == 2 else .075) * radius
                              for name in MODULES["commodity"][-2:]
                              if weights.get(name, 0) == 0})
        raw.append(("commodity", commodity))
        raw.append(("expansion", {
            name: weights[name] * factor if weights.get(name, 0) != 0 else
            (.025, .05, .1, .2)[index] * radius
            for name in MODULES["expansion"]}))
        enabled = weights.get(LEADER, 0) == 1
        retention = weights.get(RETENTION, 0)
        leader = ({LEADER: 0.0} if enabled and index == 0 else {
            LEADER: 1.0,
            RETENTION: ((0, .05, .15, .3)[index] * radius if not enabled else
                        max(0, retention * factor + (.025 * radius if retention == 0 else 0)
                            * index))})
        raw.append(("leader", leader))
        raw.append(("events", {
            name: weights[name] * factor if weights.get(name, 0) > 0 else
            ((.025, .05), (.05, .025), (.1, .05), (.05, .1))[index][position] * radius
            for position, name in enumerate(MODULES["events"])}))
        token = {name: weights[name] * factor for name in token_keys
                 if index < 2 or ".v2." in name}
        if index >= 2:
            gate = TOKEN_GATES[index - 2]
            token[gate] = 1.0 - weights.get(gate, 0)
        raw.append(("token", token))
    result, seen = [], set()
    for module, changes in raw:
        changes = changed_weights(weights, changes)
        candidate = dict(model, weights=dict(weights, **changes))
        validate_model(candidate)
        require(module == "anchor" or set(changes) <= set(allowed[module]),
                "Proposal changed weights outside its declared module.")
        fingerprint = json.dumps(candidate["weights"], sort_keys=True, allow_nan=False)
        if fingerprint in seen:
            continue
        seen.add(fingerprint)
        result.append({"candidateId": len(result), "module": module,
                       "changes": changed_weights(weights, candidate["weights"]), "model": candidate})
    require(2 <= len(result) <= 21, "A round requires two through twenty-one distinct candidates.")
    return result


def seed_banks(seed, profile, source_banks):
    banks = {f"round-{index}-{name}": experiments.arena_seeds(seed + 4 * index + offset, profile[count])
             for index in range(profile["rounds"])
             for offset, (name, count) in enumerate((("screening", "screeningPairs"),
                                                    ("selection", "selectionPairs")))}
    banks.update({name: experiments.arena_seeds(seed + offset, profile[count])
                  for name, offset, count in (("confirmation", 20, "confirmationPairs"),
                                               ("robustness", 21, "robustnessPairs"))})
    experiments.validate_banks(banks)
    reserved = {value for values in source_banks.values() for value in values}
    require(not reserved.intersection(value for bank in banks.values() for value in bank),
            "Adaptive banks overlap the source campaign's reserved worlds.")
    return banks


def freeze_copy(source, target):
    if target.exists():
        require(sha(source) == sha(target), "Frozen copied input changed: " + str(target))
        return target
    return experiments.copy_input(source, target, [])


def model_key(path):
    return json.dumps(validate_model(read(path)), sort_keys=True, allow_nan=False)


def prepare(options):
    source = options.from_campaign.expanduser().resolve(strict=True)
    original, old_runtime, old_hashes = search.source_inputs(source)
    runtime = options.runtime.expanduser().resolve(strict=True)
    require((runtime / "lib/training.jar").is_file(), "Use a complete new training distribution.")
    root = options.output.expanduser().resolve()
    require(all(root != path and root not in path.parents and path not in root.parents
                for path in (source, runtime)), "Prepared output must be separate from both source and runtime.")
    require(not root.exists() or not any(root.iterdir()), "Prepare a new empty evolution directory.")
    require(type(options.workers) is int and 1 <= options.workers <= 4,
            "Adaptive campaigns support one through four workers.")
    maximum = options.maximum_seconds
    require(type(maximum) in (int, float) and math.isfinite(maximum) and 30 <= maximum <= 43200,
            "Campaign allowance must be 30 seconds through twelve hours.")
    profile = PROFILES[options.profile]
    banks = seed_banks(options.seed, profile, original["seedBanks"])
    baseline_model = read(original["baseline"])
    validate_model(baseline_model)
    bundle = read(options.purchase_bundle)
    require(bundle.get("configuration") == BUNDLE_CONFIGURATION
            and bundle.get("baseline", {}).get("weights") == baseline_model["weights"]
            and bundle.get("provenance", {}).get("sourceBaselineSha256") == sha(original["baseline"])
            and bundle.get("provenance", {}).get("qualificationPassed") is True
            and isinstance(bundle.get("model"), dict),
            "The qualified P32 bundle must bind the original baseline and exact supported configuration.")
    training = [Path(original["baseline"]), *options.opponent]
    robust = options.robust_opponent
    training_keys, robust_keys = [model_key(path) for path in training], [model_key(path) for path in robust]
    require(training_keys and robust_keys and len(training_keys) == len(set(training_keys))
            and len(robust_keys) == len(set(robust_keys)) and not set(training_keys).intersection(robust_keys),
            "Training and robustness pools must be nonempty, internally distinct and mutually disjoint.")
    require(len(training) <= 16 and len(robust) <= 16, "Opponent pools support at most sixteen models each.")
    java = experiments.java_identity(original["java"]["executable"])
    require(java["files"] == original["java"]["files"], "Source JDK identity changed.")
    hashes = experiments.distribution_hashes(runtime)
    root.mkdir(parents=True, exist_ok=True)
    provenance = []
    shutil.copytree(runtime, root / "runtime", symlinks=False)
    require(hashes == experiments.distribution_hashes(runtime)
            == experiments.distribution_hashes(root / "runtime"), "New runtime changed during preparation.")
    for name in ("automata_evolution.py", "automata_policy_search.py", "automata_experiments.py",
                 "automata_campaign.py", "train-opponents.py"):
        experiments.copy_input(Path(__file__).with_name(name), root / "scripts" / name, provenance)
    baseline = experiments.copy_input(original["baseline"], root / "inputs/baseline.json", provenance)
    purchase_bundle = experiments.copy_input(options.purchase_bundle, root / "inputs/purchase-bundle.json", provenance)
    prior, policy = root / "inputs/purchase-prior.json", root / "inputs/purchase-policy.json"
    document(prior, bundle["baseline"])
    document(policy, bundle["model"])
    opponents = [baseline, *[experiments.copy_input(path, root / f"inputs/opponent-{index}.json", provenance)
                            for index, path in enumerate(options.opponent)]]
    robust_opponents = [experiments.copy_input(path, root / f"inputs/robust-opponent-{index}.json", provenance)
                        for index, path in enumerate(robust)]
    document(root / "provenance.json", {
        "sourceCampaign": str(source), "sourceCampaignSha256": sha(source / "campaign.json"),
        "sourceProtocolSha256": sha(source / "protocol.json"), "copiedInputs": provenance,
        "originalRuntime": str(old_runtime), "originalRuntimeSha256": old_hashes,
        "runtimeOverride": str(runtime), "runtimeSha256": hashes})
    protocol = {
        "schemaVersion": 1, "kind": "automata-evolution", "profile": options.profile,
        "preparedAt": datetime.now(timezone.utc).isoformat(), "configuration": experiments.CONFIGURATION,
        "python": {"executable": str(Path(sys.executable).resolve()), "version": sys.version,
                   "sha256": sha(sys.executable)}, "java": java, "runtime": str(root / "runtime"),
        "baseline": str(baseline), "baselineSha256": sha(baseline), "purchaseBundle": str(purchase_bundle),
        "purchaseBundleSha256": sha(purchase_bundle), "frozenPurchase": {"baseline": str(prior), "policy": str(policy)},
        "frozenPurchaseSha256": {"baseline": sha(prior), "policy": sha(policy)},
        "opponents": list(map(str, opponents)), "robustOpponents": list(map(str, robust_opponents)),
        "workers": options.workers, "heapMiB": 1536, "seed": options.seed, "seedBanks": banks,
        "sourceSeedBanks": original["seedBanks"], **profile, "finalists": 4,
        "maximumCandidates": 21, "maximumConsecutiveFailures": 2, "initialRadius": 1.0,
        "minimumRoundSeconds": 10 if options.profile == "smoke" else 300,
        "seedNote": "Disjoint from the supplied source campaign and every bank in this campaign; external historical banks are not certified.",
        "gates": {"evolve": "Different final parent and last accepted selection win-share AND composite mean > 0.",
                  "confirm": "One final model versus original baseline; 1024 fresh worlds, both lower95 > 0.",
                  "robustness": "Same final model versus original baseline; 512 fresh worlds with held-out opponents, both lower95 > 0; smoke never qualifies."},
        "scope": "Five bounded policy modules; P32 purchase actor and its prior frozen for every seat.",
        "productionActivation": False, "humanStrengthVerified": False,
        "intervalMethod": "Approximate 95% normal intervals over independent paired game worlds.",
    }
    document(root / "protocol.json", protocol)
    inputs = {str(path.relative_to(root)): sha(path) for path in sorted(root.rglob("*")) if path.is_file()}
    inputs.update(java["files"])
    inputs[str(Path(sys.executable).resolve())] = sha(sys.executable)
    names = ("evolve", "confirm", "robustness")
    campaign = {"schemaVersion": 1, "kind": "automata-campaign", "inputs": inputs,
                "limits": {"maximumSeconds": maximum, "maximumDataBytes": 4 * experiments.GIB,
                           "minimumFreeBytes": 2 * experiments.GIB, "maximumRssBytes": 3 * experiments.GIB},
                "stages": [{"id": name, "dependsOn": [] if index == 0 else [names[index - 1]],
                            "command": [str(Path(sys.executable).resolve()),
                                        str(root / "scripts/automata_evolution.py"), "stage", "--protocol",
                                        str(root / "protocol.json"), "--output", "{output}", "--stage", name],
                            "result": f"results/{name}.json", "maximumSeconds": maximum * fraction,
                            "maximumAttempts": 2}
                           for index, (name, fraction) in enumerate(zip(names, (2 / 3, 1 / 6, 1 / 6)))]}
    document(root / "campaign.json", campaign)
    print(root / "campaign.json")
    return campaign


def arena_source(protocol, candidate, name):
    robust = name == "robustness"
    source = experiments.arena_source(protocol["baseline"],
        protocol["robustOpponents" if robust else "opponents"], protocol["seed"] + (21 if robust else 20),
        protocol["robustnessPairs" if robust else "confirmationPairs"], candidate, "CONFIRMATION")
    return dict(source, frozenPurchase=protocol["frozenPurchase"])


def verify_purchase(protocol, directory, reports):
    expected = {name: {"path": str(Path(path).resolve()), "sha256": protocol["frozenPurchaseSha256"][name],
                       "snapshot": f"frozen-purchase-{name}.json"}
                for name, path in protocol["frozenPurchase"].items()}
    require(all(report.get("frozenPurchase") == expected for report in reports),
            "Native report changed the frozen purchase actor or its prior.")
    for item in expected.values():
        require(sha(directory / item["snapshot"]) == item["sha256"], "Frozen purchase snapshot changed.")


def verify_snapshot(supplied, actual):
    require(all(actual.get(name) == value for name, value in supplied.items()),
            "Native model snapshot changed supplied weights.")


def verify_search(protocol, source, directory, pool, round_index):
    report, state, run = (read(directory / name) for name in ("search.json", "state.json", "run.json"))
    finalists = min(protocol["finalists"], len(pool))
    require(report["complete"] is True and report["selectionOnly"] is True
            and report["kind"] == state["kind"] == run["kind"] == "strategy-search"
            and report["signature"] == state["signature"] == run["signature"]
            and state["phase"] == "complete" and (directory / "finished.json").is_file()
            and report["source"] == str(source) and report["workers"] == protocol["workers"]
            and report["candidates"] == len(pool) and report["finalists"] == finalists
            and report["sourceParameters"] == [], "Native search has not completed its frozen specification.")
    verify_purchase(protocol, directory, (report, run))
    initial, incumbent = (read(directory / name)["weights"] for name in ("initial.json", "incumbent.json"))
    verify_snapshot(pool[0]["model"]["weights"], initial)
    require(initial == incumbent, "The current-parent anchor differs from its incumbent.")
    for index, opponent in enumerate(protocol["opponents"]):
        verify_snapshot(read(opponent)["weights"], read(directory / f"opponent-{index}.json")["weights"])
    screening = report["screening"]
    require(len(screening) == len(pool) and {row["candidateId"] for row in screening} == set(range(len(pool))),
            "Screening must cover every frozen candidate exactly once.")
    require(len(report["selection"]) == finalists and {row["candidateId"] for row in report["selection"]}
            == {row["candidateId"] for row in screening[:finalists]}, "Selection changed its frozen finalists.")
    for offset, bank in enumerate(("screening", "selection")):
        seeds = protocol["seedBanks"][f"round-{round_index}-{bank}"]
        require(report["banks"][bank] == {"purpose": "SCREENING",
                "seedOffset": protocol["seed"] + 4 * round_index + offset,
                "pairsPerConfiguration": len(seeds), "seeds": seeds}, "Native search world bank changed.")
        rows = report[bank]
        require(rows == sorted(rows, key=search.ranking), "Native candidate ranking changed.")
        for row in rows:
            candidate_id = row["candidateId"]
            proposal = pool[candidate_id]
            require(row["label"] == ("current" if candidate_id == 0 else f"sample-{candidate_id}")
                    and row["parameters"] == proposal["changes"]
                    and row["model"] == f"candidate-{candidate_id}.json"
                    and read(directory / row["model"])["weights"] == dict(initial, **proposal["changes"]),
                    "Native candidate differs from its frozen module proposal.")
            pairs = [read(directory / f"{bank}-candidate-{candidate_id}-pair-{index}.json")
                     for index in range(len(seeds))]
            for index, pair in enumerate(pairs):
                search.validate_pair(pair, index, seeds[index], len(protocol["opponents"]))
                require(search.same_game(pair["incumbent"], read(directory / f"{bank}-incumbent-{index}.json")),
                        "Search did not use its shared incumbent outcome.")
                require(candidate_id != 0 or search.same_game(pair["candidate"], pair["incumbent"]),
                        "Current-parent anchor failed exact incumbent gameplay parity.")
            require(row["overall"]["pairs"] == len(seeds), "Native candidate bank is incomplete.")
            search.verify_metrics(row["overall"], search.comparison(pairs))
    selected = report["selection"][0]
    require(report["selectedCandidateId"] == state["selectedCandidateId"] == selected["candidateId"]
            and read(directory / "candidate.json") == read(directory / selected["model"]),
            "Native selected model differs from the selection ranking.")
    return selected


def artifact_hashes(directory):
    return {str(path.relative_to(directory)): sha(path) for path in sorted(directory.rglob("*"))
            if path.is_file() and path.name != "receipt.json"}


def verify_receipt(directory, receipt):
    require(receipt["artifacts"] == artifact_hashes(directory), "Cached round artifacts changed.")


def estimates(metrics):
    lines = []
    for label, name, scale, unit in (("Win-share", "winShareDifference", 100, " pp"),
                                     ("Composite", "rewardDifference", 1, "")):
        value = metrics[name]
        bounds = (f"[{value['lower95'] * scale:+.4f}, {value['upper95'] * scale:+.4f}]{unit}"
                  if value["lower95"] is not None else "unavailable")
        lines.append(f"{label}: {value['mean'] * scale:+.4f}{unit}; 95% interval {bounds}; "
                     f"{value['count']} paired worlds.")
    return lines


def progress(output, summary, rounds):
    lines = [summary, "Training selection remains provisional; independent confirmation is required."]
    for row in rounds:
        lines.append(f"Round {row['round'] + 1}: {row['decision']}; module {row['module']}; radius {row['radius']:g}.")
        lines.extend(estimates(row["selection"]))
    lines.extend(["Frozen P32 purchase actor and prior on every seat.", "No policy activated; human strength unverified."])
    document(output / "evolution-progress.json", {"summary": summary, "rounds": rounds}, immutable=False)
    (output / "evolution-progress.txt").write_text("\n".join(lines) + "\n")


def round_transition(parent, radius, failures, candidate, metrics):
    accepted = search.search_passes(metrics) and model_key(candidate) != model_key(parent)
    return {"parent": str(candidate if accepted else parent), "radius": radius if accepted else radius / 2,
            "failures": 0 if accepted else failures + 1, "accepted": accepted}


def report_result(protocol, output, stage, passed, summary, metrics, artifacts):
    report = {"schemaVersion": 1, "stage": stage, "accepted": stage == "robustness" and passed,
              "summary": summary, "metrics": metrics, "profile": protocol["profile"],
              "productionActivation": False, "humanStrengthVerified": False,
              "seedNote": protocol["seedNote"], "intervalMethod": protocol["intervalMethod"]}
    report_path, text_path = output / f"{stage}-report.json", output / f"{stage}-report.txt"
    document(report_path, report)
    lines = [summary]
    if stage == "evolve":
        for row in metrics["rounds"]:
            lines.append(f"Round {row['round'] + 1}: {row['decision']}; module {row['module']}.")
            lines.extend(estimates(row["selection"]))
        lines.append("Selection intervals are descriptive; the adaptive parent is unqualified.")
    else:
        lines.extend(estimates(metrics))
    lines.extend(["No policy activated; human strength unverified.", protocol["seedNote"]])
    content = "\n".join(lines) + "\n"
    require(not text_path.exists() or text_path.read_text() == content, "Frozen completed report changed.")
    text_path.write_text(content)
    return experiments.stage_result(output, stage, passed, summary, metrics, [*artifacts, report_path, text_path])


def evolve_stage(protocol, output):
    parent, radius, failures = protocol["baseline"], protocol["initialRadius"], 0
    summaries, artifacts, last_accepted = [], [], None
    stop = "Maximum declared rounds completed."
    for index in range(protocol["rounds"]):
        directory = output / "rounds" / f"round-{index}"
        receipt_path = directory / "receipt.json"
        source = directory / "source.json"
        if not receipt_path.exists() and not source.exists() and experiments.remaining_seconds() < protocol["minimumRoundSeconds"]:
            stop = "Original evolve-stage allowance cannot admit another round; confirmation reserve retained."
            break
        pool = proposals(read(parent), radius)
        paths = [directory / f"proposal-{row['candidateId']}.json" for row in pool]
        for path, row in zip(paths, pool):
            document(path, row["model"])
        descriptor = {"round": index, "parent": str(parent), "parentSha256": sha(parent), "radius": radius,
                      "proposals": [{key: value for key, value in row.items() if key != "model"}
                                    for row in pool]}
        document(directory / "proposals.json", descriptor)
        native_source = experiments.arena_source(parent, protocol["opponents"], protocol["seed"] + 4 * index,
                                                  protocol["screeningPairs"], paths[0])
        native_source.update(candidateModels=list(map(str, paths)), candidates=len(pool),
                             screeningPairs=protocol["screeningPairs"], selectionPairs=protocol["selectionPairs"],
                             finalists=min(protocol["finalists"], len(pool)), frozenPurchase=protocol["frozenPurchase"])
        document(source, native_source)
        if receipt_path.exists():
            receipt = read(receipt_path)
            verify_receipt(directory, receipt)
            require(receipt["descriptor"] == descriptor, "Cached round differs from its adaptive parent.")
        else:
            progress(output, f"Round {index + 1}/{protocol['rounds']}: evaluating {len(pool)} candidates across "
                     "commodity, expansion, leader, events and token modules.", summaries)
            native = directory / "search"
            experiments.native(protocol, "strategy-search", source, native,
                               extra=("--workers", str(protocol["workers"])))
            selected = verify_search(protocol, source, native, pool, index)
            selected_id = selected["candidateId"]
            transition = round_transition(parent, radius, failures, paths[selected_id], selected["overall"])
            summary = {"round": index, "radius": radius, "selected": selected_id,
                       "module": pool[selected_id]["module"], "selection": selected["overall"],
                       "accepted": transition["accepted"], "zeroAnchorParity": True,
                       "decision": "provisional parent accepted" if transition["accepted"] else "rejected; radius halved",
                       "modules": {module: [{"candidateId": row["candidateId"], "overall": row["overall"]}
                                             for row in read(native / "search.json")["screening"]
                                             if pool[row["candidateId"]]["module"] == module]
                                   for module in ("anchor", *MODULES, "token")}}
            document(directory / "summary.json", summary)
            receipt = {"descriptor": descriptor, "summary": summary, "transition": transition,
                       "artifacts": artifact_hashes(directory)}
            document(receipt_path, receipt)
        summary, transition = receipt["summary"], receipt["transition"]
        expected = round_transition(parent, radius, failures, paths[summary["selected"]], summary["selection"])
        require(transition == expected and read(directory / "summary.json") == summary,
                "Cached adaptive transition changed.")
        if transition["accepted"]:
            last_accepted = summary
        parent, radius, failures = transition["parent"], transition["radius"], transition["failures"]
        summaries.append(summary)
        artifacts.extend(sorted(path for path in directory.rglob("*") if path.is_file()))
        progress(output, f"Round {index + 1} complete: {summary['decision']}.", summaries)
        if failures >= protocol["maximumConsecutiveFailures"]:
            stop = "Two consecutive rounds failed; adaptive search stopped."
            break
    passed = last_accepted is not None and model_key(parent) != model_key(protocol["baseline"])
    selection = {"schemaVersion": 1, "rounds": summaries, "stopReason": stop,
                 "lastAccepted": last_accepted, "confirmationInspected": False, "productionActivation": False}
    if passed:
        candidate = freeze_copy(parent, output / "selected/candidate.json")
        selection.update(candidate=str(candidate), candidateSha256=sha(candidate))
        for name in ("confirmation", "robustness"):
            path = output / f"{name}-source.json"
            document(path, arena_source(protocol, candidate, name))
            artifacts.append(path)
        artifacts.append(candidate)
    document(output / "selection.json", selection)
    artifacts.append(output / "selection.json")
    summary = ("One final training parent frozen for fresh confirmation against the original baseline. " if passed else
               "No qualifying provisional parent; final banks remain unopened. ") + stop
    progress(output, summary, summaries)
    return report_result(protocol, output, "evolve", passed, summary, selection, artifacts)


def qualification_passes(protocol, metrics, stage):
    minimum = 1024 if stage == "confirm" else 512
    return protocol["profile"] != "smoke" and all(
        metrics[name]["count"] >= minimum and metrics[name]["lower95"] is not None
        and metrics[name]["lower95"] > 0 for name in ("winShareDifference", "rewardDifference"))


def final_stage(protocol, output, stage):
    selection = read(output / "selection.json")
    require(selection["lastAccepted"] is not None and search.search_passes(selection["lastAccepted"]["selection"])
            and sha(selection["candidate"]) == selection["candidateSha256"]
            and model_key(selection["candidate"]) != model_key(protocol["baseline"]),
            "Final evaluation requires the one unchanged positively selected candidate.")
    if stage == "robustness":
        confirmation = read(output / "confirm-report.json")
        require(qualification_passes(protocol, confirmation["metrics"], "confirm")
                and confirmation["metrics"]["candidateSha256"] == selection["candidateSha256"],
                "Robustness requires the same candidate's passed confirmation gate.")
    name = "confirmation" if stage == "confirm" else "robustness"
    source = output / f"{name}-source.json"
    expected = arena_source(protocol, selection["candidate"], name)
    require(read(source) == expected, "Final source differs from its frozen candidate or world bank.")
    directory = output / name
    experiments.native(protocol, "arena", source, directory)
    experiments.verify_arena(directory, source)
    report, state, run = (read(directory / path) for path in ("evaluation.json", "state.json", "run.json"))
    require(state["kind"] == run["kind"] == "strategy-arena"
            and state["signature"] == run["signature"]
            and run["source"] == str(source) and run["workers"] == protocol["workers"],
            "Native final arena changed its frozen identity.")
    verify_purchase(protocol, directory, (report, run))
    for label, path in (("candidate", selection["candidate"]), ("incumbent", protocol["baseline"])):
        verify_snapshot(read(path)["weights"], read(directory / f"{label}.json")["weights"])
    for index, opponent in enumerate(expected["opponents"]):
        verify_snapshot(read(opponent)["weights"], read(directory / f"opponent-{index}.json")["weights"])
    seeds = protocol["seedBanks"][name]
    pairs = [read(directory / f"pair-{index}.json") for index in range(len(seeds))]
    for index, pair in enumerate(pairs):
        search.validate_pair(pair, index, seeds[index], len(expected["opponents"]))
    metrics = search.comparison(pairs)
    require(report["overall"]["pairs"] == len(seeds), "Final arena bank is incomplete.")
    search.verify_metrics(report["overall"], metrics)
    passed = qualification_passes(protocol, metrics, stage)
    metrics.update(candidateSha256=selection["candidateSha256"], minimumPairs=1024 if stage == "confirm" else 512)
    summary = (f"{name.capitalize()} passed both independent lower95 gates; "
               + ("same frozen model advances to held-out opponents." if stage == "confirm" else
                  "candidate is ready for offline review.") if passed else
               f"{name.capitalize()} did not qualify the candidate; keep the incumbent.")
    if protocol["profile"] == "smoke":
        summary = "Smoke evaluation completed; smoke never qualifies a model. Keep the incumbent."
    return report_result(protocol, output, stage, passed, summary, metrics,
                         sorted(path for path in directory.rglob("*") if path.is_file()))


def run_stage(options):
    protocol = read(options.protocol)
    require(protocol.get("schemaVersion") == 1 and protocol.get("kind") == "automata-evolution",
            "Unsupported adaptive evolution protocol.")
    require(protocol["seedBanks"] == seed_banks(protocol["seed"], PROFILES[protocol["profile"]],
                                               protocol["sourceSeedBanks"]), "Frozen adaptive world banks changed.")
    require(Path(sys.executable).resolve() == Path(protocol["python"]["executable"])
            and sys.version == protocol["python"]["version"] and sha(sys.executable) == protocol["python"]["sha256"],
            "Prepared Python identity changed.")
    for path, digest in protocol["java"]["files"].items():
        require(sha(path) == digest, "Prepared Java identity changed: " + path)
    require(sha(protocol["baseline"]) == protocol["baselineSha256"]
            and sha(protocol["purchaseBundle"]) == protocol["purchaseBundleSha256"]
            and all(sha(path) == protocol["frozenPurchaseSha256"][name]
                    for name, path in protocol["frozenPurchase"].items()), "Frozen incumbent or P32 actor changed.")
    output = options.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    result = evolve_stage(protocol, output) if options.stage == "evolve" else final_stage(protocol, output, options.stage)
    print(json.dumps(result, sort_keys=True))
    return result


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--from-campaign", type=Path, required=True)
    prep.add_argument("--runtime", type=Path, required=True)
    prep.add_argument("--purchase-bundle", type=Path, required=True)
    prep.add_argument("--opponent", type=Path, action="append", default=[])
    prep.add_argument("--robust-opponent", type=Path, action="append", required=True)
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--seed", type=int, required=True)
    prep.add_argument("--workers", type=int, default=4)
    prep.add_argument("--profile", choices=PROFILES, default="search")
    prep.add_argument("--maximum-seconds", type=float, default=43200)
    stage = commands.add_parser("stage")
    stage.add_argument("--protocol", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    stage.add_argument("--stage", choices=("evolve", "confirm", "robustness"), required=True)
    options = parser.parse_args(arguments)
    return {"prepare": prepare, "stage": run_stage}[options.command](options)


if __name__ == "__main__":
    main()
