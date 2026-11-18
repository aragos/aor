"""Compact, immutable RNG metadata reservations; never draws RNGs or runs games.

The newest audit references its parent's protected values instead of copying every historical
integer. Native offsets protect their first 10,000 arithmetic worlds, including rejected probes.
An accepted reservation protects its complete declarations even when a later run uses a prefix.
"""
import hashlib
import json
from collections import defaultdict
from pathlib import Path


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
