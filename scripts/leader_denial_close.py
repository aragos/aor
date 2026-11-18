"""Read only the original sealed90 audit and process metadata; never rerun or renew its audit."""
import argparse
import json
from pathlib import Path

import leader_denial_contract as policy
import robust_token_campaign as c
from robust_token_close import close


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--desktop-profile', type=Path, required=True)
    parser.add_argument('--mac-profile', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    policy.validate(c.read(args.protocol))
    record = close(args.protocol, {'DESKTOP': args.desktop_profile, 'MAC': args.mac_profile}, args.output)
    record['kind'] = 'leader-denial-closed-metadata-review-1'
    c.write(args.receipt, record)
    print(json.dumps(record, sort_keys=True))
