"""Portable fixed-stage subjects, separate from host-local origin provenance.

Only current center/directions, the immediate update, historical evidence and the small inherited
seal closure travel with a stage. The original model/projection/warm-start proof stay pinned once
per host. This owner does no model forward, outcome decoding or process execution.
"""
from pathlib import Path

import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_es_storage as storage
import coordinated_win_handoff as handoff
import coordinated_win_numeric_stage as numeric
import coordinated_win_plan as p
import coordinated_win_storage as wins

FIELDS=('center','directions','previousUpdate','historical','inheritedCalibration','population')
CALIBRATION_FILES={'inherited-calibration.json','origin/protocol.json','origin/calibration.json',
    'origin/gap-EXPANSION.json','origin/gap-TOKEN.json'}


def identity(plan,protocol,number,phase):
    p.generation(number)
    p.require(phase in ('controls','population') and p.file_sha(protocol)==plan.file_sha256,
        'The portable stage must retain its exact continuation protocol and fixed phase.')
    return dict(protocolSha256=plan.file_sha256,scopeSha256=plan.scope.scope_sha256,
        warmStartSha256=plan.record['warmStart']['identitySha256'],
        ecologySha256=plan.record['evaluation']['ecologySha256'],stage=f'{phase}-{number}',generation=number)


def create(directory,plan,protocol,profile,number,phase,center,previous,historical,population=None):
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    p.require(not (directory/'stage.json').exists(),'An original continuation stage cannot be republished.')
    items={}
    for key,item,name in (('center',center,'center.json'),
            ('directions',profile['directions'][number-1],'directions.json'),
            ('previousUpdate',previous,'previous-update.json')):
        if item is None:items[key]=None
        else:
            copied=numeric.copy_file(item,directory/name);items[key]=dict(path=name,sha256=copied['sha256'])
    p.checked_subject(historical)
    numeric.copy_tree(Path(historical['path']).parent,directory/'historical')
    items['historical']=dict(path='historical/'+Path(historical['path']).name,sha256=historical['sha256'])
    inherited=profile['inheritedCalibration'];source=p.checked_subject(inherited)
    wins.inherited_calibration(plan,plan.file_sha256,inherited)
    p.require(source.name=='inherited-calibration.json' and
        set(storage.inventory(source.parent))==CALIBRATION_FILES,
        'The inherited seal has exactly its five unchanged original byte subjects.')
    destination=directory/'calibration'
    if not destination.exists():numeric.copy_tree(source.parent,destination)
    p.require(storage.inventory(destination)==storage.inventory(source.parent),
        'The portable inherited calibration closure must remain byte-exact.')
    items['inheritedCalibration']=dict(path='calibration/inherited-calibration.json',sha256=inherited['sha256'])
    if population is None:items['population']=None
    else:
        p.checked_subject(population)
        p.require(p.file_sha(directory/'population.json')==population['sha256'],
            'The executable population retains its existing portable member layout.')
        items['population']=dict(path='population.json',sha256=population['sha256'])
    row=dict(kind=p.PREFIX+'-stage-context-1',subjects=items,**identity(plan,protocol,number,phase))
    storage.publish_json(directory/'stage.json',row)
    # Apply the same typed consumer boundary locally before publishing to the peer.
    read(directory,plan,protocol,profile,number,phase)
    return directory


def read(directory,plan,protocol,profile,number,phase):
    directory=Path(directory).resolve();row=p.read(directory/'stage.json')
    p.exact(row,('kind','subjects',*identity(plan,protocol,number,phase)),'Continuation stage context')
    p.require(row['kind']==p.PREFIX+'-stage-context-1' and
        all(row[key]==value for key,value in identity(plan,protocol,number,phase).items()),
        'Both hosts consume the exact generation, inherited parameter space and supported ecology.')
    p.exact(row['subjects'],FIELDS,'Portable continuation subjects')
    subjects=dict(protocol=p.subject(protocol),**{key:profile[key] for key in
        ('arena','originalBundle','projection','warmStart','originProof')})
    for key,item in row['subjects'].items():
        if item is None:subjects[key]=None;continue
        p.exact(item,('path','sha256'),'Portable stage member')
        target=directory/storage.safe(item['path'])
        p.require(target.resolve().is_relative_to(directory),'Portable subjects cannot escape their stage closure.')
        subjects[key]=dict(path=str(target.resolve()),sha256=p.sha(item['sha256']))
        p.checked_subject(subjects[key])
    chunk=p.GENERATION_CHUNKS[profile['host']][0]
    plan.generation_source(number,chunk,phase.upper(),subjects)
    p.require(subjects['directions']['sha256']==profile['directions'][number-1]['sha256'] and
        subjects['inheritedCalibration']['sha256']==profile['inheritedCalibration']['sha256'],
        'A received stage cannot replace the prospective direction table or inherited calibration.')
    warm=c.WarmStart.from_record(wins.canonical_subject(subjects['warmStart']));plan.verify_warm_start(warm)
    center=c.Center.from_record(wins.canonical_subject(subjects['center']),warm)
    directions=es.DirectionTable.from_record(wins.canonical_subject(subjects['directions']))
    c.bind(center,directions)
    p.require(center.step==number-1,'Only the immediate completed center may open the next generation.')
    if number==1:
        p.require(subjects['center']['sha256']==profile['initialCenter']['sha256'],
            'The first generation consumes the exact prepared parent final coordinates.')
    else:
        update=wins.canonical_subject(subjects['previousUpdate'])
        p.require(update['kind']==c.UPDATE_KIND and update['complete'] is True and
            update['objective']==c.OBJECTIVE and update['protocolSha256']==plan.file_sha256 and
            update['warmStartSha256']==warm.sha256 and update['center']==center.record and
            update['directionsSha256']==profile['directions'][number-2]['sha256'],
            'The previous win-only update must bind this exact output center and preceding directions.')
    wins.inherited_calibration(plan,plan.file_sha256,subjects['inheritedCalibration'])
    return subjects


def publish(root,directory,number,phase,protocol,plan):
    directory=Path(directory);name=f'{phase}-{number}'
    files={member:p.subject(directory/member) for member in storage.inventory(directory)}
    return handoff.bundle(Path(root)/'publications'/name,name,files,
        dict(identity(plan,protocol,number,phase),contextSha256=p.file_sha(directory/'stage.json')))


def verify_publication(ready,directory,number,phase,protocol,plan):
    p.require(ready['publication']['identity']==dict(identity(plan,protocol,number,phase),
        contextSha256=p.file_sha(Path(directory)/'stage.json')),
        'The immutable outbound publication retains its exact portable stage identity.')
