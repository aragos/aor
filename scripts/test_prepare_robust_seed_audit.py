from collections import defaultdict
import unittest
from unittest.mock import patch

import prepare_robust_seed_audit as audit


class FreshSeedMetadataTest(unittest.TestCase):
    def test_actual_values_are_rejected_even_without_a_conservative_offset(self):
        expected = audit.WORLD_BASE + 50 + audit.WORLD_STEP
        self.assertEqual(audit.conflicts(50, 3, {expected}, defaultdict(list)), [expected])

    def test_conservative_range_keeps_both_boundaries(self):
        offset = 200
        old = offset - 9998 * audit.WORLD_STEP
        residues = defaultdict(list, {offset % audit.WORLD_STEP: [old]})
        self.assertEqual(audit.conflicts(offset, 3, set(), residues), audit.worlds(offset, 2))

    def test_three_scientific_banks_have_no_cross_bank_reuse(self):
        with patch.object(audit, 'PREFIX', 100), patch.object(
                audit, 'COUNTS', {'development': 2, 'mixed': 3, 'homogeneous': 3}):
            banks, probes, rejected = audit.reserve(set(), set())
        values = [value for bank in banks.values() for value in bank['seeds']]
        self.assertEqual(len(values), len(set(values)))
        self.assertEqual(len(probes), 1)
        self.assertEqual(rejected, [])

    def test_rejected_probe_offsets_remain_conservatively_held(self):
        with patch.object(audit, 'PREFIX', 100), patch.object(
                audit, 'COUNTS', {'development': 2, 'mixed': 3, 'homogeneous': 3}):
            banks, probes, rejected = audit.reserve({audit.WORLD_BASE + 100}, set())
        self.assertEqual(banks['development']['nativeOffset'], 101)
        self.assertEqual(len(probes), 2)
        self.assertEqual(rejected, [100, 101, 102])


if __name__ == '__main__':
    unittest.main()
