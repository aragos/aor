"""One finite local policy search; native ownership is reused from study91's frozen executor."""
import argparse
import importlib
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time

import mac_qualification_campaign as local
import mac_qualification_close as metadata
import robust_token_campaign as c


def subject(item):
    path=Path(item['path'])
    c.require(c.sha(path)==item['sha256'],'An original declared metadata subject changed.')
    return c.read(path)


def prerequisite(plan, contract, *, processes=metadata.processes):
    terminal=subject(plan['parentTerminal']);closed=subject(plan['parentClosure'])
    c.require(terminal['kind']==contract.PARENT_TERMINAL_KIND and
        terminal['status'] in ('completed','failed','interrupted') and
        closed['kind']==contract.PARENT_CLOSURE_KIND and closed['passed'] is True and
        closed['allOwnedProcessesExited'] is True and
        terminal['protocolSha256']==closed['protocolSha256'] and
        closed['originalClocksRenewed'] is False and closed['auditReruns']==0,
        'The preceding study must have its original terminal and physical closure before preparation.')
    if 'parentTrialProtocolSha256' in plan:
        c.require(terminal['protocolSha256']==plan['parentTrialProtocolSha256'],
            'Only the exact prospectively declared prior trial can open this independent search.')
    pids,groups=set(closed['ownedPids']),set(closed['ownedGroups'])
    c.require(not [row for row in processes() if int(row[0]) in pids or int(row[1]) in groups],
        'All preceding study owners must remain exited before the next independent experiment.')
    for path,digest in closed['artifactPins'].items():
        c.require(c.sha(path)==digest,'A preceding closed-study artifact changed.')


def verify_profile(path, plan):
    profile=c.read(path)
    c.require(profile['kind']=='mac-policy-search-host-1' and profile['study']==plan['study'] and
        profile['workers']==plan['workers'] and profile['heapMiB']==plan['heapMiB'] and
        all(profile[key]==plan[key] for key in
            ('maximumRssBytes','minimumFreeBytes','maximumDataBytes')) and
        profile['nativeJar']['sha256']==plan['nativeJarSha256'],
        'The local search profile must retain its exact native source and fixed Mac resource allowance.')
    for owner,digest in profile['pins'].items():
        c.require(c.sha(owner)==digest,'An original pinned local search input changed: '+owner)
    for name in ('candidateBundle','inferenceAsset','nomination','arena','nativeJar'):
        item=profile[name]
        c.require(profile['pins'].get(item['path'])==item['sha256'] and c.sha(item['path'])==item['sha256'],
            'The local executable and policy subjects must all belong to the physically pinned closure.')
    c.require(profile['classpath']==str(Path(profile['root'])/'runtime/lib/*') and
        profile['nativeJar']['path']==str(Path(profile['root'])/'runtime/lib/training.jar'),
        'Only the admitted local runtime may own the native execution classpath.')
    return profile


def audit(protocol, profile_path, output, terminal, contract, *, now=time.time):
    plan=contract.validate(c.read(protocol));output=Path(output)
    profile=verify_profile(profile_path,plan);clock=c.read(output/'clock.json')
    c.require(clock==c.science_clock(plan,clock['startedEpoch'],c.sha(protocol),contract.PREFIX) and
        clock['startedEpoch']>=plan['preparedEpoch'] and
        plan['globalDeadlineEpoch']-clock['startedEpoch']>=plan['minimumRemainingSecondsAtPreparation'] and
        now()<=clock['auditDeadlineEpoch'] and terminal['complete'] is True and
        terminal['status']=='completed' and terminal['protocolSha256']==c.sha(protocol) and
        (output/'protocol-copy.json').read_bytes()==Path(protocol).read_bytes(),
        'The original complete search alone may receive its unchanged bounded audit.')
    pins={};reports={};opened=[];selected=None;executions=0;controls=[]
    for bank_name in contract.BANK_ORDER:
        bank=plan['banks'][bank_name];opened.append(bank_name)
        cases=plan['caseConfigs'] if bank_name=='development' else [selected]
        root=output/'chunks'/bank_name
        c.require({path.name for path in root.iterdir()}=={case['id'] for case in cases},
            'Every attempted case must belong to its complete originally opened bank.')
        reports[bank_name]={}
        for case in cases:
            directory=root/case['id'];rows=[];sciences=set()
            chunks=(bank['worlds']+plan['chunkSize']-1)//plan['chunkSize']
            c.require({path.name for path in directory.iterdir()}=={f'chunk-{i}' for i in range(chunks)},
                'Every complete case requires exactly its original chunk set.')
            for chunk in range(chunks):
                folder=directory/f'chunk-{chunk}'
                source=contract.native_source(plan,profile,bank_name,case,chunk,c.sha(protocol))
                c.require(c.read(folder/'source.json')==source,'An original native source changed.')
                records,counts,_=contract.native_chunk(source,folder/'native',bank_name)
                rows.extend(records);executions+=counts['terminalExecutions']
                receipt=c.read(folder/'receipt.json')
                c.require(receipt['complete'] is True and receipt['sourceSha256']==c.sha(folder/'source.json') and
                    receipt['finishedSha256']==c.sha(folder/'native/finished.json') and
                    receipt['scienceSha256']==c.read(folder/'native/finished.json')['scienceSha256'] and
                    receipt['terminalExecutions']==local.expected_executions(source),
                    'An original completed local chunk receipt changed.')
                sciences.add(receipt['scienceSha256'])
                for path in folder.rglob('*'):
                    if path.is_file():pins[str(path)]=c.sha(path)
            c.require(len(sciences)==1,'A complete original case cannot mix scientific identities.')
            reports[bank_name][case['id']]=contract.aggregate(rows,bank)
            if bank_name=='development':
                controls.append([row['roles']['CONTROL'] for row in sorted(rows,key=lambda row:row['index'])])
        c.require(reports[bank_name]==c.read(output/'reports'/f'{bank_name}.json')['cases'],
            'All complete original win and bid-concentration statistics must independently reproduce.')
        if bank_name=='development':
            c.require(all(control==controls[0] for control in controls[1:]),
                'The common CONTROL worlds must remain identical across all eight development cases.')
            selected=contract.select(reports[bank_name])
            c.require(c.read(output/'selection.json')['case']==selected,
                'The sole nomination must reproduce the fixed complete-development rule.')
            if selected is None:break
        elif not contract.passed(reports[bank_name][selected['id']],bank_name):break
    c.require({path.stem for path in (output/'reports').glob('*.json')}==set(opened) and
        {path.name for path in (output/'chunks').iterdir()}==set(opened),
        'No unreported or ungated original bank may disappear from execution accounting.')
    qualified=(opened==list(contract.BANK_ORDER) and selected is not None and
        contract.passed(reports[opened[-1]][selected['id']],opened[-1]))
    c.require(qualified is terminal['qualified'] and executions==terminal['knownCompletedTerminalExecutions']==
        terminal['reservedTerminalUpperBound'] and executions<=plan['maximumTerminalExecutions'],
        'Final qualification and all original executions must match the complete audited evidence.')
    for path in (Path(protocol),Path(profile_path),output/'clock.json',output/'launch.json',
                 output/'selection.json',*list((output/'reports').glob('*.json'))):
        pins[str(path)]=c.sha(path)
    reservation=(dict(fullReservedWorlds=plan['fullReservedWorlds'],activeReservedWorlds=plan['activeReservedWorlds'],
        unusedReservedWorlds=plan['unusedReservedWorlds'],fullScheduleSha256=plan['scheduleSha256'])
        if 'fullReservedWorlds' in plan else None)
    return dict(kind=contract.PREFIX+'-original-audit-1',passed=True,complete=True,qualified=qualified,
        study=plan['study'],protocolSha256=c.sha(protocol),profileSha256=c.sha(profile_path),
        clockSha256=c.sha(output/'clock.json'),terminalExecutions=executions,selectedCase=selected,
        artifactPins=pins,allWin99StatisticsRecomputed=True,originalReproductionsVerified=True,
        allObservedConcentrationStatisticsRecomputed=True,newGames=0,newFits=0,modelForwards=0,
        completedEpoch=now(),protectedReservation=reservation)


class Campaign:
    def __init__(self, protocol, profile_path, approval, approval_sha, output, contract):
        self.protocol,self.profile_path,self.output=Path(protocol),Path(profile_path),Path(output)
        self.contract=contract;self.plan=contract.validate(c.read(protocol))
        self.profile=verify_profile(profile_path,self.plan)
        prerequisite(self.plan,contract)
        review=c.read(approval)
        c.require(c.sha(approval)==approval_sha and review['launchAuthorized'] is True and
            review['protocolSha256']==c.sha(protocol) and review['profileSha256']==c.sha(profile_path),
            'The exact original local search protocol and profile require root launch approval.')
        started=time.time()
        c.require(self.plan['globalDeadlineEpoch']-started>=self.plan['minimumRemainingSecondsAtPreparation'] and
            self.plan['preparedEpoch']<=started and not self.output.exists() and
            self.output.resolve().is_relative_to(Path(self.profile['root']).resolve()),
            'The new-only local search must start inside its original minimum remaining user window.')
        self.output.mkdir(parents=True)
        self.clock=c.science_clock(self.plan,started,c.sha(protocol),contract.PREFIX)
        (self.output/'protocol-copy.json').write_bytes(self.protocol.read_bytes())
        c.write(self.output/'clock.json',self.clock)
        c.write(self.output/'launch.json',dict(pid=os.getpid(),processGroup=os.getpgrp(),
            protocolSha256=c.sha(protocol),profileSha256=c.sha(profile_path),approvalSha256=approval_sha))
        self.cancelled,self.process,self.interrupted=threading.Event(),None,False
        self.known,self.reserved=0,0

    def status(self, **values):
        c.replace_status(self.output/'status.json',dict(kind=self.contract.PREFIX+'-status-1',
            updatedEpoch=time.time(),knownCompletedTerminalExecutions=self.known,
            reservedTerminalUpperBound=self.reserved,**values))

    def stop(self, signum=None, _frame=None):
        self.interrupted=self.interrupted or signum in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP)
        self.cancelled.set();local.stop_process(self.process)

    def bank(self, name, cases):
        bank=self.plan['banks'][name];reports={};controls=[]
        for case in cases:
            rows=[]
            for chunk in range((bank['worlds']+self.plan['chunkSize']-1)//self.plan['chunkSize']):
                source=self.contract.native_source(self.plan,self.profile,name,case,chunk,c.sha(self.protocol))
                self.reserved+=local.expected_executions(source)
                c.require(self.reserved<=self.plan['maximumTerminalExecutions'],
                    'The original bounded search execution allowance cannot be exceeded.')
                validator=lambda source,native:self.contract.native_chunk(source,native,name)[0]
                rows.extend(local.execute(self.profile,source,
                    self.output/'chunks'/name/case['id']/f'chunk-{chunk}',self.clock,self.cancelled,
                    lambda process:setattr(self,'process',process),validator=validator))
                self.known+=local.expected_executions(source)
                self.status(status='running',stage=name,case=case['id'],lastChunk=chunk)
            reports[case['id']]=self.contract.aggregate(rows,bank)
            if name=='development':
                controls.append([row['roles']['CONTROL'] for row in sorted(rows,key=lambda row:row['index'])])
        c.require(all(control==controls[0] for control in controls[1:]),
            'Repeated CONTROL worlds must be identical before development selection.')
        c.write(self.output/'reports'/f'{name}.json',dict(complete=True,bank=name,cases=reports,
            protocolSha256=c.sha(self.protocol),qualificationClaimed=False))
        return reports

    def run(self):
        previous={event:signal.signal(event,self.stop) for event in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP)}
        try:
            development=self.bank('development',self.plan['caseConfigs'])
            selected=self.contract.select(development)
            c.write(self.output/'selection.json',dict(complete=True,case=selected,protocolSha256=c.sha(self.protocol)))
            qualified=False
            if selected is not None:
                for name in self.contract.BANK_ORDER[1:]:
                    report=self.bank(name,[selected])[selected['id']]
                    if not self.contract.passed(report,name):break
                    qualified=name==self.contract.BANK_ORDER[-1]
            terminal=dict(kind=self.contract.PREFIX+'-terminal-1',study=self.plan['study'],complete=True,
                status='completed',qualified=qualified,selectedCase=selected,protocolSha256=c.sha(self.protocol),
                knownCompletedTerminalExecutions=self.known,reservedTerminalUpperBound=self.reserved,
                finishedEpoch=time.time(),activated=False)
            result=audit(self.protocol,self.profile_path,self.output,terminal,self.contract)
            c.require(not self.cancelled.is_set() and time.time()<=self.clock['auditDeadlineEpoch'],
                'The original statistical audit cannot finish after interruption or its original allowance.')
            c.write(self.output/'completion-audit.json',result)
            terminal['completionAuditSha256']=c.sha(self.output/'completion-audit.json')
            c.write(self.output/'terminal.json',terminal);self.status(status='completed',qualified=qualified)
            return terminal
        except BaseException as error:
            self.stop()
            terminal=dict(kind=self.contract.PREFIX+'-terminal-1',study=self.plan['study'],complete=False,
                status='interrupted' if self.interrupted else 'failed',qualified=False,
                reason=type(error).__name__+': '+str(error),protocolSha256=c.sha(self.protocol),
                knownCompletedTerminalExecutions=self.known,reservedTerminalUpperBound=self.reserved,
                finishedEpoch=time.time(),activated=False)
            c.write(self.output/'terminal.json',terminal);self.status(status=terminal['status'])
            raise
        finally:
            for event,handler in previous.items():signal.signal(event,handler)


def launch(args, contract):
    plan=contract.validate(c.read(args.protocol));profile=verify_profile(args.profile,plan)
    prerequisite(plan,contract)
    review=c.read(args.approval)
    c.require(c.sha(args.approval)==args.approval_sha256 and review['launchAuthorized'] is True and
        review['protocolSha256']==c.sha(args.protocol) and review['profileSha256']==c.sha(args.profile) and
        args.owner_record is not None and not args.output.exists(),
        'The owned Terminal launch requires exact root approval, its new owner record and a fresh attempt.')
    environment={name:os.environ[name] for name in
        ('HOME','USER','LOGNAME','TMPDIR','__CF_USER_TEXT_ENCODING') if name in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin',LANG='en_US.UTF-8',LC_ALL='en_US.UTF-8',
        TZ='UTC',PYTHONDONTWRITEBYTECODE='1',PYTHONNOUSERSITE='1',OMP_NUM_THREADS='1',
        MKL_NUM_THREADS='1',OPENBLAS_NUM_THREADS='1',VECLIB_MAXIMUM_THREADS='1')
    command=[profile['python'],'-E','-s','-B',str(Path(__file__).resolve()),'--contract',contract.__name__,
        '--protocol',str(args.protocol),'--profile',str(args.profile),'--approval',str(args.approval),
        '--approval-sha256',args.approval_sha256,'--output',str(args.output)]
    caffeine=subprocess.Popen(['/usr/bin/caffeinate','-i','-w',str(os.getpid())],env=environment,
        stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
    c.write(args.owner_record,dict(kind=contract.PREFIX+'-terminal-owner-1',controllerPid=os.getpid(),
        controllerParentPid=os.getppid(),controllerGroup=os.getpgrp(),caffeinatePid=caffeine.pid,
        exactCommand=command,approvalSha256=args.approval_sha256,environmentNames=sorted(environment)))
    os.execve(command[0],command,environment)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract',choices=('learned_bid_contract','mac_leader_contract'),required=True)
    for name in ('protocol','profile','approval','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--approval-sha256',required=True)
    parser.add_argument('--launch',action='store_true')
    parser.add_argument('--owner-record',type=Path)
    args=parser.parse_args();contract=importlib.import_module(args.contract)
    if args.launch:return launch(args,contract)
    result=Campaign(args.protocol,args.profile,args.approval,args.approval_sha256,args.output,contract).run()
    print(json.dumps(result,sort_keys=True))


if __name__=='__main__':main()
