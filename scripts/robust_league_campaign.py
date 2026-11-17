"""Six fixed ES generations, one final mean, then untouched independent win gates.

The Desktop alone materializes numerical candidates. Each original native task is attempted
once; incomplete populations, arithmetic stops and expired clocks never produce a nominee.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tarfile
import threading
import time

import robust_token_campaign as c


KIND='robust-league-campaign-1'
MAXIMUM_EXECUTIONS=36920
GENERATIONS=6
ADOPTION_RULE='complete-desktop-320-winner;otherwise-apriori-c06;ignore-confirmation-outcomes'


def validate(plan):
    c.require(plan['kind']==KIND and type(plan['maximumScienceSeconds']) is int and
        0<plan['maximumScienceSeconds']<=32400 and
        plan['auditSeconds']==600 and plan['maximumTerminalExecutions']==MAXIMUM_EXECUTIONS,
        'Only the fixed original six-generation experiment may execute.')
    recipe=plan['numericRecipe']
    c.require(recipe['generations']==GENERATIONS and recipe['mutants']==32 and recipe['worlds']==128 and
        recipe['objective']=='WIN_SHARE_ONLY' and recipe['candidate']=='FINAL_MEAN_6_ONLY',
        'The numerical method cannot substitute shorter runs, different fitness or selected checkpoints.')
    counts={**{f'generation-{n}':128 for n in range(1,9)},'development':1024,'mixed':2048,'homogeneous':2048}
    c.require(set(plan['banks'])==set(counts),'All eight training and three independent test banks must be reserved.')
    values=[]
    for name,count in counts.items():
        bank=plan['banks'][name]
        c.require(len(bank['seeds'])==len(set(bank['seeds']))==count and
            all(type(value) is int for value in bank['seeds']),
            'Every complete fixed world bank requires exact independent integer seeds.')
        values.extend(bank['seeds'])
    c.require(len(set(values))==6144,'Every training, development and qualification bank must be disjoint.')
    return plan


def owner(member,chunk,generation):
    """Four balanced signed-member chunks per generation use the slower Mac."""
    assigned=(member+2*(generation-1))%16 in (0,15) and chunk==(generation-1)%2
    return 'MAC' if assigned else 'DESKTOP'


def test_owner(chunk):
    return 'MAC' if chunk%16==15 else 'DESKTOP'


def replay_indices(bank,member=None):
    if bank.startswith('generation-'):return [0,127] if member in (0,1) else []
    return [0,341,682,1023] if bank=='development' else [0,682,1365,2047]


def statistics(values,confidence):
    multiplier=1.96 if confidence==95 else c.MULTIPLIER
    row=c.interval(values,multiplier)
    return {('lower'+str(confidence) if key=='lower99' else
        'upper'+str(confidence) if key=='upper99' else key):value for key,value in row.items()}


def check_worlds(rows,seeds,candidate_only):
    rows=sorted(rows,key=lambda row:row['index'])
    c.require(len(rows)==len(seeds) and [row['index'] for row in rows]==list(range(len(seeds))) and
        [row['seed'] for row in rows]==seeds and all(row['seat']==row['index']%4 for row in rows),
        'Every original world, seed and controlled seat must complete exactly once.')
    roles={'CANDIDATE'} if candidate_only else {'CANDIDATE','CONTROL'}
    c.require(all(set(row['roles'])==roles for row in rows),'The original role accounting cannot change.')
    styles=('QUALIFIED','UNDERCUT','OVERBID','MIXED')
    c.require(all(row['opponentStyle']==styles[(row['index']//4+row['seat'])%4] for row in rows),
        'Training and development ecology must be crossed with controlled seat.')
    return rows


def outcome_matrix(generation,population,rows):
    c.require(len(rows)==32,'A numerical update requires all32 complete original signed members.')
    result=[]
    for member in population['members']:
        index=member['index'];ordered=check_worlds(rows[index],population['worldSeeds'],True)
        wins=[c.metric(row,'CANDIDATE','wins') for row in ordered]
        c.require(all(math.isfinite(value) and 0<=value<=1 for value in wins),
            'Fitness contains normalized original controlled-seat terminal win shares only.')
        result.append(dict(index=index,direction=member['direction'],sign=member['sign'],
            bundleSha256=member['bundle']['sha256'],winShares=wins))
    return dict(kind='robust-league-generation-outcomes-1',complete=True,verified=True,generation=generation,
        generationBankSha256=population['generationBankSha256'],worldSeeds=population['worldSeeds'],members=result)


def development(final,start,seeds):
    final=check_worlds(final,seeds,False);start=check_worlds(start,seeds,False)
    c.require([row['roles']['CONTROL'] for row in final]==[row['roles']['CONTROL'] for row in start],
        'The repeated original CONTROL worlds must match exactly across both development cases.')
    final_wins=[c.metric(row,'CANDIDATE','wins') for row in final]
    start_wins=[c.metric(row,'CANDIDATE','wins') for row in start]
    control=[c.metric(row,'CONTROL','wins') for row in final]
    learn=statistics([a-b for a,b in zip(final_wins,start_wins)],95)
    gain=statistics([a-b for a,b in zip(final_wins,control)],95)
    return dict(kind='robust-league-development-1',complete=True,worlds=1024,
        finalMinusStart=learn,finalMinusControl=gain,
        startMinusControl=statistics([a-b for a,b in zip(start_wins,control)],95),
        finalWin=math.fsum(final_wins)/len(final),startWin=math.fsum(start_wins)/len(start),
        controlWin=math.fsum(control)/len(control),
        learningWin95Passed=learn['lower95']>0,controlWin95Passed=gain['lower95']>0,
        passed=learn['lower95']>0 and gain['lower95']>0,qualification=False)


def adopt_selector(parent_protocol,parent_output,closed_review):
    """Apply the foretold development-only rule; confirmation reports are never opened."""
    parent_output=Path(parent_output);plan=c.validate(c.read(parent_protocol))
    closed=c.read(closed_review)
    c.require(closed['passed'] is True and
        closed['allNativeGroupsAndControllerIndependentlyGone'] is True and
        closed['parentProtocolSha256']==c.sha(parent_protocol),
        'The parent experiment must be externally closed before its development selector is adopted.')
    folders=[parent_output/'chunks/development'/case['id']/f'chunk-{chunk}'
        for case in plan['caseConfigs'] for chunk in range(5)]
    complete=all((folder/'receipt.json').exists() for folder in folders)
    reports={};pins={};all_rows={}
    if complete:
        for case in plan['caseConfigs']:
            values=[]
            for chunk in range(5):
                folder=parent_output/'chunks/development'/case['id']/f'chunk-{chunk}'
                receipt=c.read(folder/'receipt.json');finished=c.read(folder/'native/finished.json')
                source=c.read(folder/'request.json')['source']
                c.require(receipt['host']=='DESKTOP' and source['selector']==case['selector'] and
                    receipt['finishedSha256']==c.sha(folder/'native/finished.json') and finished['complete'] is True,
                    'Initializer data must be the exact original completed Desktop chunks.')
                c.require(source['protocolSha256']==c.sha(parent_protocol) and source['globalStart']==chunk*64 and
                    source['count']==64 and source['seeds']==plan['banks']['development']['seeds'] and
                    source['opponentStyle']=='BALANCED' and source['replayIndices']==plan['banks']['development']['replayIndices'],
                    'Every initializer source must retain its original fixed seed bank and exact chunk range.')
                prepared=c.read(folder/'native/prepared.json')
                c.require(finished['sourceSha256']==prepared['sourceSha256']==
                    hashlib.sha256(c.canonical(source)).hexdigest() and
                    finished['scienceSha256']==prepared['scienceSha256'],
                    'Initializer native source and science identities must remain exact.')
                for name,digest in finished['files'].items():
                    path=folder/'native'/name
                    c.require(c.sha(path)==digest,'An original initializer native output changed.');pins[str(path)]=digest
                path=folder/'native/worlds.jsonl'
                c.require(c.sha(path)==finished['files']['worlds.jsonl'],'Original initializer outcomes changed.')
                rows=[json.loads(line) for line in path.read_text().splitlines()]
                c.validate_chunk_indices(rows,chunk*64,64)
                repro_path=folder/'native/reproductions.jsonl'
                repros=[json.loads(line) for line in repro_path.read_text().splitlines()] if repro_path.exists() else []
                owned=[index for index in source['replayIndices'] if chunk*64<=index<(chunk+1)*64]
                c.require(len(repros)==2*len(owned) and {(row['index'],row['role']) for row in repros}==
                    {(index,role) for index in owned for role in ('CANDIDATE','CONTROL')},
                    'Initializer reproductions must retain the exact original prescribed index-role set.')
                originals={row['index']:row for row in rows}
                for replay in repros:
                    world=originals[replay['index']];role=replay['role']
                    c.require(replay['matched'] is True and replay['terminal']==world['roles'][role] and
                        replay['diagnostics']==world['diagnostics'][role] and
                        replay['canonicalSha256']==world['canonicalSha256'][role],
                        'Every original initializer reproduction must retain exact outcome and canonical equality.')
                expected_executions=128+len(repros)
                c.require(finished['worlds']==64 and finished['games']==128 and finished['reproductions']==len(repros) and
                    finished['scientificTerminalExecutions']==finished['attemptedGames']==
                    finished['completedGames']==expected_executions,
                    'Every original initializer execution must remain fully accounted.')
                values.extend(rows)
                for name in ('request.json','receipt.json','native/finished.json'):
                    path=folder/name;pins[str(path)]=c.sha(path)
            all_rows[case['id']]=sorted(values,key=lambda row:row['index'])
        controls=[[row['roles']['CONTROL'] for row in all_rows[case['id']]] for case in plan['caseConfigs']]
        c.require(all(value==controls[0] for value in controls[1:]),
            'The original repeated CONTROL records must be byte-equivalent normalized values across all eight cases.')
        for case in plan['caseConfigs']:
            reports[case['id']]=c.aggregate(all_rows[case['id']],
                dict(seeds=plan['banks']['development']['seeds'][:320],opponentStyle='BALANCED'))
        selected=max(plan['caseConfigs'],key=lambda case:reports[case['id']]['wins']['mean'])
    else:
        selected=next(case for case in plan['caseConfigs'] if case['id']=='c06')
    return dict(kind='robust-league-initial-selector-1',complete=True,
        parentProtocolSha256=c.sha(parent_protocol),parentClosedReviewSha256=c.sha(closed_review),
        source88bProtocolSHA=c.sha(parent_protocol),
        rule=ADOPTION_RULE,selectedCase=selected['id'],selector=selected['selector'],
        completeDesktop320Initializer=complete,initializerReports=reports,initializerOutcomePins=pins,
        noPositiveMeanRequirement=True,confirmationReportsOpened=0,qualificationClaimed=False)


class Campaign:
    def __init__(self,protocol,profiles,approval,approval_sha,adoption,output):
        self.protocol=Path(protocol).resolve();self.plan=validate(c.read(protocol));self.protocol_sha=c.sha(protocol)
        self.profile_paths={host:Path(path).resolve() for host,path in profiles.items()}
        self.profiles={host:c.read(path) for host,path in self.profile_paths.items()}
        c.require(set(self.profiles)==set(c.HOSTS),'Both original actual-host profiles are required.')
        approved=c.read(approval)
        c.require(c.sha(approval)==approval_sha and approved['launchAuthorized'] is True and
            approved['protocolSha256']==self.protocol_sha and approved['hostProfiles']==
            {host:c.sha(path) for host,path in self.profile_paths.items()} and
            approved['selectorAdoptionSha256']==c.sha(adoption),
            'Root approval must bind this exact protocol, both frozen hosts and foretold selector adoption.')
        adopted=c.read(adoption)
        c.require(adopted['complete'] is True and adopted['parentProtocolSha256']==self.plan['parentProtocolSha256'] and
            adopted['rule']==ADOPTION_RULE,
            'The selector must follow the prospectively frozen prior development rule.')
        self.selector=adopted['selector']
        self.output=Path(output).resolve();c.require(not self.output.exists(),'An experiment attempt is new-only.')
        self.output.mkdir(parents=True)
        with (self.output/'protocol-copy.json').open('xb') as stream:stream.write(self.protocol.read_bytes())
        started=time.time();deadline=min(started+self.plan['maximumScienceSeconds'],self.plan['globalDeadlineEpoch'])
        c.require(deadline>started,'The original user science window has elapsed.')
        self.clock=dict(kind='robust-league-clock-1',protocolSha256=self.protocol_sha,startedEpoch=started,
            deadlineEpoch=deadline,auditDeadlineEpoch=min(deadline+600,self.plan['globalDeadlineEpoch']+600))
        c.write(self.output/'clock.json',self.clock)
        c.write(self.output/'launch.json',dict(kind='robust-league-launch-1',pid=os.getpid(),processGroup=os.getpgrp(),
            protocolSha256=self.protocol_sha,profileSha256={h:c.sha(p) for h,p in self.profile_paths.items()},
            selectorAdoptionSha256=c.sha(adoption),approvalSha256=approval_sha))
        self.lock=threading.Lock();self.active={};self.cancel=threading.Event();self.interrupted=False
        self.known=0;self.reserved=0;self.handlers={};self.dynamic={};self.receipts={}

    def status(self,**values):
        c.replace_status(self.output/'status.json',dict(kind='robust-league-status-1',updatedEpoch=time.time(),
            knownCompletedTerminalExecutions=self.known,reservedTerminalUpperBound=self.reserved,**values))

    def stop(self,signum=None,_frame=None):
        if signum in (signal.SIGINT,signal.SIGTERM):self.interrupted=True
        self.cancel.set()
        with self.lock:active=list(self.active.items())
        for host,process in active:
            if process.poll() is not None:continue
            process.terminate();profile=self.profiles[host]
            command=[profile['python'],'-E','-s','-B',profile['workerScript'],'--profile',profile['actualProfile'],'--cancel-active']
            if host=='DESKTOP':command=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=5',profile['sshTarget'],*command]
            try:subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=12)
            except (OSError,subprocess.TimeoutExpired):pass

    def request(self,host,body,folder,mode=None):
        deadline=self.clock['auditDeadlineEpoch'] if mode=='verify-numerical' else self.clock['deadlineEpoch']
        c.require(not self.cancel.is_set() and time.time()<deadline,
            'No numerical or game task may start after cancellation or the original deadline.')
        profile=self.profiles[host];folder=Path(folder);folder.mkdir(parents=True)
        request=dict(body,clock=self.clock,profileSha256=c.sha(self.profile_paths[host]))
        c.write(folder/'request.json',request)
        command=[profile['python'],'-E','-s','-B',profile['workerScript'],'--profile',profile['actualProfile']]
        if mode:command.append('--'+mode)
        if host=='DESKTOP':command=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8',profile['sshTarget'],*command]
        process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
        with self.lock:self.active[host]=process
        try:
            stdout,stderr=process.communicate(c.canonical(request),timeout=max(.1,deadline-time.time()+15))
        except BaseException:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            raise
        finally:
            with self.lock:self.active.pop(host,None)
        (folder/'worker-stderr.log').write_bytes(stderr)
        c.require(process.returncode==0,'An original '+str(mode or 'native')+' task failed; preserve its diagnostics, no retry.')
        c.require(len(stdout)<=96*1024**2,'A compact transport exceeded its fixed byte allowance.')
        answer=json.loads(stdout);c.require(answer['complete'] is True,'Only a complete original worker result is admitted.')
        return answer

    def decode(self,files,folder):
        folder=Path(folder)
        for name,item in files.items():
            path=Path(name)
            c.require(not path.is_absolute() and '..' not in path.parts,'Transported members must stay inside their owner.')
            raw=base64.b64decode(item['base64'],validate=True)
            c.require(hashlib.sha256(raw).hexdigest()==item['sha256'],'A transported original artifact changed.')
            target=folder/name;target.parent.mkdir(parents=True,exist_ok=True)
            with target.open('xb') as stream:stream.write(raw)

    def numerical(self,mode,task_id,body,outcomes=None):
        request=dict(kind='robust-league-numeric-task-1',taskId=task_id,mode=mode,request=body)
        if outcomes is not None:request['outcomesValue']=outcomes
        folder=self.output/'numeric'/task_id;answer=self.request('DESKTOP',request,folder,'numeric')
        c.require(answer['kind']=='robust-league-numeric-result-1' and answer['mode']==mode and
            answer['taskId']==task_id,'The numerical response must belong to its exact original operation.')
        self.decode(answer['metadata'],folder/'artifacts')
        receipt=c.read(folder/'artifacts/receipt.json')
        c.require(c.sha(folder/'artifacts/receipt.json')==answer['receipt']['sha256'] and receipt['complete'] is True,
            'The actual numerical receipt must be retained byte exactly.')
        expected={'prepare':'robust-league-preparation-1','population':'robust-league-population-receipt-1',
            'update':'robust-league-update-1','final':'robust-league-final-mean-1'}
        c.require(receipt['kind']==expected[mode] and receipt['recipe']==self.plan['numericRecipe'],
            'The completed numerical receipt must retain this exact operation and six-generation recipe.')
        c.write(folder/'transport.json',{key:value for key,value in answer.items() if key!='metadata'})
        self.receipts[task_id]=dict(subject=answer['receipt'],record=receipt,output=answer['output'],local=folder/'artifacts')
        return self.receipts[task_id]

    def transfer(self,name,base,bundles):
        request=dict(kind='robust-league-archive-task-1',taskId=name,bundleRoot=base,bundles=bundles)
        folder=self.output/'transfers'/name;answer=self.request('DESKTOP',request,folder,'archive')
        c.require(answer['kind']=='robust-league-model-transfer-1','Only canonical original numerical actors may be copied.')
        archive=folder/'models.tar.gz';profile=self.profiles['DESKTOP']
        subprocess.run(['scp','-q',profile['sshTarget']+':'+answer['archive']['path'],str(archive)],
            check=True,timeout=max(1,self.clock['deadlineEpoch']-time.time()))
        c.require(c.sha(archive)==answer['archive']['sha256'],'A canonical model archive changed during transfer.')
        expanded=answer['expandedBytes']
        c.require(shutil.disk_usage(self.output).free>=16*1024**3+expanded,
            'The Mac must retain16GiB free after extracting exact model copies.')
        all_local=sum(path.stat().st_size for path in self.output.parent.rglob('*') if path.is_file())
        c.require(all_local+expanded<4*1024**3,'All this experiment Mac artifacts must fit its4GiB allowance.')
        target=Path(self.profiles['MAC']['root'])/'models'/name
        c.require(not target.exists(),'A consumed model population cannot be overwritten or rebound.')
        target.mkdir(parents=True)
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                path=Path(member.name)
                c.require(member.isfile() and not path.is_absolute() and '..' not in path.parts,
                    'Model transfers contain confined original regular files only.')
            source.extractall(target,filter='data')
        for relative,digest in answer['files'].items():
            c.require(c.sha(target/relative)==digest,'A copied Mac actor differs from its retained Desktop canonical bytes.')
            self.dynamic[str(target/relative)]=digest
        c.write(folder/'receipt.json',dict(answer,macRoot=str(target),macFilesRehashed=len(answer['files']),
            localDuplicateArchiveRemovedAfterVerification=True))
        # The executable model bytes remain on both machines. The duplicate compressed transport
        # stays canonical on Desktop and is removed locally only after every model byte is verified.
        archive.unlink()
        return target

    def native_task(self,host,bank,case,chunk,candidate_only):
        profile=self.profiles[host];seeds=self.plan['banks'][bank]['seeds']
        replays=replay_indices(bank,case.get('member'))
        roles=1 if candidate_only else 2
        count=64;executions=roles*(count+sum(chunk*64<=value<(chunk+1)*64 for value in replays))
        with self.lock:
            self.reserved+=executions
            c.require(self.reserved<=MAXIMUM_EXECUTIONS,'The fixed native execution allowance is exhausted.')
        source=dict(kind='robust-token-chunk-1',protocolSha256=self.protocol_sha,
            inferenceAsset=profile['inferenceAsset'],nomination=profile['nomination'],arena=profile['arena'],
            selector=self.selector,opponentStyle='QUALIFIED' if bank=='homogeneous' else 'BALANCED',
            seeds=seeds,globalStart=chunk*64,count=count,replayIndices=replays,candidateOnly=candidate_only)
        if case.get('bundles') is not None:source['candidateBundle']=case['bundles'][host]
        task_id=f'{bank}/{case["id"]}/chunk-{chunk}';folder=self.output/'chunks'/task_id
        answer=self.request(host,dict(kind='robust-token-worker-task-1',taskId=task_id,source=source),folder)
        c.require(answer['taskId']==task_id and answer['profileSha256']==c.sha(self.profile_paths[host]),
            'Native output must retain its original requested host and once-only task.')
        native=folder/'native';self.decode(answer['files'],native)
        finished=c.read(native/'finished.json');prepared=c.read(native/'prepared.json')
        c.require(finished['sourceSha256']==hashlib.sha256(c.canonical(source)).hexdigest() and
            prepared['sourceSha256']==finished['sourceSha256'] and
            prepared['scienceSha256']==finished['scienceSha256'] and prepared['passed'] is True and
            prepared['newGames']==prepared['modelForwards']==0,
            'The native preparation and completed games must bind exact original source and science bytes.')
        for name,digest in finished['files'].items():c.require(c.sha(native/name)==digest,'An original native member changed.')
        rows=[json.loads(line) for line in (native/'worlds.jsonl').read_text().splitlines()]
        c.validate_chunk_indices(rows,chunk*64,64)
        expected_roles=['CANDIDATE'] if candidate_only else ['CANDIDATE','CONTROL']
        c.require(finished['complete'] is True and finished['roles']==expected_roles and
            prepared['roles']==expected_roles and finished['candidateOnly'] is candidate_only and
            finished['worlds']==64 and finished['games']==64*roles and
            finished['scientificTerminalExecutions']==finished['attemptedGames']==
            finished['completedGames']==finished['newGames']==executions and finished['newFits']==0 and
            finished['qualificationClaimed'] is False,
            'Actual native role and execution accounting must exactly match the fixed original task.')
        reproduction_path=native/'reproductions.jsonl'
        repros=[json.loads(line) for line in reproduction_path.read_text().splitlines()] if reproduction_path.exists() else []
        expected={(index,role) for index in replays if chunk*64<=index<(chunk+1)*64 for role in expected_roles}
        c.require(len(repros)==len(expected) and {(row['index'],row['role']) for row in repros}==expected,
            'Each actual prescribed reproduction must occur exactly once.')
        original={row['index']:row for row in rows}
        c.require(all(row['scienceSha256']==prepared['scienceSha256'] for row in rows),
            'Every compact original world must retain its admitted scientific identity.')
        for row in repros:
            world=original[row['index']];role=row['role']
            c.require(row['matched'] is True and row['terminal']==world['roles'][role] and
                row['diagnostics']==world['diagnostics'][role] and
                row['canonicalSha256']==world['canonicalSha256'][role],
                'Original/reproduction outcomes, diagnostics and canonical histories must agree exactly.')
        c.write(folder/'receipt.json',dict(kind='robust-league-native-receipt-1',complete=True,host=host,
            taskId=task_id,sourceSha256=finished['sourceSha256'],scienceSha256=finished['scienceSha256'],
            finishedSha256=c.sha(native/'finished.json'),terminalExecutions=executions))
        with self.lock:
            self.known+=executions;self.status(status='running',stage=bank,lastTask=task_id)
        return rows

    def cohort(self,bank,cases,candidate_only=False):
        result={case['id']:[] for case in cases};chunks=len(self.plan['banks'][bank]['seeds'])//64
        def run_host(host):
            own={case['id']:[] for case in cases}
            for case in cases:
                for chunk in range(chunks):
                    chosen=owner(case['member'],chunk,int(bank.split('-')[1])) if candidate_only else test_owner(chunk)
                    if chosen==host:own[case['id']].extend(self.native_task(host,bank,case,chunk,candidate_only))
            return own
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(run_host,host) for host in c.HOSTS]
            try:
                for future in as_completed(futures):
                    for case_id,rows in future.result().items():result[case_id].extend(rows)
            except BaseException:self.stop();raise
        for case in cases:
            if bank=='homogeneous':
                # The homogeneous bank deliberately has one unchanged qualified rival ecology.
                ordered=sorted(result[case['id']],key=lambda row:row['index'])
                c.require(len(ordered)==len(self.plan['banks'][bank]['seeds']) and
                    [row['seed'] for row in ordered]==self.plan['banks'][bank]['seeds'] and
                    [row['index'] for row in ordered]==list(range(len(ordered))) and
                    all(row['opponentStyle']=='QUALIFIED' and row['seat']==row['index']%4 and
                        set(row['roles'])=={'CANDIDATE','CONTROL'} for row in ordered),
                    'The complete homogeneous world bank must retain original seeds, seats and qualified rivals.')
                result[case['id']]=ordered
            else:result[case['id']]=check_worlds(result[case['id']],self.plan['banks'][bank]['seeds'],candidate_only)
        return result

    @staticmethod
    def numeric_subject(result,record):
        return dict(path=str(Path(result['output'])/record['path']),sha256=record['sha256'])

    def run(self):
        for event in (signal.SIGINT,signal.SIGTERM):self.handlers[event]=signal.signal(event,self.stop)
        self.status(status='running',stage='numerical-preparation')
        try:
            desktop=self.profiles['DESKTOP']
            prep=self.numerical('prepare','prepare',dict(kind='robust-league-numeric-prepare-1',
                protocol=desktop['protocol'],seedSchedule=desktop['seedSchedule'],baseInferenceAsset=desktop['inferenceAsset']))
            center=self.numeric_subject(prep,prep['record']['center'])
            for generation in range(1,GENERATIONS+1):
                name=f'generation-{generation}'
                population=self.numerical('population',name+'-population',dict(kind='robust-league-numeric-population-1',
                    preparation=prep['subject'],center=center,generation=generation))
                record=c.read(population['local']/'population.json')
                wanted=[member for member in record['members'] if any(owner(member['index'],chunk,generation)=='MAC' for chunk in (0,1))]
                bundles=[self.numeric_subject(population,member['bundle']) for member in wanted]
                mac=self.transfer(name,population['output'],bundles)
                cases=[]
                for member in record['members']:
                    paths={'DESKTOP':self.numeric_subject(population,member['bundle'])}
                    if member in wanted:paths['MAC']=dict(path=str(mac/member['bundle']['path']),sha256=member['bundle']['sha256'])
                    cases.append(dict(id=f'member-{member["index"]:02d}',member=member['index'],bundles=paths))
                self.status(status='running',stage=name)
                rows=self.cohort(name,cases,True)
                outcomes=outcome_matrix(generation,record,{case['member']:rows[case['id']] for case in cases})
                outcomes['populationSha256']=population['record']['population']['sha256']
                c.write(self.output/'generations'/name/'outcomes.json',outcomes)
                updated=self.numerical('update',name+'-update',dict(kind='robust-league-numeric-update-1',
                    preparation=prep['subject'],population=population['subject']),outcomes)
                center=self.numeric_subject(updated,updated['record']['center'])
                c.write(self.output/'generations'/name/'receipt.json',dict(kind='robust-league-generation-complete-1',
                    complete=True,generation=generation,population=population['subject'],update=updated['subject'],
                    center=center,outcomesSha256=c.sha(self.output/'generations'/name/'outcomes.json')))
            final=self.numerical('final','final-mean-6',dict(kind='robust-league-numeric-final-1',preparation=prep['subject'],center=center))
            bundle=self.numeric_subject(final,final['record']['bundle'])
            mac=self.transfer('final-mean-6',final['output'],[bundle])
            final_case=dict(id='final',bundles={'DESKTOP':bundle,'MAC':dict(path=str(mac/final['record']['bundle']['path']),sha256=bundle['sha256'])})
            rows=self.cohort('development',[final_case,dict(id='start')])
            dev=development(rows['final'],rows['start'],self.plan['banks']['development']['seeds'])
            c.write(self.output/'reports/development.json',dev)
            qualified=False;reason='development-win95-refused'
            if dev['passed']:
                for bank in ('mixed','homogeneous'):
                    pairs=self.cohort(bank,[final_case])['final']
                    report=c.aggregate(pairs,dict(seeds=self.plan['banks'][bank]['seeds'],
                        opponentStyle='QUALIFIED' if bank=='homogeneous' else 'BALANCED'))
                    passed=report['wins']['lower99']>0
                    c.write(self.output/'reports'/f'{bank}.json',dict(kind='robust-league-qualification-bank-1',
                        complete=True,bank=bank,statistics=report,win99Passed=passed,qualification=False))
                    reason=bank+'-win99-refused'
                    if not passed:break
                    if bank=='homogeneous':qualified=True;reason='both-independent-win99-passed'
            terminal=dict(kind='robust-league-terminal-1',complete=True,status='completed',qualified=qualified,
                candidate='FINAL_MEAN_6_ONLY',candidateBundle=bundle,selector=self.selector,reason=reason,
                knownCompletedTerminalExecutions=self.known,reservedTerminalUpperBound=self.reserved,
                protocolSha256=self.protocol_sha,finishedEpoch=time.time(),activated=False)
            c.write(self.output/'dynamic-mac-inputs.json',self.dynamic)
            numerical_proof=self.request('DESKTOP',dict(kind='robust-league-original-numerical-audit-1'),
                self.output/'numerical-physical-review','verify-numerical')
            c.write(self.output/'numerical-physical-review/proof.json',numerical_proof)
            import robust_league_audit
            audit=robust_league_audit.verify(self.protocol,self.output,terminal)
            c.write(self.output/'completion-audit.json',audit)
            terminal['completionAuditSha256']=c.sha(self.output/'completion-audit.json')
            c.write(self.output/'terminal.json',terminal);self.status(status='completed')
            return terminal
        except BaseException as error:
            self.stop()
            terminal=dict(kind='robust-league-terminal-1',complete=False,status='interrupted' if self.interrupted else 'failed',
                qualified=False,reason=type(error).__name__+': '+str(error),protocolSha256=self.protocol_sha,
                knownCompletedTerminalExecutions=self.known,reservedTerminalUpperBound=self.reserved,
                finishedEpoch=time.time(),activated=False)
            c.write(self.output/'terminal.json',terminal);self.status(status=terminal['status'],failureReason=terminal['reason'])
            raise
        finally:
            for event,handler in self.handlers.items():signal.signal(event,handler)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('protocol','desktop-profile','mac-profile','approval','selector-adoption','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--approval-sha256',required=True)
    args=parser.parse_args()
    print(json.dumps(Campaign(args.protocol,{'DESKTOP':args.desktop_profile,'MAC':args.mac_profile},
        args.approval,args.approval_sha256,args.selector_adoption,args.output).run(),sort_keys=True))
