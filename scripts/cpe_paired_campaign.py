"""The fixed two-iteration Desktop coordinator and outbound-only Mac worker.

This is one finite job, not a scheduler. Native collection/reproduction and the numerical owner
retain their own invariants. Every subprocess shares the original clock and controller group.
"""
import argparse
import os
from pathlib import Path
import time

import expansion_on_policy_loop as native
import cpe_paired_process as execution
import cpe_paired_admission as admission
import cpe_paired_campaign_plan as p
import cpe_paired_handoff as handoff
import cpe_paired_storage as storage


def check_profile(protocol_path, profile_path):
    plan=p.Plan.from_file(protocol_path);profile=p.read(profile_path)
    p.require(profile['kind']=='cpe-paired-host-1' and profile['host'] in p.HOSTS and
        profile['protocolSha256']==p.file_sha(protocol_path), 'Host package must bind the exact shared scientific protocol.')
    expected=plan.record['hosts'][profile['host']]
    p.require(all(profile[key]==value for key,value in expected.items()) and
        os.cpu_count()==profile['machineCpus'] and
        os.environ.get('AOR_TRAINING_MACHINE_CPUS')==str(profile['machineCpus']),
        'Actual host/native CPU and resource declarations must match the frozen profile.')
    p.require(profile['files'] and all(p.file_sha(path)==digest for path,digest in profile['files'].items()),
        'Every executing source/runtime/environment/model subject must remain exact.')
    p.require(profile['files'].get('/bin/ps')==p.file_sha('/bin/ps'),
        'The exact read-only worker-liveness executable must be a frozen host input.')
    for key in ('arena','originalBundle'):p.checked_subject(profile[key])
    for item in p.head_record(profile['dictionaries']).values():p.checked_subject(item)
    p.require(profile['originalBundle']['sha256']==plan.record['originalBundleSha256'] and
        all(profile['dictionaries'][head]['sha256']==plan.record['heads'][head]['dictionarySha256'] for head in p.HEADS), 'Host actor/dictionary subjects differ.')
    return plan,profile


def verify_replay(root,index,prepared,origin):
    path=Path(root)/f'replay-{index}'/f'replay-{index}.json';value=p.read(path)
    p.require(value['kind']=='cpe-paired-reproduction-1' and value['signature']==prepared['signature'] and
        value['bundleScopeSha256']==prepared['bundleScopeSha256'] and value['scopeSha256']==prepared['scopeSha256'] and
        value['scienceSha256']==prepared['scienceSha256'] and
        value['globalIndex']==index and value['request']==p.request(index,prepared['head']) and
        value['sourceSha256']==prepared['source']['sha256'] and value['original']['sha256']==origin['origin']['sha256']
        and value['fullBackboneEqual'] is True and value['publicRecordsEqual'] is True and
        value['terminalEqual'] is True and value['scientificTerminalGames']==1 and value['tailGames']==0,
        'A prescribed whole-backbone reproduction must match its exact source and complete public/canonical history.')
    for name in ('backboneTerminalSha256','publicRootSha256','anchorsSha256'):p.sha(value[name])
    return dict(globalIndex=index,path=str(path.relative_to(root)),sha256=p.file_sha(path),
        originalSha256=origin['origin']['sha256'],scientificTerminalGames=1)


def collect(plan,profile,root,iteration,head,chunk,current):
    p.require(p.owner(chunk)==profile['host'],'A host can execute only its original fixed chunk.')
    directory=Path(root)/'iterations'/f'iteration-{iteration}'/'cohorts'/head/'chunks'/f'chunk-{chunk}'
    p.require(not directory.exists(),'A scientific chunk permits one original attempt only.')
    directory.mkdir(parents=True)
    source=directory/'source.json'
    storage.publish_json(source,plan.collection_source(iteration,head,chunk,profile['host'],profile['arena'],current,profile['originalBundle']))
    output=directory/'native'
    execution.run_child(profile,native.native_command(profile,'cpe-paired-collect',source,output,
        ('--workers',str(profile['workers']))),directory/'collection.log')
    for index in p.replays(chunk):
        execution.run_child(profile,native.native_command(profile,'cpe-paired-reproduce',source,
            output/f'replay-{index}',('--original',str(output),'--index',str(index))),directory/f'replay-{index}.log')
    receipt=storage.verify_collection(plan,output,source,head,chunk,iteration,current['sha256'],verify_replay)
    archive=directory/'evidence.tar.gz'
    storage.archive_collection(output,archive,receipt)
    return archive,archive.with_name(archive.name+'.json')


def wait_chunks(root,clock,iteration=None,screen=False,*,head=None,now=time.time,sleep=time.sleep):
    """Wait only for this fixed stage's Mac receipts; absence never authorizes recomputation."""
    root=Path(root);chunks=p.SCREEN_CHUNKS['MAC'] if screen else p.COLLECTION_CHUNKS['MAC']
    while True:
        clock.remaining(now())
        p.require(handoff.terminal(root) is None,'The original coordinator has already ended.')
        p.require(not (root/'peer-failed.json').exists(),'The Mac worker failed; no native shard may be retried.')
        paths=[root/'incoming'/handoff.tag(iteration,chunk,screen,head=head)/'accepted.json' for chunk in chunks]
        if all(path.exists() for path in paths):
            receipts=[]
            for path,chunk in zip(paths,chunks):
                row=p.read(path);p.require(row['kind']=='cpe-paired-handoff-1' and
                    row['tag']==handoff.tag(iteration,chunk,screen,head=head), 'Incoming fixed stage identity changed.')
                p.checked_subject(row['archive']);p.checked_subject(row['receipt']);receipts.append(row['receipt'])
            return receipts
        sleep(min(10,clock.remaining(now())))


def portable_files(root):
    root=Path(root)
    return {name:p.subject(root/name) for name in storage.inventory(root)}


def publish_next(root,stage,portable,protocol,bundle_sha,bundle_scope_sha):
    return handoff.bundle(Path(root)/'publications'/stage,stage,portable_files(portable),
        dict(protocolSha256=p.file_sha(protocol),bundleSha256=bundle_sha,bundleScopeSha256=bundle_scope_sha))


def numeric_command(profile,protocol,profile_path,iteration_root,corpus,original_corpus,current,previous):
    command=[profile['python']['executable'],'-E','-s','-B',str(Path(__file__).with_name('cpe_paired_numeric_stage.py')),
        'update','--protocol',str(protocol),'--profile',str(profile_path),'--corpus',str(corpus),
        '--original-corpus',str(original_corpus),'--current',str(current),
        '--output',str(iteration_root/'fit'),'--seal',str(iteration_root/'validation.json')]
    if previous is not None:command+=['--previous',str(previous)]
    return command


def desktop(plan,profile,protocol_path,profile_path,clock,root):
    import cpe_paired_screen as screen
    root=Path(root);portable=root/'portable';current=profile['originalBundle'];initial_corpus=None
    for iteration in (1,2):
        clock.remaining(time.time())
        cohorts={}
        for head in p.HEADS:
            receipts=[]
            for chunk in p.COLLECTION_CHUNKS['DESKTOP']:
                _,receipt=collect(plan,profile,root,iteration,head,chunk,current);receipts.append(p.subject(receipt))
            receipts+=wait_chunks(root,clock,iteration,head=head)
            cohort=storage.cohort_corpus(plan,iteration,head,receipts)
            cohort_path=root/'iterations'/f'iteration-{iteration}'/'cohorts'/head/'corpus.json'
            cohorts[head]=storage.publish_json(cohort_path,cohort)
        corpus=storage.iteration_corpus(plan,iteration,cohorts)
        directory=root/'iterations'/f'iteration-{iteration}';corpus_path=directory/'corpus.json'
        storage.publish_json(corpus_path,corpus)
        if not corpus['exposurePassed']:
            return dict(status='stopped',reason='public-exposure-gate',completedIterations=iteration-1)
        if initial_corpus is None:initial_corpus=corpus_path
        previous=None if iteration==1 else root/'iterations/iteration-1/fit'
        execution.run_child(profile,numeric_command(profile,protocol_path,profile_path,directory,
            corpus_path,initial_corpus,Path(current['path']),previous),directory/'numeric.log')
        done=p.read(directory/'numeric-complete.json')
        p.require(done['iteration']==iteration and done['complete'] is True and
            done['protocolSha256']==p.file_sha(protocol_path), 'The one numerical child must complete its exact requested update.')
        if not done['operationalPassed']:
            return dict(status='stopped',reason='no-accepted-actor-change',completedIterations=iteration-1)
        admission.publish_iteration(p.subject(protocol_path),profile['originalBundle'],p.subject(corpus_path),
            directory/'fit',done['validationProbabilitySeal'],portable)
        current=p.subject(directory/'fit/bundle.json')
        if iteration==1:publish_next(root,'iteration-2',portable,protocol_path,current['sha256'],corpus['bundleScopeSha256'])
    final=admission.publish_final(portable)
    # No validation direction can skip/choose a screen: both diagnostics and the sole final stage are fixed.
    command=[profile['python']['executable'],'-E','-s','-B',str(Path(__file__).with_name('cpe_paired_numeric_stage.py')),
        'effects','--protocol',str(protocol_path),'--profile',str(profile_path),'--output',str(root/'iterations')]
    execution.run_child(profile,command,root/'validation-effects.log')
    historical=screen.historical(plan,profile,root,final)
    admission.copy_subject(historical,portable/'historical-admission.json')
    publish_next(root,'screen',portable,protocol_path,current['sha256'],corpus['bundleScopeSha256'])
    local=[screen.collect(plan,profile,root,chunk,final,historical) for chunk in p.SCREEN_CHUNKS['DESKTOP']]
    remote=wait_chunks(root,clock,screen=True)
    report=screen.complete(plan,profile,root,local+remote,
        p.subject(root/'iterations/validation-effect.json'))
    return dict(status='completed',reason='two-fixed-iterations-and-screen',completedIterations=2,
        finalAdmission=final,report=report)


def mac(plan,profile,protocol_path,clock,root):
    import cpe_paired_screen as screen
    root=Path(root);peer=handoff.Outbound(profile['peer'],clock,root);current=profile['originalBundle']
    peer.await_active(p.file_sha(protocol_path))
    expected_scope=None
    for iteration in (1,2):
        if iteration==2:
            ready=peer.await_stage('iteration-2')
            folder=peer.fetch('iteration-2',ready,root/'downloads/iteration-2')
            row=admission.verify_iteration(folder/'iterations/iteration-1/admission.json')
            p.require(row['protocolSha256']==p.file_sha(protocol_path) and row['bundleScopeSha256']==expected_scope and
                ready['publication']['identity']==dict(protocolSha256=p.file_sha(protocol_path),
                    bundleSha256=row['outputBundle']['sha256'],bundleScopeSha256=row['bundleScopeSha256']),
                'Mac may continue only the exact completed Desktop first actor in the original shared scope.')
            current=p.subject(admission.resolve(row['outputBundle'],folder/'iterations/iteration-1/admission.json'))
        for head in p.HEADS:
            for chunk in p.COLLECTION_CHUNKS['MAC']:
                archive,receipt=collect(plan,profile,root,iteration,head,chunk,current)
                value=p.read(receipt)
                if expected_scope is None:expected_scope=value['bundleScopeSha256']
                p.require(value['bundleScopeSha256']==expected_scope,'The fixed bundle scope cannot change across hosts or heads.')
                accepted=peer.push(archive,receipt,iteration,chunk,head=head)
                storage.publish_json(archive.parent/'handoff.json',accepted)
    ready=peer.await_stage('screen');folder=peer.fetch('screen',ready,root/'downloads/screen')
    final=p.subject(folder/'final-admission.json');value=p.read(final['path'])
    p.require(value['bundleScopeSha256']==expected_scope and
        ready['publication']['identity']==dict(protocolSha256=p.file_sha(protocol_path),
            bundleSha256=value['finalBundle']['sha256'],bundleScopeSha256=expected_scope),
        'Fresh Mac screens require the exact two-update shared scope and historical admission.')
    for chunk in p.SCREEN_CHUNKS['MAC']:
        receipt=screen.collect(plan,profile,root,chunk,final,p.subject(folder/'historical-admission.json'))
        path=p.checked_subject(receipt);row=p.read(path)
        accepted=peer.push(path.with_name(row['archive']),path,None,chunk,True)
        storage.publish_json(path.parent/'handoff.json',accepted)
    return dict(status='completed',reason='all-fixed-mac-shards-delivered',completedIterations=2)


def run(protocol_path,profile_path,clock_path,root):
    plan,profile=check_profile(protocol_path,profile_path);launch=p.read(clock_path)
    p.require(launch['kind']=='cpe-paired-clock-1' and launch['protocolSha256']==p.file_sha(protocol_path),
        'Both hosts require the same explicitly reviewed original clock subject.')
    clock=p.Clock(launch['startedEpoch'],launch['deadlineEpoch']);clock.remaining(time.time())
    p.require(float(os.environ['AOR_CAMPAIGN_DEADLINE_EPOCH'])<=clock.deadline and
        float(os.environ['AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'])<=clock.deadline,
        'Neither local controller may renew or extend the shared original science clock.')
    root=Path(root);p.require(not root.exists(),'The finite host worker runs exactly once, with no resume.')
    root.mkdir(parents=True)
    storage.publish_json(root/'worker-owner.json',dict(kind='cpe-paired-worker-owner-1',
        pid=os.getpid(),processGroup=os.getpgrp()))
    storage.publish_json(root/'clock.json',launch)
    profile=dict(profile,_root=root,_clock=clock)
    if profile['host']=='MAC':profile['_peer']=handoff.Outbound(profile['peer'],clock,root)
    try:
        result=(desktop(plan,profile,protocol_path,profile_path,clock,root) if profile['host']=='DESKTOP'
            else mac(plan,profile,protocol_path,clock,root))
    except handoff.PeerStopped as stopped:
        try:
            peer=stopped.response['terminal'];peer_subject=stopped.response['terminalSubject']
            p.require(profile['host']=='MAC' and peer['clock']==launch,
                'An operational peer stop must belong to this exact original campaign clock.')
            copied=storage.publish_json(root/'peer-terminal.json',peer)
            p.require(copied['sha256']==peer_subject['sha256'],'The complete peer terminal bytes must remain exact.')
            result=dict(status='stopped',reason='coordinator-operational-stop',
                completedIterations=peer['completedIterations'],peerTerminal=copied,originalPeerSubject=peer_subject)
        except BaseException as failure:
            failed(profile,root,launch,failure)
            raise
    except BaseException as failure:
        failed(profile,root,launch,failure)
        raise
    storage.publish_json(root/'terminal.json',dict(kind='cpe-paired-terminal-1',host=profile['host'],
        updatedEpoch=time.time(),clock=launch,**result))
    return result


def failed(profile,root,launch,failure):
    storage.publish_json(Path(root)/'terminal.json',dict(kind='cpe-paired-terminal-1',host=profile['host'],
        status='failed',failure=str(failure),updatedEpoch=time.time(),clock=launch))
    if profile['host']=='MAC':
        try:profile['_peer'].remote('fail','MAC')
        except Exception:pass  # The independent original deadline still bounds an unreachable peer.


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('protocol','profile','clock','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();run(args.protocol,args.profile,args.clock,args.output)
