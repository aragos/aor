import copy
import math
import unittest

import coordinated_es_calibration as v1
import coordinated_es_calibration_v2 as v2
import coordinated_es_math as es
from test_coordinated_es_calibration import panel,typed


class ThresholdTests(unittest.TestCase):
    def test_exact_five_percent_atom_uses_strict_upper_quantile(self):
        value=v2.weighted_interval([(-1.,60),(1.,35),(2.,5)])
        self.assertEqual((value['lowerGap'],value['lowerAboveWeight'],value['lowerAtWeight']),(2.,0,5))
        self.assertEqual(value['lowerScale'],.5)
        self.assertFalse(value['lowerInclusive'])

    def test_exact_forty_percent_does_not_cross_until_next_atom(self):
        value=v2.weighted_interval([(-1.,40),(1.,20),(2.,40)])
        self.assertEqual((value['upperGap'],value['upperAboveWeight'],value['upperAtWeight']),(1.,40,20))
        self.assertEqual(value['upperScale'],1.)
        self.assertTrue(value['upperInclusive'])

    def test_integer_thresholds_round_inward(self):
        value=v2.weighted_interval([(-1.,12),(2.,7),(4.,1),(8.,1)])
        self.assertEqual((value['minimumChangedWeight'],value['maximumChangedWeight']),(2,8))
        self.assertEqual((value['lowerGap'],value['upperGap']),(4.,2.))

    def test_equal_atoms_are_aggregated_before_crossing(self):
        value=v2.weighted_interval([(2.,2),(2.,3),(1.,35),(-1.,60)])
        self.assertEqual((value['lowerAboveWeight'],value['lowerAtWeight']),(0,5))

    def test_positive_mass_at_maximum_has_no_upper_gap(self):
        value=v2.weighted_interval([(1.,40),(-1.,60)])
        self.assertIsNone(value['upperGap'])
        self.assertEqual((value['upperAboveWeight'],value['upperAtWeight'],value['upperScale']),(40,0,32.))

    def test_tiny_positive_upper_gap_uses_scale_cap_without_overflow(self):
        value=v2.weighted_interval([(1.,20),(1e-320,40),(-1.,40)])
        self.assertEqual(value['upperGap'],1e-320)
        self.assertEqual(value['upperScale'],32.)

    def test_strictly_larger_scale_floor_is_closed(self):
        value=v2.weighted_interval([(100.,25),(-1.,75)])
        self.assertEqual(value['lowerScale'],1/32)
        self.assertTrue(value['lowerInclusive'])

    def test_equal_scale_floor_keeps_original_open_endpoint(self):
        value=v2.weighted_interval([(32.,25),(-1.,75)])
        self.assertEqual(value['lowerScale'],1/32)
        self.assertFalse(value['lowerInclusive'])

    def test_negative_gap_magnitude_cannot_drive_interval(self):
        self.assertEqual(v2.weighted_interval([(1.,25),(-1e100,75)]),
                         v2.weighted_interval([(1.,25),(-1.,75)]))

    def test_noninteger_weights_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'integer mass'):
            v2.weighted_interval([(1.,1.5)])


class IntersectionTests(unittest.TestCase):
    def test_both_distributions_constrain_one_scale(self):
        intervals=dict(mutants=v2.weighted_interval([(2.,25),(-1.,75)]),
            prescribedMean=v2.weighted_interval([(1.,20),(.5,30),(-1.,50)]))
        common=v2.common_interval(intervals)
        self.assertEqual((common['lowerScale'],common['upperScale']),(1.,2.))
        self.assertEqual(v2.interior_scale(common),math.sqrt(2.))

    def test_disjoint_intervals_stop(self):
        intervals=dict(mutants=v2.weighted_interval([(2.,50),(-1.,50)]),
            prescribedMean=v2.weighted_interval([(.5,25),(-1.,75)]))
        with self.assertRaisesRegex(es.OperationalStop,'empty-feasible-interval'):
            v2.common_interval(intervals)

    def test_closed_singleton_is_still_rejected(self):
        value=dict(lowerScale=1.,upperScale=1.,lowerInclusive=True,upperInclusive=True)
        with self.assertRaisesRegex(es.OperationalStop,'empty-feasible-interval'):
            v2.common_interval(dict(mutants=value,prescribedMean=value))

    def test_equal_lower_endpoints_combine_inclusivity(self):
        closed=dict(lowerScale=1.,upperScale=2.,lowerInclusive=True,upperInclusive=True)
        opened=dict(closed,lowerInclusive=False)
        self.assertFalse(v2.common_interval(dict(mutants=closed,prescribedMean=opened))['lowerInclusive'])

    def test_adjacent_float_endpoints_do_not_trigger_neighbor_search(self):
        value=dict(lowerScale=1.,upperScale=math.nextafter(1.,2.))
        with self.assertRaisesRegex(es.OperationalStop,'no-interior-scale'):
            v2.interior_scale(value)


class CalibrationTests(unittest.TestCase):
    def test_complete_receipt_uses_one_geometric_midpoint(self):
        value=v2.calibrate(typed(panel()))
        self.assertEqual(value['kind'],v2.VERSION)
        self.assertEqual(value['scale'],math.sqrt(32.))
        self.assertEqual(value['movement']['mutants']['rate'],.25)
        self.assertEqual(value['movement']['prescribedMean']['rate'],.25)
        self.assertEqual(value['scaleAttempts'],1)
        self.assertFalse(value['terminalLabelsRead'])

    def test_v1_result_is_unchanged_and_distinct(self):
        evidence=typed(panel())
        self.assertEqual(v1.calibrate(evidence)['scale'],1+2**-20)
        self.assertNotEqual(v1.calibrate(evidence)['kind'],v2.calibrate(evidence)['kind'])

    def test_missing_world_prerequisite_remains(self):
        with self.assertRaisesRegex(es.OperationalStop,'insufficient-worlds'):
            v2.calibrate(typed(panel(127)))

    def test_positive_mass_prerequisite_remains(self):
        with self.assertRaisesRegex(es.OperationalStop,'positive-gap-mass'):
            v2.calibrate(typed(panel(128,12)))

    def test_exact_positive_ten_percent_still_requires_positive_q90(self):
        with self.assertRaisesRegex(es.OperationalStop,'small-signed-quantile'):
            v2.calibrate(typed(panel(160,16)))

    def test_small_q90_cannot_pass_via_feasible_scale(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:
                state['mutantGaps']=[value*1e-9 for value in state['mutantGaps']]
        with self.assertRaisesRegex(es.OperationalStop,'small-signed-quantile'):
            v2.calibrate(typed(record))

    def test_fixed_scale_cap_can_make_interval_empty(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:
                state['meanGap']*=.01
        with self.assertRaisesRegex(es.OperationalStop,'empty-feasible-interval'):
            v2.calibrate(typed(record))

    def test_all_positive_equal_gap_cannot_satisfy_movement_band(self):
        with self.assertRaisesRegex(es.OperationalStop,'empty-feasible-interval'):
            v2.calibrate(typed(panel(128,128)))

    def test_duplicating_state_within_world_preserves_weights(self):
        record=panel();duplicate=copy.deepcopy(record)
        for world in duplicate['worlds'][:32]:
            state=copy.deepcopy(world['states'][0]);state['decisionNumber']+=1;world['states'].append(state)
        first=v2.calibrate(typed(record));second=v2.calibrate(typed(duplicate))
        for field in ('distributions','intervals','commonInterval','scale','movement'):
            self.assertEqual(first[field],second[field])

    def test_common_mode_actual_scores_can_reject_analytic_interval(self):
        record=panel(160,40)
        for world in record['worlds'][:160]:
            state=world['states'][0];index=world['index']
            state['mutantGaps']=[4. if index<40 else 25/9 if index<120 else -.5]*32
            state['meanGap']=4. if index<40 else -.5
        value=typed(record).record
        for world in value['worlds'][:40]:
            state=world['states'][0];witness=dict(reference=float(2**54),alternativeMaximum=float(2**54+4))
            state['mutantWitnesses']=[witness]*32;state['meanWitness']=witness
        with self.assertRaisesRegex(es.OperationalStop,'movement-bounds'):
            v2.calibrate(v1.GapPanel.from_record(value))

    def test_nonfinite_actual_score_is_a_fixed_operational_stop(self):
        value=typed(panel()).record;state=value['worlds'][0]['states'][0]
        witness=dict(reference=1.6e308,alternativeMaximum=math.nextafter(1.6e308,math.inf))
        gap=v1.witness_gap(witness)
        state.update(mutantGaps=[gap]*32,meanGap=gap,mutantWitnesses=[witness]*32,meanWitness=witness)
        with self.assertRaisesRegex(es.OperationalStop,'nonfinite-movement'):
            v2.calibrate(v1.GapPanel.from_record(value))

    def test_live_zero_movement_is_diagnostic_only(self):
        evidence=typed(panel());sealed=v2.calibrate(evidence);rows,_=v1.weighted_rows(evidence)
        gaps=[dict(index=index,decisionNumber=state['decisionNumber'],batchStartNumber=state['batchStartNumber'],
            witness=dict(reference=0.,alternativeMaximum=0.)) for index,state,_ in rows]
        value=v2.live_movement(evidence,sealed,gaps)
        self.assertEqual(value['rate'],0.)
        self.assertFalse(value['eligibilityGate'])

    def test_tampered_interval_cannot_enter_live_diagnostic(self):
        evidence=typed(panel());sealed=v2.calibrate(evidence)
        sealed['intervals']['mutants']['lowerAtWeight']+=1
        with self.assertRaisesRegex(ValueError,'exact V2'):
            v2.live_movement(evidence,sealed,[])


if __name__=='__main__':unittest.main()
