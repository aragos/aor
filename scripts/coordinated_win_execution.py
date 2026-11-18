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


def verify_profile(protocol,output):
    """The new profile-dependent numerical audit consumes the original controller input."""
    output=Path(output);spec=p.read(output/'campaign.json')
    p.require(len(spec['stages'])==1 and spec['stages'][0]['id']==PREFIX,
        'Only the original single continuation stage may supply the audit profile.')
    command=spec['stages'][0]['command']
    p.require(command.count('--profile')==command.count('--protocol')==1,
        'The original controller must declare exactly one profile and protocol.')
    profile=p.subject(command[command.index('--profile')+1])
    declared_protocol=p.subject(command[command.index('--protocol')+1])
    owner=p.read(output/'run/study/worker-owner.json')
    p.require(owner['kind']==PREFIX+'-worker-owner-1' and owner['profile']==profile and
        declared_protocol==p.subject(protocol) and
        all(spec['inputs'].get(item['path'])==item['sha256'] for item in (profile,declared_protocol)),
        'The worker audit profile and protocol must equal the exact pinned controller arguments.')


def verify(protocol,output):
    verify_profile(protocol,output)
    return shared.verify(protocol,output,contract=sys.modules[__name__])


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
