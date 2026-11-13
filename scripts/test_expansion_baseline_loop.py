"""Finite matched scheduling and provenance tests using only constructed metadata."""
import copy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import expansion_baseline_loop as loop


def protocol(root, replica='MAC_REPLICATION'):
    actor=root/'actor.json';actor.write_text('{}')
    sources={name:dict(path=str(Path(loop.__file__).resolve().with_name(name)),
                      sha256=loop.sha(Path(loop.__file__).with_name(name))) for name in loop.SCIENCE_SOURCES}
    return dict(kind=loop.VERSION,rounds=8,worldsPerBatch=256,prefixRounds=2,
        arms=list(loop.ARMS),automaticRetry=False,automaticSuccessor=False,intermediateScreens=[],
        offsets=list(range(8)),shuffleSeeds=[1000000+4*i for i in range(8)],
        workers=loop.HOSTS[replica][0],machineCpus=loop.HOSTS[replica][1],replica=replica,
        screenBank=dict(nativeOffset=20,count=1024),maximumTerminalExecutions=7198,
        maximumDataBytes=10000,screenMaximumBytes=1000,updateSeconds=900,
        actor=str(actor),actorSha256=loop.sha(actor),arena=str(actor),arenaSha256=loop.sha(actor),
        prepared=dict(path=str(actor),sha256=loop.sha(actor)),scientificSources=sources,
        files={str(actor):loop.sha(actor),**{item['path']:item['sha256'] for item in sources.values()}},
        prefixGates=dict(minimumBranchingWorldsPerArm=2,minimumCardOfferWorldsPerArm=2,
            maximumSecondsPerRound=20,maximumBytesPerRound=1000,screenReserveSeconds=50),
        baselineRecipe=dict(kind='initial-expansion-hidden-ridge-1',hiddenUnits=64,penalty=1.,
            interceptPenalized=False,rowWeight='one-over-world-rows',
            normalization='sum-world-mean-squared-errors',clipPrediction=[0.,1.],fits=7))


def rounds():
    return [dict(batch=i,complete=True,elapsedSeconds=10,retainedBytes=100,
        arms={arm:dict(worlds=256,reproductions=1,acceptedEpochs=1,actorChanged=True,
            actorAdamSteps=i,previousActorAdamSteps=i-1,
            exposure=dict(branchingWorlds=1,cardOfferWorlds=1)) for arm in loop.ARMS}) for i in (1,2)]


class PlanTests(unittest.TestCase):
    def setUp(self):
        temporary=TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name);self.protocol=protocol(self.root)

    def test_matching_worlds_and_shuffles_are_shared_but_order_alternates(self):
        plan=loop.Plan.read(self.protocol)
        self.assertEqual([loop.order(i) for i in (1,2)], [('CONTROL','TREATMENT'),('TREATMENT','CONTROL')])
        self.assertEqual([plan.source(self.protocol,i,self.protocol['actor'])['replayIndices'] for i in range(1,9)],
            [[0],[85],[170],[255],[0],[85],[170],[255]])

    def test_two_host_profiles_preserve_same_recipe(self):
        desktop=protocol(self.root,'PRIMARY_DESKTOP')
        self.assertEqual(loop.Plan.read(desktop).workers,10)
        self.assertEqual(loop.Plan.read(self.protocol).workers,6)
        self.assertEqual(desktop['baselineRecipe'],self.protocol['baselineRecipe'])

    def test_native_worker_allowance_cannot_be_silently_changed(self):
        self.protocol['workers']=8
        with self.assertRaisesRegex(ValueError,'host CPU'):loop.Plan.read(self.protocol)

    def test_screen_bank_cannot_reuse_training(self):
        self.protocol['screenBank']['nativeOffset']=0
        with self.assertRaisesRegex(ValueError,'separate'):loop.Plan.read(self.protocol)

    def test_world_collisions_between_rounds_reject(self):
        self.protocol['offsets'][1]=8191
        with self.assertRaisesRegex(ValueError,'collide'):loop.Plan.read(self.protocol)

    def test_epoch_shuffle_overlap_rejects(self):
        self.protocol['shuffleSeeds'][1]=1000003
        with self.assertRaisesRegex(ValueError,'collide'):loop.Plan.read(self.protocol)

    def test_ridge_normalization_is_sum_not_mean(self):
        self.protocol['baselineRecipe']['normalization']='mean-world-mse'
        with self.assertRaisesRegex(ValueError,'cannot be tuned'):loop.Plan.read(self.protocol)

    def test_prefix_projects_remaining_six_rounds_plus_screen(self):
        plan=loop.Plan.read(self.protocol)
        self.assertTrue(plan.prefix_passed(rounds(),110))
        self.assertFalse(plan.prefix_passed(rounds(),109))

    def test_each_arm_requires_exposure_and_progress(self):
        values=rounds()
        for value in values:value['arms']['TREATMENT']['actorAdamSteps']=0
        self.assertFalse(loop.Plan.read(self.protocol).prefix_passed(values,1000))

    def test_gate_ignores_rewards_and_prediction_error(self):
        values=rounds();other=copy.deepcopy(values)
        for value in other:value.update(reward=-999,mse=10000)
        plan=loop.Plan.read(self.protocol)
        self.assertEqual(plan.prefix_passed(values,1000),plan.prefix_passed(other,1000))

    def test_initializer_and_predecessor_are_explicit_per_arm(self):
        self.assertEqual(loop.expected_actor(self.protocol,self.root,1,'TREATMENT'),Path(self.protocol['actor']))
        self.assertEqual(loop.expected_actor(self.protocol,self.root,3,'CONTROL'),
            self.root/'batches/batch-02/CONTROL/numeric/actor/policy.json')

    def test_shortened_actor_cannot_be_marked_complete(self):
        path=self.root/'protocol.json';loop.document(path,self.protocol)
        with self.assertRaisesRegex(ValueError,'shortened'):loop.finish(self.root,rounds(),'both-eight-complete',self.protocol,path)
        self.assertFalse((self.root/'finished.json').exists())

    def test_prefix_stop_retains_original_execution_count(self):
        path=self.root/'protocol.json';loop.document(path,self.protocol)
        result=loop.finish(self.root,rounds(),'operational-prefix-gate',self.protocol,path)
        self.assertEqual((result['scientificWorlds'],result['historicalReproductions']),(1024,4))
        self.assertFalse(result['eligibleFinalActors'])

    def test_first_parity_reads_all_worlds_and_binds_optimizer(self):
        actor=dict(actor=1)
        for arm in loop.ARMS:
            path=loop.actor_directory(self.root,1,arm);path.mkdir(parents=True)
            loop.document(path/'policy.json',actor)
            loop.document(path/'checkpoint.json',dict(payload=dict(model=actor,
                optimizer={'state':{}},epoch=4,stopReason=None)))
        class Archive:
            metadata={'collection/replay-0.json':{'entries':['same']}}
            def games(self):return iter((f'game-{i}',{'index':i}) for i in range(256))
        with patch.object(loop.evidence,'Archived',return_value=Archive()):
            result=loop.verify_first_batch_parity(self.root)
        self.assertEqual(result['worlds'],256)
        self.assertFalse((self.root/'first-batch-parity.json').exists())

    def test_first_parity_rejects_changed_world_without_publishing(self):
        class Archive:
            metadata={}
            def __init__(self,changed):self.changed=changed
            def games(self):return iter((f'game-{i}',{'index':i,'value':int(self.changed and i==255)}) for i in range(256))
        with patch.object(loop.evidence,'Archived',side_effect=[Archive(False),Archive(True)]):
            with self.assertRaisesRegex(ValueError,'exactly equal'):loop.verify_first_batch_parity(self.root)
        self.assertFalse((self.root/'first-batch-parity.json').exists())


if __name__=='__main__':unittest.main()
