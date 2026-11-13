"""Invented JSON/capsules only: no scientific inputs, native calls, Torch or optimizer steps."""
import copy
import gzip
import hashlib
import json
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import triple_mc_matched as loop
import triple_mc_matched_audit as audit
import triple_mc_matched_storage as storage
from test_triple_mc_matched import protocol as basic_protocol, receipt as progress_receipt


def model(phase):
    return dict(schemaVersion=2 if phase=='expansion' else 1,actorVersion=audit.ACTORS[phase],
        encoderVersion='UNIT-'+phase,stateFeatureNames=['state'],actionFeatureNames=['choice'],
        stateWeights=[[0.] for _ in range(64)],actionWeights=[[0.] for _ in range(64)],
        stateBias=[0.]*64,actionBias=[0.]*64,actorWeights=[0.]*64,valueWeights=[0.]*64,valueBias=.125,
        priorStrength=.97)


def config(seed=17):
    return dict(learningRate=.0003,epochs=4,batchSize=64,clip=.2,valueCoefficient=0.,entropyCoefficient=0.,
        maxGradientNorm=.5,maxBehaviorKl=.01,seed=seed,threads=1,maxSeconds=900.,
        cardReferenceKlCoefficient=0.,cardReferenceVersion='aor-card-physical-reference-1')


def optimizer(steps):
    state={}
    if steps:
        for index in range(5):
            vector=[[0.] for _ in range(64)] if index in (0,2) else [0.]*64
            state[str(index)]=dict(step={'tensor':float(steps),'dtype':'torch.float32'},
                exp_avg={'tensor':copy.deepcopy(vector),'dtype':'torch.float64'},
                exp_avg_sq={'tensor':copy.deepcopy(vector),'dtype':'torch.float64'})
    return dict(state=state,param_groups=[dict(params=list(range(5)),lr=.0003,betas=[.9,.999],eps=1e-8,
        weight_decay=0,amsgrad=False,maximize=False,foreach=False,capturable=False,differentiable=False,fused=None)])


def phase_fixture(phase='expansion',previous=None,accepted=4):
    original=model(phase);updated=copy.deepcopy(original)
    if accepted:updated['actorWeights'][0]=.125
    behavior=dict.fromkeys(audit.PHASES,'a'*64);cfg=config();ecology={'UNIT':'fixed'}
    objective=dict(version='triple-mc-actor-only-1',phase=phase,estimator=audit.ESTIMATOR,ecology=ecology,
        behaviorModels=behavior,collectionSha256='b'*64,gameHashes=['c'*64],frozenParameters=['valueWeights','valueBias'],
        optimizerInitialization='fresh-adam' if previous is None else 'matching-triple-mc-adam')
    identity=dict(actorVersion=audit.ACTORS[phase],modelSha256=behavior[phase],config=cfg,
        optimizerFromSha256=None if previous is None else previous['checkpointSha256'],tripleMcObjective=objective,
        deadlineEpochSeconds=1234.)
    if previous is not None:
        identity['tripleMcOptimizerContinuation']=dict(version='triple-mc-adam-continuation-1',phase=phase,
            actorAdamSteps=previous['actorAdamSteps'],checkpointSha256=previous['checkpointSha256'],
            previousIdentitySha256=previous['identityDigest'])
    history=[dict(epoch=i+1,accepted=True,behaviorKl=0.,priorKl=0.,valueMse=0.) for i in range(accepted)]
    if accepted<4:history.append(dict(epoch=accepted+1,accepted=False,behaviorKl=.02,priorKl=0.,valueMse=0.))
    steps=(0 if previous is None else previous['actorAdamSteps'])+accepted
    payload=dict(schemaVersion=1,identitySha256=audit.digest(identity),deadlineEpochSeconds=1234.,epoch=accepted,
        model=updated,optimizer=optimizer(steps),history=history,stopReason=None if accepted==4 else 'behavior-kl-limit')
    finished=dict(identitySha256=audit.digest(identity),inputModelSha256=behavior[phase],tripleMcObjective=objective,
        history=history,acceptedEpochs=accepted,stopReason=payload['stopReason'] or 'epochs-complete')
    checkpoint=dict(sha256=audit.digest(payload),payload=payload)
    return dict(phase=phase,identity=identity,finished=finished,checkpoint=checkpoint,model=updated,
        original=original,config=cfg,behavior=behavior,previous=previous)


class PhaseTests(unittest.TestCase):
    def test_pure_five_slot_metadata_admits_fresh_completed_epoch_history(self):
        value=audit.phase_metadata(**phase_fixture())
        self.assertEqual(4,value['actorAdamSteps']);self.assertTrue(value['actorChanged'])

    def test_negative_float64_kl_roundoff_preserves_original_acceptance(self):
        item=phase_fixture();item['checkpoint']['payload']['history'][0]['behaviorKl']=-1e-17
        item['checkpoint']['sha256']=audit.digest(item['checkpoint']['payload'])
        self.assertEqual(4,audit.phase_metadata(**item)['acceptedEpochs'])

    def test_initial_whole_epoch_rejection_keeps_fresh_empty_adam(self):
        value=audit.phase_metadata(**phase_fixture(accepted=0))
        self.assertEqual(0,value['actorAdamSteps']);self.assertFalse(value['actorChanged'])

    def test_inherited_rejection_keeps_all_original_moments(self):
        first=audit.phase_metadata(**phase_fixture());first['checkpointSha256']='d'*64
        value=audit.phase_metadata(**phase_fixture(previous=first,accepted=0))
        self.assertEqual(4,value['actorAdamSteps']);self.assertEqual(4,value['previousActorAdamSteps'])

    def test_missing_inherited_moment_rejects_even_when_report_resealed(self):
        item=phase_fixture();del item['checkpoint']['payload']['optimizer']['state']['4']
        item['checkpoint']['sha256']=audit.digest(item['checkpoint']['payload'])
        with self.assertRaisesRegex(ValueError,'five original moments'):audit.phase_metadata(**item)

    def test_negative_squared_moment_rejects(self):
        item=phase_fixture();item['checkpoint']['payload']['optimizer']['state']['0']['exp_avg_sq']['tensor'][0][0]=-1.
        item['checkpoint']['sha256']=audit.digest(item['checkpoint']['payload'])
        with self.assertRaisesRegex(ValueError,'squared moments'):audit.phase_metadata(**item)

    def test_nonuniform_cumulative_steps_reject(self):
        item=phase_fixture();item['checkpoint']['payload']['optimizer']['state']['4']['step']['tensor']=5.
        item['checkpoint']['sha256']=audit.digest(item['checkpoint']['payload'])
        with self.assertRaisesRegex(ValueError,'same nondecreasing'):audit.phase_metadata(**item)

    def test_value_head_change_rejects(self):
        item=phase_fixture();item['model']['valueBias']=.2
        item['checkpoint']['sha256']=audit.digest(item['checkpoint']['payload'])
        with self.assertRaisesRegex(ValueError,'value-head'):audit.phase_metadata(**item)

    def test_wrong_previous_phase_checkpoint_rejects(self):
        previous=audit.phase_metadata(**phase_fixture());previous['checkpointSha256']='d'*64
        item=phase_fixture(previous=previous);item['identity']['optimizerFromSha256']='e'*64
        with self.assertRaises(ValueError):audit.phase_metadata(**item)

    def test_constant_baseline_substitution_rejects(self):
        item=phase_fixture();item['identity']['tripleMcObjective']['estimator']=dict(audit.ESTIMATOR,baseline=.6)
        with self.assertRaisesRegex(ValueError,'objective'):audit.phase_metadata(**item)

    def test_phase_recipe_cannot_gain_value_or_entropy_loss(self):
        cfg=config();cfg['valueCoefficient']=.5
        with self.assertRaisesRegex(ValueError,'recipe'):audit.exact_config(cfg,17)

    def test_resealed_actor_dimension_change_rejects(self):
        item=phase_fixture();item['model']['stateWeights'][0].append(.1)
        item['checkpoint']['sha256']=audit.digest(item['checkpoint']['payload'])
        with self.assertRaisesRegex(ValueError,'input dimensions'):audit.phase_metadata(**item)


class TerminalTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.protocol=basic_protocol(self.root/'inputs');self.plan=loop.LoopPlan.read(self.protocol)
        self.rows=[progress_receipt(a,i) for i in range(1,5) for a in self.plan.order(i)]

    def finish(self,rows,reason):
        return dict(kind=loop.VERSION,stopReason=reason,complete=reason is None,eligibleFinalActors=reason is None,
            completedBatches={a:sum(r['arm']==a for r in rows) for a in audit.ARMS},scientificWorlds=len(rows)*256,
            historicalReproductions=len(rows),qualification=False,automaticSuccessor=False,finalBundles={})

    def prefix(self,passed=True):
        return dict(kind='triple-mc-operational-prefix-1',passed=passed,remainingSeconds=20000.,
            roundSeconds=[21.]*4,qualityInspected=False)

    def test_failed_balanced_prefix_is_honest_unqualified_terminal(self):
        self.rows[0]['phases']['card']['branchingWorlds']=31
        result=audit.terminal_contract(self.plan,self.finish(self.rows,'operational-prefix-gate'),self.rows,self.prefix(False))
        self.assertEqual(dict.fromkeys(audit.ARMS,4),result)

    def test_shortened_success_rejects(self):
        with self.assertRaisesRegex(ValueError,'final32'):
            audit.terminal_contract(self.plan,self.finish(self.rows,None),self.rows,self.prefix())

    def test_failed_prefix_cannot_continue(self):
        self.rows[0]['phases']['card']['branchingWorlds']=31
        self.rows.append(progress_receipt('JOINT',5))
        with self.assertRaisesRegex(ValueError,'cannot continue'):
            audit.terminal_contract(self.plan,self.finish(self.rows,'exception'),self.rows,self.prefix(False))

    def test_no_progress_stops_on_first_four_not_at_end_of_paired_round(self):
        rows=[progress_receipt(a,i,progress=a!='E_ONLY') for i in range(1,5) for a in self.plan.order(i)][:-1]
        result=audit.terminal_contract(self.plan,self.finish(rows,'four-no-progress-batches'),rows,None)
        self.assertEqual({'JOINT':3,'E_ONLY':4},result)

    def test_continuation_after_stall_rejects(self):
        rows=[progress_receipt(a,i,progress=a!='E_ONLY') for i in range(1,5) for a in self.plan.order(i)]
        with self.assertRaisesRegex(ValueError,'no-progress stop'):
            audit.terminal_contract(self.plan,self.finish(rows,'four-no-progress-batches'),rows,None)

    def test_prefix_uses_original_wall_measurements_including_release(self):
        prefix=self.prefix();prefix['roundSeconds']=[601.]*4
        with self.assertRaisesRegex(ValueError,'admission'):
            audit.terminal_contract(self.plan,self.finish(self.rows,'exception'),self.rows,prefix)

    def test_changed_execution_order_rejects(self):
        self.rows[0],self.rows[1]=self.rows[1],self.rows[0]
        with self.assertRaisesRegex(ValueError,'alternating'):
            audit.terminal_contract(self.plan,self.finish(self.rows,'exception'),self.rows,self.prefix())

    def test_zero_completed_failure_has_no_fabricated_final(self):
        self.assertEqual(dict.fromkeys(audit.ARMS,0),audit.terminal_contract(self.plan,self.finish([],'exception'),[],None))

    def test_only_complete_original32_pair_schedule_is_eligible(self):
        rows=[progress_receipt(a,i) for i in range(1,33) for a in self.plan.order(i)]
        self.assertEqual(dict.fromkeys(audit.ARMS,32),audit.terminal_contract(self.plan,self.finish(rows,None),rows,self.prefix()))

    def test_complete_inventory_rejects_extra_archived_batch(self):
        run=self.root/'complete';run.mkdir()
        for name in ('finished.json','first-collection-parity.json','operational-prefix.json'):(run/name).touch()
        for arm in audit.ARMS:
            root=run/'arms'/arm
            for section in ('batches','archives','receipts','releases'):(root/section).mkdir(parents=True)
            for i in range(1,33):
                (root/'batches'/f'batch-{i:02d}').mkdir()
                for suffix in ('','.json'):(root/'archives'/f'batch-{i:02d}.tar.gz{suffix}').touch()
                (root/'receipts'/f'batch-{i:02d}.json').touch()
                if i<32:(root/'releases'/f'batch-{i:02d}.json').touch()
        audit.complete_inventory(run,dict.fromkeys(audit.ARMS,32))
        (run/'arms/JOINT/archives/batch-33.tar.gz').touch()
        with self.assertRaisesRegex(ValueError,'exactly32'):audit.complete_inventory(run,dict.fromkeys(audit.ARMS,32))


class ProvenanceTests(unittest.TestCase):
    def test_first_game_equality_excludes_only_signature(self):
        audit.equal_original_games(dict(signature='a',terminal={'reward':.4}),dict(signature='b',terminal={'reward':.4}))

    def test_first_game_terminal_difference_is_not_hidden(self):
        with self.assertRaisesRegex(ValueError,'beyond'):
            audit.equal_original_games(dict(signature='a',terminal={'reward':.4}),dict(signature='b',terminal={'reward':.5}))

    def test_canonical_header_and_entries_compare_after_own_trajectory_binding(self):
        entries=['UNIT \u03b1'];base=dict(kind='triple-on-policy-canonical-1',index=0,replayVerified=True,header={'UNIT':True},
            entries=entries,entriesSha256=hashlib.sha256(json.dumps(entries,ensure_ascii=False,separators=(',',':')).encode()).hexdigest())
        left=audit.canonical_identity(dict(base,signature='a',trajectorySha256='a'*64),'a'*64,'a',0)
        right=audit.canonical_identity(dict(base,signature='b',trajectorySha256='b'*64),'b'*64,'b',0)
        self.assertEqual(left,right)

    def test_canonical_cannot_borrow_other_arm_game_hash(self):
        value=dict(kind='triple-on-policy-canonical-1',index=0,replayVerified=True,header={},entries=[],
            entriesSha256=audit.digest([]),signature='a',trajectorySha256='b'*64)
        with self.assertRaisesRegex(ValueError,'own exact'):audit.canonical_identity(value,'a'*64,'a',0)

    def test_duplicate_json_fields_reject(self):
        with self.assertRaisesRegex(ValueError,'Duplicate'):audit.decode(b'{"a":1,"a":2}')


class ArchivedFixture:
    """Construct the actual completed JSON/capsule schema without running any policy."""
    def __init__(self,root):
        self.root=root;self.run=root/'run';self.run.mkdir();inputs=root/'inputs'
        self.protocol=basic_protocol(inputs)
        for p in audit.PHASES:loop.document(inputs/f'{p}.json',model(p),immutable=False)
        for name in ('ordinary','prior','reference','reader','objective','learner','entrypoint'):
            loop.document(inputs/f'{name}.json',{'UNIT':name})
        fixed={p:loop.subject(inputs/f'{p}.json') for p in ('card','purchase')}
        cp=dict(schemaVersion=1,kind='joint-card-purchase-1',configuration='UNIT-configuration',
            baselineSha256=loop.sha(inputs/'prior.json'),**fixed)
        loop.document(inputs/'bundle.json',cp,immutable=False)
        template=dict(schemaVersion=1,kind='joint-collect',configuration='UNIT-configuration',games=256,seed=0,
            baseline=str(inputs/'ordinary.json'),sharedPurchaseBaseline=str(inputs/'prior.json'),
            bundle=str(inputs/'bundle.json'),opponents=[str(inputs/'bundle.json')],
            jointStateChronology='joint-state-chronology-1',continuationProtocol='ARENA_OVERLAYS_1',
            experimentalRulesTransfer={'sourceRulesVersion':'UNIT-old','targetRulesVersion':'UNIT-current'},
            expansionImitation=dict(candidateModel=str(inputs/'reference.json'),opponentModels=[str(inputs/'reference.json')]),
            tokenOrder=dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']))
        loop.document(inputs/'template.json',template,immutable=False)
        self.protocol.update(initialExpansion=loop.subject(inputs/'expansion.json'),fixedHeads=fixed,
            fixedBundle=loop.subject(inputs/'bundle.json'),jointSourceTemplate=loop.subject(inputs/'template.json'),
            files={str(p):loop.sha(p) for p in inputs.iterdir()})
        self.plan=loop.LoopPlan.read(self.protocol);self.last={};self.indices={};self.receipts=[]

    @staticmethod
    def descriptor(path):
        raw=loop.read(path);value=dict(loop.subject(path),kind=raw['kind'],configuration=raw['configuration'],baselineSha256=raw['baselineSha256'])
        for p in ('card','purchase'):
            actor=loop.read(raw[p]['path']);value[p]=dict(raw[p],**{k:actor[k]
                for k in ('encoderVersion','actorVersion','stateFeatureNames','actionFeatureNames')})
        return value

    def batch(self,arm,batch):
        p=self.protocol;active=loop.active(arm);previous=self.last.get(arm)
        directory=loop.batch_path(self.run,arm,batch);directory.mkdir(parents=True)
        joint,_=self.plan.source(p,arm,batch,directory);loop.document(directory/'joint-source.json',joint)
        _,outer=self.plan.source(p,arm,batch,directory);loop.document(directory/'collection-source.json',outer)
        original_paths=dict(expansion=outer['expansion']['path'],**{phase:loop.read(joint['bundle'])[phase]['path'] for phase in ('card','purchase')})
        originals={phase:loop.read(path) for phase,path in original_paths.items()}
        behavior={phase:loop.sha(path) for phase,path in original_paths.items()}
        own=self.descriptor(Path(joint['bundle']));rival=self.descriptor(Path(p['fixedBundle']['path']))
        source_ref=loop.subject(directory/'collection-source.json');joint_ref=loop.subject(directory/'joint-source.json')
        snapshots={'source.json':source_ref,'joint/source.json':joint_ref,'expansion.json':outer['expansion'],
            'joint/bundle.json':loop.subject(joint['bundle']),'joint/opponent-0-bundle.json':p['fixedBundle'],
            **{'joint/'+phase+'-policy.json':loop.subject(original_paths[phase]) for phase in ('card','purchase')},
            **{'joint/opponent-0-'+phase+'-policy.json':p['fixedHeads'][phase] for phase in ('card','purchase')},
            'joint/baseline.json':loop.subject(joint['baseline']),
            'joint/shared-purchase-baseline.json':loop.subject(joint['sharedPurchaseBaseline']),
            'joint/expansion-imitation-candidate.json':loop.subject(joint['expansionImitation']['candidateModel']),
            'joint/expansion-imitation-opponent-0.json':loop.subject(joint['expansionImitation']['opponentModels'][0])}
        for name,value in snapshots.items():
            target=directory/'collection'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(value['path'],target)
        overlay=dict(version='expansion-imitation-1',decision='expand',featureNames=['UNIT'],
            candidateModel=dict(snapshots['joint/expansion-imitation-candidate.json'],snapshot='expansion-imitation-candidate.json'),
            opponentModels=[dict(snapshots['joint/expansion-imitation-opponent-0.json'],snapshot='expansion-imitation-opponent-0.json')])
        signature=arm+str(batch);replay=self.plan.replay(batch);seeds=[-1000000000+self.plan.offsets[batch-1]+i*8191 for i in range(256)]
        jm=dict(kind='joint-collect',schemaVersion=1,signature=signature,mode='on-policy',trajectoryEncoding='gzip-json-v1',
            source=joint_ref['path'],sourceSha256=joint_ref['sha256'],configuration=joint['configuration'],games=256,
            seed=joint['seed'],seeds=seeds,bundle=own,opponents=[rival],controlledSeat='index % playerCount',
            opponentRotation='(index / playerCount + seat) % opponentCount',reward='.8 * winShare + .2 * rankReward',
            replayIndices=[replay],baseline=snapshots['joint/baseline.json'],
            sharedPurchaseBaseline=dict(snapshots['joint/shared-purchase-baseline.json'],snapshot='shared-purchase-baseline.json'),
            expansionImitation=overlay,tokenOrder=joint['tokenOrder'],continuationProtocol=joint['continuationProtocol'],
            jointStateChronology=joint['jointStateChronology'])
        manifest=dict(kind='triple-on-policy-collect-1',schemaVersion=1,signature=signature,trajectoryEncoding='gzip-json-v1',
            source=dict(source_ref,snapshot='source.json'),jointSource=dict(joint_ref,snapshot='joint/source.json'),joint=jm,
            expansion=dict(outer['expansion'],snapshot='expansion.json',modelSchemaVersion=2,**{k:originals['expansion'][k]
                for k in ('actorVersion','encoderVersion','stateFeatureNames','actionFeatureNames')}),
            controlledBehavior=behavior,snapshots=snapshots,replayIndices=[replay])
        loop.document(directory/'collection/collection.json',manifest)
        loop.document(directory/'collection/started.json',dict(signature=signature,workers=self.plan.workers,
            startedAt='2000-01-01T00:00:00Z',deadlineAt='2000-01-01T00:01:00Z'))
        hashes={};origins=[];reward=.3
        for i in range(256):
            game=dict(kind='triple-on-policy-trajectory-1',schemaVersion=1,signature=signature,index=i,
                configuration=joint['configuration'],gameSeed=seeds[i],controlledSeat=i%4,opponentIndices=[0]*4,
                bundleSha256=own['sha256'],**{phase+'ModelSha256':h for phase,h in behavior.items()},jointEvidence=[])
            for ordinal,phase in enumerate(audit.PHASES):
                seed=(seeds[i]^(ordinal*65537+(i%4)*8191));seed=(seed+2**31)%2**32-2**31
                salt=dict(expansion=0x9b05688c,card=0x3c6ef372,purchase=0x6a09e667)[phase]
                row=dict(number=ordinal,turn=1,decision=dict(expansion='expand',card='play-cards',purchase='purchase-advances')[phase],
                    selectedIndex=0,behaviorLogProbability=-.7,behaviorValue=.5,actorSeed=((seed^salt)+2**31)%2**32-2**31,
                    observation=dict(encoderVersion=originals[phase]['encoderVersion'],stateFeatures={'state':.25},baselineIndex=0,
                        actions=[dict(command='pass',features={'choice':0.}),dict(command='UNIT-action',features={'choice':1.})]))
                if phase=='expansion':row.update(batchStartNumber=ordinal,botSeed=seed)
                game[phase+'Decisions']=[] if i%16==0 else [row]
            game['terminal']=dict(winShare=.25,rankReward=.5,reward=reward,gameDecisions=4,
                scores=[dict(player='p'+str(seat+1),advancePoints=100,cash=0,misery=0,total=100,winner=True) for seat in range(4)])
            path=directory/'collection'/f'game-{i}.json.gz';path.write_bytes(gzip.compress(json.dumps(game).encode(),mtime=0));hashes[path.name]=loop.sha(path)
            origins.append(dict(index=i,gameSeed=seeds[i],controlledSeat=i%4,**{phase+'Decisions':len(game[phase+'Decisions']) for phase in audit.PHASES}))
        canonical=dict(kind='triple-on-policy-canonical-1',signature=signature,index=replay,trajectorySha256=hashes[f'game-{replay}.json.gz'],
            replayVerified=True,header={'UNIT':True},entries=['UNIT'],entriesSha256=audit.digest(['UNIT']))
        loop.document(directory/'collection'/f'replay-{replay}.json',canonical)
        done=dict(kind='triple-on-policy-collect-1',signature=signature,complete=True,games=256,gameSha256=hashes,
            canonicalSha256={f'replay-{replay}.json':loop.sha(directory/'collection'/f'replay-{replay}.json')})
        loop.document(directory/'collection/finished.json',done)
        loop.document(directory/'reproduction/replay.json',dict(kind='triple-on-policy-reproduction-1',signature=signature,index=replay,
            passed=True,completeTypedRecordsEqual=True,completeCanonicalEqual=True,trajectorySha256=hashes[f'game-{replay}.json.gz'],
            canonicalSha256=done['canonicalSha256'][f'replay-{replay}.json']))
        inputs=self.root/'inputs';common=dict(collectionSha256=loop.sha(directory/'collection/collection.json'),
            completionSha256=loop.sha(directory/'collection/finished.json'),collectionSignature=signature,
            gameHashes=[hashes[f'game-{i}.json.gz'] for i in range(256)],games=256,gameMeanReward=reward,
            originCoverage=origins,behaviorModels=behavior,collectionReaderSha256=loop.sha(inputs/'reader.json'),readerSha256=loop.sha(inputs/'reader.json'))
        ecology=dict(configuration=joint['configuration'],ordinary=dict(jm['baseline'],snapshot='baseline.json'),
            purchasePrior=jm['sharedPurchaseBaseline'],expansionReference=overlay,tokenOrder=jm['tokenOrder'],
            rules=joint['experimentalRulesTransfer'],rivalBundle=rival,
            dictionaries={phase:{k:originals[phase][k] for k in ('encoderVersion','stateFeatureNames','actionFeatureNames')} for phase in audit.PHASES})
        allpins=dict(p['files'])
        if previous:allpins.update(previous.original_hashes)
        allpins.update({str(path):loop.sha(path) for path in directory.rglob('*') if path.is_file()})
        kind='triple-mc-update-1' if arm=='JOINT' else 'triple-mc-e-only-control-1'
        configs={phase:config(self.plan.shuffles[batch-1][phase]) for phase in active}
        numeric=dict(kind=kind,behaviorModels=behavior,collection=common,ecology=ecology,inputHashes=allpins,
            previous=None if previous is None else dict(path=str(Path(previous.document['originalRoot'])/'numeric'),
                completionSha256=previous.files['numeric/finished.json']['sha256']),maximumAttempts=1,automaticResume=False)
        if arm=='JOINT':numeric['configs']=configs
        else:numeric.update(config=configs['expansion'],frozenCardPurchase=own)
        loop.document(directory/'numeric/identity.json',numeric);reports={};progress={}
        for phase in active:
            item=phase_fixture(phase);identity=item['identity'];objective=identity['tripleMcObjective']
            identity.update(modelSha256=behavior[phase],config=configs[phase],
                data=dict(common,phase=phase,behaviorModelSha256=behavior[phase],readerSha256=loop.sha(inputs/'objective.json')),
                collectionReaderSource=loop.subject(inputs/'objective.json'),tripleMcOwnerSha256=loop.sha(inputs/'objective.json'),
                learnerSha256=loop.sha(inputs/'learner.json'),entrypointSha256=loop.sha(inputs/'entrypoint.json'))
            objective.update(ecology=ecology,behaviorModels=behavior,collectionSha256=common['collectionSha256'],gameHashes=common['gameHashes'])
            inherited=0
            if previous:
                oldidentity=previous.read(f'numeric/{phase}/identity.json');oldcheckpoint=previous.read(f'numeric/{phase}/checkpoint.json')
                inherited=oldcheckpoint['payload']['optimizer']['state']['0']['step']['tensor']
                identity['optimizerFromSha256']=previous.files[f'numeric/{phase}/checkpoint.json']['sha256']
                identity['tripleMcOptimizerContinuation']=dict(version='triple-mc-adam-continuation-1',phase=phase,actorAdamSteps=inherited,
                    checkpointSha256=identity['optimizerFromSha256'],previousIdentitySha256=audit.digest(oldidentity))
                objective['optimizerInitialization']='matching-triple-mc-adam'
            updated=copy.deepcopy(originals[phase]);updated['actorWeights'][0]+=.125
            payload=item['checkpoint']['payload'];payload.update(identitySha256=audit.digest(identity),model=updated,optimizer=optimizer(inherited+4))
            finished=item['finished'];finished.update(identitySha256=audit.digest(identity),inputModelSha256=behavior[phase],
                tripleMcObjective=objective,games=256,decisions=240,gameMeanReward=reward)
            phase_root=directory/'numeric'/phase
            loop.document(phase_root/'policy.json',updated);finished['outputModelSha256']=loop.sha(phase_root/'policy.json')
            for name,value in [('identity',identity),('checkpoint',dict(sha256=audit.digest(payload),payload=payload)),('finished',finished)]:loop.document(phase_root/f'{name}.json',value)
            loop.document(phase_root/'epoch-started.json',dict(kind=phase+'-epoch-started',identitySha256=audit.digest(identity)))
            reports[phase]=dict(policySha256=loop.sha(phase_root/'policy.json'),checkpointSha256=loop.sha(phase_root/'checkpoint.json'),
                identitySha256=loop.sha(phase_root/'identity.json'),completionSha256=loop.sha(phase_root/'finished.json'),
                decisions=240,acceptedEpochs=4,stopReason='epochs-complete')
            progress[phase]=dict(acceptedEpochs=4,actorChanged=True,actorAdamSteps=inherited+4,previousActorAdamSteps=inherited,decisions=240,branchingWorlds=240)
        cp_subject=p['fixedBundle']
        if arm=='JOINT':
            loop.document(directory/'numeric/bundle.json',dict(schemaVersion=1,kind='joint-card-purchase-1',configuration=joint['configuration'],
                baselineSha256=own['baselineSha256'],**{phase:loop.subject(directory/'numeric'/phase/'policy.json') for phase in ('card','purchase')}))
            cp_subject=loop.subject(directory/'numeric/bundle.json')
        loop.document(directory/'numeric/triple-bundle.json',dict(schemaVersion=1,kind='triple-actor-bundle-1',configuration=joint['configuration'],
            baselineSha256=own['baselineSha256'],expansion=loop.subject(directory/'numeric/expansion/policy.json'),jointBundle=cp_subject))
        outputs={str(path.relative_to(directory/'numeric')):loop.sha(path) for path in (directory/'numeric').rglob('*.json')
            if path!=directory/'numeric/identity.json' and path.name!='epoch-started.json'}
        numeric_done=dict(kind=kind,complete=True,identitySha256=audit.digest(numeric),phases=reports,outputHashes=outputs,
            allInputHashesUnchanged=True,strengthClaim=False,policyPromotion=False)
        if arm=='E_ONLY':numeric_done['frozenCardPurchase']=own
        loop.document(directory/'numeric/finished.json',numeric_done)
        update=dict(kind='triple-mc-matched-numeric-1',arm=arm,batch=batch,numericCompletionSha256=loop.sha(directory/'numeric/finished.json'),
            phases=progress,outputBundle=loop.subject(directory/'numeric/triple-bundle.json'),complete=True)
        loop.document(directory/'numeric-complete.json',update)
        for name in ('collection.log','reproduction.log','numeric.log'):(directory/name).write_text('UNIT only\n')
        archive=self.run/'arms'/arm/'archives'/f'batch-{batch:02d}.tar.gz';capsule=storage.archive_batch(directory,archive,arm,batch)
        receipt=dict(kind='triple-mc-matched-batch-1',arm=arm,batch=batch,complete=True,worlds=256,reproductions=1,
            sourceSha256=loop.sha(directory/'collection-source.json'),jointSourceSha256=loop.sha(directory/'joint-source.json'),capsule=capsule,
            numericCompletionSha256=update['numericCompletionSha256'],outputBundle=update['outputBundle'],
            previousCompletion=None if previous is None else audit.subject(previous,'numeric/finished.json'),
            previousCheckpoints={} if previous is None else {phase:audit.subject(previous,f'numeric/{phase}/checkpoint.json') for phase in active},
            phases=progress,elapsedSeconds=10.,retainedBytes=archive.stat().st_size+Path(capsule['indexPath']).stat().st_size)
        receipt_path=self.run/'arms'/arm/'receipts'/f'batch-{batch:02d}.json';loop.document(receipt_path,receipt)
        index=storage.BatchIndex(archive);storage.prune(index)
        if previous:
            (self.run/'arms'/arm/'releases').mkdir(exist_ok=True)
            storage.release_numeric(previous,receipt_path,self.run/'arms'/arm/'releases'/f'batch-{batch-1:02d}.json')
        self.indices[arm,batch]=index;self.last[arm]=index;self.receipts.append(receipt)
        if all((a,1) in self.indices for a in audit.ARMS) and not (self.run/'first-collection-parity.json').exists():
            loop.document(self.run/'first-collection-parity.json',loop.first_collection_parity({a:self.indices[a,1] for a in audit.ARMS}))
        return index

    def finish(self):
        loop.terminal(self.run,self.receipts,'exception')
        loop.document(self.run/'failure.json',dict(kind=loop.VERSION,failure='UNIT stopped before next child',
            completedBatches=len(self.receipts),automaticRetry=False,eligibleFinalActors=False))


class ArchiveAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()

    def test_original_joint_capsule_audits_after_raw_collection_prune(self):
        fixture=ArchivedFixture(self.root);index=fixture.batch('JOINT',1);fixture.finish()
        self.assertFalse(Path(index.files['collection/game-0.json.gz']['originalPath']).exists())
        result=audit.verify(fixture.protocol,fixture.run)
        self.assertTrue(result['passed']);self.assertFalse(result['eligibleFinalActors'])
        self.assertEqual(256,result['scientificWorlds'])

    def test_control_carry_resolves_pruned_originals_and_retains_fixed_cp(self):
        fixture=ArchivedFixture(self.root)
        fixture.batch('JOINT',1);first=fixture.batch('E_ONLY',1);fixture.batch('E_ONLY',2);fixture.finish()
        self.assertFalse(Path(first.files['numeric/expansion/checkpoint.json']['originalPath']).exists())
        result=audit.verify(fixture.protocol,fixture.run)
        self.assertEqual({'JOINT':1,'E_ONLY':2},result['completedBatches']);self.assertEqual(768,result['scientificWorlds'])

    def test_joint_carry_resolves_all_three_original_phase_checkpoints(self):
        fixture=ArchivedFixture(self.root);first=fixture.batch('JOINT',1)
        fixture.batch('E_ONLY',1);fixture.batch('E_ONLY',2);fixture.batch('JOINT',2);fixture.finish()
        self.assertTrue(all(not Path(first.files[f'numeric/{p}/checkpoint.json']['originalPath']).exists() for p in audit.PHASES))
        result=audit.verify(fixture.protocol,fixture.run)
        self.assertEqual(dict.fromkeys(audit.ARMS,2),result['completedBatches'])

    def test_tampered_receipt_even_with_terminal_hash_resealed_rejects(self):
        fixture=ArchivedFixture(self.root);fixture.batch('JOINT',1);fixture.finish()
        path=fixture.run/'arms/JOINT/receipts/batch-01.json';receipt=loop.read(path)
        receipt['phases']['expansion']['actorAdamSteps']+=1;loop.document(path,receipt,immutable=False)
        terminal=loop.read(fixture.run/'finished.json');terminal['receiptSha256']['JOINT']['1']=loop.sha(path)
        loop.document(fixture.run/'finished.json',terminal,immutable=False)
        with self.assertRaisesRegex(ValueError,'independently derived'):audit.verify(fixture.protocol,fixture.run)

    def test_changed_archive_rejects_before_semantic_audit(self):
        fixture=ArchivedFixture(self.root);index=fixture.batch('JOINT',1);fixture.finish()
        with index.archive.open('ab') as stream:stream.write(b'UNIT mutation')
        with self.assertRaises(ValueError):audit.verify(fixture.protocol,fixture.run)

    def test_latest_loose_model_substitution_rejects(self):
        fixture=ArchivedFixture(self.root);index=fixture.batch('JOINT',1);fixture.finish()
        Path(index.files['numeric/expansion/policy.json']['originalPath']).write_text('{}')
        with self.assertRaisesRegex(ValueError,'loose duplicate'):audit.verify(fixture.protocol,fixture.run)


if __name__=='__main__':unittest.main()
