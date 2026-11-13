"""Finite family81 identities and fixed host ownership; no model or outcome access.

The portable protocol owns science. Host profiles own readable paths. Projection and
Gaussian arithmetic remain with the numerical owner, never this scheduling contract.
"""
from dataclasses import dataclass
import json
import math
from pathlib import Path

from token_paired_campaign_plan import (
    canonical, checked_subject, digest, file_sha, integer, read, require, sha, subject,
)

PREFIX = 'coordinated-es'
KIND = PREFIX + '-campaign-1'
HEADS = ('CARD', 'PURCHASE', 'EXPANSION', 'TOKEN')
HOSTS = ('DESKTOP', 'MAC')
SCIENCE_SECONDS = 86400
AUDIT_SECONDS = 900
MAXIMUM_TERMINALS = 139306
GENERATION_CHUNKS = {'DESKTOP': tuple(range(5)), 'MAC': tuple(range(5, 8))}
SCREEN_CHUNKS = {'DESKTOP': tuple(range(10)), 'MAC': tuple(range(10, 16))}
SCREEN_REPLAYS = (0, 682, 1365, 2047)
HISTORICAL_CONTROLS = [dict(family=60,bank='HOMOGENEOUS_HELDOUT',index=index,side='candidate')
    for index in (0,4095)]
ORIGINAL_ACTORS = dict(
    CARD='916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf',
    PURCHASE='6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79',
    EXPANSION='8268ba94536c5286ca7e7e9aedf148bffab2b8f5244e7a631594c5b4af8303fc',
    TOKEN='812a16f1b8d26911752db0720607d260f410b821922ba2f10f1e3254aa8c9ae6',
)
RECIPE = dict(
    kind=PREFIX + '-normalized-antithetic-1', heads=list(HEADS), dimensions=256,
    coordinatesPerHead=64, generations=16, directions=16, mutants=32, worlds=256,
    sigma=1., stepRms=1., maximumCenterRms=4., minimumGradientRms=1e-12,
    dtype='float64', threads=1, reward='AUTHORITATIVE_NORMALIZED_TERMINAL',
    ranking=False, valueUpdates=False, mutantProjection=False,
    liveHeadMovement='DIAGNOSTIC_ONLY', candidate='FINAL_MEAN_16_ONLY',
)
CALIBRATION = dict(
    kind=PREFIX + '-signed-gap-calibration-1', generation=1, heads=['EXPANSION', 'TOKEN'],
    windows='FIRST_LAST_BRANCHING_ASSIGNED_EPOCH', minimumWorlds=128,
    weighting='EQUAL_WORLD_THEN_UNIQUE_STATE', quantile=.9, minimumPositiveMass=.1,
    minimumQuantile=1e-8, numerator=1. + 2.**-20,
    minimumScale=1./32., maximumScale=32., minimumMovement=.05, maximumMovement=.40,
    meanProbe='ALTERNATING_SIGN_LINEAR_FITNESS', margin=1., scoreClamp=False,
    ties='REFERENCE_THEN_LOWEST_ORIGINAL_INDEX', recalibration=False,
)
PREFIX_GATE = dict(
    completedGenerations=2, remainingGenerations=14, finalReserveSeconds=7200,
    screenReserveBytes=2 * 1024**3, generationCost='MAXIMUM_INCLUSIVE_WALL_TIME',
    bytes='SHARED_BASE_ONCE_PLUS_16_MAX_GENERATION_DELTAS',
)
GAME_LIMITS = dict(maximumSeconds=300, maximumDecisions=12000,
    invalidGame='ABORT_ATTEMPT', rewardImputation=False, refill=False)


def host_limits(host):
    require(host in HOSTS, 'A declared Desktop or Mac host is required.')
    cpus, workers = (16, 10) if host == 'DESKTOP' else (10, 6)
    return dict(machineCpus=cpus, workers=workers, heapMiB=6144,
        maximumRssBytes=12*1024**3, maximumDataBytes=24*1024**3,
        minimumFreeBytes=16*1024**3)


def generation(value):
    return integer(value, 1, 16, 'Generation')


def owner(chunk, screen=False):
    integer(chunk, 0, 15 if screen else 7, 'Fixed shard')
    table = SCREEN_CHUNKS if screen else GENERATION_CHUNKS
    return next(host for host in HOSTS if chunk in table[host])


def indices(chunk, screen=False):
    owner(chunk, screen)
    size = 128 if screen else 32
    return range(chunk*size, (chunk+1)*size)


def mutant(index):
    integer(index, 0, 31, 'Mutant index')
    return dict(index=index, direction=index//2, sign=1 if index%2 == 0 else -1)


def replays(number, chunk):
    generation(number)
    return [dict(globalIndex=index, mutant=mutant(2*(number-1)+sign))
        for index, sign in ((0, 0), (255, 1)) if index in indices(chunk)]


def screen_replays(chunk):
    return tuple(index for index in SCREEN_REPLAYS if index in indices(chunk, True))


def expected_budget():
    return dict(rawControls=4096, mutants=131072, populationReproductions=32,
        screenGames=4096, screenReproductions=8, historicalControls=2,
        maximumTerminalExecutions=MAXIMUM_TERMINALS)


def native_bank(record, count):
    require(set(record) == {'nativeOffset', 'count', 'seeds'} and record['count'] == count,
        'A complete fixed native world bank is required.')
    offset = integer(record['nativeOffset'], 0, 100000000, 'Native world offset')
    require(record['seeds'] == [-1000000000 + offset + i*8191 for i in range(count)],
        'Every world seed must equal its authoritative global-index formula.')
    return record['seeds']


@dataclass(frozen=True)
class Plan:
    wire: bytes

    @classmethod
    def from_file(cls, path):
        plan = cls.from_record(read(path))
        require(Path(path).read_bytes() == plan.wire + b'\n',
            'The shared protocol must preserve canonical JSON and exactly one framing LF.')
        return plan

    @classmethod
    def from_record(cls, record):
        require(set(record) == {'kind', 'recipe', 'calibration', 'prefixGate', 'gameLimits', 'budget',
            'scienceSeconds', 'auditSeconds', 'hosts', 'original', 'projectionSeed',
            'generations', 'screenBank', 'screenGate', 'historicalControls', 'automaticRetry', 'automaticSuccessor'},
            'A coordinated ES protocol requires only its complete explicit scientific fields.')
        require(record['kind'] == KIND and record['recipe'] == RECIPE and
            record['calibration'] == CALIBRATION and record['prefixGate'] == PREFIX_GATE and
            record['gameLimits'] == GAME_LIMITS,
            'Only the prescribed optimizer, one calibration and outcome-blind prefix are admitted.')
        require(record['budget'] == expected_budget() and
            record['scienceSeconds'] == SCIENCE_SECONDS and record['auditSeconds'] == AUDIT_SECONDS and
            record['automaticRetry'] is False and record['automaticSuccessor'] is False,
            'Counts, original clocks and no-retry/no-successor policy cannot change.')
        require(record['historicalControls']==HISTORICAL_CONTROLS,
            'Only the two predetermined original qualified-G3 historical sides may execute.')
        require(record['hosts'] == {host: host_limits(host) for host in HOSTS},
            'Both exact native resource profiles must remain declared.')
        original = record['original']
        require(set(original) == {'bundleSha256', 'scopeSha256', 'ecologySha256',
            'projectionSha256', 'projectionRecipeSha256', 'heads'},
            'The original four-head scope and fixed projection need complete identities.')
        for key, value in original.items():
            if key != 'heads': sha(value)
        require(set(original['heads']) == set(HEADS), 'Every original head must be explicitly bound.')
        for head, row in original['heads'].items():
            require(set(row) == {'actorSha256', 'dictionarySha256'} and
                row['actorSha256'] == ORIGINAL_ACTORS[head],
                'Each head must retain its prescribed original qualified or untrained bytes.')
            sha(row['dictionarySha256'])
        streams = [integer(record['projectionSeed'], 1, 2**31-1, 'Projection stream')]
        require(isinstance(record['generations'], list) and len(record['generations']) == 16,
            'Exactly16 complete generation banks and direction streams are required.')
        for number, row in enumerate(record['generations'], 1):
            require(set(row) == {'generation', 'worldBank', 'directionSeed'} and
                row['generation'] == number, 'Generation identity/order cannot change.')
            streams += native_bank(row['worldBank'], 256)
            streams.append(integer(row['directionSeed'], 1, 2**31-1, 'Direction stream'))
        streams += native_bank(record['screenBank'], 2048)
        require(len(streams) == len(set(streams)),
            'Projection, direction, generation and final-screen streams must all be distinct.')
        require(record['screenGate'] == dict(worlds=2048, roles=['FINAL', 'RAW_G3'],
            candidate='FINAL_MEAN_16_ONLY', intervalMultiplier=1.96,
            rewardLower95Positive=True, winLower95Positive=True, qualification=False),
            'Only the complete final mean can enter the one prescribed development screen.')
        return cls(canonical(record))

    @property
    def record(self):
        return json.loads(self.wire)

    @property
    def sha256(self):
        return digest(self.record)

    def bank(self, number):
        return self.record['generations'][generation(number)-1]

    def generation_source(self, number, chunk, phase, subjects):
        """Native executable request; construction does not open models or fitness records."""
        generation(number); owner(chunk)
        require(phase in ('CONTROLS', 'POPULATION'), 'A fixed native stage is required.')
        required = {'protocol', 'arena', 'originalBundle', 'projection', 'center',
            'directions', 'population', 'calibration', 'previousUpdate', 'historical'}
        require(set(subjects) == required, 'The native generation needs its exact subject closure.')
        nullable = {'population', 'calibration', 'previousUpdate'}
        for key, value in subjects.items():
            if value is None:
                require(key in nullable, 'A mandatory generation subject cannot be omitted.')
            else:
                require(isinstance(value, dict) and set(value) == {'path', 'sha256'},
                    'Native subjects retain an explicit path and raw SHA256.')
                require(isinstance(value['path'], str) and value['path'], 'A subject path is required.')
                sha(value['sha256'])
        require((subjects['population'] is None) == (phase == 'CONTROLS'),
            'Only population stages admit a sealed executable population.')
        require((subjects['calibration'] is None) == (number == 1 and phase == 'CONTROLS'),
            'Only first-generation controls may precede the single calibration seal.')
        require((subjects['previousUpdate'] is None) == (number == 1),
            'Every later generation must bind its immediate preceding update.')
        require(subjects['originalBundle']['sha256'] == self.record['original']['bundleSha256'] and
            subjects['projection']['sha256'] == self.record['original']['projectionSha256'],
            'The immutable original bundle and fixed projection cannot change.')
        return dict(kind=PREFIX+'-generation-1', stage=phase, generation=number,
            globalStart=chunk*32, count=32, bank=self.bank(number)['worldBank'], **subjects)


@dataclass(frozen=True)
class Clock:
    start: float
    deadline: float

    def __post_init__(self):
        require(all(type(v) in (int, float) and math.isfinite(v) for v in (self.start, self.deadline))
            and self.deadline-self.start == SCIENCE_SECONDS,
            'Both workers require one unchanged original24-hour science clock.')

    def remaining(self, now):
        require(type(now) in (int, float) and math.isfinite(now) and now < self.deadline,
            'The original shared science deadline expired.')
        return self.deadline-now

    def audit_deadline(self, terminal):
        require(type(terminal) in (int, float) and math.isfinite(terminal) and terminal >= self.start,
            'A persisted original terminal timestamp is required.')
        return min(terminal+AUDIT_SECONDS, self.deadline+AUDIT_SECONDS)


def prefix_gate(remaining_seconds, generation_seconds, retention):
    """Pure resource gate after two complete generations; no model or reward arguments.

    Each host supplies its shared/base bytes once and two inclusive generation deltas.
    The caller records the exact sampled remaining time and uses this same result to branch.
    """
    require(type(remaining_seconds) in (int, float) and math.isfinite(remaining_seconds) and
        remaining_seconds >= 0 and len(generation_seconds) == 2 and all(
            type(v) in (int, float) and math.isfinite(v) and v > 0 for v in generation_seconds),
        'The prefix needs one finite remaining-clock sample and two positive inclusive durations.')
    require(set(retention) == set(HOSTS), 'The prefix needs both original hosts storage evidence.')
    projected = {}
    for host, row in retention.items():
        require(set(row) == {'sharedBaseBytes', 'generationDeltaBytes'} and
            len(row['generationDeltaBytes']) == 2,
            'Storage must separate shared base overhead from both complete generation deltas.')
        base = integer(row['sharedBaseBytes'], 0, 24*1024**3, 'Shared retained base bytes')
        deltas = [integer(v, 0, 24*1024**3, 'Inclusive generation retained bytes')
            for v in row['generationDeltaBytes']]
        projected[host] = base + 16*max(deltas) + PREFIX_GATE['screenReserveBytes']
    needed = 14*max(generation_seconds) + PREFIX_GATE['finalReserveSeconds']
    time_passed = remaining_seconds >= needed
    storage_passed = all(projected[h] <= host_limits(h)['maximumDataBytes'] for h in HOSTS)
    return dict(kind=PREFIX+'-operational-prefix-1', completedGenerations=2,
        remainingSeconds=remaining_seconds, generationSeconds=list(generation_seconds),
        requiredRemainingSeconds=needed, retention=retention, projectedRetainedBytes=projected,
        timePassed=time_passed, storagePassed=storage_passed, passed=time_passed and storage_passed)
