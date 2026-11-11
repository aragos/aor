"""Two state-only critics with equal whole-world, then equal nonempty-phase weighting.

The warm arm expands the exact refreshed purchase critic without changing its purchase-only
function. Validation alone selects an epoch and arm. Final testing compares only that selection
with a stitched training-card constant and the unchanged refreshed purchase critic; this is a
prediction diagnostic, not an action value, actor update or playing-strength qualification.
"""
from dataclasses import asdict
from pathlib import Path

import numpy as np
import torch

import purchase_value_benchmark as benchmark
import purchase_value_data as data


KIND = 'joint-state-value-benchmark-1'
REFRESHED_SHA = 'c2ea78e7a225998e7c690bdce852f3502d8198a464f37fd1674bbc01142b7f17'
CARD, PURCHASE = 0, 1
PHASES = ('card', 'purchase')
PARITY_TOLERANCE = 2e-6


def purchase_columns(purchase_names, joint_names):
    """Map the original ordered purchase dictionary into a sorted, namespaced union."""
    data.require(purchase_names and len(set(purchase_names)) == len(purchase_names),
                 'The purchase dictionary must be nonempty and unique.')
    data.require(joint_names == sorted(set(joint_names))
                 and all(name.startswith(('card.', 'purchase.')) for name in joint_names),
                 'Joint state names must be a sorted unique card/purchase union.')
    expected = {'purchase.' + name for name in purchase_names}
    data.require({name for name in joint_names if name.startswith('purchase.')} == expected
                 and any(name.startswith('card.') for name in joint_names),
                 'The joint dictionary must preserve every original purchase input exactly once.')
    indices = {name: index for index, name in enumerate(joint_names)}
    return np.asarray([indices['purchase.' + name] for name in purchase_names], dtype=np.int64)


def expand_warm_state(original, purchase_names, joint_names, seed):
    """Copy purchase columns and every bias/output tensor; retain fresh seeded card columns."""
    columns = purchase_columns(purchase_names, joint_names)
    shapes = {'layers.0.weight': (64, len(purchase_names)), 'layers.0.bias': (64,),
              'layers.2.weight': (1, 64), 'layers.2.bias': (1,)}
    data.require(set(original) == set(shapes) and all(
        isinstance(original[key], torch.Tensor) and tuple(original[key].shape) == shape
        and original[key].dtype == torch.float32 and bool(torch.isfinite(original[key]).all())
        for key, shape in shapes.items()), 'The warm source must be the finite FP32 64-unit critic.')
    expanded = benchmark.Critic((64,), seed, inputs=len(joint_names)).state_dict()
    expanded['layers.0.weight'][:, columns] = original['layers.0.weight'].detach().cpu()
    for key in shapes:
        if key != 'layers.0.weight':
            expanded[key] = original[key].detach().cpu().clone()
    return expanded


def warm_purchase_parity(original, expanded, purchase_names, joint_names, states):
    """FP32 purchase-only equivalence; supplied rows must be synthetic or frozen TRAINING states."""
    columns = purchase_columns(purchase_names, joint_names)
    states = np.asarray(states, dtype=np.float32)
    data.require(states.ndim == 2 and states.shape[1] == len(purchase_names)
                 and len(states) > 0 and np.isfinite(states).all(),
                 'Warm parity requires finite original purchase-state rows.')
    before = benchmark.Critic((64,), 0, inputs=len(purchase_names))
    after = benchmark.Critic((64,), 0, inputs=len(joint_names))
    before.load_state_dict(original, strict=True)
    after.load_state_dict(expanded, strict=True)
    projected = np.zeros((len(states), len(joint_names)), dtype=np.float32)
    projected[:, columns] = states
    with torch.no_grad():
        difference = (before(torch.from_numpy(states)) - after(torch.from_numpy(projected))).abs()
    maximum = float(difference.max())
    data.require(maximum <= PARITY_TOLERANCE, 'Warm purchase-only FP32 parity exceeded 2e-6.')
    return dict(rows=len(states), maximumAbsoluteError=maximum, tolerance=PARITY_TOLERANCE,
                dtype='float32', unchangedPurchaseFunction=True)


def phase_row_weights(cache):
    """One total weight per represented world, split equally over its nonempty phases."""
    a = cache.arrays
    data.require(np.isin(a['phase'], (CARD, PURCHASE)).all(), 'Rows require CARD or PURCHASE phase ownership.')
    weights = np.zeros(len(a['rowGame']), dtype=np.float64)
    for game, (start, stop) in enumerate(zip(a['starts'], a['stops'], strict=True)):
        rows = np.arange(int(start), int(stop), dtype=np.int64)
        data.require((a['rowGame'][rows] == game).all(), 'Every CSR row must retain its whole-world owner.')
        counts = np.bincount(a['phase'][rows], minlength=2)
        nonempty = int(np.count_nonzero(counts))
        if nonempty:
            weights[rows] = 1. / (nonempty * counts[a['phase'][rows]])
    return weights


def world_errors(predictions, cache, games, *, balanced=True, phase=None):
    """Return errors aligned to all supplied worlds; no-row/group worlds are explicit NaN."""
    a = cache.arrays
    output = np.full(len(games), np.nan, dtype=np.float64)
    for index, game in enumerate(games):
        rows = np.arange(int(a['starts'][game]), int(a['stops'][game]), dtype=np.int64)
        if phase is not None:
            rows = rows[a['phase'][rows] == phase]
        if not len(rows):
            continue
        values = predictions[rows]
        data.require(np.isfinite(values).all() and ((0 <= values) & (values <= 1)).all(),
                     'Critic output must be finite in [0,1].')
        errors = (values - a['reward'][game]) ** 2
        output[index] = (np.mean([errors[a['phase'][rows] == item].mean()
                                 for item in np.unique(a['phase'][rows])])
                         if balanced else errors.mean())
    return output


def phase_balanced_errors(predictions, cache, games):
    errors = world_errors(predictions, cache, games)
    return errors[np.isfinite(errors)]


def selected_arm(receipts):
    data.require(set(receipts) == {'warm', 'fresh'} and all(not item['testInspected']
                 and np.isfinite(item['best']['validationMse']) for item in receipts.values()),
                 'Select only two completed validation-only fits.')
    # fit_one retains the earlier epoch on an exact tie; warm wins an arm tie.
    return min(('warm', 'fresh'), key=lambda name: receipts[name]['best']['validationMse'])


def training_card_mean(cache, corpus):
    a = cache.arrays
    games = [game['id'] for game in corpus['games'] if game['split'] == 'training'
             and (a['phase'][int(a['starts'][game['id']]):int(a['stops'][game['id']])] == CARD).any()]
    data.require(games, 'The stitched card baseline requires represented TRAINING worlds.')
    return float(a['reward'][games].mean()), len(games)


def stitched_predictions(critic, constant, cache, games, purchase_names, joint_names,
                         device, guard, maximum=4096):
    """The purchase critic sees only original purchase columns of PURCHASE rows."""
    columns = purchase_columns(purchase_names, joint_names)
    output = np.full(len(cache.arrays['rowGame']), np.nan, dtype=np.float64)
    critic.eval()
    with torch.no_grad():
        for rows in benchmark.rows_in_chunks(cache, games, maximum):
            guard()
            card = rows[cache.arrays['phase'][rows] == CARD]
            purchase = rows[cache.arrays['phase'][rows] == PURCHASE]
            output[card] = constant
            if len(purchase):
                states = torch.from_numpy(cache.dense(purchase)[:, columns]).to(device)
                output[purchase] = critic(states).detach().cpu().numpy()
    return output


def error_views(predictions, cache, games):
    return dict(phaseBalanced=world_errors(predictions, cache, games),
                rawWholeWorld=world_errors(predictions, cache, games, balanced=False),
                card=world_errors(predictions, cache, games, phase=CARD),
                purchase=world_errors(predictions, cache, games, phase=PURCHASE))


def evaluate_selected(selected, baseline, cache, corpus, config):
    """Whole-world paired uncertainty; omitted phases never receive invented observations."""
    games = [game['id'] for game in corpus['games'] if game['split'] == 'test']
    errors = {name: error_views(values, cache, games)
              for name, values in [('selected', selected), ('stitched', baseline)]}
    metrics, comparisons = {}, {}
    for name, views in errors.items():
        metrics[name] = {key: dict(representedWorlds=int(np.isfinite(values).sum()),
            excludedWorlds=int(np.isnan(values).sum()),
            mse=float(np.nanmean(values)) if np.isfinite(values).any() else None)
            for key, values in views.items()}
    for key in errors['selected']:
        left, right = errors['selected'][key], errors['stitched'][key]
        present = np.isfinite(left)
        data.require(np.array_equal(present, np.isfinite(right)),
                     'Paired comparisons require identical represented whole worlds.')
        comparisons[key] = (benchmark.paired_bootstrap(left[present] - right[present],
            config.bootstrapSeed, config.bootstrapReplicates) if present.sum() > 1 else
            dict(games=int(present.sum()), mean=float((left-right)[present].mean()) if present.any() else None,
                 lower95=None, upper95=None, reason='Fewer than two represented test worlds.'))
    records = [dict(id=game, gameSeed=corpus['games'][game]['gameSeed'],
        mse={name: {key: float(values[index]) if np.isfinite(values[index]) else None
                    for key, values in views.items()} for name, views in errors.items()})
        for index, game in enumerate(games)]
    return metrics, comparisons, records


def fit(cache, corpus, output, config, guard, device, *, purchase_names, joint_names, critic_path):
    """Fit exactly two critics; caller owns the frozen corpus, runtime and absolute deadline."""
    output, critic_path = Path(output), Path(critic_path)
    data.require(not output.exists(), 'The joint critic fit is one attempt in a new output directory.')
    data.require(len(purchase_names) == 3556 and len(joint_names) == 7241
                 and corpus['stateFeatureNames'] == joint_names == cache.feature_names,
                 'The frozen card/purchase dictionary or cache binding changed.')
    purchase_columns(purchase_names, joint_names)
    data.require({part: sum(g['split'] == part for g in corpus['games'])
                  for part in ('training', 'validation', 'test')}
                 == dict(training=3072, validation=512, test=512), 'Whole-world split counts changed.')
    data.require((config.epochs, config.learningRate, config.gamesPerBatch,
                  config.maximumDenseRows, config.bootstrapReplicates) == (20, .001, 64, 4096, 2000),
                 'The original optimizer, dense bound and bootstrap recipe must remain fixed.')
    data.require(data.sha(critic_path) == REFRESHED_SHA, 'The exact refreshed49 purchase critic changed.')
    original = torch.load(critic_path, map_location='cpu', weights_only=True)
    warm = expand_warm_state(original, purchase_names, joint_names, config.smallSeed)
    synthetic = ((np.arange(16 * len(purchase_names)).reshape(16, -1) % 17) - 8).astype(np.float32) / 8
    synthetic[0] = 0
    parity = warm_purchase_parity(original, warm, purchase_names, joint_names, synthetic)
    output.mkdir(parents=True)
    data.document(output/'warm-synthetic-parity.json', parity)
    weights = phase_row_weights(cache)
    models, receipts = {}, {}
    for name, seed in [('warm', config.smallSeed), ('fresh', config.largeSeed)]:
        guard()
        models[name], receipts[name] = benchmark.fit_one(name, (64,), seed, cache, corpus,
            output, config, guard, device, initial_state=warm if name == 'warm' else None,
            input_count=len(joint_names), training_row_weights=weights,
            validation_errors=phase_balanced_errors)
    arm = selected_arm(receipts)
    selection = dict(kind=KIND, arm=arm, fits=receipts,
        selectedSha256=receipts[arm]['modelSha256'], warmInitialSha256=REFRESHED_SHA,
        dictionarySha256=corpus['dictionarySha256'], inputCount=len(joint_names),
        config=asdict(config), testInspected=False,
        selection='Lowest validation phase-balanced whole-world MSE; earlier epoch, then warm ties.',
        weighting='Equal represented world, then equal nonempty phase, then equal rows within phase.',
        warmOptimizer='Fresh Adam; copied purchase columns, biases and output; freshly seeded card columns.',
        qualificationPassed=False, productionActivation=False)
    data.document(output/'selected.json', selection)
    data.require(all(data.sha(output/(name+'-best.pt')) == item['modelSha256']
                     for name, item in receipts.items()), 'Validation-selected model bytes changed.')
    baseline = benchmark.Critic((64,), 0).to(device)
    baseline.load_state_dict(original, strict=True)
    constant, constant_worlds = training_card_mean(cache, corpus)
    games = [game['id'] for game in corpus['games'] if game['split'] == 'test']
    predictions = benchmark.predict(models[arm], cache, games, device, guard,
                                    maximum=config.maximumDenseRows)
    stitched = stitched_predictions(baseline, constant, cache, games, purchase_names,
                                    joint_names, device, guard, config.maximumDenseRows)
    metrics, comparisons, records = evaluate_selected(predictions, stitched, cache, corpus, config)
    data.document(output/'test-game-metrics.json', records)
    report = dict(kind=KIND, selectedArm=arm, selectedSha256=selection['selectedSha256'],
        refreshedPurchaseSha256=REFRESHED_SHA, selectionSha256=data.sha(output/'selected.json'),
        testWorlds=len(games), models=metrics, comparisons=comparisons,
        primaryComparison='phaseBalanced.selected-minus-stitched',
        cardTrainingWorldMean=constant, cardConstantTrainingWorlds=constant_worlds,
        testNeverUsedForSelection=True, nonselectedTestEvaluations=0,
        gameMetricsSha256=data.sha(output/'test-game-metrics.json'),
        qualificationPassed=False, productionActivation=False, actorTraining=False,
        humanStrengthVerified=False,
        limitation='State prediction under fixed play; no evidence of action ranking or stronger play.')
    data.document(output/'report.json', report)
    guard()
    data.document(output/'finished.json', dict(passed=True, actualFits=2, newGames=0,
        artifacts={str(path.relative_to(output)): data.sha(path) for path in output.rglob('*')
                   if path.is_file()}))
    return report
