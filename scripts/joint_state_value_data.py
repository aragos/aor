"""Strict actor-state chronology and bounded CSR storage for a fixed shared-value benchmark.

Only prefixed existing stateFeatures enter tensors. Identities, decisions, turns, actions,
coverage and terminal outcomes retain separate owners; terminal reward is the sole target.
Zero-row worlds remain in their original split and have no invented training state.
"""
from pathlib import Path
import gzip
import hashlib
import json
import math
import tarfile

import purchase_value_data as base

VERSION = 'joint-state-chronology-1'
KIND = 'joint-state-value-corpus-1'
PHASES = {'CARD': ('card', 'cardDecisions', 0), 'PURCHASE': ('purchase', 'purchaseDecisions', 1)}
SPLITS = {'training': 3072, 'validation': 512, 'test': 512}
read, sha, document, require = base.read, base.sha, base.document, base.require


def split(batch):
    require(type(batch) is int and 1 <= batch <= 16, 'A fixed corpus has sixteen whole-world chunks.')
    return 'training' if batch <= 12 else 'validation' if batch <= 14 else 'test'


def config(banks):
    names = ('warmInitialization', 'freshInitialization', 'epochOrder', 'bootstrap')
    seeds = [seed for name in names for seed in banks[name]['seeds']]
    require(len(seeds) == 23 and seeds == list(range(seeds[0], seeds[0]+23)),
            'Two critic initializers, twenty shared epoch orders and one bootstrap stream are fixed.')
    return base.Config(smallSeed=seeds[0], largeSeed=seeds[1], orderSeed=seeds[2], bootstrapSeed=seeds[-1])


def dictionary_names(dictionary):
    require(set(dictionary) == {'version', 'stateFeatureNames', 'units', 'missingValue'}
            and dictionary['version'] == VERSION and dictionary['missingValue'] == 0.
            and dictionary['units'] == 'unchanged-existing-phase-encoder-units',
            'Joint inputs require the exact phase-prefixed state-only dictionary contract.')
    names = dictionary['stateFeatureNames']
    require(isinstance(names, list)
            and all(isinstance(name, str) and name.startswith(('card.', 'purchase.')) for name in names)
            and names == sorted(set(names))
            and all(any(name.startswith(prefix+'.') for name in names) for prefix in ('card', 'purchase')),
            'Both complete unique phase vocabularies must remain explicit and sorted.')
    return names


def encoded_state(features, names):
    require(isinstance(features, dict) and set(features) <= names.keys()
            and all(type(value) in (int, float) and math.isfinite(value) for value in features.values()),
            'Model inputs allow only finite values from the frozen state dictionary, never metadata.')
    ordered = sorted((names[name], float(value)) for name, value in features.items())
    return [index for index, _ in ordered], [value for _, value in ordered]


def project_world(game, state, label, coverage, dictionary, *, game_sha256, dictionary_sha256):
    """Reconstruct the sidecar from original actor observations, without actions or future views."""
    names = set(dictionary_names(dictionary))
    identity = dict(version=VERSION, signature=game['signature'], originIndex=game['index'],
                    sourceGameSha256=game_sha256, dictionarySha256=dictionary_sha256)
    require(set(state) == set(identity) | {'rows'} and set(label) == set(identity) | {'terminal'}
            and all(sidecar[name] == value for sidecar in (state, label, coverage)
                    for name, value in identity.items()), 'State, label and coverage provenance must match one complete world.')
    projected = []
    for phase, (prefix, field, _) in PHASES.items():
        for source_row, row in enumerate(game[field]):
            number, turn = row['number'], row['turn']
            require(type(number) is int and number >= 0 and type(turn) is int and turn >= 0,
                    'Absolute decision and public turn metadata must be nonnegative integers.')
            features = {prefix+'.'+key: value for key, value in row['observation']['stateFeatures'].items()}
            require(set(features) <= names and all(type(v) in (int, float) and math.isfinite(v) for v in features.values()),
                    'Original actor states must retain their finite phase encoder values.')
            projected.append(dict(number=number, turn=turn, phase=phase, sourceRow=source_row, stateFeatures=features))
    projected.sort(key=lambda row: row['number'])
    require(len({row['number'] for row in projected}) == len(projected)
            and all(a['turn'] <= b['turn'] for a, b in zip(projected, projected[1:]))
            and state['rows'] == projected, 'Chronological rows must exactly reproduce their actor-visible source states.')
    terminal = label['terminal']
    require(terminal == game['terminal'] and all(type(terminal[k]) in (int, float)
            and math.isfinite(terminal[k]) and 0 <= terminal[k] <= 1 for k in ('reward', 'winShare', 'rankReward'))
            and math.isclose(terminal['reward'], .8*terminal['winShare']+.2*terminal['rankReward'], abs_tol=1e-12),
            'The separately bound complete terminal objective must remain unchanged.')
    require(type(terminal['gameDecisions']) is int and all(row['number'] < terminal['gameDecisions'] for row in projected),
            'Every actor-state row must precede the recorded terminal boundary.')
    counts = {phase: len(game[field]) for phase, (_, field, _) in PHASES.items()}
    require(coverage['independentWorlds'] == 1 and coverage['cardDecisions'] == counts['CARD']
            and coverage['purchaseDecisions'] == counts['PURCHASE']
            and coverage['worldCoverage']['zeroCardDecisions'] == (counts['CARD'] == 0)
            and coverage['worldCoverage']['zeroPurchaseDecisions'] == (counts['PURCHASE'] == 0),
            'Coverage must preserve empty phases/worlds; repeated rows are not independent origins.')
    return projected, terminal['reward'], counts


def verify_corpus(corpus):
    games, batches = corpus['games'], corpus['batches']
    names = dictionary_names(corpus['dictionary'])
    require(corpus['kind'] == KIND and len(games) == 4096 and len(batches) == 16
            and corpus['splitCounts'] == SPLITS and corpus['stateFeatureNames'] == names
            and len(names) == 7241 and sum(name.startswith('purchase.') for name in names) == 3556
            and len({game['gameSeed'] for game in games}) == len(games)
            and len({game['sourceGameSha256'] for game in games}) == len(games),
            'The prospective corpus requires all4096 distinct worlds and its complete7241-state dictionary.')
    for index, game in enumerate(games):
        batch, offset = divmod(index, 256)
        require(game['id'] == index and game['batch'] == batch+1 and game['index'] == offset
                and game['split'] == split(batch+1) and game['seat'] == offset % 4
                and type(game['decisions']) is int and game['decisions'] >= 0
                and set(game['phaseCounts']) == set(PHASES) and all(type(n) is int and n >= 0 for n in game['phaseCounts'].values())
                and sum(game['phaseCounts'].values()) == game['decisions']
                and game['sourceGameSha256'] == game['files'][f'collection/game-{offset}.json.gz'],
                'World order, original split and possibly empty phase coverage cannot be selected by outcomes.')
    require([batch['index'] for batch in batches] == list(range(1,17))
            and corpus['rows'] == sum(game['decisions'] for game in games)
            and corpus['nonzeros'] == sum(game['nonzeros'] for game in games), 'Corpus coverage changed.')


def relative_file(package, name):
    root, relative = Path(package).resolve(), Path(name)
    require(not relative.is_absolute() and '..' not in relative.parts
            and (root/relative).resolve().is_relative_to(root),
            'Portable benchmark inputs must resolve inside their package, never original native paths.')
    return root/relative


def iter_worlds(package, batch, declarations, guard):
    """One compressed native world and its three sidecars at a time; no archive extraction."""
    archive = relative_file(package, batch['archive'])
    require(sha(archive) == batch['archiveSha256'], 'A complete lossless chunk archive changed.')
    with tarfile.open(archive, 'r|gz') as source:
        members = iter(source); seen = set()
        for declared in declarations:
            guard(); index = declared['index']; documents = []
            for name in (f'collection/game-{index}.json.gz', f'collection/joint-state-{index}.json',
                         f'collection/joint-label-{index}.json', f'collection/joint-coverage-{index}.json'):
                member = next(members, None)
                require(member is not None and member.isfile() and member.name == name,
                        'A lossless chunk must retain each complete world and sidecar in fixed origin order.')
                content = source.extractfile(member).read()
                seen.add(name)
                require(hashlib.sha256(content).hexdigest() == declared['files'][name] == batch['files'][name],
                        'An immutable native world or sidecar changed.')
                documents.append(json.loads(gzip.decompress(content) if name.endswith('.gz') else content))
            require(documents[0]['gameSeed'] == declared['gameSeed'] and documents[0]['index'] == index
                    and documents[0]['controlledSeat'] == declared['seat']
                    and documents[0]['signature'] == batch['signature'], 'Reserved origin identity changed.')
            yield declared, documents
        for member in members:
            guard()
            require(member.isfile() and member.name in batch['files'] and member.name not in seen
                    and not Path(member.name).is_absolute() and '..' not in Path(member.name).parts,
                    'Only declared immutable snapshot/reproduction members may follow the fixed world records.')
            seen.add(member.name)
            require(hashlib.sha256(source.extractfile(member).read()).hexdigest() == batch['files'][member.name],
                    'A retained source/model/history/reproduction snapshot changed.')
        require(seen == set(batch['files']), 'The complete lossless chunk must retain every immutable snapshot.')


def build_cache(package, output, guard):
    import numpy as np
    package, output = Path(package), Path(output)
    corpus = read(package/'corpus.json'); verify_corpus(corpus)
    require(sha(relative_file(package, corpus['dictionaryPath'])) == corpus['dictionarySha256']
            and read(relative_file(package, corpus['dictionaryPath'])) == corpus['dictionary'], 'Original dictionary bytes changed.')
    require(not output.exists(), 'Shared CSR construction requires one new immutable output.')
    output.mkdir(parents=True); names = {name: i for i, name in enumerate(corpus['stateFeatureNames'])}
    specifications = dict(values=('float64', corpus['nonzeros']), columns=('uint16', corpus['nonzeros']),
        rowptr=('uint64', corpus['rows']+1), rowGame=('uint32', corpus['rows']), phase=('uint8', corpus['rows']),
        reward=('float64', len(corpus['games'])), starts=('uint64', len(corpus['games'])), stops=('uint64', len(corpus['games'])))
    arrays = {name: np.lib.format.open_memmap(output/(name+'.npy'), mode='w+', dtype=dtype, shape=(count,))
              for name, (dtype, count) in specifications.items()}
    row_offset = value_offset = 0; arrays['rowptr'][0] = 0
    for batch in corpus['batches']:
        start = (batch['index']-1)*256
        for game, documents in iter_worlds(package, batch, corpus['games'][start:start+256], guard):
            require(all(documents[0][key] == value for key, value in corpus['policyBindings'].items())
                    and documents[0]['configuration'] == corpus['configuration'], 'Frozen actor roles or setup changed.')
            rows, reward, counts = project_world(*documents, corpus['dictionary'],
                game_sha256=game['sourceGameSha256'], dictionary_sha256=corpus['dictionarySha256'])
            require(len(rows) == game['decisions'] and counts == game['phaseCounts'], 'Declared chronological coverage changed.')
            gid = game['id']; arrays['starts'][gid] = row_offset; arrays['reward'][gid] = reward
            for row in rows:
                columns, values = encoded_state(row['stateFeatures'], names); stop = value_offset+len(values)
                require(stop <= corpus['nonzeros'] and row_offset < corpus['rows'], 'CSR allocation bound changed.')
                arrays['columns'][value_offset:stop], arrays['values'][value_offset:stop] = columns, values
                arrays['rowptr'][row_offset+1] = stop; arrays['rowGame'][row_offset] = gid
                arrays['phase'][row_offset] = PHASES[row['phase']][2]
                row_offset += 1; value_offset = stop
            arrays['stops'][gid] = row_offset
    require(row_offset == corpus['rows'] and value_offset == corpus['nonzeros'], 'Complete CSR coverage changed.')
    for value in arrays.values(): value.flush()
    guard(); document(output/'finished.json', dict(kind='joint-state-value-csr-1', rows=row_offset,
        nonzeros=value_offset, games=4096, corpusSha256=sha(package/'corpus.json'),
        files={p.name: sha(p) for p in output.glob('*.npy')}, stateFeatureNames=corpus['stateFeatureNames'],
        rawValues='float64', normalization='unchanged-existing-phase-encoder-units', newGames=0))


class Cache(base.Cache):
    """The original bounded sparse/dense interface with an explicit larger state dictionary."""
    def __init__(self, directory, verify=True):
        super().__init__(directory, verify)
        done = read(self.root/'finished.json')
        require(done['kind'] == 'joint-state-value-csr-1', 'Use a completed shared-state CSR cache.')
        self.feature_names = done['stateFeatureNames']

    def game_rows(self, games):
        if not len(games): return self.np.array([], dtype='int64')
        return super().game_rows(games)

    def dense(self, rows, dtype='float32'):
        np, a = self.np, self.arrays
        counts = (a['rowptr'][rows+1]-a['rowptr'][rows]).astype('int64')
        starts = np.repeat(a['rowptr'][rows], counts).astype('int64')
        within = np.arange(int(counts.sum()))-np.repeat(np.cumsum(counts)-counts, counts)
        indices = starts+within.astype('int64')
        output = np.zeros((len(rows), len(self.feature_names)), dtype=dtype)
        output[np.repeat(np.arange(len(rows)), counts), a['columns'][indices]] = a['values'][indices]
        return output
