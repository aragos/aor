"""A fixed 14-candidate economy search with cached incumbents and independent confirmation.

Only token buying and expansion may vary. Purchase32, other decisions, candidate weights,
worlds, rival populations and stage gates are frozen before the first screen game.
"""
import argparse
from datetime import datetime
import fcntl
import json
from pathlib import Path
import signal
import sys

sys.dont_write_bytecode = True
import card_advantage_experiment as experiment
import joint_economy as search

require, read, sha, document = experiment.require, experiment.read, experiment.sha, experiment.document
# The full digest is deliberately checked against the original qualified input, not a model name.
BASE_SHA256 = 'ca5a700523e29d5084bf754d6a19ff2051d618ee844ec8a617aee53701bed140'
PURCHASE_SHA256 = '3802440bc84830724e32c8f32f273648f38128fc7fc1774a9c586e5b1b1b2aa4'
PRIORITY_SHA256 = 'caf24d21d2304ead3a090a9e0dff8e61bfbf32578af171a2e451677d271acf5b'
STAGES = {'screen': {'worlds': 64, 'maximumSeconds': 1800},
          'selection': {'worlds': 192, 'maximumSeconds': 1800},
          'confirmation': {'worlds': 1024, 'maximumSeconds': 3600}}
STATISTICS = {'bootstrapSamples': 10000, 'bootstrapSeed': 501107,
              'selectionMinimumGain': .03, 'selectionPopulationMinimumGain': 0.,
              'confirmationMinimumGain': .05, 'confirmationLower95Above': 0.,
              'confirmationPopulationMinimumGain': 0., 'confirmationPopulationLower95Above': -.03}
RESOURCES = {'nativeWorkers': 6, 'activeProcessorsPerWorker': 1, 'heapGiBPerWorker': 1,
             'maximumNewDataBytes': 4 * 1024**3, 'minimumDiskFreeBytes': 8 * 1024**3}
HELPERS = ('joint_economy_experiment.py', 'joint_economy.py',
           'card_advantage_experiment.py', 'card_advantage_evaluation.py', 'card-advantage-run.py')


def python_runtime():
    """Pin the existing numerical interpreter without creating or modifying an environment."""
    return dict(executable=str(Path(sys.executable).absolute()), executableSha256=sha(sys.executable),
                version=sys.version, numpyVersion=search.np.__version__)


def analyze_request(path):
    """Run the completed-only statistical owner inside the existing bounded process runner."""
    request = read(path)
    source = Path(request['source'])
    require(sha(source) == request['sourceSha256'], 'The analysis source changed after completion.')
    report = search.analyze_completed(request['stage'], request['seeds'], request['ids'],
        Path(request['native']), source, request['base'], Path(request['candidates']), Path(request['priority']))
    output = Path(request['output'])
    document(output, report)
    document(output.with_suffix('.binding.json'), {'requestSha256': sha(path), 'reportSha256': sha(output)})


class JointEconomyWindow(experiment.Window):
    def __init__(self, lab, protocol_path):
        super().__init__(lab, protocol_path)
        self.preflight = read(self.lab / 'preflight.json')
        require(self.protocol['kind'] == 'joint-economy-search-window' and self.protocol['schemaVersion'] == 1,
                'Unsupported joint economy search protocol.')
        draft = read(self.pin(self.protocol['recipeDraft']))
        fields = ('configuration', 'stages', 'statistics', 'resources', 'seedReservation', 'originRequests', 'evaluation', 'candidateManifest', 'pythonRuntime')
        require(all(self.protocol[name] == draft[name] for name in fields), 'The prospective recipe changed.')
        require(self.protocol['pythonRuntime'] == python_runtime(),
                'Use the original pinned Python executable, version and NumPy version on every restart.')
        require(self.protocol['stages'] == STAGES and self.protocol['statistics'] == STATISTICS
                and self.protocol['resources'] == RESOURCES and self.protocol['evaluation'] == {'batchSize': 8}
                and self.protocol['originRequests'] == {}, 'Keep the fixed evaluation-only search recipe.')
        started = datetime.fromisoformat(self.clock['startedAt'].replace('Z', '+00:00')).timestamp()
        require(self.deadline - started == self.clock['originalAllowanceSeconds'] == 10800
                and self.clock['renewOnResume'] is False, 'All stages share one original three-hour clock.')
        require(set(self.banks) == set(STAGES) and all(len(self.banks[key]) == value['worlds']
                for key, value in STAGES.items()), 'Use exactly the three predeclared full world banks.')
        seeds = [seed for bank in self.banks.values() for seed in bank]
        require(all(type(seed) is int for seed in seeds) and len(set(seeds)) == 1280,
                'All 1280 worlds must be distinct integer seeds; no stage reuse or topups.')
        self.verify_prerequisites()

    def pin(self, entry):
        path = (self.lab / entry['file']).resolve(strict=True)
        require(sha(path) == entry['sha256'] == self.preflight['pins'].get(str(path)),
                'A frozen prerequisite changed: ' + str(path))
        return path

    def verify_prerequisites(self):
        prior = self.protocol['prerequisites']
        self.priority = self.pin(prior['priorityCandidate'])
        require(sha(self.base['baseline']) == BASE_SHA256
                and sha(self.base['frozenPurchasePolicy']) == PURCHASE_SHA256
                and sha(self.priority) == PRIORITY_SHA256,
                'Preserve the exact qualified F/purchase32 and original priority64 rival.')
        priority_fit = read(self.pin(prior['priorityFitCompletion']))
        require(priority_fit['models']['action-priority']['sha256'] == PRIORITY_SHA256,
                'The priority rival must remain the original C1 model.')
        binding = read(self.pin(prior['baselineBindingEvidence']))
        require(binding['baselineSha256'] == BASE_SHA256 and binding['candidateSha256'] == PURCHASE_SHA256
                and binding['sourceParametersPreserved'] is True and binding['qualificationPassed'] is True,
                'The baseline and purchase model must retain their qualified deployment binding.')
        for name in ('purchaseEvidence', 'purchaseRivalEvidence'):
            evidence = read(self.pin(prior[name]))
            require(evidence['passed'] is True and evidence['candidateSha256'] == PURCHASE_SHA256,
                    'The frozen purchase model must retain its original and learned-rival qualification.')
        candidate_manifest = self.pin(prior['candidateManifest'])
        require(candidate_manifest == self.pin(self.protocol['candidateManifest']),
                'Use the exact candidate manifest frozen in the prospective recipe.')
        self.candidates = candidate_manifest.parent
        manifest, _ = search.load_candidates(self.candidates)
        require(manifest['base']['sha256'] == BASE_SHA256, 'Candidates must derive from the exact incumbent.')
        self.ids = [row['id'] for row in manifest['candidates'] if row['id'] != 'incumbent']
        require(len(self.ids) == len(set(self.ids)) == 14, 'Freeze exactly 14 non-incumbent candidates.')
        for row in manifest['candidates']:
            self.pin({'file': str(self.candidates / row['file']), 'sha256': row['sha256']})
        jars = {path.name: sha(path) for path in self.runtime.glob('*.jar')}
        require(jars, 'Native runtime jars must exist.')
        expected_inputs = {'baselineSha256': BASE_SHA256, 'purchaseSha256': PURCHASE_SHA256,
                           'prioritySha256': PRIORITY_SHA256, 'candidateManifestSha256': sha(candidate_manifest)}
        for name in ('incumbentParity', 'behavioralFeasibility'):
            report = read(self.pin(prior[name]))
            require(report['passed'] is True and report['inputs'] == expected_inputs
                    and report['runtimeJars'] == jars and report['allPinsUnchanged'] is True,
                    'Admission requires current runtime and exact policy evidence: ' + name)
            require(report['sourceHashes'] and all(sha(path) == expected
                    and self.preflight['pins'].get(str(Path(path).resolve())) == expected
                    for path, expected in report['sourceHashes'].items()),
                    'Every admission proof source must retain its pinned bytes.')
            if name == 'incumbentParity':
                require(report['newWorlds'] == 0 and type(report['exactGames']) is int and report['exactGames'] >= 4,
                        'Require at least four exact consumed incumbent games, with no new worlds.')
            else:
                require(report['fixedInputs'] is True and report['candidateCount'] == 15,
                        'Behavioral feasibility covers all fixed candidates before outcome selection.')
        for path in [*(self.scripts / name for name in HELPERS), *self.runtime.glob('*.jar'),
                     Path(self.base['baseline']), Path(self.base['frozenPurchasePolicy']), Path(sys.executable)]:
            require(self.preflight['pins'].get(str(path.resolve())) == sha(path),
                    'Every executed helper, native jar and playing input must be pinned: ' + str(path))

    def stage(self, name, ids):
        source = search.make_source(name, self.banks[name], ids, self.base, self.candidates, self.priority)
        output = self.native('evaluate', name, source, STAGES[name]['maximumSeconds'])
        source_path = self.lab / (name + '-source.json')
        report_path = self.lab / (name + '-report.json')
        request_path = self.lab / (name + '-analysis.json')
        document(request_path, dict(stage=name, seeds=self.banks[name], ids=ids, base=self.base,
            candidates=str(self.candidates), priority=str(self.priority), native=str(output),
            source=str(source_path), sourceSha256=sha(source_path), output=str(report_path)))
        binding = report_path.with_suffix('.binding.json')
        if not binding.exists():
            # Audit belongs to the same original stage allowance, including on restart.
            deadline = read(output / 'started.json')['deadline']
            self.execute(name + '-analysis', [sys.executable, '-B', str(self.scripts / 'joint_economy_experiment.py'),
                '--analyze', str(request_path)], STAGES[name]['maximumSeconds'], deadline)
        require(read(binding) == {'requestSha256': sha(request_path), 'reportSha256': sha(report_path)},
                'A completed stage report changed after its exact-input audit.')
        return read(report_path)

    def run(self):
        screen = self.stage('screen', self.ids)
        selected = screen['selected']
        require(len(selected) == len(set(selected)) == 3 and set(selected) <= set(self.ids),
                'Screening selects exactly three frozen candidates.')
        document(self.lab / 'selected.json', {'ids': selected, 'screenReportSha256': sha(self.lab / 'screen-report.json')})
        selection = self.stage('selection', selected)
        require(type(selection['mayConfirm']) is bool and selection['winner'] in selected,
                'Selection must name one screened winner and a Boolean gate.')
        if not selection['mayConfirm']:
            return self.finish('joint-economy-selection-gate-failed', policyPromotion=False)
        winner = selection['winner']
        manifest, _ = search.load_candidates(self.candidates)
        candidate = next(row for row in manifest['candidates'] if row['id'] == winner)
        finalist = {'id': winner, 'model': str(self.candidates / candidate['file']), 'modelSha256': candidate['sha256'],
                    'selectionReportSha256': sha(self.lab / 'selection-report.json')}
        document(self.lab / 'finalist.json', finalist)
        confirmation = self.stage('confirmation', [winner])
        require(type(confirmation['passed']) is bool, 'Confirmation has one fixed Boolean qualification gate.')
        return self.finish('qualified-for-integration' if confirmation['passed']
            else 'joint-economy-independent-qualification-failed', finalist=finalist,
            confirmationPassed=confirmation['passed'], policyPromotion=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab', type=Path)
    parser.add_argument('--protocol', type=Path)
    parser.add_argument('--status', action='store_true')
    parser.add_argument('--analyze', type=Path)
    args = parser.parse_args()
    if args.analyze:
        require(args.lab is None and args.protocol is None and not args.status, 'Analysis takes only its frozen request.')
        analyze_request(args.analyze)
        return
    require(args.lab is not None and args.protocol is not None, 'The window requires --lab and --protocol.')
    if args.status:
        print(json.dumps(read(args.lab / 'window-status.json'), indent=2)); return
    def canceled(*_):
        raise KeyboardInterrupt('Joint economy search canceled.')
    signal.signal(signal.SIGTERM, canceled)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        JointEconomyWindow(args.lab, args.protocol).run()


if __name__ == '__main__': main()
