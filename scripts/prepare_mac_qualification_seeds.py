"""Reserve study91's sole fixed candidate qualification; no model or outcome inputs."""
import argparse
import datetime
from pathlib import Path
import shutil
import subprocess

import seed_reservation_dag as dag


PARENT_SHA = '3193943dee4dddd649d17e88e09dac29591b2f9a89b50eab3e9c50b3dd7c97a2'
METHOD_SHA = '0687dabf2cb25e1a88740be9a8b29ec5a21a4151b1e7f9df73991de5a76b489b'
COUNTS = {'mixed': 2048, 'homogeneous': 2048}
PREFIX = 91000000


def prepare(parent_path, method_path, output):
    parent_path, method_path = Path(parent_path).resolve(), Path(method_path).resolve()
    dag.require(dag.sha(method_path) == METHOD_SHA,
        'Use the exact prospectively approved study91 qualification method.')
    method = dag.read(method_path)
    dag.require({bank['name']: bank['worlds'] for bank in method['banks']} == COUNTS and
        method['maximumTerminalExecutions'] == 8208 and method['newFits'] == 0 and
        method['scientificNumericDraws'] == 0 and method['localOnly'] is True,
        'Study91 is exactly two fresh 2048-world paired banks without fitting or numerical draws.')
    _, pins = dag.ancestry(parent_path, PARENT_SHA)
    dag.require(len(pins) == 72, 'All 72 frozen ancestry pins must remain protected.')
    prior, offsets = dag.protection(parent_path, PARENT_SHA)
    dag.require(len(prior) == 1094687 and len(offsets) == 3153,
        'The complete study90 actual and expanded historical protection inventories changed.')
    banks, probes, rejected = dag.reserve(prior, offsets, COUNTS, PREFIX)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, output / 'audit_frozen.py')
    shutil.copyfile(dag.__file__, output / 'seed_reservation_dag.py')
    dag.write(output / 'stream-banks.json', banks)
    dag.write(output / 'rng-streams.json', {})
    dag.write(output / 'schedule.json', dict(kind='mac-qualification-schedule-1', banks=banks))
    plan = dict(kind='mac-qualification-rng-preparation-plan-1', family=91, banks=COUNTS,
        maximumTerminalExecutions=8208, firstPrefix=PREFIX, numericalStreams=0,
        reproductionIndices=[0, 682, 1365, 2047],
        worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        authorization='Root metadata declarations only; no scientific games, fits or RNG draws.')
    dag.write(output / 'preparation-plan.json', plan)
    audit = dict(kind='mac-qualification-frozen-ancestry-1', schemaVersion=1, family=91,
        auditedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        protectionStorage='parent-dag-1', carriedForwardAudit=dag.subject(parent_path),
        verifiedAncestors=pins[1:], preparationPlan=dag.subject(output / 'preparation-plan.json'),
        prospectiveMethod=dag.subject(method_path),
        sourceCommit=subprocess.check_output(['git', '-C', str(Path(__file__).resolve().parent.parent),
            'rev-parse', 'HEAD'], text=True).strip(),
        auditScriptSha256=dag.sha(output / 'audit_frozen.py'),
        collisionOwner=dag.subject(output / 'seed_reservation_dag.py'),
        worldFormula=plan['worldFormula'], conservativePairsPerOffset=10000,
        priorActualValues=len(prior), priorActualValuesSha256=dag.values_digest(prior),
        inheritedConservativeOffsets=sorted(offsets), newlyProtectedProbeOffsets=rejected,
        conservativelyExpandedOffsets=sorted(offsets | set(rejected)), seedBanks=banks,
        nativeReservationProbes=probes, numericalReservationProbes=[],
        streamBanks=dag.subject(output / 'stream-banks.json'),
        rngStreams=dag.subject(output / 'rng-streams.json'), schedule=dag.subject(output / 'schedule.json'),
        declaredNewActualValues=4096, declaredNewWorlds=4096, declaredNumericalStreams=0,
        maximumTerminalExecutions=8208, allIntersectionsZero=True,
        scientificStreamUseAuthorized=False, scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0,
        sources='All previous metadata reservations through90; no outcomes, human saves or models.',
        note='89 numerical integers, all unused banks and all rejected native prefixes remain held.')
    dag.write(output / 'seed-audit.json', audit)
    return dict(audit=dag.subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=4096, ancestryPins=len(pins), rejectedPrefixes=len(probes)-1,
        nativeOffsets=[bank['nativeOffset'] for bank in banks.values()], numericalStreams=0,
        scientificStreamUseAuthorized=False)


if __name__ == '__main__':
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--method', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.parent, arguments.method, arguments.output), sort_keys=True))
