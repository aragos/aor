"""One finite outbound Mac→Desktop handoff; no remote scheduler, retries or credentials.

Both executables inherit the same reviewed original clock. Only immutable fixed publications
are transferred; absence is polled, while a transport error or peer failure ends the worker.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tarfile
import time

import token_paired_campaign_plan as p
import token_paired_storage as s
import purchase_storage as storage

ADDRESS = 'aragos@192.168.86.44'
STAGES = ('iteration-2', 'screen')
PREFIX = 'token-paired'
Clock = p.Clock


def contract_or_default(contract):
    """Only the explicit TOKEN/CPE wrappers supply wire identities; transport is shared."""
    return sys.modules[__name__] if contract is None else contract


class PeerStopped(Exception):
    """The completed coordinator deliberately hit one of the frozen operational gates."""
    def __init__(self, response, *, prefix=PREFIX):
        value=response['terminal']
        p.require(value['kind']==prefix+'-terminal-1' and value['host']=='DESKTOP' and
            value['status']=='stopped' and value['reason'] in ('public-exposure-gate','no-accepted-actor-change')
            and value['completedIterations'] in (0,1), 'Only the exact coordinator operational stop may propagate.')
        self.response=response
        super().__init__('Coordinator stopped at its fixed operational gate.')


def tag(iteration, chunk, screen=False):
    p.require(p.owner(chunk,screen)=='MAC', 'Only fixed Mac-owned chunks use the outbound mailbox.')
    if screen:
        p.require(iteration is None, 'A screen handoff has no training iteration.')
        return f'screen-{chunk}'
    p.integer(iteration,1,2,'Iteration')
    return f'iteration-{iteration}-chunk-{chunk}'


def allowed_tag(value):
    values={tag(i,c) for i in (1,2) for c in p.COLLECTION_CHUNKS['MAC']}
    values|={tag(None,c,True) for c in p.SCREEN_CHUNKS['MAC']}
    p.require(value in values, 'Only the eleven fixed immutable Mac shard handoffs exist.')
    return value


def terminal(root, *, contract=None):
    contract=contract_or_default(contract)
    path=Path(root)/'terminal.json'
    if path.exists():
        value=p.read(path)
        p.require(value['kind']==contract.PREFIX+'-terminal-1' and value['status'] in
            ('completed','stopped','failed'), 'Unknown terminal mailbox identity.')
        return value
    return None


def health(root, *, contract=None):
    contract=contract_or_default(contract)
    ended=contract.terminal(root)
    if ended is not None:return dict(status='terminal',terminal=ended,terminalSubject=p.subject(Path(root)/'terminal.json'))
    failure=Path(root)/'peer-failed.json'
    if failure.exists():return dict(status='terminal',terminal=p.read(failure))
    clock=Path(root)/'clock.json'
    if not clock.exists():return dict(status='absent')
    value=p.read(clock)
    p.require(value['kind']==contract.PREFIX+'-clock-1','The peer requires its original clock identity.')
    owner=Path(root)/'worker-owner.json'
    if not owner.exists() or not contract.worker_alive(p.read(owner)):
        return dict(status='terminal',terminal=dict(status='failed',reason='original-worker-exited'))
    if time.time()>=value['deadlineEpoch']:
        return dict(status='terminal',terminal=dict(status='failed',reason='original-deadline'))
    return dict(status='active',protocolSha256=value['protocolSha256'],deadlineEpoch=value['deadlineEpoch'])


def worker_alive(owner, *, contract=None):
    """Read only the recorded worker PID/PGID; a stale clock cannot keep a peer alive."""
    contract=contract_or_default(contract)
    p.require(owner['kind']==contract.PREFIX+'-worker-owner-1' and
        type(owner['pid']) is int and owner['pid']>1 and
        type(owner['processGroup']) is int and owner['processGroup']>1,
        'Worker ownership requires explicit positive original PID and process group.')
    try:
        if os.getpgid(owner['pid'])!=owner['processGroup']:return False
    except ProcessLookupError:return False
    result=subprocess.run(['/bin/ps','-p',str(owner['pid']),'-o','stat='],
        capture_output=True,text=True,timeout=5,check=False)
    return result.returncode==0 and bool(result.stdout.strip()) and not result.stdout.strip().startswith('Z')


def notify_failure(root, name, *, contract=None):
    contract=contract_or_default(contract)
    p.require(name=='MAC','Only the fixed outbound Mac worker can notify this peer slot.')
    path=Path(root)/'peer-failed.json'
    if path.exists():return p.read(path)
    result=dict(kind=contract.PREFIX+'-peer-failure-1',host='MAC',status='failed',updatedEpoch=time.time())
    s.publish_json(path,result)
    return result


def stage(root, name, *, contract=None):
    contract=contract_or_default(contract)
    p.require(name in contract.STAGES, 'The Mac worker waits only for its fixed published stages.')
    lifecycle=contract.health(root)
    if lifecycle['status']=='terminal':return lifecycle
    path=Path(root)/'publications'/name/'publication.json'
    if not path.exists():return dict(status='absent')
    value=p.read(path)
    p.require(value['kind']==contract.PREFIX+'-publication-1' and value['stage']==name,
        'Immutable stage publication has the wrong identity.')
    return dict(status='ready',publication=value,subject=p.subject(path))


def reserve(root, name, *, contract=None):
    contract=contract_or_default(contract)
    contract.allowed_tag(name)
    p.require(contract.health(root)['status']=='active', 'A completed, expired or unstarted coordinator cannot accept new work.')
    directory=Path(root)/'incoming'/name
    directory.mkdir(parents=True,exist_ok=False)
    return dict(directory=str(directory.resolve()))


def receipt_tag(record):
    screen=record['kind']=='token-paired-screen-chunk-receipt-1'
    return tag(None if screen else record['iteration'],record['chunk'],screen)


def accept(root, name, *, contract=None):
    """Receiver verifies original archive members before publishing a once-only completion marker."""
    contract=contract_or_default(contract)
    contract.allowed_tag(name);directory=Path(root)/'incoming'/name
    p.require(contract.health(root)['status']=='active','An ended coordinator cannot admit another transferred shard.')
    p.require({x.name for x in directory.iterdir()}=={'evidence.tar.gz','receipt.json'},
        'A fixed shard transfer must contain exactly its archive and immutable receipt.')
    record=p.read(directory/'receipt.json')
    p.require(record['archive']=='evidence.tar.gz' and record['kind'] in
        (contract.PREFIX+'-chunk-receipt-1',contract.PREFIX+'-screen-chunk-receipt-1'),
        'The receiver accepts only completed native chunk evidence.')
    p.require(contract.receipt_tag(record)==name and
        record['host']=='MAC', 'Remote receipt ownership does not match its fixed handoff slot.')
    s.verify_archive(directory/'evidence.tar.gz',record)
    p.require(contract.health(root)['status']=='active','The original worker must still be active after member verification.')
    result=dict(kind=contract.PREFIX+'-handoff-1',tag=name,receipt=p.subject(directory/'receipt.json'),
        archive=p.subject(directory/'evidence.tar.gz'))
    s.publish_json(directory/'accepted.json',result)
    return result


def serve(mode,root,name, *, contract=None):
    """A remote helper cannot outlive its bounded SSH request or renew the scientific clock."""
    contract=contract_or_default(contract);seconds=60.
    if mode in ('reserve','accept'):
        clock=p.read(Path(root)/'clock.json')
        p.require(clock['kind']==contract.PREFIX+'-clock-1','A transfer requires the original clock.')
        seconds=min(seconds,contract.Clock(clock['startedEpoch'],clock['deadlineEpoch']).remaining(time.time()))
    def expired(_number,_frame):raise TimeoutError('The bounded immutable handoff request expired.')
    old=signal.signal(signal.SIGALRM,expired)
    signal.setitimer(signal.ITIMER_REAL,seconds)
    try:
        return contract.health(root) if mode=='health' else {
            'probe':contract.stage,'reserve':contract.reserve,'accept':contract.accept,
            'fail':contract.notify_failure}[mode](root,name)
    finally:
        signal.setitimer(signal.ITIMER_REAL,0)
        signal.signal(signal.SIGALRM,old)


def bundle(directory, name, files, identity, *, contract=None):
    """Publish a small portable model/admission closure, preserving every source byte."""
    contract=contract_or_default(contract)
    p.require(name in contract.STAGES, 'Only the declared actor/public admission stages are published.')
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=False)
    rows={}
    for member,source in files.items():
        s.safe(member);path=p.checked_subject(source)
        rows[member]=dict(sha256=source['sha256'],size=path.stat().st_size)
    maximum=getattr(contract,'MAXIMUM_PUBLICATION_BYTES',256*1024**2)
    p.require(type(maximum) is int and 0<maximum<=1024**3 and rows and
        sum(row['size'] for row in rows.values())<=maximum,
        'Model/admission publication exceeds its fixed byte bound; raw corpus archives do not belong here.')
    archive=directory/'publication.tar.gz'
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as out:
            for member,source in files.items():out.add(source['path'],arcname=member,recursive=False)
    storage.publish(archive,write)
    storage.verify_members(archive,rows,p.file_sha(archive))
    record=dict(kind=contract.PREFIX+'-publication-1',stage=name,archive=archive.name,
        archiveSha256=p.file_sha(archive),files=rows,identity=identity)
    s.publish_json(directory/'publication.json',record)
    return record


def unpack(archive, record, directory):
    directory=Path(directory)
    for member in record['files']:s.safe(member)
    storage.verify_members(Path(archive),record['files'],record['archiveSha256'])
    directory.mkdir(parents=True,exist_ok=False)
    with tarfile.open(archive,'r:gz') as source:
        for member in source:
            target=directory/member.name;target.parent.mkdir(parents=True,exist_ok=True)
            with source.extractfile(member) as content:
                storage.publish(target,lambda destination: __import__('shutil').copyfileobj(content,destination),
                    record['files'][member.name]['sha256'])
    p.require(s.inventory(directory)==record['files'],'Unpacked portable publication must be byte-exact.')


class Outbound:
    """Only Mac instantiates this client. Every SSH/SCP call runs once with a finite timeout."""
    def __init__(self, profile, clock, local_root, *, runner=subprocess.run, now=time.time, sleep=time.sleep,
            contract=None):
        p.require(profile['address']==ADDRESS and Path(profile['root']).is_absolute() and
            Path(profile['python']).is_absolute() and Path(profile['script']).is_absolute(),
            'Use only the predeclared research Desktop endpoint and absolute reviewed helper paths.')
        self.profile=profile;self.clock=clock;self.local=Path(local_root)
        self.runner=runner;self.now=now;self.sleep=sleep
        self.contract=contract_or_default(contract)

    def call(self, arguments, timeout=60):
        remaining=self.clock.remaining(self.now())
        result=self.runner(arguments,capture_output=True,timeout=min(timeout,remaining),check=False)
        p.require(result.returncode==0,'The fixed research transfer failed; no network/scientific retry.')
        p.require(len(result.stdout)<=2*1024**2,'Remote handoff output exceeded its compact receipt bound.')
        return result.stdout

    def remote(self, mode, name):
        command=[self.profile['python'],'-B',self.profile['script'],mode,'--root',self.profile['root'],'--name',name]
        result=self.call(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10',ADDRESS,
            ' '.join(shlex.quote(value) for value in command)])
        return json.loads(result)

    def push(self, archive, receipt, iteration, chunk, screen=False, **identity):
        name=self.contract.tag(iteration,chunk,screen,**identity);record=p.read(receipt)
        s.verify_archive(archive,record)
        target=self.remote('reserve',name)['directory']
        p.require(target==str(Path(self.profile['root'])/'incoming'/name),
            'Remote reservation cannot redirect a research artifact.')
        for source,destination in ((archive,'evidence.tar.gz'),(receipt,'receipt.json')):
            self.call(['scp','-q','-o','BatchMode=yes','-o','ConnectTimeout=10',str(Path(source).resolve()),
                ADDRESS+':'+shlex.quote(target+'/'+destination)],timeout=600)
        return self.remote('accept',name)

    def await_active(self,protocol_sha):
        """A launched Desktop may still be hashing inputs; absence never starts a native game."""
        while True:
            result=self.remote('health','MAC')
            if result['status']=='absent':
                self.sleep(min(10,self.clock.remaining(self.now())))
                continue
            if result['status']=='terminal' and result['terminal'].get('status')=='stopped':
                raise self.contract.PeerStopped(result)
            p.require(result['status']=='active' and result['protocolSha256']==protocol_sha and
                result['deadlineEpoch']==self.clock.deadline,
                'The original Desktop must be active on the same protocol and clock before Mac games.')
            return result

    def await_stage(self, name):
        p.require(name in self.contract.STAGES,'Only the fixed next actor/screen stages may be awaited.')
        while True:
            p.require(self.contract.terminal(self.local) is None,'Local terminal state ends the Mac worker.')
            result=self.remote('probe',name)
            p.require(result['status'] in ('absent','ready','terminal'),'Unknown peer lifecycle status.')
            if result['status']=='terminal':
                if result['terminal'].get('status')=='stopped':raise self.contract.PeerStopped(result)
                raise ValueError('Coordinator ended; no further Mac native stage is authorized.')
            if result['status']=='ready':return result
            self.sleep(min(10,self.clock.remaining(self.now())))

    def fetch(self, name, ready, target):
        p.require(name in self.contract.STAGES and ready['status']=='ready' and
            ready['publication']['stage']==name, 'Download only the exact admitted stage publication.')
        record=ready['publication'];target=Path(target)
        p.require(not target.exists(),'A stage download cannot overwrite a previous attempted handoff.')
        target.mkdir(parents=True)
        remote=Path(self.profile['root'])/'publications'/name/'publication.tar.gz'
        archive=target/'publication.tar.gz'
        self.call(['scp','-q','-o','BatchMode=yes','-o','ConnectTimeout=10',
            ADDRESS+':'+shlex.quote(str(remote)),str(archive)],timeout=600)
        unpack(archive,record,target/'content')
        s.publish_json(target/'publication.json',record)
        return target/'content'


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('probe','reserve','accept','health','fail'))
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--name',required=True)
    args=parser.parse_args()
    result=serve(args.mode,args.root,args.name)
    print(json.dumps(result))
