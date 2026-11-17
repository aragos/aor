"""Reserve study88 metadata against the frozen complete study87 ancestry, without games."""
import argparse
from collections import defaultdict
import datetime
import hashlib
import json
from pathlib import Path
import subprocess


PARENT_SHA = 'fcea21ba216f70e357fdbee371bca070be16f8e73bc8f3b6e92b973aec04d873'
COUNTS = {'development': 512, 'mixed': 2048, 'homogeneous': 2048}
WORLD_STEP = 8191
WORLD_BASE = -1000000000
CONSERVATIVE_COUNT = 10000
PREFIX = 88000000


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def subject(path):
    return {'path': str(Path(path).resolve()), 'sha256': sha(path)}


def worlds(offset, count):
    return [WORLD_BASE + offset + index * WORLD_STEP for index in range(count)]


def conflicts(offset, count, actual, residues):
    """Return exact candidate collisions with actual values or conservative old progressions."""
    return [value for value in worlds(offset, count)
            if value in actual or any(
                0 <= (value - WORLD_BASE - old) // WORLD_STEP < CONSERVATIVE_COUNT
                for old in residues[(value - WORLD_BASE) % WORLD_STEP])]


def reserve(actual, offsets):
    residues = defaultdict(list)
    for offset in sorted(offsets):
        residues[offset % WORLD_STEP].append(offset)
    probes = []
    rejected_offsets = set()
    for first in range(PREFIX, PREFIX + WORLD_STEP):
        overlaps = {}
        for index, count in enumerate(COUNTS.values()):
            offset = first + index
            collision = conflicts(offset, count, actual, residues)
            if collision:
                overlaps[str(offset)] = collision
        probes.append({'firstOffset': first,
                       'collisions': sum(len(values) for values in overlaps.values()),
                       'conflictingValuesByOffset': overlaps})
        if not overlaps:
            banks = {name: {'nativeOffset': first + index, 'count': count,
                            'seeds': worlds(first + index, count)}
                     for index, (name, count) in enumerate(COUNTS.items())}
            values = [value for bank in banks.values() for value in bank['seeds']]
            if len(values) != len(set(values)) or actual.intersection(values):
                raise ValueError('The three scientific banks must be mutually distinct and fresh.')
            return banks, probes, sorted(rejected_offsets)
        rejected_offsets.update(first + index for index in range(len(COUNTS)))
    raise ValueError('No fresh prefix exists inside the single bounded search window.')


def write(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, sort_keys=True, separators=(',', ':'), allow_nan=False)
        stream.write('\n')


def prepare(parent_path, output):
    parent_path = Path(parent_path).resolve()
    output = Path(output).resolve()
    if sha(parent_path) != PARENT_SHA:
        raise ValueError('Use the exact complete frozen study87 audit.')
    parent = read(parent_path)
    ancestors = [subject(parent_path), parent['carriedForwardAudit'], *parent['verifiedAncestors']]
    if len(ancestors) != 68 or len({(item['path'], item['sha256']) for item in ancestors}) != 68:
        raise ValueError('All68 frozen ancestry subjects must remain distinct and present.')
    for item in ancestors:
        if sha(item['path']) != item['sha256']:
            raise ValueError('A frozen ancestry subject changed: ' + item['path'])
    actual = set(parent['reservedSeeds'])
    added = [value for bank in parent['seedBanks'].values()
             for key in ('seeds', 'continuationSeeds', 'proposalSeeds') for value in bank.get(key, [])]
    if len(added) != len(set(added)) or len(added) != 8192 or actual.intersection(added):
        raise ValueError('Both entire87 banks must be fresh declarations relative to inherited values.')
    actual.update(added)
    offsets = set(parent['conservativelyExpandedOffsets']) | {
        bank['nativeOffset'] for bank in parent['seedBanks'].values() if 'nativeOffset' in bank}
    if len(actual) != 1074710 or len(offsets) != 3095:
        raise ValueError('The complete87 protected actual and conservative counts changed.')
    banks, probes, rejected_offsets = reserve(actual, offsets)
    source_commit = subprocess.check_output(
        ['git', '-C', str(Path(__file__).resolve().parent.parent), 'rev-parse', 'HEAD'], text=True).strip()
    output.mkdir(parents=True, exist_ok=False)
    (output / 'audit_frozen.py').write_bytes(Path(__file__).read_bytes())
    write(output / 'stream-banks.json', banks)
    write(output / 'rng-streams.json', {})
    write(output / 'schedule.json', {'kind': 'robust-token-schedule-1', 'banks': banks})
    recipe = {'kind': 'robust-token-rng-preparation-plan-1', 'family': 88,
              'banks': COUNTS, 'firstPrefix': PREFIX, 'maximumTerminalExecutions': 16464,
              'reproductionStreams': 'Reuse existing original world values; no new declarations.',
              'developmentWorldReuse': 'The same512 independent worlds across all8 fixed configurations.',
              'numericalStreams': 0, 'worldFormula': '-1000000000 + nativeOffset + globalIndex*8191',
              'rejectedProbeOffsets': 'Conservatively protect the full10000-value progression for each rejected offset.',
              'authorization': 'Root fixed4608 declaration metadata authorization; scientific use remains forbidden.'}
    write(output / 'preparation-plan.json', recipe)
    audit = {'kind': 'robust-token-frozen-ancestry-1', 'schemaVersion': 1, 'family': 88,
             'auditedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
             'carriedForwardAudit': subject(parent_path), 'verifiedAncestors': ancestors[1:],
             'preparationPlan': subject(output / 'preparation-plan.json'),
             'sourceCommit': source_commit, 'auditScriptSha256': sha(output / 'audit_frozen.py'),
             'referenceInferenceAssetSha256': 'c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a',
             'qualifiedCandidateBundleSha256': 'e62d749f4d9125cc86107cc2c37d77cb2f23af65c3648105a5fd2636f1ee27f1',
             'parentNominationSha256': 'f9da5a9e987870a847e4a56091174b56c137c40cead67936dfb1e0cbad95bb4d',
             'worldFormula': recipe['worldFormula'], 'conservativePairsPerOffset': CONSERVATIVE_COUNT,
             'reservedSeeds': sorted(actual), 'inheritedConservativeOffsets': sorted(offsets),
             'newlyProtectedProbeOffsets': rejected_offsets,
             'conservativelyExpandedOffsets': sorted(offsets | set(rejected_offsets)),
             'seedBanks': banks, 'nativeReservationProbes': probes, 'numericalReservationProbes': [],
             'streamBanks': subject(output / 'stream-banks.json'),
             'rngStreams': subject(output / 'rng-streams.json'),
             'schedule': subject(output / 'schedule.json'),
             'declaredNewActualValues': 4608, 'declaredNewWorlds': 4608,
             'declaredDevelopmentWorlds': 512, 'declaredMixedWorlds': 2048,
             'declaredHomogeneousWorlds': 2048, 'declaredNumericalStreams': 0,
             'maximumTerminalExecutions': 16464,
             'allIntersectionsZero': True, 'scientificStreamUseAuthorized': False,
             'scientificStreamUseRequiresRootSeedReview': True,
             'modelsRead': 0, 'gamesRun': 0, 'fitsRun': 0, 'humanInputsRead': 0,
             'sources': 'Only exact frozen87 ancestry and explicit metadata preparation plan; no humans/models/outcomes.',
             'note': 'All prior unused and held declarations remain protected. Rejected probe offsets are additionally held conservatively.'}
    write(output / 'seed-audit.json', audit)
    return {'audit': subject(output / 'seed-audit.json'), 'schedule': audit['schedule'],
            'priorActualValues': len(actual), 'inheritedConservativeOffsets': len(offsets),
            'protectedRejectedProbeOffsets': len(rejected_offsets), 'freshWorlds': 4608,
            'nativeOffsets': [bank['nativeOffset'] for bank in banks.values()],
            'rejectedPrefixes': len(probes) - 1, 'games': 0, 'scientificUseAuthorized': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.parent, args.output), sort_keys=True))
