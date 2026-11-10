"""Frozen token roots, completed-grid admission, explicit smoke fallback and immutable clocks."""

import argparse
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import automata_experiments as exp
import automata_token_order as runner


def metrics(count=4096, mean=.1, lower=.09):
    return {name: {"count": count, "mean": mean, "lower95": lower, "upper95": 2 * mean - lower}
            for name in ("winShareDifference", "rewardDifference")}


def audit(seed=13000210, profile="pilot", **extra):
    names = ("training", "validation", "screening", "confirmation", "heldout")
    counts = (2, 2, 4, 4, 4) if profile == "smoke" else (256, 128, 512, 2048, 4096)
    prefix = "smoke-" if profile == "smoke" else ""
    return {"allIntersectionsZero": True, "conservativelyExpandedOffsets": [1],
            "seedBanks": {prefix + name: {"seeds": exp.arena_seeds(seed + index, count),
                "count": count, "nativeOffset": seed + index} for index, (name, count) in enumerate(zip(names, counts))}, **extra}


class TokenOrderRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.baseline, self.prior, self.actor, self.rival = [self.root / name for name in
                                                         ("f1.json", "f0.json", "p48.json", "rival.json")]
        for path, weight in ((self.baseline, 2), (self.prior, 1), (self.rival, 3)):
            exp.document(path, {"schemaVersion": 1, "weights": {"cash": weight},
                               "rulesVersion": runner.TRANSFER["sourceRulesVersion"], "dataVersion": "ah1996-catalog-16"})
        exp.document(self.actor, {"schemaVersion": 1, "actorWeights": [.1]})

    def protocol(self, profile="pilot"):
        config = self.root / "fit-config.json"
        if not config.exists():
            exp.document(config, dict(runner.FIT, seed=13001210))
        return {"profile": profile, **runner.PROFILES[profile], "seed": 13000210,
                "baseline": str(self.baseline), "configuration": exp.CONFIGURATION,
                **runner.TRANSFER, "experimentalRulesTransfer": runner.TRANSFER, "dataVersion": "ah1996-catalog-16",
                "frozenPurchase": {"baseline": str(self.prior), "policy": str(self.actor)},
                "frozenPurchaseSha256": {"baseline": exp.sha(self.prior), "policy": exp.sha(self.actor)},
                "opponents": [str(self.baseline), str(self.prior)], "robustOpponents": [str(self.rival)],
                "workers": 4, "qualificationConfidence": .99, "fitConfig": str(config),
                "scripts": str(Path(runner.__file__).parent),
                "numerical": {"executable": "/pinned/python", "pythonVersion": "frozen-python", "torchVersion": "frozen-torch"}}

    def collection_check(self, protocol, enough=True):
        output = self.root / "run"
        exp.document(output / "collection/dataset.json", {"prospective": True})
        initializer = {"outputWeights": [0.], "outputBias": .5}
        exp.document(output / "initializer.json", initializer)
        provenance = {"source": str(output / "collection"), "signature": "frozen-roots",
                      "files": {"dataset.json": exp.sha(output / "collection/dataset.json")}, "collected": 4 if enough else 1, "skipped": 0 if enough else 3}
        check = {"provenance": provenance, "collected": {"TRAINING": 2 if enough else 1, "VALIDATION": 2 if enough else 0},
                 "initializerSha256": exp.sha(output / "initializer.json"), "initializedModelDigest": runner.purchase.digest(initializer)}
        exp.document(output / "collection-validation.json", check)
        return output, check

    def fit_fixture(self, scaling=True, grid_count=3):
        protocol = self.protocol()
        output, check = self.collection_check(protocol)
        directory = output / "fit"
        config = exp.read(protocol["fitConfig"])
        identity = {"config": config, "source": check["provenance"], "initializedModelSha256": check["initializedModelDigest"],
            "learnerSha256": exp.sha(Path(protocol["scripts"]) / "token_order_policy.py"),
            "qBackendSha256": exp.sha(Path(protocol["scripts"]) / "territory_value.py"),
            "inputBackendSha256": exp.sha(Path(protocol["scripts"]) / "purchase_policy.py"),
            "pythonVersion": protocol["numerical"]["pythonVersion"], "torchVersion": protocol["numerical"]["torchVersion"],
            "minimumAdvantage": .02, "startedEpoch": 1000., "deadlineEpoch": 1000. + config["maxSeconds"]}
        exp.document(directory / "identity.json", identity)
        rows = []
        for index, decay in enumerate(config["weightDecay"][:grid_count]):
            exp.document(directory / f"model-{index}.json", {"model": index})
            row = {"index": index, "weightDecay": decay, "modelSha256": exp.sha(directory / f"model-{index}.json"),
                   "validation": {"pairedMse": [.4, .2, .3][index], "observedMeanDifference": .05 if scaling else -.01,
                                  "overrides": 8, "positiveOrigins": 4, "largestPositiveFraction": .4}}
            exp.document(directory / f"fit-{index}.json", row)
            rows.append(row)
        chosen = min(rows, key=lambda row: row["validation"]["pairedMse"])
        (directory / "candidate.json").write_bytes((directory / f"model-{chosen['index']}.json").read_bytes())
        report = {"identitySha256": exp.sha(directory / "identity.json"), "qualificationPassed": False,
                  "initializerValidation": {"pairedMse": 1.}, "fits": rows, "selected": chosen,
                  "scalingDiagnosticPassed": scaling, "candidateSha256": exp.sha(directory / "candidate.json"), "elapsedSeconds": 1.}
        exp.document(directory / "fit.json", report)
        self.finish(directory)
        return protocol, output

    def finish(self, directory):
        exp.document(directory / "finished.json", {"kind": "token-order-paired-fit", "reportSha256": exp.sha(directory / "fit.json"),
            "artifacts": {path.name: exp.sha(path) for path in directory.iterdir() if path.is_file() and path.name != "finished.json"}}, immutable=False)

    def test_collection_keeps_shared_purchase_and_precommitted_two_stream_banks(self):
        protocol = self.protocol()
        source = runner.collection_source(protocol)
        self.assertEqual(source["candidate"], source["incumbent"])
        self.assertEqual(256, source["trainingRoots"])
        self.assertEqual(128, source["validationRoots"])
        self.assertEqual(2, source["continuationReplicates"])
        self.assertEqual(protocol["frozenPurchase"], source["frozenPurchase"])
        self.assertEqual(runner.TRANSFER, source["experimentalRulesTransfer"])
        self.assertNotIn("tokenOrder", source)

    def test_arena_overlays_only_candidate_tokens_and_uses_fresh_heldout_bank(self):
        protocol = self.protocol()
        source = runner.arena_source(protocol, "selected.json", "robustness")
        self.assertEqual({"candidateModel": "selected.json"}, source["tokenOrder"])
        self.assertEqual(protocol["frozenPurchase"], source["frozenPurchase"])
        self.assertEqual(protocol["robustOpponents"], source["opponents"])
        self.assertEqual(13000214, source["seed"])
        self.assertEqual(4096, source["pairsPerConfiguration"])

    def environment_fixture(self):
        protocol = self.protocol()
        source = runner.collection_source(protocol)
        directory = self.root / "collection"
        directory.mkdir()
        roles = {"candidate": self.baseline, "incumbent": self.baseline,
                 **{f"opponent-{index}": Path(path) for index, path in enumerate(source["opponents"])}}
        sources = {role: {"path": str(path), "sha256": exp.sha(path),
                          "snapshot": "initial.json" if role == "candidate" else role + ".json"}
                   for role, path in roles.items()}
        purchase = {role: {"path": path, "sha256": exp.sha(path), "snapshot": f"frozen-purchase-{role}.json"}
                    for role, path in source["frozenPurchase"].items()}
        for item in [*sources.values(), *purchase.values()]:
            (directory / item["snapshot"]).write_bytes(Path(item["path"]).read_bytes())
        report = {"experimentalRulesTransfer": dict(runner.TRANSFER, initializationOnly=True, qualificationTransferred=False),
                  "rulesVersion": protocol["targetRulesVersion"], "dataVersion": protocol["dataVersion"],
                  "ordinaryModelSources": sources, "frozenPurchase": purchase}
        return protocol, source, directory, report

    def test_transfer_accepts_enriched_metadata_and_preserves_original_model_versions(self):
        protocol, source, directory, report = self.environment_fixture()
        runner.verify_environment(protocol, source, directory, (report,), "initial.json")
        self.assertEqual(protocol["sourceRulesVersion"], exp.read(directory / "initial.json")["rulesVersion"])

    def test_transfer_rejects_imported_qualification(self):
        protocol, source, directory, report = self.environment_fixture()
        report["experimentalRulesTransfer"]["qualificationTransferred"] = True
        with self.assertRaisesRegex(ValueError, "initialization-only"):
            runner.verify_environment(protocol, source, directory, (report,), "initial.json")

    def test_transfer_rejects_relabeling_even_when_weights_are_unchanged(self):
        protocol, source, directory, report = self.environment_fixture()
        model = exp.read(directory / "initial.json")
        exp.document(directory / "initial.json", dict(model, rulesVersion=protocol["targetRulesVersion"]), immutable=False)
        with self.assertRaisesRegex(ValueError, "original ordinary model snapshot bytes"):
            runner.verify_environment(protocol, source, directory, (report,), "initial.json")

    def test_transfer_rejects_wrong_native_rules(self):
        protocol, source, directory, report = self.environment_fixture()
        report["rulesVersion"] = protocol["sourceRulesVersion"]
        with self.assertRaisesRegex(ValueError, "Rules transfer metadata"):
            runner.verify_environment(protocol, source, directory, (report,), "initial.json")

    def test_all_prefix_continuation_and_arena_worlds_are_distinct(self):
        banks = runner.seed_banks(13000210, runner.PROFILES["pilot"], audit())
        values = [world for bank in banks.values() for world in bank]
        self.assertEqual(7040 + 768, len(values))
        self.assertEqual(len(values), len(set(values)))
        self.assertEqual(exp.arena_seeds(13000211, 128), banks["validation"])

    def test_stride_shifted_historical_worlds_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            runner.seed_banks(13000210 + 8191, runner.PROFILES["pilot"], audit(13000210 + 8191, conservativelyExpandedOffsets=[13000210]))

    def test_reserved_continuation_stream_is_rejected(self):
        chance = (exp.arena_seeds(13000210, 1)[0] << 3) ^ 0x6f20a5319d
        with self.assertRaisesRegex(ValueError, "overlap"):
            runner.seed_banks(13000210, runner.PROFILES["pilot"], audit(reservedSeeds=[chance]))

    def test_unaudited_nearby_seed_cannot_reuse_audited_offsets(self):
        with self.assertRaisesRegex(ValueError, "exact five pre-audited"):
            runner.seed_banks(13000211, runner.PROFILES["pilot"], audit())

    def test_unaudited_count_cannot_extend_an_approved_bank(self):
        with self.assertRaisesRegex(ValueError, "exact five pre-audited"):
            runner.seed_banks(13000210, dict(runner.PROFILES["pilot"], screeningPairs=513), audit())

    def test_audit_must_explicitly_confirm_zero_historical_intersections(self):
        with self.assertRaisesRegex(ValueError, "all historical intersections zero"):
            runner.seed_banks(13000210, runner.PROFILES["pilot"], audit(allIntersectionsZero=False))

    def test_smoke_uses_its_separately_approved_banks(self):
        banks = runner.seed_banks(13000200, runner.PROFILES["smoke"], audit(13000200, "smoke"))
        self.assertEqual(exp.arena_seeds(13000200, 2), banks["training"])

    def test_candidate_file_without_fit_completion_cannot_advance(self):
        protocol, output = self.fit_fixture()
        (output / "fit/finished.json").unlink()
        with self.assertRaisesRegex(ValueError, "candidate.json alone"):
            runner.verify_fit(protocol, output)

    def test_fit_requires_the_entire_declared_regularization_grid(self):
        protocol, output = self.fit_fixture(grid_count=2)
        with self.assertRaisesRegex(ValueError, "entire fixed fitting grid"):
            runner.verify_fit(protocol, output)

    def test_mse_improvement_without_positive_scaling_diagnostic_is_rejected(self):
        protocol, output = self.fit_fixture(scaling=False)
        _, admitted = runner.verify_fit(protocol, output)
        self.assertFalse(admitted)

    def test_complete_fit_binds_selected_candidate_and_scaling_admission(self):
        protocol, output = self.fit_fixture()
        report, admitted = runner.verify_fit(protocol, output)
        self.assertTrue(admitted)
        self.assertEqual(1, report["selected"]["index"])

    def test_corrupted_selected_model_cannot_pass_a_rewritten_completion_manifest(self):
        protocol, output = self.fit_fixture()
        exp.document(output / "fit/candidate.json", {"different": True}, immutable=False)
        self.finish(output / "fit")
        with self.assertRaisesRegex(ValueError, "Selected token candidate"):
            runner.verify_fit(protocol, output)

    def test_interrupted_fit_does_not_restart_even_with_partial_candidate(self):
        protocol, output = self.fit_fixture()
        (output / "fit/finished.json").unlink()
        with mock.patch.object(runner.subprocess, "run") as process, self.assertRaisesRegex(ValueError, "cannot restart"):
            runner.fit_stage(protocol, output)
        process.assert_not_called()

    def test_insufficient_smoke_roots_use_only_initializer_without_fitting_or_replenishment(self):
        protocol = self.protocol("smoke")
        output, _ = self.collection_check(protocol, enough=False)
        with mock.patch.object(runner.subprocess, "run") as process:
            result = runner.fit_stage(protocol, output)
        process.assert_not_called()
        selection = exp.read(output / "selection.json")
        self.assertTrue(selection["smokeInitializer"])
        self.assertFalse(selection["admitted"])
        self.assertEqual("passed", result["status"])
        self.assertEqual((output / "initializer.json").read_bytes(), Path(selection["model"]).read_bytes())
        self.assertFalse(exp.read(output / "fit-report.json")["qualificationPassed"])

    def test_final_gate_accepts_both_positive_99_percent_bounds(self):
        self.assertTrue(runner.qualification_passes(self.protocol(), metrics(), "robustness"))

    def test_final_gate_rejects_positive_95_percent_but_negative_99_percent_bound(self):
        self.assertFalse(runner.qualification_passes(self.protocol(), metrics(mean=.02, lower=.001), "robustness"))

    def test_final_gate_requires_exact_declared_count(self):
        self.assertFalse(runner.qualification_passes(self.protocol(), metrics(count=4095), "robustness"))

    def test_smoke_never_qualifies(self):
        self.assertFalse(runner.qualification_passes(self.protocol("smoke"), metrics(count=4), "robustness"))

    def test_rejected_screen_keeps_confirmation_worlds_unopened(self):
        protocol, output = self.fit_fixture()
        runner.fit_stage(protocol, output)
        selection = exp.read(output / "selection.json")
        report = output / "screen-report.json"
        exp.document(report, {"metrics": dict(metrics(count=512, mean=-.01), modelSha256=selection["modelSha256"])})
        exp.document(output / "results/screen.json", {"status": "rejected", "artifacts": {report.name: exp.sha(report)}})
        with mock.patch.object(exp, "native") as native, self.assertRaisesRegex(ValueError, "passed preceding gate"):
            runner.arena_stage(protocol, output, "confirm")
        native.assert_not_called()

    def test_native_resume_preserves_cached_worlds_and_original_deadline(self):
        directory = self.root / "collection"
        exp.document(directory / "origin-0.json", {"cached": True})
        (directory / "started-at.txt").write_text("original-start")
        (directory / "deadline-at.txt").write_text("original-deadline")
        def renewed(*args, **kwargs):
            (directory / "deadline-at.txt").write_text("renewed-deadline")
        with mock.patch.object(exp, "native", side_effect=renewed), self.assertRaisesRegex(ValueError, "original clock"):
            runner.native_once(self.protocol(), "token-order-collect", self.root / "source.json", directory)

    def test_completed_native_collection_is_verified_without_reexecution(self):
        directory = self.root / "collection"
        exp.document(directory / "finished.json", {"complete": True})
        with mock.patch.object(exp, "native") as native:
            runner.native_once(self.protocol(), "token-order-collect", self.root / "source.json", directory)
        native.assert_not_called()

    def test_preparation_requires_explicit_rules_transfer(self):
        with self.assertRaisesRegex(ValueError, "historical initialization"):
            runner.prepare(argparse.Namespace(experimental_rules_transfer=False))

    def test_prepare_freezes_grid_roles_resources_and_single_fit_attempt(self):
        source, runtime, java = self.root / "historical", self.root / "runtime", self.root / "java"
        source.mkdir()
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"rebased-runtime")
        java.write_bytes(b"jdk")
        java_identity = {"executable": str(java), "files": {str(java): exp.sha(java)}}
        parent = dict(self.protocol(), prior=str(self.prior), java=java_identity)
        audit_path = self.root / "seed-audit.json"
        exp.document(audit_path, audit())
        numerical = dict(parent["numerical"], files={str(java): exp.sha(java)})
        output = self.root / "prepared"
        options = argparse.Namespace(experimental_rules_transfer=True, from_campaign=source, runtime=runtime,
            numerical_python=Path("/pinned/python"), seed_audit=audit_path, seed=13000210, workers=4,
            profile="pilot", maximum_seconds=28800, output=output)
        with mock.patch.object(runner, "qualified_hybrid", return_value=(parent, str(self.actor), {})), \
                mock.patch.object(runner.purchase, "numerical_identity", return_value=numerical), \
                mock.patch.object(exp, "java_identity", return_value=java_identity), contextlib.redirect_stdout(io.StringIO()):
            campaign = runner.prepare(options)
        self.assertEqual([7200, 1800, 1800, 7200, 10800], [stage["maximumSeconds"] for stage in campaign["stages"]])
        self.assertEqual([2, 1, 2, 2, 2], [stage["maximumAttempts"] for stage in campaign["stages"]])
        self.assertEqual([0., .0001, .001], exp.read(output / "fit-config.json")["weightDecay"])
        self.assertEqual(4 * exp.GIB, campaign["limits"]["maximumDataBytes"])
        self.assertFalse(exp.read(output / "protocol.json")["historicalQualificationImported"])
        for path, digest in campaign["inputs"].items():
            self.assertEqual(digest, exp.sha(output / path))


if __name__ == "__main__":
    unittest.main()
