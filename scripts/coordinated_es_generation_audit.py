"""Pure Desktop numerical-chain audit; native archives and host release belong to the caller.

No NumPy, Torch, model decoding, RNG regeneration, forward or process execution. The
optional Pins ledger is shared with the outer terminal auditor, including its deadline.
"""
from pathlib import Path
import time

import coordinated_es_math as es
import coordinated_es_calibration as cal
import coordinated_es_plan as p
from coordinated_es_storage import prefix_retention
from token_paired_campaign_audit import Pins
from token_paired_storage import safe

ZERO_EXECUTION=dict(newGames=0,actualFits=0,optimizerSteps=0)
CALIBRATION_STOPS={'calibration-insufficient-worlds','calibration-positive-gap-mass',
    'calibration-small-signed-quantile','calibration-scale-bounds','calibration-movement-bounds'}


def subject(pins,value,canonical=False):
    path=pins.subject(value);record=pins.read(path)
    if canonical:p.require(es.canonical(record)==path.read_bytes(),'Numerical subjects retain canonical bytes without LF.')
    return record


def file_subject(pins,path):return dict(path=str(Path(path).absolute()),sha256=pins.pin(path))


def same(left,right):return left['sha256']==right['sha256']


def calibration_owner(plan):
    configuration=plan.record['calibration']
    if configuration['kind']==cal.VERSION:return cal
    import coordinated_es_calibration_v2 as v2
    p.require(configuration==v2.PROTOCOL_RECIPE,'Pure audit requires the exact declared calibration version.')
    return v2


def numeric(pins,receipt_subject,verb,protocol,clock,*,stopped=False,prefix='coordinated-es'):
    """Verify the entire immutable transaction while treating actor files as opaque bytes."""
    receipt=subject(pins,receipt_subject,True);root=Path(receipt_subject['path']).parent
    expected=prefix+('-numeric-stop-1' if stopped else '-numeric-complete-1')
    p.require(receipt['kind']==expected and receipt['verb']==verb and receipt['complete'] is (not stopped) and
        receipt['passed'] is (not stopped) and receipt['execution']==ZERO_EXECUTION,
        'A numerical receipt must retain its exact operation and zero execution counters.')
    request=subject(pins,receipt['request'])
    p.require(request['kind']==f'{prefix}-{verb}-request-1' and same(request['protocol'],protocol) and
        request['deadlineEpoch']==clock.deadline,'The request must retain the original raw protocol and clock.')
    started=pins.read(root/'started.json')
    p.require(started['kind']==prefix+'-numeric-started-1' and started['verb']==verb and
        started['request']==receipt['request'] and started['deadlineEpoch']==clock.deadline,
        'The original numerical startup evidence must remain bound.')
    completed=receipt['stoppedEpoch'] if stopped else receipt['completedEpoch']
    p.require(clock.start<=started['startedEpoch']<=completed<=clock.deadline,
        'A numerical operation cannot renew or outlive the original science clock.')
    if stopped:p.require(receipt['deadlineEpoch']==clock.deadline,'The stop clock changed.')
    else:p.require(receipt['protocolSha256']==protocol['sha256'] and
        receipt['sourceOwners']==request['sourceOwners'],'A completion must bind its actual imported source owners.')
    files=set()
    for path in root.rglob('*'):
        if path.name=='.DS_Store':continue
        p.require(not path.is_symlink(),'Numerical output evidence cannot introduce symlinked subjects.')
        if path.is_file():files.add(path.relative_to(root).as_posix())
    terminal='stopped.json' if stopped else 'finished.json'
    p.require(files==set(receipt['artifacts'])|{terminal},'The numerical output inventory must be exact and complete.')
    for name,digest in receipt['artifacts'].items():pins.pin(root/safe(name),digest)
    p.require(receipt['inputPins'] and receipt['inputPins'].get(receipt['request']['path'])==receipt['request']['sha256'],
        'Every numerical transaction must retain its exact original request bytes.')
    for path,digest in receipt['inputPins'].items():pins.pin(path,digest)
    p.require(request['sourceOwners'] and all(receipt['inputPins'].get(path)==digest
        for path,digest in request['sourceOwners'].items()),'Imported source owners must be members of the input closure.')
    for value in (request['protocol'],request['numerical']):
        p.require(receipt['inputPins'].get(value['path'])==value['sha256'],'Required numerical inputs are unbound.')
    if not stopped:
        p.require(receipt['outputs'] and all(safe(name) in receipt['artifacts'] for name in receipt['outputs'].values()),
            'A consumable output must belong to the exact completed inventory.')
    return receipt,request


def calibration(pins,value,protocol,plan):
    owner=calibration_owner(plan)
    record=subject(pins,value,True);root=Path(value['path']).parent
    p.require(record['kind']==getattr(owner,'OUTER_VERSION','coordinated-es-calibration-1') and record['passed'] is True and
        record['complete'] is True and record['protocolSha256']==protocol['sha256'] and
        record['originalBundleSha256']==plan.record['original']['bundleSha256'] and
        record['projectionSha256']==plan.record['original']['projectionSha256'] and
        record['semanticFitnessDecoded'] is False and record['calibrationAttempts']==1 and
        set(record['heads'])==set(record['scales'])=={'EXPANSION','TOKEN'},
        'The sole outcome-free calibration must bind both original heads before any fitness decode.')
    for head in ('EXPANSION','TOKEN'):
        receipt=record['heads'][head];path=root/f'gap-{head}.json'
        pins.pin(path,receipt['panelSha256']);panel=cal.GapPanel(path.read_bytes())
        p.require(owner.calibrate(panel)==receipt and receipt['head']==head and
            receipt['scale']==record['scales'][head] and receipt['subjects']==dict(
                protocolSha256=protocol['sha256'],originalModelSha256=plan.record['original']['heads'][head]['actorSha256'],
                projectionSha256=record['projectionSha256'],directionsSha256=record['directionsSha256'],
                prescribedMeanSha256=record['prescribedMeanSha256'],publicPanelSha256=record['panelSha256']),
            'Calibration arithmetic and actual-score movement witnesses must reproduce exactly.')
    return record


def scope(plan):
    original=plan.record['original']
    return es.Scope(original['scopeSha256'],original['bundleSha256'],original['projectionSha256'])


def common_update(pins,request,protocol,plan,number,previous):
    p.require(same(request['protocol'],protocol) and (previous is None or same(request['center'],previous)),
        'Only the immediate prior same-scope center may enter an update.')
    center=es.Center.from_record(subject(pins,request['center'],True))
    directions=es.DirectionTable.from_record(subject(pins,request['directions'],True))
    p.require(center.scope==directions.scope==scope(plan) and center.generation==number-1 and
        directions.generation==number,'Update center, directions, generation and original scope cannot drift.')
    population=subject(pins,request['population'],True)
    p.require(population['protocolSha256']==protocol['sha256'] and population['worldIds']==list(range(256)) and
        population['worldBankSha256']==es.digest(plan.bank(number)['worldBank']),
        'The complete ordered population must use its original fixed global bank.')
    return center,directions,population


def generation(pins,protocol,plan,clock,row,number,previous,previous_finished,fixed_calibration):
    es.exact(row,('kind','complete','generation','protocolSha256','scopeSha256','inputCenter','outputCenter',
        'directions','bank','population','calibration','controls','populations','fitness','update','diagnostic',
        'startedEpoch','finishedEpoch','elapsedSeconds'),'Completed generation')
    p.require(row['kind']=='coordinated-es-generation-receipt-1' and row['complete'] is True and
        row['generation']==number and row['protocolSha256']==protocol['sha256'] and
        row['scopeSha256']==plan.record['original']['scopeSha256'] and
        previous_finished<=row['startedEpoch']<=row['finishedEpoch']<=clock.deadline and
        row['elapsedSeconds']==row['finishedEpoch']-row['startedEpoch'] and row['elapsedSeconds']>0,
        'A complete generation must retain its exact index, original clock and inclusive duration.')
    p.require(len(row['controls'])==len(row['populations'])==8,
        'All eight control and population archive subjects must remain explicit.')
    for key in ('controls','populations'):
        p.require(len({value['sha256'] for value in row[key]})==8,'Duplicate shard receipts cannot replace a complete cohort.')
        for value in row[key]:pins.subject(value)  # Outer owner verifies native members and global conservation.
    bank=subject(pins,row['bank'],True)
    p.require(bank==plan.bank(number)['worldBank'],'The retained bank must equal the entire prospective generation bank.')
    p.require(fixed_calibration is None or same(row['calibration'],fixed_calibration),'Calibration cannot be renewed.')
    sealed=calibration(pins,row['calibration'],protocol,plan)
    directory=Path(row['update']['path']).parent.parent
    if number==1:
        cal_finished,cal_request=numeric(pins,file_subject(pins,Path(row['calibration']['path']).parent/'finished.json'),
            'calibrate',protocol,clock)
        p.require(cal_request['materialization']['sha256']==sealed['probePopulationSha256'] and
            cal_request['publicPanel']['sha256']==sealed['panelSha256'] and
            cal_request['directions']['sha256']==sealed['directionsSha256'] and
            cal_request['originalBundle']['sha256']==plan.record['original']['bundleSha256'] and
            cal_request['projection']['sha256']==plan.record['original']['projectionSha256'] and
            row['startedEpoch']<=cal_finished['completedEpoch']<=row['finishedEpoch'],
            'The fixed seal must bind its original materialization, public panel and directions.')
        directions=es.DirectionTable.from_record(subject(pins,cal_request['directions'],True))
        p.require(es.digest(es.prescribed_mean_probe(directions))==sealed['prescribedMeanSha256'],
            'The sole prescribed mean probe must use the fixed optimizer and G1 directions.')
    materialized,materialize_request=numeric(pins,file_subject(pins,directory/'materialize/finished.json'),
        'materialize',protocol,clock)
    p.require(same(materialize_request['center'],row['inputCenter']) and
        same(materialize_request['directions'],row['directions']) and same(materialize_request['worldBank'],row['bank']) and
        materialize_request['worldIds']==list(range(256)) and
        materialize_request['originalBundle']['sha256']==plan.record['original']['bundleSha256'] and
        materialize_request['projection']['sha256']==plan.record['original']['projectionSha256'] and
        row['startedEpoch']<=materialized['completedEpoch']<=row['finishedEpoch'] and
        ((materialize_request['calibration'] is None and number==1) or
         (number>1 and same(materialize_request['calibration'],row['calibration']))),
        'Materialization must consume its exact original center, directions, world bank and sealed scale.')
    update_finished,request=numeric(pins,file_subject(pins,Path(row['update']['path']).parent/'finished.json'),'update',protocol,clock)
    p.require(all(same(request[key],row[name]) for key,name in
        (('center','inputCenter'),('directions','directions'),('population','population'),('fitness','fitness'))) and
        row['startedEpoch']<=update_finished['completedEpoch']<=row['finishedEpoch'],
        'The generation receipt must name the exact completed numerical transaction inputs.')
    center,directions,population=common_update(pins,request,protocol,plan,number,previous)
    p.require(population['calibrationSha256']==row['calibration']['sha256'] and
        population['materializationSha256']==materialized['artifacts']['materialization.json'],
        'Executable population must retain its exact materialization and original calibration.')
    # Fitness is semantically decoded only after the sole complete calibration is validated.
    result=es.update(center,directions,population,subject(pins,row['fitness'],True))
    p.require(result==subject(pins,row['update'],True) and result['center']==subject(pins,row['outputCenter'],True),
        'Raw-return global ES and the exact next center must reproduce without a forward or fit.')
    diagnostic,diagnostic_request=numeric(pins,row['diagnostic'],'diagnose',protocol,clock)
    p.require(same(diagnostic_request['center'],row['outputCenter']) and
        same(diagnostic_request['calibration'],row['calibration']) and
        diagnostic_request['publicPanel']['sha256']==sealed['panelSha256'] and
        row['startedEpoch']<=diagnostic['completedEpoch']<=row['finishedEpoch'],
        'Live movement remains tied to the exact current mean and original panel.')
    movement=pins.read(Path(row['diagnostic']['path']).parent/diagnostic['outputs']['movement'])
    p.require(movement['kind']=='coordinated-es-center-movement-1' and movement['generation']==number and
        movement['protocolSha256']==protocol['sha256'] and set(movement['heads'])=={'EXPANSION','TOKEN'} and
        movement['centerSha256']==row['outputCenter']['sha256'] and
        movement['calibrationSha256']==row['calibration']['sha256'] and movement['diagnosticOnly'] is True and
        movement['eligibilityGate'] is False and all(item['diagnosticOnly'] is True and
            item['eligibilityGate'] is False for item in movement['heads'].values()),
        'Actual live-head movement cannot become a hidden candidate or continuation gate.')
    return dict(generation=number,worlds=256,members=32,inputCenter=row['inputCenter'],
        outputCenter=row['outputCenter'],outputCenterSha256=row['outputCenter']['sha256'],
        update=row['update'],population=row['population'],calibration=row['calibration'],
        gradientRms=result['gradientRms'],centerRms=result['centerRms'],radiusDivisor=result['radiusDivisor'])


def prefix(pins,path,rows,upper_epoch,clock,protocol_sha,*,plan_owner=p):
    record=pins.read(path)
    keys=set(plan_owner.prefix_gate(1,[1,1],{host:dict(sharedBaseBytes=0,generationDeltaBytes=[0,0]) for host in p.HOSTS}))
    p.require(set(record)==keys|{'censusAdjustmentBytes','censuses'},'The operational prefix has exact original fields.')
    actual=plan_owner.prefix_gate(record['remainingSeconds'],record['generationSeconds'],record['retention'])
    p.require(all(record[key]==value for key,value in actual.items()) and
        record['generationSeconds']==[row['elapsedSeconds'] for row in rows[:2]] and
        rows[1]['finishedEpoch']<=clock.deadline-record['remainingSeconds']<=upper_epoch,
        'The two-generation resource gate must reproduce from original inclusive costs and clock.')
    p.require(set(record['censuses'])==set(p.HOSTS),'Resource censuses must retain both exact named hosts.')
    retention={host:prefix_retention(subject(pins,item),protocol_sha,host,2)
        for host,item in record['censuses'].items()}
    size=Path(path).stat().st_size
    retention['DESKTOP']['generationDeltaBytes'][1]+=size
    p.require(record['censusAdjustmentBytes']==size and record['retention']==retention,
        'Projected retention must derive from both sealed censuses and the exact prefix receipt byte size.')
    return record


def stopped(pins,terminal,root,protocol,plan,clock,previous):
    reason=terminal['reason'];number=terminal['completedGenerations']+1
    record_subject=terminal['numericStop'];record=subject(pins,record_subject,True)
    verb='calibrate' if reason=='calibration-failed' else 'update'
    p.require(Path(record_subject['path']).resolve()==(root/'generations'/f'generation-{number}'/verb/'stopped.json').resolve(),
        'A terminal stop must identify its original next finite operation.')
    receipt,request=numeric(pins,record_subject,verb,protocol,clock,stopped=True)
    p.require(not (Path(record_subject['path']).parent/'center.json').exists(),
        'A stopped numerical operation cannot leave a consumable next center.')
    if verb=='calibrate':
        owner=calibration_owner(plan)
        p.require(number==1 and receipt['reason'] in getattr(owner,'STOPS',CALIBRATION_STOPS),
            'Only the original single calibration may stop before all fitness.')
        p.require(not (root/'generations/generation-1/fitness.json').exists() and
            not (root/'generations/generation-1/portable/population.json').exists(),
            'Calibration failure cannot decode fitness or expose an executable population.')
        panel=subject(pins,request['publicPanel'])
        directions=es.DirectionTable.from_record(subject(pins,request['directions'],True))
        materialization=subject(pins,request['materialization'],True)
        p.require(request['originalBundle']['sha256']==plan.record['original']['bundleSha256'] and
            request['projection']['sha256']==plan.record['original']['projectionSha256'] and
            directions.scope==scope(plan) and directions.generation==1 and
            materialization['kind']=='coordinated-es-materialization-1' and materialization['generation']==1 and
            materialization['scope']==scope(plan).record and materialization['directionsSha256']==request['directions']['sha256'] and
            materialization['worldBankSha256']==es.digest(plan.bank(1)['worldBank']) and
            materialization['worldIds']==list(range(256)) and len(materialization['members'])==32 and
            panel['kind']=='coordinated-es-public-panel-1' and panel['protocolSha256']==protocol['sha256'] and
            panel['originalBundleSha256']==plan.record['original']['bundleSha256'] and len(panel['worlds'])==256,
            'An honest calibration stop must retain the actual original scope, probes and complete public panel.')
        if receipt['reason']=='calibration-insufficient-worlds':
            p.require(any(sum(bool(world['heads'][head]) for world in panel['worlds'])<128
                for head in ('EXPANSION','TOKEN')),'Coverage stops require an actually missing public prerequisite.')
        else:
            failures=[]
            for head in ('EXPANSION','TOKEN'):
                path=Path(record_subject['path']).parent/f'gap-{head}.json'
                if not path.exists():continue
                pins.pin(path);gap=cal.GapPanel(path.read_bytes())
                try:owner.calibrate(gap)
                except es.OperationalStop as failure:failures.append(failure.reason)
            p.require(failures==[receipt['reason']],'The retained scalar gap evidence must reproduce the declared stop.')
    else:
        p.require(reason in ('zero-gradient','nonfinite-arithmetic') and
            (receipt['reason']=='zero-gradient' if reason=='zero-gradient' else receipt['reason'].startswith('nonfinite-')),
            'Only exact declared global arithmetic stops are admitted.')
        calibration_subject=file_subject(pins,root/'generations/generation-1/calibrate/calibration.json')
        calibration(pins,calibration_subject,protocol,plan)
        center,directions,population=common_update(pins,request,protocol,plan,number,previous)
        p.require(population['calibrationSha256']==calibration_subject['sha256'],'Stopped update changed the original calibration.')
        try:es.update(center,directions,population,subject(pins,request['fitness'],True))
        except es.OperationalStop as failure:
            p.require(failure.reason==receipt['reason'],'The declared arithmetic stop must reproduce exactly.')
        else:raise ValueError('A numerically successful update cannot be relabeled an operational stop.')


def chain(pins,protocol_subject,plan,root,clock,count,expected):
    actual={path.name for path in (root/'generations').iterdir() if path.name!='.DS_Store'}
    p.require(actual=={f'generation-{number}' for number in range(1,expected+1)},
        'Generation directories must retain the exact finite completed/stopped prefix.')
    rows=[];raw_rows=[];previous=None;previous_finished=clock.start;fixed_calibration=None
    for number in range(1,count+1):
        row=pins.read(root/'generations'/f'generation-{number}'/'receipt.json')
        rows.append(generation(pins,protocol_subject,plan,clock,row,number,previous,previous_finished,fixed_calibration))
        raw_rows.append(row);previous=row['outputCenter'];previous_finished=row['finishedEpoch'];fixed_calibration=row['calibration']
    return rows,raw_rows,previous


def result(pins,protocol_subject,rows,status,previous):
    pins.unchanged();complete=status=='completed'
    return dict(kind='coordinated-es-numerical-audit-1',passed=True,complete=complete,status=status,
        protocolSha256=protocol_subject['sha256'],completedGenerations=len(rows),rows=rows,
        eligibleFinalCenter=previous if complete else None,
        artifactPins=dict(pins.values),execution=ZERO_EXECUTION,rngRegenerated=False,nativeArchivesRecomputed=False)


def clock_record(pins,root,protocol_subject):
    wire=pins.read(root/'clock.json')
    p.require(wire['kind']=='coordinated-es-clock-1' and wire['protocolSha256']==protocol_subject['sha256'],
        'Original protocol and shared clock bytes must be unchanged.')
    return wire,p.Clock(wire['startedEpoch'],wire['deadlineEpoch'])


def verify_learning(protocol_subject,run,*,pins=None):
    """Exact16-generation admission before final publication; no terminal is read or invented."""
    pins=pins if pins is not None else Pins();plan=p.Plan.from_file(pins.subject(protocol_subject));root=Path(run).absolute()
    _,clock=clock_record(pins,root,protocol_subject);now=time.time()
    p.require(clock.start<=now<clock.deadline,'Learning admission retains the original active science clock.')
    pins.deadline=min(pins.deadline if pins.deadline is not None else float('inf'),clock.deadline)
    rows,raw_rows,previous=chain(pins,protocol_subject,plan,root,clock,16,16)
    p.require(raw_rows[-1]['finishedEpoch']<=now,'Future-dated numerical completion cannot admit final publication.')
    p.require(prefix(pins,root/'generations/generation-2/operational-prefix.json',raw_rows,now,clock,protocol_subject['sha256'])['passed'],
        'Final publication requires the original successful resource gate.')
    return result(pins,protocol_subject,rows,'completed',previous)


def verify(protocol_subject,run,*,pins=None):
    """Desktop-only terminal primitive; the caller audits native receipts and final-screen eligibility."""
    pins=pins if pins is not None else Pins();protocol_path=pins.subject(protocol_subject)
    plan=p.Plan.from_file(protocol_path);root=Path(run).absolute();terminal=pins.read(root/'terminal.json')
    p.require(terminal['kind']=='coordinated-es-terminal-1' and terminal['host']=='DESKTOP',
        'This complete-fitness primitive is for the sole Desktop numerical owner.')
    clock_wire,clock=clock_record(pins,root,protocol_subject)
    p.require(terminal['clock']==clock_wire,'Original protocol and shared clock bytes must be unchanged.')
    pins.deadline=min(pins.deadline if pins.deadline is not None else float('inf'),clock.audit_deadline(terminal['updatedEpoch']))
    pins.check_clock()
    p.require(terminal['status'] in ('completed','stopped') and clock.start<=terminal['updatedEpoch']<=clock.deadline,
        'Failed or expired science is never admitted as a valid completed or honest stopped numerical chain.')
    count=p.integer(terminal['completedGenerations'],0,16,'Completed ES generations')
    p.require((terminal['status']=='completed' and count==16) or (terminal['status']=='stopped' and count<16),
        'Only all16 means can complete; a stopped prefix cannot be a shortened candidate.')
    expected=count+(1 if terminal['status']=='stopped' and terminal['reason']!='prefix-resource-gate' else 0)
    rows,raw_rows,previous=chain(pins,protocol_subject,plan,root,clock,count,expected)
    if count>=2:
        gate=prefix(pins,root/'generations/generation-2/operational-prefix.json',raw_rows,terminal['updatedEpoch'],clock,protocol_subject['sha256'])
        p.require(gate['passed'] is (terminal.get('reason')!='prefix-resource-gate'),
            'Only the exact resource gate may stop its two-generation prefix.')
    if terminal['status']=='stopped':
        if terminal['reason']=='prefix-resource-gate':p.require(count==2,'Resource prefix has exactly two complete generations.')
        else:stopped(pins,terminal,root,protocol_subject,plan,clock,previous)
    return result(pins,protocol_subject,rows,terminal['status'],previous)
