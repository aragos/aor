"""Synthetic gates, scheduling and lifecycle checks; no game or model execution."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_experiments as exp
import joint_chrono_qualification as trial


class QualificationContracts(unittest.TestCase):
    def metrics(self,win=.1,reward=.1,count=4096,lower=.01):
        return {name:dict(count=count,mean=value,lower95=lower,upper95=2*value-lower)
                for name,value in (('winShareDifference',win),('rewardDifference',reward))}

    def test_gate_uses99_bound_not_positive95_bound(self):
        self.assertFalse(trial.gate(self.metrics(lower=.01)))

    def test_both_exact4096_positive99_bounds_are_required(self):
        self.assertTrue(trial.gate(self.metrics(lower=.09)))
        self.assertFalse(trial.gate(self.metrics(count=4095,lower=.09)))

    def test_one_failing_reward_bound_rejects_even_with_win_gain(self):
        metrics=self.metrics(lower=.09)
        metrics['rewardDifference']=dict(count=4096,mean=0,lower95=-.01,upper95=.01)
        self.assertFalse(trial.gate(metrics))

    def test_replay_schedule_has_exact8sides_per_stage(self):
        mapped=[(index//512,index%512,side) for index in trial.REPLAYS
                for side in ('candidate','incumbent')]
        self.assertEqual(mapped,[(0,0,'candidate'),(0,0,'incumbent'),
            (2,341,'candidate'),(2,341,'incumbent'),(5,170,'candidate'),(5,170,'incumbent'),
            (7,511,'candidate'),(7,511,'incumbent')])

    def test_rejected_confirmation_does_not_call_native_or_open_heldout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            with patch.object(trial,'heldout_ready',side_effect=ValueError('not admitted')):
                with patch.object(exp,'native') as native:
                    with self.assertRaisesRegex(ValueError,'not admitted'):
                        trial.stage({},root/'protocol.json',root,'heldout')
            native.assert_not_called()
            self.assertFalse((root/'heldout').exists())

    def test_campaign_has_one_attempt_per4hour_stage_with_global8hour_limit(self):
        protocol=dict(maximumSeconds=28800,maximumDataBytes=8*exp.GIB,
            maximumRssBytes=10*exp.GIB,minimumFreeBytes=8*exp.GIB,stageSeconds=14400,
            python=dict(executable='/python'))
        result=trial.campaign_spec(Path('/package'),protocol,{})
        self.assertEqual(result['limits']['maximumSeconds'],28800)
        self.assertEqual([(s['id'],s['maximumSeconds'],s['maximumAttempts'],s['dependsOn'])
            for s in result['stages']],[('confirm',14400,1,[]),('heldout',14400,1,['confirm'])])


if __name__=='__main__':
    unittest.main()
