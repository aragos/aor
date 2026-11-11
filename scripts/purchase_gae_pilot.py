"""Eight matched on-policy batches per MC/GAE arm; frozen critic, no candidate selection."""
import argparse
from pathlib import Path
import os
import sys

import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as data
import purchase_storage as storage

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
KIND='purchase-matched-gae-pilot-1'
ARMS=('mc','gae')
BATCHES=8
ESTIMATOR=dict(version='purchase-gae-1',gamma=1.,**{'lambda':.95},
    clock='recorded-controlled-purchase-decision-1',forcedRowsIncluded=True,
    intermediateReward=0.,terminalValue=0.)


def batch_source(protocol, index, model):
    require(1<=index<=BATCHES,'Only the eight frozen on-policy batches are permitted.')
    return matched.collection_source(dict(protocol,banks=dict(protocol['banks'],
        training=protocol['banks'][f'training-{index}'])),model)


def update_config(protocol,index):
    return read(Path(protocol['configDirectory'])/f'update-{index}.json')


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['workers']==6 and protocol['maximumSeconds']==7200
        and protocol['maximumAttempts']==1 and protocol['maximumScientificTerminalGames']==6144
        and protocol['maximumHistoricalTerminalReproductions']==80
        and protocol['maximumTerminalExecutions']==6224 and protocol['batchesPerArm']==8
        and protocol['arms']==list(ARMS) and protocol['advantageEstimator']==ESTIMATOR
        and protocol['qualificationPassed'] is False and protocol['productionActivation'] is False
        and protocol['automaticNextCampaign'] is False,
        'Use the declared eight-batch-per-arm, six-worker, two-hour diagnostic only.')
    require(protocol['configuration']==exp.CONFIGURATION
        and protocol['experimentalRulesTransfer']==legacy.RULES_TRANSFER
        and sha(protocol['baseline'])==matched.ORDINARY and sha(protocol['expansionModel'])==matched.EXPANSION
        and sha(protocol['purchasePolicy'])==data.C and sha(protocol['prior'])==data.F0
        and protocol['opponents']==[protocol['baseline']]*4 and sha(protocol['critic'])==matched.CRITIC
        and sha(protocol['inferenceContract'])=='120748a9cfe6e018a6885108c0b652990d034a41cfc870600fb6a04bbe22ef45'
        and sha(protocol['stateDictionary'])=='8261e8bfa69892f6971fb6fca340860610d59afb93c26bcf264039d460bb6864',
        'Preserve exact qualified ETM8/C64/F0, mixed rivals and frozen fresh64 inference.')
    audit=read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and protocol['banks']==audit['seedBanks']
        and set(protocol['banks'])=={*[f'training-{i}' for i in range(1,9)],'screen','updates'},
        'Use only the complete audited shared-arm banks and epoch streams.')
    updates=protocol['banks']['updates']['seeds']
    require(len(updates)==32 and updates==list(range(updates[0],updates[0]+32)),
        'Both arms must share the same four original epoch shuffles per batch.')
    for name,bank in protocol['banks'].items():
        if name=='updates':continue
        count=512 if name=='screen' else 256
        require(bank['count']==count and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],count),
            'A frozen game bank differs from its native world formula.')
    for index in range(1,9):
        require(update_config(protocol,index)==dict(protocol['numerical']['configDefaults'],
            **dict(legacy.HYPERPARAMETERS,valueCoefficient=0),seed=updates[4*(index-1)]),
            'MC and GAE must retain the identical original actor-only PPO recipe.')
    exp.validate_banks({name:bank['seeds'] for name,bank in protocol['banks'].items()})


def numeric(protocol,directory,index,arm):
    import purchase_actor_baseline as baseline
    import purchase_gae as gae
    import purchase_policy as learner
    import purchase_ecology_environment as environment
    environment.verify(protocol['numerical'])
    model=Path(read(directory/'collection-source.json')['policy'])
    previous=directory.parent.parent/f'batch-{index-1}'/arm/'update/checkpoint.json' if index>1 else None
    prediction=directory/'external-baselines.json'
    baseline.predict(directory/'collection',model,protocol['critic'],protocol['inferenceContract'],
                     protocol['stateDictionary'],prediction,expected_model_sha=sha(model))
    rows,provenance=baseline.read_collection(directory/'collection',model,read(model),
                                           contract=learner.PURCHASE_CONTRACT)
    targets=directory/'advantages.json'
    if arm=='gae':gae.targets(rows,provenance,sha(model),prediction,targets)
    document(directory/'baseline-freeze.json',dict(predictionsSha256=sha(prediction),
        behaviorModelSha256=sha(model),criticSha256=sha(protocol['critic']),updatesStarted=False,
        targetsSha256=sha(targets) if arm=='gae' else None,newCriticFits=0))
    document(directory/'critic-metrics.json',gae.critic_metrics(rows,provenance,prediction))
    gae.update(directory/'collection',model,learner.UpdateConfig(**update_config(protocol,index)),
               prediction,targets,directory/'update',arm,optimizer_from=previous)
    freeze=read(directory/'baseline-freeze.json')
    require(sha(prediction)==freeze['predictionsSha256']
        and (arm!='gae' or sha(targets)==freeze['targetsSha256']),
        'Frozen state predictions or explicit advantage targets changed during the update.')


def verify_update(protocol,directory,index,arm,model,provenance):
    update=directory/'update';initial=read(model);candidate=read(update/'policy.json')
    payload=ecology.checkpoint(update/'checkpoint.json',update/'policy.json')
    identity,report=read(update/'identity.json'),read(update/'finished.json')
    previous=directory.parent.parent/f'batch-{index-1}'/arm/'update/checkpoint.json' if index>1 else None
    objective=identity.get('actorOnlyObjective',{})
    require(identity['data']==provenance and identity['modelSha256']==sha(model)
        and identity['config']==update_config(protocol,index)
        and identity['optimizerFromSha256']==(sha(previous) if previous else None)
        and identity['learnerSha256']==sha(Path(__file__).with_name('purchase_policy.py'))
        and identity['entrypointSha256']==sha(Path(__file__).with_name('purchase_gae.py'))
        and identity['torchVersion']==protocol['numerical']['torchVersion']
        and identity['pythonVersion']==protocol['numerical']['pythonVersion']
        and payload['identitySha256']==legacy.digest(identity),
        'The exact arm, current behavior actor, collection or optimizer lineage changed.')
    require(objective['frozenParameters']==['valueWeights','valueBias']
        and objective['optimizerInitialization']==('fresh-adam' if index==1 else 'matching-actor-adam'),
        'Both arms must freeze the original value head and carry only their own actor Adam.')
    if arm=='mc':
        require(objective['version']=='purchase-actor-only-baseline-1' and objective['baseline']=='external',
            'The control must remain original terminal-return-minus-frozen-value PPO.')
        prediction=objective
    else:
        require(objective['version']=='purchase-precomputed-advantage-1'
            and objective['estimator']==ESTIMATOR and objective['targetsFile']==str(directory/'advantages.json')
            and objective['targetsSha256']==sha(directory/'advantages.json'),
            'The GAE arm requires its explicit frozen sampled-continuation targets.')
        prediction=objective['frozenStatePredictions']
    require(prediction['externalFile']==str(directory/'external-baselines.json')
        and prediction['externalSha256']==sha(directory/'external-baselines.json')
        and prediction['predictor']==read(directory/'external-baselines.json')['predictor'],
        'Both arms must use their separately frozen prebatch state predictions.')
    if previous:
        carry=identity['actorOptimizerContinuation']
        previous_identity=read(previous.parent/'identity.json')
        require(carry==dict(version='purchase-actor-adam-continuation-1',checkpointSha256=sha(previous),
            previousIdentitySha256=legacy.digest(previous_identity))
            and read(previous.parent/'policy.json')==initial,
            'Actor Adam must come from this same arm and immediately preceding batch.')
    else:require('actorOptimizerContinuation' not in identity,'The first batch must initialize empty Adam.')
    require(report['identitySha256']==payload['identitySha256'] and report['actorOnlyObjective']==objective
        and report['inputModelSha256']==sha(model) and report['outputModelSha256']==sha(update/'policy.json')
        and report['history']==payload['history'] and report['acceptedEpochs']==payload['epoch']
        ==sum(row['accepted'] for row in payload['history']) and 0<=payload['epoch']<=4
        and all(row['accepted']==(row['behaviorKl']<=.01) for row in payload['history']),
        'Original whole-collection KL rollback and completed update receipts must agree.')
    anchor=read(protocol['purchasePolicy'])
    require(all(candidate[name]==initial[name]==anchor[name] for name in ('valueWeights','valueBias'))
        and len(payload['optimizer']['param_groups'])==1
        and len(payload['optimizer']['param_groups'][0]['params'])==5 and len(payload['optimizer']['state'])<=5,
        'Value-head parameters and momentum must remain excluded throughout both arms.')
    return report


def train(protocol,path,output):
    models={arm:Path(protocol['purchasePolicy']) for arm in ARMS};receipts=[]
    for index in range(1,9):
        for arm in ARMS:
            directory=output/'batches'/f'batch-{index}'/arm
            require(not directory.exists(),'Each on-policy arm/batch permits exactly one attempt.')
            source=directory/'collection-source.json';document(source,batch_source(protocol,index,models[arm]))
            exp.native(protocol,'purchase-collect',source,directory/'collection',extra=('--workers','6'))
            provenance,decisions=data.verify_collection(directory/'collection',expected_ordinary=matched.ORDINARY,
                                                       expected_expansion=matched.EXPANSION)
            provenance['readerSha256']=sha(Path(__file__).with_name('purchase_actor_baseline.py'))
            ecology.reproduction(protocol,directory)
            command=[protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
                'numeric','--protocol',str(path),'--output',str(directory),'--index',str(index),'--arm',arm]
            ecology.run_child(protocol,command,directory/'numeric.log')
            update=verify_update(protocol,directory,index,arm,models[arm],provenance)
            models[arm]=directory/'update/policy.json'
            archive=storage.archive(directory)
            receipt=dict(batch=index,arm=arm,modelSha256=sha(models[arm]),update=update,decisions=decisions,
                provenance=provenance,storage=archive,criticMetrics=read(directory/'critic-metrics.json'),
                reproductionSha256=sha(directory/'reproduction/reproduction.json'))
            document(directory/'receipt.json',receipt);receipts.append(receipt)
            print(f'Completed fixed batch{index}/8 arm={arm}; no candidate selection.',flush=True)
    return matched.stage_result(path,output,'train',dict(batches=receipts,scientificTerminalGames=4096,
        historicalReproductions=64,finalActors={arm:dict(path=str(model),sha256=sha(model))
                                             for arm,model in models.items()}),[output/'batches'])


def screen(protocol,path,output):
    import automata_campaign as controller
    prior=controller.validate_result(output/'results/train.json',output)
    require(prior['status']=='passed','Both complete eight-batch arms are required before screening.')
    pairs_by_arm={};cells=[]
    for arm in ARMS:
        model=output/'batches/batch-8'/arm/'update/policy.json'
        source_path=output/f'screen-{arm}-source.json';source=matched.arena_source(protocol,model)
        document(source_path,source);directory=output/'screens'/f'screen-{arm}'
        require(not directory.exists(),'Only the final batch8 actors receive one common-bank screen.')
        exp.native(protocol,'arena',source_path,directory)
        metrics=legacy.verify_arena(protocol,source_path,directory)
        data.verify_overlays(source,directory,[read(directory/'run.json'),read(directory/'evaluation.json')],
            arena=True,expected_expansion=matched.EXPANSION)
        require({p.name for p in directory.glob('pair-*.json')}=={f'pair-{i}.json' for i in range(512)},
            'Each final screen must cover its complete512 paired worlds.')
        pairs=[read(directory/f'pair-{i}.json') for i in range(512)];pairs_by_arm[arm]=pairs
        replays=ecology.arena_replays(protocol,source_path,pairs,output/'replays'/arm,
                                     expected_expansion=matched.EXPANSION)
        cells.append(dict(arm=arm,modelSha256=sha(model),versusAnchor=metrics,replays=replays))
    return matched.stage_result(path,output,'screen',dict(
        primaryGaeMinusMc=matched.compare(pairs_by_arm['mc'],pairs_by_arm['gae']),cells=cells,
        sharedIncumbentParityWorlds=512,scientificTerminalGames=6144,historicalReproductions=80,
        totalTerminalExecutions=6224,selectionPerformed=False,
        inference='Approximate paired95% intervals across512 independent final worlds; no qualification.'),
        [output/'screens',output/'replays'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage','numeric'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=matched.STAGES);parser.add_argument('--index',type=int)
    parser.add_argument('--arm',choices=ARMS)
    args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);matched.verify_execution(protocol)
    if args.command=='preflight':
        import automata_campaign as controller
        import json
        require(not args.output.exists(),'Preflight must precede any game or fit.')
        own=[p for p in controller.processes.sample_group(os.getpgrp()) if p.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'Actual child process sampling must work.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    elif args.command=='numeric':numeric(protocol,args.output,args.index,args.arm)
    elif args.stage=='train':train(protocol,args.protocol,args.output)
    else:screen(protocol,args.protocol,args.output)


if __name__=='__main__':main()
