"""Two predeclared fresh state critics, with whole-game weighting and sealed final evaluation.

This module produces benchmark artifacts only, never an automata actor or qualification result.
No action features, chosen actions, behavior predictions or terminal scores enter model inputs.
"""
from __future__ import annotations

from dataclasses import asdict
from pathlib import Path
import time

import numpy as np
import torch
from torch import nn

import purchase_value_data as data


Config = data.Config

class Critic(nn.Module):
    """Fresh 3,556-state MLP; one or two tanh layers and sigmoid terminal-reward output."""
    def __init__(self, widths, seed, inputs=data.STATE_COUNT):
        super().__init__()
        data.require(tuple(widths) in ((64,), (256, 256)), 'Only the two declared critic architectures are allowed.')
        generator = torch.Generator(device='cpu').manual_seed(seed)
        sizes = [inputs, *widths, 1]
        layers = []
        for index, (before, after) in enumerate(zip(sizes, sizes[1:])):
            linear = nn.Linear(before, after, dtype=torch.float32)
            nn.init.xavier_uniform_(linear.weight, generator=generator)
            nn.init.zeros_(linear.bias)
            layers.extend((linear, nn.Sigmoid() if index == len(sizes)-2 else nn.Tanh()))
        self.layers = nn.Sequential(*layers)

    def forward(self, inputs):
        return self.layers(inputs).flatten()


class FrozenC64(nn.Module):
    """Exact state-only C64 critic formula; actor/prior/action parameters are unused."""
    def __init__(self, model):
        super().__init__()
        self.register_buffer('stateWeights', torch.tensor(model['stateWeights'], dtype=torch.float64).reshape(64, -1))
        self.register_buffer('stateBias', torch.tensor(model['stateBias'], dtype=torch.float64))
        self.register_buffer('valueWeights', torch.tensor(model['valueWeights'], dtype=torch.float64))
        self.register_buffer('valueBias', torch.tensor(model['valueBias'], dtype=torch.float64))

    def forward(self, inputs):
        return torch.sigmoid(torch.tanh(inputs @ self.stateWeights.T+self.stateBias)
                             @ self.valueWeights+self.valueBias)


def configure():
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.backends.cudnn.benchmark = False


def game_weights(owners, lengths):
    """Each complete game has total weight one regardless of its purchase-decision count."""
    return 1. / lengths[owners]


def order(games, epoch, seed):
    generator = np.random.Generator(np.random.PCG64(seed+epoch))
    return generator.permutation(np.asarray(games, dtype=np.int64))


def rows_in_chunks(cache, games, maximum):
    rows = cache.game_rows(games)
    for start in range(0, len(rows), maximum):
        yield rows[start:start+maximum]


def predict(model, cache, games, device, guard, dtype='float32', maximum=4096):
    model.eval()
    output = np.full(len(cache.arrays['rowGame']), np.nan, dtype=np.float64)
    with torch.no_grad():
        for rows in rows_in_chunks(cache, games, maximum):
            guard()
            x = torch.from_numpy(cache.dense(rows, dtype=dtype)).to(device)
            output[rows] = model(x).detach().cpu().numpy()
    return output


def per_game_mse(predictions, cache, games):
    a = cache.arrays
    return np.asarray([np.mean((predictions[int(a['starts'][g]):int(a['stops'][g])]-a['reward'][g])**2)
                       for g in games], dtype=np.float64)


def fit_one(name, widths, seed, cache, corpus, output, config, guard, device):
    """One fit, best validation epoch only; never accesses test indices or test metrics."""
    model = Critic(widths, seed).to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=config.learningRate, betas=(.9, .999),
                                 eps=1e-8, weight_decay=0, foreach=False, fused=False)
    training = [g['id'] for g in corpus['games'] if g['split'] == 'training']
    validation = [g['id'] for g in corpus['games'] if g['split'] == 'validation']
    data.require(set(training).isdisjoint(validation), 'Whole-game model selection cannot overlap training.')
    a = cache.arrays
    lengths = a['stops']-a['starts']
    history = []
    best = None
    started = time.monotonic()
    torch.cuda.reset_peak_memory_stats(device)
    for epoch in range(config.epochs):
        guard()
        model.train()
        shuffled = order(training, epoch, config.orderSeed)
        squared_error = 0.
        for offset in range(0, len(shuffled), config.gamesPerBatch):
            games = shuffled[offset:offset+config.gamesPerBatch]
            optimizer.zero_grad(set_to_none=True)
            for rows in rows_in_chunks(cache, games, config.maximumDenseRows):
                guard()
                owners = a['rowGame'][rows]
                x = torch.from_numpy(cache.dense(rows)).to(device)
                targets = torch.tensor(a['reward'][owners], dtype=torch.float32, device=device)
                weights = torch.tensor(game_weights(owners, lengths), dtype=torch.float32, device=device)
                losses = (model(x)-targets).square()*weights
                (losses.sum()/len(games)).backward()
                squared_error += float(losses.detach().sum())
            optimizer.step()
        predictions = predict(model, cache, validation, device, guard, maximum=config.maximumDenseRows)
        validation_mse = float(per_game_mse(predictions, cache, validation).mean())
        record = dict(epoch=epoch+1, trainingMse=squared_error/len(training),
                      validationMse=validation_mse, elapsedSeconds=time.monotonic()-started)
        history.append(record)
        # Strict improvement keeps the earlier epoch on an exact tie.
        if best is None or validation_mse < best['validationMse']:
            best = dict(record)
            torch.save({key: value.detach().cpu() for key, value in model.state_dict().items()},
                       output/(name+'-best.pt'))
        data.document(output/(name+f'-epoch-{epoch+1:02}.json'), record)
    torch.cuda.synchronize(device)
    receipt = dict(name=name, widths=list(widths), seed=seed, parameterCount=sum(p.numel() for p in model.parameters()),
                   optimizer='Adam', config=asdict(config), best=best, history=history,
                   elapsedSeconds=time.monotonic()-started, peakCudaBytes=torch.cuda.max_memory_allocated(device),
                   modelSha256=data.sha(output/(name+'-best.pt')), testInspected=False,
                   dtype='float32', amp=False, tf32=False, qualificationPassed=False)
    data.document(output/(name+'-fit.json'), receipt)
    model.load_state_dict(torch.load(output/(name+'-best.pt'), map_location=device, weights_only=True))
    return model, receipt


def calibration(predictions, targets, weights):
    """Ten fixed-width prediction bins; weights sum to one per represented whole game."""
    bins = np.minimum((predictions*10).astype(np.int64), 9)
    rows = []
    for index in range(10):
        mask = bins == index
        total = float(weights[mask].sum())
        rows.append(dict(bin=index, lower=index/10, upper=(index+1)/10,
                         rows=int(mask.sum()), gameWeight=total,
                         meanPrediction=float(np.average(predictions[mask], weights=weights[mask])) if total else None,
                         meanReward=float(np.average(targets[mask], weights=weights[mask])) if total else None))
    return rows


def group_metrics(predictions, cache, games, row_mask=None):
    a = cache.arrays
    owners, values, targets, weights = [], [], [], []
    game_errors = []
    for game in games:
        rows = np.arange(int(a['starts'][game]), int(a['stops'][game]))
        if row_mask is not None:
            rows = rows[row_mask[rows]]
        if not len(rows):
            continue
        p = predictions[rows]
        data.require(np.isfinite(p).all() and ((0 <= p) & (p <= 1)).all(), 'Critic output must be finite in [0,1].')
        target = float(a['reward'][game])
        game_errors.append(float(np.mean((p-target)**2)))
        owners.append(game)
        values.extend(p)
        targets.extend([target]*len(rows))
        weights.extend([1/len(rows)]*len(rows))
    if not owners:
        return dict(games=0, rows=0, mse=None, calibration=[])
    p, y, w = map(np.asarray, (values, targets, weights))
    return dict(games=len(owners), rows=len(values), mse=float(np.mean(game_errors)),
                mae=float(np.average(abs(p-y), weights=w)), bias=float(np.average(p-y, weights=w)),
                calibration=calibration(p, y, w),
                weighting='Equal represented whole-game weight; equal rows within each game/group.')


def paired_bootstrap(differences, seed, replicates):
    """Fixed-seed paired percentile bootstrap across whole games, never individual decisions."""
    differences = np.asarray(differences, dtype=np.float64)
    data.require(len(differences) > 1 and np.isfinite(differences).all(), 'Bootstrap needs finite whole-game contrasts.')
    rng = np.random.Generator(np.random.PCG64(seed))
    means = []
    for start in range(0, replicates, 100):
        count = min(100, replicates-start)
        selected = rng.integers(0, len(differences), size=(count, len(differences)))
        means.extend(differences[selected].mean(axis=1).tolist())
    bounds = np.quantile(means, [.025, .975], method='linear')
    return dict(games=len(differences), mean=float(differences.mean()), lower95=float(bounds[0]),
                upper95=float(bounds[1]), replicates=replicates, seed=seed,
                method='Paired whole-game percentile bootstrap; negative means lower MSE.')


def evaluate(models, cache, corpus, output, receipts, config, guard, device):
    """Open the final ecology test only after both validation-selected models are bound."""
    selected = data.read(output/'selected.json')
    data.require(selected['fits'] == receipts and all(not r['testInspected'] for r in receipts.values()),
                 'Final evaluation requires both completed validation-only fits.')
    data.require(all(data.sha(output/(name+'-best.pt')) == row['modelSha256'] for name, row in receipts.items()),
                 'A validation-selected model changed before final testing.')
    training = [g['id'] for g in corpus['games'] if g['split'] == 'training']
    constant = float(np.mean(cache.arrays['reward'][training]))
    results, game_metrics = {}, {}
    for split in ('validation', 'test'):
        games = [g['id'] for g in corpus['games'] if g['split'] == split]
        predictions = {name: predict(model, cache, games, device, guard,
                                      dtype='float64' if name == 'c64' else 'float32')
                       for name, model in models.items()}
        predictions['train-constant'] = np.full(len(cache.arrays['rowGame']), constant, dtype=np.float64)
        cells = {}
        for name, values in predictions.items():
            guard()
            cells[name] = dict(overall=group_metrics(values, cache, games),
                byStage={stage: group_metrics(values, cache, games, cache.arrays['stage'] == index)
                         for index, stage in enumerate(('purchase-shipping', 'purchase-advances'))},
                byEpoch={str(epoch): group_metrics(values, cache, games, cache.arrays['epoch'] == epoch)
                         for epoch in (1, 2, 3)},
                byEcology={dataset: group_metrics(values, cache,
                           [g for g in games if corpus['games'][g]['dataset'] == dataset])
                           for dataset in data.DATASETS})
        errors = {name: per_game_mse(values, cache, games) for name, values in predictions.items()}
        contrasts = {'large-minus-small': paired_bootstrap(errors['large']-errors['small'], config.bootstrapSeed,
                                                           config.bootstrapReplicates)}
        for name in ('small', 'large'):
            for reference in ('c64', 'train-constant'):
                contrasts[name+'-minus-'+reference] = paired_bootstrap(
                    errors[name]-errors[reference], config.bootstrapSeed, config.bootstrapReplicates)
        results[split] = dict(models=cells, contrasts=contrasts)
        game_metrics[split] = [dict(id=game, seed=corpus['games'][game]['gameSeed'],
                                   mse={name: float(values[i]) for name, values in errors.items()})
                              for i, game in enumerate(games)]
    data.document(output/'game-metrics.json', game_metrics)
    data.document(output/'report.json', dict(kind='purchase-state-value-benchmark-1', results=results,
        trainConstant=constant, primaryComparison='test.large-minus-small',
        c64ComparatorCaveat='C64 was exposed to its ancestry; fresh critics are the primary fair capacity comparison.',
        testNeverUsedForSelection=True, newGames=0, actorTraining=False, actionQ=False,
        qualificationPassed=False, productionActivation=False, humanStrengthVerified=False,
        gameMetricsSha256=data.sha(output/'game-metrics.json')))


def run(package, output, guard):
    package, output = Path(package), Path(output)
    protocol = data.read(package/'protocol.json')
    config = Config(**protocol['config']).validate()
    configure()
    data.require(torch.cuda.is_available(), 'The frozen benchmark requires the declared CUDA device.')
    device = torch.device('cuda:0')
    corpus = data.read(package/'corpus.json')
    data.verify_declaration(corpus)
    data.build_cache(package, output/'cache', guard)
    cache = data.Cache(output/'cache')
    fits = output/'fits'
    fits.mkdir()
    models, receipts = {}, {}
    for name, widths, seed in (('small', (64,), config.smallSeed), ('large', (256, 256), config.largeSeed)):
        guard()
        models[name], receipts[name] = fit_one(name, widths, seed, cache, corpus, fits, config, guard, device)
    data.document(fits/'selected.json', dict(fits=receipts, selection='Lowest whole-game validation MSE; earlier epoch on ties.',
                                           testInspected=False, qualificationPassed=False))
    models['c64'] = FrozenC64(data.read(package/'inputs/c64.json')).to(device)
    evaluate(models, cache, corpus, fits, receipts, config, guard, device)
    guard()
    data.document(output/'finished.json', dict(kind='purchase-state-value-benchmark-1',
        reportSha256=data.sha(fits/'report.json'), selectedSha256=data.sha(fits/'selected.json'),
        artifacts={str(p.relative_to(output)): data.sha(p) for p in output.rglob('*') if p.is_file()},
        newGames=0, productionActivation=False, qualificationPassed=False))
