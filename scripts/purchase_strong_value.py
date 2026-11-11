"""One fresh strong-ecology value diagnostic; no actor updates, arena or activation."""
import argparse
from dataclasses import asdict
import json
import os
from pathlib import Path
import platform
import subprocess

import automata_campaign as controller
import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as roles
import purchase_storage as storage
import purchase_strong_data as strong
import purchase_strong_value_data as data
import purchase_value_campaign as gpu

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
AUDIT='622d806700b9b19e22909d01929c9896a73c2838cfcd556965f19a5444537f34'


def batch_source(protocol,index):
    source=matched.collection_source(dict(protocol,banks=dict(protocol['banks'],
        training=protocol['banks'][data.bank_name(index)])),protocol['purchasePolicy'])
    return dict(source,**roles.bindings(protocol['expansionModel'],collection_ecology=strong.ECOLOGY))


def validate_inputs(protocol):
    require(protocol['kind']==data.KIND and protocol['workers']==10
        and protocol['maximumSeconds']==7200 and protocol['maximumAttempts']==1
        and protocol['maximumScientificTerminalGames']==4096
        and protocol['maximumHistoricalTerminalReproductions']==64
        and protocol['maximumTerminalExecutions']==4160
        and protocol['maximumDataBytes']==8*exp.GIB and protocol['maximumRssBytes']==6*exp.GIB
        and protocol['minimumFreeBytes']==4*exp.GIB
        and protocol['splitCounts']==data.SPLITS and protocol['batches']==16
        and protocol['collectionEcology']==strong.ECOLOGY.value
        and protocol['testModels']==['validation-selected','original-fresh64']
        and all(protocol[key] is False for key in ('qualificationPassed','productionActivation',
            'automaticNextCampaign','actorTraining','automaticResume')),
        'Use one fixed16-batch, two-fit, sealed-test value study with no playing-strength claim.')
    require(protocol['configuration']==exp.CONFIGURATION
        and protocol['nativeSourceCommit']=='1b1b1c6cf2bc3c94258a4078d9d3bfeaa5361ca1'
        and protocol['experimentalRulesTransfer']==legacy.RULES_TRANSFER
        and sha(protocol['baseline'])==matched.ORDINARY and sha(protocol['expansionModel'])==matched.EXPANSION
        and sha(protocol['purchasePolicy'])==strong.G2 and sha(protocol['prior'])==roles.F0
        and sha(protocol['critic'])==matched.CRITIC and protocol['opponents']==[protocol['baseline']]*4
        and sha(protocol['qualificationAudit'])==AUDIT,
        'Freeze qualified G2 in every purchase seat and unchanged complete ETM8/F0 outside purchases.')
    audit=read(protocol['qualificationAudit'])
    require(audit['passed'] and audit['qualificationPassed'] and audit['candidateSha256']==strong.G2
        and audit['allOwnedProcessesExited'],'The exact completed47 qualification is required.')
    banks=protocol['banks'];seeds=read(protocol['seedAudit'])
    require(banks==seeds['seedBanks'] and seeds['allIntersectionsZero']
        and seeds['allDeclared48StreamsPreserved'] and seeds['liveMetadataScans']==seeds['humanInputsRead']==0
        and len(banks)==20,'Use complete frozen48 ancestry and no live game scans.')
    for index in range(1,17):
        bank=banks[data.bank_name(index)]
        require(bank['count']==256 and bank['sourceIndex']==index
            and bank['role']==data.split(index) and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],256),
            'The exact prospective whole-world source/split assignment changed.')
    require(protocol['fitConfig']==asdict(data.config(banks)),'Original fit math or audited RNG roles changed.')
    exp.validate_banks({name:bank['seeds'] for name,bank in banks.items()})
    require(sha(protocol['inferenceContract'])=='120748a9cfe6e018a6885108c0b652990d034a41cfc870600fb6a04bbe22ef45'
        and sha(protocol['stateDictionary'])=='8261e8bfa69892f6971fb6fca340860610d59afb93c26bcf264039d460bb6864'
        and read(protocol['purchasePolicy'])['stateFeatureNames']==read(protocol['stateDictionary']),
        'The fixed original fresh64 critic uses exactly the unchanged actor-visible state dictionary.')


def collect(protocol,output):
    require(not (output/'batches').exists(),'Each collection permits one attempt, never resume.')
    batches=[]
    for index in range(1,17):
        exp.remaining_seconds()
        directory=output/'batches'/f'batch-{index}'
        source=directory/'collection-source.json';document(source,batch_source(protocol,index))
        exp.native(protocol,'purchase-collect',source,directory/'collection',
            extra=('--workers',str(protocol['workers'])))
        provenance,decisions=strong.verify_collection(directory/'collection')
        require(sha(directory/'collection/policy.json')==strong.G2,'The controlled actor changed across fixed-policy batches.')
        ecology.reproduction(protocol,directory)
        games,nonzeros=data.batch_declaration(directory,index,protocol['banks'][data.bank_name(index)])
        archived=storage.archive(directory)
        archive=dict(dataset=data.KIND,batch=f'batch-{index}',
            packageArchive=str((directory/storage.ARCHIVE).relative_to(output)),archiveSha256=archived['archiveSha256'],
            packageMetadata=str((directory/'collection/collection.json').relative_to(output)),
            metadataSha256=sha(directory/'collection/collection.json'))
        receipt=dict(index=index,split=data.split(index),sourceSha256=sha(source),games=games,
            nonzeros=nonzeros,decisions=decisions,provenance=provenance,archive=archive,
            replaySha256=sha(directory/'reproduction/reproduction.json'),
            storageManifestSha256=sha(directory/storage.MANIFEST),scientificTerminalGames=256,historicalReproductions=4)
        document(directory/'batch-receipt.json',receipt);batches.append(receipt)
        document(output/'progress.json',dict(completedBatches=index,scientificTerminalGames=index*256,
            historicalReproductions=index*4,actualFits=0),immutable=False)
        print(f'Completed fixed-G2 collection {index}/16; no critic fit or test evaluation yet.',flush=True)
    document(output/'corpus.json',data.corpus(batches))
    return batches


def study(protocol,path,output):
    batches=collect(protocol,output)
    command=[read(protocol['gpuEnvironment'])['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
        'numeric','--protocol',str(path),'--output',str(output)]
    environment=gpu.clean_environment()
    environment.update({key:value for key,value in os.environ.items() if key in
        ('AOR_CAMPAIGN_OUTPUT','AOR_CAMPAIGN_STAGE_ID','AOR_CAMPAIGN_DEADLINE_EPOCH','AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH')})
    with (output/'numeric.log').open('xb') as log:
        done=subprocess.run(command,env=environment,stdout=log,stderr=subprocess.STDOUT,
            timeout=exp.remaining_seconds(),check=False)
    require(done.returncode==0,'The single bounded GPU fit attempt stopped; preserve partial evidence.')
    finished=read(output/'value/finished.json');report=read(output/'value/report.json')
    require(finished['passed'] and finished['actualFits']==2 and finished['newGames']==0
        and sha(output/'value/report.json')==finished['reportSha256']
        and all(sha(output/'value'/name)==digest for name,digest in finished['artifacts'].items()),
        'Completed critic fit/test artifacts changed.')
    summary=dict(report,completedBatches=len(batches),scientificTerminalGames=4096,
        historicalReproductions=64,totalTerminalExecutions=4160,actualFits=2,protocolSha256=sha(path))
    document(output/'study-report.json',summary)
    artifacts=[output/name for name in ('progress.json','corpus.json','numeric.log','study-report.json')]
    for directory in (output/'batches',output/'value'):
        artifacts.extend(p for p in directory.rglob('*') if p.is_file())
    return exp.stage_result(output,'study',True,'Bounded predictive-value study completed; no strength qualification.',
        summary,artifacts)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage','numeric','gpu-preflight'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();protocol=read(args.protocol);validate_inputs(protocol)
    if args.command=='numeric':
        import purchase_strong_value_fit as fit
        fit.run(protocol,args.output,exp.remaining_seconds)
    elif args.command=='gpu-preflight':
        import torch
        import purchase_value_benchmark as benchmark
        gpu.verify_environment(read(protocol['gpuEnvironment']));benchmark.configure()
        weights=torch.load(protocol['critic'],map_location='cpu',weights_only=True)
        model=benchmark.Critic((64,),0);model.load_state_dict(weights,strict=True)
        cpu=model(torch.zeros((3,3556)));cuda=model.cuda()(torch.zeros((3,3556),device='cuda'))
        torch.testing.assert_close(cpu,cuda.cpu(),rtol=2e-5,atol=2e-6)
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,checkpointSha256=sha(protocol['critic']),
            gpu=torch.cuda.get_device_name(),maximumSyntheticError=float((cpu-cuda.cpu()).abs().max().detach()))))
    else:
        matched.verify_execution(protocol)
        require(platform.platform()==protocol['environment']['platform'] and os.cpu_count()==16,
                'The frozen desktop host changed.')
        if args.command=='preflight':
            require(not args.output.exists(),'Preflight must precede the single game/fit attempt.')
            own=[p for p in controller.processes.sample_group(os.getpgrp()) if p.pid==os.getpid()]
            require(len(own)==1 and own[0].rss_bytes>0,'Exact child requires external process sampling.')
            print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                protocolSha256=sha(args.protocol))))
        else:study(protocol,args.protocol,args.output)


if __name__=='__main__':main()
