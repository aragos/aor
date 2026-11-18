import ast
import copy
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import random
from statistics import NormalDist
import sys
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import token_paired_campaign_audit as a
import token_paired_campaign_plan as p
import token_paired_storage as storage
from test_token_paired_campaign_plan import fixture


def write(path, value):
    path = Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(p.canonical(value)); return p.subject(path)


def model():
    return dict(schemaVersion=3,encoderVersion='aor-token-observation-2',hiddenSize=64,
        baselineProbability=.97,stateFeatureNames=['own.cash'],actionFeatureNames=['amount'],
        stateWeights=[.01]*64,stateBias=[.0]*64,actionWeights=[.02]*64,actionBias=[.0]*64,
        actorWeights=[.0]*64,valueWeights=[.0]*64,valueBias=.0)


def archive(path, files):
    with tarfile.open(path,'w:gz') as out:
        for name,raw in files.items():
            member = tarfile.TarInfo(name); member.size = len(raw)
            out.addfile(member,io.BytesIO(raw))
    return dict(archive=path.name,archiveSha256=p.file_sha(path),files={name:
        dict(size=len(raw),sha256=hashlib.sha256(raw).hexdigest()) for name,raw in files.items()})


def slots(steps):
    result = {}
    for index,name in enumerate(a.ACTOR_PARAMETERS):
        tensor = [[.0] for _ in range(64)] if name in ('stateWeights','actionWeights') else [.0]*64
        result[str(index)] = dict(step=dict(tensor=float(steps),dtype='torch.float32'),
            exp_avg=dict(tensor=tensor,dtype='torch.float64'),
            exp_avg_sq=dict(tensor=copy.deepcopy(tensor),dtype='torch.float64'))
    return dict(state=result if steps else {},param_groups=[dict(params=list(range(5)),lr=.0003,
        betas=[.9,.999],eps=1e-8,weight_decay=0,amsgrad=False,maximize=False)])


def fit_fixture(directory, *, accepted=1, initial_mean=.0):
    initial=model(); output=copy.deepcopy(initial)
    if accepted: output['actorWeights'][0]=.01
    active=[True]+[False]*511;seeds=list(range(1,21))
    context=dict(iteration=1,source_sha256='a'*64,scope_sha256='b'*64,request_bank_sha256='c'*64,
        dictionary_sha256='d'*64,dictionary_semantic_sha256=a.model_metadata(initial)['dictionarySha256'],
        original_model_sha256='e'*64,current_model_sha256='e'*64)
    identity=dict(kind='token-paired-fit-identity-1',recipe=a.FIT_RECIPE,context=context,
        inputModelCanonicalSha256=a.digest(initial),frozenModelFieldsSha256=a.model_metadata(initial)['frozenSha256'],
        inputActorParametersSha256=a.actor_parameters_sha256(initial),
        trainingSha256='f'*64,anchorSha256='1'*64,predecessor=None,
        publicCoverage=dict(trainingReady=256,validationReady=64),epochSeeds=seeds,originalDeadline=22600.,
        sourceSha256={name:p.file_sha(Path(a.__file__).with_name(name)) for name in
            ('token_paired_fit.py','token_paired_data.py','token_paired_policy.py','token_paired_identity.py','purchase_policy.py')})
    history=[dict(epoch=0,accepted=True,anchor=dict(mean=initial_mean,maximum=initial_mean),actorSha256=a.digest(initial))]
    for epoch in range(1,accepted+1):
        history.append(dict(epoch=epoch,accepted=True,anchor=dict(mean=.01,maximum=.05),
            attemptedAdamSteps=1,actorSha256=a.digest(output)))
    history.append(dict(epoch=accepted+1,accepted=False,anchor=dict(mean=.03,maximum=.05),
        attemptedAdamSteps=1,actorSha256=a.digest(output)))
    write(directory/'identity.json',identity);write(directory/'history.json',history)
    policy=write(directory/'policy.json',output)
    write(directory/'checkpoint.json',dict(kind='token-paired-adam-1',model=output,
        optimizer=slots(accepted),acceptedEpochs=accepted,adamSteps=accepted))
    done=dict(kind='token-paired-fit-complete-1',complete=True,identity=identity,
        acceptedEpochs=accepted,attemptedEpochs=accepted+1,actorChanged=bool(accepted),
        operationalPassed=bool(accepted),adamSteps=accepted,stopReason='original-kl-limit',
        outputModelSha256=policy['sha256'],artifacts={name:p.file_sha(directory/name) for name in a.FIT_FILES})
    write(directory/'finished.json',done)
    arguments=(directory,a.Pins(),context,a.model_metadata(initial),a.model_metadata(initial),
        identity['publicCoverage'],'1'*64,'f'*64,seeds,active,None,22600.)
    return arguments


def reseal(directory):
    done=p.read(directory/'finished.json')
    done['artifacts']={name:p.file_sha(directory/name) for name in a.FIT_FILES}
    done['outputModelSha256']=done['artifacts']['policy.json']
    write(directory/'finished.json',done)


def plan_fixture():
    value=fixture(); initial=model()
    dictionary={key:initial[key] for key in ('encoderVersion','stateFeatureNames','actionFeatureNames')}
    bindings={'jointPolicy':{'card':{'sha256':'d'*64,'path':'/constructed','snapshot':'card.json'}}}
    value.update(originalActorSha256=p.digest(initial),dictionarySha256=p.digest(dictionary),
        ecologySha256=p.digest(storage.portable(bindings)))
    return p.Plan.from_record(value),initial,dictionary,bindings


def missing_chunk(root, plan, initial, dictionary, bindings, chunk):
    directory=root/'iterations/iteration-1/chunks'/f'chunk-{chunk}';raw=directory/'native'
    raw.mkdir(parents=True)
    def put(name,value):return write(raw/name,value)['sha256']
    actor=put('current-actor.json',initial);put('original-actor.json',initial)
    arena=put('arena.json',{});put('dictionary.json',dictionary)
    source=plan.collection_source(1,chunk,p.owner(chunk),p.subject(raw/'arena.json'),
        p.subject(raw/'current-actor.json'),p.subject(raw/'original-actor.json'))
    source_sha=put('source.json',source)
    native=dict(kind='token-paired-collect-1',iteration=1,signature='a'*64,scopeSha256='b'*64,
        scienceSha256='c'*64,scienceIdentity={'kind':'constructed'},range=dict(globalStart=chunk*64,count=64),
        source=dict(sha256=source_sha,snapshot='source.json'),arena=dict(sha256=arena),
        currentActor=dict(sha256=actor),originalActor=dict(sha256=actor),
        dictionary=dict(path='dictionary.json',sha256=plan.record['dictionarySha256']),
        snapshots={name:p.file_sha(raw/name) for name in
            ('current-actor.json','original-actor.json','arena.json','dictionary.json','source.json')},
        origins=plan.record['iterations'][0]['origins'],requests=[p.request(i) for i in p.indices(chunk)],
        replayIndices=list(p.COLLECTION_REPLAYS),ownedReplayIndices=list(p.replays(chunk)),
        policyBindings=bindings,ecologySha256=plan.record['ecologySha256'])
    put('prepared.json',native);rows=[]
    for index in p.indices(chunk):
        origin=f'origin-{index}.json.gz';labels=f'labels-{index}.json';public=f'public-{index}.json'
        (raw/origin).write_bytes(b'OPAQUE invalid gzip');(raw/labels).write_bytes(b'{"FORBIDDEN":POISON}')
        row=dict(globalIndex=index,status='MISSING',origin=dict(file=origin,sha256=p.file_sha(raw/origin)),
            labels=dict(file=labels,sha256=p.file_sha(raw/labels)),tailGames=0,anchorRows=0)
        value=dict(kind='token-paired-public-1',signature=native['signature'],scopeSha256=native['scopeSha256'],
            scienceSha256=native['scienceSha256'],iteration=1,request=p.request(index),
            streams=plan.record['iterations'][0]['origins'][index],status='MISSING',
            originSha256=row['origin']['sha256'],missingReason='constructed',observedWindows=0,
            publicRoot=None,anchors=[])
        row['public']=dict(file=public,sha256=put(public,value));rows.append(row)
    put('finished.json',dict(kind='token-paired-finished-1',complete=True,iteration=1,signature=native['signature'],
        scopeSha256=native['scopeSha256'],scienceSha256=native['scienceSha256'],range=native['range'],
        origins=rows,backboneGames=64,tailGames=0,scientificTerminalGames=64,
        readyRoots=0,singletonRoots=0,missingRoots=64,capturedRoots=0))
    replays=[]
    for index in p.replays(chunk):
        name=f'replay-{index}/replay-{index}.json'
        replays.append(dict(globalIndex=index,path=name,sha256=put(name,{'constructed':True}),
            originalSha256=rows[index-chunk*64]['origin']['sha256'],scientificTerminalGames=1))
    receipt=storage.verify_collection(plan,raw,raw/'source.json',chunk,1,actor,
        lambda _,index,*__:next(row for row in replays if row['globalIndex']==index))
    stored=storage.archive_collection(raw,directory/'evidence.tar.gz',receipt)
    return p.subject(directory/'evidence.tar.gz.json'),stored


def lifecycle(root,plan,host,status='stopped',reason='public-exposure-gate'):
    subject=write(root.parent/'protocol.json',plan.record)
    clock=dict(kind='token-paired-clock-1',protocolSha256=subject['sha256'],startedEpoch=1000.,deadlineEpoch=22600.)
    write(root/'clock.json',clock)
    write(root/'worker-owner.json',dict(kind='token-paired-worker-owner-1',pid=101,processGroup=100))
    terminal=dict(kind='token-paired-terminal-1',host=host,status=status,reason=reason,
        completedIterations=0,updatedEpoch=1002.,clock=clock)
    write(root/'terminal.json',terminal)
    return subject,terminal


class ArchiveTests(unittest.TestCase):
    def test_only_requested_members_decode_and_all_opaque_bytes_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'evidence.tar.gz'
            receipt=archive(path,{'public.json':b'{"x":1}','labels.json':b'FORBIDDEN'})
            pins=a.Pins();self.assertEqual(a.stream_archive(path,receipt,pins,['public.json']),{'public.json':{'x':1}})
            self.assertEqual(pins.values[str(path)],receipt['archiveSha256'])

    def test_raw_member_hash_failure_precedes_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'evidence.tar.gz';receipt=archive(path,{'public.json':b'INVALID'})
            receipt['files']['public.json']['sha256']='0'*64
            with self.assertRaisesRegex(ValueError,'before semantic decoding'):
                a.stream_archive(path,receipt,a.Pins(),['public.json'])

    def test_requested_gzip_world_decodes_after_exact_raw_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'evidence.tar.gz';receipt=archive(path,{'world-0.json.gz':gzip.compress(b'{"world":0}')})
            self.assertEqual(a.stream_archive(path,receipt,a.Pins(),['world-0.json.gz']),{'world-0.json.gz':{'world':0}})

    def test_repeated_archive_pass_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'evidence.tar.gz';receipt=archive(path,{'public.json':b'{}'});pins=a.Pins()
            a.stream_archive(path,receipt,pins,())
            with self.assertRaisesRegex(ValueError,'only once'):a.stream_archive(path,receipt,pins,())

    def test_archive_changed_after_pass_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'evidence.tar.gz';receipt=archive(path,{'public.json':b'{}'});pins=a.Pins()
            a.stream_archive(path,receipt,pins,());path.write_bytes(path.read_bytes()+b'changed')
            with self.assertRaisesRegex(ValueError,'after audit'):pins.unchanged()

    def test_unexpected_member_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'evidence.tar.gz';receipt=archive(path,{'extra.json':b'{}'})
            receipt['files']={}
            with self.assertRaisesRegex(ValueError,'undeclared'):a.stream_archive(path,receipt,a.Pins(),())


class NumericReceiptTests(unittest.TestCase):
    def test_actor_change_uses_parameter_values_not_json_number_spelling(self):
        before=model();after=copy.deepcopy(before)
        before['actorWeights'][0]=0;after['actorWeights'][0]=-.0
        self.assertNotEqual(a.model_metadata(before)['semanticSha256'],a.model_metadata(after)['semanticSha256'])
        self.assertEqual(a.model_metadata(before)['actorParametersSha256'],a.model_metadata(after)['actorParametersSha256'])

    def test_last_accepted_checkpoint_excludes_rejected_adam_steps(self):
        with tempfile.TemporaryDirectory() as directory:
            args=fit_fixture(Path(directory));result=a.transaction(*args)
            self.assertEqual(result['done']['adamSteps'],1)

    def test_epoch_zero_is_diagnostic_even_above_hard_epoch_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            args=fit_fixture(Path(directory),initial_mean=.5)
            self.assertTrue(a.transaction(*args)['done']['operationalPassed'])

    def test_first_epoch_rejection_retains_exact_input_and_empty_adam(self):
        with tempfile.TemporaryDirectory() as directory:
            args=fit_fixture(Path(directory),accepted=0);result=a.transaction(*args)
            self.assertFalse(result['done']['operationalPassed']);self.assertEqual(result['done']['adamSteps'],0)

    def test_accepted_kl_beyond_fixed_limit_rejected_even_with_resealed_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);args=fit_fixture(root);history=p.read(root/'history.json')
            history[1]['anchor']['maximum']=.101;write(root/'history.json',history);reseal(root)
            with self.assertRaisesRegex(ValueError,'fixed inequalities'):a.transaction(*args)

    def test_optimizer_carry_from_previous_iteration_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);args=fit_fixture(root);checkpoint=p.read(root/'checkpoint.json')
            checkpoint['optimizer']['state']['0']['step']['tensor']=161.
            write(root/'checkpoint.json',checkpoint);reseal(root)
            with self.assertRaisesRegex(ValueError,'restart at zero'):a.transaction(*args)

    def test_value_head_adam_slot_rejected(self):
        value=slots(1);value['state']['5']=copy.deepcopy(value['state']['0'])
        with self.assertRaisesRegex(ValueError,'five actor'):a.optimizer(value,a.model_metadata(model())['shapes'],1)

    def test_negative_second_moment_is_rejected(self):
        value=slots(1);value['state']['0']['exp_avg_sq']['tensor'][0][0]=-.001
        with self.assertRaisesRegex(ValueError,'nonnegative second moments'):
            a.optimizer(value,a.model_metadata(model())['shapes'],1)

    def test_changed_predecessor_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args=list(fit_fixture(Path(directory)));args[-2]={'finishedSha256':'a'*64,'modelSha256':'b'*64}
            with self.assertRaisesRegex(ValueError,'fixed model chain'):a.transaction(*args)

    def test_renewed_numeric_deadline_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args=list(fit_fixture(Path(directory)));args[-1]=23000.
            with self.assertRaisesRegex(ValueError,'original shared'):a.transaction(*args)

    def test_shuffle_partition_counts_nonempty_slots_and_rollback(self):
        active=[i%17==0 for i in range(512)];seeds=list(range(1,21));counts=[]
        for seed in seeds[:2]:
            order=list(range(512));random.Random(seed).shuffle(order)
            counts.append(sum(any(active[i] for i in order[j:j+64]) for j in range(0,512,64)))
        history=[{},dict(epoch=1,accepted=True,attemptedAdamSteps=counts[0]),
            dict(epoch=2,accepted=False,attemptedAdamSteps=counts[1])]
        self.assertEqual(a.accepted_steps(active,seeds,history),counts[0])

    def test_recipe_literal_matches_source_without_importing_numeric_owner(self):
        tree=ast.parse(Path(a.__file__).with_name('token_paired_fit.py').read_text())
        statement=next(node for node in tree.body if isinstance(node,ast.Assign) and
            any(isinstance(target,ast.Name) and target.id=='RECIPE' for target in node.targets))
        namespace={'dict':dict};exec(compile(ast.Module(body=[statement],type_ignores=[]),'constructed-recipe','exec'),namespace)
        self.assertEqual(a.FIT_RECIPE,namespace['RECIPE'])


class ValidationTests(unittest.TestCase):
    def test_duplicate_chunk_cannot_move_validation_labels_into_training_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);plan,*_=plan_fixture();subjects=[]
            for index,chunk in enumerate([0,1,2,3,4,5,6,9,9,9]):
                subjects.append(write(root/f'chunk-{index}.json',dict(chunk=chunk,iteration=1)))
            subject=write(root/'corpus.json',dict(kind='token-paired-corpus-1',complete=True,
                protocolDigest=plan.sha256,origins=640,trainOrigins=512,validationOrigins=128,
                iteration=1,chunks=subjects))
            with patch.object(a,'inspect_chunk',side_effect=AssertionError('No label member may be read.')):
                with self.assertRaisesRegex(ValueError,'precede any TRAIN'):
                    a.corpus(plan,subject['path'],a.Pins(),labels=False)

    def test_second_probability_seal_is_required_before_any_corpus_label_reader(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);plan,*_=plan_fixture()
            for iteration in (1,2):
                base=root/'iterations'/f'iteration-{iteration}';fit=base/'fit'
                fit_fixture(fit);done=p.read(fit/'finished.json');identity=done['identity']
                identity['context']['iteration']=iteration
                if iteration==2:
                    previous=root/'iterations/iteration-1/fit'
                    identity['context']['current_model_sha256']=p.file_sha(previous/'policy.json')
                    identity['predecessor']=dict(finishedSha256=p.file_sha(previous/'finished.json'),
                        modelSha256=p.file_sha(previous/'policy.json'))
                write(fit/'identity.json',identity);write(fit/'finished.json',done);reseal(fit)
                if iteration==1:
                    write(base/'validation.json',dict(kind='token-paired-validation-probabilities-1',
                        complete=True,semanticLabelsDecoded=False,context=identity['context'],
                        fitFinishedSha256=p.file_sha(fit/'finished.json'),modelSha256=p.file_sha(fit/'policy.json'),
                        originalModelSha256=identity['context']['original_model_sha256'],
                        currentModelSha256=identity['context']['current_model_sha256'],
                        rows=[dict(index=i,status='READY' if i<576 else 'MISSING') for i in range(512,640)],
                        counts=dict(READY=64,SINGLETON=0,MISSING=64)))
            terminal=dict(status='completed',reason='two-fixed-iterations-and-screen',completedIterations=2)
            with patch.object(a,'corpus',side_effect=AssertionError('Labels must remain closed.')):
                with self.assertRaisesRegex(ValueError,'regular file'):
                    a._desktop(plan,'a'*64,root,terminal,a.Pins())

    def test_paired_effect_uses_inclusion_sum_and_missing_is_not_zero_imputed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);public={};labels={};rows=[];effects=[]
            for index,returns in ((512,[[.2,.4],[.8,.6],[.1,.3]]),
                                  (513,[[.7,.5],[.2,.3],[.9,.8]])):
                row=dict(index=index,status='READY',requestSha256=str(index%10)*64,
                    futureSeeds=[index,index+1000],proposedIndices=[0,1,2],inclusionProbability=.5,
                    currentProbabilities=[.5,.3,.2])
                public[index]=row
                labels[index]=dict(actions=[dict(rewards=value) for value in returns])
                rows.append(dict(row,publicSha256=a.digest(row),probabilities=[.4,.4,.2]))
                gains=[(.4-.3)*(returns[1][stream]-returns[0][stream])/.5 for stream in range(2)]
                effects.append(dict(index=index,status='READY',pairedFutureGains=gains,gain=math.fsum(gains)/2))
            missing=dict(index=514,status='MISSING',requestSha256='4'*64,futureSeeds=[514,1514],
                proposedIndices=None,inclusionProbability=None,currentProbabilities=None)
            public[514]=missing;labels[514]=None;rows.append(dict(missing,publicSha256=a.digest(missing),probabilities=None))
            effects.append(dict(index=514,status='MISSING',gain=None))
            seal=dict(rows=rows,context={'iteration':2},modelSha256='a'*64,
                counts=dict(READY=2,SINGLETON=0,MISSING=1))
            subject=write(root/'validation.json',seal);values=[row['gain'] for row in effects[:2]]
            mean=math.fsum(values)/2;variance=math.fsum((x-mean)**2 for x in values)
            margin=NormalDist().inv_cdf(.975)*math.sqrt(variance/2)
            report=dict(kind='token-paired-validation-effect-1',probabilitySeal=subject,
                context=seal['context'],modelSha256=seal['modelSha256'],counts=seal['counts'],
                estimate=dict(mean=mean,lower=mean-margin,upper=mean+margin,origins=2,
                    interval='two-sided-normal-95-independent-origin'),perOrigin=effects,
                optimizerSteps=0,modelForwards=0,selectedCheckpoint=False)
            write(root/'effect.json',report)
            result=a._effect(root/'effect.json',root/'validation.json',seal,dict(public=public,labels=labels),a.Pins())
            self.assertEqual(result['sha256'],p.file_sha(root/'effect.json'))

    def test_stored_world_hash_includes_unicode_exactly_like_numeric_owner(self):
        self.assertEqual(a.digest({'command':'buy-tokens −1'}),p.digest({'command':'buy-tokens −1'}))


class TerminalTests(unittest.TestCase):
    def test_clean_import_closure_contains_no_numerical_library(self):
        result=subprocess.run([sys.executable,'-B','-c',
            'import sys; import token_paired_campaign_audit; import token_paired_screen; '
            'assert not ({"torch","numpy"} & set(sys.modules))'],
            cwd=Path(a.__file__).parent,capture_output=True,text=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr)

    def test_full_public_exposure_stop_retains_all640_worlds_without_label_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'run';plan,initial,dictionary,bindings=plan_fixture()
            protocol,_=lifecycle(root,plan,'DESKTOP');subjects=[]
            for chunk in range(10):subjects.append(missing_chunk(root,plan,initial,dictionary,bindings,chunk)[0])
            write(root/'iterations/iteration-1/corpus.json',storage.iteration_corpus(plan,1,subjects))
            with patch.object(a.time,'time',return_value=1003.):result=a.verify(protocol,root)
            self.assertTrue(result['passed']);self.assertFalse(result['complete'])
            self.assertEqual(result['counts']['terminalExecutions'],644)
            self.assertEqual(result['counts']['pairedTails'],0);self.assertIsNone(result['nominee'])
            owner=p.subject(root/'worker-owner.json')
            self.assertEqual(result['artifactPins'][owner['path']],owner['sha256'])

    def test_mac_stop_preserves_own_archives_and_remote_acceptance_without_opening_remote_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'run';plan,initial,dictionary,bindings=plan_fixture()
            protocol,terminal=lifecycle(root,plan,'MAC',reason='coordinator-operational-stop')
            peer=dict(terminal,host='DESKTOP',reason='public-exposure-gate',updatedEpoch=1001.)
            peer_subject=write(root/'peer-terminal.json',peer)
            terminal.update(peerTerminal=peer_subject,originalPeerSubject=dict(peer_subject,path='/absent/Desktop/terminal.json'))
            write(root/'terminal.json',terminal)
            for chunk in p.COLLECTION_CHUNKS['MAC']:
                subject,receipt=missing_chunk(root,plan,initial,dictionary,bindings,chunk)
                tag=f'iteration-1-chunk-{chunk}';remote=f'/absent/Desktop/incoming/{tag}/'
                write(Path(subject['path']).parent/'handoff.json',dict(kind='token-paired-handoff-1',tag=tag,
                    receipt=dict(path=remote+'receipt.json',sha256=subject['sha256']),
                    archive=dict(path=remote+'evidence.tar.gz',sha256=receipt['archiveSha256'])))
            with patch.object(a.time,'time',return_value=1003.):result=a.verify(protocol,root)
            self.assertTrue(result['passed']);self.assertEqual(result['counts']['terminalExecutions'],258)
            self.assertFalse(result['counts']['fullTrainingRecomputed'])
            owner=p.subject(root/'worker-owner.json')
            self.assertEqual(result['artifactPins'][owner['path']],owner['sha256'])

    def test_peer_failure_cannot_be_relabelled_as_operational_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);plan,*_=plan_fixture();_,terminal=lifecycle(root,plan,'MAC',reason='coordinator-operational-stop')
            peer=dict(terminal,host='DESKTOP',status='failed',reason='public-exposure-gate')
            subject=write(root/'peer-terminal.json',peer)
            terminal.update(peerTerminal=subject,originalPeerSubject=dict(subject,path='/absent/terminal.json'))
            with self.assertRaisesRegex(ValueError,'ordinary coordinator stop'):a.peer_stop(root,terminal,a.Pins())

    def test_failure_remains_failed_without_opening_partial_scientific_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'run';plan,*_=plan_fixture();protocol,_=lifecycle(root,plan,'DESKTOP',status='failed')
            (root/'iterations').write_bytes(b'FORBIDDEN')
            with patch.object(a.time,'time',return_value=1003.):result=a.verify(protocol,root)
            self.assertFalse(result['passed']);self.assertIsNone(result['counts'])

    def test_original_audit_deadline_cannot_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'run';plan,*_=plan_fixture();protocol,_=lifecycle(root,plan,'DESKTOP',status='failed')
            with patch.object(a.time,'time',return_value=1903.),self.assertRaisesRegex(ValueError,'audit deadline'):
                a.verify(protocol,root)

    def test_peer_clock_must_be_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);plan,*_=plan_fixture();_,terminal=lifecycle(root,plan,'MAC',reason='coordinator-operational-stop')
            peer=dict(terminal,host='DESKTOP',reason='public-exposure-gate',clock=dict(terminal['clock'],startedEpoch=999.))
            subject=write(root/'peer-terminal.json',peer)
            terminal.update(peerTerminal=subject,originalPeerSubject=dict(subject,path='/absent/terminal.json'))
            with self.assertRaisesRegex(ValueError,'original-clock'):a.peer_stop(root,terminal,a.Pins())


if __name__=='__main__':unittest.main()
