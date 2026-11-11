"""Bind a portable scientific template to one reviewed host before exact-child preflight."""
import argparse
import copy
import os
from pathlib import Path
import platform
import sys

import target_campaign as campaign
import target_portability as portability
import prepare_expansion_target_search as prepare

read,sha,document,require = campaign.read,campaign.sha,campaign.document,campaign.require


def transport_files(root):
    files = {}
    for name in ('scripts','runtime','inputs','audit','evidence','portability'):
        files.update({str(path.relative_to(root)):sha(path) for path in sorted((root/name).rglob('*')) if path.is_file()})
    for name in ('protocol-template.json','provenance.json'):
        files[name] = sha(root/name)
    return files


def bind_portability(root,proof,expected_sha):
    import shutil
    require(not (root/'transport-manifest.json').exists() and not (root/'portability').exists(),
            'Bind one reviewed weighted proof before transport freeze.')
    template = read(root/'protocol-template.json')
    receipt = portability.verify(proof,expected_sha,template)
    require(receipt['seedAuditSha256'] == sha(root/template['seedAudit']),
            'Weighted proof must use the exact fresh target seed audit.')
    directory = root/'portability'
    directory.mkdir()
    for name in receipt['artifactHashes']:
        source,destination = portability.member(proof,name),portability.member(directory,name)
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,destination)
    shutil.copy2(proof/'receipt.json',directory/'receipt.json')
    portability.verify(directory,expected_sha,template)
    document(root/'weighted-portability.json',dict(receipt='portability/receipt.json',sha256=expected_sha,
        comparedPairs=32,usedForStrength=False,usedForSelection=False))


def freeze_transport(root):
    require(not any((root/name).exists() for name in ('transport-manifest.json','protocol-draft.json','protocol.json','run')),
            'Freeze transport once, before host binding or games.')
    declaration = read(root/'weighted-portability.json')
    portability.verify(root/'portability',declaration['sha256'],read(root/'protocol-template.json'))
    files = dict(transport_files(root),**{'weighted-portability.json':sha(root/'weighted-portability.json')})
    document(root/'transport-manifest.json',dict(schemaVersion=1,kind='expansion-target-search-transport',files=files,
        newTrainingGames=0,productionActivation=False,hostUnbound=True))
    print(dict(manifestSha256=sha(root/'transport-manifest.json'),pins=len(files),unlaunched=True))


def verify_transport(root,expected_sha):
    require(sha(root/'transport-manifest.json') == expected_sha,'Reviewed target transport manifest changed.')
    manifest = read(root/'transport-manifest.json')
    require(manifest['kind'] == 'expansion-target-search-transport' and manifest['hostUnbound'] is True,
            'Use the host-independent target transport declaration.')
    require(dict(transport_files(root),**{'weighted-portability.json':sha(root/'weighted-portability.json')})
            == manifest['files'],'Target transport bytes or file set changed.')
    return manifest


def finalize(root,request_path,request_sha,manifest_sha):
    require(not any((root/name).exists() for name in ('protocol-draft.json','protocol.json','campaign.json','run')),
            'Bind exactly one reviewed host before target execution.')
    verify_transport(root,manifest_sha)
    require(sha(request_path) == request_sha,'Reviewed target host request changed.')
    request = read(request_path)
    template = read(root/'protocol-template.json')
    workers = 10 if 'jointExpansion' in template else 6
    require(request_path.resolve() == root/'host-request.json' and request['root'] == str(root)
            and request['workers'] == workers and request['platform'] == platform.platform()
            and request['machine'] == platform.machine() and request['logicalCpus'] == os.cpu_count()
            and request['python'] == str(Path(sys.executable).resolve()) and request['pythonVersion'] == sys.version
            and sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Finalize only the reviewed profile-specific worker count and clean interpreter.')
    require((platform.system(),platform.machine()) in (('Darwin','arm64'),('Linux','x86_64')),
            'Target execution is restricted to the two reviewed hosts.')
    require('jointExpansion' not in template or platform.system() == 'Linux',
            'The joint campaign is reserved for the reviewed desktop host.')
    protocol = copy.deepcopy(template)
    declaration = read(root/'weighted-portability.json')
    receipt = portability.verify(root/'portability',declaration['sha256'],protocol)
    require(receipt['seedAuditSha256'] == sha(root/protocol['seedAudit']),
            'Weighted host proof and learning must retain the same reservation audit.')
    java,python = campaign.exp.java_identity(request['java']),prepare.python_identity()
    environment = dict(platform=platform.platform(),machine=platform.machine(),logicalCpus=os.cpu_count(),
        variables=dict(HOME=request['home'],PATH='/usr/bin:/bin',LANG='C.UTF-8',LC_ALL='C.UTF-8',TZ='UTC',
            PYTHONNOUSERSITE='1',PYTHONDONTWRITEBYTECODE='1',JAVA_HOME=str(Path(java['executable']).parent.parent)))
    if platform.system() == 'Darwin':
        environment['variables']['__CF_USER_TEXT_ENCODING'] = '0x1F5:0x0:0x0'
    else:
        # The unchanged benchmark pins interpreter/JDK compatibility; weighted parity independently
        # validates the new AI feature bytes and nonzero target models.
        import portable_fixed_token as benchmark
        _,previous,acceptance,_ = benchmark.verify_benchmark(Path(request['benchmarkOutput']),Path(request['benchmarkBundle']),
            Path(request['benchmarkNumericalSupplement']),request['benchmarkNumericalSupplementSha256'])
        require(previous['python']['sha256'] == python['sha256'] and previous['python']['version'] == python['version']
                and previous['java'] == java,'Use the exact independently benchmarked desktop Python/JDK.')
        environment['benchmarkAcceptance'] = acceptance
    for key in ('baseline','expansionModel','runtime','seedAudit'):
        protocol[key] = str(portability.member(root,protocol[key]))
    protocol['frozenPurchase'] = {key:str(portability.member(root,path)) for key,path in protocol['frozenPurchase'].items()}
    if 'jointExpansion' in protocol:
        settings = protocol['jointExpansion']
        settings['normalizationSource']['path'] = str(portability.member(root,settings['normalizationSource']['path']))
        for item in (settings['militaryQualification'] or {}).values():
            item['path'] = str(portability.member(root,item['path']))
    protocol.update(java=java,python=python,workers=workers,environment=environment,
        transportManifestSha256=manifest_sha,hostRequestSha256=request_sha,
        weightedPortabilitySha256=declaration['sha256'],weightedPortabilityVerified=True)
    document(root/'environment.json',environment)
    document(root/'protocol-draft.json',protocol)
    print(dict(draftSha256=sha(root/'protocol-draft.json'),newGames=0,next='prepare-preflight then freeze'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('bind-portability','freeze-transport','finalize'))
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--proof-root',type=Path)
    parser.add_argument('--proof-sha')
    parser.add_argument('--request',type=Path)
    parser.add_argument('--request-sha')
    parser.add_argument('--manifest-sha')
    args=parser.parse_args();root=args.root.resolve()
    if args.command=='bind-portability':bind_portability(root,args.proof_root.resolve(),args.proof_sha)
    elif args.command=='freeze-transport':freeze_transport(root)
    else:finalize(root,args.request.resolve(),args.request_sha,args.manifest_sha)
