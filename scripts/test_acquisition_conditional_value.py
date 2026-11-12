"""Synthetic privacy, grouping, and numerical checks; no pilot outcomes or scientific fits."""
import copy
import unittest

import numpy as np

import acquisition_conditional_value as diagnostic


def actor_input():
    players = [{"id": {"value": f"p{i}"}, "cash": 20, "handCount": 2,
                "stockTokens": 10, "poolTokens": 9, "boardMarkers": 3, "stockMarkers": 22,
                "income": {name: 0 for name in diagnostic.INCOME_FIELDS},
                "misery": "ZERO", "advances": []} for i in range(4)]
    return {"view": {"players": players, "currentEpoch": 2, "turnNumber": 5,
                     "deckRemaining": 12, "endingTriggered": False,
                     "stabilizationCost": 5, "stabilizationMisery": 20},
            "observation": {"playerId": "p0", "decision": "expand", "candidates": [
                {"command": "buy-expansion-card", "features": {"cards": 1, "tokens": -3},
                 "cashCost": 0, "tokenCost": 3},
                {"command": "place 2 paris", "features": {"action.expansion": 1, "tokens": -2},
                 "cashCost": 0, "tokenCost": 2}]}}


class FeaturePrivacyTest(unittest.TestCase):
    def test_dictionary_is_fixed_unique_and_bounded(self):
        self.assertEqual(len(diagnostic.FEATURE_NAMES), 105)
        self.assertEqual(len(set(diagnostic.FEATURE_NAMES)), 105)
        self.assertEqual(len(diagnostic.extract(actor_input(), "EXPANSION", "place 2 paris")), 105)

    def test_future_identity_and_unknown_feature_fields_do_not_enter_input(self):
        original = actor_input()
        altered = copy.deepcopy(original)
        altered["view"].update({"scores": [{"total": 9999}], "hand": ["secret-card"],
                                "canonicalEntries": ["future"], "drawnCard": "secret"})
        altered["view"]["players"][1]["hiddenHand"] = ["secret"]
        altered["observation"].update({"seed": 123, "futureReward": 1, "chosenWinner": "BUY"})
        altered["observation"]["candidates"][0]["features"]["futureReward"] = 1e9
        np.testing.assert_array_equal(diagnostic.extract(original, "EXPANSION", "place 2 paris"),
                                      diagnostic.extract(altered, "EXPANSION", "place 2 paris"))

    def test_renaming_ids_preserves_features(self):
        original = actor_input()
        altered = copy.deepcopy(original)
        for i, player in enumerate(altered["view"]["players"]):
            player["id"]["value"] = f"renamed-{i}"
        altered["observation"]["playerId"] = "renamed-0"
        np.testing.assert_array_equal(diagnostic.extract(original, "EXPANSION", "place 2 paris"),
                                      diagnostic.extract(altered, "EXPANSION", "place 2 paris"))

    def test_public_rival_order_does_not_enter_features(self):
        original = actor_input()
        altered = copy.deepcopy(original)
        altered["view"]["players"].reverse()
        np.testing.assert_array_equal(diagnostic.extract(original, "EXPANSION", "place 2 paris"),
                                      diagnostic.extract(altered, "EXPANSION", "place 2 paris"))

    def test_authoritative_own_economics_and_candidate_cost_are_retained(self):
        actor = actor_input()
        actor["view"]["players"][0].update({"misery": "HUNDRED_TWENTY_FIVE", "advances": ["PRINTED_WORD"]})
        features = dict(zip(diagnostic.FEATURE_NAMES, diagnostic.extract(actor, "EXPANSION", "place 2 paris")))
        self.assertEqual(features["own.misery"], 125)
        self.assertEqual(features["own.advance.PRINTED_WORD"], 1)
        self.assertEqual(features["BUY.tokenCost"], 3)
        self.assertEqual(features["REFERENCE.tokenCost"], 2)

    def test_cash_discard_is_not_a_decline(self):
        actor = actor_input()
        actor["observation"]["decision"] = "buy-card"
        with self.assertRaisesRegex(ValueError, "explicit pass"):
            diagnostic.extract(actor, "CASH", "discard commodity")

    def test_missing_reference_candidate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly once"):
            diagnostic.extract(actor_input(), "EXPANSION", "pass")

    def test_nonfinite_whitelisted_feature_is_rejected(self):
        actor = actor_input()
        actor["observation"]["candidates"][0]["features"]["cards"] = float("nan")
        with self.assertRaisesRegex(ValueError, "finite"):
            diagnostic.extract(actor, "EXPANSION", "place 2 paris")


class GroupingAndScalingTest(unittest.TestCase):
    def test_every_origin_is_held_out_once_with_both_routes(self):
        rows = [{"index": i, "route": route} for i in range(10) for route in diagnostic.ROUTES]
        held_out = []
        for fold in range(5):
            train, heldout = diagnostic.partition(rows, fold)
            self.assertEqual({rows[i]["index"] % 5 for i in heldout}, {fold})
            self.assertFalse({rows[i]["index"] for i in train} & {rows[i]["index"] for i in heldout})
            held_out.extend(heldout.tolist())
        self.assertEqual(sorted(held_out), list(range(20)))

    def test_mean_loss_ridge_has_unpenalized_intercept_and_fixed_lambda(self):
        fitted = diagnostic.fit_ridge([[-1], [1]], [1, 3], [1, 1])
        self.assertAlmostEqual(fitted["intercept"], 2)
        self.assertAlmostEqual(fitted["coefficients"][0], 1 / 11)

    def test_weighted_training_population_scale_matches_closed_form(self):
        fitted = diagnostic.fit_ridge([[-1], [3]], [0, 4], [3, 1])
        np.testing.assert_allclose(fitted["mean"], [0])
        np.testing.assert_allclose(fitted["standardDeviation"], [np.sqrt(3)])
        self.assertAlmostEqual(fitted["intercept"], 1)
        self.assertAlmostEqual(fitted["coefficients"][0], np.sqrt(3) / 11)

    def test_splitting_a_world_into_duplicate_routes_preserves_fit(self):
        single = diagnostic.fit_ridge([[-1], [1]], [1, 3], [1, 1])
        split = diagnostic.fit_ridge([[-1], [-1], [1]], [1, 1, 3], [.5, .5, 1])
        np.testing.assert_allclose(single["mean"], split["mean"])
        np.testing.assert_allclose(single["coefficients"], split["coefficients"])
        self.assertEqual(single["intercept"], split["intercept"])

    def test_constant_training_feature_cannot_use_heldout_scale(self):
        fitted = diagnostic.fit_ridge([[2], [2]], [1, 3], [1, 1])
        np.testing.assert_array_equal(diagnostic.predict(fitted, [[-1000], [1000]]), [2, 2])

    def test_extreme_heldout_input_is_scaled_only_by_training_statistics(self):
        fitted = diagnostic.fit_ridge([[-1], [1]], [1, 3], [1, 1])
        np.testing.assert_allclose(diagnostic.predict(fitted, [[100]]), [2 + 100 / 11])

    def test_route_mean_uses_only_weighted_training_rows(self):
        rows = [{"route": "CASH"}, {"route": "CASH"}, {"route": "EXPANSION"}, {"route": "CASH"}]
        result = diagnostic.route_means(rows, np.array([1., 3., 2., 999.]), np.array([.5, 1., .5, 1.]), [0, 1, 2])
        self.assertAlmostEqual(result["CASH"], 7 / 3)
        self.assertEqual(result["EXPANSION"], 2)

    def test_metrics_first_average_available_routes_within_world(self):
        rows = [{"index": 0}, {"index": 0}, {"index": 1}]
        values = diagnostic.origin_values(rows, [0, 2, 4])
        self.assertEqual(values, [1, 4])
        self.assertEqual(diagnostic.interval(values)["mean"], 2.5)

    def test_zero_prediction_holds_instead_of_buying(self):
        rows = [{"index": 0, "route": "CASH"}, {"index": 1, "route": "CASH"}]
        result = diagnostic.summarize(rows, np.array([.5, -.5]), np.array([0., 0.]), np.array([0., 0.]))
        self.assertEqual(result["positivePredictions"], 0)
        self.assertEqual(result["proxyIncrementalReturn"]["buyIfPredictionPositive"]["mean"], 0)


if __name__ == "__main__":
    unittest.main()
