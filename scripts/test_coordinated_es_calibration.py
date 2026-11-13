import copy
import unittest

import coordinated_es_math as es
import coordinated_es_calibration as c


def panel(worlds=128,positive=32):
    return dict(kind='coordinated-es-gap-panel-1',head='EXPANSION',protocolSha256='a'*64,
        originalModelSha256='b'*64,projectionSha256='c'*64,directionsSha256='d'*64,
        prescribedMeanSha256='e'*64,publicPanelSha256='f'*64,
        worlds=[dict(index=index,states=[] if index>=worlds else [dict(decisionNumber=2*index,
            batchStartNumber=2*index,observationSha256=es.digest(['state',index]),referenceIndex=1,
            actionCount=3,mutantGaps=[1. if index<positive else -.5]*32,
            meanGap=1. if index<positive else -.5)]) for index in range(256)])


def typed(record):
    record=copy.deepcopy(record)
    for world in record['worlds']:
        for state in world['states']:
            state['mutantWitnesses']=[dict(reference=0.,alternativeMaximum=value) for value in state['mutantGaps']]
            state['meanWitness']=dict(reference=0.,alternativeMaximum=state['meanGap'])
    return c.GapPanel.from_record(record)


class CalibrationTests(unittest.TestCase):
    def test_one_fixed_scale_satisfies_both_populations(self):
        value=c.calibrate(typed(panel()))
        self.assertEqual(value['scale'],1+2**-20)
        self.assertEqual(value['movement']['mutants']['rate'],.25)
        self.assertEqual(value['movement']['prescribedMean']['rate'],.25)

    def test_quantile_is_inverse_cdf_with_exact_integer_boundary(self):
        self.assertEqual(c.quantile90([(-1.,9),(4.,1)]),-1.)
        self.assertEqual(c.quantile90([(-1.,89),(4.,11)]),4.)

    def test_signed_gap_does_not_use_absolute_values(self):
        self.assertEqual(c.signed_gap([-5.,2.,-9.],1),-7.)

    def test_absolute_large_but_negative_gaps_stop(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:state['mutantGaps']=[-99.]*32
        with self.assertRaisesRegex(es.OperationalStop,'positive-gap'):c.calibrate(typed(record))

    def test_prescribed_mean_must_also_have_sufficient_gap(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:state['meanGap']=0.
        with self.assertRaisesRegex(es.OperationalStop,'positive-gap'):c.calibrate(typed(record))

    def test_exact10percent_positive_can_still_fail_q90(self):
        record=panel(160,16)
        for world in record['worlds']:
            for state in world['states']:
                if state['meanGap']<0:state['meanGap']=0.;state['mutantGaps']=[0.]*32
        with self.assertRaisesRegex(es.OperationalStop,'small-signed-quantile'):c.calibrate(typed(record))

    def test_missing_worlds_are_counted_and_no_refill(self):
        with self.assertRaisesRegex(es.OperationalStop,'insufficient-worlds'):
            c.calibrate(typed(panel(127)))

    def test_each_world_has_equal_weight_despite_two_states(self):
        one=panel();two=copy.deepcopy(one)
        for world in two['worlds'][:32]:
            state=copy.deepcopy(world['states'][0]);state['decisionNumber']+=1;world['states'].append(state)
        before=c.calibrate(typed(one));after=c.calibrate(typed(two))
        self.assertEqual(before['movement'],after['movement'])
        self.assertEqual(before['distributions'],after['distributions'])

    def test_duplicate_sole_state_is_rejected(self):
        record=panel();record['worlds'][0]['states']*=2
        with self.assertRaisesRegex(ValueError,'deduplicated'):typed(record)

    def test_small_signed_quantile_stops_without_rescaling(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:
                state['mutantGaps']=[value*1e-9 for value in state['mutantGaps']]
        with self.assertRaisesRegex(es.OperationalStop,'small-signed-quantile'):c.calibrate(typed(record))

    def test_scale_range_is_fixed(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:state['mutantGaps']=[value*.01 for value in state['mutantGaps']]
        with self.assertRaisesRegex(es.OperationalStop,'scale-bounds'):c.calibrate(typed(record))

    def test_mean_movement_has_independent_fixed_upper_bound(self):
        record=panel()
        for world in record['worlds']:
            for state in world['states']:state['meanGap']=1.
        with self.assertRaisesRegex(es.OperationalStop,'movement-bounds'):c.calibrate(typed(record))

    def test_incumbent_wins_exact_margin_tie(self):
        self.assertEqual(c.choose([1.,0.,1.],1,1.),1)

    def test_lowest_original_index_wins_nonincumbent_tie(self):
        self.assertEqual(c.choose([2.,0.,2.],1,1.),0)

    def test_zero_difference_preserves_raw_reference(self):
        self.assertEqual(c.choose([0.]*75,31,32.),31)

    def test_scale_is_applied_before_adding_margin(self):
        self.assertEqual(c.choose([.6,0.],1,2.),0)

    def test_nonfinite_scores_stop(self):
        with self.assertRaisesRegex(es.OperationalStop,'nonfinite'):
            c.choose([1e308,0.],1,32.)

    def test_outcome_payload_is_rejected_by_public_owner(self):
        record=panel();record['reward']=1.
        with self.assertRaisesRegex(ValueError,'unexpected'):typed(record)

    def test_live_zero_movement_remains_eligible_diagnostic(self):
        evidence=typed(panel());calibration=c.calibrate(evidence)
        rows,_=c.weighted_rows(evidence)
        gaps=[dict(index=index,decisionNumber=state['decisionNumber'],batchStartNumber=state['batchStartNumber'],witness=dict(reference=0.,alternativeMaximum=0.))
            for index,state,_ in rows]
        result=c.live_movement(evidence,calibration,gaps)
        self.assertEqual(result['rate'],0.)
        self.assertFalse(result['eligibilityGate'])

    def test_actual_score_common_mode_tie_differs_from_gap_shortcut(self):
        values=[float(2**54),float(2**54+4)]
        scale=.3
        self.assertGreater(scale*c.signed_gap(values,0),1.)
        self.assertEqual(c.choose(values,0,scale),0)
        self.assertFalse(c.changed(c.witness(values,0),scale))

    def test_witness_tamper_cannot_retain_original_signed_gap(self):
        record=typed(panel()).record
        record['worlds'][0]['states'][0]['meanWitness']['reference']=1.
        with self.assertRaisesRegex(ValueError,'exact movement witnesses'):
            c.GapPanel.from_record(record)

    def test_mutating_input_dictionary_cannot_change_panel(self):
        record=panel();evidence=typed(record);record['worlds'][0]['states'][0]['meanGap']=-9.
        self.assertEqual(evidence.record['worlds'][0]['states'][0]['meanGap'],1.)


if __name__=='__main__':unittest.main()
