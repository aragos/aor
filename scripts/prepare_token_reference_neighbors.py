"""Freeze portable inherited evidence, then bind one host without opening any game worlds."""
import argparse
import copy
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

import automata_token_reference_neighbors as target
import prepare_token_eight_tail as old
import adaptive_token_environment as numerical

read,sha,document,require=target.read,target.sha,target.document,target.require
NATIVE_READINESS='048744ea8ea01b3c7f5f1bd4116723ea3a42eb12a63906107155fea02d393ed5'
ROOT_REVIEW='f1cc0464d34fbde5a996b635f1240cd2d0c70c55fa504f50b05a5d685b075084'
SCRIPTS=tuple(dict.fromkeys((*old.SCRIPT_NAMES,'purchase_ecology_environment.py',
    'token_order_augmentation.py','automata_token_reference_neighbors.py','prepare_token_reference_neighbors.py',
    'test_token_order_augmentation.py','test_token_reference_neighbor_campaign.py',
    'test_token_order_reference_neighbors.py','test_token_eight_tail_supervisor.py')))


def prepare(root,parent,parent_audit,native,plan):
    require(root.exists() and {p.name for p in root.iterdir()}<={'audit'},'Prepare only the new audited package.')
    require(sha(root/'audit/seed-audit.json')==target.AUDIT and sha(parent_audit)==target.data.PARENT_AUDIT,
            'Fresh audit or original completed parent identity changed.')
    require(sha(parent/'protocol.json')==target.data.PARENT_PROTOCOL,'Require exact original fixed-T protocol.')
    audit=read(parent_audit)
    for name,digest in audit['artifactHashes'].items():
        require(sha(parent/'run'/name)==digest,'An independently audited parent output changed: '+name)
    require(sha(native/'readiness-v2.json')==NATIVE_READINESS and sha(native/'root-review.json')==ROOT_REVIEW,
            'Require exact reviewed native relocation/capture/augmentation implementation.')
    readiness=read(native/'readiness-v2.json')
    for name,digest in readiness['fileSha256'].items():
        require(sha(name)==digest,'A reviewed native readiness artifact changed: '+name)
    require(readiness['tailsGenerated']==readiness['prefixesReconstructed']==readiness['fits']==0,
            'Only the zero-world native preparation may precede this experiment.')
    parent_protocol=read(parent/'protocol.json')
    require(sha(parent/'evidence/military/completion-audit.json')==old.MILITARY_AUDIT
            and sha(parent/'evidence/military/root-review.json')==old.MILITARY_REVIEW
            and read(parent/'evidence/military/root-review.json')['qualificationPassed'],
            'Carry exact qualified military/current-incumbent evidence, without relabeling parent fit eligibility.')
    provenance=[]
    def take(source,relative):
        return target.exp.copy_input(Path(source),root/relative,provenance)
    shutil.copytree(parent/'run',root/'parent/run')
    take(parent/'protocol.json','parent/protocol.json')
    take(parent/'campaign.json','parent/campaign.json')
    take(parent_audit,'parent/completion-audit.json')
    for name in ('ai-jvm.jar','core-jvm.jar','training.jar'):
        take(parent/'runtime/lib'/name,'parent/runtime/lib/'+name)
    shutil.copytree(parent/'scripts',root/'scripts')
    for name in SCRIPTS:
        take(Path(__file__).parent/name,'scripts/'+name)
    shutil.copytree(native/'runtime-v2',root/'runtime')
    # Java libraries retain the exact reviewed bytes. Python installed tools use the integrated
    # dependency closure; they are pinned separately and never alter historical runtime jars.
    for name in ('token_order_eight_tail.py','token_order_augmentation.py','token_order_reference_neighbors.py'):
        take(Path(__file__).parent/name,'runtime/bin/'+name)
    for name in ('ordinary-military-8.json','expansion-military-8.json','purchase-c-p64.json',
                 'purchase-prior-f0.json','original-initializer.json','original-basis-identity.json'):
        take(parent/'inputs'/name,'inputs/'+name)
    shutil.copytree(parent/'evidence/military',root/'evidence/military')
    for name in ('readiness-v2.json','root-review.json','source.json','relocated-source.json'):
        take(native/name,'evidence/native/'+name)
    for name,digest in readiness['fileSha256'].items():
        source=Path(name)
        relative=source.relative_to(native)
        take(source,'evidence/native/'+str(relative))
    for name in ('plan.json','plan-initial.json','metadata-branches.json'):
        take(plan/name,'evidence/proposal/'+name)
    document(root/'inputs/fit-config.json',target.prior.FIT)
    old_source=read(native/'source.json')
    source=dict(old_source,parentDirectory='parent/run/collection',
        parentCompletionAudit=dict(path='parent/completion-audit.json',sha256=target.data.PARENT_AUDIT),
        branchPlan=dict(path='evidence/proposal/metadata-branches.json',sha256=target.data.BRANCH_PLAN),
        parentRuntime={role:dict(path='parent/runtime/lib/'+Path(entry['path']).name,sha256=entry['sha256'])
                       for role,entry in old_source['parentRuntime'].items()},
        parentSource=dict(aliases={original:dict(path='parent/run/collection/'+Path(entry['path']).name,
                                                 sha256=entry['sha256'])
                                 for original,entry in old_source['parentSource']['aliases'].items()}))
    document(root/'augmentation-template.json',source)
    protocol=dict(schemaVersion=1,kind=target.KIND,banks=read(root/'audit/seed-audit.json')['seedBanks'],
        workers=10,maximumSeconds=21600,maximumAttempts=1,heapMiB=1536,
        proposalVersion=target.codec.PROPOSALS,selectorVersion=target.codec.SELECTOR_VERSION,
        referenceProtocol=target.data.fixed.REFERENCE,learnerSelector=target.codec.SELECTOR,
        trainingRoots=512,validationRoots=128,continuationReplicates=8,replayOrigins=target.REPLAYS,
        validationPreviouslyInspected=True,validationPurpose='REUSED_DEVELOPMENT_SELECTION_ONLY',
        addedBranches=314,addedLabelTails=2512,maximumArenaTerminals=13312,maximumReplayTerminals=88,
        maximumTerminalGames=15912,maximumHistoricalOriginReplays=8,maximumHistoricalArenaReplays=24,
        maximumHistoricalTerminalBranches=64,oldOutcomeTailsResimulated=0,
        historicalPrefixReconstruction=dict(augmentation=314,replay=8,terminalGame=False),
        screenRule=target.prior.SCREEN,qualificationConfidence=.99,diagnosticPrefixes=[2,4],
        diagnosticPrefixesAffectSelection=False,seedAudit='audit/seed-audit.json',
        baseline='inputs/ordinary-military-8.json',baselineSha256=target.prior.BASELINE,
        expansionModel='inputs/expansion-military-8.json',expansionSha256=target.prior.EXPANSION,
        frozenPurchase={'baseline':'inputs/purchase-prior-f0.json','policy':'inputs/purchase-c-p64.json'},
        frozenPurchaseSha256={'baseline':target.previous.F0,'policy':target.previous.C},
        initializer='inputs/original-initializer.json',originalBasisIdentity='inputs/original-basis-identity.json',
        initializerCanonicalSha256=read(root/'inputs/original-basis-identity.json')['initializedModelSha256'],
        fitConfig='inputs/fit-config.json',runtime='runtime',scripts='scripts',
        augmentationSource='sources/augmentation.json',nativePreparation='sources/native-preparation/preparation.json',
        parentDatasetSha256=sha(root/'parent/run/collection/dataset.json'),
        runtimeCommit=readiness['sourceCommit'],runtimeTrainingJarSha256=sha(root/'runtime/lib/training.jar'),
        runtimeFiles=target.exp.distribution_hashes(root/'runtime'),
        metadataValidatorSha256=sha(root/'scripts/token_order_metadata.py'),
        nativeContractVerified=True,incumbentQualified=True,
        incumbentQualification=parent_protocol['incumbentQualification'],
        numericalRequirementsLockSha256=old.LOCK,
        executionAllocation={'thisWorkers':10,'otherDesktopWorkers':0,'desktopLogicalCpus':16},
        heldoutRivalSelectors=['FIXED_16_TO_13','FIXED_16_TO_14','FIXED_16_TO_15','ORDINARY'],
        **{key:parent_protocol[key] for key in ('configuration','dataVersion','sourceRulesVersion','targetRulesVersion','experimentalRulesTransfer')},
        productionActivation=False,humanStrengthVerified=False,automaticNextCampaign=False,
        limitation='Reused fixed-T training/development evidence; only missing reference-neighbor labels are new. Fresh games own qualification, with an explicit varied-bid heldout ecology. No human-level or learning-necessity claim.')
    document(root/'protocol-template.json',protocol)
    document(root/'provenance.json',provenance)
    files={str(p.relative_to(root)):sha(p) for p in sorted(root.rglob('*')) if p.is_file()}
    document(root/'transport-manifest.json',dict(kind='token-reference-neighbor-transport-1',files=files,
        hostUnbound=True,nativeGames=0,actualFits=0))
    print(dict(pins=len(files),manifestSha256=sha(root/'transport-manifest.json'),unlaunched=True))


def relocate(root,relative):
    path=Path(relative)
    require(not path.is_absolute() and '..' not in path.parts,'Portable paths must stay within this package.')
    return str(root/path)


def finalize(root,request_path,request_sha,manifest_sha):
    require(not any((root/name).exists() for name in ('protocol-draft.json','protocol.json','campaign.json','run')),
            'Finalize once before launch, preserving all earlier experiments.')
    manifest=read(root/'transport-manifest.json')
    require(sha(root/'transport-manifest.json')==manifest_sha and manifest['hostUnbound'],
            'Reviewed portable manifest changed.')
    actual={str(path.relative_to(root)) for path in root.rglob('*') if path.is_file()
            and path.name not in ('transport-manifest.json','host-request.json')}
    require(actual==set(manifest['files']),'Portable file membership changed before host binding.')
    for name,digest in manifest['files'].items():
        require(sha(root/name)==digest,'A portable input changed: '+name)
    require(sha(request_path)==request_sha and request_path==root/'host-request.json','Exact host request changed.')
    request=read(request_path)
    require(request['root']==str(root) and request['workers']==10
            and request['platform']==platform.platform() and request['machine']==platform.machine()
            and request['logicalCpus']==os.cpu_count()==16
            and request['python']==str(Path(sys.executable).resolve()) and request['pythonVersion']==sys.version
            and (platform.system(),platform.machine())==('Linux','x86_64')
            and sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Bind the declared desktop with clean pure Python -E -s -S -B and ten-worker allocation.')
    protocol=copy.deepcopy(read(root/'protocol-template.json'))
    java,python=target.exp.java_identity(request['java']),old.python_identity()
    import portable_fixed_token as benchmark
    _,previous,acceptance,_=benchmark.verify_benchmark(Path(request['benchmarkOutput']),
        Path(request['benchmarkBundle']),Path(request['benchmarkNumericalSupplement']),
        request['benchmarkNumericalSupplementSha256'])
    require(previous['python']['sha256']==python['sha256'] and previous['python']['version']==python['version']
            and previous['java']==java,'Use the exact benchmarked desktop pure Python/JDK.')
    identity=numerical.inspect_numerical(request['numericalPython'])
    require(identity['requirementsLockSha256']==protocol['numericalRequirementsLockSha256']==old.LOCK
            and identity['torchVersion']=='2.14.0+cpu' and identity['numpyVersion']=='2.5.4',
            'Use the unchanged deterministic one-thread CPU fitting environment.')
    environment=dict(platform=platform.platform(),machine=platform.machine(),logicalCpus=os.cpu_count(),
        benchmarkAcceptance=acceptance,variables=dict(HOME=request['home'],PATH='/usr/bin:/bin',LANG='C.UTF-8',
        LC_ALL='C.UTF-8',TZ='UTC',PYTHONNOUSERSITE='1',PYTHONDONTWRITEBYTECODE='1',
        JAVA_HOME=str(Path(java['executable']).parent.parent)))
    for key in ('baseline','expansionModel','runtime','scripts','seedAudit','initializer','originalBasisIdentity',
                'fitConfig','augmentationSource','nativePreparation'):
        protocol[key]=relocate(root,protocol[key])
    protocol['frozenPurchase']={key:relocate(root,path) for key,path in protocol['frozenPurchase'].items()}
    source=read(root/'augmentation-template.json')
    source['parentDirectory']=relocate(root,source['parentDirectory'])
    for entry in (source['parentCompletionAudit'],source['branchPlan'],*source['parentRuntime'].values(),
                  *source['parentSource']['aliases'].values()):
        entry['path']=relocate(root,entry['path'])
    aliases={original:entry['path'] for original,entry in source['parentSource']['aliases'].items()}
    def replace(value):
        if isinstance(value,str):return aliases.get(value,value)
        if isinstance(value,list):return [replace(item) for item in value]
        if isinstance(value,dict):return {key:replace(item) for key,item in value.items()}
        return value
    relocated=root/'sources/relocated-parent-source.json'
    document(relocated,replace(read(root/'parent/run/collection/source.json')))
    source['parentSource']['relocatedSource']=dict(path=str(relocated),sha256=sha(relocated))
    source_path=Path(protocol['augmentationSource'])
    document(source_path,source)
    protocol.update(java=java,python=python,numerical=identity,environment=environment,
        transportManifestSha256=manifest_sha,hostRequestSha256=request_sha,
        augmentationSourceSha256=sha(source_path))
    command=[java['executable'],'-Xmx1536m','-cp',str(root/'runtime/lib/*'),
        'dev.aragos.aor.training.TokenOrderNeighborMainKt',str(source_path),sha(source_path),
        str(root/'sources/native-preparation'),'prepare','10','0.1']
    with (root/'native-preparation.log').open('x') as log:
        result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=360,check=False,
                              env=dict(environment['variables'],AOR_TRAINING_MACHINE_CPUS='16'))
    require(result.returncode==0,'Metadata-only native relocation preflight failed; no games started.')
    protocol['nativePreparationSha256']=sha(protocol['nativePreparation'])
    target.validate_inputs(protocol)
    document(root/'environment.json',environment)
    document(root/'numerical-environment.json',identity)
    document(root/'protocol-draft.json',protocol)
    print(dict(draftSha256=sha(root/'protocol-draft.json'),numericalPins=len(identity['files']),newGames=0))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('prepare','finalize'))
    parser.add_argument('--root',type=Path,required=True)
    for name in ('parent','parent-audit','native','plan','request'):
        parser.add_argument('--'+name,type=Path)
    parser.add_argument('--request-sha');parser.add_argument('--manifest-sha')
    args=parser.parse_args()
    if args.command=='prepare':
        prepare(args.root.resolve(),args.parent.resolve(),args.parent_audit.resolve(),args.native.resolve(),args.plan.resolve())
    else:
        finalize(args.root.resolve(),args.request.resolve(),args.request_sha,args.manifest_sha)
