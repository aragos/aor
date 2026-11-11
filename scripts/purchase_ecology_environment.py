"""Exact numerical identity for the existing CPU purchase-learning environments.

The venv launcher path controls prefix discovery. Keep it distinct from the resolved
interpreter executable, and retain site-packages while excluding user customization.
"""
import argparse
from dataclasses import asdict
import json
import os
from pathlib import Path
import subprocess
import sys
import sysconfig

from automata_experiments import require, sha


def interpreter_files():
    """Pin the interpreter, shared library and standard library, excluding caches."""
    executable = Path(sys.executable).resolve(strict=True)
    paths = {executable}
    library = Path(sysconfig.get_config_var('LIBDIR') or '') / (sysconfig.get_config_var('LDLIBRARY') or '')
    if library.is_file():
        paths.add(library.resolve())
    framework = sysconfig.get_config_var('PYTHONFRAMEWORK')
    if framework:
        binary = Path(sys.base_prefix) / framework
        if binary.is_file():
            paths.add(binary.resolve())
    standard = Path(sysconfig.get_path('stdlib')).resolve(strict=True)
    for directory, folders, names in os.walk(standard):
        folders[:] = [name for name in folders if name not in {'site-packages', 'dist-packages', '__pycache__'}]
        paths.update((Path(directory) / name).resolve() for name in names
                     if name.endswith(('.py', '.so', '.dylib', '.zip')))
    return standard, paths


def current():
    require(sys.flags.ignore_environment and sys.flags.no_user_site
            and not sys.flags.no_site and sys.dont_write_bytecode,
            'Numerical children require -E -s -B with the pinned venv site-packages enabled.')
    prefix, base = Path(sys.prefix).resolve(), Path(sys.base_prefix).resolve()
    require(prefix != base and (prefix / 'pyvenv.cfg').is_file(),
            'Purchase learning requires the existing dedicated numerical venv.')
    import numpy
    import torch
    import purchase_policy
    require(torch.version.cuda is None, 'The purchase pilot uses the declared CPU environment.')
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.set_default_device('cpu')
    require(str(torch.get_default_device()) == 'cpu', 'Purchase tensors must default to CPU, never MPS.')
    library = Path(sysconfig.get_path('purelib')).resolve(strict=True)
    require(library.is_relative_to(prefix), 'Numerical package files must belong to the venv.')
    standard, paths = interpreter_files()
    paths.update(path.resolve() for path in library.rglob('*')
                 if path.is_file() and '__pycache__' not in path.parts and path.suffix not in ('.pyc', '.pyo'))
    # The already-installed desktop venv has separate pip receipts; Mac's original single
    # receipt and complete identity remain byte-compatible. No packages are installed here.
    install_names = (('torch-install.json', 'numpy-install.json') if sys.platform == 'linux'
                     else ('install-report.json',))
    for name in ('pyvenv.cfg', 'requirements.lock.txt', 'environment-receipt.json', *install_names):
        path = prefix / name
        require(path.is_file(), 'The existing numerical installation receipt is missing: ' + name)
        paths.add(path)
    executable = Path(sys.executable).absolute()
    installation = ({'installationReportSha256': sha(prefix / 'install-report.json')}
                    if sys.platform != 'linux' else
                    {'installationReportsSha256': {name: sha(prefix/name) for name in install_names}})
    return dict(executable=str(executable), resolvedExecutable=str(executable.resolve()),
        prefix=str(prefix), basePrefix=str(base), sitePackages=str(library), stdlib=str(standard),
        pythonVersion=sys.version, torchVersion=torch.__version__, numpyVersion=numpy.__version__,
        configDefaults=asdict(purchase_policy.UpdateConfig()), purchaseLearnerSha256=sha(purchase_policy.__file__),
        torchThreads=torch.get_num_threads(), deterministicAlgorithms=torch.are_deterministic_algorithms_enabled(),
        device='cpu', cuda=None, mpsUsed=False, gpuUsed=False,
        requirementsLockSha256=sha(prefix / 'requirements.lock.txt'),
        **installation,
        files={str(path): sha(path) for path in sorted(paths)})


def inspect(executable):
    executable = str(Path(executable).expanduser().absolute())
    child = subprocess.run([executable, '-E', '-s', '-B', str(Path(__file__).resolve()), 'inspect'],
                           capture_output=True, text=True, timeout=120, check=False)
    require(child.returncode == 0, 'Existing CPU environment inspection failed: ' + child.stderr[-2000:])
    identity = json.loads(child.stdout)
    require(identity['executable'] == executable and identity['torchThreads'] == 1
            and identity['deterministicAlgorithms'] and identity['device'] == 'cpu'
            and identity['cuda'] is None and not identity['mpsUsed'] and not identity['gpuUsed'],
            'The numerical launcher or deterministic CPU settings changed.')
    return identity


def verify(expected):
    require(current() == expected, 'Numerical venv, defaults, CPU settings or pinned library bytes changed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('inspect',))
    parser.parse_args()
    print(json.dumps(current(), sort_keys=True))
