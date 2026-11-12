"""Strict public afterstate rows and terminal labels; portable, streamed whole-world storage."""
import gzip
import hashlib
import json
import math
from pathlib import Path
import tarfile

import joint_state_value_data as joint
import purchase_value_data as base

VERSION = 'aor-afterstate-value-1'
KIND = 'afterstate-value-corpus-1'
CONTEXTS = ('CARD', 'SHIPPING', 'ADVANCES', 'STABILIZATION', 'RELIEF', 'OWNER_EXIT')
DECISIONS = ('play-cards', 'purchase-shipping', 'purchase-advances',
             'purchase-stabilization', 'purchase-relief', 'purchase-owner-exit')
APPENDED = ['purchase.decision.purchase-owner-exit', 'purchase.decision.purchase-relief',
            'purchase.decision.purchase-stabilization']
read, sha, document, require = base.read, base.sha, base.document, base.require
relative_file, split = joint.relative_file, joint.split


def dictionary_names(value):
    require(set(value) == {'version', 'stateFeatureNames', 'legacyStateFeatureNames',
            'appendedStateFeatureNames', 'contexts', 'units', 'missingValue'}
        and value['version'] == VERSION and value['missingValue'] == 0.
        and value['units'] == 'unchanged-existing-phase-encoder-units',
        'Afterstate dictionary must bind its explicit version and unchanged units.')
    old, names = value['legacyStateFeatureNames'], value['stateFeatureNames']
    require(len(old) == 7241 and old == sorted(set(old)) and len(set(names)) == len(names)
        and names == old + APPENDED and value['appendedStateFeatureNames'] == APPENDED
        and value['contexts'] == [dict(name=name, index=i, decision=DECISIONS[i])
                                 for i, name in enumerate(CONTEXTS)],
        'All original7241 coordinates must remain unchanged before the three proven new keys.')
    return names


def project_states(states, *, dictionary, dictionary_sha, signature, index, seed, source_sha, states_sha):
    """Feature-only validation; no terminal label is decoded or synthesized."""
    names = set(dictionary_names(dictionary))
    identity = dict(version=VERSION, signature=signature, index=index, gameSeed=seed,
                    controlledSeat=index % 4, sourceSha256=source_sha, dictionarySha256=dictionary_sha)
    require(set(states) == set(identity) | {'rows', 'exclusions', 'originalStateParity'}
        and all(states[key] == value for key, value in identity.items())
        and states['originalStateParity'] is True,
        'Each separately labeled origin requires exact source, dictionary and compressed state identity.')
    rows, ordering, counts, exact = [], [], dict.fromkeys(CONTEXTS, 0), dict.fromkeys(CONTEXTS, 0)
    for row in states['rows']:
        require(set(row) == {'number','ordinal','turn','context','stateFeatures','exact','exactReason'}
            and type(row['number']) is int and 0 <= row['number'] < 12000
            and type(row['ordinal']) is int and row['ordinal'] in (0,1)
            and type(row['turn']) is int and row['turn'] >= 0 and row['context'] in CONTEXTS,
            'Rows require genuine absolute decision/context provenance.')
        context = CONTEXTS.index(row['context']); prefix = 'card' if context == 0 else 'purchase'
        features = row['stateFeatures']
        require(isinstance(features, dict) and set(features) <= names
            and all(name.startswith(prefix+'.') for name in features)
            and all(type(v) in (int,float) and math.isfinite(v) and -16 <= v <= 16 for v in features.values())
            and features.get(prefix+'.decision.'+DECISIONS[context]) == 1.
            and (row['ordinal'] == 1) == (context == 5),
            'Only factual own/public inputs in original units and the honest context enter encoding.')
        require(type(row['exact']) is bool and row['exact'] == (row['exactReason'] is not None)
            and row['exactReason'] in (None, 'OWN_CHAOS', 'GAME_TERMINAL'), 'Terminal bypass needs a typed reason.')
        if row['exactReason'] == 'OWN_CHAOS':
            require(features.get(prefix+'.role.0.chaos') == 1.,
                    'Chaos bypass requires authoritative own elimination, not an estimated hazard.')
        ordering.append((row['number'], row['ordinal'])); counts[row['context']] += 1
        if row['exact']: exact[row['context']] += 1
        else: rows.append(row)
    require(ordering == sorted(set(ordering)) and all(a['turn'] <= b['turn'] for a,b in zip(states['rows'], states['rows'][1:])),
            'Before/after chronology is unique and monotone; empty worlds remain permitted.')
    for exclusion in states['exclusions']:
        require(set(exclusion) == {'number','reason'} and type(exclusion['number']) is int
            and 0 <= exclusion['number'] < 12000
            and exclusion['reason'] in ('INFORMATION','INTERVENING_CHOICE'),
            'Unsupported boundaries remain explicit coverage exclusions, never fabricated inputs.')
    return rows, dict(contextCounts=counts, exactCounts=exact,
        exclusions=len(states['exclusions']), decisions=len(rows), nonzeros=sum(len(r['stateFeatures']) for r in rows))


def project_world(states, label, **identity):
    """Detached terminal validation is called only by admitted development or TEST scoring."""
    rows, counts = project_states(states, **identity)
    require(set(label) == {'version','signature','index','gameSeed','controlledSeat','sourceSha256',
        'dictionarySha256','statesSha256','terminal'} and label['statesSha256'] == identity['states_sha']
        and all(label[key] == states[key] for key in ('version','signature','index','gameSeed',
            'controlledSeat','sourceSha256','dictionarySha256')), 'Detached label identity changed.')
    index=identity['index']
    terminal = label['terminal']
    require(set(terminal) == {'reward', 'winShare', 'rankReward', 'scores', 'gameDecisions'}
        and all(type(terminal[k]) in (float, int) and math.isfinite(terminal[k]) and 0 <= terminal[k] <= 1
                for k in ('reward', 'winShare', 'rankReward'))
        and abs(terminal['reward'] - .8*terminal['winShare'] - .2*terminal['rankReward']) <= 1e-12
        and type(terminal['gameDecisions']) is int and terminal['gameDecisions'] >= 0,
        'The original terminal composite must remain separate and unchanged.')
    scores = terminal['scores']
    require(len(scores) == 4 and [s['player'] for s in scores] == ['p1','p2','p3','p4'],
            'Terminal labels retain the original public seat ordering.')
    own = scores[index % 4]
    require(own['total'] is not None or terminal['reward'] == terminal['winShare'] == terminal['rankReward'] == 0,
            'Own Chaos has exact zero terminal value.')
    require(all(row['number'] < terminal['gameDecisions'] for row in states['rows'])
        and all(e['number'] < terminal['gameDecisions'] for e in states['exclusions']),
        'Every recorded input precedes the actual terminal boundary.')
    require(not any(row['exactReason']=='OWN_CHAOS' for row in states['rows']) or own['total'] is None,
        'Exact Chaos rows must agree with the authoritative terminal result.')
    return rows, terminal['reward'], counts


def verify_corpus(corpus):
    names = dictionary_names(corpus['dictionary'])
    require(corpus['kind'] == KIND and corpus['stateFeatureNames'] == names
        and len(corpus['games']) == 4096 and len(corpus['batches']) == 16
        and corpus['splitCounts'] == joint.SPLITS, 'The fresh corpus retains all prospective4096 worlds and fixed split.')
    require(len({g['gameSeed'] for g in corpus['games']}) == 4096, 'Every corpus origin must be independently seeded.')
    for gid, game in enumerate(corpus['games']):
        batch, index = divmod(gid, 256)
        require(game['id'] == gid and game['batch'] == batch+1 and game['index'] == index
            and game['split'] == split(batch+1) and game['seat'] == index % 4
            and type(game['decisions']) is int and game['decisions'] >= 0
            and set(game['contextCounts']) == set(CONTEXTS) and set(game['exactCounts']) == set(CONTEXTS)
            and game['decisions'] == sum(game['contextCounts'].values()) - sum(game['exactCounts'].values()),
            'Original whole-world ordering/splits and zero-row origins cannot be selected by outcomes.')
    require(corpus['rows'] == sum(g['decisions'] for g in corpus['games'])
        and corpus['nonzeros'] == sum(g['nonzeros'] for g in corpus['games']), 'Corpus allocation coverage changed.')


def iter_worlds(package, batch, games, guard, *, decode_labels=True):
    archive = relative_file(package, batch['archive'])
    require(sha(archive) == batch['archiveSha256'], 'Lossless archive bytes changed.')
    seen = set()
    with tarfile.open(archive, 'r|gz') as stream:
        members = iter(stream)
        for game in games:
            docs = []
            for name in (f'collection/states-{game["index"]}.json.gz', f'collection/label-{game["index"]}.json'):
                guard(); member = next(members, None)
                require(member is not None and member.isfile() and member.name == name and member.size <= 64*1024*1024,
                        'Each original state/label pair must appear in fixed bounded archive order.')
                content = stream.extractfile(member).read(); seen.add(name)
                require(hashlib.sha256(content).hexdigest() == game['files'][name] == batch['files'][name],
                        'An immutable state or label changed.')
                if name.endswith('.json') and not decode_labels:
                    docs.append(None)
                else:
                    if name.endswith('.gz'):
                        import io
                        with gzip.GzipFile(fileobj=io.BytesIO(content)) as compressed:
                            expanded=compressed.read(64*1024*1024+1)
                    else: expanded=content
                    require(len(expanded) <= 64*1024*1024, 'Expanded origin exceeds its bounded document size.')
                    docs.append(json.loads(expanded))
            yield game, docs
        for member in members:
            guard()
            require(member.isfile() and member.name in batch['files'] and member.name not in seen
                and not Path(member.name).is_absolute() and '..' not in Path(member.name).parts,
                'Only declared regular evidence follows the original world records.')
            seen.add(member.name)
            digest = hashlib.sha256()
            with stream.extractfile(member) as content:
                for block in iter(lambda: content.read(1024*1024), b''): digest.update(block)
            require(digest.hexdigest() == batch['files'][member.name], 'Retained source/reproduction evidence changed.')
        require(seen == set(batch['files']), 'Archive must preserve every declared evidence member.')


def build_cache(package, output, guard, *, partition='development', selection=None):
    """DEV reads first14 chunks; TEST reads only last2 public states after fixed selection."""
    import numpy as np
    package, output = Path(package), Path(output)
    corpus = read(package/'corpus.json'); verify_corpus(corpus)
    require(partition in ('development','test'), 'Cache partition must be explicit development or test.')
    if partition == 'test':
        require(isinstance(selection,dict) and set(selection)=={'path','sha256'}
            and sha(selection['path'])==selection['sha256'], 'TEST features require the frozen selection receipt.')
        selected=read(selection['path'])
        require(selected['kind']=='afterstate-value-critic-1' and selected['testInspected'] is False
            and selected['dictionarySha256']==corpus['dictionarySha256'], 'TEST selection/dictionary binding changed.')
    else: require(selection is None, 'Development cache does not accept a TEST selection.')
    require(sha(relative_file(package, corpus['dictionaryPath'])) == corpus['dictionarySha256']
        and read(relative_file(package, corpus['dictionaryPath'])) == corpus['dictionary'], 'Exact static dictionary changed.')
    wanted=lambda game: (game['split']=='test') == (partition=='test')
    included=[g for g in corpus['games'] if wanted(g)]
    rows_total=sum(g['decisions'] for g in included);nz_total=sum(g['nonzeros'] for g in included)
    require(not output.exists(), 'Afterstate CSR requires new output, without resume.')
    output.mkdir(parents=True); names = {name:i for i,name in enumerate(corpus['stateFeatureNames'])}
    specs = dict(values=('float64',nz_total), columns=('uint16',nz_total), rowptr=('uint64',rows_total+1),
        rowGame=('uint32',rows_total), context=('uint8',rows_total), reward=('float64',4096), starts=('uint64',4096), stops=('uint64',4096))
    arrays = {name: np.lib.format.open_memmap(output/(name+'.npy'), mode='w+', dtype=dtype, shape=(count,)) for name,(dtype,count) in specs.items()}
    row_offset=value_offset=0;arrays['rowptr'][0]=0;arrays['reward'][:]=np.nan
    for batch in corpus['batches']:
        start=(batch['index']-1)*256;games=corpus['games'][start:start+256]
        if not wanted(games[0]):
            arrays['starts'][start:start+256]=row_offset;arrays['stops'][start:start+256]=row_offset
            continue
        for game,(states,label) in iter_worlds(package,batch,games,guard,decode_labels=partition=='development'):
            identity=dict(dictionary=corpus['dictionary'],dictionary_sha=corpus['dictionarySha256'],
                signature=batch['signature'],index=game['index'],seed=game['gameSeed'],source_sha=batch['sourceSha256'],
                states_sha=game['files'][f'collection/states-{game["index"]}.json.gz'])
            if partition=='development':rows,reward,counts=project_world(states,label,**identity)
            else:
                rows,counts=project_states(states,**identity);reward=np.nan
            require(all(game[k]==v for k,v in counts.items()),'Declared public coverage changed.')
            gid=game['id'];arrays['starts'][gid]=row_offset;arrays['reward'][gid]=reward
            for row in rows:
                columns,values=joint.encoded_state(row['stateFeatures'],names);stop=value_offset+len(values)
                require(stop<=nz_total and row_offset<rows_total,'CSR size exceeded declared coverage.')
                arrays['columns'][value_offset:stop]=columns;arrays['values'][value_offset:stop]=values
                arrays['rowptr'][row_offset+1]=stop;arrays['rowGame'][row_offset]=gid
                arrays['context'][row_offset]=CONTEXTS.index(row['context']);row_offset+=1;value_offset=stop
            arrays['stops'][gid]=row_offset
    require(row_offset==rows_total and value_offset==nz_total,'Complete partition counts changed.')
    for array in arrays.values():array.flush()
    guard();document(output/'finished.json',dict(kind='afterstate-value-csr-1',partition=partition,selection=selection,
        rows=row_offset,nonzeros=value_offset,games=4096,corpusSha256=sha(package/'corpus.json'),
        files={p.name:sha(p) for p in output.glob('*.npy')},stateFeatureNames=corpus['stateFeatureNames'],
        testLabelsDecoded=False,newGames=0))


def load_test_labels(package, guard, predictions):
    """Second TEST pass, called only after selected-model predictions have been persisted."""
    import numpy as np
    package=Path(package);predictions=Path(predictions)
    corpus=read(package/'corpus.json');verify_corpus(corpus)
    receipt=read(predictions);root=predictions.parent;selection=read(root/'selected.json')
    files={f'test-predictions-{name}.npy' for name in ('selected','original52','constant')}
    require(receipt['kind']=='afterstate-test-predictions-1' and receipt['predictionsComplete'] is True
        and receipt['semanticLabelsDecoded'] is False and receipt['testIds']==list(range(3584,4096))
        and receipt['selectionSha256']==sha(root/'selected.json')
        and receipt['selectedSha256']==selection['selectedSha256']==sha(root/'warm-best.pt')
        and receipt['dictionarySha256']==selection['dictionarySha256']==corpus['dictionarySha256']
        and receipt['sourceSha256']==selection['sourceSha256']
        and set(receipt['files'])==files and all(sha(root/name)==digest for name,digest in receipt['files'].items()),
        'Semantic TEST labels require exact persisted selected-model predictions and immutable subjects.')
    document(root/'test-label-attempt.json',dict(predictionsSha256=sha(predictions),maximumAttempts=1))
    rewards=np.full(4096,np.nan,dtype=np.float64)
    for batch in corpus['batches'][14:]:
        start=(batch['index']-1)*256
        for game,(states,label) in iter_worlds(package,batch,corpus['games'][start:start+256],guard):
            _,reward,counts=project_world(states,label,dictionary=corpus['dictionary'],dictionary_sha=corpus['dictionarySha256'],
                signature=batch['signature'],index=game['index'],seed=game['gameSeed'],source_sha=batch['sourceSha256'],
                states_sha=game['files'][f'collection/states-{game["index"]}.json.gz'])
            require(all(game[k]==v for k,v in counts.items()),'Scoring coverage differs from fixed predictions.')
            rewards[game['id']]=reward
    return rewards


class Cache(joint.Cache):
    def __init__(self,directory,verify=True):
        base.Cache.__init__(self,directory,verify)
        done=read(self.root/'finished.json')
        require(done['kind']=='afterstate-value-csr-1','Use a completed afterstate CSR cache.')
        self.feature_names=done['stateFeatureNames']
