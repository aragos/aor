"""Independent study97 proof using the shared, separately owned arithmetic verifier."""
import argparse
import json
from pathlib import Path

import verify_token_focus_seeds as verifier


SPEC = verifier.VerificationSpec(97, 'token-focus-second-frozen-ancestry-1',
    'token-focus-second-prospective-method-1',
    '6193986dbd71fad6cd227562132d198ecd147469360187b79582d804788a7b7c',
    '88b085e79c716c0bfff224cc0f9a2bff114f87ca270bc5e7dcf19c81b1ee4ed4',
    97000000, 97001000, 78, 1103025, 3181,
    'token-focus-second-independent-seed-proof-1')


def verify(audit, expected_sha):
    result = verifier.verify(audit, expected_sha, SPEC)
    result['independentEntryPointSha256'] = verifier.inventory_owner.sha(__file__)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.audit, args.expected_sha)
    with args.output.open('x') as stream:
        json.dump(result, stream, sort_keys=True, indent=2)
        stream.write('\n')
    print(json.dumps(dict(proof=str(args.output), sha256=verifier.inventory_owner.sha(args.output))))
