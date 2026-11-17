from collections import defaultdict
import unittest
from unittest.mock import patch

import prepare_robust_league_seeds as audit


class LeagueSeedMetadataTests(unittest.TestCase):
    def reserve(self, prior):
        with patch.object(audit, 'NATIVE_PREFIX', 100), patch.object(audit, 'NUMERIC_PREFIX', 2000):
            return audit.reserve(prior, set())

    def test_all_native_and_numeric_roles_are_distinct(self):
        banks, numerical, native_probes, numeric_probes, _, _ = self.reserve(set())
        values = [value for bank in banks.values() for value in bank['seeds']]
        values += [numerical['projectionSeed'], *numerical['directionSeeds']]
        self.assertEqual(len(values), 6153)
        self.assertEqual(len(set(values)), 6153)
        self.assertEqual(len(native_probes), 1)
        self.assertEqual(len(numeric_probes), 1)

    def test_conservative_projection_includes_only_its10000_positions(self):
        offset = 500
        residues = defaultdict(list, {offset % 8191: [offset]})
        values = audit.worlds(offset, 10001)
        self.assertEqual(audit.collisions(values, set(), residues), values[:10000])

    def test_rejected_native_prefixes_remain_held(self):
        banks, _, native_probes, _, held_offsets, _ = self.reserve({-1000000000 + 100})
        self.assertEqual(len(native_probes), 2)
        self.assertEqual(banks['generation-1']['nativeOffset'], 101)
        self.assertEqual(held_offsets, list(range(100, 111)))

    def test_rejected_numerical_prefixes_remain_held(self):
        _, numerical, _, numeric_probes, _, held = self.reserve({2000})
        self.assertEqual(numerical['projectionSeed'], 2001)
        self.assertEqual(len(numeric_probes), 2)
        self.assertEqual(held, list(range(2000, 2009)))


if __name__ == '__main__':
    unittest.main()
