"""Predeclared complete-game gates for the bounded card-advantage experiment.

The world is the independent unit. Four-role robustness games are averaged within
world before uncertainty is calculated. Training/local counterfactual validation
is deliberately outside this module; only complete frozen-policy games enter it.
"""
import argparse
from collections import defaultdict
import gzip
import hashlib
import json
import math
from pathlib import Path
import statistics


STAGES = {
    'p1-screen': (128, ('p1', 'baseline'), False),
    'common-development': (256, ('p1', 'p2', 'baseline'), False),
    'primary-confirmation': (1024, ('candidate', 'baseline', 'frequency'), False),
    'rival-robustness': (256, ('candidate', 'baseline'), True),
}
METRICS = ('winShare', 'rankReward', 'reward')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def interval(values):
    """Two-sided normal approximation on independent, equally weighted worlds."""
    values = list(values)
    require(len(values) >= 2 and all(math.isfinite(value) for value in values),
            'An interval needs at least two finite independent world values.')
    mean = statistics.mean(values)
    error = 1.96 * statistics.stdev(values) / math.sqrt(len(values))
    return dict(independentWorlds=len(values), mean=mean,
                lower95=mean - error, upper95=mean + error)


def paired(records, left, right, metric):
    """Pair identical world/role games, then average role differences per world."""
    worlds = defaultdict(list)
    by_key = {(row['seed'], row['controlledSeat'], row['arm']): row for row in records}
    for (seed, seat, arm), row in by_key.items():
        if arm == left:
            other = by_key[(seed, seat, right)]
            worlds[seed].append(row['terminal'][metric] - other['terminal'][metric])
    result = interval(statistics.mean(values) for values in worlds.values())
    result['pairedRoles'] = sum(map(len, worlds.values()))
    return result


def validate_records(stage, seeds, records):
    require(stage in STAGES, 'Unknown predeclared evaluation stage.')
    worlds, arms, all_roles = STAGES[stage]
    require(len(seeds) == worlds and len(set(seeds)) == worlds
            and all(type(seed) is int for seed in seeds),
            'The stage requires its complete distinct integer world bank.')
    expected = {(seed, seat, arm)
                for index, seed in enumerate(seeds)
                for seat in (range(4) if all_roles else (index % 4,))
                for arm in arms}
    actual = [(row['seed'], row['controlledSeat'], row['arm']) for row in records]
    require(len(actual) == len(expected) and set(actual) == expected,
            'Evaluation must contain every planned world/role/arm exactly once.')
    for row in records:
        terminal = row['terminal']
        require(all(type(terminal[key]) in (int, float)
                    and math.isfinite(terminal[key]) and 0 <= terminal[key] <= 1
                    for key in METRICS), 'Terminal metrics must be finite and in [0,1].')
        require(abs(terminal['reward'] - .8 * terminal['winShare']
                    - .2 * terminal['rankReward']) < 1e-12,
                'Terminal composite reward must preserve the frozen reward definition.')
    return arms


def analyze(stage, seeds, records):
    arms = validate_records(stage, seeds, records)
    comparisons = {}
    for left, right in [(arms[0], arm) for arm in arms[1:]]:
        comparisons[left + '-minus-' + right] = {
            metric: paired(records, left, right, metric) for metric in METRICS}
    report = dict(
        kind='card-advantage-full-game-analysis', schemaVersion=1, stage=stage,
        independentWorlds=len(seeds), executions=len(records), comparisons=comparisons,
        meanWinShares={arm: statistics.mean(row['terminal']['winShare']
                      for row in records if row['arm'] == arm) for arm in arms},
        uncertainty='Mean +/- 1.96 sample SD of paired world means / sqrt(world count).',
        humanStrengthClaim=False)
    if stage == 'p1-screen':
        value = comparisons['p1-minus-baseline']['winShare']
        report['harmfulTransfer'] = value['mean'] < -.05 and value['upper95'] < 0
        report['mayContinue'] = not report['harmfulTransfer']
        report['qualification'] = False
    elif stage == 'common-development':
        value = paired(records, 'p2', 'p1', 'winShare')
        report['p2MinusP1'] = value
        report['selected'] = 'p2' if value['mean'] >= .01 else 'p1'
        report['qualification'] = False
    elif stage == 'primary-confirmation':
        incumbent = comparisons['candidate-minus-baseline']['winShare']
        frequency = comparisons['candidate-minus-frequency']['winShare']
        report['gates'] = dict(
            incumbent=incumbent['mean'] >= .05 and incumbent['lower95'] > 0,
            frequencyControl=frequency['lower95'] > 0)
        report['passed'] = all(report['gates'].values())
    else:
        value = comparisons['candidate-minus-baseline']['winShare']
        report['gates'] = dict(nonnegativeMean=value['mean'] >= 0,
                               boundedLoss=value['lower95'] > -.03)
        report['passed'] = all(report['gates'].values())
    return report


def load_completed(directory):
    """Open game outcomes only after all declared completion hashes validate."""
    directory = Path(directory)
    manifest_path = directory / 'finished.json'
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    require(manifest['kind'] == 'card-advantage-evaluate',
            'A complete card-advantage evaluation manifest is required.')
    entries = manifest['files']
    require(manifest['games'] == len(entries)
            and [entry['index'] for entry in entries] == list(range(len(entries))),
            'Completion manifest must list every game once in index order.')
    blobs = []
    for entry in entries:
        name = entry['file']
        require(name == 'game-' + str(entry['index']) + '.json.gz',
                'Game artifact names must match their completion indices.')
        blob = (directory / name).read_bytes()
        require(hashlib.sha256(blob).hexdigest() == entry['sha256'],
                'Completed evaluation game hash differs: ' + name)
        blobs.append(blob)
    # No terminal data is decoded before the whole completion set has been verified.
    records = [json.loads(gzip.decompress(blob)) for blob in blobs]
    require(all(row['schemaVersion'] == 1 and row['index'] == index
                and row['signature'] == manifest['signature']
                for index, row in enumerate(records)),
            'Completed game schema, index or frozen runtime/input identity differs.')
    require(manifest_path.read_bytes() == manifest_bytes,
            'Completion manifest changed during analysis.')
    return records, dict(manifestSha256=hashlib.sha256(manifest_bytes).hexdigest(),
                         signature=manifest['signature'], files=entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', choices=STAGES, required=True)
    parser.add_argument('--seeds', type=Path, required=True,
                        help='Frozen seed-reservations.json with named banks.')
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'Preserve previous analyses; use a fresh output path.')
    reservation = json.loads(args.seeds.read_bytes())
    require(reservation['passed'] is True and reservation['overlaps'] == [],
            'Fresh-world reservations must have passed their overlap audit.')
    banks = [bank for bank in reservation['banks'] if bank['name'] == args.stage]
    require(len(banks) == 1, 'The requested stage requires one exact reserved world bank.')
    records, provenance = load_completed(args.source)
    report = analyze(args.stage, banks[0]['seeds'], records)
    report['provenance'] = provenance
    report['analysisSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    report['seedReservationSha256'] = hashlib.sha256(args.seeds.read_bytes()).hexdigest()
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2, allow_nan=False)
        stream.write('\n')
    print(json.dumps({key: report[key] for key in
                      ('stage', 'executions', 'passed', 'mayContinue', 'selected') if key in report}))


if __name__ == '__main__':
    main()
