"""Finite, local-only qualification of one frozen automata candidate.

Native chunks run once in their original directories. Only complete prospectively
reserved world banks can open a win interval or the next bank. No fitting, selection,
network access, resume or retry is implemented here.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading
import time

import robust_token_campaign as c
import robust_token_worker as resource

KIND = 'mac-qualification-campaign-1'
CANDIDATE = 'fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e'
ASSET = 'c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a'
CURRENT = 'e62d749f4d9125cc86107cc2c37d77cb2f23af65c3648105a5fd2636f1ee27f1'
NATIVE = 'f74ea7735f74cecdf92d57523ff93c67fa6ba47ff1b37eadad9a3f4f66a86265'
SELECTOR = dict(kind='near-optimal-token-softmax-1', temperature=.35, maxScoreGap=3.0, neighborRadius=4)
REPLAYS = [0, 682, 1365, 2047]
BANKS = ('mixed', 'homogeneous')
MAXIMUM = 8208
USER_END = 1795020399.0


def validate(plan):
    c.require(plan['kind'] == KIND and plan['candidateBundleSha256'] == CANDIDATE and
        plan['baseInferenceAssetSha256'] == ASSET and plan['nativeJarSha256'] == NATIVE and
        plan['selector'] == SELECTOR, 'Qualification uses exactly final mean6+c05 and the frozen native89 engine.')
    c.require(plan['chunkSize'] == 256 and plan['maximumTerminalExecutions'] == MAXIMUM and
        plan['maximumScienceSeconds'] == 25200 and plan['auditSeconds'] == 600 and
        plan['globalDeadlineEpoch'] == USER_END and plan['confidenceMultiplier'] == c.MULTIPLIER,
        'The original finite world, execution, confidence and clock allowances cannot change.')
    c.require(set(plan['banks']) == set(BANKS), 'Only the two fresh independent qualification banks are admitted.')
    seeds = []
    for name in BANKS:
        bank = plan['banks'][name]
        c.require(bank['count'] == len(bank['seeds']) == 2048 and
            len(set(bank['seeds'])) == 2048 and all(type(value) is int for value in bank['seeds']) and
            bank['replayIndices'] == REPLAYS and
            bank['opponentStyle'] == ('QUALIFIED' if name == 'homogeneous' else 'BALANCED'),
            'Each bank owns2048 original seeds, fixed replays and the prospectively chosen ecology.')
        seeds.extend(bank['seeds'])
    c.require(len(set(seeds)) == 4096, 'The two complete original banks must be disjoint.')
    return plan


def verify_profile(path):
    profile = c.read(path)
    c.require(profile['kind'] == 'mac-qualification-host-1' and profile['workers'] == 10 and
        profile['heapMiB'] == 6144 and profile['maximumRssBytes'] == 12 * 1024**3 and
        profile['minimumFreeBytes'] == 16 * 1024**3 and profile['maximumDataBytes'] == 768 * 1024**2,
        'The original Mac-only resource profile must remain fixed.')
    c.require(profile['candidateBundle']['sha256'] == CANDIDATE and
        profile['inferenceAsset']['sha256'] == ASSET and profile['nativeJar']['sha256'] == NATIVE,
        'The local profile must retain the exact frozen candidate, base and runtime.')
    for owner, digest in profile['pins'].items():
        c.require(c.sha(owner) == digest, 'An original pinned local input changed: ' + owner)
    return profile


def source_value(plan, protocol_sha, profile, bank, start, count=256):
    return dict(kind='robust-token-chunk-1', protocolSha256=protocol_sha,
        inferenceAsset=profile['inferenceAsset'], nomination=profile['nomination'], arena=profile['arena'],
        candidateBundle=profile['candidateBundle'], selector=plan['selector'],
        opponentStyle=plan['banks'][bank]['opponentStyle'], seeds=plan['banks'][bank]['seeds'],
        globalStart=start, count=count, replayIndices=plan['banks'][bank]['replayIndices'], candidateOnly=False)


def expected_executions(source):
    start, count = source['globalStart'], source['count']
    return 2 * (count + sum(start <= index < start + count for index in source['replayIndices']))


def validate_chunk(source, native):
    """Audit complete original local artifacts, independent of concurrent JSONL append order."""
    native = Path(native)
    prepared, finished = c.read(native/'prepared.json'), c.read(native/'finished.json')
    source_sha = hashlib.sha256(c.canonical(source)).hexdigest()
    executions = expected_executions(source)
    c.require(prepared['passed'] is True and prepared['newGames'] == prepared['modelForwards'] == 0 and
        prepared['maximumTerminalExecutions'] == executions and
        prepared['sourceSha256'] == finished['sourceSha256'] == source_sha and
        prepared['scienceSha256'] == finished['scienceSha256'] and
        prepared['scienceIdentity']['candidateBundleSha256'] == source['candidateBundle']['sha256'] and
        prepared['scienceIdentity']['controlAndRivalsBundleSha256'] == CURRENT and
        prepared['scienceIdentity']['selector'] == source['selector'],
        'Native preparation and completion must retain the actual candidate, selector, base and original source.')
    for owner, digest in prepared['inputPins'].items():
        c.require(c.sha(owner) == digest, 'An original declared native/model input changed.')
    for name in ('candidateBundle', 'inferenceAsset', 'nomination', 'arena'):
        item = source[name]
        c.require(prepared['inputPins'].get(item['path']) == item['sha256'] and
            c.sha(item['path']) == item['sha256'], 'Every actual declared input must be physically pinned.')
    for relative, digest in finished['files'].items():
        c.require(not Path(relative).is_absolute() and '..' not in Path(relative).parts and
            c.sha(native/relative) == digest, 'An original native artifact changed.')
    roles = ['CANDIDATE', 'CONTROL']
    c.require(finished['complete'] is True and prepared['roles'] == finished['roles'] == roles and
        finished['candidateOnly'] is False and finished['worlds'] == source['count'] and
        finished['games'] == source['count'] * 2 and
        finished['scientificTerminalExecutions'] == finished['attemptedGames'] ==
        finished['completedGames'] == finished['newGames'] == executions and
        finished['newFits'] == 0 and finished['qualificationClaimed'] is False,
        'Every original paired game and prescribed reproduction must be counted exactly once.')
    rows = [json.loads(line) for line in (native/'worlds.jsonl').read_text().splitlines()]
    c.validate_chunk_indices(rows, source['globalStart'], source['count'])
    styles = ('QUALIFIED', 'UNDERCUT', 'OVERBID', 'MIXED')
    for row in rows:
        index = row['index']
        style = styles[(index//4 + index%4)%4] if source['opponentStyle'] == 'BALANCED' else source['opponentStyle']
        c.require(row['seed'] == source['seeds'][index] and row['seat'] == index%4 and
            row['opponentStyle'] == style and row['scienceSha256'] == finished['scienceSha256'] and
            set(row['roles']) == set(roles), 'Every world must retain its original stream, seat, crossed ecology and roles.')
    owned = [index for index in source['replayIndices']
             if source['globalStart'] <= index < source['globalStart'] + source['count']]
    path = native/'reproductions.jsonl'
    replays = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
    c.require(len(replays) == len(owned)*2 == finished['reproductions'] and
        {(row['index'], row['role']) for row in replays} == {(index, role) for index in owned for role in roles},
        'Exactly the prospectively prescribed original reproductions are required.')
    by_index = {row['index']: row for row in rows}
    for row in replays:
        original, role = by_index[row['index']], row['role']
        c.require(row['matched'] is True and row['terminal'] == original['roles'][role] and
            row['diagnostics'] == original['diagnostics'][role] and
            row['canonicalSha256'] == original['canonicalSha256'][role] and
            isinstance(row['canonicalSha256'], str) and len(row['canonicalSha256']) == 64,
            'Every prescribed replay must match normalized outcome, diagnostics and canonical decision history.')
    return sorted(rows, key=lambda row: row['index'])


def stop_process(process):
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def bounded_process(command, folder, profile, clock, cancelled, on_process=None):
    """Run one owned process group under the unchanged original clock and local resource limits."""
    c.require(time.time() < clock['deadlineEpoch'] and not cancelled.is_set(),
        'A new original task cannot start after cancellation or expiry.')
    c.require(shutil.disk_usage(profile['root']).free >= profile['minimumFreeBytes'] and
        resource.census(profile['root']) < profile['maximumDataBytes'],
        'The original local disk allowance must be available before a native task starts.')
    folder = Path(folder)
    environment = {key:value for key,value in os.environ.items()
                   if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    process = None
    try:
        with (folder/'native.log').open('xb') as log:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, env=environment, start_new_session=True)
            c.write(folder/'owner.json', dict(kind='mac-qualification-native-owner-1', workerPid=os.getpid(),
                nativePid=process.pid, nativeProcessGroup=process.pid, deadlineEpoch=clock['deadlineEpoch']))
            if on_process:
                on_process(process)
            while process.poll() is None:
                c.require(not cancelled.is_set() and time.time() < clock['deadlineEpoch'],
                    'The original task was interrupted or reached its original science deadline.')
                c.require(resource.group_rss(process.pid) <= profile['maximumRssBytes'] and
                    shutil.disk_usage(profile['root']).free >= profile['minimumFreeBytes'] and
                    resource.census(profile['root']) < profile['maximumDataBytes'],
                    'The original task exceeded its local memory or disk allowance.')
                time.sleep(.5)
        c.require(process.returncode == 0, 'The original native task failed; no retry is allowed.')
    finally:
        stop_process(process)
        if on_process:
            on_process(None)


def execute(profile, source, folder, clock, cancelled=None, on_process=None, validator=validate_chunk):
    folder = Path(folder)
    c.require(not folder.exists(), 'Every native chunk is new-only and cannot resume.')
    folder.mkdir(parents=True)
    for owner, digest in profile['pins'].items():
        c.require(c.sha(owner) == digest, 'An original local input changed before native execution.')
    c.write(folder/'source.json', source)
    seconds = max(1, int(clock['deadlineEpoch'] - time.time()))
    command = [profile['java'], '-Xmx'+str(profile['heapMiB'])+'m', *resource.java_options(profile),
        '-cp', profile['classpath'], 'dev.aragos.aor.training.TrainingMainKt',
        '--mode', 'robust-token-screen-chunk', '--source', str(folder/'source.json'),
        '--output', str(folder/'native'), '--seconds', str(seconds), '--workers', str(profile['workers'])]
    bounded_process(command, folder, profile, clock, cancelled or threading.Event(), on_process)
    rows = validator(source, folder/'native')
    c.write(folder/'receipt.json', dict(kind='mac-qualification-chunk-complete-1', complete=True,
        sourceSha256=c.sha(folder/'source.json'), finishedSha256=c.sha(folder/'native/finished.json'),
        scienceSha256=c.read(folder/'native/finished.json')['scienceSha256'], terminalExecutions=expected_executions(source)))
    return rows


def complete_bank(rows, bank):
    report = c.aggregate(rows, bank)
    return dict(kind='mac-qualification-bank-1', complete=True, statistics=report,
                win99Passed=report['wins']['lower99'] > 0, qualificationClaimed=False)


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
        c.require({p.name for p in directory.iterdir()} == {f'chunk-{index}' for index in range(8)},
            'A complete original bank requires all eight exact256-world chunks.')
        for chunk in range(8):
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
        executions <= MAXIMUM, 'Final qualification and actual execution counts must match the complete original evidence.')
    for path in (Path(protocol), Path(profile_path), output/'clock.json', output/'launch.json',
                 *list((output/'reports').glob('*.json'))):
        pins[str(path)] = c.sha(path)
    return dict(kind='mac-qualification-original-audit-1', passed=True, complete=True, qualified=qualified,
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
        c.require(not self.output.exists(), 'A qualification attempt is once-only and cannot resume.')
        self.output.mkdir(parents=True)
        started = time.time()
        deadline = min(started+self.plan['maximumScienceSeconds'], self.plan['globalDeadlineEpoch'])
        c.require(deadline > started, 'The original user qualification window has elapsed.')
        self.clock = dict(kind='mac-qualification-clock-1', protocolSha256=c.sha(protocol), startedEpoch=started,
            deadlineEpoch=deadline, auditDeadlineEpoch=min(deadline+600, self.plan['globalDeadlineEpoch']+600))
        (self.output/'protocol-copy.json').write_bytes(Path(protocol).read_bytes())
        c.write(self.output/'clock.json', self.clock)
        c.write(self.output/'launch.json', dict(pid=os.getpid(), processGroup=os.getpgrp(),
            protocolSha256=c.sha(protocol), profileSha256=c.sha(profile_path), approvalSha256=approval_sha))
        self.cancelled, self.process, self.interrupted = threading.Event(), None, False
        self.known, self.reserved = 0, 0

    def status(self, **values):
        c.replace_status(self.output/'status.json', dict(kind='mac-qualification-status-1', updatedEpoch=time.time(),
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
                for chunk in range(8):
                    source = source_value(self.plan, c.sha(self.protocol), self.profile, name, chunk*256)
                    self.reserved += expected_executions(source)
                    c.require(self.reserved <= MAXIMUM, 'The original execution budget cannot be exceeded.')
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
            terminal = dict(kind='mac-qualification-terminal-1', complete=True, status='completed', qualified=qualified,
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
            terminal = dict(kind='mac-qualification-terminal-1', complete=False,
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
    for name in ('mac_qualification_campaign.py', 'mac_qualification_close.py', 'mac_qualification_monitor.py',
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
    readiness = dict(kind='mac-qualification-local-preflight-1', passed=True, profileSha256=c.sha(root/'profile.json'),
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
        ('HOME','USER','LOGNAME','SSH_AUTH_SOCK','TMPDIR','__CF_USER_TEXT_ENCODING') if name in os.environ}
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
