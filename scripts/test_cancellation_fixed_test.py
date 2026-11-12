import copy
import unittest
from unittest.mock import patch

import numpy as np
import cancellation_fixed_test as t
import test_cancellation_conditional_value as fixtures


def model(intercept=0.1):
    return dict(mean=[0.] * 58, standardDeviation=[1.] * 58, coefficients=[0.] * 58,
                intercept=intercept, **{"lambda": 10},
                objective="weightedMeanMSE + lambda * sum(beta^2)")


def public(index=224):
    actor, projection, command, catalog = fixtures.fixture()
    row = dict(index=index, split="TEST", sha256="original")
    projection.update(index=index, split="TEST", status="COLLECTED", sourceSha256="original",
                      prefixSha256="prefix", actorInput=actor,
                      provenance=dict(actor="p1", incumbentCommand="pass", exactPrefixAndIncumbentVerified=True))
    return row, projection, command, catalog


class FixedPredictionTest(unittest.TestCase):
    def test_original_zero_variance_columns_stay_zero(self):
        fit = model(); fit["standardDeviation"][0] = 0; fit["coefficients"][0] = 99
        t.validate_model(fit)
        np.testing.assert_array_equal(t.fixed.ridge.predict(fit, [[100.] * 58]), [.1])

    def test_negative_scaling_is_rejected(self):
        fit = model(); fit["standardDeviation"][0] = -1
        with self.assertRaisesRegex(ValueError, "scaling"):
            t.validate_model(fit)

    def test_wrong_column_count_is_rejected(self):
        fit = model(); fit["coefficients"].pop()
        with self.assertRaisesRegex(ValueError, "58-column"):
            t.validate_model(fit)

    def test_public_prediction_never_calls_fit(self):
        row, projection, command, catalog = public()
        with patch.object(t.fixed.ridge, "fit_ridge", side_effect=AssertionError("No fit")):
            result = t.public_world(row, projection, catalog, model())
        self.assertEqual(result["selected"], command)

    def test_metadata_returns_do_not_enter_features_or_choice(self):
        row, projection, command, catalog = public()
        expected = t.public_world(row, projection, catalog, model())
        projection.update(terminalReward=1000, futureCard="gold", outcomes={"pass": 100})
        actual = t.public_world(row, projection, catalog, model())
        np.testing.assert_array_equal(actual["features"][command], expected["features"][command])
        self.assertEqual(actual["predictions"], expected["predictions"])
        self.assertEqual(actual["selected"], expected["selected"])

    def test_validation_origin_is_rejected(self):
        row, projection, _, catalog = public(223)
        with self.assertRaisesRegex(ValueError, "TEST origin"):
            t.public_world(row, projection, catalog, model())

    def test_original_source_substitution_is_rejected(self):
        row, projection, _, catalog = public(); projection["sourceSha256"] = "changed"
        with self.assertRaisesRegex(ValueError, "binding"):
            t.public_world(row, projection, catalog, model())

    def test_zero_prediction_uses_pass(self):
        row, projection, _, catalog = public()
        self.assertEqual(t.public_world(row, projection, catalog, model(0))["selected"], "pass")

    def test_missing_root_has_no_fabricated_row(self):
        row, projection, _, catalog = public(); projection = {k: projection[k] for k in
            ("index", "split", "sourceSha256")}; projection["status"] = "MISSING"
        self.assertIsNone(t.public_world(row, projection, catalog, model()))


class ReportingTest(unittest.TestCase):
    def test_origin_weight_not_action_or_tail_weight(self):
        first = fixtures.world(224, split="TEST")
        second = fixtures.world(225, ("a", "b"), "TEST"); second["selected"] = "pass"
        report, retained = t.summarize([first, second], {"SURPLUS": 0}, list(range(226, 256)))
        self.assertAlmostEqual(report["selectionEffects"]["pass"]["reward"]["mean"], .1)
        self.assertEqual(report["selectionEffects"]["pass"]["reward"]["origins"], 2)
        self.assertEqual(report["counts"]["missingOrigins"], 30)
        self.assertEqual(len(retained[1]["actions"]), 2)

    def test_both_references_remain_explicit(self):
        worlds = [fixtures.world(i, split="TEST") for i in (224, 225)]
        report, _ = t.summarize(worlds, {"SURPLUS": 0}, list(range(226, 256)))
        self.assertAlmostEqual(report["selectionEffects"]["pass"]["reward"]["mean"], .2)
        self.assertEqual(report["selectionEffects"]["incumbent"]["reward"]["mean"], 0)

    def test_label_pass_does_not_mutate_selected_action(self):
        row, projection, command, catalog = public()
        world = t.public_world(row, projection, catalog, model())
        origin = fixtures.LabelsTest().origin()
        origin.update(kind="cancellation-origin-1", status="COLLECTED", request={"index": 224},
                      actorInput=copy.deepcopy(projection["actorInput"]),
                      provenance=copy.deepcopy(projection["provenance"]),
                      prefix={"canonicalSha256": "prefix"})
        origin["arms"][1]["command"] = command
        t.attach_labels(world, origin, projection)
        self.assertEqual(world["selected"], command)
        self.assertEqual(len(world["labels"][command]), 2)

    def test_wrong_semantic_prefix_is_rejected(self):
        row, projection, _, catalog = public(); world = t.public_world(row, projection, catalog, model())
        with self.assertRaisesRegex(ValueError, "original root"):
            t.attach_labels(world, dict(kind="cancellation-origin-1", status="COLLECTED",
                                       request={"index": 225}), projection)


if __name__ == "__main__":
    unittest.main()
