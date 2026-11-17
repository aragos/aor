"""Reserve the fixed89native and numerical declarations using complete frozen88ancestry."""
import argparse
from collections import defaultdict
import datetime
import hashlib
import json
from pathlib import Path
import subprocess


PARENT_SHA = '658aec8c3cd948f49c010c43bce42da6c0abcdf78b4747154fbdea7876286761'
COUNTS = {**{'generation-' + str(index): 128 for index in range(1, 9)},
    'development': 1024, 'mixed': 2048, 'homogeneous': 2048}
NATIVE_PREFIX = 89000000
NUMERIC_PREFIX = 89001000


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def subject(path):
    return dict(path=str(Path(path).resolve()), sha256=sha(path))


def worlds(offset, count):
    return [-1000000000 + offset + index * 8191 for index in range(count)]


def collisions(values, prior, residues):
    return [value for value in values if value in prior or any(
        0 <= (value + 1000000000 - old) // 8191 < 10000
        for old in residues[(value + 1000000000) % 8191])]


def reserve(prior, offsets):
    residues = defaultdict(list)
    for offset in sorted(offsets):
        residues[offset % 8191].append(offset)
    native_probes = []
    rejected_offsets = set()
    for first in range(NATIVE_PREFIX, NATIVE_PREFIX + 8191):
        overlap = {str(first + index): conflicts for index, count in enumerate(COUNTS.values())
            if (conflicts := collisions(worlds(first + index, count), prior, residues))}
        native_probes.append(dict(firstOffset=first,
            collisions=sum(len(rows) for rows in overlap.values()), conflictingValuesByOffset=overlap))
        if not overlap:
            banks = {name: dict(nativeOffset=first + index, count=count,
                seeds=worlds(first + index, count)) for index, (name, count) in enumerate(COUNTS.items())}
            break
        rejected_offsets.update(first + index for index in range(len(COUNTS)))
    else:
        raise ValueError('No fresh native prefix in the single bounded search window.')
    native = [value for bank in banks.values() for value in bank['seeds']]
    if len(native) != len(set(native)) or len(native) != 6144 or prior.intersection(native):
        raise ValueError('The11 original native banks must be distinct and fully fresh.')
    numeric_probes = []
    held_numerics = set()
    for first in range(NUMERIC_PREFIX, NUMERIC_PREFIX + 8191):
        values = list(range(first, first + 9))
        conflicts = collisions(values, prior | set(native), residues)
        numeric_probes.append(dict(firstSeed=first, collisions=len(conflicts), conflictingValues=conflicts))
        if not conflicts:
            numerical = dict(projectionSeed=values[0], directionSeeds=values[1:])
            break
        held_numerics.update(values)
    else:
        raise ValueError('No fresh numerical prefix in the single bounded search window.')
    return banks, numerical, native_probes, numeric_probes, sorted(rejected_offsets), sorted(held_numerics)


def write(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, sort_keys=True, separators=(',', ':'), allow_nan=False)
        stream.write('\n')


def prepare(parent_path, output):
    parent_path = Path(parent_path).resolve()
    if sha(parent_path) != PARENT_SHA:
        raise ValueError('Use the exact complete88audit including both unused qualification banks.')
    parent = read(parent_path)
    ancestors = [subject(parent_path), parent['carriedForwardAudit'], *parent['verifiedAncestors']]
    if len(ancestors) != 69 or len({(row['path'], row['sha256']) for row in ancestors}) != 69:
        raise ValueError('All69 frozen ancestry subjects must remain present and distinct.')
    for row in ancestors:
        if sha(row['path']) != row['sha256']:
            raise ValueError('Frozen ancestry changed: ' + row['path'])
    prior = set(parent['reservedSeeds'])
    added = [value for bank in parent['seedBanks'].values()
        for key in ('seeds', 'continuationSeeds', 'proposalSeeds') for value in bank.get(key, [])]
    if len(added) != len(set(added)) or len(added) != 4608 or prior.intersection(added):
        raise ValueError('All4608 declared88worlds must be retained unchanged and distinct.')
    prior.update(added)
    prior.update(parent.get('heldRejectedNumericalValues', []))
    offsets = set(parent['conservativelyExpandedOffsets']) | {
        bank['nativeOffset'] for bank in parent['seedBanks'].values() if 'nativeOffset' in bank}
    if len(prior) != 1079318 or len(offsets) != 3098:
        raise ValueError('The complete88ancestry protection counts changed.')
    banks, numerical, native_probes, numeric_probes, rejected_offsets, held_numerics = reserve(prior, offsets)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / 'audit_frozen.py').write_bytes(Path(__file__).read_bytes())
    write(output / 'native-banks.json', banks)
    write(output / 'rng-streams.json', numerical)
    write(output / 'schedule.json', dict(kind='robust-league-schedule-1', banks=banks, **numerical))
    recipe = dict(kind='robust-league-rng-preparation-plan-1', family=89, banks=COUNTS,
        projectionStreams=1, directionStreams=8, nativeWorlds=6144,
        numericalIntegers=9, maximumTerminalExecutions=45120,
        training='8generations x32signedmembers x128commonworlds; candidate-only',
        development='2pairedcases x1024commonworlds', qualification='2banks x2048pairs',
        reproductions='32training +16development +16qualification; existing world values only',
        nativePrefix=NATIVE_PREFIX, numericalPrefix=NUMERIC_PREFIX,
        worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        authorization='Root metadata declaration authorization only; no actual draws, models or games.')
    write(output / 'preparation-plan.json', recipe)
    seed_banks = dict(banks, projection=dict(count=1, seeds=[numerical['projectionSeed']]),
        directions=dict(count=8, seeds=numerical['directionSeeds']))
    audit = dict(kind='robust-league-frozen-ancestry-1', schemaVersion=1, family=89,
        auditedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        carriedForwardAudit=subject(parent_path), verifiedAncestors=ancestors[1:],
        preparationPlan=subject(output / 'preparation-plan.json'),
        sourceCommit=subprocess.check_output(['git', '-C', str(Path(__file__).resolve().parent.parent),
            'rev-parse', 'HEAD'], text=True).strip(), auditScriptSha256=sha(output / 'audit_frozen.py'),
        referenceInferenceAssetSha256='c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a',
        qualifiedNumericalOriginBundleSha256='e62d749f4d9125cc86107cc2c37d77cb2f23af65c3648105a5fd2636f1ee27f1',
        selectorOriginalBundleSha256='4a18d298cdfcc80f0e15f0e24cf10a2f8e9a5d36f9901da058b50560a43d72b3',
        worldFormula=recipe['worldFormula'], conservativePairsPerOffset=10000,
        reservedSeeds=sorted(prior), inheritedConservativeOffsets=sorted(offsets),
        newlyProtectedProbeOffsets=rejected_offsets,
        conservativelyExpandedOffsets=sorted(offsets | set(rejected_offsets)),
        heldRejectedNumericalValues=held_numerics, seedBanks=seed_banks,
        nativeReservationProbes=native_probes, numericalReservationProbes=numeric_probes,
        nativeBanks=subject(output / 'native-banks.json'), rngStreams=subject(output / 'rng-streams.json'),
        schedule=subject(output / 'schedule.json'), declaredNewActualValues=6153,
        declaredNewWorlds=6144, declaredNumericalStreams=9, declaredProjectionStreams=1,
        declaredDirectionStreams=8, maximumTerminalExecutions=45120,
        allIntersectionsZero=True, scientificStreamUseAuthorized=False,
        scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0,
        sources='Only exact frozen88metadata ancestry; all unused older worlds remain protected.',
        note='Numerical integer declarations are metadata only; PCG64 draws and materialization remain forbidden.')
    write(output / 'seed-audit.json', audit)
    return dict(audit=subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=6144, numericalIntegers=9,
        nativeOffsets=[bank['nativeOffset'] for bank in banks.values()],
        numericStreams=numerical, rejectedNativePrefixes=len(native_probes) - 1,
        rejectedNumericPrefixes=len(numeric_probes) - 1, scientificStreamUseAuthorized=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.parent, arguments.output), sort_keys=True))
