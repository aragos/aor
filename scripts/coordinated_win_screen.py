"""Two raw reference regressions and the sole final/start/control supported mixed screen.

Archive, fixed-range transfer, canonical reproduction and complete-bank execution stay with the
existing ES screen owner. This adapter owns only the new typed subject, composition and gate joins.
"""
from pathlib import Path
import sys

import coordinated_es_screen as base
import coordinated_es_storage as storage
import coordinated_win_admission as admission
import coordinated_win_handoff as handoff
import coordinated_win_numeric_stage as numeric
import coordinated_win_plan as p
import coordinated_win_process as execution
import coordinated_win_report as report
from token_paired_campaign_audit import Pins

KIND=p.PREFIX+'-screen-1'
ROLES=report.ROLES
CAMPAIGN_MODULE='coordinated_win_campaign'
SNAPSHOTS=dict(base.SNAPSHOTS,originProof='origin-proof.json')
HISTORICAL_NATIVE_INDICES=base.HISTORICAL_NATIVE_INDICES
verify_report=report.verify


def contract():return sys.modules[__name__]


def scope_identity(plan):
    return dict(scopeSha256=plan.scope.scope_sha256,ecologySha256=plan.record['evaluation']['ecologySha256'])


def original_bundle_sha(plan):return plan.scope.original_bundle_sha256


def final_bindings(plan,protocol_sha,final_subject,pins):
    subjects,bindings=admission.final_bindings(plan,protocol_sha,final_subject,pins)
    return subjects,dict(bindings,protocolSha256=protocol_sha)


def source(plan,profile,chunk,final,historical):
    return dict(kind=KIND,host=profile['host'],originProof=profile['originProof'],arena=profile['arena'],
        admission=final,historical=historical,globalStart=chunk*128,count=128,bank=plan.record['screenBank'])


def publication_identity(plan,protocol_sha,final_sha):
    return dict(protocolSha256=protocol_sha,finalAdmissionSha256=final_sha,**scope_identity(plan),
        warmStartSha256=plan.record['warmStart']['identitySha256'])


def validate_world(plan,world,index,science):
    base.shared.validate_world(plan,world,index,science,kind=KIND,roles=ROLES,opponent_count=4)
    p.require(all(0<game['decisions']<=plan.record['gameLimits']['maximumDecisions'] for game in world['roles'].values()),
        'Every screened role retains the fixed original per-game action ceiling.')


def replay_bindings(plan,bindings):
    return dict(bindings,purchaseF0Sha256=plan.record['evaluation']['references']['purchaseF0Sha256'],
        scales=plan.record['calibration']['scales'])


def replay_role(replay,role,bindings):
    value=replay.get('coordinatedEs')
    if not isinstance(value,dict):return False
    if not (value.get('version')=='coordinated-support-arena-1' and
            value.get('reference')=='complete-joint-token-expansion-composition' and
            value.get('supportProfile')=='known-own-chaos-support-1' and value.get('supportRoles')=='ALL_SEATS_CP' and
            value.get('originalBundle',{}).get('sha256')==bindings['originalBundleSha256'] and
            value.get('baseline',{}).get('sha256')==bindings['purchaseF0Sha256']):return False
    scales={head:1. for head in ('EXPANSION','TOKEN')} if role=='CONTROL' else bindings['scales']
    if value.get('selectors')!={head:dict(kind='incumbent-residual-margin-1',margin=1.,scale=scale)
            for head,scale in scales.items()}:return False
    if role=='CONTROL':return value.get('execution')=='ZERO_REFERENCE' and value.get('roles')=={} and 'calibration' not in value
    expected=bindings['finalBundleSha256' if role=='FINAL' else 'startBundleSha256']
    return (role in ('FINAL','START') and value.get('execution')=='CALIBRATED' and
        set(value.get('roles',{}))=={'CANDIDATE'} and value['roles']['CANDIDATE'].get('sha256')==expected and
        value.get('calibration',{}).get('sha256')==bindings['calibrationSha256'])


def chunk_evidence(plan,chunk,files,manifest,finished,worlds,replays,canonicals,final_sha,historical_sha,bindings):
    p.require(manifest['originProof']['sha256']==plan.record['warmStart']['proofSha256'][p.owner(chunk,True)],
        'Each final shard retains its original host-local parent admission proof.')
    return base.chunk_evidence(plan,chunk,files,manifest,finished,worlds,replays,canonicals,
        final_sha,historical_sha,bindings,contract=contract())


def verify_historical(gate,plan,protocol_sha):
    p.require(gate['kind']==p.PREFIX+'-historical-admission-1' and gate['complete'] is True and
        gate['passed'] is True and gate['zeroFailures'] is True and gate['protocolSha256']==protocol_sha and
        all(gate[key]==value for key,value in scope_identity(plan).items()) and
        gate['originalBundleSha256']==plan.scope.original_bundle_sha256 and
        gate['warmStartSha256']==plan.record['warmStart']['identitySha256'] and
        gate['scientificTerminalExecutions']==gate['attemptedGames']==gate['completedGames']==2 and
        len(gate['controls'])==2,'Both exact raw reference regressions must precede supported fresh games.')
    for expected,row in zip(plan.record['historicalControls'],gate['controls']):
        p.require(all(row[key]==expected[key] for key in ('globalIndex','index','side')) and
            row['canonicalVerified'] is True and row['execution']=='ZERO_REFERENCE' and
            row['purpose']=='RAW_REFERENCE_REGRESSION_ONLY',
            'Original global/chunk-local raw histories cannot become supported-composite strength evidence.')


def historical(plan,profile,protocol,root):
    from coordinated_win_campaign import native_run
    p.require(profile['host']=='DESKTOP' and len(profile['historicalControls'])==2,
        'Only Desktop executes the two original raw reference regressions once.')
    root=Path(root);source_path=root/'historical-source.json';output=root/'historical'
    for expected,row in zip(plan.record['historicalControls'],profile['historicalControls']):
        p.require(all(row[key]==expected[key] for key in ('globalIndex','index','side')) and
            all(row[key]['sha256']==expected[key+'Sha256'] for key in ('source','pair','replay')),
            'Historical globals, local indices and old raw subjects cannot change after preparation.')
    source_value=dict(kind=p.PREFIX+'-historical-source-1',host=profile['host'],protocol=p.subject(protocol),
        arena=profile['originArena'],controls=profile['historicalControls'],
        **{key:profile[key] for key in ('originalBundle','warmStart','originProof','inheritedCalibration')})
    storage.publish_json(source_path,source_value)
    native_run(profile,p.PREFIX+'-historical',source_path,output,root/'historical.log')
    gate=p.read(output/'historical-admission.json');verify_historical(gate,plan,p.file_sha(protocol))
    p.require(gate['sourceSha256']==p.file_sha(source_path),'Raw reference evidence binds its exact original request.')
    for expected,row in zip(profile['historicalControls'],gate['controls']):
        p.require(all(row[field]==expected[key]['sha256'] for key,field in
            (('source','originalSourceSha256'),('pair','originalPairSha256'),('replay','originalReplaySha256'))) and
            p.file_sha(output/storage.safe(row['replayFile']))==row['replaySha256'],
            'Reference regression retains exact source/pair/replay and actual reproduced canonical bytes.')
    return p.subject(output/'historical-admission.json')


def audit_historical(protocol,root,host,pins):return base.audit_historical(protocol,root,host,pins,contract=contract())
def collect(plan,profile,root,chunk,final,historical):return base.collect(plan,profile,root,chunk,final,historical,contract=contract())
def archived(plan,item,pins,bindings):return base.archived(plan,item,pins,bindings,contract=contract())
def complete(plan,profile,protocol,root,receipts,final,historical):
    return base.complete(plan,profile,protocol,root,receipts,final,historical,contract=contract())
def mac(plan,profile,protocol,root,folder,ready):return base.mac(plan,profile,protocol,root,folder,ready,contract=contract())
def audit_completed(protocol,root,host,pins):return base.audit_completed(protocol,root,host,pins,contract=contract())


def finish(plan,profile,protocol,root,center,calibration,historical):
    import coordinated_win_audit as audit
    import coordinated_win_generation_audit as mathematics
    from coordinated_win_campaign import wait_receipts
    root=Path(root);subject=p.subject(protocol);pins=Pins(profile['_clock'].deadline)
    owner=pins.read(root/'worker-owner.json');profile_subject=owner['profile'];pins.subject(profile_subject)
    numerical_audit=storage.publish_json(root/'numerical-audit.json',mathematics.verify_learning(
        subject,root,profile_subject=profile_subject,pins=pins))
    native_audit=storage.publish_json(root/'training-audit.json',audit.training(subject,root,pins=pins))
    result=numeric.invoke(profile,'center',root/'final-materialization',dict(protocol=subject,
        originalBundle=profile['originalBundle'],projection=profile['projection'],warmStart=profile['warmStart'],
        center=center,inheritedCalibration=calibration))
    p.require(result['complete'],'Only complete mean16 materialization can enter the fixed final screen.')
    final=admission.publish(subject,profile,root,root/'final-materialization',numerical_audit,native_audit,root/'final-portable')
    portable=Path(final['path']).parent
    handoff.bundle(root/'publications/screen','screen',{name:p.subject(portable/name) for name in storage.inventory(portable)},
        publication_identity(plan,subject['sha256'],final['sha256']))
    local=[collect(plan,profile,root,chunk,final,historical) for chunk in p.SCREEN_CHUNKS['DESKTOP']]
    remote=wait_receipts(profile,root,screen=True)
    completion=complete(plan,profile,protocol,root,local+remote,final,historical)
    value=p.read(final['path']);bundle=p.subject(admission.resolve(value['finalBundle'],final['path']))
    return dict(status='completed',reason='sixteen-fixed-win-updates-and-final-screen',completedGenerations=16,
        finalAdmission=final,finalBundle=bundle,screenComplete=completion)
