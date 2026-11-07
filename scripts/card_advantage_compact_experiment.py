"""One conditional eight-unit capacity experiment using the existing bounded Window executor.

Only original C1 train/development data fit the model. Original validation is descriptive; the
fresh F-continuation collection supplies the local gate. The 64-unit reference is never reselected.
No stage activates a browser asset, renews a clock, or retries qualification on additional worlds.
"""
import argparse
from datetime import datetime
import fcntl
import math
from pathlib import Path
import signal
import sys
import time

sys.dont_write_bytecode = True
import card_advantage_experiment as experiment

require = experiment.require
read = experiment.read
sha = experiment.sha
document = experiment.document


def timestamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


class CompactWindow(experiment.Window):
    def __init__(self, lab, protocol_path):
        super().__init__(lab, protocol_path)
        require(self.protocol.get('kind') == 'card-advantage-compact-window'
                and self.protocol.get('schemaVersion') == 1, 'Unsupported compact experiment protocol.')
        self.preflight = read(self.lab / 'preflight.json')
        self.verify_recipe()
        self.verify_prerequisites()

    def pin(self, entry):
        path = (self.lab / entry['file']).resolve(strict=True)
        require(sha(path) == entry['sha256']
                and self.preflight['pins'].get(str(path)) == entry['sha256'],
                'Every compact prerequisite must retain its exact preflight-bound bytes: ' + str(path))
        return path

    def prerequisite(self, name):
        return self.pin(self.protocol['prerequisites'][name])

    def verify_recipe(self):
        draft = read(self.pin(self.protocol['recipeDraft']))
        for key in ('configuration', 'fit', 'collection', 'coverage', 'evaluation', 'analysis',
                    'resources', 'seedReservation', 'originRequests'):
            require(self.protocol[key] == draft[key], 'The prepared scientific recipe changed: ' + key)
        self.training = Path(self.protocol['prerequisites']['trainingCollection']).resolve(strict=True)
        require(str(self.training) == draft['trainingCollection'], 'Compact fitting must use the original declared C1 collection.')
        require(self.protocol['fit']['activeHidden'] == 8
                and self.protocol['fit']['maximumSecondsPerCycle'] == 2700
                and self.protocol['collection']['maximumSeconds'] == 3600
                and self.protocol['analysis']['maximumSeconds'] == 600
                and self.protocol['evaluation'] == {'batchSize': 8, 'maximumSecondsPerStage': 7200},
                'Compact capacity and stage limits are fixed before outcomes.')
        require(self.protocol['coverage'] == {'validation': {'origins': 96, 'epoch3': 8, 'printedWordLeader': 4}},
                'The fresh validation coverage guard may not change.')
        start = timestamp(self.clock['startedAt'])
        require(self.deadline - start == 6 * 3600 and self.clock['originalAllowanceSeconds'] == 6 * 3600
                and self.clock['renewOnResume'] is False and self.admission == self.deadline - 3 * 3600
                and start <= self.collection_cutoff <= self.admission,
                'Compact work requires one original six-hour clock and a final three-hour reserve.')
        require(set(self.protocol['originRequests']) == {'compact-validation'}, 'Compact work has one fresh collection only.')
        self.requests = read(self.lab / self.protocol['originRequests']['compact-validation']['file'])
        require(len(self.requests) == 128 and [r['index'] for r in self.requests] == list(range(128))
                and len({r['seed'] for r in self.requests}) == 128
                and all(r['split'] == 'validation' and not any(r['opponentUsesCandidate']) for r in self.requests),
                'Fresh validation requires 128 prospective, distinct F-continuation worlds.')
        require(set(self.banks) == {'compact-validation', 'common-development', 'primary-confirmation', 'rival-robustness'}
                and self.banks['compact-validation'] == [r['seed'] for r in self.requests],
                'Fresh collection and evaluation banks must match their declared reservations.')

    def verify_prerequisites(self):
        audit_path = self.prerequisite('priorAudit')
        result_path = self.prerequisite('priorResult')
        audit, result = read(audit_path), read(result_path)
        require(audit.get('readyForCollection') is True
                and result.get('preflightSha256') == sha(audit_path)
                and result.get('disposition') == 'independent-qualification-failed'
                and result.get('browserActivated') is False
                and type(result.get('primaryPassed')) is bool and type(result.get('robustnessPassed')) is bool
                and not (result['primaryPassed'] and result['robustnessPassed']),
                'The prior window must have completed and failed independent qualification; a qualified policy forbids this run.')
        prior_lab = audit_path.parent
        require(result_path == prior_lab / 'window-result.json' and self.training == prior_lab / 'cycle-1'
                and self.lab != prior_lab and prior_lab not in self.lab.parents and self.lab not in prior_lab.parents,
                'Original evidence and new compact work require separate labs and exact C1 ownership.')
        training_source = self.prerequisite('trainingSource')
        require(training_source == prior_lab / 'cycle-1-source.json'
                and experiment.completed_native(self.training, training_source),
                'Compact fitting requires the exact completed original C1 source and records.')
        training_manifest = read(self.training / 'collection.json')
        require(training_manifest['configuration'] == self.protocol['configuration']
                and training_manifest['continuation'] is None
                and sha(self.base['baseline']) == training_manifest['baseline']['sha256']
                and sha(self.base['frozenPurchasePolicy']) == training_manifest['frozenPurchasePolicy']['sha256'],
                'New validation/evaluation must retain original C1 F and purchase32 policies.')
        self.reference = self.prerequisite('referenceModel')
        require(self.reference == prior_lab / 'cycle-1-fit/full.json', 'The fixed reference must be original P1, never a reselected model.')
        reference_report = read(self.reference.parent / 'finished.json')
        require(sha(self.reference) == reference_report['models']['full']['sha256']
                == result['finalist']['modelSha256'],
                'The original P1 completed-fit and prior finalist bindings differ.')
        toy = read(self.prerequisite('compactToy'))
        toy_path = self.prerequisite('compactToyProtocol')
        toy_protocol = read(toy_path)
        require(toy.get('passed') is True and toy.get('status') == 'complete' and toy.get('actualGames') == 0
                and len(toy['cycles']) == 4 and toy['protocolSha256'] == sha(toy_path)
                and toy_protocol['config'] == experiment.fit_config(self.protocol)
                and toy_protocol['maximumSeconds'] == 600,
                'The same fixed eight-unit recipe must pass its complete four-cycle, game-free toy.')
        toy_sources = {Path(path).name: expected for path, expected in toy_protocol['sourceHashes'].items()}
        require(all(toy_sources.get(name) == sha(self.scripts / name)
                    for name in ('card_advantage.py', 'card_advantage_train.py')),
                'The compact toy and deployed numerical implementation differ.')
        profile = read(self.prerequisite('batchProfile'))
        profile_path = self.prerequisite('batchProfileProtocol')
        profile_protocol = read(profile_path)
        require(profile.get('passed') is True and profile.get('allPinsUnchanged') is True
                and profile.get('compressedRecordsExactlyEqual') == 64
                and profile.get('oldRecordsExactlyEqualExceptSignature') == 64
                and profile.get('exactOldPublicTerminalsAndSteps') == 64 and profile.get('newWorlds') == 0
                and profile['protocolSha256'] == sha(profile_path),
                'Eight-game batching requires the complete consumed-world parity proof.')
        measured_runtime = Path(profile_protocol['runtime'])
        measured_jars = {Path(path).name: expected for path, expected in profile_protocol['pins'].items()
                         if Path(path).parent == measured_runtime and Path(path).suffix == '.jar'}
        actual_jars = {path.name: sha(path) for path in self.runtime.glob('*.jar')}
        require(measured_jars and measured_jars == actual_jars,
                'Compact native jars must be byte-identical to the parity-profiled runtime.')
        required_scripts = ('card_advantage_compact_experiment.py', 'card_advantage_experiment.py',
                            'card_advantage_capacity.py', 'card-advantage-run.py',
                            'card_advantage_evaluation.py', 'card_advantage_train.py',
                            'card_advantage.py', 'card_advantage_data.py', 'purchase_policy.py')
        for path in [*(self.scripts / name for name in required_scripts), *self.runtime.glob('*.jar')]:
            require(self.preflight['pins'].get(str(path.resolve())) == sha(path),
                    'The installed compact helper/runtime must be explicitly pinned: ' + str(path))

    def admit(self, seconds):
        if time.time() >= self.collection_cutoff or time.time() + seconds > self.admission:
            raise experiment.CollectionNotAdmitted('New compact development work cannot consume the final three-hour reserve.')

    def fit_compact(self):
        fit = self.lab / 'compact-fit'
        saved = read(fit / 'identity.json') if (fit / 'identity.json').exists() else None
        if not (fit / 'finished.json').exists():
            if saved is None:
                self.admit(2700 + 3600 + 600)
            command = [sys.executable, '-B', str(self.scripts / 'card_advantage_train.py'),
                       '--source', str(self.training), '--output', str(fit), '--active-hidden', '8']
            if saved:
                command.append('--resume')
            self.execute('compact-fit', command, 2700, saved['deadlineEpochSeconds'] if saved else None)
        experiment.completed_fit(fit, self.training, self.protocol, self.scripts)
        document(self.lab / 'compact-fit-disposition.json', dict(
            fitCompletionSha256=sha(fit / 'finished.json'), activeHidden=8,
            originalLocalValidationIsDescriptiveOnly=True, originalLocalGateUsedForAdmission=False))
        return fit

    def validate_compact(self, fit):
        output = self.lab / 'compact-validation'
        if not (output / 'started.json').exists():
            self.admit(3600 + 600)
        source = dict(self.base, continuation=None, origins=self.requests)
        self.native('collect', 'compact-validation', source, 3600)
        check = experiment.coverage(output, self.protocol['coverage'])
        document(self.lab / 'compact-validation-coverage.json', check)
        if not check['passed']:
            return None
        report_path = self.lab / 'compact-capacity-report.json'
        expected = dict(source=str(output), completionSha256=sha(output / 'finished.json'),
                        fit=str(fit), fitCompletionSha256=sha(fit / 'finished.json'),
                        candidateSha256=sha(fit / 'full.json'), referenceModel=str(self.reference),
                        referenceSha256=sha(self.reference), helperSha256=sha(self.scripts / 'card_advantage_capacity.py'))
        document(self.lab / 'compact-analysis-inputs.json', expected)
        clock_path = self.lab / 'compact-analysis-clock.json'
        clock_binding = self.lab / 'compact-analysis-clock-binding.json'
        analysis_started = report_path.exists() or (self.lab / 'compact-capacity-analysis.log').exists()
        if not clock_path.exists():
            require(not analysis_started and not clock_binding.exists(),
                    'An existing analysis may not adopt a renewed or missing original clock.')
            self.admit(600)
            now = time.time()
            document(clock_path, dict(startedAt=now, deadline=min(self.deadline, now + 600),
                                     inputsSha256=sha(self.lab / 'compact-analysis-inputs.json')))
        clock = read(clock_path)
        require(type(clock['startedAt']) in (int, float) and math.isfinite(clock['startedAt'])
                and 0 < clock['startedAt'] <= time.time() + 5
                and clock['deadline'] == min(self.deadline, clock['startedAt'] + 600)
                and clock['inputsSha256'] == sha(self.lab / 'compact-analysis-inputs.json'),
                'Compact analysis must retain its exact inputs and original ten-minute allowance.')
        require(clock_binding.exists() or not analysis_started,
                'An analysis already started without its original clock binding.')
        document(clock_binding, {'sha256': sha(clock_path)})
        if not report_path.exists():
            self.execute('compact-capacity-analysis', [sys.executable, '-B',
                str(self.scripts / 'card_advantage_capacity.py'), '--source', str(output),
                '--fit', str(fit), '--reference-model', str(self.reference),
                '--reference-sha256', self.protocol['prerequisites']['referenceModel']['sha256'],
                '--output', str(report_path)],
                600, clock['deadline'])
        report = read(report_path)
        require(report['inputs'] == expected and type(report['candidate']['localGatePassed']) is bool,
                'Capacity analysis must belong to the exact completed fresh validation and frozen models.')
        document(self.lab / 'compact-analysis-binding.json', dict(
            reportSha256=sha(report_path), inputsSha256=sha(self.lab / 'compact-analysis-inputs.json')))
        return report

    def run(self):
        fit = self.fit_compact()
        local = self.validate_compact(fit)
        if local is None:
            return self.finish('compact-validation-coverage-failed')
        if not local['candidate']['localGatePassed']:
            return self.finish('compact-fresh-local-gate-failed')
        development = self.evaluate('common-development', {'p1': self.reference, 'p2': fit / 'full.json'})
        if development['selected'] != 'p2' or development['meanWinShares']['p2'] <= development['meanWinShares']['baseline']:
            return self.finish('compact-common-development-gate-failed', compactSelected=False)
        selection = dict(model=str(fit / 'full.json'), modelSha256=sha(fit / 'full.json'),
                         frequency=str(fit / 'frequency-control.json'),
                         frequencySha256=sha(fit / 'frequency-control.json'))
        document(self.lab / 'finalist.json', selection)
        primary = self.evaluate('primary-confirmation', {'candidate': fit / 'full.json', 'frequency': fit / 'frequency-control.json'})
        robustness = self.evaluate('rival-robustness', {'candidate': fit / 'full.json'})
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
        raise KeyboardInterrupt('Compact experiment canceled.')
    signal.signal(signal.SIGTERM, cancel)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        CompactWindow(args.lab, args.protocol).run()


if __name__ == '__main__':
    main()
