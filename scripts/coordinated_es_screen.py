"""Two original zero-policy controls and the sole final16 paired development screen."""
import math
from pathlib import Path
import tarfile

import automata_experiments as intervals
import automata_policy_search as arena_math
import coordinated_es_plan as p
import coordinated_es_storage as storage
import coordinated_es_numeric_stage as numeric
import coordinated_es_admission as admission
import coordinated_es_handoff as handoff
import coordinated_es_process as execution
import token_paired_screen as shared
from token_paired_campaign_audit import Pins,stream_archive

KIND=p.PREFIX+'-screen-1'
ROLES=('FINAL','RAW_G3')


def verify_historical(gate,plan,protocol_sha):
    original=plan.record['original']
    p.require(gate['kind']==p.PREFIX+'-historical-admission-1' and gate['complete'] is True and
        gate['passed'] is True and gate['zeroFailures'] is True and gate['protocolSha256']==protocol_sha and
        gate['scopeSha256']==original['scopeSha256'] and gate['ecologySha256']==original['ecologySha256'] and
        gate['originalBundleSha256']==original['bundleSha256'] and
        gate['scientificTerminalExecutions']==gate['attemptedGames']==gate['completedGames']==2 and
        len(gate['controls'])==2,'Both exact original zero-role histories must pass before any fresh game.')
    for expected,row in zip(plan.record['historicalControls'],gate['controls']):
        p.require(row['index']==expected['index'] and row['side']==expected['side'] and
            row['canonicalVerified'] is True and row['execution']=='ZERO_REFERENCE',
            'The two historical sides and exact-zero executable identity cannot change.')


def historical(plan,profile,protocol,root):
    from coordinated_es_campaign import native_run
    p.require(profile['host']=='DESKTOP' and len(profile['historicalControls'])==2,
        'Desktop alone executes the two original historical controls once.')
    root=Path(root);source=root/'historical-source.json';output=root/'historical'
    for expected,row in zip(plan.record['historicalControls'],profile['historicalControls']):
        p.require(row['index']==expected['index'] and row['side']==expected['side'],
            'The host cannot substitute a historical control after preparation.')
    storage.publish_json(source,dict(kind=p.PREFIX+'-historical-source-1',protocol=p.subject(protocol),
        arena=profile['arena'],originalBundle=profile['originalBundle'],controls=profile['historicalControls']))
    native_run(profile,p.PREFIX+'-historical',source,output,root/'historical.log')
    gate=p.read(output/'historical-admission.json');verify_historical(gate,plan,p.file_sha(protocol))
    p.require(gate['sourceSha256']==p.file_sha(source),'The historical admission must retain its exact raw source.')
    for expected,row in zip(profile['historicalControls'],gate['controls']):
        p.require(all(row[field]==expected[key]['sha256'] for key,field in
            (('source','originalSourceSha256'),('pair','originalPairSha256'),('replay','originalReplaySha256'))) and
            p.file_sha(output/storage.safe(row['replayFile']))==row['replaySha256'],
            'Historical source, pair, canonical and actual replay bytes remain exact.')
    return p.subject(output/'historical-admission.json')


def audit_historical(protocol,root,host,pins):
    plan=p.Plan.from_file(pins.subject(protocol));root=Path(root)
    directory=root/'historical' if host=='DESKTOP' else root/'downloads/controls-1/content/historical'
    gate=pins.read(directory/'historical-admission.json');verify_historical(gate,plan,protocol['sha256'])
    for row in gate['controls']:pins.pin(directory/storage.safe(row['replayFile']),row['replaySha256'])
    if host=='DESKTOP':
        source=root/'historical-source.json';pins.pin(source,gate['sourceSha256']);request=pins.read(source)
        for original,row in zip(request['controls'],gate['controls']):
            for key,field in (('source','originalSourceSha256'),('pair','originalPairSha256'),('replay','originalReplaySha256')):
                p.require(original[key]['sha256']==row[field],'Original historical subject binding changed.')
                pins.subject(original[key])
    return p.subject(directory/'historical-admission.json')


def final_bindings(plan,protocol_sha,final_subject,pins):
    owner=pins.subject(final_subject);value=pins.read(owner)
    p.require(value['kind']==p.PREFIX+'-final-admission-1' and value['complete'] is True and
        value['completedGenerations']==16 and value['validationEffectsUsedForSelection'] is False,
        'Only the complete admitted mean16 may enter the final screen.')
    subjects={name:p.subject(admission.resolve(value[name],owner)) for name in
        ('protocol','originalBundle','projection','calibration','center','finalBundle','centerMaterialization','generationAudit','historical')}
    for item in subjects.values():pins.subject(item)
    original=plan.record['original']
    bundle=pins.read(subjects['finalBundle']['path'])
    p.require(set(bundle)=={'kind','heads'} and bundle['kind']==p.PREFIX+'-bundle-1' and
        set(bundle['heads'])==set(p.HEADS),'The final descriptor must retain all four explicit heads.')
    for head,row in bundle['heads'].items():
        p.require(set(row)=={'path','sha256','dictionarySha256'} and
            row['dictionarySha256']==original['heads'][head]['dictionarySha256'],
            'Every final head retains its exact original feature dictionary.')
        path=admission.resolve({key:row[key] for key in ('path','sha256')},subjects['finalBundle']['path'])
        pins.pin(path,row['sha256'])
    p.require(subjects['protocol']['sha256']==protocol_sha and
        subjects['originalBundle']['sha256']==original['bundleSha256'] and
        subjects['projection']['sha256']==original['projectionSha256'] and
        all(value[name]==original[name] for name in ('scopeSha256','ecologySha256')),
        'Final admission retains the exact original protocol, scope, projection and four-head container.')
    proof=pins.read(subjects['centerMaterialization']['path']);audit=pins.read(subjects['generationAudit']['path'])
    p.require(proof['kind']==p.PREFIX+'-center-materialization-1' and
        proof['generation']==16 and proof['finalCandidate'] is True and
        all(proof[field]==subjects[name]['sha256'] for name,field in
            (('protocol','protocolSha256'),('center','centerSha256'),('projection','projectionSha256'),
             ('originalBundle','originalBundleSha256'),('calibration','calibrationSha256'),('finalBundle','bundleSha256'))) and
        audit['kind']==p.PREFIX+'-generation-audit-1' and audit['passed'] is True and audit['complete'] is True and
        audit['completedGenerations']==16 and audit['finalCenterSha256']==subjects['center']['sha256'],
        'The screened bundle must be the exact materialization of the admitted final center.')
    identity=dict(protocolSha256=protocol_sha,finalBundleSha256=subjects['finalBundle']['sha256'],
        calibrationSha256=subjects['calibration']['sha256'])
    return subjects,identity


def chunk_evidence(plan,chunk,files,manifest,finished,worlds,replays,canonicals,final_sha,historical_sha,identity_bindings):
    indices=list(p.indices(chunk,True));owned=p.screen_replays(chunk);expected=256+2*len(owned)
    p.require(manifest['kind']==finished['kind']==KIND and finished['complete'] is True and
        finished['stage']=='chunk' and finished['globalStart']==manifest['globalStart']==chunk*128 and
        finished['count']==manifest['count']==128 and finished['signature']==manifest['signature'] and
        finished['scienceSha256']==manifest['scienceSha256'] and finished['scienceIdentity']==manifest['scienceIdentity'] and
        finished['finalAdmissionSha256']==manifest['admission']['sha256']==final_sha and
        finished['historicalAdmissionSha256']==manifest['historicalAdmission']['sha256']==historical_sha and
        finished['files']=={name:row['sha256'] for name,row in files.items() if name!='finished.json'} and
        finished['reproductions']==2*len(owned) and finished['scientificTerminalExecutions']==expected and
        finished['attemptedGames']==finished['completedGames']==expected,
        'A screen shard requires exactly its original completed worlds/replays and entire raw inventory.')
    identity=finished['scienceIdentity'];original=plan.record['original']
    p.require(identity['seeds']==plan.record['screenBank']['seeds'] and identity['roles']==list(ROLES) and
        identity['replayIndices']==list(p.SCREEN_REPLAYS) and
        all(identity[name]==original[name] for name in ('scopeSha256','ecologySha256')) and
        identity['originalBundleSha256']==original['bundleSha256'] and
        all(identity[name]==digest for name,digest in identity_bindings.items()),
        'Screen worlds retain one fixed role/ecology/bank across both hosts.')
    p.require(sorted(worlds)==indices and [row['index'] for row in finished['worlds']]==indices and
        {name for name in files if name.startswith('world-')}=={f'world-{index}.json.gz' for index in indices},
        'A screen shard cannot replace, duplicate or omit a global independent world.')
    for index,row in zip(indices,finished['worlds']):
        p.require(row['file']==f'world-{index}.json.gz' and files[row['file']]['sha256']==row['sha256'] and
            worlds[index]['signature']==manifest['signature'],'Native compact worlds must bind their original raw bytes.')
        shared.validate_world(plan,worlds[index],index,manifest['scienceSha256'],kind=KIND,roles=ROLES)
    p.require(set(replays)==set(canonicals)=={(index,role) for index in owned for role in ROLES} and
        manifest['ownedReplayIndices']==list(owned),'Only the prescribed global two-role reproductions may occur.')
    for (index,role),replay in replays.items():
        world=worlds[index]
        p.require(replay['replayVerified'] is True and replay['index']==index and replay['seed']==world['seed'] and
            replay['seat']==world['seat'] and replay['configuration']==world['configuration'] and
            replay['opponentIndices']==world['opponentIndices'] and replay['side']=='CANDIDATE' and
            arena_math.same_game(replay['game'],world['roles'][role]) and
            shared.canonical(replay)==canonicals[index,role] and
            ((replay.get('coordinatedEs') is None) == (role=='RAW_G3')),
            'Each actual role reproduction preserves the original world, full canonical and explicit role binding.')


def collect(plan,profile,root,chunk,final,historical):
    from coordinated_es_campaign import native_run
    p.require(p.owner(chunk,True)==profile['host'],'Final worlds cannot migrate between hosts.')
    directory=Path(root)/'screen/chunks'/f'chunk-{chunk}';directory.mkdir(parents=True,exist_ok=False)
    source=directory/'source.json';output=directory/'native'
    storage.publish_json(source,dict(kind=KIND,arena=profile['arena'],admission=final,historical=historical,
        globalStart=chunk*128,count=128,bank=plan.record['screenBank']))
    native_run(profile,p.PREFIX+'-screen-chunk',source,output,directory/'screen.log',('--workers',str(profile['workers'])))
    files=storage.inventory(output);worlds={};replays={};canonicals={};metadata={}
    for name in files:
        if name.startswith(('world-','replay-','canonical-')) or name in ('manifest.json','finished.json'):
            shared.parse_member(name,(output/name).read_bytes(),worlds,replays,canonicals,metadata)
    _,bindings=final_bindings(plan,profile['protocolSha256'],final,Pins(profile['_clock'].deadline))
    chunk_evidence(plan,chunk,files,metadata['manifest.json'],metadata['finished.json'],worlds,replays,canonicals,
        final['sha256'],historical['sha256'],bindings)
    receipt=dict(kind=p.PREFIX+'-screen-chunk-receipt-1',chunk=chunk,host=profile['host'],files=files,
        scienceSha256=metadata['manifest.json']['scienceSha256'],finalAdmissionSha256=final['sha256'],
        historicalAdmissionSha256=historical['sha256'],worlds=128,games=256,reproductions=len(replays),
        scientificTerminalExecutions=256+len(replays))
    archive=directory/'evidence.tar.gz';storage.archive_collection(output,archive,receipt)
    return p.subject(archive.with_name(archive.name+'.json'))


def archived(plan,item,pins,identity_bindings):
    path=pins.subject(item);receipt=pins.read(path)
    names=[name for name in receipt['files'] if name.startswith(('world-','replay-','canonical-')) or
        name in ('manifest.json','finished.json')]
    values=stream_archive(path.with_name(receipt['archive']),receipt,pins,names)
    worlds={};replays={};canonicals={};metadata={}
    for name,value in values.items():shared.parse_value(name,value,worlds,replays,canonicals,metadata)
    chunk_evidence(plan,receipt['chunk'],receipt['files'],metadata['manifest.json'],metadata['finished.json'],
        worlds,replays,canonicals,receipt['finalAdmissionSha256'],receipt['historicalAdmissionSha256'],identity_bindings)
    p.require(receipt['kind']==p.PREFIX+'-screen-chunk-receipt-1' and receipt['host']==p.owner(receipt['chunk'],True) and
        receipt['worlds']==128 and receipt['games']==256 and receipt['reproductions']==len(replays) and
        receipt['scientificTerminalExecutions']==256+len(replays),'Screen receipt cannot alter actual original counts.')
    return receipt,metadata['finished.json'],[worlds[index] for index in p.indices(receipt['chunk'],True)]


def verify_report(worlds,report):
    p.require([world['index'] for world in worlds]==list(range(2048)) and
        report['kind']==p.PREFIX+'-screen-report-1' and report['worlds']==2048 and report['intervalMultiplier']==1.96,
        'Only the complete original final screen may produce development effects.')
    for metric in ('wins','rewards','ranks','chaos'):
        def value(world,role):
            game=world['roles'][role];seat=world['seat']
            return int(game['scores'][seat]['total'] is None) if metric=='chaos' else game[metric][seat]
        expected=intervals.interval([value(world,'FINAL')-value(world,'RAW_G3') for world in worlds])
        p.require(all(math.isclose(report['finalMinusRawG3'][metric][name],number,rel_tol=1e-10,abs_tol=1e-12)
            for name,number in expected.items()),'Native intervals must reproduce from independent whole paired worlds.')
    passed=all(report['finalMinusRawG3'][metric]['lower95']>0 for metric in ('wins','rewards'))
    p.require(report['developmentGatePassed']==passed and report['qualification'] is False and
        report['activated'] is False and report['automaticSuccessor'] is False,
        'The sole fixed development gate cannot become qualification, activation or another campaign.')
    return passed


def complete(plan,profile,protocol,root,receipts,final,historical):
    root=Path(root);directory=root/'screen-report-input';directory.mkdir(parents=True,exist_ok=False)
    p.require(len(receipts)==16,'Every fixed Desktop/Mac final shard must complete before effects.')
    pins=Pins(profile['_clock'].deadline);worlds=[];chunks=[];sciences=set()
    _,bindings=final_bindings(plan,p.file_sha(protocol),final,pins)
    ordered=sorted(receipts,key=lambda item:p.read(p.checked_subject(item))['chunk'])
    for ordinal,item in enumerate(ordered):
        receipt,finished,rows=archived(plan,item,pins,bindings)
        p.require(receipt['chunk']==ordinal and receipt['finalAdmissionSha256']==final['sha256'] and
            receipt['historicalAdmissionSha256']==historical['sha256'],'Final and historical subjects are shared by every shard.')
        worlds.extend(rows);sciences.add(receipt['scienceSha256'])
        archive=Path(item['path']).with_name(receipt['archive'])
        with tarfile.open(archive,'r:gz') as source:
            with source.extractfile('finished.json') as stream:raw=stream.read()
        path=directory/f'chunk-{ordinal}-finished.json';path.write_bytes(raw)
        p.require(p.file_sha(path)==receipt['files']['finished.json']['sha256'],'Copied completion bytes changed.')
        chunks.append(admission.relative(path,directory/'compact.json'))
    p.require(len(sciences)==1,'All final world records must retain one shared portable science identity.')
    compact=storage.publish_json(directory/'compact.json',dict(kind=p.PREFIX+'-screen-compact-1',chunks=chunks,worlds=worlds))
    command=[profile['java']['executable'],f'-Xmx{profile["heapMiB"]}m','-XX:ActiveProcessorCount=1',
        '-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',str(Path(profile['runtime'])/'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt','--mode',p.PREFIX+'-screen-report',
        '--worlds',compact['path'],'--output',str(root/'screen-report')]
    execution.run_child(profile,command,root/'screen-report.log')
    report=p.read(root/'screen-report/report.json');passed=verify_report(worlds,report)
    record=dict(kind=p.PREFIX+'-screen-complete-1',complete=True,protocolSha256=p.file_sha(protocol),chunks=ordered,
        compact=compact,report=p.subject(root/'screen-report/report.json'),reportFinished=p.subject(root/'screen-report/finished.json'),
        finalAdmission=final,historicalAdmission=historical,worlds=2048,games=4096,reproductions=8,historicalControls=2,
        developmentGatePassed=passed,qualification=False,activated=False,automaticSuccessor=False)
    return storage.publish_json(root/'screen-complete.json',record)


def finish(plan,profile,protocol,root,center,calibration,historical):
    import coordinated_es_audit as audit
    import coordinated_es_generation_audit as mathematics
    from coordinated_es_campaign import wait_receipts
    root=Path(root);subject=p.subject(protocol)
    numeric_audit=storage.publish_json(root/'numerical-audit.json',mathematics.verify_learning(subject,root))
    native_audit=storage.publish_json(root/'training-audit.json',audit.training(subject,root,pins=Pins(profile['_clock'].deadline)))
    result=numeric.invoke(profile,'center',root/'final-materialization',dict(protocol=subject,
        originalBundle=profile['originalBundle'],projection=profile['projection'],center=center,calibration=calibration))
    p.require(result['complete'],'Final mean16 materialization must complete without a replacement.')
    final=admission.publish(subject,profile,root,root/'final-materialization',numeric_audit,native_audit,root/'final-portable')
    portable=Path(final['path']).parent
    handoff.bundle(root/'publications/screen','screen',{name:p.subject(portable/name) for name in storage.inventory(portable)},
        dict(protocolSha256=subject['sha256'],finalAdmissionSha256=final['sha256'],
            scopeSha256=plan.record['original']['scopeSha256']))
    local=[collect(plan,profile,root,chunk,final,historical) for chunk in p.SCREEN_CHUNKS['DESKTOP']]
    remote=wait_receipts(profile,root,screen=True)
    completion=complete(plan,profile,protocol,root,local+remote,final,historical)
    final_record=p.read(final['path']);bundle=p.subject(admission.resolve(final_record['finalBundle'],final['path']))
    return dict(status='completed',reason='sixteen-fixed-generations-and-final-screen',completedGenerations=16,
        finalAdmission=final,finalBundle=bundle,screenComplete=completion)


def mac(plan,profile,protocol,root,folder,ready):
    root=Path(root);folder=Path(folder);final=p.subject(folder/'final-admission.json');value=p.read(final['path'])
    p.require(ready['publication']['identity']==dict(protocolSha256=p.file_sha(protocol),
        finalAdmissionSha256=final['sha256'],scopeSha256=plan.record['original']['scopeSha256']),
        'Mac may screen only the shared admitted final16 mean.')
    historical=p.subject(admission.resolve(value['historical'],final['path']))
    receipts=[]
    for chunk in p.SCREEN_CHUNKS['MAC']:
        item=collect(plan,profile,root,chunk,final,historical);receipts.append(item)
        path=Path(item['path']);row=p.read(path)
        accepted=profile['_peer'].push(path.with_name(row['archive']),path,None,chunk,True)
        storage.publish_json(path.parent/'handoff.json',accepted)
    completion=storage.publish_json(root/'screen-complete.json',dict(kind=p.PREFIX+'-screen-host-complete-1',
        complete=True,protocolSha256=p.file_sha(protocol),chunks=receipts,finalAdmission=final,historicalAdmission=historical))
    bundle=p.subject(admission.resolve(value['finalBundle'],final['path']))
    return dict(status='completed',reason='all-original-mac-shards-delivered',completedGenerations=16,
        finalAdmission=final,finalBundle=bundle,screenComplete=completion)


def audit_completed(protocol,root,host,pins):
    root=Path(root);plan=p.Plan.from_file(pins.subject(protocol));completion=pins.read(root/'screen-complete.json')
    p.require(completion['complete'] is True and completion['protocolSha256']==protocol['sha256'],
        'Final screen completion must retain the original protocol.')
    subjects,bindings=final_bindings(plan,protocol['sha256'],completion['finalAdmission'],pins)
    terminal=pins.read(root/'terminal.json')
    p.require(terminal['finalAdmission']==completion['finalAdmission'] and
        terminal['screenComplete']==p.subject(root/'screen-complete.json') and
        terminal['finalBundle']==subjects['finalBundle'] and
        completion['historicalAdmission']['sha256']==subjects['historical']['sha256'],
        'Terminal eligibility must identify exactly the complete final bundle that was screened.')
    verify_historical(
        pins.read(pins.subject(completion['historicalAdmission'])),plan,protocol['sha256'])
    expected=list(range(16)) if host=='DESKTOP' else list(p.SCREEN_CHUNKS['MAC'])
    p.require(len(completion['chunks'])==len(expected),'Every host must retain its entire fixed final range.')
    worlds=[];reproductions=0;sciences=set()
    for ordinal,item in zip(expected,completion['chunks']):
        receipt,finished,rows=archived(plan,item,pins,bindings)
        p.require(receipt['chunk']==ordinal and receipt['finalAdmissionSha256']==completion['finalAdmission']['sha256'] and
            receipt['historicalAdmissionSha256']==completion['historicalAdmission']['sha256'],
            'Final evidence must preserve the exact original completed shard order and actor.')
        worlds.extend(rows);reproductions+=receipt['reproductions'];sciences.add(receipt['scienceSha256'])
    p.require(len(sciences)==1,'Every retained screen shard must preserve its one fixed science identity.')
    if host=='DESKTOP':
        p.require(completion['kind']==p.PREFIX+'-screen-complete-1' and
            verify_report(worlds,pins.read(pins.subject(completion['report'])))==completion['developmentGatePassed'],
            'The complete final native report and prescribed gate must reproduce.')
        pins.subject(completion['compact']);pins.subject(completion['reportFinished'])
    else:p.require(completion['kind']==p.PREFIX+'-screen-host-complete-1','Mac cannot claim a global effect report.')
    return dict(games=len(worlds)*2,reproductions=reproductions,historicalControls=2 if host=='DESKTOP' else 0)
