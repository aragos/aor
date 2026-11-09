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

    def test_preparation_flattens_children_and_pins_absolute_inputs(self):
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
                                   profile="smoke", java=str(java), seed=7100000, workers=1,
                                   maximum_seconds=900, opponent=[], territory_value_bundle=None,
                                   frozen_card_purchase_bundle=None, frozen_purchase_policy=None)
        options = SimpleNamespace(from_campaign=source, output=prepared, seed=7101000,
                                  campaigns=2, profile="smoke", workers=1, maximum_seconds=900)
        with mock.patch.object(experiments, "java_identity", return_value=identity):
            experiments.prepare(original)
            series.prepare(options)
        spec = experiments.read(prepared / "series.json")
        controller.validate_spec(spec, self.output)
        stages = {stage["id"]: stage for stage in spec["stages"]}
        self.assertEqual(10, len(stages))
        self.assertEqual([], stages["campaign-01-gate"]["dependsOn"])
        self.assertEqual(["campaign-01-gate"], stages["campaign-02-diagnostic"]["dependsOn"])
        self.assertEqual(["campaign-01-gate"], stages["campaign-02-gate"]["dependsOn"])
        self.assertTrue(all(Path(path).is_absolute() for path in spec["inputs"]))
        self.assertEqual(900, spec["limits"]["maximumSeconds"])


if __name__ == "__main__":
    unittest.main()
