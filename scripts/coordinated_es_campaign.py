"""One fixed16-generation Desktop coordinator and outbound-only Mac worker.

There is no scheduler, retry, shortened candidate or quality-driven branch. The existing
controller owns all subprocesses and the original shared clock; this file owns only the
finite scientific transitions and immutable handoff subjects.
"""
import argparse
import os
from pathlib import Path
import time

import expansion_on_policy_loop as native
import coordinated_es_handoff as handoff
import coordinated_es_numeric_stage as numeric
import coordinated_es_plan as p
import coordinated_es_process as execution
import coordinated_es_storage as storage


def check_profile(protocol_path,profile_path):
    plan=p.Plan.from_file(protocol_path);profile=p.read(profile_path)
    p.require(profile['kind']==p.PREFIX+'-host-1' and profile['host'] in p.HOSTS and
        profile['protocolSha256']==p.file_sha(protocol_path),
        'The actual host must bind its exact shared scientific protocol.')
    p.require(all(profile[key]==value for key,value in p.host_limits(profile['host']).items()) and
        os.cpu_count()==profile['machineCpus'] and
        os.environ.get('AOR_TRAINING_MACHINE_CPUS')==str(profile['machineCpus']),
        'Native CPU and resource declarations must match the actual fixed host.')
    p.require(profile['files'] and all(p.file_sha(path)==digest for path,digest in profile['files'].items()) and
        profile['files'].get('/bin/ps')==p.file_sha('/bin/ps'),
        'Every frozen executing source, runtime, model and liveness executable must remain exact.')
    for key in ('arena','originalBundle','projection','initialCenter','numerical'):
        p.checked_subject(profile[key])
    p.require(profile['originalBundle']['sha256']==plan.record['original']['bundleSha256'] and
        profile['projection']['sha256']==plan.record['original']['projectionSha256'] and
        len(profile['directions'])==16,'The one original scope and fixed projection/directions cannot change.')
    for item in profile['directions']:p.checked_subject(item)
    preparation=profile['numericalPreparation'];request=p.read(p.checked_subject(preparation['request']))
    finished=p.read(p.checked_subject(preparation['finished']))
    p.require(finished['kind']==p.PREFIX+'-numeric-complete-1' and finished['verb']=='prepare' and
        finished['complete'] is True and finished['passed'] is True and
        finished['request']==preparation['request'] and request['projectionSeed']==plan.record['projectionSeed'] and
        request['directionSeeds']==[row['directionSeed'] for row in plan.record['generations']] and
        request['scopeSha256']==plan.record['original']['scopeSha256'] and
        request['originalBundle']['sha256']==profile['originalBundle']['sha256'],
        'The original zero-world preparation must use the exact prospectively reserved streams.')
    roots=[Path(path).resolve() for path in profile['scientificInputDirectories']]
    p.require(roots and all(any(Path(item['path']).resolve().is_relative_to(root) for root in roots)
        for item in [profile['originalBundle'],profile['projection'],profile['initialCenter'],*profile['directions']]),
        'All immutable scientific model/projection inputs must count in shared base storage.')
    return plan,profile


def native_run(profile,mode,source,output,log,extra=()):
    command=native.native_command(profile,mode,source,output,extra,maximum_seconds=p.SCIENCE_SECONDS)
    execution.run_child(profile,command,log)


def context(directory,plan,protocol,profile,number,phase,center,previous,historical,calibration=None,population=None):
    """One portable stage closure, with relative subjects and no path-dependent reserialization."""
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    p.require(not (directory/'stage.json').exists(),'A stage context cannot be republished.')
    items={}
    for key,item,name in (('center',center,'center.json'),('directions',profile['directions'][number-1],
        'directions.json'),('previousUpdate',previous,'previous-update.json')):
        if item is None:items[key]=None
        else:
            copied=numeric.copy_file(item,directory/name);items[key]=dict(path=name,sha256=copied['sha256'])
    numeric.copy_tree(Path(historical['path']).parent,directory/'historical')
    items['historical']=dict(path='historical/'+Path(historical['path']).name,sha256=historical['sha256'])
    if calibration is None:items['calibration']=None
    else:
        destination=directory/'calibration'
        if not destination.exists():numeric.copy_tree(Path(calibration['path']).parent,destination)
        p.require(p.file_sha(destination/'calibration.json')==calibration['sha256'],
            'Every later stage must retain the original unchanged calibration closure.')
        items['calibration']=dict(path='calibration/calibration.json',sha256=calibration['sha256'])
    if population is None:items['population']=None
    else:
        p.require(p.file_sha(directory/'population.json')==population['sha256'],
            'Executable population must already occupy its unchanged relative member layout.')
        items['population']=dict(path='population.json',sha256=population['sha256'])
    row=dict(kind=p.PREFIX+'-stage-context-1',stage=f'{phase}-{number}',generation=number,
        protocolSha256=p.file_sha(protocol),scopeSha256=plan.record['original']['scopeSha256'],subjects=items)
    storage.publish_json(directory/'stage.json',row)
    return directory


def read_context(directory,plan,protocol,profile,number,phase):
    directory=Path(directory);row=p.read(directory/'stage.json')
    p.require(row['kind']==p.PREFIX+'-stage-context-1' and row['stage']==f'{phase}-{number}' and
        row['generation']==number and row['protocolSha256']==p.file_sha(protocol) and
        row['scopeSha256']==plan.record['original']['scopeSha256'] and
        set(row['subjects'])=={'center','directions','previousUpdate','historical','calibration','population'},
        'Both hosts require the exact same finite stage and original scientific scope.')
    subjects=dict(protocol=p.subject(protocol),arena=profile['arena'],originalBundle=profile['originalBundle'],
        projection=profile['projection'])
    for key,item in row['subjects'].items():
        if item is None:subjects[key]=None;continue
        target=directory/storage.safe(item['path'])
        subjects[key]=dict(path=str(target.resolve()),sha256=item['sha256']);p.checked_subject(subjects[key])
    plan.generation_source(number,0,phase.upper(),subjects)
    return subjects


def publish_context(root,directory,number,phase,protocol,plan):
    name=f'{phase}-{number}'
    files={member:p.subject(Path(directory)/member) for member in storage.inventory(directory)}
    return handoff.bundle(Path(root)/'publications'/name,name,files,
        dict(protocolSha256=p.file_sha(protocol),scopeSha256=plan.record['original']['scopeSha256'],
            stage=name,generation=number,contextSha256=p.file_sha(Path(directory)/'stage.json')))


def collect(plan,profile,root,number,chunk,phase,subjects):
    p.require(p.owner(chunk)==profile['host'],'Only the original assigned host may execute a shard.')
    directory=Path(root)/'generations'/f'generation-{number}'/phase/f'chunk-{chunk}'
    p.require(not directory.exists(),'A native shard has one original attempt and no resume.')
    directory.mkdir(parents=True);source=directory/'source.json';output=directory/'native'
    storage.publish_json(source,plan.generation_source(number,chunk,phase.upper(),subjects))
    native_run(profile,'coordinated-es-'+phase,source,output,directory/'native.log',
        ('--workers',str(profile['workers'])))
    if phase=='population':
        for item in p.replays(number,chunk):
            name=f'replay-{storage.role(item["mutant"]["index"])}-{item["globalIndex"]}'
            native_run(profile,'coordinated-es-reproduce',source,output/name,directory/(name+'.log'),
                ('--original',str(output),'--index',str(item['globalIndex']),
                 '--role',storage.role(item['mutant']['index'])))
    receipt=storage.verify_generation(plan,output,source,number,chunk,phase)
    archive=directory/'evidence.tar.gz';storage.archive_collection(output,archive,receipt)
    return p.subject(archive.with_name(archive.name+'.json'))


def wait_receipts(profile,root,number=None,phase=None,screen=False):
    root=Path(root);chunks=p.SCREEN_CHUNKS['MAC'] if screen else p.GENERATION_CHUNKS['MAC']
    while True:
        execution.live(profile)
        paths=[root/'incoming'/handoff.tag(number,chunk,screen,phase=phase)/'accepted.json' for chunk in chunks]
        if all(path.is_file() for path in paths):
            result=[]
            for chunk,path in zip(chunks,paths):
                row=p.read(path)
                p.require(row['kind']==p.PREFIX+'-handoff-1' and
                    row['tag']==handoff.tag(number,chunk,screen,phase=phase),
                    'The completed transfer must belong to its exact original slot.')
                p.checked_subject(row['receipt']);p.checked_subject(row['archive']);result.append(row['receipt'])
            return result
        time.sleep(min(10.,profile['_clock'].remaining(time.time())))


def stop(result,completed):
    reason=result['reason']
    if reason in numeric.CALIBRATION_STOPS:kind='calibration-failed'
    elif reason=='zero-gradient':kind=reason
    else:
        p.require(reason.startswith('nonfinite-'),'Unknown operational stop cannot choose a successor.')
        kind='nonfinite-arithmetic'
    return dict(status='stopped',reason=kind,completedGenerations=completed,numericStop=result['receipt'])


def prefix(profile,root,protocol,durations):
    root=Path(root);peer=root/'peer-retention/generation-2.json'
    while not peer.exists():
        execution.live(profile);time.sleep(min(10.,profile['_clock'].remaining(time.time())))
    local=storage.retention_record(root,profile['scientificInputDirectories'],'DESKTOP',2,p.file_sha(protocol))
    retention={host:storage.prefix_retention(row,p.file_sha(protocol),host,2)
        for host,row in (('DESKTOP',local),('MAC',p.read(peer)))}
    remaining=profile['_clock'].remaining(time.time());size=0;base=retention['DESKTOP']['generationDeltaBytes'][1]
    for _ in range(8):
        retention['DESKTOP']['generationDeltaBytes'][1]=base+size
        row=p.prefix_gate(remaining,durations,retention)
        row.update(censusAdjustmentBytes=size,censuses=dict(DESKTOP=p.subject(root/'generations/generation-2/retention.json'),
            MAC=p.subject(peer)))
        new_size=len(p.canonical(row))+1
        if new_size==size:break
        size=new_size
    else:raise ValueError('The bounded prefix receipt framing did not stabilize.')
    storage.publish_json(root/'generations/generation-2/operational-prefix.json',row)
    return row


def desktop(plan,profile,protocol,root):
    import coordinated_es_screen as screen
    root=Path(root);historical=screen.historical(plan,profile,protocol,root)
    center=profile['initialCenter'];previous=None;calibration=None;panel=None;durations=[]
    for number in range(1,17):
        execution.live(profile);started=time.time();directory=root/'generations'/f'generation-{number}'
        control=context(directory/'control-context',plan,protocol,profile,number,'controls',center,previous,historical,calibration)
        publish_context(root,control,number,'controls',protocol,plan)
        subjects=read_context(control,plan,protocol,profile,number,'controls')
        controls=[collect(plan,profile,root,number,chunk,'controls',subjects)
            for chunk in p.GENERATION_CHUNKS['DESKTOP']]
        controls+=wait_receipts(profile,root,number,'controls')
        bank=numeric.bank_subject(plan,number,directory)
        materialize=numeric.invoke(profile,'materialize',directory/'materialize',dict(protocol=p.subject(protocol),
            originalBundle=profile['originalBundle'],projection=profile['projection'],center=center,
            directions=profile['directions'][number-1],worldBank=bank,worldIds=list(range(256)),calibration=calibration))
        p.require(materialize['complete'],'Population materialization failure cannot create a replacement.')
        if number==1:
            value,pins=storage.public_panel(plan,p.file_sha(protocol),controls)
            panel=numeric.publish_numeric(directory/'public-panel.json',value)
            storage.publish_json(directory/'public-panel-binding.json',dict(panel=panel,controls=pins))
            result=numeric.invoke(profile,'calibrate',directory/'calibrate',dict(protocol=p.subject(protocol),
                originalBundle=profile['originalBundle'],projection=profile['projection'],
                directions=profile['directions'][0],materialization=p.subject(directory/'materialize/materialization.json'),
                publicPanel=panel))
            if not result['complete']:return stop(result,0)
            calibration=p.subject(directory/'calibrate/calibration.json')
            population=p.subject(directory/'calibrate/population.json')
        else:population=p.subject(directory/'materialize/population.json')
        population=numeric.population_publication(directory/'materialize',population,
            Path(calibration['path']).parent,directory/'portable')
        portable=context(directory/'portable',plan,protocol,profile,number,'population',center,previous,
            historical,calibration,population)
        publish_context(root,portable,number,'population',protocol,plan)
        subjects=read_context(portable,plan,protocol,profile,number,'population')
        populations=[collect(plan,profile,root,number,chunk,'population',subjects)
            for chunk in p.GENERATION_CHUNKS['DESKTOP']]
        populations+=wait_receipts(profile,root,number,'population')
        fitness=numeric.publish_numeric(directory/'fitness.json',
            storage.fitness(plan,p.file_sha(protocol),population,calibration,populations))
        result=numeric.invoke(profile,'update',directory/'update',dict(protocol=p.subject(protocol),center=center,
            directions=profile['directions'][number-1],population=population,fitness=fitness))
        if not result['complete']:return stop(result,number-1)
        next_center=p.subject(directory/'update/center.json');update=p.subject(directory/'update/update.json')
        diagnostic=numeric.invoke(profile,'diagnose',directory/'diagnose',dict(protocol=p.subject(protocol),
            originalBundle=profile['originalBundle'],projection=profile['projection'],center=next_center,
            calibration=calibration,publicPanel=panel))
        p.require(diagnostic['complete'],'Diagnostic evidence corruption is failure, never a movement gate.')
        finished=time.time();durations.append(finished-started)
        storage.publish_json(directory/'receipt.json',dict(kind=p.PREFIX+'-generation-receipt-1',complete=True,
            generation=number,protocolSha256=p.file_sha(protocol),scopeSha256=plan.record['original']['scopeSha256'],
            inputCenter=center,outputCenter=next_center,directions=profile['directions'][number-1],bank=bank,
            population=population,calibration=calibration,controls=controls,populations=populations,
            fitness=fitness,update=update,diagnostic=diagnostic['receipt'],startedEpoch=started,
            finishedEpoch=finished,elapsedSeconds=finished-started))
        center=next_center;previous=update
        if number==1:storage.retention_record(root,profile['scientificInputDirectories'],'DESKTOP',1,p.file_sha(protocol))
        if number==2 and not prefix(profile,root,protocol,durations)['passed']:
            return dict(status='stopped',reason='prefix-resource-gate',completedGenerations=2)
    return screen.finish(plan,profile,protocol,root,center,calibration,historical)


def mac(plan,profile,protocol,root):
    import coordinated_es_screen as screen
    root=Path(root);peer=profile['_peer'];peer.await_active(p.file_sha(protocol))
    for number in range(1,17):
        for phase in ('controls','population'):
            name=f'{phase}-{number}';ready=peer.await_stage(name)
            folder=peer.fetch(name,ready,root/'downloads'/name)
            subjects=read_context(folder,plan,protocol,profile,number,phase)
            p.require(ready['publication']['identity']==dict(protocolSha256=p.file_sha(protocol),
                scopeSha256=plan.record['original']['scopeSha256'],stage=name,generation=number,
                contextSha256=p.file_sha(folder/'stage.json')),'The portable publication scope cannot change.')
            for chunk in p.GENERATION_CHUNKS['MAC']:
                receipt=collect(plan,profile,root,number,chunk,phase,subjects)
                path=Path(receipt['path']);value=p.read(path)
                accepted=peer.push(path.with_name(value['archive']),path,number,chunk,phase=phase)
                storage.publish_json(path.parent/'handoff.json',accepted)
        if number in (1,2):
            peer.push_retention(storage.retention_record(root,profile['scientificInputDirectories'],
                'MAC',number,p.file_sha(protocol)))
    ready=peer.await_stage('screen');folder=peer.fetch('screen',ready,root/'downloads/screen')
    return screen.mac(plan,profile,protocol,root,folder,ready)


def failed(profile,root,launch,error):
    storage.publish_json(Path(root)/'terminal.json',dict(kind=p.PREFIX+'-terminal-1',host=profile['host'],
        status='failed',failure=str(error),updatedEpoch=time.time(),clock=launch))
    if profile['host']=='MAC':
        try:profile['_peer'].remote('fail','MAC')
        except Exception:pass  # The original shared deadline still bounds an unreachable peer.


def run(protocol_path,profile_path,clock_path,root):
    plan,profile=check_profile(protocol_path,profile_path);launch=p.read(clock_path)
    p.require(launch['kind']==p.PREFIX+'-clock-1' and launch['protocolSha256']==p.file_sha(protocol_path),
        'Both workers require the exact reviewed original clock bytes.')
    clock=p.Clock(launch['startedEpoch'],launch['deadlineEpoch']);clock.remaining(time.time())
    p.require(float(os.environ['AOR_CAMPAIGN_DEADLINE_EPOCH'])<=clock.deadline and
        float(os.environ['AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'])<=clock.deadline,
        'The original controller clock cannot be extended by either host.')
    root=Path(root);p.require(not root.exists(),'This finite worker has one original attempt and no resume.')
    root.mkdir(parents=True)
    storage.publish_json(root/'worker-owner.json',dict(kind=p.PREFIX+'-worker-owner-1',pid=os.getpid(),processGroup=os.getpgrp()))
    storage.publish_json(root/'clock.json',launch);profile=dict(profile,_root=root,_clock=clock)
    if profile['host']=='MAC':profile['_peer']=handoff.Outbound(profile['peer'],clock,root)
    try:result=(desktop if profile['host']=='DESKTOP' else mac)(plan,profile,protocol_path,root)
    except handoff.PeerStopped as stopped:
        try:
            row=stopped.response['terminal'];original=stopped.response['terminalSubject']
            p.require(profile['host']=='MAC' and row['clock']==launch,'Peer stop must bind the unchanged shared clock.')
            copied=storage.publish_json(root/'peer-terminal.json',row)
            p.require(copied['sha256']==original['sha256'],'The peer stop must preserve exact original bytes.')
            result=dict(status='stopped',reason='coordinator-operational-stop',completedGenerations=row['completedGenerations'],
                peerTerminal=copied,originalPeerSubject=original)
        except BaseException as error:failed(profile,root,launch,error);raise
    except BaseException as error:failed(profile,root,launch,error);raise
    storage.publish_json(root/'terminal.json',dict(kind=p.PREFIX+'-terminal-1',host=profile['host'],
        updatedEpoch=time.time(),clock=launch,**result))
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('protocol','profile','clock','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();run(args.protocol,args.profile,args.clock,args.output)
