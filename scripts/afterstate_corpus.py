"""Complete public origins, opaque labels, and lossless archives before pruning native chunks."""
import gzip
import hashlib
import json
from pathlib import Path
import tarfile

import afterstate_value_data as data
import automata_experiments as exp
import purchase_storage as storage

read,sha,require=exp.read,exp.sha,exp.require


def origin(collection,index,chunk,dictionary,dictionary_sha,manifest):
    state_path=collection/f'states-{index}.json.gz';label_path=collection/f'label-{index}.json'
    with gzip.open(state_path,'rt') as stream:states=json.load(stream)
    files={f'collection/states-{index}.json.gz':sha(state_path),f'collection/label-{index}.json':sha(label_path)}
    _,counts=data.project_states(states,dictionary=dictionary,dictionary_sha=dictionary_sha,
        signature=manifest['signature'],index=index,seed=chunk['seeds'][index],
        source_sha=manifest['sourceSha256'],states_sha=sha(state_path))
    return dict(id=(chunk['index']-1)*256+index,batch=chunk['index'],index=index,
        split=data.split(chunk['index']),gameSeed=chunk['seeds'][index],seat=index%4,files=files,**counts)


def completed_files(directory,games,index):
    directory=Path(directory);collection=directory/'collection';reproduction=directory/'reproduction'
    manifest=read(collection/'manifest.json');finished=read(collection/'finished.json')
    require(finished['kind']==data.VERSION and finished['games']==games
        and finished['signature']==manifest['signature'] and finished['originalStateParity'] is True,
        'Only a complete source-bound afterstate chunk can be archived.')
    expected={}
    for name,digest in finished['artifactSha256'].items():
        require(Path(name).name==name and name not in ('.','..') and sha(collection/name)==digest,
                'A completed native evidence member changed.')
        path=collection/name;expected['collection/'+name]=dict(sha256=digest,size=path.stat().st_size)
    receipt=read(reproduction/'reproduction.json')
    require(receipt['kind']=='afterstate-reproduction-1' and receipt['signature']==manifest['signature']
        and receipt['index']==index and receipt['passed'] is True and receipt['newWorlds']==0
        and receipt['historicalReproductions']==1 and receipt['observerEnabled'] is False
        and receipt['allControlsAndCanonicalExact'] is True
        and receipt['originalFinishedSha256']==sha(collection/'finished.json'),
        'One prescribed completed original must reproduce through the same exact collector.')
    for name in (f'control-{index}.json.gz',f'replay-{index}.json'):
        require(sha(collection/name)==sha(reproduction/name),'Reproduced state/label/canonical bytes differ.')
    for parent in (collection,reproduction):
        for path in parent.iterdir():
            require(path.is_file() and not path.is_symlink(),'Only regular native evidence files are permitted.')
            if path.name=='.DS_Store':continue
            name=str(path.relative_to(directory));expected[name]=dict(sha256=sha(path),size=path.stat().st_size)
    for name in ('collection.log','reproduction.log'):
        path=directory/name
        require(path.is_file() and not path.is_symlink(),'Completed native log is required.')
        expected[name]=dict(sha256=sha(path),size=path.stat().st_size)
    first=[name for i in range(games) for name in (f'collection/states-{i}.json.gz',f'collection/label-{i}.json')]
    require(set(first)<=set(expected),'Every original, including zero-row worlds, remains present.')
    return expected,first+sorted(set(expected)-set(first))


def archive(directory,expected,order):
    """Shared publish/fsync/member verification; delete only verified exact scientific duplicates."""
    directory=Path(directory);target=directory/'evidence.tar.gz';receipt=directory/'storage.json'
    require(not target.exists() and not receipt.exists() and set(order)==set(expected)
        and len(order)==len(expected),'One complete lossless archive attempt only.')
    for name,item in expected.items():
        relative=Path(name);path=directory/relative
        require(not relative.is_absolute() and '..' not in relative.parts and not path.is_symlink()
            and path.is_file() and sha(path)==item['sha256'] and path.stat().st_size==item['size'],
            'Only unchanged regular owned members can enter the archive.')
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as destination:
            for name in order:destination.add(directory/name,arcname=name,recursive=False)
    storage.publish(target,write);digest=sha(target);storage.verify_members(target,expected,digest)
    result=dict(kind='afterstate-chunk-storage-1',archive=target.name,archiveSha256=digest,files=expected,memberOrder=order)
    storage.publish(receipt,lambda stream:stream.write((json.dumps(result,sort_keys=True,indent=2)+'\n').encode()))
    for name,item in expected.items():
        path=directory/name
        require(not path.is_symlink() and sha(path)==item['sha256'],'Evidence changed before verified removal.')
        path.unlink()
    # Incidental Finder metadata is left loose and excluded from scientific identity.
    for name in ('collection','reproduction'):
        path=directory/name
        if path.exists() and not any(path.iterdir()):path.rmdir()
    storage.sync_directory(directory)
    return result
