"""Study95 fixed local-neighbor mixture and exact owner-quote evidence contract.

The shared executor/auditor owns lifecycle, repeated controls and counts. This type
owns the immutable95 method, new native-byte binding, baseline, source and quote
invariants. It never fits models, executes games or draws random numbers.
"""
from collections import Counter
import hashlib
import math
from pathlib import Path
import re
import sys

import learned_bid_contract as bids
import mac_policy_search as search
import robust_token_campaign as c

KIND='local-bid-mixture-1'
PREFIX='local-bid-mixture'
STUDY=95
PARENT_TERMINAL_KIND='mac-leader-search-terminal-1'
PARENT_CLOSURE_KIND='mac-leader-search-physical-closure-1'
METHOD_SHA='4c757424a2700de67d2f2dc6ba6b2e7e9bef8c76a96dc714eec158578698179f'
SCHEDULE_SHA='b0f5395ff359758c88bf54255b3ccf4f63b12e3835e80b417b13a4ea1d2f248f'
RNG_AUDIT_SHA='e0f1a49cb228e71fb5cdd35182910b0b81bf53f02f05c70aa1388132edc8c227'
# Resolved once from the root-reviewed frozen metadata/native build before final source admission.
ROOT_RNG_PROOF_SHA='5ba937ecbf5fb1b6f8f349517297087cd6589f3b85c3260f5b4ac9db10900a0e'
NATIVE_READINESS_SHA='687c152ffedfe867b4b98b993b6e1cc099aaa10cdadd5b9783d551c5ed120efa'
NATIVE_SHA='6d7a056095ad5f886171823a29b23e466f4f89ad948bfc2f8e6999a71c4a6097'
CANDIDATE_SHA=bids.CANDIDATE_SHA
OLD_CONTROL_SHA=bids.OLD_CONTROL_SHA
ASSET_SHA=bids.ASSET_SHA
NOMINATION_SHA=bids.NOMINATION_SHA
CONTROL_SELECTOR=bids.CONTROL_SELECTOR
GLOBAL_DEADLINE=bids.GLOBAL_DEADLINE
BASELINE_PROTOCOL_SHA='140427b0db6635b7842b1b51213a5db174382e8088a0d43fc2917aa7a1c37e5e'
CASES=[dict(id=f'n{index:02d}',tokenNeighborMixture=dict(kind='local-token-neighbor-mixture-1',
    departureProbability=departure,radius=radius,admission=admission))
    for index,(admission,radius,departure) in enumerate((admission,radius,departure)
        for admission in ('OFFERED_ONLY','QUOTED_LEGAL_NEIGHBORS')
        for radius in (1,2) for departure in (.25,.5))]
BANKS=(dict(name='development',worlds=64,opponentStyle='BALANCED',control='QUALIFIED94',replayIndices=[0,21,42,63]),
    dict(name='mixed',worlds=256,opponentStyle='BALANCED',control='QUALIFIED94',replayIndices=[0,85,170,255]),
    dict(name='homogeneous',worlds=256,opponentStyle='QUALIFIED',control='QUALIFIED94',replayIndices=[0,85,170,255]),
    dict(name='reference',worlds=256,opponentStyle='QUALIFIED',control='QUALIFIED87',replayIndices=[0,85,170,255]))
BANK_ORDER=tuple(bank['name'] for bank in BANKS)
FULL_BANK_COUNTS={bank['name']:bank['worlds'] for bank in BANKS}
FIXED=dict(study=95,localOnly=True,remoteUseAuthorized=False,globalDeadlineEpoch=bids.GLOBAL_DEADLINE,
    candidateBundleSha256=CANDIDATE_SHA,controlAndRivalsBundleSha256=CANDIDATE_SHA,
    baseTokenSelector=CONTROL_SELECTOR,baseExpansionDenial=None,reference87BundleSha256=OLD_CONTROL_SHA,
    caseConfigs=CASES,positiveBidConcentrationMaximum=.8,confidenceMultiplier=c.MULTIPLIER,
    maximumTerminalExecutions=2648,maximumScienceSeconds=10800,minimumRemainingSecondsAtPreparation=10800,
    auditSeconds=600,workers=10,heapMiB=6144,maximumRssBytes=12*1024**3,
    minimumFreeBytes=16*1024**3,maximumDataBytes=768*1024**2,chunkSize=256,
    noRetry=True,noResume=True,noTopUp=True,noAlternativeCandidate=True,noNewFits=True,
    weightsImmutable=True,launchViaOwnedTerminal=True,nativeArtifactsMustBeFrozenBeforeAnyData=True)
BASELINE_KEYS={'protocol','terminal','closure','completionAudit'}


def declaration(path):
    return dict(path=str(Path(path).resolve()),sha256=c.sha(path))


def read_method(path):
    c.require(c.sha(path)==METHOD_SHA,'Only the exact root-frozen95 prospective method may prepare a trial.')
    method=c.read(path)
    c.require(method['kind']=='local-bid-mixture-prospective-method-1' and
        all(method[key]==value for key,value in FIXED.items()) and method['banks']==list(BANKS),
        'The95 method must preserve its eight local mixtures, four banks and exact original budget.')
    return method


def native_binding(path):
    c.require(NATIVE_READINESS_SHA is not None and NATIVE_SHA is not None and
        path is not None and c.sha(path)==NATIVE_READINESS_SHA,
        'The exact root-reviewed95 native readiness and JAR must be frozen before any preparation.')
    native=c.read(path)
    c.require(native['kind']=='local-token-mixture-native-source-final-readiness-1' and
        native['passed'] is True and native['sourceOnly'] is True and
        native['nativeJar']==declaration(Path(native['runtimeRoot'])/'lib/training.jar') and
        native['nativeJar']['sha256']==NATIVE_SHA and
        c.sha(Path(native['runtimeRoot'])/'lib/training.jar')==NATIVE_SHA and
        all(c.sha(owner)==digest for owner,digest in native['runtimeFiles'].items()),
        'Only the complete physically frozen95 native runtime may admit this optional policy.')
    return declaration(path)


def validate_baseline(plan):
    baseline=plan['qualifiedBaseline94']
    c.require(set(baseline)==BASELINE_KEYS,'The fixed qualified94 baseline needs exactly its original four evidence subjects.')
    protocol,terminal,closed,audit=(search.subject(baseline[name])
        for name in ('protocol','terminal','closure','completionAudit'))
    audit_sha=baseline['completionAudit']['sha256']
    c.require(baseline['protocol']['sha256']==BASELINE_PROTOCOL_SHA and
        protocol['candidateBundleSha256']==CANDIDATE_SHA and protocol['selector']==CONTROL_SELECTOR and
        terminal['kind']=='fixed-policy-qualification-terminal-1' and terminal['status']=='completed' and
        terminal['complete'] is True and terminal['qualified'] is True and
        terminal['protocolSha256']==BASELINE_PROTOCOL_SHA and terminal['completionAuditSha256']==audit_sha and
        closed['kind']=='mac-qualification-physical-closure-1' and closed['passed'] is True and
        closed['scienceComplete'] is True and closed['qualified'] is True and closed['allOwnedProcessesExited'] is True and
        closed['protocolSha256']==BASELINE_PROTOCOL_SHA and closed['actualCompletedLowerBound']==2064 and
        closed['originalClocksRenewed'] is False and closed['auditReruns']==0 and
        audit['kind']=='fixed-policy-qualification-original-audit-1' and audit['passed'] is True and
        audit['complete'] is True and audit['qualified'] is True and audit['protocolSha256']==BASELINE_PROTOCOL_SHA and
        audit['terminalExecutions']==2064 and closed['artifactPins'].get(baseline['completionAudit']['path'])==audit_sha,
        'Study95 compares only the originally qualified immutable final6+c05 policy, regardless of93 outcomes.')


def validate(plan):
    c.require(plan['kind']==KIND and plan['methodSha256']==METHOD_SHA and
        all(plan[key]==value for key,value in FIXED.items()),
        'The finite95 protocol must preserve every original prospective policy and resource parameter.')
    c.require(NATIVE_READINESS_SHA is not None and NATIVE_SHA is not None and
        plan['nativeJarSha256']==NATIVE_SHA and plan['nativeSourceReadiness']['sha256']==NATIVE_READINESS_SHA,
        'The95 protocol cannot substitute an arbitrary native JAR or an unfrozen source manifest.')
    prepared=plan['preparedEpoch']
    c.require(type(prepared) in (int,float) and math.isfinite(prepared) and
        bids.GLOBAL_DEADLINE-prepared>=10800,'Study95 must defer when fewer than three original hours remain.')
    validate_worlds(plan)
    c.require(plan['inferenceAssetSha256']==ASSET_SHA and plan['nominationSha256']==NOMINATION_SHA,
        'The original87 selector references, dictionaries and calibration foundation stay fixed.')
    validate_baseline(plan)
    return plan


def validate_worlds(plan):
    reserved=plan['reservedSchedule']
    c.require(reserved['kind']=='local-bid-mixture-schedule-1' and
        hashlib.sha256(c.canonical(reserved)).hexdigest()==plan['scheduleSha256']==SCHEDULE_SHA and
        set(reserved['banks'])==set(BANK_ORDER)==set(plan['banks']) and
        plan['fullReservedWorlds']==plan['activeReservedWorlds']==832 and plan['unusedReservedWorlds']==0,
        'All and only the freshly declared832 worlds must belong to this95 reservation.')
    seeds=[]
    for bank in BANKS:
        name=bank['name'];original=reserved['banks'][name]
        c.require(original['count']==len(original['seeds'])==bank['worlds'] and
            plan['banks'][name]==dict(bank,seeds=original['seeds']) and
            all(type(value) is int and -(2**31)<=value<2**31 for value in original['seeds']),
            'Each95 bank keeps its exact full original stream, replay indices, comparator and ecology.')
        seeds.extend(original['seeds'])
    c.require(len(seeds)==len(set(seeds))==832,'Development and all three95 confirmation banks must be disjoint.')


def prepare_protocol(method,schedule,seed_audit,root_proof,parent_terminal,parent_closure,arena_sha,
                     *,native_readiness,now,extra):
    method_value=read_method(method)
    c.require(ROOT_RNG_PROOF_SHA is not None and c.sha(schedule)==SCHEDULE_SHA and
        c.sha(seed_audit)==RNG_AUDIT_SHA and c.sha(root_proof)==ROOT_RNG_PROOF_SHA,
        'The full original95 RNG reservation requires exact root-reviewed metadata before preparation.')
    proof=c.read(root_proof)
    c.require(proof['kind']=='local-bid-mixture-independent-seed-proof-1' and
        proof['passed'] is True and proof['scheduleSha256']==SCHEDULE_SHA and
        proof['auditSha256']==RNG_AUDIT_SHA and proof['declaredNewWorlds']==832,
        'The root freshness proof must bind the complete original95 reservation.')
    audit=c.read(seed_audit)
    for item in [*audit['verifiedAncestors'],audit['carriedForwardAudit']]:
        c.require(c.sha(item['path'])==item['sha256'],'An original protected95 RNG ancestor changed.')
    native=native_binding(native_readiness)
    c.require(type(extra) is dict and set(extra)=={'qualifiedBaseline94'},
        'Only the four exact qualified94 baseline subjects may extend the original95 protocol.')
    reserved=c.read(schedule)
    c.require(c.read(parent_terminal)['study']==93 and c.read(parent_closure)['study']==93,
        'Only the physically closed original study93 may precede study95.')
    plan=dict(method_value,kind=KIND,methodSha256=METHOD_SHA,preparedEpoch=now(),
        nativeJarSha256=NATIVE_SHA,nativeSourceReadiness=native,
        reservedSchedule=reserved,scheduleSha256=SCHEDULE_SHA,seedAudit=declaration(seed_audit),
        rootSeedProof=declaration(root_proof),parentTerminal=declaration(parent_terminal),
        parentClosure=declaration(parent_closure),parentTrialProtocolSha256=c.read(parent_terminal)['protocolSha256'],
        inferenceAssetSha256=ASSET_SHA,nominationSha256=NOMINATION_SHA,arenaSha256=arena_sha,
        fullReservedWorlds=832,activeReservedWorlds=832,unusedReservedWorlds=0,
        banks={bank['name']:dict(bank,seeds=reserved['banks'][bank['name']]['seeds']) for bank in BANKS},**extra)
    validate(plan);search.prerequisite(plan,sys.modules[__name__])
    return plan


def input_subjects(plan):
    return [plan['nativeSourceReadiness'],*plan['qualifiedBaseline94'].values()]


def native_source(plan,profile,bank_name,case,chunk,protocol_sha):
    bank=plan['banks'][bank_name];start=chunk*256
    c.require(case in CASES and type(chunk) is int and chunk>=0 and start<bank['worlds'],
        'A95 native task requires one original registered mixture and bounded bank range.')
    candidate=profile['candidateBundle']
    c.require(candidate['sha256']==CANDIDATE_SHA and profile['inferenceAsset']['sha256']==ASSET_SHA and
        profile['nomination']['sha256']==NOMINATION_SHA and profile['arena']['sha256']==plan['arenaSha256'],
        'The95 native source must preserve the fixed full-policy candidate and original87 references.')
    source=dict(kind='robust-token-chunk-1',protocolSha256=protocol_sha,inferenceAsset=profile['inferenceAsset'],
        nomination=profile['nomination'],arena=profile['arena'],candidateBundle=candidate,
        selector=CONTROL_SELECTOR,tokenNeighborMixture=case['tokenNeighborMixture'],
        opponentStyle=bank['opponentStyle'],seeds=bank['seeds'],globalStart=start,
        count=min(256,bank['worlds']-start),replayIndices=bank['replayIndices'])
    if bank['control']=='QUALIFIED94':source.update(controlBundle=candidate,controlSelector=CONTROL_SELECTOR)
    return source


def check_source(plan,profile,source,bank_name,case,chunk,protocol_sha):
    c.require(source==native_source(plan,profile,bank_name,case,chunk,protocol_sha),
        'The candidate-only95 mixture cannot change weights, base selectors, comparators or original worlds.')


def check_prepared(prepared,bank_name):
    identity=prepared['scienceIdentity'];learned=bank_name!='reference'
    c.require(bank_name in BANK_ORDER and identity['candidateBundleSha256']==CANDIDATE_SHA and
        identity['controlAndRivalsBundleSha256']==(CANDIDATE_SHA if learned else OLD_CONTROL_SHA) and
        identity['selector']==CONTROL_SELECTOR and identity.get('controlSelector')==(CONTROL_SELECTOR if learned else None) and
        identity.get('tokenNeighborMixture') in [case['tokenNeighborMixture'] for case in CASES] and
        'expansionDenial' not in identity and identity['roles']==['CANDIDATE','CONTROL'],
        'Native95 identity must prove a candidate-only local mixture around the exact original comparator policy.')


def quote_traces(rows,source):
    mixture=source['tokenNeighborMixture']
    for world in rows:
        for role in ('CANDIDATE','CONTROL'):
            diagnostic=world['diagnostics'][role]
            counts=diagnostic.get('controlledUnofferedTokenBidCounts')
            records=diagnostic.get('controlledTokenQuoteAdmissions')
            c.require(type(counts) is dict and type(records) is list,
                'Opted-in95 diagnostics require explicit unoffered counts and original accepted owner-quote traces.')
            parsed={}
            for text,count in counts.items():
                c.require(type(text) is str and re.fullmatch(r'-?(0|[1-9][0-9]*)',text) is not None and
                    str(int(text))==text and type(count) is int and count>0,
                    'Unoffered bid counts require canonical signed integer amounts and positive integral counts.')
                parsed[int(text)]=count
            c.require(Counter(record['amount'] for record in records)==Counter(parsed),
                'Every actually unoffered controlled amount requires one retained original owner-quote trace.')
            if role=='CONTROL' or mixture['admission']=='OFFERED_ONLY':
                c.require(not counts and not records,'Controls and offered-only cases cannot contain unoffered token commands.')
            all_counts=diagnostic['controlledBidCounts']
            for amount,count in parsed.items():
                c.require(count<=all_counts.get(str(amount),0),'Unoffered accepted counts must remain part of actual controlled bid counts.')
            for record in records:
                amount=record['amount'];offered=record['originalOfferedAmounts'];before=record['ownerResourceBefore'];quote=record['quote']
                c.require(type(amount) is int and record['command']=='buy-tokens '+str(amount) and
                    type(offered) is list and offered and all(type(value) is int for value in offered) and
                    len(set(offered))==len(offered) and amount not in offered and
                    any(0<abs(amount-original)<=mixture['radius'] for original in offered),
                    'A newly quoted command must be canonical, originally unoffered and a declared local neighbor.')
                c.require(set(before)=={'cash','stock','pool'} and all(type(value) is int and value>=0 for value in before.values()) and
                    set(quote)=={'amount','cost','tokensReceived','cashAfter','stockAfter','poolAfter'} and
                    all(type(value) is int for value in quote.values()),'Owner budgets and the exact accepted quote require integral native values.')
                received=min(amount,before['stock']) if amount>0 else 0
                c.require(abs(amount)<=before['cash'] and quote==dict(amount=amount,cost=abs(amount),tokensReceived=received,
                    cashAfter=before['cash']-abs(amount),stockAfter=before['stock']-received,poolAfter=before['pool']+received),
                    'The retained accepted quote must match the same actor-owned affordable signed purchase exactly.')
                names=('originalObservationSha256','acceptedObservationSha256','acceptedCandidateSha256','acceptedQuotedViewSha256')
                c.require(all(type(record[name]) is str and re.fullmatch('[0-9a-f]{64}',record[name]) is not None for name in names) and
                    record['originalObservationSha256']!=record['acceptedObservationSha256'] and
                    record['retainedForDecisionTrace'] is True,
                    'The original and accepted observation provenance must remain distinct and retained for the actual decision trace.')


def native_chunk(source,directory,bank_name,*,source_path=None):
    import robust_token_audit
    result=robust_token_audit.native_chunk(source,directory,source_path=source_path)
    prepared=c.read(Path(directory)/'prepared.json');check_prepared(prepared,bank_name);bids.check_inputs(prepared,source)
    quote_traces(result[0],source)
    return result


aggregate=bids.aggregate
passed=bids.passed
check_inputs=bids.check_inputs


def select(reports):
    c.require(set(reports)=={case['id'] for case in CASES},'Nomination requires all eight complete original95 development cases.')
    eligible=[case for case in CASES if reports[case['id']]['wins']['mean']>0 and
        reports[case['id']]['observedBids']['CANDIDATE']['observedConcentrationPassed']]
    return max(eligible,key=lambda case:reports[case['id']]['wins']['mean']) if eligible else None
