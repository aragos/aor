"""One bounded query-coverage experiment over immutable fixed-T parent evidence.

Only missing branch labels are simulated. Reused development labels select a frozen head;
three fresh paired banks, including a varied-bid rival pool, own all strength evidence.
"""
import argparse
from dataclasses import asdict
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess

import automata_token_eight_tail as prior
import token_order_augmentation as data
import token_order_reference_neighbors as codec

previous, arena, exp, evolution, controller = prior.previous,prior.arena,prior.exp,prior.evolution,prior.controller
read,sha,document,require = prior.read,prior.sha,prior.document,prior.require
KIND = codec.CAMPAIGN_KIND
STAGES = ('augment','fit','screen','confirm','heldout')
CEILINGS = (21600,600,1800,3600,7200)
COUNTS = dict(screen=512,confirm=2048,heldout=4096)
REPLAYS = [3,7,10,11,514,516,517,519]
AUDIT = '933db80abe1ff3e1fc0f63ca91229205fcdec8917f6a98438d0c03e3ba4c773d'


def numerical_environment(protocol):
    import adaptive_token_environment
    return adaptive_token_environment


def validate_inputs(protocol):
    require(protocol['schemaVersion']==1 and protocol['kind']==KIND
            and protocol['workers']==10 and protocol['maximumSeconds']==21600
            and protocol['maximumAttempts']==1 and protocol['heapMiB']==1536
            and protocol['screenRule']==prior.SCREEN and protocol['qualificationConfidence']==.99
            and not protocol['productionActivation'] and not protocol['automaticNextCampaign'],
            'Require the fixed ten-worker, six-hour, single-attempt neighbor experiment.')
    require(protocol['proposalVersion']==codec.PROPOSALS
            and protocol['selectorVersion']==codec.SELECTOR_VERSION
            and protocol['referenceProtocol']==data.fixed.REFERENCE
            and protocol['learnerSelector']==codec.SELECTOR
            and protocol['validationPreviouslyInspected'] is True
            and protocol['validationPurpose']=='REUSED_DEVELOPMENT_SELECTION_ONLY'
            and protocol['trainingRoots']==512 and protocol['validationRoots']==128
            and protocol['continuationReplicates']==8 and protocol['replayOrigins']==REPLAYS
            and protocol['addedBranches']==314 and protocol['addedLabelTails']==2512
            and protocol['maximumArenaTerminals']==13312 and protocol['maximumReplayTerminals']==88
            and protocol['maximumTerminalGames']==15912
            and protocol['maximumHistoricalOriginReplays']==8
            and protocol['maximumHistoricalArenaReplays']==24
            and protocol['maximumHistoricalTerminalBranches']==64
            and protocol['oldOutcomeTailsResimulated']==0,
            'All inherited roots, missing branches, historical checks and new arena caps are explicit.')
    require(read(protocol['fitConfig'])==prior.FIT and protocol['diagnosticPrefixes']==[2,4]
            and protocol['diagnosticPrefixesAffectSelection'] is False,
            'Keep the same frozen64 basis, five penalties and primary eight-tail selection.')
    require(sha(protocol['baseline'])==protocol['baselineSha256']==prior.BASELINE
            and sha(protocol['expansionModel'])==protocol['expansionSha256']==prior.EXPANSION
            and {key:sha(path) for key,path in protocol['frozenPurchase'].items()}
                ==protocol['frozenPurchaseSha256']=={'baseline':previous.F0,'policy':previous.C}
            and sha(protocol['initializer'])==prior.INITIALIZER
            and sha(protocol['originalBasisIdentity'])==prior.BASIS_IDENTITY,
            'Preserve exact qualified M8/E/T/C64/F0 and original unfitted64 basis.')
    require(protocol['dataVersion']=='ah1996-catalog-16'
            and protocol['experimentalRulesTransfer']==previous.legacy.TRANSFER
            and protocol['sourceRulesVersion']==previous.legacy.TRANSFER['sourceRulesVersion']
            and protocol['targetRulesVersion']==previous.legacy.TRANSFER['targetRulesVersion']
            and exp.distribution_hashes(Path(protocol['runtime']))==protocol['runtimeFiles']
            and sha(Path(protocol['runtime'])/'lib/training.jar')==protocol['runtimeTrainingJarSha256']
            and sha(Path(protocol['scripts'])/'token_order_metadata.py')==protocol['metadataValidatorSha256'],
            'Rules transfer, catalog, installed helper closure and native runtime must remain frozen.')
    source,parent,_,plan=data.parent_inputs(protocol['augmentationSource'])
    require(sha(protocol['augmentationSource'])==protocol['augmentationSourceSha256']
            and source['replayOrigins']==REPLAYS and sha(parent/'dataset.json')==protocol['parentDatasetSha256'],
            'The augmentation source and exact completed parent bank changed.')
    require(sha(protocol['seedAudit'])==AUDIT and set(protocol['banks'])==set(COUNTS),
            'Bind all three fresh arena banks and the explicit inherited-stream audit.')
    audit=read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and audit['freshArenaWorlds']==6656
            and audit['inheritedCounterfactualNewLabelTails']==2512
            and audit['inheritedCounterfactualPlanSha256']==data.BRANCH_PLAN
            and audit['inheritedCounterfactualDatasetSha256']==sha(parent/'dataset.json'),
            'Inherited counterfactual streams are intentional reuse; arena worlds must all be fresh.')
    streams={}
    for name,count in COUNTS.items():
        bank=protocol['banks'][name]
        require(bank==audit['seedBanks'][name] and bank['count']==count
                and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],count),
                'A prospective arena differs from its audited complete world bank.')
        streams[name]=bank['seeds']
    exp.validate_banks(streams)
    require(protocol['heldoutRivalSelectors']==['FIXED_16_TO_13','FIXED_16_TO_14','FIXED_16_TO_15','ORDINARY'],
            'Heldout uses four fixed conditional-bid roles with E/M8/C64 on every rival.')
    proof=read(protocol['nativePreparation'])
    require(sha(protocol['nativePreparation'])==protocol['nativePreparationSha256']
            and proof['sourceSha256']==sha(protocol['augmentationSource']) and proof['mode']=='prepare'
            and proof['prefixesReconstructed']==0 and proof['tailsGenerated']==0
            and proof['newBranches']==314 and proof['newTerminalTails']==2512,
            'Require the exact installed native parser and parent-lineage preparation proof, with zero games.')


def native(protocol,output,mode):
    directory=output/('augmentation-replay' if mode=='replay' else 'augmentation')
    require(not directory.exists(),'One new-branch attempt only; no resume or top-ups.')
    remaining=exp.remaining_seconds()
    source=Path(protocol['augmentationSource'])
    command=[protocol['java']['executable'],'-Xmx1536m',
        '-XX:ActiveProcessorCount=10','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1',
        '-cp',str(Path(protocol['runtime'])/'lib/*'),'dev.aragos.aor.training.TokenOrderNeighborMainKt',
        str(source),sha(source),str(directory),mode,'10',str(min(6.,remaining/3600))]
    if mode=='replay':
        command.append(str(output/'augmentation'))
    with (output/(mode+'-native.log')).open('x') as log:
        child=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=remaining,check=False,
            env=dict(os.environ,AOR_TRAINING_MACHINE_CPUS=str(os.cpu_count() or 1)))
    require(child.returncode==0,'Native missing-branch augmentation failed; no automatic recovery.')
    attempt=read(directory/'attempt.json')
    start=datetime.fromisoformat(attempt['startedAt'].replace('Z','+00:00')).timestamp()
    deadline=datetime.fromisoformat(attempt['deadlineAt'].replace('Z','+00:00')).timestamp()
    require(attempt['attempt']==1 and attempt['retries']==0 and attempt['workers']==10
            and 0<deadline-start<=21600.001,'Native attempt/worker/deadline ownership changed.')
    return directory


def bindings(protocol,output):
    # Canonical digest is separately pinned; the initializer bytes remain the original export.
    return data.AugmentedBindings(str(output/'derived-view.json'),sha(output/'derived-view.json'),
        protocol['initializer'],prior.INITIALIZER,protocol['initializerCanonicalSha256'],
        protocol['originalBasisIdentity'],prior.BASIS_IDENTITY)


def numerical_child(protocol,output,operation):
    numerical_environment(protocol).verify_numerical(protocol['numerical'])
    import token_order_eight_tail as fitter
    inputs=bindings(protocol,output)
    if operation=='fit-child':
        document(output/'fit-inputs.json',asdict(inputs))
        fitter.fit(inputs,output/'fit',fitter.ridge.FitConfig(),fixed_reference=True)
    else:
        model,rows,provenance=data.load_collection(inputs)
        document(output/'collection-validation.json',dict(provenance=provenance,
            collected={split:sum(row['split']==split for row in rows) for split in ('TRAINING','VALIDATION')},
            initializerSha256=prior.INITIALIZER,initializedModelDigest=fitter.b.digest(model),
            validationPurpose='REUSED_DEVELOPMENT_SELECTION_ONLY'))


def run_numerical(protocol,protocol_path,output,operation):
    command=[protocol['numerical']['executable'],'-E','-s','-B',
        str(Path(protocol['scripts'])/Path(__file__).name),operation,
        '--protocol',str(protocol_path),'--output',str(output)]
    with (output/(operation+'.log')).open('x') as log:
        child=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,
            timeout=min(exp.remaining_seconds(),600),check=False)
    require(child.returncode==0,'Derived validation or fixed ridge fitting failed; no alternative fit.')


def augment(protocol,protocol_path,output):
    directory=native(protocol,output,'augment')
    primary=data.verify_native(protocol['augmentationSource'],directory)
    replay=native(protocol,output,'replay')
    data.assemble_view(protocol['augmentationSource'],directory,replay,output/'derived-view.json')
    run_numerical(protocol,protocol_path,output,'validate-augmentation')
    check=read(output/'collection-validation.json')
    artifacts=[path for root in (directory,replay) for path in root.rglob('*') if path.is_file()]
    return previous.stage_receipt(protocol,protocol_path,output,'augment',True,
        'All640 parent roots retained; only314 new branches and eight historical new-branch replays completed.',
        dict(check,augmentation=primary),artifacts+[output/'derived-view.json',output/'collection-validation.json'])


def fit(protocol,protocol_path,output):
    require(not (output/'fit').exists(),'One unchanged five-penalty ridge fit, without retries.')
    run_numerical(protocol,protocol_path,output,'fit-child')
    report,admitted=prior.verify_fit(protocol,output)
    selection=dict(admitted=admitted,fitReportSha256=sha(output/'fit/fit.json'),productionActivation=False,
                   validationPurpose='REUSED_DEVELOPMENT_SELECTION_ONLY')
    if admitted:
        model=evolution.freeze_copy(output/'fit/candidate.json',output/'selected/token-order.json')
        selection.update(model=str(model),modelSha256=sha(model))
        for stage in COUNTS:
            document(output/(stage+'-source.json'),previous.arena_source(protocol,model,stage))
    document(output/'selection.json',selection)
    artifacts=[path for name in ('fit','selected') for path in (output/name).rglob('*') if path.is_file()]
    artifacts+=list(output.glob('*-source.json'))+[output/'selection.json',output/'fit-inputs.json']
    return previous.stage_receipt(protocol,protocol_path,output,'fit',admitted,
        'Reused-development ridge selection and unchanged positive-policy gate completed; fresh arenas still required.',
        selection,artifacts)


def require_previous(protocol_path,output,stage):
    state=read(output/'campaign-state.json')
    for name in STAGES[:STAGES.index(stage)]:
        path=output/'results'/(name+'.json')
        receipt=controller.validate_result(path,output)
        require(receipt['status']==state['stages'][name]['status']=='passed'
                and sha(path)==state['stages'][name]['resultSha256']
                and receipt['metrics']==read(output/(name+'-report.json'))
                and receipt['metrics']['protocolSha256']==sha(protocol_path),
                'Each stage requires all original prior receipts and artifacts from this exact protocol.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('stage','validate-augmentation','fit-child'))
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=STAGES)
    options=parser.parse_args()
    protocol,output=read(options.protocol),options.output.resolve()
    if options.command!='stage':
        numerical_child(protocol,output,options.command)
        return
    import token_eight_tail_campaign as campaign
    campaign.verify_execution(protocol,options.protocol,output)
    validate_inputs(protocol)
    require_previous(options.protocol,output,options.stage)
    operation={'augment':augment,'fit':fit}.get(options.stage)
    receipt=(operation(protocol,options.protocol,output) if operation
             else prior.evaluate(protocol,options.protocol,output,options.stage))
    print(json.dumps(dict(stage=options.stage,status=receipt['status'])),flush=True)


if __name__=='__main__':
    main()
