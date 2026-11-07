"""Finite equal-origin advantage fitting, frozen controls, and completed local diagnostics."""
import argparse
import copy
import math
import json
from pathlib import Path
import random
import time

import torch
import card_advantage as a
import card_advantage_data as data
import purchase_policy as common


def immutable(path, value):
    if path.exists(): a.require(common.read_json(path)==value, 'Existing immutable fitting evidence differs: '+str(path))
    else: common.atomic_json(path,value)

def prediction_metrics(network, origins):
    errors=[];zero=[]
    with torch.no_grad():
        for origin in origins:
            groups=[g for g in origin if g.nontrivial()]
            if not groups: continue
            origin_error=[];origin_zero=[]
            for g,q in zip(groups,network(groups)):
                mask=[i for i in range(len(q)) if i!=g.baseline];d=q-q[g.baseline]
                origin_error.append(float(((d[mask]-g.targets[mask])**2).mean()))
                origin_zero.append(float((g.targets[mask]**2).mean()))
            errors.append(sum(origin_error)/len(origin_error));zero.append(sum(origin_zero)/len(origin_zero))
    a.require(errors,'Prediction diagnostics require nontrivial origins.')
    return {'origins':len(errors),'mse':sum(errors)/len(errors),'zeroMse':sum(zero)/len(zero)}

def epoch(network, optimizer, origins, seed, iteration, config, deadline):
    indices=list(range(len(origins)));random.Random(seed*100003+iteration).shuffle(indices)
    total=0.
    for start in range(0,len(indices),config.originsPerBatch):
        a.require(time.time()<deadline,'The original inclusive fit deadline elapsed.')
        batch=[origins[i] for i in indices[start:start+config.originsPerBatch]]
        optimizer.zero_grad(set_to_none=True);value=a.loss(network,batch)
        a.require(bool(torch.isfinite(value)), 'Paired loss became nonfinite.')
        value.backward();gradient=torch.nn.utils.clip_grad_norm_(network.parameters(),config.maxGradientNorm)
        a.require(bool(torch.isfinite(gradient)), 'Paired gradient became nonfinite.')
        optimizer.step();total+=float(value.detach())*len(batch)
    return total/len(origins)

def fit_member(schema, training, development, seed, config, output, identity, deadline):
    output=Path(output);output.mkdir(exist_ok=True)
    immutable(output/'identity.json',identity)
    path=output/'checkpoint.json'
    if path.exists():
        checkpoint=common.read_checkpoint(path)
        a.require(checkpoint['identitySha256']==common.digest(identity),'Member checkpoint identity differs.')
    else:
        a.require(not (output/'epoch-started.json').exists(),'A started member lost its checkpoint; preserve evidence.')
        checkpoint={'identitySha256':common.digest(identity),'epoch':0,'network':a.initialize(schema,seed),
                    'optimizer':None,'history':[],'bestEpoch':None,'bestLoss':None}
        common.save_checkpoint(path,checkpoint)
    network=a.Network(schema,checkpoint['network'])
    optimizer=torch.optim.Adam(network.parameters(),lr=config.learningRate,weight_decay=config.weightDecay,foreach=False)
    if checkpoint['optimizer'] is not None: common.restore_optimizer(optimizer,checkpoint['optimizer'])
    immutable(output/'epoch-started.json',{'identitySha256':common.digest(identity)})
    while checkpoint['epoch']<config.epochs:
        iteration=checkpoint['epoch']+1
        train_loss=epoch(network,optimizer,training,seed,iteration,config,deadline)
        record={'epoch':iteration,'onlineTrainingLoss':train_loss}
        if iteration in config.checkpoints:
            metrics=prediction_metrics(network,development);record['development']=metrics
            immutable(output/f'epoch-{iteration}.json',network.artifact())
            if checkpoint['bestLoss'] is None or metrics['mse']<checkpoint['bestLoss']:
                checkpoint['bestLoss']=metrics['mse'];checkpoint['bestEpoch']=iteration
        checkpoint.update(epoch=iteration,network=network.artifact(),optimizer=common.encode_optimizer(optimizer),
                          history=checkpoint['history']+[record])
        common.save_checkpoint(path,checkpoint)
    best=common.read_json(output/f"epoch-{checkpoint['bestEpoch']}.json")
    immutable(output/'finished.json',{'identitySha256':common.digest(identity),'seed':seed,
        'selectedEpoch':checkpoint['bestEpoch'],'developmentMse':checkpoint['bestLoss'],
        'selectedNetworkSha256':common.file_digest(output/f"epoch-{checkpoint['bestEpoch']}.json")})
    return best,{'seed':seed,'selectedEpoch':checkpoint['bestEpoch'],'developmentMse':checkpoint['bestLoss']}

def ablated(origins,schema):
    return [[a.prepare(g.observation,g.seed,schema,g.targets.tolist(),g.identifier,True) for g in origin] for origin in origins]

def fit_ensemble(schema,training,development,output,identity,deadline,ablation=False,config=a.CONFIG):
    output=Path(output);output.mkdir(exist_ok=True)
    if ablation: training=ablated(training,schema);development=ablated(development,schema)
    members=[];reports=[]
    for seed in config.seeds:
        member,report=fit_member(schema,training,development,seed,config,output/f'seed-{seed}',
                                {**identity,'seed':seed,'ablation':ablation},deadline)
        members.append(a.materialize_ablation(member,schema) if ablation else member);reports.append(report)
    return a.bundle(schema,members),reports

def local_metrics(ensemble,origins,control=None):
    per_origin=[];prediction=[];zero=[]
    with torch.no_grad():
        for origin in origins:
            if not origin: continue
            selected=[];nontrivial_error=[];nontrivial_zero=[];overrides=0;passes=0
            values=[member(origin) for member in ensemble.members]
            for j,g in enumerate(origin):
                choice=a.choose_values(g,[v[j] for v in values]);i=choice['proposalPosition']
                if control is not None:
                    i=g.indices.index(a.frequency_choice(control,g.observation,g.seed))
                selected.append(float(g.targets[i]));overrides+=int(i!=g.baseline)
                passes+=int(i!=g.baseline and g.commands[i]=='pass')
                mask=[k for k in range(len(g.indices)) if k!=g.baseline]
                if mask:
                    mean=torch.stack([v[j]-v[j][g.baseline] for v in values]).mean(0)
                    nontrivial_error.append(float(((mean[mask]-g.targets[mask])**2).mean()))
                    nontrivial_zero.append(float((g.targets[mask]**2).mean()))
            per_origin.append({'origin':origin[0].identifier.split(':')[0], 'decisions':len(origin),
                'overrides':overrides,'passOverrides':passes,'meanSelectedAdvantage':sum(selected)/len(selected)})
            if nontrivial_error:
                prediction.append(sum(nontrivial_error)/len(nontrivial_error));zero.append(sum(nontrivial_zero)/len(nontrivial_zero))
    a.require(per_origin,'Local diagnostics require completed origins.')
    advantages=[r['meanSelectedAdvantage'] for r in per_origin];mean=sum(advantages)/len(advantages)
    se=math.sqrt(sum((x-mean)**2 for x in advantages)/(len(advantages)-1)/len(advantages)) if len(advantages)>1 else None
    positives=[x for x in advantages if x>1e-12];fraction=max(positives)/sum(positives) if positives else None
    overridden=sum(r['overrides']>0 for r in per_origin)
    return {'origins':len(advantages),'meanSelectedAdvantage':mean,'lower95':mean-1.96*se if se is not None else None,
        'upper95':mean+1.96*se if se is not None else None,'overriddenOrigins':overridden,
        'positiveOrigins':len(positives),'largestPositiveOriginFraction':fraction,
        'predictionMse':sum(prediction)/len(prediction) if prediction and control is None else None,
        'zeroPredictionMse':sum(zero)/len(zero) if zero else None,'perOrigin':per_origin,
        'localGatePassed':mean>1e-12 and overridden>=16 and fraction is not None and fraction<=.25,
        'strengthQualified':False}

def train(source,output,resume=False):
    output=Path(output);torch.set_num_threads(a.CONFIG.threads);torch.use_deterministic_algorithms(True)
    dataset,provenance=data.read_collection(source,splits=('train','development'))
    a.require(dataset['train'] and dataset['development'],'Fitting requires training and development origins.')
    files=[Path(__file__),Path(a.__file__),Path(data.__file__),Path(common.__file__)]
    identity={'kind':'card-advantage-fit','schemaVersion':1,'config':json.loads(json.dumps(a.asdict(a.CONFIG))),
              'collection':provenance,'sourceHashes':{str(p.resolve()):common.file_digest(p) for p in files},
              'torchVersion':torch.__version__}
    identity_path=output/'identity.json'
    if resume:
        a.require(identity_path.exists(),'Resume requires the original fitting identity.')
        old=common.read_json(identity_path);deadline=old['deadlineEpochSeconds']
        a.require({k:v for k,v in old.items() if k!='deadlineEpochSeconds'}==identity,'Resume inputs/config/runtime differ.')
        identity=old
    else:
        a.require(not output.exists(),'Fresh fitting requires an unused directory.')
        output.mkdir(parents=True);deadline=time.time()+a.CONFIG.maxSeconds
        identity['deadlineEpochSeconds']=deadline;common.atomic_json(identity_path,identity)
    schema=provenance['schema'];models={};member_reports={}
    for mode,mask in [('full',False),('action-priority',True)]:
        artifact,reports=fit_ensemble(schema,dataset['train'],dataset['development'],output/mode,identity,deadline,mask)
        immutable(output/f'{mode}.json',artifact);models[mode]=a.Ensemble(artifact);member_reports[mode]=reports
    control,calibration=a.calibrate_frequency(models['full'],dataset['development'])
    immutable(output/'frequency-control.json',control);immutable(output/'frequency-calibration.json',calibration)
    # Validation outcomes become accessible only after models AND controls are immutable.
    validation,checked=data.read_collection(source,splits=('validation',))
    a.require(checked['fileHashes']==provenance['fileHashes'],'Collection changed after fitting.')
    report={'kind':'completed-card-advantage-fit','identitySha256':common.digest(identity),
        'models':{name:{'file':name+'.json','sha256':common.file_digest(output/(name+'.json')),'members':member_reports[name]} for name in models},
        'frequencyControlSha256':common.file_digest(output/'frequency-control.json'),
        'validation':{name:local_metrics(model,validation['validation']) for name,model in models.items()},
        'frequencyValidation':local_metrics(models['full'],validation['validation'],control),
        'counts':{**provenance['counts'],'validation':checked['counts']['validation']},'automaticPromotion':False}
    immutable(output/'finished.json',report)
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--resume',action='store_true')
    args=p.parse_args();report=train(args.source,args.output,args.resume)
    print(json.dumps({'finished':str(args.output/'finished.json'),'validation':{k:{n:v[n] for n in ('origins','meanSelectedAdvantage','overriddenOrigins','localGatePassed')} for k,v in report['validation'].items()}},indent=2))
if __name__=='__main__':main()
