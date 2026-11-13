"""Fixed independent child fits with one atomic complete C/P/E bundle publication."""
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import time

import card_policy
import expansion_mc
import purchase_policy as b
import paired_policy_math as math_owner
import token_paired_identity as actor_identity
import token_paired_data as boundary_helpers
import cpe_paired_data as data
import cpe_paired_policy as policy


RECIPE=dict(version='cpe-paired-expected-improvement-1',epochs=20,batchWorlds=64,
    learningRate=.0003,gradientNorm=.5,originalKlCoefficient=.1,
    maximumMeanOriginalKl=.02,maximumStateOriginalKl=.10,
    gainWeighting='equal-ready-root-horvitz-thompson-sum',
    anchorWeighting='equal-represented-world-equal-deduplicated-state',
    klOrientation='current-to-original',optimizer='fresh-adam-per-head-per-iteration',
    reference='actual-current-sampled-command',frozenParameters=['valueWeights','valueBias'])


@dataclass(frozen=True)
class Config:
    epochSeeds: tuple
    deadline: float

    def __post_init__(self):
        b.require(type(self.epochSeeds) is tuple and len(self.epochSeeds)==len(set(self.epochSeeds))==20,
            'Each head requires exactly20 prospectively fixed distinct epoch seeds.')
        for seed in self.epochSeeds:b.integer(seed,'Head epoch shuffle',1,2**31-1)
        b.number(self.deadline,'Original absolute science deadline',1.)


def source_hashes():
    modules=(b,math_owner,actor_identity,boundary_helpers,data,policy,card_policy,expansion_mc)
    return {Path(path).name:b.file_digest(path) for path in (__file__,*(module.__file__ for module in modules))}


class Prepared(math_owner.Prepared):
    def __init__(self,training,actor):
        b.require(isinstance(training,data.Training),'CPE preparation requires its own strict TRAIN type.')
        super().__init__(training,actor)


def verify_original(prepared,original):
    """Nonzero G3 full distributions are intrinsically checked before any child update."""
    with b.torch.no_grad():
        for chunk in math_owner.batches(prepared.anchor_rows(range(512))):
            logs,_=original([item[0] for item in chunk])
            for log,(_,expected,_) in zip(logs,chunk):
                b.require(max(abs(left-right) for left,right in zip(log.exp().tolist(),expected))<=2e-12,
                    'Recorded original full probabilities must equal the exact original head actor.')


def validate_optimizer(encoded, model, steps):
    """Intrinsic five-slot metadata/shape check; it performs no update or optimizer restore."""
    data.exact(encoded,('state','param_groups'),'Head Adam checkpoint')
    groups=encoded['param_groups'];b.require(isinstance(groups,list) and len(groups)==1,
        'Each head retains exactly one fresh actor-only Adam group.')
    group=groups[0]
    expected=dict(lr=.0003,betas=[.9,.999],eps=1e-8,weight_decay=0,amsgrad=False,maximize=False,
        foreach=None,capturable=False,differentiable=False,fused=None,decoupled_weight_decay=False,params=list(range(5)))
    b.require(group==expected,'Head Adam must retain the exact fixed fresh five-parameter recipe.')
    state=encoded['state'];b.require(isinstance(state,dict) and set(state)==({str(i) for i in range(5)} if steps else set()),
        'Only all five actor moment slots may exist after accepted steps; fresh zero-step state is empty.')
    for index,name in enumerate(b.PARAMETERS[:-2]):
        if not steps:break
        slot=state[str(index)];data.exact(slot,('step','exp_avg','exp_avg_sq'),'Actor Adam slot')
        step=slot['step'];data.exact(step,('tensor','dtype'),'Adam step')
        b.require(step['dtype'] in ('torch.float32','torch.float64') and step['tensor']==steps,
            'All actor Adam slots retain the exact accepted-epoch step count.')
        width=(len(model['stateFeatureNames']) if name=='stateWeights' else
            len(model['actionFeatureNames']) if name=='actionWeights' else None)
        for key in ('exp_avg','exp_avg_sq'):
            item=slot[key];data.exact(item,('tensor','dtype'),'Adam moment tensor')
            values=item['tensor']
            b.require(item['dtype']=='torch.float64' and isinstance(values,list) and len(values)==64 and
                (all(isinstance(row,list) and len(row)==width for row in values) if width is not None else
                 all(type(value) in (int,float) for value in values)),
                'All five actor moments must preserve the exact Float64 parameter shapes.')
            for value in (number for row in values for number in row) if width is not None else values:
                b.number(value,'Finite actor moment',0. if key=='exp_avg_sq' else -float('inf'))


def child_completed(directory):
    directory=Path(directory);done=b.read_json(directory/'finished.json')
    b.require(done['kind']=='cpe-paired-head-complete-1' and done['complete'] is True,
        'Each head requires its explicit complete paired-fit receipt.')
    b.require(set(done['artifacts'])=={'identity.json','policy.json','checkpoint.json','history.json'} and
        all(b.file_digest(directory/name)==digest for name,digest in done['artifacts'].items()),
        'Every child policy/Adam/history/identity subject must remain exact.')
    identity=b.read_json(directory/'identity.json');context=data.Context.from_record(identity['context'])
    b.require(identity==done['identity'] and identity['kind']=='cpe-paired-head-identity-1' and
        identity['recipe']==RECIPE and identity['sourceSha256']==source_hashes(),
        'CPE child identity, recipe and executing source closure must remain exact.')
    model=policy.validate_model(b.read_json(directory/'policy.json'),context.head)
    b.require(done['outputModelSha256']==b.file_digest(directory/'policy.json'),'Published head raw hash differs.')
    history=b.read_json(directory/'history.json');checkpoint=b.read_json(directory/'checkpoint.json')
    attempted=b.integer(done['attemptedEpochs'],'Attempted head epochs',1,20)
    accepted=b.integer(done['acceptedEpochs'],'Accepted head epochs',0,attempted)
    Config(tuple(identity['epochSeeds']),identity['originalDeadline'])
    b.integer(identity['publicCoverage']['trainingReady'],'Admitted TRAIN roots',256,512)
    b.integer(identity['publicCoverage']['validationReady'],'Admitted VAL roots',64,128)
    b.require(len(history)==attempted+1 and [row['epoch'] for row in history]==list(range(attempted+1)) and
        history[0]['accepted'] is True and all(row['accepted'] is True for row in history[1:accepted+1]) and
        (accepted==attempted or (accepted==attempted-1 and history[-1]['accepted'] is False)) and
        history[-1]['actorSha256']==b.digest(model),
        'Child history must retain epoch0, all accepted epochs and at most one final rollback.')
    b.require((accepted==attempted==20 and done['stopReason']=='epoch-limit') or
        (accepted==attempted-1 and done['stopReason']=='original-kl-limit'),
        'Each child ends at epoch20 or the first original-KL rejection.')
    for row in history[1:]:
        mean=b.number(row['anchor']['mean'],'Anchor mean KL');maximum=b.number(row['anchor']['maximum'],'Anchor maximum KL')
        b.require(row['accepted']==(mean<=.02 and maximum<=.10),'Child KL admission must use the exact original limits.')
        b.integer(row['attemptedAdamSteps'],'Attempted actor minibatches',1,8)
    changed=actor_identity.actor_parameters_sha256(model)!=data.sha(identity['inputActorParametersSha256'],'Input head parameters')
    steps=sum(row['attemptedAdamSteps'] for row in history[1:] if row['accepted'])
    b.require(checkpoint['kind']=='cpe-paired-head-adam-1' and checkpoint['head']==context.head.value and
        checkpoint['model']==model and checkpoint['acceptedEpochs']==accepted and
        checkpoint['adamSteps']==done['adamSteps']==steps and done['actorChanged']==changed and
        done['operationalPassed']==(accepted>0 and changed) and
        b.digest({key:value for key,value in model.items() if key not in b.PARAMETERS[:-2]})==identity['frozenModelFieldsSha256'],
        'Child policy, actor-only movement, preserved values and accepted Adam counters must agree.')
    validate_optimizer(checkpoint['optimizer'],model,steps)
    return done


def _fit_head(training,original,current,output,config,predecessor):
    """Only fit_bundle calls this after all-head public/source/carry admission."""
    math_owner.check_clock(config);output.mkdir()
    b.torch.set_num_threads(1);b.torch.use_deterministic_algorithms(True)
    head=training.context.head;actor=policy.CpePolicy(current.model,head);prepared=Prepared(training,actor)
    math_owner.verify_behavior(prepared,policy.CpePolicy(current.model,head))
    verify_original(prepared,policy.CpePolicy(original.model,head))
    optimizer=b.torch.optim.Adam([getattr(actor,name) for name in b.PARAMETERS[:-2]],lr=.0003)
    identity=dict(kind='cpe-paired-head-identity-1',recipe=RECIPE,context=asdict(training.context),
        inputActorParametersSha256=actor_identity.actor_parameters_sha256(current.model),
        frozenModelFieldsSha256=b.digest({key:value for key,value in current.model.items() if key not in b.PARAMETERS[:-2]}),
        trainingSha256=training.sha256,anchorSha256=training.anchor_sha256,predecessor=predecessor,
        publicCoverage=dict(trainingReady=training.ready_count,validationReady=training.validation_ready_count),
        epochSeeds=list(config.epochSeeds),originalDeadline=config.deadline,sourceSha256=source_hashes())
    b.atomic_json(output/'identity.json',identity)
    history=[dict(epoch=0,accepted=True,anchor=math_owner.anchor_metrics(actor,prepared),actorSha256=b.digest(actor.artifact()))]
    accepted=0;steps=0
    for epoch,seed in enumerate(config.epochSeeds,1):
        result=math_owner.epoch(actor,optimizer,prepared,seed,config)
        history.append(dict(result,epoch=epoch,actorSha256=b.digest(actor.artifact())))
        b.atomic_json(output/'history.json',history)
        if not result['accepted']:break
        accepted+=1;steps+=result['attemptedAdamSteps']
    math_owner.check_clock(config);model=actor.artifact()
    b.require(all(model[name]==current.model[name] for name in b.PARAMETERS[-2:]),
        'Every head must preserve its own original value parameters exactly.')
    b.require(training.sha256==identity['trainingSha256'] and source_hashes()==identity['sourceSha256'],
        'Immutable TRAIN subjects and all numerical owners must survive the child update.')
    changed=actor_identity.actor_parameters_sha256(model)!=identity['inputActorParametersSha256']
    b.atomic_json(output/'policy.json',model)
    b.atomic_json(output/'checkpoint.json',dict(kind='cpe-paired-head-adam-1',head=head.value,model=model,
        optimizer=b.encode_optimizer(optimizer),acceptedEpochs=accepted,adamSteps=steps))
    done=dict(kind='cpe-paired-head-complete-1',complete=True,identity=identity,
        acceptedEpochs=accepted,attemptedEpochs=len(history)-1,adamSteps=steps,actorChanged=changed,
        operationalPassed=accepted>0 and changed,stopReason='epoch-limit' if accepted==20 else 'original-kl-limit',
        outputModelSha256=b.file_digest(output/'policy.json'),artifacts={name:b.file_digest(output/name)
            for name in ('identity.json','policy.json','checkpoint.json','history.json')})
    b.atomic_json(output/'finished.json',done)
    return child_completed(output)


def load_completed_bundle(directory):
    directory=Path(directory);raw=(directory/'bundle.json').read_bytes();value=json.loads(raw)
    actors=[]
    for head in policy.HEADS:
        item=value['heads'][head.value]
        b.require(item['path']==f'heads/{head.value}/policy.json','Published bundle paths must be portable fixed child paths.')
        actors.append(policy.FrozenActor(head,(directory/item['path']).read_bytes(),item['sha256']))
    return policy.Bundle(raw,b.file_digest(directory/'bundle.json'),tuple(actors))


def completed(directory):
    directory=Path(directory);done=b.read_json(directory/'finished.json');identity=b.read_json(directory/'identity.json')
    b.require(done['kind']=='cpe-paired-bundle-complete-1' and done['complete'] is True and done['operationalPassed'] is True and
        identity['kind']=='cpe-paired-bundle-identity-1' and identity==done['identity'] and identity['recipe']==RECIPE and
        identity['sourceSha256']==source_hashes(), 'Only one complete successful three-head transaction is consumable.')
    expected={'identity.json','bundle.json',*(f'heads/{head.value}/{name}' for head in policy.HEADS
        for name in ('identity.json','policy.json','checkpoint.json','history.json','finished.json'))}
    b.require(set(done['artifacts'])==expected and all(b.file_digest(directory/name)==digest for name,digest in done['artifacts'].items()),
        'Atomic bundle completion must bind exactly all three full child transactions and descriptor.')
    bundle=load_completed_bundle(directory)
    b.require(done['outputBundleSha256']==bundle.sha256,'Atomic output bundle subject differs.')
    b.require(all(set(identity[key])=={head.value for head in policy.HEADS} for key in
        ('contexts','trainingSha256','epochSeeds','predecessors')),'Atomic identity requires all three explicit heads.')
    seeds=[seed for values in identity['epochSeeds'].values() for seed in values]
    b.require(len(seeds)==len(set(seeds))==60,'Atomic publication requires60 distinct head-specific epoch shuffles.')
    b.require(identity['iteration'] in (1,2) and (identity['previousFinishedSha256'] is None)==(identity['iteration']==1),
        'Atomic publication must retain its fixed iteration and predecessor presence.')
    for head in policy.HEADS:
        child=child_completed(directory/'heads'/head.value);context=child['identity']['context']
        typed=data.Context.from_record(context)
        b.require(child['operationalPassed'] is True and child['outputModelSha256']==bundle.actor(head).sha256 and
            context==identity['contexts'][head.value] and child['identity']['trainingSha256']==identity['trainingSha256'][head.value] and
            child['identity']['epochSeeds']==identity['epochSeeds'][head.value] and
            child['identity']['predecessor']==identity['predecessors'][head.value],
            'Every published head must belong to this exact same-bundle cohort and predecessor transaction.')
        b.require(typed.head is head and typed.iteration==identity['iteration'] and
            typed.bundle_scope_sha256==identity['bundleScopeSha256'] and typed.ecology_sha256==identity['ecologySha256'] and
            typed.original_bundle_sha256==identity['originalBundleSha256'] and typed.current_bundle_sha256==identity['currentBundleSha256'] and
            child['identity']['originalDeadline']==identity['originalDeadline'] and
            (child['identity']['predecessor'] is None)==(typed.iteration==1),
            'Every completed child must share its complete-bundle identity and original deadline.')
        if typed.iteration==2:
            predecessor=child['identity']['predecessor']
            data.exact(predecessor,('bundleFinishedSha256','headFinishedSha256','modelSha256'),'Atomic child predecessor')
            b.require(predecessor['bundleFinishedSha256']==identity['previousFinishedSha256'] and
                predecessor['modelSha256']==typed.current_model_sha256,'A child cannot consume another bundle predecessor.')
    return done


def _admit(trainings,original,current,dictionaries,configs,previous):
    b.require(all(isinstance(value,dict) and set(value)==set(policy.HEADS) for value in (trainings,dictionaries,configs)),
        'Declare exactly all three typed heads, raw dictionaries and fixed configurations.')
    rows=tuple(trainings[head] for head in policy.HEADS);cfg=tuple(configs[head] for head in policy.HEADS)
    b.require(all(isinstance(row,data.Training) and row.context.head is head for head,row in zip(policy.HEADS,rows)) and
        all(isinstance(item,Config) for item in cfg),'All cohorts/configurations must use the dedicated CPE types.')
    b.require(all(row.ready_count>=256 for row in rows),'Every head needs at least256 READY TRAIN roots before any fit.')
    for head,row in zip(policy.HEADS,rows):row.context.bind(original,current,dictionaries[head])
    first=rows[0].context
    common=('iteration','bundle_scope_sha256','ecology_sha256','original_bundle_sha256','current_bundle_sha256')
    b.require(all(all(getattr(row.context,key)==getattr(first,key) for key in common) for row in rows),
        'All three independent cohorts must use the same original/current complete bundle and ecology.')
    b.require(len({row.context.request_bank_sha256 for row in rows})==3 and
        len({root.record['requestSha256'] for row in rows for root in row.roots})==1536 and
        len({seed for row in rows for root in row.roots for seed in root.record['futureSeeds']})==3072,
        'The three head cohorts must retain distinct world banks, requests and paired streams.')
    seeds=[seed for item in cfg for seed in item.epochSeeds]
    b.require(len(set(seeds))==60 and len({item.deadline for item in cfg})==1,
        'Each iteration uses60 distinct head shuffles and the same original deadline.')
    b.require((previous is None)==(first.iteration==1),'Iteration2 requires only the atomic completed iteration1 predecessor.')
    predecessors={head.value:None for head in policy.HEADS};previous_sha=None
    if previous is not None:
        done=completed(previous);old=done['identity'];previous_sha=b.file_digest(Path(previous)/'finished.json')
        b.require(old['iteration']==1 and done['outputBundleSha256']==current.sha256 and
            old['originalBundleSha256']==original.sha256 and old['bundleScopeSha256']==first.bundle_scope_sha256,
            'Iteration2 consumes the sole completed iteration1 bundle and original scientific scope.')
        old_seeds={seed for values in old['epochSeeds'].values() for seed in values}
        b.require(not old_seeds.intersection(seeds),'Iteration2 cannot reuse any original epoch shuffle.')
        for head,row in zip(policy.HEADS,rows):
            child=child_completed(Path(previous)/'heads'/head.value);context=child['identity']['context']
            for key in ('scope_sha256','ecology_sha256','dictionary_sha256','dictionary_semantic_sha256','original_model_sha256'):
                b.require(context[key]==getattr(row.context,key),'Original head scope, model or dictionary changed.')
            b.require(child['identity']['anchorSha256']==row.anchor_sha256 and
                context['request_bank_sha256']!=row.context.request_bank_sha256 and
                context['source_sha256']!=row.context.source_sha256 and
                child['outputModelSha256']==current.actor(head).sha256,
                'Each head retains its original anchors and uses its sole actor with fresh cohort evidence.')
            predecessors[head.value]=dict(bundleFinishedSha256=previous_sha,
                headFinishedSha256=b.file_digest(Path(previous)/'heads'/head.value/'finished.json'),
                modelSha256=child['outputModelSha256'])
    return rows,cfg,predecessors,previous_sha


def fit_bundle(trainings,original,current,dictionaries,output,configs,*,previous=None):
    """No child result becomes a next bundle until all three successful receipts are checked."""
    rows,cfg,predecessors,previous_sha=_admit(trainings,original,current,dictionaries,configs,previous)
    math_owner.check_clock(cfg[0]);output=Path(output)
    b.require(not output.exists(),'The atomic paired bundle permits one original output and no retry.')
    output.mkdir(parents=True);(output/'heads').mkdir()
    context=rows[0].context
    identity=dict(kind='cpe-paired-bundle-identity-1',recipe=RECIPE,iteration=context.iteration,
        originalBundleSha256=original.sha256,currentBundleSha256=current.sha256,
        bundleScopeSha256=context.bundle_scope_sha256,ecologySha256=context.ecology_sha256,
        contexts={row.context.head.value:asdict(row.context) for row in rows},
        trainingSha256={row.context.head.value:row.sha256 for row in rows},
        epochSeeds={head.value:list(item.epochSeeds) for head,item in zip(policy.HEADS,cfg)},
        predecessors=predecessors,previousFinishedSha256=previous_sha,sourceSha256=source_hashes(),
        originalDeadline=cfg[0].deadline)
    b.atomic_json(output/'identity.json',identity)
    try:
        for head,row,item in zip(policy.HEADS,rows,cfg):
            child=_fit_head(row,original.actor(head),current.actor(head),output/'heads'/head.value,item,predecessors[head.value])
            if not child['operationalPassed']:
                stopped=dict(kind='cpe-paired-bundle-stop-1',complete=False,operationalPassed=False,
                    reason='no-accepted-actor-change',head=head.value,identity=identity,outputBundleSha256=None,
                    completedHeads=[h.value for h in policy.HEADS if (output/'heads'/h.value/'finished.json').exists()])
                b.atomic_json(output/'stopped.json',stopped);return stopped
        b.require(source_hashes()==identity['sourceSha256'] and all(row.sha256==identity['trainingSha256'][row.context.head.value] for row in rows),
            'Every original source/cohort must remain unchanged before atomic publication.')
        if previous is not None:
            b.require(b.file_digest(Path(previous)/'finished.json')==previous_sha and completed(previous)['outputBundleSha256']==current.sha256,
                'The same atomic predecessor must survive all three child fits.')
        math_owner.check_clock(cfg[0]);heads={}
        for head in policy.HEADS:
            done=child_completed(output/'heads'/head.value)
            raw=(output/'heads'/head.value/'policy.json').read_bytes()
            result=policy.FrozenActor(head,raw,done['outputModelSha256'])
            heads[head.value]=dict(path=f'heads/{head.value}/policy.json',sha256=result.sha256,dictionarySha256=result.dictionary_sha256)
        b.atomic_json(output/'bundle.json',dict(kind=policy.BUNDLE_KIND,heads=heads))
        bundle=load_completed_bundle(output)
        names=['identity.json','bundle.json',*(f'heads/{head.value}/{name}' for head in policy.HEADS
            for name in ('identity.json','policy.json','checkpoint.json','history.json','finished.json'))]
        done=dict(kind='cpe-paired-bundle-complete-1',complete=True,operationalPassed=True,identity=identity,
            outputBundleSha256=bundle.sha256,artifacts={name:b.file_digest(output/name) for name in names})
        b.atomic_json(output/'finished.json',done);return completed(output)
    except BaseException as failure:
        b.atomic_json(output/'failed.json',dict(kind='cpe-paired-bundle-failure-1',complete=False,
            operationalPassed=False,error=str(failure),identitySha256=b.file_digest(output/'identity.json'),
            outputBundleConsumable=False))
        raise
