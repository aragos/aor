"""One-hour, one-attempt GPU benchmark supervisor and exact environment receipt.

Preflight checks synthetic CPU/GPU math only. It cannot construct the retained-data cache,
query retained labels or fit either scientific model. A run requires separately reviewed hashes.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import platform
import shutil
import signal
import subprocess
import sys
import sysconfig
import time

import purchase_value_data as data

GIB = 1024**3
VARIABLES = dict(OMP_NUM_THREADS='2', MKL_NUM_THREADS='2', OPENBLAS_NUM_THREADS='2',
                 NUMEXPR_NUM_THREADS='2', CUBLAS_WORKSPACE_CONFIG=':4096:8',
                 CUDA_VISIBLE_DEVICES='0', PYTHONDONTWRITEBYTECODE='1')


def files(directory):
    return sorted(p for p in Path(directory).rglob('*') if p.is_file()
                  and '__pycache__' not in p.parts and not p.name.endswith('.pyc'))


def environment(executable):
    """Inspect from the dedicated venv; preserve its executable path, prefix and CUDA identity."""
    import numpy as np
    import torch
    prefix = Path(sys.prefix).resolve()
    data.require(prefix != Path(sys.base_prefix).resolve() and Path(executable).parent.parent.resolve() == prefix,
                 'Numerical environment must retain the dedicated venv executable/prefix.')
    stdlib = Path(sysconfig.get_path('stdlib')).resolve()
    paths = files(prefix) + files(stdlib)
    # Exclude only mutable install logs/temp; immutable locks/reports remain pinned.
    paths = [p for p in paths if not ({'install-temp', '__pycache__'} & set(p.parts))
             and p.name not in ('install.log', 'environment.json', 'synthetic-preflight.log')]
    paths += [Path(sys.executable).resolve(), Path('/usr/lib/wsl/lib/libcuda.so.1').resolve(),
              Path('/usr/lib/wsl/lib/nvidia-smi').resolve()]
    pins = {str(p): data.sha(p) for p in sorted(set(paths))}
    driver = subprocess.check_output(['/usr/lib/wsl/lib/nvidia-smi', '--query-gpu=driver_version',
                                      '--format=csv,noheader'], text=True).strip()
    data.require(torch.cuda.is_available(), 'The isolated runtime must expose the existing CUDA GPU.')
    return dict(executable=str(Path(executable).absolute()), prefix=str(prefix), basePrefix=sys.base_prefix,
                python=sys.version, torch=torch.__version__, numpy=np.__version__, cuda=torch.version.cuda,
                gpu=torch.cuda.get_device_name(0), capability=list(torch.cuda.get_device_capability(0)),
                driver=driver, files=pins, treeRoots=[str(prefix), str(stdlib)], cpuThreads=2,
                dtype='float32', amp=False, tf32=False, deterministic=True, variables=VARIABLES,
                venvMaximumBytes=12*GIB, freeMinimumBytes=8*GIB)


def verify_environment(expected, *, variables=None):
    import numpy as np
    import torch
    data.require(str(Path(sys.executable).absolute()) == expected['executable']
                 and str(Path(sys.prefix).resolve()) == expected['prefix'] and sys.version == expected['python']
                 and torch.__version__ == expected['torch'] and np.__version__ == expected['numpy']
                 and torch.version.cuda == expected['cuda'] and torch.cuda.is_available()
                 and torch.cuda.get_device_name(0) == expected['gpu']
                 and list(torch.cuda.get_device_capability(0)) == expected['capability'],
                 'The pinned numerical interpreter, CUDA stack or GPU changed.')
    required_variables = VARIABLES if variables is None else variables
    data.require(all(os.environ.get(k) == v for k, v in required_variables.items()),
                 'The numerical child requires exact deterministic/thread environment settings.')
    for name, digest in expected['files'].items():
        data.require(data.sha(name) == digest, 'Numerical environment file changed: '+name)
    actual = set()
    for root in expected['treeRoots']:
        actual.update(str(p) for p in files(root) if not ({'install-temp', '__pycache__'} & set(p.parts))
                      and p.name not in ('install.log', 'environment.json', 'synthetic-preflight.log'))
    data.require(actual <= set(expected['files']), 'The frozen Python/venv tree gained unpinned files.')
    driver = subprocess.check_output(['/usr/lib/wsl/lib/nvidia-smi', '--query-gpu=driver_version',
                                      '--format=csv,noheader'], text=True).strip()
    data.require(driver == expected['driver'], 'Existing GPU driver identity changed.')


def verify_package(root, expected):
    manifest = root/'manifest.json'
    data.require(data.sha(manifest) == expected, 'Frozen benchmark manifest changed.')
    contents = data.read(manifest)
    for name, digest in contents['files'].items():
        path = Path(name)
        data.require(not path.is_absolute() and '..' not in path.parts and data.sha(root/path) == digest,
                     'A frozen benchmark input changed or escaped its package.')
    protocol = data.read(root/'protocol.json')
    data.require(protocol['kind'] == 'purchase-state-value-benchmark-1'
                 and protocol['maximumSeconds'] == 3600 and protocol['maximumAttempts'] == 1
                 and protocol['maximumDataBytes'] == 6*GIB and protocol['minimumFreeBytes'] == 8*GIB
                 and protocol['maximumEnvironmentBytes'] == 12*GIB and protocol['cpuThreads'] == 2
                 and protocol['productionActivation'] is False and protocol['qualificationPassed'] is False
                 and protocol['actorTraining'] is False and protocol['actionQ'] is False,
                 'Benchmark limits, one-attempt or value-only scope changed.')
    data.require(data.sha(root/'corpus.json') == protocol['corpusSha256'], 'Corpus binding changed.')
    data.Config(**protocol['config']).validate()
    data.verify_declaration(data.read(root/'corpus.json'))
    return protocol


def guard(root, deadline, protocol):
    data.require(time.time() < deadline, 'The original benchmark deadline expired.')
    data.require(shutil.disk_usage(root).free >= protocol['minimumFreeBytes'], 'Free disk fell below 8 GiB.')
    size = sum(p.stat().st_size for p in (root/'run').rglob('*') if p.is_file())
    data.require(size <= protocol['maximumDataBytes'], 'Derived cache/output exceeded 6 GiB.')


def clean_environment():
    return dict(PATH='/usr/bin:/bin:/usr/lib/wsl/lib', LANG='C.UTF-8', **VARIABLES)


def synthetic(output, bindings):
    """CPU/GPU forward and gradient parity on generated states; no retained states/labels."""
    import numpy as np
    import torch
    import purchase_value_benchmark as benchmark
    benchmark.configure()
    generator = torch.Generator().manual_seed(1)
    x = torch.rand((19, data.STATE_COUNT), generator=generator, dtype=torch.float32)*2-1
    y = torch.linspace(0, 1, 19)
    comparisons = []
    for widths in ((64,), (256, 256)):
        cpu = benchmark.Critic(widths, 2)
        gpu = benchmark.Critic(widths, 2).cuda()
        yp, yg = cpu(x), gpu(x.cuda())
        (yp-y).square().mean().backward()
        (yg-y.cuda()).square().mean().backward()
        torch.testing.assert_close(yp, yg.cpu(), rtol=2e-5, atol=2e-6)
        worst = 0.
        for p, q in zip(cpu.parameters(), gpu.parameters()):
            torch.testing.assert_close(p.grad, q.grad.cpu(), rtol=2e-4, atol=2e-6)
            worst = max(worst, float((p.grad-q.grad.cpu()).abs().max()))
        comparisons.append(dict(widths=list(widths), parameters=sum(p.numel() for p in cpu.parameters()),
                                maximumGradientAbsoluteDifference=worst,
                                maximumOutputAbsoluteDifference=float((yp-yg.cpu()).abs().max())))
    # Per-game weighting is invariant to repeating the states of one complete game.
    first = np.asarray([.1, .4]); repeated = np.asarray([.1, .1, .1, .4])
    data.require(np.isclose(np.mean(first), np.sum(repeated*np.asarray([1/3, 1/3, 1/3, 1]))/2),
                 'Synthetic whole-game weighting changed under row duplication.')
    data.document(output, dict(kind='purchase-value-synthetic-preflight-1', passed=True,
                               comparisons=comparisons, bindings=bindings, actualFitGames=0, retainedLabelsOpened=0,
                               actualModelFits=0, cudaAvailable=True))


def child(root, manifest_sha, deadline):
    protocol = verify_package(root, manifest_sha)
    verify_environment(data.read(root/'environment.json'))
    import purchase_value_benchmark as benchmark
    benchmark.run(root, root/'run', lambda: guard(root, deadline, protocol))


def run(root, manifest_sha):
    protocol = verify_package(root, manifest_sha)
    expected = data.read(root/'environment.json')
    proof = data.read(root/'synthetic-preflight.json')
    data.require(proof['passed'] and proof['bindings'] == dict(
        manifestSha256=data.sha(root/'preflight-manifest.json'),
        protocolSha256=data.sha(root/'protocol.json'), environmentSha256=data.sha(root/'environment.json')),
        'Run requires the exact successful synthetic preflight bound to its frozen inputs.')
    data.require(not (root/'run').exists() and not (root/'attempt.json').exists(),
                 'One explicit benchmark attempt only; no resume or automatic successor.')
    data.require(shutil.disk_usage(root).free >= protocol['minimumFreeBytes'], 'Insufficient free disk.')
    started = time.time(); deadline = started+protocol['maximumSeconds']
    data.document(root/'attempt.json', dict(startedEpoch=started, deadlineEpoch=deadline,
                                          manifestSha256=manifest_sha, maximumAttempts=1))
    (root/'run').mkdir()
    command = [expected['executable'], '-E', '-s', '-B', str(root/'scripts/purchase_value_campaign.py'),
               'child', '--root', str(root), '--manifest-sha', manifest_sha, '--deadline', str(deadline)]
    with (root/'run/child.log').open('x') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   env=clean_environment(), start_new_session=True)
        data.document(root/'run/launch.json', dict(pid=process.pid, processGroup=process.pid, command=command,
                                                 startedEpoch=started, deadlineEpoch=deadline))
        try:
            while process.poll() is None:
                guard(root, deadline, protocol)
                time.sleep(2)
        except BaseException:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise
    data.document(root/'exit.json', dict(exitCode=process.returncode, finishedEpoch=time.time(),
                                        originalDeadlineEpoch=deadline, productionActivation=False))
    data.require(process.returncode == 0, 'Benchmark stopped; preserve partial artifacts without retry.')
    data.require((root/'run/finished.json').exists(), 'Successful child omitted final benchmark receipt.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('inspect-environment', 'preflight', 'child', 'run'))
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--manifest-sha')
    parser.add_argument('--deadline', type=float)
    options = parser.parse_args()
    root = options.root.resolve()
    if options.mode == 'inspect-environment':
        data.document(root/'environment.json', environment(sys.executable))
    elif options.mode == 'preflight':
        verify_package(root, options.manifest_sha)
        verify_environment(data.read(root/'environment.json'))
        synthetic(root/'synthetic-preflight.json', dict(manifestSha256=options.manifest_sha,
            protocolSha256=data.sha(root/'protocol.json'), environmentSha256=data.sha(root/'environment.json')))
    elif options.mode == 'child':
        child(root, options.manifest_sha, options.deadline)
    else:
        run(root, options.manifest_sha)


if __name__ == '__main__':
    main()
