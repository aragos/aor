"""Finite two-host development and independent confirmation of a token selector.

Worlds, configurations, host ownership and the selection rule are fixed before launch.
Each native chunk is attempted once. Closed or interrupted runs cannot resume.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import threading
import time


KIND = 'robust-token-campaign-1'
HOSTS = ('DESKTOP', 'MAC')
BANKS = ('development', 'mixed', 'homogeneous')
MULTIPLIER = 2.5758293035489004
MAXIMUM_EXECUTIONS = 16464


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_bytes())


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()+b'\n'


def sha(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            result.update(block)
    return result.hexdigest()


def write(path, value):
    """Publish a new complete record without replacing an earlier result."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = canonical(value)
    temporary = path.with_name(path.name+'.partial')
    with temporary.open('xb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    os.link(temporary, path)
    temporary.unlink()


def replace_status(path, value):
    path = Path(path)
    temporary = path.with_suffix('.status-partial')
    temporary.write_text(json.dumps(value, sort_keys=True, allow_nan=False)+'\n')
    os.replace(temporary, path)


def validate(plan):
    require(plan.get('kind') == KIND, 'A typed robust-token campaign protocol is required.')
    require(plan['maximumScienceSeconds'] == 10800 and plan['auditSeconds'] == 600 and
        plan['maximumTerminalExecutions'] == MAXIMUM_EXECUTIONS and plan['chunkSize'] == 64 and
        plan['minimumDevelopmentDelta'] == 0.0 and plan['confidenceMultiplier'] == MULTIPLIER,
        'The fixed finite method, execution allowance and confidence rule cannot change.')
    cases = plan['caseConfigs']
    require(len(cases) == 8 and len({row['id'] for row in cases}) == 8 and
        all(re.fullmatch('[a-z][a-z0-9-]{0,47}', row['id']) for row in cases),
        'Development requires exactly eight uniquely ordered safe case identifiers.')
    require(set(plan['banks']) == set(BANKS), 'All three independent world banks must be reserved.')
    all_seeds = []
    for name, count, replay in (
        ('development', 512, [0, 170, 341, 511]),
        ('mixed', 2048, [0, 682, 1365, 2047]),
        ('homogeneous', 2048, [0, 682, 1365, 2047]),
    ):
        bank = plan['banks'][name]
        require(len(bank['seeds']) == count and len(set(bank['seeds'])) == count and
            all(type(value) is int for value in bank['seeds']) and bank['replayIndices'] == replay and
            bank['opponentStyle'] == ('QUALIFIED' if name == 'homogeneous' else 'BALANCED'),
            'Every complete bank retains distinct seeds, prescribed reproductions and ecology.')
        all_seeds.extend(bank['seeds'])
    require(len(set(all_seeds)) == 4608,
        'Development and both confirmation banks must be mutually disjoint.')
    require(type(plan['globalDeadlineEpoch']) in (int, float) and
        math.isfinite(plan['globalDeadlineEpoch']), 'A finite original user deadline is required.')
    return plan


def owner(chunk):
    """Repeat the same five-Desktop/three-Mac assignment within every eight chunks."""
    return 'DESKTOP' if chunk % 8 < 5 else 'MAC'


def interval(values, multiplier=MULTIPLIER):
    require(len(values) >= 2 and all(math.isfinite(value) for value in values),
        'A paired interval needs at least two finite independent-world differences.')
    count = len(values)
    mean = math.fsum(values)/count
    error = math.sqrt(math.fsum((value-mean)**2 for value in values)/(count*(count-1)))
    return dict(count=count, mean=mean, standardError=error,
        lower99=mean-multiplier*error, upper99=mean+multiplier*error)


def chosen_case(reports, ordered_cases):
    require(set(reports) == {row['id'] for row in ordered_cases},
        'Selection opens only after all eight whole development cases complete.')
    best = max(ordered_cases, key=lambda row: reports[row['id']]['wins']['mean'])
    return best if reports[best['id']]['wins']['mean'] > 0 else None


def metric(world, role, name):
    game = world['roles'][role]
    seat = world['seat']
    if name == 'chaos':
        own = next(row for row in game['scores'] if row['player'] == f'p{seat+1}')
        return float(own['total'] is None)
    return game[name][seat]


def aggregate(rows, bank):
    rows = sorted(rows, key=lambda row: row['index'])
    require(len(rows) == len(bank['seeds']) and
        [row['index'] for row in rows] == list(range(len(rows))) and
        [row['seed'] for row in rows] == bank['seeds'] and
        all(row['seat'] == row['index'] % 4 and
            set(row['roles']) == {'CANDIDATE', 'CONTROL'} for row in rows),
        'A report requires every original world, seed, controlled seat and paired role exactly once.')
    styles = ('QUALIFIED', 'UNDERCUT', 'OVERBID', 'MIXED')
    require(all(row['opponentStyle'] == (styles[(row['index']//4+row['seat']) % 4]
        if bank['opponentStyle'] == 'BALANCED' else bank['opponentStyle']) for row in rows),
        'The public ecology rotation must be crossed with controlled seat, not confounded with it.')
    result = {}
    for name in ('wins', 'rewards', 'ranks', 'chaos'):
        own = [metric(row, 'CANDIDATE', name) for row in rows]
        control = [metric(row, 'CONTROL', name) for row in rows]
        require(all(math.isfinite(value) and 0 <= value <= 1 for value in own+control),
            'Terminal metrics must retain finite normalized units.')
        result[name] = dict(interval([a-b for a, b in zip(own, control)]),
            candidate=math.fsum(own)/len(rows), control=math.fsum(control)/len(rows))
    return result


class Campaign:
    def __init__(self, protocol, profiles, approval, approval_sha, output):
        self.protocol_path = Path(protocol).resolve()
        self.plan = validate(read(protocol))
        self.protocol_sha = sha(protocol)
        self.profile_paths = {name: Path(path).resolve() for name, path in profiles.items()}
        require(set(profiles) == set(HOSTS), 'Both frozen host profiles are required.')
        self.profiles = {name: read(path) for name, path in self.profile_paths.items()}
        require(sha(approval) == approval_sha, 'Root approval bytes must match the supplied raw hash.')
        approved = read(approval)
        require(approved.get('launchAuthorized') is True and
            approved['protocolSha256'] == self.protocol_sha and
            approved['hostProfiles'] == {name: sha(path) for name, path in self.profile_paths.items()},
            'Root approval must bind this exact protocol and both actual host profiles.')
        self.output = Path(output).resolve()
        require(not self.output.exists(), 'A campaign output is new-only; attempts cannot resume or overwrite.')
        self.output.mkdir(parents=True)
        self.started = time.time()
        deadline = min(self.started+self.plan['maximumScienceSeconds'], self.plan['globalDeadlineEpoch'])
        require(deadline > self.started, 'The original user science window has elapsed.')
        self.clock = dict(kind='robust-token-clock-1', startedEpoch=self.started,
            deadlineEpoch=deadline, auditDeadlineEpoch=min(deadline+600, self.plan['globalDeadlineEpoch']+600),
            protocolSha256=self.protocol_sha)
        write(self.output/'clock.json', self.clock)
        write(self.output/'launch.json', dict(kind='robust-token-launch-1',
            pid=os.getpid(), processGroup=os.getpgrp(), protocolSha256=self.protocol_sha,
            profileSha256={name: sha(path) for name, path in self.profile_paths.items()},
            approvalSha256=approval_sha, automaticRetry=False, maximumAttempts=1))
        self.cancel = threading.Event()
        self.interrupted = False
        self.active = {}
        self.lock = threading.Lock()
        self.executions = 0
        self.reserved_executions = 0
        self.original_handlers = {}

    def status(self, **fields):
        replace_status(self.output/'status.json', dict(kind='robust-token-status-1',
            updatedEpoch=time.time(), protocolSha256=self.protocol_sha,
            terminalExecutions=self.executions, **fields))

    def stop(self, signum=None, _frame=None):
        if signum in (signal.SIGTERM, signal.SIGINT): self.interrupted = True
        self.cancel.set()
        with self.lock:
            processes = list(self.active.items())
        for host, process in processes:
            if process.poll() is None:
                process.terminate()
                profile = self.profiles[host]
                cancellation = [profile['python'], '-B', profile['workerScript'], '--profile',
                    profile['actualProfile'], '--cancel-active']
                if host == 'DESKTOP':
                    cancellation = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5',
                        profile['sshTarget'], *cancellation]
                try:
                    subprocess.run(cancellation, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL, timeout=12, check=False)
                except (OSError, subprocess.TimeoutExpired):
                    # Each remote task independently owns the original deadline and cleanup.
                    pass

    def task(self, host, bank_name, case, chunk):
        require(not self.cancel.is_set() and time.time() < self.clock['deadlineEpoch'],
            'No new task may start after cancellation or the original science deadline.')
        profile = self.profiles[host]
        bank = self.plan['banks'][bank_name]
        expected_replays = 2*sum(chunk*64 <= index < (chunk+1)*64 for index in bank['replayIndices'])
        executed = 128+expected_replays
        with self.lock:
            require(shutil.disk_usage(self.output).free >= 16*1024**3,
                'The local coordinator must retain at least16GiB free.')
            require(sum(path.stat().st_size for path in self.output.rglob('*') if path.is_file()) < 4*1024**3,
                'The local compact campaign must remain below4GiB.')
            self.reserved_executions += executed
            require(self.reserved_executions <= MAXIMUM_EXECUTIONS,
                'Actual started tasks cannot exceed the fixed scientific execution allowance.')
        source = dict(kind='robust-token-chunk-1', protocolSha256=self.protocol_sha,
            inferenceAsset=profile['inferenceAsset'], nomination=profile['nomination'],
            arena=profile['arena'], selector=case['selector'], opponentStyle=bank['opponentStyle'],
            seeds=bank['seeds'], globalStart=chunk*64, count=64,
            replayIndices=bank['replayIndices'])
        if 'candidateBundle' in case:
            source['candidateBundle'] = case['candidateBundle'][host]
        task_id = f'{bank_name}/{case["id"]}/chunk-{chunk}'
        request = dict(kind='robust-token-worker-task-1', taskId=task_id,
            clock=self.clock, source=source, profileSha256=sha(self.profile_paths[host]))
        directory = self.output/'chunks'/task_id
        directory.mkdir(parents=True)
        write(directory/'request.json', request)
        worker = [profile['python'], '-B', profile['workerScript'], '--profile', profile['actualProfile']]
        command = worker if host == 'MAC' else ['ssh', '-o', 'BatchMode=yes', '-o',
            'ConnectTimeout=8', profile['sshTarget'], *worker]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=False, start_new_session=True)
        with self.lock:
            self.active[host] = process
        allowance = max(.1, self.clock['deadlineEpoch']-time.time()+15)
        try:
            stdout, stderr = process.communicate(json.dumps(request, allow_nan=False).encode(), timeout=allowance)
        except BaseException:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)
            raise
        finally:
            with self.lock:
                self.active.pop(host, None)
        (directory/'worker-stderr.log').write_bytes(stderr)
        require(process.returncode == 0, f'{host} native task failed; no retry is allowed: {task_id}.')
        require(len(stdout) <= 96*1024**2, 'A native chunk transport exceeded its compact output allowance.')
        response = json.loads(stdout)
        require(response['kind'] == 'robust-token-worker-result-1' and response['complete'] is True and
            response['taskId'] == task_id and response['profileSha256'] == request['profileSha256'],
            'The completed transport must identify this exact original task and frozen host.')
        for name, item in response['files'].items():
            path = Path(name)
            require(not path.is_absolute() and all(part not in ('.', '..') for part in path.parts),
                'Transported native members must remain inside their original chunk.')
            raw = base64.b64decode(item['base64'], validate=True)
            require(hashlib.sha256(raw).hexdigest() == item['sha256'],
                'A transported original native output changed in transit.')
            target = directory/'native'/path
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as stream: stream.write(raw)
        finished = read(directory/'native/finished.json')
        require(finished['sourceSha256'] == hashlib.sha256(canonical(source)).hexdigest(),
            'The native completion must bind the exact original canonical source bytes.')
        for name, digest in finished['files'].items():
            require(sha(directory/'native'/name) == digest,
                'The native completion must hash each original outcome and reproduction file.')
        rows = [json.loads(line) for line in (directory/'native/worlds.jsonl').read_text().splitlines()]
        require(len(rows) == 64 and [row['index'] for row in rows] == list(range(chunk*64, (chunk+1)*64)),
            'Every transported chunk must retain its exact original global index range.')
        require(finished['complete'] is True and finished['worlds'] == 64 and
            finished['games'] == 128 and finished['reproductions'] == expected_replays and
            finished['scientificTerminalExecutions'] == executed and
            finished['attemptedGames'] == executed and finished['completedGames'] == executed,
            'Native execution accounting must match the fixed paired worlds and actual reproductions.')
        prepared = read(directory/'native/prepared.json')
        require(prepared['passed'] is True and prepared['newGames'] == prepared['modelForwards'] == 0 and
            prepared['scienceSha256'] == finished['scienceSha256'] and
            prepared['sourceSha256'] == finished['sourceSha256'] and
            all(row['scienceSha256'] == prepared['scienceSha256'] for row in rows),
            'Every original outcome and completed task must retain the admitted native scientific identity.')
        write(directory/'receipt.json', dict(kind='robust-token-chunk-receipt-1', complete=True,
            host=host, taskId=task_id, finishedSha256=sha(directory/'native/finished.json'),
            rowsSha256=sha(directory/'native/worlds.jsonl'), terminalExecutions=executed))
        with self.lock:
            self.executions += executed
            require(self.executions <= MAXIMUM_EXECUTIONS,
                'The fixed total scientific execution allowance cannot be exceeded.')
            self.status(status='running', stage=bank_name, case=case['id'], lastTask=task_id)
        return rows

    def host_bank(self, host, name, cases):
        result = {case['id']: [] for case in cases}
        for case in cases:
            for chunk in range(len(self.plan['banks'][name]['seeds'])//64):
                if owner(chunk) == host:
                    result[case['id']].extend(self.task(host, name, case, chunk))
        return result

    def bank(self, name, cases):
        values = {case['id']: [] for case in cases}
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = {pool.submit(self.host_bank, host, name, cases): host for host in HOSTS}
            try:
                for future in as_completed(futures):
                    for case_id, rows in future.result().items(): values[case_id].extend(rows)
            except BaseException:
                self.stop()
                raise
        result = {case['id']: aggregate(values[case['id']], self.plan['banks'][name]) for case in cases}
        if name == 'development':
            controls = [[row['roles']['CONTROL'] for row in sorted(values[case['id']], key=lambda row: row['index'])]
                for case in cases]
            require(all(control == controls[0] for control in controls[1:]),
                'Repeated original CONTROL worlds must be identical across all selector configurations.')
        write(self.output/'reports'/f'{name}.json', dict(kind='robust-token-bank-report-1',
            complete=True, bank=name, protocolSha256=self.protocol_sha, cases=result,
            qualification=False, activated=False))
        return result

    def run(self):
        for event in (signal.SIGTERM, signal.SIGINT):
            self.original_handlers[event] = signal.signal(event, self.stop)
        self.status(status='running', stage='development')
        try:
            development = self.bank('development', self.plan['caseConfigs'])
            selected = chosen_case(development, self.plan['caseConfigs'])
            write(self.output/'selection.json', dict(kind='robust-token-selection-1',
                complete=True, case=selected, rule='highest-development-mean-win;stable-case-order-ties;positive-only',
                protocolSha256=self.protocol_sha))
            qualified = False
            reason = 'no-positive-development-candidate'
            if selected is not None:
                mixed = self.bank('mixed', [selected])[selected['id']]
                reason = 'mixed-win99-refused'
                if mixed['wins']['lower99'] > 0:
                    homogeneous = self.bank('homogeneous', [selected])[selected['id']]
                    qualified = homogeneous['wins']['lower99'] > 0
                    reason = 'both-independent-win99-passed' if qualified else 'homogeneous-win99-refused'
            terminal = dict(kind='robust-token-terminal-1', status='completed', complete=True,
                qualified=qualified, selectedCase=selected, reason=reason,
                protocolSha256=self.protocol_sha, terminalExecutions=self.executions,
                knownCompletedTerminalExecutions=self.executions,
                reservedTerminalUpperBound=self.reserved_executions,
                finishedEpoch=time.time(), newFits=0, activated=False)
            import robust_token_audit
            audit = robust_token_audit.verify(self.protocol_path, self.output, terminal)
            write(self.output/'completion-audit.json', audit)
            terminal['completionAuditSha256'] = sha(self.output/'completion-audit.json')
            write(self.output/'terminal.json', terminal)
            self.status(status='completed', qualified=qualified, reason=reason)
            return terminal
        except BaseException as error:
            self.stop()
            terminal = dict(kind='robust-token-terminal-1', status='interrupted' if self.interrupted else 'failed',
                complete=False, qualified=False, protocolSha256=self.protocol_sha,
                reason=type(error).__name__+': '+str(error), terminalExecutions=self.executions,
                knownCompletedTerminalExecutions=self.executions,
                reservedTerminalUpperBound=self.reserved_executions,
                finishedEpoch=time.time(), activated=False)
            write(self.output/'terminal.json', terminal)
            self.status(status=terminal['status'], reason=terminal['reason'])
            raise
        finally:
            for event, handler in self.original_handlers.items(): signal.signal(event, handler)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--desktop-profile', type=Path, required=True)
    parser.add_argument('--mac-profile', type=Path, required=True)
    parser.add_argument('--approval', type=Path, required=True)
    parser.add_argument('--approval-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(Campaign(args.protocol, {'DESKTOP': args.desktop_profile, 'MAC': args.mac_profile},
        args.approval, args.approval_sha256, args.output).run(), sort_keys=True))
