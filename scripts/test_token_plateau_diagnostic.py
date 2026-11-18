"""Pure constructed selector checks; no actual observations, models or random draws."""
import math
import unittest

import token_plateau_diagnostic as diagnostic


class TokenPlateauDiagnosticTest(unittest.TestCase):
    def test_reference_wins_exact_score_tie(self):
        winner, _ = diagnostic.distribution([2., 2., 1.], [10, 2, 11], baseline=1)
        self.assertEqual(winner, 1)

    def test_radius_centers_on_actual_maximum_and_preserves_lexical_order(self):
        winner, probabilities = diagnostic.distribution([1., 0., 2.], [10, 2, 11], baseline=1)
        self.assertEqual(winner, 2)
        self.assertGreater(probabilities[0], 0.)
        self.assertEqual(probabilities[1], 0.)
        self.assertGreater(probabilities[2], probabilities[0])
        self.assertAlmostEqual(sum(probabilities), 1.)

    def test_score_gap_excludes_action_even_within_radius(self):
        _, probabilities = diagnostic.distribution([0., 4.], [20, 21], baseline=0)
        self.assertEqual(probabilities, [0., 1.])

    def test_common_score_shift_changes_no_probability(self):
        _, before = diagnostic.distribution([1., 2., 3.], [20, 21, 22], baseline=0)
        _, after = diagnostic.distribution([101., 102., 103.], [20, 21, 22], baseline=0)
        self.assertEqual(before, after)

    def test_same_uniform_disagreement_uses_cdf_intervals(self):
        self.assertAlmostEqual(diagnostic.same_uniform_disagreement([.5,.5], [.25,.75]), .25)
        self.assertEqual(diagnostic.same_uniform_disagreement([0.,1.], [0.,1.]), 0.)
        self.assertEqual(diagnostic.same_uniform_disagreement([1.,0.], [0.,1.]), 1.)

    def test_nonfinite_scores_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'finite'):
            diagnostic.distribution([math.nan], [21], baseline=0)


if __name__ == '__main__':
    unittest.main()
