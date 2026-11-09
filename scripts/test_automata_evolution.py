"""Bounded adaptation, immutable resumption and independent qualification checks."""

import argparse
import contextlib
import copy
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import automata_evolution as evolution
import automata_experiments as experiments
import automata_policy_search as search


def metrics(win=.1, reward=.2, count=1024, lower=.01):
    return {name: {"mean": mean, "count": count, "lower95": lower, "upper95": mean + .1}
            for name, mean in (("winShareDifference", win), ("rewardDifference", reward))}


class EvolutionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.model = {"schemaVersion": 1, "weights": {
            "cash": 2.5, "advance.A": .4, "planning.heldCommodity": .7,
            "planning.publicCommodity": .1, "planning.retainedCommodity": .6,
            "planning.commodityOption": 6.7, "planning.commodityDenial": .03,
            "planning.tokenValue.enabled": 1.0, "planning.targetTieBreak": 1.0,
            "planning.tokenValue.v1.costFraction": -.07,
            "planning.tokenValue.v2.plan.income": .3}}
        self.baseline = self.root / "baseline.json"
        experiments.document(self.baseline, self.model)

    def protocol(self, profile="search"):
        prior, policy = self.root / "prior.json", self.root / "policy.json"
        experiments.document(prior, self.model)
        experiments.document(policy, {"schemaVersion": 1, "actorWeights": [1.0]})
        profile_values = evolution.PROFILES[profile]
        return {"profile": profile, **profile_values, "baseline": str(self.baseline),
                "opponents": [str(self.baseline)], "robustOpponents": [str(self.baseline)],
                "seed": 7200000, "seedBanks": evolution.seed_banks(7200000, profile_values, {}),
                "initialRadius": 1.0, "maximumConsecutiveFailures": 2,
                "minimumRoundSeconds": 10, "finalists": 3 if profile == "archive" else 4, "workers": 4,
                "seedNote": "Source worlds excluded.", "intervalMethod": "Paired intervals.",
                "frozenPurchase": {"baseline": str(prior), "policy": str(policy)},
                "frozenPurchaseSha256": {"baseline": experiments.sha(prior), "policy": experiments.sha(policy)}}

    def test_proposals_are_deterministic_local_and_preserve_original_model(self):
        original = copy.deepcopy(self.model)
        proposals = evolution.proposals(self.model)
        self.assertEqual(proposals, evolution.proposals(self.model))
        self.assertEqual(original, self.model)
        self.assertEqual(21, len(proposals))
        self.assertEqual(self.model, proposals[0]["model"])
        for proposal in proposals[1:]:
            with self.subTest(module=proposal["module"], candidate=proposal["candidateId"]):
                allowed = (set(evolution.MODULES[proposal["module"]]) if proposal["module"] != "token" else
                           {name for name in original["weights"] if name.startswith(evolution.TOKEN)
                            and name != evolution.TOKEN + "enabled"} | set(evolution.TOKEN_GATES))
                self.assertLessEqual(set(proposal["changes"]), allowed)
                self.assertEqual(dict(original["weights"], **proposal["changes"]), proposal["model"]["weights"])
                self.assertTrue(all(proposal["model"]["weights"].get(gate, 0) in (0, 1)
                                    for gate in evolution.GATES))
                self.assertEqual(0, proposal["model"]["weights"].get(search.GATE, 0))

    def test_initial_leader_proposals_cover_exact_gate_and_retention_levels(self):
        leaders = [row["model"]["weights"] for row in evolution.proposals(self.model)
                   if row["module"] == "leader"]
        self.assertEqual([1, 1, 1, 1], [row[evolution.LEADER] for row in leaders])
        self.assertEqual([0, .05, .15, .3], [row.get(evolution.RETENTION, 0) for row in leaders])

    def test_enabled_leader_parent_includes_off_ablation(self):
        parent = dict(self.model, weights=dict(self.model["weights"], **{
            evolution.LEADER: 1.0, evolution.RETENTION: .15}))
        leaders = [row for row in evolution.proposals(parent) if row["module"] == "leader"]
        self.assertEqual({evolution.LEADER: 0.0}, leaders[0]["changes"])
        self.assertEqual(4, len(leaders))

    def test_smaller_radius_halves_coefficient_displacement_and_new_activations(self):
        original, smaller = evolution.proposals(self.model), evolution.proposals(self.model, .5)
        for key in ("planning.heldCommodity", "planning.reachableRetention"):
            full = next(row for row in original if key in row["changes"] and row["changes"][key] > 0)
            half = smaller[full["candidateId"]]
            baseline = self.model["weights"].get(key, 0)
            self.assertAlmostEqual((full["changes"][key] - baseline) / 2,
                                   half["changes"][key] - baseline)

    def test_identical_weight_maps_are_deduplicated(self):
        proposals = evolution.proposals({"schemaVersion": 1, "weights": {"cash": 1.0}})
        maps = [row["model"]["weights"] for row in proposals]
        self.assertLess(len(maps), 21)
        self.assertTrue(all(first != second for index, first in enumerate(maps) for second in maps[index + 1:]))

    def test_invalid_gates_and_enabled_correction_are_rejected(self):
        for key, value in ((evolution.LEADER, .5), (evolution.TOKEN + "enabled", 1.1), (search.GATE, 1.0)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                evolution.proposals(dict(self.model, weights=dict(self.model["weights"], **{key: value})))

    def test_every_round_and_final_world_is_disjoint_from_source_and_each_other(self):
        banks = evolution.seed_banks(7200000, evolution.PROFILES["search"], {
            "old": experiments.arena_seeds(7100000, 1024)})
        flattened = [seed for bank in banks.values() for seed in bank]
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertEqual(experiments.arena_seeds(7200013, 192), banks["round-3-selection"])
        self.assertEqual(experiments.arena_seeds(7200020, 1024), banks["confirmation"])
        self.assertEqual(experiments.arena_seeds(7200021, 512), banks["robustness"])

    def test_source_overlap_at_a_stride_offset_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            evolution.seed_banks(7100000 + 8191, evolution.PROFILES["search"], {
                "old": experiments.arena_seeds(7100000, 4)})

    def test_accepting_parent_requires_both_means_and_a_changed_model(self):
        candidate = self.root / "candidate.json"
        experiments.document(candidate, evolution.proposals(self.model)[1]["model"])
        accepted = evolution.round_transition(self.baseline, .5, 1, candidate, metrics())
        self.assertEqual({"parent": str(candidate), "radius": .5, "failures": 0, "accepted": True}, accepted)
        for win, reward, path in ((0, .2, candidate), (.1, 0, candidate), (.1, .2, self.baseline)):
            with self.subTest(win=win, reward=reward, path=path):
                rejected = evolution.round_transition(self.baseline, .5, 1, path, metrics(win, reward))
                self.assertEqual({"parent": str(self.baseline), "radius": .25, "failures": 2,
                                  "accepted": False}, rejected)

    def test_final_gates_require_both_positive_bounds_and_the_declared_sample_size(self):
        for stage, count in (("confirm", 1024), ("robustness", 512)):
            self.assertTrue(evolution.qualification_passes({"profile": "search"}, metrics(count=count), stage))
            for name in ("winShareDifference", "rewardDifference"):
                for changed in ({"count": count - 1}, {"lower95": 0}, {"lower95": None}):
                    value = metrics(count=count)
                    value[name].update(changed)
                    with self.subTest(stage=stage, metric=name, changed=changed):
                        self.assertFalse(evolution.qualification_passes({"profile": "search"}, value, stage))
            self.assertFalse(evolution.qualification_passes({"profile": "smoke"}, metrics(count=count), stage))

    def run_evolution(self, outcomes, profile="search", seed_models=()):
        protocol = self.protocol(profile)
        if seed_models:
            protocol["seedModels"] = list(map(str, seed_models))
        output = self.root / "run"
        output.mkdir(exist_ok=True)
        selected = [{"candidateId": candidate, "overall": metrics(win, reward)}
                    for candidate, win, reward in outcomes]

        def native(_protocol, _mode, _source, directory, extra):
            self.assertEqual(("--workers", "4"), extra)
            # Only native verification is mocked; immutable proposal and receipt machinery is real.
            experiments.document(directory / "search.json", {"screening": []})

        with mock.patch.object(experiments, "remaining_seconds", return_value=3600), \
                mock.patch.object(experiments, "native", side_effect=native) as invoke, \
                mock.patch.object(evolution, "verify_search", side_effect=selected):
            result = evolution.evolve_stage(protocol, output)
        return protocol, output, result, invoke.call_count

    def test_two_failures_stop_without_opening_final_banks(self):
        _, output, result, calls = self.run_evolution([(0, 0, 0), (0, 0, 0)])
        self.assertEqual(2, calls)
        self.assertEqual("rejected", result["status"])
        self.assertFalse((output / "confirmation-source.json").exists())
        self.assertEqual([1, .5], [row["radius"] for row in result["metrics"]["rounds"]])

    def test_last_accepted_parent_is_frozen_after_later_failures(self):
        protocol, output, result, calls = self.run_evolution([(1, .1, .2), (0, 0, 0), (0, 0, 0)])
        self.assertEqual(3, calls)
        self.assertEqual("passed", result["status"])
        self.assertEqual(experiments.read(output / "rounds/round-0/proposal-1.json"),
                         experiments.read(output / "selected/candidate.json"))
        for name in ("confirmation", "robustness"):
            source = experiments.read(output / f"{name}-source.json")
            self.assertEqual(protocol["baseline"], source["incumbent"])
            self.assertEqual(str(output / "selected/candidate.json"), source["candidate"])
            self.assertEqual(protocol["frozenPurchase"], source["frozenPurchase"])
        self.assertFalse(experiments.read(output / "evolve-report.json")["accepted"])

    def test_maximum_four_rounds_bounds_a_successful_training_sequence(self):
        _, _, _, calls = self.run_evolution([(1, .1, .2)] * 4)
        self.assertEqual(4, calls)

    def test_cached_receipts_resume_without_new_native_calls_or_worlds(self):
        protocol, output, expected, _ = self.run_evolution([(0, 0, 0), (0, 0, 0)])
        with mock.patch.object(experiments, "native") as native, \
                mock.patch.object(experiments, "remaining_seconds") as remaining:
            self.assertEqual(expected, evolution.evolve_stage(protocol, output))
        native.assert_not_called()
        remaining.assert_not_called()

    def test_tampered_completed_round_prevents_resume(self):
        protocol, output, _, _ = self.run_evolution([(0, 0, 0), (0, 0, 0)])
        experiments.document(output / "rounds/round-0/search/search.json", {"screening": ["changed"]}, immutable=False)
        with mock.patch.object(experiments, "native") as native, self.assertRaisesRegex(ValueError, "artifacts changed"):
            evolution.evolve_stage(protocol, output)
        native.assert_not_called()

    def test_round_admission_uses_original_remaining_stage_budget(self):
        protocol = self.protocol()
        output = self.root / "run"
        output.mkdir()
        with mock.patch.object(experiments, "remaining_seconds", return_value=9), \
                mock.patch.object(experiments, "native") as native:
            result = evolution.evolve_stage(protocol, output)
        native.assert_not_called()
        self.assertEqual("rejected", result["status"])
        self.assertIn("confirmation reserve retained", result["summary"])

    def preparation_inputs(self):
        source, runtime = self.root / "source", self.root / "new-runtime"
        experiments.document(source / "campaign.json", {"kind": "automata-campaign"})
        experiments.document(source / "protocol.json", {})
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"explicit-new-runtime")
        java = self.root / "java"
        java.write_bytes(b"jdk")
        identity = {"executable": str(java), "files": {str(java): experiments.sha(java)}}
        original = {"baseline": str(self.baseline), "java": identity,
                    "seedBanks": {"old": experiments.arena_seeds(7100000, 8)}}
        bundle = self.root / "bundle.json"
        experiments.document(bundle, {"configuration": evolution.BUNDLE_CONFIGURATION,
            "baseline": self.model, "model": {"actorWeights": [1.0]}, "provenance": {
                "selectedUpdate": 32, "qualificationPassed": True,
                "sourceBaselineSha256": experiments.sha(self.baseline)}})
        robust = self.root / "robust.json"
        experiments.document(robust, evolution.proposals(self.model)[1]["model"])
        options = argparse.Namespace(from_campaign=source, runtime=runtime, purchase_bundle=bundle,
            output=self.root / "prepared", opponent=[], robust_opponent=[robust], workers=4,
            maximum_seconds=43200, profile="search", seed=7200000)
        return options, original, identity

    def prepare(self, options, original, identity):
        with mock.patch.object(search, "source_inputs", return_value=(original, self.root / "old-runtime", {})), \
                mock.patch.object(experiments, "java_identity", return_value=identity), \
                contextlib.redirect_stdout(io.StringIO()):
            return evolution.prepare(options)

    def test_preparation_pins_new_runtime_frozen_actor_and_nonrenewable_stage_caps(self):
        options, original, identity = self.preparation_inputs()
        campaign = self.prepare(options, original, identity)
        protocol = experiments.read(options.output / "protocol.json")
        self.assertEqual([28800, 7200, 7200], [row["maximumSeconds"] for row in campaign["stages"]])
        self.assertEqual([[], ["evolve"], ["confirm"]], [row["dependsOn"] for row in campaign["stages"]])
        self.assertEqual(["evolve", "confirm", "robustness"], [row["id"] for row in campaign["stages"]])
        self.assertEqual(b"explicit-new-runtime", (options.output / "runtime/lib/training.jar").read_bytes())
        self.assertEqual(self.model, experiments.read(protocol["frozenPurchase"]["baseline"]))
        self.assertEqual({"actorWeights": [1.0]}, experiments.read(protocol["frozenPurchase"]["policy"]))
        for name in ("inputs/purchase-bundle.json", "inputs/purchase-prior.json", "inputs/purchase-policy.json",
                     "inputs/robust-opponent-0.json", "scripts/automata_evolution.py", "runtime/lib/training.jar"):
            self.assertEqual(experiments.sha(options.output / name), campaign["inputs"][name])

    def test_robustness_pool_must_be_nonempty_and_distinct_from_training(self):
        options, original, identity = self.preparation_inputs()
        for pool in ([], [self.baseline], [*options.robust_opponent, *options.robust_opponent]):
            with self.subTest(pool=pool), self.assertRaisesRegex(ValueError, "distinct"):
                self.prepare(argparse.Namespace(**dict(vars(options), robust_opponent=pool)), original, identity)
        self.assertFalse(options.output.exists())

    def test_preparation_rejects_purchase_bundle_with_different_prior(self):
        options, original, identity = self.preparation_inputs()
        bundle = experiments.read(options.purchase_bundle)
        bundle["baseline"]["weights"]["cash"] += 1
        experiments.document(options.purchase_bundle, bundle, immutable=False)
        with self.assertRaisesRegex(ValueError, "P32 bundle"):
            self.prepare(options, original, identity)

    def archived_models(self):
        proposals = evolution.proposals(self.model)
        models = [self.root / f"archive-{index}.json" for index in range(3)]
        for path, candidate in zip(models, (1, 6, 11)):
            experiments.document(path, proposals[candidate]["model"])
        return models

    def test_archive_preparation_freezes_distinct_saved_models_with_hash_provenance(self):
        options, original, identity = self.preparation_inputs()
        models = self.archived_models()
        options.profile, options.seed_model = "archive", models
        campaign = self.prepare(options, original, identity)
        protocol = experiments.read(options.output / "protocol.json")
        self.assertEqual(1, protocol["rounds"])
        self.assertEqual(3, protocol["finalists"])
        self.assertEqual((512, 1024, 2048, 1024), tuple(protocol[key] for key in (
            "screeningPairs", "selectionPairs", "confirmationPairs", "robustnessPairs")))
        provenance = experiments.read(options.output / "provenance.json")["copiedInputs"]
        for source, frozen in zip(models, protocol["seedModels"]):
            self.assertEqual(source.read_bytes(), Path(frozen).read_bytes())
            self.assertEqual(experiments.sha(source), protocol["seedModelSha256"][frozen])
            self.assertEqual(experiments.sha(source), campaign["inputs"][str(Path(frozen).relative_to(options.output))])
            self.assertIn({"source": str(source), "copy": frozen, "sha256": experiments.sha(source)}, provenance)

    def test_archive_rejects_missing_duplicate_baseline_or_excess_seed_models(self):
        options, original, identity = self.preparation_inputs()
        models = self.archived_models()
        for paths in ([], [models[0], models[0]], [self.baseline], models * 6):
            rejected = argparse.Namespace(**dict(vars(options), profile="archive", seed_model=paths))
            with self.subTest(paths=paths), self.assertRaisesRegex(ValueError, "seed models"):
                self.prepare(rejected, original, identity)
        self.assertFalse(options.output.exists())

    def test_seed_models_are_forbidden_in_standard_profiles(self):
        options, original, identity = self.preparation_inputs()
        for profile in ("smoke", "search"):
            rejected = argparse.Namespace(**dict(vars(options), profile=profile, seed_model=[self.baseline]))
            with self.subTest(profile=profile), self.assertRaisesRegex(ValueError, "archive profile"):
                self.prepare(rejected, original, identity)

    def test_archive_race_freezes_the_selected_checkpoint_without_generating_new_models(self):
        models = self.archived_models()
        with mock.patch.object(evolution, "proposals", side_effect=AssertionError("Archive must not generate models")):
            _, output, result, calls = self.run_evolution([(1, .1, .2)], "archive", models)
        self.assertEqual(1, calls)
        self.assertEqual("passed", result["status"])
        self.assertEqual(models[0].read_bytes(), (output / "selected/candidate.json").read_bytes())
        self.assertNotEqual(models[-1].read_bytes(), (output / "selected/candidate.json").read_bytes())
        for index, source in enumerate((self.baseline, *models)):
            self.assertEqual(source.read_bytes(), (output / f"rounds/round-0/proposal-{index}.json").read_bytes())
        summary = experiments.read(output / "rounds/round-0/summary.json")
        self.assertEqual("archive", summary["module"])
        self.assertEqual({"anchor", "archive"}, set(summary["modules"]))

    def test_archive_zero_mean_does_not_admit_a_final_candidate(self):
        _, output, result, calls = self.run_evolution([(1, 0, .2)], "archive", self.archived_models())
        self.assertEqual(1, calls)
        self.assertEqual("rejected", result["status"])
        self.assertFalse((output / "confirmation-source.json").exists())

    def test_archive_final_gates_require_the_larger_declared_banks(self):
        protocol = self.protocol("archive")
        for stage, count in (("confirm", 2048), ("robustness", 1024)):
            with self.subTest(stage=stage):
                self.assertEqual(count, evolution.minimum_pairs(protocol, stage))
                self.assertFalse(evolution.qualification_passes(protocol, metrics(count=count - 1), stage))
                self.assertTrue(evolution.qualification_passes(protocol, metrics(count=count), stage))

    def frozen_purchase_report(self, protocol, directory):
        result = {}
        for name, path in protocol["frozenPurchase"].items():
            snapshot = f"frozen-purchase-{name}.json"
            evolution.freeze_copy(path, directory / snapshot)
            result[name] = {"path": path, "sha256": experiments.sha(path), "snapshot": snapshot}
        return result

    def terminal_pair(self, index, seed, delta):
        incumbent = {"wins": [0.0] * 4, "rewards": [.1] * 4, "ranks": [2.0] * 4,
                     "scores": [{"total": 100}] * 4, "decisions": 100, "elapsedMillis": 10}
        candidate = copy.deepcopy(incumbent)
        candidate["wins"][index % 4] += delta
        candidate["rewards"][index % 4] += delta / 2
        candidate["elapsedMillis"] = 20
        return {"index": index, "seed": seed, "seat": index % 4,
                "configuration": experiments.CONFIGURATION, "opponentIndices": [0] * 4,
                "candidate": candidate, "incumbent": incumbent}

    def test_final_arena_uses_real_evaluation_shape_without_search_metadata(self):
        protocol, output, _, _ = self.run_evolution([(1, .1, .2)], profile="smoke")
        directory = output / "confirmation"
        selection = experiments.read(output / "selection.json")
        frozen = self.frozen_purchase_report(protocol, directory)
        pairs = [self.terminal_pair(index, seed, .1)
                 for index, seed in enumerate(protocol["seedBanks"]["confirmation"])]
        for pair in pairs:
            experiments.document(directory / f"pair-{pair['index']}.json", pair)
        experiments.document(directory / "evaluation.json", {
            "complete": True, "purpose": "CONFIRMATION", "frozenPurchase": frozen,
            "overall": dict(search.comparison(pairs), pairs=len(pairs))})
        experiments.document(directory / "run.json", {
            "kind": "strategy-arena", "signature": "frozen", "workers": 4,
            "source": str(output / "confirmation-source.json"), "frozenPurchase": frozen})
        experiments.document(directory / "state.json", {
            "kind": "strategy-arena", "signature": "frozen", "phase": "complete"})
        experiments.document(directory / "finished.json", {"complete": True})
        for name, path in (("candidate", selection["candidate"]), ("incumbent", self.baseline),
                           ("opponent-0", self.baseline)):
            evolution.freeze_copy(path, directory / f"{name}.json")
        with mock.patch.object(experiments, "native") as native:
            result = evolution.final_stage(protocol, output, "confirm")
        native.assert_called_once_with(protocol, "arena", output / "confirmation-source.json", directory)
        self.assertEqual("rejected", result["status"])
        self.assertEqual(4, result["metrics"]["winShareDifference"]["count"])
        self.assertFalse(experiments.read(output / "confirm-report.json")["accepted"])

    def search_fixture(self):
        protocol = dict(self.protocol("smoke"), finalists=1)
        source, directory = self.root / "source.json", self.root / "native"
        pool = evolution.proposals(self.model)[:2]
        identity = {"kind": "strategy-search", "signature": "frozen", "workers": 4,
                    "source": str(source), "frozenPurchase": self.frozen_purchase_report(protocol, directory)}
        for name in ("initial", "incumbent", "opponent-0"):
            experiments.document(directory / f"{name}.json", self.model)
        for row in pool:
            experiments.document(directory / f"candidate-{row['candidateId']}.json", row["model"])
        report = dict(identity, complete=True, selectionOnly=True, candidates=2, finalists=1,
                      selectedCandidateId=1, sourceParameters=[], banks={})
        for offset, bank in enumerate(("screening", "selection")):
            seeds = protocol["seedBanks"][f"round-0-{bank}"]
            report["banks"][bank] = {"purpose": "SCREENING", "seedOffset": protocol["seed"] + offset,
                                     "pairsPerConfiguration": len(seeds), "seeds": seeds}
            rows = []
            for candidate_id in ([0, 1] if bank == "screening" else [1]):
                pairs = [self.terminal_pair(index, seed, .1 * candidate_id) for index, seed in enumerate(seeds)]
                for pair in pairs:
                    experiments.document(directory / f"{bank}-candidate-{candidate_id}-pair-{pair['index']}.json", pair)
                    experiments.document(directory / f"{bank}-incumbent-{pair['index']}.json", pair["incumbent"])
                rows.append({"candidateId": candidate_id, "label": "current" if candidate_id == 0 else "sample-1",
                             "parameters": pool[candidate_id]["changes"], "model": f"candidate-{candidate_id}.json",
                             "overall": dict(search.comparison(pairs), pairs=len(pairs))})
            report[bank] = sorted(rows, key=search.ranking)
        experiments.document(directory / "candidate.json", pool[1]["model"])
        experiments.document(directory / "search.json", report)
        experiments.document(directory / "run.json", identity)
        experiments.document(directory / "state.json", dict(identity, phase="complete", selectedCandidateId=1))
        experiments.document(directory / "finished.json", {"complete": True})
        return protocol, source, directory, pool

    def test_native_search_verifies_complete_explicit_pool_and_selected_model(self):
        protocol, source, directory, pool = self.search_fixture()
        selected = evolution.verify_search(protocol, source, directory, pool, 0)
        self.assertEqual(1, selected["candidateId"])

    def test_native_search_preserves_explicit_zero_weights_in_an_exact_saved_model(self):
        protocol, source, directory, pool = self.search_fixture()
        pool[1]["model"]["weights"]["planning.retainedLeader"] = 0.0
        for name in ("candidate-1.json", "candidate.json"):
            experiments.document(directory / name, pool[1]["model"], immutable=False)
        self.assertEqual(1, evolution.verify_search(protocol, source, directory, pool, 0)["candidateId"])

    def test_native_search_rejects_changed_anchor_gameplay_without_return_changes(self):
        protocol, source, directory, pool = self.search_fixture()
        path = directory / "screening-candidate-0-pair-0.json"
        pair = experiments.read(path)
        pair["candidate"]["scores"][0]["total"] += 1
        experiments.document(path, pair, immutable=False)
        with self.assertRaisesRegex(ValueError, "exact incumbent gameplay parity"):
            evolution.verify_search(protocol, source, directory, pool, 0)

    def test_native_search_rejects_missing_selection_pair(self):
        protocol, source, directory, pool = self.search_fixture()
        (directory / "selection-candidate-1-pair-3.json").unlink()
        with self.assertRaises(FileNotFoundError):
            evolution.verify_search(protocol, source, directory, pool, 0)


if __name__ == "__main__":
    unittest.main()
