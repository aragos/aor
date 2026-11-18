"""Constructed prefix metadata; no simulations, model access or random draws."""
import unittest
from verify_mac_search_refinements import partition


class ResourcePrefixTest(unittest.TestCase):
    def test_original_order_and_every_unused_value_are_preserved(self):
        active, unused = partition({'mixed': {'count': 4, 'seeds': [8, 3, 7, 2]}}, {'mixed': 2})
        self.assertEqual({'mixed': [8, 3]}, active)
        self.assertEqual({'mixed': [7, 2]}, unused)

    def test_top_up_past_original_bank_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'without top-up'):
            partition({'mixed': {'count': 2, 'seeds': [8, 3]}}, {'mixed': 3})

    def test_omitting_a_reserved_bank_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Every original bank'):
            partition({'mixed': {'count': 1, 'seeds': [8]},
                'homogeneous': {'count': 1, 'seeds': [3]}}, {'mixed': 1})


if __name__ == '__main__':
    unittest.main()
