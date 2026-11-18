"""Reserve optional study93 leader exploration after the complete study92 reservation."""
import argparse
import datetime
from pathlib import Path
import shutil
import subprocess

import seed_reservation_dag as dag


PARENT_SHA = '7df8f7ab2ddce6807731f55218b4646d2ffed6da2b644b7df74fb51bd576b3f4'
METHOD_SHA = '20a3a84a7c03a601fffffe20fe08c691ff22e691b87b992b22a1d5fb17ee79fa'
COUNTS = {'development': 64, 'mixed': 256, 'homogeneous': 256}
PREFIX = 93000000


def prepare(parent_path, method_path, output):
    parent_path, method_path = Path(parent_path).resolve(), Path(method_path).resolve()
    dag.require(dag.sha(method_path) == METHOD_SHA,
        'Use the exact prospectively approved study93 leader-search method.')
    method = dag.read(method_path)
    dag.require({bank['name']: bank['worlds'] for bank in method['banks']} == COUNTS and
        method['maximumTerminalExecutions'] == 2128 and method['noNewFits'] is True and
        method['weightsImmutable'] is True and method['localOnly'] is True,
        'Study93 retains exactly three fixed world banks and immutable models without fitting.')
    _, pins = dag.ancestry(parent_path, PARENT_SHA)
    dag.require(len(pins) == 74, 'All 74 frozen ancestry pins must remain protected.')
    prior, offsets = dag.protection(parent_path, PARENT_SHA)
    dag.require(len(prior) == 1100447 and len(offsets) == 3170,
        'The complete study92 actual and expanded historical protection inventories changed.')
    banks, probes, rejected = dag.reserve(prior, offsets, COUNTS, PREFIX)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, output / 'audit_frozen.py')
    shutil.copyfile(dag.__file__, output / 'seed_reservation_dag.py')
    dag.write(output / 'stream-banks.json', banks)
    dag.write(output / 'rng-streams.json', {})
    dag.write(output / 'schedule.json', dict(kind='mac-leader-search-schedule-1', banks=banks))
    plan = dict(kind='mac-leader-search-rng-preparation-plan-1', family=93, banks=COUNTS,
        maximumTerminalExecutions=2128, firstPrefix=PREFIX, numericalStreams=0,
        reproductionIndices={'development': [0, 21, 42, 63],
            'confirmation': [0, 85, 170, 255]},
        worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        authorization='Root metadata declarations only; no scientific games, fits or RNG draws.')
    dag.write(output / 'preparation-plan.json', plan)
    audit = dict(kind='mac-leader-search-frozen-ancestry-1', schemaVersion=1, family=93,
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
        declaredNewActualValues=576, declaredNewWorlds=576, declaredNumericalStreams=0,
        maximumTerminalExecutions=2128, allIntersectionsZero=True,
        scientificStreamUseAuthorized=False, scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0,
        sources='All previous metadata reservations through92; no outcomes, human saves or models.',
        note='91/92 full native banks, 89 numerical integers, all unused banks and all rejected native prefixes remain held.')
    dag.write(output / 'seed-audit.json', audit)
    return dict(audit=dag.subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=576, ancestryPins=len(pins), rejectedPrefixes=len(probes)-1,
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
