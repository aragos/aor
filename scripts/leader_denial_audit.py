"""Pure90 original-audit entry point; bounded by the original clock, with no new game or fit."""
import argparse
from pathlib import Path

from leader_denial_contract import LeaderDenialExperiment
import robust_token_audit as audit
import robust_token_campaign as c


def verify(protocol, output, terminal):
    return audit.verify(protocol, output, terminal, contract=LeaderDenialExperiment())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    c.write(args.receipt, verify(args.protocol, args.output, c.read(args.output/'terminal.json')))
