"""Typed continuation operations over the existing projection and finite numerical request owner.

An inherited calibration is a new binding envelope around unchanged old seal/protocol/gap bytes.
It never re-labels that seal as calibrated under the new supported execution ecology.
"""
import argparse
import json
from pathlib import Path

import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_es_numeric as numeric
import coordinated_es_projection as projection
import coordinated_win_plan as p

FIELDS={
    'prepare':('protocol','originalBundle','projection','warmStart','originCenter','originWarmStart',
        'originBundle','originAdmission','originMaterialization','calibrationProtocol','calibration'),
    'materialize':('protocol','originalBundle','projection','warmStart','center','directions','worldBank','inheritedCalibration'),
    'update':('protocol','warmStart','center','directions','population','fitness','inheritedCalibration'),
    'center':('protocol','originalBundle','projection','warmStart','center','inheritedCalibration'),
    'diagnose':('protocol','originalBundle','projection','warmStart','center','inheritedCalibration','publicPanel'),
}
INHERITED_KIND='coordinated-es-inherited-calibration-1'


def source_owners(inputs,values):
    numeric.source_owners(inputs,values)
    for owner in (__file__,c.__file__,p.__file__,p.base.__file__):
        path=str(Path(owner).absolute())
        es.require(path in values,'Every executing continuation owner must be explicitly source-pinned.')
        inputs.raw(dict(path=path,sha256=values[path]))


def plan(inputs,request):
    raw=inputs.raw(request['protocol']);value=p.Plan.from_record(json.loads(raw))
    es.require(raw==value.wire+b'\n','The new protocol retains canonical JSON plus one LF.')
    return value


def warm_start(inputs,request,value):
    warm=c.WarmStart.from_record(inputs.canonical(request['warmStart']))
    value.verify_warm_start(warm)
    return warm


def checked_projection(inputs,request,value):
    expected=value.record['parameterSpace']
    es.require(request['originalBundle']['sha256']==expected['originalBundleSha256'] and
        request['projection']['sha256']==expected['projectionSha256'],
        'The continuation cannot create or rebase its original bundle/projection.')
    result=numeric.checked_projection(inputs,request)
    es.require(result.scope==value.scope and es.digest(result.record['recipe'])==expected['projectionRecipeSha256'],
        'The exact inherited projection recipe and global coordinate origin must match.')
    for head,row in expected['heads'].items():
        actual=result.record['heads'][head]
        es.require(actual['modelSha256']==row['actorSha256'] and actual['dictionarySha256']==row['dictionarySha256'],
            'All four original parameter containers and full dictionaries remain immutable.')
    return result


def checked_center(inputs,request,value,warm):
    center=c.Center.from_record(inputs.canonical(request['center']),warm)
    es.require(center.scope==value.scope,'The current center retains its inherited parameter space.')
    return center


def inherited_metadata(inputs,subject,request,value,warm):
    record=inputs.canonical(subject);expected=value.record['calibration']
    es.exact(record,('kind','protocolSha256','parameterSpace','warmStartSha256','originProtocol',
        'originCalibration','recipe','scales','recalibrated'),'Inherited calibration envelope')
    es.require(record['kind']==INHERITED_KIND and record['protocolSha256']==request['protocol']['sha256'] and
        record['parameterSpace']==value.scope.record and record['warmStartSha256']==warm.sha256 and
        record['recipe']==expected['recipe'] and record['scales']==expected['scales'] and
        record['recalibrated'] is False,'The envelope must bind the unchanged seal to this new protocol and warm start.')
    old={}
    for key,expected_sha in (('originProtocol',expected['originProtocolSha256']),
            ('originCalibration',expected['originCalibrationSha256'])):
        row=record[key];es.exact(row,('path','sha256'),'Inherited raw subject')
        es.require(row['sha256']==expected_sha,'An inherited raw calibration/protocol cannot change.')
        old[key]=inputs.sibling(subject,row['path'],row['sha256'])
    old_protocol=inputs.json(old['originProtocol']);seal=inputs.canonical(old['originCalibration'])
    outer='coordinated-es-calibration-2' if expected['recipe']==p.base.calibration_recipe(2) else 'coordinated-es-calibration-1'
    es.require(old_protocol.get('calibration')==expected['recipe'] and seal.get('kind')==outer and
        seal.get('passed') is True and seal.get('complete') is True and
        seal.get('protocolSha256')==old['originProtocol']['sha256'] and
        seal.get('originalBundleSha256')==value.scope.original_bundle_sha256 and
        seal.get('projectionSha256')==value.scope.projection_sha256 and seal.get('scales')==expected['scales'] and
        seal.get('semanticFitnessDecoded') is False and type(seal.get('calibrationAttempts')) is int and
        seal['calibrationAttempts']==1,'Only the exact original versioned outcome-blind seal may be inherited.')
    return record,old,seal


def checked_calibration(inputs,request,value,warm,factors):
    record,old,seal=inherited_metadata(inputs,request['inheritedCalibration'],request,value,warm)
    old_request=dict(request,protocol=old['originProtocol'])
    validated,panels=numeric.checked_calibration(inputs,old['originCalibration'],old_request,factors)
    es.require(validated==seal,'Pure original calibration validation cannot rewrite its sealed bytes.')
    return record,old_request,validated,panels


def prepare(inputs,request,out,guard):
    value=plan(inputs,request);warm=warm_start(inputs,request,value)
    factors=checked_projection(inputs,request,value)
    expected=value.record['warmStart']
    for key,name in (('originCenter','centerSha256'),('originBundle','bundleSha256'),
            ('originAdmission','admissionSha256'),('originMaterialization','materializationSha256')):
        es.require(request[key]['sha256']==expected[name],'Preparation requires the exact complete parent subjects.')
    raw_center=inputs.canonical(request['originCenter'])
    if raw_center['kind']=='coordinated-es-center-1':
        es.require(request['originWarmStart'] is None,'A zero-origin parent cannot acquire fabricated continuation ancestry.')
        parent=es.Center.from_record(raw_center)
    else:
        es.require(raw_center['kind']==c.CENTER_KIND and request['originWarmStart'] is not None,
            'A continuation parent must retain its own original typed warm-start identity.')
        parent_warm=c.WarmStart.from_record(inputs.canonical(request['originWarmStart']))
        parent=c.Center.from_record(raw_center,parent_warm)
    warm.verify_origin(parent)
    admission=inputs.json(request['originAdmission'])
    es.require(admission.get('kind') in ('coordinated-es-final-admission-1','coordinated-win-final-admission-1') and
        admission.get('complete') is True and admission.get('completedGenerations')==16 and
        admission.get('scopeSha256')==value.scope.scope_sha256,
        'Preparation inherits an admitted complete final mean, never an earlier population member.')
    for name,expected_sha in (('protocol',expected['protocolSha256']),('center',expected['centerSha256']),
            ('finalBundle',expected['bundleSha256']),('centerMaterialization',expected['materializationSha256']),
            ('projection',value.scope.projection_sha256),('originalBundle',value.scope.original_bundle_sha256),
            ('calibration',warm.calibration_sha256)):
        es.require(admission.get(name,{}).get('sha256')==expected_sha,
            'The retained parent admission must bind every unchanged warm-start subject.')
    materialization=inputs.canonical(request['originMaterialization'])
    step_key='generation' if isinstance(parent,es.Center) else 'step'
    expected_kind='coordinated-es-center-materialization-1' if isinstance(parent,es.Center) else 'coordinated-es-continuation-center-materialization-1'
    es.require(materialization.get('kind')==expected_kind and materialization.get('protocolSha256')==expected['protocolSha256'] and
        materialization.get('centerSha256')==expected['centerSha256'] and materialization.get('bundleSha256')==expected['bundleSha256'] and
        materialization.get(step_key)==16 and materialization.get('finalCandidate') is True and
        materialization.get('projectionSha256')==value.scope.projection_sha256 and
        materialization.get('originalBundleSha256')==value.scope.original_bundle_sha256,
        'The complete parent bundle must be its exact retained final-coordinate materialization.')
    if isinstance(parent,es.Center):
        es.require(materialization.get('calibrationSha256')==warm.calibration_sha256,
            'The original parent materialization must use its exact admitted scalar seal.')
    else:
        inherited_sha=admission.get('inheritedCalibration',{}).get('sha256')
        es.sha(inherited_sha,'Parent inherited calibration envelope')
        es.require(materialization.get('inheritedCalibrationSha256')==inherited_sha,
            'A continuation parent materialization retains its own exact admitted inherited envelope.')
    old=value.record['calibration']
    es.require(request['calibrationProtocol']['sha256']==old['originProtocolSha256'] and
        request['calibration']['sha256']==old['originCalibrationSha256'],
        'Inherited calibration preparation cannot substitute new protocol/seal bytes.')
    old_request=dict(request,protocol=request['calibrationProtocol'])
    seal,panels=numeric.checked_calibration(inputs,request['calibration'],old_request,factors)
    es.require(seal['scales']==old['scales'] and inputs.json(request['calibrationProtocol'])['calibration']==old['recipe'],
        'The new protocol must declare the exact original scalar seal and recipe.')
    original=numeric.originals(inputs,request['originalBundle']);guard()
    expected_heads=numeric.bundle(inputs,request['originBundle'])
    actual=projection.materialize(factors,original,warm.coordinates)
    es.require(actual==expected_heads,'The warm center must reproduce every admitted parent head byte in the original parameter space.')
    out.raw('projection.json',factors.wire);out.json('warm-start.json',warm.record)
    out.json('initial-center.json',c.Center.initial(warm).record)
    reproduced=out.bundle('warm-bundle/',actual)
    es.require(reproduced==expected['bundleSha256'],'The portable warm bundle descriptor must also remain byte-exact.')
    out.raw('origin/protocol.json',inputs.raw(request['calibrationProtocol']))
    out.raw('origin/calibration.json',inputs.raw(request['calibration']))
    for head,panel in panels.items():out.raw(f'origin/gap-{head}.json',panel.wire)
    inherited=dict(kind=INHERITED_KIND,protocolSha256=request['protocol']['sha256'],parameterSpace=value.scope.record,
        warmStartSha256=warm.sha256,originProtocol=dict(path='origin/protocol.json',sha256=request['calibrationProtocol']['sha256']),
        originCalibration=dict(path='origin/calibration.json',sha256=request['calibration']['sha256']),
        recipe=old['recipe'],scales=old['scales'],recalibrated=False)
    out.json('inherited-calibration.json',inherited)
    for row in value.record['generations']:
        guard();directions,receipt=projection.generate_directions(value.scope,row['generation'],row['directionSeed'])
        out.json(f"directions/generation-{row['generation']:02d}.json",directions.record)
        out.json(f"directions/generation-{row['generation']:02d}-generation.json",receipt)
    out.json('warm-start-proof.json',dict(kind='coordinated-es-warm-start-proof-1',complete=True,
        protocolSha256=request['protocol']['sha256'],warmStartSha256=warm.sha256,
        originCenterSha256=expected['centerSha256'],originBundleSha256=expected['bundleSha256'],
        allFourHeadBytesExact=True,descriptorBytesExact=True,projectionRegenerated=False,
        calibrationRecomputedFromNewStates=False,initializationStreams=0,directionStreams=16,
        execution=dict(newGames=0,actualFits=0,optimizerSteps=0)))
    return dict(projection='projection.json',warmStart='warm-start.json',center='initial-center.json',
        inheritedCalibration='inherited-calibration.json',proof='warm-start-proof.json')


def materialize(inputs,request,out,guard):
    value=plan(inputs,request);warm=warm_start(inputs,request,value);factors=checked_projection(inputs,request,value)
    center=checked_center(inputs,request,value,warm);directions=numeric.checked_directions(inputs,request,factors)
    c.bind(center,directions);checked_calibration(inputs,request,value,warm,factors)
    bank=inputs.canonical(request['worldBank'])
    es.require(bank==value.bank(directions.generation)['worldBank'],'Materialization requires the exact prospective current256 world bank.')
    original=numeric.originals(inputs,request['originalBundle']);members=[]
    for row in c.mutants(center,directions):
        guard();name=f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/"
        digest=out.bundle(name,projection.materialize(factors,original,row['coordinates']))
        members.append(dict(direction=row['direction'],sign=row['sign'],path=name+'bundle.json',
            coordinatesSha256=es.digest(row['coordinates']),bundleSha256=digest))
    record=dict(kind=c.MATERIALIZATION_KIND,parameterSpace=value.scope.record,warmStartSha256=warm.sha256,
        step=directions.generation,centerSha256=es.digest(center.record),directionsSha256=es.digest(directions.record),
        worldBankSha256=request['worldBank']['sha256'],worldIds=list(range(256)),members=members)
    out.json('materialization.json',record)
    out.json('population.json',c.seal_population(record,request['protocol']['sha256'],request['inheritedCalibration']['sha256']))
    return dict(materialization='materialization.json',population='population.json')


def update(inputs,request,out,guard):
    value=plan(inputs,request);warm=warm_start(inputs,request,value);center=checked_center(inputs,request,value,warm)
    directions=es.DirectionTable.from_record(inputs.canonical(request['directions']))
    inherited_metadata(inputs,request['inheritedCalibration'],request,value,warm)
    population=inputs.canonical(request['population'])
    es.require(population['protocolSha256']==request['protocol']['sha256'] and
        population['inheritedCalibrationSha256']==request['inheritedCalibration']['sha256'] and
        population['worldBankSha256']==es.digest(value.bank(directions.generation)['worldBank']),
        'Win updates retain the exact new protocol, current world bank and inherited seal.')
    result=c.update(center,directions,population,inputs.canonical(request['fitness']))
    guard();out.json('update.json',result);out.json('center.json',result['center'])
    return dict(update='update.json',center='center.json')


def center(inputs,request,out,guard):
    value=plan(inputs,request);warm=warm_start(inputs,request,value);factors=checked_projection(inputs,request,value)
    current=checked_center(inputs,request,value,warm);checked_calibration(inputs,request,value,warm,factors)
    guard();bundle=out.bundle('',projection.materialize(factors,numeric.originals(inputs,request['originalBundle']),current.coordinates))
    out.json('center-materialization.json',dict(kind='coordinated-es-continuation-center-materialization-1',
        protocolSha256=request['protocol']['sha256'],centerSha256=es.digest(current.record),step=current.step,
        warmStartSha256=warm.sha256,projectionSha256=factors.sha256,originalBundleSha256=value.scope.original_bundle_sha256,
        inheritedCalibrationSha256=request['inheritedCalibration']['sha256'],bundleSha256=bundle,finalCandidate=current.step==16))
    return dict(bundle='bundle.json',materialization='center-materialization.json')


def diagnose(inputs,request,out,guard):
    value=plan(inputs,request);warm=warm_start(inputs,request,value);factors=checked_projection(inputs,request,value)
    current=checked_center(inputs,request,value,warm)
    _,old_request,seal,panels=checked_calibration(inputs,request,value,warm,factors)
    es.require(seal['panelSha256']==request['publicPanel']['sha256'],'Movement uses only the original calibration public panel.')
    original=numeric.originals(inputs,request['originalBundle'])
    _,flat=numeric.public_panel(inputs,request['publicPanel'],old_request,original)
    actors=projection.materialize(factors,original,current.coordinates);owner=numeric.calibration_owner(inputs,old_request);heads={}
    for name in ('EXPANSION','TOKEN'):
        head=es.Head(name);rows=flat[name];prepared,reference=numeric.panel_forward(original,head,rows,guard)
        raw=projection.raw_all(projection.RawPolicy(json.loads(actors[head]),head),prepared,guard)
        gaps=[dict(index=index,decisionNumber=state['number'],batchStartNumber=state['batchStartNumber'],
            witness=numeric.cal.witness(row,state['referenceIndex'])) for row,(index,state,_) in
            zip(numeric.differences(raw,reference),rows)]
        heads[name]=owner.live_movement(panels[name],seal['heads'][name],gaps)
    out.json('movement.json',dict(kind='coordinated-es-continuation-movement-1',step=current.step,
        protocolSha256=request['protocol']['sha256'],centerSha256=es.digest(current.record),
        inheritedCalibrationSha256=request['inheritedCalibration']['sha256'],heads=heads,
        diagnosticOnly=True,eligibilityGate=False))
    return dict(movement='movement.json')


def execute(verb,request_path,output):
    es.require(verb in FIELDS,'Unknown continuation numerical operation.')
    return numeric.execute_request(verb,request_path,output,FIELDS[verb],p.PREFIX,source_owners,globals()[verb])


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('verb',choices=tuple(FIELDS));parser.add_argument('--request',required=True)
    parser.add_argument('--output',required=True);args=parser.parse_args(argv)
    try:result=execute(args.verb,args.request,args.output)
    except es.OperationalStop as failure:
        path=Path(args.output)/'stopped.json';record=json.loads(path.read_bytes())
        es.require(record['kind']==p.PREFIX+'-numeric-stop-1' and record['reason']==failure.reason and
            not (Path(args.output)/'finished.json').exists(),'Only a retained honest stop may return normally.')
        print(json.dumps(dict(passed=False,status='stopped',verb=args.verb,reason=failure.reason,
            stopped=dict(path=str(path.absolute()),sha256=numeric.raw_sha(path.read_bytes()))),sort_keys=True))
        return
    print(json.dumps(dict(passed=result['passed'],verb=args.verb,outputs=result['outputs']),sort_keys=True))


if __name__=='__main__':main()
