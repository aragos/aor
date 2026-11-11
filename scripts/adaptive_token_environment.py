"""Keep the isolated supervisor and the explicitly pinned numerical venv distinct."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import sysconfig

from automata_experiments import require, sha


def current_numerical():
    """Run only under the dedicated venv, with site enabled and user/site injection disabled."""
    require(sys.flags.ignore_environment and sys.flags.no_user_site
            and not sys.flags.no_site and sys.dont_write_bytecode,
            'Numerical children require -E -s -B, retaining their pinned venv site-packages.')
    prefix, base = Path(sys.prefix).resolve(), Path(sys.base_prefix).resolve()
    require(prefix != base and (prefix/'pyvenv.cfg').is_file(),
            'Use a dedicated existing virtual environment; the system interpreter is not the numerical identity.')
    import numpy
    import torch
    require(torch.__version__.endswith('+cpu') and torch.version.cuda is None,
            'This experiment keeps the existing CPU-only fitter and does not switch backend.')
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    library = Path(sysconfig.get_path('purelib')).resolve()
    require(library.is_relative_to(prefix), 'Numerical site-packages must belong to the declared venv.')
    files = {str(path.resolve()): sha(path) for path in sorted(library.rglob('*'))
             if path.is_file() and '__pycache__' not in path.parts and path.suffix not in ('.pyc', '.pyo')}
    executable = Path(sys.executable).absolute()
    files[str(executable.resolve())] = sha(executable.resolve())
    for name in ('pyvenv.cfg', 'requirements.lock.txt', 'environment-receipt.json'):
        path = prefix/name
        require(path.is_file(), 'The dedicated venv must retain its original package receipt and lock: ' + name)
        files[str(path)] = sha(path)
    return dict(executable=str(executable), resolvedExecutable=str(executable.resolve()),
        prefix=str(prefix), basePrefix=str(base), sitePackages=str(library),
        pythonVersion=sys.version, torchVersion=torch.__version__, numpyVersion=numpy.__version__,
        torchThreads=torch.get_num_threads(), deterministicAlgorithms=torch.are_deterministic_algorithms_enabled(),
        backend='cpu', requirementsLockSha256=sha(prefix/'requirements.lock.txt'), files=files)


def inspect_numerical(executable):
    """Do not resolve away the venv launcher: its path controls Python's prefix discovery."""
    executable = str(Path(executable).expanduser().absolute())
    child = subprocess.run([executable, '-E', '-s', '-B', str(Path(__file__).resolve()), 'inspect'],
                           capture_output=True, text=True, timeout=120, check=False)
    require(child.returncode == 0, 'Dedicated numerical environment inspection failed: ' + child.stderr[-2000:])
    identity = json.loads(child.stdout)
    require(identity['executable'] == executable and identity['torchThreads'] == 1
            and identity['deterministicAlgorithms'], 'Numerical launcher or deterministic CPU settings changed.')
    return identity


def verify_numerical(expected):
    require(current_numerical() == expected,
            'Numerical venv path, prefix, package versions, CPU settings or pinned library bytes changed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['inspect'])
    parser.parse_args()
    print(json.dumps(current_numerical(), sort_keys=True))
