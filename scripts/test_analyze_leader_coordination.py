import unittest
from analyze_leader_coordination import analyze_root, origin_interval


def fixture():
    target = {'advance': 'INSTITUTIONAL_RESEARCH', 'leaderCardId': 'nicolaus-copernicus', 'priceAtRoot': 80, 'savingAtRoot': 20}
    root = {'world': 5, 'rootIndex': 0, 'target': target, 'command': 'play hand-1'}
    arms = [[{'action': action, 'replicate': replicate, 'split': 'evaluation', 'target': target,
              'command': 'play hand-1', 'terminal': {'reward': action / 10, 'winShare': 0, 'rankReward': action / 2},
              'economics': {'rootTurnPurchases': [], 'nextBoundary': {'cash': 80, 'markers': 5}},
              'coordination': {'ended': 'INSUFFICIENT_CASH', 'decisions': []} if action else None}
             for replicate in range(16)] for action in range(2)]
    return root, arms


class CoordinationAnalysisTest(unittest.TestCase):
    def test_aborted_options_remain_in_the_paired_estimate(self):
        root, arms = fixture()
        result = analyze_root(arms, root)
        self.assertEqual(16, result['optionEndReasons']['INSUFFICIENT_CASH'])
        self.assertAlmostEqual(.1, result['evaluation']['reward']['mean'])
        self.assertEqual(16, result['evaluation']['reward']['samples'])

    def test_changed_root_command_is_rejected(self):
        root, arms = fixture()
        arms[1][0]['command'] = 'pass'
        with self.assertRaises(ValueError):
            analyze_root(arms, root)

    def test_selection_split_is_rejected(self):
        root, arms = fixture()
        arms[1][0]['split'] = 'selection'
        with self.assertRaises(ValueError):
            analyze_root(arms, root)

    def test_missing_tail_is_rejected(self):
        root, arms = fixture()
        arms[1].pop()
        with self.assertRaises(ValueError):
            analyze_root(arms, root)

    def test_across_origin_uncertainty_uses_origin_count(self):
        result = origin_interval([-.1, .1] * 8)
        self.assertEqual(16, result['origins'])
        self.assertGreater(result['acrossOriginApprox95'][1], .05)

    def test_coverage_floor_cannot_be_bypassed(self):
        with self.assertRaises(ValueError):
            origin_interval([.1] * 15)


if __name__ == '__main__':
    unittest.main()
