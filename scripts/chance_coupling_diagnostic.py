"""Metadata-only root selection and completed two-mode chance diagnostic analysis; no fitting."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

KIND = 'logical-chance-coupling-diagnostic-1'
MODES = ('LEGACY', 'LOGICAL_REQUEST_1')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def select_roots(metadata):
    """Balance only collected training roots with an existing distinct proposal; never use labels."""
    require(len(metadata) == 512 and {r['index'] for r in metadata} == set(range(512)),
            'Select only the complete original512 training request metadata.')
    require(all(r['split'] == 'TRAINING' and r['requestedEpoch'] in (1, 2, 3)
                and r['seat'] in range(4) for r in metadata), 'Invalid prospective request metadata.')
    epochs, seats, buckets = Counter(), Counter(), Counter()
    require(all(r['status'] in ('COLLECTED', 'SKIPPED') and type(r['eligiblePair']) is bool
                for r in metadata), 'Root eligibility uses completed status and offered commands only.')
    remaining = {r['index']:r for r in metadata if r['status'] == 'COLLECTED' and r['eligiblePair']}
    require(len(remaining) >= 32, 'At least32 retained eligible training roots are required; no new roots.')
    chosen = []
    for _ in range(32):
        row = min(remaining.values(), key=lambda r:(epochs[r['requestedEpoch']], seats[r['seat']],
            buckets[r['requestedEpoch'], r['seat']], r['index']))
        chosen.append(row['index'])
        epochs[row['requestedEpoch']] += 1
        seats[row['seat']] += 1
        buckets[row['requestedEpoch'], row['seat']] += 1
        del remaining[row['index']]
    return chosen


def neighbor(origin):
    observation = origin['observation']
    baseline = observation['baselineIndex']
    commands = [a['command'] for a in observation['actions']]
    require(all(c.startswith('buy-tokens ') for c in commands), 'Token actions require canonical amounts.')
    amounts = [int(c.removeprefix('buy-tokens ')) for c in commands]
    choices = [i for i in origin['proposedIndices'] if i != baseline]
    require(choices, 'A collected root requires a distinct existing proposal.')
    return min(choices, key=lambda i:(abs(amounts[i]-amounts[baseline]), commands[i]))


def analyze(source_path, output, bootstrap_seed):
    from card_policy_coupling_analysis import chance_cost_statistics
    source, finished = read(source_path), read(output/'finished.json')
    require(source['kind'] == finished['kind'] == KIND and finished['sourceSha256'] == sha(source_path),
            'Completed chance diagnostic source changed.')
    require(set(finished['originSha256']) == {f'origin-{r["index"]}.json' for r in source['roots']},
            'The completed diagnostic must retain all32 requested roots, all eligible and collected.')
    worlds, skipped, matches = [], [], []
    for declared in source['roots']:
        name = f'origin-{declared["index"]}.json'
        require(sha(output/name) == finished['originSha256'][name], 'A completed origin changed.')
        root = read(output/name)
        require(root['index'] == declared['index'] and root['originSha256'] == declared['sha256'],
                'Diagnostic root identity changed.')
        require(root['status'] == 'COLLECTED', 'Every frozen eligible root must complete; no substitutions.')
        reference, alternative = declared['reference'], declared['alternative']
        rows = {(r['mode'],r['action'],r['replicate']):r for r in root['tails']}
        require(len(rows) == len(root['tails']) == 64 and set(rows) == {
            (m,a,i) for m in MODES for a in (reference,alternative) for i in range(16)},
            'Each collected root requires exactly64 uniquely identified tails.')
        world = {}
        for mode in MODES:
            cells = [[rows[mode,a,i] for i in range(16)] for a in (reference,alternative)]
            for cell in cells:
                require(all(r['seed'] == declared['seeds'][i] and r['replayVerified']
                            and r['cpuNanos'] > 0 and r['wallNanos'] > 0
                            and r['outcome']['continuationSeed'] == declared['seeds'][i]
                            for i,r in enumerate(cell)), 'Paired streams or canonical replay changed.')
            world[mode] = dict(reference=[r['outcome']['reward'] for r in cells[0]],
                alternative=[r['outcome']['reward'] for r in cells[1]],
                cpuSeconds=[(a['cpuNanos']+b['cpuNanos'])/1e9 for a,b in zip(*cells)])
            for i,(a,b) in enumerate(zip(*cells)):
                keys = [{(x['requestSha256'],x['occurrence']) for x in r['chanceTrace']} for r in (a,b)]
                require(all(len(k)==len(r['chanceTrace']) for k,r in zip(keys,(a,b))),
                        'A chance address was queried more than once in one arm.')
                matches.append(dict(origin=root['index'],mode=mode,replicate=i,
                    referenceRequests=len(keys[0]),alternativeRequests=len(keys[1]),
                    matchedRequests=len(keys[0]&keys[1])))
        worlds.append(world)
    report = chance_cost_statistics(worlds, bootstrap_seed) if worlds else dict(
        worlds=0, primary=dict(gatePassed=False, ratio=None, lower95=None, upper95=None))
    return dict(kind=KIND, sourceSha256=sha(source_path), completedRoots=len(worlds),
        skippedRoots=skipped, requestedRoots=32, tails=len(worlds)*64,
        statistics=report, requestMatching=matches, newAnalysisGames=0, actualFits=0,
        qualificationPassed=False, productionActivation=False,
        limitation='Authority chance only; global-index policy seeds remain unchanged. No held-out strength claim.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--bootstrap-seed', type=int, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'Preserve previous chance diagnostic analysis.')
    result = analyze(args.source, args.run, args.bootstrap_seed)
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2)+'\n')
