"""Complete-game evidence for one unchanged, repeated card-PPO signal.

The prior reversal failures remain unresolved. Training movement is a diagnostic,
not a strength claim. Development selection reuses one control bank; only the
separate final bank can qualify a frozen candidate. Nothing here activates it.
"""
import gzip
import math
from pathlib import Path

import numpy as np

from card_advantage_experiment import completed_native
from joint_economy import read, require, sha, terminal_metrics


MILESTONES = (8, 16, 24, 32)
ARMS = ('f', 'initial', 'priority64', 'candidate')
POPULATIONS = ('f-purchase32', 'priority64-purchase32')
METRICS = ('winShare', 'rankReward', 'reward')
BOOTSTRAP = dict(resamples=10000, seed=601107, percentiles=[2.5, 97.5])
TRAINING = dict(batches=32, gamesPerBatch=256, maximumCollectionSeconds=300,
                maximumUpdateSeconds=300, updateSeeds=list(range(74601, 74633)))
PPO = dict(learningRate=.0003, epochs=4, batchSize=64, clip=.2, valueCoefficient=.5,
           entropyCoefficient=0., maxGradientNorm=.5, maxBehaviorKl=.01, threads=1,
           maxSeconds=300.)
DEVELOPMENT = dict(worlds=256, milestones=list(MILESTONES), minimumGainVsF=.03,
                   minimumGainVsInitialAbove=0., minimumGainVsPriorityAbove=0.,
                   harmMeanBelow=-.10, harmUpper95Below=0.,
                   noProgressAfterBatch=16, minimumLatestAcceptedPriorKl=.0001,
                   ranking='highest eligible mean win share versus F; earliest checkpoint on exact ties')
CONFIRMATION = dict(worlds=1024, minimumGainVsF=.05, lower95VsFAbove=0.,
                    lower95VsInitialAbove=0., lower95VsPriorityAbove=0.,
                    populationMeanVsFAtLeast=0., populationLower95VsFAbove=-.03)


def validate_bank(stage, seeds):
    worlds = 1024 if stage == 'confirmation' else 256
    require(stage in ('controls', 'development', 'confirmation')
            and len(seeds) == worlds and len(set(seeds)) == worlds
            and all(type(seed) is int and -(2**31) <= seed < 2**31 for seed in seeds),
            'Use the exact complete distinct signed-integer trajectory evaluation bank.')


def make_source(stage, seeds, models, base, priority):
    """A cached three-control bank, one checkpoint bank, or the four-arm final bank."""
    validate_bank(stage, seeds)
    needed = {'initial'} if stage == 'controls' else {'candidate'} if stage == 'development' else {'initial', 'candidate'}
    require(set(models) == needed, 'Evaluation models must match the exact requested stage.')
    require(set(base) in ({'configuration', 'baseline', 'frozenPurchasePolicy'},
                         {'schemaVersion', 'configuration', 'baseline', 'frozenPurchasePolicy'})
            and type(base.get('schemaVersion', 1)) is int and base.get('schemaVersion', 1) == 1,
            'Evaluation requires its exact base configuration and purchase identity.')
    registry = {'f': {'kind': 'baseline'},
                'priority64': {'kind': 'advantage', 'path': str(Path(priority).resolve(strict=True))}}
    registry.update({name: dict(kind='card-actor', path=str(Path(path).resolve(strict=True)))
                     for name, path in models.items()})
    arms = ARMS[:3] if stage == 'controls' else ('candidate',) if stage == 'development' else ARMS
    jobs = []
    for world, seed in enumerate(seeds):
        seat = world % 4
        rivals = ['f' if (world // 4) % 2 == 0 else 'priority64'] * 4
        rivals[seat] = 'f'
        for arm in arms:
            jobs.append(dict(index=len(jobs), seed=seed, seat=seat, arm=arm, policy=arm, rivals=list(rivals)))
    return dict(schemaVersion=1, configuration=base['configuration'],
                baseline=str(Path(base['baseline']).resolve(strict=True)),
                frozenPurchasePolicy=str(Path(base['frozenPurchasePolicy']).resolve(strict=True)),
                policies=registry, jobs=jobs)


def intervals(values):
    """Independent worlds, paired arms, fixed equal rival-population composition."""
    values = np.asarray(values, dtype=np.float64)
    populations = [values[np.array([(index // 4) % 2 == pop for index in range(len(values))])]
                   for pop in range(2)]
    rng = np.random.default_rng(BOOTSTRAP['seed'])
    samples = np.empty((2, BOOTSTRAP['resamples'], values.shape[1]), dtype=np.float64)
    for start in range(0, BOOTSTRAP['resamples'], 250):
        for pop, rows in enumerate(populations):
            indices = rng.integers(0, len(rows), size=(250, len(rows)))
            samples[pop, start:start + 250] = rows[indices].mean(axis=1)
    draws = [(samples[0] + samples[1]) / 2, samples[0], samples[1]]
    result = []
    for rows, sample in zip([values, *populations], draws):
        bounds = np.percentile(sample, BOOTSTRAP['percentiles'], axis=0, method='linear')
        result.append([dict(independentWorlds=len(rows), mean=math.fsum(rows[:, column]) / len(rows),
                            lower95=float(bounds[0, column]), upper95=float(bounds[1, column]))
                       for column in range(values.shape[1])])
    return result


def analyze(stage, seeds, records):
    require(stage in ('development', 'confirmation'), 'Statistics require all four arms.')
    validate_bank(stage, seeds)
    expected = {(seed, index % 4, arm) for index, seed in enumerate(seeds) for arm in ARMS}
    actual = [(row['seed'], row['controlledSeat'], row['arm']) for row in records]
    require(len(actual) == len(expected) and set(actual) == expected
            and all(type(row['seed']) is int and type(row['controlledSeat']) is int for row in records),
            'Every complete world, rotating seat and arm must occur exactly once.')
    rows = {(row['seed'], row['controlledSeat'], row['arm']): terminal_metrics(row) for row in records}
    deltas = [[rows[seed, index % 4, 'candidate'][metric] - rows[seed, index % 4, arm][metric]
               for arm in ARMS[:3] for metric in METRICS] for index, seed in enumerate(seeds)]
    uncertainty = intervals(deltas)
    versus = {}
    for arm_index, arm in enumerate(ARMS[:3]):
        sections = [{metric: population[arm_index * len(METRICS) + metric_index]
                     for metric_index, metric in enumerate(METRICS)} for population in uncertainty]
        versus[arm] = dict(pooled=sections[0], populations=dict(zip(POPULATIONS, sections[1:])))
    pooled = {arm: versus[arm]['pooled']['winShare'] for arm in ARMS[:3]}
    report = dict(kind='card-trajectory-analysis', schemaVersion=1, stage=stage,
                  independentWorlds=len(seeds), executions=len(records), versus=versus,
                  bootstrap=dict(BOOTSTRAP, unit='paired whole world', method='stratified percentile-linear',
                                 population='(worldIndex//4)%2; equal composition'),
                  humanStrengthClaim=False, policyPromotion=False,
                  limits=['Development comparisons reuse controls and select among four checkpoints; '
                          'their intervals are not independent confirmation or selection-adjusted.',
                          'Rank and composite reward are descriptive; every playing-strength gate uses win share.',
                          'Both previously failed card payoff-reversal gates remain unresolved.'])
    if stage == 'development':
        report['eligible'] = (pooled['f']['mean'] >= DEVELOPMENT['minimumGainVsF']
                              and pooled['initial']['mean'] > 0 and pooled['priority64']['mean'] > 0)
        report['harmful'] = pooled['f']['mean'] < -.10 and pooled['f']['upper95'] < 0
    else:
        populations = [versus['f']['populations'][name]['winShare'] for name in POPULATIONS]
        gates = dict(minimumGainVsF=pooled['f']['mean'] >= .05, lower95VsF=pooled['f']['lower95'] > 0,
                     lower95VsInitial=pooled['initial']['lower95'] > 0,
                     lower95VsPriority=pooled['priority64']['lower95'] > 0,
                     nonnegativePopulationMeans=all(value['mean'] >= 0 for value in populations),
                     boundedPopulationLoss=all(value['lower95'] > -.03 for value in populations))
        report.update(gates=gates, passed=all(gates.values()))
    return report


def progress(updates):
    """Use the last accepted epoch, never a rejected/rolled-back attempt.

    The caller binds each report/checkpoint/model in the unchanged PPO lineage,
    including full parsed-model equality for updates with no accepted epoch.
    Such a carry may reserialize the same model into different JSON bytes.
    priorKl is mean KL(fixed .97 HARD prior || current actor) on that update's
    visited decisions. Its threshold measures movement, not conditional quality.
    """
    require(1 <= len(updates) <= 32, 'Progress requires an ordered completed update prefix.')
    total, latest = 0, None
    previous = None
    for index, report in enumerate(updates, 1):
        accepted = [row for row in report['history'] if row['accepted'] is True]
        require(type(report['acceptedEpochs']) is int and report['acceptedEpochs'] == len(accepted),
                'Accepted epoch counts must match the completed optimizer history.')
        require(previous is None or report['inputModelSha256'] == previous,
                'Completed updates must preserve exact preceding policy lineage.')
        for row in accepted:
            require(type(row['priorKl']) in (int, float) and math.isfinite(row['priorKl'])
                    and row['priorKl'] >= -1e-12, 'Accepted prior KL must be finite and nonnegative.')
            latest = dict(update=index, epoch=row['epoch'], priorKl=row['priorKl'])
        total += len(accepted)
        previous = report['outputModelSha256']
    return dict(completedUpdates=len(updates), totalAcceptedEpochs=total, latestAccepted=latest,
                latestAcceptedPriorKl=None if latest is None else latest['priorKl'],
                noProgress=total == 0 or latest['priorKl'] < DEVELOPMENT['minimumLatestAcceptedPriorKl'],
                diagnosticOnly=True, strengthClaim=False)


def select(reports):
    """Eligible highest F-relative mean; exact ties choose the earlier milestone."""
    require(all(type(batch) is int and batch in MILESTONES for batch in reports),
            'Only the four predeclared completed checkpoints may be selected.')
    require(not any(report['harmful'] for report in reports.values()),
            'Harm stops the entire experiment; an earlier actor may not be rescued.')
    eligible = [batch for batch, report in reports.items() if report['eligible']]
    return min(eligible, key=lambda batch: (-reports[batch]['versus']['f']['pooled']['winShare']['mean'], batch)) if eligible else None


def bind_completed(directory, source, expected):
    """Metadata/hash validation only. Call for ALL banks before decoding ANY game."""
    directory, source = Path(directory).resolve(strict=True), Path(source).resolve(strict=True)
    require(read(source) == expected, 'Trajectory evaluation source differs from its fixed stage.')
    require(completed_native(directory, source), 'The entire declared trajectory stage must complete first.')
    manifest, finish = read(directory / 'collection.json'), read(directory / 'finished.json')
    require(manifest['kind'] == finish['kind'] == 'card-advantage-evaluate'
            and manifest['schemaVersion'] == 1 and manifest['cardActorVersion'] == 'aor-card-policy-1'
            and manifest['trajectoryEncoding'] == 'gzip-json-v1'
            and manifest['source'] == str(source), 'Completed card actor runtime/wire identity differs.')
    entries = finish['files']
    require([row['index'] for row in entries] == list(range(len(expected['jobs'])))
            and all(row['file'] == f'game-{index}.json.gz' for index, row in enumerate(entries)),
            'Completion must enumerate all exact game artifacts in order.')
    require(all(sha(directory / row['file']) == row['sha256'] for row in entries),
            'Every completed compressed game must match before any terminal labels are read.')
    return dict(directory=directory, source=source, expected=expected, signature=manifest['signature'],
                entries=entries, metadataHashes={name: sha(directory / name)
                    for name in ('collection.json', 'finished.json', 'state.json')})


def decode(bound):
    import json
    result = []
    for index, (entry, job) in enumerate(zip(bound['entries'], bound['expected']['jobs'])):
        with gzip.open(bound['directory'] / entry['file'], 'rb') as stream:
            blob = stream.read(4 * 1024 * 1024 + 1)
        require(len(blob) <= 4 * 1024 * 1024, 'Expanded terminal record exceeds its size limit.')
        row = json.loads(blob)
        require(type(row['schemaVersion']) is int and row['schemaVersion'] == 1
                and row['signature'] == bound['signature'] and type(row['index']) is int
                and row['index'] == index and row['seed'] == job['seed']
                and row['controlledSeat'] == job['seat'] and row['arm'] == job['arm'],
                'Terminal record differs from its prospective evaluation job.')
        result.append(row)
    return result


def finish_analysis(stage, seeds, bounds):
    # Every source completion was checked by the caller before reaching decode().
    records = [row for bound in bounds for row in decode(bound)]
    report = analyze(stage, seeds, records)
    for bound in bounds:
        require(all(sha(bound['directory'] / name) == value for name, value in bound['metadataHashes'].items())
                and all(sha(bound['directory'] / row['file']) == row['sha256'] for row in bound['entries'])
                and completed_native(bound['directory'], bound['source']),
                'Completed trajectory evaluation changed during analysis.')
    report['inputs'] = dict(analysisSha256=sha(__file__), sources=[dict(
        directory=str(bound['directory']), source=str(bound['source']), sourceSha256=sha(bound['source']),
        completionSha256=sha(bound['directory'] / 'finished.json'), signature=bound['signature']) for bound in bounds])
    return report


def analyze_development_completed(seeds, controls_dir, controls_source, candidate_dir, candidate_source,
                                  base, initial, model, priority):
    controls = make_source('controls', seeds, {'initial': initial}, base, priority)
    candidate = make_source('development', seeds, {'candidate': model}, base, priority)
    bounds = [bind_completed(controls_dir, controls_source, controls),
              bind_completed(candidate_dir, candidate_source, candidate)]
    return finish_analysis('development', seeds, bounds)


def analyze_completed(stage, seeds, native_dir, source_path, base, initial, model, priority):
    require(stage == 'confirmation', 'Standalone final analysis requires the four-arm fresh bank.')
    expected = make_source(stage, seeds, {'initial': initial, 'candidate': model}, base, priority)
    return finish_analysis(stage, seeds, [bind_completed(native_dir, source_path, expected)])
