"""One bounded continuation of the exact44 GAE actor; fixed development looks, no promotion.

The original44 Adam moments/step counters cross only the explicit initial checkpoint seam.
Completed batches form a strict actor/checkpoint chain. Partial runs are evidence, never an
automatic resume: the existing generic supervisor owns one attempt and its original six hours.
"""
import argparse
import json
import math
import os
from pathlib import Path
import platform

import automata_campaign as controller
import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as data
import purchase_gae_pilot as pilot
import purchase_gae_qualification as qualified
import purchase_storage as storage

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
KIND='purchase-gae-learning-curve-1'
BATCHES=64
CHECKPOINTS=(16,32,64)
INITIAL_FILES={
    'policy.json':qualified.CANDIDATE,
    'checkpoint.json':'7d0957f2670b51ea0d0b187860522c6c063c483a04095280dde733782bb58b2c',
    'identity.json':'b950ad7aab76fe4215c46006382c4b08131fe7e9b57ffba1a59e3353e4d0757d',
    'finished.json':'abf517350afa4597186d04440f0872df8e2a76cee6e6b64b9c631282ee1b7674'}
FUTILITY=dict(batch=32,metric='winShareDifference',confidence=.95,side='upper',
              criticalValue=1.6448536269514722,minimumGain=.03,comparison='strictly-less')


def batch_source(protocol,index,model):
    require(1<=index<=BATCHES,'Only the64 prospectively frozen training batches are permitted.')
    return matched.collection_source(dict(protocol,banks=dict(protocol['banks'],
        training=protocol['banks'][f'training-{index}'])),model)


def arena_source(protocol,index,model):
    require(index in CHECKPOINTS,'Only development checkpoints16,32,64 may open.')
    source=exp.arena_source(protocol['baseline'],protocol['opponents'],
        protocol['banks'][f'checkpoint-{index}']['nativeOffset'],512,protocol['baseline'],'SCREENING')
    return dict(source,**legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'],arena=True,
                        rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS),
        frozenPurchase=dict(baseline=protocol['prior'],policy=protocol['purchasePolicy'],
            candidatePolicy=str(model),incumbentPolicy=protocol['initialActor']))


def validate_initial(protocol):
    """Bind unchanged44 bytes and the actual actor-only optimizer, not reconstructed moments."""
    initial=Path(protocol['initialOptimizer']).parent
    require(Path(protocol['initialActor'])==initial/'policy.json'
        and Path(protocol['initialOptimizer'])==initial/'checkpoint.json'
        and all(sha(initial/name)==digest for name,digest in INITIAL_FILES.items()),
        'Continuation requires all four exact completed44 final GAE checkpoint files.')
    payload=ecology.checkpoint(initial/'checkpoint.json',initial/'policy.json')
    identity,finished=read(initial/'identity.json'),read(initial/'finished.json')
    objective=identity['actorOnlyObjective']
    require(payload['identitySha256']==finished['identitySha256']==legacy.digest(identity)
        and finished['outputModelSha256']==qualified.CANDIDATE
        and finished['acceptedEpochs']==payload['epoch']==4
        and objective['version']=='purchase-precomputed-advantage-1'
        and objective['estimator']==pilot.ESTIMATOR,
        'Initial actor must retain the completed44 GAE estimator and audited optimizer history.')
    predictor=objective['frozenStatePredictions']['predictor']
    owners={'sourceSha256':'purchase_actor_baseline.py','criticCodeSha256':'purchase_value_benchmark.py',
            'encoderCodeSha256':'purchase_value_data.py'}
    require(all(predictor[key]==sha(Path(__file__).with_name(name)) for key,name in owners.items())
        and predictor['torchVersion']==protocol['numerical']['torchVersion']
        and predictor['checkpointSha256']==sha(protocol['critic'])
        and predictor['stateDictionarySha256']==sha(protocol['stateDictionary'])
        and predictor['inferenceContractSha256']==sha(protocol['inferenceContract'])
        and predictor['device']=='cpu' and predictor['dtype']=='float32' and predictor['threads']==1,
        'Exact44 optimizer continuation cannot change its frozen predictor or numerical contract.')
    require({k:v for k,v in identity['config'].items() if k!='seed'}==
        {k:v for k,v in pilot.update_config(protocol,1).items() if k!='seed'},
        'Continuation must preserve the original PPO recipe, including all optimizer settings.')
    return payload,identity


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['workers'] in (6,10)
        and protocol['maximumSeconds']==21600 and protocol['maximumAttempts']==1
        and protocol['batches']==BATCHES and protocol['checkpointBatches']==list(CHECKPOINTS)
        and protocol['advantageEstimator']==pilot.ESTIMATOR and protocol['futility']==FUTILITY
        and protocol['maximumConsecutiveZeroAcceptedBatches']==4
        and protocol['maximumScientificTerminalGames']==19456
        and protocol['maximumHistoricalTerminalReproductions']==280
        and protocol['maximumTerminalExecutions']==19736
        and protocol['finalCheckpointOnly'] is True
        and all(protocol[name] is False for name in ('qualificationPassed','productionActivation',
            'automaticNextCampaign','checkpointSelection','automaticResume')),
        'Use one fixed64-batch learning curve, three development looks and the declared finite stops.')
    require(protocol['configuration']==exp.CONFIGURATION
        and protocol['experimentalRulesTransfer']==legacy.RULES_TRANSFER
        and sha(protocol['baseline'])==matched.ORDINARY and sha(protocol['expansionModel'])==matched.EXPANSION
        and sha(protocol['purchasePolicy'])==data.C and sha(protocol['prior'])==data.F0
        and protocol['opponents']==[protocol['baseline']]*4 and sha(protocol['critic'])==matched.CRITIC
        and sha(protocol['pilotAudit'])==qualified.PILOT_AUDIT,
        'Keep exact ETM8/C64/F0, the frozen fresh64 critic and independently completed44 provenance.')
    audit=read(protocol['seedAudit']);banks=protocol['banks']
    require(audit['allIntersectionsZero'] and banks==audit['seedBanks']
        and set(banks)=={*[f'training-{i}' for i in range(1,65)],
                          *[f'checkpoint-{i}' for i in CHECKPOINTS],'updates'},
        'Every training, development and shuffle bank must match the complete fresh ancestry audit.')
    updates=banks['updates']['seeds']
    require(len(updates)==256 and updates==list(range(updates[0],updates[0]+256)),
        'Preserve four fresh original epoch-shuffle streams for each of64 batches.')
    for name,bank in banks.items():
        if name=='updates':continue
        count=512 if name.startswith('checkpoint-') else 256
        require(bank['count']==count and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],count),
                'A fresh learning-curve bank differs from its exact native world formula.')
    for index in range(1,65):
        require(pilot.update_config(protocol,index)==dict(protocol['numerical']['configDefaults'],
            **dict(legacy.HYPERPARAMETERS,valueCoefficient=0),seed=updates[4*(index-1)]),
            'Only epoch seeds may change from the exact44 actor-only PPO recipe.')
    exp.validate_banks({name:bank['seeds'] for name,bank in banks.items()})
    validate_initial(protocol)


def numerical_preflight(protocol):
    """Validate the real continuation owner without predictions, fitting or game execution."""
    import purchase_ecology_environment as environment
    import purchase_policy as learner
    environment.verify(protocol['numerical'])
    payload,identity=validate_initial(protocol)
    receipt=learner.ActorAdamContinuation().validate(protocol['initialOptimizer'],
        identity['actorOnlyObjective'],read(protocol['initialActor']),
        learner.UpdateConfig(**pilot.update_config(protocol,1)))
    return dict(passed=True,continuation=receipt,actorAdamSlots=len(payload['optimizer']['state']),
        actorStepCounters={key:value['step'] for key,value in payload['optimizer']['state'].items()},
        newGames=0,actualFits=0,predictions=0)


def futility(metrics):
    estimate=metrics['winShareDifference']
    mean,lower=estimate['mean'],estimate['lower95']
    require(estimate['count']==512 and all(type(v) in (int,float) and math.isfinite(v)
        for v in (mean,lower)) and lower<=mean,'Futility requires all512 finite paired win differences.')
    # The existing native arena owner reports mean ±1.96*SE; use the same paired SE,
    # with the prospectively declared one-sided95 critical value (not a two-sided95 bound).
    standard_error=(mean-lower)/1.96
    upper=mean+FUTILITY['criticalValue']*standard_error
    return dict(**FUTILITY,mean=mean,standardError=standard_error,upperConfidence=upper,
        stop=upper<FUTILITY['minimumGain'],
        interpretation='Compute allocation rule, not evidence that later learning cannot improve.')


def zero_streak(previous,accepted):
    require(type(previous) is int and 0<=previous<4 and type(accepted) is int and 0<=accepted<=4,
            'Accepted epochs and consecutive zero-update batches must be bounded integers.')
    return previous+1 if accepted==0 else 0


def checkpoint(protocol,path,output,index,model):
    source_path=output/f'checkpoint-{index}-source.json';source=arena_source(protocol,index,model)
    directory=output/'screens'/f'checkpoint-{index}'
    require(not source_path.exists() and not directory.exists(),'Each development look permits exactly one attempt.')
    document(source_path,source);exp.native(protocol,'arena',source_path,directory)
    metrics=legacy.verify_arena(protocol,source_path,directory)
    data.verify_overlays(source,directory,[read(directory/'run.json'),read(directory/'evaluation.json')],
        arena=True,expected_expansion=matched.EXPANSION,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS)
    require({p.name for p in directory.glob('pair-*.json')}=={f'pair-{i}.json' for i in range(512)},
            'Each development estimate retains all512 wins, losses and ties.')
    pairs=[read(directory/f'pair-{i}.json') for i in range(512)]
    replays=ecology.arena_replays(protocol,source_path,pairs,output/'replays'/f'checkpoint-{index}',
        expected_expansion=matched.EXPANSION,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS)
    report=dict(batch=index,protocolSha256=sha(path),candidateSha256=sha(model),
        incumbentSha256=qualified.CANDIDATE,rivalPurchaseSha256=data.C,metrics=metrics,replays=replays,
        futility=futility(metrics) if index==32 else None,qualificationPassed=False,
        selectionPerformed=False,developmentOnly=True)
    document(output/f'checkpoint-{index}-report.json',report)
    return report


def progress(path,output,batches,checks,reason,streak):
    """Only the final64 checkpoint is eligible for later review; earlier actors never substitute."""
    completed=len(batches);final=completed==64 and any(row['batch']==64 for row in checks)
    value=dict(protocolSha256=sha(path),completedBatches=completed,completedCheckpoints=[r['batch'] for r in checks],
        stopReason=reason,consecutiveZeroAcceptedBatches=streak,
        scientificTerminalGames=completed*256+len(checks)*1024,
        historicalReproductions=completed*4+len(checks)*8,
        finalCheckpointEligibleForReview=final,eligibleActorSha256=batches[-1]['modelSha256'] if final else None,
        qualificationPassed=False,productionActivation=False,automaticNextCampaign=False,
        checkpointSelection=False)
    document(output/'progress.json',value,immutable=False)
    return value


def train_batch(protocol,path,output,index,model):
    directory=output/'batches'/f'batch-{index}'/'gae'
    require(not directory.exists(),'Each fresh on-policy batch permits one attempt.')
    source=directory/'collection-source.json';document(source,batch_source(protocol,index,model))
    exp.native(protocol,'purchase-collect',source,directory/'collection',
               extra=('--workers',str(protocol['workers'])))
    provenance,decisions=data.verify_collection(directory/'collection',expected_ordinary=matched.ORDINARY,
                                               expected_expansion=matched.EXPANSION)
    provenance['readerSha256']=sha(Path(__file__).with_name('purchase_actor_baseline.py'))
    ecology.reproduction(protocol,directory)
    command=[protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
             'numeric','--protocol',str(path),'--output',str(directory),'--index',str(index)]
    ecology.run_child(protocol,command,directory/'numeric.log')
    update=pilot.verify_update(protocol,directory,index,'gae',model,provenance,
                               initial_optimizer=Path(protocol['initialOptimizer']))
    previous=pilot.previous_optimizer(directory,index,'gae',Path(protocol['initialOptimizer']))
    model=directory/'update/policy.json';archive=storage.archive(directory)
    receipt=dict(batch=index,arm='gae',modelSha256=sha(model),previousCheckpointSha256=sha(previous),
        update=update,decisions=decisions,provenance=provenance,storage=archive,
        criticMetrics=read(directory/'critic-metrics.json'),
        reproductionSha256=sha(directory/'reproduction/reproduction.json'))
    document(directory/'receipt.json',receipt)
    return model,receipt


def train(protocol,path,output):
    require(not (output/'progress.json').exists() and not (output/'batches').exists(),
            'A partial or completed learning curve cannot be resumed, retried or given a renewed deadline.')
    model=Path(protocol['initialActor']);batches=[];checks=[];streak=0;reason='all64-batches-complete'
    for index in range(1,65):
        exp.remaining_seconds()
        model,receipt=train_batch(protocol,path,output,index,model)
        batches.append(receipt)
        streak=zero_streak(streak,receipt['update']['acceptedEpochs'])
        if streak==4:
            reason='four-consecutive-zero-accepted-batches'
            progress(path,output,batches,checks,reason,streak)
            break
        progress(path,output,batches,checks,'running',streak)
        if index in CHECKPOINTS:
            checks.append(checkpoint(protocol,path,output,index,model))
            if index==32 and checks[-1]['futility']['stop']:
                reason='prespecified32-batch-futility'
                progress(path,output,batches,checks,reason,streak)
                break
        progress(path,output,batches,checks,'running',streak)
        print(f'Completed learning-curve batch{index}/64; accepted epochs={receipt["update"]["acceptedEpochs"]}.',flush=True)
    report=dict(progress(path,output,batches,checks,reason,streak),batches=batches,checkpoints=checks,
        inference='Fixed exploratory learning curve versus44 GAE; no qualification or GAE-versus-MC claim.')
    document(output/'learning-curve-report.json',report)
    artifacts=[output/'progress.json',output/'learning-curve-report.json',
               *output.glob('checkpoint-*-source.json'),*output.glob('checkpoint-*-report.json')]
    for root in (output/'batches',output/'screens',output/'replays'):
        artifacts.extend(p for p in root.rglob('*') if p.is_file())
    return exp.stage_result(output,'learn',True,'Bounded learning curve finished; no qualification.',report,artifacts)


def campaign_spec(root,protocol,files):
    """Reuse the existing external process-group/resource supervisor; one stage/attempt only."""
    return dict(schemaVersion=1,kind='automata-campaign',inputs=files,
        limits=dict(maximumSeconds=21600,maximumDataBytes=8*exp.GIB,
                    maximumRssBytes=6*exp.GIB,minimumFreeBytes=8*exp.GIB),
        stages=[dict(id='learn',dependsOn=[],maximumSeconds=21600,maximumAttempts=1,
            result='results/learn.json',command=[protocol['python']['executable'],'-E','-s','-S','-B',
                str(root/'scripts/purchase_gae_learning_curve.py'),'stage','--protocol',str(root/'protocol.json'),
                '--output','{output}'])])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','numerical-preflight','stage','numeric'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--index',type=int)
    args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);matched.verify_execution(protocol)
    expected=protocol['environment']
    require(platform.platform()==expected['platform'] and platform.machine()==expected['machine']
        and os.cpu_count()==expected['logicalCpus'],'The one prospectively frozen execution host changed.')
    if args.command=='preflight':
        require(not args.output.exists(),'Preflight must precede any game or fit attempt.')
        own=[p for p in controller.processes.sample_group(os.getpgrp()) if p.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'Actual child process sampling must work.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    elif args.command=='numerical-preflight':print(json.dumps(numerical_preflight(protocol)))
    elif args.command=='numeric':
        require(1<=args.index<=64,'Numerical update requires a declared batch index.')
        pilot.numeric(protocol,args.output,args.index,'gae',initial_optimizer=Path(protocol['initialOptimizer']))
    else:train(protocol,args.protocol,args.output)


if __name__=='__main__':main()
