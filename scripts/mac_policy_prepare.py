"""Conditional zero-game local search admission, after the preceding physical closure only."""
import argparse
import ast
import importlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import mac_policy_search as search
import robust_token_campaign as c
import robust_token_worker as resource


def declaration(path):
    return dict(path=str(Path(path).resolve()),sha256=c.sha(path))


def build_protocol(contract, method, schedule, seed_audit, root_proof, parent_terminal,
                   parent_closure, arena_sha, *, now=time.time, extra=None):
    method=contract.read_method(method)
    c.require(c.sha(schedule)==contract.SCHEDULE_SHA and c.sha(seed_audit)==contract.RNG_AUDIT_SHA and
        c.sha(root_proof)==contract.ROOT_RNG_PROOF_SHA,
        'Only the independently approved complete RNG metadata subjects may prepare this study.')
    proof=c.read(root_proof)
    c.require(proof['passed'] is True and proof['scheduleSha256']==c.sha(schedule) and
        proof['auditSha256']==c.sha(seed_audit),
        'The independent root freshness proof must bind this exact schedule and original seed audit.')
    seed_record=c.read(seed_audit)
    for item in [*seed_record['verifiedAncestors'],seed_record['carriedForwardAudit']]:
        c.require(c.sha(item['path'])==item['sha256'],
            'An original protected RNG ancestry subject changed before actual preparation.')
    reserved=c.read(schedule)
    plan=dict(method,kind=contract.KIND,methodSha256=contract.METHOD_SHA,preparedEpoch=now(),
        reservedSchedule=reserved,scheduleSha256=c.sha(schedule),seedAudit=declaration(seed_audit),
        rootSeedProof=declaration(root_proof),parentTerminal=declaration(parent_terminal),
        parentClosure=declaration(parent_closure),inferenceAssetSha256=contract.ASSET_SHA,
        nominationSha256=contract.NOMINATION_SHA,arenaSha256=arena_sha,
        banks={bank['name']:dict(bank,seeds=reserved['banks'][bank['name']]['seeds'])
            for bank in method['banks']})
    if extra:plan.update(extra)
    contract.validate(plan);search.prerequisite(plan,contract)
    return plan


def source_closure(directory, entries):
    """Copy each local import exactly once; immutable native/model bytes stay pinned separately."""
    directory=Path(directory);pending=list(entries);names=set(entries)
    while pending:
        path=directory/pending.pop()
        for node in ast.walk(ast.parse(path.read_text())):
            modules=([node.module] if isinstance(node,ast.ImportFrom) and node.module else
                [item.name for item in node.names] if isinstance(node,ast.Import) else [])
            for module in modules:
                name=module.split('.')[0]+'.py'
                if (directory/name).is_file() and name not in names:names.add(name);pending.append(name)
    return [directory/name for name in sorted(names)]


def prepare(contract, method, schedule, seed_audit, root_proof, parent_terminal, parent_closure,
            old_profile, native_readiness, source_readiness, candidate, root, python, *, extra=None):
    root=Path(root).resolve();candidate=Path(candidate).resolve();old=c.read(old_profile)
    c.require(not root.exists(),'A local search preparation is new-only and cannot replace an earlier attempt.')
    plan=build_protocol(contract,method,schedule,seed_audit,root_proof,parent_terminal,parent_closure,
        old['arena']['sha256'],extra=extra)
    native=c.read(native_readiness)
    source=c.read(source_readiness)
    scripts_to_copy=source_closure(Path(__file__).parent,
        ('mac_policy_search.py','mac_policy_prepare.py','mac_policy_close.py',contract.__name__+'.py'))
    c.require(source['passed'] is True and source['sourceOnly'] is True and
        all(c.sha(path)==digest for path,digest in source['sourceFiles'].items()) and
        all(source['sourceFiles'].get(str(path.resolve()))==c.sha(path) for path in scripts_to_copy),
        'All executing search modules must belong to the exact reviewed retained source closure.')
    c.require(native['passed'] is True and native['sourceOnly'] is True and
        c.sha(Path(native['runtimeRoot'])/'lib/training.jar')==plan['nativeJarSha256'] and
        all(c.sha(path)==digest for path,digest in native['runtimeFiles'].items()) and
        {str(path) for path in Path(native['runtimeRoot']).rglob('*') if path.is_file()}==set(native['runtimeFiles']),
        'The original reviewed native runtime must remain completely unchanged before admission.')
    parent=root.parent;parent.mkdir(parents=True,exist_ok=True)
    c.require(shutil.disk_usage(parent).free>=plan['minimumFreeBytes']+64*1024**2,
        'Preparation must preserve the original minimum free disk after copying its runtime.')
    root.mkdir();scripts=root/'runtime/bin';library=root/'runtime/lib'
    scripts.mkdir(parents=True);library.mkdir()
    for path in (Path(native['runtimeRoot'])/'lib').glob('*.jar'):shutil.copyfile(path,library/path.name)
    for path in scripts_to_copy:shutil.copyfile(path,scripts/path.name)
    c.write(root/'protocol.json',plan)
    profile=dict(kind='mac-policy-search-host-1',study=plan['study'],host='MAC',root=str(root),
        python=str(python),java=old['java'],classpath=str(library/'*'),jvmOptions=old['jvmOptions'],
        workers=plan['workers'],heapMiB=plan['heapMiB'],maximumRssBytes=plan['maximumRssBytes'],
        minimumFreeBytes=plan['minimumFreeBytes'],maximumDataBytes=plan['maximumDataBytes'],
        inferenceAsset=old['inferenceAsset'],nomination=old['nomination'],arena=old['arena'],
        candidateBundle=declaration(candidate),nativeJar=declaration(library/'training.jar'),pins={})
    pins={str(path):c.sha(path) for path in [*library.glob('*.jar'),*scripts.glob('*.py'),
        root/'protocol.json',Path(python),Path(old['java']),Path(method),Path(schedule),Path(seed_audit),
        Path(root_proof),Path(parent_terminal),Path(parent_closure),Path(native_readiness),Path(source_readiness)]}
    pins.update({path:digest for path,digest in old['pins'].items() if path.endswith('/lib/server/libjvm.dylib')})
    admissions={}
    for name in contract.BANK_ORDER:
        source=contract.native_source(plan,profile,name,plan['caseConfigs'][0],0,c.sha(root/'protocol.json'))
        source_path,target=root/f'preflight-{name}-source.json',root/f'preflight-{name}'
        c.write(source_path,source)
        command=[profile['java'],'-Xmx'+str(profile['heapMiB'])+'m',*resource.java_options(profile),
            '-cp',profile['classpath'],'dev.aragos.aor.training.TrainingMainKt','--mode','robust-token-prepare',
            '--source',str(source_path),'--output',str(target)]
        environment={key:value for key,value in os.environ.items()
            if key not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
        result=subprocess.run(command,capture_output=True,env=environment,timeout=120)
        (root/f'preflight-{name}.log').write_bytes(result.stdout+result.stderr)
        c.require(result.returncode==0,'The actual zero-game native search admission failed; preserve its log.')
        admission=c.read(target/'prepared.json')
        c.require(admission['passed'] is True and admission['newGames']==admission['modelForwards']==0 and
            admission['sourceSha256']==c.sha(source_path) and
            admission['scienceIdentity']['selector']==source['selector'] and admission['count']==source['count'],
            'The actual zero-game search admission must bind the exact original selector, source and chunk.')
        contract.check_prepared(admission,name);contract.check_inputs(admission,source)
        pins.update(admission['inputPins']);pins[str(target/'prepared.json')]=c.sha(target/'prepared.json')
        pins[str(source_path)]=c.sha(source_path)
        admissions[name]=dict(scienceSha256=admission['scienceSha256'],preparedSha256=c.sha(target/'prepared.json'))
    profile['pins']=pins;c.write(root/'profile.json',profile)
    search.verify_profile(root/'profile.json',plan)
    ready=dict(kind='mac-policy-search-preflight-1',passed=True,study=plan['study'],
        profileSha256=c.sha(root/'profile.json'),protocolSha256=c.sha(root/'protocol.json'),
        nativeAdmissions=admissions,inputPins=len(pins),newGames=0,newFits=0,modelForwards=0,
        clockCreated=False,launchAuthorized=False)
    c.write(root/'readiness.json',ready)
    return ready


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract',choices=('learned_bid_contract','mac_leader_contract'),required=True)
    for name in ('method','schedule','seed-audit','root-proof','parent-terminal','parent-closure',
                 'old-profile','native-readiness','source-readiness','candidate','root','python'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--extra',type=Path)
    args=parser.parse_args();values=vars(args);contract=importlib.import_module(values.pop('contract'))
    extra=values.pop('extra');values['extra']=c.read(extra) if extra else None
    print(json.dumps(prepare(contract,**values),sort_keys=True))
