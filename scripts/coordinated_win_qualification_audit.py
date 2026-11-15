"""Pure terminal audit for the nominated win-only contract; shared archive/statistical owner."""
import argparse
from functools import partial
from pathlib import Path
import sys

import coordinated_qualification_audit as legacy
import coordinated_win_qualification_plan as p
import coordinated_win_qualification as q


def contract():
    return sys.modules[__name__]


children = legacy.children
handoff = partial(legacy.handoff, owner=contract())
bank = partial(legacy.bank, owner=contract())
local_bank = partial(legacy.local_bank, owner=contract())
qualified = partial(legacy.qualified, owner=contract())
verify = partial(legacy.verify, owner=contract())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    q.storage.publish_json(args.output, verify(p.subject(args.protocol), args.run))
