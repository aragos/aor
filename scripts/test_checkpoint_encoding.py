"""Prefix checkpoint encoding preserves numerical progress and atomic resume semantics."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import purchase_policy as backend
from purchase_policy_toy import SCHEMA
from test_purchase_policy import collection


def pretty_checkpoint(path, payload):
    """The frozen checkpoint writer, independent of the new formatting selection."""
    backend.atomic_json(path, {"sha256": backend.digest(payload), "payload": payload})


class CheckpointEncodingTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_prefix_compact_payload_and_checksum_equal_frozen_writer(self):
        payload = {"schemaVersion": 2, "model": [0., -0., 1e-300, 1e300],
                   "optimizer": {"state": {"0": {"step": 128.}}}}
        old, new = self.root/'old.json', self.root/'new.json'
        pretty_checkpoint(old, payload)
        backend.save_checkpoint(new, payload)
        self.assertEqual(json.loads(old.read_bytes()), json.loads(new.read_bytes()))
        self.assertEqual(backend.canonical(backend.read_checkpoint(old)),
                         backend.canonical(backend.read_checkpoint(new)))
        self.assertLess(new.stat().st_size, old.stat().st_size)
        self.assertEqual(new.read_bytes().count(b'\n'), 1)

    def test_legacy_checkpoint_bytes_remain_exact(self):
        payload = {"schemaVersion": 1, "model": [-0., .25], "epoch": 3}
        old, new = self.root/'old.json', self.root/'new.json'
        pretty_checkpoint(old, payload)
        backend.save_checkpoint(new, payload)
        self.assertEqual(old.read_bytes(), new.read_bytes())

    def test_failed_compact_publication_preserves_previous_checkpoint(self):
        path = self.root/'checkpoint.json'
        backend.save_checkpoint(path, {"schemaVersion": 2, "acceptedSteps": 8})
        before = path.read_bytes()
        with patch.object(backend.os, 'replace', side_effect=OSError('publication interrupted')):
            with self.assertRaisesRegex(OSError, 'publication interrupted'):
                backend.save_checkpoint(path, {"schemaVersion": 2, "acceptedSteps": 16})
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(sorted(item.name for item in self.root.iterdir()), ['checkpoint.json'])

    def prepare_update(self):
        model = self.root/'model.json'
        backend.atomic_json(model, backend.initialize(SCHEMA, 17))
        data = self.root/'data'
        collection(data, model, games=19)
        config = backend.UpdateConfig(epochs=2, batchSize=1, maxBehaviorKl=.2)
        return model, data, config

    def assert_progress_equal(self, first, second):
        left, right = (backend.read_checkpoint(path/'checkpoint.json')
                       for path in (first, second))
        for key in ('model', 'optimizer', 'history', 'cursor', 'epoch', 'acceptedSteps',
                    'acceptedBlocks', 'stopReason'):
            self.assertEqual(left[key], right[key], key)
        self.assertEqual((first/'policy.json').read_bytes(), (second/'policy.json').read_bytes())

    def test_two_updates_and_adam_carry_equal_frozen_pretty_publication(self):
        model, data, config = self.prepare_update()
        old, new = self.root/'old', self.root/'new'
        options = dict(update_mode=backend.CHECKED_PREFIX_MODE)
        with patch.object(backend, 'save_checkpoint', side_effect=pretty_checkpoint):
            backend.update(model, data, config, old, **options)
        backend.update(model, data, config, new, **options)
        self.assert_progress_equal(old, new)
        next_data = self.root/'next-data'
        collection(next_data, old/'policy.json', games=19)
        old_next, new_next = self.root/'old-next', self.root/'new-next'
        with patch.object(backend, 'save_checkpoint', side_effect=pretty_checkpoint):
            backend.update(old/'policy.json', next_data, config, old_next,
                           optimizer_from=old/'checkpoint.json', **options)
        backend.update(old/'policy.json', next_data, config, new_next,
                       optimizer_from=new/'checkpoint.json', **options)
        self.assert_progress_equal(old_next, new_next)

    def test_old_pretty_partial_checkpoint_resumes_without_replaying_committed_block(self):
        model, data, config = self.prepare_update()
        continuous, resumed = self.root/'continuous', self.root/'resumed'
        options = dict(update_mode=backend.CHECKED_PREFIX_MODE)
        backend.update(model, data, config, continuous, **options)
        def interrupted(path, payload):
            pretty_checkpoint(path, payload)
            if payload['acceptedSteps'] == 8:
                raise RuntimeError('interrupted after publication')
        with patch.object(backend, 'save_checkpoint', side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError, 'after publication'):
                backend.update(model, data, config, resumed, **options)
        before = backend.read_checkpoint(resumed/'checkpoint.json')
        starts = []
        original = backend.fit_checked_block
        def tracked(*args, **kwargs):
            starts.append(dict(args[-2]))
            return original(*args, **kwargs)
        with patch.object(backend, 'fit_checked_block', side_effect=tracked):
            backend.update(model, data, config, resumed, resume=True, **options)
        self.assertEqual(starts[0], before['cursor'])
        self.assertEqual(backend.read_checkpoint(resumed/'checkpoint.json')['deadlineEpochSeconds'],
                         before['deadlineEpochSeconds'])
        self.assert_progress_equal(continuous, resumed)


if __name__ == '__main__':
    unittest.main()
