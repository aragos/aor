"""Synthetic state rows only; no retained game, checkpoint or outcome files are opened."""
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import numpy as np
import torch

import joint_state_value_fit as joint
import purchase_value_benchmark as benchmark
import purchase_value_data as data


torch.set_num_threads(1)


class DenseCache:
    def __init__(self, phases, rewards, *, inputs=3):
        lengths = np.asarray([len(items) for items in phases], dtype=np.int64)
        self.arrays = dict(starts=np.cumsum(lengths)-lengths, stops=np.cumsum(lengths),
            rowGame=np.repeat(np.arange(len(phases)), lengths),
            phase=np.asarray([phase for items in phases for phase in items], dtype=np.int64),
            reward=np.asarray(rewards, dtype=np.float64))
        rows = int(lengths.sum())
        self.states = ((np.arange(rows*inputs).reshape(rows, inputs) % 13)-6).astype(np.float32)/6
        self.read_rows = []

    def game_rows(self, games):
        return np.concatenate([np.arange(self.arrays['starts'][game], self.arrays['stops'][game])
                               for game in games]).astype(np.int64)

    def dense(self, rows, dtype='float32'):
        self.read_rows.extend(rows.tolist())
        return self.states[rows].astype(dtype)


def corpus(splits):
    return dict(games=[dict(id=index, split=split, gameSeed=100+index)
                       for index, split in enumerate(splits)])


class WeightTests(unittest.TestCase):
    def test_two_phases_receive_equal_world_weight_despite_unequal_rows(self):
        cache = DenseCache([[0, 0, 0, 1], [0, 1]], [.2, .8])
        np.testing.assert_allclose(joint.phase_row_weights(cache), [1/6, 1/6, 1/6, .5, .5, .5])

    def test_single_nonempty_phase_receives_full_world_weight(self):
        cache = DenseCache([[0, 0], [1]], [.2, .8])
        np.testing.assert_array_equal(joint.phase_row_weights(cache), [.5, .5, 1.])

    def test_no_row_world_retains_no_loss_weight(self):
        cache = DenseCache([[], [1]], [.99, .2])
        np.testing.assert_array_equal(joint.phase_row_weights(cache), [1.])
        self.assertEqual(len(cache.arrays['reward']), 2)

    def test_phase_balanced_error_differs_from_row_mean(self):
        cache = DenseCache([[0, 0, 0, 1]], [0.])
        prediction = np.asarray([0., 0., 0., 1.])
        np.testing.assert_array_equal(joint.world_errors(prediction, cache, [0]), [.5])
        np.testing.assert_array_equal(joint.world_errors(prediction, cache, [0], balanced=False), [.25])

    def test_no_row_world_is_explicit_in_metrics_but_absent_from_mean(self):
        cache = DenseCache([[], [1]], [.9, 0.])
        prediction = np.asarray([.5])
        errors = joint.world_errors(prediction, cache, [0, 1])
        self.assertTrue(np.isnan(errors[0]))
        np.testing.assert_array_equal(joint.phase_balanced_errors(prediction, cache, [0, 1]), [.25])

    def test_wrong_phase_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'phase ownership'):
            joint.phase_row_weights(DenseCache([[2]], [.2]))

    def test_wrong_world_owner_is_rejected(self):
        cache = DenseCache([[0], [1]], [.2, .3])
        cache.arrays['rowGame'][0] = 1
        with self.assertRaisesRegex(ValueError, 'whole-world owner'):
            joint.phase_row_weights(cache)


class WarmTests(unittest.TestCase):
    def setUp(self):
        self.purchase = ['z', 'a']
        self.joint = ['card.x', 'purchase.a', 'purchase.z']
        self.original = benchmark.Critic((64,), 19, inputs=2).state_dict()

    def test_mapping_preserves_original_purchase_order(self):
        np.testing.assert_array_equal(joint.purchase_columns(self.purchase, self.joint), [2, 1])

    def test_missing_purchase_column_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'every original purchase input'):
            joint.purchase_columns(self.purchase, ['card.x', 'purchase.a'])

    def test_unsorted_joint_names_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'sorted unique'):
            joint.purchase_columns(self.purchase, list(reversed(self.joint)))

    def test_warm_purchase_columns_are_exact_copies(self):
        expanded = joint.expand_warm_state(self.original, self.purchase, self.joint, 23)
        self.assertTrue(torch.equal(expanded['layers.0.weight'][:, [2, 1]], self.original['layers.0.weight']))

    def test_warm_noninput_parameters_are_exact_copies(self):
        expanded = joint.expand_warm_state(self.original, self.purchase, self.joint, 23)
        for key in ('layers.0.bias', 'layers.2.weight', 'layers.2.bias'):
            self.assertTrue(torch.equal(expanded[key], self.original[key]), key)

    def test_warm_card_columns_retain_its_fresh_expanded_initializer(self):
        fresh = benchmark.Critic((64,), 23, inputs=3).state_dict()
        expanded = joint.expand_warm_state(self.original, self.purchase, self.joint, 23)
        self.assertTrue(torch.equal(expanded['layers.0.weight'][:, 0], fresh['layers.0.weight'][:, 0]))

    def test_warm_expansion_does_not_mutate_source(self):
        before = {key: value.clone() for key, value in self.original.items()}
        expanded = joint.expand_warm_state(self.original, self.purchase, self.joint, 23)
        expanded['layers.0.bias'].add_(1)
        self.assertTrue(all(torch.equal(value, before[key]) for key, value in self.original.items()))

    def test_purchase_only_predictions_preserve_fp32_function(self):
        expanded = joint.expand_warm_state(self.original, self.purchase, self.joint, 23)
        receipt = joint.warm_purchase_parity(self.original, expanded, self.purchase, self.joint,
                                           [[0., 0.], [1., -1.], [.33, -.25]])
        self.assertLessEqual(receipt['maximumAbsoluteError'], 2e-6)

    def test_wrong_output_bias_fails_purchase_parity(self):
        expanded = joint.expand_warm_state(self.original, self.purchase, self.joint, 23)
        expanded['layers.2.bias'].add_(.1)
        with self.assertRaisesRegex(ValueError, 'parity exceeded'):
            joint.warm_purchase_parity(self.original, expanded, self.purchase, self.joint, [[1., -1.]])

    def test_non_fp32_source_is_rejected(self):
        original = {key: value.double() for key, value in self.original.items()}
        with self.assertRaisesRegex(ValueError, 'finite FP32'):
            joint.expand_warm_state(original, self.purchase, self.joint, 23)


class SelectionTests(unittest.TestCase):
    def receipts(self, warm=.2, fresh=.3):
        return {name: dict(testInspected=False, best=dict(validationMse=value, epoch=1))
                for name, value in [('warm', warm), ('fresh', fresh)]}

    def test_lower_validation_error_selects_arm(self):
        self.assertEqual(joint.selected_arm(self.receipts(warm=.4)), 'fresh')

    def test_exact_validation_tie_selects_warm(self):
        self.assertEqual(joint.selected_arm(self.receipts(fresh=.2)), 'warm')

    def test_test_inspected_receipt_is_rejected(self):
        receipts = self.receipts()
        receipts['warm']['testInspected'] = True
        with self.assertRaisesRegex(ValueError, 'validation-only'):
            joint.selected_arm(receipts)

    def test_card_constant_is_equal_training_world_mean(self):
        cache = DenseCache([[0, 0, 0], [0], [1], [], [0]], [0., 1., .9, .9, .9])
        value, games = joint.training_card_mean(cache,
            corpus(['training', 'training', 'training', 'training', 'test']))
        self.assertEqual((value, games), (.5, 2))

    def test_validation_and_test_rewards_cannot_change_card_constant(self):
        cache = DenseCache([[0], [0], [0]], [.2, .8, .9])
        declaration = corpus(['training', 'validation', 'test'])
        before = joint.training_card_mean(cache, declaration)
        cache.arrays['reward'][1:] = [0., 0.]
        self.assertEqual(joint.training_card_mean(cache, declaration), before)

    def test_stitched_baseline_never_passes_card_rows_to_purchase_critic(self):
        cache = DenseCache([[0, 1], [0]], [.2, .8])
        cache.states = np.asarray([[99, 99, 99], [0, .25, -.5], [88, 88, 88]], dtype=np.float32)
        critic = benchmark.Critic((64,), 9, inputs=2)
        observed = []
        handle = critic.register_forward_pre_hook(lambda _, args: observed.append(args[0].clone()))
        actual = joint.stitched_predictions(critic, .4, cache, [0, 1], ['z', 'a'],
            ['card.x', 'purchase.a', 'purchase.z'], torch.device('cpu'), lambda: None)
        handle.remove()
        self.assertEqual(actual[[0, 2]].tolist(), [.4, .4])
        self.assertEqual(len(observed), 1)
        np.testing.assert_array_equal(observed[0].numpy(), [[-.5, .25]])
        self.assertEqual(cache.read_rows, [1])

    def test_final_metrics_pair_whole_worlds_and_retain_empty_worlds(self):
        cache = DenseCache([[0, 0, 1], [1], []], [0., 0., .9])
        selected = np.asarray([0., 0., 0., 0.])
        baseline = np.asarray([1., 1., 1., 1.])
        metrics, comparisons, records = joint.evaluate_selected(selected, baseline, cache,
            corpus(['test'] * 3), replace(data.Config(), bootstrapReplicates=2000))
        self.assertEqual(metrics['selected']['phaseBalanced']['representedWorlds'], 2)
        self.assertEqual(metrics['selected']['phaseBalanced']['excludedWorlds'], 1)
        self.assertEqual(comparisons['phaseBalanced']['lower95'], -1.)
        self.assertEqual(comparisons['phaseBalanced']['replicates'], 2000)
        self.assertIsNone(records[2]['mse']['selected']['phaseBalanced'])


class FitterSeamTests(unittest.TestCase):
    def config(self, **changes):
        return replace(data.Config(), epochs=1, gamesPerBatch=2, **changes)

    def fit(self, cache, declaration, directory, *, weighted=True, **kwargs):
        if weighted:
            kwargs.update(input_count=cache.states.shape[1],
                training_row_weights=joint.phase_row_weights(cache),
                validation_errors=joint.phase_balanced_errors)
        return benchmark.fit_one('synthetic', (64,), 31, cache, declaration, Path(directory),
                                 self.config(), lambda: None, torch.device('cpu'), **kwargs)

    def test_default_input_and_explicit_legacy_weights_produce_same_model(self):
        cache = DenseCache([[1, 1], [1], [1]], [.2, .8, .5], inputs=3556)
        declaration = corpus(['training', 'training', 'validation'])
        with TemporaryDirectory() as left, TemporaryDirectory() as right:
            default, default_receipt = self.fit(cache, declaration, left, weighted=False)
            explicit, explicit_receipt = self.fit(cache, declaration, right)
            self.assertTrue(all(torch.equal(value, explicit.state_dict()[key])
                                for key, value in default.state_dict().items()))
            self.assertEqual(default_receipt['best']['trainingMse'], explicit_receipt['best']['trainingMse'])
            self.assertEqual(default_receipt['best']['validationMse'], explicit_receipt['best']['validationMse'])

    def test_one_training_step_uses_equal_phase_loss(self):
        cache = DenseCache([[0, 0, 0, 1], [1], [0]], [.2, .8, .5])
        declaration = corpus(['training', 'training', 'validation'])
        reference = benchmark.Critic((64,), 31, inputs=3)
        optimizer = torch.optim.Adam(reference.parameters(), lr=.001, betas=(.9, .999),
                                     eps=1e-8, weight_decay=0, foreach=False, fused=False)
        rows = cache.game_rows(benchmark.order([0, 1], 0, self.config().orderSeed))
        values = reference(torch.from_numpy(cache.dense(rows)))
        owners = cache.arrays['rowGame'][rows]
        loss = ((values-torch.tensor(cache.arrays['reward'][owners], dtype=torch.float32)).square()
                * torch.tensor(joint.phase_row_weights(cache)[rows], dtype=torch.float32)).sum()/2
        loss.backward()
        optimizer.step()
        with TemporaryDirectory() as directory:
            actual, _ = self.fit(cache, declaration, directory)
            self.assertTrue(all(torch.equal(value, actual.state_dict()[key])
                                for key, value in reference.state_dict().items()))

    def test_zero_row_training_world_does_not_dilute_step(self):
        cache = DenseCache([[0], [], [1]], [.2, .99, .5])
        complete = corpus(['training', 'training', 'validation'])
        represented = dict(games=[complete['games'][0], complete['games'][2]])
        with TemporaryDirectory() as left, TemporaryDirectory() as right:
            actual, receipt = self.fit(cache, complete, left)
            reference, _ = self.fit(cache, represented, right)
            self.assertTrue(all(torch.equal(value, reference.state_dict()[key])
                                for key, value in actual.state_dict().items()))
            self.assertTrue(np.isfinite(receipt['best']['trainingMse']))

    def test_validation_changes_cannot_change_single_epoch_training_coefficients(self):
        cache = DenseCache([[0, 1], [0], [1]], [.2, .8, .5])
        declaration = corpus(['training', 'validation', 'test'])
        with TemporaryDirectory() as left, TemporaryDirectory() as right:
            before, old_receipt = self.fit(cache, declaration, left)
            cache.arrays['reward'][1] = .05
            cache.states[2] *= -1
            after, new_receipt = self.fit(cache, declaration, right)
            self.assertTrue(all(torch.equal(value, after.state_dict()[key])
                                for key, value in before.state_dict().items()))
            self.assertNotEqual(old_receipt['best']['validationMse'], new_receipt['best']['validationMse'])

    def test_test_rows_are_never_materialized_during_fit(self):
        cache = DenseCache([[0], [1], [0]], [.2, .8, .5])
        with TemporaryDirectory() as directory:
            self.fit(cache, corpus(['training', 'validation', 'test']), directory)
        self.assertNotIn(2, cache.read_rows)

    def test_exact_epoch_tie_retains_earlier_epoch(self):
        cache = DenseCache([[0], [1]], [.2, .8])
        with TemporaryDirectory() as directory:
            _, receipt = benchmark.fit_one('tie', (64,), 31, cache,
                corpus(['training', 'validation']), Path(directory),
                replace(data.Config(), epochs=2), lambda: None, torch.device('cpu'), input_count=3,
                training_row_weights=joint.phase_row_weights(cache),
                validation_errors=lambda predictions, cache, games: np.ones(len(games)))
        self.assertEqual(receipt['best']['epoch'], 1)

    def test_unnormalized_world_weights_are_rejected(self):
        cache = DenseCache([[0], [1]], [.2, .8])
        with TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, 'total one'):
            self.fit(cache, corpus(['training', 'validation']), directory, weighted=False,
                     input_count=3, training_row_weights=np.asarray([.5, 1.]))


class SyntheticPipelineTests(unittest.TestCase):
    def test_two_fits_select_before_test_and_never_predict_nonselected_arm(self):
        # The production dimensions/splits are exercised, but almost all synthetic worlds have
        # no decisions. Only six invented rows are fitted; no retained files enter this test.
        phases = [[] for _ in range(4096)]
        for game, items in [(0, [0]), (1, [1]), (3072, [0, 1]), (3584, [0]), (3585, [1])]:
            phases[game] = items
        cache = DenseCache(phases, np.linspace(0, 1, 4096), inputs=7241)
        purchase_names = [f'feature.{index:04}' for index in range(3556)]
        joint_names = sorted([f'card.feature.{index:04}' for index in range(3685)]
                             + ['purchase.' + name for name in purchase_names])
        cache.feature_names = joint_names
        declaration = dict(corpus(['training']*3072 + ['validation']*512 + ['test']*512),
                           stateFeatureNames=joint_names, dictionarySha256='synthetic-dictionary')
        actual_predict = benchmark.predict
        test_models = []
        with TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint = root/'synthetic.pt'
            torch.save(benchmark.Critic((64,), 71).state_dict(), checkpoint)
            output = root/'fit'

            def inspect_prediction(model, cache, games, device, guard, **kwargs):
                if any(game >= 3584 for game in games):
                    self.assertTrue((output/'selected.json').is_file())
                    test_models.append(model)
                return actual_predict(model, cache, games, device, guard, **kwargs)

            with patch.object(joint, 'REFRESHED_SHA', data.sha(checkpoint)), \
                    patch.object(benchmark, 'predict', side_effect=inspect_prediction):
                report = joint.fit(cache, declaration, output, data.Config(), lambda: None,
                    torch.device('cpu'), purchase_names=purchase_names, joint_names=joint_names,
                    critic_path=checkpoint)
            self.assertEqual(len(test_models), 1)
            self.assertEqual(report['nonselectedTestEvaluations'], 0)
            self.assertEqual(report['testWorlds'], 512)
            self.assertEqual(report['models']['selected']['phaseBalanced']['excludedWorlds'], 510)
            self.assertEqual(data.read(output/'finished.json')['actualFits'], 2)
            self.assertFalse(report['qualificationPassed'])


if __name__ == '__main__':
    unittest.main()
