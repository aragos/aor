"""Public canonical plan rows and separately admitted paired labels for fixed70.

Native completion owns execution/replay semantics. This reader preserves every original world,
uses only the existing public leaf dictionary, and never decodes TEST outcome members while
building DEVELOPMENT or feature-only TEST caches. Archives stay portable and byte-bound.
"""
import hashlib
import json
import math
from pathlib import Path
import tarfile

import afterstate_value_data as states
import automata_experiments as exp
import purchase_storage as storage

read, sha, require, document = exp.read, exp.sha, exp.require, exp.document
KIND = 'purchase-plan-difference-corpus-1'
CHECKPOINT = 'd45f59654e5fcd6c5d36ea1eea163781596f0433feb810b4a5dff1d71ca6202b'
DICTIONARY = 'aba7c81037b6549a0ae1c6c3b77f00d377cc7c6e248e580aa36155d2326bd4a8'
METRICS = ('reward', 'winShare', 'rankReward', 'chaos')
DECISIONS = ('purchase-advances', 'purchase-stabilization', 'purchase-relief')
KINDS = ('INCUMBENT', 'ADVANCE_PASS', 'SAMPLED', 'SAMPLED', 'SAMPLED', 'UNIFORM', 'UNIFORM', 'UNIFORM')


def canonical_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def select_validation(records):
    """Strict VAL error selection: unchanged67, then earliest epoch, then head-only on ties."""
    require(records and all(math.isfinite(r['validationMse']) and r['validationMse'] >= 0
        and r['arm'] in ('unchanged', 'head', 'full') and type(r['epoch']) is int
        and ((r['arm'] == 'unchanged' and r['epoch'] == 0)
             or (r['arm'] in ('head', 'full') and 1 <= r['epoch'] <= 20)) for r in records),
        'Selection requires only original67 or the forty prescribed validation candidates.')
    return min(records, key=lambda r: (r['validationMse'], r['arm'] != 'unchanged',
                                      r['epoch'], 0 if r['arm'] == 'head' else 1))


def split(index):
    require(type(index) is int and 0 <= index < 2048, 'A prospective origin index is0..2047.')
    return 'TRAIN' if index < 1536 else 'VALIDATION' if index < 1792 else 'TEST'


def relative(root, name):
    path = Path(name)
    require(not path.is_absolute() and '..' not in path.parts and path.parts,
            'Stored evidence paths must remain bounded and relative.')
    target = Path(root) / path
    require(not target.is_symlink() and target.resolve().is_relative_to(Path(root).resolve()),
            'Stored evidence cannot traverse a symlink outside its owner.')
    return target


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def public_origin(value, index, names):
    """Validate public-only native wire; commands/metadata never become model columns."""
    require(set(value) == {'version', 'originIndex', 'status', 'plans'}
        and value['version'] == 'purchase-plan-ranking-1' and type(value['originIndex']) is int and value['originIndex'] == index
        and value['status'] in ('READY', 'MISSING', 'INCUMBENT_UNSUPPORTED'), 'Public origin identity changed.')
    plans = value['plans']
    if value['status'] == 'MISSING':
        require(plans == [], 'Missing roots retain no invented plans.')
        return value
    require(len(plans) == 8, 'Every captured root retains exactly eight logical proposals.')
    for i, plan in enumerate(plans):
        require(set(plan) == {'index', 'kind', 'actions', 'status', 'exclusion', 'leaf', 'aliasOf'}
            and type(plan['index']) is int and plan['index'] == i and plan['kind'] == KINDS[i]
            and plan['status'] in ('SUPPORTED', 'UNSUPPORTED') and isinstance(plan['actions'], list)
            and len(plan['actions']) <= 64, 'The fixed logical plan bank changed.')
        require(all(set(action) == {'decision', 'command'} and action['decision'] in DECISIONS
                    and isinstance(action['command'], str) and action['command'] for action in plan['actions']),
                'Plan actions retain only their exact original public decision/command.')
        leaf = plan['leaf']
        if plan['status'] == 'UNSUPPORTED':
            require(leaf is None and plan['exclusion'] in ('HYPOTHETICAL', 'INVALID', 'INFORMATION',
                'INTERVENING_CHOICE', 'CHOICE_CAP'), 'Unsupported plans retain their original boundary reason.')
        else:
            require(plan['actions'] and plan['exclusion'] is None and isinstance(leaf, dict)
                and set(leaf) == {'context', 'stateFeatures', 'exactValue', 'exactReason'}
                and leaf['context'] == 'OWNER_EXIT', 'Supported plans require a genuine complete owner-exit leaf.')
            features = leaf['stateFeatures']
            require(isinstance(features, dict) and set(features) <= set(names)
                and all(name.startswith('purchase.') and finite(v) and -16 <= v <= 16 for name, v in features.items())
                and features.get('purchase.decision.purchase-owner-exit') == 1.
                and all(v == 0 for name, v in features.items() if name.startswith('purchase.decision.')
                        and name != 'purchase.decision.purchase-owner-exit'),
                'Only original own/public purchase-state columns and units are admitted.')
            reason, exact = leaf['exactReason'], leaf['exactValue']
            require(reason in (None, 'OWN_CHAOS', 'GAME_TERMINAL') and (exact is None) == (reason is None)
                and (exact is None or finite(exact) and 0 <= exact <= 1), 'Exact bypass must be an authoritative normalized value.')
            if reason == 'OWN_CHAOS':
                require(exact == 0 and features.get('purchase.role.0.chaos') == 1., 'Own Chaos bypass requires factual own elimination.')
        alias = plan['aliasOf']
        require(alias is None or type(alias) is int and 0 <= alias < i and plans[alias]['aliasOf'] is None
            and all(plan[key] == plans[alias][key] for key in ('actions', 'status', 'exclusion', 'leaf')),
            'Aliases must refer directly to an identical earlier canonical execution.')
    require((value['status'] == 'READY') == (plans[0]['status'] == 'SUPPORTED'), 'Reference support determines READY before labels.')
    return value


def metadata(value, chunk, public_sha, source_sha, signature, game_seed):
    index = (chunk - 1) * 256 + value['originIndex']
    plans = [{key: plan[key] for key in ('index', 'status', 'aliasOf')} for plan in value['plans']]
    canonical = sum(p['status'] == 'SUPPORTED' and p['aliasOf'] is None for p in plans) if value['status'] == 'READY' else 0
    return dict(index=index, chunk=chunk, localIndex=value['originIndex'], split=split(index), status=value['status'],
        plans=plans, informative=value['status'] == 'READY' and canonical > 1, canonicalPlans=canonical,
        publicSha256=public_sha, sourceSha256=source_sha, signature=signature, gameSeed=game_seed)


def verify_corpus(corpus):
    names = states.dictionary_names(corpus['dictionary'])
    require(corpus['kind'] == KIND and corpus['stateFeatureNames'] == names
        and corpus['dictionarySha256'] == DICTIONARY and corpus['warmCheckpointSha256'] == CHECKPOINT
        and len(corpus['origins']) == 2048 and len(corpus['chunks']) == 8,
        'The fixed six/one/one whole-world corpus and original67 dictionary/checkpoint are required.')
    for i, origin in enumerate(corpus['origins']):
        chunk = corpus['chunks'][i // 256]
        require(origin['index'] == i and origin['chunk'] == i // 256 + 1 and origin['localIndex'] == i % 256
            and origin['split'] == split(i) and origin['status'] in ('READY', 'MISSING', 'INCUMBENT_UNSUPPORTED'),
            'Origin identity/split cannot be inferred from availability or outcomes.')
        require(chunk['index'] == i // 256 + 1 and chunk['split'] == split(i)
            and origin['publicSha256'] == chunk['files'][f'public/public-{i % 256}.json']['sha256']
            and origin['sourceSha256'] == chunk['sourceSha256'] and origin['signature'] == chunk['signature']
            and origin['gameSeed'] == chunk['gameSeeds'][i % 256], 'Origin/archive/source provenance changed.')
        count = sum(p['status'] == 'SUPPORTED' and p['aliasOf'] is None for p in origin['plans']) if origin['status'] == 'READY' else 0
        require(origin['canonicalPlans'] == count and origin['informative'] == (origin['status'] == 'READY' and count > 1),
            'No-alternative worlds are excluded from pair loss by public support only.')
    require(len({o['gameSeed'] for o in corpus['origins']}) == 2048, 'Original worlds must be distinct.')
    require(len(corpus['sourceSha256']) == 64 and all(c in '0123456789abcdef' for c in corpus['sourceSha256']), 'Corpus provenance requires a SHA256 subject.')
    counts = {name: sum(o['informative'] for o in corpus['origins'] if o['split'] == name) for name in ('TRAIN', 'VALIDATION')}
    counts['TEST'] = sum(o['status'] == 'READY' for o in corpus['origins'] if o['split'] == 'TEST')
    require(counts['TRAIN'] >= 1200 and counts['VALIDATION'] >= 160 and counts['TEST'] >= 160,
            'Public coverage is insufficient; stop without refill or TEST label access.')
    return names


def completed_chunk(directory, prepared, source, chunk_index, names):
    """Public-only admission: terminal result/canonical members are always opaque here."""
    directory = Path(directory)
    public, tails = directory / 'public', directory / 'tails'
    manifest, finished = read(public / 'manifest.json'), read(public / 'finished.json')
    require(manifest == prepared == read(tails / 'manifest.json')
        and finished['kind'] == 'purchase-plan-public-complete-1' and finished['signature'] == manifest['signature']
        and finished['origins'] == 256 and finished['sourceSha256'] == manifest['sourceSha256']
        and finished['terminalTails'] == 0, 'Exact completed public native source/runtime is required.')
    snapshots = {r['snapshot'] for r in manifest['inputs']}
    expected_public = {'manifest.json', *snapshots, *(f'{kind}-{i}.json' for i in range(256) for kind in ('public', 'backbone'))}
    require(set(finished['artifactSha256']) == expected_public
        and finished['publicSha256'] == {str(i): finished['artifactSha256'][f'public-{i}.json'] for i in range(256)},
        'Every raw public/backbone/model member must be retained.')
    predictions = directory / 'predictions/predictions.json'
    envelope, receipt = read(predictions), read(directory / 'predictions/receipt.json')
    require(envelope['kind'] == 'purchase-plan-predictions-1' and envelope['signature'] == manifest['signature']
        and envelope['publicFinishedSha256'] == sha(public / 'finished.json') and envelope['publicSha256'] == finished['publicSha256']
        and envelope['predictorDescriptorSha256'] == manifest['predictorDescriptorSha256']
        and envelope['allPredictionsComplete'] is True and envelope['terminalTailsStarted'] is False
        and len(envelope['decisions']) == 256 and receipt['passed'] and receipt['newGames'] == receipt['actualFits'] == 0
        and receipt['terminalLabelsDecoded'] is False and receipt['predictionsSha256'] == sha(predictions),
        'The original frozen67 mechanical tail-admission seal must precede every native tail.')
    admission = read(tails / 'admission.json')
    admitted = {str(public / n): h for n, h in finished['artifactSha256'].items()}
    admitted.update({str(public / 'finished.json'): sha(public / 'finished.json'), str(predictions): sha(predictions)})
    require(admission == dict(publicDirectory=str(public), publicFinishedSha256=sha(public / 'finished.json'),
        predictions=str(predictions), predictionsSha256=sha(predictions), inputSha256=admitted), 'Native tail admission changed.')
    final = read(tails / 'finished.json')
    require(final['kind'] == 'purchase-plan-tails-complete-1' and final['signature'] == manifest['signature']
        and final['origins'] == 256 and final['actorFits'] == 0 and final['predictionsSha256'] == sha(predictions),
        'A complete source-bound native four-future matrix is required.')
    expected_tails, origins, count = {'manifest.json', 'admission.json'}, [], 0
    for i in range(256):
        value = public_origin(read(public / f'public-{i}.json'), i, names)
        origins.append(metadata(value, chunk_index, finished['publicSha256'][str(i)], manifest['sourceSha256'],
            manifest['signature'], manifest['collection']['seeds'][i]))
        expected_tails.add(f'result-{i}.json')
        if value['status'] == 'READY':
            for plan in value['plans']:
                if plan['status'] == 'SUPPORTED' and plan['aliasOf'] is None:
                    expected_tails.update(f'tail-{i}-{plan["index"]}-{s}.json' for s in range(4))
                    count += 4
    require(set(final['artifactSha256']) == expected_tails and final['terminalGames'] == count <= 8192,
        'Every supported canonical sibling/future is retained, independent of predictions or outcomes.')
    require(len(source['streams']) == 256 and all(len(s['futures']) == 4 for s in source['streams']), 'Fixed four-future subjects required.')
    files = {}
    for owner, declaration in ((public, finished['artifactSha256']), (tails, final['artifactSha256'])):
        for name, digest in declaration.items():
            path = relative(owner, name)
            require(Path(name).name == name and path.is_file() and sha(path) == digest, 'A native completion member changed.')
        require({p.name for p in owner.iterdir() if p.name != '.DS_Store'} == set(declaration) | {'finished.json'}, 'Unexpected native evidence.')
    for path in directory.rglob('*'):
        if path.is_file() and path.name != '.DS_Store':
            require(not path.is_symlink(), 'Raw evidence must be regular owned files.')
            files[str(path.relative_to(directory))] = dict(sha256=sha(path), size=path.stat().st_size)
    require({p.name for p in directory.iterdir() if p.is_dir()} == {'public', 'predictions', 'tails'}, 'Unexpected chunk output directory.')
    return dict(files=files, origins=origins, terminalGames=256 + count, tailGames=count,
        sourceSha256=manifest['sourceSha256'], signature=manifest['signature'],
        gameSeeds=manifest['collection']['seeds'], futureStreams=[r['futures'] for r in source['streams']],
        semanticLabelsDecoded=False)


def archive(directory, files):
    """Verify/fsync the full raw chunk before deleting only exact archived duplicates."""
    directory = Path(directory)
    target, receipt = directory / 'evidence.tar.gz', directory / 'storage.json'
    require(not target.exists() and not receipt.exists(), 'Each completed chunk is archived once.')
    for name, item in files.items():
        path = relative(directory, name)
        require(path.is_file() and sha(path) == item['sha256'] and path.stat().st_size == item['size'], 'A raw chunk member changed.')
    def publish(stream):
        with tarfile.open(fileobj=stream, mode='w:gz', compresslevel=1) as destination:
            for name in sorted(files):
                destination.add(directory / name, arcname=name, recursive=False)
    storage.publish(target, publish)
    digest = sha(target)
    storage.verify_members(target, files, digest)
    result = dict(kind='purchase-plan-difference-storage-1', archive=target.name, archiveSha256=digest, files=files)
    storage.publish(receipt, lambda stream: stream.write((json.dumps(result, indent=2, sort_keys=True) + '\n').encode()))
    for name, item in files.items():
        path = relative(directory, name)
        require(sha(path) == item['sha256'], 'A raw member changed before verified removal.')
        path.unlink()
    for name in ('public', 'predictions', 'tails'):
        path = directory / name
        if path.exists() and not any(path.iterdir()):
            path.rmdir()
    storage.sync_directory(directory)
    return result


def documents(root, chunk, selected):
    """Hash every member, but semantically decode only the explicitly selected public/label files."""
    archive_path = relative(root, chunk['archive'])
    storage.verify_members(archive_path, chunk['files'], chunk['archiveSha256'])
    require(set(selected) <= set(chunk['files']), 'Requested members must be declared by complete native evidence.')
    seen = set()
    with tarfile.open(archive_path, 'r|gz') as source:
        for member in source:
            if member.name in selected:
                require(member.isfile() and member.name not in seen, 'Archive selection must be unique regular files.')
                seen.add(member.name)
                with source.extractfile(member) as stream:
                    yield member.name, json.load(stream)
    require(seen == set(selected) and sha(archive_path) == chunk['archiveSha256'], 'Selected archive members changed during reading.')


def paired_labels(value, origin, chunk):
    """Decode normalized terminal labels only after explicit DEVELOPMENT or sealed TEST admission."""
    require(set(value) == {'originIndex', 'status', 'logicalPlanExecutions', 'tails'}
        and value['originIndex'] == origin['localIndex'] and value['status'] == origin['status'], 'Detached result identity changed.')
    supported = [p for p in origin['plans'] if p['status'] == 'SUPPORTED'] if origin['status'] == 'READY' else []
    mapping = {str(p['index']): p['aliasOf'] if p['aliasOf'] is not None else p['index'] for p in supported}
    canonical = [p['index'] for p in supported if p['aliasOf'] is None]
    require(value['logicalPlanExecutions'] == mapping and [(t['planIndex'], t['replica']) for t in value['tails']]
        == [(p, s) for p in canonical for s in range(4)], 'All canonical siblings and four paired streams are mandatory.')
    samples = {p: [] for p in canonical}
    for tail in value['tails']:
        p, s = tail['planIndex'], tail['replica']
        seed = chunk['futureStreams'][origin['localIndex']][s]
        outcome = tail['outcome']
        name = f'tail-{origin["localIndex"]}-{p}-{s}.json'
        require(tail['continuationSeed'] == outcome['continuationSeed'] == seed and tail['canonicalFile'] == name
            and tail['canonicalSha256'] == chunk['files']['tails/' + name]['sha256'], 'Outcome/canonical/stream binding changed.')
        require(all(finite(outcome[k]) and 0 <= outcome[k] <= 1 for k in METRICS[:3])
            and abs(outcome['reward'] - .8 * outcome['winShare'] - .2 * outcome['rankReward']) <= 1e-12
            and type(outcome['decisions']) is int and outcome['decisions'] >= 0,
            'The original terminal reward and units remain unchanged.')
        scores = outcome['scores']
        require(len(scores) == 4 and [s['player'] for s in scores] == ['p1', 'p2', 'p3', 'p4'], 'Public terminal seat ordering changed.')
        chaos = scores[origin['localIndex'] % 4]['total'] is None
        require(not chaos or outcome['reward'] == outcome['winShare'] == outcome['rankReward'] == 0,
                'Own Chaos has zero normalized terminal reward.')
        samples[p].append([outcome[k] for k in METRICS[:3]] + [float(chaos)])
    return samples


class Cache:
    """Immutable sparse canonical leaves; metadata never enters dense model inputs."""
    def __init__(self, directory):
        import numpy as np
        self.directory = Path(directory)
        self.metadata = read(self.directory / 'cache.json')
        require(self.metadata['kind'] == 'purchase-plan-difference-cache-1'
            and self.metadata['partition'] in ('DEVELOPMENT', 'TEST_FEATURES'), 'Explicit cache partition required.')
        require(all(sha(self.directory / n) == h for n, h in self.metadata['files'].items()), 'Sparse cache bytes changed.')
        self.partition = self.metadata['partition']
        self.feature_names = self.metadata['stateFeatureNames']
        self.arrays = {Path(n).stem: np.load(self.directory / n, mmap_mode='r', allow_pickle=False) for n in self.metadata['files']}
        a = self.arrays
        rows = len(a['rowOrigin'])
        require(a['starts'].shape == a['stops'].shape == (2048,) and a['indptr'].shape == (rows + 1,)
            and all(a[k].shape == (rows,) for k in ('planIndex', 'referenceRow', 'exactValue', 'deltaTarget'))
            and a['indptr'][0] == 0 and a['indptr'][-1] == len(a['indices']) == len(a['data'])
            and np.all(np.diff(a['indptr']) >= 0) and np.isfinite(a['data']).all()
            and np.all((a['indices'] >= 0) & (a['indices'] < len(self.feature_names))), 'Sparse cache dimensions/values changed.')
        require(np.all((a['rowOrigin'] >= 0) & (a['rowOrigin'] < 2048))
            and np.all((a['planIndex'] >= 0) & (a['planIndex'] < 8))
            and np.all((a['referenceRow'] >= 0) & (a['referenceRow'] < rows))
            and np.all(a['rowOrigin'][a['referenceRow']] == a['rowOrigin'])
            and np.all(a['planIndex'][a['referenceRow']] == 0), 'Canonical/reference rows must remain in the same origin.')
        require(np.all(np.isnan(a['exactValue']) | ((a['exactValue'] >= 0) & (a['exactValue'] <= 1)))
            and np.all(np.isnan(a['deltaTarget'][a['planIndex'] == 0])), 'Bypasses/reference target semantics changed.')
        if self.partition == 'TEST_FEATURES':
            require(np.all(a['rowOrigin'] >= 1792) and np.isnan(a['deltaTarget']).all(), 'TEST feature caches cannot contain semantic labels.')
        else:
            require(np.all(a['rowOrigin'] < 1792) and np.isfinite(a['deltaTarget'][a['planIndex'] != 0]).all(),
                    'DEVELOPMENT contains only complete TRAIN/VALIDATION pairs.')

    def game_rows(self, games):
        import numpy as np
        a = self.arrays
        return np.asarray([row for game in games for row in range(int(a['starts'][game]), int(a['stops'][game]))], dtype=np.int64)

    def dense(self, rows, dtype='float32'):
        import numpy as np
        result = np.zeros((len(rows), len(self.feature_names)), dtype=dtype)
        a = self.arrays
        for i, row in enumerate(rows):
            start, end = a['indptr'][row:row + 2]
            result[i, a['indices'][start:end]] = a['data'][start:end]
        return result


def build_cache(corpus_path, output, partition, guard=lambda: None):
    """DEVELOPMENT opens only first7 chunks; TEST_FEATURES opens no outcome trees."""
    import numpy as np
    corpus_path, output = Path(corpus_path), Path(output)
    corpus = read(corpus_path)
    names = verify_corpus(corpus)
    dictionary_path = relative(corpus_path.parent, corpus['dictionaryFile'])
    require(sha(dictionary_path) == DICTIONARY and read(dictionary_path) == corpus['dictionary'], 'Exact admitted67 dictionary bytes required.')
    require(partition in ('DEVELOPMENT', 'TEST_FEATURES') and not output.exists(), 'Separate immutable caches are required.')
    chosen = [c for c in corpus['chunks'] if (c['index'] <= 7) == (partition == 'DEVELOPMENT')]
    lookup = {name: i for i, name in enumerate(names)}
    a = {key: [] for key in ('data', 'indices', 'rowOrigin', 'planIndex', 'referenceRow', 'exactValue', 'deltaTarget')}
    a['indptr'] = [0]
    a['starts'] = [0] * 2048
    a['stops'] = [0] * 2048
    for chunk in chosen:
        selected = {f'public/public-{i}.json' for i in range(256)}
        if partition == 'DEVELOPMENT':
            selected |= {f'tails/result-{i}.json' for i in range(256)}
        public, results = {}, {}
        for name, value in documents(corpus_path.parent, chunk, selected):
            guard()
            (public if name.startswith('public/') else results)[int(Path(name).stem.split('-')[-1])] = value
        for local in range(256):
            gid = (chunk['index'] - 1) * 256 + local
            origin = corpus['origins'][gid]
            value = public_origin(public[local], local, names)
            require(metadata(value, chunk['index'], origin['publicSha256'], origin['sourceSha256'], origin['signature'], origin['gameSeed']) == origin,
                    'Public metadata differs from its pre-label support declaration.')
            samples = paired_labels(results[local], origin, chunk) if partition == 'DEVELOPMENT' else None
            start = len(a['rowOrigin'])
            a['starts'][gid] = start
            for plan in value['plans'] if origin['status'] == 'READY' else []:
                if plan['status'] != 'SUPPORTED' or plan['aliasOf'] is not None:
                    continue
                features = plan['leaf']['stateFeatures']
                for name, v in sorted(features.items(), key=lambda pair: lookup[pair[0]]):
                    if v != 0:
                        a['indices'].append(lookup[name]); a['data'].append(v)
                a['indptr'].append(len(a['data']))
                a['rowOrigin'].append(gid); a['planIndex'].append(plan['index']); a['referenceRow'].append(start)
                a['exactValue'].append(float('nan') if plan['leaf']['exactValue'] is None else plan['leaf']['exactValue'])
                target = (sum(x[0] - y[0] for x, y in zip(samples[plan['index']], samples[0])) / 4
                          if samples is not None and plan['index'] != 0 else float('nan'))
                a['deltaTarget'].append(target)
            a['stops'][gid] = len(a['rowOrigin'])
    cursor = 0
    for gid in range(2048):
        if (gid < 1792) != (partition == 'DEVELOPMENT'):
            a['starts'][gid] = a['stops'][gid] = cursor
        else:
            require(a['starts'][gid] == cursor, 'Canonical cache rows must remain contiguous by original world.')
            cursor = a['stops'][gid]
    output.mkdir(parents=True)
    dtypes = dict(data='float32', indices='int32', indptr='int64', starts='int64', stops='int64', rowOrigin='int32',
        planIndex='int8', referenceRow='int64', exactValue='float64', deltaTarget='float64')
    for name, dtype in dtypes.items():
        np.save(output / (name + '.npy'), np.asarray(a[name], dtype=dtype), allow_pickle=False)
    files = {p.name: sha(p) for p in output.glob('*.npy')}
    document(output / 'cache.json', dict(kind='purchase-plan-difference-cache-1', partition=partition,
        corpusSha256=sha(corpus_path), corpusCanonicalSha256=canonical_sha(corpus),
        sourceSha256=corpus['sourceSha256'], dictionarySha256=corpus['dictionarySha256'],
        stateFeatureNames=names, rows=len(a['rowOrigin']), files=files, testLabelsDecoded=False))
    return Cache(output)


def choices(cache, corpus, candidate, frozen):
    """Fixed public strict-improvement rule; aliases copy their canonical execution value."""
    a = cache.arrays
    def choose(origin, values):
        if origin['status'] != 'READY':
            return None if origin['status'] == 'MISSING' else 0
        i = origin['index']
        rows = range(int(a['starts'][i]), int(a['stops'][i]))
        q = {int(a['planIndex'][r]): float(values[r]) for r in rows}
        require(sorted(q) == [p['index'] for p in origin['plans'] if p['status'] == 'SUPPORTED' and p['aliasOf'] is None],
                'Every public canonical TEST execution must have its own sealed value.')
        best, best_value = 0, q[0]
        for plan in origin['plans']:
            if plan['status'] == 'SUPPORTED':
                value = q[plan['index'] if plan['aliasOf'] is None else plan['aliasOf']]
                if value > best_value:
                    best, best_value = plan['index'], value
        return best
    return [dict(originIndex=o['index'], status=o['status'], selectedIndex=choose(o, candidate), frozen67Index=choose(o, frozen))
            for o in corpus['origins'][1792:]]


def test_labels(corpus_path, cache, prediction_receipt):
    """Only a fixed changed candidate with complete persisted TEST predictions opens labels."""
    import numpy as np
    corpus_path, prediction_receipt = Path(corpus_path), Path(prediction_receipt)
    corpus, receipt = read(corpus_path), read(prediction_receipt)
    verify_corpus(corpus)
    require(cache.partition == 'TEST_FEATURES' and cache.metadata['corpusSha256'] == sha(corpus_path)
        and np.isnan(cache.arrays['deltaTarget']).all(), 'Only the immutable label-free TEST cache is admitted.')
    selection_path = Path(receipt['selection']['path'])
    require(selection_path.is_absolute() and sha(selection_path) == receipt['selection']['sha256'], 'Frozen model selection changed.')
    selected = read(selection_path)
    model_path = relative(selection_path.parent, selected['modelFile'])
    require(selected['kind'] == 'purchase-plan-paired-difference-selection-1' and selected['unchanged'] is False
        and selected['testPermitted'] is True and selected['testInspected'] is False
        and selected['warmCheckpointSha256'] == CHECKPOINT and selected['dictionarySha256'] == DICTIONARY
        and selected['sourceSha256'] == corpus['sourceSha256'] and selected['corpusSha256'] == sha(corpus_path)
        and sha(model_path) == selected['modelSha256'],
        'Only the once-fixed validation-selected learned candidate may open TEST; unchanged67 stops.')
    require(receipt['kind'] == 'purchase-plan-difference-test-predictions-1' and receipt['predictionsComplete'] is True
        and receipt['semanticLabelsDecoded'] is False and receipt['corpusSha256'] == sha(corpus_path)
        and receipt['cacheSha256'] == sha(cache.directory / 'cache.json')
        and receipt['modelSha256'] == selected['modelSha256'] and receipt['dictionarySha256'] == DICTIONARY
        and receipt['testIds'] == list(range(1792, 2048))
        and set(receipt['files']) == {'selected.npy', 'frozen67.npy', 'decisions.json'},
        'All fixed TEST feature predictions and choices must be persisted before semantic labels.')
    descriptor_path = prediction_receipt.parent / 'predictor.json'
    descriptor = read(descriptor_path)
    require(sha(descriptor_path) == receipt['predictorDescriptorSha256']
        and descriptor['kind'] == 'purchase-plan-difference-predictor-1'
        and descriptor['checkpointSha256'] == selected['modelSha256']
        and descriptor['warmCheckpointSha256'] == CHECKPOINT and descriptor['dictionarySha256'] == DICTIONARY
        and descriptor['selectionSha256'] == receipt['selection']['sha256']
        and descriptor['corpusSha256'] == sha(corpus_path), 'TEST uses an honest descriptor for the new selected critic.')
    for name, digest in receipt['files'].items():
        require(sha(relative(prediction_receipt.parent, name)) == digest, 'Persisted TEST prediction evidence changed.')
    a = cache.arrays
    predictions = {}
    for name in ('selected.npy', 'frozen67.npy'):
        values = np.load(prediction_receipt.parent / name, allow_pickle=False)
        require(values.shape == a['exactValue'].shape and np.isfinite(values).all()
            and np.all((values >= 0) & (values <= 1))
            and np.array_equal(values[np.isfinite(a['exactValue'])], a['exactValue'][np.isfinite(a['exactValue'])]),
            'Every canonical TEST leaf needs a finite value and exact authoritative bypass before labels.')
        predictions[name] = values
    decisions = read(prediction_receipt.parent / 'decisions.json')
    require(decisions == choices(cache, corpus, predictions['selected.npy'], predictions['frozen67.npy']),
        'Public support and all prospective TEST worlds must remain in the sealed choice file.')
    chunk = corpus['chunks'][7]
    selected_members = {f'tails/result-{i}.json' for i in range(256)}
    labels = {}
    for name, value in documents(corpus_path.parent, chunk, selected_members):
        index = 1792 + int(Path(name).stem.split('-')[-1])
        labels[index] = paired_labels(value, corpus['origins'][index], chunk)
    require(set(labels) == set(range(1792, 2048)) and sha(selection_path) == receipt['selection']['sha256']
        and sha(model_path) == selected['modelSha256']
        and sha(descriptor_path) == receipt['predictorDescriptorSha256']
        and all(sha(prediction_receipt.parent / n) == h for n, h in receipt['files'].items()),
        'Selection/predictions changed during the single label pass.')
    return labels
