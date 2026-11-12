"""Pure finite scheduling tests; all offsets are synthetic fixture values, never reservations."""
from pathlib import Path
from tempfile import TemporaryDirectory
import copy
import unittest
from unittest.mock import patch

import expansion_on_policy_loop as loop
import expansion_on_policy_data as data
import purchase_ecology_environment as environment


class LoopTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.actor=self.root/'actor.json';self.actor.write_text('{}')
        self.protocol=dict(kind=loop.VERSION,batches=64,worldsPerBatch=256,prefixBatches=4,
            automaticRetry=False,strengthScreens=[],offsets=list(range(64)),
            shuffleSeeds=[1000000+4*i for i in range(64)],workers=6,machineCpus=10,actor=str(self.actor),
            maximumDataBytes=8*1024**3,updateSeconds=900,
            arena=str(self.actor),actorSha256=loop.sha(self.actor),arenaSha256=loop.sha(self.actor),
            files={str(self.actor):loop.sha(self.actor)},prefixGates=dict(minimumBranchingWorlds=4,
                minimumCardOfferWorlds=4,maximumSecondsPerBatch=100,maximumBytesPerBatch=1000,reserveSeconds=50))
        self.receipts=[dict(batch=i,complete=True,worlds=256,reproductions=1,elapsedSeconds=10,
            acceptedEpochs=1,actorChanged=True,actorAdamSteps=i,previousActorAdamSteps=i-1,
            retainedBytes=100,exposure=dict(branchingWorlds=1,cardOfferWorlds=1)) for i in range(1,5)]

    def test_source_uses_fixed_worlds_and_rotating_prospective_replay(self):
        plan=loop.LoopPlan.read(self.protocol)
        self.assertEqual([plan.source(self.protocol,i,self.actor)['replayIndices'][0] for i in range(1,6)],
            [0,85,170,255,0])
        self.assertEqual(plan.source(self.protocol,64,self.actor)['seed'],63)

    def test_duplicate_worlds_reject(self):
        self.protocol['offsets'][1]=0
        with self.assertRaisesRegex(ValueError,'distinct'):loop.LoopPlan.read(self.protocol)

    def test_overlapping_epoch_shuffles_reject(self):
        self.protocol['shuffleSeeds'][1]=self.protocol['shuffleSeeds'][0]+3
        with self.assertRaisesRegex(ValueError,'distinct'):loop.LoopPlan.read(self.protocol)

    def test_no_early_strength_stage_or_retry_can_enter(self):
        self.protocol['strengthScreens']=['after-four']
        with self.assertRaisesRegex(ValueError,'no retries or strength'):loop.LoopPlan.read(self.protocol)

    def test_prefix_exposure_floor_retains_failed_prefix(self):
        plan=loop.LoopPlan.read(self.protocol);self.receipts[0]['exposure']['cardOfferWorlds']=0
        self.assertFalse(plan.prefix_passed(self.receipts,1000))

    def test_remaining_original_time_must_cover_declared_suffix(self):
        plan=loop.LoopPlan.read(self.protocol)
        self.assertTrue(plan.prefix_passed(self.receipts,651))
        self.assertFalse(plan.prefix_passed(self.receipts,649))

    def test_prefix_gate_does_not_use_reward_or_fit_quality(self):
        plan=loop.LoopPlan.read(self.protocol);other=copy.deepcopy(self.receipts)
        for row in other:row['reward']=-999;row['valueMse']=999
        self.assertEqual(plan.prefix_passed(self.receipts,1000),plan.prefix_passed(other,1000))

    def test_prefix_requires_actual_actor_and_adam_progress(self):
        plan=loop.LoopPlan.read(self.protocol)
        for row in self.receipts:row['actorChanged']=False
        self.assertFalse(plan.prefix_passed(self.receipts,1000))

    def test_accepted_counter_alone_is_not_learning_progress(self):
        row=dict(self.receipts[0],actorAdamSteps=0,previousActorAdamSteps=0)
        self.assertFalse(loop.learning_progress(row))

    def test_native_reproduction_has_original_seconds_bound(self):
        protocol=dict(java={'executable':'java'},heapMiB=1024,workers=6,machineCpus=10,runtime='/synthetic')
        with patch.object(loop.exp,'remaining_seconds',return_value=901.9):
            command=loop.native_command(protocol,'expansion-on-policy-reproduce','source','out')
        self.assertEqual(command[command.index('--seconds')+1],'901')
        self.assertNotIn('--hours',command)
        self.assertIn('-XX:ActiveProcessorCount=10',command)

    def test_first_batch_rejects_an_actor_other_than_the_declared_initializer(self):
        directory=self.root/'batches/batch-01';directory.mkdir(parents=True)
        substitute=self.root/'substitute.json';substitute.write_bytes(self.actor.read_bytes())
        plan=loop.LoopPlan.read(self.protocol)
        loop.document(directory/'collection-source.json',plan.source(self.protocol,1,substitute))
        with patch.object(environment,'verify'),patch.object(data,'ExpansionCollection') as reader:
            with self.assertRaisesRegex(ValueError,'exact initializer'):
                loop.numeric(dict(self.protocol,numerical={}),directory,1)
        reader.assert_not_called()

    def test_next_batch_rejects_stale_actor_before_opening_its_collection(self):
        directory=self.root/'batches/batch-02';directory.mkdir(parents=True)
        previous=self.root/'batches/batch-01/numeric/policy.json';previous.parent.mkdir(parents=True)
        previous.write_bytes(self.actor.read_bytes())
        plan=loop.LoopPlan.read(self.protocol)
        loop.document(directory/'collection-source.json',plan.source(self.protocol,2,self.actor))
        with patch.object(environment,'verify'),patch.object(data,'ExpansionCollection') as reader:
            with self.assertRaisesRegex(ValueError,'immediate preceding completed actor'):
                loop.numeric(dict(self.protocol,numerical={}),directory,2)
        reader.assert_not_called()


if __name__=='__main__':unittest.main()
