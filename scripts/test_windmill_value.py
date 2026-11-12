"""UNIT deterministic public matrices and detached labels only; no retained corpus."""
from dataclasses import replace
import unittest
from unittest.mock import patch

import windmill_value as value

HASH = 'a' * 64


def outcome(number):
    return value.Outcome(number, number, number, 0)


def row(index=0, x=1., effect=.2, incumbent=value.Action.PASS):
    features = [0.] * 273;features[value.FEATURE_NAMES.index('bias')] = 1.
    features[1] = x
    split = value.Split.TRAIN if index % 256 < 192 else value.Split.VALIDATION
    return value.Origin(index, split, HASH, tuple(features), (outcome(.4),)*4,
                        (outcome(.4+effect),)*4, incumbent)


def zero_model():
    return value.Model((1.,)*273, (0.,)*273, tuple(range(273)), 2, .1)


class WindmillValueTest(unittest.TestCase):
    def test_dictionary_has_explicit_bias_without_cohort(self):
        self.assertEqual(len(value.FEATURE_NAMES), 273)
        self.assertIn('bias', value.FEATURE_NAMES)
        self.assertFalse(any('cohort' in name.lower() for name in value.FEATURE_NAMES))

    def test_test_index_is_rejected_before_poisoned_payload(self):
        with self.assertRaisesRegex(ValueError, 'TEST is forbidden'):
            value.Origin(224, value.Split.VALIDATION, HASH, object(), object(), object(), object())

    def test_validation_index_cannot_be_relabelled_training(self):
        with self.assertRaisesRegex(ValueError, 'TRAIN/VALIDATION'):
            replace(row(192), split=value.Split.TRAIN)

    def test_missing_origin_has_no_fabricated_features_or_returns(self):
        missing = value.Origin(1, value.Split.TRAIN, HASH, None, None, None, None, 'NO_ROOT')
        self.assertFalse(missing.captured)

    def test_missing_origin_rejects_zero_label_substitution(self):
        with self.assertRaisesRegex(ValueError, 'without fabricated'):
            replace(row(1), missingReason='NO_ROOT')

    def test_both_actions_require_all_four_futures(self):
        with self.assertRaisesRegex(ValueError, 'all four'):
            replace(row(), attemptReturns=(outcome(.5),)*3)

    def test_bias_column_is_exact_one(self):
        with self.assertRaisesRegex(ValueError, 'bias column1'):
            replace(row(), publicFeatures=(0.,)*273)

    def test_composite_reward_owner_is_unchanged(self):
        with self.assertRaisesRegex(ValueError, 'Original reward'):
            value.Outcome(.7, .1, .2, 0)

    def test_target_averages_all_four_paired_streams(self):
        origin = replace(row(), attemptReturns=tuple(outcome(v) for v in (.2, .4, .6, .8)))
        self.assertAlmostEqual(value.difference(origin, value.Action.ATTEMPT), .1)

    def test_fit_gives_each_captured_origin_equal_weight(self):
        origins = [row(0, x=0., effect=.2), row(1, x=0., effect=-.1)]
        model = value.fit(origins)
        self.assertAlmostEqual(model.trainingMean, .05)
        self.assertAlmostEqual(value.predict(model, origins[0].publicFeatures), .05/1.1)

    def test_training_rms_is_uncentered(self):
        model = value.fit([row(0, x=3.), row(1, x=3.)])
        self.assertEqual(model.scale[1], 3.)

    def test_zero_training_columns_remain_exact_zero(self):
        model = value.fit([row(0), row(1)])
        self.assertEqual((model.scale[2], model.coefficients[2]), (1., 0.))
        self.assertIn(2, model.zeroColumns)

    def test_original_incumbent_cannot_change_fitting(self):
        origins = [row(0, x=1.), row(1, x=2., effect=-.1)]
        changed = [replace(origin, incumbent=value.Action.ATTEMPT) for origin in origins]
        self.assertEqual(value.fit(origins), value.fit(changed))

    def test_validation_rows_cannot_enter_fitting(self):
        with self.assertRaisesRegex(ValueError, 'requested split'):
            value.fit([row(192), row(193)])

    def test_pass_wins_zero_and_negative_predictions(self):
        self.assertEqual([value.choose(v) for v in (-.1, 0., .1)],
                         [value.Action.PASS, value.Action.PASS, value.Action.ATTEMPT])

    def test_validation_predictions_all_precede_label_differences(self):
        origins = [row(192), row(193)];calls = []
        original = value.difference
        def difference(*args, **kwargs):
            self.assertEqual(calls, [192, 193])
            return original(*args, **kwargs)
        with patch.object(value, 'predict', side_effect=lambda model, features: calls.append(192+len(calls)) or 0.), \
             patch.object(value, 'difference', side_effect=difference):
            value.evaluate_validation(zero_model(), origins)

    def test_validation_reports_actual_incumbent_comparison_separately(self):
        report = value.evaluate_validation(zero_model(), [row(192, incumbent=value.Action.ATTEMPT),
                                                         row(193, incumbent=value.Action.ATTEMPT)])
        self.assertEqual(report['policyGain']['pass']['reward']['mean'], 0.)
        self.assertAlmostEqual(report['policyGain']['incumbent']['reward']['mean'], -.2)

    def test_missing_validation_origins_stay_outside_loss_denominator(self):
        missing = value.Origin(194, value.Split.VALIDATION, HASH, None, None, None, None, 'NO_ROOT')
        report = value.evaluate_validation(zero_model(), [row(192), row(193), missing])
        self.assertEqual(report['capturedOrigins'], 2)
        self.assertEqual(report['mse']['zero']['origins'], 2)
        self.assertEqual(report['missingOrigins'][0]['reason'], 'NO_ROOT')

    def test_validation_only_changes_do_not_mutate_model(self):
        model = value.fit([row(0), row(1, effect=-.1)])
        before = (model.scale, model.coefficients, model.trainingMean)
        value.evaluate_validation(model, [row(192, x=100., effect=-.3), row(193, x=9., effect=.1)])
        self.assertEqual(before, (model.scale, model.coefficients, model.trainingMean))

    def test_training_mean_baseline_is_frozen_in_model(self):
        report = value.evaluate_validation(zero_model(), [row(192), row(193)])
        self.assertAlmostEqual(report['mse']['trainingMean']['mean'], .01)

    def test_paired_interval_counts_origins_not_future_streams(self):
        result = value.interval([.1, .3])
        self.assertEqual(result['origins'], 2)
        self.assertAlmostEqual(result['standardError'], .1)


if __name__ == '__main__':
    unittest.main()
