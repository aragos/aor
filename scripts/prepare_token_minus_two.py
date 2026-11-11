"""Copy one reviewed selector diagnostic and bind the existing desktop; never launch."""
import argparse
import copy
import os
from pathlib import Path
import platform
import shutil
import sys

import prepare_adaptive_token as prior
import token_minus_two as diagnostic

exp, read, sha, document, require = diagnostic.exp, diagnostic.read, diagnostic.sha, diagnostic.document, diagnostic.require


def prepare(root, adaptive, combination):
    require(root.exists() and {path.name for path in root.iterdir()} <= {'audit'},
            'Prepare only into the separately audited diagnostic directory.')
    require(sha(adaptive/'protocol.json') == 'c08b2446f5a0df6465d679975277137b8e75705fad120334fd39043ed1a1e6dc'
            and sha(adaptive/'completion-audit.json') == 'caaf05eabfc9a5c2f2048728572c14405139824c9b551f4941c7806a0659ee6d',
            'Retain the completed adaptive experiment and its unopened bank reservations.')
    parent = read(adaptive/'protocol.json')
    qualified = prior.verify_combination(combination)
    runtime = adaptive/'runtime'
    require(exp.distribution_hashes(runtime) == parent['runtimeFiles']
            and sha(runtime/'lib/training.jar') == prior.NATIVE_JAR,
            'Reuse the exact reviewed evaluated minus-two implementation.')
    shutil.copytree(runtime, root/'runtime')
    shutil.copytree(adaptive/'scripts', root/'scripts')
    for name in ('adaptive_token_arena.py', 'token_minus_two.py', 'token_minus_two_campaign.py',
                 'prepare_token_minus_two.py', 'test_token_minus_two.py'):
        shutil.copy2(Path(__file__).parent/name, root/'scripts'/name)
    provenance = []
    for name in ('ordinary-f1.json', 'lambda-1.0.json', 'purchase-prior-f0.json', 'purchase-c-p64.json'):
        exp.copy_input(adaptive/'inputs'/name, root/'inputs'/name, provenance)
    shutil.copytree(adaptive/'evidence', root/'evidence')
    for name in ('protocol.json', 'completion-audit.json', 'run/selection.json'):
        exp.copy_input(adaptive/name, root/'evidence/adaptive'/name, provenance)
    audit = read(root/'audit/seed-audit.json')
    require(audit['allIntersectionsZero'] and set(audit['seedBanks']) == {'screen'},
            'The independent simple control uses one separate fresh bank only.')
    protocol = dict(schemaVersion=1, kind=diagnostic.KIND, banks=audit['seedBanks'],
        maximumSeconds=3600, maximumAttempts=1, maximumTerminalGames=1024, maximumHistoricalReplays=8,
        heapMiB=1536, confidence=.95, baseline='inputs/ordinary-f1.json', expansionModel='inputs/lambda-1.0.json',
        expansionSha256=diagnostic.components.E, frozenPurchase={'baseline':'inputs/purchase-prior-f0.json',
            'policy':'inputs/purchase-c-p64.json'}, frozenPurchaseSha256=parent['frozenPurchaseSha256'],
        runtime='runtime', runtimeFiles=parent['runtimeFiles'], runtimeCommit=parent['runtimeCommit'],
        runtimeTrainingJarSha256=parent['runtimeTrainingJarSha256'], nativeContractVerified=True,
        source='sources/screen.json', seedAudit='audit/seed-audit.json', incumbentQualified=True,
        incumbentQualification=qualified, qualificationPassed=False, productionActivation=False,
        humanStrengthVerified=False, automaticNextCampaign=False, modelSelection=False,
        **{key:parent[key] for key in ('configuration','dataVersion','sourceRulesVersion','targetRulesVersion','experimentalRulesTransfer')},
        limitation='One fixed model-free token selector on512 fresh strong mixed-rival worlds. Marginal95 intervals only; no qualification, human-level or learning-necessity claim.')
    document(root/'protocol-template.json', protocol)
    document(root/'provenance.json', provenance)
    files = {str(path.relative_to(root)):sha(path) for path in sorted(root.rglob('*')) if path.is_file()}
    document(root/'transport-manifest.json', dict(schemaVersion=1, kind='token-minus-two-transport-1',
        files=files, hostUnbound=True, newGames=0))
    print(dict(templateSha256=sha(root/'protocol-template.json'), manifestSha256=sha(root/'transport-manifest.json'),
               pins=len(files), unlaunched=True))


def finalize(root, request_path, request_sha, manifest_sha):
    require(not any((root/name).exists() for name in ('protocol-draft.json','protocol.json','campaign.json','run')),
            'Bind the one reviewed desktop before any attempt.')
    require(sha(root/'transport-manifest.json') == manifest_sha and sha(request_path) == request_sha,
            'Reviewed transport or host request changed.')
    manifest = read(root/'transport-manifest.json')
    require(manifest['kind'] == 'token-minus-two-transport-1' and manifest['hostUnbound']
            and all(sha(root/name) == digest for name,digest in manifest['files'].items()),
            'Frozen portable selector diagnostic inputs changed.')
    for name in ('scripts','runtime','inputs','audit','evidence'):
        require({str(path.relative_to(root)) for path in (root/name).rglob('*') if path.is_file()}
                == {path for path in manifest['files'] if Path(path).parts[0] == name},
                'Unexpected portable input files in '+name)
    request = read(request_path)
    require(request_path.resolve() == root/'host-request.json' and request['root'] == str(root)
            and request['workers'] == 10 and request['platform'] == platform.platform()
            and request['machine'] == platform.machine() and request['logicalCpus'] == os.cpu_count()
            and request['python'] == str(Path(sys.executable).resolve()) and request['pythonVersion'] == sys.version
            and (platform.system(),platform.machine()) == ('Linux','x86_64')
            and sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Use only the exact reviewed desktop clean interpreter and ten-worker allocation.')
    protocol = copy.deepcopy(read(root/'protocol-template.json'))
    java, python = exp.java_identity(request['java']), prior.python_identity()
    import portable_fixed_token as benchmark
    _, previous, acceptance, _ = benchmark.verify_benchmark(Path(request['benchmarkOutput']),
        Path(request['benchmarkBundle']),Path(request['benchmarkNumericalSupplement']),request['benchmarkNumericalSupplementSha256'])
    require(previous['python']['sha256'] == python['sha256'] and previous['python']['version'] == python['version']
            and previous['java'] == java, 'Keep the exact benchmarked desktop Python/JDK.')
    for key in ('baseline','expansionModel','runtime','source','seedAudit'):
        path = Path(protocol[key])
        require(not path.is_absolute() and '..' not in path.parts, 'Portable input must remain inside its package.')
        protocol[key] = str(root/path)
    protocol['frozenPurchase'] = {key:str(root/path) for key,path in protocol['frozenPurchase'].items()}
    environment = dict(platform=platform.platform(),machine=platform.machine(),logicalCpus=os.cpu_count(),
        benchmarkAcceptance=acceptance,variables=dict(HOME=request['home'],PATH='/usr/bin:/bin',LANG='C.UTF-8',
            LC_ALL='C.UTF-8',TZ='UTC',PYTHONNOUSERSITE='1',PYTHONDONTWRITEBYTECODE='1',
            JAVA_HOME=str(Path(java['executable']).parent.parent)))
    protocol.update(java=java,python=python,workers=10,environment=environment,
        transportManifestSha256=manifest_sha,hostRequestSha256=request_sha)
    document(Path(protocol['source']),diagnostic.source(protocol))
    diagnostic.validate_inputs(protocol)
    document(root/'environment.json',environment)
    document(root/'protocol-draft.json',protocol)
    print(dict(draftSha256=sha(root/'protocol-draft.json'),newGames=0,next='prepare-preflight then freeze'))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('prepare','finalize'))
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--adaptive-root',type=Path)
    parser.add_argument('--combination-root',type=Path)
    parser.add_argument('--request',type=Path)
    parser.add_argument('--request-sha')
    parser.add_argument('--manifest-sha')
    args=parser.parse_args()
    if args.command=='prepare':prepare(args.root.resolve(),args.adaptive_root.resolve(),args.combination_root.resolve())
    else:finalize(args.root.resolve(),args.request.resolve(),args.request_sha,args.manifest_sha)
