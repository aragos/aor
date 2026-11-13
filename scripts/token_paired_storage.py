"""Lossless fixed-shard receipts and public-only corpus admission for family79.

Raw origins and label sidecars are hashed as bytes during collection admission. Only the public
sidecar is decoded here. In particular an archive hash check is not semantic validation access.
"""
import json
from pathlib import Path, PurePosixPath
import tarfile

import purchase_storage as storage
import token_paired_campaign_plan as p


def safe(name):
    require = p.require
    require(isinstance(name, str) and name and '\\' not in name, 'Invalid evidence member name.')
    path = PurePosixPath(name)
    require(not path.is_absolute() and '..' not in path.parts and str(path) == name,
        'Evidence members must be normalized relative paths.')
    return name


def publish_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    storage.publish(path, lambda stream: stream.write(p.canonical(value)+b'\n'))
    return p.subject(path)


def inventory(root):
    root = Path(root); result = {}
    p.require(root.is_dir() and not root.is_symlink(), 'Completed native output must be a real directory.')
    for path in sorted(root.rglob('*')):
        p.require(not path.is_symlink(), 'Scientific evidence cannot traverse symlinks.')
        if path.is_file() and path.name != '.DS_Store':
            result[safe(path.relative_to(root).as_posix())] = dict(
                sha256=p.file_sha(path), size=path.stat().st_size)
    return result


def portable(value):
    if isinstance(value, dict):
        return {key:portable(item) for key,item in value.items() if key not in ('path','originalPath')}
    if isinstance(value, list):
        return [portable(item) for item in value]
    return value


def prepared(plan, native, source, source_sha, files, chunk, iteration, current_sha):
    expected_range = dict(globalStart=chunk*64, count=64)
    p.require(native['kind'] == 'token-paired-collect-1' and native['iteration'] == iteration and
        native['range'] == expected_range and native['source']['sha256'] == source_sha and
        native['source']['snapshot'] == 'source.json', 'Native preparation has a different request/source.')
    p.require(native['origins'] == plan.record['iterations'][iteration-1]['origins'] and
        native['requests'] == [p.request(index) for index in p.indices(chunk)] and
        native['replayIndices'] == list(p.COLLECTION_REPLAYS) and
        native['ownedReplayIndices'] == list(p.replays(chunk)),
        'Native global streams, request schedule or historical indices changed.')
    p.require(native['currentActor']['sha256'] == current_sha and
        native['originalActor']['sha256'] == plan.record['originalActorSha256'] and
        native['dictionary']['sha256'] == plan.record['dictionarySha256'] and
        p.digest(portable(native['policyBindings'])) == plan.record['ecologySha256'],
        'Prepared actor, dictionary or complete frozen ecology differs from the scientific protocol.')
    for key, field in (('arena','arena'),('currentActor','currentActor'),('originalActor','originalActor')):
        p.require(native[key]['sha256'] == source[field]['sha256'], 'Source and snapshot subject disagree.')
    snapshots = native['snapshots']
    p.require(snapshots and all(safe(name) in files and files[name]['sha256'] == digest
        for name,digest in snapshots.items()), 'Every native source/model/ecology snapshot must remain exact.')
    p.require(files['source.json']['sha256'] == source_sha and
        files[native['dictionary']['path']]['sha256'] == native['dictionary']['sha256'],
        'Dictionary and raw source snapshots must be bound by their original raw bytes.')
    for value in (native['signature'],native['scienceSha256'],native['scopeSha256']):p.sha(value)


def public_record(value, receipt, native, plan, index):
    p.require(set(value) == {'kind','signature','scopeSha256','scienceSha256','iteration','request','streams',
        'status','originSha256','missingReason','publicRoot','anchors'},
        'Public evidence must exclude authority, canonical history and all outcomes.')
    p.require(value['kind'] == 'token-paired-public-1' and
        value['signature'] == native['signature'] and value['scienceSha256'] == native['scienceSha256'] and
        value['scopeSha256'] == native['scopeSha256'] and
        value['iteration'] == native['iteration'] and value['request'] == p.request(index) and
        value['streams'] == plan.record['iterations'][native['iteration']-1]['origins'][index] and
        value['originSha256'] == receipt['origin']['sha256'] and value['status'] == receipt['status'],
        'Public evidence is not the exact original request and complete-origin subject.')
    status=value['status'];p.require(status in ('READY','SINGLETON','MISSING'), 'Unknown root status.')
    root=value['publicRoot']
    p.require((root is None) == (status == 'MISSING'), 'Only a missing root lacks public decision evidence.')
    if root is not None:
        actions=root['observation']['actions'];count=len(actions)
        p.require(1 <= count <= 75 and (count > 1) == (status == 'READY'),
            'Public root status must match its full offered support.')
        p.require(root['referenceIndex'] == root['observation']['baselineIndex'] and
            type(root['sampledCommandIndex']) is int and 0 <= root['sampledCommandIndex'] < count,
            'Reference and actual sampled command must remain separate exact indices.')
        indices=root['proposedIndices'];size=min(8,count-1)
        p.require(len(indices)==size+1 and len(set(indices))==len(indices) and
            indices[0]==root['referenceIndex'] and all(type(i) is int and 0<=i<count for i in indices) and
            root['inclusionProbability']==(size/(count-1) if count>1 else 0.),
            'Uniform-subset indices and original inclusion probability must remain exact.')
    p.require(isinstance(value['anchors'],list) and (not value['anchors'] or
        (native['iteration']==1 and index<512)), 'Only first-iteration TRAIN backbones supply anchors.')
    return status


def verify_collection(plan, root, source, chunk, iteration, current_sha, replay_verifier):
    """Only public inputs are decoded; the supplied pure owner checks same-factory replay receipts."""
    root=Path(root);files=inventory(root)
    native=p.read(root/'prepared.json');completed=p.read(root/'finished.json')
    source_sha=p.file_sha(source);source_value=p.read(source)
    prepared(plan,native,source_value,source_sha,files,chunk,iteration,current_sha)
    p.require(completed['kind']=='token-paired-finished-1' and completed['complete'] is True and
        completed['signature']==native['signature'] and completed['scienceSha256']==native['scienceSha256'] and
        completed['iteration']==iteration and completed['range']==native['range'],
        'Only a complete original shard may be archived or admitted to learning.')
    rows=completed['origins'];p.require([r['globalIndex'] for r in rows]==list(p.indices(chunk)),
        'Every original global index must occur exactly once in canonical order.')
    statuses=[];tails=0;public_files=[]
    for row in rows:
        index=row['globalIndex']
        for key,name in (('origin',f'origin-{index}.json.gz'),('public',f'public-{index}.json'),
                         ('labels',f'labels-{index}.json')):
            item=row[key]
            p.require(item['file']==name and files[name]['sha256']==item['sha256'],
                'Complete origin/public/label raw subjects must match before any decoding.')
        value=p.read(root/row['public']['file'])
        status=public_record(value,row,native,plan,index);statuses.append(status)
        expected=2*len(value['publicRoot']['proposedIndices']) if status=='READY' else 0
        p.require(row['tailGames']==expected and row['anchorRows']==len(value['anchors']),
            'Actual independent backbone/tail/anchor counts changed.')
        tails+=expected;public_files.append(row['public']['file'])
    p.require({name for name in files if name.startswith(('origin-','public-','labels-'))}==
        {row[key]['file'] for row in rows for key in ('origin','public','labels')},
        'No missing, duplicate or unexpected origin sidecar may enter the corpus.')
    p.require(completed['backboneGames']==64 and completed['tailGames']==tails and
        completed['scientificTerminalGames']==64+tails and
        completed['readyRoots']==statuses.count('READY') and
        completed['singletonRoots']==statuses.count('SINGLETON') and
        completed['missingRoots']==statuses.count('MISSING') and
        completed['capturedRoots']==64-statuses.count('MISSING'), 'Complete native counts must be exact.')
    replay_receipts=[replay_verifier(root,index,native,rows[index-chunk*64]) for index in p.replays(chunk)]
    return dict(kind='token-paired-chunk-receipt-1',iteration=iteration,chunk=chunk,host=p.owner(chunk),
        range=native['range'],signature=native['signature'],scopeSha256=native['scopeSha256'],scienceSha256=native['scienceSha256'],
        scienceIdentity=native['scienceIdentity'],sourceSha256=source_sha,
        preparedSha256=files['prepared.json']['sha256'],finishedSha256=files['finished.json']['sha256'],
        currentActorSha256=current_sha,originalActorSha256=plan.record['originalActorSha256'],
        origins=rows,readyRoots=completed['readyRoots'],singletonRoots=completed['singletonRoots'],
        missingRoots=completed['missingRoots'],scientificTerminalGames=64+tails,
        historicalReproductions=len(replay_receipts),replays=replay_receipts,
        files=files,semanticValidationLabelsDecoded=False)


def archive_collection(root, target, receipt):
    """One durable archive, every member verified, then prune only the exact owned originals."""
    root,target=Path(root),Path(target)
    p.require(inventory(root)==receipt['files'], 'Native files changed after completed admission.')
    target.parent.mkdir(parents=True,exist_ok=True)
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as archive:
            for name in receipt['files']:archive.add(root/name,arcname=name,recursive=False)
    storage.publish(target,write)
    record=dict(receipt,archive=target.name,archiveSha256=p.file_sha(target))
    storage.verify_members(target,record['files'],record['archiveSha256'])
    publish_json(target.with_name(target.name+'.json'),record)
    p.require(inventory(root)==record['files'], 'Native files changed before verified pruning.')
    for name in record['files']:(root/name).unlink()
    for path in sorted(root.rglob('*'),key=lambda x:len(x.parts),reverse=True):
        if path.is_dir():path.rmdir()
        else:
            p.require(path.name=='.DS_Store' and not path.is_symlink(), 'Unexpected remaining evidence.')
            path.unlink()
    root.rmdir();storage.sync_directory(root.parent)
    return record


def verify_archive(archive, receipt):
    archive=Path(archive)
    p.require(archive.name==receipt['archive'], 'Archive basename changed.')
    for name in receipt['files']:safe(name)
    storage.verify_members(archive,receipt['files'],receipt['archiveSha256'])


def public_members(archive, receipt):
    """Read one public origin at a time; labels and opaque full worlds never enter the JSON parser."""
    verify_archive(archive,receipt)
    allowed={row['public']['file'] for row in receipt['origins']};seen=set()
    with tarfile.open(archive,'r:gz') as source:
        for member in source:
            if member.name in allowed:
                seen.add(member.name)
                with source.extractfile(member) as stream:yield json.load(stream)
    p.require(seen==allowed, 'Every admitted public sidecar must remain present.')


def iteration_corpus(plan, iteration, chunk_subjects):
    """Bind both immutable host contributions before exposing TRAIN labels or any learner input."""
    p.integer(iteration,1,2,'Iteration')
    p.require(len(chunk_subjects)==10, 'A corpus requires all ten completed fixed chunks.')
    rows=[]
    for item in chunk_subjects:
        path=p.checked_subject(item);row=p.read(path)
        archive=path.with_name(row['archive']);verify_archive(archive,row)
        rows.append(row)
    rows.sort(key=lambda row:row['chunk'])
    p.require([row['chunk'] for row in rows]==list(range(10)) and
        all(row['iteration']==iteration and row['host']==p.owner(row['chunk']) for row in rows),
        'Only the original globally complete two-host iteration is admitted.')
    p.require(len({row['scienceSha256'] for row in rows})==1 and len({row['scopeSha256'] for row in rows})==1 and
        len({p.digest(row['scienceIdentity']) for row in rows})==1 and
        len({row['currentActorSha256'] for row in rows})==1 and
        all(row['originalActorSha256']==plan.record['originalActorSha256'] for row in rows),
        'Host-local raw signatures may differ; all portable science/actor identities must match.')
    counts={split:sum(row['readyRoots'] for row in rows if
        ('TRAIN' if row['chunk']<8 else 'VALIDATION')==split) for split in ('TRAIN','VALIDATION')}
    p.require(sum(row['historicalReproductions'] for row in rows)==4,
        'The four prescribed backbone reproductions must run exactly once globally.')
    return dict(kind='token-paired-corpus-1',iteration=iteration,protocolDigest=plan.sha256,
        scopeSha256=rows[0]['scopeSha256'],scienceSha256=rows[0]['scienceSha256'],scienceIdentity=rows[0]['scienceIdentity'],
        chunks=chunk_subjects,currentActorSha256=rows[0]['currentActorSha256'],
        originalActorSha256=plan.record['originalActorSha256'],ready=counts,
        exposurePassed=counts['TRAIN']>=256 and counts['VALIDATION']>=64,
        scientificTerminalGames=sum(row['scientificTerminalGames'] for row in rows),
        historicalReproductions=4,semanticValidationLabelsDecoded=False)
