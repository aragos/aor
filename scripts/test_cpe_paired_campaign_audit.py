"""Pure constructed archives only; no numerical library, actor forward or native execution."""
import copy
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import cpe_paired_campaign_audit as audit
import cpe_paired_campaign_plan as p
import cpe_paired_storage as storage
from test_cpe_paired_campaign_plan import fixture
from test_token_paired_campaign_audit import archive,write


def constructed_plan():
    record=fixture();dictionaries={};models={}
    for head in p.HEADS:
        dictionaries[head]=dict(encoderVersion='constructed-'+head,stateFeatureNames=['cash'],actionFeatureNames=['amount'])
        models[head]=p.canonical({'constructedOpaqueModelSubject':head})
        record['heads'][head].update(originalActorSha256=hashlib.sha256(models[head]).hexdigest(),
            dictionarySha256=p.digest(dictionaries[head]),semanticDictionarySha256=p.digest(dictionaries[head]))
    bundle=dict(kind='cpe-paired-bundle-1',heads={head:dict(path=head.lower()+'.json',
        sha256=record['heads'][head]['originalActorSha256'],dictionarySha256=p.digest(dictionaries[head])) for head in p.HEADS})
    record['originalBundleSha256']=p.digest(bundle)
    # This archive-only UNIT does not pretend its invented bytes are the admitted75 initializer.
    return p.Plan(p.canonical(record)),dictionaries,models,p.canonical(bundle)


def missing_chunk(root,plan,dictionaries,models,bundle,head,chunk,*,bad_replay=False):
    directory=root/'iterations/iteration-1/cohorts'/head/'chunks'/f'chunk-{chunk}';directory.mkdir(parents=True)
    files={}
    def put(name,value):
        raw=p.canonical(value);files[name]=raw;return hashlib.sha256(raw).hexdigest()
    for prefix in ('original','current'):
        files[prefix+'/bundle.json']=bundle
        for phase in p.HEADS:files[f'{prefix}/{phase.lower()}.json']=models[phase]
    arena_sha=put('arena.json',{'constructed':True});put('dictionary.json',dictionaries[head])
    source=dict(kind='cpe-paired-collect-1',iteration=1,head=head,globalStart=chunk*64,count=64,
        arena=dict(path='/constructed/arena.json',sha256=arena_sha),
        currentBundle=dict(path='/constructed/bundle.json',sha256=plan.record['originalBundleSha256']),
        originalBundle=dict(path='/constructed/bundle.json',sha256=plan.record['originalBundleSha256']),
        origins=plan.cohort(1,head)['origins'],replayIndices=list(p.COLLECTION_REPLAYS))
    source_sha=put('source.json',source)
    identities={phase:dict(sha256=plan.record['heads'][phase]['originalActorSha256'],
        dictionarySha256=plan.record['heads'][phase]['semanticDictionarySha256']) for phase in p.HEADS}
    native=dict(kind='cpe-paired-collect-1',head=head,iteration=1,range=dict(globalStart=chunk*64,count=64),
        signature=p.digest([head,chunk]),scienceSha256=p.digest(['science',head]),scienceIdentity={'constructedHead':head},
        bundleScopeSha256=plan.record['bundleScopeSha256'],scopeSha256=plan.record['heads'][head]['scopeSha256'],
        ecologySha256=plan.record['ecologySha256'],source=dict(sha256=source_sha,snapshot='source.json'),
        arena=source['arena'],currentBundle=source['currentBundle'],originalBundle=source['originalBundle'],
        currentBundleIdentity=identities,originalBundleIdentity=identities,dictionaries=dictionaries,
        dictionary=dict(path='dictionary.json',sha256=plan.record['heads'][head]['dictionarySha256']),
        dictionarySha256=plan.record['heads'][head]['semanticDictionarySha256'],
        snapshots={name:hashlib.sha256(raw).hexdigest() for name,raw in files.items()},
        origins=plan.cohort(1,head)['origins'],requests=[p.request(i,head) for i in p.indices(chunk)],
        replayIndices=list(p.COLLECTION_REPLAYS),ownedReplayIndices=list(p.replays(chunk)))
    prepared_sha=put('prepared.json',native);rows=[]
    for index in p.indices(chunk):
        origin=f'origin-{index}.json.gz';labels=f'labels-{index}.json';public=f'public-{index}.json'
        files[origin]=b'OPAQUE invalid gzip';files[labels]=b'FORBIDDEN INVALID LABEL JSON'
        row=dict(globalIndex=index,status='MISSING',tailGames=0,anchorRows=0,
            origin=dict(file=origin,sha256=hashlib.sha256(files[origin]).hexdigest()),
            labels=dict(file=labels,sha256=hashlib.sha256(files[labels]).hexdigest()))
        value=dict(kind='cpe-paired-public-1',**{key:native[key] for key in
            ('signature','bundleScopeSha256','scopeSha256','scienceSha256','iteration')},
            request=p.request(index,head),streams=plan.cohort(1,head)['origins'][index],status='MISSING',
            originSha256=row['origin']['sha256'],missingReason='NO_BRANCHING_WINDOW_IN_ASSIGNED_EPOCH',
            observedWindows=3,branchingWindows=0,selectedOrdinal=None,publicRoot=None,anchors=[])
        row['public']=dict(file=public,sha256=put(public,value));rows.append(row)
    done=dict(kind='cpe-paired-finished-1',complete=True,head=head,iteration=1,signature=native['signature'],
        scienceSha256=native['scienceSha256'],range=native['range'],origins=rows,backboneGames=64,tailGames=0,
        scientificTerminalGames=64,readyRoots=0,missingRoots=64,capturedRoots=0)
    finished_sha=put('finished.json',done);replays=[]
    for index in p.replays(chunk):
        name=f'replay-{index}/replay-{index}.json'
        value=dict(kind='cpe-paired-reproduction-1',**{key:native[key] for key in
            ('signature','bundleScopeSha256','scopeSha256','scienceSha256')},globalIndex=index,
            request=p.request(index,head),sourceSha256=source_sha,original={'sha256':rows[index-chunk*64]['origin']['sha256']},
            fullBackboneEqual=True,publicRecordsEqual=not bad_replay,terminalEqual=True,scientificTerminalGames=1,tailGames=0,
            backboneTerminalSha256='a'*64,publicRootSha256='b'*64,anchorsSha256='c'*64)
        replays.append(dict(globalIndex=index,path=name,sha256=put(name,value),
            originalSha256=rows[index-chunk*64]['origin']['sha256'],scientificTerminalGames=1))
    stored=archive(directory/'evidence.tar.gz',files)
    receipt=dict(kind='cpe-paired-chunk-receipt-1',head=head,iteration=1,chunk=chunk,host=p.owner(chunk),
        range=native['range'],**{key:native[key] for key in ('signature','bundleScopeSha256','scopeSha256','scienceSha256',
            'scienceIdentity','ecologySha256','currentBundleIdentity','originalBundleIdentity')},
        sourceSha256=source_sha,preparedSha256=prepared_sha,finishedSha256=finished_sha,
        currentBundleSha256=plan.record['originalBundleSha256'],originalBundleSha256=plan.record['originalBundleSha256'],
        origins=rows,readyRoots=0,missingRoots=64,scientificTerminalGames=64,
        historicalReproductions=len(replays),replays=replays,semanticValidationLabelsDecoded=False,**stored)
    return write(directory/'evidence.tar.gz.json',receipt),receipt


def lifecycle(root,plan,host,status='stopped',reason=None):
    path=root.parent/'protocol.json';path.write_bytes(plan.wire+b'\n');protocol=p.subject(path);now=time.time()
    clock=dict(kind='cpe-paired-clock-1',protocolSha256=protocol['sha256'],startedEpoch=now-10,deadlineEpoch=now-10+64800)
    write(root/'clock.json',clock);write(root/'worker-owner.json',dict(kind='cpe-paired-worker-owner-1',pid=101,processGroup=100))
    terminal=dict(kind='cpe-paired-terminal-1',host=host,status=status,completedIterations=0,
        reason=reason or ('public-exposure-gate' if host=='DESKTOP' else 'coordinator-operational-stop'),updatedEpoch=now,clock=clock)
    if host=='MAC' and status=='stopped':
        peer=dict(terminal,host='DESKTOP',reason='public-exposure-gate',updatedEpoch=now-1)
        subject=write(root/'peer-terminal.json',peer)
        terminal.update(peerTerminal=subject,originalPeerSubject=dict(path='/constructed/Desktop/terminal.json',sha256=subject['sha256']))
    write(root/'terminal.json',terminal);return protocol,terminal


class ArchiveTests(unittest.TestCase):
    def test_missing_shard_hashes_worlds_and_labels_without_decoding_either(self):
        with tempfile.TemporaryDirectory() as temp:
            plan,dictionaries,models,bundle=constructed_plan()
            subject,_=missing_chunk(Path(temp),plan,dictionaries,models,bundle,'CARD',0)
            result=audit.inspected_chunk(plan,subject,audit.Pins(),labels=False)
            self.assertEqual(len(result['worlds']),64);self.assertEqual(result['tails'],0)
            self.assertTrue(all(row['rewards'] is None for row in result['worlds'].values()))

    def test_native_reproduction_equality_is_checked_not_just_hashed(self):
        with tempfile.TemporaryDirectory() as temp:
            plan,dictionaries,models,bundle=constructed_plan()
            subject,_=missing_chunk(Path(temp),plan,dictionaries,models,bundle,'CARD',0,bad_replay=True)
            with self.assertRaisesRegex(ValueError,'independently bind'):
                audit.inspected_chunk(plan,subject,audit.Pins(),labels=False)

    def test_archive_public_digest_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            plan,dictionaries,models,bundle=constructed_plan()
            subject,receipt=missing_chunk(Path(temp),plan,dictionaries,models,bundle,'PURCHASE',1)
            receipt['origins'][0]['public']['sha256']='f'*64;subject=write(subject['path'],receipt)
            with self.assertRaisesRegex(ValueError,'native source/preparation/completion'):
                audit.inspected_chunk(plan,subject,audit.Pins(),labels=False)

    def test_compaction_does_not_retain_anchor_feature_trees(self):
        with tempfile.TemporaryDirectory() as temp:
            plan,dictionaries,models,bundle=constructed_plan()
            subject,_=missing_chunk(Path(temp),plan,dictionaries,models,bundle,'EXPANSION',1)
            result=audit.inspected_chunk(plan,subject,audit.Pins(),labels=False)
            self.assertEqual(set(result['worlds'][64]),{'publicSha256','status','anchorSha256','anchorActive','rewards','validationLabel'})


class IdentityTests(unittest.TestCase):
    def test_exact_training_identity_uses_world_order_and_origin_group_hashes(self):
        anchors=[dict(anchorSha256=p.digest(['anchor',i]),anchorActive=i==0) for i in range(512)]
        worlds={i:dict(publicSha256=p.digest(['public',i]),status='READY' if i<256 else 'MISSING',
            rewards=[[.2,.4],[.4,.6]] if i<256 else None) for i in range(640)}
        value=dict(record=dict(iteration=2,ready={'VALIDATION':64}),worlds=worlds);context={'constructed':True}
        actual,anchor_sha,active=audit.training_identity(value,context,anchors)
        expected=p.digest(dict(context=context,roots=[worlds[i]['publicSha256'] for i in range(512)],
            labels=[worlds[i]['rewards'] for i in range(512)],anchors=p.digest([row['anchorSha256'] for row in anchors]),
            validationReadyCount=64))
        self.assertEqual(actual,expected);self.assertEqual(sum(active),256)
        changed=copy.deepcopy(value);changed['worlds'][0]['rewards'][1][0]=.1
        self.assertNotEqual(audit.training_identity(changed,context,anchors)[0],actual)

    def test_iteration1_empty_anchor_is_not_replaced_by_requested_root_weight(self):
        anchors=[dict(anchorSha256=p.digest(i),anchorActive=i==2) for i in range(512)]
        value=dict(record=dict(iteration=1,ready={'VALIDATION':64}),worlds={i:dict(publicSha256=p.digest(i),
            status='READY',rewards=[[.5,.5]]) for i in range(512)})
        self.assertEqual(sum(audit.training_identity(value,{},anchors)[2]),1)

    def test_actual_import_closure_contains_no_numeric_library(self):
        result=subprocess.run([sys.executable,'-E','-s','-B','-c',
            'import sys;import cpe_paired_campaign_audit;assert not any(n=="torch" or n.startswith("torch.") or n=="numpy" or n.startswith("numpy.") for n in sys.modules)'],
            cwd=Path(__file__).parent,capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)


class PublicationTests(unittest.TestCase):
    def make(self,root):
        content=root/'portable';content.mkdir()
        write(content/'protocol.json',{'constructed':True})
        write(content/'original/actor.json',{'opaqueConstructed':True})
        write(content/'iterations/iteration-1/admission.json',{'constructed':True})
        files={str(path.relative_to(content)):path.read_bytes() for path in content.rglob('*') if path.is_file()}
        directory=root/'publications/iteration-2';directory.mkdir(parents=True)
        value=dict(kind='cpe-paired-publication-1',stage='iteration-2',identity={'protocolSha256':'a'*64},
            **archive(directory/'publication.tar.gz',files))
        write(directory/'publication.json',value)
        write(content/'iterations/iteration-2/later.json',{'later':True})
        return directory

    def test_original_publication_stays_bound_after_second_iteration_is_added(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);directory=self.make(root)
            result=audit.publication(directory,'iteration-2','a'*64,audit.Pins(),download=False)
            self.assertEqual(len(result['files']),3)

    def test_transmitted_bytes_must_equal_original_portable_component(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);directory=self.make(root)
            (root/'portable/original/actor.json').write_text('changed')
            with self.assertRaisesRegex(ValueError,'Portable member size|evidence subject changed'):
                audit.publication(directory,'iteration-2','a'*64,audit.Pins(),download=False)

    def test_incidental_finder_files_do_not_change_published_subject_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);directory=self.make(root)
            (root/'portable/.DS_Store').write_bytes(b'Finder metadata')
            (root/'portable/original/.DS_Store').write_bytes(b'Finder metadata')
            result=audit.publication(directory,'iteration-2','a'*64,audit.Pins(),download=False)
            self.assertEqual(len(result['files']),3)

    def test_finder_named_symlink_is_not_exempted_from_subject_checks(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);directory=self.make(root)
            (root/'portable/.DS_Store').symlink_to(root/'portable/protocol.json')
            with self.assertRaisesRegex(ValueError,'portable closure'):
                audit.publication(directory,'iteration-2','a'*64,audit.Pins(),download=False)


class StoppedTransactionTests(unittest.TestCase):
    def fixture(self,root):
        identity=dict(kind='cpe-paired-bundle-identity-1',recipe=audit.numeric.RECIPE,
            sourceSha256=audit.numeric.source_hashes())
        write(root/'identity.json',identity)
        write(root/'stopped.json',dict(kind='cpe-paired-bundle-stop-1',complete=False,operationalPassed=False,
            reason='no-accepted-actor-change',outputBundleSha256=None,identity=identity,head='CARD',completedHeads=['CARD']))
        child=dict(operationalPassed=False,artifacts={})
        write(root/'heads/CARD/finished.json',child)
        return child

    def test_original_unchanged_head_prefix_is_retained_without_bundle(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);child=self.fixture(root)
            (root/'heads/.DS_Store').write_bytes(b'Finder metadata')
            with patch.object(audit.numeric,'child_completed',return_value=child) as intrinsic:
                stopped,children=audit.pin_transaction(root,audit.Pins(),complete=False)
            self.assertEqual(stopped['head'],'CARD');self.assertEqual(set(children),{'CARD'})
            intrinsic.assert_called_once_with(root/'heads/CARD')

    def test_later_head_cannot_follow_an_operational_stop(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);child=self.fixture(root);(root/'heads/PURCHASE').mkdir()
            with patch.object(audit.numeric,'child_completed',return_value=child) as intrinsic:
                with self.assertRaisesRegex(ValueError,'contiguous attempted head prefix'):
                    audit.pin_transaction(root,audit.Pins(),complete=False)
            intrinsic.assert_not_called()


class TerminalTests(unittest.TestCase):
    def test_all30_global_shards_form_honest_exposure_stop_without_label_decode(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'run';plan,dictionaries,models,bundle=constructed_plan();subjects={}
            for head in p.HEADS:
                chunks=[missing_chunk(root,plan,dictionaries,models,bundle,head,chunk)[0] for chunk in range(10)]
                record=storage.cohort_corpus(plan,1,head,chunks)
                subjects[head]=write(root/'iterations/iteration-1/cohorts'/head/'corpus.json',record)
            write(root/'iterations/iteration-1/corpus.json',storage.iteration_corpus(plan,1,subjects))
            protocol,_=lifecycle(root,plan,'DESKTOP')
            with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
                result=audit.verify(protocol,root)
            self.assertTrue(result['passed']);self.assertFalse(result['complete']);self.assertIsNone(result['nominee'])
            self.assertEqual(result['counts']['terminalExecutions'],1932)
            self.assertFalse(result['counts']['fullTrainingRecomputed'])
            self.assertEqual(result['counts']['trainingCohortsRecomputed'],0)
            self.assertEqual(result['execution'],dict(newGames=0,modelForwards=0,optimizerSteps=0))

    def test_mac12_shards_require_exact_peer_stop_and_each_delivery(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'run';plan,dictionaries,models,bundle=constructed_plan()
            for head in p.HEADS:
                for chunk in p.COLLECTION_CHUNKS['MAC']:
                    subject,receipt=missing_chunk(root,plan,dictionaries,models,bundle,head,chunk)
                    tag=audit.handoff.tag(1,chunk,head=head)
                    write(Path(subject['path']).parent/'handoff.json',dict(kind='cpe-paired-handoff-1',tag=tag,
                        receipt=dict(path=f'/constructed/{tag}/receipt.json',sha256=subject['sha256']),
                        archive=dict(path=f'/constructed/{tag}/evidence.tar.gz',sha256=receipt['archiveSha256'])))
            protocol,_=lifecycle(root,plan,'MAC')
            for directory in (root/'iterations',root/'iterations/iteration-1/cohorts',
                    root/'iterations/iteration-1/cohorts/CARD/chunks'):
                (directory/'.DS_Store').write_bytes(b'Finder metadata')
            with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
                result=audit.verify(protocol,root)
            self.assertTrue(result['passed']);self.assertFalse(result['counts']['fullTrainingRecomputed'])
            self.assertEqual(result['counts']['terminalExecutions'],774)

    def test_failed_science_is_failed_audit_not_honest_stop(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'run';plan,*_=constructed_plan();protocol,_=lifecycle(root,plan,'DESKTOP','failed')
            with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
                result=audit.verify(protocol,root)
            self.assertFalse(result['passed']);self.assertEqual(result['status'],'failed');self.assertIsNone(result['counts'])

    def test_real_plan_rejects_constructed_initializer_without_test_only_bypass(self):
        plan,*_=constructed_plan()
        with self.assertRaisesRegex(ValueError,'untrained75'):p.Plan.from_record(plan.record)

    def test_original_audit_ceiling_is_not_renewed(self):
        clock=p.Clock(1000.,65800.)
        self.assertEqual(clock.audit_deadline(2000.),2900.)
        self.assertEqual(clock.audit_deadline(66000.),66700.)


if __name__=='__main__':unittest.main()
