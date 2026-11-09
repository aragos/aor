#!/usr/bin/env python3
"""Freeze a finite whole-game token correction search and one held-out confirmation.

The existing campaign controller owns process groups, resource limits and the original
deadline. Native strategy-search evaluates the correction repeatedly throughout each game.
No stage activates a policy. Smoke runs exercise the pipeline and can never qualify a model.
"""

import argparse
from datetime import datetime, timezone
import math
from pathlib import Path
import shutil
import sys

import automata_experiments as experiments


read, sha, document, require = experiments.read, experiments.sha, experiments.document, experiments.require
PREFIX = "planning.tokenCorrection."
GATE = PREFIX + "enabled"
PARAMETERS = [
    {"name": PREFIX + name, "min": -bound, "max": bound}
    for name, bound in (
        ("v1.costFraction", .3),
        ("v1.affordablePeaceful", .5),
        ("v1.affordableContested", .5),
        ("v2.plan.heldCommodity", .5),
        ("v2.plan.income", .5),
        ("v2.plan.uncommittedTokens", .3),
    )
]
PROFILES = {
    "smoke": {"candidates": 4, "screeningPairs": 2, "finalists": 2,
              "selectionPairs": 4, "confirmationPairs": 4},
    "search": {"candidates": 32, "screeningPairs": 64, "finalists": 4,
               "selectionPairs": 256, "confirmationPairs": 512},
}
GAME_FIELDS = ("wins", "rewards", "ranks", "scores", "decisions")


def zero_correction(model):
    """Enable the separate gate without changing any incumbent coefficient or token head."""
    weights = model.get("weights")
    require(isinstance(weights, dict), "The incumbent must contain model weights.")
    require(all(type(value) in (int, float) and math.isfinite(value)
                for value in weights.values()), "Incumbent weights must be finite numbers.")
    require(weights.get(GATE, 0) == 0, "Source baseline must have a disabled correction head.")
    require(all(value == 0 for name, value in weights.items()
                if name.startswith(PREFIX) and name != GATE),
            "Source baseline has nonzero dormant correction coefficients; cannot preserve a zero anchor.")
    return dict(model, weights=dict(weights, **{GATE: 1.0}))


def seed_banks(seed, profile, source_banks):
    banks = {name: experiments.arena_seeds(seed + offset, profile[count])
             for offset, (name, count) in enumerate((
                 ("screening", "screeningPairs"), ("selection", "selectionPairs"),
                 ("confirmation", "confirmationPairs")))}
    experiments.validate_banks(banks)
    reserved = {value for values in source_banks.values() for value in values}
    require(not reserved.intersection(value for values in banks.values() for value in values),
            "New game banks overlap the source campaign's reserved worlds.")
    return banks


def source_inputs(source):
    """Verify declared bytes and require every reused source asset to be among those pins."""
    specification = read(source / "campaign.json")
    require(specification.get("kind") == "automata-campaign", "Use a frozen source campaign.")
    pins = {(source / name).resolve(): digest for name, digest in specification["inputs"].items()}
    for path, digest in pins.items():
        require(sha(path) == digest, "Source campaign input changed: " + str(path))
    protocol_path = source / "protocol.json"
    require(protocol_path in pins, "The source protocol must be a frozen campaign input.")
    original = read(protocol_path)
    require(original.get("kind") == "automata-token-experiment"
            and original.get("learningMode") == "CORRECTION",
            "Use a prepared correction campaign with its original runtime.")
    runtime = Path(original["runtime"]).resolve(strict=True)
    require((runtime / "lib/training.jar").is_file(), "Source runtime lacks lib/training.jar.")
    runtime_hashes = experiments.distribution_hashes(runtime)
    reused = [Path(original["baseline"]), *map(Path, original["opponents"]),
              *(runtime / name for name in runtime_hashes),
              *map(Path, original["java"]["files"])]
    require(all(path.resolve() in pins for path in reused),
            "Every reused model, runtime and JDK file must be pinned by the source campaign.")
    require(sha(original["baseline"]) == original["baselineSha256"],
            "Source baseline differs from its protocol hash.")
    return original, runtime, runtime_hashes


def prepare(options):
    source = options.from_campaign.expanduser().resolve(strict=True)
    original, runtime, runtime_hashes = source_inputs(source)
    root = options.output.expanduser().resolve()
    require(root != source and source not in root.parents and root not in source.parents,
            "Prepared output must be separate from its frozen source campaign.")
    require(not root.exists() or not any(root.iterdir()), "Prepare a new empty search directory.")
    require(type(options.workers) is int and 1 <= options.workers <= 4,
            "Whole-game campaigns support one through four workers.")
    maximum = options.maximum_seconds
    require(type(maximum) in (int, float) and math.isfinite(maximum) and 30 <= maximum <= 14400,
            "Campaign allowance must be 30 seconds through four hours.")
    profile = PROFILES[options.profile]
    banks = seed_banks(options.seed, profile, original["seedBanks"])
    zero = zero_correction(read(original["baseline"]))
    java = experiments.java_identity(original["java"]["executable"])
    require(java["files"] == original["java"]["files"], "Source JDK identity changed.")
    root.mkdir(parents=True, exist_ok=True)
    provenance = []
    shutil.copytree(runtime, root / "runtime", symlinks=False)
    require(runtime_hashes == experiments.distribution_hashes(runtime)
            == experiments.distribution_hashes(root / "runtime"),
            "Runtime changed during preparation; use a fresh directory.")
    for name in ("automata_policy_search.py", "automata_experiments.py", "automata_campaign.py",
                 "train-opponents.py"):
        experiments.copy_input(Path(__file__).with_name(name), root / "scripts" / name, provenance)
    baseline = experiments.copy_input(original["baseline"], root / "inputs/baseline.json", provenance)
    opponents = [experiments.copy_input(path, root / f"inputs/opponent-{index}.json", provenance)
                 for index, path in enumerate(original["opponents"])]
    initial = root / "inputs/zero-correction.json"
    document(initial, zero)
    search_source = root / "sources/search.json"
    native_source = experiments.arena_source(baseline, opponents, options.seed,
                                             profile["screeningPairs"], initial)
    native_source.update(parameters=PARAMETERS,
                         **{key: value for key, value in profile.items() if key != "confirmationPairs"})
    document(search_source, native_source)
    document(root / "provenance.json", {
        "sourceCampaign": str(source), "sourceCampaignSha256": sha(source / "campaign.json"),
        "sourceProtocolSha256": sha(source / "protocol.json"), "copiedInputs": provenance,
        "runtimeSha256": runtime_hashes,
    })
    protocol = {
        "schemaVersion": 1, "kind": "automata-policy-search", "profile": options.profile,
        "preparedAt": datetime.now(timezone.utc).isoformat(), "configuration": experiments.CONFIGURATION,
        "scope": "Six token correction coefficients around the intact incumbent, evaluated in complete games.",
        "python": {"executable": str(Path(sys.executable).resolve()), "version": sys.version,
                   "sha256": sha(sys.executable)},
        "java": java, "runtime": str(root / "runtime"), "baseline": str(baseline),
        "baselineSha256": sha(baseline), "initial": str(initial), "initialSha256": sha(initial),
        "opponents": list(map(str, opponents)), "workers": options.workers, "heapMiB": 1536,
        "searchSource": str(search_source), "parameters": PARAMETERS, **profile,
        "seed": options.seed, "seedBanks": banks, "sourceSeedBanks": original["seedBanks"],
        "seedNote": "Disjoint from the supplied source campaign only; no claim covering all external historical banks.",
        "gates": {"search": "Selected fresh selection mean win-share AND composite reward both > 0.",
                  "confirmation": "One candidate, at least 512 independent worlds, win-share AND composite lower95 > 0; smoke never qualifies."},
        "zeroAnchor": "All screening wins/rewards/ranks/scores/decisions exactly match the incumbent; elapsedMillis excluded.",
        "minimumConfirmationPairs": 512, "productionActivation": False,
        "intervalMethod": "Approximate 95% normal intervals over independent paired game worlds.",
    }
    document(root / "protocol.json", protocol)
    inputs = {str(path.relative_to(root)): sha(path) for path in sorted(root.rglob("*")) if path.is_file()}
    inputs.update(java["files"])
    inputs[str(Path(sys.executable).resolve())] = sha(sys.executable)
    stages = [{"id": name, "dependsOn": [] if name == "search" else ["search"],
               "command": [str(Path(sys.executable).resolve()), str(root / "scripts/automata_policy_search.py"),
                           "stage", "--protocol", str(root / "protocol.json"),
                           "--output", "{output}", "--stage", name],
               "result": f"results/{name}.json", "maximumSeconds": maximum * fraction,
               "maximumAttempts": 2}
              for name, fraction in (("search", .75), ("confirm", .25))]
    campaign = {"schemaVersion": 1, "kind": "automata-campaign", "inputs": inputs,
                "limits": {"maximumSeconds": maximum, "maximumDataBytes": 4 * experiments.GIB,
                           "minimumFreeBytes": 2 * experiments.GIB, "maximumRssBytes": 3 * experiments.GIB},
                "stages": stages}
    document(root / "campaign.json", campaign)
    print(root / "campaign.json")
    return campaign


def same_game(first, second):
    return all(first[field] == second[field] for field in GAME_FIELDS)


def validate_pair(pair, index, seed, opponents):
    require(pair["index"] == index and pair["seed"] == seed and pair["seat"] == index % 4
            and pair["configuration"] == experiments.CONFIGURATION
            and pair["opponentIndices"] == [(index // 4 + seat) % opponents for seat in range(4)],
            "Native pair differs from its frozen world/seat/opponent bank.")
    for game in (pair["candidate"], pair["incumbent"]):
        require(all(len(game[name]) == 4 and all(type(value) in (int, float) and math.isfinite(value)
                                               for value in game[name])
                    for name in ("wins", "rewards", "ranks"))
                and len(game["scores"]) == 4 and type(game["decisions"]) is int
                and game["decisions"] > 0, "Native pair requires complete finite terminal results.")


def comparison(pairs):
    return {metric: experiments.interval([pair["candidate"][field][pair["seat"]]
                                         - pair["incumbent"][field][pair["seat"]] for pair in pairs])
            for metric, field in (("rewardDifference", "rewards"), ("winShareDifference", "wins"))}


def verify_metrics(reported, calculated):
    for name, interval in calculated.items():
        for field, value in interval.items():
            actual = reported[name][field]
            require(actual is None if value is None else
                    type(actual) in (int, float) and math.isfinite(actual)
                    and math.isclose(actual, value, rel_tol=1e-10, abs_tol=1e-12),
                    "Native aggregate disagrees with its paired public returns.")


def ranking(row):
    return (-row["overall"]["winShareDifference"]["mean"],
            -row["overall"]["rewardDifference"]["mean"], row["candidateId"])


def verify_search(protocol, directory):
    report, state, run = (read(directory / name) for name in ("search.json", "state.json", "run.json"))
    require(report["complete"] is True and report["selectionOnly"] is True
            and report["kind"] == state["kind"] == run["kind"] == "strategy-search"
            and report["signature"] == state["signature"] == run["signature"]
            and state["phase"] == "complete" and (directory / "finished.json").is_file()
            and report["source"] == protocol["searchSource"]
            and report["workers"] == protocol["workers"]
            and report["candidates"] == protocol["candidates"]
            and report["finalists"] == protocol["finalists"],
            "Native search has not completed its frozen specification.")
    initial = read(directory / "initial.json")["weights"]
    incumbent = read(directory / "incumbent.json")["weights"]
    supplied = read(protocol["baseline"])["weights"]
    require(all(incumbent.get(name) == value for name, value in supplied.items())
            and initial == dict(incumbent, **{GATE: 1.0}),
            "Native search changed incumbent weights outside the correction gate.")
    controlled = {item["name"]: item for item in protocol["parameters"]}
    screening = report["screening"]
    require(len(screening) == protocol["candidates"]
            and {row["candidateId"] for row in screening} == set(range(protocol["candidates"])),
            "Search screening must contain every frozen candidate exactly once.")
    require(len(report["selection"]) == protocol["finalists"]
            and {row["candidateId"] for row in report["selection"]}
            == {row["candidateId"] for row in screening[:protocol["finalists"]]},
            "Search selection must contain the screening finalists exactly once.")
    zero = next(row for row in screening if row["candidateId"] == 0)
    require(zero["label"] in ("zero", "zero-and-center") and all(value == 0 for value in zero["parameters"].values()),
            "Native search must include the zero correction anchor.")
    for offset, bank in enumerate(("screening", "selection")):
        seeds = protocol["seedBanks"][bank]
        declared = report["banks"][bank]
        require(declared == {"purpose": "SCREENING", "seedOffset": protocol["seed"] + offset,
                             "pairsPerConfiguration": len(seeds), "seeds": seeds},
                "Native search bank differs from the frozen world seeds.")
        rows = report[bank]
        require(rows == sorted(rows, key=ranking), "Native search ranking differs from its declared rule.")
        for row in rows:
            candidate_id = row["candidateId"]
            parameters = row["parameters"]
            require(set(parameters) == set(controlled) and all(
                type(value) in (int, float) and math.isfinite(value)
                and controlled[name]["min"] <= value <= controlled[name]["max"]
                for name, value in parameters.items()), "Candidate differs from the frozen search bounds.")
            require(row["model"] == f"candidate-{candidate_id}.json"
                    and read(directory / row["model"])["weights"] == dict(initial, **parameters),
                    "Native candidate changed weights outside the frozen correction parameters.")
            pairs = [read(directory / f"{bank}-candidate-{candidate_id}-pair-{index}.json")
                     for index in range(len(seeds))]
            for index, pair in enumerate(pairs):
                validate_pair(pair, index, seeds[index], len(protocol["opponents"]))
                require(same_game(pair["incumbent"], read(directory / f"{bank}-incumbent-{index}.json")),
                        "Search pair does not use its shared incumbent outcome.")
                require(candidate_id != 0 or same_game(pair["candidate"], pair["incumbent"]),
                        "Zero correction anchor failed exact incumbent gameplay parity.")
            require(row["overall"]["pairs"] == len(seeds), "Native search candidate bank is incomplete.")
            verify_metrics(row["overall"], comparison(pairs))
    selected = report["selection"][0]
    require(report["selectedCandidateId"] == state["selectedCandidateId"] == selected["candidateId"]
            and read(directory / "candidate.json") == read(directory / selected["model"]),
            "Native selected candidate differs from the completed selection ranking.")
    return selected


def search_passes(metrics):
    return metrics["winShareDifference"]["mean"] > 0 and metrics["rewardDifference"]["mean"] > 0


def confirmation_passes(protocol, metrics):
    return (protocol["profile"] != "smoke" and all(
        metrics[name]["count"] >= 512 and metrics[name]["lower95"] is not None
        and metrics[name]["lower95"] > 0 for name in ("winShareDifference", "rewardDifference")))


def result(protocol, output, stage, passed, summary, metrics, artifacts):
    report = {"schemaVersion": 1, "stage": stage, "accepted": stage == "confirm" and passed,
              "summary": summary, "metrics": metrics, "profile": protocol["profile"],
              "productionActivation": False, "humanStrengthVerified": False,
              "seedNote": protocol["seedNote"], "intervalMethod": protocol["intervalMethod"]}
    path = output / f"{stage}-report.json"
    document(path, report)
    text_path = output / f"{stage}-report.txt"
    measured = metrics["selection"] if stage == "search" else metrics
    lines = [summary]
    if stage == "search":
        lines.append(f"Selected candidate: {metrics['selected']}; zero-anchor gameplay parity passed.")
    for label, key, scale, unit in (("Win-share difference", "winShareDifference", 100, " pp"),
                                    ("Composite reward difference", "rewardDifference", 1, "")):
        estimate = measured[key]
        interval = (f"[{estimate['lower95'] * scale:+.4f}, {estimate['upper95'] * scale:+.4f}]{unit}"
                    if estimate["lower95"] is not None else "unavailable")
        lines.append(f"{label}: {estimate['mean'] * scale:+.4f}{unit}; "
                     f"95% interval {interval}; {estimate['count']} paired worlds.")
    if stage == "search":
        lines.append("Selection intervals are descriptive; independent confirmation is required.")
    lines.extend(["No policy activated.", protocol["seedNote"]])
    text_path.write_text("\n".join(lines) + "\n")
    return experiments.stage_result(output, stage, passed, summary, metrics,
                                    [*artifacts, path, text_path])


def search_stage(protocol, output):
    directory = output / "search"
    experiments.native(protocol, "strategy-search", protocol["searchSource"], directory,
                       extra=("--workers", str(protocol["workers"])))
    selected = verify_search(protocol, directory)
    passed = search_passes(selected["overall"])
    artifacts = sorted(path for path in directory.rglob("*") if path.is_file())
    selection = {"schemaVersion": 1, "selected": selected["candidateId"],
                 "selection": selected["overall"], "zeroAnchorParity": True,
                 "confirmationInspected": False, "productionActivation": False}
    if passed:
        candidate = output / "selected/candidate.json"
        experiments.copy_input(directory / "candidate.json", candidate, [])
        selection.update(candidate=str(candidate), candidateSha256=sha(candidate))
        source = output / "confirmation-source.json"
        document(source, experiments.arena_source(protocol["baseline"], protocol["opponents"],
                 protocol["seed"] + 2, protocol["confirmationPairs"], candidate, "CONFIRMATION"))
        document(output / "selection.json", selection)
        artifacts.extend([candidate, source, output / "selection.json"])
    summary = ("Selection passed both positive-mean gates; one frozen candidate advances to fresh confirmation."
               if passed else "Selection failed the positive win-share/composite gate. Keep the incumbent; confirmation not opened.")
    return result(protocol, output, "search", passed, summary, selection, artifacts)


def confirm_stage(protocol, output):
    selection = read(output / "selection.json")
    require(search_passes(selection["selection"]), "Confirmation requires the passed selection gate.")
    require(sha(selection["candidate"]) == selection["candidateSha256"],
            "Frozen candidate changed before confirmation.")
    source = output / "confirmation-source.json"
    expected = experiments.arena_source(protocol["baseline"], protocol["opponents"],
               protocol["seed"] + 2, protocol["confirmationPairs"], selection["candidate"], "CONFIRMATION")
    require(read(source) == expected, "Confirmation differs from its one predeclared candidate/bank.")
    directory = output / "confirmation"
    experiments.native(protocol, "arena", source, directory)
    verified = experiments.verify_arena(directory, source)
    pairs = [read(directory / f"pair-{index}.json") for index in range(protocol["confirmationPairs"])]
    for index, pair in enumerate(pairs):
        validate_pair(pair, index, protocol["seedBanks"]["confirmation"][index], len(protocol["opponents"]))
    metrics = comparison(pairs)
    verify_metrics(read(directory / "evaluation.json")["overall"], metrics)
    require(verified["count"] == metrics["rewardDifference"]["count"], "Confirmation pair count changed.")
    passed = confirmation_passes(protocol, metrics)
    metrics.update(candidateSha256=selection["candidateSha256"], minimumConfirmationPairs=512)
    summary = ("Independent confirmation passed both lower95 gates. Candidate is ready for review; no activation."
               if passed else "Confirmation did not qualify a candidate. Keep the incumbent; this finite search is finished.")
    if protocol["profile"] == "smoke":
        summary = "Smoke pipeline completed; smoke never qualifies a model. Keep the incumbent."
    return result(protocol, output, "confirm", passed, summary, metrics,
                  sorted(path for path in directory.rglob("*") if path.is_file()))


def run_stage(options):
    protocol = read(options.protocol)
    require(protocol.get("schemaVersion") == 1 and protocol.get("kind") == "automata-policy-search",
            "Unsupported whole-game search protocol.")
    require(protocol["seedBanks"] == seed_banks(protocol["seed"], PROFILES[protocol["profile"]],
                                               protocol["sourceSeedBanks"]), "Frozen game banks changed.")
    require(sha(protocol["baseline"]) == protocol["baselineSha256"]
            and sha(protocol["initial"]) == protocol["initialSha256"], "Frozen search input changed.")
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    return {"search": search_stage, "confirm": confirm_stage}[options.stage](protocol, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--from-campaign", type=Path, required=True)
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--seed", type=int, required=True)
    prep.add_argument("--workers", type=int, default=4)
    prep.add_argument("--maximum-seconds", type=float, default=14400)
    prep.add_argument("--profile", choices=PROFILES, default="search")
    stage = commands.add_parser("stage")
    stage.add_argument("--protocol", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    stage.add_argument("--stage", choices=("search", "confirm"), required=True)
    options = parser.parse_args()
    {"prepare": prepare, "stage": run_stage}[options.command](options)


if __name__ == "__main__":
    main()
