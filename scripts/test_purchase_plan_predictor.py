"""Synthetic checkpoint/forward/ranking checks only; no retained model, states or labels."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import afterstate_value_data as data
import purchase_plan_predictor as predictor
import purchase_policy as policy
import purchase_value_benchmark as benchmark


class PlanPredictorTests(unittest.TestCase):
    def setUp(self):
        predictor.configure()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        old = sorted(['card.cash', 'purchase.cash', 'purchase.role.0.chaos',
                      'purchase.decision.purchase-advances']
                     + [f'purchase.synthetic.{index:04d}' for index in range(7237)])
        self.names = old + data.APPENDED
        self.dictionary = self.root / 'dictionary.json'
        policy.atomic_json(self.dictionary, dict(version=data.VERSION, stateFeatureNames=self.names,
            legacyStateFeatureNames=old, appendedStateFeatureNames=data.APPENDED,
            contexts=[dict(name=name, index=index, decision=data.DECISIONS[index])
                      for index, name in enumerate(data.CONTEXTS)],
            units='unchanged-existing-phase-encoder-units', missingValue=0.))
        self.model = benchmark.Critic((64,), 19, inputs=7244)
        self.checkpoint = self.root / 'critic.pt'
        torch.save(self.model.state_dict(), self.checkpoint)
        for name, value in dict(CHECKPOINT=policy.file_digest(self.checkpoint),
                                DICTIONARY=policy.file_digest(self.dictionary)).items():
            guard = patch.object(predictor, name, value)
            guard.start()
            self.addCleanup(guard.stop)
        self.descriptor = predictor.prepare_descriptor(self.checkpoint, self.dictionary, self.root / 'frozen')
        self.actor = predictor.FrozenPlanPredictor(self.descriptor)

    def leaf(self, cash=.25):
        return dict(context='OWNER_EXIT', stateFeatures={predictor.OWNER_EXIT: 1., 'purchase.cash': cash},
                    exactValue=None, exactReason=None)

    def origin(self):
        return dict(version=predictor.VERSION, originIndex=3, status='READY', plans=[
            dict(index=index, kind=kind, actions=[dict(decision='purchase-advances', command='pass')],
                 status='SUPPORTED', exclusion=None, leaf=self.leaf(), aliasOf=None)
            for index, kind in enumerate(predictor.KINDS)])

    def unsupported(self, origin, index):
        origin['plans'][index].update(status='UNSUPPORTED', exclusion='INFORMATION', leaf=None)
        if index == 0:
            origin['status'] = 'INCUMBENT_UNSUPPORTED'
        return origin

    def rank(self, values):
        with patch.object(self.actor, 'values', return_value=values):
            return predictor.rank_origin(self.actor, self.origin())

    def test_actual_fp32_forward_matches_unchanged_critic(self):
        leaves = [self.leaf(.25), self.leaf(.75)]
        inputs = torch.zeros((2, 7244), dtype=torch.float32)
        inputs[:, self.names.index(predictor.OWNER_EXIT)] = 1.
        inputs[:, self.names.index('purchase.cash')] = torch.tensor([.25, .75])
        with torch.no_grad():
            expected = [float(value) for value in self.model(inputs)]
        self.assertEqual(expected, self.actor.values(leaves))

    def test_missing_features_remain_zero_in_original_units(self):
        leaf = self.leaf()
        del leaf['stateFeatures']['purchase.cash']
        self.assertEqual(self.actor.values([self.leaf(0.)]), self.actor.values([leaf]))

    def test_loader_preserves_caller_rng(self):
        before = torch.random.get_rng_state().clone()
        predictor.FrozenPlanPredictor(self.descriptor)
        self.assertTrue(torch.equal(before, torch.random.get_rng_state()))

    def test_descriptor_binds_actual_subjects_and_numerical_owner(self):
        descriptor = policy.read_json(self.descriptor)
        contract = policy.read_json(descriptor['inferenceContractPath'])
        self.assertEqual(policy.file_digest(self.checkpoint), descriptor['checkpointSha256'])
        self.assertEqual(policy.file_digest(self.dictionary), descriptor['dictionarySha256'])
        self.assertEqual(policy.file_digest(benchmark.__file__), contract['criticOwnerSha256'])
        self.assertEqual(('cpu', 'float32', 1, 1),
                         tuple(contract[key] for key in ('device', 'dtype', 'threads', 'interopThreads')))
        self.assertFalse(any(parameter.requires_grad for parameter in self.actor.critic.parameters()))

    def test_model_tampering_is_rejected_before_scoring(self):
        self.checkpoint.write_bytes(self.checkpoint.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'input changed'):
            self.actor.values([self.leaf()])

    def test_dictionary_tampering_is_rejected(self):
        self.dictionary.write_bytes(self.dictionary.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'subject changed'):
            predictor.FrozenPlanPredictor(self.descriptor)

    def test_unknown_descriptor_fields_are_rejected(self):
        descriptor = policy.read_json(self.descriptor)
        descriptor['outcome'] = .5
        policy.atomic_json(self.descriptor, descriptor)
        with self.assertRaisesRegex(ValueError, 'complete frozen'):
            predictor.FrozenPlanPredictor(self.descriptor)

    def test_changed_inference_contract_is_rejected_even_with_rehashed_descriptor(self):
        descriptor = policy.read_json(self.descriptor)
        contract = Path(descriptor['inferenceContractPath'])
        value = policy.read_json(contract)
        value['dtype'] = 'float64'
        policy.atomic_json(contract, value)
        descriptor['inferenceContractSha256'] = policy.file_digest(contract)
        policy.atomic_json(self.descriptor, descriptor)
        with self.assertRaisesRegex(ValueError, 'numerical environment'):
            predictor.FrozenPlanPredictor(self.descriptor)

    def test_checkpoint_requires_exact_finite_fp32_tensor_shapes(self):
        state = self.model.state_dict()
        state['layers.2.bias'] = torch.tensor([float('nan')], dtype=torch.float32)
        torch.save(state, self.checkpoint)
        with patch.object(predictor, 'CHECKPOINT', policy.file_digest(self.checkpoint)):
            with self.assertRaisesRegex(ValueError, 'finite FP32'):
                predictor.load_subjects(self.checkpoint, self.dictionary)

    def test_float64_checkpoint_is_rejected(self):
        torch.save({key: value.double() for key, value in self.model.state_dict().items()}, self.checkpoint)
        with patch.object(predictor, 'CHECKPOINT', policy.file_digest(self.checkpoint)):
            with self.assertRaisesRegex(ValueError, 'finite FP32'):
                predictor.load_subjects(self.checkpoint, self.dictionary)

    def test_exact_bypasses_do_not_call_critic(self):
        chaos = self.leaf()
        chaos.update(exactValue=0., exactReason='OWN_CHAOS')
        chaos['stateFeatures']['purchase.role.0.chaos'] = 1.
        terminal = self.leaf()
        terminal.update(exactValue=.75, exactReason='GAME_TERMINAL')
        with patch.object(self.actor.critic, 'forward', side_effect=AssertionError('No model call')):
            self.assertEqual([0., .75], self.actor.values([chaos, terminal]))

    def test_own_chaos_requires_own_chaos_feature(self):
        leaf = self.leaf()
        leaf.update(exactValue=0., exactReason='OWN_CHAOS')
        with self.assertRaisesRegex(ValueError, 'authoritative own elimination'):
            self.actor.values([leaf])

    def test_exact_value_without_reason_is_rejected(self):
        leaf = self.leaf()
        leaf['exactValue'] = .5
        with self.assertRaisesRegex(ValueError, 'typed authoritative'):
            self.actor.values([leaf])

    def test_unsupported_incumbent_falls_back_without_any_model_call(self):
        origin = self.unsupported(self.origin(), 0)
        with patch.object(self.actor, 'values', side_effect=AssertionError('No model call')):
            result = predictor.rank_origin(self.actor, origin)
        self.assertEqual((0, 'INCUMBENT_UNSUPPORTED'), (result['selectedIndex'], result['fallbackReason']))
        self.assertEqual([None] * 8, [plan['value'] for plan in result['plans']])

    def test_missing_origin_retains_explicit_normal_fallback(self):
        origin = dict(version=predictor.VERSION, originIndex=255, status='MISSING', plans=[])
        result = predictor.rank_origin(self.actor, origin)
        self.assertIsNone(result['selectedIndex'])
        self.assertEqual('MISSING', result['fallbackReason'])
        self.assertEqual([], result['plans'])

    def test_incumbent_wins_exact_value_ties(self):
        self.assertEqual(0, self.rank([.5] * 8)['selectedIndex'])

    def test_strictly_better_value_is_selected(self):
        self.assertEqual(4, self.rank([.5, .3, .4, .2, .50000001, .1, .2, .3])['selectedIndex'])

    def test_equal_improving_alternatives_keep_original_plan_order(self):
        self.assertEqual(2, self.rank([.5, .4, .6, .2, .6, .1, .6, .3])['selectedIndex'])

    def test_alias_is_retained_and_does_not_gain_an_extra_vote(self):
        origin = self.origin()
        origin['plans'][7]['aliasOf'] = 0
        with patch.object(self.actor, 'values', return_value=[.5] * 8):
            result = predictor.rank_origin(self.actor, origin)
        self.assertEqual(0, result['selectedIndex'])
        self.assertEqual(0, result['plans'][7]['aliasOf'])
        self.assertEqual(8, len(result['plans']))

    def test_false_alias_is_rejected(self):
        origin = self.origin()
        origin['plans'][7].update(aliasOf=0, leaf=self.leaf(.8))
        with self.assertRaisesRegex(ValueError, 'same commands'):
            predictor.rank_origin(self.actor, origin)

    def test_alias_chain_is_rejected_like_the_native_canonical_execution_owner(self):
        origin = self.origin()
        origin['plans'][1]['aliasOf'] = 0
        origin['plans'][7]['aliasOf'] = 1
        with self.assertRaisesRegex(ValueError, 'same commands'):
            predictor.rank_origin(self.actor, origin)

    def test_actual_duplicate_inputs_have_exactly_equal_logical_values(self):
        origin = self.origin()
        origin['plans'][7]['aliasOf'] = 0
        result = predictor.rank_origin(self.actor, origin)
        self.assertEqual(result['plans'][0]['value'], result['plans'][7]['value'])

    def test_unsupported_alternative_is_retained_without_value(self):
        origin = self.unsupported(self.origin(), 4)
        with patch.object(self.actor, 'values', return_value=[.5] * 7):
            result = predictor.rank_origin(self.actor, origin)
        self.assertEqual('INFORMATION', result['plans'][4]['exclusion'])
        self.assertIsNone(result['plans'][4]['value'])

    def test_unknown_origin_outcome_is_rejected(self):
        origin = self.origin()
        origin['reward'] = 1.
        with self.assertRaisesRegex(ValueError, 'excludes seeds'):
            predictor.rank_origin(self.actor, origin)

    def test_future_feature_is_rejected(self):
        leaf = self.leaf()
        leaf['stateFeatures']['purchase.future.reward'] = 1.
        with self.assertRaisesRegex(ValueError, 'actor-safe OWNER_EXIT'):
            self.actor.values([leaf])

    def test_card_namespace_is_rejected_even_if_in_dictionary(self):
        leaf = self.leaf()
        leaf['stateFeatures']['card.cash'] = 1.
        with self.assertRaisesRegex(ValueError, 'actor-safe OWNER_EXIT'):
            self.actor.values([leaf])

    def test_wrong_decision_flag_is_rejected(self):
        leaf = self.leaf()
        leaf['stateFeatures']['purchase.decision.purchase-advances'] = 1.
        with self.assertRaisesRegex(ValueError, 'actor-safe OWNER_EXIT'):
            self.actor.values([leaf])

    def test_nonfinite_feature_is_rejected(self):
        leaf = self.leaf(float('nan'))
        with self.assertRaisesRegex(ValueError, 'actor-safe OWNER_EXIT'):
            self.actor.values([leaf])

    def test_boolean_feature_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'actor-safe OWNER_EXIT'):
            self.actor.values([self.leaf(True)])

    def test_wrong_proposal_order_is_rejected(self):
        origin = self.origin()
        origin['plans'][2]['kind'] = 'UNIFORM'
        with self.assertRaisesRegex(ValueError, 'proposal kind'):
            predictor.rank_origin(self.actor, origin)

    def test_actions_are_not_critic_inputs(self):
        origin = self.origin()
        changed = copy.deepcopy(origin)
        changed['plans'][2]['actions'][0]['command'] = 'synthetic-different-command'
        before = predictor.rank_origin(self.actor, origin)
        after = predictor.rank_origin(self.actor, changed)
        self.assertEqual(before, after)

    def test_model_rejects_nonfinite_prediction(self):
        with patch.object(self.actor.critic, 'forward', return_value=torch.tensor([float('nan')])):
            with self.assertRaisesRegex(ValueError, 'finite terminal reward'):
                self.actor.values([self.leaf()])


if __name__ == '__main__':
    unittest.main()
