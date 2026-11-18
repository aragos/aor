"""Constructed metadata only: no scientific RNG calls or simulations."""
import tempfile
import unittest
from pathlib import Path

import seed_reservation_dag as dag


class ReservationDagTest(unittest.TestCase):
    def test_differently_sized_banks_keep_original_order_and_distinct_worlds(self):
        counts = {'development': 3, 'mixed': 5, 'homogeneous': 5, 'reference': 5}
        banks, _, _ = dag.reserve(set(), set(), counts, 200)
        self.assertEqual([200, 201, 202, 203],
            [bank['nativeOffset'] for bank in banks.values()])
        self.assertEqual([3, 5, 5, 5], [len(bank['seeds']) for bank in banks.values()])
        values = [value for bank in banks.values() for value in bank['seeds']]
        self.assertEqual(18, len(set(values)))

    def test_exhausted_prefix_window_stops_instead_of_retrying_forever(self):
        with self.assertRaisesRegex(ValueError, 'single bounded metadata search window'):
            dag.reserve(set(), set(range(dag.STRIDE)), {'mixed': 1}, 1000)

    def test_exact_prior_integer_rejects_prefix_and_holds_every_probed_offset(self):
        banks, probes, rejected = dag.reserve({dag.BASE + 100}, set(), {'a': 2, 'b': 2}, 100)
        self.assertEqual([100, 101], rejected)
        self.assertEqual(101, banks['a']['nativeOffset'])
        self.assertEqual([1, 0], [probe['collisions'] for probe in probes])

    def test_expanded_unused_historical_worlds_are_protected(self):
        banks, _, _ = dag.reserve(set(), {100}, {'a': 2}, 100 + 5 * dag.STRIDE)
        self.assertEqual(101 + 5 * dag.STRIDE, banks['a']['nativeOffset'])

    def test_compact_parent_carries_numeric_and_rejected_reservations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old = root / 'old.json'
            dag.write(old, dict(reservedSeeds=[7, 8], conservativelyExpandedOffsets=[10],
                seedBanks={'projection': {'seeds': [9]}}, heldRejectedNumericalValues=[11]))
            parent = root / 'parent.json'
            dag.write(parent, dict(protectionStorage='parent-dag-1',
                carriedForwardAudit=dag.subject(old), priorActualValues=4,
                priorActualValuesSha256=dag.values_digest({7, 8, 9, 11}),
                inheritedConservativeOffsets=[10], newlyProtectedProbeOffsets=[20],
                seedBanks={'mixed': {'nativeOffset': 30, 'seeds': [dag.BASE + 30]}}))
            values, offsets = dag.protection(parent, dag.sha(parent))
            self.assertEqual({7, 8, 9, 11, dag.BASE + 30}, values)
            self.assertEqual({10, 20, 30}, offsets)

    def test_changed_parent_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'old.json'
            dag.write(path, dict(reservedSeeds=[], conservativelyExpandedOffsets=[], seedBanks={}))
            original = dag.sha(path)
            path.write_bytes(b'{}\n')
            with self.assertRaisesRegex(ValueError, 'frozen subject changed'):
                dag.protection(path, original)


if __name__ == '__main__':
    unittest.main()
