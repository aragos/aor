"""Fixed sixteen-mask diagnostic types; no selection, model execution or random generation."""
from dataclasses import dataclass
import json
import math
from pathlib import Path

from token_paired_campaign_plan import canonical, checked_subject, digest, file_sha, integer, read, require, sha, subject
from coordinated_qualification_plan import CANDIDATE, CONFIGURATION, DATA, HOSTS, REFERENCE_FIELDS, RULES, host_limits

PREFIX = 'coordinated-diagnostic'
PROTOCOL_KIND = PREFIX+'-protocol-1'
KIND = PREFIX+'-screen-1'
HEADS = dict(CARD=1, PURCHASE=2, EXPANSION=4, TOKEN=8)
SUPPORTS = ('RAW', 'SUPPORT1')
ECOLOGIES = ('HOMOGENEOUS', 'MIXED')
ROLES = ('CANDIDATE', 'INCUMBENT')
CHUNKS = dict(DESKTOP=(0,), MAC=(1,))
RANGES = ((0, 320), (320, 192))
REPLAYS = (0, 170, 341, 511)
SCIENCE_SECONDS = 14400
AUDIT_SECONDS = 900
MAXIMUM_TERMINALS = 32832
INTERVAL = dict(confidence=.95, multiplier=1.96, unit='WORLD', gates=False)
METRICS = ('wins', 'rewards', 'ranks', 'chaos')


def owner(chunk):
    integer(chunk, 0, 1, 'Diagnostic host range')
    return 'DESKTOP' if chunk == 0 else 'MAC'


def indices(chunk):
    owner(chunk)
    start, count = RANGES[chunk]
    return range(start, start+count)


@dataclass(frozen=True)
class Case:
    ecology: str
    support: str
    pair: int

    def __post_init__(self):
        require(self.ecology in ECOLOGIES and self.support in SUPPORTS,
            'A diagnostic has exactly the two fixed rival and support contexts.')
        integer(self.pair, 0, 7, 'Complement pair')

    @property
    def identity(self): return f'{self.ecology}-{self.support}-pair-{self.pair}'

    @property
    def masks(self): return dict(CANDIDATE=self.pair, INCUMBENT=15-self.pair)

    def replays(self, chunk):
        return tuple((REPLAYS[mask%4], role) for role, mask in self.masks.items()
            if REPLAYS[mask%4] in indices(chunk))


CASES = tuple(Case(ecology, support, pair) for ecology in ECOLOGIES for support in SUPPORTS for pair in range(8))


def case(value):
    matches = [item for item in CASES if item.identity == value]
    require(len(matches) == 1, 'Only the32 prospective diagnostic cases exist.')
    return matches[0]


def counts(host=None):
    require(host is None or host in HOSTS, 'Only declared local or global diagnostic counts exist.')
    chunks = range(2) if host is None else CHUNKS[host]
    worlds = sum(len(indices(chunk)) for chunk in chunks)
    pairs = len(CASES)*worlds
    reproductions = sum(len(item.replays(chunk)) for item in CASES for chunk in chunks)
    return dict(independentWorlds=worlds, pairedWorldRecords=pairs, games=2*pairs,
        reproductions=reproductions, terminalExecutions=2*pairs+reproductions)


@dataclass(frozen=True)
class Clock:
    started: float
    deadline: float

    def __post_init__(self):
        require(all(type(value) in (int, float) and math.isfinite(value) for value in (self.started, self.deadline)) and
            self.started > 0 and self.deadline-self.started == SCIENCE_SECONDS,
            'Diagnostic execution retains one original four-hour clock.')

    def remaining(self, now):
        require(math.isfinite(now) and now < self.deadline, 'The original diagnostic deadline expired.')
        return self.deadline-now


@dataclass(frozen=True)
class Plan:
    wire: bytes

    @classmethod
    def from_file(cls, path):
        raw = Path(path).read_bytes(); value = read(path)
        require(raw == canonical(value)+b'\n', 'Diagnostic protocol requires canonical JSON and one LF.')
        return cls(raw)

    @classmethod
    def from_record(cls, value): return cls(canonical(value)+b'\n')

    @property
    def record(self): return json.loads(self.wire)

    @property
    def sha256(self): return __import__('hashlib').sha256(self.wire).hexdigest()

    def __post_init__(self):
        value = self.record
        require(type(self.wire) is bytes and self.wire == canonical(value)+b'\n', 'Protocol bytes are immutable and canonical.')
        require(set(value) == {'kind', 'candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256',
            'originAdmissionSha256', 'originArenaSha256', 'originProofSha256', 'references', 'rulesVersion',
            'dataVersion', 'configuration', 'bank', 'heads', 'masks', 'supportProfiles', 'ecologies',
            'replayWorlds', 'diagnosticOnly', 'promotionAllowed', 'maximumSeconds', 'auditSeconds',
            'maximumTerminalExecutions', 'interval'}, 'Only the fixed diagnostic protocol fields are admitted.')
        require(value['kind'] == PROTOCOL_KIND and value['candidateBundleSha256'] == CANDIDATE and
            value['rulesVersion'] == RULES and value['dataVersion'] == DATA and value['configuration'] == CONFIGURATION,
            'The sole frozen83 mean16 and exact rule/configuration identities cannot change.')
        for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256', 'originAdmissionSha256'):
            sha(value[key])
        for key in ('originArenaSha256', 'originProofSha256'):
            require(set(value[key]) == set(HOSTS), 'Both retained host origin subjects require exact raw identities.')
            for item in value[key].values(): sha(item)
        references = value['references']
        require(set(references) == set(REFERENCE_FIELDS) and references['tokenControlVersion'] == 'FIXED_16_TO_15',
            'Original M8/F0/E and fixed token references must remain explicit.')
        for key in REFERENCE_FIELDS[:-1]: sha(references[key])
        require(value['heads'] == HEADS and all(type(item) is int for item in value['heads'].values()) and
            value['masks'] == list(range(16)) and all(type(item) is int for item in value['masks']) and
            value['supportProfiles'] == list(SUPPORTS) and value['ecologies'] == list(ECOLOGIES) and
            value['replayWorlds'] == list(REPLAYS) and all(type(item) is int for item in value['replayWorlds']) and
            value['diagnosticOnly'] is True and value['promotionAllowed'] is False and
            value['interval'] == INTERVAL and value['interval']['gates'] is False,
            'Masks, contexts, role-specific reproductions and diagnostic-only intervals are fixed before data.')
        for key, expected in (('maximumSeconds', SCIENCE_SECONDS), ('auditSeconds', AUDIT_SECONDS),
                ('maximumTerminalExecutions', MAXIMUM_TERMINALS)):
            require(type(value[key]) is int and value[key] == expected, 'Original execution and audit budgets cannot change.')
        bank = value['bank']
        require(set(bank) == {'nativeOffset', 'count', 'seeds'} and type(bank['count']) is int and bank['count'] == 512,
            'Every mask/context shares exactly512 prospective native world IDs.')
        integer(bank['nativeOffset'], 0, 100000000, 'Native offset')
        require(len(bank['seeds']) == 512 and len(set(bank['seeds'])) == 512 and
            all(type(seed) is int and -(2**31) <= seed < 2**31 and
                seed == -1000000000+bank['nativeOffset']+index*8191 for index, seed in enumerate(bank['seeds'])),
            'The whole common bank must preserve exact global-index native seed expansion.')

    def source(self, selected, chunk, subjects):
        require(selected in CASES and set(subjects) == {'protocol', 'originArena', 'originAdmission', 'originProof', 'arena'},
            'A case source requires its five exact immutable file subjects.')
        host = owner(chunk)
        for item in subjects.values():
            require(set(item) == {'path', 'sha256'} and type(item['path']) is str and
                Path(item['path']).is_absolute(), 'Source subjects retain exact absolute readable paths and raw SHA.')
            sha(item['sha256'])
        value = self.record
        require(subjects['protocol']['sha256'] == self.sha256 and
            subjects['originArena']['sha256'] == value['originArenaSha256'][host] and
            subjects['originProof']['sha256'] == value['originProofSha256'][host] and
            subjects['originAdmission']['sha256'] == value['originAdmissionSha256'],
            'A source cannot rebind its original host83 arena, origin proof or complete admission.')
        start, count = RANGES[chunk]
        return dict(kind=PREFIX+'-input-1', host=host, ecology=selected.ecology, support=selected.support,
            pair=selected.pair, globalStart=start, count=count, **subjects)
