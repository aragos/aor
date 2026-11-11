"""Whole-game splits and bounded lossless CSR input for purchase-state value benchmarks.

These inputs contain encoded actor-visible purchase states, not engine states or action-Q labels.
The source inventory supplies game identities and counts only. Reward values are opened by the
explicit run's cache stage, after the experiment protocol is frozen.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import json
import math
from pathlib import Path
import tarfile

STATE_COUNT = 3556
GIB = 1024**3
CORE = ('automata-purchase-continuation-20261110', 'automata-purchase-generation-20261110',
        'automata-purchase-replication-20261110')
TRANSFER = 'automata-purchase-p80-transfer-20261110'
TEST = 'automata-purchase-ecology-recovery-20261111'
DATASETS = (*CORE, TRANSFER, TEST)
C64 = '62dae4a44c84fca9ae39a50fdf93e1001d4b3e1ef55db736b1d7e387559a5a44'


def require(condition, message):
    if not condition:
        raise ValueError(message)


@dataclass(frozen=True)
class Config:
    epochs: int = 20
    learningRate: float = .001
    gamesPerBatch: int = 64
    maximumDenseRows: int = 4096
    bootstrapReplicates: int = 2000
    smallSeed: int = 910000000001
    largeSeed: int = 910000000002
    orderSeed: int = 910000000003
    bootstrapSeed: int = 910000000004

    def validate(self):
        require(asdict(self) == asdict(Config()), 'The benchmark has one fixed optimizer/grid/seed contract.')
        return self


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            digest.update(block)
    return digest.hexdigest()


def document(path, value):
    path = Path(path)
    require(not path.exists(), 'Benchmark evidence cannot overwrite an existing artifact.')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as stream:
        stream.write(json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n')


def split(dataset, batch):
    """Chronological ownership is by batch, never by row, reward or model performance."""
    number = int(batch.removeprefix('batch-'))
    require(dataset in DATASETS and 1 <= number <= (8 if dataset == TEST else 16),
            'Unknown dataset or chronological batch.')
    if dataset == TEST:
        return 'test'
    if dataset == TRANSFER and number > 12:
        return 'validation'
    return 'training'


def declaration(inventory, games):
    """Declare the exact deduplicated corpus without opening outcomes or feature rows."""
    selected = [row for row in games if row['dataset'] in DATASETS]
    require(len(selected) == 18432 and len({row['sha256'] for row in selected}) == len(selected),
            'Require all 18,432 distinct retained game records without recovered copies.')
    require(len({row['gameSeed'] for row in selected}) == len(selected),
            'Every split must remain disjoint by actual game seed, not only file hash.')
    selected.sort(key=lambda row: (DATASETS.index(row['dataset']),
                                  int(row['batch'].removeprefix('batch-')), row['index']))
    declared = [dict(row, id=index, split=split(row['dataset'], row['batch']))
                for index, row in enumerate(selected)]
    counts = {part: sum(row['split'] == part for row in declared)
              for part in ('training', 'validation', 'test')}
    require(counts == dict(training=15360, validation=1024, test=2048),
            'Prospective whole-game split counts changed.')
    batches = [row for row in inventory['batches'] if row['dataset'] in DATASETS]
    require(len(batches) == 72 and all(row['archive'] for row in batches),
            'Use only the 72 completed lossless archive batches.')
    return dict(schemaVersion=1, kind='purchase-state-value-corpus-1', games=declared,
                batches=batches, splitCounts=counts,
                rows=sum(row['decisions'] for row in declared),
                nonzeros=sum(row['statistics']['stateFeatureValues'] for row in batches),
                noRowSplit=True, terminalLabelsOpenedDuringPreparation=False)


def verify_declaration(corpus):
    games = corpus['games']
    require(corpus['kind'] == 'purchase-state-value-corpus-1'
            and len(games) == len({g['gameSeed'] for g in games})
            == len({g['sha256'] for g in games}) == 18432,
            'Corpus identity or whole-seed independence changed.')
    for index, game in enumerate(games):
        require(game['id'] == index and game['split'] == split(game['dataset'], game['batch']),
                'Game order or prospective split changed.')
    require({p: sum(g['split'] == p for g in games) for p in ('training', 'validation', 'test')}
            == corpus['splitCounts'] == dict(training=15360, validation=1024, test=2048),
            'Train, validation and untouched test counts changed.')


def encode_state(features, names):
    """Preserve original encoder units and IEEE Float64 values; do not fit normalization."""
    require(isinstance(features, dict) and set(features) <= set(names),
            'A purchase state uses an unknown feature.')
    require(all(type(value) in (int, float) and math.isfinite(value) and abs(value) <= 16
                for value in features.values()), 'State values must retain the encoder range.')
    ordered = sorted((names[name], float(value)) for name, value in features.items())
    return [index for index, _ in ordered], [value for _, value in ordered]


def build_cache(package, output, guard):
    """Stream one source game at a time; never expand an archive to a JSON directory."""
    import numpy as np
    package, output = Path(package), Path(output)
    corpus = read(package/'corpus.json')
    verify_declaration(corpus)
    model = read(package/'inputs/c64.json')
    require(sha(package/'inputs/c64.json') == C64 and len(model['stateFeatureNames']) == STATE_COUNT,
            'The benchmark requires exact C64 and its original 3,556-state dictionary.')
    names = {name: index for index, name in enumerate(model['stateFeatureNames'])}
    require(not output.exists(), 'CSR construction is a single attempt with a new output.')
    output.mkdir(parents=True)
    arrays = {}
    specifications = dict(values=('float64', corpus['nonzeros']),
                          columns=('uint16', corpus['nonzeros']),
                          rowptr=('uint64', corpus['rows'] + 1),
                          rowGame=('uint32', corpus['rows']), epoch=('uint8', corpus['rows']),
                          stage=('uint8', corpus['rows']), reward=('float64', len(corpus['games'])),
                          starts=('uint64', len(corpus['games'])), stops=('uint64', len(corpus['games'])))
    for name, (dtype, count) in specifications.items():
        arrays[name] = np.lib.format.open_memmap(output/(name+'.npy'), mode='w+', dtype=dtype, shape=(count,))
    by_batch = {}
    for game in corpus['games']:
        by_batch.setdefault((game['dataset'], game['batch']), {})[game['index']] = game
    row_offset = value_offset = 0
    arrays['rowptr'][0] = 0
    seen_games = set()
    for batch in corpus['batches']:
        guard()
        archive = package/batch['packageArchive']
        metadata = read(package/batch['packageMetadata'])
        require(sha(archive) == batch['archiveSha256'] and
                sha(package/batch['packageMetadata']) == batch['metadataSha256'],
                'A pinned source archive or metadata changed.')
        require(metadata['stateFeatureNames'] == model['stateFeatureNames'] and
                metadata['dataVersion'] == 'ah1996-catalog-16', 'Source feature/catalog identity changed.')
        batch_games = by_batch[(batch['dataset'], batch['batch'])]
        seen_indices = set()
        with tarfile.open(archive, 'r:gz') as source:
            for member in source:
                guard()
                require(member.isfile() and Path(member.name).name.startswith('game-'),
                        'Unexpected non-trajectory archive member.')
                content = source.extractfile(member).read()
                native = json.loads(content)
                index = native['index']
                require(index in batch_games and index not in seen_indices, 'Duplicate or unreserved game.')
                seen_indices.add(index)
                game = batch_games[index]
                require(Path(member.name).name == f'game-{index}.json' and
                        hashlib.sha256(content).hexdigest() == game['sha256'] and
                        native['gameSeed'] == game['gameSeed'] and native['controlledSeat'] == game['seat']
                        and native['signature'] == metadata['signature'], 'A source game identity changed.')
                records = native['decisions']
                require(len(records) == game['decisions'] > 0, 'Game decision coverage changed.')
                game_id = game['id']
                terminal = native['terminal']
                require(math.isclose(terminal['reward'], .8*terminal['winShare']+.2*terminal['rankReward'],
                                     abs_tol=1e-10) and 0 <= terminal['reward'] <= 1,
                        'The public terminal objective changed.')
                arrays['reward'][game_id] = terminal['reward']
                arrays['starts'][game_id] = row_offset
                for record in records:
                    observation = record['observation']
                    require(observation['encoderVersion'] == 'aor-purchase-observation-1',
                            'Unsupported purchase state encoder.')
                    indices, values = encode_state(observation['stateFeatures'], names)
                    stop = value_offset + len(values)
                    require(stop <= corpus['nonzeros'] and row_offset < corpus['rows'], 'CSR budget changed.')
                    arrays['columns'][value_offset:stop], arrays['values'][value_offset:stop] = indices, values
                    arrays['rowptr'][row_offset+1] = stop
                    arrays['rowGame'][row_offset] = game_id
                    epoch = round(observation['stateFeatures']['epoch']*3)
                    require(epoch in (1, 2, 3) and
                            abs(observation['stateFeatures']['epoch'] - epoch/3) < 1e-12,
                            'Epoch feature must retain the original encoder units.')
                    require(record['decision'] in ('purchase-shipping', 'purchase-advances'),
                            'Only purchase-stage states belong in this critic benchmark.')
                    arrays['epoch'][row_offset] = epoch
                    arrays['stage'][row_offset] = int(record['decision'] == 'purchase-advances')
                    row_offset, value_offset = row_offset+1, stop
                arrays['stops'][game_id] = row_offset
                seen_games.add(game_id)
        require(seen_indices == set(batch_games), 'An archive omitted a prospective whole game.')
    require(row_offset == corpus['rows'] and value_offset == corpus['nonzeros']
            and seen_games == set(range(len(corpus['games']))), 'Completed CSR coverage changed.')
    for value in arrays.values():
        value.flush()
    guard()
    document(output/'finished.json', dict(kind='purchase-value-csr-1', rows=row_offset,
        nonzeros=value_offset, games=len(seen_games), corpusSha256=sha(package/'corpus.json'),
        files={path.name: sha(path) for path in output.glob('*.npy')},
        rawValues='float64', normalization='unchanged-encoder-units', newGames=0))


class Cache:
    """Read-only CSR arrays; only a bounded batch is ever materialized densely."""
    def __init__(self, directory, verify=True):
        import numpy as np
        self.np = np
        self.root = Path(directory)
        done = read(self.root/'finished.json')
        if verify:
            require(all(sha(self.root/name) == digest for name, digest in done['files'].items()),
                    'Completed CSR bytes changed.')
        self.arrays = {name.removesuffix('.npy'): np.load(self.root/name, mmap_mode='r')
                       for name in done['files']}

    def game_rows(self, games):
        a = self.arrays
        return self.np.concatenate([self.np.arange(a['starts'][g], a['stops'][g], dtype='int64')
                                   for g in games])

    def dense(self, rows, dtype='float32'):
        np, a = self.np, self.arrays
        counts = (a['rowptr'][rows+1]-a['rowptr'][rows]).astype('int64')
        starts = np.repeat(a['rowptr'][rows], counts.astype('int64')).astype('int64')
        within = np.arange(int(counts.sum()))-np.repeat(np.cumsum(counts)-counts, counts.astype('int64'))
        indices = starts + within.astype('int64')
        output = np.zeros((len(rows), STATE_COUNT), dtype=dtype)
        output[np.repeat(np.arange(len(rows)), counts.astype('int64')), a['columns'][indices]] = a['values'][indices]
        return output
