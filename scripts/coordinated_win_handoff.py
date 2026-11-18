"""The existing finite ES mailbox, with exact win-continuation receipt/stop kinds."""
import argparse
import json
import sys

import coordinated_es_handoff as base
import coordinated_win_plan as p

PREFIX=p.PREFIX
ADDRESS=base.ADDRESS
Clock=p.Clock
STAGES=base.STAGES
MAXIMUM_PUBLICATION_BYTES=base.MAXIMUM_PUBLICATION_BYTES
tag=base.tag
allowed_tag=base.allowed_tag
STOP_REASONS=('zero-gradient','nonfinite-arithmetic','prefix-resource-gate')


class PeerStopped(Exception):
    def __init__(self,response):
        value=response['terminal']
        p.require(value['kind']==PREFIX+'-terminal-1' and value['host']=='DESKTOP' and
            value['status']=='stopped' and value['reason'] in STOP_REASONS,
            'Only an original win-continuation arithmetic/resource stop may propagate.')
        count=p.integer(value['completedGenerations'],0,15,'Completed continuation steps')
        p.require(value['reason']!='prefix-resource-gate' or count==2,
            'Resource stops retain the two-complete-generation prefix.')
        self.response=response
        super().__init__('The fixed coordinator stopped without a replacement candidate.')


def contract():return sys.modules[__name__]


def receipt_tag(record):
    if record['kind']==PREFIX+'-screen-chunk-receipt-1':return tag(None,record['chunk'],True)
    p.require(record['kind']==PREFIX+'-chunk-receipt-1','Unknown continuation shard receipt kind.')
    return tag(record['generation'],record['chunk'],phase=record['phase'])


def terminal(root):return base.shared.terminal(root,contract=contract())
def health(root):return base.shared.health(root,contract=contract())
def worker_alive(owner):return base.shared.worker_alive(owner,contract=contract())
def notify_failure(root,name):return base.shared.notify_failure(root,name,contract=contract())
def stage(root,name):return base.shared.stage(root,name,contract=contract())
def reserve(root,name):return base.shared.reserve(root,name,contract=contract())
def accept(root,name):return base.shared.accept(root,name,contract=contract())
def receive_retention(root,name):return base.receive_retention(root,name,contract=contract())
def serve(mode,root,name):return base.serve(mode,root,name,contract=contract())
def bundle(directory,name,files,identity):return base.shared.bundle(directory,name,files,identity,contract=contract())


class Outbound(base.Outbound):
    def __init__(self,profile,clock,local_root,**options):
        super().__init__(profile,clock,local_root,contract=contract(),**options)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('probe','reserve','accept','health','fail','retention'))
    parser.add_argument('--root',required=True);parser.add_argument('--name',required=True)
    args=parser.parse_args();print(json.dumps(serve(args.mode,args.root,args.name)))
