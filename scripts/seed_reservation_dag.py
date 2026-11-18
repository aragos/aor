"""Compact, immutable RNG metadata reservations; never draws RNGs or runs games.

The newest audit references its parent's protected values instead of copying every historical
integer. Native offsets protect their first 10,000 arithmetic worlds, including rejected probes.
An accepted reservation protects its complete declarations even when a later run uses a prefix.
"""
import hashlib
import json
from collections import defaultdict
from dataclasses import dataclass
import datetime
from pathlib import Path
import shutil
import subprocess


STRIDE = 8191
BASE = -1000000000
EXPANSION = 10000
VALUE_KEYS = ('seeds', 'continuationSeeds', 'proposalSeeds')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode() + b'\n'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def subject(path):
    return dict(path=str(Path(path).resolve()), sha256=sha(path))


def verify_subject(item):
    require(sha(item['path']) == item['sha256'], 'A frozen subject changed: ' + item['path'])
    return read(item['path'])


def write(path, value):
    with Path(path).open('xb') as stream:
        stream.write(canonical(value))


def values_digest(values):
    result = hashlib.sha256()
    for value in sorted(values):
        result.update((str(value) + '\n').encode())
    return result.hexdigest()


def declarations(record):
    return [value for bank in record['seedBanks'].values() for key in VALUE_KEYS
        for value in bank.get(key, [])]


def protection(path, expected_sha, visiting=None):
    """Load historical metadata through one acyclic parent edge, retaining all unused suffixes."""
    path = Path(path).resolve()
    visiting = set() if visiting is None else visiting
    require(str(path) not in visiting, 'Reservation ancestry must be acyclic.')
    visiting.add(str(path))
    record = verify_subject(dict(path=str(path), sha256=expected_sha))
    if 'reservedSeeds' in record:
        values = set(record['reservedSeeds'])
        offsets = set(record['conservativelyExpandedOffsets'])
    else:
        require(record.get('protectionStorage') == 'parent-dag-1',
            'A compact reservation requires its explicit parent DAG contract.')
        parent = record['carriedForwardAudit']
        values, offsets = protection(parent['path'], parent['sha256'], visiting)
        require(len(values) == record['priorActualValues'] and
            values_digest(values) == record['priorActualValuesSha256'],
            'The compact inherited value inventory must independently reproduce.')
        require(sorted(offsets) == record['inheritedConservativeOffsets'],
            'The compact inherited progression inventory must independently reproduce.')
        offsets.update(record['newlyProtectedProbeOffsets'])
    values.update(declarations(record))
    values.update(record.get('heldRejectedNumericalValues', []))
    offsets.update(bank['nativeOffset'] for bank in record['seedBanks'].values()
        if 'nativeOffset' in bank)
    visiting.remove(str(path))
    return values, offsets


def ancestry(parent_path, expected_sha):
    parent = verify_subject(dict(path=str(Path(parent_path).resolve()), sha256=expected_sha))
    pins = [subject(parent_path), parent['carriedForwardAudit'], *parent['verifiedAncestors']]
    require(len({row['path'] for row in pins}) == len(pins),
        'Every ancestry path must occur exactly once.')
    for item in pins:
        verify_subject(item)
    return parent, pins


def worlds(offset, count):
    return [BASE + offset + index * STRIDE for index in range(count)]


def conflicts(values, prior, residues):
    return [value for value in values if value in prior or any(
        0 <= (value - BASE - old) // STRIDE < EXPANSION
        for old in residues[(value - BASE) % STRIDE])]


def reserve(prior, offsets, counts, prefix):
    require(counts and all(type(count) is int and 0 < count <= EXPANSION
        for count in counts.values()), 'Each declared native bank needs a positive bounded size.')
    residues = defaultdict(list)
    for offset in sorted(offsets):
        residues[offset % STRIDE].append(offset)
    probes = []
    rejected = set()
    for first in range(prefix, prefix + STRIDE):
        overlap = {}
        for index, count in enumerate(counts.values()):
            found = conflicts(worlds(first + index, count), prior, residues)
            if found:
                overlap[str(first + index)] = dict(count=len(found), sha256=values_digest(found))
        probes.append(dict(firstOffset=first, conflictingValuesByOffset=overlap,
            collisions=sum(row['count'] for row in overlap.values())))
        if not overlap:
            banks = {name: dict(nativeOffset=first + index, count=count,
                seeds=worlds(first + index, count))
                for index, (name, count) in enumerate(counts.items())}
            values = [value for bank in banks.values() for value in bank['seeds']]
            require(len(values) == len(set(values)) and not prior.intersection(values),
                'Every accepted declaration must be mutually distinct and historically fresh.')
            return banks, probes, sorted(rejected)
        rejected.update(first + index for index in range(len(counts)))
    raise ValueError('No fresh prefix inside the single bounded metadata search window.')


def reserve_numerical(prior, offsets, names, prefix):
    """Declare bounded integer stream identifiers without constructing or drawing any RNG."""
    require(names and len(names) == len(set(names)),
        'Numerical stream names must be nonempty and mutually distinct.')
    residues = defaultdict(list)
    for offset in sorted(offsets):
        residues[offset % STRIDE].append(offset)
    probes, held = [], set()
    for first in range(prefix, prefix + STRIDE):
        values = list(range(first, first + len(names)))
        overlap = conflicts(values, prior, residues)
        probes.append(dict(firstSeed=first, collisions=len(overlap), conflictingValues=overlap))
        if not overlap:
            return dict(zip(names, values)), probes, sorted(held)
        held.update(values)
    raise ValueError('No fresh numerical prefix inside the single bounded metadata search window.')


@dataclass(frozen=True)
class MetadataRecipe:
    """Fixed protocol declarations; this type has no model, outcome or RNG capabilities."""
    family: int
    audit_kind: str
    schedule_kind: str
    counts: dict
    native_prefix: int
    numerical_prefix: int
    numerical_names: tuple
    maximum_executions: int
    expected_ancestors: int
    expected_prior_values: int
    expected_offsets: int
    reproductions: dict
    note: str

    def __post_init__(self):
        require(self.counts and self.numerical_names and
            not set(self.counts).intersection(self.numerical_names) and
            len(self.numerical_names) == len(set(self.numerical_names)),
            'Native bank and numerical stream names must remain distinct and nonempty.')
        require(self.native_prefix >= 0 and self.numerical_prefix >= 0 and
            self.maximum_executions > 0,
            'Metadata prefixes and the execution budget must use valid nonnegative units.')


def prepare_metadata(parent_path, parent_sha, method_path, method_sha, output, recipe, source_file):
    """Write a fresh reservation DAG node using one fixed, already-approved typed recipe."""
    parent_path, method_path = Path(parent_path).resolve(), Path(method_path).resolve()
    require(sha(method_path) == method_sha, 'Use the exact prospectively approved method subject.')
    _, pins = ancestry(parent_path, parent_sha)
    prior, offsets = protection(parent_path, parent_sha)
    require(len(pins) == recipe.expected_ancestors and
        len(prior) == recipe.expected_prior_values and len(offsets) == recipe.expected_offsets,
        'The complete prior metadata ancestry and protection inventory must remain unchanged.')
    banks, native_probes, rejected = reserve(prior, offsets, recipe.counts, recipe.native_prefix)
    native_values = {value for bank in banks.values() for value in bank['seeds']}
    protected_offsets = offsets | set(rejected) | {bank['nativeOffset'] for bank in banks.values()}
    numerical, numeric_probes, held = reserve_numerical(
        prior | native_values, protected_offsets, recipe.numerical_names, recipe.numerical_prefix)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(source_file, output / 'audit_frozen.py')
    shutil.copyfile(__file__, output / 'seed_reservation_dag.py')
    write(output / 'stream-banks.json', banks)
    write(output / 'rng-streams.json', numerical)
    write(output / 'schedule.json', dict(kind=recipe.schedule_kind, banks=banks, **numerical))
    plan = dict(kind='native-numerical-rng-preparation-plan-1', family=recipe.family,
        banks=recipe.counts, maximumTerminalExecutions=recipe.maximum_executions,
        nativePrefix=recipe.native_prefix, numericalPrefix=recipe.numerical_prefix,
        numericalStreams=list(recipe.numerical_names), reproductionIndices=recipe.reproductions,
        worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        authorization='Metadata declarations only; no scientific games, fits or RNG draws.')
    write(output / 'preparation-plan.json', plan)
    seed_banks = dict(banks, **{name: dict(count=1, seeds=[value])
        for name, value in numerical.items()})
    audit = dict(kind=recipe.audit_kind, schemaVersion=1, family=recipe.family,
        auditedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        protectionStorage='parent-dag-1', carriedForwardAudit=subject(parent_path),
        verifiedAncestors=pins[1:], preparationPlan=subject(output / 'preparation-plan.json'),
        prospectiveMethod=subject(method_path),
        sourceCommit=subprocess.check_output(['git', '-C', str(Path(source_file).resolve().parent.parent),
            'rev-parse', 'HEAD'], text=True).strip(),
        auditScriptSha256=sha(output / 'audit_frozen.py'),
        collisionOwner=subject(output / 'seed_reservation_dag.py'),
        worldFormula=plan['worldFormula'], conservativePairsPerOffset=EXPANSION,
        priorActualValues=len(prior), priorActualValuesSha256=values_digest(prior),
        inheritedConservativeOffsets=sorted(offsets), newlyProtectedProbeOffsets=rejected,
        conservativelyExpandedOffsets=sorted(offsets | set(rejected)),
        heldRejectedNumericalValues=held, seedBanks=seed_banks,
        nativeReservationProbes=native_probes, numericalReservationProbes=numeric_probes,
        streamBanks=subject(output / 'stream-banks.json'),
        rngStreams=subject(output / 'rng-streams.json'), schedule=subject(output / 'schedule.json'),
        declaredNewActualValues=len(native_values) + len(numerical),
        declaredNewWorlds=len(native_values), declaredNumericalStreams=len(numerical),
        maximumTerminalExecutions=recipe.maximum_executions, allIntersectionsZero=True,
        scientificStreamUseAuthorized=False, scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0, note=recipe.note)
    write(output / 'seed-audit.json', audit)
    return dict(audit=subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        ancestryPins=len(pins), priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=len(native_values), numericalIntegers=numerical,
        nativeOffsets=[bank['nativeOffset'] for bank in banks.values()],
        rejectedNativePrefixes=len(native_probes)-1, rejectedNumericPrefixes=len(numeric_probes)-1,
        scientificStreamUseAuthorized=False)
