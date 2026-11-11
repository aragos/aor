import unittest
from analyze_leader_hold import POLICIES, analyze_root


def arms():
    def row(action, replicate):
        return {"entry": 235, "action": action, "replicate": replicate, "split": "evaluation",
                "policy": POLICIES[action], "command": "pass" if action else "play hand-1",
                "terminal": {"reward": action / 10, "winShare": 0, "rankReward": action / 2,
                             "scores": [{"player": "p2", "total": 100 + action, "cash": 20,
                                         "advancePoints": 80 + action, "misery": 0}]},
                "economics": {"nextBoundary": {"cash": 20, "markers": 1, "income": {"ordinary": 5},
                                                  "commodityMarkets": {}}, "rootTurnPurchases": [],
                              "rootTurnLeaderPlays": [], "rootTurnOwnLeaderPatronage": []},
                "hold": {"maskedDecisions": 1, "forcedReleases": []} if action else None}
    return [[row(action, replicate) for replicate in range(32)] for action in range(2)]


class HoldAnalysisTest(unittest.TestCase):
    def test_all_thirty_two_evaluation_pairs_are_used(self):
        result = analyze_root(arms(), "p2")
        self.assertAlmostEqual(.1, result["evaluation"]["reward"]["mean"])
        self.assertEqual(32, result["evaluation"]["reward"]["samples"])
        self.assertNotIn("selectedAction", result)

    def test_selection_split_is_rejected(self):
        rows = arms()
        rows[1][0]["split"] = "selection"
        with self.assertRaises(ValueError):
            analyze_root(rows, "p2")

    def test_partial_bank_is_rejected(self):
        rows = arms()
        rows[1].pop()
        with self.assertRaises(ValueError):
            analyze_root(rows, "p2")

    def test_chaos_keeps_cash_and_reports_common_total_count(self):
        rows = arms()
        rows[1][0]["terminal"]["scores"][0]["total"] = None
        result = analyze_root(rows, "p2")
        self.assertEqual([0, 1], result["chaosCounts"])
        self.assertEqual(31, result["terminalScoreDeltas"]["total"]["samples"])
        self.assertIsNone(result["terminalScoreDeltas"]["total"]["conditionalApprox95"])
        self.assertEqual(32, result["terminalScoreDeltas"]["cash"]["samples"])

    def test_forced_release_is_retained(self):
        rows = arms()
        rows[1][0]["hold"]["forcedReleases"] = [{"reason": "no-other-legal-command"}]
        result = analyze_root(rows, "p2")
        self.assertEqual(1, result["forcedReleaseTails"])
        self.assertEqual(32, result["evaluation"]["reward"]["samples"])

    def test_pair_order_is_required(self):
        rows = arms()
        rows[1].reverse()
        with self.assertRaises(ValueError):
            analyze_root(rows, "p2")


if __name__ == "__main__":
    unittest.main()
