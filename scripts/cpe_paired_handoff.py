"""The fixed C/P/E mailbox identity over the existing bounded outbound transport.

No network scheduling differs from TOKEN. Phase-qualified collection slots prevent a
completed head shard from being mistaken for another head's evidence.
"""
import argparse
import json
from pathlib import Path
import sys

import cpe_paired_campaign_plan as p
import token_paired_handoff as shared

PREFIX = 'cpe-paired'
ADDRESS = shared.ADDRESS
Clock = p.Clock
STAGES = shared.STAGES


class PeerStopped(shared.PeerStopped):
    def __init__(self, response):
        super().__init__(response, prefix=PREFIX)


def tag(iteration, chunk, screen=False, *, head=None):
    p.require(p.owner(chunk, screen)=='MAC', 'Only fixed Mac-owned shards use the outbound mailbox.')
    if screen:
        p.require(iteration is None and head is None, 'A screen shard has no cohort head/iteration.')
        return f'screen-{chunk}'
    p.integer(iteration, 1, 2, 'Iteration'); p.head(head)
    return f'iteration-{iteration}-{head}-chunk-{chunk}'


def allowed_tag(value):
    values={tag(i,c,head=h) for i in (1,2) for h in p.HEADS for c in p.COLLECTION_CHUNKS['MAC']}
    values|={tag(None,c,True) for c in p.SCREEN_CHUNKS['MAC']}
    p.require(value in values, 'Only the27 fixed C/P/E Mac shard handoffs exist.')
    return value


def receipt_tag(record):
    screen=record['kind']==PREFIX+'-screen-chunk-receipt-1'
    return tag(None if screen else record['iteration'],record['chunk'],screen,
        head=None if screen else record['head'])


def terminal(root):return shared.terminal(root,contract=sys.modules[__name__])
def health(root):return shared.health(root,contract=sys.modules[__name__])
def worker_alive(owner):return shared.worker_alive(owner,contract=sys.modules[__name__])
def notify_failure(root,name):return shared.notify_failure(root,name,contract=sys.modules[__name__])
def stage(root,name):return shared.stage(root,name,contract=sys.modules[__name__])
def reserve(root,name):return shared.reserve(root,name,contract=sys.modules[__name__])
def accept(root,name):return shared.accept(root,name,contract=sys.modules[__name__])
def serve(mode,root,name):return shared.serve(mode,root,name,contract=sys.modules[__name__])
def bundle(directory,name,files,identity):
    return shared.bundle(directory,name,files,identity,contract=sys.modules[__name__])


class Outbound(shared.Outbound):
    def __init__(self, profile, clock, local_root, **options):
        super().__init__(profile,clock,local_root,contract=sys.modules[__name__],**options)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('probe','reserve','accept','health','fail'))
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--name',required=True)
    args=parser.parse_args();print(json.dumps(serve(args.mode,args.root,args.name)))
