"""Explicit leader-denial worker adapter; bounded process ownership stays with the common worker."""
import argparse
import json
from pathlib import Path
import sys

from leader_denial_contract import LeaderDenialExperiment, worker_source
import robust_token_worker as worker


def execute(profile, request):
    return worker.execute(profile, request, profile_kind=LeaderDenialExperiment.profile_kind,
        source_validator=worker_source,
        native_environment=LeaderDenialExperiment().native_environment)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--cancel-active', action='store_true')
    parser.add_argument('--inspect', action='store_true')
    args = parser.parse_args()
    if args.inspect:
        print(json.dumps(worker.inspect(args.profile)))
    elif args.cancel_active:
        print(json.dumps({'stoppedNativeGroups': worker.cancel_active(args.profile)}))
    else:
        print(json.dumps(execute(args.profile, json.load(sys.stdin)), separators=(',', ':'), allow_nan=False))
