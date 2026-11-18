"""One research-only TOKEN update: immutable policy origin and fresh native worlds.

Numerical execution belongs to the separately frozen numeric owner. This contract
only admits its exact source, original closed parent, paired native policy and counts.
"""
import hashlib
from dataclasses import dataclass
from pathlib import Path
import sys

import local_bid_mixture_contract as mixture
import mac_policy_search as search
import robust_token_campaign as c

KIND='token-focus-step-campaign-1'
PREFIX='token-focus-step'
STUDY=96
METHOD_SHA='1a13821a64de5c1dfdf685f46128969c793a09a03667e6431d55e9c8fe467369'
PARENT_PROTOCOL_SHA='646862f24cc0153fe804c3f7d684fa8b4790ea8947486311e17737ea5a0901d5'
PARENT_TERMINAL_KIND='local-bid-mixture-terminal-1'
PARENT_CLOSURE_KIND='local-bid-mixture-physical-closure-1'
SCHEDULE_SHA='5d6509513880cfc1065047fcb5fa27e1aa40f18470d5ece983297ad94faf4387'
RNG_AUDIT_SHA='88b085e79c716c0bfff224cc0f9a2bff114f87ca270bc5e7dcf19c81b1ee4ed4'
ROOT_RNG_PROOF_SHA='b51d98b5ec8db1a8d0ef78fc22dca826d6693e07abd7c841426d4dd299f5b43d'
NUMERIC_READY_SHA='06f1f010d4899623fc5de3bc8f2222fc41768e27bab151cf9b1894894474b8c4'
NATIVE_SHA=mixture.NATIVE_SHA
NATIVE_READINESS_SHA=mixture.NATIVE_READINESS_SHA
CANDIDATE_SHA=mixture.CANDIDATE_SHA
ASSET_SHA=mixture.ASSET_SHA
NOMINATION_SHA=mixture.NOMINATION_SHA
BANK_ORDER=('training','holdout')
CASES=[dict(id='START')]
EXECUTABLES=('token_focus_campaign.py',)
CASE_IDS=tuple(f'{sign}_{direction:02d}' for direction in range(16) for sign in ('PLUS','MINUS'))
CONFIDENCE=1.959963984540054


@dataclass(frozen=True)
class StageSpec:
    """Source-registered experiment identity; external requests cannot supply another stage."""
    study: int
    method_sha: str
    kind: str
    prefix: str
    parent_study: int
    parent_protocol_sha: str
    parent_terminal_kind: str
    parent_closure_kind: str
    module_name: str
    schedule_sha: str
    rng_audit_sha: str
    root_rng_proof_sha: str
    numeric_ready_sha: str


def stage_spec(stage=None):
    return stage or StageSpec(STUDY,METHOD_SHA,KIND,PREFIX,95,PARENT_PROTOCOL_SHA,
        PARENT_TERMINAL_KIND,PARENT_CLOSURE_KIND,__name__,SCHEDULE_SHA,RNG_AUDIT_SHA,
        ROOT_RNG_PROOF_SHA,NUMERIC_READY_SHA)


def declaration(path):
    return dict(path=str(Path(path).absolute()),sha256=c.sha(path))


def read_method(path,*,stage=None):
    c.require(c.sha(path)==stage_spec(stage).method_sha,'Only the exact root-frozen TOKEN-only one-step method may prepare research.')
    return c.read(path)


def origin_policy(terminal_item,closure_item,*,stage=None):
    spec=stage_spec(stage)
    terminal=search.subject(terminal_item);closed=search.subject(closure_item)
    c.require(terminal['kind']==spec.parent_terminal_kind and closed['kind']==spec.parent_closure_kind and
        terminal['study']==closed['study']==spec.parent_study and terminal['protocolSha256']==closed['protocolSha256']==spec.parent_protocol_sha and
        terminal['status'] in ('completed','failed','interrupted') and closed['passed'] is True and
        closed['allOwnedProcessesExited'] is True and closed['originalClocksRenewed'] is False and closed['auditReruns']==0,
        'Only the original physically closed registered parent can determine the fixed starting policy.')
    if spec.study==97:
        audit_path=Path(terminal_item['path']).parent/'completion-audit.json'
        c.require(terminal['complete'] is True and terminal['status']=='completed' and terminal['qualified'] is False and
            closed['qualified'] is False and closed['scienceComplete'] is True,
            'Defer study97 unless the sole original96 learning attempt completed and physically closed.')
        c.require(closed['artifactPins'].get(terminal_item['path'])==terminal_item['sha256'] and
            all(closed[key]==1560 for key in ('actualAttemptedLowerBound','actualAttemptedUpperBound',
                'knownCompletedTerminalExecutions','controllerReservedTerminalUpperBound')),
            'The original96 closure must seal its exact terminal and all1560 execution counters.')
        audit=c.read(audit_path);candidate=terminal['candidateBundle']
        c.require(audit['kind']=='token-focus-step-original-audit-1' and audit['passed'] is True and
            audit['complete'] is True and audit['qualified'] is False and audit['protocolSha256']==spec.parent_protocol_sha and
            terminal['knownCompletedTerminalExecutions']==terminal['reservedTerminalUpperBound']==
            closed['actualCompletedLowerBound']==audit['terminalExecutions']==1560 and
            terminal['completionAuditSha256']==c.sha(audit_path)==closed['artifactPins'].get(str(audit_path)) and
            c.sha(candidate['path'])==candidate['sha256']==audit['artifactPins'].get(candidate['path'])==
            closed['artifactPins'].get(candidate['path']),
            'Study97 admits only the original audited96 sole FINAL, independently of its holdout result.')
        return dict(study=96,tokenNeighborMixture=None,original96Final=candidate,original96Audit=declaration(audit_path))
    if not terminal['qualified']:
        c.require(closed['qualified'] is False,'An unqualified95 parent cannot supply a qualified mixture.')
        return dict(study=94,tokenNeighborMixture=None)
    audit_path=Path(terminal_item['path']).parent/'completion-audit.json'
    audit=c.read(audit_path)
    c.require(terminal['complete'] is True and terminal['status']=='completed' and closed['qualified'] is True and
        closed['scienceComplete'] is True and audit['complete'] is True and audit['passed'] is True and
        audit['kind']=='local-bid-mixture-original-audit-1' and audit['qualified'] is True and
        terminal['knownCompletedTerminalExecutions']==closed['actualCompletedLowerBound']==audit['terminalExecutions']==2648 and
        audit['protocolSha256']==PARENT_PROTOCOL_SHA and
        terminal['completionAuditSha256']==c.sha(audit_path)==closed['artifactPins'].get(str(audit_path)) and
        audit['selectedCase']==terminal['selectedCase'] and terminal['selectedCase'] in mixture.CASES,
        'The original qualified95 audit and closure must bind its sole selected mixture exactly.')
    return dict(study=95,tokenNeighborMixture=terminal['selectedCase']['tokenNeighborMixture'],
        original95Audit=declaration(audit_path))


def validate_worlds(plan,*,stage=None):
    spec=stage_spec(stage)
    reserved=plan['reservedSchedule']
    c.require(spec.schedule_sha is not None and plan['scheduleSha256']==spec.schedule_sha and
        hashlib.sha256(c.canonical(reserved)).hexdigest()==spec.schedule_sha and
        reserved['kind']=='token-focus-step-schedule-1' and set(reserved['banks'])==set(BANK_ORDER),
        'TOKEN learning requires the complete original fresh training and holdout reservation.')
    seeds=[]
    for name,count in (('training',16),('holdout',128)):
        bank=reserved['banks'][name]
        c.require(bank['count']==len(bank['seeds'])==count and plan['banks'][name]==bank and
            all(type(seed) is int and -(2**31)<=seed<2**31 for seed in bank['seeds']),
            'Every original TOKEN training/holdout world must remain complete and unchanged.')
        seeds.extend(bank['seeds'])
    c.require(len(set(seeds))==144 and type(reserved['projectionSeed']) is int and
        type(reserved['directionSeed']) is int and reserved['projectionSeed']!=reserved['directionSeed'],
        'The two native banks and two declared numerical streams must stay distinct.')


def validate(plan,*,stage=None):
    spec=stage_spec(stage)
    method=search.subject(plan['prospectiveMethod'])
    c.require(plan['prospectiveMethod']['sha256']==spec.method_sha and plan['kind']==spec.kind and plan['study']==spec.study and
        all(plan[key]==value for key,value in method.items() if key!='kind'),
        'All original TOKEN-only learning, resource and research-only parameters must remain fixed.')
    c.require(spec.numeric_ready_sha is not None and plan['numericSourceReadiness']['sha256']==spec.numeric_ready_sha and
        plan['nativeJarSha256']==NATIVE_SHA and plan['nativeSourceReadiness']['sha256']==NATIVE_READINESS_SHA,
        'Only the prospectively frozen numerical owner and unchanged Native95 runtime may execute.')
    c.require(plan['numericRecipe']==search.subject(plan['numericSourceReadiness'])['numericRecipe'],
        'The actual numerical request recipe must equal its frozen source owner exactly.')
    c.require(plan['globalDeadlineEpoch']-plan['preparedEpoch']>=7800,
        'Defer TOKEN learning when fewer than7800 original seconds remain.')
    validate_worlds(plan,stage=spec);mixture.validate_baseline(plan)
    c.require(plan['originPolicy']==origin_policy(plan['parentTerminal'],plan['parentClosure'],stage=spec) and
        plan['inferenceAssetSha256']==ASSET_SHA and plan['nominationSha256']==NOMINATION_SHA,
        'The starting full policy must follow the original closed95 rule and preserve all selector references.')
    if spec.study==97:
        c.require(plan['originWeightsSha256']==plan['originPolicy']['original96Final']['sha256'] and
            plan['numericStageSpec']==search.subject(plan['numericSourceReadiness'])['stageSpec'],
            'The registered97 numerical stage must use exactly the original96 FINAL weights.')
    return plan


def prepare_protocol(method,schedule,seed_audit,root_proof,parent_terminal,parent_closure,arena_sha,
                     *,native_readiness,now,extra,stage=None):
    spec=stage_spec(stage);method_value=read_method(method,stage=spec)
    c.require(spec.schedule_sha is not None and spec.rng_audit_sha is not None and spec.root_rng_proof_sha is not None and
        c.sha(schedule)==spec.schedule_sha and c.sha(seed_audit)==spec.rng_audit_sha and c.sha(root_proof)==spec.root_rng_proof_sha,
        'Exact root-reviewed fresh TOKEN streams are required before any preparation.')
    proof=c.read(root_proof)
    c.require(proof['passed'] is True and proof['scheduleSha256']==spec.schedule_sha and proof['auditSha256']==spec.rng_audit_sha,
        'The original independent freshness proof must bind both native banks and numerical streams.')
    audit=c.read(seed_audit)
    for item in [*audit['verifiedAncestors'],audit['carriedForwardAudit']]:
        c.require(c.sha(item['path'])==item['sha256'],'A protected prior RNG reservation changed.')
    c.require(type(extra) is dict and set(extra)=={'qualifiedBaseline94','numericSourceReadiness'},
        'TOKEN learning preparation requires its exact qualified94 evidence and numerical source subject.')
    numeric=search.subject(extra['numericSourceReadiness'])
    c.require(spec.numeric_ready_sha is not None and extra['numericSourceReadiness']['sha256']==spec.numeric_ready_sha and
        numeric['passed'] is True and numeric['sourceOnly'] is True and
        all(c.sha(path)==digest for path,digest in numeric['sourceFiles'].items()),
        'The complete original numerical source must remain frozen before native admission.')
    reserved=c.read(schedule)
    origin=origin_policy(declaration(parent_terminal),declaration(parent_closure),stage=spec)
    plan=dict(method_value,kind=spec.kind,methodSha256=spec.method_sha,prospectiveMethod=declaration(method),
        preparedEpoch=now(),nativeSourceReadiness=mixture.native_binding(native_readiness),
        reservedSchedule=reserved,scheduleSha256=spec.schedule_sha,seedSchedule=declaration(schedule),seedAudit=declaration(seed_audit),
        rootSeedProof=declaration(root_proof),parentTerminal=declaration(parent_terminal),
        parentClosure=declaration(parent_closure),parentTrialProtocolSha256=spec.parent_protocol_sha,originPolicy=origin,
        inferenceAssetSha256=ASSET_SHA,nominationSha256=NOMINATION_SHA,arenaSha256=arena_sha,
        caseConfigs=CASES,banks=reserved['banks'],numericRecipe=numeric['numericRecipe'],**extra)
    if spec.study==97:plan.update(originWeightsSha256=origin['original96Final']['sha256'],numericStageSpec=numeric['stageSpec'])
    validate(plan,stage=spec);search.prerequisite(plan,sys.modules[spec.module_name]);return plan


def input_subjects(plan):
    numeric=search.subject(plan['numericSourceReadiness'])
    return [plan['nativeSourceReadiness'],plan['numericSourceReadiness'],numeric['python'],
        *[dict(path=path,sha256=digest) for path,digest in numeric['sourceFiles'].items()],
        *plan['qualifiedBaseline94'].values(),
        *([plan['originPolicy']['original95Audit']] if plan['originPolicy']['study']==95 else []),
        *([plan['originPolicy']['original96Final'],plan['originPolicy']['original96Audit']] if plan['study']==97 else [])]


def native_source(plan,profile,bank_name,case,chunk,protocol_sha,*,stage=None):
    spec=stage_spec(stage)
    c.require(bank_name in BANK_ORDER and chunk==0 and
        case['id'] in (*CASE_IDS,'START','FINAL'),'TOKEN research uses one fixed chunk per registered case.')
    c.require((bank_name=='training' and case['id'] in (*CASE_IDS,'START')) or
        (bank_name=='holdout' and case['id'] in ('START','FINAL')),
        'Training permits only its32 mutants or zero-game START admission; holdout permits START and FINAL.')
    control=profile.get('controlBundle',profile['candidateBundle'])
    c.require(profile['candidateBundle']['sha256']==plan.get('originWeightsSha256',CANDIDATE_SHA) and
        control['sha256']==CANDIDATE_SHA and (spec.study==96 or 'controlBundle' in profile) and
        profile['inferenceAsset']['sha256']==ASSET_SHA and profile['nomination']['sha256']==NOMINATION_SHA and
        profile['arena']['sha256']==plan['arenaSha256'],
        'The original fca full-policy weights and selector foundation must stay fixed.')
    candidate=case.get('bundle',profile['candidateBundle'])
    replays=([0,15] if case['id'] in ('PLUS_00','MINUS_00') else []) if bank_name=='training' else [0,42,85,127]
    source=dict(kind='robust-token-chunk-1',protocolSha256=protocol_sha,
        **{name:profile[name] for name in ('inferenceAsset','nomination','arena')},
        candidateBundle=candidate,controlBundle=control,
        selector=plan['baseSelector'],controlSelector=plan['baseSelector'],opponentStyle='BALANCED',
        seeds=plan['banks'][bank_name]['seeds'],globalStart=0,count=plan['banks'][bank_name]['count'],
        replayIndices=replays)
    if plan['originPolicy']['tokenNeighborMixture'] is not None:
        source['tokenNeighborMixture']=plan['originPolicy']['tokenNeighborMixture']
    return source


def profile_fields(plan,old):
    if plan['study']==96:return {}
    control=old.get('controlBundle',old['candidateBundle'])
    c.require(control['sha256']==CANDIDATE_SHA,'The separate rival/control subject must remain the fixed qualified94 bundle.')
    return dict(controlBundle=control)


def check_prepared(prepared,bank_name):
    identity=prepared['scienceIdentity']
    c.require(bank_name in BANK_ORDER and identity['controlAndRivalsBundleSha256']==CANDIDATE_SHA and
        identity['selector']==mixture.CONTROL_SELECTOR and identity['controlSelector']==mixture.CONTROL_SELECTOR and
        identity['roles']==['CANDIDATE','CONTROL'] and 'expansionDenial' not in identity,
        'TOKEN research must preserve all fixed94 controls/rivals and both baseC05 selectors.')


check_inputs=mixture.check_inputs


def native_chunk(source,directory,bank_name,*,source_path=None):
    import robust_token_audit
    result=robust_token_audit.native_chunk(source,directory,source_path=source_path)
    prepared=c.read(Path(directory)/'prepared.json');check_prepared(prepared,bank_name);check_inputs(prepared,source)
    if 'tokenNeighborMixture' in source:mixture.quote_traces(result[0],source)
    return result
