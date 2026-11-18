"""Actual-host zero-game admission; callable only after the comparator and root protocol freeze."""
import argparse
import json
from pathlib import Path

from leader_denial_contract import LeaderDenialExperiment
from robust_token_install import install


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--host', choices=('DESKTOP', 'MAC'), required=True)
    parser.add_argument('--python', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(install(args.root, args.host, args.python, args.java,
        contract=LeaderDenialExperiment()), sort_keys=True))
