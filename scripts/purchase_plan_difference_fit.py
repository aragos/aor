"""Two fixed warm67 paired-difference fits; no TEST labels, actor updates or plan execution.

Canonical siblings stay together. Each informative origin has equal weight, then each of its
nonreference executions. Exact terminal/own-Chaos values are detached constants, not fit rows.
The separate reader owns public support and paired four-future labels; this owner never reads
terminal histories. The old point-target critic fitter and all original guards remain unchanged.
"""
from dataclasses import asdict, dataclass
from pathlib import Path
import time

import numpy as np
import torch

import purchase_plan_predictor as frozen
import purchase_plan_difference_data as paired
import purchase_value_benchmark as benchmark
import purchase_value_data as data

KIND = 'purchase-plan-paired-difference-fit-1'
ARMS = ('head', 'full')
WORLDS = 2048
TRAIN_END, VALIDATION_END = 1536, 1792


@dataclass(frozen=True)
class Config:
    epochSeeds: tuple
    epochs: int = 20
    learningRate: float = .001
    worldsPerBatch: int = 64
    anchorCoefficient: float = .1
    minimumTrainingOrigins: int = 1200
    minimumValidationOrigins: int = 160

    def validate(self):
        fixed = Config(self.epochSeeds)
        data.require(asdict(self) == asdict(fixed)
            and isinstance(self.epochSeeds, tuple) and len(self.epochSeeds) == 20
            and all(type(seed) is int and 0 <= seed < 2**63 for seed in self.epochSeeds)
            and self.epochSeeds == tuple(range(self.epochSeeds[0], self.epochSeeds[0] + 20)),
            'Use the fixed20/.001/64/.1 recipe and twenty audited shared consecutive shuffle seeds.')
        return self


def validate_cache(cache, corpus, names):
    """Validate the typed DEVELOPMENT boundary without opening any TEST artifact."""
    data.require(cache.partition == 'DEVELOPMENT' and cache.feature_names == names,
                 'Fitting requires only the original ordered7244-column DEVELOPMENT cache.')
    data.require(corpus['dictionarySha256'] == frozen.DICTIONARY
        and corpus['warmCheckpointSha256'] == frozen.CHECKPOINT
        and isinstance(corpus['sourceSha256'], str) and len(corpus['sourceSha256']) == 64
        and all(c in '0123456789abcdef' for c in corpus['sourceSha256']),
        'Corpus provenance must bind the exact warm67 checkpoint, dictionary and source digest.')
    data.require(cache.metadata['corpusCanonicalSha256'] == paired.canonical_sha(corpus)
        and cache.metadata['sourceSha256'] == corpus['sourceSha256']
        and cache.metadata['dictionarySha256'] == corpus['dictionarySha256']
        and isinstance(cache.metadata['corpusSha256'], str) and len(cache.metadata['corpusSha256']) == 64
        and all(c in '0123456789abcdef' for c in cache.metadata['corpusSha256']),
        'The immutable cache must bind this exact original corpus and its raw-file digest.')
    origins = corpus['origins']
    data.require(len(origins) == WORLDS and [o['index'] for o in origins] == list(range(WORLDS)),
                 'Retain all2048 prospective origins, including missing and unsupported roots.')
    a = cache.arrays
    starts, stops = np.asarray(a['starts']), np.asarray(a['stops'])
    row_count = len(a['rowOrigin'])
    data.require(starts.shape == stops.shape == (WORLDS,)
        and starts.dtype.kind in 'iu' and stops.dtype.kind in 'iu'
        and starts[0] == 0 and stops[-1] == row_count
        and np.array_equal(starts[1:], stops[:-1]) and (stops >= starts).all(),
        'Canonical rows must be contiguous, uniquely owned and preserve empty worlds.')
    for key in ('planIndex', 'referenceRow', 'exactValue', 'deltaTarget'):
        data.require(np.asarray(a[key]).shape == (row_count,), 'Malformed canonical row array: ' + key)
    for key in ('rowOrigin', 'planIndex', 'referenceRow'):
        data.require(np.asarray(a[key]).dtype.kind in 'iu', 'Canonical identity arrays require integers.')
    exact = np.asarray(a['exactValue'])
    data.require((np.isnan(exact) | (np.isfinite(exact) & (exact >= 0) & (exact <= 1))).all(),
                 'Exact authoritative bypasses are finite rewards in[0,1], or NaN for model leaves.')
    groups = {'TRAIN': [], 'VALIDATION': []}
    for origin in origins:
        index = origin['index']
        split = 'TRAIN' if index < TRAIN_END else 'VALIDATION' if index < VALIDATION_END else 'TEST'
        data.require(origin['split'] == split and origin['chunk'] == index // 256 + 1
            and origin['localIndex'] == index % 256, 'Every origin keeps its prospective whole-world split.')
        rows = np.arange(int(starts[index]), int(stops[index]), dtype=np.int64)
        canonical = [p['index'] for p in origin['plans']
                     if p['status'] == 'SUPPORTED' and p['aliasOf'] is None]
        ready = origin['status'] == 'READY'
        informative = ready and len(canonical) > 1
        data.require(origin['informative'] is informative,
                     'Informative admission is public supported-alternative coverage, never an outcome.')
        if split == 'TEST' or not ready:
            data.require(len(rows) == 0, 'DEVELOPMENT cannot contain TEST or unsupported-reference rows.')
            continue
        data.require(canonical and canonical[0] == 0 and canonical == sorted(set(canonical))
            and len(canonical) <= 8 and list(a['planIndex'][rows]) == canonical
            and (a['rowOrigin'][rows] == index).all()
            and (a['referenceRow'][rows] == starts[index]).all(),
            'Canonical supported siblings and their original G3 reference must remain exact.')
        targets = a['deltaTarget'][rows]
        data.require(np.isnan(targets[0]) and np.isfinite(targets[1:]).all()
            and (np.abs(targets[1:]) <= 1).all(),
            'Only canonical nonreference DEVELOPMENT rows carry paired four-future differences.')
        if informative:
            groups[split].append(index)
    return groups


def make_model(state, arm, device):
    data.require(arm in ARMS, 'Only the declared head-only and full-network fits exist.')
    shapes = {'layers.0.weight': (64, frozen.INPUTS), 'layers.0.bias': (64,),
              'layers.2.weight': (1, 64), 'layers.2.bias': (1,)}
    data.require(set(state) == set(shapes) and all(isinstance(state[k], torch.Tensor)
        and state[k].dtype == torch.float32 and tuple(state[k].shape) == shape
        and bool(torch.isfinite(state[k]).all()) for k, shape in shapes.items()),
        'Both fits start from complete finite original67 FP32 critic tensors.')
    with torch.random.fork_rng(devices=list(range(torch.cuda.device_count()))):
        model = benchmark.Critic((64,), 0, inputs=frozen.INPUTS)
    model.load_state_dict(state, strict=True)
    for name, parameter in model.named_parameters():
        parameter.requires_grad_(arm == 'full' or name.startswith('layers.2.'))
    data.require(sum(p.numel() for p in model.parameters() if p.requires_grad) ==
                 (65 if arm == 'head' else 463745), 'The two fixed fits retain their exact trainable sizes.')
    return model.to(device)


def rows_for(cache, worlds):
    a = cache.arrays
    return np.concatenate([np.arange(int(a['starts'][i]), int(a['stops'][i]), dtype=np.int64)
                           for i in worlds]) if len(worlds) else np.empty(0, dtype=np.int64)


def values(model, cache, rows, device):
    """Only public sparse feature rows reach the critic; exact values bypass its computation."""
    exact = np.asarray(cache.arrays['exactValue'][rows], dtype=np.float64)
    learned = np.flatnonzero(np.isnan(exact))
    result = torch.tensor(np.nan_to_num(exact, nan=0.), dtype=torch.float32, device=device)
    if len(learned):
        inputs = cache.dense(rows[learned])
        data.require(inputs.shape == (len(learned), frozen.INPUTS)
            and inputs.dtype == np.float32 and np.isfinite(inputs).all()
            and (np.abs(inputs) <= 16).all(), 'Only finite original-unit public OWNER_EXIT inputs are allowed.')
        predictions = model(torch.from_numpy(inputs).to(device))
        result = result.index_copy(0, torch.tensor(learned, dtype=torch.long, device=device), predictions)
    return result


def predict(model, cache, worlds, device, guard):
    model.eval()
    result = np.full(len(cache.arrays['rowOrigin']), np.nan, dtype=np.float64)
    with torch.no_grad():
        for offset in range(0, len(worlds), 64):
            guard()
            rows = rows_for(cache, worlds[offset:offset + 64])
            result[rows] = values(model, cache, rows, device).cpu().numpy()
            exact = np.asarray(cache.arrays['exactValue'][rows], dtype=np.float64)
            known = np.isfinite(exact)
            result[rows[known]] = exact[known]
    return result


def paired_errors(predictions, cache, worlds):
    """One MSE per informative origin, never a pooled-plan or per-future statistic."""
    a = cache.arrays
    result = []
    for world in worlds:
        start, stop = int(a['starts'][world]), int(a['stops'][world])
        data.require(stop - start > 1, 'Paired-error worlds need a canonical nonreference alternative.')
        error = predictions[start + 1:stop] - predictions[start] - a['deltaTarget'][start + 1:stop]
        data.require(np.isfinite(error).all(), 'Paired errors require complete DEVELOPMENT labels and predictions.')
        result.append(float(np.mean(error**2)))
    return np.asarray(result, dtype=np.float64)


def batch_loss(model, cache, worlds, frozen_values, device):
    """Equal worlds, then alternatives; the anchor separately balances canonical learned leaves."""
    data.require(len(worlds) > 0, 'A gradient batch requires informative origins.')
    a = cache.arrays
    rows = rows_for(cache, worlds)
    q = values(model, cache, rows, device)
    pairs, anchors, position = [], [], 0
    for world in worlds:
        start, stop = int(a['starts'][world]), int(a['stops'][world])
        count = stop - start
        data.require(count > 1, 'Gradient batches cannot contain fabricated missing-origin targets.')
        own = q[position:position + count]
        target = torch.tensor(a['deltaTarget'][start + 1:stop], dtype=torch.float32, device=device)
        pairs.append(((own[1:] - own[0]) - target).square().mean())
        learned = np.flatnonzero(np.isnan(a['exactValue'][start:stop]))
        if len(learned):
            indices = torch.tensor(learned, dtype=torch.long, device=device)
            reference = torch.tensor(frozen_values[start:stop][learned], dtype=torch.float32, device=device)
            anchors.append((own[indices] - reference).square().mean())
        else:
            anchors.append(torch.zeros((), dtype=torch.float32, device=device))
        position += count
    pair = torch.stack(pairs).mean()
    anchor = torch.stack(anchors).mean()
    return pair + .1 * anchor, pair, anchor


choose = paired.select_validation


def _save(model, path):
    torch.save({key: value.detach().cpu().clone() for key, value in model.state_dict().items()}, path)


def _fit_arm(arm, state, cache, groups, frozen_values, output, config, guard, device, baseline_mse):
    model = make_model(state, arm, device)
    optimizer = torch.optim.Adam([p for p in model.parameters() if p.requires_grad],
        lr=.001, betas=(.9, .999), eps=1e-8, weight_decay=0, foreach=False, fused=False)
    data.require(not optimizer.state, 'Each arm begins with fresh Adam moments, never inherited ones.')
    best = dict(arm='unchanged', epoch=0, validationMse=baseline_mse)
    history = [dict(best)]
    best_path = output / (arm + '-best.pt')
    _save(model, best_path)
    for epoch, seed in enumerate(config.epochSeeds, 1):
        guard()
        shuffled = np.random.Generator(np.random.PCG64(seed)).permutation(groups['TRAIN'])
        model.train()
        totals = np.zeros(3, dtype=np.float64)
        for offset in range(0, len(shuffled), 64):
            guard()
            worlds = shuffled[offset:offset + 64]
            optimizer.zero_grad(set_to_none=True)
            loss, pair, anchor = batch_loss(model, cache, worlds, frozen_values, device)
            data.require(bool(torch.isfinite(loss)), 'The fixed paired objective must remain finite.')
            # A wholly exact-bypass batch has a genuine constant loss and no learnable parameters.
            if loss.requires_grad:
                loss.backward()
                data.require(all(p.grad is None or bool(torch.isfinite(p.grad).all())
                    for p in model.parameters()), 'The fixed paired gradient must remain finite.')
                optimizer.step()
            totals += len(worlds) * np.asarray([float(x.detach()) for x in (loss, pair, anchor)])
        predictions = predict(model, cache, groups['VALIDATION'], device, guard)
        record = dict(arm=arm, epoch=epoch,
            validationMse=float(paired_errors(predictions, cache, groups['VALIDATION']).mean()),
            trainingObjective=float(totals[0] / len(shuffled)),
            trainingPairedMse=float(totals[1] / len(shuffled)),
            trainingAnchorMse=float(totals[2] / len(shuffled)))
        history.append(record)
        if record['validationMse'] < best['validationMse']:
            best = dict(record)
            _save(model, best_path)
        data.document(output / f'{arm}-epoch-{epoch:02d}.json', record)
    if arm == 'head':
        data.require(all(torch.equal(model.state_dict()[key].cpu(), state[key].cpu())
            for key in ('layers.0.weight', 'layers.0.bias')), 'The head-only fit must preserve its exact67 basis.')
    receipt = dict(kind=KIND, arm=arm, trainableParameters=65 if arm == 'head' else 463745,
        history=history, best=best, modelFile=best_path.name, modelSha256=data.sha(best_path),
        optimizer='fresh-Adam', config=asdict(config), testInspected=False)
    data.document(output / (arm + '-fit.json'), receipt)
    return receipt


def fit(cache, corpus, output, config, guard, device, *, checkpoint, dictionary):
    """Exactly two fixed fits and one frozen validation nomination; no TEST reads or scoring."""
    config.validate()
    checkpoint, dictionary, names, original = frozen.load_subjects(checkpoint, dictionary)
    groups = validate_cache(cache, corpus, names)
    cache_path = cache.directory / 'cache.json'
    data.require(data.read(cache_path) == cache.metadata, 'The loaded cache metadata changed before fitting.')
    cache_sha = data.sha(cache_path)
    data.require(len(groups['TRAIN']) >= 1200 and len(groups['VALIDATION']) >= 160,
                 'Insufficient prospectively admitted paired TRAIN/VALIDATION origins; do not refill.')
    output = Path(output)
    data.require(not output.exists(), 'One fresh paired-fit attempt only; preserve every prior output.')
    data.require(torch.get_num_threads() == 1 and torch.get_num_interop_threads() == 1
        and torch.are_deterministic_algorithms_enabled()
        and not torch.backends.cuda.matmul.allow_tf32 and not torch.backends.cudnn.allow_tf32,
        'Use the preflighted deterministic FP32 environment, one CPU thread and TF32 disabled.')
    output.mkdir(parents=True)
    started = time.monotonic()
    state = {key: value.detach().cpu().clone() for key, value in original.state_dict().items()}
    original = original.to(device)
    all_worlds = groups['TRAIN'] + groups['VALIDATION']
    frozen_values = predict(original, cache, all_worlds, device, guard)
    baseline_mse = float(paired_errors(frozen_values, cache, groups['VALIDATION']).mean())
    receipts = [_fit_arm(arm, state, cache, groups, frozen_values, output, config, guard,
                         device, baseline_mse) for arm in ARMS]
    candidates = [dict(arm='unchanged', epoch=0, validationMse=baseline_mse)]
    candidates.extend(record for receipt in receipts for record in receipt['history'][1:])
    chosen = dict(choose(candidates))
    unchanged = chosen['arm'] == 'unchanged'
    if unchanged:
        model_file, model_sha = str(checkpoint), frozen.CHECKPOINT
    else:
        receipt = next(r for r in receipts if r['arm'] == chosen['arm'])
        data.require(receipt['best']['epoch'] == chosen['epoch'],
                     'The nominated epoch must be the retained earliest best checkpoint of its arm.')
        model_file, model_sha = receipt['modelFile'], receipt['modelSha256']
    selected = dict(kind='purchase-plan-paired-difference-selection-1', **chosen, unchanged=unchanged,
        modelFile=model_file, modelSha256=model_sha, warmCheckpointSha256=frozen.CHECKPOINT,
        dictionarySha256=frozen.DICTIONARY, sourceSha256=corpus['sourceSha256'],
        corpusSha256=cache.metadata['corpusSha256'], cacheSha256=cache_sha,
        config=asdict(config), trainingOrigins=len(groups['TRAIN']), validationOrigins=len(groups['VALIDATION']),
        ownerSha256=data.sha(__file__), criticOwnerSha256=data.sha(benchmark.__file__),
        fits=2, testInspected=False, testPermitted=not unchanged, qualificationPassed=False,
        elapsedSeconds=time.monotonic() - started)
    data.require(data.sha(checkpoint) == frozen.CHECKPOINT and data.sha(dictionary) == frozen.DICTIONARY
        and data.sha(cache_path) == cache_sha, 'Original67 subjects or cache identity changed during fitting.')
    data.document(output / 'selected.json', selected)
    return selected
