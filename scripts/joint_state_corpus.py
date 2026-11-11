"""Portable lossless joint data: original paths are provenance, never remote file lookups."""
import gzip
import json
import math
from pathlib import Path
import tarfile

import automata_experiments as exp
import purchase_storage as storage

read, sha, require = exp.read, exp.sha, exp.require
ARCHIVE = 'evidence.tar.gz'
MANIFEST = 'storage.json'
KINDS = ('game', 'joint-state', 'joint-label', 'joint-coverage')


def world_members(index):
    return [f'collection/{kind}-{index}.json' + ('.gz' if kind == 'game' else '') for kind in KINDS]


def origin(collection, index, chunk, dictionary, policy_bindings):
    """Cross-check state-only rows against the retained typed owner; no policy or future simulation."""
    names = world_members(index)
    paths = [collection.parent / name for name in names]
    with gzip.open(paths[0], 'rt') as stream:
        game = json.load(stream)
    state, labels, coverage = [read(path) for path in paths[1:]]
    signature = read(collection / 'collection.json')['signature']
    return origin_records((game,state,labels,coverage),index,chunk,dictionary,policy_bindings,signature,
                          {name:sha(path) for name,path in zip(names,paths)})


def origin_records(records,index,chunk,dictionary,policy_bindings,signature,member_hashes):
    """The same pure state/provenance check for bounded streaming audits of archived worlds."""
    game,state,labels,coverage=records
    require(game['index'] == index and game['gameSeed'] == chunk['seeds'][index]
            and game['controlledSeat'] == index % 4 and game['configuration'] == exp.CONFIGURATION
            and game['signature'] == signature and game['bundleSha256'] == policy_bindings['bundleSha256']
            and game['cardModelSha256'] == policy_bindings['cardModelSha256']
            and game['purchaseModelSha256'] == policy_bindings['purchaseModelSha256'],
            'Joint origin, role or frozen actor identity changed.')
    game_hash = member_hashes[world_members(index)[0]]
    for item in (state, labels, coverage):
        require(item['version'] == 'joint-state-chronology-1' and item['signature'] == signature
                and item['originIndex'] == index and item['sourceGameSha256'] == game_hash
                and item['dictionarySha256'] == dictionary['sha256'],
                'Joint sidecar must bind the exact original game and full dictionary.')
    require(set(state) == {'version','signature','originIndex','sourceGameSha256','dictionarySha256','rows'}
            and set(labels) == {'version','signature','originIndex','sourceGameSha256','dictionarySha256','terminal'}
            and labels['terminal'] == game['terminal'], 'State inputs and terminal labels remain separate.')
    expected=[]
    counts={}
    for phase, key in (('CARD','cardDecisions'),('PURCHASE','purchaseDecisions')):
        counts[phase] = len(game[key])
        for position, row in enumerate(game[key]):
            expected.append(dict(number=row['number'],turn=row['turn'],phase=phase,sourceRow=position,
                stateFeatures={phase.lower()+'.'+name:value for name,value in row['observation']['stateFeatures'].items()}))
    expected.sort(key=lambda row:row['number'])
    require(state['rows'] == expected and len({row['number'] for row in expected}) == len(expected),
            'Chronology must copy exact existing state features in actual decision order.')
    allowed=set(dictionary['metadata']['stateFeatureNames'])
    require(all(set(row['stateFeatures'])<=allowed and all(type(value) in (float,int)
        and math.isfinite(value) and -16<=value<=16 for value in row['stateFeatures'].values())
        for row in expected), 'Joint state dictionary, original units or finite bounds changed.')
    require(coverage['independentWorlds']==1 and coverage['cardDecisions']==counts['CARD']
            and coverage['purchaseDecisions']==counts['PURCHASE'], 'Coverage must preserve empty phases/worlds.')
    return dict(id=256*(chunk['index']-1)+index, originKey=f'chunk-{chunk["index"]:02d}/origin-{index:03d}',
        batch=chunk['index'],index=index,split=chunk['split'],gameSeed=game['gameSeed'],seat=index%4,
        decisions=len(expected),nonzeros=sum(value != 0 for row in expected for value in row['stateFeatures'].values()),
        phaseCounts=counts,sourceGameSha256=game_hash,files=member_hashes)


def completed_files(directory, expected_games, replay_index):
    """Validate original completion/reproduction hashes before any archive or deletion."""
    collection=directory/'collection'; reproduction=directory/'reproduction'
    manifest=read(collection/'collection.json'); finished=read(collection/'finished.json')
    require(manifest['games']==finished['games']==expected_games and
        manifest['signature']==finished['signature']==read(collection/'state.json')['signature']
        and read(collection/'state.json')['phase']=='complete', 'Only complete joint chunks can be archived.')
    for kind,key in zip(KINDS,('gameSha256','jointStateSha256','jointLabelSha256','jointCoverageSha256')):
        hashes=finished[key]
        require(len(hashes)==expected_games, 'Every origin, including zero-row origins, remains in completion.')
        for index,digest in enumerate(hashes):
            name=f'{kind}-{index}.json'+('.gz' if kind=='game' else '')
            require(sha(collection/name)==digest, 'Original joint evidence differs from completion: '+name)
    require(finished['canonicalReplaySha256']=={f'replay-{replay_index}.json':sha(collection/f'replay-{replay_index}.json')}
        and finished['jointCoverageSummarySha256']==sha(collection/'joint-coverage-summary.json'),
        'Canonical/coverage completion binding changed.')
    result=read(reproduction/'reproduction.json'); end=read(reproduction/'finished.json')
    require(result['kind']==end['kind']=='joint-reproduction-1' and result['signature']==end['signature']==manifest['signature']
        and result['replayIndices']==[replay_index] and result['allGamesExact'] is True
        and result['allCanonicalHistoriesExact'] is True and result['newGameWorlds']==0
        and result['historicalTerminalExecutions']==1 and result['collection']==str(collection)
        and end['reportSha256']==sha(reproduction/'reproduction.json'),
        'Exactly the one predeclared canonical origin must reproduce through the same collector.')
    for name in (f'game-{replay_index}.json.gz',f'replay-{replay_index}.json'):
        require(sha(collection/name)==sha(reproduction/name), 'Historical canonical/typed bytes must match exactly.')
    paths=[path for root in (collection,reproduction) for path in root.rglob('*') if path.is_file()]
    paths += [directory/'collection.log',directory/'reproduction.log']
    require(all(path.is_file() and not path.is_symlink() for path in paths), 'Archive only regular native evidence files.')
    require(not any(path.is_symlink() for root in (collection,reproduction) for path in root.rglob('*')),
            'Joint native evidence may not contain symbolic links.')
    expected={str(path.relative_to(directory)):{'sha256':sha(path),'size':path.stat().st_size} for path in paths}
    first=[name for index in range(expected_games) for name in world_members(index)]
    require(set(first)<=set(expected), 'Every origin requires all four portable members.')
    order=first+sorted(set(expected)-set(first))
    return expected,order


def archive(directory, expected, order):
    """Publish+fsync and verify every member before deleting only unchanged original bytes."""
    directory=Path(directory);target=directory/ARCHIVE
    require(not target.exists() and not (directory/MANIFEST).exists() and set(order)==set(expected)
        and len(order)==len(expected), 'One ordered archive attempt only; partial evidence is retained.')
    require(all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in expected),
        'Archive members must be relative paths inside their chunk.')
    require(all(not (directory/name).is_symlink() and sha(directory/name)==item['sha256']
        for name,item in expected.items()), 'Evidence changed before archiving.')
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as destination:
            for name in order:
                destination.add(directory/name,arcname=name,recursive=False)
    storage.publish(target,write)
    digest=sha(target)
    storage.verify_members(target,expected,digest)
    result=dict(kind='joint-state-chunk-storage-1',archive=ARCHIVE,archiveSha256=digest,files=expected,memberOrder=order)
    storage.publish(directory/MANIFEST,lambda stream:stream.write((json.dumps(result,sort_keys=True,indent=2)+'\n').encode()))
    for name,item in expected.items():
        path=directory/name
        require(not path.is_symlink() and sha(path)==item['sha256'],'Evidence changed before verified removal: '+name)
        path.unlink()
    for folder in ('collection','reproduction'):
        for path in sorted((directory/folder).rglob('*'),key=lambda p:len(p.parts),reverse=True):
            if path.is_dir():path.rmdir()
        (directory/folder).rmdir()
    storage.sync_directory(directory)
    return result
