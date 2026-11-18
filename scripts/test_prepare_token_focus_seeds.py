"""Constructed metadata contracts only; no RNG objects, models, forwards or games."""
from dataclasses import replace
import unittest

import prepare_token_focus_seeds as focus
import seed_reservation_dag as dag


def method():
    return dict(kind='token-focus-step-prospective-method-1', study=96, localOnly=True,
        remoteUseAuthorized=False, globalDeadlineEpoch=1795020399.0,
        trainingWorlds=16, holdoutWorlds=128, freshGameWorlds=144, freshNumericStreams=2,
        maximumTerminalExecutions=1560, generations=1, directions=16, trainableHead='TOKEN',
        trainingReplayIndices={'PLUS_00': [0, 15], 'MINUS_00': [0, 15]},
        holdoutReplayIndices=[0, 42, 85, 127])


class TokenFocusMetadataTest(unittest.TestCase):
    def test_approved_training_and_holdout_declarations_are_valid(self):
        self.assertIsNone(focus.validate_method(method()))

    def test_extra_training_worlds_are_rejected(self):
        changed = method()
        changed['trainingWorlds'] = 17
        with self.assertRaisesRegex(ValueError, 'exactly144'):
            focus.validate_method(changed)

    def test_reproduction_does_not_allocate_new_worlds(self):
        changed = method()
        changed['holdoutReplayIndices'] = [0, 42, 85, 128]
        with self.assertRaisesRegex(ValueError, 'exact approved'):
            focus.validate_method(changed)

    def test_deadline_cannot_be_extended(self):
        changed = method()
        changed['globalDeadlineEpoch'] += 1
        with self.assertRaisesRegex(ValueError, 'user deadline'):
            focus.validate_method(changed)

    def test_rejected_numerical_probe_holds_every_proposed_identifier(self):
        streams, probes, held = dag.reserve_numerical({500}, set(), ('projection', 'direction'), 500)
        self.assertEqual({'projection': 501, 'direction': 502}, streams)
        self.assertEqual([500, 501], held)
        self.assertEqual([1, 0], [probe['collisions'] for probe in probes])

    def test_numerical_identifiers_avoid_unopened_native_progressions(self):
        offset = 1000000000 + 500 - 3 * 8191
        streams, _, _ = dag.reserve_numerical(set(), {offset}, ('projection', 'direction'), 500)
        self.assertEqual({'projection': 501, 'direction': 502}, streams)

    def test_duplicate_numerical_names_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'mutually distinct'):
            dag.reserve_numerical(set(), set(), ('same', 'same'), 500)

    def test_exhausted_numerical_prefix_stops_after_one_bounded_window(self):
        with self.assertRaisesRegex(ValueError, 'single bounded metadata search window'):
            dag.reserve_numerical(set(range(500, 500+8192)), set(), ('a', 'b'), 500)

    def test_numerical_stream_names_cannot_overwrite_native_bank_names(self):
        with self.assertRaisesRegex(ValueError, 'distinct and nonempty'):
            replace(focus.RECIPE, numerical_names=('training', 'direction'))


if __name__ == '__main__':
    unittest.main()
