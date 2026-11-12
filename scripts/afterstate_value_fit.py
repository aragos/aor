"""One fixed warm state critic; equal world/context loss and separately admitted TEST scoring.

Exact terminal/own-Chaos rows are excluded by the typed reader and bypass approximation. This
module never constructs an actor, selects an action, or changes the existing benchmark optimizer.
"""
from dataclasses import asdict, dataclass
import copy
import hashlib
import json
from pathlib import Path

import numpy as np
import torch

import purchase_value_benchmark as benchmark
import purchase_value_data as data


KIND = 'afterstate-value-critic-1'
CONTEXTS = ('CARD', 'SHIPPING', 'ADVANCES', 'STABILIZATION', 'RELIEF', 'OWNER_EXIT')
SOURCE_SHA = 'ffdceb830f97513d07b3ab63c5b7f425545f91749dba3fba81e80ed3e63773eb'
OLD_NAMES_SHA = '95f969fa5194601bf08ea01d71c2d1dbe5695e48b2c2edca0c10e19d0cfa8509'
APPENDED_NAMES = ('purchase.decision.purchase-owner-exit', 'purchase.decision.purchase-relief',
                  'purchase.decision.purchase-stabilization')


@dataclass(frozen=True)
class Config:
    orderSeed: int = 67100000
    bootstrapSeed: int = 67100020
    epochs: int = 20
    learningRate: float = .001
    gamesPerBatch: int = 64
    maximumDenseRows: int = 4096
    bootstrapReplicates: int = 2000
    minimumNewContextTestWorlds: int = 64

    def validate(self):
        data.require(asdict(self) == asdict(Config()), 'The family67 fit recipe and audited RNGs are fixed.')
        return self


def names_sha(names):
    return hashlib.sha256(json.dumps(names, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


def warm_state(original, old_names, new_names):
    """Copy every old coordinate/later parameter; append exact zeros without using any RNG."""
    data.require(isinstance(old_names, list) and old_names == sorted(set(old_names)) and old_names,
                 'The original state dictionary must retain its ordered unique columns.')
    data.require(isinstance(new_names, list) and new_names[:len(old_names)] == old_names
                 and len(new_names) > len(old_names) and len(set(new_names)) == len(new_names)
                 and new_names[len(old_names):] == sorted(new_names[len(old_names):]),
                 'The new dictionary must append unique ordered columns to the unchanged old prefix.')
    shapes = {'layers.0.weight': (64, len(old_names)), 'layers.0.bias': (64,),
              'layers.2.weight': (1, 64), 'layers.2.bias': (1,)}
    data.require(set(original) == set(shapes) and all(isinstance(original[key], torch.Tensor)
        and tuple(original[key].shape) == shape and original[key].dtype == torch.float32
        and bool(torch.isfinite(original[key]).all()) for key, shape in shapes.items()),
        'The original checkpoint must be the finite FP32 width64 state critic.')
    state = {key: tensor.detach().cpu().clone() for key, tensor in original.items()}
    state['layers.0.weight'] = torch.zeros((64, len(new_names)), dtype=torch.float32)
    state['layers.0.weight'][:, :len(old_names)] = original['layers.0.weight'].detach().cpu()
    return state


def context_row_weights(cache):
    """Each represented world totals one; its nonempty contexts receive equal shares."""
    a = cache.arrays
    context = a['context']
    data.require(context.shape == a['rowGame'].shape and np.isin(context, range(6)).all(),
                 'Every retained row requires one of the six actual state contexts.')
    weights = np.zeros(len(context), dtype=np.float64)
    for game, (start, stop) in enumerate(zip(a['starts'], a['stops'], strict=True)):
        rows = np.arange(int(start), int(stop), dtype=np.int64)
        data.require((a['rowGame'][rows] == game).all(), 'Rows must retain their complete-world owner.')
        counts = np.bincount(context[rows], minlength=6)
        present = int(np.count_nonzero(counts))
        if present:
            weights[rows] = 1. / (present * counts[context[rows]])
    return weights


def world_errors(predictions, cache, games, context=None):
    """One balanced error per supplied world; absent contexts remain NaN, never zero."""
    a = cache.arrays
    result = np.full(len(games), np.nan, dtype=np.float64)
    for position, game in enumerate(games):
        rows = np.arange(int(a['starts'][game]), int(a['stops'][game]), dtype=np.int64)
        if context is not None:
            rows = rows[a['context'][rows] == context]
        if not len(rows):
            continue
        values = predictions[rows]
        data.require(np.isfinite(values).all() and ((values >= 0) & (values <= 1)).all(),
                     'A state value must be finite in [0,1].')
        errors = (values - a['reward'][game]) ** 2
        result[position] = np.mean([errors[a['context'][rows] == item].mean()
                                    for item in np.unique(a['context'][rows])])
    return result


def balanced_errors(predictions, cache, games):
    values = world_errors(predictions, cache, games)
    return values[np.isfinite(values)]


def training_constants(cache, corpus):
    """Per-context constants minimize the same TRAIN world/context-weighted squared loss."""
    weights = context_row_weights(cache)
    totals = np.zeros(6, dtype=np.float64)
    weighted = np.zeros(6, dtype=np.float64)
    counts = np.zeros(6, dtype=np.int64)
    a = cache.arrays
    for game in (g['id'] for g in corpus['games'] if g['split'] == 'training'):
        rows = np.arange(int(a['starts'][game]), int(a['stops'][game]), dtype=np.int64)
        for context in np.unique(a['context'][rows]):
            weight = float(weights[rows[a['context'][rows] == context]].sum())
            totals[context] += weight
            weighted[context] += weight * float(a['reward'][game])
            counts[context] += 1
    return {name: dict(value=float(weighted[i] / totals[i]) if totals[i] else None,
                       trainingWorlds=int(counts[i]), trainingWorldWeight=float(totals[i]))
            for i, name in enumerate(CONTEXTS)}


def _critic(state, inputs, device):
    # nn.Linear's discarded constructor values never become model state or consume caller RNG.
    with torch.random.fork_rng(devices=list(range(torch.cuda.device_count()))):
        model = benchmark.Critic((64,), 0, inputs=inputs)
    model.load_state_dict(state, strict=True)
    return model.to(device)


def warm_parity(original, expanded, old_names, new_names, device):
    """Synthetic FP32 functional proof, including nonzero appended inputs; no game-state rows."""
    states = ((np.arange(16 * len(new_names)).reshape(16, -1) % 17) - 8).astype(np.float32) / 8
    states[0] = 0
    before = _critic(original, len(old_names), device)
    after = _critic(expanded, len(new_names), device)
    with torch.no_grad():
        old = before(torch.from_numpy(states[:, :len(old_names)].copy()).to(device))
        new = after(torch.from_numpy(states).to(device))
        error = float((old - new).abs().max().cpu())
    data.require(error <= 2e-6, 'Warm original-state FP32 parity exceeded2e-6.')
    return dict(syntheticRows=16, maximumAbsoluteError=error, tolerance=2e-6,
                originalColumns=len(old_names), zeroAppendedColumns=len(new_names)-len(old_names),
                device=str(device), dtype='float32', retainedStateRowsRead=0)


def _validate_subject(cache, corpus, old_names):
    data.require(len(old_names) == 7241 and names_sha(old_names) == OLD_NAMES_SHA,
                 'The exact original52 ordered7241-column dictionary changed.')
    data.require(cache.feature_names == corpus['stateFeatureNames']
                 == old_names + list(APPENDED_NAMES), 'Only the three actual context keys may be appended.')
    games = corpus['games']
    data.require(len(games) == 4096 and [g['id'] for g in games] == list(range(4096))
                 and len({g['gameSeed'] for g in games}) == 4096,
                 'The corpus must retain4096 unique ordered independent origin worlds.')
    data.require(all(g['split'] == ('training' if g['id'] < 3072 else
                 'validation' if g['id'] < 3584 else 'test') for g in games),
                 'The fixed12/2/2 complete-chunk split changed.')


def fit(cache, corpus, output, config, guard, device, *, original, old_names, source_sha=SOURCE_SHA):
    """Fit one warm critic. TEST state/reward rows are not read; evaluation is a separate call."""
    config.validate()
    _validate_subject(cache, corpus, old_names)
    original, output = Path(original), Path(output)
    data.require(source_sha == SOURCE_SHA and data.sha(original) == SOURCE_SHA,
                 'The unchanged validation-selected52 warm checkpoint is required.')
    data.require(not output.exists(), 'The fixed fit requires a new output directory and one attempt.')
    state = torch.load(original, map_location='cpu', weights_only=True)
    expanded = warm_state(state, old_names, cache.feature_names)
    weights = context_row_weights(cache)
    constants = training_constants(cache, corpus)
    data.require(all(row['trainingWorlds'] > 0 for row in constants.values()),
                 'Insufficient prerequisite: every context needs a represented TRAIN world; no refill.')
    output.mkdir(parents=True)
    guard()
    parity = warm_parity(state, expanded, old_names, cache.feature_names, device)
    data.document(output / 'warm-synthetic-parity.json', parity)
    torch.save(expanded, output / 'warm-expanded-initial.pt')
    with torch.random.fork_rng(devices=list(range(torch.cuda.device_count()))):
        model, receipt = benchmark.fit_one('warm', (64,), 0, cache, corpus, output,
            config, guard, device, initial_state=expanded, input_count=len(cache.feature_names),
            training_row_weights=weights, validation_errors=balanced_errors)
    selection = dict(kind=KIND, sourceSha256=SOURCE_SHA, originalNamesSha256=OLD_NAMES_SHA,
        dictionarySha256=corpus['dictionarySha256'], inputCount=len(cache.feature_names),
        appendedNames=list(APPENDED_NAMES), selectedSha256=receipt['modelSha256'], fit=receipt,
        warmExpandedSha256=data.sha(output / 'warm-expanded-initial.pt'), config=asdict(config),
        warmParitySha256=data.sha(output / 'warm-synthetic-parity.json'),
        trainingConstants=constants, actualFits=1, testInspected=False,
        initializer='Exact old52 columns/biases/output; three zero columns; constructor RNG discarded and restored.',
        constructorSeed=0, scientificInitializerRng=False,
        weighting='Equal represented world, then equal nonempty context, then equal rows.',
        selection='Lowest validation world/context-balanced MSE; earlier epoch on exact ties.',
        actorTraining=False, qualificationPassed=False, productionActivation=False)
    data.document(output / 'selected.json', selection)
    return model, selection


def _summary(values):
    present = np.isfinite(values)
    return dict(representedWorlds=int(present.sum()), excludedWorlds=int((~present).sum()),
                mse=float(values[present].mean()) if present.any() else None)


def shared_bootstrap(contrasts, seed, replicates):
    """One resampled world-index bank for every contrast, retaining missing-context masks."""
    data.require(contrasts and len({len(v) for v in contrasts.values()}) == 1,
                 'All bootstrap contrasts must share the same whole-world index space.')
    count = len(next(iter(contrasts.values())))
    data.require(count > 1 and all(not np.isinf(v).any() for v in contrasts.values()),
                 'Bootstrap inputs require finite-or-missing whole-world contrasts.')
    generator = np.random.Generator(np.random.PCG64(seed))
    means = {key: [] for key in contrasts}
    for start in range(0, replicates, 100):
        indices = generator.integers(0, count, size=(min(100, replicates - start), count))
        for key, values in contrasts.items():
            sampled = np.asarray(values, dtype=np.float64)[indices]
            present = np.isfinite(sampled)
            represented = present.sum(axis=1)
            totals = np.where(present, sampled, 0).sum(axis=1)
            means[key].extend((totals[represented > 0] / represented[represented > 0]).tolist())
    result = {}
    for key, values in contrasts.items():
        present = np.isfinite(values)
        bounds = np.quantile(means[key], [.025, .975], method='linear') if means[key] else (None, None)
        result[key] = dict(worlds=int(present.sum()), prospectiveWorlds=count,
            mean=float(np.asarray(values)[present].mean()) if present.any() else None,
            lower95=float(bounds[0]) if bounds[0] is not None else None,
            upper95=float(bounds[1]) if bounds[1] is not None else None,
            replicates=replicates, nonemptyReplicates=len(means[key]), seed=seed,
            method='Shared paired whole-world percentile bootstrap; missing contexts are omitted, not zero.')
    return result


def _metrics(predictions, cache, games):
    a = cache.arrays
    weights = context_row_weights(cache)
    rows = cache.game_rows(games)
    weights = weights[rows]
    targets = a['reward'][a['rowGame'][rows]]
    values = predictions[rows]
    return dict(**_summary(world_errors(predictions, cache, games)), rows=len(rows),
                calibration=benchmark.calibration(values, targets, weights) if len(rows) else [])


def evaluate(cache, corpus, output, config, guard, device, *, original, old_names, load_test_labels):
    """Persist fixed TEST predictions first; only then admit the separate terminal-label owner.

    The callback receives the prediction receipt path and returns4096 rewards, NaN outside TEST.
    The input CSR remains label-free and immutable; scoring uses a shallow copy of its array map.
    """
    config.validate()
    _validate_subject(cache, corpus, old_names)
    data.require(callable(load_test_labels) and cache.arrays['reward'].shape == (4096,)
                 and np.isnan(cache.arrays['reward']).all(),
                 'TEST prediction requires a label-free CSR and a separate label-admission callback.')
    output, original = Path(output), Path(original)
    selection = data.read(output / 'selected.json')
    data.require(selection['kind'] == KIND and not selection['testInspected']
                 and selection['sourceSha256'] == data.sha(original) == SOURCE_SHA
                 and selection['originalNamesSha256'] == OLD_NAMES_SHA
                 and selection['config'] == asdict(config)
                 and selection['dictionarySha256'] == corpus['dictionarySha256']
                 and selection['selectedSha256'] == data.sha(output / 'warm-best.pt')
                 and selection['fit']['modelSha256'] == selection['selectedSha256']
                 and selection['fit']['testInspected'] is False,
                 'TEST requires the unchanged validation-selected model, source and dictionary.')
    data.document(output / 'test-attempt.json', dict(selectionSha256=data.sha(output / 'selected.json'),
        selectedSha256=selection['selectedSha256'], maximumAttempts=1))
    selected_model = _critic(torch.load(output / 'warm-best.pt', map_location='cpu', weights_only=True),
                             len(cache.feature_names), device)
    old_model = _critic(torch.load(original, map_location='cpu', weights_only=True), len(old_names), device)
    games = [g['id'] for g in corpus['games'] if g['split'] == 'test']
    predicted = benchmark.predict(selected_model, cache, games, device, guard, maximum=config.maximumDenseRows)
    baseline = np.full_like(predicted, np.nan)
    old = np.full_like(predicted, np.nan)
    a = cache.arrays
    with torch.no_grad():
        for rows in benchmark.rows_in_chunks(cache, games, config.maximumDenseRows):
            guard()
            for context in np.unique(a['context'][rows]):
                value = selection['trainingConstants'][CONTEXTS[context]]['value']
                data.require(value is not None, 'A represented TEST context lacks a TRAIN constant.')
                baseline[rows[a['context'][rows] == context]] = value
            comparable = rows[a['context'][rows] < 3]
            if len(comparable):
                inputs = torch.from_numpy(cache.dense(comparable)[:, :len(old_names)]).to(device)
                old[comparable] = old_model(inputs).detach().cpu().numpy()
    predictions = {'selected': predicted, 'original52': old, 'constant': baseline}
    test_rows = cache.game_rows(games)
    test_mask = np.zeros(len(a['rowGame']), dtype=bool)
    test_mask[test_rows] = True
    files = {}
    for name, values in predictions.items():
        expected = test_mask & (a['context'] < 3) if name == 'original52' else test_mask
        data.require(np.isfinite(values[expected]).all()
                     and ((values[expected] >= 0) & (values[expected] <= 1)).all()
                     and np.isnan(values[~expected]).all(),
                     'Each frozen prediction array must cover only its declared TEST context rows.')
        filename = f'test-predictions-{name}.npy'
        with (output / filename).open('xb') as stream:
            np.save(stream, values, allow_pickle=False)
        files[filename] = data.sha(output / filename)
    receipt_path = output / 'test-predictions.json'
    data.document(receipt_path, dict(kind='afterstate-test-predictions-1',
        selectionSha256=data.sha(output / 'selected.json'), selectedSha256=selection['selectedSha256'],
        sourceSha256=SOURCE_SHA, dictionarySha256=corpus['dictionarySha256'],
        testIds=games, files=files, predictionsComplete=True, semanticLabelsDecoded=False))
    prediction_receipt_sha = data.sha(receipt_path)
    guard()
    targets = np.asarray(load_test_labels(receipt_path), dtype=np.float64).copy()
    data.require(targets.shape == (4096,) and np.isnan(targets[:3584]).all()
                 and np.isfinite(targets[3584:]).all()
                 and ((targets[3584:] >= 0) & (targets[3584:] <= 1)).all(),
                 'The admitted label owner must return only the512 finite TEST rewards.')
    data.require(np.isnan(cache.arrays['reward']).all()
                 and data.sha(receipt_path) == prediction_receipt_sha
                 and all(data.sha(output / name) == digest for name, digest in files.items()),
                 'Label admission cannot mutate immutable CSR rewards or already-frozen predictions.')
    scoring = copy.copy(cache)
    scoring.arrays = dict(cache.arrays, reward=targets)
    cache = scoring
    errors = {'selected': {'overall': world_errors(predicted, cache, games)},
              'constant': {'overall': world_errors(baseline, cache, games)}, 'original52': {}}
    for context, name in enumerate(CONTEXTS):
        for model, values in [('selected', predicted), ('constant', baseline)]:
            errors[model][name] = world_errors(values, cache, games, context)
        if context < 3:
            errors['original52'][name] = world_errors(old, cache, games, context)
    contrasts = {'overall.selected-minus-constant': errors['selected']['overall'] - errors['constant']['overall']}
    coverage = {}
    for context, name in enumerate(CONTEXTS):
        count = int(np.isfinite(errors['selected'][name]).sum())
        sufficient = context < 3 or count >= config.minimumNewContextTestWorlds
        coverage[name] = dict(independentWorlds=count, sufficientCoverage=sufficient,
            minimumRequired=config.minimumNewContextTestWorlds if context >= 3 else None)
        if sufficient:
            contrasts[name + '.selected-minus-constant'] = errors['selected'][name] - errors['constant'][name]
            if context < 3:
                contrasts[name + '.selected-minus-original52'] = errors['selected'][name] - errors['original52'][name]
    comparisons = shared_bootstrap(contrasts, config.bootstrapSeed, config.bootstrapReplicates)
    records = [dict(id=game, mse={model: {name: float(values[i]) if np.isfinite(values[i]) else None
        for name, values in views.items()} for model, views in errors.items()}) for i, game in enumerate(games)]
    data.document(output / 'test-world-metrics.json', records)
    # The selected and constant whole-world calibration uses the same context-balanced loss weights.
    report = dict(kind=KIND, selectionSha256=data.sha(output / 'selected.json'),
        selectedSha256=selection['selectedSha256'], testWorlds=len(games),
        predictionsSha256=prediction_receipt_sha, predictionsFrozenBeforeLabels=True,
        models={model: {name: _summary(values) for name, values in views.items()} for model, views in errors.items()},
        calibration={name: dict(overall=_metrics(values, cache, games)['calibration'],
            byContext={context: benchmark.group_metrics(values, cache, games,
                a['context'] == index)['calibration'] for index, context in enumerate(CONTEXTS)})
            for name, values in [('selected', predicted), ('constant', baseline)]},
        comparisons=comparisons, contextCoverage=coverage,
        primaryComparison='overall.selected-minus-constant',
        allNewContextsSufficient=all(coverage[name]['sufficientCoverage'] for name in CONTEXTS[3:]),
        testNeverUsedForSelection=True, original52Contexts=list(CONTEXTS[:3]),
        exactTerminalAndChaosRows='Excluded from learned loss and metrics; reader retains exact bypass counts.',
        actualFits=1, newGames=0, actorTraining=False, productionActivation=False,
        qualificationPassed=False, humanStrengthVerified=False,
        limitation='On-policy state prediction only; no hypothetical action-ranking or whole-game strength claim.')
    data.document(output / 'report.json', report)
    return report
