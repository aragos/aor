"""Constructed archive-to-win-update proof; never a scientific campaign.

Toy models/projection come from the numerical owner's fixture. Parent admission,
old V2 gap witnesses and native game attestations are explicitly invented. Actual
request/receipt, archive, terminal, fitness, update and materialization owners run.
"""
import argparse
import contextlib
import json
import math
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
from unittest.mock import patch

import torch
import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_es_numeric as numeric
import coordinated_es_plan as legacy_plan
import coordinated_es_storage as storage
import coordinated_win_numeric as n
import coordinated_win_numeric_stage as stage
import coordinated_win_plan as p
import coordinated_win_storage as win
from test_coordinated_es_plan import protocol as legacy_protocol
from test_coordinated_win_numeric import fixture as numeric_fixture, source_owners
from test_coordinated_win_storage import terminal


def put(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as stream:stream.write(es.canonical(value))
    return legacy_plan.subject(path)


def outcome(index,plus):
    """Wins favor PLUS while old rank-weighted reward favors MINUS; no hindsight tuning."""
    if plus:
        totals=[4,3,2,1] if index%4==0 else [None if index%4==1 else 0,3,2,1]
    else:
        totals=[4,4,4,1] if index%2==0 else [3,4,2,1]
    seat=index%4
    return terminal([totals[(own-seat)%4] for own in range(4)])


def archives(root,plan,protocol_sha,population_subject,calibration_subject,*,subjects=None):
    """Real eight lossless archives; native attestations are intentionally constructed.

    This is not native collect/replay admission. Every game byte is subsequently
    hashed and decoded by the real shared archive and authoritative score owners.
    """
    root=Path(root);population=p.read(population_subject['path']);continuation=isinstance(plan,p.Plan)
    prefix=p.PREFIX if continuation else legacy_plan.PREFIX
    number=population['step' if continuation else 'generation'];receipts=[]
    record=plan.record
    original=plan.scope.original_bundle_sha256 if continuation else record['original']['bundleSha256']
    for chunk in range(8):
        native=root/f'chunk-{chunk}'/'native';native.mkdir(parents=True)
        if continuation:
            request=plan.generation_source(number,chunk,'POPULATION',subjects[p.owner(chunk)])
        else:
            request=dict(constructedOnly=True,protocolSha256=protocol_sha,chunk=chunk)
        source=put(native/'source.json',request)
        common=dict(signature=es.digest([prefix,chunk,source['sha256']]),sourceSha256=source['sha256'],
            scopeSha256=plan.scope.scope_sha256 if continuation else record['original']['scopeSha256'],
            scienceSha256=es.digest([prefix,protocol_sha,population_subject['sha256']]))
        if continuation:
            common.update(warmStartSha256=record['warmStart']['identitySha256'],
                inheritedCalibrationSha256=calibration_subject['sha256'],
                ecologySha256=record['evaluation']['ecologySha256'])
        for index in p.indices(chunk):
            for member in range(32):
                put(native/storage.game_name(index,member),dict(kind=prefix+'-game-1',generation=number,
                    globalIndex=index,seed=plan.bank(number)['worldBank']['seeds'][index],seat=index%4,
                    role=storage.role(member),bundleSha256=population['members'][member]['bundleSha256'],
                    originalBundleSha256=original,terminal=outcome(index,member%2==0),canonical=None,**common))
        selected=subjects[p.owner(chunk)] if continuation else dict(population=population_subject,calibration=calibration_subject)
        receipt=dict(kind=prefix+'-chunk-receipt-1',phase='population',generation=number,chunk=chunk,
            host=p.owner(chunk),globalStart=chunk*32,count=32,protocolSha256=protocol_sha,subjects=selected,
            attemptedGames=1024,completedGames=1024,constructedNativeAttestation=True,
            games=[storage.game_name(index,member) for index in p.indices(chunk) for member in range(32)],
            files=storage.inventory(native),**common)
        storage.archive_collection(native,native.parent/'evidence.tar.gz',receipt)
        receipts.append(legacy_plan.subject(native.parent/'evidence.tar.gz.json'))
    return receipts


def legacy_positives(root,members):
    """Both old version gates traverse actual archives; no old numerical fit is repeated."""
    rows=[]
    for version in (1,2):
        folder=Path(root)/f'v{version}';record=legacy_protocol();record['calibration']=legacy_plan.calibration_recipe(version)
        plan=legacy_plan.Plan.from_record(record);protocol_sha=es.digest(record)
        seal=put(folder/'calibration.json',dict(kind=f'coordinated-es-calibration-{version}',passed=True,complete=True,
            protocolSha256=protocol_sha,semanticFitnessDecoded=False,calibrationAttempts=1))
        population=put(folder/'population.json',dict(kind='coordinated-es-population-1',generation=1,
            protocolSha256=protocol_sha,calibrationSha256=seal['sha256'],
            worldBankSha256=es.digest(plan.bank(1)['worldBank']),worldIds=list(range(256)),members=members))
        receipts=archives(folder/'archives',plan,protocol_sha,population,seal)
        result=storage.fitness(plan,protocol_sha,population,seal,receipts)
        result_subject=put(folder/'fitness.json',result)
        plus=math.fsum(result['members'][0]['rewards'])/256
        minus=math.fsum(result['members'][1]['rewards'])/256
        es.require(plus<minus,'The invented rank-weighted legacy fitness must favor the opposite sign.')
        rows.append(dict(version=version,fitness=result_subject,archives=receipts,
            meanPlusReward=plus,meanMinusReward=minus,constructedCalibrationAdmission=True))
    return rows


def execute(root,*,numerical=None):
    """One retained proof. A supplied actual descriptor enables ordinary CLI child execution."""
    root=Path(root).resolve();root.mkdir(parents=True,exist_ok=False);torch.set_num_threads(1)
    started=time.time();deadline=started+240
    request,public,plan=numeric_fixture(root)
    record=plan.record
    proofs={host:put(root/f'origin-{host}.json',dict(constructedOriginProof=True,host=host)) for host in p.HOSTS}
    record['warmStart']['proofSha256']={host:subject['sha256'] for host,subject in proofs.items()}
    plan=p.Plan.from_record(record)
    protocol_path=root/'continuation-protocol.json';protocol_path.write_bytes(plan.wire+b'\n')
    request['protocol']=legacy_plan.subject(protocol_path)
    descriptor=legacy_plan.subject(numerical) if numerical else put(root/'environment-fixture.json',dict(constructed=True))
    profile=dict(python=dict(executable=sys.executable),numerical=descriptor,numericalSourceOwners=source_owners(),
        _clock=SimpleNamespace(deadline=deadline))
    stages=[]
    def child(_,command,log):
        with Path(log).open('x') as output:
            if numerical:
                result=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT,timeout=deadline-time.time())
                es.require(result.returncode==0,'Retain the failed constructed numerical child log.')
            else:
                with patch.object(numeric.environment,'verify'),contextlib.redirect_stdout(output):
                    n.main(command[5:])
    # Preparation has the same actual request owner; the campaign stage intentionally
    # excludes this pre-game verb. No model/source expectation is substituted.
    prepare_request=put(root/'prepare-request.json',dict(kind='coordinated-win-prepare-request-1',
        sourceOwners=profile['numericalSourceOwners'],numerical=descriptor,deadlineEpoch=deadline,**request))
    command=[sys.executable,'-E','-s','-B',n.__file__,'prepare','--request',prepare_request['path'],'--output',str(root/'prepared')]
    child(profile,command,root/'prepare.log')
    stage.verify_complete(root/'prepared',prepare_request['path'],'prepare')
    common={key:request[key] for key in ('protocol','originalBundle','projection','warmStart')}
    inherited=legacy_plan.subject(root/'prepared/calibration/inherited-calibration.json')
    common['inheritedCalibration']=inherited
    first_center=legacy_plan.subject(root/'prepared/initial-center.json')
    directions=legacy_plan.subject(root/'prepared/directions/generation-01.json')
    def invoke(verb,name,fields):
        with patch.object(stage,'run_child',side_effect=child):
            result=stage.invoke(profile,verb,root/name,fields)
        es.require(result['complete'],'The constructed numeric transition must complete.')
        stages.append(dict(verb=verb,receipt=result['receipt'],request=legacy_plan.subject(root/(name+'-request.json'))))
        return result
    first=dict(common,center=first_center,directions=directions,
        worldBank=put(root/'bank1.json',plan.bank(1)['worldBank']))
    invoke('materialize','materialize1',first)
    population=stage.population_publication(root/'materialize1',legacy_plan.subject(root/'materialize1/population.json'),
        inherited,root/'portable1')
    portable_calibration=legacy_plan.subject(root/'portable1/calibration/inherited-calibration.json')
    arena=put(root/'invented-arena.json',dict(constructedArena=True));historical=put(root/'invented-historical.json',dict(constructedHistory=True))
    subjects={host:dict(protocol=request['protocol'],arena=arena,originalBundle=request['originalBundle'],
        projection=request['projection'],warmStart=request['warmStart'],originProof=proofs[host],center=first_center,
        numericalPreparation=legacy_plan.subject(root/'prepared/finished.json'),
        directions=directions,population=population,inheritedCalibration=portable_calibration,
        previousUpdate=None,historical=historical) for host in p.HOSTS}
    receipts=archives(root/'archives',plan,request['protocol']['sha256'],population,portable_calibration,subjects=subjects)
    result=win.fitness(plan,request['protocol']['sha256'],population,portable_calibration,receipts)
    fitness=put(root/'fitness.json',result)
    update_fields=dict(protocol=request['protocol'],warmStart=request['warmStart'],center=first_center,
        directions=directions,population=population,fitness=fitness,inheritedCalibration=portable_calibration)
    invoke('update','update1',update_fields)
    next_center=legacy_plan.subject(root/'update1/center.json');update=legacy_plan.subject(root/'update1/update.json')
    invoke('diagnose','diagnose1',dict(common,center=next_center,publicPanel=public))
    invoke('center','center1',dict(common,center=next_center))
    invoke('materialize','materialize2',dict(common,center=next_center,
        directions=legacy_plan.subject(root/'prepared/directions/generation-02.json'),
        worldBank=put(root/'bank2.json',plan.bank(2)['worldBank'])))
    second=p.read(root/'materialize2/population.json')
    es.require(second['centerSha256']==next_center['sha256'] and second['inheritedCalibrationSha256']==inherited['sha256'],
        'The next materialization consumes the produced center and unchanged inherited seal.')
    second_sources={host:plan.generation_source(2,0 if host=='DESKTOP' else 5,'POPULATION',dict(subjects[host],
        center=next_center,previousUpdate=update,directions=legacy_plan.subject(root/'prepared/directions/generation-02.json'),
        population=legacy_plan.subject(root/'materialize2/population.json'))) for host in p.HOSTS}
    contexts=put(root/'next-source-contexts.json',second_sources)
    means=[math.fsum(row['winShares'])/256 for row in result['members']]
    es.require(means[0]>means[1],'The fixed invented wins must favor PLUS.')
    legacy=legacy_positives(root/'legacy-positive',p.read(population['path'])['members'])
    es.require(time.time()<deadline,'The single bounded constructed proof must finish without clock renewal.')
    proof=dict(kind='coordinated-win-constructed-joined-proof-1',passed=True,constructedOnly=True,
        protocol=request['protocol'],fitness=fitness,archives=receipts,numericalStages=stages,
        inheritedCalibration=inherited,firstCenter=first_center,producedCenter=next_center,update=update,
        nextPopulation=legacy_plan.subject(root/'materialize2/population.json'),nextSourceContexts=contexts,
        meanPlusWin=means[0],meanMinusWin=means[1],legacyPositive=legacy,
        completeMutantRecords=8192,archivesRead=8,actualNumericUpdates=1,actualMaterializations=2,
        nativeGamesExecuted=0,nativeReplaysExecuted=0,scientificUpdates=0,scientificOriginalAdmission=False,
        invented=['parent admission','original calibration witnesses','native execution/replay attestations','all terminal scores'],
        environmentVerificationMocked=numerical is None,externalChildMocked=numerical is None,
        actualArchiveAndTerminalOwners=True,actualFitnessAndNumericOwners=True,
        fullCampaignOrFinalAdmissionProved=False,startedEpoch=started,finishedEpoch=time.time(),deadlineEpoch=deadline,
        fixtureAndImportedSourcePins={str(Path(module.__file__).resolve()):legacy_plan.file_sha(module.__file__)
            for module in tuple(sys.modules.values()) if getattr(module,'__file__',None) and
            Path(module.__file__).resolve().parent==Path(__file__).resolve().parent and
            Path(module.__file__).suffix=='.py'},
        sourceOwners={str(Path(module.__file__).resolve()):legacy_plan.file_sha(module.__file__)
            for module in (sys.modules[__name__],win,storage,n,stage,c,es,p)})
    return put(root/'joined-proof.json',proof)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True)
    parser.add_argument('--numerical');args=parser.parse_args()
    print(json.dumps(execute(args.output,numerical=args.numerical),sort_keys=True))
