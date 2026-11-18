import copy
import unittest

import automata_experiments as exp
import token_paired_campaign_plan as p
import token_paired_screen as screen
from test_token_paired_campaign_plan import fixture


def world(plan,index):
    scores=[dict(player='p'+str(i+1),cash=4-i,advancePoints=0,misery=0,total=4-i,winner=i==0) for i in range(4)]
    ranks=[1.,2/3,1/3,0.];wins=[1.,0.,0.,0.]
    game=dict(wins=wins,ranks=ranks,rewards=[.8*w+.2*r for w,r in zip(wins,ranks)],scores=scores,decisions=100)
    return dict(kind=screen.KIND,signature='a'*64,scienceSha256='b'*64,index=index,
        seed=plan.record['screenBank']['seeds'][index],seat=index%4,configuration=exp.CONFIGURATION,
        opponentIndices=[0]*4,roles={role:copy.deepcopy(game) for role in p.ROLES})


def zero_report():
    return dict(kind='token-paired-screen-report-1',worlds=1024,intervalMultiplier=1.96,
        **{name:{metric:exp.interval([0.]*1024) for metric in ('wins','rewards','ranks','chaos')}
            for name in ('finalMinusRawT','finalMinusInitializer','initializerMinusRawT')},
        developmentGatePassed=False,qualification=False,activated=False)


class ScreenTests(unittest.TestCase):
    def test_compact_role_rewards_are_verified_from_public_scores(self):
        plan=p.Plan.from_record(fixture());value=world(plan,0)
        screen.validate_world(plan,value,0,'b'*64)
        value['roles']['INITIAL']['rewards'][0]=.5
        with self.assertRaisesRegex(ValueError,'terminal label'):screen.validate_world(plan,value,0,'b'*64)

    def test_missing_role_is_not_a_three_role_world(self):
        plan=p.Plan.from_record(fixture());value=world(plan,0);del value['roles']['INITIAL']
        with self.assertRaisesRegex(ValueError,'all three'):screen.validate_world(plan,value,0,'b'*64)

    def test_zero_complete_screen_fails_without_fallback(self):
        plan=p.Plan.from_record(fixture());worlds=[world(plan,i) for i in range(1024)]
        self.assertFalse(screen.verify_report(worlds,zero_report()))

    def test_report_rejects_partial_bank_before_effect_calculation(self):
        with self.assertRaisesRegex(ValueError,'complete original'):screen.verify_report([],zero_report())

    def test_changed_native_interval_is_rejected(self):
        plan=p.Plan.from_record(fixture());worlds=[world(plan,i) for i in range(1024)];report=zero_report()
        report['finalMinusRawT']['rewards']['mean']=.01
        with self.assertRaisesRegex(ValueError,'interval'):screen.verify_report(worlds,report)

    def test_historical_controls_cannot_be_skipped_or_rebound(self):
        gate=dict(kind='token-paired-historical-admission-1',complete=True,controls=2,zeroFailures=True,
            protocolSha256='a'*64,scopeSha256='b'*64,ecologySha256='c'*64,scientificTerminalExecutions=2,records=[{},{}])
        screen.verify_historical(gate,'a'*64,'b'*64,'c'*64)
        with self.assertRaisesRegex(ValueError,'both passing'):screen.verify_historical(gate,'a'*64,'b'*64,'d'*64)


if __name__=='__main__':unittest.main()
