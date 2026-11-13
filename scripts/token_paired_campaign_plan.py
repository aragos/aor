"""Finite family79 identities and fixed host slices; no discovery or scientific defaults.

The shared protocol contains only portable scientific identities. Host profiles supply readable
paths and resource limits separately. A raw native source signature remains host/range specific.
"""
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re

KIND = 'token-paired-campaign-1'
HOSTS = ('DESKTOP', 'MAC')
COLLECTION_CHUNKS = {'DESKTOP': (0, 1, 2, 3, 4, 8), 'MAC': (5, 6, 7, 9)}
SCREEN_CHUNKS = {'DESKTOP': (0, 1, 2, 3, 4), 'MAC': (5, 6, 7)}
COLLECTION_REPLAYS = (0, 213, 426, 639)
SCREEN_REPLAYS = (0, 341, 682, 1023)
ROLES = ('FINAL', 'RAW_T', 'INITIAL')
RECIPE = dict(kind='token-paired-direct-objective-1', iterations=2, origins=640,
    train=512, validation=128, alternatives=8, futures=2, epochs=20, batchSize=64,
    learningRate=.0003, maxGradientNorm=.5, anchorCoefficient=.1,
    maximumMeanKl=.02, maximumStateKl=.10, dtype='float64', threads=1,
    minimumBranchingTrain=256, minimumBranchingValidation=64,
    validationSelection=False, valueUpdates=False)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True,
        allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def sha(value):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value),
        'An exact lowercase SHA256 identity is required.')
    return value


def integer(value, lower, upper, label):
    require(type(value) is int and lower <= value <= upper, label + ' is out of range.')
    return value


def read(path):
    def pairs(items):
        value = {}
        for key, item in items:
            require(key not in value, 'Duplicate JSON field: ' + key)
            value[key] = item
        return value
    return json.loads(Path(path).read_bytes(), object_pairs_hook=pairs,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite JSON: ' + value)))


def subject(path):
    path = Path(path).resolve(strict=True)
    return dict(path=str(path), sha256=file_sha(path))


def checked_subject(value):
    require(isinstance(value, dict) and set(value) in ({'path', 'sha256'},
        {'path', 'sha256', 'originalPath'}), 'A complete immutable file subject is required.')
    path = Path(value['path'])
    require(path.is_absolute() and path.is_file() and not path.is_symlink(),
        'A subject must be a readable absolute regular file.')
    require(file_sha(path) == sha(value['sha256']), 'Immutable subject bytes changed: ' + str(path))
    if 'originalPath' in value:
        require(Path(value['originalPath']).is_absolute(), 'Original provenance path must be absolute.')
    return path


def request(index):
    integer(index, 0, 639, 'Global origin index')
    return dict(globalIndex=index, seat=index % 4, epoch=1 + (index // 4) % 3,
        ordinal=1 + (index // 12) % 4, split='TRAIN' if index < 512 else 'VALIDATION')


def owner(chunk, screen=False):
    integer(chunk, 0, 7 if screen else 9, 'Fixed chunk')
    table = SCREEN_CHUNKS if screen else COLLECTION_CHUNKS
    return next(host for host in HOSTS if chunk in table[host])


def indices(chunk, screen=False):
    owner(chunk, screen)
    size = 128 if screen else 64
    return range(chunk * size, (chunk + 1) * size)


def replays(chunk, screen=False):
    return tuple(index for index in (SCREEN_REPLAYS if screen else COLLECTION_REPLAYS)
        if index in indices(chunk, screen))


@dataclass(frozen=True)
class Plan:
    wire: bytes

    @classmethod
    def from_record(cls, record):
        require(record['kind'] == KIND and record['recipe'] == RECIPE,
            'Only the fixed two-iteration paired TOKEN recipe is admitted.')
        require(record['maximumTerminalExecutions'] == 27414 and
            record['scienceSeconds'] == 21600 and record['auditSeconds'] == 900 and
            record['maximumDataBytes'] == 16 * 1024**3 and
            record['automaticRetry'] is False and record['automaticSuccessor'] is False,
            'The original finite execution, storage and no-retry limits are mandatory.')
        require(set(record['hosts']) == set(HOSTS), 'Both prescribed hosts are required.')
        total = 0
        for host, cpus, workers in (('DESKTOP', 16, 10), ('MAC', 10, 6)):
            profile = record['hosts'][host]
            require(profile['machineCpus'] == cpus and profile['workers'] == workers and
                profile['heapMiB'] == 6144 and profile['maximumRssBytes'] == 12 * 1024**3,
                'Use the frozen host CPU and native heap/RSS profile.')
            total += integer(profile['maximumDataBytes'], 1, 16 * 1024**3, 'Host data cap')
            integer(profile['minimumFreeBytes'], 8 * 1024**3, 2**63-1, 'Host free-space floor')
        require(total <= record['maximumDataBytes'],
            'Physical retained copies on both hosts must fit the combined data cap.')
        sha(record['originalActorSha256']); sha(record['ecologySha256']); sha(record['dictionarySha256'])
        require(len(record['iterations']) == 2, 'Exactly two immutable request banks are required.')
        streams = []
        for iteration, bank in enumerate(record['iterations'], 1):
            require(bank['iteration'] == iteration and len(bank['origins']) == 640 and
                len(bank['shuffleSeeds']) == 20, 'Each iteration requires 640 origins and20 epoch streams.')
            for row in bank['origins']:
                require(set(row) == {'gameSeed', 'proposalSeed', 'future'} and len(row['future']) == 2,
                    'Each original request requires independent backbone/proposal/two future streams.')
                streams.append(integer(row['gameSeed'], -(2**31), 2**31-1, 'Native world seed'))
                streams += [integer(value, -(2**63), 2**63-1, 'Proposal/future stream')
                    for value in [row['proposalSeed'], *row['future']]]
            streams += [integer(value, 1, 2**31-1, 'Epoch shuffle stream') for value in bank['shuffleSeeds']]
        screen=record['screenBank']
        require(set(screen)=={'nativeOffset','count','seeds'} and screen['count']==1024,
            'The sole final screen requires one exact native1024-world bank.')
        offset=integer(screen['nativeOffset'],0,100000000,'Screen native offset')
        require(screen['seeds']==[-1000000000+offset+i*8191 for i in range(1024)],
            'Screen world identities must equal the authoritative native world formula.')
        streams += screen['seeds']
        require(len(streams) == len(set(streams)),
            'Every backbone, proposal, future, shuffle and screen stream must be disjoint.')
        require(record['selection'] == dict(candidate='ITERATION_2_ONLY',
            validationRewardLower95Positive=True, finalMinusInitialRewardLower95Positive=True,
            finalMinusRawWinAndRewardLower95Positive=True), 'The sole final nomination rule is fixed.')
        return cls(canonical(record))

    @property
    def record(self):
        return json.loads(self.wire)

    @property
    def sha256(self):
        return hashlib.sha256(self.wire).hexdigest()

    def collection_source(self, iteration, chunk, host, arena, current, original):
        integer(iteration, 1, 2, 'Iteration')
        require(host == owner(chunk), 'A chunk cannot migrate or be duplicated on another host.')
        for value in (arena, current, original):
            checked_subject(value)
        require(original['sha256'] == self.record['originalActorSha256'] and
            (iteration != 1 or current['sha256'] == original['sha256']),
            'The original initializer and first-iteration actor must be exact.')
        return dict(kind='token-paired-collect-1', iteration=iteration,
            globalStart=chunk*64, count=64, arena=arena, currentActor=current,
            originalActor=original, origins=self.record['iterations'][iteration-1]['origins'],
            replayIndices=list(COLLECTION_REPLAYS))


@dataclass(frozen=True)
class Clock:
    start: float
    deadline: float

    def __post_init__(self):
        require(all(type(value) in (int, float) and math.isfinite(value)
            for value in (self.start, self.deadline)) and self.deadline-self.start == 21600,
            'Both workers must inherit the same original six-hour science clock.')

    def remaining(self, now):
        require(math.isfinite(now) and now < self.deadline, 'The original science deadline expired.')
        return self.deadline-now

    def audit_deadline(self, terminal):
        require(math.isfinite(terminal) and terminal >= self.start, 'Persisted terminal time is required.')
        return min(terminal+900, self.deadline+900)


def expected_terminal_budget():
    return dict(backbones=1280, maximumTails=23040, backboneReproductions=8,
        screen=3072, screenReproductions=12, historicalControls=2, maximum=27414)
