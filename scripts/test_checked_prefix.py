"""Durable minibatch-prefix acceptance without changing the legacy epoch optimizer."""

from contextlib import redirect_stdout
import io
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import purchase_policy as backend
from purchase_policy_toy import SCHEMA
from test_purchase_policy import collection


class CheckedPrefixTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model = backend.initialize(SCHEMA, 17)
        self.model_path = self.root / 'model.json'
        backend.atomic_json(self.model_path, self.model)
        self.data = self.root / 'data'
        collection(self.data, self.model_path, games=19)
        self.rows, _ = backend.read_collection(self.data, self.model_path, self.model)
        self.config = backend.UpdateConfig(epochs=2, batchSize=1, maxBehaviorKl=.2)
        self.cursor = {'epoch': 0, 'minibatchOffset': 0}

    def actors(self):
        policy = backend.PurchasePolicy(self.model)
        optimizer = torch.optim.Adam(policy.parameters(), lr=self.config.learningRate,
                                     foreach=False)
        return policy, optimizer, backend.PurchasePolicy(self.model)

    def update(self, name, **kwargs):
        return backend.update(self.model_path, self.data, self.config, self.root / name,
                              update_mode=backend.CHECKED_PREFIX_MODE, **kwargs)

    def checkpoint(self, name):
        return backend.read_checkpoint(self.root / name / 'checkpoint.json')

    def assert_same_progress(self, first, second):
        for key in ('model', 'optimizer', 'history', 'cursor', 'epoch', 'acceptedSteps',
                    'acceptedBlocks', 'stopReason'):
            self.assertEqual(first[key], second[key], key)

    def test_blocks_and_short_epoch_remainder_equal_original_epoch_steps(self):
        legacy, old_optimizer, _ = self.actors()
        policy, optimizer, behavior = self.actors()
        backend.fit_epoch(legacy, old_optimizer, self.rows, self.config, 0, math.inf)
        cursor, counts = self.cursor, []
        while cursor['epoch'] == 0:
            block = backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                              self.config, cursor, math.inf)
            self.assertTrue(block['accepted'])
            counts.append(block['acceptedSteps'])
            cursor = block['nextCursor']
        self.assertEqual(counts, [8, 8, 3])
        self.assertEqual(cursor, {'epoch': 1, 'minibatchOffset': 0})
        self.assertEqual(policy.artifact(), legacy.artifact())
        self.assertEqual(backend.encode_optimizer(optimizer), backend.encode_optimizer(old_optimizer))

    def test_every_block_checks_all_rows_against_the_same_original_behavior(self):
        policy, optimizer, behavior = self.actors()
        metric = backend.behavior_metrics
        calls = []
        def observe(current, old, rows, size):
            self.assertIs(old, behavior)
            self.assertIs(rows, self.rows)
            calls.append(old.artifact())
            return metric(current, old, rows, size)
        with patch.object(backend, 'behavior_metrics', side_effect=observe):
            first = backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                              self.config, self.cursor, math.inf)
            backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                      self.config, first['nextCursor'], math.inf)
        self.assertEqual(calls, [self.model, self.model])

    def test_rejected_second_block_restores_exact_first_model_and_adam(self):
        policy, optimizer, behavior = self.actors()
        first = backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                          self.config, self.cursor, math.inf)
        before = policy.artifact(), backend.encode_optimizer(optimizer)
        with patch.object(backend, 'behavior_metrics', return_value={
                'behaviorKl': .3, 'priorKl': .3, 'valueMse': .1}):
            block = backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                              self.config, first['nextCursor'], math.inf)
        self.assertFalse(block['accepted'])
        self.assertEqual(block['stopReason'], 'behavior-kl-limit')
        self.assertEqual(block['acceptedSteps'], 0)
        self.assertEqual(block['attemptedSteps'], 8)
        self.assertEqual(block['nextCursor'], first['nextCursor'])
        self.assertEqual((policy.artifact(), backend.encode_optimizer(optimizer)), before)

    def test_kl_at_exact_cap_is_accepted(self):
        policy, optimizer, behavior = self.actors()
        with patch.object(backend, 'behavior_metrics', return_value={
                'behaviorKl': self.config.maxBehaviorKl, 'priorKl': .1, 'valueMse': .1}):
            block = backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                              self.config, self.cursor, math.inf)
        self.assertTrue(block['accepted'])

    def test_timeout_inside_block_restores_parameters_and_adam(self):
        policy, optimizer, behavior = self.actors()
        before = policy.artifact(), backend.encode_optimizer(optimizer)
        with patch.object(backend.time, 'monotonic', side_effect=[0, 2]):
            block = backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                              self.config, self.cursor, 1)
        self.assertEqual(block['stopReason'], 'time-budget')
        self.assertEqual(block['attemptedSteps'], 1)
        self.assertEqual(block['nextCursor'], self.cursor)
        self.assertEqual((policy.artifact(), backend.encode_optimizer(optimizer)), before)

    def test_exception_after_step_restores_parameters_and_adam(self):
        policy, optimizer, behavior = self.actors()
        before = policy.artifact(), backend.encode_optimizer(optimizer)
        step = backend.fit_minibatch
        def interrupt(*args):
            step(*args)
            raise KeyboardInterrupt('process interruption')
        with patch.object(backend, 'fit_minibatch', side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                backend.fit_checked_block(policy, optimizer, behavior, self.rows,
                                          self.config, self.cursor, math.inf)
        self.assertEqual((policy.artifact(), backend.encode_optimizer(optimizer)), before)

    def check_publication_interrupt(self, after, at_steps=8, previous_steps=0):
        self.update('continuous')
        save = backend.save_checkpoint
        def interrupt(path, payload):
            if payload.get('acceptedSteps') == at_steps:
                if after:
                    save(path, payload)
                raise RuntimeError('publication interruption')
            save(path, payload)
        with patch.object(backend, 'save_checkpoint', side_effect=interrupt):
            with self.assertRaisesRegex(RuntimeError, 'publication interruption'):
                self.update('resumed')
        saved = self.checkpoint('resumed')
        self.assertEqual(saved['acceptedSteps'], at_steps if after else previous_steps)
        original_deadline = saved['deadlineEpochSeconds']
        fit = backend.fit_checked_block
        starts = []
        def track(*args):
            starts.append(dict(args[-2]))
            return fit(*args)
        with patch.object(backend, 'fit_checked_block', side_effect=track):
            self.update('resumed', resume=True)
        self.assertEqual(starts[0], saved['cursor'])
        self.assertEqual(self.checkpoint('resumed')['deadlineEpochSeconds'], original_deadline)
        self.assert_same_progress(self.checkpoint('continuous'), self.checkpoint('resumed'))

    def test_interruption_before_publication_replays_only_uncommitted_block(self):
        self.check_publication_interrupt(after=False)

    def test_interruption_after_publication_never_replays_accepted_block(self):
        self.check_publication_interrupt(after=True)

    def test_interrupted_final_short_block_resumes_its_three_steps_exactly(self):
        self.check_publication_interrupt(after=False, at_steps=38, previous_steps=35)

    def test_zero_completed_epochs_can_retain_changed_model_and_adam(self):
        metrics = [{'behaviorKl': .01, 'priorKl': .01, 'valueMse': .1},
                   {'behaviorKl': .3, 'priorKl': .3, 'valueMse': .1}]
        with patch.object(backend, 'behavior_metrics', side_effect=metrics):
            report = self.update('partial')
        checkpoint = self.checkpoint('partial')
        self.assertEqual(report['schemaVersion'], 2)
        self.assertEqual(report['acceptedEpochs'], 0)
        self.assertEqual(report['acceptedSteps'], 8)
        self.assertEqual(report['acceptedBlocks'], 1)
        self.assertTrue(report['partialEpochAccepted'])
        self.assertEqual(report['stopReason'], 'behavior-kl-limit')
        self.assertNotEqual(checkpoint['model'], self.model)
        self.assertTrue(checkpoint['optimizer']['state'])

    def test_resume_does_not_renew_deadline_after_an_accepted_prefix(self):
        fit = backend.fit_checked_block
        def interrupt(*args):
            if args[-2]['minibatchOffset'] == 8:
                raise RuntimeError('interrupted')
            return fit(*args)
        with patch.object(backend, 'fit_checked_block', side_effect=interrupt):
            with self.assertRaises(RuntimeError):
                self.update('expired')
        before = self.checkpoint('expired')
        with patch.object(backend.time, 'time', return_value=1e12):
            report = self.update('expired', resume=True)
        after = self.checkpoint('expired')
        self.assertEqual(report['stopReason'], 'time-budget')
        self.assertEqual(report['acceptedSteps'], 8)
        for key in ('model', 'optimizer', 'cursor', 'deadlineEpochSeconds'):
            self.assertEqual(before[key], after[key])

    def test_resume_cannot_switch_update_mode(self):
        self.update('complete')
        with self.assertRaisesRegex(ValueError, 'Resume inputs'):
            backend.update(self.model_path, self.data, self.config, self.root / 'complete',
                           resume=True)

    def test_validator_rejects_cursor_or_counters_inconsistent_with_history(self):
        self.update('complete')
        identity = backend.read_json(self.root / 'complete/identity.json')
        checkpoint = self.checkpoint('complete')
        for key, value in [('cursor', self.cursor), ('acceptedSteps', 0), ('acceptedBlocks', True)]:
            changed = {**checkpoint, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                backend.validate_checked_prefix_checkpoint(changed, identity, self.rows)

    def test_legacy_checkpoint_can_warm_start_checked_prefix_adam(self):
        old = self.root / 'legacy'
        backend.update(self.model_path, self.data, self.config, old)
        self.assertEqual(backend.read_checkpoint(old / 'checkpoint.json')['schemaVersion'], 1)
        data = self.root / 'next'
        collection(data, old / 'policy.json', games=19)
        backend.update(old / 'policy.json', data, self.config, self.root / 'prefix',
                       optimizer_from=old / 'checkpoint.json',
                       update_mode=backend.CHECKED_PREFIX_MODE)
        first, second = self.checkpoint('legacy'), self.checkpoint('prefix')
        for key, state in second['optimizer']['state'].items():
            self.assertEqual(state['step']['tensor'],
                             first['optimizer']['state'][key]['step']['tensor'] + 38)
        self.assertEqual(second['acceptedSteps'], 38)

    def test_card_cli_publishes_explicit_prefix_checkpoint_and_summary(self):
        import card_policy as card
        from card_policy_toy import SCHEMA as CARD_SCHEMA
        from test_card_policy import collection as card_collection
        model_path = self.root / 'card.json'
        backend.atomic_json(model_path, card.initialize(CARD_SCHEMA, 17))
        data, output = self.root / 'card-data', self.root / 'card-fit'
        card_collection(data, model_path)
        with redirect_stdout(io.StringIO()):
            card.main(['update', '--model', str(model_path), '--source', str(data),
                       '--output', str(output), '--seed', '17', '--update-mode',
                       backend.CHECKED_PREFIX_MODE])
        report = backend.read_json(output / 'finished.json')
        self.assertEqual(report['updateMode'], backend.CHECKED_PREFIX_MODE)
        self.assertEqual(report['acceptedSteps'], 4)
        self.assertEqual(report['completedEpochs'], 4)

    def test_legacy_completed_update_refuses_even_one_block_per_epoch(self):
        import card_trajectory_experiment as driver
        self.update('complete')
        with self.assertRaisesRegex(ValueError, 'Legacy completion requires'):
            driver.completed_update(self.root / 'complete', {}, {}, self.model_path)

    def test_missing_checkpoint_after_block_start_preserves_evidence(self):
        with patch.object(backend, 'fit_checked_block', side_effect=RuntimeError('interrupted')):
            with self.assertRaises(RuntimeError):
                self.update('lost')
        (self.root / 'lost/checkpoint.json').unlink()
        marker = (self.root / 'lost/block-started.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'checkpoint is missing'):
            self.update('lost', resume=True)
        self.assertEqual((self.root / 'lost/block-started.json').read_bytes(), marker)
        self.assertFalse((self.root / 'lost/checkpoint.json').exists())

    def test_unsupported_mode_rejects_before_reading_data(self):
        with patch.object(backend, 'read_collection', side_effect=AssertionError('must not read')):
            with self.assertRaisesRegex(ValueError, 'Unsupported PPO'):
                backend.update(self.model_path, self.data, self.config, self.root / 'bad',
                               update_mode='unknown')


if __name__ == '__main__':
    unittest.main()
