#!/usr/bin/env python3
"""Bounded collection/evaluation of complete advantage origins; no fitting or model promotion.

Six single-core JVMs share an immutable source and original deadline. Complete records are atomic,
gzip-compressed and hashed; outcomes are never decoded by this operational supervisor.
Collection and evaluation can opt into batches of up to 64 complete records per JVM.
"""
import argparse
import concurrent.futures
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
GIB = 1024 ** 3
STOP = threading.Event()
SPACE_LOCK = threading.Lock()
LAST_SPACE_CHECK = 0.
NATIVE_MODES = {'collect': 'card-advantage-collect', 'evaluate': 'card-advantage-evaluate',
                'coupling': 'card-policy-coupling'}


def require(value, message):
    if not value:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_bytes())


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def atomic(path, data, immutable=True):
    path = Path(path)
    if immutable and path.exists():
        require(path.read_bytes() == data, 'Existing evidence differs; refusing overwrite: ' + str(path))
        return
    fd, name = tempfile.mkstemp(prefix='.advantage-', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def document(path, value, immutable=True):
    atomic(path, (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n').encode(), immutable)


def options(arguments=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mode', choices=tuple(NATIVE_MODES), required=True)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--runtime', type=Path, help='Installed training/lib directory; default sibling installation.')
    p.add_argument('--workers', type=int, default=6)
    p.add_argument('--batch-size', type=int, default=1,
                   help='Complete origins or games per JVM, 1–64. Default 1 preserves single-job execution.')
    p.add_argument('--hours', type=float, required=True)
    p.add_argument('--deadline-epoch', type=float, required=True, help='Original workflow deadline, never renewed on resume.')
    p.add_argument('--data-root', type=Path, required=True, help='Whole experiment directory subject to4GiB data cap.')
    value = p.parse_args(arguments)
    value.source = value.source.resolve(strict=True)
    value.output = value.output.resolve()
    value.data_root = value.data_root.resolve(strict=True)
    default = Path(__file__).resolve().parent / '../lib'
    if not default.is_dir():
        default = Path(__file__).resolve().parent.parent / 'training/build/install/training/lib'
    value.runtime = (value.runtime or default).resolve(strict=True)
    require(value.runtime.is_dir(), 'Runtime must be the exact installed training/lib directory.')
    max_workers = min(6, max(1, int((os.cpu_count() or 1) * .8) - 2))
    require(1 <= value.workers <= max_workers, f'Use1–{max_workers} single-core JVM workers.')
    require(1 <= value.batch_size <= 64, 'Batch size must be 1–64 for collection or evaluation.')
    require(math.isfinite(value.hours) and 0 < value.hours <= 12, 'Stage hours must be finite and within(0,12].')
    require(math.isfinite(value.deadline_epoch) and value.deadline_epoch <= time.time() + 12 * 3600 + 5,
            'The original workflow deadline must be finite and at most12hours ahead.')
    require(value.output != value.source and value.output not in value.source.parents,
            'Output may not contain its frozen source.')
    require(value.data_root == value.output or value.data_root in value.output.parents,
            'Output must be within the experiment data-budget directory.')
    return value


def resources(root, force=False):
    global LAST_SPACE_CHECK
    with SPACE_LOCK:
        if not force and time.monotonic() - LAST_SPACE_CHECK < 5:
            return
        require(shutil.disk_usage(root).free >= 8 * GIB, 'Stopped: fewer than8GiB remain free.')
        size = sum(p.stat().st_size for p in root.rglob('*') if p.is_file())
        require(size <= 4 * GIB, 'Stopped: experiment data exceeds4GiB; no records are dropped.')
        LAST_SPACE_CHECK = time.monotonic()


def runtime(value):
    home = os.environ.get('JAVA_HOME')
    require(home, 'Set JAVA_HOME explicitly to the pinned JDK21 installation.')
    java = (Path(home) / 'bin/java').resolve(strict=True)
    jars = sorted(value.runtime.glob('*.jar'))
    require(len(jars) >= 3, 'Install the native training distribution before running.')
    version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True, timeout=10)
    require('version "21.' in version, 'The advantage experiment requires its pinned Java21 runtime.')
    identity = {'java': str(java), 'javaSha256': sha(java), 'javaVersion': version,
        'jars': {str(p): sha(p) for p in jars}, 'supervisorSha256': sha(Path(__file__)),
        'flags': ['-Xmx1g', '-XX:ActiveProcessorCount=1', '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1']}
    return [str(java), *identity['flags'], '-cp', str(value.runtime / '*'),
            'dev.aragos.aor.training.TrainingMainKt'], identity


def check_runtime(identity):
    require(sha(identity['java']) == identity['javaSha256'] and sha(Path(__file__)) == identity['supervisorSha256'],
            'The frozen Java runtime or supervisor changed.')
    for path, expected in identity['jars'].items():
        require(sha(path) == expected, 'Frozen native JAR changed: ' + path)
    parent = Path(next(iter(identity['jars']))).parent
    require(sorted(str(x) for x in parent.glob('*.jar')) == sorted(identity['jars']), 'Runtime JAR membership changed.')


def clock(value, identity):
    path = value.output / 'started.json'
    bound = value.output / 'clock.binding.json'
    progress = list(value.output.glob('origin-*.json.gz')) + list(value.output.glob('game-*.json.gz'))
    if not path.exists():
        require(not progress and not bound.exists(), 'Original stage clock is missing after progress.')
        now = time.time()
        document(path, {'startedAt': now, 'deadline': min(now + value.hours * 3600, value.deadline_epoch),
                        'workflowDeadline': value.deadline_epoch, 'hours': value.hours, 'identity': identity})
    saved = read(path)
    require(saved['identity'] == identity and saved['workflowDeadline'] == value.deadline_epoch and saved['hours'] == value.hours
            and all(type(saved[k]) in (int, float) and math.isfinite(saved[k]) for k in ('startedAt', 'deadline'))
            and 0 < saved['startedAt'] <= time.time() + 5
            and saved['deadline'] == min(saved['startedAt'] + value.hours * 3600, value.deadline_epoch),
            'Original stage identity, allocation or deadline changed.')
    require(bound.exists() or not progress, 'Original stage clock binding is missing after progress.')
    document(bound, {'startedSha256': sha(path)})
    return saved['deadline']


def snapshots(value, manifest):
    sources = {'source.json': {'path': str(value.source), 'sha256': manifest['sourceSha256']},
               'baseline.json': manifest['baseline'], 'frozen-purchase-policy.json': manifest['frozenPurchasePolicy']}
    if value.mode in ('collect', 'coupling') and manifest['continuation'] is not None:
        sources['continuation.json'] = manifest['continuation']
    if value.mode == 'evaluate':
        for name, policy in manifest['policies'].items():
            if policy.get('path') is not None:
                sources[f'policy-{name}.json'] = policy
    for filename, item in sources.items():
        require(sha(item['path']) == item['sha256'], 'A frozen policy/source changed after native validation.')
        atomic(value.output / filename, Path(item['path']).read_bytes())
    return {item['path']: item['sha256'] for item in sources.values()}


def record(value, index):
    prefix = 'origin' if value.mode in ('collect', 'coupling') else 'game'
    path = value.output / f'{prefix}-{index}.json.gz'
    return path, path.with_suffix(path.suffix + '.binding.json')


def binding(value, index):
    path, bound = record(value, index)
    result = {'index': index, 'file': path.name, 'sha256': sha(path)}
    document(bound, result)
    return result


def launch_native(value, base, selection, log_name, deadline):
    """One owned process, with the original deadline covering every index in its selection."""
    require(not STOP.is_set() and time.time() < deadline, 'Original deadline elapsed or another worker failed.')
    resources(value.data_root)
    command = base + ['--mode', NATIVE_MODES[value.mode], '--source', str(value.source),
        '--output', str(value.output), *selection, '--hours', str((deadline - time.time()) / 3600)]
    with (value.output / log_name).open('ab') as log:
        child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        try:
            while child.poll() is None:
                require(not STOP.is_set() and time.time() < deadline, 'Original deadline elapsed or collection was canceled.')
                resources(value.data_root)
                time.sleep(.25)
            require(child.returncode == 0, f'Native execution failed; inspect {log_name}. No replacement world is allowed.')
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)


def execute(value, base, index, deadline):
    path, bound = record(value, index)
    if path.exists() and bound.exists():
        return binding(value, index)
    launch_native(value, base, ['--index', str(index)], f'worker-{index}.log', deadline)
    require(path.exists(), 'A successful native worker must publish its whole immutable record.')
    return binding(value, index)


def execute_batch(value, base, indices, deadline):
    """Keep per-record bindings; a failed batch leaves completed native records resumable."""
    require(1 <= len(indices) <= 64 and len(set(indices)) == len(indices)
            and all(type(index) is int and index >= 0 for index in indices),
            'A batch requires 1–64 distinct job indices.')
    if len(indices) == 1:
        return [execute(value, base, indices[0], deadline)]
    pending = []
    for index in indices:
        path, bound = record(value, index)
        if path.exists() and bound.exists():
            binding(value, index)
        else:
            pending.append(index)
    if pending:
        launch_native(value, base, ['--indices', ','.join(map(str, pending))],
               f'worker-batch-{indices[0]}-{indices[-1]}.log', deadline)
    require(all(record(value, index)[0].exists() for index in indices),
            'A successful batch must publish every requested complete record.')
    return [binding(value, index) for index in indices]


def run(value):
    value.output.mkdir(parents=True, exist_ok=True)
    resources(value.data_root, True)
    base, native = runtime(value)
    identity = {'source': str(value.source), 'sourceSha256': sha(value.source), 'mode': value.mode,
                'workers': value.workers, 'runtime': native}
    if value.batch_size != 1:
        identity['batchSize'] = value.batch_size
    identity_path = value.output / 'execution.json'
    if not identity_path.exists():
        require(not any(p.name not in {'.advantage.lock'} for p in value.output.iterdir()),
                'A fresh output may not adopt unrelated or unbound prior evidence.')
    document(identity_path, identity)
    deadline = clock(value, identity)
    manifest = json.loads(subprocess.check_output(base + ['--mode', NATIVE_MODES[value.mode],
        '--source', str(value.source), '--describe', 'true'], text=True, timeout=30))
    require(manifest['sourceSha256'] == identity['sourceSha256'], 'Native source identity differs.')
    inputs = snapshots(value, manifest)
    document(value.output / 'collection.json', manifest)
    jobs = manifest['origins'] if value.mode in ('collect', 'coupling') else manifest['jobs']
    count = len(jobs)
    prefix = 'origin' if value.mode in ('collect', 'coupling') else 'game'
    require({p.name for p in value.output.glob(prefix + '-*.json.gz')} <= {f'{prefix}-{i}.json.gz' for i in range(count)},
            'Output contains an unexpected world/job index.')
    completed = []
    def progress(phase):
        document(value.output / 'state.json', {'kind': manifest['kind'], 'signature': manifest['signature'],
            'phase': phase, 'completed': len(completed), 'total': count, 'deadline': deadline}, immutable=False)
    progress('running')
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=value.workers) as pool:
            chunks = [list(range(start, min(start + value.batch_size, count)))
                      for start in range(0, count, value.batch_size)]
            futures = [pool.submit(execute_batch, value, base, indices, deadline) for indices in chunks]
            try:
                for future in concurrent.futures.as_completed(futures):
                    completed.extend(future.result())
                    progress('running')
                    print(json.dumps({'completed': len(completed), 'total': count, 'lastIndex': completed[-1]['index']}), flush=True)
            except BaseException:
                STOP.set()
                for future in futures:
                    future.cancel()
                raise
        check_runtime(native)
        require(all(sha(path) == expected for path, expected in inputs.items()), 'Frozen source/policy bytes changed during execution.')
        rows = [binding(value, i) for i in range(count)]
        result = {'kind': manifest['kind'], 'signature': manifest['signature']}
        if value.mode in ('collect', 'coupling'):
            result.update(origins=count, originSha256=[row['sha256'] for row in rows])
        else:
            result.update(games=count, files=rows)
        document(value.output / 'finished.json', result)
        progress('complete')
    except BaseException:
        progress('incomplete')
        raise


def main(arguments=None):
    value = options(arguments)
    value.output.mkdir(parents=True, exist_ok=True)
    def canceled(*_):
        STOP.set()
        raise KeyboardInterrupt('Advantage execution canceled.')
    signal.signal(signal.SIGTERM, canceled)
    with (value.output / '.advantage.lock').open('a+') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        run(value)


if __name__ == '__main__':
    main()
