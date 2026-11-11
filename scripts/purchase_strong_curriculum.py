"""One32-batch GAE continuation against fixed initialG2 purchases and homogeneous ETM8 rivals.

This changes the training rival ecology only. The final32 actor receives one1024-pair development
evaluation against initialG2, with the same fixedG2 rivals. No intermediate looks or successor.
"""
import argparse
import json
import os
from pathlib import Path
import platform

import automata_campaign as controller
import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as data
import purchase_gae_learning_curve as curve
import purchase_gae_pilot as pilot
import purchase_strong_data as strong

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
KIND='purchase-strong-opponent-curriculum-1'
INITIAL_FILES={'policy.json':strong.G2,
    'checkpoint.json':'fe00ddecc7045e333a8817ea24f3561071162bda4691a25e93359b4b154d6623',
    'identity.json':'eb9ae5f9e8da83ccc69dd63526d4d514443db788f30d9ca3cb99102bde0be3b6',
    'finished.json':'279dedeee529891fcaac5450060f36630cb308e513ac18a463be5dc983d5b548'}
INITIAL_AUDIT='11cd7cff6ec2e5aea8b9e2b84ddc5a2de33d1f3bb4163cedc35ae534061ca1da'
REPLAYS=(0,341,682,1023)


def batch_source(protocol,index,model):
    require(1<=index<=32,'Only the32 fixed strong-opponent batches are permitted.')
    source=matched.collection_source(dict(protocol,banks=dict(protocol['banks'],
        training=protocol['banks'][f'training-{index}'])),model)
    return dict(source,**data.bindings(protocol['expansionModel'],collection_ecology=strong.ECOLOGY))


def arena_source(protocol,model):
    source=exp.arena_source(protocol['baseline'],protocol['opponents'],
        protocol['banks']['checkpoint-32']['nativeOffset'],1024,protocol['baseline'],'SCREENING')
    return dict(source,**legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'],arena=True,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS),
        frozenPurchase=dict(baseline=protocol['prior'],policy=protocol['initialActor'],
            candidatePolicy=str(model),incumbentPolicy=protocol['initialActor']))


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['workers']==6 and protocol['maximumSeconds']==14400
        and protocol['maximumAttempts']==1 and protocol['batches']==32
        and protocol['checkpointBatches']==[32] and protocol['checkpointPairs']==1024
        and protocol['replayIndices']==list(REPLAYS) and protocol['advantageEstimator']==pilot.ESTIMATOR
        and protocol['maximumConsecutiveZeroAcceptedBatches']==4
        and protocol['maximumScientificTerminalGames']==10240
        and protocol['maximumHistoricalTerminalReproductions']==136
        and protocol['maximumTerminalExecutions']==10376 and protocol['finalCheckpointOnly'] is True
        and all(protocol[key] is False for key in ('qualificationPassed','productionActivation',
            'automaticNextCampaign','checkpointSelection','automaticResume','intermediateLooks')),
        'Use one32-batch/final1024-pair four-hour strong-opponent curriculum only.')
    require(protocol['configuration']==exp.CONFIGURATION
        and protocol['nativeSourceCommit']=='1b1b1c6cf2bc3c94258a4078d9d3bfeaa5361ca1'
        and protocol['experimentalRulesTransfer']==legacy.RULES_TRANSFER
        and sha(protocol['baseline'])==matched.ORDINARY and sha(protocol['expansionModel'])==matched.EXPANSION
        and sha(protocol['purchasePolicy'])==strong.G2 and protocol['purchasePolicy']==protocol['initialActor']
        and sha(protocol['prior'])==data.F0 and sha(protocol['critic'])==matched.CRITIC
        and protocol['opponents']==[protocol['baseline']]*4
        and protocol['collectionEcology']==strong.ECOLOGY.value,
        'Every rival must retain the fixed initialG2 purchase actor and complete ETM8/F0 roles.')
    require(sha(protocol['initialAudit'])==INITIAL_AUDIT,'The completed46 initialization evidence changed.')
    audit=read(protocol['initialAudit'])
    require(audit['passed'] and audit['status']=='completed' and audit['completedBatchAudits']==64
        and audit['eligibleActorSha256']==strong.G2 and audit['allOwnedProcessesExited'],
        'Initialize only the exact completed46 final64 actor; qualification47 cannot redefine this run.')
    banks=protocol['banks'];seeds=read(protocol['seedAudit'])
    require(seeds['allIntersectionsZero'] and seeds['seedBanks']==banks
        and seeds['liveMetadataScans']==0 and seeds['humanInputsRead']==0
        and set(banks)=={*[f'training-{i}' for i in range(1,33)],'checkpoint-32','updates'},
        'Fresh banks must come only from the explicitly frozen47v2/46 ancestry, without live scans.')
    updates=banks['updates']['seeds']
    require(len(updates)==128 and updates==list(range(updates[0],updates[0]+128)),
            'Retain exactly four original epoch shuffles per batch.')
    for name,bank in banks.items():
        if name=='updates':continue
        count=1024 if name=='checkpoint-32' else 256
        require(bank['count']==count and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],count),
                'The declared native world formula or bank count changed.')
    exp.validate_banks({name:bank['seeds'] for name,bank in banks.items()})
    for index in range(1,33):
        require(pilot.update_config(protocol,index)==dict(protocol['numerical']['configDefaults'],
            **dict(legacy.HYPERPARAMETERS,valueCoefficient=0),seed=updates[4*(index-1)]),
            'Strong opponents must not alter the original GAE/PPO recipe.')
    _,identity=curve.validate_initial(protocol,initial_files=INITIAL_FILES)
    require(strong.predictor_identity(protocol['critic'],protocol['inferenceContract'],
        protocol['stateDictionary'],protocol['numerical']['torchVersion'])==
        identity['actorOnlyObjective']['frozenStatePredictions']['predictor'],
        'The new collection reader cannot change the exact46 frozen state predictor identity.')


def final_checkpoint(protocol,path,output,model):
    source_path=output/'checkpoint-32-source.json';source=arena_source(protocol,model)
    directory=output/'screens/checkpoint-32'
    require(not source_path.exists() and not directory.exists(),'One final1024-pair development look only.')
    document(source_path,source);exp.native(protocol,'arena',source_path,directory)
    metrics=legacy.verify_arena(protocol,source_path,directory)
    data.verify_overlays(source,directory,[read(directory/'run.json'),read(directory/'evaluation.json')],
        arena=True,expected_expansion=matched.EXPANSION,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS)
    require({p.name for p in directory.glob('pair-*.json')}=={f'pair-{i}.json' for i in range(1024)},
            'The final comparison requires every frozen pair, including losses and ties.')
    pairs=[read(directory/f'pair-{i}.json') for i in range(1024)]
    replays=ecology.arena_replays(protocol,source_path,pairs,output/'replays/checkpoint-32',
        expected_expansion=matched.EXPANSION,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS,indices=REPLAYS)
    report=dict(batch=32,protocolSha256=sha(path),candidateSha256=sha(model),incumbentSha256=strong.G2,
        rivalPurchaseSha256=strong.G2,metrics=metrics,replays=replays,qualificationPassed=False,
        selectionPerformed=False,developmentOnly=True)
    document(output/'checkpoint-32-report.json',report)
    return report


def progress(path,output,batches,checks,reason,streak):
    return curve.progress(path,output,batches,checks,reason,streak,final_batch=32,checkpoint_pairs=1024)


def train(protocol,path,output):
    require(not (output/'progress.json').exists() and not (output/'batches').exists(),
            'Preserve every partial attempt; no automatic resume or renewed deadline.')
    model=Path(protocol['initialActor']);batches=[];checks=[];streak=0;reason='all32-batches-complete'
    for index in range(1,33):
        exp.remaining_seconds()
        model,receipt=curve.train_batch(protocol,path,output,index,model,source_builder=batch_source,
            collection_verifier=strong.verify_collection,reader_path=Path(strong.__file__),
            numeric_entrypoint=Path(__file__).resolve())
        batches.append(receipt);streak=curve.zero_streak(streak,receipt['update']['acceptedEpochs'])
        if streak==4:
            reason='four-consecutive-zero-accepted-batches'
            break
        progress(path,output,batches,checks,'running',streak)
        print(f'Completed strong-opponent batch{index}/32; no intermediate evaluation.',flush=True)
    if reason=='all32-batches-complete':checks.append(final_checkpoint(protocol,path,output,model))
    report=dict(progress(path,output,batches,checks,reason,streak),batches=batches,checkpoints=checks,
        inference='One stronger-rival curriculum and one final development comparison; no qualification or activation.')
    document(output/'strong-curriculum-report.json',report)
    artifacts=[output/'progress.json',output/'strong-curriculum-report.json',
               *output.glob('checkpoint-*-source.json'),*output.glob('checkpoint-*-report.json')]
    for root in (output/'batches',output/'screens',output/'replays'):
        artifacts.extend(p for p in root.rglob('*') if p.is_file())
    return exp.stage_result(output,'learn',True,'Bounded strong-opponent curriculum finished; no qualification.',report,artifacts)


def campaign_spec(root,protocol,files):
    return curve.campaign_spec(root,protocol,files,maximum_seconds=14400,
                               script_name='purchase_strong_curriculum.py')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','numerical-preflight','stage','numeric'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--index',type=int)
    args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);matched.verify_execution(protocol)
    expected=protocol['environment']
    require(platform.platform()==expected['platform'] and platform.machine()==expected['machine']
        and os.cpu_count()==expected['logicalCpus'],'The frozen Mac execution host changed.')
    if args.command=='preflight':
        require(not args.output.exists(),'Preflight precedes games or fitting.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'The exact child requires external process sampling.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    elif args.command=='numerical-preflight':
        print(json.dumps(curve.numerical_preflight(protocol,initial_files=INITIAL_FILES)))
    elif args.command=='numeric':
        require(1<=args.index<=32,'A numeric update requires one declared batch.')
        pilot.numeric(protocol,args.output,args.index,'gae',initial_optimizer=Path(protocol['initialOptimizer']),
                      collection_adapter=strong)
    else:train(protocol,args.protocol,args.output)


if __name__=='__main__':main()
