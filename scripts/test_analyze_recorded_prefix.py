import copy
import unittest

from analyze_recorded_prefix import analyze_root


class RecordedPrefixAnalysisTest(unittest.TestCase):
    def actions(self):
        return [[{"entry": 42, "action": action, "command": f"arm {action}", "replicate": i,
                  "split": "selection" if i < 16 else "evaluation",
                  "terminal": {"reward": (0.7 if i < 16 else 0.2) if action else 0.5,
                               "winShare": 0.0, "rankReward": 0.5}}
                 for i in range(32)] for action in range(2)]

    def test_selection_winner_can_lose_on_independent_evaluation(self):
        result = analyze_root(self.actions())
        self.assertEqual(result["selectedAction"], 1)
        self.assertAlmostEqual(result["evaluation"]["reward"]["mean"], -0.3)

    def test_evaluation_outcomes_cannot_change_selected_action(self):
        original = self.actions()
        changed = copy.deepcopy(original)
        for row in changed[1][16:]:
            row["terminal"]["reward"] = 1.0
        self.assertEqual(analyze_root(original)["selectedAction"], analyze_root(changed)["selectedAction"])

    def test_ties_retain_recorded_action(self):
        actions = self.actions()
        for row in actions[1][:16]:
            row["terminal"]["reward"] = 0.5
        self.assertEqual(analyze_root(actions)["selectedAction"], 0)

    def test_missing_evaluation_tails_cannot_be_analyzed(self):
        actions = self.actions()
        actions[1].pop()
        with self.assertRaisesRegex(ValueError, "all 32"):
            analyze_root(actions)

    def test_economic_receipts_are_descriptive_and_cannot_select_an_arm(self):
        actions = self.actions()
        for index, rows in enumerate(actions):
            for row in rows:
                row["economics"] = {
                    "nextBoundary": {"cash": 1000 if index == 0 else 0, "markers": 3,
                                     "income": {"ordinary": 15}, "commodityMarkets": {}},
                    "rootTurnLeaderPlays": [{"projectedPrintedWordRebate": 10 if index == 0 else 0}],
                    "rootTurnPurchases": [{"ownNewLeaderEffectiveSaving": 20 if index == 0 else 0}],
                    "rootTurnOwnLeaderPatronage": [],
                }
        result = analyze_root(actions)
        self.assertEqual(result["selectedAction"], 1)
        self.assertEqual(result["selectedEconomicDeltas"]["projectedPrintedWordRebate"]["mean"], -10)


if __name__ == "__main__":
    unittest.main()
