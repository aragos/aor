"""Post-terminal local metadata closure; never rerun games, fits or statistical audits."""
import argparse
from pathlib import Path
import subprocess
import time

import robust_token_campaign as c
import mac_qualification_campaign as m


def processes():
    answer = subprocess.run(['/bin/ps', '-axo', 'pid=,pgid=,stat='], capture_output=True,
        text=True, check=True, timeout=15)
    return [tuple(line.split()) for line in answer.stdout.splitlines() if len(line.split()) == 3]


def accounting(source, native):
    """Finished counters are exact; interrupted native counters remain lower bounds."""
    upper = m.expected_executions(source)
    finished, failure = Path(native)/'finished.json', Path(native)/'failed.json'
    record = c.read(finished) if finished.exists() else c.read(failure) if failure.exists() else None
    attempted = record['attemptedGames'] if record else 0
    completed = record['completedGames'] if record else 0
    c.require(0 <= completed <= attempted <= upper,
        'Original native attempt/completion counters must remain within the task allowance.')
    if finished.exists():
        c.require(record['complete'] is True and attempted == completed == upper,
            'A finished native task must have completed every original execution.')
        for name, digest in record['files'].items():
            c.require(c.sha(Path(native)/name) == digest, 'An original completed native artifact changed.')
    return dict(attemptedLowerBound=attempted, completedLowerBound=completed,
        reservedUpperBound=upper, finished=finished.exists())


def close(protocol, profile_path, output, owner_record, observer_record):
    output, protocol = Path(output), Path(protocol)
    terminal, clock, launch = c.read(output/'terminal.json'), c.read(output/'clock.json'), c.read(output/'launch.json')
    owner, observer = c.read(owner_record), c.read(observer_record)
    c.require(terminal['status'] in ('completed', 'failed', 'interrupted') and
        terminal['protocolSha256'] == launch['protocolSha256'] == clock['protocolSha256'] == c.sha(protocol) and
        observer['clockSha256'] == c.sha(output/'clock.json') and
        (output/'protocol-copy.json').read_bytes() == protocol.read_bytes(),
        'Closure requires the exact original terminal, protocol and unchanged shared clock.')
    c.require(owner['controllerPid'] == launch['pid'] == observer['controllerPid'],
        'Original controller ownership must match the Terminal launch and metadata observer.')
    pids = {launch['pid'], owner['caffeinatePid'], observer['observerPid']}
    groups = {launch['processGroup']}
    for path in (output/'chunks').glob('*/*/owner.json'):
        row = c.read(path); pids.update((row['workerPid'], row['nativePid'])); groups.add(row['nativeProcessGroup'])
    c.require(not [row for row in processes() if int(row[0]) in pids or int(row[1]) in groups],
        'Every original native/controller/caffeine/observer owner must exit before read-only closure.')
    c.require(c.sha(profile_path) == launch['profileSha256'], 'The originally executing local profile changed.')
    profile = m.verify_profile(profile_path)
    counts = []
    pins = dict(profile['pins'])
    for path in (output/'chunks').glob('*/*/source.json'):
        source = c.read(path)
        c.require(source['protocolSha256'] == c.sha(protocol), 'An original native source protocol changed.')
        counts.append(dict(task=str(path.parent.relative_to(output/'chunks')),
            **accounting(source, path.parent/'native')))
        prepared = path.parent/'native/prepared.json'
        if prepared.exists():
            for name, digest in c.read(prepared)['inputPins'].items():
                c.require(c.sha(name) == digest, 'An original declared model/native input changed.')
                pins[name] = digest
    if terminal['complete']:
        audit = c.read(output/'completion-audit.json')
        c.require(terminal['status'] == 'completed' and audit['passed'] is True and audit['complete'] is True and
            terminal['completionAuditSha256'] == c.sha(output/'completion-audit.json') and
            audit['clockSha256'] == c.sha(output/'clock.json') and audit['qualified'] is terminal['qualified'],
            'Completed qualification requires the exact sealed original audit, never a rerun.')
        for name, digest in audit['artifactPins'].items():
            c.require(c.sha(name) == digest, 'An originally audited artifact changed.')
    else:
        c.require(terminal['qualified'] is False, 'An interrupted run cannot claim qualification.')
    for path in output.rglob('*'):
        if path.is_file():
            pins[str(path)] = c.sha(path)
    for path in (Path(protocol), Path(profile_path), Path(owner_record), Path(observer_record)):
        pins[str(path)] = c.sha(path)
    return dict(kind='mac-qualification-physical-closure-1', passed=True,
        scienceComplete=terminal['complete'], qualified=terminal['qualified'],
        originalTerminalStatus=terminal['status'], protocolSha256=c.sha(protocol),
        clockSha256=c.sha(output/'clock.json'), originalClocksRenewed=False, auditReruns=0,
        ownedPids=sorted(pids), ownedGroups=sorted(groups), allOwnedProcessesExited=True,
        nativeCounters=counts, knownCompletedTerminalExecutions=terminal['knownCompletedTerminalExecutions'],
        actualCompletedLowerBound=sum(row['completedLowerBound'] for row in counts),
        actualAttemptedLowerBound=sum(row['attemptedLowerBound'] for row in counts),
        actualAttemptedUpperBound=sum(row['reservedUpperBound'] for row in counts),
        controllerReservedTerminalUpperBound=terminal['reservedTerminalUpperBound'], artifactPins=pins,
        newGames=0, newFits=0, modelForwards=0, effectRecordsParsed=0, checkedEpoch=time.time())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('protocol', 'profile', 'output', 'owner-record', 'observer-record', 'receipt'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    c.write(args.receipt, close(args.protocol, args.profile, args.output, args.owner_record, args.observer_record))
