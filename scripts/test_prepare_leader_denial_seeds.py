from collections import defaultdict
import unittest
from unittest.mock import patch

import prepare_leader_denial_seeds as audit


class LeaderDenialMetadataTests(unittest.TestCase):
    def test_prior_actual_values_force_a_new_prefix(self):
        with patch.object(audit, 'PREFIX', 100):
            banks, probes, _ = audit.reserve({-1000000000 + 100}, set())
        self.assertEqual(banks['development']['nativeOffset'], 101)
        self.assertEqual(len(probes), 2)

    def test_future_reserved89offsets_are_protected_conservatively(self):
        with patch.object(audit, 'PREFIX', 100):
            banks, probes, rejected = audit.reserve(set(), {100})
        self.assertEqual(banks['development']['nativeOffset'], 101)
        self.assertEqual(probes[0]['collisions'], 512)
        self.assertEqual(rejected, [100, 101, 102])

    def test_all_three_full_banks_are_mutually_distinct(self):
        with patch.object(audit, 'PREFIX', 100):
            banks, probes, rejected = audit.reserve(set(), set())
        values = [value for bank in banks.values() for value in bank['seeds']]
        self.assertEqual(len(values), 4608)
        self.assertEqual(len(set(values)), 4608)
        self.assertEqual(len(probes), 1)
        self.assertEqual(rejected, [])

    def test_numeric_declarations_are_protected_as_actual_values(self):
        residues = defaultdict(list)
        self.assertEqual(audit.arithmetic.collisions([89001000, 89001001], {89001000}, residues), [89001000])


if __name__ == '__main__':
    unittest.main()
