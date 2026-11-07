"""Evaluate one frozen action-priority policy without fitting or changing the proposal sampler.

A complete 256-world screen precedes the existing independent strength/robustness gates.
All stages share one original three-hour clock, per-game artifacts and the existing supervisor.
"""
import argparse
from datetime import datetime
import fcntl
import math
from pathlib import Path
import signal
import statistics
import sys

sys.dont_write_bytecode = True
import card_advantage_experiment as experiment

require = experiment.require
read = experiment.read
sha = experiment.sha
document = experiment.document
evaluation = experiment.evaluation
CANDIDATE_SHA256 = 'caf24d21d2304ead3a090a9e0dff8e61bfbf32578af171a2e451677d271acf5b'
SCREEN = 'priority-screen'


def screen_source(base, seeds, candidate):
    """Explicit paired jobs; every world uses its precommitted rotating controlled role."""
    jobs = [dict(index=2 * index + offset, seed=seed, seat=index % 4, arm=arm,
                 policy=arm, rivals=['baseline'] * 4)
            for index, seed in enumerate(seeds)
            for offset, arm in enumerate(('candidate', 'baseline'))]
    return dict(base, policies={'baseline': {'kind': 'baseline'},
                               'candidate': {'kind': 'advantage', 'path': str(candidate)}}, jobs=jobs)


def analyze_screen(seeds, rows):
    """The two-point mean gate is fixed; its paired interval is descriptive, not another gate."""
    require(len(seeds) == 256 and len(set(seeds)) == 256
            and all(type(seed) is int for seed in seeds), 'The screen requires 256 distinct integer worlds.')
    expected = {(seed, index % 4, arm) for index, seed in enumerate(seeds)
                for arm in ('candidate', 'baseline')}
    actual = [(row['seed'], row['controlledSeat'], row['arm']) for row in rows]
    require(len(actual) == len(expected) and set(actual) == expected,
            'The complete screen must contain every planned world, role and arm exactly once.')
    for row in rows:
        require(type(row['seed']) is int and type(row['controlledSeat']) is int,
                'Screen world and role identifiers must be integers, not booleans.')
        terminal = row['terminal']
        require(all(type(terminal[key]) in (int, float) and math.isfinite(terminal[key])
                    and 0 <= terminal[key] <= 1 for key in evaluation.METRICS),
                'Screen terminal metrics must be finite and within [0,1].')
        require(abs(terminal['reward'] - .8 * terminal['winShare']
                    - .2 * terminal['rankReward']) < 1e-12,
                'Screen rewards must retain the frozen composite definition.')
    comparisons = {metric: evaluation.paired(rows, 'candidate', 'baseline', metric)
                   for metric in evaluation.METRICS}
    return dict(kind='card-action-priority-screen', schemaVersion=1, stage=SCREEN,
                independentWorlds=256, executions=len(rows), comparisons=comparisons,
                meanWinShares={arm: statistics.mean(row['terminal']['winShare'] for row in rows
                                                   if row['arm'] == arm)
                               for arm in ('candidate', 'baseline')},
                minimumWinShareGain=.02, mayContinue=comparisons['winShare']['mean'] >= .02,
                qualification=False, humanStrengthClaim=False)


class PriorityWindow(experiment.Window):
    def __init__(self, lab, protocol_path):
        super().__init__(lab, protocol_path)
        self.preflight = read(self.lab / 'preflight.json')
        require(self.protocol.get('kind') == 'card-advantage-priority-window'
                and self.protocol.get('schemaVersion') == 1, 'Unsupported priority experiment protocol.')
        require(self.protocol['originRequests'] == {}
                and self.protocol['screen'] == {'worlds': 256, 'minimumWinShareGain': .02, 'maximumSeconds': 1800}
                and self.protocol['evaluation'] == {'batchSize': 8, 'maximumSecondsPerStage': 7200},
                'This evaluation-only recipe has fixed screen, batch and stage limits.')
        start = datetime.fromisoformat(self.clock['startedAt'].replace('Z', '+00:00')).timestamp()
        require(self.deadline - start == 10800 and self.clock['originalAllowanceSeconds'] == 10800
                and self.clock['renewOnResume'] is False,
                'All priority stages must share one original nonrenewable three-hour clock.')
        counts = {SCREEN: 256, 'primary-confirmation': 1024, 'rival-robustness': 256}
        require(set(self.banks) == set(counts)
                and all(len(self.banks[name]) == count for name, count in counts.items()),
                'Priority evaluation requires exactly its three complete predeclared banks.')
        seeds = [seed for bank in self.banks.values() for seed in bank]
        require(len(set(seeds)) == len(seeds) and all(type(seed) is int for seed in seeds),
                'Evaluation banks require distinct integer world seeds, with no cross-stage reuse.')
        self.verify_prerequisites()

    def pinned(self, name):
        entry = self.protocol['prerequisites'][name]
        path = (self.lab / entry['file']).resolve(strict=True)
        require(sha(path) == entry['sha256']
                and self.preflight['pins'].get(str(path)) == entry['sha256'],
                'The exact prerequisite must be pinned before any game: ' + name)
        return path

    def verify_prerequisites(self):
        compact_preflight = self.pinned('compactPreflight')
        compact_result = self.pinned('compactResult')
        result = read(compact_result)
        require(read(compact_preflight)['readyForCollection'] is True
                and result['preflightSha256'] == sha(compact_preflight)
                and result['disposition'] == 'compact-common-development-gate-failed'
                and result['browserActivated'] is False
                and compact_result == compact_preflight.parent / 'window-result.json',
                'Only the completed failed compact development gate permits this experiment.')
        original = self.pinned('originalPreflight')
        require(read(original)['readyForCollection'] is True
                and read(compact_preflight)['pins'].get(str(original)) == sha(original),
                'The compact result must descend from the same original preflight evidence.')
        training_source = self.pinned('trainingSource')
        training = original.parent / 'cycle-1'
        require(training_source == original.parent / 'cycle-1-source.json'
                and experiment.completed_native(training, training_source),
                'The priority policy requires its complete original C1 collection.')
        manifest = read(training / 'collection.json')
        require(manifest['configuration'] == self.protocol['configuration'] and manifest['continuation'] is None
                and sha(self.base['baseline']) == manifest['baseline']['sha256']
                and sha(self.base['frozenPurchasePolicy']) == manifest['frozenPurchasePolicy']['sha256'],
                'Priority games must preserve original C1 setup, F baseline and purchase32 policy.')
        fit_report = self.pinned('fitReport')
        self.candidate = self.pinned('candidate')
        require(fit_report == original.parent / 'cycle-1-fit/finished.json'
                and sha(self.candidate) == CANDIDATE_SHA256
                == read(fit_report)['models']['action-priority']['sha256'],
                'The candidate must be the exact existing C1 action-priority model, without refitting.')
        self.frequency = self.pinned('frequencyControl')
        calibration = read(self.pinned('frequencyCalibration'))
        require(calibration['candidateSha256'] == sha(self.candidate)
                and calibration['collection'] == str(training)
                and calibration['collectionCompletionSha256'] == sha(training / 'finished.json')
                and calibration['collectionSignature'] == manifest['signature']
                and calibration['split'] == 'development'
                and calibration['controlSha256'] == sha(self.frequency)
                and calibration['calibration']['outcomeUse'] is False
                and calibration['calibration']['decisions'] > 0,
                'Frequency control must be frozen from this candidate on original C1 development inputs only.')
        require(calibration['sourceHashes'] and all(
            sha(path) == expected and self.preflight['pins'].get(str(Path(path).resolve())) == expected
            for path, expected in calibration['sourceHashes'].items()),
            'Frequency calibration implementation must retain its exact pinned source bytes.')
        profile_path = self.pinned('batchProfileProtocol')
        profile = read(self.pinned('batchProfile'))
        profile_protocol = read(profile_path)
        require(profile['passed'] is True and profile['allPinsUnchanged'] is True
                and profile['compressedRecordsExactlyEqual'] == profile['oldRecordsExactlyEqualExceptSignature']
                == profile['exactOldPublicTerminalsAndSteps'] == 64 and profile['newWorlds'] == 0
                and profile['protocolSha256'] == sha(profile_path)
                and profile_protocol['batchSizes'] == [1, 8] and profile_protocol['workers'] == 6,
                'Eight-game batching requires the completed consumed-world parity proof.')
        measured_runtime = Path(profile_protocol['runtime'])
        measured_jars = {Path(path).name: expected for path, expected in profile_protocol['pins'].items()
                         if Path(path).parent == measured_runtime and Path(path).suffix == '.jar'}
        require(measured_jars and measured_jars == {path.name: sha(path) for path in self.runtime.glob('*.jar')},
                'Priority native jars must be byte-identical to the measured parity runtime.')
        scripts = ('card_advantage_priority_experiment.py', 'card_advantage_experiment.py',
                   'card_advantage_evaluation.py', 'card-advantage-run.py')
        for path in [*(self.scripts / name for name in scripts), *self.runtime.glob('*.jar')]:
            require(self.preflight['pins'].get(str(path.resolve())) == sha(path),
                    'Every executed helper and native jar must be explicitly pinned: ' + str(path))

    def screen(self):
        source = screen_source(self.base, self.banks[SCREEN], self.candidate)
        output = self.native('evaluate', SCREEN, source, 1800)
        rows, provenance = evaluation.load_completed(output)
        report = analyze_screen(self.banks[SCREEN], rows)
        report['provenance'] = provenance
        document(self.lab / (SCREEN + '-report.json'), report)
        return report

    def run(self):
        if not self.screen()['mayContinue']:
            return self.finish('priority-screen-gate-failed')
        selection = dict(model=str(self.candidate), modelSha256=sha(self.candidate),
                         frequency=str(self.frequency), frequencySha256=sha(self.frequency))
        document(self.lab / 'finalist.json', selection)
        primary = self.evaluate('primary-confirmation', {'candidate': self.candidate, 'frequency': self.frequency})
        robustness = self.evaluate('rival-robustness', {'candidate': self.candidate})
        return self.finish('qualified-for-integration' if primary['passed'] and robustness['passed']
                           else 'independent-qualification-failed', finalist=selection,
                           primaryPassed=primary['passed'], robustnessPassed=robustness['passed'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--status', action='store_true')
    args = parser.parse_args()
    if args.status:
        print(__import__('json').dumps(read(args.lab / 'window-status.json'), indent=2))
        return
    def cancel(*_):
        raise KeyboardInterrupt('Priority evaluation canceled.')
    signal.signal(signal.SIGTERM, cancel)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        PriorityWindow(args.lab, args.protocol).run()


if __name__ == '__main__':
    main()
