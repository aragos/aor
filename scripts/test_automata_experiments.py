"""Tests for frozen preparation, bounded recipes and held-out confirmation decisions."""

import argparse
import gzip
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import automata_experiments as experiment


class ExperimentTest(unittest.TestCase):
    def test_four_hour_parent_caps_only_strategy_replay_at_one_hour(self):
        with mock.patch.object(experiment, "remaining_seconds", return_value=14400):
            with mock.patch.object(subprocess, "run", return_value=mock.Mock(returncode=0)) as run:
                experiment.native(self.protocol, "strategy-replay", self.baseline, self.output / "replay")
                command = run.call_args.args[0]
                self.assertEqual(command[command.index("--hours") + 1], "1.0")
                experiment.native(self.protocol, "arena", self.baseline, self.output / "arena")
                command = run.call_args.args[0]
                self.assertEqual(command[command.index("--hours") + 1], "4.0")

    def test_strategy_replay_retains_shorter_remaining_deadline(self):
        with mock.patch.object(experiment, "remaining_seconds", return_value=900):
            with mock.patch.object(subprocess, "run", return_value=mock.Mock(returncode=0)) as run:
                experiment.native(self.protocol, "strategy-replay", self.baseline, self.output / "replay")
                command = run.call_args.args[0]
                self.assertEqual(command[command.index("--hours") + 1], "0.25")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.baseline = self.root / "baseline.json"
        experiment.document(self.baseline, {"weights": {}})
        self.output = self.root / "run"
        self.output.mkdir()
        environment = mock.patch.dict(os.environ, {
            "AOR_CAMPAIGN_DEADLINE_EPOCH": str(time.time() + 3600),
            "AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH": str(time.time() + 2400)})
        environment.start()
        self.addCleanup(environment.stop)
        self.protocol = {
            "baseline": str(self.baseline), "opponents": [str(self.baseline)],
            "seed": 7100000, "developmentPairs": 4, "confirmationPairs": 32,
            "minimumConfirmationPairs": 32, "diagnosticSource": None,
            "scope": "Token-only F continuation", "recipes": [],
            "java": {"executable": "/frozen/jdk/bin/java"}, "runtime": "/frozen/runtime",
            "workers": 2, "heapMiB": 1536,
            "minimumRecipeAdmissionSeconds": 30, "confirmationReserveSeconds": 360,
        }

    def add_recipe(self, name, replicates=1):
        source = self.root / (name + ".json")
        experiment.document(source, {"trainingRoots": 12, "validationRoots": 8})
        self.protocol["recipes"].append({"id": name, "source": str(source),
                                         "continuationReplicates": replicates})

    def fit(self, output, mean=0.1):
        experiment.document(output / "fit.json", {
            "complete": True, "labelDiagnostics": {
                "TRAINING": {"nonbaselineAlternatives": 12, "zeroRewardAlternatives": 3}},
            "validationRewardDifference": {"mean": mean},
        })
        experiment.document(output / "dataset.json", {"complete": True})
        experiment.document(output / "candidate.json", {"weights": {"candidate": 1}})
        experiment.document(output / "finished.json", {"finished": True})

    def arena(self, source, output, delta=0.1, win_delta=None):
        source = experiment.read(source)
        win_delta = delta if win_delta is None else win_delta
        deltas = []
        for index, seed in enumerate(experiment.arena_seeds(source["seed"], source["pairsPerConfiguration"])):
            seat = index % 4
            rewards = [0.5] * 4
            rewards[seat] += delta
            wins = [0.5] * 4
            wins[seat] += win_delta
            deltas.append(delta)
            experiment.document(output / f"pair-{index}.json", {
                "index": index, "seed": seed, "seat": seat,
                "configuration": experiment.CONFIGURATION,
                "candidate": {"rewards": rewards, "wins": wins},
                "incumbent": {"rewards": [0.5] * 4, "wins": [0.5] * 4},
            })
        experiment.document(output / "evaluation.json", {
            "complete": True, "purpose": source["purpose"],
            "overall": {"rewardDifference": experiment.interval(deltas),
                        "winShareDifference": experiment.interval([win_delta] * len(deltas))},
        })
        experiment.document(output / "state.json", {"phase": "complete"})
        experiment.document(output / "finished.json", {"finished": True})

    def candidate_trial(self, name, mean):
        candidate = self.root / (name + "-candidate.json")
        experiment.document(candidate, {"candidate": name})
        return {"id": name, "status": "evaluated", "development": {"mean": mean},
                "candidate": str(candidate), "candidateSha256": experiment.sha(candidate)}

    def test_seed_banks_use_distinct_actual_worlds_and_training_prefixes(self):
        banks = experiment.seed_banks(7100000, experiment.PROFILES["overnight"])
        seeds = [seed for bank in banks.values() for seed in bank]
        self.assertEqual(len(seeds), len(set(seeds)))
        self.assertEqual(banks["training"][:32], experiment.arena_seeds(7100001, 32))
        self.assertEqual(banks["validation"], experiment.arena_seeds(7100002, 32))

    def test_different_offsets_that_overlap_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            experiment.validate_banks({"first": experiment.arena_seeds(10, 4),
                                       "second": experiment.arena_seeds(8201, 4)})

    def test_optional_diagnostic_uses_same_world_seat_and_frozen_rivals(self):
        source = experiment.diagnostic_source("baseline", "purchase", "joint", "territory", [11, 12])
        for seed in (11, 12):
            jobs = [job for job in source["jobs"] if job["seed"] == seed]
            self.assertEqual(4, len(jobs))
            self.assertEqual(1, len({job["seat"] for job in jobs}))
            self.assertTrue(all(job["rivals"] == ["epsilon-zero"] * 4 for job in jobs))
        self.assertEqual([0, .02, .2], [source["policies"][name]["epsilon"]
                                      for name in ("epsilon-zero", "epsilon-low", "epsilon-high")])

    def test_missing_optional_assets_are_explicitly_unmeasured(self):
        result = experiment.diagnostic_stage(self.protocol, self.output)
        self.assertEqual("passed", result["status"])
        self.assertFalse(result["metrics"]["explorationMeasured"])

    def test_diagnostic_computes_paired_returns_by_world(self):
        source = self.root / "diagnostic-source.json"
        specification = experiment.diagnostic_source("baseline", "purchase", "joint", "territory", [11, 12])
        experiment.document(source, specification)
        self.protocol["diagnosticSource"] = str(source)
        rewards = {"frozen": .4, "epsilon-zero": .5, "epsilon-low": .6, "epsilon-high": .3}
        def native(protocol, mode, source, output, extra):
            output.mkdir(parents=True, exist_ok=True)
            for job in specification["jobs"]:
                with gzip.open(output / f'game-{job["index"]}.json.gz', "wt") as stream:
                    json.dump(dict(index=job["index"], seed=job["seed"], controlledSeat=job["seat"],
                                   arm=job["arm"], terminal={"reward": rewards[job["arm"]]}), stream)
        with mock.patch.object(experiment, "native", side_effect=native):
            result = experiment.diagnostic_stage(self.protocol, self.output)
        self.assertEqual(2, result["metrics"]["worlds"])
        self.assertAlmostEqual(.1, result["metrics"]["comparisonsAgainstEpsilonZero"]["epsilon-low"]["mean"])

    def test_first_pilot_without_signal_stops_larger_recipes(self):
        self.add_recipe("screen")
        self.add_recipe("larger", 2)
        def native(protocol, mode, source, output, **unused):
            if mode == "token-value-pilot":
                self.fit(output, mean=0)
            else:
                self.arena(source, output, delta=-.1)
            return True
        with mock.patch.object(experiment, "native", side_effect=native) as run:
            result = experiment.trials_stage(self.protocol, self.output)
        self.assertEqual("passed", result["status"])
        self.assertEqual(2, run.call_count)
        self.assertEqual("not-admitted", result["metrics"]["trials"][1]["status"])

    def run_screen(self, reward, win, validation=.1):
        self.add_recipe("screen", 2)
        self.add_recipe("larger", 4)
        def native(protocol, mode, source, output, **unused):
            if mode == "token-value-pilot":
                self.fit(output, mean=validation)
            else:
                self.arena(source, output, delta=reward, win_delta=win)
            return True
        with mock.patch.object(experiment, "native", side_effect=native) as run:
            result = experiment.trials_stage(self.protocol, self.output)
        return result["metrics"]["trials"], run.call_count

    def correction_protocol(self):
        self.protocol.update(learningMode="CORRECTION", developmentPairs=64,
                             confirmationPairs=256, minimumConfirmationPairs=256)

    def test_positive_validation_cannot_scale_correction_after_negative_development(self):
        self.correction_protocol()
        trials, calls = self.run_screen(reward=-.347, win=.1)
        self.assertEqual(2, calls)
        self.assertFalse(trials[0]["scaleSignal"])
        self.assertEqual("not-admitted", trials[1]["status"])

    def test_correction_cannot_scale_after_negative_development_win_share(self):
        self.correction_protocol()
        trials, calls = self.run_screen(reward=.1, win=-.1)
        self.assertEqual(2, calls)
        self.assertFalse(trials[0]["scaleSignal"])
        self.assertEqual("not-admitted", trials[1]["status"])

    def test_correction_cannot_scale_without_positive_validation(self):
        self.correction_protocol()
        trials, calls = self.run_screen(reward=.1, win=0, validation=0)
        self.assertEqual(2, calls)
        self.assertFalse(trials[0]["scaleSignal"])
        self.assertEqual("not-admitted", trials[1]["status"])

    def test_positive_development_with_nonnegative_wins_admits_second_correction_recipe(self):
        self.correction_protocol()
        trials, calls = self.run_screen(reward=.1, win=0)
        self.assertEqual(3, calls)
        self.assertTrue(trials[0]["scaleSignal"])
        self.assertEqual("duplicate", trials[1]["status"])

    def test_legacy_scaling_still_uses_validation_without_development_gate(self):
        trials, calls = self.run_screen(reward=-.347, win=-.1)
        self.assertEqual(3, calls)
        self.assertTrue(trials[0]["scaleSignal"])
        self.assertEqual("duplicate", trials[1]["status"])

    def test_known_insufficient_origin_rejection_skips_scale(self):
        self.add_recipe("screen")
        self.add_recipe("larger", 2)
        def native(protocol, mode, source, output, **unused):
            experiment.document(output / "dataset.json", {"counts": {}})
            return False
        with mock.patch.object(experiment, "native", side_effect=native) as run:
            result = experiment.trials_stage(self.protocol, self.output)
        self.assertEqual("rejected", result["status"])
        self.assertEqual(1, run.call_count)
        self.assertEqual("not-admitted", result["metrics"]["trials"][1]["status"])

    def test_rejected_later_recipe_preserves_first_evaluated_candidate(self):
        self.add_recipe("screen")
        self.add_recipe("larger", 2)
        def native(protocol, mode, source, output, **unused):
            if "larger" in str(output):
                experiment.document(output / "dataset.json", {"counts": {}})
                return False
            if mode == "token-value-pilot":
                self.fit(output)
            else:
                self.arena(source, output)
            return True
        with mock.patch.object(experiment, "native", side_effect=native):
            result = experiment.trials_stage(self.protocol, self.output)
        self.assertEqual("passed", result["status"])
        self.assertEqual(["evaluated", "rejected"], [row["status"] for row in result["metrics"]["trials"]])

    def test_unexpected_native_error_is_not_scientific_rejection(self):
        self.add_recipe("screen")
        with mock.patch.object(experiment, "native", side_effect=RuntimeError("JVM failure")):
            with self.assertRaisesRegex(RuntimeError, "JVM failure"):
                experiment.trials_stage(self.protocol, self.output)
        self.assertFalse((self.output / "results/token-trials.json").exists())

    def test_identical_candidate_reuses_development_instead_of_repeating_games(self):
        self.add_recipe("screen")
        self.add_recipe("larger", 2)
        def native(protocol, mode, source, output, **unused):
            if mode == "token-value-pilot":
                self.fit(output)
            else:
                self.arena(source, output)
            return True
        with mock.patch.object(experiment, "native", side_effect=native) as run:
            result = experiment.trials_stage(self.protocol, self.output)
        self.assertEqual(3, run.call_count)
        duplicate = result["metrics"]["trials"][1]
        self.assertEqual("duplicate", duplicate["status"])
        self.assertEqual("screen", duplicate["duplicateOf"])
        self.assertFalse((self.output / "trials/larger/development").exists())

    def test_cached_trial_rejects_changed_dataset(self):
        self.add_recipe("screen")
        def native(protocol, mode, source, output, **unused):
            if mode == "token-value-pilot":
                self.fit(output)
            else:
                self.arena(source, output)
            return True
        with mock.patch.object(experiment, "native", side_effect=native):
            experiment.trials_stage(self.protocol, self.output)
        (self.output / "trials/screen/pilot/dataset.json").write_text("changed")
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            experiment.trials_stage(self.protocol, self.output)

    def test_recipe_admission_preserves_confirmation_allowance(self):
        with mock.patch.dict(os.environ, {
                "AOR_CAMPAIGN_DEADLINE_EPOCH": str(time.time() + 380)}):
            self.assertFalse(experiment.recipe_admitted(self.protocol))

    def insufficient_fixture(self):
        source = self.root / "pilot-source.json"
        experiment.document(source, {"trainingRoots": 2, "validationRoots": 2})
        output = self.output / "native"
        names = [f"origin-{index}.json" for index in range(4)]
        experiment.document(output / "dataset.json", {
            "requestedOrigins": 4, "originFiles": names,
            "counts": {split: {"requested": 2, "collected": 1, "skipped": 1}
                       for split in ("TRAINING", "VALIDATION")}})
        for name in names:
            experiment.document(output / name, {"alternatives": []})
        return source, output

    def test_insufficient_native_rejection_requires_current_failure_and_complete_dataset(self):
        source, output = self.insufficient_fixture()
        def run(command, stdout, **unused):
            stdout.write(experiment.INSUFFICIENT_ROOTS.encode())
            return subprocess.CompletedProcess(command, 1)
        with mock.patch.object(experiment.subprocess, "run", side_effect=run):
            self.assertFalse(experiment.native(self.protocol, "token-value-pilot", source, output,
                                               allow_insufficient=True))

    def test_old_insufficient_log_does_not_hide_new_infrastructure_failure(self):
        source, output = self.insufficient_fixture()
        output.with_suffix(".log").write_text(experiment.INSUFFICIENT_ROOTS)
        def run(command, stdout, **unused):
            stdout.write(b"Unexpected JVM failure")
            return subprocess.CompletedProcess(command, 1)
        with mock.patch.object(experiment.subprocess, "run", side_effect=run):
            with self.assertRaisesRegex(RuntimeError, "failed with exit"):
                experiment.native(self.protocol, "token-value-pilot", source, output,
                                  allow_insufficient=True)

    def test_incomplete_dataset_is_infrastructure_failure(self):
        source, output = self.insufficient_fixture()
        (output / "origin-0.json").unlink()
        def run(command, stdout, **unused):
            stdout.write(experiment.INSUFFICIENT_ROOTS.encode())
            return subprocess.CompletedProcess(command, 1)
        with mock.patch.object(experiment.subprocess, "run", side_effect=run):
            with self.assertRaisesRegex(RuntimeError, "failed with exit"):
                experiment.native(self.protocol, "token-value-pilot", source, output,
                                  allow_insufficient=True)

    def test_selection_uses_only_development_and_freezes_one_candidate(self):
        trials = [self.candidate_trial("b", .2), self.candidate_trial("a", .2),
                  self.candidate_trial("negative", -.1)]
        experiment.document(self.output / "trials.json", {"trials": trials})
        result = experiment.select_stage(self.protocol, self.output)
        self.assertEqual("a", result["metrics"]["selected"])
        self.assertFalse(result["metrics"]["confirmationInspected"])
        source = experiment.read(self.output / "confirmation-source.json")
        self.assertEqual(7100004, source["seed"])
        self.assertEqual("CONFIRMATION", source["purpose"])
        self.assertEqual(32, source["pairsPerConfiguration"])
        self.assertEqual({"selected/candidate.json", "selection.json", "confirmation-source.json"},
                         set(result["artifacts"]))

    def test_nonpositive_development_does_not_open_confirmation(self):
        experiment.document(self.output / "trials.json", {"trials": [self.candidate_trial("zero", 0)]})
        result = experiment.select_stage(self.protocol, self.output)
        self.assertEqual("rejected", result["status"])
        self.assertFalse((self.output / "confirmation-source.json").exists())

    def test_negative_development_win_share_does_not_open_correction_confirmation(self):
        self.correction_protocol()
        trial = self.candidate_trial("negative-wins", .1)
        trial["development"].update(count=64, winShareDifference={"mean": -.1})
        experiment.document(self.output / "trials.json", {"trials": [trial]})
        result = experiment.select_stage(self.protocol, self.output)
        self.assertEqual("rejected", result["status"])
        self.assertFalse((self.output / "confirmation-source.json").exists())

    def test_confirmation_passes_with_frozen_candidate_and_fresh_bank(self):
        experiment.document(self.output / "trials.json", {"trials": [self.candidate_trial("best", .1)]})
        experiment.select_stage(self.protocol, self.output)
        def native(protocol, mode, source, output):
            self.arena(source, output, delta=.1)
        with mock.patch.object(experiment, "native", side_effect=native) as run:
            result = experiment.confirm_stage(self.protocol, self.output)
        self.assertEqual(1, run.call_count)
        self.assertEqual("passed", result["status"])
        self.assertFalse(result["metrics"]["productionActivation"])

    def test_negative_confirmation_is_final_without_added_games(self):
        experiment.document(self.output / "trials.json", {"trials": [self.candidate_trial("best", .1)]})
        experiment.select_stage(self.protocol, self.output)
        def native(protocol, mode, source, output):
            self.arena(source, output, delta=-.1)
        with mock.patch.object(experiment, "native", side_effect=native) as run:
            result = experiment.confirm_stage(self.protocol, self.output)
        self.assertEqual("rejected", result["status"])
        self.assertEqual(1, run.call_count)
        self.assertEqual(32, result["metrics"]["rewardDifference"]["count"])

    def test_small_smoke_sample_cannot_qualify_a_candidate(self):
        self.protocol["confirmationPairs"] = 4
        experiment.document(self.output / "trials.json", {"trials": [self.candidate_trial("best", .1)]})
        experiment.select_stage(self.protocol, self.output)
        def native(protocol, mode, source, output):
            self.arena(source, output, delta=.1)
        with mock.patch.object(experiment, "native", side_effect=native):
            result = experiment.confirm_stage(self.protocol, self.output)
        self.assertEqual("rejected", result["status"])

    def test_positive_reward_with_lower_mean_win_share_fails_confirmation(self):
        experiment.document(self.output / "trials.json", {"trials": [self.candidate_trial("best", .1)]})
        experiment.select_stage(self.protocol, self.output)
        def native(protocol, mode, source, output):
            self.arena(source, output, delta=.1, win_delta=-.1)
        with mock.patch.object(experiment, "native", side_effect=native):
            result = experiment.confirm_stage(self.protocol, self.output)
        self.assertEqual("rejected", result["status"])
        self.assertLess(result["metrics"]["winShareDifference"]["mean"], 0)

    def test_changed_selected_bytes_prevent_confirmation(self):
        experiment.document(self.output / "trials.json", {"trials": [self.candidate_trial("best", .1)]})
        experiment.select_stage(self.protocol, self.output)
        (self.output / "selected/candidate.json").write_text("changed")
        with mock.patch.object(experiment, "native") as run:
            with self.assertRaisesRegex(ValueError, "candidate changed"):
                experiment.confirm_stage(self.protocol, self.output)
        run.assert_not_called()

    def test_native_inherits_group_and_uses_original_remaining_deadline(self):
        future = time.time() + 20
        environment = {"AOR_CAMPAIGN_DEADLINE_EPOCH": str(future + 100),
                       "AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH": str(future)}
        with mock.patch.dict(os.environ, environment), mock.patch.object(
                experiment.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            experiment.native(self.protocol, "arena", self.baseline, self.output / "native")
        args, kwargs = run.call_args
        self.assertNotIn("start_new_session", kwargs)
        self.assertNotIn("preexec_fn", kwargs)
        hours = float(args[0][args[0].index("--hours") + 1])
        self.assertGreater(hours, 0)
        self.assertLessEqual(hours, 20 / 3600)
        self.assertEqual("/frozen/jdk/bin/java", args[0][0])

    def test_native_worker_allowance_uses_host_cpus_instead_of_jvm_thread_cap(self):
        environment = {"AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH": str(time.time() + 20)}
        with mock.patch.dict(os.environ, environment), mock.patch.object(os, "cpu_count", return_value=10), \
                mock.patch.object(experiment.subprocess, "run",
                                  return_value=subprocess.CompletedProcess([], 0)) as run:
            experiment.native(self.protocol, "arena", self.baseline, self.output / "native")
        args, kwargs = run.call_args
        self.assertEqual(kwargs["env"]["AOR_TRAINING_MACHINE_CPUS"], "10")
        self.assertIn(f'-XX:ActiveProcessorCount={self.protocol["workers"]}', args[0])

    def test_expired_original_deadline_prevents_native_launch(self):
        with mock.patch.dict(os.environ, {"AOR_CAMPAIGN_DEADLINE_EPOCH": str(time.time() - 1)}), \
                mock.patch.object(experiment.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "deadline has expired"):
                experiment.native(self.protocol, "arena", self.baseline, self.output / "native")
        run.assert_not_called()

    def test_bundle_relocation_verifies_original_components(self):
        actor = self.root / "actor.json"
        experiment.document(actor, {"model": True})
        source = self.root / "joint.json"
        experiment.document(source, {"card": None,
                                     "purchase": {"path": actor.name, "sha256": experiment.sha(actor)}})
        target = self.root / "copied/joint.json"
        provenance = []
        experiment.freeze_bundle(source, target, provenance)
        relocated = experiment.read(target)
        self.assertEqual("joint-purchase.json", relocated["purchase"]["path"])
        self.assertEqual(actor.read_bytes(), (target.parent / "joint-purchase.json").read_bytes())
        self.assertEqual(source.read_bytes(), (target.parent / "joint-original.json").read_bytes())

    def test_preparation_freezes_inputs_runtime_and_finite_graph(self):
        runtime = self.root / "distribution"
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"frozen-bytecode")
        java = self.root / "java"
        java.write_bytes(b"jdk")
        identity = {"executable": str(java), "version": "JDK 21",
                    "files": {str(java): experiment.sha(java)}}
        options = argparse.Namespace(output=self.root / "prepared", runtime=runtime,
                                     baseline=self.baseline, profile="smoke", java=str(java),
                                     seed=7100000, workers=1, maximum_seconds=900, opponent=[],
                                     territory_value_bundle=None, frozen_card_purchase_bundle=None,
                                     frozen_purchase_policy=None)
        with mock.patch.object(experiment, "java_identity", return_value=identity):
            campaign = experiment.prepare(options)
        self.assertEqual(["diagnostic", "token-trials", "select", "confirm"],
                         [stage["id"] for stage in campaign["stages"]])
        self.assertEqual(["select"], campaign["stages"][-1]["dependsOn"])
        self.assertEqual(900, campaign["limits"]["maximumSeconds"])
        self.assertIn("runtime/lib/training.jar", campaign["inputs"])
        self.assertIn("scripts/train-opponents.py", campaign["inputs"])
        self.assertIn(str(java), campaign["inputs"])
        self.baseline.write_text("changed after preparation")
        self.assertEqual({"weights": {}}, experiment.read(options.output / "inputs/baseline.json"))
        with mock.patch.object(experiment, "java_identity", return_value=identity):
            with self.assertRaisesRegex(ValueError, "new empty directory"):
                experiment.prepare(options)

    def test_enabled_incumbent_is_preserved_with_separate_disabled_training_reference(self):
        original = {"schemaVersion": 1, "weights": {"planning.tokenValue.enabled": 1,
                                                     "planning.tokenValue.coefficient": .7,
                                                     "other.weight": 2.5}}
        experiment.document(self.baseline, original, immutable=False)
        baseline_bytes = self.baseline.read_bytes()
        runtime = self.root / "distribution"
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"frozen-bytecode")
        java = self.root / "java"
        java.write_bytes(b"jdk")
        identity = {"executable": str(java), "version": "JDK 21",
                    "files": {str(java): experiment.sha(java)}}
        options = argparse.Namespace(output=self.root / "prepared", runtime=runtime,
                                     baseline=self.baseline, profile="smoke", java=str(java),
                                     seed=7100000, workers=1, maximum_seconds=900, opponent=[],
                                     territory_value_bundle=None, frozen_card_purchase_bundle=None,
                                     frozen_purchase_policy=None)
        with mock.patch.object(experiment, "java_identity", return_value=identity):
            campaign = experiment.prepare(options)
        protocol = experiment.read(options.output / "protocol.json")
        self.assertEqual("REPLACEMENT", protocol["learningMode"])
        training = experiment.read(protocol["trainingBaseline"])
        expected = dict(original, weights=dict(original["weights"], **{"planning.tokenValue.enabled": 0.0}))
        self.assertEqual(expected, training)
        self.assertEqual(baseline_bytes, Path(protocol["baseline"]).read_bytes())
        self.assertEqual(baseline_bytes, self.baseline.read_bytes())
        self.assertEqual(experiment.sha(protocol["trainingBaseline"]), protocol["trainingBaselineSha256"])
        self.assertIn("inputs/token-training-baseline.json", campaign["inputs"])
        source = experiment.read(protocol["recipes"][0]["source"])
        self.assertNotIn("learningMode", source)
        self.assertEqual(protocol["trainingBaseline"], source["candidate"])
        self.assertEqual(protocol["trainingBaseline"], source["incumbent"])
        self.assertEqual([protocol["baseline"]], source["opponents"])
        trial = self.candidate_trial("learned", .1)
        experiment.document(self.output / "trials.json", {"trials": [trial]})
        experiment.select_stage(protocol, self.output)
        confirmation = experiment.read(self.output / "confirmation-source.json")
        self.assertEqual(protocol["baseline"], confirmation["incumbent"])

    def test_correction_preserves_enabled_incumbent_bytes_and_declares_disjoint_banks(self):
        original = {"schemaVersion": 1, "weights": {"planning.tokenValue.enabled": 1,
                    "planning.tokenValue.coefficient": .7, "other.weight": 2.5}}
        self.baseline.write_text(json.dumps(original, separators=(",", ":")))
        baseline_bytes = self.baseline.read_bytes()
        runtime = self.root / "distribution"
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"frozen-bytecode")
        java = self.root / "java"
        java.write_bytes(b"jdk")
        identity = {"executable": str(java), "version": "JDK 21",
                    "files": {str(java): experiment.sha(java)}}
        options = argparse.Namespace(output=self.root / "prepared", runtime=runtime,
                                     baseline=self.baseline, profile=None, learning_mode="correction",
                                     java=str(java), seed=7100000, workers=1, maximum_seconds=900,
                                     opponent=[], territory_value_bundle=None,
                                     frozen_card_purchase_bundle=None, frozen_purchase_policy=None)
        with mock.patch.object(experiment, "java_identity", return_value=identity):
            campaign = experiment.prepare(options)
        protocol = experiment.read(options.output / "protocol.json")
        self.assertEqual("CORRECTION", protocol["learningMode"])
        self.assertEqual("correction", protocol["profile"])
        self.assertEqual(baseline_bytes, Path(protocol["baseline"]).read_bytes())
        self.assertEqual(baseline_bytes, Path(protocol["trainingBaseline"]).read_bytes())
        self.assertEqual(baseline_bytes, self.baseline.read_bytes())
        self.assertEqual(protocol["baselineSha256"], protocol["trainingBaselineSha256"])
        self.assertEqual(64, protocol["developmentPairs"])
        self.assertEqual(256, protocol["confirmationPairs"])
        self.assertEqual(256, protocol["minimumConfirmationPairs"])
        self.assertEqual([(64, 32, 2), (128, 64, 4)],
                         [(row["trainingRoots"], row["validationRoots"], row["continuationReplicates"])
                          for row in protocol["recipes"]])
        for recipe in protocol["recipes"]:
            source = experiment.read(recipe["source"])
            self.assertEqual("CORRECTION", source["learningMode"])
            self.assertEqual(protocol["trainingBaseline"], source["candidate"])
            self.assertEqual(protocol["trainingBaseline"], source["incumbent"])
            self.assertEqual([protocol["baseline"]], source["opponents"])
            self.assertEqual(experiment.sha(recipe["source"]),
                             campaign["inputs"][str(Path(recipe["source"]).relative_to(options.output.resolve()))])
            self.assertEqual(experiment.arena_seeds(source["seed"], recipe["trainingRoots"]),
                             protocol["seedBanks"]["training"][:recipe["trainingRoots"]])
            self.assertEqual(experiment.arena_seeds(source["seed"] + 1, recipe["validationRoots"]),
                             protocol["seedBanks"]["validation"][:recipe["validationRoots"]])
        experiment.validate_banks(protocol["seedBanks"])

    def test_correction_profile_cannot_be_used_for_replacement_training(self):
        with self.assertRaisesRegex(ValueError, "replacement profiles are separate"):
            experiment.profile_name("correction", "REPLACEMENT")


if __name__ == "__main__":
    unittest.main()
