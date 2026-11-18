"""Prospectively fixed finite leader-denial experiment; no simulation, inference or RNG draws."""
from datetime import datetime
import hashlib
import math
import os
from pathlib import Path
import re

import robust_token_campaign as c

KIND = 'leader-denial-campaign-1'
METHOD_SHA = '79a06fe8b7760b8ba7b35a8aebce6f9e3fe5e952f156b0cb1b9795e51d60e468'
RESERVATION_SHA = '12c96d27c5b75a0b33c9c125fff58b452a1b175c2355440f1a53343566d65953'
SEED_REVIEW_SHA = 'ee6f4a5ad6428889e542f741e38fde74f772c655e558279be2f665763ff38b44'
NATIVE_SHA = '14245cba2d1dbd4e62780371efd84d4416bd91592ceca876449d7f417756463f'
ASSET_SHA = 'c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a'
NOMINATION_SHA = 'f9da5a9e987870a847e4a56091174b56c137c40cead67936dfb1e0cbad95bb4d'
GLOBAL_DEADLINE = datetime.fromisoformat('2026-11-18T02:28:29+00:00').timestamp()
DATA_MODES = {
    'FULL': dict(minimumRemainingSeconds=9000, maximumTerminalExecutions=16464,
        unusedReservedWorlds=0, banks={
            'development': (512, [0, 170, 341, 511]),
            'mixed': (2048, [0, 682, 1365, 2047]),
            'homogeneous': (2048, [0, 682, 1365, 2047]),
        }),
    'SMALL': dict(minimumRemainingSeconds=4500, maximumTerminalExecutions=8272,
        unusedReservedWorlds=2304, banks={
            'development': (256, [0, 85, 170, 255]),
            'mixed': (1024, [0, 341, 682, 1023]),
            'homogeneous': (1024, [0, 341, 682, 1023]),
        }),
}
BANKS = ('development', 'mixed', 'homogeneous')


def data_mode(frozen_epoch):
    remaining = GLOBAL_DEADLINE-frozen_epoch
    return next((name for name, mode in DATA_MODES.items()
        if remaining >= mode['minimumRemainingSeconds']), None)


CASES = [dict(id=f'd{index:02d}', expansionDenial=dict(kind='public-leader-attack-bonus-1',
    beta=beta, minimumWinProbability=probability))
    for index, (beta, probability) in enumerate((beta, probability)
        for beta in (.25, .5, 1.0, 2.0) for probability in (.5, .8))]
ORIGINS = ('89-final-6', '88b-selector', '87')
COMPARATOR_KEYS = ('origin', 'bundleSha256', 'selector', 'qualificationSha256')


def digest(value):
    return hashlib.sha256(c.canonical(value)).hexdigest()


def validate_selector(selector):
    if selector is None: return
    c.require(type(selector) is dict and set(selector) ==
        {'kind', 'temperature', 'maxScoreGap', 'neighborRadius'} and
        selector['kind'] == 'near-optimal-token-softmax-1' and
        type(selector['temperature']) in (int, float) and 1/64 <= selector['temperature'] <= 8 and
        type(selector['maxScoreGap']) in (int, float) and 0 <= selector['maxScoreGap'] <= 16 and
        type(selector['neighborRadius']) is int and 1 <= selector['neighborRadius'] <= 8,
        'A comparator token policy requires the complete typed bounded sampler or explicit null.')


def validate(plan):
    c.require(plan.get('kind') == KIND and plan['methodSha256'] == METHOD_SHA and
        plan['seedReviewSha256'] == SEED_REVIEW_SHA and plan['seedReservationSha256'] == RESERVATION_SHA,
        'The leader-denial protocol must retain its exact root-reviewed method and RNG reservation.')
    frozen_epoch = plan['modeFrozenEpoch']
    c.require(type(frozen_epoch) in (int, float) and math.isfinite(frozen_epoch) and
        plan['dataMode'] in DATA_MODES and data_mode(frozen_epoch) == plan['dataMode'],
        'A fixed FULL or SMALL size must be selected only from remaining wall time before preparation.')
    mode = DATA_MODES[plan['dataMode']]
    c.require(plan['maximumScienceSeconds'] == 7200 and plan['auditSeconds'] == 600 and
        plan['maximumTerminalExecutions'] == mode['maximumTerminalExecutions'] and plan['chunkSize'] == 64 and
        plan['minimumDevelopmentDelta'] == 0 and plan['confidenceMultiplier'] == c.MULTIPLIER and
        plan['globalDeadlineEpoch'] == GLOBAL_DEADLINE,
        'The fixed two-hour/absolute deadline, selected prefixes, confidence and execution cap cannot change.')
    c.require(plan['caseConfigs'] == CASES and set(plan['banks']) == set(BANKS),
        'Only the eight prospectively ordered denial settings and three independent banks are admitted.')
    reservation = plan['reservedSchedule']
    c.require(digest(reservation) == RESERVATION_SHA,
        'All 4608 reserved worlds, including every unused suffix, must remain byte-canonically unchanged.')
    all_seeds = []
    for name, (count, replays) in mode['banks'].items():
        bank = plan['banks'][name]; reserved = reservation['banks'][name]['seeds']
        c.require(bank['seeds'] == reserved[:count] and len(bank['seeds']) == count and
            bank['replayIndices'] == replays and
            bank['opponentStyle'] == ('QUALIFIED' if name == 'homogeneous' else 'BALANCED'),
            'Each active bank must use only its fixed original prefix and prescribed reproductions.')
        all_seeds.extend(bank['seeds'])
    c.require(len(set(all_seeds)) == 4608-mode['unusedReservedWorlds'] and
        sum(len(row['seeds']) for row in reservation['banks'].values()) == 4608 and
        plan['unusedReservedWorlds'] == mode['unusedReservedWorlds'],
        'Every unconsumed original suffix must remain protected and cannot be reassigned or topped up.')
    c.require(plan['resources'] == dict(workers=dict(DESKTOP=16, MAC=10), heapMiB=6144,
        maximumRssGiB=12, minimumFreeGiB=16),
        'The fixed Terminal-launched allocation must retain 16 Desktop workers and 10 Mac workers.')
    comparator = plan['comparator']
    c.require(comparator['origin'] in ORIGINS and all(isinstance(comparator[key], str) and
        re.fullmatch('[0-9a-f]{64}', comparator[key])
        for key in ('bundleSha256', 'qualificationSha256', 'rootFreezeSha256')),
        'The comparator must be the exact root-frozen qualified lineage and model subject.')
    validate_selector(comparator['selector'])
    c.require(plan['nativeJarSha256'] == NATIVE_SHA and plan['inferenceAssetSha256'] == ASSET_SHA and
        plan['nominationSha256'] == NOMINATION_SHA,
        'Only the reviewed native90 runtime and unchanged numerical-reference asset may execute.')
    return plan


def validate_freeze(record, plan):
    c.require(record.get('kind') == 'leader-denial-comparator-freeze-1' and
        record.get('passed') is True and record.get('complete') is True and
        record['methodSha256'] == METHOD_SHA and
        all(record[key] == plan['comparator'][key] for key in COMPARATOR_KEYS),
        'A root-written passing comparator freeze must bind the exact qualified bundle and token policy.')
    c.require(record.get('qualified') is True,
        'An operational closure or unqualified development result cannot become the comparator.')


class LeaderDenialExperiment(c.TokenExperiment):
    prefix = 'leader-denial'
    profile_kind = 'leader-denial-host-1'
    worker_script = 'leader_denial_worker.py'
    workers = {'DESKTOP': 16, 'MAC': 10}
    validate = staticmethod(validate)

    def native_environment(self):
        environment = os.environ.copy()
        for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
            environment.pop(name, None)
        return environment

    def task_owner(self, plan, bank_name, case, chunk):
        return 'MAC' if chunk % 4 == 3 else 'DESKTOP'

    def extra_profile(self, root, plan):
        root = Path(root); freeze = root/'comparator-freeze.json'
        c.require(freeze.is_file() and c.sha(freeze) == plan['comparator']['rootFreezeSha256'],
            'Actual host admission requires the original frozen comparator bytes before preparation.')
        validate_freeze(c.read(freeze), plan)
        control = root/'inputs/control/bundle.json'
        c.require(c.sha(control) == plan['comparator']['bundleSha256'],
            'The actual comparator descriptor must retain the exact root-qualified bundle bytes.')
        return dict(controlBundle=dict(path=str(control), sha256=c.sha(control)),
            controlSelector=plan['comparator']['selector'],
            comparatorFreeze=dict(path=str(freeze), sha256=c.sha(freeze)))

    def native_source(self, plan, profile, bank_name, case, chunk, protocol_sha):
        bank = plan['banks'][bank_name]
        control = profile['controlBundle']
        c.require(control['sha256'] == plan['comparator']['bundleSha256'] and
            profile['controlSelector'] == plan['comparator']['selector'],
            'Every case must retain its identical root-frozen candidate/control model and token policy.')
        return dict(kind='robust-token-chunk-1', protocolSha256=protocol_sha,
            inferenceAsset=profile['inferenceAsset'], nomination=profile['nomination'], arena=profile['arena'],
            candidateBundle=control, controlBundle=control,
            selector=profile['controlSelector'], controlSelector=profile['controlSelector'],
            expansionDenial=case['expansionDenial'], opponentStyle=bank['opponentStyle'],
            seeds=bank['seeds'], globalStart=chunk*64, count=64, replayIndices=bank['replayIndices'])

    def check_source(self, plan, source, bank_name, case, chunk, protocol_sha):
        profile = dict(inferenceAsset=source['inferenceAsset'], nomination=source['nomination'],
            arena=source['arena'], controlBundle=source['controlBundle'],
            controlSelector=plan['comparator']['selector'])
        c.require(source == self.native_source(plan, profile, bank_name, case, chunk, protocol_sha) and
            source['inferenceAsset']['sha256'] == plan['inferenceAssetSha256'] and
            source['nomination']['sha256'] == plan['nominationSha256'] and
            source['arena']['sha256'] == plan['arenaSha256'],
            'Only the exact matched-model denial intervention may vary across native cases.')

    def audit(self, protocol, output, terminal):
        import robust_token_audit
        return robust_token_audit.verify(protocol, output, terminal, contract=self)


def worker_source(profile, request):
    plan = validate(c.read(Path(profile['root'])/'protocol.json'))
    c.require(profile['workers'] == LeaderDenialExperiment.workers[profile['host']],
        'The worker must retain its fixed Desktop16/Mac10 resource allowance.')
    parts = request['taskId'].split('/')
    c.require(len(parts) == 3 and parts[0] in BANKS and parts[2].startswith('chunk-'),
        'Only a fixed original leader-denial stage/case/chunk may execute.')
    bank, case_id, chunk_text = parts
    chunk = int(chunk_text.removeprefix('chunk-'))
    c.require(request['taskId'] == f'{bank}/{case_id}/chunk-{chunk}',
        'A task must retain its single canonical stage/case/chunk identifier.')
    case = next((row for row in plan['caseConfigs'] if row['id'] == case_id), None)
    contract = LeaderDenialExperiment()
    c.require(case is not None and 0 <= chunk < len(plan['banks'][bank]['seeds'])//64 and
        contract.task_owner(plan, bank, case, chunk) == profile['host'],
        'An original task cannot migrate away from its prospectively assigned host.')
    expected = contract.native_source(plan, profile, bank, case, chunk,
        c.sha(Path(profile['root'])/'protocol.json'))
    c.require(request['source'] == expected,
        'The executing native request must equal its frozen method, exact comparator and assigned case.')
