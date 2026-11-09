"""Finite-series policy, seed isolation and controller-compatible child receipts."""

import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import automata_campaign as controller
import automata_experiments as experiments
import automata_series as series


class SeriesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.output = self.root / "run"
        self.output.mkdir()

    def test_empty_series_admits_first_campaign(self):
        self.assertTrue(series.decision([], 3)["continue"])

    def test_unsuccessful_campaign_admits_next_predeclared_campaign(self):
        self.assertTrue(series.decision([False], 3)["continue"])

    def test_confirmation_pass_stops_for_review(self):
        self.assertEqual({"continue": False, "reason": "candidate-ready-for-review"},
                         series.decision([False, True], 3))

    def test_finite_queue_exhaustion_stops(self):
        self.assertEqual({"continue": False, "reason": "declared-campaigns-exhausted"},
                         series.decision([False, False, False], 3))

    def test_source_collision_is_detected_across_arena_stride(self):
        source = {"confirmation": experiments.arena_seeds(10, 4)}
        child = {"training": experiments.arena_seeds(8201, 2)}
        with self.assertRaisesRegex(ValueError, "overlap"):
            series.validate_seed_reservations(source, [child])

    def test_future_child_collision_is_reserved_before_any_execution(self):
        first = {"confirmation": experiments.arena_seeds(10, 4)}
        second = {"training": experiments.arena_seeds(8201, 2)}
        with self.assertRaisesRegex(ValueError, "overlap"):
            series.validate_seed_reservations({"source": [1]}, [first, second])

    def test_disjoint_actual_worlds_are_accepted(self):
        series.validate_seed_reservations({"source": [1, 2]},
                                          [{"training": [3], "validation": [4]},
                                           {"training": [5], "validation": [6]}])

    def child_receipt(self, child_id="campaign-01", stage="token-trials"):
        child = self.output / child_id
        artifact = child / "trials/first/dataset.json"
        experiments.document(artifact, {"complete": True})
        result = {"schemaVersion": 1, "status": "passed", "summary": "Finished trial.",
                  "artifacts": {"trials/first/dataset.json": experiments.sha(artifact)}}
        path = child / "results" / (stage + ".json")
        experiments.document(path, result)
        return path, result

    def test_rebased_receipt_validates_without_changing_child_resume_keys(self):
        path, result = self.child_receipt()
        original, original_bytes = copy.deepcopy(result), path.read_bytes()
        wrapped = series.rebase_result(result, "campaign-01", path, self.output)
        receipt = self.output / "results/wrapper.json"
        experiments.document(receipt, wrapped)
        self.assertEqual(wrapped, controller.validate_result(receipt, self.output))
        self.assertEqual({"campaign-01/trials/first/dataset.json",
                          "campaign-01/results/token-trials.json"}, set(wrapped["artifacts"]))
        self.assertEqual(original, result)
        self.assertEqual(original_bytes, path.read_bytes())

    def test_stage_publishes_controller_receipt_after_in_process_child_execution(self):
        def run_stage(options):
            self.assertEqual(self.output / "campaign-01", options.output)
            self.child_receipt(stage=options.stage)
        options = SimpleNamespace(output=self.output, child_id="campaign-01",
                                  protocol=self.root / "protocol.json", stage="token-trials")
        with mock.patch.object(experiments, "run_stage", side_effect=run_stage) as run:
            series.stage(options)
        run.assert_called_once()
        result = controller.validate_result(self.output / "results/campaign-01-token-trials.json",
                                            self.output)
        self.assertEqual("passed", result["status"])

    def gate_fixture(self, confirmations, maximum=3):
        children = [{"id": f"campaign-{index + 1:02d}"} for index in range(maximum)]
        plan = self.root / "series-plan.json"
        experiments.document(plan, {"children": children, "maximumCampaigns": maximum,
                                    "confirmationCaveat": "No series-wide significance claim."})
        entries = {}
        for child, status in zip(children, confirmations):
            for name in series.STAGES:
                entry = {"status": status if name == "confirm" else "passed"}
                if entry["status"] in ("passed", "rejected"):
                    path = self.output / "results" / f"{child['id']}-{name}.json"
                    experiments.document(path, {"schemaVersion": 1, "status": entry["status"],
                                                "summary": "Scientific result."})
                    entry.update(result=str(path.relative_to(self.output)),
                                 resultSha256=experiments.sha(path))
                entries[f"{child['id']}-{name}"] = entry
        experiments.document(self.output / "campaign-state.json", {"stages": entries})
        return SimpleNamespace(output=self.output, plan=plan,
                               child_id=children[len(confirmations) - 1]["id"])

    def test_skipped_confirmation_advances_the_series(self):
        options = self.gate_fixture(["skipped"])
        series.gate(options)
        receipt = experiments.read(self.output / "results/campaign-01-gate.json")
        self.assertEqual("passed", receipt["status"])
        self.assertFalse(receipt["metrics"]["campaigns"][0]["confirmationPassed"])

    def test_passing_confirmation_publishes_explicit_control_stop(self):
        options = self.gate_fixture(["passed"])
        series.gate(options)
        receipt = experiments.read(self.output / "results/campaign-01-gate.json")
        self.assertEqual("rejected", receipt["status"])
        self.assertEqual("candidate-ready-for-review", receipt["summary"])
        self.assertFalse(experiments.read(self.output / "series-report.json")["productionActivation"])

    def test_last_unsuccessful_campaign_publishes_exhausted_queue(self):
        options = self.gate_fixture(["rejected", "skipped"], maximum=2)
        series.gate(options)
        receipt = experiments.read(self.output / "results/campaign-02-gate.json")
        self.assertEqual("declared-campaigns-exhausted", receipt["summary"])
        self.assertFalse(receipt["metrics"]["continue"])

    def test_unfinished_child_cannot_advance_the_series(self):
        options = self.gate_fixture(["running"])
        with self.assertRaisesRegex(ValueError, "completed scientific campaign"):
            series.gate(options)
        self.assertFalse((self.output / "results/campaign-01-gate.json").exists())

    def prepare_fixture(self, source_mode="replacement", requested_mode=None, legacy=False,
                        override_runtime=False):
        runtime = self.root / "runtime"
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"test-bytecode")
        baseline, java = self.root / "baseline.json", self.root / "java"
        experiments.document(baseline, {"weights": {}})
        java.write_bytes(b"test-java")
        identity = {"executable": str(java), "version": "JDK 21",
                    "files": {str(java): experiments.sha(java)}}
        source, prepared = self.root / "source", self.root / "prepared"
        original = SimpleNamespace(output=source, runtime=runtime, baseline=baseline,
                                   profile=None, learning_mode=source_mode,
                                   java=str(java), seed=7100000, workers=1,
                                   maximum_seconds=900, opponent=[], territory_value_bundle=None,
                                   frozen_card_purchase_bundle=None, frozen_purchase_policy=None)
        options = SimpleNamespace(from_campaign=source, output=prepared, seed=7101000,
                                  campaigns=2, profile=None, workers=1, maximum_seconds=900,
                                  learning_mode=requested_mode)
        if override_runtime:
            replacement_runtime = self.root / "correction-runtime"
            (replacement_runtime / "lib").mkdir(parents=True)
            (replacement_runtime / "lib/training.jar").write_bytes(b"correction-bytecode")
            options.runtime = replacement_runtime
        with mock.patch.object(experiments, "java_identity", return_value=identity):
            experiments.prepare(original)
            if legacy:
                protocol = experiments.read(source / "protocol.json")
                protocol.pop("learningMode")
                experiments.document(source / "protocol.json", protocol, immutable=False)
                spec = experiments.read(source / "campaign.json")
                spec["inputs"]["protocol.json"] = experiments.sha(source / "protocol.json")
                experiments.document(source / "campaign.json", spec, immutable=False)
            series.prepare(options)
        return source, prepared

    def test_preparation_flattens_children_and_pins_absolute_inputs(self):
        source, prepared = self.prepare_fixture()
        spec = experiments.read(prepared / "series.json")
        controller.validate_spec(spec, self.output)
        stages = {stage["id"]: stage for stage in spec["stages"]}
        self.assertEqual(10, len(stages))
        self.assertEqual([], stages["campaign-01-gate"]["dependsOn"])
        self.assertEqual(["campaign-01-gate"], stages["campaign-02-diagnostic"]["dependsOn"])
        self.assertEqual(["campaign-01-gate"], stages["campaign-02-gate"]["dependsOn"])
        self.assertTrue(all(Path(path).is_absolute() for path in spec["inputs"]))
        self.assertEqual(900, spec["limits"]["maximumSeconds"])

    def test_series_inherits_correction_mode_and_reserves_all_actual_worlds(self):
        source, prepared = self.prepare_fixture(source_mode="correction")
        original = experiments.read(source / "protocol.json")
        plan = experiments.read(prepared / "series-plan.json")
        self.assertEqual("CORRECTION", plan["learningMode"])
        self.assertEqual("CORRECTION", plan["sourceLearningMode"])
        self.assertEqual(experiments.sha(source / "campaign.json"), plan["sourceCampaignSha256"])
        self.assertEqual(experiments.sha(source / "protocol.json"), plan["sourceProtocolSha256"])
        self.assertEqual(experiments.sha(original["baseline"]), plan["baselineSha256"])
        children = [experiments.read(Path(child["directory"]) / "protocol.json")
                    for child in plan["children"]]
        for child in children:
            self.assertEqual("CORRECTION", child["learningMode"])
            self.assertEqual("correction", child["profile"])
            self.assertEqual(2, len(child["recipes"]))
            self.assertEqual(64, child["developmentPairs"])
            self.assertEqual(256, child["confirmationPairs"])
            self.assertEqual(Path(original["baseline"]).read_bytes(),
                             Path(child["trainingBaseline"]).read_bytes())
            self.assertTrue(all(experiments.read(recipe["source"])["learningMode"] == "CORRECTION"
                                for recipe in child["recipes"]))
        series.validate_seed_reservations(original["seedBanks"],
                                          [child["seedBanks"] for child in children])

    def test_legacy_source_without_mode_defaults_to_existing_replacement_profile(self):
        source, prepared = self.prepare_fixture(legacy=True)
        plan = experiments.read(prepared / "series-plan.json")
        self.assertEqual("REPLACEMENT", plan["learningMode"])
        self.assertEqual("REPLACEMENT", plan["sourceLearningMode"])
        child = experiments.read(Path(plan["children"][0]["directory"]) / "protocol.json")
        self.assertEqual("overnight", child["profile"])
        self.assertEqual(48, child["developmentPairs"])
        self.assertEqual([(32, 16, 1), (96, 32, 2), (96, 32, 4)],
                         [(row["trainingRoots"], row["validationRoots"], row["continuationReplicates"])
                          for row in child["recipes"]])
        self.assertTrue(all("learningMode" not in experiments.read(recipe["source"])
                            for recipe in child["recipes"]))

    def test_explicit_correction_upgrade_uses_and_pins_new_runtime(self):
        source, prepared = self.prepare_fixture(requested_mode="correction", legacy=True,
                                               override_runtime=True)
        original = experiments.read(source / "protocol.json")
        plan = experiments.read(prepared / "series-plan.json")
        self.assertEqual("REPLACEMENT", plan["sourceLearningMode"])
        self.assertEqual("CORRECTION", plan["learningMode"])
        self.assertEqual(original["runtime"], plan["sourceRuntime"])
        self.assertEqual(str(self.root / "correction-runtime"), plan["runtimeSource"])
        self.assertEqual(experiments.distribution_hashes(self.root / "correction-runtime"),
                         plan["runtimeSha256"])
        for child in plan["children"]:
            protocol = experiments.read(Path(child["directory"]) / "protocol.json")
            self.assertEqual("CORRECTION", protocol["learningMode"])
            self.assertEqual(b"correction-bytecode",
                             (Path(protocol["runtime"]) / "lib/training.jar").read_bytes())

    def test_correction_upgrade_requires_an_explicit_capable_runtime(self):
        with self.assertRaisesRegex(ValueError, "requires --runtime"):
            self.prepare_fixture(requested_mode="correction", legacy=True)


if __name__ == "__main__":
    unittest.main()
