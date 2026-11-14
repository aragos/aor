"""One continuation stage under the unchanged original-clock campaign controller."""
import argparse
from pathlib import Path
import sys

import coordinated_win_campaign as worker
import coordinated_win_plan as p
import token_paired_execution as shared
import token_paired_storage as storage

PREFIX=p.PREFIX
SCIENCE_SECONDS=p.SCIENCE_SECONDS
LABEL='coordinated win-only continuation'
EVIDENCE_MODULE='coordinated_win_audit'
controller=shared.controller


def specification(protocol,profile,clock):
    p.Plan.from_file(protocol)
    return shared.specification(protocol,profile,clock,contract=sys.modules[__name__])


def host(protocol,profile,clock,output):return shared.host(protocol,profile,clock,output,contract=sys.modules[__name__])
def stage(protocol,profile,clock,output):return shared.stage(protocol,profile,clock,output,contract=sys.modules[__name__])
def verify(protocol,output):return shared.verify(protocol,output,contract=sys.modules[__name__])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('host','stage','audit'))
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--profile',type=Path);parser.add_argument('--clock',type=Path)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--receipt',type=Path)
    args=parser.parse_args()
    if args.mode=='audit':
        p.require(args.receipt is not None,'The pure audit requires a new immutable receipt.')
        storage.publish_json(args.receipt,verify(args.protocol,args.output))
    else:
        p.require(args.profile is not None and args.clock is not None,
            'Execution requires its frozen profile and original shared clock.')
        result=(host if args.mode=='host' else stage)(args.protocol,args.profile,args.clock,args.output)
        if args.mode=='host':raise SystemExit(result)
