"""Fixed joint-economy candidates and complete, paired, stratified-world statistics.

Only eight existing BotModel weights vary. Purchase32 and ordinary card behavior
remain the native controller's responsibility. These helpers neither run games nor
activate policies. Screening/selection intervals do not correct selection bias.
"""
from copy import deepcopy
import gzip
import hashlib
import itertools
import json
import math
from pathlib import Path

import numpy as np

from card_advantage_experiment import completed_native


VERSION = 'aor-joint-economy-1'
AXES = {
    'liquidity': ('planning.tokenValue.v1.costFraction',
                  'planning.tokenValue.v2.plan.interest'),
    'investment': ('planning.tokenValue.v2.plan.heldCommodity',
                   'planning.tokenValue.v2.plan.publicCommodity',
                   'planning.tokenValue.v2.plan.income',
                   'planning.tokenValue.v2.plan.endgameIncome'),
    'expansion': ('planning.heldCommodity', 'planning.publicCommodity'),
}
STAGES = {'screen': (64, 14), 'selection': (192, 3), 'confirmation': (1024, 1)}
POPULATIONS = ('f-purchase32', 'priority64-purchase32')
BOOTSTRAP = {'resamples': 10000, 'seed': 501107, 'percentiles': [2.5, 97.5]}
METRICS = ('winShare', 'rankReward', 'reward')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON field: ' + key)
            result[key] = value
        return result
    return json.loads(Path(path).read_bytes(), object_pairs_hook=unique,
                      parse_constant=lambda value: require(False, 'Nonfinite JSON value: ' + value))


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n').encode()


def candidate_specs():
    """Canonical generation order; ranking ties instead use lexical candidate ID."""
    result = [('incumbent', dict.fromkeys(AXES, 1.0))]
    for axis in AXES:
        for suffix, factor in (('low', .5), ('high', 1.5)):
            factors = dict.fromkeys(AXES, 1.0)
            factors[axis] = factor
            result.append((axis + '-' + suffix, factors))
    for values in itertools.product((.5, 1.5), repeat=3):
        result.append(('corner-' + ''.join('l' if value == .5 else 'h' for value in values),
                       dict(zip(AXES, values))))
    return result


CANDIDATE_IDS = tuple(name for name, _ in candidate_specs())


def validate_base(model):
    require(set(model) == {'schemaVersion', 'rulesVersion', 'dataVersion', 'weights'}
            and type(model['schemaVersion']) is int and model['schemaVersion'] == 1,
            'Candidates require the complete ordinary BotModel schema 1 wire object.')
    require(all(isinstance(model[key], str) and model[key] for key in ('rulesVersion', 'dataVersion')),
            'Ordinary model rules and catalog identities must be explicit.')
    weights = model['weights']
    require(isinstance(weights, dict) and len(weights) <= 4096
            and all(isinstance(key, str) and 1 <= len(key) <= 120
                    and type(value) in (int, float) and math.isfinite(value)
                    and abs(value) <= 1e6 for key, value in weights.items()),
            'Ordinary model weights must be named, finite and bounded.')
    require(all(key in weights for keys in AXES.values() for key in keys),
            'The frozen base must explicitly contain all eight economic weights.')


def models_from(base):
    validate_base(base)
    result = {}
    for name, factors in candidate_specs():
        model = deepcopy(base)
        for axis, keys in AXES.items():
            for key in keys:
                model['weights'][key] = base['weights'][key] * factors[axis]
        validate_base(model)
        result[name] = model
    return result


def changes(base, model):
    return [dict(key=key, before=base['weights'][key], after=model['weights'][key])
            for keys in AXES.values() for key in keys
            if base['weights'][key] != model['weights'][key]]


def generate(base_model_path, output_dir):
    """Create one fresh immutable candidate set; never repair existing evidence."""
    base_path = Path(base_model_path).resolve(strict=True)
    output = Path(output_dir)
    require(not output.exists(), 'Candidate output must be a fresh directory.')
    base = read(base_path)
    models = models_from(base)
    output.mkdir(parents=True)
    rows = []
    for name, factors in candidate_specs():
        path = output / (name + '.json')
        path.write_bytes(base_path.read_bytes() if name == 'incumbent' else encoded(models[name]))
        rows.append(dict(id=name, factors=factors, file=path.name, sha256=sha(path),
                         weightChanges=changes(base, models[name])))
    manifest = dict(kind='joint-economy-candidates', schemaVersion=1, economyVersion=VERSION,
                    base=dict(path=str(base_path), sha256=sha(base_path)),
                    axes={key: list(value) for key, value in AXES.items()}, candidates=rows)
    (output / 'manifest.json').write_bytes(encoded(manifest))
    load_candidates(output)
    return manifest


def load_candidates(directory):
    directory = Path(directory).resolve(strict=True)
    manifest = read(directory / 'manifest.json')
    require(set(manifest) == {'kind', 'schemaVersion', 'economyVersion', 'base', 'axes', 'candidates'}
            and manifest['kind'] == 'joint-economy-candidates'
            and type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1
            and manifest['economyVersion'] == VERSION
            and manifest['axes'] == {key: list(value) for key, value in AXES.items()},
            'Candidate manifest must preserve the fixed economic axes and version.')
    base_path = Path(manifest['base']['path']).resolve(strict=True)
    require(manifest['base'] == dict(path=str(base_path), sha256=sha(base_path)),
            'Frozen candidate base bytes changed.')
    base = read(base_path)
    expected = models_from(base)
    require(len(manifest['candidates']) == 15, 'Exactly fifteen fixed candidates are required.')
    actual = {}
    for row, (name, factors) in zip(manifest['candidates'], candidate_specs()):
        path = directory / (name + '.json')
        require(row == dict(id=name, factors=factors, file=path.name, sha256=sha(path),
                            weightChanges=changes(base, expected[name])),
                'Candidate metadata, factors, raw changes or byte hash differ: ' + name)
        model = read(path)
        require(model == expected[name], 'Candidate changed an unauthorized weight or identity: ' + name)
        actual[name] = model
    require((directory / 'incumbent.json').read_bytes() == base_path.read_bytes(),
            'Incumbent must preserve the exact original base bytes.')
    return manifest, actual


def validate_stage(stage, seeds, ids):
    require(stage in STAGES, 'Unknown joint-economy stage.')
    worlds, count = STAGES[stage]
    require(len(seeds) == worlds and len(set(seeds)) == worlds
            and all(type(seed) is int and -(2**31) <= seed < 2**31 for seed in seeds),
            'Stage requires its complete distinct signed 32-bit world seed bank.')
    require(len(ids) == count and len(set(ids)) == count
            and all(name in CANDIDATE_IDS[1:] for name in ids),
            'Stage must contain the exact number of distinct non-incumbent fixed candidates.')
    if stage == 'screen':
        require(list(ids) == list(CANDIDATE_IDS[1:]), 'Screen must preserve all fourteen candidate IDs/order.')


def make_source(stage, seeds, ids, base, candidate_dir, priority_path):
    validate_stage(stage, seeds, ids)
    manifest, _ = load_candidates(candidate_dir)
    require(set(base) in ({'configuration', 'baseline', 'frozenPurchasePolicy'},
                         {'schemaVersion', 'configuration', 'baseline', 'frozenPurchasePolicy'})
            and type(base.get('schemaVersion', 1)) is int
            and base.get('schemaVersion', 1) == 1, 'Evaluation base fields differ.')
    baseline = Path(base['baseline']).resolve(strict=True)
    require(sha(baseline) == manifest['base']['sha256'],
            'Evaluation F must equal the exact candidate-generation base.')
    policies = {'incumbent': {'kind': 'baseline'},
                'priority64': {'kind': 'advantage', 'path': str(Path(priority_path).resolve(strict=True))}}
    policies.update({name: dict(kind='economy', path=str((Path(candidate_dir) / (name + '.json')).resolve()))
                     for name in ids})
    jobs = []
    for world, seed in enumerate(seeds):
        seat = world % 4
        rivals = ['incumbent' if (world // 4) % 2 == 0 else 'priority64'] * 4
        rivals[seat] = 'incumbent'
        for arm in ('incumbent', *ids):
            jobs.append(dict(index=len(jobs), seed=seed, seat=seat, arm=arm, policy=arm,
                             rivals=list(rivals)))
    return dict(schemaVersion=1, configuration=base['configuration'], baseline=str(baseline),
                frozenPurchasePolicy=str(Path(base['frozenPurchasePolicy']).resolve(strict=True)),
                policies=policies, jobs=jobs)


def terminal_metrics(row):
    """Independently recover terminal labels from the four public score records."""
    terminal = row['terminal']
    scores = terminal['scores']
    require(len(scores) == 4 and {score['player'] for score in scores} == {'p1', 'p2', 'p3', 'p4'},
            'Terminal scores must identify every public player exactly once.')
    for score in scores:
        require(all(type(score[key]) is int and 0 <= score[key] < 2**31
                    for key in ('advancePoints', 'cash'))
                and (score['misery'] is None or type(score['misery']) is int and score['misery'] >= 0)
                and (score['total'] is None or type(score['total']) is int)
                and type(score['winner']) is bool,
                'Public score fields require exact integers, nullable Chaos totals and Boolean winners.')
        expected = None if score['misery'] is None else score['advancePoints'] + score['cash'] - score['misery']
        require(score['total'] == expected, 'Public score arithmetic differs.')
    totals = [score['total'] for score in scores if score['total'] is not None]
    maximum = max(totals) if totals else None
    require(all(score['winner'] == (score['total'] is not None and score['total'] == maximum)
                for score in scores), 'Finished public winner flags differ from scores.')
    own = next(score for score in scores if score['player'] == 'p' + str(row['controlledSeat'] + 1))
    win = 1 / sum(score['winner'] for score in scores) if own['winner'] else 0.0
    rank = 0.0 if own['total'] is None else math.fsum(
        1.0 if score['total'] is None or own['total'] > score['total']
        else .5 if own['total'] == score['total'] else 0.0
        for score in scores if score is not own) / 3
    expected = {'winShare': win, 'rankReward': rank, 'reward': .8 * win + .2 * rank}
    require(all(type(terminal[key]) in (int, float) and math.isfinite(terminal[key])
                and abs(terminal[key] - value) < 1e-12 for key, value in expected.items()),
            'Terminal win/rank/composite labels differ from public scores.')
    require(type(terminal['gameDecisions']) is int and terminal['gameDecisions'] > 0,
            'Completed games require positive decision counts.')
    return dict(expected, ownTotal=own['total'], chaos=own['total'] is None)


def bootstrap(values):
    """Resample complete paired worlds within each rival population, equally pooled.

    values has shape (worlds, statistics). The same resampled worlds apply to every
    candidate/metric. RNG, 250-draw chunk order and linear percentiles are fixed.
    """
    values = np.asarray(values, dtype=np.float64)
    populations = [values[np.array([(index // 4) % 2 == pop for index in range(len(values))])]
                   for pop in range(2)]
    rng = np.random.default_rng(BOOTSTRAP['seed'])
    draws = np.empty((2, BOOTSTRAP['resamples'], values.shape[1]), dtype=np.float64)
    for start in range(0, BOOTSTRAP['resamples'], 250):
        for pop, rows in enumerate(populations):
            indices = rng.integers(0, len(rows), size=(250, len(rows)))
            draws[pop, start:start + 250] = rows[indices].mean(axis=1)
    pooled = (draws[0] + draws[1]) / 2
    bounds = [np.percentile(sample, BOOTSTRAP['percentiles'], axis=0, method='linear')
              for sample in (pooled, draws[0], draws[1])]
    samples = [values, *populations]
    return [[dict(independentWorlds=len(rows), mean=math.fsum(rows[:, column]) / len(rows),
                  lower95=float(bound[0, column]), upper95=float(bound[1, column]))
             for column in range(values.shape[1])] for rows, bound in zip(samples, bounds)]


def analyze(stage, seeds, ids, records):
    validate_stage(stage, seeds, ids)
    expected = {(seed, index % 4, arm) for index, seed in enumerate(seeds)
                for arm in ('incumbent', *ids)}
    actual = [(row['seed'], row['controlledSeat'], row['arm']) for row in records]
    require(len(actual) == len(expected) and set(actual) == expected,
            'Complete paired worlds, seats and arms must occur exactly once.')
    require(all(type(row['seed']) is int and type(row['controlledSeat']) is int for row in records),
            'World and seat identifiers must be exact integers.')
    by_key = {(row['seed'], row['controlledSeat'], row['arm']): terminal_metrics(row) for row in records}
    values = [[by_key[seed, index % 4, name][metric] - by_key[seed, index % 4, 'incumbent'][metric]
               for name in ids for metric in METRICS] for index, seed in enumerate(seeds)]
    intervals = bootstrap(values)
    comparisons = {}
    for position, name in enumerate(ids):
        sections = [{metric: sample[position * len(METRICS) + offset]
                     for offset, metric in enumerate(METRICS)} for sample in intervals]
        for section, population in zip(sections, (None, 0, 1)):
            worlds = [index for index in range(len(seeds)) if population is None or (index // 4) % 2 == population]
            pairs = [(by_key[seeds[index], index % 4, name], by_key[seeds[index], index % 4, 'incumbent'])
                     for index in worlds]
            scored = [left['ownTotal'] - right['ownTotal'] for left, right in pairs
                      if left['ownTotal'] is not None and right['ownTotal'] is not None]
            section['publicScoreDescriptive'] = dict(
                bothSurvivingPairs=len(scored), excludedChaosPairs=len(pairs) - len(scored),
                meanOwnScoreDifference=math.fsum(scored) / len(scored) if scored else None,
                candidateChaosRate=sum(left['chaos'] for left, _ in pairs) / len(pairs),
                incumbentChaosRate=sum(right['chaos'] for _, right in pairs) / len(pairs))
        comparisons[name] = dict(pooled=sections[0], populations=dict(zip(POPULATIONS, sections[1:])))
    ranking = sorted(ids, key=lambda name: (-comparisons[name]['pooled']['winShare']['mean'], name))
    winner = ranking[0]
    pooled = comparisons[winner]['pooled']['winShare']
    populations = [comparisons[winner]['populations'][name]['winShare'] for name in POPULATIONS]
    report = dict(kind='joint-economy-analysis', schemaVersion=1, economyVersion=VERSION, stage=stage,
                  independentWorlds=len(seeds), executions=len(records), ranking=ranking,
                  comparisons=comparisons, bootstrap=dict(BOOTSTRAP, unit='paired world',
                  stratification='(worldIndex//4)%2; equal population composition', method='percentile-linear'),
                  diagnosticOnly=stage != 'confirmation', policyActivation=False, humanStrengthClaim=False,
                  uncertaintyLimit='Screen/selection intervals do not account for candidate selection. '
                  'Rank/composite/score statistics are descriptive; only win share determines gates.')
    if stage == 'screen':
        report.update(selected=ranking[:3], mayContinue=True)
    elif stage == 'selection':
        gates = dict(minimumPooledGain=pooled['mean'] >= .03,
                     nonnegativePopulations=all(value['mean'] >= 0 for value in populations))
        report.update(selected=winner, winner=winner, gates=gates, mayConfirm=all(gates.values()))
    else:
        gates = dict(minimumPooledGain=pooled['mean'] >= .05, positivePooledLower95=pooled['lower95'] > 0,
                     nonnegativePopulations=all(value['mean'] >= 0 for value in populations),
                     boundedPopulationLoss=all(value['lower95'] > -.03 for value in populations))
        report.update(selected=winner, winner=winner, gates=gates, passed=all(gates.values()))
    return report


def analyze_completed(stage, seeds, ids, native_dir, source_path, base, candidate_dir, priority_path):
    """Bind source/models and all completed compressed hashes before any outcome decode."""
    expected = make_source(stage, seeds, ids, base, candidate_dir, priority_path)
    source_path, directory = Path(source_path).resolve(strict=True), Path(native_dir).resolve(strict=True)
    require(read(source_path) == expected, 'Native source differs from the exact prospective stage.')
    require(completed_native(directory, source_path), 'All declared games must complete before analysis.')
    manifest, finished = read(directory / 'collection.json'), read(directory / 'finished.json')
    require(manifest['kind'] == finished['kind'] == 'card-advantage-evaluate'
            and type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1
            and manifest['economyVersion'] == VERSION
            and manifest['trajectoryEncoding'] == 'gzip-json-v1'
            and manifest['source'] == str(source_path), 'Native economic evaluation identity differs.')
    entries = finished['files']
    require([row['index'] for row in entries] == list(range(len(expected['jobs'])))
            and all(row['file'] == 'game-' + str(index) + '.json.gz' for index, row in enumerate(entries)),
            'Completion must enumerate every exact game filename once in order.')
    paths = [directory / row['file'] for row in entries]
    require(all(sha(path) == row['sha256'] for path, row in zip(paths, entries)),
            'Every compressed game hash must match before any outcomes are decoded.')
    metadata_hashes = {name: sha(directory / name) for name in ('collection.json', 'finished.json', 'state.json')}
    records = []
    for index, (path, job) in enumerate(zip(paths, expected['jobs'])):
        with gzip.open(path, 'rb') as stream:
            blob = stream.read(4 * 1024 * 1024 + 1)
        require(len(blob) <= 4 * 1024 * 1024, 'Expanded terminal record exceeds its bounded size.')
        row = json.loads(blob)
        require(type(row['schemaVersion']) is int and row['schemaVersion'] == 1
                and row['signature'] == manifest['signature']
                and type(row['index']) is int and row['index'] == index
                and type(row['seed']) is int and row['seed'] == job['seed']
                and type(row['controlledSeat']) is int and row['controlledSeat'] == job['seat']
                and row['arm'] == job['arm'],
                'Terminal record differs from its prospective native job.')
        records.append(row)
    report = analyze(stage, seeds, ids, records)
    require(all(sha(directory / name) == value for name, value in metadata_hashes.items())
            and all(sha(path) == row['sha256'] for path, row in zip(paths, entries))
            and completed_native(directory, source_path), 'Completed evidence changed during analysis.')
    report['inputs'] = dict(source=str(source_path), sourceSha256=sha(source_path), native=str(directory),
                           completionSha256=sha(directory / 'finished.json'), signature=manifest['signature'],
                           candidates=str(Path(candidate_dir).resolve()),
                           candidatesSha256=sha(Path(candidate_dir) / 'manifest.json'),
                           priority=str(Path(priority_path).resolve()), prioritySha256=sha(priority_path),
                           analysisSha256=sha(__file__))
    return report
