"""Fixed inherited-center, win-only scientific transitions over the existing two-host lifecycle.

There is no calibration branch, scheduler, resumed attempt or shortened final candidate. The
original controller/native/archive owners enforce process, execution and byte accounting limits.
"""
import argparse
import importlib
import os
from pathlib import Path
import sys
import time

import coordinated_es_campaign as base
import coordinated_es_storage as storage
import coordinated_win_context as contexts
import coordinated_win_handoff as handoff
import coordinated_win_numeric_stage as numeric
import coordinated_win_plan as p
import coordinated_win_preparation as preparation
import coordinated_win_process as execution
import coordinated_win_storage as wins

SCREEN_MODULE='coordinated_win_screen'


def contract():return sys.modules[__name__]


def check_profile(protocol_path,profile_path):
    plan=p.Plan.from_file(protocol_path);profile=p.read(profile_path)
    p.require(profile['kind']==p.PREFIX+'-host-1' and profile['host'] in p.HOSTS and
        profile['protocolSha256']==plan.file_sha256,
        'The actual host binds the exact prospective continuation protocol.')
    p.require(all(profile[key]==value for key,value in p.host_limits(profile['host']).items()) and
        os.cpu_count()==profile['machineCpus'] and
        os.environ.get('AOR_TRAINING_MACHINE_CPUS')==str(profile['machineCpus']),
        'Native CPU and resource declarations must match the actual fixed host.')
    p.require(profile['files'] and all(p.file_sha(path)==digest for path,digest in profile['files'].items()) and
        profile['files'].get('/bin/ps')==p.file_sha('/bin/ps'),
        'All executing source, runtime, model and liveness bytes remain exactly frozen.')
    for key in ('arena','originArena','originProof','originalBundle','projection','warmStart','initialCenter',
            'inheritedCalibration','numerical'):
        p.checked_subject(profile[key])
    p.require(profile['originProof']['sha256']==plan.record['warmStart']['proofSha256'][profile['host']],
        'The host must retain its separately admitted old-runtime origin closure.')
    preparation.verify(plan,profile)
    wins.inherited_calibration(plan,plan.file_sha256,profile['inheritedCalibration'])
    diagnostic=profile.get('diagnosticPanel')
    if diagnostic is not None:p.checked_subject(diagnostic)
    roots=[Path(path).resolve() for path in profile['scientificInputDirectories']]
    subjects=[profile[key] for key in ('arena','originArena','originProof','originalBundle','projection','warmStart',
        'initialCenter','inheritedCalibration')]+profile['directions']
    if diagnostic is not None:subjects.append(diagnostic)
    p.require(roots and all(any(Path(item['path']).resolve().is_relative_to(root) for root in roots)
        for item in subjects),'All inherited and generated scientific payloads count in shared base storage.')
    return plan,profile


def native_run(profile,mode,source,output,log,extra=()):
    return base.native_run(profile,mode,source,output,log,extra,contract=contract())


def collect(plan,profile,root,number,chunk,phase,subjects):
    return base.collect(plan,profile,root,number,chunk,phase,subjects,contract=contract())


def verify_generation(plan,output,source,number,chunk,phase):
    return wins.verify_generation(plan,output,source,number,chunk,phase)


def wait_receipts(profile,root,number=None,phase=None,screen=False):
    return base.wait_receipts(profile,root,number,phase,screen,contract=contract())


def prefix(profile,root,protocol,durations):
    return base.prefix(profile,root,protocol,durations,contract=contract())


read_context=contexts.read
verify_publication=contexts.verify_publication


def stop(result,completed):
    reason=result['reason']
    p.require(reason=='zero-gradient' or reason.startswith('nonfinite-'),
        'Only the existing arithmetic stops can interrupt an inherited-calibration update.')
    return dict(status='stopped',reason='zero-gradient' if reason=='zero-gradient' else 'nonfinite-arithmetic',
        completedGenerations=completed,numericStop=result['receipt'])


def desktop(plan,profile,protocol,root):
    screen=importlib.import_module(SCREEN_MODULE)
    root=Path(root);historical=screen.historical(plan,profile,protocol,root)
    center=profile['initialCenter'];previous=None;durations=[]
    calibration=profile['inheritedCalibration'];panel=profile.get('diagnosticPanel')
    common={key:profile[key] for key in ('originalBundle','projection','warmStart','inheritedCalibration')}
    common['protocol']=p.subject(protocol)
    for number in range(1,17):
        execution.live(profile);started=time.time();directory=root/'generations'/f'generation-{number}'
        controls_context=contexts.create(directory/'control-context',plan,protocol,profile,number,
            'controls',center,previous,historical)
        contexts.publish(root,controls_context,number,'controls',protocol,plan)
        subjects=read_context(controls_context,plan,protocol,profile,number,'controls')
        controls=[collect(plan,profile,root,number,chunk,'controls',subjects)
            for chunk in p.GENERATION_CHUNKS['DESKTOP']]
        controls+=wait_receipts(profile,root,number,'controls')
        bank=numeric.bank_subject(plan,number,directory)
        materialize=numeric.invoke(profile,'materialize',directory/'materialize',dict(common,center=center,
            directions=profile['directions'][number-1],worldBank=bank))
        p.require(materialize['complete'],'Failed materialization cannot create a replacement population.')
        population=numeric.population_publication(directory/'materialize',p.subject(directory/'materialize/population.json'),
            calibration,directory/'portable')
        portable=contexts.create(directory/'portable',plan,protocol,profile,number,'population',center,
            previous,historical,population)
        contexts.publish(root,portable,number,'population',protocol,plan)
        subjects=read_context(portable,plan,protocol,profile,number,'population')
        populations=[collect(plan,profile,root,number,chunk,'population',subjects)
            for chunk in p.GENERATION_CHUNKS['DESKTOP']]
        populations+=wait_receipts(profile,root,number,'population')
        fitness=numeric.publish_numeric(directory/'fitness.json',
            wins.fitness(plan,plan.file_sha256,population,calibration,populations))
        result=numeric.invoke(profile,'update',directory/'update',dict(protocol=common['protocol'],
            warmStart=profile['warmStart'],center=center,directions=profile['directions'][number-1],
            population=population,fitness=fitness,inheritedCalibration=calibration))
        if not result['complete']:return stop(result,number-1)
        next_center=p.subject(directory/'update/center.json');update=p.subject(directory/'update/update.json')
        diagnostic=None
        if panel is not None:
            proof=numeric.invoke(profile,'diagnose',directory/'diagnose',dict(common,center=next_center,publicPanel=panel))
            p.require(proof['complete'],'Diagnostic corruption is an evidence failure, never a movement gate.')
            diagnostic=proof['receipt']
        finished=time.time();durations.append(finished-started)
        storage.publish_json(directory/'receipt.json',dict(kind=p.PREFIX+'-generation-receipt-1',complete=True,
            generation=number,protocolSha256=plan.file_sha256,scopeSha256=plan.scope.scope_sha256,
            warmStartSha256=plan.record['warmStart']['identitySha256'],ecologySha256=plan.record['evaluation']['ecologySha256'],
            inputCenter=center,outputCenter=next_center,directions=profile['directions'][number-1],bank=bank,
            population=population,inheritedCalibration=calibration,controls=controls,populations=populations,
            fitness=fitness,update=update,diagnostic=diagnostic,startedEpoch=started,finishedEpoch=finished,
            elapsedSeconds=finished-started))
        center=next_center;previous=update
        if number==1:storage.retention_record(root,profile['scientificInputDirectories'],'DESKTOP',1,plan.file_sha256)
        if number==2 and not prefix(profile,root,protocol,durations)['passed']:
            return dict(status='stopped',reason='prefix-resource-gate',completedGenerations=2)
    return screen.finish(plan,profile,protocol,root,center,calibration,historical)


def mac(plan,profile,protocol,root):return base.mac(plan,profile,protocol,root,contract=contract())
def failed(profile,root,launch,error):return base.failed(profile,root,launch,error,contract=contract())
def run(protocol,profile,clock,output):return base.run(protocol,profile,clock,output,contract=contract())


def worker_identity(profile_path):
    return dict(kind=p.PREFIX+'-worker-owner-1',pid=os.getpid(),processGroup=os.getpgrp(),
        profile=p.subject(profile_path))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('protocol','profile','clock','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();run(args.protocol,args.profile,args.clock,args.output)
