"""Reserve study96 metadata only:144 game worlds and two PCG64 stream identifiers."""
import argparse
import json
from pathlib import Path

import seed_reservation_dag as dag


PARENT_SHA = 'e0f1a49cb228e71fb5cdd35182910b0b81bf53f02f05c70aa1388132edc8c227'
METHOD_SHA = '1a13821a64de5c1dfdf685f46128969c793a09a03667e6431d55e9c8fe467369'
COUNTS = {'training': 16, 'holdout': 128}
RECIPE = dag.MetadataRecipe(
    family=96, audit_kind='token-focus-step-frozen-ancestry-1',
    schedule_kind='token-focus-step-schedule-1', counts=COUNTS,
    native_prefix=96000000, numerical_prefix=96001000,
    numerical_names=('projectionSeed', 'directionSeed'), maximum_executions=1560,
    expected_ancestors=77, expected_prior_values=1102879, expected_offsets=3179,
    reproductions={'training': {'PLUS_00': [0, 15], 'MINUS_00': [0, 15]},
        'holdout': [0, 42, 85, 127]},
    note='All prior streams and unused worlds through95 remain held. PCG64 identifiers are declarations only; no factors or directions are generated.')


def validate_method(method):
    dag.require(method['kind'] == 'token-focus-step-prospective-method-1' and
        method['study'] == 96 and method['localOnly'] is True and
        method['remoteUseAuthorized'] is False and method['globalDeadlineEpoch'] == 1795020399.0,
        'Study96 must retain its original local-only method and user deadline.')
    dag.require(method['trainingWorlds'] == 16 and method['holdoutWorlds'] == 128 and
        method['freshGameWorlds'] == 144 and method['freshNumericStreams'] == 2 and
        method['maximumTerminalExecutions'] == 1560 and method['generations'] == 1 and
        method['directions'] == 16 and method['trainableHead'] == 'TOKEN',
        'Study96 requires exactly144 fresh game worlds and two numerical stream declarations.')
    dag.require(method['trainingReplayIndices'] == RECIPE.reproductions['training'] and
        method['holdoutReplayIndices'] == RECIPE.reproductions['holdout'],
        'Reproductions must reuse the exact approved training and holdout positions.')


def prepare(parent, method, output):
    dag.require(dag.sha(method) == METHOD_SHA, 'Use the exact root-approved study96 method bytes.')
    validate_method(dag.read(method))
    return dag.prepare_metadata(parent, PARENT_SHA, method, METHOD_SHA, output, RECIPE, __file__)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--method', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.parent, args.method, args.output), sort_keys=True))
