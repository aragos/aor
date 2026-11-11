"""Completed consumed-world policy-coupling diagnostic; no fitting or policy promotion.

Each root is the statistical unit. Eight future streams remain nested within its fixed hidden
hands/deck. Primary S/G compares two newly salted sampling schedules; legacy L is descriptive.
Changing a seed schedule does not claim historical per-world policy equivalence.
"""
from __future__ import annotations

import argparse
import hashlib
import math
import os
from pathlib import Path
import signal
import time
for _name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'VECLIB_MAXIMUM_THREADS'):
    os.environ[_name] = '1'
import numpy as np

import card_advantage as advantage
import card_continuation_analysis as previous
import card_policy
import purchase_policy as common

KIND = 'card-policy-coupling'
PLAN = {'kind': 'policy-seed-coupling-1', 'replicates': 8, 'schedules': ['L', 'G', 'S']}
ARMS = ('l-f', 'l-a', 'g-f', 'g-a', 's-f', 's-a')
SCHEDULES = ('L', 'G', 'S')
TRACE_FIELDS = {'number', 'batchStart', 'turn', 'stage', 'decision', 'seat', 'occurrence', 'seed', 'forced'}
BOOTSTRAPS = 10000
BOOTSTRAP_SEED = 381107
MAX_SECONDS = 600
require = common.require


def read(path): return common.read_json(Path(path))
def sha(path): return common.file_digest(Path(path))


def immutable(path, value):
    if path.exists(): require(read(path) == value, 'Immutable coupling evidence differs: '+str(path))
    else: common.atomic_json(path, value)


def signed32(value):
    return (value+2**31) % 2**32-2**31


def chance_seed(world_seed, replicate):
    return (world_seed << 4) ^ 0x37a51b20d ^ replicate


def policy_seed(schedule, world_seed, replicate, row):
    """Independent byte-level reproduction; forced root consumes its occurrence, not this draw."""
    if schedule == 'L': return signed32(world_seed ^ signed32(row['batchStart']*65537+row['seat']*8191))
    master = hashlib.sha256(f'aor-policy-coupling-master-1\n{world_seed}\n{replicate}\n'.encode()).digest()
    if schedule == 'G': key = f"G\n{row['batchStart']}\n{row['seat']}\n"
    else:
        require(schedule == 'S', 'Unknown policy sampling schedule.')
        key = f"S\n{row['turn']}\n{row['stage']}\n{row['decision']}\n{row['seat']}\n{row['occurrence']}\n"
    raw = hashlib.sha256(master+b'aor-policy-coupling-seed-1\n'+key.encode()).digest()
    return int.from_bytes(raw[:4], 'big', signed=True)


def verify_trace(rows, root, outcome, schedule, world_seed, replicate, seat):
    require(isinstance(rows, list) and len(rows) == outcome['gameDecisions']-root['number'] and rows,
            'Every post-root policy decision requires one trace row.')
    counters = {}; keys = {}; last_batch = root['number']
    for offset, row in enumerate(rows):
        require(set(row) == TRACE_FIELDS and type(row['forced']) is bool, 'Policy trace fields differ.')
        for field, low, high in (('number', 0, 2**31-1), ('batchStart', 0, 2**31-1),
                                  ('turn', 1, 2**31-1), ('seat', 0, 3), ('occurrence', 0, 2**31-1),
                                  ('seed', -2**31, 2**31-1)):
            common.integer(row[field], 'Policy trace '+field, low, high)
        require(type(row['stage']) is str and row['stage'] and type(row['decision']) is str and row['decision'],
                'Semantic policy keys require explicit stage and decision strings.')
        require(row['number'] == root['number']+offset and last_batch <= row['batchStart'] <= row['number'],
                'Trace numbers or sealed-batch boundaries are inconsistent.')
        require(row['number']-row['batchStart'] <= 3, 'A sealed policy batch cannot exceed four actors.')
        if offset == 0:
            require(row['batchStart'] == root['number'], 'The forced root starts its own policy batch.')
        elif row['batchStart'] != row['number']:
            prior = rows[offset-1]
            require(row['batchStart'] == prior['batchStart'] and all(row[key] == prior[key]
                    for key in ('turn', 'stage', 'decision')) and not any(previous_row['seat'] == row['seat']
                    for previous_row in rows[offset-(row['number']-row['batchStart']):offset]),
                    'A sealed policy batch must retain its pre-state context and distinct actors.')
        last_batch = row['batchStart']
        bucket = (row['turn'], row['stage'], row['decision'], row['seat'])
        occurrence = counters.get(bucket, 0)
        require(row['occurrence'] == occurrence, 'Semantic phase counters must be unique and consecutive.')
        counters[bucket] = occurrence+1
        if offset == 0:
            require(row['forced'] and row['seat'] == seat and row['turn'] == root['turn']
                    and row['stage'] == 'PLAY_CARDS' and row['decision'] == 'play-cards'
                    and row['seed'] == root['botSeed'], 'The forced root must retain its actual legacy seed.')
        else:
            require(not row['forced'], 'Only the initial root action can be forced.')
            require(row['seed'] == policy_seed(schedule, world_seed, replicate, row),
                    'A recorded policy seed differs from its versioned schedule.')
            key = (*bucket, occurrence)
            require(key not in keys, 'A semantic policy bucket was reused.')
            keys[key] = row['seed']
    return keys


class Completed:
    """Bind all new/reference compressed bytes before decoding either set of outcomes."""
    def __init__(self, source, reference):
        self.source = Path(source).resolve(strict=True)
        self.reference = previous.CompletedCollection(reference)
        self.manifest = read(self.source/'collection.json'); self.finished = read(self.source/'finished.json')
        state = read(self.source/'state.json'); m = self.manifest
        require(m['kind'] == self.finished['kind'] == KIND and type(m['schemaVersion']) is int and m['schemaVersion'] == 1,
                'The policy-coupling collection kind/version differs.')
        require(state['phase'] == 'complete' and state['signature'] == m['signature'] == self.finished['signature']
                and self.finished['origins'] == len(m['origins']) == len(self.finished['originSha256']) == 64,
                'All 64 predeclared consumed roots must finish before analysis.')
        require(m['collection'] == PLAN and m['replicates'] == 8 and m['maxTailsPerOrigin'] == 48
                and m['schedules'] == list(SCHEDULES) and m['arms'] == list(ARMS)
                and m['policySeedVersion'] == PLAN['kind']
                and m['backbonePolicy'] == m['tailPolicy'] == m['opponentPolicy'] == 'F'
                and m.get('continuation') is None, 'The fixed F/purchase32 coupling experiment differs.')
        require(m['origins'] == self.reference.requests[:64], 'Consumed roots must be exactly reference requests 0..63.')
        for key in ('encoderVersion', 'selectorVersion', 'proposalVersion', 'stateFeatureNames', 'actionFeatureNames',
                    'configuration', 'rulesVersion', 'dataVersion', 'trajectoryEncoding'):
            require(m[key] == self.reference.manifest[key], 'Reference public schema/configuration differs: '+key)
        self.pins = {name: sha(self.source/name) for name in ('collection.json', 'finished.json', 'source.json')}
        declared = read(self.source/'source.json')
        require(self.pins['source.json'] == m['sourceSha256'] and declared['schemaVersion'] == 1
                and declared['kind'] == KIND and declared['collection'] == PLAN and declared['origins'] == m['origins'],
                'The frozen coupling source differs from its manifest.')
        for key, filename in (('baseline', 'baseline.json'), ('frozenPurchasePolicy', 'frozen-purchase-policy.json')):
            require(sha(self.source/filename) == m[key]['sha256'] == self.reference.manifest[key]['sha256'],
                    'Playing policy bytes differ from the consumed reference: '+key)
            self.pins[filename] = m[key]['sha256']
        for index, expected in enumerate(self.finished['originSha256']):
            name = f'origin-{index}.json.gz'
            require(sha(self.source/name) == expected, 'Completed coupling origin bytes changed: '+name)
            self.pins[name] = expected

    def verify(self):
        require(all(sha(self.source/name) == expected for name, expected in self.pins.items()),
                'Coupling inputs changed during analysis.')
        self.reference.verify()

    def origin(self, request):
        index = request['index']
        row = common.read_game(self.source/f'origin-{index}.json.gz', card_policy.CONTRACT)
        old = common.read_game(self.reference.directory/f'origin-{index}.json.gz', card_policy.CONTRACT)
        # Reuse the prior strict root/arm/acquisition validator, not only raw identity comparison.
        self.reference.read_origin(request)
        require(row['kind'] == KIND and type(row['schemaVersion']) is int and row['schemaVersion'] == 1
                and row['signature'] == self.manifest['signature'] and row['request'] == request
                and all(row[key] == request[key] for key in ('index', 'split', 'seed', 'seat')),
                'Coupling origin provenance differs.')
        for key in ('request', 'status', 'skipReason', 'backboneDecisions', 'backboneCanonicalSha256'):
            require(row[key] == old[key], 'The consumed root backbone changed: '+key)
        if row['status'] == 'skipped':
            require(row['root'] is None and not row['outcomes'] and not row['policyTraces'],
                    'A skipped consumed root cannot have outcomes or traces.')
            return None, {'index': index, 'status': 'skipped'}
        root = row['root']; old_root = old['root']
        require({key: value for key, value in root.items() if key != 'arms'}
                == {key: value for key, value in old_root.items() if key != 'arms'},
                'Root observation, proposals, sampled A or legacy seed changed.')
        require([arm['id'] for arm in root['arms']] == list(ARMS), 'Coupling arms require their canonical order.')
        require(set(row['policyTraces']) == set(row['outcomes']), 'Every distinct tail requires exactly one policy trace.')
        values = {}; used = set(); semantic = {}; matched = 0; unmatched = 0; legacy = 0
        for position, arm in enumerate(root['arms']):
            schedule = SCHEDULES[position//2]; alternative = position % 2 == 1
            trivial = root['alternativeIndex'] is None
            action = root['alternativeIndex'] if alternative and not trivial else root['referenceIndex']
            require(arm['schedule'] == schedule and arm['actionIndex'] == action, 'An arm changed its action or sampling schedule.')
            identifier = ARMS[position-1] if alternative and trivial else arm['id']
            ids = [f'arm-{identifier}-{replicate}' for replicate in range(8)]
            require(arm['outcomeIds'] == ids, 'Coupling outcome identities/explicit trivial aliases differ.')
            values[arm['id']] = []
            for replicate, oid in enumerate(ids):
                used.add(oid); outcome = row['outcomes'][oid]
                require(outcome['chanceSeed'] == row['outcomes'][f'arm-l-f-{replicate}']['chanceSeed'],
                        'Authority future randomness differs across coupled arms.')
                require(type(outcome['chanceSeed']) is int
                        and outcome['chanceSeed'] == chance_seed(request['seed'], replicate),
                        'Authority future seed differs from the fixed world/replicate formula.')
                for name in ('reward', 'winShare', 'rankReward'): common.number(outcome[name], name, 0, 1)
                require(abs(outcome['reward']-.8*outcome['winShare']-.2*outcome['rankReward']) < 1e-12,
                        'The terminal composite reward changed.')
                common.integer(outcome['gameDecisions'], 'Terminal game decision count', root['number']+1, 2**31-1)
                keys = verify_trace(row['policyTraces'][oid], root, outcome, schedule, request['seed'], replicate, request['seat'])
                if schedule == 'S': semantic[(alternative, replicate)] = keys
                if schedule == 'L' and replicate < 4:
                    old_arm = old_root['arms'][1 if alternative else 0]
                    require(outcome == old['outcomes'][old_arm['outcomeIds'][replicate]],
                            'Legacy replicas 0..3 must exactly reproduce stored F/A terminal outcomes.')
                    legacy += 1
                values[arm['id']].append(outcome['reward'])
        require(set(row['outcomes']) == used and len(used) == (24 if root['alternativeIndex'] is None else 48),
                'The coupling origin has unexpected distinct tails.')
        require(len({row['outcomes'][f'arm-l-f-{r}']['chanceSeed'] for r in range(8)}) == 8,
                'The eight future replicas must have distinct chance seeds.')
        for replicate in range(8):
            first, second = semantic[(False, replicate)], semantic[(True, replicate)]
            shared = first.keys() & second.keys()
            require(all(first[key] == second[key] for key in shared), 'Matching semantic buckets changed their policy draw.')
            matched += len(shared); unmatched += len(first.keys() ^ second.keys())
        audit = {'index': index, 'status': 'trivial' if root['alternativeIndex'] is None else 'nontrivial',
                 'epoch': root['epoch'], 'legacyOutcomesVerified': legacy,
                 'matchedSemanticBuckets': matched, 'unmatchedSemanticBuckets': unmatched}
        return values if root['alternativeIndex'] is not None else None, audit


def moments(baseline, alternative, replicates=8):
    f = np.asarray(baseline, dtype=np.float64); a = np.asarray(alternative, dtype=np.float64)
    require(replicates >= 2 and f.shape == a.shape == (replicates,)
            and np.isfinite(f).all() and np.isfinite(a).all(),
            'Each arm requires its declared finite paired returns.')
    d = a-f; covariance = float(np.cov(f, a, ddof=1)[0, 1])
    result = {'meanF': float(f.mean()), 'meanA': float(a.mean()), 'meanD': float(d.mean()),
              'varF': float(f.var(ddof=1)), 'varA': float(a.var(ddof=1)),
              'covariance': covariance, 'varD': float(d.var(ddof=1))}
    require(abs(result['varD']-(result['varF']+result['varA']-2*covariance)) < 1e-12,
            'Paired variance decomposition is inconsistent.')
    return result


def chance_cost_statistics(worlds, bootstrap_seed, bootstraps=10000):
    """Authority-only diagnostic: equal original-root weights and sixteen nested future streams.

    Cost is simulation thread CPU seconds per paired replicate, excluding retrospective replay.
    Existing policy-coupling schedules/statistics retain their unchanged default contract.
    """
    require(worlds and type(bootstrap_seed) is int and bootstrap_seed >= 0,
            'Chance statistics require retained roots and an audited nonnegative analysis seed.')
    modes = ('LEGACY', 'LOGICAL_REQUEST_1')
    rows = []
    for world in worlds:
        require(set(world) == set(modes), 'Every root requires both frozen chance modes.')
        row = {}
        for mode in modes:
            cell = world[mode]
            costs = np.asarray(cell['cpuSeconds'], dtype=np.float64)
            require(costs.shape == (16,) and np.isfinite(costs).all() and (costs > 0).all(),
                    'Each chance arm requires sixteen positive measured paired CPU costs.')
            row[mode] = dict(moments(cell['reference'], cell['alternative'], 16),
                             meanCpuSeconds=float(costs.mean()))
        rows.append(row)
    variances = np.asarray([[row[mode]['varD'] for mode in modes] for row in rows])
    costs = np.asarray([[row[mode]['meanCpuSeconds'] for mode in modes] for row in rows])
    products = variances.mean(0) * costs.mean(0)
    ratio = float(products[1]/products[0]) if products[0] > 0 else None
    rng = np.random.default_rng(bootstrap_seed)
    ratios = []
    for start in range(0, bootstraps, 250):
        indices = rng.integers(0, len(rows), (min(250, bootstraps-start), len(rows)))
        values = variances[indices].mean(1) * costs[indices].mean(1)
        ratios.extend(np.divide(values[:, 1], values[:, 0],
            out=np.full(len(values), np.inf), where=values[:, 0] > 0))
    upper = percentile(ratios, .975)
    summaries = {mode: {key:float(np.mean([row[mode][key] for row in rows]))
                       for key in rows[0][mode]} for mode in modes}
    return dict(worlds=len(rows), modes=summaries, perWorld=rows,
        primary=dict(ratio=ratio, lower95=percentile(ratios, .025), upper95=upper,
            varianceCostProducts={mode:float(products[i]) for i, mode in enumerate(modes)},
            undefinedBootstrapDraws=int(np.sum(~np.isfinite(ratios))),
            gatePassed=len(rows) >= 24 and ratio is not None and ratio <= .75
                       and upper is not None and upper < 1,
            metric='Equal-root mean paired-return variance times equal-root mean paired simulation CPU seconds.'),
        bootstrap=dict(seed=bootstrap_seed, resamples=bootstraps, unit='whole original root'),
        limitation='Conditional variance on retained training worlds; no marginal-equivalence or playing-strength claim.')


def percentile(values, quantile):
    """Undefined ratios remain +infinity, never omitted from a favorable bootstrap interval."""
    ordered = np.sort(values); location = (len(ordered)-1)*quantile
    low = math.floor(location); high = math.ceil(location)
    if not np.isfinite(ordered[high]): return None
    return float(ordered[low]+(location-low)*(ordered[high]-ordered[low]))


def statistics(worlds):
    require(worlds, 'Variance analysis requires at least one nontrivial consumed world.')
    rows = [{schedule: moments(world[schedule.lower()+'-f'], world[schedule.lower()+'-a'])
             for schedule in SCHEDULES} for world in worlds]
    metrics = tuple(rows[0]['L'])
    means = {schedule: {metric: float(np.mean([row[schedule][metric] for row in rows]))
                        for metric in metrics} for schedule in SCHEDULES}
    g = np.asarray([row['G']['varD'] for row in rows]); s = np.asarray([row['S']['varD'] for row in rows])
    mean_difference = np.asarray([row['S']['meanD']-row['G']['meanD'] for row in rows])
    ratio = float(s.mean()/g.mean()) if g.mean() > 0 else None
    rng = np.random.default_rng(BOOTSTRAP_SEED); ratios = []; differences = []
    for start in range(0, BOOTSTRAPS, 250):
        indices = rng.integers(0, len(rows), (min(250, BOOTSTRAPS-start), len(rows)))
        denominator = g[indices].mean(1); numerator = s[indices].mean(1)
        ratios.extend(np.divide(numerator, denominator, out=np.full_like(numerator, np.inf), where=denominator > 0))
        differences.extend(mean_difference[indices].mean(1))
    lower, upper = percentile(ratios, .025), percentile(ratios, .975)
    primary = {'ratio': ratio, 'ratioInterval': {'lower95': lower, 'upper95': upper,
        'undefinedBootstrapDraws': int(np.sum(~np.isfinite(ratios)))},
        'gatePassed': ratio is not None and ratio <= .75 and upper is not None and upper < 1,
        'comparison': 'semantic S / independently salted global-index G; legacy L descriptive only'}
    return {'worlds': len(rows), 'schedules': means, 'primary': primary,
            'pairedMeanDifferenceSemanticMinusGlobal': {'mean': float(mean_difference.mean()),
                'lower95': percentile(differences, .025), 'upper95': percentile(differences, .975),
                'interpretation': 'Descriptive only; this is not a marginal-equivalence test.'},
            'perWorld': rows, 'bootstrap': {'resamples': BOOTSTRAPS, 'seed': BOOTSTRAP_SEED,
                'unit': 'whole independent root', 'interval': 'linear percentile 2.5%/97.5%'}}


def run(source, reference, output, resume=False):
    started = time.time(); output = Path(output).resolve()
    old_identity = read(output/'identity.json') if resume else None
    deadline = old_identity['deadlineEpochSeconds'] if resume else started+MAX_SECONDS
    require(time.time() < deadline or (output/'finished.json').exists(), 'The original coupling analysis deadline elapsed.')
    def expired(signum, frame): raise TimeoutError('The original coupling analysis deadline elapsed.')
    handler = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, max(.001, deadline-time.time()) if not (output/'finished.json').exists() else MAX_SECONDS)
    try:
        collection = Completed(source, reference)
        inputs = {'source': str(collection.source), 'completionSha256': sha(collection.source/'finished.json'),
            'reference': str(collection.reference.directory),
            'referenceCompletionSha256': sha(collection.reference.directory/'finished.json'), 'analysisSha256': sha(__file__)}
        identity = {'kind': KIND+'-analysis', 'inputs': inputs, 'sourceFiles': collection.pins,
            'referenceFiles': collection.reference.pins, 'sourceHashes': {str(Path(module.__file__).resolve()): sha(module.__file__)
                for module in (previous, advantage, card_policy, common)}, 'numpyVersion': np.__version__,
            'bootstrap': [BOOTSTRAPS, BOOTSTRAP_SEED], 'gates': {'maximumRatio': .75, 'upper95Below': 1,
                'nontrivialWorlds': 48, 'epoch3Worlds': 12}, 'deadlineEpochSeconds': deadline}
        if resume: require(old_identity == identity, 'Coupling analysis resume inputs or original clock differ.')
        else:
            require(not output.exists(), 'Fresh coupling analysis requires an unused output directory.')
            output.mkdir(parents=True); immutable(output/'identity.json', identity)
        identity_sha = common.digest(identity)
        if (output/'finished.json').exists():
            done = read(output/'finished.json')
            require(done == {'identitySha256': identity_sha, 'reportSha256': sha(output/'report.json')},
                    'Completed coupling analysis changed.')
            return read(output/'report.json')
        worlds = []; audits = []
        for request in collection.manifest['origins']:
            values, audit = collection.origin(request); audits.append(audit)
            if values is not None: worlds.append(values)
        coverage = {'requestedWorlds': 64, 'nontrivialWorlds': len(worlds),
            'epoch3Worlds': sum(audit['status'] == 'nontrivial' and audit['epoch'] == 3 for audit in audits)}
        coverage['passed'] = coverage['nontrivialWorlds'] >= 48 and coverage['epoch3Worlds'] >= 12
        stats = statistics(worlds) if worlds else {'primary': {'ratio': None, 'ratioInterval': None, 'gatePassed': False}}
        if worlds:
            stats['perWorld'] = [{'index': audit['index'], **values} for audit, values in
                zip([audit for audit in audits if audit['status'] == 'nontrivial'], stats['perWorld'])]
        stats['primary']['gatePassed'] = bool(coverage['passed'] and stats['primary']['gatePassed'])
        classification = ('insufficient-coverage' if not coverage['passed'] else
            'variance-reduction-demonstrated' if stats['primary']['gatePassed'] else 'variance-reduction-not-demonstrated')
        collection.verify()
        require(all(sha(path) == expected for path, expected in identity['sourceHashes'].items())
                and sha(__file__) == inputs['analysisSha256'], 'Coupling analysis code changed while running.')
        require(time.time() < deadline, 'The original coupling analysis deadline elapsed.')
        report = {'kind': KIND+'-analysis', 'inputs': inputs, 'identitySha256': identity_sha,
            'diagnosticOnly': True, 'policyPromotion': False, 'coverage': coverage, **stats,
            'classification': classification, 'audits': audits,
            'legacyReferenceVerified': True, 'semanticTraceVerified': True,
            'limitations': ['Eight replicas share their root hidden hands/deck.',
                'Variance reduction is not improved play or observer learnability.',
                'Legacy schedules are descriptive; changed schedules do not establish historical marginal equivalence.']}
        immutable(output/'report.json', report)
        immutable(output/'finished.json', {'identitySha256': identity_sha, 'reportSha256': sha(output/'report.json')})
        return report
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0); signal.signal(signal.SIGALRM, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'reference', 'output'): parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--resume', action='store_true'); args = parser.parse_args()
    report = run(args.source, args.reference, args.output, args.resume)
    print({'report': str(args.output/'report.json'), 'classification': report['classification']})


if __name__ == '__main__': main()
