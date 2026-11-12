"""UNIT-only invented behavior; no native worlds, scientific actors or retained training rows."""
import copy
from dataclasses import replace
import gzip
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

import expansion_on_policy_data as expansion
import joint_trajectory as joint
import purchase_policy as policy
import triple_mc as mc
import triple_mc_update as update
import triple_trajectory as data
import test_joint_current_ecology as current_fixture
from territory_trajectory import bot_seed, signed


def put(path, value):
    policy.atomic_json(path, value)
    return path


class TripleFixture(unittest.TestCase):
    def setUp(self):
        self.base = current_fixture.CurrentReader(); self.base.setUp(); self.addCleanup(self.base.doCleanups)
        self.root = self.base.root
        self.config = policy.UpdateConfig(epochs=1,valueCoefficient=0,maxBehaviorKl=2,maxSeconds=60)
        self.configs = dict.fromkeys(mc.PHASES,self.config)
        # The legacy fixture intentionally has a null-card rival. New triple evidence requires both.
        rival = joint.read(self.root/'rival.json'); rival['card'] = joint.read(self.root/'bundle.json')['card']
        put(self.root/'rival.json', rival)
        self.expansion_path = put(self.root/'expansion-policy.json',policy.initialize(dict(
            encoderVersion=mc.CONTRACTS['expansion'].encoder,stateFeatureNames=['state'],
            actionFeatureNames=['choice']),37,contract=mc.CONTRACTS['expansion']))

    def directory(self, name='triple', previous=None, singleton=False, empty_phase=None):
        directory = self.root/name; directory.mkdir()
        bundle_path = self.root/'bundle.json' if previous is None else previous/'bundle.json'
        e_path = self.expansion_path if previous is None else previous/'expansion/policy.json'
        cp, models = joint.load_bundle(bundle_path,joint.CONFIGURATION,joint.sha(self.root/'F.json'),{})
        models['expansion'] = joint.read(e_path)
        rival, _ = joint.load_bundle(self.root/'rival.json',joint.CONFIGURATION,joint.sha(self.root/'F.json'),{})
        source_joint = dict(self.base.source,bundle=str(bundle_path))
        joint_path = put(self.root/(name+'-joint-source.json'),source_joint)
        source_path = put(self.root/(name+'-source.json'),dict(kind=data.KIND,
            jointSource=dict(path=str(joint_path),sha256=joint.sha(joint_path)),
            expansion=dict(path=str(e_path),sha256=joint.sha(e_path))))
        snapshots = {}
        def snapshot(path,name):
            target=directory/name;target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(path,target);snapshots[name]=dict(path=str(path),sha256=joint.sha(path))
            return dict(snapshots[name],snapshot=name)
        source = snapshot(source_path,'source.json');js = snapshot(joint_path,'joint/source.json')
        e = snapshot(e_path,'expansion.json')
        for path,name in [(self.base.baseline,'baseline.json'),(self.root/'F.json','shared-purchase-baseline.json'),
            (self.base.expansion,'expansion-imitation-candidate.json'),
            (self.base.expansion,'expansion-imitation-opponent-0.json')]:snapshot(path,'joint/'+name)
        for bundle,prefix in ((cp,''),(rival,'opponent-0-')):
            snapshot(Path(bundle['path']),f'joint/{prefix}bundle.json')
            for p in ('card','purchase'):snapshot(Path(bundle[p]['path']),f'joint/{prefix}{p}-policy.json')
        jm = dict(self.base.manifest,source=str(joint_path),sourceSha256=joint.sha(joint_path),
            bundle=cp,opponents=[rival],controlledSeat='index % playerCount',
            opponentRotation='(index / playerCount + seat) % opponentCount',reward='.8 * winShare + .2 * rankReward')
        e.update(modelSchemaVersion=2,actorVersion=mc.CONTRACTS['expansion'].actor,
            **{k:models['expansion'][k] for k in ('encoderVersion','stateFeatureNames','actionFeatureNames')})
        heads=dict(expansion=e,**{p:cp[p] for p in ('card','purchase')})
        manifest=dict(kind=data.KIND,schemaVersion=1,signature=jm['signature'],trajectoryEncoding='gzip-json-v1',
            rulesVersion=jm['rulesVersion'],dataVersion=jm['dataVersion'],source=source,jointSource=js,
            joint=jm,expansion=e,controlledBehavior={p:heads[p]['sha256'] for p in mc.PHASES},
            snapshots=snapshots,replayIndices=[0])
        put(directory/'collection.json',manifest)
        put(directory/'state.json',dict(kind=data.KIND,signature=manifest['signature'],phase='complete',completedGames=2))
        for index in range(2):
            old=data.read(self.base.directory/f'game-{index}.json.gz')
            game=dict(old,kind=data.TRAJECTORY,bundleSha256=cp['sha256'],jointEvidence=[],
                **{p+'ModelSha256':heads[p]['sha256'] for p in mc.PHASES})
            for p in mc.PHASES:
                number={'expansion':2,'card':0,'purchase':1}[p]
                commands=['pass'] if singleton else (['pass','play hand-1'] if p=='card' else
                    ['buy-advance A','pass'] if p=='purchase' else ['pass','place synthetic 1'])
                obs=dict(encoderVersion=mc.CONTRACTS[p].encoder,stateFeatures={'state':.2+index*.1},
                    baselineIndex=commands.index('pass'),actions=[dict(command=c,features={'choice':float(i)})
                        for i,c in enumerate(commands)])
                seed=(signed(bot_seed(game['gameSeed'],number,index)^expansion.ACTOR_SALT) if p=='expansion'
                    else joint.actor_seed(game['gameSeed'],number,index,p))
                result=policy.reference_forward(models[p],obs,contract=mc.CONTRACTS[p])
                selected=policy.select(result['probabilities'],policy.selection_uniform(seed))
                row=dict(number=number,turn=1,actorSeed=seed,decision='expand' if p=='expansion' else
                    'play-cards' if p=='card' else 'purchase-advances',observation=obs,selectedIndex=selected,
                    behaviorLogProbability=policy.math.log(result['probabilities'][selected]),behaviorValue=result['value'])
                if p=='expansion':row.update(batchStartNumber=number,botSeed=bot_seed(game['gameSeed'],number,index))
                game[p+'Decisions']=[row] if p=='expansion' or index==int(p=='purchase') else []
                if p == empty_phase:game[p+'Decisions']=[]
            (directory/f'game-{index}.json.gz').write_bytes(gzip.compress(policy.canonical(game),mtime=0))
        self.finish(directory)
        return directory

    def finish(self,directory):
        manifest=data.read(directory/'collection.json')
        put(directory/'replay-0.json',dict(kind='triple-on-policy-canonical-1',signature=manifest['signature'],
            index=0,trajectorySha256=joint.sha(directory/'game-0.json.gz'),replayVerified=True,
            header={'synthetic':True},entries=[],entriesSha256=policy.digest([])))
        put(directory/'finished.json',dict(kind=data.KIND,signature=manifest['signature'],complete=True,games=2,
            gameSha256={f'game-{i}.json.gz':joint.sha(directory/f'game-{i}.json.gz') for i in range(2)},
            canonicalSha256={'replay-0.json':joint.sha(directory/'replay-0.json')}))

    def collection(self,**kwargs):return data.TripleCollection(self.directory(**kwargs))

    def change(self,directory,index,change):
        path=directory/f'game-{index}.json.gz';game=data.read(path);change(game)
        path.write_bytes(gzip.compress(policy.canonical(game),mtime=0));self.finish(directory)


class TripleReaderTests(TripleFixture):
    def test_three_original_views_retain_missing_phase_worlds(self):
        c=self.collection()
        self.assertEqual([1,0],[r['cardDecisions'] for r in c.origins])
        self.assertEqual([0,1],[r['purchaseDecisions'] for r in c.origins])
        self.assertEqual([1,1],[r['expansionDecisions'] for r in c.origins])
        self.assertEqual(set(c.heads),set(mc.PHASES))

    def test_original_rows_remain_identical_except_detached_mc_advantage(self):
        c=self.collection()
        for phase in mc.Phase:
            obj=mc.TripleMcObjective(c,phase);head=c.heads[phase.value]
            rows,p=obj.phase_reader(c.directory,head['path'],c.models[phase.value],contract=mc.CONTRACTS[phase.value])
            _,identity=obj.bind(rows,p,head['sha256'])
            self.assertEqual(identity['behaviorModels'],c.common['behaviorModels'])
            self.assertEqual(p['collectionReaderSha256'],joint.sha(data.__file__))
            self.assertEqual(p['readerSha256'],joint.sha(mc.__file__))
            self.assertEqual(c.pins[str(Path(data.__file__).resolve())],joint.sha(data.__file__))
            for row,original in zip(rows,c.rows[phase.value]):
                self.assertEqual(row['_actorOnlyAdvantage'],original['return']-.5)
                self.assertEqual({k:v for k,v in row.items() if k!='_actorOnlyAdvantage'},original)

    def test_legacy_joint_reader_rejects_new_kind(self):
        directory=self.directory()
        with self.assertRaises(ValueError):joint.JointCollection(directory,self.root/'bundle.json')

    def test_legacy_expansion_reader_rejects_new_kind(self):
        directory=self.directory()
        with self.assertRaises(ValueError):expansion.ExpansionCollection(directory,self.expansion_path)

    def test_legacy_objectives_do_not_accept_triple_collection(self):
        with self.assertRaisesRegex(ValueError,'separately typed'):
            mc.expansion_mc.ExpansionMcObjective(self.collection())

    def test_extra_private_row_field_is_rejected_even_when_resealed(self):
        d=self.directory();self.change(d,0,lambda g:g['cardDecisions'][0].update(rivalHand=['private']))
        with self.assertRaisesRegex(ValueError,'private'):data.TripleCollection(d)

    def test_phase_overlap_is_rejected(self):
        d=self.directory();self.change(d,0,lambda g:g['expansionDecisions'][0].update(number=0))
        with self.assertRaises(ValueError):data.TripleCollection(d)

    def test_likelihood_tamper_is_rejected(self):
        d=self.directory();self.change(d,0,lambda g:g['expansionDecisions'][0].update(behaviorLogProbability=-2.))
        with self.assertRaisesRegex(ValueError,'likelihood/value'):data.TripleCollection(d)

    def test_original_absolute_seed_is_required(self):
        d=self.directory();self.change(d,1,lambda g:g['purchaseDecisions'][0].update(actorSeed=1))
        with self.assertRaisesRegex(ValueError,'absolute sampler'):data.TripleCollection(d)

    def test_snapshot_change_blocks_collection(self):
        d=self.directory();(d/'joint/opponent-0-card-policy.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'snapshot changed'):data.TripleCollection(d)

    def test_missing_original_world_is_rejected(self):
        d=self.directory();(d/'game-1.json.gz').unlink()
        with self.assertRaisesRegex(ValueError,'exactly once'):data.TripleCollection(d)

    def test_singleton_forced_rows_are_retained(self):
        c=self.collection(singleton=True)
        self.assertTrue(all(row['behaviorLogProbability']==0 for rows in c.rows.values() for row in rows))

    def test_resealed_canonical_entry_tamper_is_rejected(self):
        d=self.directory();path=d/'replay-0.json';value=data.read(path);value['entries']=['different']
        put(path,value);done=data.read(d/'finished.json');done['canonicalSha256'][path.name]=joint.sha(path)
        put(d/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'canonical replay'):data.TripleCollection(d)


class TripleUpdateTests(TripleFixture):
    def test_actual_unit_updates_publish_all_three_with_frozen_values(self):
        c=self.collection();out=self.root/'update';report=update.update_triple(c,self.configs,out)
        self.assertTrue(report['complete'])
        self.assertEqual(len(report['outputHashes']),14)
        for p in mc.PHASES:
            checkpoint=policy.read_checkpoint(out/p/'checkpoint.json')
            self.assertEqual(set(checkpoint['optimizer']['state']),set(map(str,range(5))))
            self.assertEqual(checkpoint['model']['valueWeights'],c.models[p]['valueWeights'])
            self.assertEqual(checkpoint['model']['valueBias'],c.models[p]['valueBias'])
            self.assertNotEqual(checkpoint['model']['actorWeights'],c.models[p]['actorWeights'])

    def test_all_three_matching_adams_continue(self):
        out=self.root/'first';update.update_triple(self.collection(),self.configs,out)
        c=self.collection(name='next-batch',previous=out)
        update.update_triple(c,{p:replace(v,seed=44) for p,v in self.configs.items()},self.root/'next',previous=out)
        for p in mc.PHASES:
            identity=joint.read(self.root/'next'/p/'identity.json')
            self.assertGreater(identity['tripleMcOptimizerContinuation']['actorAdamSteps'],0)
            self.assertEqual(identity['tripleMcOptimizerContinuation']['phase'],p)

    def test_third_failure_keeps_diagnostics_without_bundle(self):
        original=policy.update
        def run(*args,**kwargs):
            if kwargs['contract']==mc.CONTRACTS['purchase']:raise ValueError('UNIT third phase failure')
            return original(*args,**kwargs)
        out=self.root/'failed'
        with patch.object(policy,'update',side_effect=run),self.assertRaisesRegex(ValueError,'third phase'):
            update.update_triple(self.collection(),self.configs,out)
        self.assertTrue((out/'card/finished.json').exists())
        self.assertFalse((out/'bundle.json').exists());self.assertFalse((out/'triple-bundle.json').exists())
        self.assertFalse((out/'finished.json').exists())

    def test_final_guard_failure_keeps_three_completed_phases_without_bundle(self):
        calls=[];out=self.root/'failed'
        def guard():
            calls.append(1)
            if len(calls)==5:raise ValueError('UNIT global deadline')
        with self.assertRaisesRegex(ValueError,'global deadline'):
            update.update_triple(self.collection(),self.configs,out,guard=guard)
        self.assertTrue((out/'purchase/finished.json').exists());self.assertFalse((out/'bundle.json').exists())

    def test_empty_phase_rejects_before_any_update(self):
        c=self.collection(empty_phase='card')
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'entirely empty'):
            update.update_triple(c,self.configs,self.root/'invalid')
        step.assert_not_called();self.assertFalse((self.root/'invalid').exists())

    def test_epoch_rejection_restores_all_three_actor_and_adam_states(self):
        c=self.collection();out=self.root/'rejected'
        with patch.object(policy,'behavior_metrics',return_value={'behaviorKl':3.,'valueMse':0.}):
            update.update_triple(c,self.configs,out)
        for p in mc.PHASES:
            checkpoint=policy.read_checkpoint(out/p/'checkpoint.json')
            self.assertEqual(checkpoint['model'],c.models[p]);self.assertEqual(checkpoint['optimizer']['state'],{})

    def test_critic_loss_is_rejected_before_first_update(self):
        config=dict(self.configs,card=replace(self.config,valueCoefficient=.5))
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'actor-only'):
            update.update_triple(self.collection(),config,self.root/'invalid')
        step.assert_not_called()

    def test_changed_advantage_is_rejected(self):
        c=self.collection();obj=mc.TripleMcObjective(c,mc.Phase.CARD);head=c.heads['card']
        rows,p=obj.phase_reader(c.directory,head['path'],c.models['card'],contract=mc.CONTRACTS['card'])
        rows[0]['_actorOnlyAdvantage']=.2
        with self.assertRaisesRegex(ValueError,'cannot replace'):obj.bind(rows,p,head['sha256'])

    def test_wrong_typed_phase_cannot_alias_another_actor(self):
        c=self.collection();obj=mc.TripleMcObjective(c,mc.Phase.CARD)
        with self.assertRaisesRegex(ValueError,'alias another phase'):
            obj.validate(mc.CONTRACTS['expansion'],self.config)

    def test_changed_previous_member_rejects_before_updates(self):
        out=self.root/'first';update.update_triple(self.collection(),self.configs,out)
        c=self.collection(name='next-batch',previous=out)
        (out/'purchase/checkpoint.json').write_text('{}')
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'previous triple output changed'):
            update.update_triple(c,self.configs,self.root/'invalid',previous=out)
        step.assert_not_called()

    def test_missing_triple_completion_cannot_supply_carried_heads(self):
        out=self.root/'first';update.update_triple(self.collection(),self.configs,out)
        c=self.collection(name='next-batch',previous=out);(out/'finished.json').unlink()
        with patch.object(policy,'update') as step,self.assertRaises(FileNotFoundError):
            update.update_triple(c,self.configs,self.root/'invalid',previous=out)
        step.assert_not_called()

    def test_rejected_later_batch_retains_inherited_five_slot_moments(self):
        first=self.root/'first';update.update_triple(self.collection(),self.configs,first)
        c=self.collection(name='next-batch',previous=first);out=self.root/'rejected'
        with patch.object(policy,'behavior_metrics',return_value={'behaviorKl':3.,'valueMse':0.}):
            update.update_triple(c,self.configs,out,previous=first)
        for p in mc.PHASES:
            before=policy.read_checkpoint(first/p/'checkpoint.json');after=policy.read_checkpoint(out/p/'checkpoint.json')
            self.assertEqual(after['optimizer'],before['optimizer'])
            self.assertEqual(after['model'],before['model']);self.assertEqual(after['epoch'],0)

    def test_intrinsic_adam_rejects_missing_moment_even_with_resealed_member(self):
        first=self.root/'first';update.update_triple(self.collection(),self.configs,first)
        c=self.collection(name='next-batch',previous=first)
        path=first/'purchase/checkpoint.json';value=policy.read_checkpoint(path)
        del value['optimizer']['state']['2'];policy.save_checkpoint(path,value)
        finished=data.read(first/'finished.json');finished['outputHashes']['purchase/checkpoint.json']=joint.sha(path)
        finished['phases']['purchase']['checkpointSha256']=joint.sha(path)
        put(first/'finished.json',finished)
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'all five moments'):
            update.update_triple(c,self.configs,self.root/'invalid',previous=first)
        step.assert_not_called()

    def test_intrinsic_adam_rejects_changed_recipe_defaults(self):
        first=self.root/'first';update.update_triple(self.collection(),self.configs,first)
        c=self.collection(name='next-batch',previous=first)
        path=first/'card/checkpoint.json';value=policy.read_checkpoint(path)
        value['optimizer']['param_groups'][0]['eps']=.1;policy.save_checkpoint(path,value)
        finished=data.read(first/'finished.json');finished['outputHashes']['card/checkpoint.json']=joint.sha(path)
        finished['phases']['card']['checkpointSha256']=joint.sha(path)
        put(first/'finished.json',finished)
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'Adam'):
            update.update_triple(c,self.configs,self.root/'invalid',previous=first)
        step.assert_not_called()

    def test_mixed_legacy_objective_is_rejected_before_output(self):
        c=self.collection();obj=mc.TripleMcObjective(c,mc.Phase.PURCHASE)
        with self.assertRaisesRegex(ValueError,'Triple MC requires its exact'):
            policy.update(c.heads['purchase']['path'],c.directory,self.config,self.root/'invalid',
                contract=mc.CONTRACTS['purchase'],entrypoint=update.__file__,collection_reader=obj.phase_reader,
                actor_only=policy.ActorOnlyObjective('recorded'),triple_mc=obj)
        self.assertFalse((self.root/'invalid').exists())

    def test_post_validation_return_edit_rejects_before_any_update(self):
        c=self.collection();c.rows['card'][0]['return']=.25
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'in-memory triple'):
            update.update_triple(c,self.configs,self.root/'invalid')
        step.assert_not_called()

    def test_post_validation_observation_edit_rejects_before_any_update(self):
        c=self.collection();c.rows['card'][0]['observation']['stateFeatures']['state']=.8
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'in-memory triple'):
            update.update_triple(c,self.configs,self.root/'invalid')
        step.assert_not_called()

    def test_post_validation_prepared_tensor_edit_rejects_before_any_update(self):
        c=self.collection();c.rows['card'][0]['_prepared'].state_values[0]=.8
        with patch.object(policy,'update') as step,self.assertRaisesRegex(ValueError,'in-memory triple'):
            update.update_triple(c,self.configs,self.root/'invalid')
        step.assert_not_called()


if __name__=='__main__':unittest.main()
