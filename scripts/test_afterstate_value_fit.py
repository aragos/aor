"""Synthetic arrays/checkpoints only; no retained worlds, selected model or outcome files."""
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import numpy as np
import torch

import afterstate_value_fit as fit
import purchase_value_benchmark as benchmark
import purchase_value_data as data


torch.set_num_threads(1)


class DenseCache:
    def __init__(self, contexts, rewards, names=None):
        self.feature_names = names or ['card.a', 'purchase.a', 'purchase.decision.new']
        lengths = np.asarray([len(items) for items in contexts], dtype=np.int64)
        self.arrays = dict(starts=np.cumsum(lengths) - lengths, stops=np.cumsum(lengths),
            rowGame=np.repeat(np.arange(len(contexts)), lengths),
            context=np.asarray([c for items in contexts for c in items], dtype=np.uint8),
            reward=np.asarray(rewards, dtype=np.float64))
        rows = int(lengths.sum())
        self.states = ((np.arange(rows * len(self.feature_names)).reshape(rows, -1) % 13) - 6).astype(np.float32) / 6
        self.read_rows = []

    def game_rows(self, games):
        return np.concatenate([np.arange(self.arrays['starts'][g], self.arrays['stops'][g])
                               for g in games]).astype(np.int64)

    def dense(self, rows, dtype='float32'):
        self.read_rows.extend(rows.tolist())
        return self.states[rows].astype(dtype)


def corpus(splits):
    return dict(games=[dict(id=i, split=split, gameSeed=100 + i) for i, split in enumerate(splits)])


class WarmStateTests(unittest.TestCase):
    def setUp(self):
        self.old_names = ['card.a', 'purchase.a']
        self.names = self.old_names + ['purchase.decision.new']
        self.original = benchmark.Critic((64,), 19, inputs=2).state_dict()

    def test_original_columns_are_exact_and_append_is_zero(self):
        result = fit.warm_state(self.original, self.old_names, self.names)
        self.assertTrue(torch.equal(result['layers.0.weight'][:, :2], self.original['layers.0.weight']))
        self.assertEqual(float(result['layers.0.weight'][:, 2].abs().sum()), 0)

    def test_bias_and_output_are_exact_copies(self):
        result = fit.warm_state(self.original, self.old_names, self.names)
        for key in ('layers.0.bias', 'layers.2.weight', 'layers.2.bias'):
            self.assertTrue(torch.equal(result[key], self.original[key]))

    def test_warm_expansion_preserves_caller_rng(self):
        before = torch.get_rng_state().clone()
        fit.warm_state(self.original, self.old_names, self.names)
        self.assertTrue(torch.equal(before, torch.get_rng_state()))

    def test_warm_expansion_does_not_alias_source(self):
        result = fit.warm_state(self.original, self.old_names, self.names)
        result['layers.2.bias'].add_(1)
        self.assertEqual(float(self.original['layers.2.bias'][0]), 0)

    def test_old_forward_function_is_preserved_with_new_keys_nonzero(self):
        expanded = fit.warm_state(self.original, self.old_names, self.names)
        before = fit._critic(self.original, 2, 'cpu')
        after = fit._critic(expanded, 3, 'cpu')
        states = torch.tensor([[1., -.5, 1.], [-.3, .1, -1.]], dtype=torch.float32)
        with torch.no_grad():
            torch.testing.assert_close(before(states[:, :2]), after(states), rtol=0, atol=2e-6)

    def test_runtime_constructor_preserves_caller_rng(self):
        before = torch.get_rng_state().clone()
        fit._critic(self.original, 2, 'cpu')
        self.assertTrue(torch.equal(before, torch.get_rng_state()))

    def test_permuted_original_prefix_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unchanged old prefix'):
            fit.warm_state(self.original, self.old_names, list(reversed(self.old_names)) + self.names[2:])

    def test_nonfinite_checkpoint_is_rejected(self):
        self.original['layers.0.bias'][0] = float('nan')
        with self.assertRaisesRegex(ValueError, 'finite FP32'):
            fit.warm_state(self.original, self.old_names, self.names)

    def test_wrong_numeric_dtype_is_rejected(self):
        original = {key: value.double() for key, value in self.original.items()}
        with self.assertRaisesRegex(ValueError, 'finite FP32'):
            fit.warm_state(original, self.old_names, self.names)

    def test_synthetic_parity_detects_changed_old_bias(self):
        expanded = fit.warm_state(self.original, self.old_names, self.names)
        expanded['layers.2.bias'].add_(.1)
        with self.assertRaisesRegex(ValueError, 'FP32 parity exceeded'):
            fit.warm_parity(self.original, expanded, self.old_names, self.names, 'cpu')


class ContextWeightTests(unittest.TestCase):
    def test_contexts_not_rows_receive_equal_world_weight(self):
        cache = DenseCache([[0, 0, 0, 3], [1]], [.2, .8])
        np.testing.assert_allclose(fit.context_row_weights(cache), [1/6, 1/6, 1/6, .5, 1.])

    def test_empty_world_stays_missing(self):
        cache = DenseCache([[], [5]], [.8, .2])
        np.testing.assert_array_equal(fit.context_row_weights(cache), [1.])
        result = fit.world_errors(np.array([.7]), cache, [0, 1])
        self.assertTrue(np.isnan(result[0]))
        self.assertAlmostEqual(result[1], .25)

    def test_six_contexts_receive_six_equal_shares(self):
        cache = DenseCache([[0, 1, 2, 3, 4, 5]], [.4])
        np.testing.assert_allclose(fit.context_row_weights(cache), np.full(6, 1/6))

    def test_wrong_context_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'six actual state contexts'):
            fit.context_row_weights(DenseCache([[6]], [.1]))

    def test_wrong_world_ownership_is_rejected(self):
        cache = DenseCache([[0], [1]], [.1, .2])
        cache.arrays['rowGame'][0] = 1
        with self.assertRaisesRegex(ValueError, 'complete-world owner'):
            fit.context_row_weights(cache)

    def test_validation_error_matches_context_balanced_training_loss(self):
        cache = DenseCache([[0, 0, 0, 3]], [0.])
        np.testing.assert_array_equal(fit.balanced_errors(np.array([0., 0., 0., 1.]), cache, [0]), [.5])

    def test_context_error_has_independent_world_denominator(self):
        cache = DenseCache([[0, 0, 0], [0], [3]], [0., 0., 0.])
        result = fit.world_errors(np.array([1., 1., 1., 0., 0.]), cache, [0, 1, 2], 0)
        self.assertEqual(fit._summary(result), dict(representedWorlds=2, excludedWorlds=1, mse=.5))

    def test_training_context_constant_optimizes_same_world_context_weights(self):
        cache = DenseCache([[0, 3], [0]], [0., 1.])
        values = fit.training_constants(cache, corpus(['training', 'training']))
        self.assertAlmostEqual(values['CARD']['value'], 2/3)
        self.assertEqual(values['STABILIZATION']['value'], 0)
        self.assertIsNone(values['RELIEF']['value'])

    def test_validation_and_test_labels_never_enter_constants(self):
        cache = DenseCache([[0], [0], [0]], [.3, float('nan'), float('nan')])
        values = fit.training_constants(cache, corpus(['training', 'validation', 'test']))
        self.assertEqual(values['CARD']['value'], .3)
        self.assertEqual(values['CARD']['trainingWorlds'], 1)


class BootstrapTests(unittest.TestCase):
    def test_identical_views_use_exactly_the_same_resampled_worlds(self):
        values = np.array([.1, .7, -.3, np.nan])
        result = fit.shared_bootstrap({'a': values, 'b': values.copy()}, 123, 2000)
        self.assertEqual(result['a'], result['b'])

    def test_missing_context_worlds_are_not_zero_imputed(self):
        result = fit.shared_bootstrap({'a': np.array([np.nan, .5, .5])}, 123, 2000)['a']
        self.assertEqual((result['worlds'], result['prospectiveWorlds']), (2, 3))
        self.assertEqual((result['mean'], result['lower95'], result['upper95']), (.5, .5, .5))

    def test_all_missing_context_is_explicit(self):
        result = fit.shared_bootstrap({'a': np.full(3, np.nan)}, 123, 2000)['a']
        self.assertEqual(result['worlds'], 0)
        self.assertIsNone(result['mean'])
        self.assertIsNone(result['lower95'])

    def test_different_world_spaces_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'same whole-world index space'):
            fit.shared_bootstrap({'a': np.array([0., 1.]), 'b': np.array([0.])}, 123, 2000)


class RecipeTests(unittest.TestCase):
    def test_fixed_config_accepts_exact_frozen_recipe(self):
        self.assertEqual(fit.Config().validate().epochs, 20)

    def test_recipe_cannot_silently_add_epoch_or_seed_look(self):
        with self.assertRaisesRegex(ValueError, 'audited RNGs are fixed'):
            replace(fit.Config(), epochs=21).validate()

    def test_old_dictionary_identity_is_intrinsic(self):
        with self.assertRaisesRegex(ValueError, 'original52 ordered7241'):
            fit._validate_subject(DenseCache([[0]], [.3]), {}, ['card.a'])


class SyntheticFitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.names = [f'card.synthetic{i:04d}' for i in range(7241)]
        cls.contexts = [[] for _ in range(4096)]
        cls.contexts[0] = list(range(6))
        cls.contexts[3072] = list(range(6))
        cls.contexts[3584] = [5]
        cls.cache = DenseCache(cls.contexts, [.3] * 3584 + [float('nan')] * 512,
                              cls.names + list(fit.APPENDED_NAMES))
        cls.corpus = corpus(['training'] * 3072 + ['validation'] * 512 + ['test'] * 512)
        cls.corpus.update(stateFeatureNames=cls.cache.feature_names, dictionarySha256='a' * 64)
        cls.source = cls.root / 'synthetic.pt'
        torch.save(benchmark.Critic((64,), 19, inputs=7241).state_dict(), cls.source)
        cls.source_sha = data.sha(cls.source)
        before = torch.get_rng_state().clone()
        with patch.object(fit, 'SOURCE_SHA', cls.source_sha), patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(cls.names)):
            cls.model, cls.selected = fit.fit(cls.cache, cls.corpus, cls.root / 'fit', fit.Config(),
                lambda: None, 'cpu', original=cls.source, old_names=cls.names, source_sha=cls.source_sha)
        cls.rng_unchanged = torch.equal(before, torch.get_rng_state())

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_exactly_twenty_epochs_and_one_warm_fit(self):
        self.assertEqual(len(self.selected['fit']['history']), 20)
        self.assertEqual(self.selected['actualFits'], 1)

    def test_synthetic_optimization_never_reads_test_state_or_labels(self):
        self.assertNotIn(12, self.cache.read_rows)
        self.assertFalse(self.selected['testInspected'])
        self.assertTrue(all(np.isfinite(item['validationMse']) for item in self.selected['fit']['history']))

    def test_selected_model_is_best_validation_with_earliest_tie(self):
        history = self.selected['fit']['history']
        expected = min(history, key=lambda row: row['validationMse'])
        self.assertEqual(self.selected['fit']['best'], expected)

    def test_constructor_randomness_does_not_escape_fit(self):
        self.assertTrue(self.rng_unchanged)

    def test_selection_is_persisted_without_opening_test(self):
        self.assertTrue((self.root / 'fit/selected.json').is_file())
        self.assertFalse((self.root / 'fit/test-attempt.json').exists())
        self.assertFalse((self.root / 'fit/report.json').exists())

    def test_full_width_warm_function_has_persisted_synthetic_parity(self):
        proof = data.read(self.root / 'fit/warm-synthetic-parity.json')
        self.assertEqual((proof['originalColumns'], proof['zeroAppendedColumns']), (7241, 3))
        self.assertLessEqual(proof['maximumAbsoluteError'], 2e-6)

    def test_fixed_source_bytes_are_checked_before_fit(self):
        with patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(self.names)):
            with self.assertRaisesRegex(ValueError, 'warm checkpoint'):
                fit.fit(self.cache, self.corpus, self.root / 'wrong', fit.Config(), lambda: None,
                        'cpu', original=self.source, old_names=self.names)

    def test_missing_training_context_stops_before_fit_or_output(self):
        contexts = list(self.contexts)
        contexts[0] = [0]
        cache = DenseCache(contexts, [.3] * 4096, self.cache.feature_names)
        with patch.object(fit, 'SOURCE_SHA', self.source_sha), patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(self.names)):
            with self.assertRaisesRegex(ValueError, 'every context needs a represented TRAIN world'):
                fit.fit(cache, self.corpus, self.root / 'missing', fit.Config(), lambda: None, 'cpu',
                        original=self.source, old_names=self.names, source_sha=self.source_sha)
        self.assertFalse((self.root / 'missing').exists())


class SyntheticEvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.names = [f'card.synthetic{i:04d}' for i in range(7241)]
        contexts = [[] for _ in range(4096)]
        contexts[0] = list(range(6))
        contexts[3072] = list(range(6))
        # Exactly63 independent RELIEF worlds; remaining worlds preserve old/new context coverage.
        test_contexts = [0] * 107 + [1] * 86 + [2] * 85 + [3] * 86 + [4] * 63 + [5] * 85
        for game, context in enumerate(test_contexts, 3584):
            contexts[game] = [context]
        cls.cache = DenseCache(contexts, [.3] * 4096, cls.names + list(fit.APPENDED_NAMES))
        cls.corpus = corpus(['training'] * 3072 + ['validation'] * 512 + ['test'] * 512)
        cls.corpus.update(stateFeatureNames=cls.cache.feature_names, dictionarySha256='a' * 64)
        cls.source = cls.root / 'synthetic.pt'
        original = benchmark.Critic((64,), 19, inputs=7241).state_dict()
        torch.save(original, cls.source)
        cls.source_sha = data.sha(cls.source)
        torch.save(fit.warm_state(original, cls.names, cls.cache.feature_names), cls.root / 'warm-best.pt')
        cls.selection = dict(kind=fit.KIND, testInspected=False, sourceSha256=cls.source_sha,
            originalNamesSha256=fit.names_sha(cls.names), config=fit.asdict(fit.Config()),
            dictionarySha256='a' * 64, selectedSha256=data.sha(cls.root / 'warm-best.pt'),
            fit=dict(testInspected=False, modelSha256=data.sha(cls.root / 'warm-best.pt')),
            trainingConstants=fit.training_constants(cls.cache, cls.corpus))
        cls.cache.arrays['reward'][:] = np.nan
        cls.cache.arrays['reward'].flags.writeable = False
        data.document(cls.root / 'selected.json', cls.selection)
        cls.label_calls = []

        def labels(receipt_path):
            receipt = data.read(receipt_path)
            assert receipt['predictionsComplete'] and not receipt['semanticLabelsDecoded']
            assert receipt['selectedSha256'] == cls.selection['selectedSha256']
            assert receipt['testIds'] == list(range(3584, 4096))
            assert all(data.sha(receipt_path.parent / name) == digest
                       for name, digest in receipt['files'].items())
            assert np.isnan(cls.cache.arrays['reward']).all()
            cls.label_calls.append(receipt_path)
            return np.concatenate([np.full(3584, np.nan), np.full(512, .3)])

        with patch.object(fit, 'SOURCE_SHA', cls.source_sha), patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(cls.names)):
            cls.report = fit.evaluate(cls.cache, cls.corpus, cls.root, fit.Config(), lambda: None,
                                      'cpu', original=cls.source, old_names=cls.names, load_test_labels=labels)
        cls.first_evaluation_reads = tuple(cls.cache.read_rows)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_new_context_threshold_counts_independent_worlds(self):
        coverage = self.report['contextCoverage']['RELIEF']
        self.assertEqual(coverage, dict(independentWorlds=63, sufficientCoverage=False, minimumRequired=64))
        self.assertNotIn('RELIEF.selected-minus-constant', self.report['comparisons'])

    def test_sufficient_new_context_has_fixed_comparison(self):
        self.assertIn('STABILIZATION.selected-minus-constant', self.report['comparisons'])
        self.assertEqual(self.report['contextCoverage']['STABILIZATION']['independentWorlds'], 86)

    def test_original52_is_never_used_on_new_context_rows(self):
        counts = np.bincount(self.first_evaluation_reads, minlength=len(self.cache.arrays['context']))
        test_rows = self.cache.game_rows(range(3584, 4096))
        np.testing.assert_array_equal(counts[test_rows], 1 + (self.cache.arrays['context'][test_rows] < 3))
        self.assertEqual(set(self.report['models']['original52']), set(fit.CONTEXTS[:3]))

    def test_each_world_has_one_primary_error(self):
        self.assertEqual(self.report['models']['selected']['overall']['representedWorlds'], 512)
        self.assertEqual(self.report['comparisons']['overall.selected-minus-constant']['prospectiveWorlds'], 512)

    def test_global_calibration_retains_total_world_weight(self):
        bins = self.report['calibration']['selected']['overall']
        self.assertAlmostEqual(sum(row['gameWeight'] for row in bins), 512)

    def test_predictions_precede_the_only_semantic_label_callback(self):
        self.assertEqual(self.label_calls, [self.root / 'test-predictions.json'])
        self.assertTrue(self.report['predictionsFrozenBeforeLabels'])

    def test_scoring_does_not_modify_label_free_csr(self):
        self.assertTrue(np.isnan(self.cache.arrays['reward']).all())
        self.assertFalse(self.cache.arrays['reward'].flags.writeable)

    def test_test_evaluation_cannot_be_repeated(self):
        with patch.object(fit, 'SOURCE_SHA', self.source_sha), patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(self.names)):
            with self.assertRaisesRegex(ValueError, 'cannot overwrite'):
                fit.evaluate(self.cache, self.corpus, self.root, fit.Config(), lambda: None,
                             'cpu', original=self.source, old_names=self.names,
                             load_test_labels=lambda _: self.fail('Repeated evaluation opened labels.'))

    def test_changed_selected_subject_fails_before_test_attempt(self):
        other = self.root / 'bad'
        other.mkdir()
        (other / 'warm-best.pt').write_bytes((self.root / 'warm-best.pt').read_bytes())
        data.document(other / 'selected.json', dict(self.selection, sourceSha256='0' * 64))
        with patch.object(fit, 'SOURCE_SHA', self.source_sha), patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(self.names)):
            with self.assertRaisesRegex(ValueError, 'unchanged validation-selected'):
                fit.evaluate(self.cache, self.corpus, other, fit.Config(), lambda: None,
                             'cpu', original=self.source, old_names=self.names,
                             load_test_labels=lambda _: self.fail('Changed model opened labels.'))
        self.assertFalse((other / 'test-attempt.json').exists())

    def test_preloaded_test_reward_is_rejected_before_prediction(self):
        import copy
        cache = copy.copy(self.cache)
        cache.arrays = dict(self.cache.arrays, reward=np.full(4096, .3))
        with self.assertRaisesRegex(ValueError, 'label-free CSR'):
            with patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(self.names)):
                fit.evaluate(cache, self.corpus, self.root, fit.Config(), lambda: None,
                             'cpu', original=self.source, old_names=self.names,
                             load_test_labels=lambda _: self.fail('Preloaded labels were accepted.'))

    def test_callback_cannot_supply_development_labels(self):
        other = self.root / 'wrong-label-subject'
        other.mkdir()
        (other / 'warm-best.pt').write_bytes((self.root / 'warm-best.pt').read_bytes())
        data.document(other / 'selected.json', self.selection)
        with patch.object(fit, 'SOURCE_SHA', self.source_sha), patch.object(fit, 'OLD_NAMES_SHA', fit.names_sha(self.names)):
            with self.assertRaisesRegex(ValueError, 'only the512 finite TEST rewards'):
                fit.evaluate(self.cache, self.corpus, other, fit.Config(), lambda: None,
                             'cpu', original=self.source, old_names=self.names,
                             load_test_labels=lambda _: np.full(4096, .3))
        self.assertTrue((other / 'test-predictions.json').is_file())
        self.assertFalse((other / 'report.json').exists())
        self.assertTrue(np.isnan(self.cache.arrays['reward']).all())


if __name__ == '__main__':
    unittest.main()
