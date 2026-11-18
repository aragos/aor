"""Reserve study92 fixed learned-bid exploration after the complete study91 reservation."""
import argparse
import datetime
from pathlib import Path
import shutil
import subprocess

import seed_reservation_dag as dag


PARENT_SHA = '5ced97b108696da9efdfc5cc23fa48c08c36d4c9add1a4e2764fb2d2d179cc21'
METHOD_SHA = '82debbe40373aaa5986a7fe79616598e0d9d6e6ec5e3b51959329c7253cbce36'
COUNTS = {'development': 128, 'mixed': 512, 'homogeneous': 512, 'reference': 512}
PREFIX = 92000000


def prepare(parent_path, method_path, output):
    parent_path, method_path = Path(parent_path).resolve(), Path(method_path).resolve()
    dag.require(dag.sha(method_path) == METHOD_SHA,
        'Use the exact prospectively approved study92 learned-bid method.')
    method = dag.read(method_path)
    dag.require({bank['name']: bank['worlds'] for bank in method['banks']} == COUNTS and
        method['maximumTerminalExecutions'] == 5208 and method['noNewFits'] is True and
        method['weightsImmutable'] is True and method['localOnly'] is True,
        'Study92 retains exactly four fixed world banks and immutable models without fitting.')
    _, pins = dag.ancestry(parent_path, PARENT_SHA)
    dag.require(len(pins) == 73, 'All 73 frozen ancestry pins must remain protected.')
    prior, offsets = dag.protection(parent_path, PARENT_SHA)
    dag.require(len(prior) == 1098783 and len(offsets) == 3155,
        'The complete study91 actual and expanded historical protection inventories changed.')
    banks, probes, rejected = dag.reserve(prior, offsets, COUNTS, PREFIX)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, output / 'audit_frozen.py')
    shutil.copyfile(dag.__file__, output / 'seed_reservation_dag.py')
    dag.write(output / 'stream-banks.json', banks)
    dag.write(output / 'rng-streams.json', {})
    dag.write(output / 'schedule.json', dict(kind='learned-bid-search-schedule-1', banks=banks))
    plan = dict(kind='learned-bid-search-rng-preparation-plan-1', family=92, banks=COUNTS,
        maximumTerminalExecutions=5208, firstPrefix=PREFIX, numericalStreams=0,
        reproductionIndices={'development': [0, 42, 85, 127],
            'confirmation': [0, 170, 341, 511]},
        worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        authorization='Root metadata declarations only; no scientific games, fits or RNG draws.')
    dag.write(output / 'preparation-plan.json', plan)
    audit = dict(kind='learned-bid-search-frozen-ancestry-1', schemaVersion=1, family=92,
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
        declaredNewActualValues=1664, declaredNewWorlds=1664, declaredNumericalStreams=0,
        maximumTerminalExecutions=5208, allIntersectionsZero=True,
        scientificStreamUseAuthorized=False, scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0,
        sources='All previous metadata reservations through91; no outcomes, human saves or models.',
        note='91 full qualification banks, 89 numerical integers, all unused banks and all rejected native prefixes remain held.')
    dag.write(output / 'seed-audit.json', audit)
    return dict(audit=dag.subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=1664, ancestryPins=len(pins), rejectedPrefixes=len(probes)-1,
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
