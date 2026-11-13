"""Fixed independent child fits with one atomic complete C/P/E bundle publication."""
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import purchase_policy as b
import paired_policy_math as math_owner
import token_paired_identity as actor_identity
import cpe_paired_data as data
import cpe_paired_policy as policy
import cpe_paired_completed as completion_owner


RECIPE=completion_owner.RECIPE


@dataclass(frozen=True)
class Config:
    epochSeeds: tuple
    deadline: float

    def __post_init__(self):
        b.require(type(self.epochSeeds) is tuple and len(self.epochSeeds)==len(set(self.epochSeeds))==20,
            'Each head requires exactly20 prospectively fixed distinct epoch seeds.')
        for seed in self.epochSeeds:b.integer(seed,'Head epoch shuffle',1,2**31-1)
        b.number(self.deadline,'Original absolute science deadline',1.)


source_hashes=completion_owner.source_hashes
validate_optimizer=completion_owner.validate_optimizer
child_completed=completion_owner.child_completed
completed=completion_owner.completed


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
        frozenModelFieldsSha256=completion_owner.frozen_fields_sha256(current.model),
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
