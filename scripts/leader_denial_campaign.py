"""Run one finite leader-denial mechanism experiment under an exact root-approved protocol."""
import argparse
import json
from pathlib import Path

from leader_denial_contract import LeaderDenialExperiment
from robust_token_campaign import Campaign


def run(protocol, profiles, approval, approval_sha, output):
    return Campaign(protocol, profiles, approval, approval_sha, output,
        contract=LeaderDenialExperiment()).run()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--desktop-profile', type=Path, required=True)
    parser.add_argument('--mac-profile', type=Path, required=True)
    parser.add_argument('--approval', type=Path, required=True)
    parser.add_argument('--approval-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.protocol, {'DESKTOP': args.desktop_profile, 'MAC': args.mac_profile},
        args.approval, args.approval_sha256, args.output), sort_keys=True))
