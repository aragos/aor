"""One bounded root-pair breadth experiment; sparse training and exhaustive held-out roots.

The full eight-unit model is the only candidate. The fitted action-priority control is descriptive;
no failed stage causes a model reselection, additional worlds, renewed clock or browser activation.
"""
import argparse
from datetime import datetime
import fcntl
import gzip
import json
import math
from pathlib import Path
import signal
import sys

sys.dont_write_bytecode = True
import card_advantage_experiment as experiment
from card_advantage_priority_experiment import analyze_screen, screen_source

require = experiment.require
read = experiment.read
sha = experiment.sha
document = experiment.document
PLAN = {'kind': 'root-pairs-1', 'sampledSplits': ['train'], 'replicates': 1}
COVERAGE = {'train': {'origins': 1800, 'epoch3': 400},
            'development': {'origins': 300, 'epoch3': 60},
            'validation': {'origins': 300, 'epoch3': 60}}
LOCAL_GATE = {'minimumOverriddenOrigins': 16, 'maximumPositiveOriginFraction': .25,
              'requirePredictionBelowZero': True}
SCREEN = 'priority-screen'
COMPARISON = 'priority-comparison'


def coverage(directory, source):
    """Count nontrivial safe roots only after verifying every compressed record; ignore returns."""
    directory = Path(directory)
    require(experiment.completed_native(directory, source), 'Coverage requires the complete declared collection.')
    manifest = read(directory / 'collection.json')
    require(manifest['schemaVersion'] == 2 and manifest['collection'] == PLAN,
            'Breadth coverage requires the exact schema-two root-pair plan.')
    counts = {split: dict(origins=0, epoch3=0, totalCollected=0, trivial=0, printedWordLeader=0)
              for split in COVERAGE}
    skipped = []
    for request in manifest['origins']:
        with gzip.open(directory / ('origin-' + str(request['index']) + '.json.gz'), 'rb') as stream:
            raw = stream.read(64 * 1024 * 1024 + 1)
        require(len(raw) <= 64 * 1024 * 1024, 'Expanded origin exceeds the artifact limit.')
        row = json.loads(raw)
        require(row['schemaVersion'] == 2 and row['request'] == request
                and row['signature'] == manifest['signature'], 'Coverage origin identity differs.')
        if row['status'] == 'skipped':
            skipped.append(request['index'])
            continue
        require(row['status'] == 'collected' and len(row['groups']) == 1
                and row['groups'][0]['kind'] == 'root', 'Breadth coverage accepts one complete root only.')
        group = row['groups'][0]
        require(group['epoch'] == request['epoch'], 'The collected root must retain its requested epoch.')
        observation = row['observations'][group['observationSha256']]
        nontrivial = len(group['proposalIndices']) > 1
        counter = counts[request['split']]
        counter['totalCollected'] += 1
        counter['trivial'] += int(not nontrivial)
        counter['origins'] += int(nontrivial)
        counter['epoch3'] += int(nontrivial and group['epoch'] == 3)
        counter['printedWordLeader'] += int(observation['stateFeatures'].get('role.0.advance.PRINTED_WORD', 0) > 0
            and any(action['features'].get('cardKind.LEADER', 0) > 0 for action in observation['actions']))
    return dict(counts=counts, skippedOrigins=skipped, labelsInspected=False,
                countingUnit='nontrivial original root, with more than one proposed action',
                completionSha256=sha(directory / 'finished.json'),
                passed=all(counts[split][key] >= minimum for split, values in COVERAGE.items()
                           for key, minimum in values.items()))


def local_gate(report):
    """Recompute the fixed full-model gate; intervals and action-priority metrics cannot select it."""
    full = report['validation']['full']
    keys = ('meanSelectedAdvantage', 'predictionMse', 'zeroPredictionMse')
    require(all(type(full[key]) in (int, float) and math.isfinite(full[key]) for key in keys),
            'The complete full-model local metrics must be finite.')
    require(full['predictionMse'] >= 0 and full['zeroPredictionMse'] >= 0
            and type(full['overriddenOrigins']) is int and full['overriddenOrigins'] >= 0,
            'Prediction errors and overridden-origin counts must be nonnegative.')
    fraction = full['largestPositiveOriginFraction']
    require(fraction is None or (type(fraction) in (int, float) and math.isfinite(fraction) and 0 <= fraction <= 1),
            'The positive-origin concentration must be absent or a finite fraction.')
    original = (full['meanSelectedAdvantage'] > 1e-12 and full['overriddenOrigins'] >= 16
                and fraction is not None and fraction <= .25)
    require(type(full['localGatePassed']) is bool and full['localGatePassed'] == original,
            'The fitter and experiment must agree on the existing local gate.')
    return dict(candidate='full', originalLocalGatePassed=original,
                predictionBelowZero=full['predictionMse'] < full['zeroPredictionMse'],
                passed=original and full['predictionMse'] < full['zeroPredictionMse'],
                actionPriorityRole='descriptive-only', intervalRole='descriptive-only')


def comparison_source(base, seeds, candidate, priority):
    """Compare the frozen policies in the same worlds and roles against three F rivals."""
    jobs = [dict(index=2 * index + offset, seed=seed, seat=index % 4, arm=arm,
                 policy=arm, rivals=['baseline'] * 4)
            for index, seed in enumerate(seeds)
            for offset, arm in enumerate(('candidate', 'priority'))]
    return dict(base, policies={'baseline': {'kind': 'baseline'},
        'candidate': {'kind': 'advantage', 'path': str(candidate)},
        'priority': {'kind': 'advantage', 'path': str(priority)}}, jobs=jobs)


def analyze_comparison(seeds, rows):
    """Require a positive paired 95% lower bound over exactly 1024 independent worlds."""
    require(len(seeds) == 1024 and len(set(seeds)) == 1024
            and all(type(seed) is int for seed in seeds),
            'Priority comparison requires 1024 distinct integer worlds.')
    expected = {(seed, index % 4, arm) for index, seed in enumerate(seeds)
                for arm in ('candidate', 'priority')}
    actual = [(row['seed'], row['controlledSeat'], row['arm']) for row in rows]
    require(len(actual) == len(expected) and set(actual) == expected,
            'Priority comparison must contain every planned world, role and arm exactly once.')
    for row in rows:
        require(type(row['seed']) is int and type(row['controlledSeat']) is int,
                'Priority comparison world and role identifiers must be integers, not booleans.')
        terminal = row['terminal']
        require(all(type(terminal[key]) in (int, float) and math.isfinite(terminal[key])
                    and 0 <= terminal[key] <= 1 for key in experiment.evaluation.METRICS),
                'Comparison terminal metrics must be finite and in [0,1].')
        require(abs(terminal['reward'] - .8 * terminal['winShare']
                    - .2 * terminal['rankReward']) < 1e-12,
                'Comparison rewards must preserve the frozen composite definition.')
    comparisons = {metric: experiment.evaluation.paired(rows, 'candidate', 'priority', metric)
                   for metric in experiment.evaluation.METRICS}
    return dict(kind='breadth-priority-comparison', schemaVersion=1, stage=COMPARISON,
                independentWorlds=1024, executions=len(rows), comparisons=comparisons,
                meanWinShares={arm: sum(row['terminal']['winShare'] for row in rows
                                       if row['arm'] == arm) / 1024 for arm in ('candidate', 'priority')},
                passed=comparisons['winShare']['lower95'] > 0, humanStrengthClaim=False)


class BreadthWindow(experiment.Window):
    def __init__(self, lab, protocol_path):
        super().__init__(lab, protocol_path)
        self.preflight = read(self.lab / 'preflight.json')
        require(self.protocol.get('kind') == 'card-advantage-breadth-window'
                and self.protocol.get('schemaVersion') == 1, 'Unsupported breadth experiment protocol.')
        draft = read(self.pin(self.protocol['recipeDraft']))
        for key in ('configuration', 'collection', 'coverage', 'fit', 'screen', 'evaluation',
                    'localGate', 'comparison', 'resources', 'seedReservation', 'originRequests'):
            require(self.protocol[key] == draft[key], 'The predeclared breadth recipe changed: ' + key)
        require(self.protocol['collection'] == {'maximumSeconds': 7200, 'batchSize': 8, 'plan': PLAN}
                and self.protocol['coverage'] == COVERAGE and self.protocol['localGate'] == LOCAL_GATE
                and self.protocol['screen'] == {'worlds': 256, 'minimumWinShareGain': .02, 'maximumSeconds': 1800}
                and self.protocol['evaluation'] == {'batchSize': 8, 'maximumSecondsPerStage': 7200}
                and self.protocol['comparison'] == {'worlds': 1024, 'positiveLower95': True, 'maximumSeconds': 7200},
                'Breadth acquisition, coverage and complete-game gates are fixed before fresh worlds.')
        require(self.protocol['fit']['activeHidden'] == 8 and self.protocol['fit']['maximumSecondsPerCycle'] == 2700,
                'Breadth fitting uses the fixed eight-unit model and 45-minute allowance.')
        start = datetime.fromisoformat(self.clock['startedAt'].replace('Z', '+00:00')).timestamp()
        require(self.deadline - start == 18000 and self.clock['originalAllowanceSeconds'] == 18000
                and self.clock['renewOnResume'] is False, 'All breadth stages share one original five-hour clock.')
        require(set(self.protocol['originRequests']) == {'breadth'}, 'Breadth has exactly one prospective collection.')
        self.requests = read(self.lab / self.protocol['originRequests']['breadth']['file'])
        require(len(self.requests) == 4096 and all(
            request['index'] == index and request['seat'] == index % 4
            and request['epoch'] == (index // 4) % 3 + 1 and request['ordinal'] == (index // 12) % 8 + 1
            and request['cohort'] == 'generic' and request['opponentUsesCandidate'] == [False] * 4
            and request['split'] == ('train' if index < 3072 else 'development' if index < 3584 else 'validation')
            for index, request in enumerate(self.requests)),
            'Breadth requests must preserve the fixed global-index split, seat, epoch and ordinal schedule.')
        sizes = {'breadth': 4096, SCREEN: 256, 'primary-confirmation': 1024,
                 'rival-robustness': 256, COMPARISON: 1024}
        require(set(self.banks) == set(sizes) and all(len(self.banks[name]) == size for name, size in sizes.items())
                and self.banks['breadth'] == [row['seed'] for row in self.requests],
                'Every breadth stage must use its complete fixed world bank.')
        seeds = [seed for bank in self.banks.values() for seed in bank]
        require(all(type(seed) is int for seed in seeds) and len(set(seeds)) == len(seeds),
                'Breadth world banks must contain distinct integer seeds with no cross-stage reuse.')
        self.verify_prerequisites()

    def pin(self, entry):
        path = (self.lab / entry['file']).resolve(strict=True)
        require(sha(path) == entry['sha256'] and self.preflight['pins'].get(str(path)) == entry['sha256'],
                'Every prerequisite must preserve its preflight-bound bytes: ' + str(path))
        return path

    def verify_prerequisites(self):
        prerequisites = self.protocol['prerequisites']
        prior_preflight = self.pin(prerequisites['priorityPreflight'])
        prior_result = self.pin(prerequisites['priorityResult'])
        previous = read(prior_result)
        require(read(prior_preflight)['readyForCollection'] is True
                and previous['preflightSha256'] == sha(prior_preflight)
                and prior_result == prior_preflight.parent / 'window-result.json'
                and previous['disposition'] in ('priority-screen-gate-failed', 'independent-qualification-failed', 'qualified-for-integration')
                and previous['browserActivated'] is False,
                'The complete prior priority result must be preserved for awareness, without requiring its failure.')
        finalist = previous.get('finalist')
        require(isinstance(finalist, dict) and 'model' in finalist and 'modelSha256' in finalist,
                'Priority comparison requires a completed, frozen preceding finalist.')
        self.priority_candidate = self.pin(prerequisites['priorityCandidate'])
        require(self.priority_candidate == Path(finalist['model']).resolve(strict=True)
                and sha(self.priority_candidate) == finalist['modelSha256'],
                'The stronger comparator must retain the exact preceding priority finalist path and bytes.')
        proof = read(self.pin(prerequisites['opsProfile']))
        proof_protocol = self.pin(prerequisites['opsProtocol'])
        require(proof['passed'] is True and proof['admitted'] is True
                and proof['protocolSha256'] == sha(proof_protocol)
                and proof['worlds'] == 64 and proof['newWorlds'] == 0
                and proof['compressedRecordsExactlyEqual'] == 64
                and proof['legacyRecordsExactlyEqualExceptSignature'] == 2,
                'Root-pair collection needs its complete consumed-world batching and legacy parity proof.')
        elapsed = proof['batchWallSeconds']
        require(type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed > 0
                and 4096 / 64 * elapsed * 1.25 <= 7200,
                'Measured root-pair throughput must fit the fixed collection cap with 25 percent headroom.')
        require(proof['runtimeJars'] == {path.name: sha(path) for path in self.runtime.glob('*.jar')}
                and proof['runtimeJars'], 'The native runtime must match the measured root-pair collector exactly.')
        require(proof['sourceHashes'] and all(
            sha(path) == expected and self.preflight['pins'].get(str(Path(path).resolve())) == expected
            for path, expected in proof['sourceHashes'].items()),
                'Consumed-world proof source bytes must retain their pinned identity.')
        names = ('card_advantage_breadth_experiment.py', 'card_advantage_experiment.py',
                 'card_advantage_priority_experiment.py', 'card_advantage_evaluation.py',
                 'card-advantage-run.py', 'card_advantage_train.py', 'card_advantage.py',
                 'card_advantage_data.py', 'purchase_policy.py')
        paths = [*(self.scripts / name for name in names), *self.runtime.glob('*.jar'),
                 Path(self.base['baseline']), Path(self.base['frozenPurchasePolicy'])]
        require(all(self.preflight['pins'].get(str(path.resolve())) == sha(path) for path in paths),
                'Every executed helper, native jar and frozen playing policy must be explicitly pinned.')

    def collect(self):
        source = dict(self.base, schemaVersion=2, continuation=None, collection=PLAN, origins=self.requests)
        output = self.native('collect', 'breadth', source, 7200)
        report = coverage(output, self.lab / 'breadth-source.json')
        document(self.lab / 'breadth-coverage.json', report)
        return output, report

    def fit(self, collection):
        output = self.lab / 'breadth-fit'
        if not (output / 'finished.json').exists():
            identity = read(output / 'identity.json') if (output / 'identity.json').exists() else None
            command = [sys.executable, '-B', str(self.scripts / 'card_advantage_train.py'),
                       '--source', str(collection), '--output', str(output), '--active-hidden', '8']
            if identity is not None:
                command.append('--resume')
            self.execute('breadth-fit', command, 2700, identity['deadlineEpochSeconds'] if identity else None)
        report = experiment.completed_fit(output, collection, self.protocol, self.scripts)
        require(read(output / 'frequency-calibration.json')['outcomeUse'] is False,
                'The full-model frequency control must use outcome-free development calibration.')
        return output, report

    def screen(self, candidate):
        output = self.native('evaluate', SCREEN, screen_source(self.base, self.banks[SCREEN], candidate), 1800)
        rows, provenance = experiment.evaluation.load_completed(output)
        report = analyze_screen(self.banks[SCREEN], rows)
        report.update(kind='breadth-card-screen', provenance=provenance)
        document(self.lab / (SCREEN + '-report.json'), report)
        return report

    def compare_priority(self, candidate):
        source = comparison_source(self.base, self.banks[COMPARISON], candidate, self.priority_candidate)
        output = self.native('evaluate', COMPARISON, source, 7200)
        rows, provenance = experiment.evaluation.load_completed(output)
        report = analyze_comparison(self.banks[COMPARISON], rows)
        report['provenance'] = provenance
        document(self.lab / (COMPARISON + '-report.json'), report)
        return report

    def run(self):
        collection, coverage_report = self.collect()
        if not coverage_report['passed']:
            return self.finish('breadth-coverage-failed', comparisonPassed=None)
        fit, report = self.fit(collection)
        require(report['validation']['full']['nontrivialOrigins'] == coverage_report['counts']['validation']['origins'],
                'Full-model validation must cover every nontrivial held-out root.')
        gate = local_gate(report)
        gate['fitCompletionSha256'] = sha(fit / 'finished.json')
        document(self.lab / 'breadth-local-gate.json', gate)
        if not gate['passed']:
            return self.finish('breadth-local-gate-failed', comparisonPassed=None)
        if not self.screen(fit / 'full.json')['mayContinue']:
            return self.finish('breadth-screen-gate-failed', comparisonPassed=None)
        selection = dict(model=str(fit / 'full.json'), modelSha256=sha(fit / 'full.json'),
                         frequency=str(fit / 'frequency-control.json'), frequencySha256=sha(fit / 'frequency-control.json'))
        document(self.lab / 'finalist.json', selection)
        primary = self.evaluate('primary-confirmation', {'candidate': fit / 'full.json', 'frequency': fit / 'frequency-control.json'})
        robustness = self.evaluate('rival-robustness', {'candidate': fit / 'full.json'})
        comparison = (self.compare_priority(fit / 'full.json')
                      if primary['passed'] and robustness['passed'] else None)
        comparison_passed = comparison['passed'] if comparison is not None else None
        return self.finish('qualified-for-integration' if comparison_passed is True
                           else 'independent-qualification-failed', finalist=selection,
                           primaryPassed=primary['passed'], robustnessPassed=robustness['passed'],
                           comparisonPassed=comparison_passed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--status', action='store_true')
    args = parser.parse_args()
    if args.status:
        print(json.dumps(read(args.lab / 'window-status.json'), indent=2))
        return
    def cancel(*_):
        raise KeyboardInterrupt('Breadth experiment canceled.')
    signal.signal(signal.SIGTERM, cancel)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        BreadthWindow(args.lab, args.protocol).run()


if __name__ == '__main__':
    main()
