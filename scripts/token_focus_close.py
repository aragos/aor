"""Narrow metadata-only closure of the original96 status finalization transition.

The original audit and every scientific artifact stay immutable. Its sole stale
operational status pin is preserved alongside the exact final status hash; the
original running-status bytes are unavailable and are not reconstructed.
"""
import argparse
from dataclasses import dataclass
import json
import math
from pathlib import Path

import mac_policy_close as closure
import robust_token_campaign as c
import token_focus_contract as policy


@dataclass(frozen=True)
class OriginalIdentity:
    protocol: str
    audit: str
    terminal: str
    running_status: str
    final_status: str


ORIGINAL96=OriginalIdentity(
    '9e176d701c681c552b763a21bfda48c5dbe96ba751740365ef34e567ed44064b',
    '07aa70b27b33b6afaf17bbb08377a3e32613f038b9aed45042bd48d4933d600e',
    'f63b14e34348c7fcd7ea2ec2294aba837c1521f2a213add883b4f2bb01f81dc3',
    '62570d80e1893df0789b659ecd41cbe9aaf753bab2b7b593dd6d3a6f7c2b25c2',
    'f0687b4d83422e84c134fa42f36b942219c6a3657e87d91faeeda2fbfa055ab5')


def transition(protocol,profile_path,output,*,identity=ORIGINAL96):
    output=Path(output);audit_path=output/'completion-audit.json';status_path=output/'status.json'
    terminal_path=output/'terminal.json'
    c.require(c.sha(protocol)==identity.protocol and c.sha(audit_path)==identity.audit and
        c.sha(terminal_path)==identity.terminal and c.sha(status_path)==identity.final_status,
        'This exception belongs only to the exact root-reviewed original96 audit, terminal and final status.')
    audit,terminal,status,clock=(c.read(output/name) for name in
        ('completion-audit.json','terminal.json','status.json','clock.json'))
    c.require(audit['kind']=='token-focus-step-original-audit-1' and audit['passed'] is True and
        audit['complete'] is True and audit['qualified'] is False and audit['terminalExecutions']==1560 and
        audit['protocolSha256']==terminal['protocolSha256']==identity.protocol and
        audit['profileSha256']==c.sha(profile_path) and audit['clockSha256']==c.sha(output/'clock.json') and
        terminal['kind']=='token-focus-step-terminal-1' and terminal['study']==96 and
        terminal['complete'] is True and terminal['status']=='completed' and terminal['qualified'] is False and
        terminal['knownCompletedTerminalExecutions']==terminal['reservedTerminalUpperBound']==1560 and
        terminal['completionAuditSha256']==identity.audit,
        'Original96 must retain its complete research-only terminal, original audit, profile, clock and1560 executions.')
    c.require(set(status)=={'kind','updatedEpoch','knownCompletedTerminalExecutions',
        'reservedTerminalUpperBound','status','qualified'} and status['kind']=='token-focus-step-status-1' and
        status['status']=='completed' and status['qualified'] is False and
        status['knownCompletedTerminalExecutions']==status['reservedTerminalUpperBound']==1560 and
        type(status['updatedEpoch']) in (int,float) and math.isfinite(status['updatedEpoch']) and
        terminal['finishedEpoch']<=status['updatedEpoch']<=clock['auditDeadlineEpoch'],
        'The final operational status must be exactly the shared lifecycle completion schema within the original audit clock.')
    pins=audit['artifactPins']
    c.require(pins.get(str(status_path))==identity.running_status,
        'The preserved original audit must contain its exact last running-status pin.')
    mismatches={path for path,digest in pins.items() if not Path(path).is_file() or c.sha(path)!=digest}
    c.require(mismatches=={str(status_path)},
        'Only operational status may differ; every scientific, numerical and game artifact must still rehash.')
    return dict(kind='original96-operational-status-finalization-1',path=str(status_path),
        originalSha256=identity.running_status,finalSha256=identity.final_status,
        originalAudit=dict(path=str(audit_path),sha256=identity.audit),
        originalTerminal=dict(path=str(terminal_path),sha256=identity.terminal),
        originalStatusBytesRetained=False,originalStatusReconstructed=False,
        immutableOriginalPinsVerified=len(pins)-1,scientificPinExceptions=[],
        transition='Shared Campaign.run writes completed status after sealing the original audit.',
        auditReruns=0,newGames=0,newFits=0,modelForwards=0)


def close(protocol,profile_path,output,owner_record):
    verified=transition(protocol,profile_path,output)
    result=closure.close(protocol,profile_path,output,owner_record,policy,status_transition=verified)
    result['statusFinalizationSource']=dict(path=str(Path(__file__).resolve()),sha256=c.sha(__file__))
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('protocol','profile','output','owner-record','receipt'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();result=close(args.protocol,args.profile,args.output,args.owner_record)
    c.write(args.receipt,result);print(json.dumps(result,sort_keys=True))
