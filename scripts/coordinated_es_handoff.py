"""The fixed16-generation mailbox over the existing finite outbound-only transport."""
import argparse
import json
import sys

import coordinated_es_plan as p
import token_paired_handoff as shared

PREFIX = p.PREFIX
ADDRESS = shared.ADDRESS
Clock = p.Clock
STAGES = tuple(f'{phase}-{g}' for g in range(1,17) for phase in ('controls','population')) + ('screen',)
MAXIMUM_PUBLICATION_BYTES = 1024**3
STOP_REASONS = ('calibration-failed', 'zero-gradient', 'prefix-resource-gate')


class PeerStopped(Exception):
    def __init__(self, response):
        value = response['terminal']
        p.require(value['kind']==PREFIX+'-terminal-1' and value['host']=='DESKTOP' and
            value['status']=='stopped' and value['reason'] in STOP_REASONS,
            'Only an exact coordinated ES operational stop may propagate.')
        count=p.integer(value['completedGenerations'],0,15,'Completed generations before stopping')
        p.require((value['reason']!='calibration-failed' or count==0) and
            (value['reason']!='prefix-resource-gate' or count==2),
            'The operational stop must occur at its original fixed boundary.')
        self.response=response
        super().__init__('Coordinator stopped at its prescribed operational boundary.')


def tag(generation, chunk, screen=False, *, phase=None):
    p.require(p.owner(chunk,screen)=='MAC','Only original Mac shards use this mailbox.')
    if screen:
        p.require(generation is None and phase is None,'A final screen has no generation/phase.')
        return f'screen-{chunk}'
    p.generation(generation)
    p.require(phase in ('controls','population'),'A generation handoff needs its explicit execution phase.')
    return f'{phase}-{generation}-chunk-{chunk}'


def allowed_tag(value):
    tags={tag(g,c,phase=phase) for g in range(1,17) for c in p.GENERATION_CHUNKS['MAC']
        for phase in ('controls','population')}
    tags|={tag(None,c,True) for c in p.SCREEN_CHUNKS['MAC']}
    p.require(value in tags,'Only the102 original ES shard slots exist.')
    return value


def receipt_tag(record):
    if record['kind']==PREFIX+'-screen-chunk-receipt-1':
        return tag(None,record['chunk'],True)
    p.require(record['kind']==PREFIX+'-chunk-receipt-1','Unknown ES shard receipt kind.')
    return tag(record['generation'],record['chunk'],phase=record['phase'])


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
    def __init__(self,profile,clock,local_root,**options):
        super().__init__(profile,clock,local_root,contract=sys.modules[__name__],**options)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('probe','reserve','accept','health','fail'))
    parser.add_argument('--root',required=True);parser.add_argument('--name',required=True)
    args=parser.parse_args()
    print(json.dumps(serve(args.mode,args.root,args.name)))
