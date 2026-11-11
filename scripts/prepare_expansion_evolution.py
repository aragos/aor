#!/usr/bin/env python3
"""Freeze the independent evolution stage after the bounded imitation screen finishes."""
import argparse
from pathlib import Path
import shutil
import sys

import automata_campaign as campaign
import automata_experiments as exp
import expansion_arena as arena
import expansion_evolution as evolution

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require


def screen_survivors(protocol_path, output):
    """Recheck all prior pairs/receipts without opening games or widening prior eligibility."""
    protocol = read(protocol_path)
    report = read(output / "screen-report.json")
    receipt = read(output / "results/screen.json")
    require(protocol["kind"] == "expansion-imitation-screen" and protocol["workers"] == 6
            and protocol["bank"]["count"] == 128
            and set(protocol["models"]) == {"lambda-0.1", "lambda-1.0", "lambda-10.0"}
            and receipt["status"] == "passed" and receipt["metrics"] == report
            and report["protocolSha256"] == sha(protocol_path)
            and report["descriptiveOnly"] and report["qualificationPassed"] is False
            and report["productionActivation"] is False,
            "Evolution requires a complete descriptive imitation screen receipt.")
    for relative, digest in receipt["artifacts"].items():
        path = output / relative
        require(path.resolve().is_relative_to(output.resolve()) and sha(path) == digest,
                "Completed screening artifact changed or escaped its output directory.")
    for path, digest in protocol["frozenFiles"].items():
        require(sha(path) == digest, "Frozen imitation screening input changed: " + path)
    fit_selection = read(protocol["fitSelection"])
    fit_finished = read(protocol["fitFinished"])
    require(sha(protocol["fitSelection"]) == fit_finished["selectionSha256"]
            and all(sha(path) == fit_finished["models"][name + ".json"]
                    for name, path in protocol["models"].items()),
            "Screened models must retain the exact completed imitation fit identities.")
    eligible = [Path(path).stem for path in fit_selection["eligibleRivalModelFiles"]]
    require(protocol["eligibleBeforeScreen"] == report["eligibleBeforeScreen"] == eligible
            and len(eligible) <= 2 and fit_selection["auditConsulted"] is False,
            "Only rivals selected before the human audit may seed evolution.")
    first = None
    for name in ("lambda-0.1", "lambda-1.0", "lambda-10.0"):
        pairs, metrics = arena.verify_arena(protocol, protocol["sources"][name], output / name, protocol["bank"])
        require(report["metrics"][name] == metrics, "Screen report differs from complete paired terminal results.")
        if first is None:
            first = pairs
        else:
            arena.verify_common_baseline(first, pairs)
    survivors = [name for name in eligible if report["metrics"][name]["rewardDifference"]["mean"] >= -.05
                 and report["metrics"][name]["winShareDifference"]["mean"] >= -.10]
    require(report["eligibleAfterCollapseExclusion"] == survivors,
            "Evolution cannot change the predeclared severe-collapse exclusion.")
    return protocol, survivors


def prepare(options):
    root = options.output.resolve()
    require(root.exists() and {path.name for path in root.iterdir()} <= {"audit"},
            "Evolution preparation requires only its independently frozen audit directory.")
    screen_root, screen_output, fit_root, leader_root = [path.resolve() for path in
        (options.screen_root, options.screen_output, options.fit_root, options.leader_root)]
    screen_protocol, survivors = screen_survivors(screen_root / "protocol.json", screen_output)
    fit = read(fit_root / "fit/run.json")
    finished = read(fit_root / "fit/finished.json")
    require(fit["featureNames"] == list(evolution.FEATURES)
            and fit["signature"] == finished["signature"]
            and sha(screen_protocol["baseline"]) == fit["baselineSha256"] == finished["baselineSha256"]
            and sha(fit_root / "fit/selection.json") == finished["selectionSha256"]
            == sha(screen_protocol["fitSelection"]),
            "Evolution normalization must belong to the exact frozen human pilot.")
    audit_path = root / "audit/seed-audit.json"
    audit = read(audit_path)
    banks, proposal_seeds = evolution.audited_reservations(audit)
    leader = read(leader_root / "protocol-template.json")
    def leader_path(value):
        return (leader_root / value).resolve()
    require([sha(path) for path in screen_protocol["opponents"]] ==
            [sha(leader_path(path)) for path in leader["opponents"]]
            and len(leader["heldoutOpponents"]) == 2,
            "Evolution must preserve the exact original four and separate two-rival leader pools.")
    provenance = []
    def copy_file(source, relative):
        return str(exp.copy_input(Path(source), root / relative, provenance))
    runtime_source = Path(screen_protocol["runtime"])
    shutil.copytree(runtime_source, root / "runtime")
    require(exp.distribution_hashes(runtime_source) == exp.distribution_hashes(root / "runtime"),
            "Frozen expansion runtime changed during preparation.")
    shutil.copytree(screen_root / "scripts", root / "scripts")
    for name in ("expansion_evolution.py", "evolution_coordinates.py", "prepare_expansion_evolution.py", "test_expansion_evolution.py",
                 "test_prepare_expansion_evolution.py"):
        copy_file(Path(__file__).with_name(name), "scripts/" + name)
    require(sha(root / "scripts/expansion_arena.py") == sha(Path(__file__).with_name("expansion_arena.py")),
            "Evolution and screen must share the same reviewed expansion arena verifier.")
    baseline = copy_file(screen_protocol["baseline"], "inputs/ordinary-f1.json")
    opponents = [copy_file(path, f"inputs/opponent-{index}.json") for index, path in enumerate(screen_protocol["opponents"])]
    heldout = [copy_file(leader_path(path), f"inputs/heldout-{index}.json") for index, path in enumerate(leader["heldoutOpponents"])]
    require(len(set(map(sha, opponents + heldout))) == 6,
            "Training and heldout rivals must have six distinct frozen model identities.")
    purchase = {name: copy_file(path, "inputs/purchase-" + name + ".json")
                for name, path in screen_protocol["frozenPurchase"].items()}
    initial = [copy_file(screen_protocol["models"][name], f"inputs/initial-{index}.json")
               for index, name in enumerate(survivors)]
    for source, name in ((screen_root / "protocol.json", "screen-protocol.json"),
                         (screen_output / "screen-report.json", "screen-report.json"),
                         (screen_output / "results/screen.json", "screen-result.json"),
                         (leader_root / "protocol-template.json", "leader-protocol.json"),
                         (fit_root / "fit/run.json", "fit-run.json"),
                         (fit_root / "fit/selection.json", "fit-selection.json"),
                         (fit_root / "fit/audit.json", "fit-audit.json"),
                         (fit_root / "fit/finished.json", "fit-finished.json"),
                         (fit_root / "pre-fit-protocol.json", "pre-fit-protocol.json"),
                         (fit_root / "evidence/independent-fit-check.json", "independent-fit-check.json")):
        copy_file(source, "evidence/" + name)
    normalization = fit["normalization"]
    bounds = evolution.coordinate_bounds([read(path) for path in initial], read(baseline), normalization)
    protocol = {"schemaVersion": 1, "kind": evolution.VERSION, "featureNames": list(evolution.FEATURES),
        "normalization": normalization, "coordinateBounds": bounds, "generations": evolution.GENERATIONS,
        "populationSize": evolution.POPULATION, "sigmas": list(evolution.SIGMAS),
        "maximumProposalAttempts": evolution.MAX_PROPOSALS, "proposalSeeds": proposal_seeds,
        "worldBanks": banks, "seedAudit": str(audit_path), "baseline": baseline,
        "opponents": opponents, "heldoutOpponents": heldout, "initialModels": initial,
        "initialPilotModelNames": survivors, "frozenPurchase": purchase,
        "frozenPurchaseSha256": {key: sha(path) for key, path in purchase.items()},
        "runtime": str(root / "runtime"), "runtimeCommit": screen_protocol["runtimeCommit"],
        "runtimeTrainingJarSha256": screen_protocol["runtimeTrainingJarSha256"],
        **{key: screen_protocol[key] for key in ("java", "python", "configuration", "dataVersion",
            "sourceRulesVersion", "targetRulesVersion", "experimentalRulesTransfer")},
        "workers": 6, "heapMiB": 2048, "maximumSeconds": 28800, "maximumAttempts": 1,
        "maximumArchiveModels": evolution.MAX_ARCHIVE,
        "maximumTerminalGames": 4*8*128*2 + 11*512*2 + 2048*2 + 4096*2,
        "mutationRule": "Child index cycles sigma .25/.5/1/2 and alternates one parent/two distinct uniformly sampled parents; arithmetic center; joint Gaussian perturbation; clip fixed coordinates; reject duplicate weight vectors up to4096 attempts.",
        "parentRule": "F1 plus screen-surviving validation-selected models at generation0; F1 plus previous generation positive-reward top2 thereafter.",
        "rankingRule": "Reward mean descending, win mean descending, fixed candidate index ascending; non-anchor positive reward only for at most2 elites.",
        "selectionRule": "Unique archive≤11: F1,initial accepted models and up to2 elites/generation; fresh512 common worlds; both positive means required; choose reward,win,index.",
        "qualificationRule": "Only selected candidate;2048 confirm then4096 heldout; both paired reward/win99% lower bounds>0;8 canonical replays per final bank.",
        "ordinaryTokenBehavior": True, "fitting": False, "learningMethod": "bounded-whole-game-evolution",
        "productionActivation": False,
        "humanStrengthVerified": False, "automaticNextCampaign": False}
    frozen = {str(path): sha(path) for path in sorted(root.rglob("*")) if path.is_file()}
    frozen.update(screen_protocol["java"]["files"])
    frozen[screen_protocol["python"]["executable"]] = sha(screen_protocol["python"]["executable"])
    protocol["frozenFiles"] = frozen
    document(root / "protocol.json", protocol)
    evolution.validate_protocol(protocol)
    names = ("evolve", "select", "confirm", "heldout")
    stages = [{"id": stage, "dependsOn": [] if index == 0 else [names[index-1]],
               "maximumSeconds": 28800, "maximumAttempts": 1, "result": "results/" + stage + ".json",
               "command": [protocol["python"]["executable"], "-E", "-s", "-S", "-B", str(root / "scripts/expansion_evolution.py"),
                           "--protocol", str(root / "protocol.json"), "--output", "{output}", "--stage", stage]}
              for index, stage in enumerate(names)]
    spec = {"schemaVersion": 1, "kind": "automata-campaign", "inputs": dict(frozen, **{str(root / "protocol.json"): sha(root / "protocol.json")}),
            "limits": {"maximumSeconds": 28800, "maximumDataBytes": 4*exp.GIB,
                       "maximumRssBytes": 4*exp.GIB, "minimumFreeBytes": 2*exp.GIB}, "stages": stages}
    campaign.validate_spec(spec, root / "run")
    document(root / "campaign.json", spec)
    print(root / "campaign.json")
    return spec


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("screen-root", "screen-output", "fit-root", "leader-root", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    return prepare(parser.parse_args(arguments))


if __name__ == "__main__":
    main()
