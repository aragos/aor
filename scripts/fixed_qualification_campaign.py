"""Finite local qualification under an explicit registered fixed-policy contract.

All native execution and replay admission use the immutable shared physical executor.
The campaign owns its own typed world counts, gating, clock and audit; no other
module's globals are changed. No selection, fitting, network, retry or resume exists.
"""
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import threading
import time

import robust_token_campaign as c
import robust_token_worker as resource
import mac_qualification_campaign as shared

KIND = 'fixed-policy-qualification-campaign-1'
CANDIDATE, ASSET, CURRENT, NATIVE = shared.CANDIDATE, shared.ASSET, shared.CURRENT, shared.NATIVE
SELECTOR, BANKS, USER_END = shared.SELECTOR, shared.BANKS, shared.USER_END
verify_profile = shared.verify_profile
source_value = shared.source_value
execute = shared.execute
expected_executions = shared.expected_executions
validate_chunk = shared.validate_chunk
stop_process = shared.stop_process
complete_bank = shared.complete_bank


@dataclass(frozen=True)
class FixedContract:
    version: str
    worlds_per_bank: int
    replay_indices: tuple
    maximum_science_seconds: int

    @property
    def maximum_executions(self):
        return 2 * 2 * (self.worlds_per_bank + len(self.replay_indices))


SMALL = FixedContract('final6-c05-small-512-1', 512, (0, 170, 341, 511), 10800)


def contract(plan):
    c.require(plan['contract'] == SMALL.version, 'Use the explicitly registered fixed-policy qualification contract.')
    return SMALL


def validate(plan):
    spec = contract(plan)
    c.require(plan['kind'] == KIND and plan['candidateBundleSha256'] == CANDIDATE and
        plan['baseInferenceAssetSha256'] == ASSET and plan['nativeJarSha256'] == NATIVE and
        plan['selector'] == SELECTOR, 'Qualification retains the sole original final6+c05 and native89 engine.')
    c.require(plan['chunkSize'] == 256 and plan['maximumTerminalExecutions'] == spec.maximum_executions and
        plan['maximumScienceSeconds'] == spec.maximum_science_seconds and plan['auditSeconds'] == 600 and
        plan['globalDeadlineEpoch'] == USER_END and plan['confidenceMultiplier'] == c.MULTIPLIER,
        'The registered finite execution, confidence and original clock allowances cannot change.')
    c.require(set(plan['banks']) == set(BANKS), 'Only the two independently reserved fixed qualification banks are admitted.')
    seeds = []
    for name in BANKS:
        bank = plan['banks'][name]
        c.require(bank['count'] == len(bank['seeds']) == spec.worlds_per_bank and
            len(set(bank['seeds'])) == spec.worlds_per_bank and
            all(type(value) is int for value in bank['seeds']) and
            bank['replayIndices'] == list(spec.replay_indices) and
            bank['opponentStyle'] == ('QUALIFIED' if name == 'homogeneous' else 'BALANCED'),
            'Each original complete bank retains the registered count, replays and crossed ecology.')
        seeds.extend(bank['seeds'])
    c.require(len(set(seeds)) == 2*spec.worlds_per_bank, 'The two complete original qualification banks must be disjoint.')
    return plan


def audit(protocol, profile_path, output, terminal):
    plan, output = validate(c.read(protocol)), Path(output)
    clock = c.read(output/'clock.json')
    c.require(time.time() <= clock['auditDeadlineEpoch'] and terminal['complete'] is True and
        terminal['protocolSha256'] == clock['protocolSha256'] == c.sha(protocol),
        'Only the original complete attempt may receive its bounded audit.')
    c.require((output/'protocol-copy.json').read_bytes() == Path(protocol).read_bytes(),
        'The original protocol copy must retain its exact raw bytes.')
    profile = verify_profile(profile_path)
    pins, reports, executions = {}, {}, 0
    expected = ['mixed']
    if c.read(output/'reports/mixed.json')['win99Passed']:
        expected.append('homogeneous')
    c.require({path.stem for path in (output/'reports').glob('*.json')} == set(expected),
        'Only complete prospectively gated qualification banks may produce reports.')
    for name in expected:
        directory, rows, sciences = output/'chunks'/name, [], set()
        c.require({p.name for p in directory.iterdir()} == {f'chunk-{index}' for index in range(plan['banks'][name]['count']//plan['chunkSize'])},
            'A complete original bank requires every exact original256-world chunk.')
        for chunk in range(plan['banks'][name]['count']//plan['chunkSize']):
            folder = directory/f'chunk-{chunk}'
            source = source_value(plan, c.sha(protocol), profile, name, chunk*256)
            c.require(c.read(folder/'source.json') == source, 'Every chunk retains the fixed actual original source.')
            rows.extend(validate_chunk(source, folder/'native'))
            receipt = c.read(folder/'receipt.json')
            c.require(receipt['complete'] is True and receipt['finishedSha256'] == c.sha(folder/'native/finished.json') and
                receipt['sourceSha256'] == c.sha(folder/'source.json') and
                receipt['terminalExecutions'] == expected_executions(source), 'An original chunk receipt changed.')
            sciences.add(receipt['scienceSha256'])
            executions += expected_executions(source)
            for path in folder.rglob('*'):
                if path.is_file():
                    pins[str(path)] = c.sha(path)
        c.require(len(sciences) == 1, 'All original chunks in a bank share exactly one scientific identity.')
        reports[name] = complete_bank(rows, plan['banks'][name])
        c.require(reports[name] == c.read(output/'reports'/f'{name}.json'),
            'Every complete original paired win99 result must reproduce exactly.')
    qualified = len(expected) == 2 and reports['homogeneous']['win99Passed']
    c.require(qualified is terminal['qualified'] and executions == terminal['knownCompletedTerminalExecutions'] and
        executions <= plan['maximumTerminalExecutions'], 'Final qualification and actual execution counts must match the complete original evidence.')
    for path in (Path(protocol), Path(profile_path), output/'clock.json', output/'launch.json',
                 *list((output/'reports').glob('*.json'))):
        pins[str(path)] = c.sha(path)
    return dict(kind='fixed-policy-qualification-original-audit-1', passed=True, complete=True, qualified=qualified,
        protocolSha256=c.sha(protocol), profileSha256=c.sha(profile_path), clockSha256=c.sha(output/'clock.json'),
        terminalExecutions=executions, artifactPins=pins, originalReproductionsVerified=True,
        allWin99StatisticsRecomputed=True, newGames=0, newFits=0, modelForwards=0, completedEpoch=time.time())


class Campaign:
    def __init__(self, protocol, profile_path, approval, approval_sha, output):
        self.protocol, self.profile_path, self.output = Path(protocol), Path(profile_path), Path(output)
        self.plan, self.profile = validate(c.read(protocol)), verify_profile(profile_path)
        c.require(self.output.resolve().is_relative_to(Path(self.profile['root']).resolve()),
            'All native qualification artifacts must remain inside the original local data allowance.')
        review = c.read(approval)
        c.require(c.sha(approval) == approval_sha and review['launchAuthorized'] is True and
            review['protocolSha256'] == c.sha(protocol) and review['profileSha256'] == c.sha(profile_path),
            'Root approval must bind the exact original local protocol and profile.')
        prior = review['priorTrialClosure']
        c.require(c.sha(prior['path']) == prior['sha256'], 'The prior resource-stop closure changed.')
        closed = c.read(prior['path'])
        c.require(closed['passed'] is True and closed['allOwnedProcessesExited'] is True and
            closed['scienceComplete'] is False and closed['qualified'] is False and
            closed['protocolSha256'] == self.plan['priorTrialProtocolSha256'],
            'The superseded trial must be physically closed and unqualified before a new scientific clock starts.')
        c.require(not self.output.exists(), 'A qualification attempt is once-only and cannot resume.')
        self.output.mkdir(parents=True)
        started = time.time()
        deadline = min(started+self.plan['maximumScienceSeconds'], self.plan['globalDeadlineEpoch'])
        c.require(deadline > started, 'The original user qualification window has elapsed.')
        self.clock = dict(kind='fixed-policy-qualification-clock-1', protocolSha256=c.sha(protocol), startedEpoch=started,
            deadlineEpoch=deadline, auditDeadlineEpoch=min(deadline+600, self.plan['globalDeadlineEpoch']+600))
        (self.output/'protocol-copy.json').write_bytes(Path(protocol).read_bytes())
        c.write(self.output/'clock.json', self.clock)
        c.write(self.output/'launch.json', dict(pid=os.getpid(), processGroup=os.getpgrp(),
            protocolSha256=c.sha(protocol), profileSha256=c.sha(profile_path), approvalSha256=approval_sha))
        self.cancelled, self.process, self.interrupted = threading.Event(), None, False
        self.known, self.reserved = 0, 0

    def status(self, **values):
        c.replace_status(self.output/'status.json', dict(kind='fixed-policy-qualification-status-1', updatedEpoch=time.time(),
            knownCompletedTerminalExecutions=self.known, reservedTerminalUpperBound=self.reserved, **values))

    def stop(self, signum=None, _frame=None):
        self.interrupted = self.interrupted or signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)
        self.cancelled.set()
        if self.process is not None and self.process.poll() is None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass

    def run(self):
        previous = {event:signal.signal(event, self.stop) for event in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
        try:
            qualified = False
            for name in BANKS:
                self.status(status='running', stage=name)
                rows = []
                for chunk in range(self.plan['banks'][name]['count']//self.plan['chunkSize']):
                    source = source_value(self.plan, c.sha(self.protocol), self.profile, name, chunk*256)
                    self.reserved += expected_executions(source)
                    c.require(self.reserved <= self.plan['maximumTerminalExecutions'], 'The original execution budget cannot be exceeded.')
                    rows.extend(execute(self.profile, source, self.output/'chunks'/name/f'chunk-{chunk}',
                        self.clock, self.cancelled, lambda process:setattr(self, 'process', process)))
                    self.known += expected_executions(source)
                    self.status(status='running', stage=name, lastChunk=chunk)
                report = complete_bank(rows, self.plan['banks'][name])
                c.write(self.output/'reports'/f'{name}.json', report)
                if not report['win99Passed']:
                    break
                if name == 'homogeneous':
                    qualified = True
            terminal = dict(kind='fixed-policy-qualification-terminal-1', complete=True, status='completed', qualified=qualified,
                candidateBundleSha256=CANDIDATE, selector=SELECTOR, protocolSha256=c.sha(self.protocol),
                knownCompletedTerminalExecutions=self.known, reservedTerminalUpperBound=self.reserved,
                finishedEpoch=time.time(), activated=False)
            result = audit(self.protocol, self.profile_path, self.output, terminal)
            c.require(not self.cancelled.is_set() and time.time() <= self.clock['auditDeadlineEpoch'],
                'The original audit cannot finish after interruption or its original allowance.')
            c.write(self.output/'completion-audit.json', result)
            terminal['completionAuditSha256'] = c.sha(self.output/'completion-audit.json')
            c.write(self.output/'terminal.json', terminal)
            self.status(status='completed')
            return terminal
        except BaseException as error:
            self.stop()
            stop_process(self.process)
            terminal = dict(kind='fixed-policy-qualification-terminal-1', complete=False,
                status='interrupted' if self.interrupted else 'failed', qualified=False,
                reason=type(error).__name__+': '+str(error), protocolSha256=c.sha(self.protocol),
                knownCompletedTerminalExecutions=self.known, reservedTerminalUpperBound=self.reserved,
                finishedEpoch=time.time(), activated=False)
            c.write(self.output/'terminal.json', terminal)
            self.status(status=terminal['status'], failureReason=terminal['reason'])
            raise
        finally:
            for event, handler in previous.items():
                signal.signal(event, handler)


def prepare(protocol, old_profile_path, native_runtime, candidate, root, python):
    """Prepare a new local profile and actual256-world native admissions; executes zero games."""
    protocol, root, candidate = Path(protocol).resolve(), Path(root).resolve(), Path(candidate).resolve()
    plan, old = validate(c.read(protocol)), c.read(old_profile_path)
    c.require(not root.exists() and c.sha(candidate) == CANDIDATE, 'Preparation requires a fresh root and exact final mean6.')
    root.mkdir(parents=True)
    library, scripts = root/'runtime/lib', root/'runtime/bin'
    library.mkdir(parents=True); scripts.mkdir()
    for path in (Path(native_runtime)/'lib').glob('*.jar'):
        shutil.copy2(path, library/path.name)
    for name in ('fixed_qualification_campaign.py', 'mac_qualification_campaign.py', 'mac_qualification_close.py', 'mac_qualification_monitor.py',
                 'robust_token_campaign.py', 'robust_token_worker.py'):
        shutil.copy2(Path(__file__).parent/name, scripts/name)
    (root/'protocol.json').write_bytes(protocol.read_bytes())
    profile = dict(kind='mac-qualification-host-1', host='MAC', root=str(root), python=str(python),
        java=old['java'], classpath=str(library/'*'), jvmOptions=old['jvmOptions'], workers=10,
        heapMiB=6144, maximumRssBytes=12*1024**3, minimumFreeBytes=16*1024**3, maximumDataBytes=768*1024**2,
        inferenceAsset=old['inferenceAsset'], nomination=old['nomination'], arena=old['arena'],
        candidateBundle=dict(path=str(candidate), sha256=CANDIDATE),
        nativeJar=dict(path=str(library/'training.jar'), sha256=NATIVE), pins={})
    c.require(c.sha(library/'training.jar') == NATIVE, 'The actual local frozen native89 JAR changed.')
    pins = {str(path):c.sha(path) for path in [*library.glob('*.jar'), *scripts.glob('*.py'), root/'protocol.json',
        Path(old['java']), *[Path(p) for p in old['pins'] if p.endswith('/lib/server/libjvm.dylib')]]}
    admissions = {}
    for name in BANKS:
        source = source_value(plan, c.sha(protocol), profile, name, 0)
        source_path, target = root/f'preflight-{name}-source.json', root/f'preflight-{name}'
        c.write(source_path, source)
        command = [profile['java'], '-Xmx6144m', *resource.java_options(profile), '-cp', profile['classpath'],
            'dev.aragos.aor.training.TrainingMainKt', '--mode', 'robust-token-prepare',
            '--source', str(source_path), '--output', str(target)]
        environment = {key:value for key,value in os.environ.items()
                       if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
        result = subprocess.run(command, capture_output=True, env=environment, timeout=120)
        (root/f'preflight-{name}.log').write_bytes(result.stdout+result.stderr)
        c.require(result.returncode == 0, 'The actual zero-game native admission failed; retain its log.')
        admission = c.read(target/'prepared.json')
        c.require(admission['passed'] is True and admission['newGames'] == admission['modelForwards'] == 0 and
            admission['scienceIdentity']['candidateBundleSha256'] == CANDIDATE and
            admission['scienceIdentity']['selector'] == SELECTOR and admission['count'] == 256,
            'The actual native preflight must admit the exact final candidate, c05 selector and256-world chunk.')
        pins.update(admission['inputPins']); pins[str(target/'prepared.json')] = c.sha(target/'prepared.json')
        admissions[name] = dict(scienceSha256=admission['scienceSha256'], preparedSha256=c.sha(target/'prepared.json'))
    profile['pins'] = pins
    c.write(root/'profile.json', profile)
    verify_profile(root/'profile.json')
    readiness = dict(kind='fixed-policy-qualification-local-preflight-1', passed=True, contract=plan['contract'], profileSha256=c.sha(root/'profile.json'),
        protocolSha256=c.sha(protocol), nativeAdmissions=admissions, inputPins=len(pins),
        newGames=0, modelForwards=0, newFits=0, launchAuthorized=False)
    c.write(root/'readiness.json', readiness)
    return readiness


def launch(args):
    """Foreground Terminal exec preserves process responsibility; no network calls exist."""
    profile = verify_profile(args.profile)
    review = c.read(args.approval)
    c.require(c.sha(args.approval) == args.approval_sha256 and review['launchAuthorized'] is True and
        review['protocolSha256'] == c.sha(args.protocol) and review['profileSha256'] == c.sha(args.profile) and
        not args.output.exists(), 'The once-only Terminal launch requires exact root approval and a fresh attempt.')
    environment = {name:os.environ[name] for name in
        ('HOME','USER','LOGNAME','TMPDIR','__CF_USER_TEXT_ENCODING') if name in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='en_US.UTF-8', LC_ALL='en_US.UTF-8', TZ='UTC',
        PYTHONDONTWRITEBYTECODE='1', PYTHONNOUSERSITE='1', OMP_NUM_THREADS='1', MKL_NUM_THREADS='1',
        OPENBLAS_NUM_THREADS='1', VECLIB_MAXIMUM_THREADS='1')
    command = [profile['python'], '-E', '-s', '-B', str(Path(__file__).resolve()), '--run',
        '--protocol', str(args.protocol), '--profile', str(args.profile), '--approval', str(args.approval),
        '--approval-sha256', args.approval_sha256, '--output', str(args.output)]
    caffeine = subprocess.Popen(['/usr/bin/caffeinate', '-i', '-w', str(os.getpid())], env=environment,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    c.write(args.owner_record, dict(kind='mac-qualification-terminal-owner-1', controllerPid=os.getpid(),
        controllerParentPid=os.getppid(), controllerGroup=os.getpgrp(), caffeinatePid=caffeine.pid,
        exactCommand=command, approvalSha256=args.approval_sha256, environmentNames=sorted(environment)))
    os.execve(command[0], command, environment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    for name in ('run', 'prepare', 'launch'):
        modes.add_argument('--'+name, action='store_true')
    for name in ('protocol','profile','approval','output','owner-record','old-profile','native-runtime','candidate','root'):
        parser.add_argument('--'+name, type=Path)
    parser.add_argument('--approval-sha256'); parser.add_argument('--python')
    args = parser.parse_args()
    if args.prepare:
        result = prepare(args.protocol, args.old_profile, args.native_runtime, args.candidate, args.root, args.python)
    elif args.launch:
        return launch(args)
    else:
        result = Campaign(args.protocol, args.profile, args.approval, args.approval_sha256, args.output).run()
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
