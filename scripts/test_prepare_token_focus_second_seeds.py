"""Only constructed metadata; no factors, directions, forwards or game worlds."""
import unittest

import prepare_token_focus_second_seeds as second
from test_prepare_token_focus_seeds import method as first_method
import verify_token_focus_seeds as verifier


def method():
    return dict(first_method(), kind=second.METHOD_KIND, study=97,
        startingWeightsMustBeOriginal96Final=True, holdoutIndependentStartingWeights=True,
        qualified94ControlWeightsSha256='fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e')


class TokenSecondMetadataTest(unittest.TestCase):
    def test_registered_second_step_is_valid(self):
        self.assertIsNone(second.validate_method(method()))

    def test_holdout_selected_origin_is_rejected(self):
        changed = method()
        changed['holdoutIndependentStartingWeights'] = False
        with self.assertRaisesRegex(ValueError, 'independently'):
            second.validate_method(changed)

    def test_alternate_starting_weights_are_rejected(self):
        changed = method()
        changed['startingWeightsMustBeOriginal96Final'] = False
        with self.assertRaisesRegex(ValueError, 'only original96FINAL'):
            second.validate_method(changed)

    def test_fresh_second_recipe_leaves_first_recipe_unchanged(self):
        self.assertEqual((96, 96000000, 96001000),
            (second.first.RECIPE.family, second.first.RECIPE.native_prefix,
             second.first.RECIPE.numerical_prefix))
        self.assertEqual((97, 97000000, 97001000),
            (second.RECIPE.family, second.RECIPE.native_prefix, second.RECIPE.numerical_prefix))
        self.assertEqual(96, verifier.FIRST.family)


if __name__ == '__main__':
    unittest.main()
