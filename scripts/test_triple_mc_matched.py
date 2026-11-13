"""Synthetic fixed-profile control flow; never reserves seeds or runs scientific children."""
import copy
import gzip
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import triple_mc_matched as loop
import triple_mc_matched_storage as storage


def protocol(root):
    root.mkdir(parents=True,exist_ok=True);root=root.resolve()
    for p in loop.PHASES:loop.document(root/f'{p}.json',{'UNIT':p})
    heads = {p:loop.subject(root/f'{p}.json') for p in ('card','purchase')}
    loop.document(root/'bundle.json',heads)
    template = dict(bundle=str(root/'bundle.json'),opponents=[str(root/'bundle.json')],UNIT=True)
    loop.document(root/'template.json',template)
    return dict(loop.LIMITS,kind=loop.VERSION,automaticRetry=False,strengthScreens=[],prefixGates=loop.PREFIX,
        offsets=list(range(32)),shuffleSeeds=[{p:100000+12*i+4*j for j,p in enumerate(loop.PHASES)} for i in range(32)],
        initialExpansion=loop.subject(root/'expansion.json'),fixedHeads=heads,fixedBundle=loop.subject(root/'bundle.json'),
        jointSourceTemplate=loop.subject(root/'template.json'),files={str(p):loop.sha(p) for p in root.iterdir()},
        java={'executable':'UNIT-java'},runtime='/UNIT/runtime',numerical={'executable':'UNIT-python'})


def receipt(arm,index,progress=True):
    return dict(kind='triple-mc-matched-batch-1',arm=arm,batch=index,complete=True,worlds=256,reproductions=1,
        phases={p:dict(acceptedEpochs=int(progress),actorChanged=progress,actorAdamSteps=index if progress else 0,
            previousActorAdamSteps=index-1 if progress else 0,decisions=100,branchingWorlds=32) for p in loop.active(arm)},
        elapsedSeconds=10,retainedBytes=1000)


class PlanTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.protocol=protocol(self.root/'inputs');self.plan=loop.LoopPlan.read(self.protocol)
        self.receipts=[receipt(a,i) for i in range(1,5) for a in self.plan.order(i)]

    def test_fixed_schedule_alternates_without_changing_world_or_epoch_streams(self):
        self.assertEqual(('JOINT','E_ONLY'),self.plan.order(1));self.assertEqual(('E_ONLY','JOINT'),self.plan.order(2))
        self.assertEqual([0,85,170,255,0],[self.plan.replay(i) for i in range(1,6)])
        for arm in loop.ARMS:
            d=loop.batch_path(self.root/'run',arm,1);d.mkdir(parents=True)
            joint,_=self.plan.source(self.protocol,arm,1,d);loop.document(d/'joint-source.json',joint)
            _,outer=self.plan.source(self.protocol,arm,1,d)
            self.assertEqual(self.protocol['initialExpansion'],outer['expansion'])
            self.assertEqual(self.protocol['fixedBundle']['path'],joint['bundle'])

    def test_control_never_uses_a_new_cp_bundle(self):
        d=loop.batch_path(self.root/'run','E_ONLY',2);d.mkdir(parents=True)
        previous=d.parent/'batch-01/numeric/expansion/policy.json';loop.document(previous,{'UNIT':'next E'})
        joint,_=self.plan.source(self.protocol,'E_ONLY',2,d)
        self.assertEqual(self.protocol['fixedBundle']['path'],joint['bundle'])

    def test_overlapping_world_banks_reject(self):
        self.protocol['offsets'][1]=8191
        with self.assertRaisesRegex(ValueError,'distinct'):loop.LoopPlan.read(self.protocol)

    def test_overlapping_phase_epoch_streams_reject(self):
        self.protocol['shuffleSeeds'][1]['card']=self.protocol['shuffleSeeds'][0]['expansion']+1
        with self.assertRaisesRegex(ValueError,'distinct'):loop.LoopPlan.read(self.protocol)

    def test_resource_and_recipe_changes_reject(self):
        self.protocol['maximumSeconds']+=1
        with self.assertRaisesRegex(ValueError,'fixed recipe'):loop.LoopPlan.read(self.protocol)

    def test_changed_fixed_cp_subject_is_rejected(self):
        self.protocol['fixedHeads']['card']=self.protocol['fixedHeads']['purchase']
        with self.assertRaisesRegex(ValueError,'same exact'):loop.LoopPlan.read(self.protocol)

    def test_original_relative_bundle_components_resolve_without_rewriting_raw_subject(self):
        path=Path(self.protocol['fixedBundle']['path']);value=loop.read(path)
        for p in ('card','purchase'):value[p]['path']=p+'.json'
        loop.document(path,value,immutable=False)
        self.protocol['fixedBundle']=loop.subject(path);self.protocol['files'][str(path)]=loop.sha(path)
        original=path.read_bytes()
        loop.LoopPlan.read(self.protocol)
        self.assertEqual(original,path.read_bytes())

    def test_prefix_admits_only_balanced_exposure_and_actual_progress(self):
        self.assertTrue(self.plan.prefix_passed(self.receipts,20000,[21]*4))
        self.receipts[0]['phases']['card']['branchingWorlds']=31
        self.assertFalse(self.plan.prefix_passed(self.receipts,20000,[21]*4))

    def test_original_remaining_time_and_all_cleanup_costs_bind_prefix(self):
        self.assertFalse(self.plan.prefix_passed(self.receipts,1500,[21]*4))
        with self.assertRaisesRegex(ValueError,'wall time'):
            self.plan.prefix_passed(self.receipts,20000,[19]*4)

    def test_prefix_never_reads_rewards_or_losses(self):
        changed=copy.deepcopy(self.receipts)
        for r in changed:r.update(reward=-999,loss=999,winRate=0)
        self.assertEqual(self.plan.prefix_passed(self.receipts,20000,[21]*4),
            self.plan.prefix_passed(changed,20000,[21]*4))

    def test_four_stalled_active_batches_stop_both_before_next_round(self):
        output=self.root/'run';calls=[]
        def batch(p,pp,plan,out,arm,index):
            calls.append((arm,index));r=receipt(arm,index,progress=arm!='JOINT')
            loop.document(out/'arms'/arm/'receipts'/f'batch-{index:02d}.json',r)
            return r
        with patch.object(loop.os,'cpu_count',return_value=16),patch.object(loop.exp,'remaining_seconds',return_value=20000),\
             patch.object(loop,'train_batch',side_effect=batch),patch.object(loop.storage,'BatchIndex'),\
             patch.object(loop,'first_collection_parity',return_value={'UNIT':True}):
            result=loop.run(self.protocol,self.root/'protocol.json',output)
        self.assertEqual('four-no-progress-batches',result['stopReason'])
        self.assertFalse(result['eligibleFinalActors']);self.assertEqual(8,len(calls))

    def test_one_arm_exception_prevents_the_other_arm_from_continuing(self):
        output=self.root/'run'
        with patch.object(loop.os,'cpu_count',return_value=16),patch.object(loop.exp,'remaining_seconds',return_value=20000),\
             patch.object(loop,'train_batch',side_effect=ValueError('UNIT child failure')) as batch:
            with self.assertRaisesRegex(ValueError,'UNIT child failure'):
                loop.run(self.protocol,self.root/'protocol.json',output)
        self.assertEqual(1,batch.call_count);self.assertEqual({},loop.read(output/'finished.json')['finalBundles'])

    def test_shortened_final_actor_cannot_be_admitted(self):
        with self.assertRaisesRegex(ValueError,'both original final32'):
            loop.terminal(self.root/'run',self.receipts,None)

    def test_only_both_full32_can_publish_the_two_final_bundles(self):
        output=self.root/'run';calls=[]
        def batch(p,pp,plan,out,arm,index):
            calls.append((arm,index));r=receipt(arm,index)
            loop.document(out/'arms'/arm/'receipts'/f'batch-{index:02d}.json',r)
            loop.document(loop.batch_path(out,arm,index)/'numeric/triple-bundle.json',dict(UNIT=arm,batch=index))
            return r
        with patch.object(loop.os,'cpu_count',return_value=16),patch.object(loop.exp,'remaining_seconds',return_value=20000),\
             patch.object(loop,'train_batch',side_effect=batch),patch.object(loop.storage,'BatchIndex'),\
             patch.object(loop,'first_collection_parity',return_value={'UNIT':True}),\
             patch.object(loop.LoopPlan,'prefix_passed',return_value=True):
            result=loop.run(self.protocol,self.root/'protocol.json',output)
        self.assertEqual([(a,i) for i in range(1,33) for a in self.plan.order(i)],calls)
        self.assertEqual({'JOINT':32,'E_ONLY':32},result['completedBatches'])
        self.assertTrue(result['eligibleFinalActors']);self.assertEqual(16384,result['scientificWorlds'])
        self.assertEqual(64,result['historicalReproductions']);self.assertEqual(set(loop.ARMS),set(result['finalBundles']))

    def test_original_native_clock_is_used_without_hours_rounding(self):
        with patch.object(loop.exp,'remaining_seconds',return_value=73.9):
            command=loop.native_command(self.protocol,'triple-on-policy-reproduce','source','output')
        self.assertEqual('73',command[command.index('--seconds')+1]);self.assertNotIn('--hours',command)


def capsule(root,arm,index,previous=None,games=False):
    """Tiny invented storage bytes; no trajectories are passed through a scientific reader."""
    directory=loop.batch_path(root,arm,index);directory.mkdir(parents=True)
    for name in storage.TOP_FILES:
        loop.document(directory/name,{'UNIT':name})
    done=dict(kind='triple-on-policy-collect-1',complete=True,games=256,gameSha256={},canonicalSha256={})
    loop.document(directory/'numeric/finished.json',dict(kind='triple-mc-update-1' if arm=='JOINT'
        else 'triple-mc-e-only-control-1',complete=True))
    loop.document(directory/'numeric/triple-bundle.json',{'UNIT':'bundle'})
    old=None if previous is None else dict(path=str(Path(previous.document['originalRoot'])/'numeric'),
        completionSha256=previous.files['numeric/finished.json']['sha256'])
    loop.document(directory/'numeric/identity.json',dict(previous=old))
    for p in loop.active(arm):
        loop.document(directory/f'numeric/{p}/checkpoint.json',{'UNIT':p,'batch':index})
        loop.document(directory/f'numeric/{p}/identity.json',dict(optimizerFromSha256=None if previous is None
            else previous.files[f'numeric/{p}/checkpoint.json']['sha256']))
    loop.document(directory/'reproduction/replay.json',{'UNIT':'replay'})
    if games:
        for i in range(256):
            path=directory/f'collection/game-{i}.json.gz';path.parent.mkdir(exist_ok=True)
            path.write_bytes(gzip.compress(loop.json.dumps(dict(signature=arm,index=i,terminal='UNIT',
                expansionDecisions=[{'value':.5}])).encode(),mtime=0))
            done['gameSha256'][path.name]=loop.sha(path)
        loop.document(directory/'collection/replay-0.json',dict(trajectorySha256=done['gameSha256']['game-0.json.gz'],
            replayVerified=True,header={'UNIT':True},entries=[]))
        done['canonicalSha256']['replay-0.json']=loop.sha(directory/'collection/replay-0.json')
    loop.document(directory/'collection/finished.json',done)
    archive=root/'arms'/arm/'archives'/f'batch-{index:02d}.tar.gz'
    ref=storage.archive_batch(directory,archive,arm,index)
    return storage.BatchIndex(archive),ref


class CapsuleTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    def test_lossless_archive_retains_original_paths_and_latest_numeric(self):
        index,_=capsule(self.root,'JOINT',1);raw=dict(index.iter_members())
        storage.prune(index)
        self.assertEqual(raw,dict(index.iter_members()))
        self.assertTrue((Path(index.document['originalRoot'])/'numeric/finished.json').is_file())
        self.assertFalse((Path(index.document['originalRoot'])/'collection/finished.json').exists())
        self.assertEqual(index.files['numeric/finished.json']['sha256'],
            index.original_hashes[str(Path(index.document['originalRoot'])/'numeric/finished.json')])

    def test_changed_duplicate_prevents_any_deletion(self):
        index,_=capsule(self.root,'JOINT',1);root=Path(index.document['originalRoot'])
        (root/'collection.log').write_text('changed')
        with self.assertRaisesRegex(ValueError,'Changed or undeclared'):storage.prune(index)
        self.assertTrue((root/'collection/finished.json').exists())

    def test_consuming_completed_archive_is_required_before_carry_release(self):
        previous,_=capsule(self.root,'E_ONLY',1);current,ref=capsule(self.root,'E_ONLY',2,previous=previous)
        old=lambda name:dict(path=previous.files[name]['originalPath'],sha256=previous.files[name]['sha256'])
        r=dict(kind='triple-mc-matched-batch-1',complete=True,arm='E_ONLY',batch=2,capsule=ref,
            previousCompletion=old('numeric/finished.json'),
            previousCheckpoints={'expansion':old('numeric/expansion/checkpoint.json')})
        path=self.root/'consumer.json';loop.document(path,r)
        storage.release_numeric(previous,path,self.root/'release.json')
        self.assertFalse(Path(previous.files['numeric/finished.json']['originalPath']).exists())
        self.assertTrue(Path(current.files['numeric/finished.json']['originalPath']).exists())
        self.assertTrue(previous.read('numeric/finished.json')['complete'])

    def test_wrong_arm_consumer_cannot_release(self):
        previous,_=capsule(self.root,'JOINT',1)
        path=self.root/'consumer.json';loop.document(path,dict(kind='triple-mc-matched-batch-1',complete=True,
            arm='E_ONLY',batch=2,previousCompletion=None,previousCheckpoints={}))
        with self.assertRaisesRegex(ValueError,'next same-arm'):storage.release_numeric(previous,path,self.root/'release.json')
        self.assertTrue(Path(previous.files['numeric/finished.json']['originalPath']).exists())

    def test_path_traversal_and_symlink_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'Unsafe'):storage.relative('numeric/../../elsewhere')
        root=self.root/'batch';root.mkdir();(root/'numeric').symlink_to(self.root,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'symlink'):storage.members(root)

    def test_archive_corruption_is_detected(self):
        index,_=capsule(self.root,'JOINT',1)
        with index.archive.open('ab') as stream:stream.write(b'tamper')
        with self.assertRaisesRegex(ValueError,'changed'):storage.BatchIndex(index.archive)

    def test_first_collection_equality_excludes_only_source_signature(self):
        indices={a:capsule(self.root,a,1,games=True)[0] for a in loop.ARMS}
        parity=loop.first_collection_parity(indices)
        self.assertTrue(parity['passed']);self.assertTrue(parity['canonicalHeaderEntriesEqual'])

    def test_first_collection_behavior_difference_cannot_be_masked_by_signature_exclusion(self):
        indices={a:capsule(self.root,a,1,games=True)[0] for a in loop.ARMS}
        original=indices['E_ONLY'].iter_members
        def changed(*args,**kwargs):
            for name,raw in original(*args,**kwargs):
                if name == 'collection/game-0.json.gz':
                    value=loop.json.loads(gzip.decompress(raw));value['expansionDecisions'][0]['value']=.6
                    raw=gzip.compress(loop.json.dumps(value).encode())
                yield name,raw
        with patch.object(indices['E_ONLY'],'iter_members',side_effect=changed):
            with self.assertRaisesRegex(ValueError,'beyond their source signature'):
                loop.first_collection_parity(indices)

    def test_fake_consumer_receipt_does_not_override_actual_numeric_carry_identity(self):
        previous,_=capsule(self.root,'E_ONLY',1);current,ref=capsule(self.root,'E_ONLY',2)
        old=lambda name:dict(path=previous.files[name]['originalPath'],sha256=previous.files[name]['sha256'])
        r=dict(kind='triple-mc-matched-batch-1',complete=True,arm='E_ONLY',batch=2,capsule=ref,
            previousCompletion=old('numeric/finished.json'),
            previousCheckpoints={'expansion':old('numeric/expansion/checkpoint.json')})
        path=self.root/'consumer.json';loop.document(path,r)
        with self.assertRaisesRegex(ValueError,'Actual next phase identities'):
            storage.release_numeric(previous,path,self.root/'release.json')
        self.assertTrue(Path(previous.files['numeric/finished.json']['originalPath']).exists())


if __name__ == '__main__':unittest.main()
