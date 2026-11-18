"""Study97 declares a fresh second TOKEN step without executing any scientific RNG."""
import argparse
from dataclasses import replace
import json
from pathlib import Path

import prepare_token_focus_seeds as first
import seed_reservation_dag as dag


PARENT_SHA = '88b085e79c716c0bfff224cc0f9a2bff114f87ca270bc5e7dcf19c81b1ee4ed4'
METHOD_SHA = '6193986dbd71fad6cd227562132d198ecd147469360187b79582d804788a7b7c'
METHOD_KIND = 'token-focus-second-prospective-method-1'
RECIPE = replace(first.RECIPE, family=97, audit_kind='token-focus-second-frozen-ancestry-1',
    native_prefix=97000000, numerical_prefix=97001000, expected_ancestors=78,
    expected_prior_values=1103025, expected_offsets=3181,
    note='All prior streams through96, including unopened worlds and both numerical declarations, remain held. This is metadata only; no factors or directions are generated.')


def validate_method(method):
    first.validate_method(method, kind=METHOD_KIND, study=97)
    dag.require(method['startingWeightsMustBeOriginal96Final'] is True and
        method['holdoutIndependentStartingWeights'] is True and
        method['qualified94ControlWeightsSha256'] ==
            'fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e',
        'Study97 must use only original96FINAL weights, independently of its holdout signal.')


def prepare(parent, method, output):
    dag.require(dag.sha(method) == METHOD_SHA, 'Use the exact approved study97 method bytes.')
    validate_method(dag.read(method))
    return dag.prepare_metadata(parent, PARENT_SHA, method, METHOD_SHA, output, RECIPE, __file__)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--method', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.parent, args.method, args.output), sort_keys=True))
