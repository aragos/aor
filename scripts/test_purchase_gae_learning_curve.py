"""Bounded continuation routing and stopping contracts; no real games or fits."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_ecology_data as data
import purchase_gae_learning_curve as curve
import purchase_gae_pilot as pilot


class LearningCurveTest(unittest.TestCase):
    def protocol(self):
        return dict(baseline='/m8',opponents=['/m8']*4,prior='/f0',purchasePolicy='/c64',
            initialActor='/exact44/policy.json',initialOptimizer='/exact44/checkpoint.json',
            expansionModel='/em8',experimentalRulesTransfer=curve.legacy.RULES_TRANSFER,
            banks={**{f'training-{i}':dict(nativeOffset=46000000+i) for i in range(1,65)},
                   **{f'checkpoint-{i}':dict(nativeOffset=46000100+i) for i in (16,32,64)}})

    def metrics(self,mean=.01,se=.01,count=512):
        return dict(winShareDifference=dict(mean=mean,lower95=mean-1.96*se,
                                            upper95=mean+1.96*se,count=count))

    def test_first_collection_uses44_while_mixed_rivals_keep_c64(self):
        protocol=self.protocol()
        source=curve.batch_source(protocol,1,protocol['initialActor'])
        self.assertEqual(source['policy'],'/exact44/policy.json')
        self.assertEqual(source['opponentPurchasePolicy'],'/c64')
        self.assertEqual(source['tokenOrder']['opponentSelectors'],['ORDINARY','ORDINARY',data.FIXED,data.FIXED])
        self.assertEqual(source['expansionImitation']['opponentModels'],[None,'/em8',None,'/em8'])

    def test_last_training_batch_uses_immediate_previous_actor(self):
        source=curve.batch_source(self.protocol(),64,'/run/batches/batch-63/gae/update/policy.json')
        self.assertEqual(source['policy'],'/run/batches/batch-63/gae/update/policy.json')
        self.assertEqual(source['seed'],46000064)

    def test_extra_training_bank_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'64 prospectively'):
            curve.batch_source(self.protocol(),65,'/actor')

    def test_development_baseline_is44_and_every_rival_remains_qualified(self):
        source=curve.arena_source(self.protocol(),32,'/candidate')
        self.assertEqual(source['frozenPurchase'],dict(baseline='/f0',policy='/c64',
            candidatePolicy='/candidate',incumbentPolicy='/exact44/policy.json'))
        self.assertEqual(source['expansionImitation']['opponentModels'],['/em8']*4)
        self.assertEqual(source['tokenOrder']['opponentSelectors'],[data.FIXED]*4)

    def test_intermediate_look_cannot_be_added(self):
        with self.assertRaisesRegex(ValueError,'checkpoints16,32,64'):
            curve.arena_source(self.protocol(),48,'/candidate')

    def test_futility_uses_one_sided95_not_existing_two_sided_upper95(self):
        metrics=self.metrics(mean=.012,se=.01)
        self.assertGreater(metrics['winShareDifference']['upper95'],.03)
        result=curve.futility(metrics)
        self.assertAlmostEqual(result['upperConfidence'],.02844853626951472)
        self.assertTrue(result['stop'])

    def test_gain_equal_to_threshold_is_not_futile(self):
        self.assertFalse(curve.futility(self.metrics(mean=.03,se=0))['stop'])

    def test_uncertain_flat_gain_continues(self):
        self.assertFalse(curve.futility(self.metrics(mean=0,se=.02))['stop'])

    def test_futility_rejects_incomplete_world_bank(self):
        with self.assertRaisesRegex(ValueError,'all512'):
            curve.futility(self.metrics(count=511))

    def test_accepted_epoch_resets_consecutive_stall(self):
        self.assertEqual(curve.zero_streak(3,1),0)

    def test_fourth_zero_batch_reaches_stop(self):
        self.assertEqual(curve.zero_streak(3,0),4)

    def test_legacy_first_batch_still_initializes_fresh(self):
        self.assertIsNone(pilot.previous_optimizer(Path('/run/batches/batch-1/gae'),1,'gae'))

    def test_explicit_initial_checkpoint_is_used_only_for_first_batch(self):
        initial=Path('/exact44/checkpoint.json')
        self.assertEqual(pilot.previous_optimizer(Path('/run/batches/batch-1/gae'),1,'gae',initial),initial)
        self.assertEqual(pilot.previous_optimizer(Path('/run/batches/batch-2/gae'),2,'gae',initial),
                         Path('/run/batches/batch-1/gae/update/checkpoint.json'))

    def test_earlier_checkpoint_never_becomes_eligible_after_stop(self):
        with patch.object(curve,'sha',return_value='protocol'),patch.object(curve,'document'):
            result=curve.progress(Path('/protocol'),Path('/out'),[{'modelSha256':'actor'}]*32,
                [{'batch':16},{'batch':32}],'prespecified32-batch-futility',0)
        self.assertFalse(result['finalCheckpointEligibleForReview'])
        self.assertIsNone(result['eligibleActorSha256'])
        self.assertEqual(result['scientificTerminalGames'],10240)

    def test_only_completed64_and_its_screen_allow_later_review(self):
        with patch.object(curve,'sha',return_value='protocol'),patch.object(curve,'document'):
            result=curve.progress(Path('/protocol'),Path('/out'),[{'modelSha256':'last'}]*64,
                [{'batch':16},{'batch':32},{'batch':64}],'all64-batches-complete',0)
        self.assertTrue(result['finalCheckpointEligibleForReview'])
        self.assertEqual(result['eligibleActorSha256'],'last')
        self.assertEqual(result['scientificTerminalGames']+result['historicalReproductions'],19736)
        self.assertFalse(result['qualificationPassed'])

    def test_partial_batch_refuses_resume_before_any_simulation(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary);(output/'batches').mkdir()
            with patch.object(curve.exp,'native') as native:
                with self.assertRaisesRegex(ValueError,'cannot be resumed'):
                    curve.train(self.protocol(),output/'protocol',output)
            native.assert_not_called()

    def test_supervisor_retains_single_original_six_hour_attempt(self):
        protocol=dict(python=dict(executable='/python'))
        spec=curve.campaign_spec(Path('/package'),protocol,{'/pin':'hash'})
        self.assertEqual(spec['limits']['maximumSeconds'],21600)
        self.assertEqual(len(spec['stages']),1)
        self.assertEqual(spec['stages'][0]['maximumAttempts'],1)
        self.assertEqual(spec['stages'][0]['maximumSeconds'],21600)

    def run_fake_curve(self,accepted,futile):
        batches=[];looks=[]
        def batch(_protocol,_path,_output,index,model):
            self.assertEqual(str(model),'/exact44/policy.json' if index==1 else f'/actor-{index-1}')
            batches.append(index)
            return Path(f'/actor-{index}'),dict(modelSha256=f'actor-{index}',
                                               update=dict(acceptedEpochs=accepted))
        def checkpoint(_protocol,_path,_output,index,_model):
            looks.append(index)
            return dict(batch=index,futility={'stop':futile} if index==32 else None)
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)
            with patch.object(curve,'train_batch',side_effect=batch), \
                 patch.object(curve,'checkpoint',side_effect=checkpoint), \
                 patch.object(curve.exp,'remaining_seconds',return_value=1000), \
                 patch.object(curve,'sha',return_value='protocol'), \
                 patch('builtins.print'):
                result=curve.train(self.protocol(),output/'protocol',output)
        return batches,looks,result['metrics']

    def test_futile32_stops_before_any_later_collection(self):
        batches,looks,report=self.run_fake_curve(4,True)
        self.assertEqual(batches,list(range(1,33)))
        self.assertEqual(looks,[16,32])
        self.assertEqual(report['stopReason'],'prespecified32-batch-futility')
        self.assertFalse(report['finalCheckpointEligibleForReview'])

    def test_stall_stops_before_any_development_look(self):
        batches,looks,report=self.run_fake_curve(0,False)
        self.assertEqual(batches,[1,2,3,4])
        self.assertEqual(looks,[])
        self.assertEqual(report['stopReason'],'four-consecutive-zero-accepted-batches')

    def test_nonfutile_run_finishes64_without_extra_looks(self):
        batches,looks,report=self.run_fake_curve(4,False)
        self.assertEqual(batches,list(range(1,65)))
        self.assertEqual(looks,[16,32,64])
        self.assertEqual(report['stopReason'],'all64-batches-complete')
        self.assertTrue(report['finalCheckpointEligibleForReview'])


if __name__=='__main__':unittest.main()
