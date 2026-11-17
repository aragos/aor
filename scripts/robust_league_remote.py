"""Bounded native, numerical and lossless model-transfer tasks on an explicit research host."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import time

import robust_token_campaign as c
import robust_token_worker as native


def admitted(profile_path, request):
    c.require(c.sha(profile_path)==request['profileSha256'], 'The original executing profile changed.')
    profile=c.read(profile_path)
    c.require(profile['host']=='DESKTOP' and os.cpu_count()==16,
        'Scientific numerical work and canonical population archives belong to the Desktop.')
    for path,digest in profile['pins'].items():
        c.require(c.sha(path)==digest,'An original static numerical/native input changed.')
    root=Path(profile['root'])
    c.require(time.time()<request['clock']['deadlineEpoch'], 'The original science deadline has elapsed.')
    clock=root/'clock.json'
    if clock.exists():c.require(clock.read_bytes()==c.canonical(request['clock']),'The original shared clock changed.')
    else:c.write(clock,request['clock'])
    c.require(shutil.disk_usage(root).free>=profile['minimumFreeBytes'] and
        native.census(root)<profile['maximumDataBytes'],'The original numerical storage allowance is exhausted.')
    return profile,root


def numeric(profile_path,request):
    profile,root=admitted(profile_path,request)
    task_id=request['taskId']
    c.require(re.fullmatch('[a-z][a-z0-9-]{0,79}',task_id),'Numerical tasks require safe new identifiers.')
    mode=request['mode'];c.require(mode in ('prepare','population','update','final'),'Unknown numerical mode.')
    directory=root/'numeric-tasks'/task_id
    c.require(not directory.exists(),'Numerical tasks are once-only and cannot resume.')
    directory.mkdir(parents=True)
    output=root/'numerical'/task_id
    body=request['request']
    if 'outcomesValue' in request:
        c.require(mode=='update','Only an update accepts the verified compact outcome matrix.')
        c.write(directory/'outcomes.json',request['outcomesValue'])
        body=dict(body,outcomes=dict(path=str(directory/'outcomes.json'),sha256=c.sha(directory/'outcomes.json')))
    source=directory/'request.json';c.write(source,body)
    command=[profile['numericPython'],'-E','-s','-B',profile['numericScript'],
        '--mode',mode,'--request',str(source),'--output',str(output)]
    environment=os.environ.copy()
    for name in ('OMP_NUM_THREADS','MKL_NUM_THREADS','OPENBLAS_NUM_THREADS','VECLIB_MAXIMUM_THREADS'):
        environment[name]='1'
    process=None;previous={};cancelled=False;peak=0
    def stop(*_):
        nonlocal cancelled
        cancelled=True
        if process is not None and process.poll() is None:os.killpg(process.pid,signal.SIGTERM)
    for event in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP):previous[event]=signal.signal(event,stop)
    try:
        with (directory/'stdout.json').open('xb') as log,(directory/'stderr.log').open('xb') as errors:
            process=subprocess.Popen(command,env=environment,stdin=subprocess.DEVNULL,stdout=log,
                stderr=errors,start_new_session=True,close_fds=True)
            c.write(directory/'owner.json',dict(kind='robust-league-numeric-owner-1',workerPid=os.getpid(),
                nativePid=process.pid,nativeProcessGroup=process.pid,command=command,
                deadlineEpoch=request['clock']['deadlineEpoch']))
            while process.poll() is None:
                peak=max(peak,native.group_rss(process.pid))
                c.require(not cancelled and time.time()<request['clock']['deadlineEpoch'],
                    'The numerical task was interrupted or reached its original deadline.')
                c.require(peak<=profile['maximumRssBytes'] and
                    shutil.disk_usage(root).free>=profile['minimumFreeBytes'] and
                    native.census(root)<profile['maximumDataBytes'],
                    'The numerical task exceeded its original memory or storage allowance.')
                time.sleep(.5)
        c.require(process.returncode==0,'The original numerical operation failed; no retry is allowed.')
        subject=c.read(directory/'stdout.json')
        c.require(subject['path']=='receipt.json' and subject['sha256']==c.sha(output/'receipt.json'),
            'The numerical subprocess must publish its exact original complete receipt.')
        receipt=c.read(output/'receipt.json');c.require(receipt['complete'] is True,'A numerical receipt is incomplete.')
        for name,digest in receipt['files'].items():
            c.require(c.sha(output/name)==digest,'A newly materialized numerical file changed before admission.')
        metadata={}
        for path in sorted(output.rglob('*.json')):
            if path.name in ('receipt.json','population.json','center.json','bundle.json'):
                raw=path.read_bytes();metadata[str(path.relative_to(output))]=dict(
                    sha256=hashlib.sha256(raw).hexdigest(),base64=base64.b64encode(raw).decode('ascii'))
        result=dict(kind='robust-league-numeric-result-1',complete=True,mode=mode,taskId=task_id,
            output=str(output),receipt=dict(path=str(output/'receipt.json'),sha256=c.sha(output/'receipt.json')),
            metadata=metadata,peakRssBytes=peak,newGames=0)
        c.write(directory/'transport.json',{key:value for key,value in result.items() if key!='metadata'})
        return result
    except BaseException as error:
        stop()
        if process is not None and process.poll() is None:
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
        c.write(directory/'failed.json',dict(reason=type(error).__name__+': '+str(error),
            complete=False,processExited=process is None or process.poll() is not None))
        raise
    finally:
        for event,handler in previous.items():signal.signal(event,handler)


def archive(profile_path,request):
    profile,root=admitted(profile_path,request)
    name=request['taskId'];c.require(re.fullmatch('[a-z][a-z0-9-]{0,79}',name),'Use a safe archive identifier.')
    base=Path(request['bundleRoot']).resolve()
    c.require(base.is_relative_to(root/'numerical'),'Only this experiment canonical model directory may be copied.')
    files={}
    for subject in request['bundles']:
        owner=Path(subject['path']).resolve()
        c.require(owner.is_relative_to(base) and c.sha(owner)==subject['sha256'],
            'Every transferred member must retain its original declared bundle identity.')
        files[str(owner.relative_to(base))]=subject['sha256']
        for head in c.read(owner)['heads'].values():
            relative=PurePosixPath(head['path'])
            c.require(not relative.is_absolute() and '..' not in relative.parts,'Actor subjects must remain confined.')
            path=owner.parent/head['path'];c.require(c.sha(path)==head['sha256'],'A transferred actor head changed.')
            files[str(path.relative_to(base))]=head['sha256']
    directory=root/'transfers';directory.mkdir(exist_ok=True)
    target=directory/(name+'.tar.gz')
    c.require(not target.exists(),'An original model archive cannot be overwritten.')
    c.write(directory/(name+'-owner.json'),dict(kind='robust-league-archive-owner-1',workerPid=os.getpid(),
        deadlineEpoch=request['clock']['deadlineEpoch']))
    def expired(*_):raise TimeoutError('The original archive deadline elapsed.')
    previous=signal.signal(signal.SIGALRM,expired)
    signal.setitimer(signal.ITIMER_REAL,max(.01,request['clock']['deadlineEpoch']-time.time()))
    try:
        with tarfile.open(target,'x:gz',compresslevel=1) as output:
            for relative in sorted(files):output.add(base/relative,arcname=relative,recursive=False)
    finally:
        signal.setitimer(signal.ITIMER_REAL,0);signal.signal(signal.SIGALRM,previous)
    result=dict(kind='robust-league-model-transfer-1',complete=True,
        archive=dict(path=str(target),sha256=c.sha(target)),files=files,
        expandedBytes=sum((base/path).stat().st_size for path in files),bytes=target.stat().st_size,
        canonicalRoot=str(base),newGames=0,newMaterializations=0)
    c.write(directory/(name+'.json'),result)
    return result


def cancel(profile_path):
    result=native.cancel_active(profile_path)
    profile=c.read(profile_path)
    for path in (Path(profile['root'])/'numeric-tasks').glob('*/owner.json'):
        row=c.read(path);pid=row['nativePid']
        answer=subprocess.run(['/bin/ps','-p',str(pid),'-o','pgid=,command='],capture_output=True,text=True)
        if answer.returncode or not answer.stdout.strip():continue
        parts=answer.stdout.strip().split(None,1)
        c.require(int(parts[0])==pid and profile['numericScript'] in parts[1] and
            str(path.parent/'request.json') in parts[1],'A numerical PID has a different live owner; preserve it.')
        os.killpg(pid,signal.SIGTERM);result.append(pid)
    for path in (Path(profile['root'])/'transfers').glob('*-owner.json'):
        pid=c.read(path)['workerPid']
        answer=subprocess.run(['/bin/ps','-p',str(pid),'-o','command='],capture_output=True,text=True)
        if answer.returncode or not answer.stdout.strip():continue
        c.require(profile['workerScript'] in answer.stdout and '--archive' in answer.stdout,
            'A recorded archive PID has a different owner; preserve it.')
        os.kill(pid,signal.SIGTERM);result.append(pid)
    return result


def inspect(profile_path):
    value=native.inspect(profile_path);profile=c.read(profile_path);root=Path(profile['root'])
    groups=set(value['ownedNativeGroups']);pids=set(value['ownedWorkerPids'])
    for path in (root/'numeric-tasks').glob('*/owner.json'):
        owner=c.read(path);groups.add(owner['nativeProcessGroup']);pids.add(owner['workerPid'])
    for path in (root/'transfers').glob('*-owner.json'):pids.add(c.read(path)['workerPid'])
    rows=subprocess.check_output(['/bin/ps','-axo','pid=,pgid=,stat=,comm='],text=True).splitlines()
    remaining=[]
    for line in rows:
        parts=line.split(None,3)
        if len(parts)>=3 and (int(parts[0]) in pids or int(parts[1]) in groups):
            remaining.append(dict(pid=int(parts[0]),group=int(parts[1]),state=parts[2]))
    value.update(kind='robust-league-host-metadata-1',ownedNativeGroups=sorted(groups),
        ownedWorkerPids=sorted(pids),remainingProcesses=remaining,allOwnedProcessesExited=not remaining)
    proof_path=root/'numerical-physical-audit.json'
    if proof_path.exists():
        proof=c.read(proof_path)
        for path,digest in proof['artifactPins'].items():
            c.require(c.sha(path)==digest,'An originally audited numerical input or output changed.')
        for receipt in proof['receipts']:
            c.require(c.sha(receipt['path'])==receipt['sha256'],'An original numerical receipt changed.')
        value.update(originalNumericalAuditSha256=c.sha(proof_path),
            originalNumericalArtifactsRehashed=len(proof['artifactPins']))
    return value


def verify_numerical(profile_path,request):
    """Read existing numerical receipts and physical hashes; never import numerical code."""
    c.require(c.sha(profile_path)==request['profileSha256'],'The original host profile changed.')
    profile=c.read(profile_path);root=Path(profile['root'])
    c.require(time.time()<=request['clock']['auditDeadlineEpoch'],
        'The original numerical audit allowance cannot renew or restart.')
    c.require((root/'clock.json').read_bytes()==c.canonical(request['clock']),
        'The original numerical science clock changed.')
    pins={};receipts=[]
    for path in sorted((root/'numerical').glob('*/receipt.json')):
        record=c.read(path);c.require(record['complete'] is True,'Only complete original numerical steps can be audited.')
        receipts.append(dict(path=str(path),sha256=c.sha(path)))
        for relative,digest in record['files'].items():
            owner=path.parent/relative
            c.require(c.sha(owner)==digest,'A canonical numerical output changed after its original completion.')
            previous=pins.setdefault(str(owner),digest)
            c.require(previous==digest,'A retained numerical file cannot have two raw identities.')
        for owner,digest in record['inputPins'].items():
            c.require(c.sha(owner)==digest,'A numerical predecessor changed after its original use.')
            previous=pins.setdefault(owner,digest);c.require(previous==digest,'A numerical input identity conflicts.')
    result=dict(kind='robust-league-numerical-physical-audit-1',passed=True,complete=True,
        profileSha256=request['profileSha256'],clockSha256=c.sha(root/'clock.json'),
        receipts=receipts,artifactPins=pins,newGames=0,newFits=0,modelForwards=0,checkedEpoch=time.time())
    c.write(root/'numerical-physical-audit.json',result)
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile',type=Path,required=True)
    modes=parser.add_mutually_exclusive_group()
    modes.add_argument('--numeric',action='store_true');modes.add_argument('--archive',action='store_true')
    modes.add_argument('--cancel-active',action='store_true');modes.add_argument('--inspect',action='store_true')
    modes.add_argument('--verify-numerical',action='store_true')
    args=parser.parse_args()
    if args.cancel_active:result={'stoppedGroups':cancel(args.profile)}
    elif args.inspect:result=inspect(args.profile)
    else:
        request=json.load(sys.stdin)
        result=(numeric if args.numeric else archive if args.archive else
            verify_numerical if args.verify_numerical else native.execute)(args.profile,request)
    print(json.dumps(result,separators=(',',':'),allow_nan=False))
