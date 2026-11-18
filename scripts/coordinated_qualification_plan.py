"""Fixed two-bank Support1 qualification identities; no model or outcome execution."""
from dataclasses import dataclass
import json
import math
from pathlib import Path

from token_paired_campaign_plan import (
    canonical, checked_subject, digest, file_sha, integer, read, require, sha, subject,
)

PREFIX = 'coordinated-qualification'
PROTOCOL_KIND = 'coordinated-support-qualification-protocol-1'
PROFILE = 'aor-coordinated-83-support-1'
SUPPORT = 'known-own-chaos-support-1'
CANDIDATE = '2d04a0738d19b06b12eb1ce64ad2f37f660f091bf6079db1679f781260252095'
CONFIGURATION = 'players-4/standard/no-crusades/deck-true/art-true'
APPLICATION_CONFIGURATION = dict(playerCount=4, map='STANDARD', initialCard='NO_CRUSADES',
    deckModification=True, immediateMasterArtDiscard=True, drawThreeKeepTwo=False)
RULES = 'ah1996-three-epoch-5'
DATA = 'ah1996-catalog-16'
BANKS = ('mixed', 'homogeneous')
HOSTS = ('DESKTOP', 'MAC')
CHUNKS = {'DESKTOP': tuple(range(20)), 'MAC': tuple(range(20, 32))}
REPLAYS = (0, 1365, 2730, 4095)
ROLES = ('CANDIDATE', 'CONTROL')
SCIENCE_SECONDS = 28800
AUDIT_SECONDS = 900
MAXIMUM_TERMINALS = 16400
MULTIPLIER = 2.5758293035489004
INTERVAL = dict(confidence=.99, multiplier=MULTIPLIER,
    requiredMetrics=['wins', 'rewards'], lowerBoundStrictlyPositive=True)
INPUT_BINDINGS = ('originArena', 'originAdmission', 'originProof')
REFERENCE_FIELDS = ('qualifiedOrdinarySha256', 'cardM8Sha256', 'purchaseF0Sha256',
    'expansionESha256', 'tokenControlVersion')


def owner(chunk):
    integer(chunk, 0, 31, 'Qualification chunk')
    return 'DESKTOP' if chunk < 20 else 'MAC'


def indices(chunk):
    owner(chunk)
    return range(chunk*128, (chunk+1)*128)


def replays(chunk):
    return tuple(index for index in REPLAYS if index in indices(chunk))


def bank_name(value):
    require(value in BANKS, 'Only the fixed mixed and homogeneous banks exist.')
    return value


def host_limits(host):
    require(host in HOSTS, 'Use one declared qualification host.')
    return dict(machineCpus=16 if host == 'DESKTOP' else 10,
        workers=10 if host == 'DESKTOP' else 6, heapMiB=6144,
        maximumRssBytes=12*1024**3, maximumDataBytes=(64 if host == 'DESKTOP' else 40)*1024**3,
        minimumFreeBytes=16*1024**3)


@dataclass(frozen=True)
class Clock:
    started: float
    deadline: float

    def __post_init__(self):
        require(all(type(value) in (int, float) and math.isfinite(value)
                    for value in (self.started, self.deadline)) and self.started > 0 and
            self.deadline-self.started == SCIENCE_SECONDS,
            'Qualification retains one original shared eight-hour clock.')

    def remaining(self, now):
        require(math.isfinite(now) and now < self.deadline, 'The original qualification deadline expired.')
        return self.deadline-now


@dataclass(frozen=True)
class Plan:
    wire: bytes

    @classmethod
    def from_file(cls, path):
        path = Path(path)
        value = read(path)
        require(path.read_bytes() == canonical(value)+b'\n',
            'The shared qualification protocol must retain canonical JSON plus one LF.')
        return cls(path.read_bytes())

    @classmethod
    def from_record(cls, value):
        return cls(canonical(value)+b'\n')

    @property
    def record(self):
        return json.loads(self.wire)

    @property
    def sha256(self):
        return __import__('hashlib').sha256(self.wire).hexdigest()

    def __post_init__(self):
        value = self.record
        require(type(self.wire) is bytes and self.wire == canonical(value)+b'\n',
            'The typed qualification protocol is immutable canonical bytes.')
        require(set(value) == {'kind', 'profile', 'supportProfile', 'candidateBundleSha256',
            'originalBundleSha256', 'calibrationSha256', 'originAdmissionSha256', 'originArenaSha256', 'originProofSha256',
            'rulesVersion', 'dataVersion', 'configuration', 'references', 'banks', 'maximumSeconds',
            'auditSeconds', 'maximumTerminalExecutions', 'interval'},
            'Qualification protocol fields must be exact.')
        require(value['kind'] == PROTOCOL_KIND and value['profile'] == PROFILE and
            value['supportProfile'] == SUPPORT and value['candidateBundleSha256'] == CANDIDATE and
            value['rulesVersion'] == RULES and value['dataVersion'] == DATA and
            value['configuration'] == CONFIGURATION and value['maximumSeconds'] == SCIENCE_SECONDS and
            value['auditSeconds'] == AUDIT_SECONDS and value['maximumTerminalExecutions'] == MAXIMUM_TERMINALS and
            value['interval'] == INTERVAL, 'Only the sole fixed candidate and two complete paired99 banks are eligible.')
        for name in ('originalBundleSha256', 'calibrationSha256', 'originAdmissionSha256'):
            sha(value[name])
        for field in ('originArenaSha256', 'originProofSha256'):
            require(set(value[field]) == set(HOSTS),
                'Each host retains its exact original83 arena and frozen-runtime verification proof.')
            for digest in value[field].values(): sha(digest)
        require(set(value['references']) == set(REFERENCE_FIELDS) and
            value['references']['tokenControlVersion'] == 'FIXED_16_TO_15',
            'All exact M8/F0/E/packed-reference identities and the fixed token control are required.')
        for name in REFERENCE_FIELDS[:-1]:
            sha(value['references'][name])
        validate_banks(value['banks'])

    def source(self, bank, chunk, subjects):
        bank_name(bank)
        owner(chunk)
        require(set(subjects) == {'protocol', 'originArena', 'originAdmission', 'originProof', 'arena', 'confirmation'},
            'Each native request binds its complete origin and separate evaluation subjects.')
        for name, value in subjects.items():
            if value is None:
                require(name == 'confirmation' and bank == 'mixed', 'Only the first-bank gate is absent.')
            else:
                require(set(value) == {'path', 'sha256'} and isinstance(value['path'], str) and value['path'],
                    'Native source subjects need explicit paths and raw hashes.')
                sha(value['sha256'])
        require((subjects['confirmation'] is None) == (bank == 'mixed'),
            'Only completed mixed confirmation may open homogeneous qualification.')
        require(subjects['protocol']['sha256'] == self.sha256 and
            subjects['originArena']['sha256'] == self.record['originArenaSha256'][owner(chunk)] and
            subjects['originProof']['sha256'] == self.record['originProofSha256'][owner(chunk)] and
            subjects['originAdmission']['sha256'] == self.record['originAdmissionSha256'],
            'The raw protocol and original83 admission/arena cannot be rebound to evaluation ecology.')
        return dict(kind=PREFIX+'-input-1', host=owner(chunk), **subjects, bankName=bank,
            globalStart=chunk*128, count=128)


def validate_banks(banks):
    require(set(banks) == set(BANKS), 'Both independent banks must be prospectively frozen.')
    all_seeds = []
    for bank in BANKS:
        record = banks[bank]
        require(set(record) == {'nativeOffset', 'count', 'seeds'} and record['count'] == 4096,
            'Each bank retains all4096 native global worlds.')
        offset = integer(record['nativeOffset'], 0, 100000000, 'Native bank offset')
        expected = [-1000000000+offset+index*8191 for index in range(4096)]
        require(record['seeds'] == expected, 'Native world identities retain the exact global-index formula.')
        all_seeds.extend(expected)
    require(len(set(all_seeds)) == 8192, 'The confirmation and heldout bank values must be disjoint.')
