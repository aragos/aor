"""Bounded engineering-only rerun of four already completed worlds; never qualification."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tarfile
import threading
import time

BASE = Path('/Users/aragos/Projects/aor/.aor-training')
NATIVE = BASE / 'automata-local-bid-mixture-20261118/native-source'
OLD = NATIVE / 'engineering-default-parity'
ASSET = BASE / 'automata-robust-league-20261117/mac-v3'
CASES = [('mixed', 0), ('mixed', 511), ('homogeneous', 0), ('homogeneous', 511)]
DEADLINE = datetime.datetime(2026, 11, 18, 16, 42, tzinfo=datetime.timezone.utc).timestamp()


def require(value, message):
    if not value:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()+b'\n'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    with path.open('xb') as stream:
        stream.write(canonical(value))


def build(folder):
    require(not folder.exists(), 'Use a new package directory.')
    folder.mkdir(parents=True)
    package = folder / 'package'
    package.mkdir()
    profile = json.loads((OLD / 'engineering-profile.json').read_bytes())
    selected = {Path(path): digest for path, digest in profile['pins'].items()
                if Path(path).is_relative_to(ASSET) and Path(path).suffix == '.json'}
    runtime = json.loads((NATIVE / 'runtime-manifest.json').read_bytes())
    selected.update({Path(path): digest for path, digest in runtime['runtimeFiles'].items()})
    copied = {}
    for path, digest in selected.items():
        require(sha(path) == digest, 'Only original pinned runtime and research assets may transfer.')
        relative = (Path('assets') / path.relative_to(ASSET) if path.is_relative_to(ASSET)
                    else Path('runtime/lib') / path.name)
        destination = package / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        copied[str(relative)] = dict(sha256=digest, originalPath=str(path), bytes=path.stat().st_size)
    cases = []
    for bank, index in CASES:
        original = OLD / 'cases' / bank / f'world-{index}'
        source = json.loads((original / 'source.json').read_bytes())
        require(source['count'] == 1 and source['globalStart'] == index and
                'tokenNeighborMixture' not in source and index in source['replayIndices'],
                'Only the unchanged four engineering worlds and their old reproductions are permitted.')
        for name in ('arena', 'inferenceAsset', 'nomination', 'candidateBundle'):
            row = source[name]
            path = Path(row['path'])
            require(path in selected and selected[path] == row['sha256'],
                    'Every source input must have a pinned portable copy.')
            row['path'] = str(Path('assets') / path.relative_to(ASSET))
        name = f'{bank}-{index}'
        source_path = package / f'{name}.json'
        write(source_path, source)
        worlds = [json.loads(line) for line in (original / 'native/worlds.jsonl').read_text().splitlines()]
        require(len(worlds) == 1, 'The existing engineering evidence must contain one world.')
        repro = [json.loads(line) for line in (original / 'native/reproductions.jsonl').read_text().splitlines()]
        require(len(repro) == 2 and all(row['matched'] for row in repro),
                'Both original role reproductions must have completed.')
        cases.append(dict(name=name, source=f'{name}.json', sourceSha256=sha(source_path),
            expectedWholeWorldSha256=hashlib.sha256(canonical(worlds[0])).hexdigest(),
            expectedReproductionsSha256=hashlib.sha256(canonical(repro)).hexdigest(),
            expectedScienceSha256=worlds[0]['scienceSha256'],
            originalWorldsSha256=sha(original / 'native/worlds.jsonl')))
    shutil.copyfile(__file__, package / 'driver.py')
    plan = dict(kind='native95-linux-existing-world-parity-1', cases=cases,
        maximumTerminalExecutions=16, maximumParallelJvms=2, heapMiB=6144, workersPerJvm=1,
        deadlineEpoch=DEADLINE, newQualificationWorlds=0, newTrainingWorlds=0,
        nativeJarSha256=runtime['nativeJar']['sha256'], copiedOriginals=copied)
    write(package / 'plan.json', plan)
    inventory = {str(path.relative_to(package)): sha(path)
                 for path in sorted(package.rglob('*')) if path.is_file()}
    write(package / 'inventory.json', inventory)
    archive = folder / 'package.tar.gz'
    with tarfile.open(archive, 'w:gz') as tar:
        tar.add(package, arcname='package')
    require(archive.stat().st_size <= 64*1024**2, 'Keep the transfer at or below64MiB compressed.')
    write(folder / 'transfer-plan.json', dict(archiveSha256=sha(archive),
        compressedBytes=archive.stat().st_size, inventorySha256=sha(package / 'inventory.json'),
        files=len(inventory), allowedContent='Frozen JVM jars, simulated policy assets, four request '
        'definitions and old simulated result hashes only; no human records, credentials or Mac JDK.'))
    print(json.dumps(dict(package=str(archive), bytes=archive.stat().st_size,
                          sha256=sha(archive), inventorySha256=sha(package / 'inventory.json'))), flush=True)


def execute(package):
    package = package.absolute()
    plan = json.loads((package / 'plan.json').read_bytes())
    require(plan['deadlineEpoch'] == DEADLINE and time.time() < DEADLINE-120,
            'Refuse a late launch or a changed original deadline.')
    for relative, digest in json.loads((package / 'inventory.json').read_bytes()).items():
        path = package / relative
        require(not path.is_symlink() and path.is_relative_to(package) and sha(path) == digest,
                'The complete transferred inventory must match before any game.')
    require(plan['maximumTerminalExecutions'] == 16 and len(plan['cases']) == 4,
            'The original16 engineering execution cap is immutable.')
    output = package.parent / 'execution'
    output.mkdir()
    write(output / 'driver-owner.json', dict(pid=os.getpid(), pgid=os.getpgrp(),
                                            startedEpoch=time.time(), deadlineEpoch=DEADLINE))
    java = Path('/usr/bin/java')
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True)
    require('21.' in version.stderr, 'Use an existing Java21 runtime.')
    mem = Path('/proc/meminfo').read_text()
    available = int(next(line.split()[1] for line in mem.splitlines() if line.startswith('MemAvailable:')))
    require(available >= 16*1024**2, 'Two bounded6GiB JVMs require at least16GiB available memory.')
    write(output / 'host.json', dict(java=str(java), javaRealPath=str(java.resolve()),
        javaSha256=sha(java), javaVersion=version.stderr, memAvailableKiB=available,
        cpus=os.cpu_count(), inventorySha256=sha(package / 'inventory.json')))
    active = {}
    lock = threading.Lock()
    stopped = threading.Event()
    def kill_all():
        stopped.set()
        with lock:
            for process in active.values():
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
    def command(argv, target, label):
        require(not stopped.is_set() and time.time() < DEADLINE, 'Stop at the immutable deadline.')
        env = {'PATH':'/usr/bin:/bin', 'HOME':str(Path.home()), 'LANG':'C.UTF-8', 'TZ':'UTC'}
        with (target / f'{label}.log').open('xb') as log:
            process = subprocess.Popen(argv, cwd=package, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            with lock:
                active[process.pid] = process
            write(target / f'{label}-owner.json', dict(pid=process.pid, pgid=process.pid,
                                                     argv=argv, deadlineEpoch=DEADLINE))
            try:
                status = process.wait(timeout=max(.01, DEADLINE-time.time()))
                require(status == 0, 'Native command failed; do not retry or substitute worlds.')
            except BaseException:
                kill_all()
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise
            finally:
                with lock:
                    active.pop(process.pid, None)
    def case(row):
        require(not stopped.is_set(), 'Never start another case after a failed case.')
        folder = output / row['name']
        folder.mkdir()
        base = [str(java), '-Xms128m', '-Xmx6144m', '-XX:ActiveProcessorCount=1',
                '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1', '-cp',
                str(package / 'runtime/lib/*'), 'dev.aragos.aor.training.TrainingMainKt']
        source = str(package / row['source'])
        command(base+['--mode','robust-token-prepare','--source',source,'--output',
                      str(folder / 'preflight')], folder, 'prepare')
        prepared = json.loads((folder / 'preflight/prepared.json').read_bytes())
        require(prepared['scienceSha256'] == row['expectedScienceSha256'],
                'Portable preparation must preserve the exact old scientific identity.')
        write(folder / 'execution-reserved.json', dict(terminalExecutionUpperBound=4))
        seconds = max(1, math.floor(DEADLINE-time.time()))
        command(base+['--mode','robust-token-screen-chunk','--source',source,'--output',
                      str(folder / 'native'),'--seconds',str(seconds),'--workers','1'], folder, 'native')
        finished = json.loads((folder / 'native/finished.json').read_bytes())
        worlds = [json.loads(line) for line in (folder / 'native/worlds.jsonl').read_text().splitlines()]
        repro = [json.loads(line) for line in (folder / 'native/reproductions.jsonl').read_text().splitlines()]
        require(len(worlds) == 1 and len(repro) == 2 and all(x['matched'] for x in repro),
                'One original world and both role reproductions must complete.')
        require(hashlib.sha256(canonical(worlds[0])).hexdigest() == row['expectedWholeWorldSha256'] and
                hashlib.sha256(canonical(repro)).hexdigest() == row['expectedReproductionsSha256'],
                'Full normalized world, diagnostics and canonical repro evidence must match exactly.')
        write(folder / 'match.json', dict(passed=True, terminalExecutions=4,
            finishedSha256=sha(folder / 'native/finished.json'),
            wholeWorldSha256=row['expectedWholeWorldSha256'],
            reproductionsSha256=row['expectedReproductionsSha256']))
        return row['name']
    passed = False
    matches = []
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(case, row) for row in plan['cases']]
            for future in futures:
                matches.append(future.result())
        passed = len(matches) == 4
    finally:
        kill_all()
        reserved = len(list(output.glob('*/execution-reserved.json'))) * 4
        completed = len(list(output.glob('*/native/finished.json'))) * 4
        write(output / 'closure.json', dict(kind='native95-linux-parity-closure-1', passed=passed,
            matches=matches, actualCompletedLowerBound=completed, attemptedUpperBound=reserved,
            allOwnedProcessesExited=not active, finishedEpoch=time.time(), deadlineEpoch=DEADLINE,
            newQualificationWorlds=0, scientificEvidence=False,
            policyAndChanceCalls='Actual16 old engineering games if complete; individual calls not instrumented.'))
    print(json.dumps(dict(passed=passed, closureSha256=sha(output / 'closure.json'))), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--execute', type=Path)
    args = parser.parse_args()
    require(bool(args.build) != bool(args.execute), 'Choose one explicit build or execute mode.')
    if args.build:
        build(args.build.absolute())
    else:
        execute(args.execute)
