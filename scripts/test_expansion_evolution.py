"""Deterministic proposals, bounded evolution and independent final gate contracts."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import expansion_evolution as evolution
import expansion_arena


def metrics(reward=.1, win=.1, count=128, lower=.01):
    return {name: {"mean": value, "count": count, "lower95": lower, "upper95": value + .1}
            for name, value in (("rewardDifference", reward), ("winShareDifference", win))}


class ExpansionEvolutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.baseline = {"schemaVersion": 1, "rulesVersion": "old", "dataVersion": "data",
                         "weights": {"tokens": 1.8, "income": 4.1, "cards": 17.0,
                                     "planning.tokenValue.enabled": 1.0}}
        self.normalization = {"features": [3., 1., 1., 2., 2.5, 1.5, 1., 1.], "temperature": 21.6}
        self.bounds = [8.] * 8

    def model(self, coordinate=1.):
        return evolution.export_model([coordinate] * 8, self.baseline, self.normalization, self.bounds, "new")

    def population(self, parents=(), seed=101):
        return evolution.population(self.baseline, list(parents), self.normalization, self.bounds, seed, "new")

    def test_proposals_are_deterministic_and_do_not_mutate_parents(self):
        parent = self.model()
        original = copy.deepcopy(parent)
        first = self.population([parent])
        self.assertEqual(first, self.population([parent]))
        self.assertEqual(parent, original)
        self.assertEqual(len(first), 8)
        self.assertEqual(len({evolution.model_key(row["model"]) for row in first}), 8)

    def test_anchor_has_exact_original_weights_and_other_phases_never_change(self):
        result = self.population([self.model()])
        self.assertEqual(evolution.model_key(result[0]["model"]), evolution.model_key(self.baseline))
        for row in result:
            self.assertEqual(row["model"]["weights"]["cards"], 17.)
            self.assertEqual(row["model"]["weights"]["planning.tokenValue.enabled"], 1.)

    def test_each_child_uses_fixed_cyclic_sigmas_and_alternating_centers(self):
        children = [row for row in self.population([self.model(), self.model(-1)]) if row["kind"] == "mutation"]
        self.assertEqual([row["sigma"] for row in children], [.25, .5, 1., 2., .25])
        self.assertEqual([len(row["parentIndices"]) for row in children], [1, 2, 1, 2, 1])
        self.assertTrue(all(len(set(row["parentIndices"])) == len(row["parentIndices"]) for row in children))

    def test_two_parent_center_is_the_arithmetic_mean(self):
        result = self.population([self.model(), self.model(-2)])
        row = next(row for row in result if len(row.get("parentIndices", [])) == 2)
        parents = [result[index]["coordinates"] for index in row["parentIndices"]]
        self.assertEqual(row["center"], [(a + b) / 2 for a, b in zip(*parents)])

    def test_one_parent_fallback_still_uses_joint_noise(self):
        children = self.population()[1:]
        self.assertTrue(all(row["parentIndices"] == [0] and len(row["gaussianNoise"]) == 8 for row in children))
        self.assertTrue(all(len(set(row["gaussianNoise"])) == 8 for row in children))

    def test_raw_coefficients_round_trip_normalized_coordinates(self):
        values = [index / 3 for index in range(8)]
        model = evolution.export_model(values, self.baseline, self.normalization, self.bounds, "new")
        for actual, expected in zip(evolution.coordinates(model, self.baseline, self.normalization), values):
            self.assertAlmostEqual(actual, expected, places=13)

    def test_coordinate_bounds_are_fixed_by_initial_models_only(self):
        large = evolution.export_model([9.] + [0.] * 7, self.baseline, self.normalization, [10.] * 8, "new")
        actual = evolution.coordinate_bounds([large], self.baseline, self.normalization)
        self.assertAlmostEqual(actual[0], 9.)
        self.assertEqual(actual[1:], [8.] * 7)

    def test_child_clipping_never_expands_bounds(self):
        noise = [1000. if index != child else -1000. for child in range(8) for index in range(8)]
        with mock.patch("random.Random.gauss", side_effect=noise):
            result = self.population([self.model()])
        self.assertTrue(all(abs(v) <= 8 for row in result for v in row["coordinates"]))

    def test_unbounded_or_nonfinite_export_is_rejected(self):
        for value in (9., float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                evolution.export_model([value] * 8, self.baseline, self.normalization, self.bounds, "new")

    def test_non_expansion_weight_change_is_rejected(self):
        changed = copy.deepcopy(self.baseline)
        changed["weights"]["cards"] += 1
        with self.assertRaisesRegex(ValueError, "eight declared"):
            evolution.coordinates(changed, self.baseline, self.normalization)

    def test_duplicate_initial_models_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "distinct"):
            self.population([self.model(), self.model()])

    def test_fixed_proposal_attempt_ceiling_fails_closed(self):
        with mock.patch("random.Random.gauss", return_value=0.), mock.patch.object(evolution, "MAX_PROPOSALS", 3):
            with self.assertRaisesRegex(ValueError, "fixed proposal ceiling"):
                self.population()

    def test_model_fingerprint_normalizes_numeric_zeros_and_number_types(self):
        first = {"schemaVersion": 1, "weights": {"a": 1, "unused": 0}}
        second = {"schemaVersion": 1, "weights": {"a": 1.0}}
        self.assertEqual(evolution.model_key(first), evolution.model_key(second))

    def test_elites_use_positive_reward_then_win_then_fixed_index(self):
        rows = [{"candidateIndex": i, "metrics": metrics(reward, win)} for i, reward, win in
                ((0, 1., 1.), (1, .2, -.1), (2, .2, .1), (3, 0., 1.), (4, .2, .1))]
        self.assertEqual([row["candidateIndex"] for row in evolution.elites(rows)], [2, 4])

    def test_elite_can_have_negative_win_mean_but_selection_cannot(self):
        rows = [{"candidateIndex": 1, "metrics": metrics(.3, -.1)}]
        self.assertEqual(len(evolution.elites(rows)), 1)
        self.assertIsNone(evolution.selected(rows))

    def test_selection_requires_strictly_positive_dual_means(self):
        for reward, win in ((0., .1), (.1, 0.), (-.1, .1)):
            self.assertIsNone(evolution.selected([{"candidateIndex": 1, "metrics": metrics(reward, win)}]))

    def test_final_gates_use_99_percent_not_descriptive_95_percent(self):
        passed, values = evolution.qualification(metrics(reward=.1, win=.1, count=2048, lower=.001), "confirm")
        self.assertFalse(passed)
        self.assertLess(values["rewardDifference"]["lower99"], 0)

    def test_small_final_bank_cannot_qualify(self):
        self.assertFalse(evolution.qualification(metrics(count=128, lower=.09), "confirm")[0])

    def test_complete_heldout_requires_both_positive_99_bounds(self):
        self.assertTrue(evolution.qualification(metrics(count=4096, lower=.09), "heldout")[0])

    def test_archive_deduplicates_by_weight_semantics(self):
        first, second = self.root / "first.json", self.root / "second.json"
        evolution.document(first, self.model())
        evolution.document(second, self.model())
        self.assertEqual(evolution.archive_append([str(first)], [str(second)]), [str(first)])

    def test_archive_cannot_exceed_eleven_models(self):
        paths = []
        for index in range(12):
            path = self.root / f"model-{index}.json"
            evolution.document(path, self.model(index / 10))
            paths.append(str(path))
        with self.assertRaisesRegex(ValueError, "eleven"):
            evolution.archive_append([], paths)

    def controller_protocol(self):
        baseline = self.root / "baseline.json"
        evolution.document(baseline, self.baseline)
        return {"baseline": str(baseline), "normalization": self.normalization,
                "coordinateBounds": self.bounds, "initialModels": [],
                "proposalSeeds": [101, 102, 103, 104], "targetRulesVersion": "new"}

    def fake_arena(self, protocol, directory, model, bank, positive=True):
        index = int(directory.name.rsplit("-", 1)[-1])
        reward = (index / 100 if positive else -index / 100)
        game = {"wins": [0., 1., 0., 0.], "rewards": [.1, .4, .2, .3],
                "ranks": [4., 1., 3., 2.], "scores": [1, 4, 2, 3], "decisions": 100}
        pair = {"index": 0, "seed": 100, "seat": 0, "configuration": "test",
                "opponentIndices": [0, 1, 2, 3], "candidate": game, "incumbent": game}
        return metrics(reward=reward, count=evolution.COUNTS[bank]), [pair], directory / "source.json"

    def test_controller_runs_four_fixed_generations_and_records_exact_elites(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        with mock.patch.object(evolution, "run_arena", side_effect=self.fake_arena) as native:
            result = evolution.evolve(protocol, output)
        archive = evolution.read(output / "archive.json")
        self.assertEqual(native.call_count, 32)
        self.assertEqual([row["eliteIndices"] for row in archive["generations"]], [[7, 6]] * 4)
        self.assertEqual(len(archive["models"]), 9)
        self.assertEqual(result["status"], "passed")
        self.assertEqual([call.args[-1] for call in native.call_args_list],
                         [f"generation-{index}" for index in range(4) for _ in range(8)])

    def test_no_elite_ever_stops_negative_without_opening_selection(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        def fake(*arguments):
            return self.fake_arena(*arguments, positive=False)
        with mock.patch.object(evolution, "run_arena", side_effect=fake) as native:
            result = evolution.evolve(protocol, output)
        self.assertEqual(native.call_count, 32)
        self.assertEqual(result["status"], "rejected")
        self.assertFalse((output / "selection").exists())
        self.assertFalse(evolution.read(output / "archive.json")["anyNonAnchorElite"])

    def test_existing_generation_cannot_be_retried(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        (output / "generation-0").mkdir(parents=True)
        with self.assertRaises(FileExistsError), mock.patch.object(evolution, "run_arena") as native:
            evolution.evolve(protocol, output)
        native.assert_not_called()

    def test_incumbent_drift_between_candidates_is_rejected(self):
        protocol = self.controller_protocol()
        def fake(*arguments):
            result, pairs, path = self.fake_arena(*arguments)
            if arguments[1].name == "evaluation-1":
                pairs[0]["incumbent"] = dict(pairs[0]["incumbent"], decisions=101)
            return result, pairs, path
        with mock.patch.object(evolution, "run_arena", side_effect=fake), self.assertRaisesRegex(ValueError, "incumbent parity"):
            evolution.evolve(protocol, self.root / "run")

    def test_evolve_receipt_excludes_mutable_campaign_status_files(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        output.mkdir()
        evolution.document(output / "state.json", {"status": "running"})
        with mock.patch.object(evolution, "run_arena", side_effect=self.fake_arena):
            result = evolution.evolve(protocol, output)
        self.assertNotIn("state.json", result["artifacts"])

    def test_selection_uses_fresh_common_worlds_and_freezes_one_winner(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        with mock.patch.object(evolution, "run_arena", side_effect=self.fake_arena):
            evolution.evolve(protocol, output)
        with mock.patch.object(evolution, "run_arena", side_effect=self.fake_arena) as native:
            result = evolution.select_archive(protocol, output)
        selected = evolution.read(output / "selected.json")
        self.assertEqual(native.call_count, 9)
        self.assertTrue(all(call.args[-1] == "selection" for call in native.call_args_list))
        self.assertEqual(selected["candidate"]["candidateIndex"], 8)
        self.assertEqual(result["status"], "passed")

    def test_selection_rejects_modified_archived_model(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        with mock.patch.object(evolution, "run_arena", side_effect=self.fake_arena):
            evolution.evolve(protocol, output)
        archive = evolution.read(output / "archive.json")
        Path(archive["models"][0]["path"]).write_text("{}")
        with mock.patch.object(evolution, "run_arena") as native, self.assertRaisesRegex(ValueError, "model bytes changed"):
            evolution.select_archive(protocol, output)
        native.assert_not_called()

    def final_selection(self):
        protocol = self.controller_protocol()
        output = self.root / "run"
        model = self.root / "selected-model.json"
        evolution.document(model, self.model())
        candidate = {"candidateIndex": 1, "model": str(model), "modelSha256": evolution.sha(model),
                     "metrics": metrics(count=512)}
        evolution.document(output / "selected.json", {"candidate": candidate, "rows": [candidate]})
        return protocol, output, candidate

    def test_failed_confirmation_never_opens_heldout(self):
        protocol, output, candidate = self.final_selection()
        evolution.document(output / "confirm-report.json", {"metrics": {"passed": False,
            "candidateSha256": candidate["modelSha256"], "estimates": metrics(count=2048)}})
        with mock.patch.object(evolution, "run_arena") as native, self.assertRaisesRegex(ValueError, "passed independent confirmation"):
            evolution.final(protocol, output, "heldout")
        native.assert_not_called()

    def test_falsely_marked_confirmation_does_not_bypass_numerical_gate(self):
        protocol, output, candidate = self.final_selection()
        evolution.document(output / "confirm-report.json", {"metrics": {"passed": True,
            "candidateSha256": candidate["modelSha256"], "estimates": metrics(count=2048, lower=-.1)}})
        with mock.patch.object(evolution, "run_arena") as native, self.assertRaisesRegex(ValueError, "passed independent confirmation"):
            evolution.final(protocol, output, "heldout")
        native.assert_not_called()

    def test_final_requires_eight_canonical_replays(self):
        protocol, output, _ = self.final_selection()
        with mock.patch.object(evolution, "run_arena", return_value=(metrics(count=2048), [None] * 2048, output / "source.json")), \
                mock.patch.object(expansion_arena, "replays", return_value=[{}] * 7), \
                self.assertRaisesRegex(ValueError, "eight canonical replay"):
            evolution.final(protocol, output, "confirm")

    def test_final_candidate_cannot_change_after_selection(self):
        protocol, output, candidate = self.final_selection()
        Path(candidate["model"]).write_text("{}")
        with mock.patch.object(evolution, "run_arena") as native, self.assertRaisesRegex(ValueError, "unchanged selected model"):
            evolution.final(protocol, output, "confirm")
        native.assert_not_called()

    def test_source_keeps_ordinary_baseline_and_exact_four_two_rival_pools(self):
        protocol = dict(self.controller_protocol(), opponents=["a", "b", "c", "d"], heldoutOpponents=["e", "f"],
            frozenPurchase={"baseline": "F0", "policy": "C"}, experimentalRulesTransfer={"sourceRulesVersion": "old", "targetRulesVersion": "new"},
            worldBanks={name: {"nativeOffset": index} for index, name in enumerate(evolution.COUNTS)})
        ordinary = evolution.source(protocol, "mutation.json", "generation-0")
        heldout = evolution.source(protocol, "mutation.json", "heldout")
        self.assertEqual(ordinary["candidate"], ordinary["incumbent"])
        self.assertEqual(ordinary["opponents"], ["a", "b", "c", "d"])
        self.assertEqual(heldout["opponents"], ["e", "f"])
        self.assertEqual(ordinary["expansionImitation"], {"candidateModel": "mutation.json"})
        self.assertNotIn("tokenOrder", ordinary)

    def test_audited_bank_aliases_preserve_the_exact_reservations(self):
        banks = {name: {"count": 1} for name in evolution.AUDIT_BANK_NAMES.values()}
        for index in range(4):
            banks[f"generation{index}"]["proposalSeeds"] = [100 + index]
        actual, seeds = evolution.audited_reservations({"allIntersectionsZero": True, "seedBanks": banks})
        self.assertEqual(actual["confirm"], banks["confirmation"])
        self.assertEqual(seeds, [100, 101, 102, 103])

    def test_missing_proposal_seed_is_rejected_before_any_games(self):
        banks = {name: {"count": 1, "proposalSeeds": []} for name in evolution.AUDIT_BANK_NAMES.values()}
        with self.assertRaisesRegex(ValueError, "exactly one"):
            evolution.audited_reservations({"allIntersectionsZero": True, "seedBanks": banks})


if __name__ == "__main__":
    unittest.main()
