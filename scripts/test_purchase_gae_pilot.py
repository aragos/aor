"""Prospective eight-batch routing tests; no real fitting or game execution."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_gae_pilot as pilot
import purchase_gae as gae
import purchase_baseline_pilot as matched
import test_purchase_baseline_pilot as fixtures


class PurchaseGaePilotTest(unittest.TestCase):
    def test_numerical_and_supervisor_estimators_match(self):
        self.assertEqual(pilot.ESTIMATOR,gae.ESTIMATOR)

    def test_each_batch_declares_its_current_arm_actor_and_shared_fresh_worlds(self):
        protocol=fixtures.MatchedPilotTest().protocol()
        protocol['banks']={**{f'training-{i}':dict(nativeOffset=44000000+i) for i in range(1,9)},
                           'screen':dict(nativeOffset=44000100)}
        for index in range(1,9):
            first=pilot.batch_source(protocol,index,f'/mc/batch-{index-1}.json')
            second=pilot.batch_source(protocol,index,f'/gae/batch-{index-1}.json')
            self.assertEqual(first['seed'],44000000+index)
            self.assertEqual(first['policy'],f'/mc/batch-{index-1}.json')
            self.assertEqual(second['policy'],f'/gae/batch-{index-1}.json')
            first['policy']=second['policy']
            self.assertEqual(first,second)
            self.assertEqual(second['opponentPurchasePolicy'],'/c64')

    def test_only_final_eighth_actor_paths_are_screened(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            observed=[]
            def source(_protocol,model):
                observed.append(model)
                raise RuntimeError('stop before any executor')
            with patch('automata_campaign.validate_result',return_value={'status':'passed'}),\
                 patch.object(matched,'arena_source',side_effect=source):
                with self.assertRaisesRegex(RuntimeError,'before any executor'):
                    pilot.screen({},root/'protocol.json',root)
            self.assertEqual(observed,[root/'batches/batch-8/mc/update/policy.json'])

    def test_extra_training_batch_is_rejected(self):
        protocol=fixtures.MatchedPilotTest().protocol()
        protocol['banks']={f'training-{i}':dict(nativeOffset=44000000+i) for i in range(1,9)}
        with self.assertRaisesRegex(ValueError,'eight frozen'):
            pilot.batch_source(protocol,9,'/actor.json')


if __name__=='__main__':unittest.main()
