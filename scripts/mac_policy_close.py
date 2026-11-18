"""Read-only local search closure; no audit renewal, native game, fit, or effect-record parsing."""
import argparse
import importlib
from pathlib import Path
import time

import mac_policy_search as search
import mac_qualification_close as metadata
import robust_token_campaign as c


def close(protocol, profile_path, output, owner_record, contract):
    protocol,output=Path(protocol),Path(output)
    plan=contract.validate(c.read(protocol));profile=search.verify_profile(profile_path,plan)
    terminal,clock,launch=(c.read(output/name) for name in ('terminal.json','clock.json','launch.json'))
    owner=c.read(owner_record)
    c.require(terminal['status'] in ('completed','failed','interrupted') and
        terminal['protocolSha256']==clock['protocolSha256']==launch['protocolSha256']==c.sha(protocol) and
        c.sha(profile_path)==launch['profileSha256'] and owner['controllerPid']==launch['pid'] and
        (output/'protocol-copy.json').read_bytes()==protocol.read_bytes(),
        'Closure requires the original Terminal owner, terminal, profile, protocol and unchanged clock.')
    pids={launch['pid'],owner['caffeinatePid']};groups={launch['processGroup']}
    counts=[];pins=dict(profile['pins'])
    for path in (output/'chunks').rglob('owner.json'):
        row=c.read(path);pids.update((row['workerPid'],row['nativePid']));groups.add(row['nativeProcessGroup'])
    c.require(not [row for row in metadata.processes() if int(row[0]) in pids or int(row[1]) in groups],
        'Every original controller, native and caffeine process owner must exit before physical closure.')
    for path in (output/'chunks').rglob('source.json'):
        source=c.read(path)
        c.require(source['protocolSha256']==c.sha(protocol),'An original native source protocol changed.')
        counts.append(dict(task=str(path.parent.relative_to(output/'chunks')),
            **metadata.accounting(source,path.parent/'native')))
        prepared=path.parent/'native/prepared.json'
        if prepared.exists():
            for name,digest in c.read(prepared)['inputPins'].items():
                c.require(c.sha(name)==digest,'An originally declared native policy input changed.')
                pins[name]=digest
    if terminal['complete']:
        audit=c.read(output/'completion-audit.json')
        c.require(terminal['status']=='completed' and audit['kind']==contract.PREFIX+'-original-audit-1' and
            audit['passed'] is True and audit['complete'] is True and
            audit['protocolSha256']==c.sha(protocol) and audit['profileSha256']==c.sha(profile_path) and
            terminal['completionAuditSha256']==c.sha(output/'completion-audit.json') and
            audit['clockSha256']==c.sha(output/'clock.json') and audit['qualified'] is terminal['qualified'],
            'A completed search requires its sealed original audit; later closure cannot rerun it.')
        for name,digest in audit['artifactPins'].items():
            c.require(c.sha(name)==digest,'An originally audited search artifact changed.')
    else:c.require(terminal['qualified'] is False,'An interrupted search cannot claim qualification.')
    for path in output.rglob('*'):
        if path.is_file():pins[str(path)]=c.sha(path)
    for path in (protocol,Path(profile_path),Path(owner_record)):pins[str(path)]=c.sha(path)
    return dict(kind=contract.PREFIX+'-physical-closure-1',study=plan['study'],passed=True,
        scienceComplete=terminal['complete'],qualified=terminal['qualified'],
        originalTerminalStatus=terminal['status'],protocolSha256=c.sha(protocol),clockSha256=c.sha(output/'clock.json'),
        ownedPids=sorted(pids),ownedGroups=sorted(groups),allOwnedProcessesExited=True,
        originalClocksRenewed=False,auditReruns=0,nativeCounters=counts,
        actualCompletedLowerBound=sum(row['completedLowerBound'] for row in counts),
        actualAttemptedLowerBound=sum(row['attemptedLowerBound'] for row in counts),
        actualAttemptedUpperBound=sum(row['reservedUpperBound'] for row in counts),
        knownCompletedTerminalExecutions=terminal['knownCompletedTerminalExecutions'],
        controllerReservedTerminalUpperBound=terminal['reservedTerminalUpperBound'],artifactPins=pins,
        newGames=0,newFits=0,modelForwards=0,effectRecordsParsed=0,checkedEpoch=time.time())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract',choices=('learned_bid_contract','mac_leader_contract'),required=True)
    for name in ('protocol','profile','output','owner-record','receipt'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();contract=importlib.import_module(args.contract)
    c.write(args.receipt,close(args.protocol,args.profile,args.output,args.owner_record,contract))
