"""Bounded policy-seed coupling measurement on consumed roots; never train or promote a policy."""
import argparse
from datetime import datetime
import fcntl
import hashlib
import json
import math
from pathlib import Path
import signal
import sys
import time

sys.dont_write_bytecode = True
import card_advantage_experiment as experiment

require, read, sha, document = experiment.require, experiment.read, experiment.sha, experiment.document
PLAN = {'kind': 'policy-seed-coupling-1', 'replicates': 8, 'schedules': ['L', 'G', 'S']}
COVERAGE = {'minimumNontrivial': 48, 'minimumEpoch3': 12}
ANALYSIS = {'maximumSeconds': 600, 'bootstrapSamples': 10000, 'bootstrapSeed': 381107,
            'maximumVarianceRatio': .75, 'upper95Below': 1.0}


def check_analysis(report, expected, output):
    require(report['inputs'] == expected and report['diagnosticOnly'] is True
            and report['policyPromotion'] is False,
            'Coupling analysis must retain its completed inputs and diagnostic-only interpretation.')
    identity = read(output / 'identity.json')
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
    require(report['identitySha256'] == digest, 'The completed coupling analysis identity changed.')
    require(type(report['coverage']['passed']) is bool and type(report['primary']['gatePassed']) is bool
            and (report['coverage']['passed'] or not report['primary']['gatePassed']),
            'Coupling gates require Boolean flags and sufficient predeclared coverage.')


class CouplingWindow(experiment.Window):
    def __init__(self, lab, protocol_path):
        super().__init__(lab, protocol_path)
        self.preflight = read(self.lab / 'preflight.json')
        require(self.protocol['kind'] == 'card-policy-coupling-window' and self.protocol['schemaVersion'] == 1,
                'Unsupported policy-coupling experiment.')
        draft = read(self.pin(self.protocol['recipeDraft']))
        for name in ('configuration', 'collection', 'coverage', 'analysis', 'resources', 'seedReservation', 'originRequests'):
            require(self.protocol[name] == draft[name], 'The fixed coupling recipe changed: ' + name)
        require(self.protocol['collection'] == {'maximumSeconds': 2700, 'batchSize': 8, 'plan': PLAN}
                and self.protocol['coverage'] == COVERAGE and self.protocol['analysis'] == ANALYSIS,
                'Keep the fixed 64-root, eight-stream, three-schedule experiment and analysis.')
        started = datetime.fromisoformat(self.clock['startedAt'].replace('Z', '+00:00')).timestamp()
        require(self.deadline - started == 7200 and self.clock['originalAllowanceSeconds'] == 7200
                and self.clock['renewOnResume'] is False, 'Coupling has one original two-hour clock.')
        require(set(self.protocol['originRequests']) == set(self.banks) == {'coupling'},
                'Coupling measures one fixed consumed-root bank, with no qualification stages.')
        self.requests = read(self.lab / self.protocol['originRequests']['coupling']['file'])
        self.verify_prerequisites()

    def pin(self, entry):
        path = (self.lab / entry['file']).resolve(strict=True)
        require(sha(path) == entry['sha256'] == self.preflight['pins'].get(str(path)),
                'A prerequisite changed from its preflight-bound bytes: ' + str(path))
        return path

    def verify_prerequisites(self):
        prior = self.protocol['prerequisites']
        reference_manifest = self.pin(prior['continuationManifest'])
        self.reference = reference_manifest.parent
        manifest = read(reference_manifest)
        completion = self.pin(prior['continuationCompletion'])
        require(completion == self.reference / 'finished.json'
                and experiment.completed_native(self.reference, Path(manifest['source'])),
                'The original continuation reference must be fully complete and hash-bound.')
        require(len(self.requests) == 64 and self.requests == manifest['origins'][:64]
                and self.banks['coupling'] == [row['seed'] for row in self.requests],
                'Use exactly original consumed requests zero through 63, without replacements.')
        for key, filename in (('baseline', 'baseline.json'), ('frozenPurchasePolicy', 'purchase.json')):
            require(manifest[key]['sha256'] == sha(self.lab / 'inputs' / filename),
                    'Every schedule must retain the original F and purchase32 policies.')
        old_preflight, old_result = self.pin(prior['continuationPreflight']), self.pin(prior['continuationResult'])
        old_report = self.pin(prior['continuationReport'])
        result = read(old_result)
        require(result['preflightSha256'] == sha(old_preflight)
                and result['analysisSha256'] == sha(old_report)
                and result['disposition'] == 'continuation-diagnostic-complete'
                and result['diagnosticOnly'] is True and result['browserActivated'] is False
                and result['policyPromotion'] is False, 'Preserve the completed continuation diagnostic.')
        linear_report, linear_done = self.pin(prior['linearReport']), self.pin(prior['linearCompletion'])
        linear = read(linear_report)
        require(linear_done == linear_report.parent / 'finished.json'
                and read(linear_done)['files']['report.json'] == sha(linear_report)
                and linear['localGatePassed'] is False and linear['diagnosticOnly'] is True
                and linear['policyPromotion'] is False and linear['validationDecoded'] is False,
                'The completed linear diagnostic is a failed local result, not an activated policy.')
        reservation = read(self.lab / self.protocol['seedReservation']['file'])
        chance = lambda seed, replicate: (seed << 4) ^ 0x37a51b20d ^ replicate
        reused = [chance(row['seed'], r) for row in self.requests for r in range(4)]
        fresh = [chance(row['seed'], r) for row in self.requests for r in range(4, 8)]
        require(reservation['reusedChanceStreams'] == reused and reservation['newChanceStreams'] == fresh
                and len(set(reused + fresh)) == 512 and reservation['newWorlds'] == 0
                and reservation['historicalOverlaps'] == [] and reservation['intentionalReuse'] is True,
                'Only the declared old streams may be reused; new streams four through seven must be reserved.')
        helpers = ('card_policy_coupling_experiment.py', 'card_policy_coupling_analysis.py',
                   'card_advantage_experiment.py', 'card-advantage-run.py', 'card_advantage.py',
                   'card_advantage_data.py', 'card_continuation_analysis.py', 'card_policy.py', 'purchase_policy.py')
        paths = [*(self.scripts / name for name in helpers), *self.runtime.glob('*.jar'),
                 self.lab / 'inputs/baseline.json', self.lab / 'inputs/purchase.json']
        require(all(self.preflight['pins'].get(str(path.resolve())) == sha(path) for path in paths),
                'All native jars, Python helpers and frozen playing policies must remain pinned.')

    def collect(self):
        source = dict(self.base, kind='card-policy-coupling', collection=PLAN, origins=self.requests)
        output = self.native('coupling', 'coupling', source, 2700)
        manifest = read(output / 'collection.json')
        require(manifest['kind'] == 'card-policy-coupling' and manifest['schemaVersion'] == 1
                and manifest['collection'] == PLAN and manifest['replicates'] == 8
                and manifest['maxTailsPerOrigin'] == 48 and manifest['continuation'] is None
                and manifest['backbonePolicy'] == manifest['opponentPolicy'] == manifest['tailPolicy'] == 'F',
                'Only the policy randomness schedule may differ between paired tails.')
        return output

    def analyze(self, source):
        output = self.lab / 'analysis'
        expected = dict(source=str(source), completionSha256=sha(source / 'finished.json'),
            reference=str(self.reference), referenceCompletionSha256=sha(self.reference / 'finished.json'),
            analysisSha256=sha(self.scripts / 'card_policy_coupling_analysis.py'))
        document(self.lab / 'analysis-inputs.json', expected)
        clock_path, binding = self.lab / 'analysis-clock.json', self.lab / 'analysis-clock-binding.json'
        progressed = output.exists() or (self.lab / 'coupling-analysis.log').exists()
        if not clock_path.exists():
            require(not progressed and not binding.exists(), 'Analysis cannot renew a missing clock after progress.')
            now = time.time()
            document(clock_path, dict(startedAt=now, deadline=min(self.deadline, now + 600),
                                     inputsSha256=sha(self.lab / 'analysis-inputs.json')))
        clock = read(clock_path)
        require(type(clock['startedAt']) in (int, float) and math.isfinite(clock['startedAt'])
                and 0 < clock['startedAt'] <= time.time() + 5
                and clock['deadline'] == min(self.deadline, clock['startedAt'] + 600)
                and clock['inputsSha256'] == sha(self.lab / 'analysis-inputs.json'),
                'Analysis must retain its original 600-second allowance and exact inputs.')
        require(binding.exists() or not progressed, 'Existing analysis lost its clock binding.')
        document(binding, {'sha256': sha(clock_path)})
        report_path = output / 'report.json'
        if not report_path.exists():
            command = [sys.executable, '-B', str(self.scripts / 'card_policy_coupling_analysis.py'),
                       '--source', str(source), '--reference', str(self.reference), '--output', str(output)]
            if output.exists():
                require((output / 'identity.json').exists(), 'Partial analysis needs its original identity to resume.')
                command.append('--resume')
            self.execute('coupling-analysis', command, 600, clock['deadline'])
        report = read(report_path)
        check_analysis(report, expected, output)
        document(self.lab / 'analysis-binding.json', dict(reportSha256=sha(report_path),
                                                        inputsSha256=sha(self.lab / 'analysis-inputs.json')))
        return report

    def run(self):
        report = self.analyze(self.collect())
        return self.finish('policy-coupling-diagnostic-complete', diagnosticOnly=True, policyPromotion=False,
            coveragePassed=report['coverage']['passed'], varianceReductionPassed=report['primary']['gatePassed'],
            analysisSha256=sha(self.lab / 'analysis/report.json'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--status', action='store_true')
    args = parser.parse_args()
    if args.status:
        print(json.dumps(read(args.lab / 'window-status.json'), indent=2)); return
    def canceled(*_):
        raise KeyboardInterrupt('Policy-coupling diagnostic canceled.')
    signal.signal(signal.SIGTERM, canceled)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        CouplingWindow(args.lab, args.protocol).run()


if __name__ == '__main__':
    main()
