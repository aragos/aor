"""Admit a copied compact payload on its actual host without running games or model forwards."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

import robust_token_campaign as c


def install(root, host, python, java):
    root=Path(root).resolve();python=Path(python);java=Path(java)
    c.require(host in c.HOSTS and os.cpu_count()==(16 if host=='DESKTOP' else 10),
        'The payload must be finalized on its declared physical host capacity.')
    c.require(not (root/'tasks').exists() and not (root/'clock.json').exists() and
        not (root/'profile.json').exists(), 'Host admission is new-only and cannot reopen a scientific attempt.')
    c.require(shutil.disk_usage(root).free>=16*1024**3,'Host admission must retain16GiB free.')
    protocol=root/'protocol.json';plan=c.validate(c.read(protocol))
    asset=root/'inputs/inference/inference-asset.json';nomination=root/'inputs/nomination/nomination.json'
    arena=root/'inputs/homogeneous-arena.json'
    c.require(c.sha(asset)==plan['inferenceAssetSha256'] and c.sha(nomination)==plan['nominationSha256'] and
        c.sha(arena)==plan['arenaSha256'] and c.sha(root/'runtime/lib/training.jar')==plan['nativeJarSha256'],
        'Actual host inputs must match the exact prospective scientific and runtime identities.')
    for filename,item in c.read(root/'payload-manifest.json')['files'].items():
        c.require(c.sha(root/filename)==item,'The copied payload changed before actual host admission.')
    subject=lambda path:dict(path=str(path),sha256=c.sha(path))
    source=dict(kind='robust-token-chunk-1',protocolSha256=c.sha(protocol),
        inferenceAsset=subject(asset),nomination=subject(nomination),arena=subject(arena),
        selector=plan['caseConfigs'][0]['selector'],opponentStyle='BALANCED',
        seeds=plan['banks']['development']['seeds'],globalStart=0,count=64,
        replayIndices=plan['banks']['development']['replayIndices'])
    source_path=root/'preflight-source.json';c.write(source_path,source)
    command=[str(java),'-Xmx6144m','-XX:ActiveProcessorCount=1','-XX:ParallelGCThreads=1',
        '-XX:ConcGCThreads=1','-cp',str(root/'runtime/lib/*'),
        'dev.aragos.aor.training.TrainingMainKt','--mode','robust-token-prepare',
        '--source',str(source_path),'--output',str(root/'host-preflight')]
    with (root/'host-preflight.log').open('xb') as log:
        subprocess.run(command,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,
            timeout=120,check=True)
    prepared=c.read(root/'host-preflight/prepared.json')
    c.require(prepared['passed'] is True and prepared['newGames']==prepared['modelForwards']==0 and
        prepared['sourceSha256']==c.sha(source_path),
        'Actual zero-game native admission must bind the exact source without executing inference.')
    pins={str(path):c.sha(path) for path in sorted(root.rglob('*')) if path.is_file()}
    # A concise native/JDK executable closure replaces copies of entire unrelated JDK trees.
    for path in (python,java,java.resolve().parent.parent/'lib/server/libjvm.dylib',
            java.resolve().parent.parent/'lib/server/libjvm.so'):
        if path.is_file():pins[str(path)]=c.sha(path)
    profile=dict(kind='robust-token-host-1',host=host,root=str(root),python=str(python),java=str(java),
        classpath=str(root/'runtime/lib/*'),machineCpus=os.cpu_count(),workers=14 if host=='DESKTOP' else 8,
        heapMiB=6144,minimumFreeBytes=16*1024**3,maximumDataBytes=(64 if host=='DESKTOP' else 4)*1024**3,
        maximumRssBytes=12*1024**3,workerScript=str(root/'runtime/bin/robust_token_worker.py'),
        actualProfile=str(root/'profile.json'),inferenceAsset=subject(asset),nomination=subject(nomination),
        arena=subject(arena),nativeJar=subject(root/'runtime/lib/training.jar'),pins=pins)
    if host=='DESKTOP':profile['sshTarget']='aragos@192.168.86.44'
    c.write(root/'profile.json',profile)
    manifest=dict(kind='robust-token-actual-host-manifest-1',host=host,
        files={**pins,str(root/'profile.json'):c.sha(root/'profile.json')})
    c.write(root/'package-manifest.json',manifest)
    ready=dict(kind='robust-token-actual-host-preflight-1',passed=True,host=host,actualRoot=str(root),
        profile=subject(root/'profile.json'),manifest=subject(root/'package-manifest.json'),
        prepared=subject(root/'host-preflight/prepared.json'),scienceSha256=prepared['scienceSha256'],
        scienceIdentity=prepared['scienceIdentity'],source=subject(source_path),
        protocolSha256=c.sha(protocol),nativeJarSha256=c.sha(root/'runtime/lib/training.jar'),
        inferenceAssetSha256=c.sha(asset),nominationSha256=c.sha(nomination),arenaSha256=c.sha(arena),
        inputPinsRehashed=len(pins),newGames=0,modelForwards=0,clockCreated=False,
        scienceLaunchAuthorized=False,completedEpoch=time.time())
    c.write(root/'readiness.json',ready)
    return dict(host=host,passed=True,readiness=subject(root/'readiness.json'),
        profile=subject(root/'profile.json'),scienceSha256=prepared['scienceSha256'],inputPins=len(pins))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--host',choices=c.HOSTS,required=True)
    parser.add_argument('--python',type=Path,required=True)
    parser.add_argument('--java',type=Path,required=True)
    args=parser.parse_args();print(json.dumps(install(args.root,args.host,args.python,args.java),sort_keys=True))
