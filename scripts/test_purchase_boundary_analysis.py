"""Synthetic tests only: no retained 40m features or critic predictions are read."""
import copy
import unittest

import purchase_boundary_analysis as analysis


def row(features=None):
    return {"rootIndex": 0, "world": 7, "action": 0, "replicate": 0,
            "sourceTailSha256": "frozen", "kind": "purchase",
            "encoderVersion": "aor-purchase-observation-1", "boundaryTurn": 3,
            "boundaryDecision": "purchase-shipping", "stateFeatures": features or {"epoch": 2 / 3}}


def table(selection_gap=.03, evaluation_gap=-.9):
    return {(root, action, rep): .5 + action * (selection_gap if rep < 8 else evaluation_gap)
            for root in range(32) for action in range(2) for rep in range(16)}


def labels():
    return {(root, action, rep): {"world": root * 11,
            "reward": .5 + action * (.2 if rep < 8 else -.1),
            "winShare": .5, "rankReward": .5}
            for root in range(32) for action in range(2) for rep in range(16)}


class FeatureTests(unittest.TestCase):
    def test_dictionary_order_missing_zero_and_original_units(self):
        self.assertEqual([0.0, 2 / 3], analysis.feature_vector(row(), ["cash", "epoch"]))

    def test_label_field_is_rejected(self):
        contaminated = dict(row(), reward=.9)
        with self.assertRaisesRegex(ValueError, "no labels"):
            analysis.feature_vector(contaminated, ["epoch"])

    def test_unknown_feature_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "dictionary"):
            analysis.feature_vector(row({"recommendation": 1}), ["epoch"])

    def test_nonfinite_features_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "finite"):
            analysis.feature_vector(row({"epoch": float("nan")}), ["epoch"])

    def test_terminal_boundaries_do_not_fabricate_features(self):
        terminal = dict(row(), kind="terminal", stateFeatures=None)
        self.assertIsNone(analysis.feature_vector(terminal, ["epoch"]))


class SplitTests(unittest.TestCase):
    def test_primary_selection_cannot_use_evaluation_predictions(self):
        result = analysis.comparison(table(), labels(), range(8), range(8, 16))
        self.assertEqual(32, result["optionSelectedOrigins"])
        self.assertAlmostEqual(-.1, result["evaluationDifferenceVsBaseline"]["reward"]["mean"])

    def test_selection_interface_rejects_evaluation_half(self):
        with self.assertRaisesRegex(ValueError, "exactly"):
            analysis.choose(table(), range(8))

    def test_changing_evaluation_labels_does_not_change_choices(self):
        changed = copy.deepcopy(labels())
        for key, value in changed.items():
            if key[2] >= 8:
                value["reward"] = key[1]
        first = analysis.comparison(table(), labels(), range(8), range(8, 16))
        second = analysis.comparison(table(), changed, range(8), range(8, 16))
        self.assertEqual([r["selectedAction"] for r in first["roots"]],
                         [r["selectedAction"] for r in second["roots"]])

    def test_exact_margin_tie_uses_baseline(self):
        values = {(root, action, rep): action * .02 for root in range(32)
                  for action in range(2) for rep in range(8)}
        choices, _ = analysis.choose(values, range(8))
        self.assertEqual({0}, set(choices.values()))

    def test_secondary_uses_only_swapped_selection_half(self):
        result = analysis.comparison(table(), labels(), range(8, 16), range(8))
        self.assertEqual(0, result["optionSelectedOrigins"])
        self.assertEqual(0, result["evaluationDifferenceVsBaseline"]["reward"]["mean"])

    def test_overlapping_halves_rejected(self):
        with self.assertRaisesRegex(ValueError, "disjoint"):
            analysis.comparison(table(), labels(), range(8), range(7, 16))

    def test_aggregate_uses_origins_not_individual_tails(self):
        result = analysis.comparison(table(), labels(), range(8), range(8, 16))
        self.assertEqual(32, result["evaluationDifferenceVsBaseline"]["reward"]["origins"])


if __name__ == "__main__":
    unittest.main()
