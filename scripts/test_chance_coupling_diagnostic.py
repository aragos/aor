import copy
import unittest

import chance_coupling_diagnostic as diagnostic
from card_policy_coupling_analysis import chance_cost_statistics, moments


class ChanceCouplingTest(unittest.TestCase):
    def metadata(self):
        return [dict(index=i, split='TRAINING', requestedEpoch=1+i%3, seat=(i//3)%4,
                     status='COLLECTED', eligiblePair=True)
                for i in range(512)]

    def world(self, coupled=True, keyed_cost=2.0):
        f = [.5 + .2*(-1)**i for i in range(16)]
        a = [.5 - .2*(-1)**i for i in range(16)]
        return dict(LEGACY=dict(reference=f, alternative=a, cpuSeconds=[2.0]*16),
                    LOGICAL_REQUEST_1=dict(reference=f, alternative=f if coupled else [.5]*16,
                                           cpuSeconds=[keyed_cost]*16))

    def test_selection_is_training_only(self):
        rows = self.metadata()
        rows[0]['split'] = 'VALIDATION'
        with self.assertRaises(ValueError):
            diagnostic.select_roots(rows)

    def test_selection_spreads_epochs_and_seats(self):
        rows = self.metadata()
        chosen = diagnostic.select_roots(rows)
        self.assertEqual(len(set(chosen)), 32)
        self.assertEqual({rows[i]['requestedEpoch'] for i in chosen}, {1, 2, 3})
        self.assertEqual({rows[i]['seat'] for i in chosen}, {0, 1, 2, 3})

    def test_selection_ignores_labels(self):
        rows = self.metadata()
        changed = [dict(row, alternatives=[{'reward': i}])
                   for i,row in enumerate(rows)]
        self.assertEqual(diagnostic.select_roots(rows), diagnostic.select_roots(changed))

    def test_unreached_requests_and_missing_pairs_are_excluded(self):
        rows = self.metadata()
        rows[0]['status'] = 'SKIPPED'
        rows[1]['eligiblePair'] = False
        chosen = diagnostic.select_roots(rows)
        self.assertNotIn(0, chosen)
        self.assertNotIn(1, chosen)

    def test_insufficient_eligible_roots_reject_before_sampling(self):
        rows = self.metadata()
        for row in rows[31:]:
            row['eligiblePair'] = False
        with self.assertRaises(ValueError):
            diagnostic.select_roots(rows)

    def test_selection_is_independent_of_input_order(self):
        rows = self.metadata()
        self.assertEqual(diagnostic.select_roots(rows), diagnostic.select_roots(rows[::-1]))

    def test_nearest_proposal_uses_canonical_tie_break(self):
        origin = dict(observation=dict(baselineIndex=0, actions=[dict(command=f'buy-tokens {n}')
                      for n in (16, 17, 15, 0)]), proposedIndices=[0, 1, 2, 3])
        self.assertEqual(diagnostic.neighbor(origin), 2)

    def test_neighbor_never_invents_missing_bid(self):
        origin = dict(observation=dict(baselineIndex=0, actions=[dict(command=f'buy-tokens {n}')
                      for n in (15, 16, 14, 0)]), proposedIndices=[0, 1, 3])
        self.assertEqual(diagnostic.neighbor(origin), 1)

    def test_zero_difference_coupling_passes_cost_gate(self):
        result = chance_cost_statistics([self.world() for _ in range(32)], 123, 500)
        self.assertEqual(result['primary']['ratio'], 0)
        self.assertTrue(result['primary']['gatePassed'])

    def test_cost_can_cancel_variance_improvement(self):
        result = chance_cost_statistics([self.world(False, 8.0) for _ in range(32)], 123, 500)
        self.assertAlmostEqual(result['primary']['ratio'], 1)
        self.assertFalse(result['primary']['gatePassed'])

    def test_insufficient_roots_cannot_pass(self):
        result = chance_cost_statistics([self.world() for _ in range(23)], 123, 500)
        self.assertFalse(result['primary']['gatePassed'])

    def test_zero_denominator_is_not_dropped(self):
        row = self.world()
        row['LEGACY']['alternative'] = row['LEGACY']['reference']
        result = chance_cost_statistics([copy.deepcopy(row) for _ in range(32)], 123, 500)
        self.assertIsNone(result['primary']['ratio'])
        self.assertIsNone(result['primary']['upper95'])
        self.assertEqual(result['primary']['undefinedBootstrapDraws'], 500)
        self.assertFalse(result['primary']['gatePassed'])

    def test_wrong_stream_count_rejects(self):
        row = self.world()
        row['LEGACY']['reference'] = row['LEGACY']['reference'][:8]
        with self.assertRaises(ValueError):
            chance_cost_statistics([row], 123, 500)

    def test_legacy_moments_default_remains_eight(self):
        result = moments(list(range(8)), list(range(8)))
        self.assertEqual(result['varD'], 0)
        with self.assertRaises(ValueError):
            moments(list(range(16)), list(range(16)))


if __name__ == '__main__':
    unittest.main()
