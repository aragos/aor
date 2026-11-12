"""Synthetic paired-objective/provenance tests only; no retained model or game inputs."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import numpy as np
import torch

import purchase_plan_difference_fit as fit


def state():
    result = {'layers.0.weight': torch.zeros((64, fit.frozen.INPUTS), dtype=torch.float32),
              'layers.0.bias': torch.zeros(64, dtype=torch.float32),
              'layers.2.weight': torch.zeros((1, 64), dtype=torch.float32),
              'layers.2.bias': torch.zeros(1, dtype=torch.float32)}
    result['layers.0.weight'][0, 0] = .4
    result['layers.2.weight'][0, 0] = .2
    return result


class Cache:
    def __init__(self, rows, exact=None, deltas=None):
        self.partition = 'DEVELOPMENT'
        self.feature_names = [f'feature-{i}' for i in range(fit.frozen.INPUTS)]
        self.x = np.zeros((sum(rows), fit.frozen.INPUTS), dtype=np.float32)
        self.x[:, 0] = np.arange(sum(rows), dtype=np.float32) / 10
        ends = np.cumsum(rows, dtype=np.int64)
        starts = np.r_[np.int64(0), ends[:-1]]
        self.arrays = dict(starts=starts, stops=ends,
            rowOrigin=np.repeat(np.arange(len(rows), dtype=np.int64), rows),
            planIndex=np.concatenate([np.arange(n, dtype=np.int64) for n in rows]),
            referenceRow=np.repeat(starts, rows),
            exactValue=np.full(sum(rows), np.nan) if exact is None else np.asarray(exact, dtype=np.float64),
            deltaTarget=np.concatenate([np.r_[np.nan, np.zeros(n - 1)] for n in rows])
                if deltas is None else np.asarray(deltas, dtype=np.float64))
        self.requested = []

    def dense(self, rows):
        self.requested.extend(map(int, rows))
        return self.x[rows]


def corpus_fixture():
    cache = Cache([2])
    cache.arrays['starts'] = np.full(fit.WORLDS, 2, dtype=np.int64)
    cache.arrays['stops'] = np.full(fit.WORLDS, 2, dtype=np.int64)
    cache.arrays['starts'][0] = 0
    origins = [dict(index=i, chunk=i // 256 + 1, localIndex=i % 256,
        split='TRAIN' if i < 1536 else 'VALIDATION' if i < 1792 else 'TEST',
        status='MISSING', informative=False, plans=[]) for i in range(fit.WORLDS)]
    origins[0].update(status='READY', informative=True,
        plans=[dict(index=i, status='SUPPORTED', aliasOf=None) for i in range(2)])
    corpus = dict(origins=origins, dictionarySha256=fit.frozen.DICTIONARY,
                  warmCheckpointSha256=fit.frozen.CHECKPOINT, sourceSha256='a' * 64)
    return cache, corpus


class FixedContractTests(unittest.TestCase):
    def test_fixed_recipe_accepts_only_shared_twenty_seeds(self):
        config = fit.Config(tuple(range(70100000, 70100020)))
        self.assertIs(config.validate(), config)

    def test_changed_anchor_is_rejected(self):
        with self.assertRaises(ValueError):
            fit.Config(tuple(range(20)), anchorCoefficient=.2).validate()

    def test_duplicate_shuffle_seed_is_rejected(self):
        with self.assertRaises(ValueError):
            fit.Config((1,) * 20).validate()

    def test_development_keeps_missing_worlds_without_rows(self):
        cache, corpus = corpus_fixture()
        self.assertEqual({'TRAIN': [0], 'VALIDATION': []},
                         fit.validate_cache(cache, corpus, cache.feature_names))

    def test_test_partition_is_rejected_before_feature_read(self):
        cache, corpus = corpus_fixture()
        cache.partition = 'TEST_FEATURES'
        with self.assertRaises(ValueError):
            fit.validate_cache(cache, corpus, cache.feature_names)
        self.assertEqual([], cache.requested)

    def test_split_migration_is_rejected(self):
        cache, corpus = corpus_fixture()
        corpus['origins'][0]['split'] = 'VALIDATION'
        with self.assertRaises(ValueError):
            fit.validate_cache(cache, corpus, cache.feature_names)

    def test_reference_target_is_rejected(self):
        cache, corpus = corpus_fixture()
        cache.arrays['deltaTarget'][0] = .1
        with self.assertRaises(ValueError):
            fit.validate_cache(cache, corpus, cache.feature_names)

    def test_alias_cannot_be_an_additional_loss_row(self):
        cache, corpus = corpus_fixture()
        corpus['origins'][0]['plans'][1]['aliasOf'] = 0
        with self.assertRaises(ValueError):
            fit.validate_cache(cache, corpus, cache.feature_names)

    def test_reference_must_remain_in_same_origin(self):
        cache, corpus = corpus_fixture()
        cache.arrays['referenceRow'][1] = 1
        with self.assertRaises(ValueError):
            fit.validate_cache(cache, corpus, cache.feature_names)

    def test_warm_checkpoint_tamper_is_rejected(self):
        cache, corpus = corpus_fixture()
        corpus['warmCheckpointSha256'] = 'b' * 64
        with self.assertRaises(ValueError):
            fit.validate_cache(cache, corpus, cache.feature_names)


class ObjectiveTests(unittest.TestCase):
    def test_pair_loss_averages_worlds_not_plan_rows(self):
        cache = Cache([2, 4], deltas=[np.nan, .1, np.nan, .3, .3, .3])
        model = fit.make_model(state(), 'head', 'cpu')
        cache.x.fill(0)
        original = np.full(6, .5)
        loss, pair, anchor = fit.batch_loss(model, cache, [0, 1], original, 'cpu')
        self.assertAlmostEqual(.05, float(pair.detach()), places=6)
        self.assertEqual(0., float(anchor.detach()))
        self.assertAlmostEqual(.05, float(loss.detach()), places=6)

    def test_anchor_averages_leaves_then_worlds(self):
        cache = Cache([2, 4])
        cache.x.fill(0)
        model = fit.make_model(state(), 'head', 'cpu')
        _, _, anchor = fit.batch_loss(model, cache, [0, 1], np.asarray([.1, .1, .3, .3, .3, .3]), 'cpu')
        self.assertAlmostEqual(.1, float(anchor.detach()), places=6)

    def test_exact_alternative_retains_reference_gradient(self):
        cache = Cache([2], exact=[np.nan, 0], deltas=[np.nan, 0])
        model = fit.make_model(state(), 'head', 'cpu')
        loss, _, _ = fit.batch_loss(model, cache, [0], np.asarray([.5, 0]), 'cpu')
        loss.backward()
        self.assertGreater(float(model.layers[2].bias.grad), 0.)
        self.assertEqual([0], cache.requested)

    def test_exact_reference_retains_alternative_gradient(self):
        cache = Cache([2], exact=[1, np.nan], deltas=[np.nan, 0])
        model = fit.make_model(state(), 'head', 'cpu')
        loss, _, _ = fit.batch_loss(model, cache, [0], np.asarray([1, .5]), 'cpu')
        loss.backward()
        self.assertLess(float(model.layers[2].bias.grad), 0.)
        self.assertEqual([1], cache.requested)

    def test_all_exact_world_keeps_constant_loss_without_model_call(self):
        cache = Cache([2], exact=[.7, 0], deltas=[np.nan, -.5])
        model = fit.make_model(state(), 'head', 'cpu')
        with mock.patch.object(model, 'forward', side_effect=AssertionError('Exact bypass entered critic')):
            loss, pair, anchor = fit.batch_loss(model, cache, [0], np.asarray([.7, 0]), 'cpu')
        self.assertFalse(loss.requires_grad)
        self.assertAlmostEqual(.04, float(pair), places=6)
        self.assertEqual(0., float(anchor))

    def test_private_or_label_arrays_do_not_enter_forward(self):
        cache = Cache([2])
        model = fit.make_model(state(), 'head', 'cpu')
        baseline = fit.predict(model, cache, [0], 'cpu', lambda: None)
        cache.arrays['deltaTarget'][1] = -.9
        cache.arrays['unknownHiddenHands'] = ['arbitrary secret']
        actual = fit.predict(model, cache, [0], 'cpu', lambda: None)
        np.testing.assert_array_equal(baseline, actual)

    def test_model_constructor_preserves_caller_rng(self):
        previous = torch.random.get_rng_state().clone()
        fit.make_model(state(), 'full', 'cpu')
        self.assertTrue(torch.equal(previous, torch.random.get_rng_state()))

    def test_two_arms_start_with_identical_forward_values(self):
        original = state()
        cache = Cache([2])
        head = fit.make_model(original, 'head', 'cpu')
        full = fit.make_model(original, 'full', 'cpu')
        np.testing.assert_array_equal(fit.predict(head, cache, [0], 'cpu', lambda: None),
                                      fit.predict(full, cache, [0], 'cpu', lambda: None))

    def test_head_step_preserves_exact_basis_and_has65_parameters(self):
        original = state()
        model = fit.make_model(original, 'head', 'cpu')
        optimizer = torch.optim.Adam([p for p in model.parameters() if p.requires_grad], lr=.001)
        loss, _, _ = fit.batch_loss(model, Cache([2], deltas=[np.nan, .3]), [0], np.asarray([.5, .5]), 'cpu')
        loss.backward()
        optimizer.step()
        self.assertTrue(torch.equal(original['layers.0.weight'], model.layers[0].weight))
        self.assertTrue(torch.equal(original['layers.0.bias'], model.layers[0].bias))
        self.assertFalse(torch.equal(original['layers.2.weight'], model.layers[2].weight))
        self.assertEqual(65, sum(p.numel() for p in model.parameters() if p.requires_grad))

    def test_full_step_can_change_basis_and_has463745_parameters(self):
        original = state()
        model = fit.make_model(original, 'full', 'cpu')
        optimizer = torch.optim.Adam(model.parameters(), lr=.001)
        loss, _, _ = fit.batch_loss(model, Cache([2], deltas=[np.nan, .3]), [0], np.asarray([.5, .5]), 'cpu')
        loss.backward()
        optimizer.step()
        self.assertFalse(torch.equal(original['layers.0.weight'], model.layers[0].weight))
        self.assertEqual(463745, sum(p.numel() for p in model.parameters() if p.requires_grad))

    def test_validation_errors_have_one_value_per_world(self):
        cache = Cache([2, 4], deltas=[np.nan, .1, np.nan, .3, .3, .3])
        actual = fit.paired_errors(np.full(6, .5), cache, [0, 1])
        np.testing.assert_allclose(actual, [.01, .09], rtol=0, atol=1e-15)


class SelectionTests(unittest.TestCase):
    def test_exact_tie_prefers_original(self):
        old = dict(arm='unchanged', epoch=0, validationMse=.2)
        records = [dict(arm='head', epoch=1, validationMse=.2), old]
        self.assertIs(old, fit.choose(records))

    def test_earlier_epoch_precedes_arm_tie_breaker(self):
        earlier = dict(arm='full', epoch=2, validationMse=.1)
        records = [dict(arm='head', epoch=3, validationMse=.1), earlier]
        self.assertIs(earlier, fit.choose(records))

    def test_identical_epoch_error_prefers_head(self):
        head = dict(arm='head', epoch=2, validationMse=.1)
        self.assertIs(head, fit.choose([dict(arm='full', epoch=2, validationMse=.1), head]))

    def test_better_error_precedes_earlier_epoch(self):
        better = dict(arm='full', epoch=20, validationMse=.1)
        self.assertIs(better, fit.choose([dict(arm='head', epoch=1, validationMse=.2), better]))

    def test_real_warm_subjects_are_required_before_fit(self):
        cache, corpus = corpus_fixture()
        with tempfile.TemporaryDirectory() as temporary:
            checkpoint = Path(temporary) / 'fake.pt'
            dictionary = Path(temporary) / 'fake.json'
            checkpoint.write_bytes(b'not the admitted checkpoint')
            dictionary.write_text('{}')
            with self.assertRaises(ValueError):
                fit.fit(cache, corpus, Path(temporary) / 'output', fit.Config(tuple(range(20))),
                        lambda: None, 'cpu', checkpoint=checkpoint, dictionary=dictionary)
            self.assertFalse((Path(temporary) / 'output').exists())

    def test_two_epoch_order_receipts_cannot_change_recipe(self):
        with self.assertRaises(ValueError):
            fit.Config(tuple(range(20)), epochs=2).validate()

    def test_synthetic_twenty_epoch_arm_retains_earliest_best_checkpoint(self):
        cache = Cache([2, 2], deltas=[np.nan, .1, np.nan, .1])
        groups = {'TRAIN': [0], 'VALIDATION': [1]}
        initial = state()
        model = fit.make_model(initial, 'head', 'cpu')
        baseline = fit.predict(model, cache, [0, 1], 'cpu', lambda: None)
        errors = [np.asarray([.2]), np.asarray([.1])] + [np.asarray([.1])] * 18
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with mock.patch.object(fit, 'paired_errors', side_effect=errors):
                receipt = fit._fit_arm('head', initial, cache, groups, baseline, output,
                    fit.Config(tuple(range(20))), lambda: None, 'cpu', .3)
            self.assertEqual(2, receipt['best']['epoch'])
            self.assertEqual(21, len(receipt['history']))
            self.assertEqual(list(range(21)), [r['epoch'] for r in receipt['history']])
            retained = torch.load(output / receipt['modelFile'], weights_only=True)
            self.assertTrue(torch.equal(initial['layers.0.weight'], retained['layers.0.weight']))
            self.assertFalse(torch.equal(initial['layers.2.weight'], retained['layers.2.weight']))
            self.assertEqual(receipt['modelSha256'], fit.data.sha(output / receipt['modelFile']))


if __name__ == '__main__':
    torch.set_num_threads(1)
    if torch.get_num_interop_threads() != 1:
        torch.set_num_interop_threads(1)
    unittest.main()
