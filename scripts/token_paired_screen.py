"""Exact two-history admission, bounded128-world archives, and the sole complete final screen."""
import gzip
import hashlib
import json
import math
from pathlib import Path
import tarfile

import automata_experiments as exp
import automata_policy_search as arena_math
import expansion_on_policy_loop as native
import expansion_training_evidence as evidence
import token_paired_admission as admission
import token_paired_campaign_plan as p
import token_paired_process as execution
import token_paired_storage as storage

KIND='token-paired-screen-1'


def arena(plan,profile,root):
    value=p.read(p.checked_subject(profile['arena']))
    value=dict(value,pairsPerConfiguration=1024,seed=plan.record['screenBank']['nativeOffset'])
    path=Path(root)/'screen-arena.json'
    if path.exists():p.require(p.read(path)==value,'The screen arena cannot change between chunks.')
    else:storage.publish_json(path,value)
    return p.subject(path)


def historical(plan,profile,root,final):
    p.require(profile['host']=='DESKTOP' and len(profile['historicalControls'])==2,
        'Only Desktop executes the two original historical controls, once globally.')
    root=Path(root);source=root/'historical-source.json';output=root/'historical'
    storage.publish_json(source,dict(kind='token-paired-historical-source-1',arena=arena(plan,profile,root),
        admission=final,controls=profile['historicalControls']))
    execution.run_child(profile,native.native_command(profile,'token-paired-historical',source,output),root/'historical.log')
    gate=p.read(output/'historical-admission.json');final_record=p.read(p.checked_subject(final))
    verify_historical(gate,profile['protocolSha256'],final_record['scopeSha256'],plan.record['ecologySha256'])
    p.require(gate['sourceSha256']==p.file_sha(source),'Historical gate must retain its exact declared control source.')
    for expected,row in zip(profile['historicalControls'],gate['records']):
        p.require(row['index']==expected['index'] and row['side']==expected['side'] and
            row['originalSourceSha256']==expected['source']['sha256'] and
            row['originalPairSha256']==expected['pair']['sha256'] and
            row['originalReplaySha256']==expected['replay']['sha256'] and
            p.file_sha(output/row['replayFile'])==row['replaySha256'] and row['canonicalVerified'] is True,
            'Both historical controls must reproduce their exact predeclared original subjects.')
    return p.subject(output/'historical-admission.json')


def verify_historical(gate,protocol_sha,scope,ecology):
    p.require(gate['kind']=='token-paired-historical-admission-1' and gate['complete'] is True and
        gate['controls']==2 and gate['zeroFailures'] is True and gate['protocolSha256']==protocol_sha and
        gate['scopeSha256']==scope and gate['ecologySha256']==ecology and
        gate['scientificTerminalExecutions']==2 and len(gate['records'])==2,
        'Fresh screens require both passing Desktop controls under the exact fixed scope.')


def validate_world(plan,world,index,science):
    p.require(world['kind']==KIND and world['scienceSha256']==science and set(world['roles'])==set(p.ROLES),
        'Each world requires all three exact frozen role results.')
    for other in ('RAW_T','INITIAL'):
        pair=dict(world,candidate=world['roles']['FINAL'],incumbent=world['roles'][other])
        arena_math.validate_pair(pair,index,plan.record['screenBank']['seeds'][index],1)
    for game in world['roles'].values():
        p.require(all(0<=value<=1 for name in ('wins','rewards','ranks') for value in game[name]),
            'Every role metric must retain the original normalized terminal units.')
        for seat in range(4):
            evidence.terminal_reward(dict(controlledSeat=seat,terminal=dict(scores=game['scores'],
                winShare=game['wins'][seat],rankReward=game['ranks'][seat],reward=game['rewards'][seat],
                gameDecisions=game['decisions'])))


def canonical(value):
    return p.digest({key:value[key] for key in ('header','entries')})


def chunk_evidence(plan,chunk,files,manifest,finished,worlds,replays,canonicals,final_sha,historical_sha):
    """Pure compact verification, used both immediately and by the terminal archive auditor."""
    indices=list(p.indices(chunk,True));owned=p.replays(chunk,True)
    p.require(finished['kind']==manifest['kind']==KIND and finished['complete'] is True and
        finished['stage']=='chunk' and finished['globalStart']==manifest['globalStart']==chunk*128 and
        finished['count']==manifest['count']==128 and finished['signature']==manifest['signature'] and
        finished['scienceSha256']==manifest['scienceSha256'] and
        finished['scienceIdentity']==manifest['scienceIdentity'] and
        finished['finalAdmissionSha256']==manifest['admission']['sha256']==final_sha and
        finished['historicalAdmissionSha256']==manifest['historicalAdmission']['sha256']==historical_sha and
        finished['files']=={name:item['sha256'] for name,item in files.items() if name!='finished.json'} and
        finished['reproductions']==len(owned)*3 and finished['scientificTerminalExecutions']==384+len(owned)*3,
        'A fixed native chunk must retain its complete source, inventory and terminal counts.')
    identity=finished['scienceIdentity']
    p.require(identity['seeds']==plan.record['screenBank']['seeds'] and identity['roles']==list(p.ROLES) and
        identity['replayIndices']==list(p.SCREEN_REPLAYS) and
        identity['originalActorSha256']==plan.record['originalActorSha256'] and
        identity['ecologySha256']==plan.record['ecologySha256'], 'Screen science cannot vary by host or chunk.')
    p.require(sorted(worlds)==indices and [row['index'] for row in finished['worlds']]==indices and
        {name for name in files if name.startswith('world-')}=={f'world-{i}.json.gz' for i in indices},
        'Each original world must occur once in its owner chunk.')
    for index,row in zip(indices,finished['worlds']):
        p.require(row['file']==f'world-{index}.json.gz' and files[row['file']]['sha256']==row['sha256'] and
            worlds[index]['signature']==manifest['signature'], 'Compact worlds must bind original raw native members.')
        validate_world(plan,worlds[index],index,manifest['scienceSha256'])
    expected={(index,role) for index in owned for role in p.ROLES}
    p.require(set(replays)==set(canonicals)==expected and manifest['ownedReplayIndices']==list(owned),
        'Only prescribed global role reproductions may occur in each owner chunk.')
    for (index,role),replay in replays.items():
        world=worlds[index]
        p.require(replay['replayVerified'] is True and replay['index']==index and replay['seed']==world['seed'] and
            replay['seat']==world['seat'] and replay['configuration']==world['configuration'] and
            replay['opponentIndices']==world['opponentIndices'] and
            replay['side']==('CANDIDATE' if role=='FINAL' else 'INCUMBENT') and
            arena_math.same_game(replay['game'],world['roles'][role]) and
            canonical(replay)==canonicals[index,role] and
            (replay.get('tokenActor') is None if role=='RAW_T' else replay.get('tokenActor') is not None),
            'Every role replay must reproduce its exact original world, actor scope and complete canonical sequence.')


def parse_member(name,raw,worlds,replays,canonicals,metadata):
    value=json.loads(gzip.decompress(raw)) if name.endswith('.json.gz') else json.loads(raw)
    parse_value(name,value,worlds,replays,canonicals,metadata)


def parse_value(name,value,worlds,replays,canonicals,metadata):
    if name.startswith('world-') and name.endswith('.json.gz'):
        worlds[value['index']]=value
    elif name.startswith(('canonical-','replay-')) and '/' not in name:
        kind,index,role=name.removesuffix('.json').split('-',2)
        if kind=='canonical':canonicals[int(index),role]=canonical(value)
        else:replays[int(index),role]=value
    elif name in ('manifest.json','finished.json'):metadata[name]=value


def collect(plan,profile,root,chunk,final,historical_gate):
    p.require(p.owner(chunk,True)==profile['host'],'A screen chunk cannot migrate between hosts.')
    root=Path(root);directory=root/'screen/chunks'/f'chunk-{chunk}'
    directory.mkdir(parents=True,exist_ok=False)
    source=directory/'source.json'
    storage.publish_json(source,dict(kind=KIND,arena=arena(plan,profile,root),admission=final,
        historicalAdmission=historical_gate,globalStart=chunk*128,count=128))
    output=directory/'native'
    execution.run_child(profile,native.native_command(profile,'token-paired-screen-chunk',source,output,
        ('--workers',str(profile['workers']))),directory/'screen.log')
    files=storage.inventory(output);worlds={};replays={};canonicals={};metadata={}
    for name in files:
        if name.startswith(('world-','canonical-','replay-')) or name in ('manifest.json','finished.json'):
            raw=(output/name).read_bytes()
            p.require(hashlib.sha256(raw).hexdigest()==files[name]['sha256'],'Native member changed during admission.')
            parse_member(name,raw,worlds,replays,canonicals,metadata)
    manifest=metadata['manifest.json'];finished=metadata['finished.json']
    p.require(manifest['source']['sha256']==p.file_sha(source), 'Native screen must bind its exact original source.')
    chunk_evidence(plan,chunk,files,manifest,finished,worlds,replays,canonicals,final['sha256'],historical_gate['sha256'])
    receipt=dict(kind='token-paired-screen-chunk-receipt-1',chunk=chunk,host=profile['host'],
        files=files,signature=manifest['signature'],scienceSha256=manifest['scienceSha256'],
        finalAdmissionSha256=final['sha256'],historicalAdmissionSha256=historical_gate['sha256'],
        worlds=128,games=384,reproductions=len(p.replays(chunk,True))*3)
    archive=directory/'evidence.tar.gz';storage.archive_collection(output,archive,receipt)
    return p.subject(archive.with_name(archive.name+'.json'))


def archived_chunk(plan,receipt_path,*,stream_archive=None):
    receipt=p.read(receipt_path);chunk=receipt['chunk'];archive=Path(receipt_path).with_name(receipt['archive'])
    p.require(receipt['kind']=='token-paired-screen-chunk-receipt-1' and receipt['host']==p.owner(chunk,True),
        'Screen archive receipt has the wrong fixed owner.')
    worlds={};replays={};canonicals={};metadata={}
    if stream_archive is None:
        storage.verify_archive(archive,receipt)
        with tarfile.open(archive,'r:gz') as source:
            for member in source:
                name=member.name
                if name.startswith(('world-','canonical-','replay-')) or name in ('manifest.json','finished.json'):
                    with source.extractfile(member) as content:raw=content.read()
                    p.require(hashlib.sha256(raw).hexdigest()==receipt['files'][name]['sha256'],
                        'Screen raw member changed at the semantic read boundary.')
                    parse_member(name,raw,worlds,replays,canonicals,metadata)
    else:
        names={name for name in receipt['files'] if name.startswith(('world-','canonical-','replay-'))
            or name in ('manifest.json','finished.json')}
        for name,value in stream_archive(archive,receipt,names).items():
            parse_value(name,value,worlds,replays,canonicals,metadata)
    chunk_evidence(plan,chunk,receipt['files'],metadata['manifest.json'],metadata['finished.json'],
        worlds,replays,canonicals,receipt['finalAdmissionSha256'],receipt['historicalAdmissionSha256'])
    return receipt,metadata['finished.json'],[worlds[i] for i in p.indices(chunk,True)]


def verify_report(worlds,report):
    p.require([row['index'] for row in worlds]==list(range(1024)) and
        report['kind']=='token-paired-screen-report-1' and report['worlds']==1024 and
        report['intervalMultiplier']==1.96,
        'Effects require the sole complete original screen, never a partial chunk.')
    for field,first,second in (('finalMinusRawT','FINAL','RAW_T'),
        ('finalMinusInitializer','FINAL','INITIAL'),('initializerMinusRawT','INITIAL','RAW_T')):
        for metric in ('wins','rewards','ranks','chaos'):
            def value(world,role):
                game=world['roles'][role];seat=world['seat']
                return int(game['scores'][seat]['total'] is None) if metric=='chaos' else game[metric][seat]
            expected=exp.interval([value(world,first)-value(world,second) for world in worlds])
            p.require(all(math.isclose(report[field][metric][name],number,rel_tol=1e-10,abs_tol=1e-12)
                for name,number in expected.items()), 'Native whole-world interval differs from its original raw results.')
    passed=(report['finalMinusRawT']['wins']['lower95']>0 and report['finalMinusRawT']['rewards']['lower95']>0
        and report['finalMinusInitializer']['rewards']['lower95']>0)
    p.require(report['developmentGatePassed']==passed and report['qualification'] is False and report['activated'] is False,
        'Screen gates must retain all three fixed tests without qualification or activation.')
    return passed


def complete(plan,profile,root,receipts,validation_effect):
    p.require(profile['host']=='DESKTOP' and len(receipts)==8,'Only Desktop reports all eight original chunks.')
    root=Path(root);directory=root/'screen-report-input';directory.mkdir(parents=True,exist_ok=False)
    ordered=sorted(receipts,key=lambda item:p.read(p.checked_subject(item))['chunk'])
    worlds=[];subjects=[];science=set()
    for ordinal,item in enumerate(ordered):
        receipt,finished,values=archived_chunk(plan,p.checked_subject(item))
        p.require(receipt['chunk']==ordinal,'The final bank cannot duplicate or skip a global chunk.')
        science.add(receipt['scienceSha256']);worlds.extend(values)
        # Preserve original native completion bytes, not a reserialized equivalent.
        archive=Path(item['path']).with_name(receipt['archive'])
        with tarfile.open(archive,'r:gz') as source:
            member=source.getmember('finished.json')
            with source.extractfile(member) as content:raw=content.read()
        path=directory/f'chunk-{ordinal}-finished.json';path.write_bytes(raw)
        p.require(p.file_sha(path)==receipt['files']['finished.json']['sha256'],'Native completion copy changed.')
        subjects.append(admission.relative_subject(path,directory/'compact.json'))
    p.require(len(science)==1,'Both hosts must retain one path-independent screen science identity.')
    compact=storage.publish_json(directory/'compact.json',dict(kind='token-paired-screen-compact-1',chunks=subjects,worlds=worlds))
    command=[profile['java']['executable'],f'-Xmx{profile["heapMiB"]}m','-XX:ActiveProcessorCount=1',
        '-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',str(Path(profile['runtime'])/'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt','--mode','token-paired-screen-report',
        '--worlds',compact['path'],'--output',str(root/'screen-report')]
    execution.run_child(profile,command,root/'screen-report.log')
    report=p.read(root/'screen-report/report.json');passed=verify_report(worlds,report)
    validation=p.read(p.checked_subject(validation_effect))
    p.require(validation['kind']=='token-paired-validation-effect-1' and validation['context']['iteration']==2,
        'The sole final validation endpoint must come from fixed iteration2.')
    final_path=root/'portable/final-admission.json';final=p.read(final_path)
    p.require(validation['modelSha256']==final['finalActor']['sha256'],'Final validation and screen actor must be identical.')
    nominated=passed and validation['estimate']['lower']>0
    result=dict(kind='token-paired-screen-complete-1',complete=True,chunks=ordered,
        compact=compact,report=p.subject(root/'screen-report/report.json'),
        reportFinished=p.subject(root/'screen-report/finished.json'),finalAdmission=p.subject(final_path),
        historicalAdmission=p.subject(root/'historical/historical-admission.json'),validationEffect=validation_effect,
        worlds=1024,games=3072,reproductions=12,historicalControls=2,
        nominationPassed=nominated,nominee=(p.subject(admission.resolve(final['finalActor'],final_path)) if nominated else None),
        qualification=False,activated=False)
    return storage.publish_json(root/'screen-complete.json',result)


def audit_completed(plan,run,host,*,read_json,check_subject,stream_archive):
    """Pure final evidence, with caller-owned one-pass archive hashing and immutable pin ledger."""
    run=Path(run);p.require(host in p.HOSTS,'A terminal screen audit requires its exact host role.')
    if host=='DESKTOP':
        completion=read_json(run/'screen-complete.json')
        p.require(completion['kind']=='token-paired-screen-complete-1' and completion['complete'] is True and
            completion['worlds']==1024 and completion['games']==3072 and completion['reproductions']==12 and
            completion['historicalControls']==2,'Only the complete fixed final screen is admissible.')
        subjects=completion['chunks'];expected_chunks=list(range(8))
        final_path=check_subject(completion['finalAdmission'])
        historical_path=check_subject(completion['historicalAdmission'])
    else:
        expected_chunks=list(p.SCREEN_CHUNKS['MAC'])
        subjects=[p.subject(run/'screen/chunks'/f'chunk-{i}'/'evidence.tar.gz.json') for i in expected_chunks]
        final_path=run/'downloads/screen/content/final-admission.json'
        historical_path=run/'downloads/screen/content/historical-admission.json'
    final=read_json(final_path);historical_gate=read_json(historical_path)
    protocol_path=admission.resolve(final['protocol'],final_path)
    p.require(p.read(protocol_path)==plan.record and final['operationallyComplete'] is True and
        final['completedIterations']==2 and final['validationEffectsUsedForSelection'] is False,
        'Final screens may use only the sole admitted two-update actor.')
    check_subject(p.subject(protocol_path))
    for item in final['iterations']:
        path=admission.resolve(item,final_path);admission.verify_iteration(path);check_subject(p.subject(path))
    verify_historical(historical_gate,p.file_sha(protocol_path),final['scopeSha256'],plan.record['ecologySha256'])
    final_sha=p.file_sha(final_path);history_sha=p.file_sha(historical_path)
    all_worlds=[];native_finishes=[]
    p.require(len(subjects)==len(expected_chunks),'The audit must retain exactly the declared host chunks.')
    for expected,item in zip(expected_chunks,subjects):
        path=check_subject(item);receipt=read_json(path)
        p.require(receipt['chunk']==expected and receipt['finalAdmissionSha256']==final_sha and
            receipt['historicalAdmissionSha256']==history_sha,'Every host chunk must share exact final/historical subjects.')
        checked,finished,worlds=archived_chunk(plan,path,stream_archive=stream_archive)
        all_worlds.extend(worlds);native_finishes.append((checked,finished))
        if host=='MAC':
            delivered=read_json(path.parent/'handoff.json')
            p.require(delivered['receipt']['sha256']==item['sha256'] and
                delivered['archive']['sha256']==receipt['archiveSha256'],
                'Each Mac chunk requires its original verified Desktop delivery acknowledgment.')
    eligible=p.subject(admission.resolve(final['finalActor'],final_path));check_subject(eligible)
    if host=='MAC':
        return dict(worlds=384,games=1152,reproductions=6,historicalControls=0,
            reportSubject=None,eligibleActor=eligible,nominee=None)
    compact=read_json(check_subject(completion['compact']))
    p.require(compact['kind']=='token-paired-screen-compact-1' and compact['worlds']==all_worlds and
        len(compact['chunks'])==8,'Complete compact worlds must equal every original archived world.')
    compact_path=Path(completion['compact']['path'])
    for item,(receipt,finished) in zip(compact['chunks'],native_finishes):
        path=admission.resolve(item,compact_path);check_subject(p.subject(path))
        p.require(p.file_sha(path)==receipt['files']['finished.json']['sha256'] and read_json(path)==finished,
            'Native compact completions must preserve the exact original member bytes.')
    report=read_json(check_subject(completion['report']));passed=verify_report(all_worlds,report)
    report_finish=read_json(check_subject(completion['reportFinished']))
    p.require(report_finish['kind']=='token-paired-screen-report-complete-1' and report_finish['complete'] is True and
        report_finish['worlds']==1024 and report_finish['newGames']==report_finish['newFits']==0,
        'Final reporting is a pure once-only read of the completed original bank.')
    report_root=Path(completion['reportFinished']['path']).parent
    for name,expected in report_finish['files'].items():check_subject(dict(path=str(report_root/name),sha256=expected))
    effect=read_json(check_subject(completion['validationEffect']))
    p.require(effect['context']['iteration']==2 and effect['modelSha256']==eligible['sha256'],
        'Only final iteration2 validation can enter the fixed nomination rule.')
    nominated=passed and effect['estimate']['lower']>0
    p.require(completion['nominationPassed']==nominated and completion['nominee']==(eligible if nominated else None)
        and completion['qualification'] is False and completion['activated'] is False,
        'No earlier policy, host, raw control or changed gate may be nominated.')
    return dict(worlds=1024,games=3072,reproductions=12,historicalControls=2,
        reportSubject=completion['report'],eligibleActor=eligible,nominee=eligible if nominated else None)
