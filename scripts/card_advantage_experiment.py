"""Run the predeclared two-cycle experiment with local programs and fixed stop gates.

The lab owns immutable window/seed/preflight documents. This driver calls the existing
native supervisor and numerical learner; it never changes a budget, fits a partial
collection, activates a browser policy, or adds confirmation samples after a result.
"""
import argparse
from datetime import datetime
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import time

import card_advantage_evaluation as evaluation


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_bytes())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def document(path, value, immutable=True):
    path = Path(path)
    content = (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n').encode()
    if immutable and path.exists():
        require(path.read_bytes() == content, 'Frozen experiment document changed: ' + str(path))
        return
    temporary = path.with_suffix(path.suffix + '.writing')
    temporary.write_bytes(content)
    temporary.replace(path)


def source_base(lab, protocol):
    return dict(schemaVersion=1, configuration=protocol['configuration'],
                baseline=str(lab / 'inputs/baseline.json'),
                frozenPurchasePolicy=str(lab / 'inputs/purchase.json'))


def evaluation_source(base, stage, seeds, models):
    _, arms, all_roles = evaluation.STAGES[stage]
    policies = {'baseline': {'kind': 'baseline'}}
    policies.update({name: dict(kind='frequency' if name == 'frequency' else 'advantage',
                               path=str(path)) for name, path in models.items()})
    jobs = []
    for world, seed in enumerate(seeds):
        for seat in (range(4) if all_roles else (world % 4,)):
            for arm in arms:
                rivals = ['candidate' if stage == 'rival-robustness' else 'baseline'] * 4
                rivals[seat] = 'baseline'  # Unused; controlled role is always explicit.
                jobs.append(dict(index=len(jobs), seed=seed, seat=seat, arm=arm,
                                 policy=arm, rivals=rivals))
    return dict(base, policies=policies, jobs=jobs)


def coverage(directory, minimums):
    """Check complete hashes, then read only root observations and coverage metadata."""
    directory = Path(directory)
    state = read(directory / 'state.json')
    manifest = read(directory / 'collection.json')
    finished = read(directory / 'finished.json')
    requests = manifest['origins']
    require(state['phase'] == 'complete' and state['signature'] == manifest['signature']
            == finished['signature'] and finished['origins'] == len(requests)
            == len(finished['originSha256']), 'Coverage requires the complete declared collection.')
    paths = [directory / ('origin-' + str(index) + '.json.gz') for index in range(len(requests))]
    require(all(sha(path) == expected for path, expected in zip(paths, finished['originSha256'])),
            'Every complete origin hash must validate before coverage is inspected.')
    counts = {split: dict(origins=0, epoch3=0, printedWordLeader=0) for split in minimums}
    skipped = []
    for request, path in zip(requests, paths):
        with gzip.open(path, 'rb') as stream:
            blob = stream.read(64 * 1024 * 1024 + 1)
        require(len(blob) <= 64 * 1024 * 1024, 'Expanded origin exceeds the artifact limit.')
        row = json.loads(blob)
        require(row['request'] == request and row['signature'] == manifest['signature'],
                'Coverage origin must preserve its prospective request and signature.')
        if row['status'] == 'skipped':
            skipped.append(request['index'])
            continue
        require(row['status'] == 'collected', 'Partial origins cannot satisfy coverage.')
        root = next(group for group in row['groups'] if group['kind'] == 'root')
        observation = row['observations'][root['observationSha256']]
        counts[request['split']]['origins'] += 1
        counts[request['split']]['epoch3'] += int(root['epoch'] == 3)
        owns_word = observation['stateFeatures'].get('role.0.advance.PRINTED_WORD', 0) > 0
        leader = any(action['features'].get('cardKind.LEADER', 0) > 0
                     for action in observation['actions'])
        counts[request['split']]['printedWordLeader'] += int(owns_word and leader)
    return dict(counts=counts, skippedOrigins=skipped,
                passed=all(counts[split][key] >= minimum for split, requirements in minimums.items()
                           for key, minimum in requirements.items()),
                labelsInspected=False, completionSha256=sha(directory / 'finished.json'))


class StageTimeout(RuntimeError):
    pass


class CollectionNotAdmitted(RuntimeError):
    pass


def completed_native(directory, source):
    """Completed work remains reusable after its original execution allowance expires."""
    directory = Path(directory)
    if not (directory / 'finished.json').exists():
        return False
    manifest = read(directory / 'collection.json')
    finished = read(directory / 'finished.json')
    state = read(directory / 'state.json')
    source = Path(source)
    specification = read(source)
    require(manifest['sourceSha256'] == sha(source)
            and state['phase'] == 'complete'
            and state['signature'] == manifest['signature'] == finished['signature'],
            'Completed stage source or completion identity differs.')
    require(manifest['configuration'] == specification['configuration']
            and (directory / 'source.json').read_bytes() == source.read_bytes(),
            'Completed stage configuration or exact source snapshot differs.')

    def check_model(identity, text, snapshot):
        expected = (source.parent / text).resolve(strict=True)
        require(identity['path'] == str(expected) and identity['sha256'] == sha(expected)
                and (directory / snapshot).read_bytes() == expected.read_bytes(),
                'Completed model identity/snapshot differs from its planned source: ' + snapshot)

    check_model(manifest['baseline'], specification['baseline'], 'baseline.json')
    check_model(manifest['frozenPurchasePolicy'], specification['frozenPurchasePolicy'],
                'frozen-purchase-policy.json')
    if manifest['kind'] == 'card-advantage-collect':
        require(manifest['origins'] == specification['origins'],
                'Completed root requests differ from the prospective source.')
        if specification['continuation'] is None:
            require(manifest['continuation'] is None, 'An F continuation cannot adopt another policy.')
        else:
            check_model(manifest['continuation'], specification['continuation'], 'continuation.json')
        entries = [('origin-' + str(index) + '.json.gz', expected)
                   for index, expected in enumerate(finished['originSha256'])]
        require(finished['origins'] == len(manifest['origins']) == len(entries),
                'Completed collection must contain all declared origins.')
    else:
        require(manifest['jobs'] == specification['jobs']
                and set(manifest['policies']) == set(specification['policies']),
                'Completed evaluation jobs or policy registry differ from the planned source.')
        for name, policy in specification['policies'].items():
            identity = manifest['policies'][name]
            require(identity['kind'] == policy['kind'], 'A completed policy changed its planned kind.')
            if policy['kind'] == 'baseline':
                require(identity['path'] is None and identity['sha256'] is None,
                        'A baseline registry entry cannot load another policy.')
            else:
                check_model(identity, policy['path'], 'policy-' + name + '.json')
        entries = [(entry['file'], entry['sha256']) for entry in finished['files']]
        require(finished['games'] == len(manifest['jobs']) == len(entries),
                'Completed evaluation must contain all declared jobs.')
    require(all(Path(name).name == name and sha(directory / name) == expected
                for name, expected in entries), 'Completed stage record hashes differ.')
    identities = [manifest['baseline'], manifest['frozenPurchasePolicy']]
    if manifest.get('continuation'):
        identities.append(manifest['continuation'])
    identities.extend(manifest.get('policies', {}).values())
    require(all(not item.get('path') or sha(item['path']) == item['sha256']
                for item in identities), 'Completed stage policy inputs changed.')
    return True


def fit_config(protocol):
    """Normalize the optional capacity setting while preserving the original 64-unit recipe."""
    settings = protocol['fit']
    return dict(learningRate=settings['learningRate'], weightDecay=settings['weightDecay'],
                maxGradientNorm=settings['gradientNormLimit'],
                originsPerBatch=settings['wholeOriginsPerBatch'], epochs=settings['epochs'],
                checkpoints=settings['checkpoints'], seeds=settings['initializationSeeds'],
                threads=settings['threads'], maxSeconds=settings['maximumSecondsPerCycle'],
                activeHidden=settings.get('activeHidden', 64))


def completed_fit(fit, collection, protocol, scripts):
    """A passing local gate belongs to this exact complete collection and learner."""
    report = read(fit / 'finished.json')
    identity = read(fit / 'identity.json')
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':'),
                                      allow_nan=False).encode()).hexdigest()
    require(report['identitySha256'] == digest, 'Completed fit identity changed after validation.')
    provenance = identity['collection']
    manifest = read(collection / 'collection.json')
    require(provenance['source'] == str(collection.resolve())
            and provenance['signature'] == manifest['signature'],
            'A completed fit belongs to a different collection.')
    expected_files = {'collection.json', 'finished.json', 'source.json', 'baseline.json',
                      'frozen-purchase-policy.json'}
    expected_files.update('origin-' + str(index) + '.json.gz'
                          for index in range(len(manifest['origins'])))
    if manifest['continuation'] is not None:
        expected_files.add('continuation.json')
    require(set(provenance['fileHashes']) == expected_files
            and all(sha(collection / name) == expected
                    for name, expected in provenance['fileHashes'].items()),
            'Completed fit must bind every file in its exact complete collection.')
    learner_files = ['card_advantage_train.py', 'card_advantage.py',
                     'card_advantage_data.py', 'purchase_policy.py']
    require(identity['sourceHashes'] == {str((scripts / name).resolve()): sha(scripts / name)
                                        for name in learner_files},
            'Completed fit used a different numerical implementation.')
    actual_config = dict(identity['config'], activeHidden=identity['config'].get('activeHidden', 64))
    require(actual_config == fit_config(protocol), 'Completed fit used a different frozen recipe.')
    require(all(sha(fit / (name + '.json')) == report['models'][name]['sha256']
                for name in ('full', 'action-priority'))
            and sha(fit / 'frequency-control.json') == report['frequencyControlSha256'],
            'Selected model/control changed after local validation.')
    return report


class Window:
    def __init__(self, lab, protocol_path):
        self.lab = lab.resolve()
        self.scripts = Path(__file__).resolve().parent
        self.protocol = read(protocol_path)
        self.clock = read(self.lab / self.protocol['clock']['file'])
        self.deadline = datetime.fromisoformat(self.clock['deadline'].replace('Z', '+00:00')).timestamp()
        self.admission = datetime.fromisoformat(
            self.clock['collectionAdmissionDeadline'].replace('Z', '+00:00')).timestamp()
        self.collection_cutoff = datetime.fromisoformat(
            self.clock['latestNewCollection'].replace('Z', '+00:00')).timestamp()
        preflight = read(self.lab / 'preflight.json')
        require(preflight['readyForCollection'] is True
                and preflight['windowSha256'] == sha(protocol_path),
                'This exact experiment requires passing preflight evidence before any fresh game.')
        for path, expected in preflight['pins'].items():
            require(sha(path) == expected, 'Frozen preflight/runtime input changed: ' + path)
        for entry in [self.protocol['clock'], self.protocol['seedReservation'],
                      *self.protocol['originRequests'].values()]:
            require(sha(self.lab / entry['file']) == entry['sha256'], 'Frozen experiment input changed.')
        reservation = read(self.lab / self.protocol['seedReservation']['file'])
        require(reservation['passed'] and not reservation['overlaps'], 'Seed reservations did not pass.')
        self.banks = {bank['name']: bank['seeds'] for bank in reservation['banks']}
        self.base = source_base(self.lab, self.protocol)
        self.preflight_hash = sha(self.lab / 'preflight.json')
        self.runtime = self.scripts.parent / 'lib'

    def status(self, stage, **details):
        value = {**details, 'stage': stage, 'updatedAt': time.time(), 'deadline': self.deadline,
                 'preflightSha256': self.preflight_hash}
        document(self.lab / 'window-status.json', value, immutable=False)
        print(json.dumps(value), flush=True)

    def execute(self, name, command, seconds, original_deadline=None):
        deadline = min(self.deadline, original_deadline or time.time() + seconds)
        if time.time() >= deadline:
            raise StageTimeout('The original execution allowance has elapsed: ' + name)
        self.status(name, command=command)
        with (self.lab / (name + '.log')).open('ab') as log:
            child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log,
                                     stderr=subprocess.STDOUT, start_new_session=True,
                                     env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1',
                                          'OMP_NUM_THREADS': '1', 'OPENBLAS_NUM_THREADS': '1'})
            try:
                last_resource_check = 0.
                while child.poll() is None:
                    if time.time() >= deadline:
                        raise StageTimeout('Original stage allowance elapsed: ' + name)
                    if time.monotonic() - last_resource_check >= 5:
                        resources = self.protocol['resources']
                        require(shutil.disk_usage(self.lab).free >= resources['minimumDiskFreeBytes'],
                                'Experiment stopped below its minimum free disk allowance.')
                        size = sum(path.stat().st_size for path in self.lab.rglob('*') if path.is_file())
                        require(size <= resources['maximumNewDataBytes'],
                                'Experiment stopped at its data cap; no records may be dropped.')
                        last_resource_check = time.monotonic()
                    time.sleep(1)
                if child.returncode != 0:
                    if time.time() >= deadline - 2:
                        raise StageTimeout('Original stage allowance elapsed: ' + name)
                    raise RuntimeError('Stage failed; inspect ' + name + '.log before proceeding.')
            finally:
                if child.poll() is None:
                    os.killpg(child.pid, signal.SIGTERM)
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait(timeout=5)

    def native(self, mode, name, source, seconds):
        section = 'evaluation' if mode == 'evaluate' else 'collection'
        batch_size = self.protocol.get(section, {}).get('batchSize', 1)
        require(type(batch_size) is int and 1 <= batch_size <= 64,
                'Native batch size must be an integer from 1 through 64.')
        path = self.lab / (name + '-source.json')
        document(path, source)
        output = self.lab / name
        if completed_native(output, path):
            return output
        command = [sys.executable, '-B', str(self.scripts / 'card-advantage-run.py'),
                   '--mode', mode, '--source', str(path), '--output', str(output),
                   '--runtime', str(self.runtime), '--workers', '6', '--hours', str(seconds / 3600),
                   '--deadline-epoch', str(self.deadline), '--data-root', str(self.lab)]
        if batch_size != 1:
            command.extend(['--batch-size', str(batch_size)])
        clock = read(output / 'started.json') if (output / 'started.json').exists() else None
        # The native supervisor owns exact stage timing; give shutdown a small outer grace.
        deadline = clock['deadline'] + 15 if clock else time.time() + seconds + 15
        try:
            self.execute(name, command, seconds + 15, deadline)
        except RuntimeError:
            clock = read(output / 'started.json') if (output / 'started.json').exists() else None
            if clock and time.time() >= clock['deadline']:
                raise StageTimeout('Native stage reached its original allowance: ' + name)
            raise
        require(completed_native(output, path), 'A successful native stage must be completely bound.')
        return output

    def cycle(self, number, continuation=None):
        name = 'cycle-' + str(number)
        seconds = self.protocol['collection']['secondsByCycle'][number - 1]
        output = self.lab / name
        if not (output / 'started.json').exists():
            if not (time.time() < self.collection_cutoff and time.time() + seconds
                    + self.protocol['fit']['maximumSecondsPerCycle'] <= self.admission):
                raise CollectionNotAdmitted('A new collection cannot consume the final three hours.')
        requests = read(self.lab / self.protocol['originRequests'][name]['file'])
        source = dict(self.base, continuation=str(continuation) if continuation else None, origins=requests)
        self.native('collect', name, source, seconds)
        check = coverage(output, self.protocol['coverage'])
        document(self.lab / (name + '-coverage.json'), check)
        if not check['passed']:
            return None
        fit = self.lab / (name + '-fit')
        command = [sys.executable, '-B', str(self.scripts / 'card_advantage_train.py'),
                   '--source', str(output), '--output', str(fit)]
        saved = read(fit / 'identity.json') if (fit / 'identity.json').exists() else None
        if saved:
            command.append('--resume')
        if not (fit / 'finished.json').exists():
            self.execute(name + '-fit', command, self.protocol['fit']['maximumSecondsPerCycle'],
                         saved['deadlineEpochSeconds'] if saved else None)
        report = completed_fit(fit, output, self.protocol, self.scripts)
        return fit if report['validation']['full']['localGatePassed'] else None

    def evaluate(self, stage, models):
        source = evaluation_source(self.base, stage, self.banks[stage], models)
        output = self.native('evaluate', stage, source, 7200)
        rows, provenance = evaluation.load_completed(output)
        report = evaluation.analyze(stage, self.banks[stage], rows)
        report['provenance'] = provenance
        document(self.lab / (stage + '-report.json'), report)
        return report

    def finish(self, disposition, **details):
        result = dict(disposition=disposition, preflightSha256=self.preflight_hash,
                      browserActivated=False, **details)
        document(self.lab / 'window-result.json', result)
        self.status('finished', **result)
        return result

    def run(self):
        p1 = self.cycle(1)
        if p1 is None:
            return self.finish('cycle-1-development-gate-failed')
        screen = self.evaluate('p1-screen', {'p1': p1 / 'full.json'})
        if screen['harmfulTransfer']:
            return self.finish('cycle-1-harmful-transfer')
        try:
            p2 = self.cycle(2, p1 / 'full.json')
        except (StageTimeout, CollectionNotAdmitted):
            self.status('cycle-2-incomplete-retaining-eligible-p1')
            p2 = None
        finalist = p1
        if p2 is not None:
            report = self.evaluate('common-development', {'p1': p1 / 'full.json', 'p2': p2 / 'full.json'})
            finalist = p2 if report['selected'] == 'p2' else p1
        selection = dict(model=str(finalist / 'full.json'), modelSha256=sha(finalist / 'full.json'),
                         frequency=str(finalist / 'frequency-control.json'),
                         frequencySha256=sha(finalist / 'frequency-control.json'))
        document(self.lab / 'finalist.json', selection)
        primary = self.evaluate('primary-confirmation',
                                {'candidate': finalist / 'full.json',
                                 'frequency': finalist / 'frequency-control.json'})
        robustness = self.evaluate('rival-robustness', {'candidate': finalist / 'full.json'})
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
        print(json.dumps(read(args.lab / 'window-status.json'), indent=2))
        return
    def cancel(*_):
        raise KeyboardInterrupt('Experiment window canceled.')
    signal.signal(signal.SIGTERM, cancel)
    with (args.lab / '.window.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        Window(args.lab, args.protocol).run()


if __name__ == '__main__':
    main()
