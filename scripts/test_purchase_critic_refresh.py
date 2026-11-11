"""Pure matched-critic orchestration tests; no native games, real collections or fitting."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_critic_refresh as study


class CriticRefreshStudyTest(unittest.TestCase):
    def protocol(self):
        return dict(baseline='/m8',expansionModel='/em8',purchasePolicy='/g2',initialActor='/g2',
            prior='/f0',opponents=['/m8']*4,experimentalRulesTransfer=study.legacy.RULES_TRANSFER,
            banks={**{f'training-{i}':{'nativeOffset':i} for i in range(1,17)},'screen':{'nativeOffset':20}},
            critics={arm:{'checkpoint':f'/{arm}.pt','inferenceContract':f'/{arm}.json'} for arm in study.ARMS})

    def arm_state(self):
        return dict(completedBatches=0,acceptedEpochs=0,consecutiveZeroAcceptedBatches=0,stopReason=None)

    def game(self,score):
        return dict(wins=[score],rewards=[score],ranks=[score],scores=[score],decisions=3,elapsedMillis=1)

    def pairs(self):
        rows = {name:[] for name in study.COMPARISONS}
        for i in range(1024):
            shared=dict(index=i,configuration='fixture',seed=i,seat=i%4,opponentIndices=[0,1,2,3])
            rows['old-anchor'].append(dict(shared,candidate=self.game(1),incumbent=self.game(0)))
            rows['refreshed-anchor'].append(dict(shared,candidate=self.game(2),incumbent=self.game(0)))
            rows['refreshed-old'].append(dict(shared,candidate=self.game(2),incumbent=self.game(1)))
        return rows

    def test_arm_protocol_changes_only_frozen_critic_and_descriptor(self):
        original=self.protocol(); arm=study.arm_protocol(original,'refreshed')
        self.assertEqual(dict(original,critic='/refreshed.pt',inferenceContract='/refreshed.json'),arm)
        self.assertNotIn('initialOptimizer',arm)

    def test_common_training_bank_retains_fixed_g2_rivals(self):
        protocol=self.protocol()
        old=study.batch_source(protocol,7,'/old-actor');new=study.batch_source(protocol,7,'/new-actor')
        self.assertEqual(dict(old,policy='/new-actor'),new)
        self.assertEqual('/g2',old['opponentPurchasePolicy'])
        self.assertEqual(['/em8']*4,old['expansionImitation']['opponentModels'])
        self.assertEqual([study.data.FIXED]*4,old['tokenOrder']['opponentSelectors'])

    def test_batch_beyond_sixteen_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'sixteen'):
            study.batch_source(self.protocol(),17,'/actor')

    def test_both_first_optimizers_are_absent(self):
        for arm in study.ARMS:
            directory=study.batch_directory(Path('/run'),1,arm)
            self.assertIsNone(study.pilot.previous_optimizer(directory,1,'gae'))

    def test_later_optimizer_stays_in_its_own_arm(self):
        directory=study.batch_directory(Path('/run'),4,'refreshed')
        self.assertEqual(Path('/run/arms/refreshed/batches/batch-3/gae/update/checkpoint.json'),
                         study.pilot.previous_optimizer(directory,4,'gae'))

    def test_numeric_rejects_cross_arm_directory_before_updater(self):
        with patch.object(study.pilot,'numeric') as update:
            with self.assertRaisesRegex(ValueError,'exact arm lineage'):
                study.numeric(self.protocol(),Path('/run/arms/old/batches/batch-1/gae'),1,'refreshed')
        update.assert_not_called()

    def test_numeric_passes_no_initial_optimizer_to_unchanged_owner(self):
        protocol=self.protocol(); directory=study.batch_directory(Path('/run'),1,'old')
        with patch.object(study.pilot,'numeric') as update:
            study.numeric(protocol,directory,1,'old')
        self.assertNotIn('initial_optimizer',update.call_args.kwargs)
        self.assertEqual('gae',update.call_args.args[3])
        self.assertEqual(study.critics.CriticRole.OLD,update.call_args.kwargs['collection_adapter'].role)

    def test_four_zero_updates_stop_only_that_arm_state(self):
        old,new=self.arm_state(),self.arm_state()
        for _ in range(4):old=study.next_arm_state(old,0)
        self.assertEqual('four-consecutive-zero-accepted-batches',old['stopReason'])
        self.assertEqual(4,old['completedBatches']);self.assertIsNone(new['stopReason'])

    def test_an_accepted_epoch_resets_the_stall_streak(self):
        state=study.next_arm_state(study.next_arm_state(self.arm_state(),0),1)
        self.assertEqual(0,state['consecutiveZeroAcceptedBatches']);self.assertEqual(1,state['acceptedEpochs'])

    def test_stopped_arm_cannot_take_an_extra_update(self):
        state=dict(self.arm_state(),stopReason='four-consecutive-zero-accepted-batches')
        with self.assertRaisesRegex(ValueError,'Stopped or complete'):
            study.next_arm_state(state,1)

    def test_final_comparisons_keep_initial_g2_rivals(self):
        protocol=self.protocol();models={'old':'/old-final','refreshed':'/new-final'}
        source=study.arena_source(protocol,models,'refreshed-old')
        self.assertEqual(dict(baseline='/f0',policy='/g2',candidatePolicy='/new-final',incumbentPolicy='/old-final'),
                         source['frozenPurchase'])
        self.assertEqual(1024,source['pairsPerConfiguration'])
        self.assertEqual(study.arena_source(protocol,models,'old-anchor')['seed'],source['seed'])

    def test_common_final_worlds_reproduce_all_three_actor_roles(self):
        study.compare_final_parity(self.pairs())

    def test_final_direct_actor_mismatch_is_rejected(self):
        rows=self.pairs();rows['refreshed-old'][17]['candidate']['wins']=[0]
        with self.assertRaisesRegex(ValueError,'reproduce each terminal arm'):
            study.compare_final_parity(rows)

    def test_missing_final_world_is_rejected(self):
        rows=self.pairs();rows['old-anchor'].pop()
        with self.assertRaisesRegex(ValueError,'three complete'):
            study.compare_final_parity(rows)

    def test_elapsed_time_does_not_change_final_game_parity(self):
        rows=self.pairs();rows['refreshed-old'][0]['candidate']['elapsedMillis']=999
        study.compare_final_parity(rows)

    def test_full_budget_counts_are_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory).resolve();path=output/'protocol';path.write_text('{}')
            states={arm:dict(self.arm_state(),completedBatches=16) for arm in study.ARMS}
            cells=[{'comparison':name} for name in study.COMPARISONS]
            report=study.progress(path,output,states,cells)
        self.assertEqual(14336,report['scientificTerminalGames'])
        self.assertEqual(152,report['historicalReproductions'])
        self.assertEqual(14488,report['totalTerminalExecutions'])

    def test_arm_stop_is_followed_by_other_arm_completion_then_one_final_evaluation(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory).resolve();calls=[];final=[]
            def batch(protocol,path,root,index,arm,model):
                calls.append((arm,index))
                return root/f'{arm}-{index}.json',{'update':{'acceptedEpochs':0 if arm=='old' else 1}}
            def screens(protocol,path,root,models):
                final.append(list(calls))
                self.assertEqual(root/'old-4.json',models['old'])
                self.assertEqual(root/'refreshed-16.json',models['refreshed'])
                return [{'comparison':name,'metrics':{}} for name in study.COMPARISONS]
            with patch.object(study,'train_batch',side_effect=batch),patch.object(study,'final_screens',side_effect=screens), \
                 patch.object(study.exp,'remaining_seconds',return_value=1000),patch.object(study,'sha',return_value='0'*64):
                result=study.train(self.protocol(),output/'protocol',output)
        self.assertEqual([('old',i) for i in range(1,5)],[row for row in calls if row[0]=='old'])
        self.assertEqual([('refreshed',i) for i in range(1,17)],[row for row in calls if row[0]=='refreshed'])
        self.assertEqual([calls],final)
        self.assertFalse(result['metrics']['qualificationPassed'])
        self.assertFalse(result['metrics']['selectionPerformed'])

    def test_partial_attempt_refuses_any_new_update(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory).resolve();(output/'arms').mkdir()
            with patch.object(study,'train_batch') as update:
                with self.assertRaisesRegex(ValueError,'No automatic resume'):
                    study.train(self.protocol(),output/'protocol',output)
            update.assert_not_called()

    def test_campaign_keeps_original_three_hour_single_attempt_limit(self):
        campaign=study.campaign_spec(Path('/package'),{'python':{'executable':'/python'}},{})
        self.assertEqual(10800,campaign['limits']['maximumSeconds'])
        self.assertEqual(1,campaign['stages'][0]['maximumAttempts'])
        self.assertEqual('learn',campaign['stages'][0]['id'])


if __name__=='__main__':unittest.main()
