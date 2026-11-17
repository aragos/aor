"""Zero-game admission of one frozen portable league payload on its actual host."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

import robust_token_campaign as c
import robust_token_worker as worker
import robust_league_campaign as league


def install(root,host,python,java,numeric_python=None,jvm_options=None):
    root=Path(root).resolve();python=Path(python);java=Path(java)
    c.require(host in c.HOSTS and os.cpu_count()==(16 if host=='DESKTOP' else 10),
        'The league payload must be admitted on its actual declared machine capacity.')
    c.require(not (root/'tasks').exists() and not (root/'clock.json').exists() and
        not (root/'profile.json').exists(),'An actual scientific host cannot be reinitialized or resumed.')
    c.require(shutil.disk_usage(root).free>=16*1024**3,'The host must retain16GiB free.')
    protocol=root/'protocol.json';plan=league.validate(c.read(protocol))
    asset=root/'inputs/inference/inference-asset.json';nomination=root/'inputs/nomination/nomination.json'
    arena=root/'inputs/homogeneous-arena.json'
    c.require(c.sha(asset)==plan['baseInferenceAssetSha256'] and
        c.sha(nomination)==plan['nominationSha256'] and c.sha(arena)==plan['arenaSha256'] and
        c.sha(root/'runtime/lib/training.jar')==plan['nativeJarSha256'],
        'All actual frozen model and native identities must match the prospective protocol.')
    for name,digest in c.read(root/'payload-manifest.json')['files'].items():
        c.require(c.sha(root/name)==digest,'A copied portable payload member changed.')
    subject=lambda path:dict(path=str(path),sha256=c.sha(path))
    options=['-XX:ActiveProcessorCount=1','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1'] if jvm_options is None else jvm_options
    worker.java_options({'jvmOptions':options})
    proofs=[]
    for candidate_only in (False,True):
        name='candidate-only' if candidate_only else 'paired'
        source=dict(kind='robust-token-chunk-1',protocolSha256=c.sha(protocol),
            inferenceAsset=subject(asset),nomination=subject(nomination),arena=subject(arena),
            selector=None,opponentStyle='BALANCED',seeds=plan['banks']['generation-1']['seeds'],
            globalStart=0,count=64,replayIndices=[0,127],candidateOnly=candidate_only)
        path=root/('preflight-source-'+name+'.json');c.write(path,source)
        output=root/('host-preflight-'+name)
        command=[str(java),'-Xmx6144m',*options,'-cp',str(root/'runtime/lib/*'),
            'dev.aragos.aor.training.TrainingMainKt','--mode','robust-token-prepare',
            '--source',str(path),'--output',str(output)]
        with (root/('preflight-'+name+'.log')).open('xb') as log:
            subprocess.run(command,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True)
        prepared=c.read(output/'prepared.json')
        c.require(prepared['passed'] is True and prepared['newGames']==prepared['modelForwards']==0 and
            prepared['sourceSha256']==c.sha(path) and prepared['candidateOnly'] is candidate_only and
            prepared['roles']==(['CANDIDATE'] if candidate_only else ['CANDIDATE','CONTROL']),
            'Both actual typed native modes must admit the exact byte source without executing any game or inference.')
        proofs.append(dict(mode=name,prepared=subject(output/'prepared.json'),
            scienceIdentity=prepared['scienceIdentity'],scienceSha256=prepared['scienceSha256']))
    pins={str(path):c.sha(path) for path in sorted(root.rglob('*')) if path.is_file()}
    for path in (python,java,java.resolve().parent.parent/'lib/server/libjvm.dylib',
            java.resolve().parent.parent/'lib/server/libjvm.so'):
        if path.is_file():pins[str(path)]=c.sha(path)
    profile=dict(kind='robust-token-host-1',host=host,root=str(root),python=str(python),java=str(java),
        classpath=str(root/'runtime/lib/*'),machineCpus=os.cpu_count(),workers=16 if host=='DESKTOP' else 10,
        heapMiB=6144,minimumFreeBytes=16*1024**3,maximumDataBytes=(64 if host=='DESKTOP' else 4)*1024**3,
        maximumRssBytes=12*1024**3,workerScript=str(root/'runtime/bin/robust_league_remote.py'),
        actualProfile=str(root/'profile.json'),inferenceAsset=subject(asset),nomination=subject(nomination),
        arena=subject(arena),nativeJar=subject(root/'runtime/lib/training.jar'),jvmOptions=options,pins=pins)
    if host=='DESKTOP':
        c.require(numeric_python is not None,'The authoritative Desktop numerical interpreter is required.')
        numeric_python=Path(numeric_python);pins[str(numeric_python)]=c.sha(numeric_python)
        profile.update(sshTarget='aragos@192.168.86.44',numericPython=str(numeric_python),
            numericScript=str(root/'runtime/numeric/robust_league_numeric.py'),
            protocol=subject(protocol),seedSchedule=subject(root/'inputs/schedule.json'))
    c.write(root/'profile.json',profile)
    c.write(root/'package-manifest.json',dict(kind='robust-league-actual-host-manifest-1',host=host,
        files={**pins,str(root/'profile.json'):c.sha(root/'profile.json')}))
    ready=dict(kind='robust-league-actual-host-preflight-1',passed=True,host=host,actualRoot=str(root),
        profile=subject(root/'profile.json'),manifest=subject(root/'package-manifest.json'),proofs=proofs,
        inputPinsRehashed=len(pins),newGames=0,newFits=0,numericalDraws=0,modelForwards=0,
        clockCreated=False,launchAuthorized=False,completedEpoch=time.time())
    c.write(root/'readiness.json',ready)
    return dict(host=host,passed=True,readiness=subject(root/'readiness.json'),profile=subject(root/'profile.json'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('root','python','java'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--host',choices=c.HOSTS,required=True)
    parser.add_argument('--numeric-python',type=Path)
    parser.add_argument('--jvm-options-json')
    args=parser.parse_args()
    options=json.loads(args.jvm_options_json) if args.jvm_options_json else None
    print(json.dumps(install(args.root,args.host,args.python,args.java,args.numeric_python,options),sort_keys=True))
