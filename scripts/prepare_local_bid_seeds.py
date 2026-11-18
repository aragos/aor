"""Reserve fresh study95 local bid mixture metadata without model or outcome access."""
import argparse
import datetime
from pathlib import Path
import shutil
import subprocess

import seed_reservation_dag as dag


PARENT_SHA = '55b4d7c184cd65a87ce50a5ece4b3fe3fb7ca5a22524ac2dd96064c92ed75a30'
METHOD_SHA = '4c757424a2700de67d2f2dc6ba6b2e7e9bef8c76a96dc714eec158578698179f'
COUNTS = {'development': 64, 'mixed': 256, 'homogeneous': 256, 'reference': 256}
PREFIX = 95000000


def validate_method(method):
    dag.require(method.get('kind') == 'local-bid-mixture-prospective-method-1' and
        method.get('study') == 95 and method.get('localOnly') is True and
        method.get('remoteUseAuthorized') is False and method.get('noNewFits') is True and
        method.get('weightsImmutable') is True and method.get('globalDeadlineEpoch') == 1795020399.0,
        'Study95 must retain its approved local-only window and immutable no-fit policy.')
    dag.require({bank['name']: bank['worlds'] for bank in method['banks']} == COUNTS and
        method.get('maximumTerminalExecutions') == 2648,
        'Study95 requires exactly 832 new worlds and at most 2648 terminal executions.')
    expected = [dict(id=f'n{index:02d}', tokenNeighborMixture=dict(
        kind='local-token-neighbor-mixture-1', departureProbability=epsilon,
        radius=radius, admission=admission))
        for index, (admission, radius, epsilon) in enumerate(
            (admission, radius, epsilon)
            for admission in ('OFFERED_ONLY', 'QUOTED_LEGAL_NEIGHBORS')
            for radius in (1, 2) for epsilon in (0.25, 0.5))]
    dag.require(method['caseConfigs'] == expected,
        'The eight prospectively approved local mixture cases must retain their exact order.')
    for bank in method['banks']:
        expected_replays = [0, 21, 42, 63] if bank['name'] == 'development' else [0, 85, 170, 255]
        dag.require(bank['replayIndices'] == expected_replays,
            'Study95 reproduction indices must reuse the fixed approved world positions.')


def prepare(parent_path, method_path, output):
    parent_path, method_path = Path(parent_path).resolve(), Path(method_path).resolve()
    dag.require(dag.sha(method_path) == METHOD_SHA,
        'Use the exact prospectively approved study95 local bid mixture method.')
    method = dag.read(method_path)
    validate_method(method)
    _, pins = dag.ancestry(parent_path, PARENT_SHA)
    dag.require(len(pins) == 76, 'All 76 frozen ancestry pins must remain protected.')
    prior, offsets = dag.protection(parent_path, PARENT_SHA)
    dag.require(len(prior) == 1102047 and len(offsets) == 3175,
        'The complete study94 actual and expanded historical protection inventories changed.')
    banks, probes, rejected = dag.reserve(prior, offsets, COUNTS, PREFIX)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, output / 'audit_frozen.py')
    shutil.copyfile(dag.__file__, output / 'seed_reservation_dag.py')
    dag.write(output / 'stream-banks.json', banks)
    dag.write(output / 'rng-streams.json', {})
    dag.write(output / 'schedule.json', dict(kind='local-bid-mixture-schedule-1', banks=banks))
    plan = dict(kind='local-bid-mixture-rng-preparation-plan-1', family=95, banks=COUNTS,
        maximumTerminalExecutions=2648, firstPrefix=PREFIX, numericalStreams=0,
        reproductionIndices={'development': [0, 21, 42, 63], 'qualification': [0, 85, 170, 255]},
        worldFormula='-1000000000 + nativeOffset + globalIndex*8191',
        authorization='Root metadata declarations only; no scientific games, fits or RNG draws.')
    dag.write(output / 'preparation-plan.json', plan)
    audit = dict(kind='local-bid-mixture-frozen-ancestry-1', schemaVersion=1, family=95,
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
        declaredNewActualValues=832, declaredNewWorlds=832, declaredNumericalStreams=0,
        maximumTerminalExecutions=2648, allIntersectionsZero=True,
        scientificStreamUseAuthorized=False, scientificStreamUseRequiresRootSeedReview=True,
        modelsRead=0, gamesRun=0, fitsRun=0, numericDraws=0, humanInputsRead=0,
        sources='All previous metadata reservations through 94; no outcomes, human saves or models.',
        note='All 4096 original91 worlds, all92/93unused banks, all94qualification worlds, 89 numerical integers and all rejected native prefixes remain held.')
    dag.write(output / 'seed-audit.json', audit)
    return dict(audit=dag.subject(output / 'seed-audit.json'), schedule=audit['schedule'],
        priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        freshNativeWorlds=832, ancestryPins=len(pins), rejectedPrefixes=len(probes)-1,
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
