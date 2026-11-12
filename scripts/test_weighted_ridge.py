"""UNIT synthetic matrices only; no retained game states or labels."""
import unittest

import numpy as np

from weighted_ridge import weighted_solve


def old_macro_reference(x, y, weights):
    # Frozen52c8b82 arithmetic, deliberately independent of the new generic owner.
    x, y, weights = (np.asarray(v, dtype=np.float64) for v in (x, y, weights))
    weights = weights / np.sum(weights)
    rms = np.sqrt(np.sum(weights[:, None] * x * x, axis=0))
    zero = rms == 0; scale = np.where(zero, 1., rms)
    normalized = x / scale
    coefficients = np.linalg.solve(normalized.T @ (weights[:, None] * normalized) +
                                   .01 * np.eye(169), normalized.T @ (weights * y))
    coefficients[zero] = 0.
    return tuple(map(float, scale)), tuple(map(float, coefficients)), tuple(map(int, np.flatnonzero(zero)))


class WeightedRidgeTest(unittest.TestCase):
    def test_macro_recipe_matches_original_arithmetic_exactly(self):
        x = np.array([[((i + 3) * (j + 5) % 19 - 9) / 7 for j in range(169)]
                      for i in range(23)], dtype=np.float64)
        x[:, [0, 13, 168]] = 0
        y = np.array([(i % 7 - 3) / 11 for i in range(23)])
        weights = np.array([1 / (i % 4 + 1) for i in range(23)])
        actual = weighted_solve(x, y, weights, dimension=169, penalty=.01)
        self.assertEqual(actual, old_macro_reference(x, y, weights))

    def test_zero_columns_keep_scale_one_and_weight_zero(self):
        scale, coefficients, zero = weighted_solve([[0., 2.], [0., 4.]], [1., 2.],
                                                   [1., 1.], dimension=2, penalty=.1)
        self.assertEqual((scale[0], coefficients[0], zero), (1., 0., (0,)))

    def test_training_rms_is_not_centered(self):
        scale, _, _ = weighted_solve([[3.], [3.]], [1., 1.], [1., 1.], dimension=1, penalty=.1)
        self.assertEqual(scale, (3.,))

    def test_common_weight_scaling_leaves_result_exact(self):
        args = ([[1., 3.], [2., 4.]], [1., -1.])
        self.assertEqual(weighted_solve(*args, [1., 2.], dimension=2, penalty=.1),
                         weighted_solve(*args, [4., 8.], dimension=2, penalty=.1))

    def test_nonpositive_weights_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'positive whole-origin'):
            weighted_solve([[1.]], [0.], [0.], dimension=1, penalty=.1)

    def test_nonfinite_targets_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'finite supported'):
            weighted_solve([[1.]], [float('nan')], [1.], dimension=1, penalty=.1)

    def test_wrong_dimension_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'finite supported'):
            weighted_solve([[1.]], [0.], [1.], dimension=2, penalty=.1)

    def test_boolean_dimension_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'positive integer'):
            weighted_solve([[1.]], [0.], [1.], dimension=True, penalty=.1)

    def test_boolean_penalty_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'finite and positive'):
            weighted_solve([[1.]], [0.], [1.], dimension=1, penalty=True)


if __name__ == '__main__':
    unittest.main()
