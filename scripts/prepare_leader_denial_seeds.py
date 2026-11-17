"""Reserve90leader-denial metadata after complete88bwhile preserving future89streams."""
import argparse
from collections import defaultdict
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

import prepare_robust_league_seeds as arithmetic


PARENT_SHA = 'a0d4a23a8ba8a21bb6aeb54b9c2c4663785c54a36dba81fdfa23d878c3192e41'
METHOD_SHA = 'c41de86dbfd514ab6666a54020cda257a9a6cbb032dd87b4b9116bf8e08a18af'
COUNTS = {'development': 512, 'mixed': 2048, 'homogeneous': 2048}
PREFIX = 90000000


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def subject(path):
    return dict(path=str(Path(path).resolve()), sha256=sha(path))


def reserve(prior, offsets):
    residues = defaultdict(list)
    for offset in sorted(offsets):
        residues[offset % 8191].append(offset)
    probes = []
    rejected = set()
    for first in range(PREFIX, PREFIX + 8191):
        overlaps = {str(first + index): values for index, count in enumerate(COUNTS.values())
            if (values := arithmetic.collisions(arithmetic.worlds(first + index, count), prior, residues))}
        probes.append(dict(firstOffset=first, collisions=sum(len(values) for values in overlaps.values()),
            conflictingValuesByOffset=overlaps))
        if not overlaps:
            banks = {name: dict(nativeOffset=first + index, count=count,
                seeds=arithmetic.worlds(first + index, count))
                for index, (name, count) in enumerate(COUNTS.items())}
            values = [value for bank in banks.values() for value in bank['seeds']]
            if len(values) != len(set(values)) or prior.intersection(values):
                raise ValueError('All90declared scientific world values must be mutually distinct and fresh.')
            return banks, probes, sorted(rejected)
        rejected.update(first + index for index in range(3))
    raise ValueError('No fresh90prefix exists inside the single bounded search window.')


def write(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, sort_keys=True, separators=(',', ':'), allow_nan=False)
        stream.write('\n')


def prepare(parent_path, method_path, output):
    parent_path = Path(parent_path).resolve()
    if sha(parent_path) != PARENT_SHA:
        raise ValueError('Carry the exact complete88baudit; all inherited89future worlds and numerical integers stay held.')
    method_path = Path(method_path).resolve()
    if sha(method_path) != METHOD_SHA:
        raise ValueError('Use the exact root-pinned90prospective method.')
    method = read(method_path)
    if {name: value['worlds'] for name, value in method['banks'].items()} != COUNTS or method['maximumTerminalExecutions'] != 16464:
        raise ValueError('Root90method world counts or terminal budget changed.')
    parent = read(parent_path)
    ancestors = [subject(parent_path), parent['carriedForwardAudit'], *parent['verifiedAncestors']]
    if len(ancestors) != 71 or len({(row['path'], row['sha256']) for row in ancestors}) != 71:
        raise ValueError('All71 complete frozen ancestry subjects must remain present and distinct.')
    for row in ancestors:
        if sha(row['path']) != row['sha256']:
            raise ValueError('A frozen ancestry subject changed: ' + row['path'])
    prior = set(parent['reservedSeeds'])
    added = [value for bank in parent['seedBanks'].values()
        for key in ('seeds', 'continuationSeeds', 'proposalSeeds') for value in bank.get(key, [])]
    if len(added) != len(set(added)) or len(added) != 4608 or prior.intersection(added):
        raise ValueError('All4608native88bdeclarations must remain unchanged and distinct.')
    prior.update(added)
    prior.update(parent.get('heldRejectedNumericalValues', []))
    offsets = set(parent['conservativelyExpandedOffsets']) | {
        bank['nativeOffset'] for bank in parent['seedBanks'].values() if 'nativeOffset' in bank}
    if len(prior) != 1090079 or len(offsets) != 3141:
        raise ValueError('The full88bactual and inherited expanded-probe protection counts changed.')
    banks, probes, rejected = reserve(prior, offsets)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / 'audit_frozen.py').write_bytes(Path(__file__).read_bytes())
    shutil.copyfile(arithmetic.__file__, output / 'prepare_robust_league_seeds.py')
    write(output / 'stream-banks.json', banks)
    write(output / 'rng-streams.json', {})
    write(output / 'schedule.json', dict(kind='robust-token-schedule-1', banks=banks))
    recipe = dict(kind='leader-denial-rng-preparation-plan-1', family=90, banks=COUNTS,
        maximumTerminalExecutions=16464, firstPrefix=PREFIX,
        reproductionStreams='Original reserved indices only; no additional random stream.',
        numericalStreams=0, worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        reason='Prospectively fixed90leader-denial intervention; all88b/89and older declarations remain protected.',
        authorization='Root metadata declaration authorization only; no games or numerical draws.')
    write(output / 'preparation-plan.json', recipe)
    audit = dict(kind='leader-denial-frozen-ancestry-1', schemaVersion=1, family=90,
        auditedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        carriedForwardAudit=subject(parent_path), verifiedAncestors=ancestors[1:],
        preparationPlan=subject(output / 'preparation-plan.json'), prospectiveMethod=subject(method_path),
        sourceCommit=subprocess.check_output(['git', '-C', str(Path(__file__).resolve().parent.parent),
            'rev-parse', 'HEAD'], text=True).strip(), auditScriptSha256=sha(output / 'audit_frozen.py'),
        collisionOwner=subject(output / 'prepare_robust_league_seeds.py'),
        referenceInferenceAssetSha256='c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a',
        qualifiedCandidateBundleSha256='e62d749f4d9125cc86107cc2c37d77cb2f23af65c3648105a5fd2636f1ee27f1',
        worldFormula=recipe['worldFormula'], conservativePairsPerOffset=10000,
        reservedSeeds=sorted(prior), inheritedConservativeOffsets=sorted(offsets),
        newlyProtectedProbeOffsets=rejected, conservativelyExpandedOffsets=sorted(offsets | set(rejected)),
        seedBanks=banks, nativeReservationProbes=probes, numericalReservationProbes=[],
        streamBanks=subject(output / 'stream-banks.json'), rngStreams=subject(output / 'rng-streams.json'),
        schedule=subject(output / 'schedule.json'), declaredNewActualValues=4608,
        declaredNewWorlds=4608, declaredNumericalStreams=0, maximumTerminalExecutions=16464,
        allIntersectionsZero=True, scientificStreamUseAuthorized=False,
        scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0,
        sources='Exact complete88band earlier metadata ancestry only; no outcomes, humans or models.',
        note='Original89future declarations remain immutable. All old88/88bunused banks and89rejected-prefix coverage remain held.')
    write(output / 'seed-audit.json', audit)
    return dict(audit=subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=4608, nativeOffsets=[bank['nativeOffset'] for bank in banks.values()],
        rejectedPrefixes=len(probes) - 1, numericalStreams=0, scientificStreamUseAuthorized=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--method', type=Path, required=True)
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.parent, arguments.method, arguments.output), sort_keys=True))
