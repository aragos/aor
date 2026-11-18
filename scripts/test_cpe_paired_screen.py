"""Complete-bank C/P/E screen semantics over invented compact terminal records."""
import unittest

import cpe_paired_campaign_plan as p
import cpe_paired_screen as screen
from test_cpe_paired_campaign_plan import fixture
from test_token_paired_screen import world as token_world, zero_report as token_report


def world(plan,index):
    value=token_world(plan,index);value['kind']=screen.KIND
    value['roles']['RAW_G3']=value['roles'].pop('RAW_T')
    return value


def zero_report():
    value=token_report();value['kind']='cpe-paired-screen-report-1'
    for old,new in (('finalMinusRawT','finalMinusRawG3'),('initializerMinusRawT','initializerMinusRawG3')):
        value[new]=value.pop(old)
    return value


class ScreenTests(unittest.TestCase):
    def test_raw_g3_is_an_explicit_distinct_complete_role(self):
        plan=p.Plan.from_record(fixture());value=world(plan,0)
        screen.validate_world(plan,value,0,'b'*64)
        value['roles']['RAW_T']=value['roles'].pop('RAW_G3')
        with self.assertRaisesRegex(ValueError,'all three'):
            screen.validate_world(plan,value,0,'b'*64)

    def test_invented_zero_gain_does_not_nominate_any_role(self):
        plan=p.Plan.from_record(fixture())
        self.assertFalse(screen.verify_report([world(plan,i) for i in range(1024)],zero_report()))

    def test_partial_world_bank_cannot_reach_interpretation(self):
        with self.assertRaisesRegex(ValueError,'complete original'):
            screen.verify_report([],zero_report())

    def test_report_interval_method_cannot_change_to_validation_method(self):
        plan=p.Plan.from_record(fixture());report=zero_report();report['intervalMultiplier']=1.95996398454
        with self.assertRaisesRegex(ValueError,'complete original'):
            screen.verify_report([world(plan,i) for i in range(1024)],report)

    def test_public_scores_remain_authoritative_for_each_role(self):
        plan=p.Plan.from_record(fixture());value=world(plan,0);value['roles']['RAW_G3']['rewards'][0]=.5
        with self.assertRaisesRegex(ValueError,'terminal label'):
            screen.validate_world(plan,value,0,'b'*64)

    def test_historical_controls_bind_complete_bundle_scope(self):
        gate=dict(kind='cpe-paired-historical-admission-1',complete=True,controls=2,zeroFailures=True,
            protocolSha256='a'*64,bundleScopeSha256='b'*64,ecologySha256='c'*64,
            scientificTerminalExecutions=2,records=[{},{}])
        screen.verify_historical(gate,'a'*64,'b'*64,'c'*64)
        with self.assertRaisesRegex(ValueError,'both passing'):
            screen.verify_historical(gate,'a'*64,'d'*64,'c'*64)


if __name__=='__main__':unittest.main()
