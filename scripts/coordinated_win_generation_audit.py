"""Pure continuation arithmetic and immutable numerical provenance for the sole Desktop owner.

The caller owns native archives, outcomes and final-screen admission. This owner reuses the
original transaction/resource checks, admits the unchanged old calibration under its old protocol,
and recomputes all sixteen win-share updates. It imports no tensor backend or model evaluator.
"""
from pathlib import Path
from types import SimpleNamespace
import time

import coordinated_es_continuation as c
import coordinated_es_generation_audit as base
import coordinated_es_math as es
import coordinated_win_context as contexts
import coordinated_win_plan as p
import coordinated_win_preparation as preparation
import coordinated_win_storage as wins

Pins=base.Pins
ZERO_EXECUTION=base.ZERO_EXECUTION
subject,file_subject,same=base.subject,base.file_subject,base.same


def numeric(pins,receipt,verb,protocol,clock,*,stopped=False):
    return base.numeric(pins,receipt,verb,protocol,clock,stopped=stopped,prefix=p.PREFIX)


def inputs(pins,protocol,plan,profile_subject):
    """Bind physical prepared bytes once, without reopening copied request provenance paths."""
    profile=subject(pins,profile_subject)
    p.require(profile['kind']==p.PREFIX+'-host-1' and profile['host']=='DESKTOP' and
        profile['protocolSha256']==protocol['sha256'],
        'The complete numerical audit requires the exact frozen sole Desktop profile.')
    prepared=profile['numericalPreparation']
    request=prepared['request']
    pins.pin(request['path'],request['sha256'])
    finished=subject(pins,prepared['finished'],True)
    for name,digest in finished['artifacts'].items():
        pins.pin(Path(prepared['finished']['path']).parent/base.safe(name),digest)
    preparation.verify(plan,profile)
    for key in ('originalBundle','projection','warmStart','initialCenter','inheritedCalibration','originProof','arena'):
        pins.subject(profile[key])
    p.require(profile['originProof']['sha256']==plan.record['warmStart']['proofSha256']['DESKTOP'],
        'The frozen host profile retains its exact separately admitted original-runtime proof.')
    warm=c.WarmStart.from_record(subject(pins,profile['warmStart'],True));plan.verify_warm_start(warm)
    # This envelope binds the old seal. Its original protocol identity is never relabeled.
    envelope=wins.inherited_calibration(plan,protocol['sha256'],profile['inheritedCalibration'])
    old={name:wins.sibling(profile['inheritedCalibration'],envelope[name])
        for name in ('originProtocol','originCalibration')}
    seal=subject(pins,old['originCalibration'],True)
    for item in old.values():pins.subject(item)
    adapter=SimpleNamespace(record=dict(calibration=plan.record['calibration']['recipe'],original=dict(
        bundleSha256=plan.scope.original_bundle_sha256,projectionSha256=plan.scope.projection_sha256,
        heads=plan.record['parameterSpace']['heads'])))
    p.require(base.calibration(pins,old['originCalibration'],old['originProtocol'],adapter)==seal,
        'The original scalar calibration must reproduce under its original protocol.')
    panel=profile.get('diagnosticPanel')
    if panel is not None:
        pins.subject(panel)
        p.require(panel['sha256']==seal['panelSha256'],
            'An optional diagnostic must be the prospectively pinned original calibration panel.')
    return profile,warm


def context(pins,directory,plan,protocol,profile,number,phase,row,previous_update):
    """Use the actual coordinator consumer and pin every subject that it consumes."""
    pins.read(Path(directory)/'stage.json')
    values=contexts.read(directory,plan,protocol['path'],profile,number,phase)
    for item in values.values():
        if item is not None:pins.subject(item)
    for name in contexts.CALIBRATION_FILES:pins.pin(Path(directory)/'calibration'/base.safe(name))
    p.require(all(same(values[key],row[target]) for key,target in
        (('center','inputCenter'),('directions','directions'),('inheritedCalibration','inheritedCalibration'))) and
        ((previous_update is None and values['previousUpdate'] is None) or
         (previous_update is not None and same(values['previousUpdate'],previous_update))),
        'The coordinator consumes this exact center, direction table, inherited seal and immediate update.')
    if phase=='population':
        p.require(same(values['population'],row['population']),
            'The population context must expose the exact sealed population used for this update.')
    return values


def request_identity(request,profile,protocol):
    p.require(same(request['protocol'],protocol) and same(request['warmStart'],profile['warmStart']) and
        same(request['inheritedCalibration'],profile['inheritedCalibration']),
        'Every numerical operation retains its exact new protocol, warm start and inherited envelope.')
    for key in ('originalBundle','projection'):
        if key in request:
            p.require(same(request[key],profile[key]),'Materialization retains its original parameter containers and projection.')


def common_update(pins,request,protocol,plan,profile,warm,number,previous):
    request_identity(request,profile,protocol)
    p.require(same(request['center'],previous) and same(request['directions'],profile['directions'][number-1]),
        'Only the immediate prepared-direction successor of the admitted center may be updated.')
    center=c.Center.from_record(subject(pins,request['center'],True),warm)
    directions=es.DirectionTable.from_record(subject(pins,request['directions'],True));c.bind(center,directions)
    p.require(center.step==number-1,'A continuation cannot skip or reset its original global coordinates.')
    population=subject(pins,request['population'],True)
    p.require(population['protocolSha256']==protocol['sha256'] and
        population['inheritedCalibrationSha256']==profile['inheritedCalibration']['sha256'] and
        population['worldBankSha256']==es.digest(plan.bank(number)['worldBank']),
        'The sealed population retains its exact protocol, original selector and full prospective bank.')
    return center,directions,population


def diagnostic(pins,row,profile,protocol,clock,number):
    panel=profile.get('diagnosticPanel')
    p.require((row['diagnostic'] is None)==(panel is None),
        'Diagnostic presence is fixed by the frozen profile, never by observed movement or fitness.')
    if panel is None:return
    receipt,request=numeric(pins,row['diagnostic'],'diagnose',protocol,clock)
    request_identity(request,profile,protocol)
    p.require(same(request['center'],row['outputCenter']) and same(request['publicPanel'],panel) and
        row['startedEpoch']<=receipt['completedEpoch']<=row['finishedEpoch'],
        'The optional diagnostic consumes this exact completed mean and original fixed panel.')
    movement=pins.read(Path(row['diagnostic']['path']).parent/base.safe(receipt['outputs']['movement']))
    p.require(movement['kind']=='coordinated-es-continuation-movement-1' and movement['step']==number and
        movement['protocolSha256']==protocol['sha256'] and movement['centerSha256']==row['outputCenter']['sha256'] and
        movement['inheritedCalibrationSha256']==profile['inheritedCalibration']['sha256'] and
        set(movement['heads'])=={'EXPANSION','TOKEN'} and movement['diagnosticOnly'] is True and
        movement['eligibilityGate'] is False and all(item['diagnosticOnly'] is True and
            item['eligibilityGate'] is False for item in movement['heads'].values()),
        'Fixed-panel movement remains descriptive and cannot select or stop a continuation.')


def generation(pins,protocol,plan,profile,warm,clock,directory,row,number,previous,previous_update,previous_finished):
    p.exact(row,('kind','complete','generation','protocolSha256','scopeSha256','warmStartSha256','ecologySha256',
        'inputCenter','outputCenter','directions','bank','population','inheritedCalibration','controls','populations',
        'fitness','update','diagnostic','startedEpoch','finishedEpoch','elapsedSeconds'),'Completed continuation generation')
    p.require(row['kind']==p.PREFIX+'-generation-receipt-1' and row['complete'] is True and
        type(row['generation']) is int and row['generation']==number and row['protocolSha256']==protocol['sha256'] and
        row['scopeSha256']==plan.scope.scope_sha256 and row['warmStartSha256']==warm.sha256 and
        row['ecologySha256']==plan.record['evaluation']['ecologySha256'] and
        previous_finished<=row['startedEpoch']<row['finishedEpoch']<=clock.deadline and
        row['elapsedSeconds']==row['finishedEpoch']-row['startedEpoch'],
        'Each complete continuation retains its exact index, parameter origin, evaluation ecology and original clock.')
    p.require(same(row['inputCenter'],previous) and same(row['directions'],profile['directions'][number-1]) and
        same(row['inheritedCalibration'],profile['inheritedCalibration']),
        'The completed chain cannot replace its immediate predecessor, fixed directions or inherited selector.')
    for key in ('controls','populations'):
        p.require(len(row[key])==8 and len({item['sha256'] for item in row[key]})==8,
            'All eight distinct original control and mutant shard receipts must remain explicit.')
        for item in row[key]:pins.subject(item)  # Native conservation belongs to the outer owner.
    p.require(subject(pins,row['bank'],True)==plan.bank(number)['worldBank'],
        'The complete prospective world bank must remain unchanged.')
    for phase,name in (('controls','control-context'),('population','portable')):
        consumed=context(pins,directory/name,plan,protocol,profile,number,phase,row,previous_update)
        for item in row['controls' if phase=='controls' else 'populations']:
            retained=subject(pins,item)['subjects']
            for key,expected in consumed.items():
                if key in ('arena','originProof'):continue  # Native owner binds the actual named host.
                p.require((expected is None and retained[key] is None) or
                    (expected is not None and retained[key] is not None and same(retained[key],expected)),
                    'Native shards must consume the same portable coordinator subject: '+key)
    materialized,request=numeric(pins,file_subject(pins,directory/'materialize/finished.json'),'materialize',protocol,clock)
    request_identity(request,profile,protocol)
    p.require(all(same(request[key],row[target]) for key,target in
        (('center','inputCenter'),('directions','directions'),('worldBank','bank'))) and
        row['startedEpoch']<=materialized['completedEpoch']<=row['finishedEpoch'],
        'Materialization must consume the exact current coordinates, directions and original world bank.')
    population=subject(pins,row['population'],True)
    p.require(row['population']['sha256']==materialized['artifacts']['population.json'] and
        population['materializationSha256']==materialized['artifacts']['materialization.json'],
        'The published population must retain the exact completed materialization bytes.')
    finished,request=numeric(pins,file_subject(pins,directory/'update/finished.json'),'update',protocol,clock)
    p.require(all(same(request[key],row[target]) for key,target in
        (('center','inputCenter'),('directions','directions'),('population','population'),('fitness','fitness'))) and
        row['startedEpoch']<=finished['completedEpoch']<=row['finishedEpoch'] and
        row['update']['sha256']==finished['artifacts']['update.json'] and
        row['outputCenter']['sha256']==finished['artifacts']['center.json'],
        'The wrapper names the exact completed win-only transaction and its two immutable outputs.')
    center,directions,population=common_update(pins,request,protocol,plan,profile,warm,number,previous)
    # All original seal/protocol joins precede semantic fitness decoding.
    result=c.update(center,directions,population,subject(pins,row['fitness'],True))
    p.require(result==subject(pins,row['update'],True) and result['center']==subject(pins,row['outputCenter'],True),
        'The exact win-share normalized update and global-radius successor must reproduce without model evaluation.')
    diagnostic(pins,row,profile,protocol,clock,number)
    return dict(generation=number,worlds=256,members=32,inputCenter=row['inputCenter'],outputCenter=row['outputCenter'],
        outputCenterSha256=row['outputCenter']['sha256'],update=row['update'],population=row['population'],
        inheritedCalibration=row['inheritedCalibration'],gradientRms=result['gradientRms'],
        centerRms=result['centerRms'],radiusDivisor=result['radiusDivisor'])


def chain(pins,protocol,plan,profile,warm,root,clock,count,expected):
    actual={path.name for path in (root/'generations').iterdir() if path.name!='.DS_Store'}
    p.require(actual=={f'generation-{number}' for number in range(1,expected+1)},
        'Generation directories must retain the exact finite completed/stopped prefix.')
    rows=[];raw=[];previous=profile['initialCenter'];update=None;finished=clock.start
    for number in range(1,count+1):
        directory=root/'generations'/f'generation-{number}';row=pins.read(directory/'receipt.json')
        rows.append(generation(pins,protocol,plan,profile,warm,clock,directory,row,number,previous,update,finished))
        raw.append(row);previous=row['outputCenter'];update=row['update'];finished=row['finishedEpoch']
    return rows,raw,previous


def stopped(pins,terminal,root,protocol,plan,profile,warm,clock,previous):
    reason=terminal['reason'];number=terminal['completedGenerations']+1;item=terminal['numericStop']
    p.require(Path(item['path']).resolve()==(root/'generations'/f'generation-{number}'/'update/stopped.json').resolve(),
        'A stopped continuation identifies its original next finite win update.')
    receipt,request=numeric(pins,item,'update',protocol,clock,stopped=True)
    p.require(not (Path(item['path']).parent/'center.json').exists() and
        reason in ('zero-gradient','nonfinite-arithmetic') and
        (receipt['reason']=='zero-gradient' if reason=='zero-gradient' else receipt['reason'].startswith('nonfinite-')),
        'Only exact arithmetic stops without a consumable successor are admitted.')
    center,directions,population=common_update(pins,request,protocol,plan,profile,warm,number,previous)
    try:c.update(center,directions,population,subject(pins,request['fitness'],True))
    except es.OperationalStop as failure:
        p.require(failure.reason==receipt['reason'],'The declared arithmetic stop must reproduce exactly.')
    else:raise ValueError('A successful win update cannot be relabeled an operational stop.')


def clock_record(pins,root,protocol):
    wire=pins.read(root/'clock.json')
    p.require(wire['kind']==p.PREFIX+'-clock-1' and wire['protocolSha256']==protocol['sha256'],
        'The continuation retains its exact original protocol and shared clock.')
    return wire,p.Clock(wire['startedEpoch'],wire['deadlineEpoch'])


def result(pins,protocol,profile_subject,plan,profile,rows,status,previous):
    pins.unchanged();complete=status=='completed'
    return dict(kind=p.PREFIX+'-numerical-audit-1',passed=True,complete=complete,status=status,
        protocolSha256=protocol['sha256'],profile=profile_subject,scopeSha256=plan.scope.scope_sha256,
        warmStartSha256=plan.record['warmStart']['identitySha256'],ecologySha256=plan.record['evaluation']['ecologySha256'],
        inheritedCalibrationSha256=profile['inheritedCalibration']['sha256'],objective=c.OBJECTIVE,
        completedGenerations=len(rows),rows=rows,eligibleFinalCenter=previous if complete else None,
        artifactPins=dict(pins.values),execution=ZERO_EXECUTION,rngRegenerated=False,nativeArchivesRecomputed=False)


def verify_learning(protocol_subject,run,*,profile_subject,pins=None):
    """Exact sixteen-update admission before final publication; no terminal is invented or read."""
    pins=pins if pins is not None else Pins();plan=p.Plan.from_file(pins.subject(protocol_subject));root=Path(run).absolute()
    _,clock=clock_record(pins,root,protocol_subject);now=time.time()
    p.require(clock.start<=now<clock.deadline,'Learning admission retains the original active science clock.')
    pins.deadline=min(pins.deadline if pins.deadline is not None else float('inf'),clock.deadline)
    profile,warm=inputs(pins,protocol_subject,plan,profile_subject)
    rows,raw,previous=chain(pins,protocol_subject,plan,profile,warm,root,clock,16,16)
    p.require(raw[-1]['finishedEpoch']<=now,'Future-dated updates cannot admit final publication.')
    p.require(base.prefix(pins,root/'generations/generation-2/operational-prefix.json',raw,now,clock,
        protocol_subject['sha256'],plan_owner=p)['passed'],'Final publication requires the original successful resource gate.')
    return result(pins,protocol_subject,profile_subject,plan,profile,rows,'completed',previous)


def verify(protocol_subject,run,*,profile_subject,pins=None):
    """Terminal wrapper; the caller owns native evidence, final eligibility and process release."""
    pins=pins if pins is not None else Pins();plan=p.Plan.from_file(pins.subject(protocol_subject));root=Path(run).absolute()
    terminal=pins.read(root/'terminal.json');wire,clock=clock_record(pins,root,protocol_subject)
    p.require(terminal['kind']==p.PREFIX+'-terminal-1' and terminal['host']=='DESKTOP' and terminal['clock']==wire,
        'This numerical primitive admits only the sole Desktop owner under its original shared clock.')
    pins.deadline=min(pins.deadline if pins.deadline is not None else float('inf'),clock.audit_deadline(terminal['updatedEpoch']))
    pins.check_clock()
    p.require(terminal['status'] in ('completed','stopped') and clock.start<=terminal['updatedEpoch']<=clock.deadline,
        'Failed or expired science is not a complete or honest stopped numerical chain.')
    count=p.integer(terminal['completedGenerations'],0,16,'Completed continuation generations')
    p.require((terminal['status']=='completed' and count==16) or (terminal['status']=='stopped' and count<16),
        'Only all sixteen updates can complete; a stopped prefix cannot become a candidate.')
    profile,warm=inputs(pins,protocol_subject,plan,profile_subject)
    owner=pins.read(root/'worker-owner.json')
    p.require(owner['kind']==p.PREFIX+'-worker-owner-1' and owner['profile']==profile_subject and
        all(type(owner[key]) is int and owner[key]>0 for key in ('pid','processGroup')),
        'The original worker owner must retain the explicit audited profile and typed process provenance.')
    expected=count+(1 if terminal['status']=='stopped' and terminal['reason']!='prefix-resource-gate' else 0)
    rows,raw,previous=chain(pins,protocol_subject,plan,profile,warm,root,clock,count,expected)
    p.require(not raw or raw[-1]['finishedEpoch']<=terminal['updatedEpoch'],
        'The original terminal must follow every admitted numerical completion.')
    if count>=2:
        gate=base.prefix(pins,root/'generations/generation-2/operational-prefix.json',raw,terminal['updatedEpoch'],
            clock,protocol_subject['sha256'],plan_owner=p)
        p.require(gate['passed'] is (terminal.get('reason')!='prefix-resource-gate'),
            'Only the exact original resource gate may stop its two-generation prefix.')
    if terminal['status']=='stopped':
        if terminal['reason']=='prefix-resource-gate':p.require(count==2,'The resource gate has exactly two completed generations.')
        else:stopped(pins,terminal,root,protocol_subject,plan,profile,warm,clock,previous)
    return result(pins,protocol_subject,profile_subject,plan,profile,rows,terminal['status'],previous)
