"""UNIT-only paired-label/support/offline-gradient checks; no real corpus or model files."""
import hashlib
import inspect
import unittest

import torch

import purchase_policy as policy
import purchase_supported_fit as supported
from purchase_policy_toy import SCHEMA


def fixture_model():
    model = policy.initialize(SCHEMA, 17)
    model['actorWeights'] = [.03 * (i % 7 - 3) for i in range(64)]
    model['valueWeights'][0] = .3
    model['valueBias'] = -.2
    return model


def fixture_record(index=0, support=(0, 3)):
    returns = {0: [.9, .5], 1: [.2, .3], 2: [.5, .5], 3: [.1, .3]}
    return dict(originIndex=index, split='TRAIN' if index < 384 else 'VALIDATION',
                observation=dict(encoderVersion=policy.ENCODER_VERSION,
                    stateFeatures={'root': 1.}, baselineIndex=3,
                    actions=[dict(command='buy A', features={'left': 1.}),
                             dict(command='buy B', features={'right': 1.}),
                             dict(command='buy C', features={'cost': 1.}),
                             dict(command='pass', features={})]),
                passIndex=3, supportedIndices=list(support), firstOnlyReturns=[
                    dict(actionIndex=i, returns=returns[i]) for i in support])


class SupportedObjectiveTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.model = fixture_model()
        raw = policy.canonical(self.model)
        self.reference = supported.FrozenReference(raw, hashlib.sha256(raw).hexdigest())
        self.row = supported.SupportedOrigin.from_record(fixture_record(), self.model)

    def actor(self):
        return supported.SupportedActor(self.reference)

    def changed_actor(self):
        actor = self.actor()
        with torch.no_grad():
            actor.actor.actorWeights.add_(.4)
        return actor

    def test_wrapper_preserves_unsupported_probabilities_bit_exactly(self):
        old = torch.tensor([.1, .2, .3, .4], dtype=policy.DTYPE).log()
        candidate = torch.log_softmax(torch.tensor([4., 2., 1., -2.], dtype=policy.DTYPE), 0)
        actual = supported.effective_probabilities(old, candidate, (0, 3))
        self.assertTrue(torch.equal(actual[[1, 2]], old.exp()[[1, 2]]))

    def test_wrapper_preserves_supported_and_total_mass(self):
        old = torch.tensor([.1, .2, .3, .4], dtype=policy.DTYPE).log()
        candidate = torch.log_softmax(torch.tensor([4., 2., 1., -2.], dtype=policy.DTYPE), 0)
        actual = supported.effective_probabilities(old, candidate, (0, 3))
        self.assertAlmostEqual(float(actual[[0, 3]].sum()), .5, places=15)
        self.assertAlmostEqual(float(actual.sum()), 1., places=15)

    def test_wrapper_uses_conditioned_full_candidate_distribution(self):
        original = torch.tensor([.1, .2, .3, .4], dtype=policy.DTYPE)
        candidate = torch.tensor([.25, .45, .25, .05], dtype=policy.DTYPE)
        actual = supported.effective_probabilities(original.log(), candidate.log(), (0, 3))
        expected = original.clone()
        expected[[0, 3]] = .5 * original[[0, 3]] + .5 * .5 * candidate[[0, 3]] / .3
        torch.testing.assert_close(actual, expected, rtol=0, atol=1e-16)

    def test_wrapper_total_variation_is_bounded_by_half_support_mass(self):
        old = torch.tensor([.01, .01, .01, .97], dtype=policy.DTYPE).log()
        candidate = torch.log_softmax(torch.tensor([100., 0., 0., -100.], dtype=policy.DTYPE), 0)
        actual = supported.effective_probabilities(old, candidate, (0, 3))
        variation = float((actual - old.exp()).abs().sum()) / 2
        self.assertGreater(variation, .48)
        self.assertLessEqual(variation, supported.ALPHA * float(old.exp()[[0, 3]].sum()))

    def test_identical_g2_probabilities_are_exact_with_nonzero_initial_gradient(self):
        actor = self.actor()
        batch = actor.prepare((self.row,), supported.Split.TRAIN)[0]
        candidate, _ = actor.actor(batch.observations)
        actual = supported.effective_probabilities(batch.reference_logp[0], candidate[0], (0, 3))
        self.assertTrue(torch.equal(actual, batch.reference_logp[0].exp()))
        objective, _, _, _ = actor.batch_terms(batch)
        (-objective.sum()).backward()
        self.assertGreater(float(actor.actor.actorWeights.grad.abs().sum()), 0.)

    def test_original_g2_has_exact_zero_gain_and_kl(self):
        actor = self.actor()
        result = actor.evaluate(actor.prepare((self.row,), supported.Split.TRAIN))
        self.assertEqual(result, dict(origins=1, objective=0., gain=0., meanKl=0.,
                                      maximumProbabilityChange=0.))

    def test_paired_advantage_subtracts_pass_on_each_stream(self):
        self.assertEqual(self.row.advantages(), (.5, 0.))

    def test_all_supported_controls_contribute_without_winner_selection(self):
        record = fixture_record(support=(0, 1, 3))
        row = supported.SupportedOrigin.from_record(record, self.model)
        self.assertAlmostEqual(row.advantages()[1], .05)
        self.assertEqual(len(row.advantages()), 3)

    def test_full_prefix_labels_are_not_an_accepted_input(self):
        record = fixture_record()
        record['fullPrefixReturns'] = [1., 1.]
        with self.assertRaisesRegex(ValueError, 'first-action-only labels'):
            supported.SupportedOrigin.from_record(record, self.model)

    def test_two_streams_are_required_for_every_support_action(self):
        record = fixture_record()
        record['firstOnlyReturns'][0]['returns'].append(.4)
        with self.assertRaisesRegex(ValueError, 'Exactly two'):
            supported.SupportedOrigin.from_record(record, self.model)

    def test_unpaired_or_missing_control_is_rejected(self):
        record = fixture_record()
        record['firstOnlyReturns'].pop()
        with self.assertRaisesRegex(ValueError, 'exact supported action indices'):
            supported.SupportedOrigin.from_record(record, self.model)

    def test_nonfinite_or_unnormalized_labels_are_rejected(self):
        for bad in (float('nan'), 1.01, -.01):
            with self.subTest(value=bad):
                record = fixture_record()
                record['firstOnlyReturns'][0]['returns'][0] = bad
                with self.assertRaisesRegex(ValueError, 'finite number'):
                    supported.SupportedOrigin.from_record(record, self.model)

    def test_original_full_observation_and_f0_baseline_are_preserved(self):
        record = fixture_record()
        record['observation']['baselineIndex'] = 1
        row = supported.SupportedOrigin.from_record(record, self.model)
        self.assertEqual(row.observation(), record['observation'])
        self.assertEqual(len(row.observation()['actions']), 4)
        self.assertEqual(row.observation()['baselineIndex'], 1)

    def test_caller_mutation_cannot_change_features_or_labels(self):
        record = fixture_record()
        row = supported.SupportedOrigin.from_record(record, self.model)
        before = row.record()
        record['observation']['stateFeatures']['root'] = -1.
        record['firstOnlyReturns'][0]['returns'][0] = 0.
        self.assertEqual(row.record(), before)

    def test_unknown_feature_is_rejected_by_existing_encoder_contract(self):
        record = fixture_record()
        record['observation']['stateFeatures']['hidden-drawn-card'] = 0.
        with self.assertRaisesRegex(ValueError, 'unknown feature'):
            supported.SupportedOrigin.from_record(record, self.model)

    def test_duplicate_origin_is_not_extra_training_weight(self):
        with self.assertRaisesRegex(ValueError, 'exactly once'):
            self.actor().prepare((self.row, self.row), supported.Split.TRAIN)

    def test_root_weight_is_independent_of_support_size(self):
        larger = supported.SupportedOrigin.from_record(fixture_record(1, (0, 1, 3)), self.model)
        actor = self.changed_actor()
        together = actor.evaluate(actor.prepare((self.row, larger), supported.Split.TRAIN))
        first = actor.evaluate(actor.prepare((self.row,), supported.Split.TRAIN))
        second = actor.evaluate(actor.prepare((larger,), supported.Split.TRAIN))
        self.assertAlmostEqual(together['objective'], (first['objective'] + second['objective']) / 2,
                               places=15)

    def test_full_train_gradient_has_one_step_regardless_of_numeric_batching(self):
        second = supported.SupportedOrigin.from_record(fixture_record(1, (0, 1, 3)), self.model)
        together, separated = self.actor(), self.actor()
        together.step(together.prepare((self.row, second), supported.Split.TRAIN))
        chunks = (separated.prepare((self.row,), supported.Split.TRAIN)
                  + separated.prepare((second,), supported.Split.TRAIN))
        separated.step(chunks)
        torch.testing.assert_close(together.actor.actorWeights, separated.actor.actorWeights,
                                   rtol=0, atol=1e-15)

    def test_only_64_actor_weights_and_one_fresh_adam_slot_can_change(self):
        actor = self.actor()
        self.assertEqual(actor.optimizer.state, {})
        self.assertEqual([name for name, parameter in actor.actor.named_parameters()
                          if parameter.requires_grad], ['actorWeights'])
        batches = actor.prepare((self.row,), supported.Split.TRAIN)
        for _ in range(3):
            actor.step(batches)
        changed = actor.artifact()
        self.assertNotEqual(changed['actorWeights'], self.model['actorWeights'])
        self.assertEqual({k: v for k, v in changed.items() if k != 'actorWeights'},
                         {k: v for k, v in self.model.items() if k != 'actorWeights'})
        self.assertEqual(len(actor.optimizer.state), 1)
        self.assertEqual(float(next(iter(actor.optimizer.state.values()))['step']), 3.)

    def test_value_predictions_remain_exact_after_actor_update(self):
        actor = self.actor()
        batches = actor.prepare((self.row,), supported.Split.TRAIN)
        before = actor.actor(batches[0].observations)[1].detach().clone()
        actor.step(batches)
        after = actor.actor(batches[0].observations)[1].detach()
        self.assertTrue(torch.equal(before, after))

    def test_effective_objective_reports_gain_minus_fixed_forward_kl(self):
        actor = self.changed_actor()
        metrics = actor.evaluate(actor.prepare((self.row,), supported.Split.TRAIN))
        self.assertGreater(metrics['meanKl'], 0.)
        self.assertGreater(metrics['maximumProbabilityChange'], 0.)
        self.assertAlmostEqual(metrics['objective'], metrics['gain'] - .01 * metrics['meanKl'],
                               places=15)

    def test_validation_cannot_update_the_actor(self):
        row = supported.SupportedOrigin.from_record(fixture_record(384), self.model)
        actor = self.actor()
        with self.assertRaisesRegex(ValueError, 'only eligible TRAIN'):
            actor.step(actor.prepare((row,), supported.Split.VALIDATION))

    def test_test_labels_are_rejected_before_access(self):
        class UnreadTest(dict):
            def __getitem__(self, key):
                if key in ('firstOnlyReturns', 'observation'):
                    raise AssertionError('TEST data was accessed')
                return super().__getitem__(key)
        for secret in ([0., 0.], [1., 1.]):
            record = UnreadTest(split='TEST', firstOnlyReturns=secret, observation=None)
            with self.assertRaisesRegex(ValueError, 'TEST labels must not'):
                supported.SupportedOrigin.from_record(record, self.model)
        self.assertNotIn('test', inspect.signature(supported.fit_supported).parameters)

    def test_split_cannot_be_reassigned_to_admit_test_origin(self):
        record = fixture_record(448)
        with self.assertRaisesRegex(ValueError, 'frozen whole-origin'):
            supported.SupportedOrigin.from_record(record, self.model)

    def test_validation_ties_keep_original_g2_and_later_earliest_winner(self):
        selection = supported.ValidationSelection(0.)
        self.assertFalse(selection.consider(10, {'objective': 0.}))
        self.assertEqual(selection.update, 0)
        self.assertTrue(selection.consider(20, {'objective': .2}))
        self.assertFalse(selection.consider(30, {'objective': .2}))
        self.assertEqual(selection.update, 20)

    def test_validation_rejects_an_extra_look(self):
        selection = supported.ValidationSelection(0.)
        with self.assertRaisesRegex(ValueError, 'exactly ten'):
            selection.consider(11, {'objective': .1})

    def test_actual_fit_requires_exact_g2_not_a_synthetic_initialization(self):
        with self.assertRaisesRegex(ValueError, 'exact G2 initialization'):
            supported.FitAdmission(self.reference.sha256, supported.SPLIT_SHA256,
                                   '0' * 64, '1' * 64, 160, 24, 24)

    def test_every_split_has_its_prospective_minimum_eligibility(self):
        for counts in ((159, 24, 24), (160, 23, 24), (160, 24, 23)):
            with self.subTest(counts=counts), self.assertRaisesRegex(ValueError, 'eligibility'):
                supported.FitAdmission(supported.G2_SHA256, supported.SPLIT_SHA256,
                                       '0' * 64, '1' * 64, *counts)

    def test_eligibility_uses_test_count_without_loading_test_labels(self):
        admission = supported.FitAdmission(supported.G2_SHA256, supported.SPLIT_SHA256,
                                           '0' * 64, '1' * 64, 160, 24, 24)
        self.assertEqual(admission.eligible_test, 24)
        self.assertNotIn('test_rows', admission.__dict__)

    def test_reference_artifact_is_rechecked_at_actual_fit_admission(self):
        admission = supported.FitAdmission(supported.G2_SHA256, supported.SPLIT_SHA256,
                                           '0' * 64, '1' * 64, 160, 24, 24)
        with self.assertRaisesRegex(ValueError, 'original G2 artifact bytes'):
            admission.validate(self.reference, (), ())

    def test_artifact_bytes_are_pinned_before_decoding(self):
        with self.assertRaisesRegex(ValueError, 'artifact SHA-256'):
            supported.FrozenReference(b'{}', '0' * 64)

    def test_existing_ppo_configuration_defaults_are_unchanged(self):
        config = policy.UpdateConfig()
        self.assertGreater(config.maxBehaviorKl, 0.)
        self.assertGreater(config.valueCoefficient, 0.)
        self.assertNotIn('support', inspect.signature(policy.update).parameters)


if __name__ == '__main__':
    unittest.main()
