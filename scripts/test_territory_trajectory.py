"""Native-shaped sealed-token provenance, shared complete data and atomic typed updates."""
from dataclasses import replace
import gzip
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import territory_trajectory as t
from test_joint_trajectory import fixture as joint_fixture
from test_joint_economy import terminal

b=t.backend


def put(path,value):b.atomic_json(path,value)


def initialize(root,phase):
    path=root/phase;path.mkdir()
    contract=t.CONTRACTS[phase]
    schema=dict(encoderVersion=contract.encoder,stateFeatureNames=['state'],actionFeatureNames=['choice'])
    model=b.initialize(schema,103,contract=contract)
    put(path/'policy.json',model);put(path/'schema.json',schema)
    put(path/'initialization.json',dict(sha256=t.sha(path/'policy.json'),learnerSha256=t.sha(b.__file__),
        entrypointSha256=t.sha(t.MODULES[phase].__file__),seed=103))
    return path/'policy.json'


def decision(model,phase,index,number,batch):
    actor=b.ResidualPolicy(model,t.CONTRACTS[phase])
    commands=['buy-tokens 0','buy-tokens 2'] if phase=='token' else ['expand near','pass']
    observation=dict(encoderVersion=t.CONTRACTS[phase].encoder,stateFeatures={'state':.2+index*.1},baselineIndex=0,
        actions=[dict(command=command,features={'choice':float(i)}) for i,command in enumerate(commands)])
    seed=t.bot_seed(-1000000000+13+index*8191,batch,index%4)
    actor_seed=t.signed(seed^t.MODULES[phase].ACTOR_SALT)
    with b.torch.no_grad():logp,value=actor([observation])
    selected=b.select(logp[0].exp().tolist(),b.selection_uniform(actor_seed))
    return dict(number=number,batchStartNumber=batch,botSeed=seed,actorSeed=actor_seed,turn=1,
        decision='buy-tokens' if phase=='token' else 'expand',observation=observation,selectedIndex=selected,
        behaviorLogProbability=float(logp[0][selected]),behaviorValue=float(value[0]))


def fixture(root,models=None,frozen_path=None):
    root=Path(root).resolve();root.mkdir(parents=True,exist_ok=True)
    if frozen_path is None:
        (root/'frozen').mkdir();_,frozen_path,_=joint_fixture(root/'frozen')
    frozen_raw=t.read(frozen_path);baseline=Path(frozen_path).parent/'F.json'
    frozen,_=t.joint.load_bundle(frozen_path,t.CONFIGURATION,t.sha(baseline),{})
    models=models or {phase:initialize(root,phase) for phase in t.PHASES}
    raw=dict(schemaVersion=1,kind='territory-token-expansion-1',configuration=t.CONFIGURATION,
        baselineSha256=t.sha(baseline),frozenCardPurchaseBundleSha256=t.sha(frozen_path),
        **{phase:dict(path=str(path),sha256=t.sha(path)) for phase,path in models.items()})
    put(root/'bundle.json',raw);put(root/'rival.json',dict(raw,token=None,expansion=None))
    own,_=t.load_bundle(root/'bundle.json',t.CONFIGURATION,t.sha(baseline),t.sha(frozen_path),{})
    rival,_=t.load_bundle(root/'rival.json',t.CONFIGURATION,t.sha(baseline),t.sha(frozen_path),{},controlled=False)
    source=dict(schemaVersion=1,kind='territory-collect',configuration=t.CONFIGURATION,games=2,seed=13,
        baseline=str(baseline),frozenCardPurchaseBundle=str(frozen_path),bundle=str(root/'bundle.json'),opponents=[str(root/'rival.json')])
    put(root/'source.json',source)
    directory=root/'collection';directory.mkdir()
    copies=[(root/'source.json','source.json'),(baseline,'baseline.json'),(root/'bundle.json','bundle.json'),
        (root/'rival.json','opponent-0-bundle.json'),(frozen_path,'frozen-card-purchase-bundle.json')]
    copies.extend((models[phase],phase+'-policy.json') for phase in t.PHASES)
    copies.extend((frozen[phase]['path'],f'frozen-{phase}-policy.json') for phase in t.joint.PHASES if frozen[phase] is not None)
    for original,name in copies:shutil.copyfile(original,directory/name)
    seeds=[-1000000000+13+i*8191 for i in range(2)]
    manifest=dict(schemaVersion=1,kind='territory-collect',mode='on-policy',signature='fixture',trajectoryEncoding='gzip-json-v1',
        source=str(root/'source.json'),sourceSha256=t.sha(root/'source.json'),configuration=t.CONFIGURATION,
        games=2,seed=13,seeds=seeds,baseline=dict(path=str(baseline),sha256=t.sha(baseline)),
        frozenCardPurchaseBundle=frozen,bundle=own,opponents=[rival])
    put(directory/'collection.json',manifest)
    put(directory/'state.json',dict(kind='territory-collect',signature='fixture',phase='complete',completedGames=2))
    for index in range(2):
        outcome=terminal(index,index==0);outcome['gameDecisions']=5
        game=dict(schemaVersion=1,signature='fixture',index=index,configuration=t.CONFIGURATION,
            gameSeed=seeds[index],controlledSeat=index,opponentIndices=[0]*4,bundleSha256=own['sha256'],
            frozenCardPurchaseBundleSha256=frozen['sha256'],tokenModelSha256=own['token']['sha256'],
            expansionModelSha256=own['expansion']['sha256'],terminal=outcome,
            tokenDecisions=[decision(t.read(models['token']),'token',index,2,0)] if index==0 else [],
            expansionDecisions=[decision(t.read(models['expansion']),'expansion',index,3,3)] if index==1 else [])
        (directory/f'game-{index}.json.gz').write_bytes(gzip.compress(b.canonical(game),mtime=0))
    finish(directory)
    return directory,root/'bundle.json'


def finish(directory):
    put(directory/'finished.json',dict(kind='territory-collect',signature='fixture',games=2,
        gameSha256=[t.sha(directory/f'game-{i}.json.gz') for i in range(2)]))


def change_game(directory,index,change):
    path=directory/f'game-{index}.json.gz';game=json.loads(gzip.decompress(path.read_bytes()));change(game)
    path.write_bytes(gzip.compress(b.canonical(game),mtime=0));finish(directory)


class TerritoryTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve()
        self.directory,self.bundle=fixture(self.root/'data')
        self.configs={phase:b.UpdateConfig(epochs=1,batchSize=4,seed=17) for phase in t.PHASES}
        self.objectives={phase:b.OfferedReferenceObjective(t.MODULES[phase].REFERENCE_VERSION,.1) for phase in t.PHASES}

    def update(self,output,**kwargs):
        return t.update_pair(self.directory,self.bundle,self.configs,dict.fromkeys(t.PHASES),output,
                             objectives=self.objectives,**kwargs)

    def test_views_retain_same_complete_origins_with_empty_per_game_arrays(self):
        collection=t.TerritoryCollection(self.directory,self.bundle)
        self.assertEqual([1,0],[row['tokenDecisions'] for row in collection.origins])
        self.assertEqual([0,1],[row['expansionDecisions'] for row in collection.origins])
        data=[]
        for phase in t.PHASES:
            rows,provenance=collection.phase_reader(self.directory,collection.bundle[phase]['path'],
                collection.models[phase],contract=t.CONTRACTS[phase])
            self.assertEqual(1,len(rows));data.append(provenance)
        for key in ('gameHashes','originCoverage','gameMeanReward','frozenCardPurchaseBundleSha256'):
            self.assertEqual(data[0][key],data[1][key])

    def test_sealed_token_uses_batch_seed_not_row_ordinal(self):
        collection=t.TerritoryCollection(self.directory,self.bundle);row=collection.rows['token'][0]
        self.assertEqual(0,row['batchStartNumber']);self.assertEqual(2,row['number'])
        self.assertNotEqual(row['botSeed'],t.bot_seed(-1000000000+13,2,0))

    def test_incorrect_ordinal_derived_token_seed_rejects(self):
        def wrong(game):
            row=game['tokenDecisions'][0];row['botSeed']=t.bot_seed(game['gameSeed'],row['number'],0)
            row['actorSeed']=t.signed(row['botSeed']^t.token.ACTOR_SALT)
        change_game(self.directory,0,wrong)
        with self.assertRaisesRegex(ValueError,'sealed-batch'):t.TerritoryCollection(self.directory,self.bundle)

    def test_sequential_expansion_cannot_use_earlier_batch(self):
        change_game(self.directory,1,lambda game:game['expansionDecisions'][0].update(batchStartNumber=0))
        with self.assertRaisesRegex(ValueError,'expansion is sequential'):t.TerritoryCollection(self.directory,self.bundle)

    def test_later_hash_mismatch_blocks_all_decoding(self):
        with (self.directory/'game-1.json.gz').open('ab') as stream:stream.write(b'wrong')
        with patch.object(b,'read_game') as decode:
            with self.assertRaisesRegex(ValueError,'compressed game'):t.TerritoryCollection(self.directory,self.bundle)
            decode.assert_not_called()

    def test_frozen_card_purchase_snapshot_cannot_change(self):
        put(self.directory/'frozen-purchase-policy.json',{})
        with self.assertRaisesRegex(ValueError,'snapshot'):t.TerritoryCollection(self.directory,self.bundle)

    def test_behavior_log_probability_cannot_be_relabelled(self):
        change_game(self.directory,0,lambda game:game['tokenDecisions'][0].update(behaviorLogProbability=-20.))
        with self.assertRaisesRegex(ValueError,'likelihood'):t.TerritoryCollection(self.directory,self.bundle)

    def test_public_terminal_labels_are_independently_recomputed(self):
        change_game(self.directory,0,lambda game:game['terminal'].update(reward=0.))
        with self.assertRaisesRegex(ValueError,'public scores'):t.TerritoryCollection(self.directory,self.bundle)

    def test_whole_empty_phase_rejects_before_any_update(self):
        change_game(self.directory,0,lambda game:game.update(tokenDecisions=[]))
        with patch.object(b,'update') as update:
            with self.assertRaisesRegex(ValueError,'entirely empty'):self.update(self.root/'output')
            update.assert_not_called()
        self.assertFalse((self.root/'output').exists())

    def test_two_typed_updates_publish_one_validated_bundle(self):
        output=self.root/'output';report=self.update(output)
        self.assertEqual(2,len(report['commonOrigins']))
        self.assertEqual(report['outputBundleSha256'],t.sha(output/'bundle.json'))
        for phase in t.PHASES:
            identity=t.read(output/phase/'identity.json')
            self.assertEqual(t.MODULES[phase].REFERENCE_VERSION,identity['offeredReferenceObjective']['version'])
            self.assertEqual(report['gameHashes'],identity['data']['gameHashes'])
            self.assertIsNone(identity['optimizerFromSha256'])
            self.assertGreater(report['phases'][phase]['acceptedSteps'],0)

    def test_completed_resume_revalidates_without_optimizer_steps(self):
        output=self.root/'output';first=self.update(output)
        with patch.object(b,'update',side_effect=AssertionError('no fit')):
            self.assertEqual(first,self.update(output,resume=True))

    def test_interruption_after_first_actor_never_publishes_half_pair(self):
        output=self.root/'output';real=b.update
        def interrupted(*args,**kwargs):
            if kwargs['contract']==t.expansion.CONTRACT:raise RuntimeError('second phase interruption')
            return real(*args,**kwargs)
        with patch.object(b,'update',side_effect=interrupted):
            with self.assertRaises(RuntimeError):self.update(output)
        self.assertTrue((output/'token/finished.json').exists())
        self.assertFalse((output/'bundle.json').exists());self.assertFalse((output/'finished.json').exists())
        first_token=(output/'token/checkpoint.json').read_bytes()
        self.update(output,resume=True)
        self.assertEqual(first_token,(output/'token/checkpoint.json').read_bytes())
        uninterrupted=self.root/'uninterrupted';self.update(uninterrupted)
        for phase in t.PHASES:
            resumed=b.read_checkpoint(output/phase/'checkpoint.json')
            original=b.read_checkpoint(uninterrupted/phase/'checkpoint.json')
            for key in ('model','optimizer','history','cursor','acceptedSteps','acceptedBlocks'):
                self.assertEqual(original[key],resumed[key])

    def test_first_output_mutation_during_second_fit_blocks_publication(self):
        output=self.root/'output';real=b.update
        def mutate(*args,**kwargs):
            report=real(*args,**kwargs)
            if kwargs['contract']==t.expansion.CONTRACT:
                with (output/'token/policy.json').open('a') as stream:stream.write(' ')
            return report
        with patch.object(b,'update',side_effect=mutate):
            with self.assertRaisesRegex(ValueError,'changed'):self.update(output)
        self.assertFalse((output/'bundle.json').exists())

    def test_new_collection_carries_each_exact_previous_adam(self):
        first=self.root/'first';self.update(first)
        original=t.read(self.root/'data/source.json')
        models={phase:first/phase/'policy.json' for phase in t.PHASES}
        directory,bundle=fixture(self.root/'second-data',models,Path(original['frozenCardPurchaseBundle']))
        checkpoints={phase:first/phase/'checkpoint.json' for phase in t.PHASES}
        output=self.root/'second'
        report=t.update_pair(directory,bundle,self.configs,checkpoints,output,objectives=self.objectives)
        for phase in t.PHASES:
            self.assertEqual(t.sha(checkpoints[phase]),t.read(output/phase/'identity.json')['optimizerFromSha256'])
            self.assertGreater(report['phases'][phase]['acceptedSteps'],0)

    def rejected_first_pair(self):
        output=self.root/'rejected'
        with patch.object(b,'behavior_metrics',return_value=dict(behaviorKl=.02,priorKl=0.,valueMse=.25)):
            self.update(output)
        return output

    def test_rejected_first_block_carries_unchanged_empty_adam(self):
        output=self.rejected_first_pair()
        models={phase:output/phase/'policy.json' for phase in t.PHASES}
        for phase in t.PHASES:
            payload=t.validate_carry(models[phase],output/phase/'checkpoint.json',t.CONTRACTS[phase],self.configs[phase],{})
            self.assertEqual({},payload['optimizer']['state'])
            self.assertEqual(0,payload['acceptedSteps'])
        original=t.read(self.root/'data/source.json')
        directory,bundle=fixture(self.root/'next-data',models,Path(original['frozenCardPurchaseBundle']))
        result=t.update_pair(directory,bundle,self.configs,
            {phase:output/phase/'checkpoint.json' for phase in t.PHASES},self.root/'next',objectives=self.objectives)
        self.assertTrue(all(row['acceptedSteps']>0 for row in result['phases'].values()))

    def test_empty_adam_hidden_parameter_substitution_is_rejected(self):
        output=self.rejected_first_pair()/'token'
        model=t.read(output/'policy.json');model['stateWeights'][0]+=.01;put(output/'policy.json',model)
        payload=b.read_checkpoint(output/'checkpoint.json');payload['model']=model
        b.save_checkpoint(output/'checkpoint.json',payload)
        done=t.read(output/'finished.json');done['outputModelSha256']=t.sha(output/'policy.json');put(output/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'unchanged never-updated'):
            t.validate_carry(output/'policy.json',output/'checkpoint.json',t.token.CONTRACT,self.configs['token'],{})

    def test_empty_adam_completion_history_substitution_is_rejected(self):
        output=self.rejected_first_pair()/'token'
        done=t.read(output/'finished.json');done['history']=[];put(output/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'unchanged never-updated'):
            t.validate_carry(output/'policy.json',output/'checkpoint.json',t.token.CONTRACT,self.configs['token'],{})

    def test_empty_adam_forged_progress_is_rejected(self):
        output=self.rejected_first_pair()/'token'
        payload=b.read_checkpoint(output/'checkpoint.json');payload['cursor']['minibatchOffset']=8
        b.save_checkpoint(output/'checkpoint.json',payload)
        done=t.read(output/'finished.json');done['cursor']=payload['cursor'];put(output/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'rejected first block'):
            t.validate_carry(output/'policy.json',output/'checkpoint.json',t.token.CONTRACT,self.configs['token'],{})

    def test_resume_cannot_change_offered_reference(self):
        output=self.root/'output';self.update(output)
        changed=dict(self.objectives,token=b.OfferedReferenceObjective(t.token.REFERENCE_VERSION,.2))
        with self.assertRaises(ValueError):
            t.update_pair(self.directory,self.bundle,self.configs,dict.fromkeys(t.PHASES),output,
                          objectives=changed,resume=True)

    def test_token_cli_initializes_reproducible_typed_model(self):
        self.check_cli_initializer(t.token)

    def test_expansion_cli_initializes_reproducible_typed_model(self):
        self.check_cli_initializer(t.expansion)

    def check_cli_initializer(self,module):
        schema=dict(encoderVersion=module.ENCODER_VERSION,stateFeatureNames=['state'],actionFeatureNames=['choice'])
        put(self.root/'schema.json',schema);output=self.root/'cli-initial'
        with redirect_stdout(io.StringIO()):
            module.main(['initialize','--schema',str(self.root/'schema.json'),'--seed','103','--output',str(output)])
        self.assertEqual(schema,t.read(output/'schema.json'))
        self.assertEqual(module.initialize(schema,103),t.validate_initializer(output/'policy.json',module.CONTRACT,{}))
        self.assertEqual(t.sha(module.__file__),t.read(output/'initialization.json')['entrypointSha256'])


if __name__=='__main__':unittest.main()
