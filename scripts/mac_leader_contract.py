"""Optional finite study93; only the candidate's public-leader expansion bonus changes."""
import hashlib
import math
from pathlib import Path
import re

import learned_bid_contract as bids
import mac_policy_search as search
import robust_token_campaign as c


KIND='mac-leader-search-1'
PREFIX='mac-leader-search'
STUDY=93
PARENT_TERMINAL_KIND='learned-bid-search-terminal-1'
PARENT_CLOSURE_KIND='learned-bid-search-physical-closure-1'
METHOD_SHA='0ee2ed67c272bc5e9035d7ad77ba8204b6a53d81a3eb9cefc78fcebe09c4aef2'
SCHEDULE_SHA='e72aac1b0fae40b0cbc92b452888f7152378ea812e1b3b6d9ecbcb18b4a72a35'
RNG_AUDIT_SHA='78c59913811288b71a8c1bfa08848f8a82b592061066a819d7801bb5dda442cb'
ROOT_RNG_PROOF_SHA='a530abde347e132f61123432c344b80aeb065f5d67261a2708edb6805ce1abab'
NATIVE_SHA=bids.NATIVE_SHA
ASSET_SHA=bids.ASSET_SHA
NOMINATION_SHA=bids.NOMINATION_SHA
CASES=[dict(id=f'l{index:02d}',expansionDenial=dict(kind='public-leader-attack-bonus-1',
    beta=beta,minimumWinProbability=probability))
    for index,(beta,probability) in enumerate((beta,probability)
        for beta in (.25,.5,1,2) for probability in (.5,.8))]
BANKS=(dict(name='development',worlds=64,opponentStyle='BALANCED',replayIndices=[0,21,42,63]),
    dict(name='mixed',worlds=256,opponentStyle='BALANCED',replayIndices=[0,85,170,255]),
    dict(name='homogeneous',worlds=256,opponentStyle='QUALIFIED',replayIndices=[0,85,170,255]))
BANK_ORDER=tuple(bank['name'] for bank in BANKS)
FIXED=dict(study=STUDY,globalDeadlineEpoch=bids.GLOBAL_DEADLINE,nativeJarSha256=NATIVE_SHA,
    caseConfigs=CASES,confidenceMultiplier=c.MULTIPLIER,maximumTerminalExecutions=2128,
    maximumScienceSeconds=10800,minimumRemainingSecondsAtPreparation=9000,auditSeconds=600,
    workers=10,heapMiB=6144,maximumRssBytes=12*1024**3,minimumFreeBytes=16*1024**3,
    maximumDataBytes=768*1024**2,chunkSize=256,localOnly=True,remoteUseAuthorized=False,
    noRetry=True,noResume=True,noTopUp=True,noAutomaticSuccessor=True,noNewFits=True,
    weightsImmutable=True,launchViaOwnedTerminal=True)
ANCHOR_KEYS=('origin','bundleSha256','selector','qualificationSha256')


def read_method(path):
    c.require(c.sha(path)==METHOD_SHA,'Only the exact prospective root-reviewed study93 method may prepare.')
    method=c.read(path)
    c.require(method['kind']=='mac-leader-search-prospective-method-1' and
        all(method[key]==value for key,value in FIXED.items()) and method['banks']==list(BANKS),
        'The optional leader search must retain its fixed8 settings, independent banks and Mac-only budget.')
    return method


def validate_anchor(plan):
    frozen=search.subject(plan['anchorFreeze']);parent=search.subject(plan['parentClosure'])
    terminal=search.subject(plan['parentTerminal']);prior91=search.subject(frozen['study91Closure'])
    c.require(frozen['kind']=='mac-leader-search-anchor-freeze-1' and frozen['passed'] is True and
        frozen['complete'] is True and frozen['qualified'] is True and frozen['methodSha256']==METHOD_SHA and
        all(frozen[key]==plan['anchor'][key] for key in ANCHOR_KEYS),
        'The leader search requires the exact root-frozen qualified anchor and unchanged token sampler.')
    c.require(re.fullmatch('[0-9a-f]{64}',plan['anchor']['qualificationSha256']) is not None,
        'A qualified leader anchor requires its original sealed qualification subject hash.')
    c.require(parent['kind']==PARENT_CLOSURE_KIND and parent['passed'] is True and
        parent['allOwnedProcessesExited'] is True and prior91['kind']=='mac-qualification-physical-closure-1' and
        prior91['passed'] is True and prior91['allOwnedProcessesExited'] is True,
        'Anchor priority requires the original physically closed92 and91 qualification records.')
    prior94=None if parent['qualified'] else search.subject(frozen['study94Closure'])
    if prior94 is not None:
        c.require(prior94['kind']=='mac-qualification-physical-closure-1' and prior94['passed'] is True and
            prior94['allOwnedProcessesExited'] is True and
            prior94['protocolSha256']==bids.FIXED['parentTrialProtocolSha256'],
            'Anchor priority requires the exact original closed94 trial, without91 metric reuse.')
    expected='92' if parent['qualified'] else '94' if prior94['qualified'] else '91' if prior91['qualified'] else '87'
    anchor=plan['anchor']
    c.require(anchor['origin']==expected,'The qualified anchor priority is92, then94, then91, then87; no skipped winner.')
    if expected=='92':
        c.require(parent['scienceComplete'] is True and terminal['complete'] is True and
            parent['actualCompletedLowerBound']==2648 and
            anchor['bundleSha256']==bids.CANDIDATE_SHA and
            anchor['selector']==terminal['selectedCase']['selector'] and
            any(case['selector']==anchor['selector'] for case in bids.CASES) and
            anchor['qualificationSha256']==terminal['completionAuditSha256'],
            'A92 anchor requires all three complete fresh banks, its exact winning sampler and sealed audit.')
    elif expected=='94':
        c.require(prior94['scienceComplete'] is True and prior94['actualCompletedLowerBound']==2064 and
            anchor['bundleSha256']==bids.CANDIDATE_SHA and anchor['selector']==bids.CONTROL_SELECTOR and
            any(path.endswith('/completion-audit.json') and digest==anchor['qualificationSha256']
                for path,digest in prior94['artifactPins'].items()),
            'A94 anchor requires both complete fresh512-world banks and the exact final+c05 policy.')
    elif expected=='91':
        c.require(prior91['scienceComplete'] is True and prior91['actualCompletedLowerBound']==8208 and
            anchor['bundleSha256']==bids.CANDIDATE_SHA and anchor['selector']==bids.CONTROL_SELECTOR and
            any(path.endswith('/completion-audit.json') and digest==anchor['qualificationSha256']
                for path,digest in prior91['artifactPins'].items()),
            'A91 anchor requires its complete original two-bank qualification and exact final+c05 policy.')
    else:
        c.require(anchor['bundleSha256']==bids.OLD_CONTROL_SHA and anchor['selector'] is None,
            'The fallback qualified87 anchor must preserve e62 and the deterministic token selector.')
        certificate=frozen['old87Certificate']
        c.require(certificate['sha256']==anchor['qualificationSha256'] and
            c.sha(certificate['path'])==certificate['sha256'] and
            frozen['old87Lineage']==dict(qualified=True,bundleSha256=bids.OLD_CONTROL_SHA,
                inferenceAssetSha256=ASSET_SHA,nominationSha256=NOMINATION_SHA),
            'The qualified87 fallback requires its exact sealed certificate and verified asset/nomination lineage.')
    for closed in (parent,prior91,*([prior94] if prior94 is not None else [])):
        for path,digest in closed['artifactPins'].items():
            c.require(c.sha(path)==digest,'An original qualified-ancestor artifact changed before anchor freeze.')


def validate(plan):
    c.require(plan['kind']==KIND and plan['methodSha256']==METHOD_SHA and
        all(plan[key]==value for key,value in FIXED.items()),
        'The finite leader protocol must preserve every prospectively fixed parameter.')
    prepared=plan['preparedEpoch']
    c.require(type(prepared) in (int,float) and math.isfinite(prepared) and
        bids.GLOBAL_DEADLINE-prepared>=9000,
        'The optional leader search must defer before preparation with fewer than150 original minutes left.')
    reserved=plan['reservedSchedule']
    c.require(reserved['kind']=='mac-leader-search-schedule-1' and
        hashlib.sha256(c.canonical(reserved)).hexdigest()==plan['scheduleSha256'] and
        set(reserved['banks'])==set(plan['banks'])==set(BANK_ORDER),
        'The complete independently reserved three-bank leader schedule cannot change.')
    seeds=[]
    for bank in BANKS:
        original=reserved['banks'][bank['name']]
        c.require(plan['banks'][bank['name']]==dict(bank,seeds=original['seeds']) and
            original['count']==len(original['seeds'])==bank['worlds'] and
            all(type(seed) is int and -(2**31)<=seed<2**31 for seed in original['seeds']),
            'Every leader bank must retain exactly its complete original seeds, ecology and reproductions.')
        seeds.extend(original['seeds'])
    c.require(len(seeds)==len(set(seeds))==576 and plan['inferenceAssetSha256']==ASSET_SHA and
        plan['nominationSha256']==NOMINATION_SHA,
        'The576 mutually disjoint leader worlds and unchanged87 numerical references must remain exact.')
    validate_anchor(plan)
    return plan


def native_source(plan, profile, bank_name, case, chunk, protocol_sha):
    bank=plan['banks'][bank_name];anchor=plan['anchor'];subject=profile['candidateBundle']
    c.require(case in CASES and type(chunk) is int and chunk==0 and subject['sha256']==anchor['bundleSha256'] and
        profile['inferenceAsset']['sha256']==ASSET_SHA and profile['nomination']['sha256']==NOMINATION_SHA and
        profile['arena']['sha256']==plan['arenaSha256'],
        'Every leader task requires its one original chunk and the exact frozen qualified anchor subjects.')
    return dict(kind='robust-token-chunk-1',protocolSha256=protocol_sha,
        inferenceAsset=profile['inferenceAsset'],nomination=profile['nomination'],arena=profile['arena'],
        candidateBundle=subject,controlBundle=subject,selector=anchor['selector'],controlSelector=anchor['selector'],
        expansionDenial=case['expansionDenial'],opponentStyle=bank['opponentStyle'],seeds=bank['seeds'],
        globalStart=0,count=bank['worlds'],replayIndices=bank['replayIndices'])


def check_prepared(prepared, bank_name):
    identity=prepared['scienceIdentity']
    c.require(bank_name in BANK_ORDER and identity['candidateBundleSha256']==identity['controlAndRivalsBundleSha256'] and
        identity['selector']==identity.get('controlSelector') and identity['roles']==['CANDIDATE','CONTROL'] and
        identity['expansionDenial'] in [case['expansionDenial'] for case in CASES],
        'Only the candidate expansion bonus may differ from the identical anchor models and token samplers.')


check_inputs=bids.check_inputs


def native_chunk(source, directory, bank_name, *, source_path=None):
    import robust_token_audit
    result=robust_token_audit.native_chunk(source,directory,source_path=source_path)
    prepared=c.read(Path(directory)/'prepared.json')
    check_prepared(prepared,bank_name);check_inputs(prepared,source)
    return result


def aggregate(rows, bank):
    return dict(c.aggregate(rows,bank),observedBids={role:bids.bid_summary(rows,role,require_positive=False)
        for role in ('CANDIDATE','CONTROL')})


def select(reports):
    return c.chosen_case(reports,CASES)


def passed(report, bank_name):
    c.require(bank_name in ('mixed','homogeneous'),'Only an original fresh leader confirmation bank has a win gate.')
    return report['wins']['lower99']>0
