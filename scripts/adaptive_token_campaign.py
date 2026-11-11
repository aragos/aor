"""Exact pure-supervisor preflight and one bounded adaptive-token experiment."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time

import automata_campaign as controller
import automata_adaptive_token as target

exp,read,sha,document,require = target.exp,target.read,target.sha,target.document,target.require
DYNAMIC_ENV = {'AOR_CAMPAIGN_OUTPUT','AOR_CAMPAIGN_STAGE_ID','AOR_CAMPAIGN_DEADLINE_EPOCH',
               'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'}
STAGES = target.STAGES


def verify_execution(protocol,path,output):
    root = Path(path).resolve().parent
    require(root == Path(__file__).resolve().parents[1] and output == root/'run',
            'Execute only in the declared isolated adaptive-token package.')
    environment = protocol['environment']
    require(platform.platform() == environment['platform'] and platform.machine() == environment['machine']
            and os.cpu_count() == environment['logicalCpus'],'Frozen adaptive-token machine identity changed.')
    require(str(Path(sys.executable).resolve()) == protocol['python']['executable']
            and sys.version == protocol['python']['version'] and sys.flags.ignore_environment
            and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Use the exact clean Python child with -E -s -S -B.')
    require({key:value for key,value in os.environ.items() if key not in DYNAMIC_ENV}
            == environment['variables'] and read(root/'environment.json') == environment,
            'Frozen adaptive-token child environment changed.')
    for path,digest in protocol['frozenFiles'].items():
        require(sha(path) == digest,'Frozen adaptive-token package input changed: '+path)
    for name in ('scripts','runtime','inputs','audit','evidence','sources'):
        actual = {str(path) for path in (root/name).rglob('*') if path.is_file()}
        declared = {path for path in protocol['frozenFiles'] if Path(path).is_relative_to(root/name)}
        require(actual == declared,'Unexpected or missing adaptive-token package files in '+name)
    require(protocol['incumbentQualified'] is True and protocol['nativeContractVerified'] is True,
            'Require exact qualified E+T evidence and verified overlay collector/selector runtime.')


def campaign_spec(root,protocol,files):
    return dict(schemaVersion=1,kind='automata-campaign',inputs=files,
        limits=dict(maximumSeconds=21600,maximumDataBytes=4*exp.GIB,maximumRssBytes=4*exp.GIB,minimumFreeBytes=2*exp.GIB),
        stages=[dict(id=name,dependsOn=[] if index == 0 else [STAGES[index-1]],
            maximumSeconds=target.CEILINGS[index],maximumAttempts=1,result=f'results/{name}.json',
            command=[protocol['python']['executable'],'-E','-s','-S','-B',str(root/'scripts/automata_adaptive_token.py'),'stage',
                     '--protocol',str(root/'protocol.json'),'--output','{output}','--stage',name])
            for index,name in enumerate(STAGES)])


def preflight_child(path,output):
    protocol = read(path)
    verify_execution(protocol,path,output)
    target.validate_inputs(protocol)
    require(not output.exists(),'Preflight must run before any adaptive-token campaign attempt.')
    import adaptive_token_environment as numerical
    require(numerical.inspect_numerical(protocol['numerical']['executable']) == protocol['numerical'],
            'The exact clean child must reproduce its separately pinned CPU numerical environment.')
    samples = controller.processes.sample_group(os.getpgrp())
    own = [sample for sample in samples if sample.pid == os.getpid()]
    require(len(own) == 1 and own[0].rss_bytes > 0,'Exact child must pass the external process sampler.')
    print(json.dumps(dict(passed=True,exactChildEnvironment=True,numericalEnvironmentVerified=True,externalPsSamplerPassed=True,
                         nativeGames=0,protocolSha256=sha(path))),flush=True)


def external_child_preflight(root,path):
    protocol = read(path)
    deadline = time.time()+21600
    environment = dict(protocol['environment']['variables'],AOR_CAMPAIGN_OUTPUT=str(root/'run'),
        AOR_CAMPAIGN_STAGE_ID='collect',AOR_CAMPAIGN_DEADLINE_EPOCH=str(deadline),
        AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH=str(deadline))
    command = [protocol['python']['executable'],'-E','-s','-S','-B',str(root/'scripts/adaptive_token_campaign.py'),
               'preflight-child','--protocol',str(path),'--output',str(root/'run')]
    child = subprocess.run(command,cwd=root,env=environment,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,text=True,start_new_session=True,timeout=60,check=False)
    require(child.returncode == 0,'Exact adaptive-token child preflight failed: '+child.stderr[-4000:])
    result = json.loads(child.stdout)
    require(result['passed'] is True and result['nativeGames'] == 0 and result['protocolSha256'] == sha(path),
            'Exact adaptive-token preflight did not verify the current protocol.')
    return dict(passed=True,checkedAt=time.time(),command=command,protocolSha256=sha(path),
        environmentSha256=sha(root/'environment.json'),verifierSha256=sha(root/'scripts/adaptive_token_campaign.py'),
        validatorSha256=sha(root/'scripts/automata_adaptive_token.py'),childResult=result,
        stdout=child.stdout,stderr=child.stderr,nativeGames=0)


def input_files(root,protocol):
    files = {str(path):sha(path) for path in sorted(root.rglob('*')) if path.is_file() and '__pycache__' not in path.parts}
    files.update(protocol['java']['files'])
    files.update(protocol['python']['files'])
    files.update(protocol['numerical']['files'])
    return files


def prepare_preflight(root):
    require(not any((root/name).exists() for name in ('protocol.json','campaign.json','run')),
            'Exact child preflight precedes freezing and launch.')
    protocol = read(root/'protocol-draft.json')
    protocol['frozenFiles'] = input_files(root,protocol)
    path = root/'preflight-protocol.json'
    document(path,protocol)
    receipt = external_child_preflight(root,path)
    document(root/'exact-child-preflight.json',receipt)
    print(json.dumps(dict(passed=True,receiptSha256=sha(root/'exact-child-preflight.json'),nativeGames=0)))


def freeze(root):
    require(not any((root/name).exists() for name in ('protocol.json','campaign.json','run')),'Freeze exactly once before launch.')
    protocol = read(root/'protocol-draft.json')
    preflight = read(root/'exact-child-preflight.json')
    require(preflight['passed'] and preflight['nativeGames'] == 0
            and preflight['protocolSha256'] == sha(root/'preflight-protocol.json')
            and preflight['environmentSha256'] == sha(root/'environment.json')
            and preflight['verifierSha256'] == sha(root/'scripts/adaptive_token_campaign.py')
            and preflight['validatorSha256'] == sha(root/'scripts/automata_adaptive_token.py'),
            'Freeze requires exact successful child/sampler validation of this package.')
    previous = read(root/'preflight-protocol.json')
    require({k:v for k,v in previous.items() if k != 'frozenFiles'} == protocol,
            'Scientific draft changed after exact-child preflight.')
    for path,digest in previous['frozenFiles'].items():
        require(sha(path) == digest,'A preflight input changed before freeze: '+path)
    protocol['frozenFiles'] = input_files(root,protocol)
    target.validate_inputs(protocol)
    document(root/'protocol.json',protocol)
    spec = campaign_spec(root,protocol,dict(protocol['frozenFiles'],**{str(root/'protocol.json'):sha(root/'protocol.json')}))
    controller.validate_spec(spec,root/'run')
    document(root/'campaign.json',spec)
    print(json.dumps(dict(protocolSha256=sha(root/'protocol.json'),campaignSha256=sha(root/'campaign.json'),pins=len(spec['inputs']),unlaunched=True)))


def run(root,options):
    require(sha(root/'protocol.json') == options.protocol_sha and sha(root/'campaign.json') == options.campaign_sha,
            'Reviewed adaptive-token protocol/campaign changed.')
    protocol = read(root/'protocol.json')
    os.environ.clear()
    os.environ.update(protocol['environment']['variables'])
    verify_execution(protocol,root/'protocol.json',root/'run')
    target.validate_inputs(protocol)
    expected = campaign_spec(root,protocol,dict(protocol['frozenFiles'],**{str(root/'protocol.json'):options.protocol_sha}))
    require(read(root/'campaign.json') == expected,'Adaptive-token commands, limits or input pins changed.')
    require(not (root/'run').exists(),'One adaptive-token attempt only; no automatic recovery, resume or new campaign.')
    predecessor = Path(protocol['predecessorCampaign'])
    previous = read(predecessor/'run/campaign-state.json')
    require(previous['status'] == 'completed'
            and all(row['status'] == 'passed' for row in previous['stages'].values()),
            'The preceding cross-play diagnostic must complete before replacing its ten-worker allocation.')
    groups = {row['processGroup'] for row in previous['stages'].values() if row.get('processGroup')}
    require(all(not controller.processes.sample_group(group) for group in groups),
            'The preceding ten-worker process groups must exit before this launch.')
    document(root/'launch-child-preflight.json',external_child_preflight(root,root/'protocol.json'))
    return controller.main(['--spec',str(root/'campaign.json'),'--output',str(root/'run')])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest='command',required=True)
    commands.add_parser('prepare-preflight');commands.add_parser('freeze')
    child=commands.add_parser('preflight-child');child.add_argument('--protocol',type=Path,required=True);child.add_argument('--output',type=Path,required=True)
    launch=commands.add_parser('run');launch.add_argument('--protocol-sha',required=True);launch.add_argument('--campaign-sha',required=True)
    options=parser.parse_args();root=Path(__file__).resolve().parents[1]
    if options.command=='prepare-preflight':prepare_preflight(root)
    elif options.command=='freeze':freeze(root)
    elif options.command=='preflight-child':preflight_child(options.protocol,options.output)
    else:sys.exit(run(root,options))
