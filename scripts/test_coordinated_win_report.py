"""Invented paired scalar rows only; these fixtures claim no native terminal execution."""
import copy
import unittest

import coordinated_win_report as report


def worlds(final=.7,start=.5,control=.4):
    def role(win):
        # Rewards deliberately oppose win improvement. The report never gates on them.
        return dict(wins=[win]*4,rewards=[1.-win]*4,ranks=[.5]*4,
            scores=[dict(player=f'p{seat+1}',total=None if seat==2 else 1) for seat in reversed(range(4))])
    return [dict(index=i,seat=i%4,roles={name:role(win) for name,win in
        (('FINAL',final),('START',start),('CONTROL',control))}) for i in range(2048)]


def native_shape(rows):
    contrasts=report.contrasts(rows)
    learning=contrasts['finalMinusStart']['wins']['lower95']>0
    return dict(kind='coordinated-win-screen-report-1',worlds=2048,intervalMultiplier=1.96,
        intervalMethod='Normal 95% across independent paired worlds',**contrasts,
        learningGatePassed=learning,
        developmentGatePassed=learning and contrasts['finalMinusControl']['wins']['lower95']>0,
        qualification=False,activated=False,automaticSuccessor=False)


class ReportTests(unittest.TestCase):
    def test_win_improvement_may_pass_while_descriptive_reward_declines(self):
        rows=worlds();value=native_shape(rows)
        self.assertLess(value['finalMinusControl']['rewards']['upper95'],0.)
        self.assertTrue(report.verify(rows,value))

    def test_final_must_improve_over_the_preselected_start_as_well_as_control(self):
        rows=worlds(final=.5,start=.7,control=.4)
        self.assertFalse(report.verify(rows,native_shape(rows)))

    def test_final_must_beat_control_even_when_learning_over_start(self):
        rows=worlds(final=.5,start=.4,control=.7)
        self.assertFalse(report.verify(rows,native_shape(rows)))

    def test_zero_bound_is_not_strict_positive_improvement(self):
        rows=worlds(final=.5,start=.5,control=.4)
        self.assertFalse(report.verify(rows,native_shape(rows)))

    def test_missing_or_reordered_worlds_reject(self):
        rows=worlds();value=native_shape(rows)
        for changed in (rows[:-1],list(reversed(rows))):
            with self.subTest(count=len(changed)),self.assertRaisesRegex(ValueError,'complete fixed2048'):
                report.verify(changed,value)

    def test_changed_count_cannot_claim_more_independent_samples(self):
        rows=worlds();value=native_shape(rows);value['finalMinusControl']['wins']['count']=8192
        with self.assertRaisesRegex(ValueError,'same complete'):report.verify(rows,value)

    def test_changed_report_interval_is_rejected(self):
        rows=worlds();value=native_shape(rows);value['finalMinusStart']['wins']['lower95']+=.01
        with self.assertRaisesRegex(ValueError,'same complete'):report.verify(rows,value)

    def test_score_player_identity_owns_chaos_even_with_reordered_scores(self):
        rows=worlds();self.assertEqual(report.value(rows[2],'FINAL','chaos'),1)
        self.assertEqual(report.value(rows[0],'FINAL','chaos'),0)

    def test_raw_or_initializer_alias_is_not_the_fixed_start(self):
        rows=worlds();value=native_shape(rows)
        rows[0]['roles']['INITIAL']=rows[0]['roles'].pop('START')
        with self.assertRaisesRegex(ValueError,'three final/start/control'):report.verify(rows,value)

    def test_passing_report_cannot_activate_or_nominate_start(self):
        rows=worlds();original=native_shape(rows)
        for field in ('activated','qualification','automaticSuccessor'):
            value=copy.deepcopy(original);value[field]=True
            with self.subTest(field=field),self.assertRaisesRegex(ValueError,'no fallback'):
                report.verify(rows,value)


if __name__=='__main__':unittest.main()
