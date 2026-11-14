"""Pure continuation fitness admission over unchanged archive and terminal owners.

The original calibration remains bound to its old protocol. Only the new envelope
joins it to the supported continuation; no population/game is decoded before this gate.
"""
from pathlib import Path

import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_es_storage as storage
import coordinated_win_plan as p


def canonical_subject(subject):
    path=p.base.checked_subject(subject);value=p.read(path)
    p.require(Path(path).read_bytes()==p.canonical(value),'Continuation metadata requires canonical bytes without LF.')
    return value


def sibling(subject,row):
    p.exact(row,('path','sha256'),'Inherited calibration member')
    owner=Path(subject['path']).resolve().parent
    path=owner/storage.safe(row['path'])
    p.require(path.resolve().is_relative_to(owner),'Inherited subjects remain in their portable envelope directory.')
    return dict(path=str(path),sha256=p.sha(row['sha256']))


def inherited_calibration(plan,protocol_sha,subject):
    """Rehash the selected envelope and original seal before any population capability."""
    p.require(isinstance(plan,p.Plan) and protocol_sha==plan.file_sha256,
        'Fitness requires the exact raw continuation protocol, including its framing LF.')
    value=canonical_subject(subject);record=plan.record;expected=record['calibration']
    p.exact(value,('kind','protocolSha256','parameterSpace','warmStartSha256','originProtocol',
        'originCalibration','recipe','scales','recalibrated'),'Inherited calibration envelope')
    p.require(value['kind']==expected['kind']=='coordinated-es-inherited-calibration-1' and
        value['protocolSha256']==protocol_sha and value['parameterSpace']==plan.scope.record and
        value['warmStartSha256']==record['warmStart']['identitySha256'] and
        value['recipe']==expected['recipe'] and value['scales']==expected['scales'] and
        value['recalibrated'] is False,'Fitness is closed until the exact inherited calibration is admitted.')
    originals={}
    for name,key in (('originProtocol','originProtocolSha256'),('originCalibration','originCalibrationSha256')):
        p.require(value[name]['sha256']==expected[key],'The original calibration protocol/seal cannot be relabelled.')
        originals[name]=sibling(subject,value[name])
    old_protocol=p.read(p.base.checked_subject(originals['originProtocol']))
    seal=canonical_subject(originals['originCalibration'])
    kind='coordinated-es-calibration-2' if expected['recipe']==p.base.calibration_recipe(2) else 'coordinated-es-calibration-1'
    p.require(old_protocol.get('calibration')==expected['recipe'] and seal.get('kind')==kind and
        seal.get('passed') is True and seal.get('complete') is True and
        seal.get('protocolSha256')==originals['originProtocol']['sha256'] and
        seal.get('originalBundleSha256')==plan.scope.original_bundle_sha256 and
        seal.get('projectionSha256')==plan.scope.projection_sha256 and seal.get('scales')==expected['scales'] and
        seal.get('semanticFitnessDecoded') is False and type(seal.get('calibrationAttempts')) is int and
        seal['calibrationAttempts']==1,'Fitness is closed until the exact original versioned public-only seal is admitted.')
    return value


def game_win(value,receipt,plan,index,expected_role,bundle_sha):
    """Read ArenaGame.wins at its controlled player slot after all-seat score validation."""
    number=receipt['generation'];p.generation(number)
    p.require(value['kind']==p.PREFIX+'-game-1' and value['generation']==number and
        type(value['globalIndex']) is int and value['globalIndex']==index and
        value['seed']==plan.bank(number)['worldBank']['seeds'][index] and
        type(value['seat']) is int and value['seat']==index%4 and value['role']==expected_role and
        value['bundleSha256']==bundle_sha and value['originalBundleSha256']==plan.scope.original_bundle_sha256 and
        all(value[key]==receipt[key] for key in ('signature','scopeSha256','scienceSha256','sourceSha256',
            'warmStartSha256','inheritedCalibrationSha256','ecologySha256')),
        'A win row retains its exact controlled world, actor, inherited anchors and supported execution identity.')
    return storage.terminal_metrics(value['terminal'])['wins'][value['seat']]


def fitness(plan,protocol_sha,population_subject,calibration_subject,receipts):
    inherited_calibration(plan,protocol_sha,calibration_subject)
    population=canonical_subject(population_subject);record=plan.record
    p.exact(population,('kind','parameterSpace','warmStartSha256','step','protocolSha256',
        'materializationSha256','centerSha256','directionsSha256','inheritedCalibrationSha256',
        'worldBankSha256','worldIds','members'),'Continuation population')
    number=p.generation(population['step'])
    p.require(population['kind']==c.POPULATION_KIND and population['protocolSha256']==protocol_sha and
        population['parameterSpace']==plan.scope.record and
        population['warmStartSha256']==record['warmStart']['identitySha256'] and
        population['inheritedCalibrationSha256']==calibration_subject['sha256'] and
        population['worldBankSha256']==p.digest(plan.bank(number)['worldBank']) and
        population['worldIds']==list(range(256)) and type(population['members']) is list and
        len(population['members'])==32,'Only the complete inherited-calibration continuation population supplies win fitness.')
    unsealed={k:v for k,v in population.items() if k not in
        ('protocolSha256','materializationSha256','inheritedCalibrationSha256')}
    unsealed['kind']=c.MATERIALIZATION_KIND
    p.require(es.digest(unsealed)==population['materializationSha256'],
        'Population materialization bytes must remain bound before reading games.')
    for index,member in enumerate(population['members']):
        p.exact(member,('direction','sign','path','coordinatesSha256','bundleSha256'),'Signed win member')
        p.require(member['direction']==index//2 and member['sign']==(1 if index%2==0 else -1) and
            member['path']==f'members/{storage.role(index)}/bundle.json','All32 signed members retain their exact order and path.')
        p.sha(member['bundleSha256']);p.sha(member['coordinatesSha256'])
    def admit(receipt):
        chunk=receipt['chunk'];host=p.owner(chunk);subjects=receipt['subjects']
        p.require(receipt['kind']==p.PREFIX+'-chunk-receipt-1' and receipt['phase']=='population' and
            receipt['generation']==number and receipt['protocolSha256']==protocol_sha and receipt['host']==host and
            receipt['scopeSha256']==plan.scope.scope_sha256 and
            receipt['ecologySha256']==record['evaluation']['ecologySha256'] and
            receipt['warmStartSha256']==record['warmStart']['identitySha256'] and
            receipt['inheritedCalibrationSha256']==calibration_subject['sha256'],
            'Every win shard retains the same parameter space, warm start, envelope and supported ecology.')
        for name,expected_sha in (('protocol',protocol_sha),('population',population_subject['sha256']),
                ('inheritedCalibration',calibration_subject['sha256']),('center',population['centerSha256']),
                ('directions',population['directionsSha256']),('warmStart',record['warmStart']['identitySha256']),
                ('originalBundle',plan.scope.original_bundle_sha256),('projection',plan.scope.projection_sha256),
                ('originProof',record['warmStart']['proofSha256'][host])):
            p.require(subjects[name]['sha256']==expected_sha,'Win shard subject changed: '+name)
        p.require(receipt['globalStart']==chunk*32 and receipt['count']==32 and
            receipt['attemptedGames']==receipt['completedGames']==1024,
            'Each population shard supplies its exact32-world1024-game range once.')
    rows=storage.return_matrix(receipts,admit,lambda value,receipt,index,member:
        game_win(value,receipt,plan,index,storage.role(member),population['members'][member]['bundleSha256']))
    return dict(kind=c.FITNESS_KIND,objective=c.OBJECTIVE,protocolSha256=protocol_sha,
        populationSha256=population_subject['sha256'],worldBankSha256=population['worldBankSha256'],
        worldIds=list(range(256)),members=[dict(direction=index//2,sign=1 if index%2==0 else -1,
            bundleSha256=member['bundleSha256'],winShares=rows[index])
            for index,member in enumerate(population['members'])])
