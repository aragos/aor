"""Fixed study92 method, native90 role sources and observed-bid admission; no execution or RNG."""
from collections import Counter
import hashlib
import math
from pathlib import Path

import robust_token_campaign as c


KIND = 'learned-bid-search-1'
PREFIX = 'learned-bid-search'
STUDY = 92
PARENT_TERMINAL_KIND = 'mac-qualification-terminal-1'
PARENT_CLOSURE_KIND = 'mac-qualification-physical-closure-1'
METHOD_SHA = '82debbe40373aaa5986a7fe79616598e0d9d6e6ec5e3b51959329c7253cbce36'
SCHEDULE_SHA = 'a49c271f9a1c1ecf69ce02345713b777209dbd1a5b6657bdefe8880e8616c987'
RNG_AUDIT_SHA = '7df8f7ab2ddce6807731f55218b4646d2ffed6da2b644b7df74fb51bd576b3f4'
ROOT_RNG_PROOF_SHA = '66fa3c163699a2ac1e062a271f29af132eef81a76eeccb7b066e5c8600ef4a07'
NATIVE_SHA = '14245cba2d1dbd4e62780371efd84d4416bd91592ceca876449d7f417756463f'
CANDIDATE_SHA = 'fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e'
OLD_CONTROL_SHA = 'e62d749f4d9125cc86107cc2c37d77cb2f23af65c3648105a5fd2636f1ee27f1'
ASSET_SHA = 'c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a'
NOMINATION_SHA = 'f9da5a9e987870a847e4a56091174b56c137c40cead67936dfb1e0cbad95bb4d'
GLOBAL_DEADLINE = 1795020399.0
CONTROL_SELECTOR = dict(kind='near-optimal-token-softmax-1', temperature=.35,
    maxScoreGap=3, neighborRadius=4)
CASES = [dict(id=f's{index:02d}', selector=dict(kind='near-optimal-token-softmax-1',
    temperature=temperature, maxScoreGap=gap, neighborRadius=radius))
    for index, (gap, radius, temperature) in enumerate((gap, radius, temperature)
        for gap, radius in ((6, 4), (16, 8)) for temperature in (.7, 1.5, 3, 8))]
BANKS = (
    dict(name='development', worlds=128, opponentStyle='BALANCED', control='LEARNED',
        replayIndices=[0, 42, 85, 127]),
    dict(name='mixed', worlds=512, opponentStyle='BALANCED', control='LEARNED',
        replayIndices=[0, 170, 341, 511]),
    dict(name='homogeneous', worlds=512, opponentStyle='QUALIFIED', control='LEARNED',
        replayIndices=[0, 170, 341, 511]),
    dict(name='reference', worlds=512, opponentStyle='QUALIFIED', control='OLD87',
        replayIndices=[0, 170, 341, 511]),
)
BANK_ORDER = tuple(bank['name'] for bank in BANKS)
FIXED = dict(study=STUDY, globalDeadlineEpoch=GLOBAL_DEADLINE, nativeJarSha256=NATIVE_SHA,
    candidateBundleSha256=CANDIDATE_SHA, learnedControlBundleSha256=CANDIDATE_SHA,
    learnedControlSelector=CONTROL_SELECTOR, oldControlBundleSha256=OLD_CONTROL_SHA,
    oldControlSelector=None, caseConfigs=CASES, confidenceMultiplier=c.MULTIPLIER,
    maximumTerminalExecutions=5208, maximumScienceSeconds=16200,
    minimumRemainingSecondsAtPreparation=10800, auditSeconds=600,
    positiveBidConcentrationMaximum=.8, workers=10, heapMiB=6144,
    maximumRssBytes=12*1024**3, minimumFreeBytes=16*1024**3,
    maximumDataBytes=768*1024**2, chunkSize=256,
    localOnly=True, remoteUseAuthorized=False, noRetry=True, noResume=True, noTopUp=True,
    noAutomaticSuccessor=True, noNewFits=True, weightsImmutable=True,
    launchViaOwnedTerminal=True)


def read_method(path):
    path = Path(path)
    c.require(c.sha(path) == METHOD_SHA,
        'Only the exact prospective root-reviewed study92 method may prepare an experiment.')
    method = c.read(path)
    c.require(method.get('kind') == 'learned-bid-search-prospective-method-1' and
        method['study'] == 92 and all(method[key] == value for key, value in FIXED.items()) and
        method['banks'] == list(BANKS),
        'The root method must retain the fixed search, four independent banks and Mac-only budget.')
    return method


def validate(plan):
    c.require(plan.get('kind') == KIND and plan['methodSha256'] == METHOD_SHA and
        all(plan[key] == value for key, value in FIXED.items()),
        'The finite learned-bid protocol must preserve every prospective method parameter.')
    prepared = plan['preparedEpoch']
    c.require(type(prepared) in (int, float) and math.isfinite(prepared) and
        GLOBAL_DEADLINE-prepared >= FIXED['minimumRemainingSecondsAtPreparation'],
        'Study92 must defer before preparation when fewer than three original hours remain.')
    reserved = plan['reservedSchedule']
    c.require(reserved['kind'] == 'learned-bid-search-schedule-1' and
        hashlib.sha256(c.canonical(reserved)).hexdigest() == plan['scheduleSha256'] and
        set(reserved['banks']) == {bank['name'] for bank in BANKS} and
        set(plan['banks']) == {bank['name'] for bank in BANKS},
        'The complete newly reserved four-bank schedule must remain unchanged.')
    seeds = []
    for bank in BANKS:
        name = bank['name']; actual = plan['banks'][name]; original = reserved['banks'][name]
        c.require(actual == dict(bank, seeds=original['seeds']) and
            original['count'] == bank['worlds'] and len(original['seeds']) == bank['worlds'] and
            all(type(seed) is int and -(2**31) <= seed < 2**31 for seed in original['seeds']),
            'Each study92 bank must consume exactly its original complete bounded native seed list.')
        seeds.extend(original['seeds'])
    c.require(len(seeds) == len(set(seeds)) == 1664,
        'The four original study92 banks must contain 1664 mutually disjoint worlds.')
    c.require(plan['inferenceAssetSha256'] == ASSET_SHA and plan['nominationSha256'] == NOMINATION_SHA,
        'The unchanged87 numerical references and calibration foundation must remain exact.')
    return plan


def native_source(plan, profile, bank_name, case, chunk, protocol_sha):
    """Learned banks change all rival/control heads; reference omits overrides for exact87."""
    bank = plan['banks'][bank_name]
    start = chunk*FIXED['chunkSize']
    c.require(case in CASES and type(chunk) is int and chunk >= 0 and start < bank['worlds'],
        'A native task requires one original case and a bounded original chunk.')
    candidate = profile['candidateBundle']
    c.require(candidate['sha256'] == CANDIDATE_SHA and
        profile['inferenceAsset']['sha256'] == ASSET_SHA and
        profile['nomination']['sha256'] == NOMINATION_SHA and
        profile['arena']['sha256'] == plan['arenaSha256'],
        'Actual native subjects must match the immutable learned candidate and original references.')
    source = dict(kind='robust-token-chunk-1', protocolSha256=protocol_sha,
        inferenceAsset=profile['inferenceAsset'], nomination=profile['nomination'], arena=profile['arena'],
        candidateBundle=candidate, selector=case['selector'], opponentStyle=bank['opponentStyle'],
        seeds=bank['seeds'], globalStart=start, count=min(FIXED['chunkSize'],bank['worlds']-start),
        replayIndices=bank['replayIndices'])
    if bank['control'] == 'LEARNED':
        source.update(controlBundle=candidate, controlSelector=CONTROL_SELECTOR)
    return source


def check_source(plan, profile, source, bank_name, case, chunk, protocol_sha):
    c.require(source == native_source(plan,profile,bank_name,case,chunk,protocol_sha),
        'Every native request must retain its exact frozen bank, paired roles and model/sampler subjects.')


def check_prepared(prepared, bank_name):
    """Effective native role identities prove that omitted reference overrides mean exact87."""
    c.require(bank_name in {bank['name'] for bank in BANKS},
        'Only an original study92 bank may receive native admission.')
    identity = prepared['scienceIdentity']
    learned = bank_name != 'reference'
    c.require(identity['candidateBundleSha256'] == CANDIDATE_SHA and
        identity['controlAndRivalsBundleSha256'] == (CANDIDATE_SHA if learned else OLD_CONTROL_SHA) and
        identity.get('controlSelector') == (CONTROL_SELECTOR if learned else None) and
        identity['roles'] == ['CANDIDATE','CONTROL'],
        'The native paired identity must prove the exact learned or unchanged87 comparator and rivals.')


def native_chunk(source, directory, bank_name, *, source_path=None):
    import robust_token_audit
    result = robust_token_audit.native_chunk(source,directory,source_path=source_path)
    prepared=c.read(Path(directory)/'prepared.json')
    check_prepared(prepared,bank_name)
    check_inputs(prepared,source)
    return result


def check_inputs(prepared, source):
    for path,digest in prepared['inputPins'].items():
        c.require(c.sha(path)==digest,'An actual native model or numerical reference input changed.')
    for name in ('candidateBundle','inferenceAsset','nomination','arena','controlBundle'):
        if name in source:
            item=source[name]
            c.require(prepared['inputPins'].get(item['path'])==item['sha256'] and
                c.sha(item['path'])==item['sha256'],
                'Every explicitly declared native model/reference subject must remain physically pinned.')


def bid_summary(rows, role):
    """Observed positive bid concentration across a complete case; not conditional entropy."""
    counts = Counter()
    for row in rows:
        values = row['diagnostics'][role].get('controlledBidCounts')
        c.require(type(values) is dict,
            'Every original world requires its explicitly recorded controlled token bid counts.')
        for text, count in values.items():
            try: amount = int(text)
            except (ValueError, TypeError): raise ValueError('Recorded token amounts must be canonical signed integers.')
            c.require(str(amount) == text and type(count) is int and count > 0,
                'Recorded token amounts and positive integral counts must retain their native wire values.')
            counts[amount] += count
    positive = {amount:count for amount,count in sorted(counts.items()) if amount > 0}
    total = sum(positive.values())
    c.require(total > 0,
        'Missing or empty positive controlled bid counts refuse admission; they cannot imply unpredictability.')
    maximum = max(positive.values())
    return dict(counts={str(amount):count for amount,count in sorted(counts.items())},
        positiveCount=total, maximumSinglePositiveAmountCount=maximum,
        maximumSinglePositiveAmountShare=maximum/total,
        mostFrequentPositiveAmount=min(amount for amount,count in positive.items() if count == maximum),
        positiveBidEntropyNats=-math.fsum((count/total)*math.log(count/total) for count in positive.values()),
        positiveBid21Share=positive.get(21,0)/total,
        observedConcentrationPassed=maximum*5 <= total*4,
        conditionalUnpredictabilityClaimed=False)


def aggregate(rows, bank):
    report = c.aggregate(rows,bank)
    return dict(report, observedBids={role:bid_summary(rows,role) for role in ('CANDIDATE','CONTROL')})


def select(reports):
    c.require(set(reports) == {case['id'] for case in CASES},
        'Nomination requires all eight complete original development cases.')
    eligible = [case for case in CASES if reports[case['id']]['wins']['mean'] > 0 and
        reports[case['id']]['observedBids']['CANDIDATE']['observedConcentrationPassed']]
    return max(eligible,key=lambda case:reports[case['id']]['wins']['mean']) if eligible else None


def passed(report, bank_name):
    c.require(bank_name in ('mixed','homogeneous','reference'),
        'Only an original independent confirmation bank has a qualification gate.')
    return report['wins']['lower99'] > 0 and (bank_name == 'reference' or
        report['observedBids']['CANDIDATE']['observedConcentrationPassed'])
