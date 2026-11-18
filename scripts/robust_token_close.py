"""Metadata-only closed-run verification using already-sealed original audits.

No statistical audit is rerun and no expiry is renewed. Safe for later read-only review.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

import robust_token_campaign as c


def close(protocol, profiles, output):
    output=Path(output)
    terminal=c.read(output/'terminal.json');audit=c.read(output/'completion-audit.json')
    launch=c.read(output/'launch.json');clock=c.read(output/'clock.json')
    c.require(terminal['complete'] is True and terminal['status']=='completed' and
        terminal['completionAuditSha256']==c.sha(output/'completion-audit.json') and
        audit['passed'] is True and audit['complete'] is True and
        audit['clockSha256']==c.sha(output/'clock.json') and
        audit['protocolSha256']==clock['protocolSha256']==c.sha(protocol) and
        audit['qualified'] is terminal['qualified'],
        'Closure requires the exact successful original pure audit and unchanged shared clock.')
    for path,digest in audit['artifactPins'].items():
        c.require(c.sha(path)==digest,'An original audited artifact changed after closure.')
    state=subprocess.run(['/bin/ps','-p',str(launch['pid']),'-o','pid=,pgid=,comm='],
        capture_output=True,text=True,check=False)
    c.require(state.returncode!=0 or not state.stdout.strip(),
        'The original campaign controller must exit before final external closure.')
    hosts={}
    for host in c.HOSTS:
        path=Path(profiles[host]);profile=c.read(path)
        c.require(c.sha(path)==launch['profileSha256'][host],
            'The final host profile must retain its original executing bytes.')
        command=[profile['python'],'-B',profile['workerScript'],'--profile',profile['actualProfile'],'--inspect']
        if host=='DESKTOP':
            command=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8',profile['sshTarget'],*command]
        answer=subprocess.run(command,capture_output=True,text=True,timeout=60,check=True)
        value=json.loads(answer.stdout)
        c.require(value['profileSha256']==c.sha(path) and value['allOriginalInputsUnchanged'] is True and
            value['allOwnedProcessesExited'] is True and not value['remainingProcesses'] and
            value['originalClockSha256']==c.sha(output/'clock.json') and value['effectReads']==0,
            'Each original host must independently retain its inputs and shared clock with every owned process gone.')
        hosts[host]=value
    return dict(kind='robust-token-closed-metadata-review-1',passed=True,complete=True,
        checkedEpoch=time.time(),protocolSha256=c.sha(protocol),clockSha256=c.sha(output/'clock.json'),
        originalAuditSha256=c.sha(output/'completion-audit.json'),
        originalArtifactPinsRehashed=len(audit['artifactPins']),hosts=hosts,
        allOwnedProcessesExited=True,reviewIsPostAuditReadOnly=True,
        execution=dict(newGames=0,newFits=0,modelForwards=0,effectReads=0),
        originalClocksRenewed=False,auditReruns=0)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--desktop-profile',type=Path,required=True)
    parser.add_argument('--mac-profile',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--receipt',type=Path,required=True)
    args=parser.parse_args()
    c.write(args.receipt,close(args.protocol,{'DESKTOP':args.desktop_profile,'MAC':args.mac_profile},args.output))
