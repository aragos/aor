"""One bounded continuation crossover diagnostic; no policy training or automatic progression.

Complete compressed records are verified before analysis. The analyzer reads training roots,
freezes its fixed ridge coefficients, then decodes validation roots and applies coverage guards.
"""
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
from card_advantage_priority_experiment import CANDIDATE_SHA256 as PRIORITY_SHA256

require = experiment.require
read = experiment.read
sha = experiment.sha
document = experiment.document
PLAN = {'kind': 'own-continuation-crossover-1', 'replicates': 4}
COVERAGE = {'trainNontrivial': 96, 'validationNontrivial': 96, 'totalNontrivial': 192, 'totalEpoch3': 48}
ANALYSIS = {'maximumSeconds': 600, 'bootstrapSamples': 10000, 'bootstrapSeed': 391107,
            'ridgeLambda': 1.0, 'featureCount': 8, 'featureMember': 0, 'minimumRms': .02}
CLASSIFICATIONS = {'insufficient-coverage', 'no-persistent-interaction',
    'persistent-not-predicted-by-fixed-probe', 'persistent-and-predicted-by-fixed-probe'}


def check_analysis(report, expected, output):
    """Bind the complete fixed analysis without selecting another model or inspecting raw returns."""
    require(report['inputs'] == expected and report['diagnosticOnly'] is True
            and report['policyPromotion'] is False and report['classification'] in CLASSIFICATIONS,
            'Analysis must retain its exact completed inputs and diagnostic-only interpretation.')
    identity = read(output / 'identity.json')
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
    require(report['identitySha256'] == digest, 'The completed analysis identity changed.')
    if report['ridgeSha256'] is not None:
        require(sha(output / 'ridge.json') == report['ridgeSha256'], 'The frozen ridge coefficients changed.')
    flags = [report['coverage']['passed'], report['persistence']['gatePassed'], report['observableProbe']['gatePassed']]
    require(all(type(flag) is bool for flag in flags), 'Analysis eligibility and diagnostic gates must be Boolean.')
    covered, persistent, observable = flags
    classification = ('insufficient-coverage' if not covered else 'no-persistent-interaction' if not persistent
                      else 'persistent-and-predicted-by-fixed-probe' if observable
                      else 'persistent-not-predicted-by-fixed-probe')
    require(report['classification'] == classification
            and (report['ridgeSha256'] is not None or not covered),
            'The diagnostic classification must match its frozen coefficients and predeclared gates.')


class ContinuationWindow(experiment.Window):
    def __init__(self, lab, protocol_path):
        super().__init__(lab, protocol_path)
        self.preflight = read(self.lab / 'preflight.json')
        require(self.protocol.get('kind') == 'card-continuation-window' and self.protocol.get('schemaVersion') == 1,
                'Unsupported continuation diagnostic protocol.')
        draft = read(self.pin(self.protocol['recipeDraft']))
        for key in ('configuration', 'collection', 'coverage', 'analysis', 'resources', 'seedReservation', 'originRequests'):
            require(self.protocol[key] == draft[key], 'The prospective continuation recipe changed: ' + key)
        require(self.protocol['collection'] == {'maximumSeconds': 5400, 'batchSize': 8, 'plan': PLAN}
                and self.protocol['coverage'] == COVERAGE and self.protocol['analysis'] == ANALYSIS,
                'Continuation collection, eligibility and analysis settings must remain fixed.')
        start = datetime.fromisoformat(self.clock['startedAt'].replace('Z', '+00:00')).timestamp()
        require(self.deadline - start == 7200 and self.clock['originalAllowanceSeconds'] == 7200
                and self.clock['renewOnResume'] is False, 'The diagnostic has one original nonrenewable two-hour clock.')
        require(set(self.protocol['originRequests']) == {'continuation'} and set(self.banks) == {'continuation'},
                'This diagnostic has one prospective root bank and no qualification stages.')
        self.requests = read(self.lab / self.protocol['originRequests']['continuation']['file'])
        require(len(self.requests) == 256 and all(
            row['index'] == i and row['seat'] == i % 4 and row['epoch'] == (i // 4) % 3 + 1
            and row['ordinal'] == (i // 12) % 8 + 1 and row['cohort'] == 'generic'
            and row['split'] == ('train' if i < 128 else 'validation')
            and row['opponentUsesCandidate'] == [False] * 4 for i, row in enumerate(self.requests)),
                'Requests must preserve their fixed global-index roles, epochs, ordinals and split.')
        require(self.banks['continuation'] == [row['seed'] for row in self.requests]
                and len(set(self.banks['continuation'])) == 256
                and all(type(seed) is int for seed in self.banks['continuation']),
                'The diagnostic requires exactly its 256 distinct integer world seeds.')
        self.verify_prerequisites()

    def pin(self, entry):
        path = (self.lab / entry['file']).resolve(strict=True)
        require(sha(path) == entry['sha256'] and self.preflight['pins'].get(str(path)) == entry['sha256'],
                'Every prerequisite must retain its preflight-bound bytes: ' + str(path))
        return path

    def verify_prerequisites(self):
        prerequisites = self.protocol['prerequisites']
        old_preflight = self.pin(prerequisites['breadthPreflight'])
        old_result = self.pin(prerequisites['breadthResult'])
        result = read(old_result)
        require(read(old_preflight)['readyForCollection'] is True
                and result['preflightSha256'] == sha(old_preflight)
                and old_result == old_preflight.parent / 'window-result.json'
                and result['disposition'] == 'breadth-local-gate-failed' and result['browserActivated'] is False,
                'Preserve the completed failed breadth diagnostic and its original preflight.')
        feature_report = self.pin(prerequisites['breadthFitReport'])
        self.feature_model = self.pin(prerequisites['featureModel'])
        self.priority = self.pin(prerequisites['priorityCandidate'])
        require(feature_report == old_preflight.parent / 'breadth-fit/finished.json'
                and sha(self.feature_model) == read(feature_report)['models']['full']['sha256']
                and sha(self.priority) == PRIORITY_SHA256,
                'Keep the exact frozen breadth feature model and original priority64 continuation.')
        decay = read(self.pin(prerequisites['decayReport']))
        require(decay['phase'] == 'complete' and decay['protocolSha256'] == sha(self.pin(prerequisites['decayProtocol']))
                and decay['allPinsUnchanged'] is True and decay['qualified'] is False
                and decay['activated'] is False and decay['newGameWorlds'] == 0,
                'The completed no-decay probe is diagnostic evidence, never a promoted playing policy.')
        proof = read(self.pin(prerequisites['opsProfile']))
        require(proof['passed'] is True and proof['allPinsUnchanged'] is True and proof['worlds'] == 8
                and proof['newWorlds'] == 0 and proof['compressedRecordsExactlyEqual'] == 8
                and proof['legacyRecordsExactlyEqualExceptSignature'] == 2
                and proof['legacySchema1RecordsExactlyEqualExceptSignature'] == 2
                and proof['referenceRootPairsExactlyEqual'] == 8
                and proof['protocolSha256'] == sha(self.pin(prerequisites['opsProtocol'])),
                'Continuation collection needs its complete consumed-world parity proof.')
        estimate = proof['estimatedCollectionSecondsWithGuard']
        batch_seconds = proof['batchWallSeconds']
        require(proof['admitted'] is True
                and all(type(value) in (int, float) and math.isfinite(value) and value > 0
                        for value in (estimate, batch_seconds))
                and estimate <= 5400
                and math.isclose(estimate, batch_seconds * (256 / 8) / 6 * 1.25, rel_tol=1e-12),
                'The consumed profile must admit the guarded collection estimate within 5400 seconds.')
        require(proof['runtimeJars'] and proof['runtimeJars'] == {p.name: sha(p) for p in self.runtime.glob('*.jar')}
                and proof['sourceHashes'] and all(sha(p) == digest and self.preflight['pins'].get(str(Path(p).resolve())) == digest
                                                for p, digest in proof['sourceHashes'].items()),
                'Continuation native runtime and proof sources must retain their measured bytes.')
        scripts = ('card_continuation_experiment.py', 'card_continuation_analysis.py', 'card_advantage_experiment.py',
                   'card_advantage_priority_experiment.py', 'card-advantage-run.py', 'card_advantage.py',
                   'card_advantage_data.py', 'card_policy.py', 'purchase_policy.py')
        paths = [*(self.scripts / name for name in scripts), *self.runtime.glob('*.jar'),
                 Path(self.base['baseline']), Path(self.base['frozenPurchasePolicy'])]
        require(all(self.preflight['pins'].get(str(path.resolve())) == sha(path) for path in paths),
                'Every executed helper, native jar and frozen playing policy must be pinned.')

    def collect(self):
        source = dict(self.base, schemaVersion=3, continuation=str(self.priority), collection=PLAN, origins=self.requests)
        output = self.native('collect', 'continuation', source, 5400)
        manifest = read(output / 'collection.json')
        require(manifest['kind'] == 'card-continuation-diagnostic' and manifest['schemaVersion'] == 3
                and manifest['collection'] == PLAN and manifest['backbonePolicy'] == 'F'
                and manifest['opponentPolicy'] == 'F',
                'The prefix and rival policies must remain F, independent of the alternative own continuation.')
        return output

    def analyze(self, source):
        output = self.lab / 'analysis'
        expected = dict(source=str(source), completionSha256=sha(source / 'finished.json'),
                        featureModel=str(self.feature_model), featureModelSha256=sha(self.feature_model),
                        analysisSha256=sha(self.scripts / 'card_continuation_analysis.py'))
        document(self.lab / 'analysis-inputs.json', expected)
        clock_path, binding = self.lab / 'analysis-clock.json', self.lab / 'analysis-clock-binding.json'
        progressed = output.exists() or (self.lab / 'continuation-analysis.log').exists()
        if not clock_path.exists():
            require(not progressed and not binding.exists(), 'An analysis cannot renew a missing original clock after progress.')
            now = time.time()
            document(clock_path, dict(startedAt=now, deadline=min(self.deadline, now + 600),
                                     inputsSha256=sha(self.lab / 'analysis-inputs.json')))
        clock = read(clock_path)
        require(type(clock['startedAt']) in (int, float) and math.isfinite(clock['startedAt'])
                and 0 < clock['startedAt'] <= time.time() + 5
                and clock['deadline'] == min(self.deadline, clock['startedAt'] + 600)
                and clock['inputsSha256'] == sha(self.lab / 'analysis-inputs.json'),
                'The analysis must retain its original allowance and exact completed inputs.')
        require(binding.exists() or not progressed, 'An existing analysis lost its original clock binding.')
        document(binding, {'sha256': sha(clock_path)})
        report_path = output / 'report.json'
        if not report_path.exists():
            command = [sys.executable, '-B', str(self.scripts / 'card_continuation_analysis.py'),
                       '--source', str(source), '--feature-model', str(self.feature_model), '--output', str(output)]
            if output.exists():
                require((output / 'identity.json').exists(), 'Partial analysis requires its original identity before resume.')
                command.append('--resume')
            self.execute('continuation-analysis', command, 600, clock['deadline'])
        report = read(report_path)
        check_analysis(report, expected, output)
        document(self.lab / 'analysis-binding.json', {'reportSha256': sha(report_path), 'inputsSha256': sha(self.lab / 'analysis-inputs.json')})
        return report

    def run(self):
        report = self.analyze(self.collect())
        return self.finish('continuation-diagnostic-complete', classification=report['classification'],
                           coveragePassed=report['coverage']['passed'], diagnosticOnly=True,
                           policyPromotion=False, analysisSha256=sha(self.lab / 'analysis/report.json'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--status', action='store_true')
    args = parser.parse_args()
    if args.status:
        print(json.dumps(read(args.lab / 'window-status.json'), indent=2)); return
    def cancel(*_):
        raise KeyboardInterrupt('Continuation diagnostic canceled.')
    signal.signal(signal.SIGTERM, cancel)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        ContinuationWindow(args.lab, args.protocol).run()


if __name__ == '__main__':
    main()
