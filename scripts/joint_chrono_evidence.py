"""Pure chunk identities, streamed native verification and lossless60 evidence storage."""
import json
from enum import Enum
from pathlib import Path, PurePosixPath
import tarfile

import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import joint_credit_trial_data as joint
import purchase_ecology_data as ecology
import purchase_storage as storage

read, sha, require = exp.read, exp.sha, exp.require
CHUNK_PAIRS, STAGE_PAIRS, CHUNKS = 512, 4096, 8
REPLAYS = (0,1365,2730,4095)
STAGES = ('confirm','heldout')
IDENTITY = ('index','configuration','seed','seat','opponentIndices')


class JointRivalProfile(Enum):
    G3_SUCCESSOR_LEAGUE = 'g3-successor-league-1'


def rival_profile(protocol):
    """Omission retains60 exactly; the explicit successor profile owns its finite role map."""
    if 'jointRivalProfile' not in protocol:
        return None
    require(protocol['jointRivalProfile'] == JointRivalProfile.G3_SUCCESSOR_LEAGUE.value,
            'Unsupported explicit joint rival profile.')
    bundles = protocol.get('rivalBundles')
    require(isinstance(bundles, dict) and set(bundles) == {'C64', 'G1', 'G2', 'G3'}
            and all(isinstance(path, str) and path for path in bundles.values()),
            'The G3 successor league requires exactly four explicit frozen rival bundle paths.')
    return JointRivalProfile.G3_SUCCESSOR_LEAGUE


def shared_bundle(protocol, stage, chunk_index):
    """One common native rival bundle per chunk; no per-seat JVM API is introduced."""
    require(stage in STAGES and type(chunk_index) is int and 0 <= chunk_index < CHUNKS,
            'A shared rival must belong to one declared stage/chunk.')
    if rival_profile(protocol) is None:
        return protocol['anchorBundle']
    role = ('C64', 'G1', 'G2', 'G3')[chunk_index % 4] if stage == 'confirm' else 'G3'
    return protocol['rivalBundles'][role]


def overlay_ecology(protocol, stage):
    require(stage in STAGES, 'Only the declared confirmation and heldout stages exist.')
    return (rival_ecology(stage) if rival_profile(protocol) is None
            else ecology.ArenaRivalEcology.HOMOGENEOUS)


def rival_ecology(stage):
    require(stage in STAGES, 'Only the declared confirmation and heldout stages exist.')
    return (ecology.ArenaRivalEcology.MIXED if stage == 'confirm'
            else ecology.ArenaRivalEcology.HOMOGENEOUS)


def replay_slot(global_index):
    require(type(global_index) is int and global_index in REPLAYS,
            'Replay only the four prospectively declared global pairs, both sides.')
    return divmod(global_index,CHUNK_PAIRS)


def source(protocol, stage, chunk_index):
    """Native indices stay local; offsets preserve the global4096-world/seat/role scheduler."""
    rivals = overlay_ecology(protocol, stage)
    require(type(chunk_index) is int and 0 <= chunk_index < CHUNKS, 'Use chunk indices0..7.')
    bank = protocol['banks'][stage]
    require(bank['count'] == STAGE_PAIRS and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'],STAGE_PAIRS),
            'Each stage requires its exact pre-reserved4096-world bank.')
    offset = bank['nativeOffset']+chunk_index*CHUNK_PAIRS*8191
    require(0 <= offset <= 100000000, 'Chunk offset must remain inside the unchanged native range.')
    result = exp.arena_source(protocol['ordinary'],[protocol['ordinary']]*4,offset,CHUNK_PAIRS,
                              purpose='CONFIRMATION')
    return dict(result,experimentalRulesTransfer=legacy.RULES_TRANSFER,
                **ecology.bindings(protocol['expansion'],arena=True,rival_ecology=rivals),
                jointPolicy=dict(baseline=protocol['prior'],sharedBundle=shared_bundle(protocol,stage,chunk_index),
                    candidateBundle=protocol['candidateBundle'],incumbentBundle=protocol['anchorBundle']))


def source_slot(protocol, document):
    matches = [(stage,index) for stage in STAGES for index in range(CHUNKS)
               if document == source(protocol,stage,index)]
    require(len(matches) == 1, 'Source must match exactly one frozen stage/chunk and all actor roles.')
    return matches[0]


def verify_roles(protocol, source_path, directory, reports):
    document = read(source_path)
    stage,chunk = source_slot(protocol,document)
    expected = joint.joint_binding(source_path)
    require(all(report.get('jointPolicy') == expected for report in reports),
            'Every report must bind exact sampled candidate/incumbent/shared roles.')
    subjects = [expected['source'],expected['baseline']]
    for role in ('sharedBundle','candidateBundle','incumbentBundle'):
        bundle = expected[role]
        subjects += [bundle]+[bundle[phase] for phase in joint.PHASES if bundle[phase] is not None]
    require(all(sha(directory/item['snapshot']) == item['sha256'] for item in subjects),
            'Raw joint bundle/phase/source/prior snapshots changed.')
    if rival_profile(protocol) is None:
        require(expected['sharedBundle']['card'] is expected['incumbentBundle']['card'] is None
                and expected['sharedBundle']['purchase']['sha256'] == joint.G2
                and expected['incumbentBundle']['purchase']['sha256'] == joint.G2
                and expected['baseline']['sha256'] == ecology.F0,
                'The strength anchor and every rival retain ordinary cards, exactG2 and separateF0.')
    else:
        verify_successor_roles(protocol,stage,chunk,expected)
    ecology.verify_overlays(document,directory,reports,arena=True,expected_expansion=joint.E,
                            rival_ecology=overlay_ecology(protocol,stage))
    references = {role:protocol['ordinary'] for role in ('candidate','incumbent',*[f'opponent-{i}' for i in range(4)])}
    for report in reports:
        legacy.verify_transferred_sources(document,report,directory,references)
    return expected


def verify_successor_roles(protocol, stage, chunk, expected):
    """Bind actual raw subjects, not a symbolic G3 label or null-card approximation."""
    declarations = {'candidateBundle':protocol['candidateBundle'],
                    'incumbentBundle':protocol['anchorBundle'],
                    'sharedBundle':shared_bundle(protocol,stage,chunk)}
    subjects = {role:joint.bundle_subjects(path) for role,path in declarations.items()}
    anchor = subjects['incumbentBundle']
    g3 = joint.bundle_subjects(protocol['rivalBundles']['G3'])
    require(anchor['card'] is not None and anchor['purchase'] is not None
            and anchor['sha256'] == g3['sha256'] and expected['baseline']['sha256'] == ecology.F0,
            'Successor qualification requires the same actual learned-card G3 anchor and league role, with F0.')
    for role, subject in subjects.items():
        actual = expected[role]
        require(all(actual[key] == subject[key] for key in
                    ('path','sha256','kind','configuration','baselineSha256'))
                and all((actual[phase] is None and subject[phase] is None) or
                        (actual[phase] is not None and subject[phase] is not None
                         and all(actual[phase][key] == value for key,value in subject[phase].items()))
                        for phase in joint.PHASES),
                'A successor candidate, incumbent or scheduled rival differs from its exact raw bundle/components.')


def compact_pair(pair, expected, local_index, global_index, seed, pair_sha, source_sha):
    """Check the full original descriptor before retaining only scalar/game/identity evidence."""
    search.validate_pair(pair,local_index,seed,4)
    require(pair.get('jointPolicy') == expected, 'Original pair actor descriptor differs from its source.')
    require(pair['seat'] == global_index % 4
            and pair['opponentIndices'] == [(global_index//4+seat)%4 for seat in range(4)],
            'Chunking changed global seat or original-index rival rotation.')
    return {**{key:pair[key] for key in IDENTITY},
            **{side:{key:pair[side][key] for key in search.GAME_FIELDS}
               for side in ('candidate','incumbent')},
            'globalIndex':global_index,'chunkIndex':global_index//CHUNK_PAIRS,
            'pairSha256':pair_sha,'sourceSha256':source_sha}


def verify_chunk(protocol, source_path, directory):
    """Validate one completed512-pair process; no native execution or scientific gate."""
    source_path,directory = Path(source_path).resolve(strict=True),Path(directory).resolve(strict=True)
    document = read(source_path)
    stage,chunk = source_slot(protocol,document)
    report,run,state = [read(directory/name) for name in ('evaluation.json','run.json','state.json')]
    require(report['complete'] is True and report['purpose'] == document['purpose']
            and report['promoted'] is False and report['humanStrengthVerified'] is False
            and state['phase'] == 'complete' and bool(read(directory/'finished.json')['at'])
            and run['kind'] == state['kind'] == 'strategy-arena'
            and run['signature'] == state['signature'] and run['source'] == str(source_path)
            and run['workers'] == protocol['workers'] and run['pairs'] == CHUNK_PAIRS
            and run['seed'] == document['seed'] and run['opponentModels'] == 4,
            'Require complete original native chunk reports and the frozen worker/source bound.')
    expected = verify_roles(protocol,source_path,directory,[report,run])
    require({path.name for path in directory.glob('pair-*.json')} == {f'pair-{i}.json' for i in range(CHUNK_PAIRS)},
            'Every chunk must retain exactly512 original pairs.')
    source_sha = sha(source_path)
    rows = []
    for index in range(CHUNK_PAIRS):
        path = directory/f'pair-{index}.json'
        global_index = chunk*CHUNK_PAIRS+index
        rows.append(compact_pair(read(path),expected,index,global_index,
                    protocol['banks'][stage]['seeds'][global_index],sha(path),source_sha))
    metrics = search.comparison(rows)
    search.verify_metrics(report['overall'],metrics)
    require(set(report['byConfiguration']) == {exp.CONFIGURATION}, 'Only one native setup is admitted.')
    search.verify_metrics(report['byConfiguration'][exp.CONFIGURATION],metrics)
    return dict(rows=rows,metrics=metrics,roles=expected,stage=stage,chunkIndex=chunk,sourceSha256=source_sha)


def verify_replay(protocol, source_path, directory, pair):
    """Compare a stored canonical side with its compact, original local-index pair."""
    source_path,directory = Path(source_path).resolve(strict=True),Path(directory).resolve(strict=True)
    stage,chunk = source_slot(protocol,read(source_path))
    replay,native,state = [read(directory/name) for name in ('replay.json','run.json','state.json')]
    side = replay['side'].lower()
    expected_chunk,local = replay_slot(pair['globalIndex'])
    require(chunk == expected_chunk and pair['index'] == local and side in ('candidate','incumbent')
            and replay['replayVerified'] is True and replay['historicalPairVerified'] is True
            and native['source'] == str(source_path) and native['workers'] == 1
            and native['signature'] == state['signature'] and state['phase'] == 'complete'
            and bool(read(directory/'finished.json')['at'])
            and all(replay[key] == pair[key] for key in IDENTITY)
            and search.same_game(replay['game'],pair[side])
            and sha(native['recordedPair']) == pair['pairSha256'],
            'Canonical side must reproduce its exact chunk source, original pair and terminal game.')
    roles = verify_roles(protocol,source_path,directory,[native,state])
    require(replay.get('jointPolicy') == roles, 'Canonical replay actor roles changed.')
    return dict(globalIndex=pair['globalIndex'],chunkIndex=chunk,index=local,side=side,stage=stage,
                replaySha256=sha(directory/'replay.json'),pairSha256=pair['pairSha256'],
                sourceSha256=sha(source_path))


def member_name(name):
    require(isinstance(name,str), 'Archive member names must be strings.')
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute()
            and '..' not in path.parts and str(path) == name and '\\' not in name,
            'Archive members require bounded canonical relative paths.')


def inventory(directory):
    require(directory.is_dir() and not directory.is_symlink(), 'Archive only an owned regular chunk directory.')
    files = {}
    for path in sorted(directory.rglob('*')):
        require(not path.is_symlink(), 'Chunk evidence must not contain symlinks.')
        if path.is_dir() or path.name == '.DS_Store':
            continue
        require(path.is_file(), 'Chunk evidence contains a nonregular file.')
        name = path.relative_to(directory).as_posix()
        member_name(name)
        files[name] = dict(sha256=sha(path),size=path.stat().st_size)
    return files


def verify_archive(receipt):
    """Portable relative archive location; retain original absolute paths only as provenance."""
    receipt = Path(receipt)
    require(receipt.is_file() and not receipt.is_symlink(), 'Archive receipt must be a regular file.')
    manifest = read(receipt)
    require(manifest['kind'] == 'joint-chrono-chunk-storage-1' and manifest['files'], 'Unsupported or empty chunk archive.')
    member_name(manifest['archive'])
    require(len(PurePosixPath(manifest['archive']).parts) == 1, 'Archive must be beside its receipt.')
    for name,item in manifest['files'].items():
        member_name(name)
        require(type(item['size']) is int and item['size'] >= 0, 'Archived member size must be nonnegative.')
    storage.verify_members(receipt.parent/manifest['archive'],manifest['files'],manifest['archiveSha256'])
    return manifest


def archive(directory, target):
    """Publish/verify ALL scientific chunk bytes before removing their exact loose duplicates."""
    directory,target = Path(directory),Path(target)
    require(not directory.is_symlink() and not target.is_symlink(), 'Archive paths cannot be symlinks.')
    directory = directory.resolve(strict=True)
    target = target.absolute()
    require(target.parent.resolve() == target.parent and not target.is_relative_to(directory),
            'Archive target must have a real parent outside its expanded evidence tree.')
    receipt = target.with_name(target.name+'.json')
    for path in (target,receipt):
        require(not path.exists() and not path.with_name(path.name+'.writing').exists(),
                'Never replace published or interrupted evidence; no archive retry.')
    require(read(directory/'native/state.json')['phase'] == 'complete'
            and read(directory/'native/evaluation.json')['complete'] is True
            and (directory/'native/finished.json').is_file(), 'Archive only a completed native chunk.')
    files = inventory(directory)
    require(bool(files), 'A completed chunk cannot have empty evidence.')

    def publish_archive(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as output:
            for name in files:
                output.add(directory/name,arcname=name,recursive=False)

    storage.publish(target,publish_archive)
    manifest = dict(kind='joint-chrono-chunk-storage-1',archive=target.name,archiveSha256=sha(target),
                    originalDirectory=str(directory),files=files,
                    incidentalFinderFilesExcluded=sum(path.name=='.DS_Store' for path in directory.rglob('*')))
    storage.verify_members(target,files,manifest['archiveSha256'])
    require(inventory(directory) == files, 'Original chunk changed before verified duplicate removal.')
    storage.publish(receipt,lambda stream:stream.write((json.dumps(manifest,sort_keys=True,indent=2)+'\n').encode()))
    verify_archive(receipt)
    for name,item in files.items():
        path = directory/name
        require(not path.is_symlink() and sha(path) == item['sha256'], 'Original changed before its duplicate removal.')
        path.unlink()
    for path in sorted([directory,*[p for p in directory.rglob('*') if p.is_dir()]],key=lambda p:len(p.parts),reverse=True):
        if not any(path.iterdir()):
            path.rmdir()
    storage.sync_directory(target.parent)
    return dict(manifest,receipt=str(receipt),receiptSha256=sha(receipt))
