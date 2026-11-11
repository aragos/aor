"""Prepare one Mac fixed-T residual experiment; never collect, fit or launch."""
import argparse
import copy
import os
from pathlib import Path
import platform
import shutil
import sys

import automata_token_eight_tail as target
import prepare_token_eight_tail as previous
import purchase_ecology_environment as numerical

read, sha, document, require = target.read, target.sha, target.document, target.require
TEMPLATE = '249fd35c08a1b857287c77650dfd44d4d588b2f9fa84774e86ec58d6774c76fe'
TRANSPORT = 'cd470781dea5d2f043b3deeae03c0276108e42075e22eebe40330f8c9af51008'
IMPLEMENTATION = 'c3ce7a9f9231a3d0c1b14242fca6927547569843bd389c3e3ab198f7b28397fd'
LOCK = 'b58e08ffbbc2b40e9d15ac45907087e362504a476f548ca30d4f037e7db392c5'
SCRIPT_NAMES = (*previous.SCRIPT_NAMES, 'purchase_ecology_environment.py',
                'prepare_token_fixed_reference.py', 'test_token_fixed_reference_campaign.py',
                'test_token_order_fixed_reference.py')


def pure_identity():
    standard, paths = numerical.interpreter_files()
    executable = Path(sys.executable).resolve(strict=True)
    return dict(executable=str(executable), version=sys.version, sha256=sha(executable),
                stdlib=str(standard), files={str(path):sha(path) for path in sorted(paths)})


def prepare(options):
    root, parent, evidence, native, runtime, proof = [getattr(options, name).resolve() for name in
        ('output', 'original_package', 'native_evidence', 'native_root', 'runtime', 'zero_head_proof')]
    require(root.exists() and {path.name for path in root.iterdir()} <= {'audit'},
            'Prepare only into a fresh seed-reserved residual package.')
    require(platform.system() == 'Darwin' and platform.machine() == 'arm64'
            and sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site
            and sys.dont_write_bytecode, 'Prepare on the Mac with clean -E -s -S -B Python.')
    require(sha(parent/'protocol-template.json') == TEMPLATE
            and sha(parent/'transport-manifest.json') == TRANSPORT,
            'Reuse only the original immutable eight-tail scientific template and prepared inputs.')
    for name, digest in read(parent/'transport-manifest.json')['files'].items():
        require(sha(parent/name) == digest, 'Original prepared input changed: '+name)
    implementation = read(evidence/'implementation-verification.json')
    require(sha(evidence/'implementation-verification.json') == IMPLEMENTATION
            and implementation['actualGameWorlds'] == 0 and implementation['actualFits'] == 0
            and all(implementation['checks'].values()),
            'Require the reviewed residual implementation, installed dependency closure and tests.')
    runtime_pins, source_pins = read(evidence/'runtime-pins.json'), read(evidence/'source-pins.json')
    require(sha(evidence/'runtime-pins.json') == implementation['runtimePinsSha256']
            and sha(evidence/'source-pins.json') == implementation['sourcePinsSha256']
            and target.exp.distribution_hashes(runtime) == runtime_pins
            and all(sha(native/name) == digest for name, digest in source_pins.items()),
            'Reviewed native/runtime bytes changed before preparation.')
    audit = read(root/'audit/seed-audit.json')
    require(audit['allIntersectionsZero'] and set(audit['seedBanks']) == set(target.COUNTS),
            'Use the complete fresh origin, continuation, ordinal and final-world audit.')
    equivalence = read(proof)
    require(equivalence['passed'] and equivalence['newGames'] == 0 and equivalence['actualFits'] == 0,
            'Require completed zero-head equivalence on the sanitized saved actor observations.')
    identity = numerical.inspect(options.numerical_python)
    python, java = pure_identity(), target.exp.java_identity(options.java)
    require(identity['resolvedExecutable'] == python['executable']
            and identity['pythonVersion'] == python['version']
            and identity['requirementsLockSha256'] == LOCK
            and identity['torchVersion'] == '2.14.0' and identity['numpyVersion'] == '2.5.4'
            and identity['device'] == 'cpu' and not identity['mpsUsed'] and not identity['gpuUsed'],
            'Use the existing pinned Mac CPU venv and exactly the same pure/numerical interpreter.')
    provenance = []
    def copied(source, relative):
        return target.exp.copy_input(Path(source), root/relative, provenance)
    shutil.copytree(runtime, root/'runtime')
    shutil.copytree(parent/'scripts', root/'scripts')
    for name in dict.fromkeys(SCRIPT_NAMES):
        copied(Path(__file__).parent/name, 'scripts/'+name)
    shutil.copytree(parent/'inputs', root/'inputs')
    shutil.copytree(parent/'evidence/military', root/'evidence/military')
    for name in ('protocol-template.json','transport-manifest.json','provenance.json'):
        copied(parent/name, 'evidence/original-preparation/'+name)
    for path in evidence.rglob('*'):
        if path.is_file():
            copied(path, 'evidence/native/'+str(path.relative_to(evidence)))
    for name in source_pins:
        copied(native/name, 'evidence/native-source/'+name)
    copied(proof, 'evidence/zero-head-equivalence.json')
    protocol = copy.deepcopy(read(parent/'protocol-template.json'))
    protocol.update(kind=target.fixed.CAMPAIGN_KIND, continuationProtocol=target.fixed.PROTOCOL,
        tokenReference=dict(version=target.fixed.REFERENCE, proposalReference='ordinary-token-reference-1',
            controlledSelector=target.fixed.SELECTOR, learnerSelector='MODEL_FIXED_T_RESIDUAL'),
        banks=audit['seedBanks'], runtimeCommit=implementation['commit'],
        runtimeTrainingJarSha256=implementation['trainingJarSha256'], runtimeFiles=runtime_pins,
        metadataValidatorSha256=sha(root/'scripts/token_order_metadata.py'),
        nativeImplementationSha256=IMPLEMENTATION, numericalRequirementsLockSha256=LOCK,
        zeroHeadEquivalence=str(root/'evidence/zero-head-equivalence.json'),
        zeroHeadEquivalenceSha256=sha(proof),
        executionAllocation=dict(thisWorkers=6,host='mac',logicalCpus=os.cpu_count(),otherMacWorkers=0),
        limitation='Fixed-T residual evidence in one fixed M8/E/C64 ecology; unchanged proposals and .02 margin. No guaranteed improvement.')
    for key in ('baseline','expansionModel','runtime','scripts','seedAudit','initializer',
                'originalBasisIdentity','fitConfig','collectionSource'):
        path = Path(protocol[key])
        require(not path.is_absolute() and '..' not in path.parts, 'Prepared inputs must stay inside the new package.')
        protocol[key] = str(root/path)
    protocol['frozenPurchase'] = {key:str(root/path) for key,path in protocol['frozenPurchase'].items()}
    variables = dict(HOME=str(Path.home()), PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='en_US.UTF-8',
        LC_ALL='en_US.UTF-8', TZ='UTC', PYTHONNOUSERSITE='1', PYTHONDONTWRITEBYTECODE='1',
        JAVA_HOME=str(Path(java['executable']).parent.parent),
        __CF_USER_TEXT_ENCODING=f'0x{os.getuid():X}:0x0:0x0')
    environment = dict(platform=platform.platform(),machine=platform.machine(),logicalCpus=os.cpu_count(),
                       variables=variables)
    protocol.update(java=java,python=python,numerical=identity,environment=environment)
    target.validate_inputs(protocol)
    document(Path(protocol['collectionSource']),target.collection_source(protocol))
    document(root/'environment.json',environment)
    document(root/'numerical-environment.json',identity)
    document(root/'protocol-draft.json',protocol)
    document(root/'provenance.json',provenance)
    print(dict(draftSha256=sha(root/'protocol-draft.json'),numericalPins=len(identity['files']),newGames=0,
               next='Independent review, exact-child preflight and freeze; no launch.'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('output','original-package','native-evidence','native-root','runtime','zero-head-proof'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--numerical-python',required=True)
    parser.add_argument('--java',required=True)
    prepare(parser.parse_args())
