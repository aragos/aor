"""Bind relative eight-tail-token inputs to the reviewed desktop and existing CPU venv."""
import argparse
import copy
import os
from pathlib import Path
import platform
import sys

import adaptive_token_environment as numerical
import prepare_token_eight_tail as prepare

target, campaign = prepare.target, prepare.campaign
read, sha, document, require = prepare.read, prepare.sha, prepare.document, prepare.require


def transport_files(root):
    files = {str(path.relative_to(root)):sha(path)
             for name in ('scripts', 'runtime', 'inputs', 'audit', 'evidence')
             for path in sorted((root/name).rglob('*')) if path.is_file()}
    return dict(files, **{name:sha(root/name) for name in ('protocol-template.json', 'provenance.json')})


def freeze_transport(root):
    require(not any((root/name).exists() for name in ('transport-manifest.json', 'protocol-draft.json', 'protocol.json', 'run')),
            'Freeze transport once, before host binding or games.')
    files = transport_files(root)
    document(root/'transport-manifest.json', dict(schemaVersion=1, kind='eight-tail-token-transport-1',
        files=files, newTrainingGames=0, productionActivation=False, hostUnbound=True))
    print(dict(manifestSha256=sha(root/'transport-manifest.json'), pins=len(files), unlaunched=True))


def finalize(root, request_path, request_sha, manifest_sha):
    require(not any((root/name).exists() for name in ('protocol-draft.json', 'protocol.json', 'campaign.json', 'run')),
            'Bind one reviewed host before any campaign attempt.')
    require(sha(root/'transport-manifest.json') == manifest_sha
            and read(root/'transport-manifest.json')['files'] == transport_files(root)
            and read(root/'transport-manifest.json')['hostUnbound'] is True,
            'Portable eight-tail-token inputs changed.')
    require(sha(request_path) == request_sha, 'Reviewed desktop request changed.')
    request = read(request_path)
    require(request_path.resolve() == root/'host-request.json' and request['root'] == str(root)
            and request['workers'] == 6 and request['platform'] == platform.platform()
            and request['machine'] == platform.machine() and request['logicalCpus'] == os.cpu_count()
            and request['python'] == str(Path(sys.executable).resolve()) and request['pythonVersion'] == sys.version
            and (platform.system(), platform.machine()) == ('Linux', 'x86_64')
            and sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Finalize only the reviewed six-worker desktop using clean -E -s -S -B Python.')
    protocol = copy.deepcopy(read(root/'protocol-template.json'))
    java, python = target.exp.java_identity(request['java']), prepare.python_identity()
    import portable_fixed_token as benchmark
    _, previous, acceptance, _ = benchmark.verify_benchmark(Path(request['benchmarkOutput']),
        Path(request['benchmarkBundle']), Path(request['benchmarkNumericalSupplement']),
        request['benchmarkNumericalSupplementSha256'])
    require(previous['python']['sha256'] == python['sha256'] and previous['python']['version'] == python['version']
            and previous['java'] == java, 'Use the exact independently benchmarked desktop Python/JDK.')
    identity = numerical.inspect_numerical(request['numericalPython'])
    require(identity['requirementsLockSha256'] == protocol['numericalRequirementsLockSha256'] == prepare.LOCK
            and identity['torchVersion'] == '2.14.0+cpu' and identity['numpyVersion'] == '2.5.4',
            'Use the separately approved exact deterministic CPU numerical environment.')
    environment = dict(platform=platform.platform(), machine=platform.machine(), logicalCpus=os.cpu_count(),
        benchmarkAcceptance=acceptance, variables=dict(HOME=request['home'], PATH='/usr/bin:/bin', LANG='C.UTF-8',
        LC_ALL='C.UTF-8', TZ='UTC', PYTHONNOUSERSITE='1', PYTHONDONTWRITEBYTECODE='1',
        JAVA_HOME=str(Path(java['executable']).parent.parent)))
    for key in ('baseline', 'expansionModel', 'runtime', 'scripts', 'seedAudit', 'initializer', 'originalBasisIdentity', 'fitConfig', 'collectionSource'):
        path = Path(protocol[key])
        require(not path.is_absolute() and '..' not in path.parts, 'Portable inputs must stay inside their package.')
        protocol[key] = str(root/path)
    protocol['frozenPurchase'] = {key:str(root/path) for key, path in protocol['frozenPurchase'].items()}
    protocol.update(java=java, python=python, numerical=identity, workers=6, environment=environment,
        transportManifestSha256=manifest_sha, hostRequestSha256=request_sha)
    target.validate_inputs(protocol)
    document(Path(protocol['collectionSource']), target.collection_source(protocol))
    document(root/'environment.json', environment)
    document(root/'numerical-environment.json', identity)
    document(root/'protocol-draft.json', protocol)
    print(dict(draftSha256=sha(root/'protocol-draft.json'), numericalPins=len(identity['files']), newGames=0,
               next='prepare-preflight then freeze'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('freeze-transport', 'finalize'))
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--request', type=Path)
    parser.add_argument('--request-sha')
    parser.add_argument('--manifest-sha')
    args = parser.parse_args()
    if args.command == 'freeze-transport':
        freeze_transport(args.root.resolve())
    else:
        finalize(args.root.resolve(), args.request.resolve(), args.request_sha, args.manifest_sha)
