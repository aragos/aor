"""Typed public expansion proposals and separately admitted paired terminal labels.

No old territory schema is relabeled. Public projections are produced by the outcome-skipping
Java adapter; the corpus manifest pins every original gzip and projection before this reader runs.
"""
from dataclasses import dataclass
import gzip
import hashlib
import json
import math
from pathlib import Path
import re

import purchase_value_data as base

read, sha, require, document = base.read, base.sha, base.require, base.document
ENCODER = 'aor-expansion-paired-observation-1'
KIND = 'expansion-paired-fit-corpus-1'
METRICS = ('reward', 'winShare', 'chaos')


def hash_value(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def split(index):
    require(type(index) is int and 0 <= index < 1024, 'Expansion origin index must be0..1023.')
    return 'TRAIN' if index < 768 else 'VALIDATION' if index < 896 else 'TEST'


def finite(value, low=-16., high=16.):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def dictionary(value):
    require(set(value) == {'encoderVersion', 'dictionary'} and value['encoderVersion'] == ENCODER,
            'Only the separately tagged expansion paired dictionary is accepted.')
    names = value['dictionary']
    require(set(names) == {'stateFeatureNames', 'actionFeatureNames'}, 'Complete state/action dictionaries required.')
    for key, maximum in [('stateFeatureNames', 8192), ('actionFeatureNames', 512)]:
        seq = names[key]
        require(isinstance(seq, list) and 0 < len(seq) <= maximum and all(type(n) is str and n for n in seq)
                and seq == sorted(set(seq)), 'A complete canonical dictionary is required: '+key)
    return names


def prepare_manifest(native, public_directory, output):
    """Join only native completion/source metadata and already admitted TRAIN/VAL projections.

    The caller first stages immutable subjects beneath output.parent. This function neither runs
    the exporter nor semantically reads an original gzip. TEST projection files are forbidden.
    """
    native, public_directory, output = Path(native).resolve(), Path(public_directory).resolve(), Path(output).resolve()
    require(not output.exists(), 'A corpus declaration cannot replace earlier evidence.')
    def subject(path):
        path = Path(path)
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(output.parent),
                'Staged immutable corpus subjects must remain inside its root.')
        return dict(path=str(path.relative_to(output.parent)), sha256=sha(path))
    prepared = read(native/'prepared.json'); finished = read(native/'finished.json')
    schema = read(native/'dictionary.json'); dictionary(schema)
    require(prepared['kind'] == 'expansion-paired-data-1' and finished['complete'] is True
            and prepared['signature'] == finished['signature']
            and prepared['dictionarySha256'] == sha(native/'dictionary.json')
            and len(prepared['requests']) == len(prepared['origins']) == len(finished['origins']) == 1024,
            'Only exact complete native source/dictionary/status metadata may declare the corpus.')
    origins = []
    for index, (request, streams, receipt) in enumerate(zip(prepared['requests'],prepared['origins'],finished['origins'])):
        require(request['index'] == index and request['split'] == split(index) and receipt['index'] == index,
                'Original request order and whole-origin splits must remain unchanged.')
        original = subject(native/receipt['file'])
        require(original['sha256'] == receipt['sha256'], 'Original compressed evidence changed.')
        row = dict(index=index, split=split(index), seat=request['seat'], signature=prepared['signature'],
                   status='COLLECTED' if receipt['captured'] else 'MISSING', sourceSha256=original['sha256'],
                   futureSeeds=streams['future'], original=original)
        projection = public_directory/f'origin-{index}.json'
        metadata = projection.with_name(projection.name+'.metadata.json')
        if split(index) == 'TEST':
            require(not projection.exists() and not metadata.exists(), 'TEST actor inputs must not be pre-exported.')
        else:
            meta = read(metadata)
            require(meta['kind'] == 'expansion-paired-public-metadata-1' and meta['index'] == index
                    and meta['sourceSha256'] == row['sourceSha256'] and meta['signature'] == row['signature']
                    and meta['status'] == row['status'] and meta['request'] == request and meta['streams'] == streams,
                    'Public metadata must match original source requests and completion status.')
            row.update(commands=meta['commands'], actionIndices=meta['actionIndices'],
                       baselineIndex=meta['baselineIndex'], public=subject(projection), publicMetadata=subject(metadata))
            public_origin(read(projection),row,schema['dictionary'])
        origins.append(row)
    manifest = dict(kind=KIND,encoderVersion=ENCODER,dictionary=schema,origins=origins,
                    prepared=subject(native/'prepared.json'),completion=subject(native/'finished.json'),
                    dictionarySource=subject(native/'dictionary.json'))
    document(output,manifest)
    return manifest


def public_origin(value, metadata, names):
    require(set(value) == {'kind', 'index', 'signature', 'sourceSha256', 'status', 'encoded', 'actionIndices'}
            and value['kind'] == 'expansion-paired-public-1'
            and value['index'] == metadata['index'] and value['signature'] == metadata['signature']
            and value['sourceSha256'] == metadata['sourceSha256'] and value['status'] == metadata['status'],
            'Public origin must retain exact original provenance and outcome-free fields.')
    if value['status'] == 'MISSING':
        require(value['encoded'] is None and value['actionIndices'] == [], 'Missing roots have no invented actions.')
        return value
    require(value['status'] == 'COLLECTED', 'Unknown public root status.')
    encoded = value['encoded']; indices = value['actionIndices']
    require(set(encoded) == {'encoderVersion', 'stateFeatures', 'actions', 'baselineIndex'}
            and encoded['encoderVersion'] == ENCODER, 'Research encoder identity must remain explicit.')
    actions = encoded['actions']; commands = [a['command'] for a in actions]
    require(2 <= len(actions) <= 8 and indices == sorted(set(indices)) and len(indices) == len(actions)
            and all(type(i) is int and 0 <= i < 1024 for i in indices)
            and commands == sorted(set(commands)) and all(type(c) is str and c for c in commands)
            and type(encoded['baselineIndex']) is int and 0 <= encoded['baselineIndex'] < len(actions),
            'Keep the bounded original offered indices, canonical commands and exact E reference.')
    if metadata['split'] != 'TEST':
        require(commands == metadata['commands'] and encoded['baselineIndex'] == metadata['baselineIndex']
                and indices == metadata['actionIndices'], 'Outcome-free action coverage metadata differs.')
    for features, vocabulary in [(encoded['stateFeatures'], names['stateFeatureNames'])] + [
            (a['features'], names['actionFeatureNames']) for a in actions]:
        require(isinstance(features, dict) and set(features) <= set(vocabulary)
                and all(finite(v) for v in features.values()), 'Only finite original encoder values are inputs.')
    require(all(set(a) == {'command', 'features'} for a in actions), 'Action metadata cannot become input features.')
    return value


def coverage(origins):
    """Status/commands only; no feature values, returns or cohort-dependent selection."""
    result = {}
    for part in ('TRAIN', 'VALIDATION', 'TEST'):
        rows = [r for r in origins if split(r['index']) == part]
        ready = [r for r in rows if r['status'] == 'COLLECTED']
        result[part] = dict(requests=len(rows), ready=len(ready), missing=len(rows)-len(ready),
            card=None if part == 'TEST' else sum('buy-expansion-card' in r['commands'] for r in ready),
            destination=None if part == 'TEST' else
                sum(any(c.startswith(('place ', 'attack ')) for c in r['commands']) for r in ready))
    return result


def training_coverage(counts):
    return all(counts[p]['ready'] >= n and counts[p]['card'] >= c and counts[p]['destination'] >= c
               for p, n, c in [('TRAIN', 256, 64), ('VALIDATION', 64, 16)])


def choose(row, values):
    require(row['status'] == 'COLLECTED' and len(values) == len(row['encoded']['actions'])
            and all(finite(v, 0., 1.) for v in values), 'Selector requires every frozen proposed Q.')
    baseline = row['encoded']['baselineIndex']
    winner = min(range(len(values)), key=lambda i: (-values[i], row['encoded']['actions'][i]['command']))
    return winner if values[winner] > values[baseline] else baseline


def validation_choice(metrics):
    require(set(metrics) == set(range(1, 21)), 'Selection requires all20 completed checkpoints.')
    require(all(finite(m['gain']['reward']['mean'], -1, 1) and finite(m['pairedMse'], 0, 4)
                for m in metrics.values()), 'Selection metrics must be finite paired means and errors.')
    best = min(metrics, key=lambda epoch: (-metrics[epoch]['gain']['reward']['mean'],
                                         metrics[epoch]['pairedMse'], epoch))
    return best if metrics[best]['gain']['reward']['mean'] > 0 else None


@dataclass(frozen=True)
class PredictionSeal:
    path: Path
    sha256: str

    def read(self):
        require(sha(self.path) == self.sha256, 'Sealed predictions changed.')
        return read(self.path)


@dataclass(frozen=True)
class SelectionSeal:
    path: Path
    sha256: str

    def read(self, corpus_sha):
        require(sha(self.path) == self.sha256, 'The published validation selection changed.')
        value = read(self.path)
        require(value['kind'] == 'expansion-paired-selection-1' and value['corpusSha256'] == corpus_sha
                and type(value['epoch']) is int and value['epoch'] in range(1, 21)
                and finite(value['validationGain'], 0, 1) and value['validationGain'] > 0
                and hash_value(value['checkpoint']['sha256'])
                and hash_value(value['validationPredictionsSha256']),
                'TEST requires a completed positive validation selection and exact checkpoint.')
        metrics = {int(k): v for k, v in value['metrics'].items()}
        require(validation_choice(metrics) == value['epoch']
                and value['validationGain'] == metrics[value['epoch']]['gain']['reward']['mean'],
                'The selected epoch must equal the prescribed complete validation comparison.')
        return value


class Corpus:
    """Manifest metadata is public; only explicit partition methods open features or labels."""
    def __init__(self, path, *, test_projector=None):
        self.path = Path(path).resolve(); self.raw = read(self.path); self.sha256 = sha(self.path)
        require(self.raw['kind'] == KIND and self.raw['encoderVersion'] == ENCODER,
                'Old territory/critic corpus contracts are not this research corpus.')
        self.names = dictionary(self.raw['dictionary'])
        self.origins = self.raw['origins']
        require(len(self.origins) == 1024, 'Retain all1024 prospective requests.')
        completion = read(self.subject(self.raw, 'completion'))
        prepared = read(self.subject(self.raw, 'prepared'))
        require(read(self.subject(self.raw, 'dictionarySource')) == self.raw['dictionary']
                and self.raw['dictionarySource']['sha256'] == prepared['dictionarySha256']
                and prepared['kind'] == 'expansion-paired-data-1'
                and prepared['signature'] == completion['signature']
                and len(prepared['requests']) == len(prepared['origins']) == 1024,
                'Complete original dictionaries and prepared request identities are required.')
        require(completion['complete'] is True and len(completion['origins']) == 1024,
                'Only a complete original native collection can supply this corpus.')
        for index, row in enumerate(self.origins):
            require(row['index'] == index and row['split'] == split(index)
                    and row['status'] in ('COLLECTED', 'MISSING') and len(row['futureSeeds']) == 2
                    and len(set(row['futureSeeds'])) == 2 and all(type(v) is int for v in row['futureSeeds'])
                    and row['seat'] == index % 4,
                    'Original index, split, status, seat and paired stream identities are required.')
            require(hash_value(row['sourceSha256']) and hash_value(row['signature'])
                    and row['original']['sha256'] == row['sourceSha256'],
                    'Original source/signature digests are required.')
            receipt = completion['origins'][index]
            request = prepared['requests'][index]
            require(receipt['index'] == index and receipt['sha256'] == row['sourceSha256']
                    and receipt['captured'] == (row['status'] == 'COLLECTED')
                    and completion['signature'] == row['signature'] and request['index'] == index
                    and request['split'] == row['split'] and request['seat'] == row['seat']
                    and prepared['origins'][index]['future'] == row['futureSeeds'],
                    'All status/hash metadata must equal the completed native receipt.')
            if row['split'] == 'TEST':
                require(all(row.get(k) is None for k in ('commands', 'actionIndices', 'baselineIndex', 'public')),
                        'Initial TEST metadata must not include exported actor inputs or proposals.')
            elif row['status'] == 'MISSING':
                require(row['commands'] == [] and row['actionIndices'] == [] and row['baselineIndex'] is None,
                        'Missing requests retain empty public coverage.')
            else:
                require(2 <= len(row['commands']) <= 8 and row['commands'] == sorted(set(row['commands']))
                        and len(row['actionIndices']) == len(row['commands'])
                        and row['actionIndices'] == sorted(set(row['actionIndices']))
                        and all(type(i) is int and 0 <= i < 1024 for i in row['actionIndices'])
                        and type(row['baselineIndex']) is int and 0 <= row['baselineIndex'] < len(row['commands']),
                        'Captured metadata must retain a distinct bounded proposal set and E.')
        self.counts = coverage(self.origins)
        self._public = {}
        self._public_hashes = {}
        self.test_projector = test_projector

    def subject(self, row, key):
        value = row[key]; p = Path(value['path'])
        require(not p.is_absolute() and '..' not in p.parts, 'Corpus inputs must be relative frozen subjects.')
        p = self.path.parent / p
        require(p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(self.path.parent)
                and sha(p) == value['sha256'], 'A corpus subject changed: '+key)
        return p

    def public(self, part, selection=None):
        require(part in ('TRAIN', 'VALIDATION', 'TEST'), 'Unknown prospective partition.')
        if part == 'TEST':
            require(isinstance(selection, SelectionSeal) and self.counts['TEST']['ready'] >= 64,
                    'Only a fixed positive VAL choice with at least64 READY TEST roots opens TEST features.')
            selection.read(self.sha256)
        if part not in self._public:
            metadata = [r for r in self.origins if r['split'] == part]
            if part == 'TEST':
                require(self.test_projector is not None, 'TEST requires the separately pinned lazy public exporter.')
                # The bounded runner owns this outcome-skipping JVM operation and new output directory.
                # It receives only original immutable subjects after selection has been published.
                paths = self.test_projector([(r['index'], self.subject(r, 'original')) for r in metadata])
                require(set(paths) == {r['index'] for r in metadata}, 'Retain every TEST request including missing roots.')
                subjects = [Path(paths[r['index']]) for r in metadata]
                require(all(p.is_file() and not p.is_symlink() for p in subjects), 'TEST public outputs must be regular files.')
            else:
                subjects = [self.subject(r, 'public') for r in metadata]
            self._public_hashes[part] = {str(r['index']): sha(p) for r,p in zip(metadata, subjects)}
            self._public[part] = [public_origin(read(p), r, self.names) for r,p in zip(metadata, subjects)]
        return self._public[part]

    def public_hashes(self, part):
        require(part in self._public_hashes, 'Public inputs must be admitted before their seal.')
        return dict(self._public_hashes[part])

    def labels(self, part, seal=None, selection=None):
        """Only this method semantically decodes terminal outcomes; TRAIN needs no predictions."""
        require(sha(self.path) == self.sha256, 'Corpus changed after admission.')
        rows = self.public(part, selection)
        if part != 'TRAIN':
            require(isinstance(seal, PredictionSeal), 'Nontraining labels require a persisted prediction seal.')
            chosen = selection.read(self.sha256) if part == 'TEST' else None
            value = seal.read(); expected = list(range(1,21)) if part == 'VALIDATION' else [chosen['epoch']]
            require(value['kind'] == 'expansion-paired-predictions-1' and value['corpusSha256'] == self.sha256
                    and value['partition'] == part and value['epochs'] == expected
                    and value['publicSha256'] == self.public_hashes(part)
                    and set(value['predictions']) == {str(e) for e in expected}
                    and set(value['checkpoints']) == {str(e) for e in expected}
                    and all(hash_value(c['sha256']) for c in value['checkpoints'].values()),
                    'All20 VAL checkpoints or only the selected TEST checkpoint must be sealed first.')
            if chosen is not None:
                require(value['selectionSha256'] == selection.sha256
                        and value['checkpoints'][str(chosen['epoch'])] == chosen['checkpoint'],
                        'TEST predictions must bind only the already selected model and selection bytes.')
            for epoch in expected:
                predictions = value['predictions'][str(epoch)]
                require([p['index'] for p in predictions] == [r['index'] for r in rows if r['status']=='COLLECTED'],
                        'Predictions must cover every captured partition origin exactly once.')
                for p, row in zip(predictions, [r for r in rows if r['status']=='COLLECTED']):
                    require(p['choice'] == choose(row, p['values']), 'Sealed choices must implement the fixed public selector.')
        labels = {}
        for row in rows:
            if row['status'] == 'MISSING': continue
            meta = self.origins[row['index']]
            with gzip.open(self.subject(meta, 'original'), 'rt') as stream: original = json.load(stream)
            require(original['kind'] == 'expansion-paired-origin-1' and original['signature'] == meta['signature']
                    and original['request']['index'] == row['index'] and original['request']['split'] == part
                    and original['request']['seat'] == meta['seat'] and original['status'] == 'COLLECTED'
                    and original['streams']['future'] == meta['futureSeeds'] and original['exactIncumbentVerified'],
                    'Original paired-label provenance changed.')
            encoded = original['actorInput']['encoded']
            require(encoded['encoderVersion'] == ENCODER and encoded['stateFeatures'] == row['encoded']['stateFeatures']
                    and [encoded['actions'][i] for i in row['actionIndices']] == row['encoded']['actions']
                    and row['actionIndices'][row['encoded']['baselineIndex']] == encoded['baselineIndex']
                    and encoded['actions'][encoded['baselineIndex']]['command'] == original['incumbentCommand'],
                    'The public subset must equal the original encoder and exact E reference.')
            arms = {arm['proposal']['actionIndex']:arm for arm in original['arms']}
            require(set(arms) == set(row['actionIndices']) and len(arms) == len(original['arms']), 'Arm coverage changed.')
            result = []
            for position, index in enumerate(row['actionIndices']):
                arm=arms[index]; require(arm['proposal']['command'] == row['encoded']['actions'][position]['command'], 'Original action differs.')
                require(len(arm['outcomes']) == 2, 'Every arm retains both future labels.')
                metrics = {k:[] for k in METRICS}
                for replicate, outcome in enumerate(arm['outcomes']):
                    t=outcome['terminal']
                    require(outcome['replicate']==replicate and outcome['replayVerified']
                            and t['continuationSeed']==meta['futureSeeds'][replicate]
                            and all(finite(t[k],0,1) for k in ('reward','winShare','rankReward'))
                            and abs(t['reward']-.8*t['winShare']-.2*t['rankReward'])<1e-12,
                            'Paired future/reward/canonical completion changed.')
                    own=[s for s in t['scores'] if s['player']==f'p{meta["seat"]+1}']
                    require(len(own)==1, 'Controlled terminal score is required.')
                    metrics['reward'].append(t['reward']);metrics['winShare'].append(t['winShare'])
                    metrics['chaos'].append(float(own[0]['total'] is None))
                result.append({k:sum(v)/2 for k,v in metrics.items()})
            labels[row['index']]=result
        return labels
