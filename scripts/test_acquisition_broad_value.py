"""Synthetic identity, provenance and grouping tests; no retained experiment outcomes."""
import copy
import gzip
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

import acquisition_broad_value as broad
import acquisition_conditional_value as fixed
from test_acquisition_conditional_value import actor_input


def origin(chunk=1, local_index=0, routes=('EXPANSION',)):
    roots = []
    for route in routes:
        actor = actor_input()
        reference = 'place 2 paris'
        command = 'buy-expansion-card'
        if route == 'CASH':
            reference, command = 'pass', 'buy-card'
            actor['observation']['decision'] = 'buy-card'
            actor['observation']['candidates'][0]['command'] = command
            actor['observation']['candidates'][1]['command'] = reference
        roots.append(dict(status='COLLECTED', route=route, actorInput=actor,
                          provenance=dict(incumbentCommand=reference),
                          arms=[dict(role=role, command=choice, outcomes=[
                              dict(replicate=i, replayVerified=True,
                                   terminal=dict(continuationSeed=100 + i, reward=reward))
                              for i in range(2)])
                                for role, choice, reward in
                                [('BUY', command, .5), ('REFERENCE', reference, .25)]]))
    return dict(chunk=chunk, localIndex=local_index, globalIndex=(chunk - 1) * 256 + local_index,
                cohort='INCUMBENT' if chunk % 2 else 'RIVAL_ACQUISITION',
                request=dict(index=local_index), originalMemberSha256='a' * 64, roots=roots)


class BroadOriginTest(unittest.TestCase):
    def test_reused_local_indices_have_distinct_global_groups(self):
        first, _ = broad.validate_origin_identity(origin(1), 1, 0)
        second, _ = broad.validate_origin_identity(origin(2), 2, 0)
        self.assertEqual((0, 256), (first, second))
        self.assertNotEqual(first % fixed.FOLDS, second % fixed.FOLDS)

    def test_mismatched_cohort_or_global_index_is_rejected(self):
        for key, value in [('cohort', 'RIVAL_ACQUISITION'), ('globalIndex', 256)]:
            with self.subTest(key=key):
                row = origin()
                row[key] = value
                with self.assertRaisesRegex(ValueError, 'identity'):
                    broad.validate_origin_identity(row, 1, 0)

    def test_native_local_index_must_still_match(self):
        row = origin(2)
        row['request']['index'] = 256
        with self.assertRaisesRegex(ValueError, 'local'):
            broad.validate_origin_identity(row, 2, 0)

    def test_two_routes_share_one_world_weight_and_fold(self):
        rows, _, labels, _ = fixed.origin_rows(origin(2, routes=fixed.ROUTES), 256, 0, 'a' * 64)
        self.assertEqual([.5, .5], [row['weight'] for row in rows])
        self.assertEqual([256, 256], [row['index'] for row in rows])
        self.assertEqual([1, 1], [row['fold'] for row in rows])
        self.assertEqual([.25, .25], labels)

    def test_global_bookkeeping_does_not_change_features_or_targets(self):
        row = origin()
        _, first, targets, _ = fixed.origin_rows(row, 0, 0, 'a' * 64)
        altered = copy.deepcopy(row)
        altered.update(globalIndex=99999, gameSeed=1234, cohort='never-an-input')
        _, second, other_targets, _ = fixed.origin_rows(altered, 256, 0, 'b' * 64)
        np.testing.assert_array_equal(first, second)
        self.assertEqual(targets, other_targets)

    def test_paired_stream_mismatch_is_rejected(self):
        row = origin()
        row['roots'][0]['arms'][0]['outcomes'][0]['terminal']['continuationSeed'] = 999
        with self.assertRaisesRegex(ValueError, 'streams must match'):
            fixed.origin_rows(row, 0, 0, 'a' * 64)

    def test_missing_world_keeps_its_global_identity(self):
        rows, features, labels, missing = fixed.origin_rows(origin(2, routes=()), 256, 0, 'a' * 64)
        self.assertEqual(([], [], [], [256]), (rows, features, labels, missing))

    def test_compressed_chunks_preserve_all_2048_missing_worlds(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixtures = []
            for chunk in range(1, 9):
                path = Path(temporary) / f'{chunk}.json.gz'
                with gzip.open(path, 'wt') as stream:
                    json.dump([origin(chunk, i, routes=()) for i in range(256)], stream)
                fixtures.append(dict(chunk=chunk, path=str(path)))
            rows, _, _, missing = broad.load(dict(fixtures=fixtures))
        self.assertFalse(rows)
        self.assertEqual(list(range(2048)), missing)


if __name__ == '__main__':
    unittest.main()
