"""Fixed leader/target coordination: independent origin clusters, no outcome-selected options."""
import argparse
import gzip
import hashlib
import json
import math
from collections import Counter
from pathlib import Path

from analyze_recorded_prefix import moments


def hash_manifest(directory, name, expected):
    records = [json.loads(line) for line in (directory / name).read_text().splitlines()]
    hashes = {row['file']: row['sha256'] for row in records}
    if len(records) != len(expected) or set(hashes) != expected:
        raise ValueError('Completed manifest does not cover exactly the frozen bank')
    for filename, expected_hash in hashes.items():
        if hashlib.sha256((directory / filename).read_bytes()).hexdigest() != expected_hash:
            raise ValueError('A completed artifact changed')
    return hashes


def origin_interval(values):
    if len(values) < 16:
        raise ValueError('The fixed coverage floor requires at least16 independent origins')
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / (len(values) - 1)
    # Conservative t(15) critical value for the fixed16..32 origin count, never tail pseudoreplicates.
    width = 2.131449545559323 * math.sqrt(variance / len(values))
    return {'mean': mean, 'origins': len(values), 'acrossOriginApprox95': [mean - width, mean + width]}


def analyze_root(arms, root):
    if len(arms) != 2 or any(len(rows) != 16 for rows in arms):
        raise ValueError('Each fixed policy requires exactly16 evaluation tails')
    for action, rows in enumerate(arms):
        if [row['replicate'] for row in rows] != list(range(16)):
            raise ValueError('Paired replicate ordering changed')
        if any(row['action'] != action or row['split'] != 'evaluation' or
               row['command'] != root['command'] or row['target'] != root['target'] for row in rows):
            raise ValueError('Fixed policy/root/target identity changed')
    baseline, option = arms
    evaluation = {metric: moments([b['terminal'][metric] - a['terminal'][metric] for a, b in zip(baseline, option)])
                  for metric in ('reward', 'winShare', 'rankReward')}
    ended = Counter(row['coordination']['ended'] for row in option)
    interventions = [sum(d['ordinaryCommand'] != d['command'] for d in row['coordination']['decisions'])
                     for row in option]
    def economics(rows):
        return [{'ownLeaderSaving': sum(p['ownNewLeaderEffectiveSaving'] for p in row['economics']['rootTurnPurchases']),
                 'boundaryCash': row['economics']['nextBoundary']['cash'],
                 'boundaryMarkers': row['economics']['nextBoundary']['markers']}
                for row in rows]
    left, right = economics(baseline), economics(option)
    return {'world': root['world'], 'rootIndex': root['rootIndex'], 'target': root['target'],
            'evaluation': evaluation, 'optionEndReasons': dict(ended),
            'intervenedTails': sum(count > 0 for count in interventions),
            'shippingReservations': sum(d['phase'] == 'purchase-shipping' and d['ordinaryCommand'] != d['command']
                                        for row in option for d in row['coordination']['decisions']),
            'economicDeltas': {name: moments([b[name] - a[name] for a, b in zip(left, right)]) for name in left[0]}}


def analyze(directory):
    prepared = json.loads((directory / 'prepared.json').read_text())
    if prepared['kind'] != 'leader-purchase-coordination-1' or prepared['selectionPerformed'] is not False:
        raise ValueError('Expected frozen coordination protocol')
    hash_manifest(directory, 'origins-completed.jsonl', {f'origin-{world}.json' for world in range(128)})
    origins = [json.loads((directory / f'origin-{world}.json').read_text()) for world in range(128)]
    for index, origin in enumerate(origins):
        if origin['world'] != index or origin['seat'] != index % 4 or origin['originChance'] != prepared['originChance'][index] or origin['originPolicy'] != prepared['originPolicy'][index] or not origin['replayVerified']:
            raise ValueError('Origin provenance differs from the frozen declaration')
    expected_roots = [origin for origin in origins if origin['status'] == 'eligible'][:32]
    frozen = json.loads((directory / 'roots.json').read_text())
    roots = frozen['roots']
    if [root['world'] for root in roots] != [origin['world'] for origin in expected_roots]:
        raise ValueError('Root retention must follow world index, never completion order')
    for index, (root, origin) in enumerate(zip(roots, expected_roots)):
        if root['rootIndex'] != index or {k: v for k, v in root.items() if k != 'rootIndex'} != {k: v for k, v in origin.items() if k != 'status'}:
            raise ValueError('Frozen root changed from its collected origin')
    coverage = {'scannedWorlds': 128, 'eligibleWorlds': sum(origin['status'] == 'eligible' for origin in origins),
                'retainedRoots': len(roots), 'leaderVisits': sum(origin['leaderVisits'] for origin in origins)}
    if len(roots) < 16:
        stop = json.loads((directory / 'coverage-stop.json').read_text())
        if stop['tails'] != 0 or (directory / 'complete.json').exists() or list(directory.glob('root-*-replicate-*')):
            raise ValueError('Coverage failure must not execute tails')
        return {'kind': 'leader-coordination-coverage-stop', 'coverage': coverage, 'selectionPerformed': False}
    complete = json.loads((directory / 'complete.json').read_text())
    if complete['tails'] != len(roots) * 32 or complete['roots'] != len(roots) or complete['replicates'] != len(roots) * 16 or not complete['allReplaysVerified']:
        raise ValueError('The full fixed tail bank must complete')
    hash_manifest(directory, 'completed.jsonl', {f'root-{index}-replicate-{replicate}.json.gz' for index in range(len(roots)) for replicate in range(16)})
    findings = []
    for index, root in enumerate(roots):
        arms = [[], []]
        for replicate in range(16):
            with gzip.open(directory / f'root-{index}-replicate-{replicate}.json.gz', 'rt') as stream:
                tails = json.load(stream)
            if len(tails) != 2:
                raise ValueError('Every paired stream retains both policies')
            stream_index = index * 16 + replicate
            for action, tail in enumerate(tails):
                if tail['world'] != root['world'] or tail['rootIndex'] != index or tail['actor'] != root['actor'] or tail['policySeed'] != prepared['tailPolicy'][stream_index] or tail['terminal']['continuationSeed'] != prepared['tailChance'][stream_index] or not tail['replayVerified']:
                    raise ValueError('Tail identity/streams differ from the fixed bank')
                arms[action].append(tail)
        findings.append(analyze_root(arms, root))
    return {'kind': 'fixed-coordination-conditional-origin-diagnostic', 'coverage': coverage, 'roots': findings,
            'selectionPerformed': False, 'tails': len(roots) * 32,
            'evaluation': {metric: origin_interval([row['evaluation'][metric]['mean'] for row in findings])
                           for metric in ('reward', 'winShare', 'rankReward')},
            'limitations': ['Population is conditional on the first eligible baseline-selected leader for a rotating seat in a fresh unchanged-policy world.',
                           'One target was fixed from public quotes before tails; no target or arm is selected from results.',
                           'Origin averages, not the16 correlated tails, are the units for the across-origin approximate interval.',
                           'Current-turn coordination can sacrifice valuable shipping or another advance; positive discount use is not a strength claim.',
                           'Global decision-index randomness may diverge between the two policies.',
                           'No fitting, qualification or activation.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with args.output.open('x') as stream:
        json.dump(analyze(args.run), stream, indent=2, sort_keys=True)
        stream.write('\n')
