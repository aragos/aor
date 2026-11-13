"""Fixed family80 scientific identities; host paths and execution remain separate.

The three cohorts have independent worlds. A cohort index is never a global cross-head
sample ID: its head and iteration are required parts of the identity.
"""
from dataclasses import dataclass
import hashlib
import json
import math

from token_paired_campaign_plan import (HOSTS, COLLECTION_CHUNKS, SCREEN_CHUNKS,
    COLLECTION_REPLAYS, SCREEN_REPLAYS, canonical, digest, file_sha, sha, integer,
    read, subject, checked_subject, require, owner, indices, replays)

KIND = 'cpe-paired-campaign-1'
HEADS = ('CARD', 'PURCHASE', 'EXPANSION')
ROLES = ('FINAL', 'RAW_G3', 'INITIAL')
ORIGINAL_EXPANSION_SHA = '8268ba94536c5286ca7e7e9aedf148bffab2b8f5244e7a631594c5b4af8303fc'
SCIENCE_SECONDS = 64800
RECIPE = dict(kind='cpe-paired-direct-objective-1', iterations=2, heads=list(HEADS),
    originsPerHead=640, train=512, validation=128, alternatives=8, futures=2,
    epochs=20, batchSize=64, learningRate=.0003, maxGradientNorm=.5,
    anchorCoefficient=.1, maximumMeanKl=.02, maximumStateKl=.10,
    dtype='float64', threads=1, minimumBranchingTrain=256,
    minimumBranchingValidation=64, validationSelection=False, valueUpdates=False,
    reference='CURRENT_SAMPLED_COMMAND', root='UNIFORM_BRANCHING_RESERVOIR',
    atomicHeads=True, freshAdamPerHeadIteration=True)


def head(value):
    require(value in HEADS, 'A cohort requires an explicit CARD, PURCHASE or EXPANSION head.')
    return value


def request(index, phase):
    integer(index, 0, 639, 'Cohort origin index'); head(phase)
    return dict(head=phase, globalIndex=index, seat=index % 4,
        epoch=1 + (index // 4) % 3, split='TRAIN' if index < 512 else 'VALIDATION')


def head_record(values):
    require(isinstance(values, dict) and set(values) == set(HEADS),
        'All three explicit head identities are mandatory.')
    return values


@dataclass(frozen=True)
class Plan:
    wire: bytes

    @classmethod
    def from_record(cls, record):
        require(record['kind'] == KIND and record['recipe'] == RECIPE,
            'Only the fixed two-iteration three-head paired recipe is admitted.')
        require(record['maximumTerminalExecutions'] == 76070 and
            record['scienceSeconds'] == SCIENCE_SECONDS and record['auditSeconds'] == 900 and
            record['maximumDataBytes'] == 48 * 1024**3 and
            record['automaticRetry'] is False and record['automaticSuccessor'] is False,
            'The original execution/storage limits and no-retry policy are mandatory.')
        require(set(record['hosts']) == set(HOSTS), 'Both prescribed hosts are required.')
        for host, cpus, workers in (('DESKTOP', 16, 10), ('MAC', 10, 6)):
            profile = record['hosts'][host]
            require(profile == dict(machineCpus=cpus, workers=workers, heapMiB=6144,
                maximumRssBytes=12*1024**3, maximumDataBytes=24*1024**3,
                minimumFreeBytes=16*1024**3), 'The fixed host resource declaration changed.')
        for name in ('originalBundleSha256', 'bundleScopeSha256', 'ecologySha256'):
            sha(record[name])
        for phase, values in head_record(record['heads']).items():
            require(set(values) == {'originalActorSha256', 'dictionarySha256',
                'semanticDictionarySha256', 'scopeSha256'}, 'A head requires exact actor/dictionary/scope pins.')
            for value in values.values(): sha(value)
            require(values['scopeSha256'] == digest(dict(
                bundleScopeSha256=record['bundleScopeSha256'], head=phase)),
                'Each head scope must bind the shared original bundle scope and explicit head.')
        require(record['heads']['EXPANSION']['originalActorSha256'] == ORIGINAL_EXPANSION_SHA,
            'EXPANSION must reuse the exact original untrained75 initializer; no trained fallback.')
        require(len(record['iterations']) == 2, 'Exactly two immutable complete iteration banks are required.')
        streams = []
        for iteration, bank in enumerate(record['iterations'], 1):
            require(set(bank) == {'iteration', 'heads'} and bank['iteration'] == iteration,
                'An iteration must explicitly own its three complete cohort banks.')
            for phase, cohort in head_record(bank['heads']).items():
                require(set(cohort) == {'origins', 'shuffleSeeds'} and
                    len(cohort['origins']) == 640 and len(cohort['shuffleSeeds']) == 20,
                    'Each head requires640 original worlds and20 fixed epoch streams.')
                for row in cohort['origins']:
                    require(set(row) == {'gameSeed', 'reservoirSeed', 'proposalSeed', 'future'} and
                        isinstance(row['future'], list) and len(row['future']) == 2,
                        'Backbone/reservoir/proposal/two future streams must be explicit and independent.')
                    streams.append(integer(row['gameSeed'], -(2**31), 2**31-1, 'Native world seed'))
                    streams += [integer(value, -(2**63), 2**63-1, 'Reservoir/proposal/future stream')
                        for value in (row['reservoirSeed'], row['proposalSeed'], *row['future'])]
                streams += [integer(value, 1, 2**31-1, 'Epoch shuffle stream')
                    for value in cohort['shuffleSeeds']]
        screen = record['screenBank']
        require(set(screen) == {'nativeOffset', 'count', 'seeds'} and screen['count'] == 1024,
            'Only one fixed final1024-world screen bank is admitted.')
        offset = integer(screen['nativeOffset'], 0, 100000000, 'Screen native offset')
        require(screen['seeds'] == [-1000000000+offset+i*8191 for i in range(1024)],
            'Screen identities must equal the authoritative native world formula.')
        streams += screen['seeds']
        require(len(streams) == len(set(streams)),
            'Worlds, reservoir/proposal/futures and numerical streams must be globally disjoint.')
        require(record['selection'] == dict(candidate='ITERATION_2_COMPLETE_BUNDLE_ONLY',
            validationEqualPhaseRewardLower95Positive=True,
            finalMinusInitialRewardLower95Positive=True,
            finalMinusRawWinAndRewardLower95Positive=True),
            'Only the predeclared complete final bundle and all four fixed gates may nominate.')
        return cls(canonical(record))

    @property
    def record(self):
        return json.loads(self.wire)

    @property
    def sha256(self):
        return hashlib.sha256(self.wire).hexdigest()

    def cohort(self, iteration, phase):
        integer(iteration, 1, 2, 'Iteration'); head(phase)
        return self.record['iterations'][iteration-1]['heads'][phase]

    def collection_source(self, iteration, phase, chunk, host, arena, current, original):
        self.cohort(iteration, phase)
        require(host == owner(chunk), 'A fixed shard cannot migrate or be duplicated.')
        for value in (arena, current, original): checked_subject(value)
        require(original['sha256'] == self.record['originalBundleSha256'] and
            (iteration != 1 or current['sha256'] == original['sha256']),
            'Original and first-iteration complete bundle subjects must be exact.')
        return dict(kind='cpe-paired-collect-1', iteration=iteration, head=phase,
            globalStart=chunk*64, count=64, arena=arena, currentBundle=current,
            originalBundle=original, origins=self.cohort(iteration, phase)['origins'],
            replayIndices=list(COLLECTION_REPLAYS))


@dataclass(frozen=True)
class Clock:
    start: float
    deadline: float

    def __post_init__(self):
        require(all(type(value) in (int, float) and math.isfinite(value)
            for value in (self.start, self.deadline)) and
            self.deadline-self.start == SCIENCE_SECONDS,
            'Both workers require one unchanged original18-hour science clock.')

    def remaining(self, now):
        require(math.isfinite(now) and now < self.deadline, 'The original science deadline expired.')
        return self.deadline-now

    def audit_deadline(self, terminal):
        require(math.isfinite(terminal) and terminal >= self.start,
            'The persisted original terminal time is required.')
        return min(terminal+900, self.deadline+900)


def expected_terminal_budget():
    return dict(backbones=3840, maximumTails=69120, backboneReproductions=24,
        screen=3072, screenReproductions=12, historicalControls=2, maximum=76070)
