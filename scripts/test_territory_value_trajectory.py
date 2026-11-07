"""Native-shaped Q completion, replay carries and atomic publication regressions."""
import gzip
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import territory_value_trajectory as t
from test_territory_trajectory import fixture as ppo_fixture

b=t.backend


def put(path,value):b.atomic_json(path,value)


def fixture(root,models=None,frozen_path=None,seed=13,empty=False,expansion2=False):
    root=Path(root).resolve()
    directory,bundle=ppo_fixture(root,frozen_path=frozen_path)
    own=t.read(bundle)
    paths={}
    for phase in t.PHASES:
        if models is None:
            if phase=='expansion' and expansion2:
                schema=t.read(root/phase/'schema.json')
                schema.update(encoderVersion=t.q.EXPANSION_ENCODER_2,
                    stateFeatureNames=sorted(schema['stateFeatureNames']+list(t.q.CATHEDRAL_USAGE_FEATURES)))
                put(root/phase/'schema.json',schema)
            t.initialize_model(root/phase/'schema.json',103,phase,root/('q-'+phase))
            paths[phase]=root/('q-'+phase)/'policy.json'
        else:paths[phase]=Path(models[phase])
        own[phase]=dict(path=str(paths[phase]),sha256=t.sha(paths[phase]))
    own['kind']='territory-token-expansion-values-1';put(bundle,own)
    put(root/'rival.json',dict(own,token=None,expansion=None))
    source=t.read(root/'source.json');source.update(kind='territory-value-collect',epsilon=.2,seed=seed)
    put(root/'source.json',source)
    descriptor,_=t.load_bundle(bundle,t.CONFIGURATION,own['baselineSha256'],own['frozenCardPurchaseBundleSha256'],{})
    rival,_=t.load_bundle(root/'rival.json',t.CONFIGURATION,own['baselineSha256'],own['frozenCardPurchaseBundleSha256'],{},controlled=False)
    for original,name in ((bundle,'bundle.json'),(root/'source.json','source.json'),(root/'rival.json','opponent-0-bundle.json')):
        shutil.copyfile(original,directory/name)
    for p in t.PHASES:shutil.copyfile(paths[p],directory/(p+'-policy.json'))
    manifest=t.read(directory/'collection.json')
    manifest.update(kind='territory-value-collect',mode='epsilon-greedy',epsilon=.2,seed=seed,
        seeds=[-1000000000+seed+i*8191 for i in range(2)],sourceSha256=t.sha(root/'source.json'),bundle=descriptor,opponents=[rival])
    put(directory/'collection.json',manifest)
    state=t.read(directory/'state.json');state['kind']='territory-value-collect';put(directory/'state.json',state)
    for index in range(2):
        path=directory/f'game-{index}.json.gz';game=json.loads(gzip.decompress(path.read_bytes()))
        game.update(gameSeed=manifest['seeds'][index],bundleSha256=descriptor['sha256'],
            tokenModelSha256=descriptor['token']['sha256'],expansionModelSha256=descriptor['expansion']['sha256'])
        for phase in t.PHASES:
            if empty:game[phase+'Decisions']=[]
            for row in game[phase+'Decisions']:
                row.pop('actorSeed');row.pop('behaviorLogProbability');row.pop('behaviorValue')
                row['botSeed']=t.bot_seed(game['gameSeed'],row['batchStartNumber'],index)
                row['selectorSeed']=t.signed(row['botSeed']^t.q.SALTS[phase])
                row['observation']['encoderVersion']=t.read(paths[phase])['encoderVersion']
                if phase=='expansion' and expansion2:
                    row['observation']['stateFeatures']['cathedral.used.role.0.role.1']=1.
                values=t.q.reference_forward(t.read(paths[phase]),row['observation'],phase)
                probabilities=t.q.probabilities(values,row['observation'])
                row['selectedIndex']=b.select(probabilities,b.selection_uniform(row['selectorSeed']))
                row['selectedQ']=values[row['selectedIndex']];row['behaviorProbability']=probabilities[row['selectedIndex']]
        path.write_bytes(gzip.compress(b.canonical(game),mtime=0))
    finish(directory)
    return directory,bundle


def finish(directory):
    put(directory/'finished.json',dict(kind='territory-value-collect',signature='fixture',games=2,
        gameSha256=[t.sha(directory/f'game-{i}.json.gz') for i in range(2)]))


def change_game(directory,index,change):
    path=directory/f'game-{index}.json.gz';game=json.loads(gzip.decompress(path.read_bytes()));change(game)
    path.write_bytes(gzip.compress(b.canonical(game),mtime=0));finish(directory)


def rewrite_checkpoint(output,mutate):
    payload=b.read_checkpoint(output/'checkpoint.json');mutate(payload)
    b.atomic_json(output/'checkpoint.json',dict(sha256=b.digest(payload),payload=payload),compact=True)
    done=t.read(output/'finished.json');done['outputHashes'][str(output/'checkpoint.json')]=t.sha(output/'checkpoint.json')
    put(output/'finished.json',done)


class CollectionTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve();self.directory,self.bundle=fixture(self.root/'data')

    def update(self,output,**kwargs):
        return t.update_pair(self.directory,self.bundle,t.q.UpdateConfig(seed=103),output,**kwargs)

    def test_complete_views_retain_empty_arrays_and_token_terminal(self):
        collection=t.ValueCollection(self.directory,self.bundle)
        self.assertEqual([1,0],[x['tokenDecisions'] for x in collection.origins])
        self.assertEqual([0,1],[x['expansionDecisions'] for x in collection.origins])
        token=collection.games[0].transitions[0]
        self.assertEqual('token',token.phase);self.assertIsNone(token.successor)
        self.assertEqual(collection.games[0].reward,token.reward)

    def test_same_phase_successor_is_actual_next_number(self):
        original=t.read(self.directory/'collection.json')
        def add(game):
            row=dict(game['expansionDecisions'][0]);row['number']=4;row['batchStartNumber']=4
            row['botSeed']=t.bot_seed(game['gameSeed'],4,1);row['selectorSeed']=t.signed(row['botSeed']^t.q.SALTS['expansion'])
            model=t.read(original['bundle']['expansion']['path']);values=t.q.reference_forward(model,row['observation'],'expansion')
            probabilities=t.q.probabilities(values,row['observation']);row['selectedIndex']=b.select(probabilities,b.selection_uniform(row['selectorSeed']))
            row['selectedQ']=values[row['selectedIndex']];row['behaviorProbability']=probabilities[row['selectedIndex']]
            game['expansionDecisions'].append(row);game['terminal']['gameDecisions']=6
        change_game(self.directory,1,add)
        first,last=t.ValueCollection(self.directory,self.bundle).games[1].transitions
        self.assertEqual('expansion',first.successor.phase);self.assertEqual(4,first.successor.number)
        self.assertEqual(0.,first.reward);self.assertIsNone(last.successor)

    def test_all_hashes_are_bound_before_any_selected_replay_decode(self):
        with (self.directory/'game-1.json.gz').open('ab') as f:f.write(b'changed')
        with patch.object(b,'read_game') as read:
            with self.assertRaises(ValueError):t.ValueCollection(self.directory,self.bundle,indices=[0])
            read.assert_not_called()

    def test_row_seed_and_behavior_corruption_reject(self):
        change_game(self.directory,0,lambda game:game['tokenDecisions'][0].update(selectorSeed=0))
        with self.assertRaisesRegex(ValueError,'sealed-batch'):t.ValueCollection(self.directory,self.bundle)

    def test_actual_pair_update_and_completed_only_reuse(self):
        output=self.root/'update';done=self.update(output)
        self.assertEqual(1,done['optimizationUpdate']);self.assertEqual({'token':4,'expansion':4},done['optimizerSteps'])
        t.read_completed(output)
        with patch.object(t.q,'fit_pair',side_effect=AssertionError('No fitting on reuse')):
            self.assertEqual(done,self.update(output,resume=True))

    def test_second_update_carries_replay_adam_and_unsynced_targets(self):
        first=self.root/'first';self.update(first)
        payload=b.read_checkpoint(first/'checkpoint.json')
        models={p:first/(p+'-policy.json') for p in t.PHASES}
        frozen=t.read(self.directory/'source.json')['frozenCardPurchaseBundle']
        next_dir,next_bundle=fixture(self.root/'next',models,frozen_path=frozen,seed=31)
        result=t.update_pair(next_dir,next_bundle,t.q.UpdateConfig(seed=103),self.root/'second',checkpoint_path=first/'checkpoint.json')
        self.assertEqual({'token':8,'expansion':8},result['optimizerSteps'])
        self.assertEqual(4,len(result['replay']['games']))
        carried=b.read_checkpoint(self.root/'second'/'checkpoint.json')
        self.assertEqual(payload['targets'],carried['targets']);t.read_completed(self.root/'second')

    def test_empty_batch_never_advances_optimizer_or_target_clock(self):
        directory,bundle=fixture(self.root/'empty',empty=True)
        result=t.update_pair(directory,bundle,t.q.UpdateConfig(),self.root/'out')
        self.assertEqual(0,result['optimizationUpdate']);self.assertFalse(result['targetSynchronized'])
        self.assertEqual({'token':0,'expansion':0},result['optimizerSteps']);t.read_completed(self.root/'out')

    def test_multistep_horizon_is_persisted_and_carried_exactly(self):
        config=t.q.UpdateConfig(seed=103,targetSteps=16);first=self.root/'first'
        t.update_pair(self.directory,self.bundle,config,first)
        self.assertEqual(16,t.read(first/'identity.json')['config']['targetSteps'])
        models={p:first/(p+'-policy.json') for p in t.PHASES}
        frozen=t.read(self.directory/'source.json')['frozenCardPurchaseBundle']
        directory,bundle=fixture(self.root/'next',models,frozen_path=frozen,seed=31)
        t.update_pair(directory,bundle,config,self.root/'second',checkpoint_path=first/'checkpoint.json')
        _,identity,_=t.read_completed(self.root/'second',config)
        self.assertEqual(16,identity['config']['targetSteps'])

    def test_carry_cannot_change_return_horizon_before_fitting(self):
        for previous_horizon,next_horizon in ((1,16),(16,1)):
            first=self.root/('first'+str(previous_horizon))
            t.update_pair(self.directory,self.bundle,t.q.UpdateConfig(seed=103,targetSteps=previous_horizon),first)
            models={p:first/(p+'-policy.json') for p in t.PHASES}
            frozen=t.read(self.directory/'source.json')['frozenCardPurchaseBundle']
            directory,bundle=fixture(self.root/('next'+str(previous_horizon)),models,frozen_path=frozen,seed=31)
            with patch.object(t.q,'fit_pair',side_effect=AssertionError('No fit after changed horizon')):
                with self.assertRaisesRegex(ValueError,'cannot change the learning algorithm'):
                    t.update_pair(directory,bundle,t.q.UpdateConfig(seed=103,targetSteps=next_horizon),
                                  self.root/('out'+str(previous_horizon)),checkpoint_path=first/'checkpoint.json')

    def test_legacy_missing_horizon_normalizes_to_one_without_rewriting_identity(self):
        output=self.root/'old';done=self.update(output)
        identity=t.read(output/'identity.json');identity['config'].pop('targetSteps')
        started=t.read(output/'started.json');started['config'].pop('targetSteps')
        put(output/'identity.json',identity);put(output/'started.json',started)
        payload=b.read_checkpoint(output/'checkpoint.json');payload['identitySha256']=b.digest(identity)
        put(output/'checkpoint.json',dict(sha256=b.digest(payload),payload=payload))
        done['identitySha256']=b.digest(identity)
        done['outputHashes']={path:t.sha(path) for path in done['outputHashes']};put(output/'finished.json',done)
        identity_sha=t.sha(output/'identity.json')
        with patch.object(t.q,'fit_pair',side_effect=AssertionError('Legacy completed reuse must not fit')):
            self.assertEqual(done,self.update(output,resume=True))
        self.assertEqual(identity_sha,t.sha(output/'identity.json'))
        with self.assertRaisesRegex(ValueError,'cannot change the learning algorithm'):
            t.read_completed(output,t.q.UpdateConfig(targetSteps=16))

    def test_validation_interruption_has_original_attempt_marker_and_no_retry(self):
        output=self.root/'out'
        with patch.object(t,'ValueCollection',side_effect=RuntimeError('validation interrupted')):
            with self.assertRaises(RuntimeError):self.update(output)
        self.assertTrue((output/'started.json').exists())
        with self.assertRaisesRegex(ValueError,'already completed'):self.update(output,resume=True)

    def test_second_head_failure_has_no_published_bundle_or_completion(self):
        original=t.q.fit_phase
        def fail(actor,optimizer,replay,phase,*args):
            if phase=='expansion':raise RuntimeError('second head interrupted')
            return original(actor,optimizer,replay,phase,*args)
        output=self.root/'out'
        with patch.object(t.q,'fit_phase',side_effect=fail):
            with self.assertRaises(RuntimeError):self.update(output)
        self.assertFalse((output/'bundle.json').exists());self.assertFalse((output/'finished.json').exists())

    def test_non_sync_target_substitution_is_rejected_even_with_updated_checksum(self):
        output=self.root/'out';self.update(output)
        rewrite_checkpoint(output,lambda p:p['targets']['token'].update(outputBias=.6))
        with self.assertRaisesRegex(ValueError,'targets must sync'):t.read_completed(output)

    def test_incomplete_output_hash_set_is_rejected(self):
        output=self.root/'out';self.update(output)
        done=t.read(output/'finished.json');done['outputHashes'].pop(str(output/'token-policy.json'));put(output/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'exact complete output'):t.read_completed(output)

    def test_zero_step_hidden_parameter_substitution_is_rejected(self):
        directory,bundle=fixture(self.root/'empty',empty=True);output=self.root/'out'
        t.update_pair(directory,bundle,t.q.UpdateConfig(),output)
        def mutate(p):p['online']['token']['stateWeights'][0]+=.1
        rewrite_checkpoint(output,mutate)
        with self.assertRaisesRegex(ValueError,'zero-step Q head'):t.read_completed(output)

    def test_numerical_guard_failure_cannot_publish(self):
        output=self.root/'out'
        with patch.object(t.q,'numerical_guard',side_effect=ValueError('Q magnitude guard')):
            with self.assertRaisesRegex(ValueError,'magnitude'):self.update(output)
        self.assertFalse((output/'finished.json').exists());self.assertFalse((output/'bundle.json').exists())

    def test_reordered_replay_fails_even_when_identity_agrees(self):
        output=self.root/'out';self.update(output)
        payload=b.read_checkpoint(output/'checkpoint.json');identity=t.read(output/'identity.json')
        payload['replay']['games'].reverse();identity['replay']=copy.deepcopy(payload['replay'])
        with self.assertRaisesRegex(ValueError,'exact FIFO suffix'):
            t.validate_progress(output,payload,identity,t.q.UpdateConfig(seed=103))

    def test_changed_processed_seed_history_fails_even_when_identity_agrees(self):
        output=self.root/'out';self.update(output)
        payload=b.read_checkpoint(output/'checkpoint.json');identity=t.read(output/'identity.json')
        payload['replay']['seenSeeds']=[];identity['replay']=copy.deepcopy(payload['replay'])
        with self.assertRaisesRegex(ValueError,'exact FIFO suffix'):
            t.validate_progress(output,payload,identity,t.q.UpdateConfig(seed=103))

    def test_fourth_nonempty_pair_syncs_both_targets(self):
        output=self.root/'update1';self.update(output)
        frozen=t.read(self.directory/'source.json')['frozenCardPurchaseBundle']
        for number in (2,3,4):
            models={p:output/(p+'-policy.json') for p in t.PHASES}
            directory,bundle=fixture(self.root/('data'+str(number)),models,frozen_path=frozen,seed=100*number)
            next_output=self.root/('update'+str(number))
            t.update_pair(directory,bundle,t.q.UpdateConfig(seed=103),next_output,checkpoint_path=output/'checkpoint.json')
            output=next_output
        payload,_,done=t.read_completed(output)
        self.assertEqual(4,done['optimizationUpdate']);self.assertTrue(done['targetSynchronized'])
        self.assertEqual(payload['online'],payload['targets'])


if __name__=='__main__':unittest.main()
