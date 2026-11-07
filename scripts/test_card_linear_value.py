import copy
import gzip
import math
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import numpy as np
import torch

import card_advantage as advantage
import card_linear_value as linear
import purchase_policy as common
from test_card_advantage import SCHEMA, group, observation
from test_card_advantage_sparse import root_pair_fixture, sampled_group


def finish(directory):
    manifest = common.read_json(directory/'collection.json')
    common.atomic_json(directory/'finished.json', {'kind': 'card-advantage-collect',
        'signature': manifest['signature'], 'origins': 3,
        'originSha256': [common.file_digest(directory/f'origin-{index}.json.gz') for index in range(3)]})


def fixture(directory):
    directory.mkdir()
    root_pair_fixture(directory)
    manifest = common.read_json(directory/'collection.json')
    common.atomic_json(directory/'source.json', {'schemaVersion': 2, 'origins': manifest['origins'],
                                               'collection': manifest['collection']})
    manifest['sourceSha256'] = common.file_digest(directory/'source.json')
    common.atomic_json(directory/'collection.json', manifest)
    # Deliberately valid compressed bytes but invalid JSON: even a "coverage-only" decode fails.
    (directory/'origin-2.json.gz').write_bytes(gzip.compress(b'validation must never be decoded', mtime=0))
    finish(directory)
    protocol = {'kind': 'card-linear-value-diagnostic', 'schemaVersion': 1,
        'source': str(directory.resolve()), 'config': linear.CONFIG,
        'featureContract': linear.feature_contract(manifest),
        'expectedCounts': {'train': 1, 'development': 1}, 'pins': linear.source_hashes()}
    path = directory.parent/'protocol.json'; common.atomic_json(path, protocol)
    return path


def simple_model(schema=SCHEMA, representation='action-only'):
    n = len(linear.feature_contract(schema)['featureNames'][representation])
    return linear.artifact(schema, representation, np.ones(n), np.zeros(n), .1)


class FeatureChecks(unittest.TestCase):
    def test_exact_explicit_extra_order_and_count(self):
        contract = linear.feature_contract(SCHEMA)
        self.assertEqual(len(contract['featureNames']['contextual'])-len(SCHEMA['actionFeatureNames']), 103)
        self.assertEqual(contract['featureNames']['contextual'][-7:], list(linear.EXTRAS))
        self.assertEqual(contract['featureNames']['action-only'], ['raw.'+name for name in SCHEMA['actionFeatureNames']])

    def test_quoted_cash_and_nominal_credit_remain_distinct(self):
        schema = {'encoderVersion': advantage.ENCODER,
            'stateFeatureNames': sorted(['cardWindow.normal', 'role.0.advance.PRINTED_WORD',
                'role.0.advance.WRITTEN_RECORD', 'role.0.advance.INTEREST_PROFIT',
                'offer.INTEREST_PROFIT.available', 'handBurden.dionysus-exiguus']),
            'actionFeatureNames': sorted(['card.dionysus-exiguus', 'cardKind.LEADER',
                'credit.INTEREST_PROFIT.effective', 'effect.role.0.cashDelta', 'protection'])}
        features = linear.Features(schema, 'contextual')
        state = {'cardWindow.normal': 1., 'role.0.advance.PRINTED_WORD': 1.,
                 'role.0.advance.WRITTEN_RECORD': 1., 'role.0.advance.INTEREST_PROFIT': 1.,
                 'offer.INTEREST_PROFIT.available': 0., 'handBurden.dionysus-exiguus': 1.}
        action = {'card.dionysus-exiguus': 1., 'cardKind.LEADER': 1.,
                  'credit.INTEREST_PROFIT.effective': .3, 'effect.role.0.cashDelta': .05, 'protection': .05}
        values = dict(zip(features.names, features.vector(state, action)))
        self.assertEqual(values['raw.effect.role.0.cashDelta'], .05)
        self.assertEqual(values['cross.role.0.advance.PRINTED_WORD.ownCashDelta'], .05)
        self.assertEqual(values['cross.role.0.advance.PRINTED_WORD.effectiveCredit'], .3)
        self.assertEqual(values['credit.unownedEffective'], 0.)
        self.assertEqual(values['credit.currentlyAvailableEffective'], 0.)
        self.assertEqual(values['selectedCard.handBurdenCount'], 1.)

    def test_target_role_join_uses_selected_opponent_not_actor_or_turn_order(self):
        schema = {'encoderVersion': advantage.ENCODER,
            'stateFeatureNames': ['cardWindow.normal', 'role.0.cash', 'role.1.cash', 'role.2.cash'],
            'actionFeatureNames': ['target.role.1', 'target.role.2']}
        features = linear.Features(schema, 'contextual')
        state = {'role.0.cash': 9., 'role.1.cash': .2, 'role.2.cash': .7}
        first = dict(zip(features.names, features.vector(state, {'target.role.1': 1.})))
        second = dict(zip(features.names, features.vector(state, {'target.role.2': 1.})))
        self.assertEqual(first['targetOpponent.cash'], .2)
        self.assertEqual(second['targetOpponent.cash'], .7)

    def test_state_cross_can_reverse_the_same_action_preference(self):
        schema = {'encoderVersion': advantage.ENCODER,
                  'stateFeatureNames': ['cardWindow.normal', 'role.0.cash'],
                  'actionFeatureNames': ['cardKind.EVENT', 'kind.pass']}
        features = linear.Features(schema, 'contextual')
        state = {'cardWindow.normal': 1., 'role.0.cash': 2.}
        raw = dict(zip(features.names, features.vector(state, {'cardKind.EVENT': 1.})))
        self.assertEqual(raw['cross.role.0.cash.event'], 2.)
        self.assertEqual(raw['cross.role.0.cash.pass'], 0.)

    def test_convex_contextual_fit_learns_opposite_visible_context_preferences(self):
        schema = {'encoderVersion': advantage.ENCODER,
                  'stateFeatureNames': ['cardWindow.normal', 'role.0.cash'],
                  'actionFeatureNames': ['cardKind.EVENT', 'kind.pass']}
        origins = []
        for index, cash in enumerate([0., 1.]*4):
            obs = {'encoderVersion': advantage.ENCODER, 'baselineIndex': 0,
                'stateFeatures': {'cardWindow.normal': 1., 'role.0.cash': cash},
                'actions': [{'command': 'pass', 'features': {'kind.pass': 1.}},
                            {'command': 'play hand-1', 'features': {'cardKind.EVENT': 1.}}]}
            origins.append([advantage.prepare(obs, 0, schema, [0., cash-.5], f'{index}:root')])
        features = linear.Features(schema, 'contextual'); rows = linear.training_rows(origins, features)
        scales = linear.rms_scales(rows)
        model = linear.LinearModel(linear.artifact(schema, 'contextual', scales, linear.ridge(rows, scales, .01), .01))
        self.assertLess(model.scores(origins[0][0])[1], -.4)
        self.assertGreater(model.scores(origins[1][0])[1], .4)
        control = linear.training_rows(origins, linear.Features(schema, 'action-only'))
        np.testing.assert_array_equal(linear.ridge(control, linear.rms_scales(control), .01), [0., 0.])

    def test_hidden_unknown_feature_rejected_even_when_zero(self):
        obs = observation(); obs['stateFeatures']['opponentPrivateHand'] = 0.
        with self.assertRaisesRegex(ValueError, 'unknown feature'):
            linear.Features(SCHEMA, 'contextual').differences(obs, [0, 1])

    def test_labels_and_provenance_cannot_change_features(self):
        first = observation(); second = copy.deepcopy(first)
        second.update(terminal={'reward': 1.}, gameSeed=-1, hiddenWorld={'deck': ['secret']})
        features = linear.Features(SCHEMA, 'contextual')
        np.testing.assert_array_equal(features.differences(first, [0, 1]), features.differences(second, [0, 1]))


class NumericalChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls): torch.set_num_threads(1)

    def test_rms_is_uncentered_and_origin_weighted(self):
        rows = linear.Rows(np.array([[1., 0.], [3., 0.]]), np.array([2., 6.]), np.array([.75, .25]), 2)
        np.testing.assert_allclose(linear.rms_scales(rows), [math.sqrt(3.), 0.])

    def test_ridge_matches_closed_form_mean_mse_penalty(self):
        rows = linear.Rows(np.array([[1.], [3.]]), np.array([2., 6.]), np.array([.5, .5]), 2)
        scales = linear.rms_scales(rows)
        result = linear.ridge(rows, scales, .1)
        self.assertAlmostEqual(result[0], 2*math.sqrt(5)/1.1)
        linear.verify_solution(rows, scales, result, .1)

    def test_jointly_changed_checkpoint_predictions_cannot_hide_wrong_solution(self):
        rows = linear.Rows(np.array([[1.]]), np.array([.5]), np.array([1.]), 1)
        with self.assertRaisesRegex(ValueError, 'no longer solves'):
            linear.verify_solution(rows, np.array([1.]), np.array([.9]), .1)

    def test_training_zero_columns_disabled_on_nonzero_later_features(self):
        rows = linear.Rows(np.array([[0., 2.], [0., 2.]]), np.array([.1, .2]), np.array([.5, .5]), 2)
        scales = linear.rms_scales(rows); coefficients = linear.ridge(rows, scales, .1)
        self.assertEqual(coefficients[0], 0.)
        np.testing.assert_array_equal(linear.normalized(np.array([[16., 2.]]), scales), [[0., 1.]])

    def test_masked_training_uses_only_observed_non_f_target(self):
        g = sampled_group()
        rows = linear.training_rows([[g]], linear.Features(SCHEMA, 'action-only'))
        self.assertEqual(rows.y.tolist(), [.25]); self.assertEqual(rows.weights.tolist(), [1.])
        self.assertEqual(len(g.indices), 4)

    def test_group_and_pair_counts_do_not_multiply_origin_weight(self):
        first = [group(.2, identifier='0:a'), group(.3, identifier='0:b')]
        second = [group(.4, identifier='1:a')]
        rows = linear.training_rows([first, second], linear.Features(SCHEMA, 'action-only'))
        np.testing.assert_array_equal(rows.weights, [.25, .25, .5])

    def test_all_trivial_training_rejects_without_zero_target_fitting(self):
        obs = observation(); obs['actions'] = obs['actions'][:1]
        trivial = advantage.prepare(obs, 0, SCHEMA, [0.], '0:g')
        with self.assertRaisesRegex(ValueError, 'observed non-F'):
            linear.training_rows([[trivial]], linear.Features(SCHEMA, 'action-only'))

    def test_dense_metrics_reject_missing_targets(self):
        with self.assertRaisesRegex(ValueError, 'Dense evaluation'):
            linear.metrics(linear.LinearModel(simple_model()), [[sampled_group()]], dense=True)

    def test_dense_selection_keeps_trivial_worlds_as_zero(self):
        model = simple_model(); model['coefficients'][model['featureNames'].index('raw.card.b')] = .1
        obs = observation(); obs['actions'] = obs['actions'][:1]
        trivial = advantage.prepare(obs, 0, SCHEMA, [0.], '1:g')
        result = linear.metrics(linear.LinearModel(model), [[group(.4)], [trivial]], dense=True)
        self.assertEqual(result['origins'], 2); self.assertEqual(result['nontrivialOrigins'], 1)
        self.assertAlmostEqual(result['meanSelectedAdvantage'], .2)

    def test_f_zero_strict_margin_and_first_maximum(self):
        self.assertEqual(linear.select(np.array([.02, 0.]), 1), 1)
        self.assertEqual(linear.select(np.array([.03, .03, 0.]), 2), 0)
        model = simple_model(); model['coefficients'] = [4.]*len(model['coefficients'])
        self.assertEqual(linear.LinearModel(model).scores(group())[0], 0.)

    def test_unobserved_labels_do_not_remove_actions_from_inference(self):
        model = simple_model(); model['coefficients'][model['featureNames'].index('raw.card.b')] = 1.
        g = sampled_group(); selected = linear.LinearModel(model).choose(g.observation, g.seed)
        self.assertEqual(len(selected['scores']), 4)

    def test_model_rejects_old_network_or_altered_feature_formula(self):
        model = simple_model(); model['selectorVersion'] = advantage.SELECTOR
        with self.assertRaisesRegex(ValueError, 'Unsupported'): linear.validate_model(model)
        model = simple_model(); model['featureNames'].reverse()
        with self.assertRaisesRegex(ValueError, 'names/order'): linear.validate_model(model)


class BoundaryChecks(unittest.TestCase):
    def test_all_file_hashes_checked_before_any_origin_decode(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)/'data'; fixture(directory)
            (directory/'origin-0.json.gz').write_bytes(b'invalid')
            finish(directory)
            (directory/'origin-2.json.gz').write_bytes(b'changed completed validation bytes')
            with patch.object(gzip, 'open', side_effect=AssertionError('must not decode')):
                with self.assertRaisesRegex(ValueError, 'completed origin changed'):
                    linear.bind_collection(directory)

    def test_full_synthetic_fit_never_opens_validation_and_replays_completed_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); protocol = fixture(root/'data'); output = root/'fit'
            original = gzip.open
            def safe_open(path, *args, **kwargs):
                if Path(path).name == 'origin-2.json.gz': raise AssertionError('forbidden validation decode')
                return original(path, *args, **kwargs)
            with patch.object(gzip, 'open', side_effect=safe_open):
                result = linear.run(protocol, output)
            self.assertFalse(result['validationDecoded']); self.assertEqual(result['counts']['validation'], 0)
            self.assertEqual(len(list(output.glob('*/lambda-*.json'))), 6)
            identity = common.read_json(output/'identity.json')
            with patch.object(linear.data, 'read_collection', side_effect=AssertionError('finished resume needs no decode')):
                self.assertEqual(linear.run(protocol, output, resume=True), result)
            self.assertEqual(common.read_json(output/'identity.json'), identity)

    def test_interrupted_fit_resumes_same_coefficients_and_original_clock(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); protocol = fixture(root/'data'); output = root/'fit'
            real = linear.ridge; calls = []
            def interrupted(*args):
                calls.append(1)
                if len(calls) == 2: raise RuntimeError('synthetic interruption')
                return real(*args)
            with patch.object(linear, 'ridge', side_effect=interrupted):
                with self.assertRaisesRegex(RuntimeError, 'synthetic'): linear.run(protocol, output)
            identity = common.read_json(output/'identity.json')
            linear.run(protocol, output, resume=True)
            self.assertEqual(common.read_json(output/'identity.json'), identity)
            reference = root/'reference'; linear.run(protocol, reference)
            for name in linear.REPRESENTATIONS:
                self.assertEqual(common.read_json(output/(name+'.json')), common.read_json(reference/(name+'.json')))

    def test_expired_interrupted_clock_is_never_renewed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); protocol = fixture(root/'data'); output = root/'fit'
            with patch.object(linear, 'ridge', side_effect=RuntimeError('interrupt')):
                with self.assertRaises(RuntimeError): linear.run(protocol, output)
            identity = common.read_json(output/'identity.json')
            with patch.object(linear.time, 'time', return_value=identity['deadlineEpochSeconds']+1):
                with self.assertRaisesRegex(ValueError, 'deadline elapsed'):
                    linear.run(protocol, output, resume=True)
            self.assertEqual(common.read_json(output/'identity.json'), identity)

    def test_report_published_before_crash_can_complete_without_refitting(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); protocol = fixture(root/'data'); output = root/'fit'
            with patch.object(linear, 'finish_report', side_effect=RuntimeError('publication interruption')):
                with self.assertRaises(RuntimeError): linear.run(protocol, output)
            self.assertTrue((output/'report.json').exists()); self.assertFalse((output/'finished.json').exists())
            with patch.object(linear.data, 'read_collection', side_effect=AssertionError('must not refit')):
                result = linear.run(protocol, output, resume=True)
            self.assertFalse(result['policyPromotion']); self.assertTrue((output/'finished.json').exists())

    def test_changed_completed_model_is_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); protocol = fixture(root/'data'); output = root/'fit'
            linear.run(protocol, output)
            path = output/'contextual.json'; model = common.read_json(path); model['coefficients'][0] += .1
            common.atomic_json(path, model)
            with self.assertRaisesRegex(ValueError, 'artifacts changed'):
                linear.run(protocol, output, resume=True)
            self.assertEqual(common.read_json(path), model)

    def test_protocol_must_pin_actual_numerical_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); protocol = fixture(root/'data'); value = common.read_json(protocol)
            del value['pins'][str(Path(linear.__file__).resolve())]; common.atomic_json(protocol, value)
            with self.assertRaisesRegex(ValueError, 'every imported'):
                linear.run(protocol, root/'fit')


if __name__ == '__main__': unittest.main()
