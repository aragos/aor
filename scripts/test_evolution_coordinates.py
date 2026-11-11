"""Immutable profile validation and bitwise regression of the original mutation sequence."""
from dataclasses import FrozenInstanceError
import hashlib
import json
import unittest

import evolution_coordinates as coordinate
import expansion_evolution as legacy

TARGET_PROFILE = coordinate.CoordinateProfile(('a', 'b', 'c', 'd'), (1.,) * 4, 8., (8.,) * 4)


class CoordinateProfileTest(unittest.TestCase):
    def test_original_eight_coordinate_full_proposal_receipts_are_unchanged(self):
        baseline=dict(schemaVersion=1,rulesVersion='old',dataVersion='data',
            weights={'tokens':1.8,'income':4.1,'cards':17.,'planning.tokenValue.enabled':1.})
        normalization=dict(features=[3.,1.,1.,2.,2.5,1.5,1.,1.],temperature=21.6)
        parents=[legacy.export_model([x]*8,baseline,normalization,[8.]*8,'new') for x in (1.,-2.)]
        expected=('087f8e252eeecf06497f33f8605da94651803e267f19052a61e3ce7fd3c32ae0',
                  '6397b1123db98c613ba6d81d97828d2f66afed515cacab80c95e5b549487fa02',
                  '262227f8370a9bae5216aba98f18daad05966a1ac516158aaeb9222aab52d6d8')
        for count,digest in enumerate(expected):
            rows=legacy.population(baseline,parents[:count],normalization,[8.]*8,101,'new')
            self.assertEqual(hashlib.sha256(json.dumps(rows,sort_keys=True,separators=(',',':')).encode()).hexdigest(),digest)

    def test_profile_cannot_be_mutated(self):
        with self.assertRaises(FrozenInstanceError): TARGET_PROFILE.temperature=9

    def test_mutable_feature_names_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'immutable tuples'):
            coordinate.CoordinateProfile(['a'],(1.,),8.,(8.,))

    def test_duplicate_names_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'unique feature'):
            coordinate.CoordinateProfile(('a','a'),(1.,1.),8.,(8.,8.))

    def test_nonfinite_scale_rejected(self):
        with self.assertRaises(ValueError): coordinate.CoordinateProfile(('a',),(float('nan'),),8.,(8.,))

    def test_bound_count_rejected(self):
        with self.assertRaisesRegex(ValueError,'one finite bound'):
            coordinate.CoordinateProfile(('a',),(1.,),8.,(8.,8.))

    def test_target_raw_range_is_exactly_sixty_four(self):
        base=dict(schemaVersion=1,rulesVersion='new',dataVersion='data',weights={'tokens':1.})
        actual=coordinate.export_model([-8.,8.,-8.,8.],base,TARGET_PROFILE,'new')
        self.assertEqual([actual['weights'][name] for name in TARGET_PROFILE.features],[-64.,64.,-64.,64.])


if __name__=='__main__': unittest.main()
