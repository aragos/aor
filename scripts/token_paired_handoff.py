"""One finite outbound Mac→Desktop handoff; no remote scheduler, retries or credentials.

Both executables inherit the same reviewed original clock. Only immutable fixed publications
are transferred; absence is polled, while a transport error or peer failure ends the worker.
"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import tarfile
import time

import token_paired_campaign_plan as p
import token_paired_storage as s
import purchase_storage as storage

ADDRESS = 'aragos@192.168.86.44'
STAGES = ('iteration-2', 'screen')


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


def terminal(root):
    path=Path(root)/'terminal.json'
    if path.exists():
        value=p.read(path)
        p.require(value['kind']=='token-paired-terminal-1' and value['status'] in
            ('completed','stopped','failed'), 'Unknown terminal mailbox identity.')
        return value
    return None


def stage(root, name):
    p.require(name in STAGES, 'The Mac worker waits only for π1 or the final screen admission.')
    ended=terminal(root)
    if ended is not None:return dict(status='terminal',terminal=ended)
    path=Path(root)/'publications'/name/'publication.json'
    if not path.exists():return dict(status='absent')
    value=p.read(path)
    p.require(value['kind']=='token-paired-publication-1' and value['stage']==name,
        'Immutable stage publication has the wrong identity.')
    return dict(status='ready',publication=value,subject=p.subject(path))


def reserve(root, name):
    allowed_tag(name)
    p.require(terminal(root) is None, 'A completed or failed coordinator cannot accept new work.')
    directory=Path(root)/'incoming'/name
    directory.mkdir(parents=True,exist_ok=False)
    return dict(directory=str(directory.resolve()))


def accept(root, name):
    """Receiver verifies original archive members before publishing a once-only completion marker."""
    allowed_tag(name);directory=Path(root)/'incoming'/name
    p.require({x.name for x in directory.iterdir()}=={'evidence.tar.gz','receipt.json'},
        'A fixed shard transfer must contain exactly its archive and immutable receipt.')
    record=p.read(directory/'receipt.json')
    p.require(record['archive']=='evidence.tar.gz' and record['kind'] in
        ('token-paired-chunk-receipt-1','token-paired-screen-chunk-receipt-1'),
        'The receiver accepts only completed native chunk evidence.')
    screen=record['kind']=='token-paired-screen-chunk-receipt-1'
    p.require(tag(None if screen else record['iteration'],record['chunk'],screen)==name and
        record['host']=='MAC', 'Remote receipt ownership does not match its fixed handoff slot.')
    s.verify_archive(directory/'evidence.tar.gz',record)
    result=dict(kind='token-paired-handoff-1',tag=name,receipt=p.subject(directory/'receipt.json'),
        archive=p.subject(directory/'evidence.tar.gz'))
    s.publish_json(directory/'accepted.json',result)
    return result


def bundle(directory, name, files, identity):
    """Publish a small portable model/admission closure, preserving every source byte."""
    p.require(name in STAGES, 'Only the two declared actor/public admission stages are published.')
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=False)
    rows={}
    for member,source in files.items():
        s.safe(member);path=p.checked_subject(source)
        rows[member]=dict(sha256=source['sha256'],size=path.stat().st_size)
    p.require(rows and sum(row['size'] for row in rows.values())<=256*1024**2,
        'Model/admission publication is bounded to256MiB; raw corpus archives do not belong here.')
    archive=directory/'publication.tar.gz'
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as out:
            for member,source in files.items():out.add(source['path'],arcname=member,recursive=False)
    storage.publish(archive,write)
    storage.verify_members(archive,rows,p.file_sha(archive))
    record=dict(kind='token-paired-publication-1',stage=name,archive=archive.name,
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
    def __init__(self, profile, clock, local_root, *, runner=subprocess.run, now=time.time, sleep=time.sleep):
        p.require(profile['address']==ADDRESS and Path(profile['root']).is_absolute() and
            Path(profile['python']).is_absolute() and Path(profile['script']).is_absolute(),
            'Use only the predeclared research Desktop endpoint and absolute reviewed helper paths.')
        self.profile=profile;self.clock=clock;self.local=Path(local_root)
        self.runner=runner;self.now=now;self.sleep=sleep

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

    def push(self, archive, receipt, iteration, chunk, screen=False):
        name=tag(iteration,chunk,screen);record=p.read(receipt)
        s.verify_archive(archive,record)
        target=self.remote('reserve',name)['directory']
        p.require(target==str(Path(self.profile['root'])/'incoming'/name),
            'Remote reservation cannot redirect a research artifact.')
        for source,destination in ((archive,'evidence.tar.gz'),(receipt,'receipt.json')):
            self.call(['scp','-q','-o','BatchMode=yes','-o','ConnectTimeout=10',str(Path(source).resolve()),
                ADDRESS+':'+shlex.quote(target+'/'+destination)],timeout=600)
        return self.remote('accept',name)

    def await_stage(self, name):
        p.require(name in STAGES,'Only the fixed next actor/screen stages may be awaited.')
        while True:
            p.require(terminal(self.local) is None,'Local terminal state ends the Mac worker.')
            result=self.remote('probe',name)
            p.require(result['status'] in ('absent','ready','terminal'),'Unknown peer lifecycle status.')
            if result['status']=='terminal':raise ValueError('Coordinator ended; no further Mac native stage is authorized.')
            if result['status']=='ready':return result
            self.sleep(min(10,self.clock.remaining(self.now())))

    def fetch(self, name, ready, target):
        p.require(name in STAGES and ready['status']=='ready' and
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
    parser.add_argument('mode',choices=('probe','reserve','accept'))
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--name',required=True)
    args=parser.parse_args()
    print(json.dumps({'probe':stage,'reserve':reserve,'accept':accept}[args.mode](args.root,args.name)))
