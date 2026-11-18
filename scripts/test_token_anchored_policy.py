"""Invented models/vectors only; no game, retained model, scientific seed or corpus is opened."""
import copy
import hashlib
import math
import itertools
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import purchase_policy as b
import paired_policy_math as arithmetic
import token_anchor_distribution as distribution
import token_anchored_data as data
import token_anchored_policy as policy
import token_anchored_fit as fit
from token_anchored_recipe import PROXIMAL_RECIPE
import token_paired_data as legacy
import token_paired_fit as old_fit
import token_paired_objective as objective
from test_token_paired_fit import model, view, frozen


def fixture(count=16):
    original = frozen(policy.initialize(model(True)))
    dictionary = b.canonical({key: original.model[key] for key in
        ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')})
    context = data.Context(1, count, 4, '1'*64, '2'*64, '3'*64,
        hashlib.sha256(dictionary).hexdigest(), original.dictionary_sha256,
        original.sha256, original.sha256, '4'*64, b.digest(distribution.RECORD))
    roots = []; targets = []; anchors = []
    for index in range(count):
        offered = view(); p94 = [0., 1., 0.]; p0 = distribution.prior(p94)
        record = dict(index=index, split='TRAIN', status='READY',
            requestSha256=hashlib.sha256(f'INVENTED-{index}'.encode()).hexdigest(),
            decisionNumber=8, batchStartNumber=7, observation=offered,
            currentProbabilities=p0, originalProbabilities=p0, anchor94Probabilities=p94,
            sampledIndex=1, referenceIndex=1, rawReferenceIndex=0,
            proposedIndices=[1, 0, 2], mandatoryIndices=[0, 1, 2], inclusionProbabilities=[1., 1., 1.],
            futureSeeds=[-1000-index*2, -1001-index*2])
        root = data.PublicRoot(record, context, original.model, split='TRAIN'); roots.append(root)
        targets.append(legacy.WinPairedTarget.from_record(root, dict(objectiveVersion=objective.WIN,
            publicSha256=root.sha256, futureSeeds=record['futureSeeds'], actions=[
                dict(actionIndex=action, winShares=[1., 1.] if action == 0 else [0., 0.])
                for action in record['proposedIndices']])))
        anchor = dict(index=index, split='TRAIN', observations=[{key: record[key] for key in
            ('decisionNumber', 'batchStartNumber', 'observation', 'anchor94Probabilities', 'originalProbabilities')}])
        anchors.append(data.AnchorWorld.from_record(anchor, original.model, count))
    return data.Training(context, tuple(roots), tuple(targets), tuple(anchors), 4), original, dictionary


class DistributionTests(unittest.TestCase):
    def test_zero_residual_is_exact_qualified_plus_uniform(self):
        self.assertEqual(distribution.probabilities([0., 1., 0.], [0., 0., 0.]),
            [.2/3, .8+.2/3, .2/3])

    def test_constant_residual_is_exact_anchor(self):
        self.assertEqual(distribution.probabilities([.2, .8], [17., 17.]), distribution.prior([.2, .8]))

    def test_supported_tail_can_become_preferred_without_gate(self):
        result = distribution.probabilities([1., 0., 0.], [0., 8., -2.])
        self.assertGreater(result[1], .99)
        self.assertTrue(all(value > 0. for value in result))

    def test_no_permanent_final_floor(self):
        result = distribution.probabilities([1., 0.], [0., -40.])
        self.assertLess(result[1], 1e-15)

    def test_numerical_underflow_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'underflowed'):
            distribution.probabilities([1., 0.], [0., -1000.])

    def test_wrong_formula_record_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'exact prospective'):
            distribution.prior([1., 0.], contract=dict(distribution.RECORD, epsilon=.21))

    def test_anchor_rejects_negative_nonfinite_and_unnormalized(self):
        for values in ([-.1, 1.1], [math.nan, 1.], [.3, .3]):
            with self.assertRaises(ValueError):
                distribution.prior(values)


class PolicyTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)

    def test_warm_initialization_copies_all_embeddings_without_rng(self):
        source = model(True); initialized = policy.initialize(source)
        for key in ('stateWeights', 'stateBias', 'actionWeights', 'actionBias'):
            self.assertEqual(initialized[key], source[key])
        self.assertEqual(initialized['actorWeights'], [0.]*64)
        self.assertEqual(initialized['valueWeights'], [0.]*64)
        self.assertEqual(initialized['valueBias'], 0.)
        self.assertNotEqual(source['actorWeights'], initialized['actorWeights'])

    def test_scalar_and_torch_forward_agree_for_singleton_and75(self):
        for count in (1, 3, 75):
            source = model(True); offered = view(count); anchor = [0.]*count; anchor[-1] = 1.
            actor = policy.TokenAnchoredPolicy(source)
            logs, _ = actor([actor.prepare(offered, anchor)])
            expected = policy.reference_forward(source, offered, anchor)['probabilities']
            self.assertLess(max(abs(a-c) for a, c in zip(logs[0].exp().tolist(), expected)), 2e-14)

    def test_raw_residual_does_not_reuse_legacy_twenty_clamp(self):
        source = policy.initialize(model()); source['actorWeights'] = [40.]+[0.]*63
        source['stateWeights'] = [0.]*len(source['stateWeights']); source['stateBias'] = [1.]*64
        source['actionWeights'] = [0.]*len(source['actionWeights'])
        column = source['actionFeatureNames'].index('amount'); source['actionWeights'][column] = -20.
        result = policy.reference_forward(source, view(), [1., 0., 0.])
        self.assertGreater(result['residuals'][0], 20.)
        self.assertGreater(result['probabilities'][0], .99)

    def test_zero_output_gradient_remains_live(self):
        actor = policy.TokenAnchoredPolicy(policy.initialize(model()))
        logs, _ = actor([actor.prepare(view(), [0., 1., 0.])]); (-logs[0][0]).backward()
        self.assertGreater(float(actor.actorWeights.grad.abs().sum()), 0.)
        self.assertIsNone(actor.valueWeights.grad); self.assertIsNone(actor.valueBias.grad)

    def test_plain_legacy_observation_cannot_silently_lose_anchor(self):
        actor = policy.TokenAnchoredPolicy(policy.initialize(model()))
        with self.assertRaises(ValueError):
            actor.prepare(view())
        with self.assertRaisesRegex(ValueError, 'frozen94'):
            actor([view()])


class DataAndFitTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)

    def test_raw_reference_and_qualified_reference_are_distinct(self):
        training, _, _ = fixture(); root = training.roots[0].record
        self.assertEqual(root['observation']['baselineIndex'], 0)
        self.assertEqual(root['referenceIndex'], 1)
        self.assertEqual(training.targets[0].deltas, (0., 1., 0.))

    def test_tied94_anchor_preserves_raw_reference_before_canonical_first(self):
        training, actor, _ = fixture(); record = training.roots[0].record
        record['observation']['baselineIndex'] = 1; record['rawReferenceIndex'] = 1
        record['anchor94Probabilities'] = [.5, .5, 0.]
        record['originalProbabilities'] = record['currentProbabilities'] = distribution.prior([.5, .5, 0.])
        data.PublicRoot(record, training.context, actor.model, split='TRAIN')
        record['referenceIndex'] = 0; record['proposedIndices'] = [0, 1, 2]
        with self.assertRaisesRegex(ValueError, 'raw-reference-first'):
            data.PublicRoot(record, training.context, actor.model, split='TRAIN')

    def test_mandatory_and_random_inclusion_estimator_is_unbiased_in_value_and_gradient(self):
        def calculate(indices, inclusion):
            logits = b.torch.tensor([.5, -.3, .7, .1, -.2, .3], dtype=b.DTYPE, requires_grad=True)
            probabilities = logits.softmax(0); deltas = [0., .3, -.2, .7, .1, -.4]
            value = arithmetic.weighted_gain(probabilities, [1/6]*6, indices,
                [deltas[index] for index in indices], inclusion)
            value.backward()
            return float(value.detach()), logits.grad
        expected, gradient = calculate(list(range(6)), [1.]*6)
        samples = [calculate([0, 1, *subset], [1., 1., .5, .5, .5, .5])
            for subset in itertools.combinations(range(2, 6), 2)]
        self.assertAlmostEqual(sum(value for value, _ in samples)/len(samples), expected, places=14)
        actual = b.torch.stack([value for _, value in samples]).mean(0)
        self.assertLess(float((actual-gradient).abs().max()), 1e-14)

    def test_scalar_inclusion_cannot_disguise_mandatory_neighbors(self):
        training, actor, _ = fixture(); record = training.roots[0].record
        record['inclusionProbabilities'] = [.5, 1., .5]
        with self.assertRaisesRegex(ValueError, 'distinguish mandatory'):
            data.PublicRoot(record, training.context, actor.model, split='TRAIN')

    def test_legacy_prior_cannot_enter_warm_corpus(self):
        training, actor, _ = fixture(); record = training.roots[0].record
        record['originalProbabilities'] = b.prior_probabilities(3, 0)
        with self.assertRaisesRegex(ValueError, 'frozen94/C05'):
            data.PublicRoot(record, training.context, actor.model, split='TRAIN')

    def test_split_rejection_precedes_invalid_observation(self):
        training, actor, _ = fixture(); record = training.roots[0].record
        record.update(split='VALIDATION', observation={'untrusted': object()})
        with self.assertRaisesRegex(ValueError, 'split admission'):
            data.PublicRoot(record, training.context, actor.model, split='TRAIN')

    def test_behavior_uses_anchor_aware_preparation(self):
        training, original, _ = fixture(); actor = policy.TokenAnchoredPolicy(original.model)
        prepared = arithmetic.Prepared(training, actor)
        arithmetic.verify_behavior(prepared, actor)
        self.assertEqual(len(prepared.roots), 16)
        self.assertAlmostEqual(arithmetic.anchor_metrics(actor, prepared)['mean'], 0., places=14)

    def test_warm_kl_bounds_are_explicit_and_legacy_bounds_remain_strict(self):
        training, original, _ = fixture(); config = old_fit.Config(tuple(range(1, 21)), time.time()+30)
        for recipe, accepted in ((arithmetic.RECIPE, False), (fit.RECIPE, True)):
            actor = policy.TokenAnchoredPolicy(original.model)
            optimizer = b.torch.optim.Adam([getattr(actor, key) for key in b.PARAMETERS[:-2]], lr=.0003)
            with patch.object(arithmetic, 'anchor_metrics', return_value=dict(mean=.20, maximum=.50)):
                result = arithmetic.epoch(actor, optimizer, arithmetic.Prepared(training, actor), 1,
                    config, recipe=recipe)
            self.assertIs(result['accepted'], accepted)

    def test_small_declared_population_receives_a_real_pure_win_update(self):
        training, original, dictionary = fixture()
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'fit'
            done = fit.fit(training, original, original, dictionary, output,
                old_fit.Config(tuple(range(1, 21)), time.time()+30))
            self.assertTrue(done['actorChanged']); self.assertTrue(done['operationalPassed'])
            self.assertFalse(done['qualificationClaimed']); self.assertFalse(done['activated'])
            result = policy.reference_forward(b.read_json(output/'policy.json'), view(), [0., 1., 0.])
            self.assertGreater(result['probabilities'][0], .2/3)
            with self.assertRaisesRegex(ValueError, 'prior fit'):
                old_fit.completed(output, objective=objective.WIN)

    def test_proximal_fit_rolls_back_on_collection_limit_and_seals_the_reason(self):
        training, original, dictionary = fixture()
        observe = arithmetic.collection_metrics

        def measured(actor, prepared):
            metrics = observe(actor, prepared)
            if metrics['maximum'] > 0.:
                return dict(metrics, maximum=.101)
            return metrics

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'fit'
            with patch.object(arithmetic, 'collection_metrics', side_effect=measured):
                done = fit.fit(training, original, original, dictionary, output,
                    old_fit.Config(tuple(range(1, 21)), time.time()+30), recipe=PROXIMAL_RECIPE)
            self.assertEqual(done['stopReason'], 'collection-kl-limit')
            self.assertEqual(done['acceptedEpochs'], 0)
            self.assertEqual(done['adamSteps'], 0)
            self.assertEqual(b.read_json(output/'policy.json'), original.model)
            history = b.read_json(output/'history.json')
            self.assertEqual(history[-1]['collection']['states'], training.ready_count)
            self.assertGreater(history[-1]['collection']['mean'], 0.)
            self.assertEqual(done, fit.completed(output))

    def test_expired_fit_creates_no_output(self):
        training, original, dictionary = fixture()
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'never'
            with self.assertRaisesRegex(ValueError, 'deadline expired'):
                fit.fit(training, original, original, dictionary, output,
                    old_fit.Config(tuple(range(1, 21)), time.time()-1))
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
