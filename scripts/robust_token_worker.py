"""One bounded original native task; stdin request and compact lossless stdout transport."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import subprocess
import sys
import threading
import time

from robust_token_campaign import read, require, sha, write


def census(root):
    return sum(path.stat().st_size for path in Path(root).rglob('*') if path.is_file())


def group_rss(group):
    rows = subprocess.check_output(['/bin/ps', '-axo', 'pgid=,rss='], text=True).splitlines()
    return sum(int(row.split()[1])*1024 for row in rows
        if len(row.split()) == 2 and int(row.split()[0]) == group)


def cancel_active(profile_path):
    """Stop only still-identical native PIDs recorded by this explicit campaign owner."""
    profile = read(profile_path)
    stopped = []
    for path in (Path(profile['root'])/'tasks').glob('*/*/*/owner.json'):
        owner = read(path)
        pid = owner['nativePid']
        state = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'pgid=,command='],
            capture_output=True, text=True, check=False)
        if state.returncode != 0 or not state.stdout.strip(): continue
        parts = state.stdout.strip().split(None, 1)
        require(int(parts[0]) == owner['nativeProcessGroup'] == pid and
            'dev.aragos.aor.training.TrainingMainKt' in parts[1] and
            str(path.parent/'source.json') in parts[1],
            'A recorded native PID has a different current identity; preserve its owner.')
        os.killpg(pid, signal.SIGTERM)
        stopped.append(pid)
    return stopped


def inspect(profile_path):
    """Metadata and physical hashes only; never open native game or metric records."""
    profile = read(profile_path)
    for path,digest in profile['pins'].items():
        require(sha(path) == digest, 'An original frozen host input changed.')
    groups = set(); pids = set()
    for path in (Path(profile['root'])/'tasks').glob('*/*/*/owner.json'):
        original=read(path);groups.add(original['nativeProcessGroup']);pids.add(original['workerPid'])
    output=subprocess.check_output(['/bin/ps','-axo','pid=,pgid=,stat=,comm='],text=True).splitlines()
    remaining=[]
    for line in output:
        parts=line.split(None,3)
        if len(parts)>=3 and (int(parts[0]) in pids or int(parts[1]) in groups):
            remaining.append(dict(pid=int(parts[0]),group=int(parts[1]),state=parts[2]))
    clock=Path(profile['root'])/'clock.json'
    return dict(kind='robust-token-host-metadata-1',host=profile['host'],checkedEpoch=time.time(),
        profileSha256=sha(profile_path),inputPinsRehashed=len(profile['pins']),
        allOriginalInputsUnchanged=True,ownedNativeGroups=sorted(groups),ownedWorkerPids=sorted(pids),
        remainingProcesses=remaining,allOwnedProcessesExited=not remaining,
        originalClockSha256=sha(clock) if clock.exists() else None,
        newGames=0,newFits=0,modelForwards=0,effectReads=0)


def execute(profile_path, request, now=time.time):
    profile_path = Path(profile_path)
    require(sha(profile_path) == request['profileSha256'], 'The actual executing host profile changed.')
    profile = read(profile_path)
    require(profile['kind'] == 'robust-token-host-1' and profile['host'] in ('DESKTOP', 'MAC') and
        profile['workers'] in range(1, os.cpu_count()+1), 'Native worker and host capacity must be explicit.')
    require(profile['pins'] and all(sha(path) == digest for path, digest in profile['pins'].items()),
        'Every executing runtime/model/source input must retain its original pinned bytes.')
    deadline = request['clock']['deadlineEpoch']
    require(deadline > now(), 'No native task may start after the original science deadline.')
    task_id = PurePosixPath(request['taskId'])
    require(not task_id.is_absolute() and len(task_id.parts) == 3 and
        all(part not in ('.', '..') for part in task_id.parts), 'A task needs a confined stage/case/chunk identifier.')
    base = Path(profile['root'])
    clock_path = base/'clock.json'
    if clock_path.exists():
        require(clock_path.read_bytes() == __import__('robust_token_campaign').canonical(request['clock']),
            'A host cannot renew or replace its original shared science clock.')
    else: write(clock_path,request['clock'])
    require(shutil.disk_usage(base).free >= profile['minimumFreeBytes'],
        'The host must retain its original free-space floor before starting a chunk.')
    require(census(base) < profile['maximumDataBytes'], 'The host scientific directory reached its fixed byte cap.')
    directory = base/'tasks'/task_id
    require(not directory.exists(), 'A native task is once-only; existing task directories cannot be retried.')
    directory.mkdir(parents=True)
    source = directory/'source.json'
    write(source, request['source'])
    for key in ('inferenceAsset', 'nomination', 'arena', 'candidateBundle'):
        item = request['source'].get(key)
        if item is not None:
            require(sha(item['path']) == item['sha256'], 'An explicitly declared native input changed.')
    output = directory/'native'
    remaining = max(1, int(deadline-now()))
    command = [profile['java'], '-Xmx'+str(profile['heapMiB'])+'m', '-XX:ActiveProcessorCount=1',
        '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1', '-cp', profile['classpath'],
        'dev.aragos.aor.training.TrainingMainKt', '--mode', 'robust-token-screen-chunk',
        '--source', str(source), '--output', str(output), '--seconds', str(remaining),
        '--workers', str(profile['workers'])]
    process = None
    interrupted = threading.Event()
    previous = {}

    def stop(*_):
        interrupted.set()
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)

    for event in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        previous[event] = signal.signal(event, stop)
    stopped_reason = None
    try:
        with (directory/'native.log').open('xb') as log:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, start_new_session=True, close_fds=True)
            write(directory/'owner.json', dict(kind='robust-token-native-owner-1',
                workerPid=os.getpid(), nativePid=process.pid, nativeProcessGroup=process.pid,
                taskId=request['taskId'], deadlineEpoch=deadline, sourceSha256=sha(source)))
            while process.poll() is None:
                if interrupted.is_set(): stopped_reason = 'controller-interrupted'
                elif now() >= deadline: stopped_reason = 'original-deadline'
                elif shutil.disk_usage(base).free < profile['minimumFreeBytes']: stopped_reason = 'free-space-floor'
                elif census(base) >= profile['maximumDataBytes']: stopped_reason = 'scientific-byte-cap'
                elif group_rss(process.pid) > profile['maximumRssBytes']: stopped_reason = 'group-memory-cap'
                if stopped_reason:
                    stop()
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=5)
                    break
                time.sleep(min(1, max(.01, deadline-now())))
        require(stopped_reason is None and process.returncode == 0,
            'The original native task did not complete: '+str(stopped_reason or process.returncode))
        finished = read(output/'finished.json')
        require(finished['complete'] is True, 'An incomplete original native collection cannot be transported.')
        files = {}; size = 0
        for path in sorted(output.rglob('*')):
            require(not path.is_symlink(), 'Native output cannot introduce symlinks.')
            if not path.is_file(): continue
            raw = path.read_bytes(); size += len(raw)
            require(size <= 64*1024**2, 'A chunk exceeds its compact64MiB transport allowance.')
            files[str(path.relative_to(output))] = dict(sha256=hashlib.sha256(raw).hexdigest(),
                base64=base64.b64encode(raw).decode('ascii'))
        result = dict(kind='robust-token-worker-result-1', complete=True,
            taskId=request['taskId'], profileSha256=request['profileSha256'], files=files)
        write(directory/'transport.json', dict(kind='robust-token-transport-receipt-1',
            complete=True, taskId=request['taskId'], finishedSha256=sha(output/'finished.json'),
            files={key:value['sha256'] for key,value in files.items()}, bytes=size,
            completedEpoch=now(), originalDeadlineEpoch=deadline))
        return result
    except BaseException as error:
        stop()
        if process is not None and process.poll() is None:
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=5)
        write(directory/'failed.json', dict(kind='robust-token-task-failure-1',
            taskId=request['taskId'], reason=type(error).__name__+': '+str(error),
            nativeProcessExited=process is None or process.poll() is not None, updatedEpoch=now()))
        raise
    finally:
        for event, handler in previous.items(): signal.signal(event, handler)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--cancel-active', action='store_true')
    parser.add_argument('--inspect', action='store_true')
    args = parser.parse_args()
    if args.inspect:
        print(json.dumps(inspect(args.profile)))
    elif args.cancel_active:
        print(json.dumps({'stoppedNativeGroups': cancel_active(args.profile)}))
    else:
        request = json.load(sys.stdin)
        print(json.dumps(execute(args.profile, request), separators=(',', ':'), allow_nan=False))
