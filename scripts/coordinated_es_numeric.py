"""One immutable numerical request, never a campaign loop or simulator.

Numerical outputs are canonical JSON without LF. A request's protocol subject is its
raw file SHA (including the protocol's LF). G1 population.json is a portable descriptor:
the campaign copies it beside the unchanged materialize/members tree before execution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import time

import coordinated_es_math as es
import coordinated_es_calibration as cal
import coordinated_es_projection as projection
import purchase_ecology_environment as environment

COMMON=('kind','sourceOwners','numerical','deadlineEpoch')
FIELDS={
    'prepare':('scopeSha256','originalBundle','projectionSeed','directionSeeds'),
    'materialize':('protocol','originalBundle','projection','center','directions','worldBank','worldIds','calibration'),
    'calibrate':('protocol','originalBundle','projection','directions','materialization','publicPanel'),
    'update':('protocol','center','directions','population','fitness'),
    'center':('protocol','originalBundle','projection','center','calibration'),
    'diagnose':('protocol','originalBundle','projection','center','calibration','publicPanel'),
}


def raw_sha(raw):return hashlib.sha256(raw).hexdigest()


def relative(name):
    value=PurePosixPath(name)
    es.require(type(name) is str and str(value)==name and not value.is_absolute() and
        name not in ('','.') and '..' not in value.parts and '\\' not in name,
        'Artifacts require normalized confined relative paths.')
    return name


class Inputs:
    """Hash exact bytes before decoding; retain every dependency for final unchanged checks."""
    def __init__(self):self.pins={}

    def raw(self,subject):
        es.exact(subject,('path','sha256'),'Numerical input subject')
        path=Path(subject['path']).absolute()
        es.require(path.is_file() and not path.is_symlink(),'An input must be an ordinary retained file.')
        raw=path.read_bytes();digest=raw_sha(raw)
        es.require(digest==es.sha(subject['sha256'],'Input') and self.pins.get(str(path),digest)==digest,
            'Numerical input bytes changed or disagree with their immutable subject.')
        self.pins[str(path)]=digest
        return raw

    def json(self,subject):return json.loads(self.raw(subject))

    def canonical(self,subject):
        raw=self.raw(subject);value=json.loads(raw)
        es.require(es.canonical(value)==raw,'Numerical records must retain canonical JSON bytes without LF.')
        return value

    def sibling(self,subject,name,digest):
        path=Path(subject['path']).absolute().parent/relative(name)
        es.require(path.resolve().is_relative_to(Path(subject['path']).absolute().parent.resolve()),
            'Bundle and calibration members must remain inside their portable directory.')
        return dict(path=str(path),sha256=digest)

    def unchanged(self):
        for path,digest in tuple(self.pins.items()):self.raw(dict(path=path,sha256=digest))


def source_owners(inputs,values):
    es.require(type(values) is dict and values,'Every numerical owner must be pinned.')
    required=(__file__,es.__file__,cal.__file__,projection.__file__,environment.__file__,
        projection.b.__file__,projection.card_policy.__file__,projection.expansion_mc.__file__,
        projection.token_paired_policy.__file__)
    normalized={str(Path(path).absolute()):digest for path,digest in values.items()}
    es.require(len(normalized)==len(values) and all(str(Path(path).absolute()) in normalized for path in required),
        'The request must pin the actual imported numerical and typed actor owners.')
    for path,digest in normalized.items():inputs.raw(dict(path=path,sha256=digest))


def bundle(inputs,subject):
    value=inputs.json(subject)
    es.exact(value,('kind','heads'),'Four-head bundle')
    es.require(value['kind']=='coordinated-es-bundle-1','A legacy model bundle is not an ES publication.')
    es.exact(value['heads'],[head.value for head in es.HEADS],'Bundle heads')
    actors={}
    for head in es.HEADS:
        row=value['heads'][head.value]
        es.exact(row,('path','sha256','dictionarySha256'),'Bundle head subject')
        raw=inputs.raw(inputs.sibling(subject,row['path'],row['sha256']))
        model=projection.validate_model(json.loads(raw),head)
        es.require(projection.dictionary_sha(model)==row['dictionarySha256'],
            'Every full actor dictionary must match its explicit head subject.')
        actors[head]=raw
    return actors


def originals(inputs,subject):
    return {head:projection.Original(head,raw,raw_sha(raw)) for head,raw in bundle(inputs,subject).items()}


class Output:
    def __init__(self,path):self.path=Path(path).absolute();self.path.mkdir(parents=True,exist_ok=False)

    def raw(self,name,raw):
        path=self.path/relative(name);path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('xb') as target:target.write(raw);target.flush();os.fsync(target.fileno())
        return raw_sha(raw)

    def json(self,name,value):return self.raw(name,es.canonical(value))

    def bundle(self,prefix,actors):
        heads={}
        for head in es.HEADS:
            name=f'heads/{head.value}/policy.json';raw=actors[head]
            digest=self.raw(prefix+name,raw)
            heads[head.value]=dict(path=name,sha256=digest,dictionarySha256=projection.dictionary_sha(json.loads(raw)))
        return self.json(prefix+'bundle.json',dict(kind='coordinated-es-bundle-1',heads=heads))

    def inventory(self):
        return {path.relative_to(self.path).as_posix():raw_sha(path.read_bytes())
            for path in sorted(self.path.rglob('*')) if path.is_file()}


def checked_projection(inputs,request):
    value=projection.Projection(inputs.raw(request['projection']))
    es.require(value.record['originalBundleSha256']==request['originalBundle']['sha256'],
        'The factor map belongs to this exact unchanged original bundle.')
    return value


def checked_center(inputs,request,value):
    center=es.Center.from_record(inputs.canonical(request['center']))
    es.require(center.scope==value.scope,'The center belongs to a different frozen factor map.')
    return center


def checked_directions(inputs,request,value):
    directions=es.DirectionTable.from_record(inputs.canonical(request['directions']))
    es.require(directions.scope==value.scope,'Directions belong to a different frozen factor map.')
    return directions


def protocol_sha(inputs,request):
    inputs.raw(request['protocol'])
    return request['protocol']['sha256']


def calibration_owner(inputs,request):
    """The explicit protocol selects V2; existing V1 request/owner requirements stay exact."""
    configuration=inputs.json(request['protocol']).get('calibration')
    if configuration is None or configuration.get('kind')==cal.VERSION:return cal
    es.require(configuration.get('kind')=='coordinated-es-feasible-gap-calibration-2',
        'An unknown calibration cannot select a legacy fallback.')
    import coordinated_es_calibration_v2 as v2
    es.require(configuration==v2.PROTOCOL_RECIPE,'The entire fixed V2 calibration recipe is required.')
    path=str(Path(v2.__file__).absolute());digest=request.get('sourceOwners',{}).get(path)
    es.require(digest is not None,'The actual selected V2 numerical owner must be pinned.')
    inputs.raw(dict(path=path,sha256=digest))
    return v2


def checked_calibration(inputs,subject,request,value):
    owner=calibration_owner(inputs,request)
    record=inputs.canonical(subject)
    es.exact(record,('kind','passed','complete','protocolSha256','originalBundleSha256','projectionSha256',
        'panelSha256','probePopulationSha256','directionsSha256','prescribedMeanSha256','scales','heads',
        'semanticFitnessDecoded','calibrationAttempts'),'Sealed E/T calibration')
    es.require(record['kind']==getattr(owner,'OUTER_VERSION','coordinated-es-calibration-1') and record['passed'] is True and
        record['complete'] is True and record['protocolSha256']==protocol_sha(inputs,request) and
        record['originalBundleSha256']==request['originalBundle']['sha256'] and
        record['projectionSha256']==value.sha256 and record['semanticFitnessDecoded'] is False and
        record['calibrationAttempts']==1,'Only the exact one-attempt outcome-free calibration can execute.')
    es.exact(record['heads'],('EXPANSION','TOKEN'),'Calibration heads')
    es.exact(record['scales'],('EXPANSION','TOKEN'),'Calibration scales')
    panels={}
    for head in ('EXPANSION','TOKEN'):
        receipt=record['heads'][head]
        panel=cal.GapPanel(inputs.raw(inputs.sibling(subject,f'gap-{head}.json',receipt['panelSha256'])))
        es.require(panel.record['head']==head and owner.calibrate(panel)==receipt and
            receipt['scale']==record['scales'][head] and
            all(receipt['subjects'][name]==record[name] for name in
                ('protocolSha256','projectionSha256','directionsSha256','prescribedMeanSha256')) and
            receipt['subjects']['publicPanelSha256']==record['panelSha256'] and
            receipt['subjects']['originalModelSha256']==value.record['heads'][head]['modelSha256'],
            'A sealed calibration must retain its exact public gaps, model and scalar admission.')
        panels[head]=panel
    return record,panels


def seal_population(materialization,protocol,calibration):
    return dict(materialization,kind='coordinated-es-population-1',protocolSha256=protocol,
        materializationSha256=es.digest(materialization),calibrationSha256=calibration)


def prepare(inputs,request,out,guard):
    original=originals(inputs,request['originalBundle'])
    seeds=request['directionSeeds']
    es.require(type(seeds) is list and len(seeds)==16 and len(set(seeds))==16 and
        request['projectionSeed'] not in seeds,'The projection and16 direction streams must be distinct.')
    value=projection.generate(original,request['scopeSha256'],request['originalBundle']['sha256'],request['projectionSeed'])
    out.raw('projection.json',value.wire)
    out.json('initial-center.json',es.Center(value.scope,0,(0.,)*256).record)
    for generation,seed in enumerate(seeds,1):
        guard();directions,receipt=projection.generate_directions(value.scope,generation,seed)
        out.json(f'directions/generation-{generation:02d}.json',directions.record)
        out.json(f'directions/generation-{generation:02d}-generation.json',receipt)
    return dict(projection='projection.json',center='initial-center.json')


def materialize(inputs,request,out,guard):
    value=checked_projection(inputs,request);original=originals(inputs,request['originalBundle'])
    center=checked_center(inputs,request,value);directions=checked_directions(inputs,request,value)
    bank=inputs.json(request['worldBank'])
    es.exact(bank,('nativeOffset','count','seeds'),'Fixed common world bank')
    es.require(type(bank['nativeOffset']) is int and bank['count']==256 and
        bank['seeds']==[-1000000000+bank['nativeOffset']+index*8191 for index in range(256)] and
        request['worldIds']==list(range(256)),'All256 original native world positions must remain bound.')
    es.require(es.digest(bank)==request['worldBank']['sha256'],'World bank bytes must be canonical JSON without LF.')
    members=[]
    for row in es.mutants(center,directions):
        guard();name=f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/"
        digest=out.bundle(name,projection.materialize(value,original,row['coordinates']))
        members.append(dict(direction=row['direction'],sign=row['sign'],path=name+'bundle.json',
            coordinatesSha256=es.digest(row['coordinates']),bundleSha256=digest))
    record=dict(kind='coordinated-es-materialization-1',scope=value.scope.record,generation=directions.generation,
        centerSha256=es.digest(center.record),directionsSha256=es.digest(directions.record),
        worldBankSha256=request['worldBank']['sha256'],worldIds=request['worldIds'],members=members)
    out.json('materialization.json',record);outputs=dict(materialization='materialization.json')
    if directions.generation==1:
        probe=es.prescribed_mean_probe(directions);out.json('prescribed-mean.json',probe)
        out.bundle('mean-probe/',projection.materialize(value,original,probe['center']['coordinates']))
        outputs['prescribedMean']='prescribed-mean.json'
    if request['calibration'] is not None:
        checked_calibration(inputs,request['calibration'],request,value)
        out.json('population.json',seal_population(record,protocol_sha(inputs,request),request['calibration']['sha256']))
        outputs['population']='population.json'
    else:es.require(directions.generation==1,'Only the original G1 probes can precede fixed calibration.')
    return outputs


def public_panel(inputs,subject,request,original):
    value=inputs.json(subject)
    es.exact(value,('kind','protocolSha256','originalBundleSha256','worlds'),'Outcome-free public panel')
    es.require(value['kind']=='coordinated-es-public-panel-1' and value['protocolSha256']==protocol_sha(inputs,request) and
        value['originalBundleSha256']==request['originalBundle']['sha256'] and
        type(value['worlds']) is list and len(value['worlds'])==256,'The panel is the fixed G1 original control cohort.')
    flattened={head:[] for head in ('EXPANSION','TOKEN')}
    for index,world in enumerate(value['worlds']):
        es.exact(world,('index','assignedEpoch','heads'),'Public panel world')
        es.require(type(world['index']) is int and world['index']==index and
            world['assignedEpoch']==1+(index//4)%3,'Public panel epoch/seat schedule cannot change.')
        es.exact(world['heads'],('EXPANSION','TOKEN'),'Public panel heads')
        for name in flattened:
            states=world['heads'][name];identities=[]
            es.require(type(states) is list and len(states)<=2,'Only first/last deduplicated windows are retained.')
            for state in states:
                es.exact(state,('number','batchStartNumber','epoch','referenceIndex','observation'),'Public panel state')
                number,start=state['number'],state['batchStartNumber']
                es.require(type(number) is int and type(start) is int and 0<=start<=number<=2**53 and
                    state['epoch']==world['assignedEpoch'],'Panel state identities must match the assigned epoch.')
                observation=state['observation'];head=es.Head(name)
                es.exact(observation,('encoderVersion','stateFeatures','baselineIndex','actions'),'Public ES observation')
                for action in observation['actions']:es.exact(action,('command','features'),'Public ES action')
                projection.b.validate_observation(observation,original[head].model,contract=projection.contract(head))
                es.require(len(observation['actions'])>=2 and type(state['referenceIndex']) is int and
                    state['referenceIndex']==observation['baselineIndex'],'Calibration requires the actual offered raw E/T reference.')
                identities.append((number,start));flattened[name].append((index,state,2//len(states)))
            es.require(identities==sorted(set(identities)),'Panel windows must be original-order and deduplicated.')
    return value,flattened


def panel_forward(original,head,rows,guard):
    actor=projection.RawPolicy(original[head].model,head)
    prepared=[actor.public(state['observation']) for _,state,_ in rows]
    return prepared,projection.raw_all(actor,prepared,guard)


def differences(raw,reference):
    return [[es.finite(value-base,'residual-difference') for value,base in zip(row,old)]
        for row,old in zip(raw,reference)]


def calibrate(inputs,request,out,guard):
    owner=calibration_owner(inputs,request)
    value=checked_projection(inputs,request);original=originals(inputs,request['originalBundle'])
    directions=checked_directions(inputs,request,value);probe=es.prescribed_mean_probe(directions)
    materialization=inputs.canonical(request['materialization'])
    es.exact(materialization,('kind','scope','generation','centerSha256','directionsSha256','worldBankSha256',
        'worldIds','members'),'G1 probe materialization')
    center=es.Center(value.scope,0,(0.,)*256)
    es.require(materialization['kind']=='coordinated-es-materialization-1' and materialization['scope']==value.scope.record and
        materialization['generation']==1 and materialization['directionsSha256']==es.digest(directions.record) and
        materialization['centerSha256']==es.digest(center.record) and materialization['worldIds']==list(range(256)) and
        len(materialization['members'])==32,'Calibration consumes all original fixed G1 probes.')
    es.sha(materialization['worldBankSha256'],'Original calibration world bank')
    for row,expected in zip(materialization['members'],es.mutants(center,directions)):
        es.exact(row,('direction','sign','path','coordinatesSha256','bundleSha256'),'Calibration probe')
        es.require(row['direction']==expected['direction'] and row['sign']==expected['sign'] and
            row['coordinatesSha256']==es.digest(expected['coordinates']) and
            row['path']==f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/bundle.json",
            'Calibration probe order, coordinates and portable paths are fixed.')
    _,flat=public_panel(inputs,request['publicPanel'],request,original);receipts={};scales={}
    for name in ('EXPANSION','TOKEN'):
        head=es.Head(name);rows=flat[name]
        # Fail public coverage before any probe forward. Never open terminal/control returns.
        if len({index for index,_,_ in rows})<128:raise es.OperationalStop('calibration-insufficient-worlds')
        prepared,reference=panel_forward(original,head,rows,guard)
        witnesses=[]
        for member in materialization['members']:
            guard();actors=bundle(inputs,inputs.sibling(request['materialization'],member['path'],member['bundleSha256']))
            actor=projection.RawPolicy(json.loads(actors[head]),head)
            changed=differences(projection.raw_all(actor,prepared,guard),reference)
            witnesses.append([cal.witness(row,state['referenceIndex']) for row,(_,state,_) in zip(changed,rows)])
        mean=projection.materialize(value,original,probe['center']['coordinates'])
        mean_raw=projection.raw_all(projection.RawPolicy(json.loads(mean[head]),head),prepared,guard)
        mean_witnesses=[cal.witness(row,state['referenceIndex']) for row,(_,state,_) in
            zip(differences(mean_raw,reference),rows)]
        worlds=[dict(index=index,states=[]) for index in range(256)]
        for position,(index,state,_) in enumerate(rows):
            probes=[items[position] for items in witnesses];one=mean_witnesses[position]
            worlds[index]['states'].append(dict(decisionNumber=state['number'],batchStartNumber=state['batchStartNumber'],
                observationSha256=es.digest(state['observation']),referenceIndex=state['referenceIndex'],
                actionCount=len(state['observation']['actions']),mutantGaps=[cal.witness_gap(item) for item in probes],
                meanGap=cal.witness_gap(one),mutantWitnesses=probes,meanWitness=one))
        panel=cal.GapPanel.from_record(dict(kind='coordinated-es-gap-panel-1',head=name,
            protocolSha256=protocol_sha(inputs,request),originalModelSha256=original[head].sha256,
            projectionSha256=value.sha256,directionsSha256=es.digest(directions.record),
            prescribedMeanSha256=es.digest(probe),publicPanelSha256=request['publicPanel']['sha256'],worlds=worlds))
        out.raw(f'gap-{name}.json',panel.wire)
        receipts[name]=owner.calibrate(panel);scales[name]=receipts[name]['scale']
    record=dict(kind=getattr(owner,'OUTER_VERSION','coordinated-es-calibration-1'),passed=True,complete=True,
        protocolSha256=protocol_sha(inputs,request),originalBundleSha256=request['originalBundle']['sha256'],
        projectionSha256=value.sha256,panelSha256=request['publicPanel']['sha256'],
        probePopulationSha256=request['materialization']['sha256'],directionsSha256=es.digest(directions.record),
        prescribedMeanSha256=es.digest(probe),scales=scales,heads=receipts,
        semanticFitnessDecoded=False,calibrationAttempts=1)
    digest=out.json('calibration.json',record)
    out.json('population.json',seal_population(materialization,record['protocolSha256'],digest))
    return dict(calibration='calibration.json',population='population.json')


def update(inputs,request,out,guard):
    center=es.Center.from_record(inputs.canonical(request['center']))
    directions=es.DirectionTable.from_record(inputs.canonical(request['directions']))
    population=inputs.canonical(request['population'])
    es.require(population['protocolSha256']==protocol_sha(inputs,request),'Update protocol changed.')
    result=es.update(center,directions,population,inputs.canonical(request['fitness']))
    guard();out.json('update.json',result);out.json('center.json',result['center'])
    return dict(update='update.json',center='center.json')


def center(inputs,request,out,guard):
    value=checked_projection(inputs,request);current=checked_center(inputs,request,value)
    checked_calibration(inputs,request['calibration'],request,value)
    guard();digest=out.bundle('',projection.materialize(value,originals(inputs,request['originalBundle']),current.coordinates))
    out.json('center-materialization.json',dict(kind='coordinated-es-center-materialization-1',
        protocolSha256=protocol_sha(inputs,request),centerSha256=es.digest(current.record),generation=current.generation,
        projectionSha256=value.sha256,originalBundleSha256=request['originalBundle']['sha256'],
        calibrationSha256=request['calibration']['sha256'],bundleSha256=digest,finalCandidate=current.generation==16))
    return dict(bundle='bundle.json',materialization='center-materialization.json')


def diagnose(inputs,request,out,guard):
    owner=calibration_owner(inputs,request)
    value=checked_projection(inputs,request);current=checked_center(inputs,request,value)
    record,panels=checked_calibration(inputs,request['calibration'],request,value)
    es.require(record['panelSha256']==request['publicPanel']['sha256'],'Live movement uses only the original fixed panel.')
    original=originals(inputs,request['originalBundle']);_,flat=public_panel(inputs,request['publicPanel'],request,original)
    actors=projection.materialize(value,original,current.coordinates);heads={}
    for name in ('EXPANSION','TOKEN'):
        head=es.Head(name);rows=flat[name];prepared,reference=panel_forward(original,head,rows,guard)
        raw=projection.raw_all(projection.RawPolicy(json.loads(actors[head]),head),prepared,guard)
        gaps=[dict(index=index,decisionNumber=state['number'],batchStartNumber=state['batchStartNumber'],
            witness=cal.witness(row,state['referenceIndex'])) for row,(index,state,_) in zip(differences(raw,reference),rows)]
        heads[name]=owner.live_movement(panels[name],record['heads'][name],gaps)
    out.json('movement.json',dict(kind='coordinated-es-center-movement-1',generation=current.generation,
        protocolSha256=protocol_sha(inputs,request),centerSha256=es.digest(current.record),
        calibrationSha256=request['calibration']['sha256'],heads=heads,diagnosticOnly=True,eligibilityGate=False))
    return dict(movement='movement.json')


def execute(verb,request_path,output):
    """One guarded CPU1 operation. The caller owns the original deadline and stage launch."""
    es.require(verb in FIELDS,'Unknown numerical operation.')
    inputs=Inputs();path=Path(request_path).absolute();request_subject=dict(path=str(path),sha256=raw_sha(path.read_bytes()))
    request=inputs.json(request_subject)
    es.exact(request,COMMON+FIELDS[verb],'Numerical request')
    es.require(request['kind']==f'coordinated-es-{verb}-request-1','Numerical request kind differs from its explicit verb.')
    deadline=es.finite(request['deadlineEpoch'],'deadline')
    def guard():
        if time.time()>=deadline:raise es.OperationalStop('original-deadline')
    guard();source_owners(inputs,request['sourceOwners'])
    expected=inputs.json(request['numerical']);environment.verify(expected)
    if verb!='prepare':protocol_sha(inputs,request)
    out=Output(output)
    out.json('started.json',dict(kind='coordinated-es-numeric-started-1',verb=verb,request=request_subject,
        startedEpoch=time.time(),deadlineEpoch=deadline))
    try:
        outputs=globals()[verb](inputs,request,out,guard)
        guard();inputs.unchanged();guard()
        receipt=dict(kind='coordinated-es-numeric-complete-1',passed=True,complete=True,verb=verb,
            request=request_subject,protocolSha256=None if verb=='prepare' else request['protocol']['sha256'],
            sourceOwners=request['sourceOwners'],inputPins=inputs.pins,artifacts=out.inventory(),outputs=outputs,
            completedEpoch=time.time(),execution=dict(newGames=0,actualFits=0,optimizerSteps=0))
        out.json('finished.json',receipt)
        return receipt
    except es.OperationalStop as failure:
        out.json('stopped.json',dict(kind='coordinated-es-numeric-stop-1',complete=False,passed=False,verb=verb,
            request=request_subject,reason=failure.reason,diagnostics=failure.diagnostics,inputPins=inputs.pins,
            artifacts=out.inventory(),stoppedEpoch=time.time(),deadlineEpoch=deadline,
            execution=dict(newGames=0,actualFits=0,optimizerSteps=0)))
        raise
    except Exception as failure:
        out.json('failed.json',dict(kind='coordinated-es-numeric-failure-1',complete=False,passed=False,verb=verb,
            request=request_subject,errorType=type(failure).__name__,message=str(failure),inputPins=inputs.pins,
            artifacts=out.inventory(),failedEpoch=time.time(),execution=dict(newGames=0,actualFits=0,optimizerSteps=0)))
        raise


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('verb',choices=tuple(FIELDS));parser.add_argument('--request',required=True)
    parser.add_argument('--output',required=True);args=parser.parse_args(argv)
    try:result=execute(args.verb,args.request,args.output)
    except es.OperationalStop as failure:
        # The parent can inspect the retained declared stop; identity failures still exit nonzero.
        path=Path(args.output)/'stopped.json'
        record=json.loads(path.read_bytes())
        es.require(record['kind']=='coordinated-es-numeric-stop-1' and record['reason']==failure.reason and
            not (Path(args.output)/'finished.json').exists(),'Only a persisted incomplete stop can return normally.')
        print(json.dumps(dict(passed=False,status='stopped',verb=args.verb,reason=failure.reason,
            stopped=dict(path=str(path.absolute()),sha256=raw_sha(path.read_bytes()))),sort_keys=True))
        return
    print(json.dumps(dict(passed=result['passed'],verb=args.verb,outputs=result['outputs']),sort_keys=True))


if __name__=='__main__':main()
