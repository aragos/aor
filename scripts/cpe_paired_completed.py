"""Pure immutable C/P/E completion admission; no Torch, NumPy, inference or optimizer execution."""
import hashlib
import json
import math
from pathlib import Path
from statistics import NormalDist
from types import SimpleNamespace

import token_paired_identity as actor_identity

HEADS=('CARD','PURCHASE','EXPANSION')
RECIPE=dict(version='cpe-paired-expected-improvement-1',epochs=20,batchWorlds=64,
    learningRate=.0003,gradientNorm=.5,originalKlCoefficient=.1,
    maximumMeanOriginalKl=.02,maximumStateOriginalKl=.10,
    gainWeighting='equal-ready-root-horvitz-thompson-sum',
    anchorWeighting='equal-represented-world-equal-deduplicated-state',
    klOrientation='current-to-original',optimizer='fresh-adam-per-head-per-iteration',
    reference='actual-current-sampled-command',frozenParameters=['valueWeights','valueBias'])

def require(condition,message):
    if not condition:raise ValueError(message)


def integer(value,name,minimum,maximum):
    require(type(value) is int and minimum<=value<=maximum,f'{name} must be an integer in [{minimum},{maximum}].')
    return value


def number(value,name,minimum=-math.inf,maximum=math.inf):
    require(type(value) in (int,float) and math.isfinite(value) and minimum<=value<=maximum,
        f'{name} must be a finite number in range.')
    return float(value)


def exact(value,keys,label):
    require(isinstance(value,dict) and set(value)==set(keys),f'{label} requires its exact declared fields.')


def sha(value,label):
    require(isinstance(value,str) and len(value)==64 and all(c in '0123456789abcdef' for c in value),
        f'{label} requires lowercase SHA-256.')
    return value


def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def digest(value):return hashlib.sha256(canonical(value)).hexdigest()
def read_json(path):
    def reject(value):raise ValueError(f'Nonfinite JSON constant: {value}')
    return json.loads(Path(path).read_bytes(),parse_constant=reject)


def file_digest(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):value.update(chunk)
    return value.hexdigest()


PARAMETERS=actor_identity.ACTOR_PARAMETERS+('valueWeights','valueBias')
SOURCE_NAMES=('purchase_policy.py','paired_policy_math.py','token_paired_identity.py','token_paired_data.py',
    'cpe_paired_data.py','cpe_paired_policy.py','card_policy.py','expansion_mc.py','cpe_paired_fit.py',
    'cpe_paired_completed.py','cpe_paired_validation.py')


def source_hashes():
    return {name:file_digest(Path(__file__).parent/name) for name in SOURCE_NAMES}


def validate_context(value):
    fields=('head','iteration','source_sha256','scope_sha256','bundle_scope_sha256','ecology_sha256',
        'request_bank_sha256','dictionary_sha256','dictionary_semantic_sha256','original_model_sha256',
        'current_model_sha256','original_bundle_sha256','current_bundle_sha256')
    exact(value,fields,'CPE context');require(value['head'] in HEADS,'Explicit CPE head required.')
    integer(value['iteration'],'Iteration',1,2)
    for key in fields:
        if key not in ('head','iteration'):sha(value[key],key)
    require(value['scope_sha256']==digest(dict(bundleScopeSha256=value['bundle_scope_sha256'],head=value['head'])),
        'Head scope must derive from the original complete bundle scope.')
    return SimpleNamespace(**value)


def validate_config(seeds,deadline):
    require(isinstance(seeds,(tuple,list)) and len(seeds)==len(set(seeds))==20,'Exactly20 distinct head shuffles required.')
    for seed in seeds:integer(seed,'Head epoch shuffle',1,2**31-1)
    number(deadline,'Original absolute deadline',1.)


def validate_model(model,head):
    expected={'CARD':(1,'aor-card-observation-1'),'PURCHASE':(1,'aor-purchase-observation-1'),
        'EXPANSION':(2,'aor-expansion-observation-2')}
    require(head in HEADS and (model.get('schemaVersion'),model.get('encoderVersion'))==expected[head] and
        type(model['schemaVersion']) is int and model.get('hiddenSize')==64 and type(model['hiddenSize']) is int and
        model.get('baselineProbability')==.97,'Completed actor must preserve its exact typed64-wide contract.')
    for key,limit in (('stateFeatureNames',8192),('actionFeatureNames',512)):
        names=model.get(key)
        require(isinstance(names,list) and 0<len(names)<=limit and all(isinstance(name,str) and name for name in names) and
            names==sorted(set(names)),'Completed actor must retain its complete sorted unique dictionary.')
    sizes=dict(stateWeights=64*len(model['stateFeatureNames']),actionWeights=64*len(model['actionFeatureNames']),
        stateBias=64,actionBias=64,actorWeights=64,valueWeights=64)
    for key,size in sizes.items():
        values=model.get(key);require(isinstance(values,list) and len(values)==size,'Completed parameter shape changed.')
        for value in values:number(value,key,-1e6,1e6)
    number(model.get('valueBias'),'Frozen value bias',-1e6,1e6)
    return model


def frozen_fields_sha256(model):
    """Preserve metadata exactly and value parameters as Float64, independent of JSON zero spelling."""
    value={key:item for key,item in model.items() if key not in PARAMETERS[:-2]}
    value['valueWeights']=[float(number(item,'Frozen value parameter')) if item else 0. for item in model['valueWeights']]
    value['valueBias']=float(number(model['valueBias'],'Frozen value bias')) if model['valueBias'] else 0.
    return digest(value)


def load_completed_bundle(directory):
    directory=Path(directory);descriptor=read_json(directory/'bundle.json')
    exact(descriptor,('kind','heads'),'CPE bundle')
    require(descriptor['kind']=='cpe-paired-bundle-1' and set(descriptor['heads'])==set(HEADS),
        'The completed bundle requires every explicit head.')
    actors={}
    for head in HEADS:
        item=descriptor['heads'][head];exact(item,('path','sha256','dictionarySha256'),'Bundle component')
        require(item['path']==f'heads/{head}/policy.json','Published component paths must be the fixed portable paths.')
        path=directory/item['path'];model=validate_model(read_json(path),head)
        require(file_digest(path)==item['sha256'] and digest({key:model[key] for key in
            ('encoderVersion','stateFeatureNames','actionFeatureNames')})==item['dictionarySha256'],
            'Completed component raw/semantic subjects differ.')
        actors[head]=SimpleNamespace(sha256=item['sha256'],model=model)
    return SimpleNamespace(sha256=file_digest(directory/'bundle.json'),actor=lambda head:actors[head])


def validate_optimizer(encoded, model, steps):
    """Intrinsic five-slot metadata/shape check; it performs no update or optimizer restore."""
    exact(encoded,('state','param_groups'),'Head Adam checkpoint')
    groups=encoded['param_groups'];require(isinstance(groups,list) and len(groups)==1,
        'Each head retains exactly one fresh actor-only Adam group.')
    group=groups[0]
    expected=dict(lr=.0003,betas=[.9,.999],eps=1e-8,weight_decay=0,amsgrad=False,maximize=False,
        foreach=None,capturable=False,differentiable=False,fused=None,decoupled_weight_decay=False,params=list(range(5)))
    require(group==expected,'Head Adam must retain the exact fixed fresh five-parameter recipe.')
    state=encoded['state'];require(isinstance(state,dict) and set(state)==({str(i) for i in range(5)} if steps else set()),
        'Only all five actor moment slots may exist after accepted steps; fresh zero-step state is empty.')
    for index,name in enumerate(PARAMETERS[:-2]):
        if not steps:break
        slot=state[str(index)];exact(slot,('step','exp_avg','exp_avg_sq'),'Actor Adam slot')
        step=slot['step'];exact(step,('tensor','dtype'),'Adam step')
        require(step['dtype'] in ('torch.float32','torch.float64') and step['tensor']==steps,
            'All actor Adam slots retain the exact accepted-epoch step count.')
        width=(len(model['stateFeatureNames']) if name=='stateWeights' else
            len(model['actionFeatureNames']) if name=='actionWeights' else None)
        for key in ('exp_avg','exp_avg_sq'):
            item=slot[key];exact(item,('tensor','dtype'),'Adam moment tensor')
            values=item['tensor']
            require(item['dtype']=='torch.float64' and isinstance(values,list) and len(values)==64 and
                (all(isinstance(row,list) and len(row)==width for row in values) if width is not None else
                 all(type(value) in (int,float) for value in values)),
                'All five actor moments must preserve the exact Float64 parameter shapes.')
            for value in (number for row in values for number in row) if width is not None else values:
                number(value,'Finite actor moment',0. if key=='exp_avg_sq' else -float('inf'))


def child_completed(directory):
    directory=Path(directory);done=read_json(directory/'finished.json')
    require(done['kind']=='cpe-paired-head-complete-1' and done['complete'] is True,
        'Each head requires its explicit complete paired-fit receipt.')
    require(set(done['artifacts'])=={'identity.json','policy.json','checkpoint.json','history.json'} and
        all(file_digest(directory/name)==digest for name,digest in done['artifacts'].items()),
        'Every child policy/Adam/history/identity subject must remain exact.')
    identity=read_json(directory/'identity.json');context=validate_context(identity['context'])
    require(identity==done['identity'] and identity['kind']=='cpe-paired-head-identity-1' and
        identity['recipe']==RECIPE and identity['sourceSha256']==source_hashes(),
        'CPE child identity, recipe and executing source closure must remain exact.')
    model=validate_model(read_json(directory/'policy.json'),context.head)
    require(done['outputModelSha256']==file_digest(directory/'policy.json'),'Published head raw hash differs.')
    history=read_json(directory/'history.json');checkpoint=read_json(directory/'checkpoint.json')
    attempted=integer(done['attemptedEpochs'],'Attempted head epochs',1,20)
    accepted=integer(done['acceptedEpochs'],'Accepted head epochs',0,attempted)
    validate_config(identity['epochSeeds'],identity['originalDeadline'])
    integer(identity['publicCoverage']['trainingReady'],'Admitted TRAIN roots',256,512)
    integer(identity['publicCoverage']['validationReady'],'Admitted VAL roots',64,128)
    require(len(history)==attempted+1 and [row['epoch'] for row in history]==list(range(attempted+1)) and
        history[0]['accepted'] is True and all(row['accepted'] is True for row in history[1:accepted+1]) and
        (accepted==attempted or (accepted==attempted-1 and history[-1]['accepted'] is False)) and
        history[-1]['actorSha256']==digest(model),
        'Child history must retain epoch0, all accepted epochs and at most one final rollback.')
    require((accepted==attempted==20 and done['stopReason']=='epoch-limit') or
        (accepted==attempted-1 and done['stopReason']=='original-kl-limit'),
        'Each child ends at epoch20 or the first original-KL rejection.')
    for row in history[1:]:
        mean=number(row['anchor']['mean'],'Anchor mean KL');maximum=number(row['anchor']['maximum'],'Anchor maximum KL')
        require(type(row['accepted']) is bool and row['accepted']==(mean<=.02 and maximum<=.10),
            'Child KL admission must use the exact original limits.')
        integer(row['attemptedAdamSteps'],'Attempted actor minibatches',1,8)
        number(row['minibatchGainMean'],'Finite minibatch gain');number(row['minibatchKlMean'],'Finite minibatch KL')
    if accepted<attempted:
        require(history[-1]['actorSha256']==history[-2]['actorSha256'],
            'The first rejected epoch must restore the exact previously accepted actor.')
    changed=actor_identity.actor_parameters_sha256(model)!=sha(identity['inputActorParametersSha256'],'Input head parameters')
    steps=sum(row['attemptedAdamSteps'] for row in history[1:] if row['accepted'])
    require(checkpoint['kind']=='cpe-paired-head-adam-1' and checkpoint['head']==context.head and
        checkpoint['model']==model and checkpoint['acceptedEpochs']==accepted and
        checkpoint['adamSteps']==done['adamSteps']==steps and done['actorChanged']==changed and
        done['operationalPassed']==(accepted>0 and changed) and
        frozen_fields_sha256(model)==identity['frozenModelFieldsSha256'],
        'Child policy, actor-only movement, preserved values and accepted Adam counters must agree.')
    validate_optimizer(checkpoint['optimizer'],model,steps)
    return done


def completed(directory):
    directory=Path(directory);done=read_json(directory/'finished.json');identity=read_json(directory/'identity.json')
    require(done['kind']=='cpe-paired-bundle-complete-1' and done['complete'] is True and done['operationalPassed'] is True and
        identity['kind']=='cpe-paired-bundle-identity-1' and identity==done['identity'] and identity['recipe']==RECIPE and
        identity['sourceSha256']==source_hashes(), 'Only one complete successful three-head transaction is consumable.')
    expected={'identity.json','bundle.json',*(f'heads/{head}/{name}' for head in HEADS
        for name in ('identity.json','policy.json','checkpoint.json','history.json','finished.json'))}
    require(set(done['artifacts'])==expected and all(file_digest(directory/name)==digest for name,digest in done['artifacts'].items()),
        'Atomic bundle completion must bind exactly all three full child transactions and descriptor.')
    bundle=load_completed_bundle(directory)
    require(done['outputBundleSha256']==bundle.sha256,'Atomic output bundle subject differs.')
    require(all(set(identity[key])=={head for head in HEADS} for key in
        ('contexts','trainingSha256','epochSeeds','predecessors')),'Atomic identity requires all three explicit heads.')
    seeds=[seed for values in identity['epochSeeds'].values() for seed in values]
    require(len(seeds)==len(set(seeds))==60,'Atomic publication requires60 distinct head-specific epoch shuffles.')
    require(identity['iteration'] in (1,2) and (identity['previousFinishedSha256'] is None)==(identity['iteration']==1),
        'Atomic publication must retain its fixed iteration and predecessor presence.')
    for head in HEADS:
        child=child_completed(directory/'heads'/head);context=child['identity']['context']
        typed=validate_context(context)
        require(child['operationalPassed'] is True and child['outputModelSha256']==bundle.actor(head).sha256 and
            context==identity['contexts'][head] and child['identity']['trainingSha256']==identity['trainingSha256'][head] and
            child['identity']['epochSeeds']==identity['epochSeeds'][head] and
            child['identity']['predecessor']==identity['predecessors'][head],
            'Every published head must belong to this exact same-bundle cohort and predecessor transaction.')
        require(typed.head==head and typed.iteration==identity['iteration'] and
            typed.bundle_scope_sha256==identity['bundleScopeSha256'] and typed.ecology_sha256==identity['ecologySha256'] and
            typed.original_bundle_sha256==identity['originalBundleSha256'] and typed.current_bundle_sha256==identity['currentBundleSha256'] and
            child['identity']['originalDeadline']==identity['originalDeadline'] and
            (child['identity']['predecessor'] is None)==(typed.iteration==1),
            'Every completed child must share its complete-bundle identity and original deadline.')
        if typed.iteration==2:
            predecessor=child['identity']['predecessor']
            exact(predecessor,('bundleFinishedSha256','headFinishedSha256','modelSha256'),'Atomic child predecessor')
            require(predecessor['bundleFinishedSha256']==identity['previousFinishedSha256'] and
                predecessor['modelSha256']==typed.current_model_sha256,'A child cannot consume another bundle predecessor.')
    return done


def verify(directory):
    """Campaign-facing zero-execution completion receipt, with every checked child artifact pinned."""
    directory=Path(directory);done=completed(directory)
    pins={str((directory/name).resolve()):value for name,value in done['artifacts'].items()}
    pins[str((directory/'finished.json').resolve())]=file_digest(directory/'finished.json')
    return dict(passed=True,completion=done,heads={head:child_completed(directory/'heads'/head) for head in HEADS},
        artifactPins=pins,modelForwards=0,optimizerSteps=0,numericalLibrariesImported=False)


def probabilities(values,count,label):
    require(isinstance(values,list) and len(values)==count and count>0,f'{label} requires every offered probability.')
    for value in values:number(value,label,0.,1.);require(value>0.,f'{label} requires positive full support.')
    require(abs(math.fsum(values)-1.)<=1e-12,f'{label} probabilities must sum to one.')


def admitted_seal(subject):
    exact(subject,('path','sha256'),'All-head probability seal subject')
    path=Path(subject['path']);sha(subject['sha256'],'Probability seal')
    require(file_digest(path)==subject['sha256'],'The persisted probability seal changed.')
    seal=read_json(path)
    require(seal['kind']=='cpe-paired-validation-probabilities-1' and seal['complete'] is True and
        seal['semanticLabelsDecoded'] is False and set(seal['heads'])=={head for head in HEADS},
        'Only a complete public all-head seal can admit labels.')
    directory=Path(seal['fitDirectory']);done=completed(directory)
    require(file_digest(directory/'finished.json')==seal['fitFinishedSha256'] and
        done['outputBundleSha256']==seal['bundleSha256'] and done['identity']['iteration']==seal['iteration'] and
        all(done['identity'][key]==seal[key] for key in
            ('originalBundleSha256','currentBundleSha256','bundleScopeSha256')),
        'The exact completed bundle and scientific identity must precede VAL labels.')
    for head in HEADS:
        item=seal['heads'][head];child=child_completed(directory/'heads'/head)
        require(item['context']==child['identity']['context'] and item['modelSha256']==child['outputModelSha256'] and
            len(item['rows'])==128 and item['counts']=={status:sum(row['status']==status for row in item['rows'])
                for status in ('READY','MISSING')} and
            item['counts']['READY']==child['identity']['publicCoverage']['validationReady']>=64,
            'Every sealed head requires its exact completed actor and original support counts.')
        for index,row in enumerate(item['rows'],512):
            require(row['index']==index and row['status'] in ('READY','MISSING'),
                'All original VAL world positions and support flags must remain exact.')
            sha(row['publicSha256'],'Sealed public input');sha(row['requestSha256'],'Sealed request')
            if row['status']=='MISSING':
                require(all(row[key] is None for key in ('referenceIndex','proposedIndices','inclusionProbability',
                    'currentProbabilities','probabilities')),'Missing VAL roots cannot invent distributions or decisions.')
                continue
            count=len(row['probabilities']);probabilities(row['probabilities'],count,'Sealed probabilities')
            probabilities(row['currentProbabilities'],count,'Sealed behavior probabilities')
            size=min(8,count-1);indices=row['proposedIndices']
            require(count>1 and row['status']=='READY' and len(indices)==size+1==len(set(indices)) and
                indices[0]==row['referenceIndex'] and row['inclusionProbability']==(size/(count-1) if count>1 else 0.),
                'Sealed sampled reference, support and uniform inclusion matrix must remain exact.')
            for action in indices:integer(action,'Sealed offered index',0,count-1)
    return seal,done


def estimate(values):
    require(len(values)>=2,'An origin interval requires at least two independent worlds.')
    mean=math.fsum(values)/len(values)
    variance=math.fsum((value-mean)**2 for value in values)/(len(values)-1)
    error=math.sqrt(variance/len(values));margin=NormalDist().inv_cdf(.975)*error
    return dict(mean=mean,variance=variance,standardError=error,lower=mean-margin,upper=mean+margin,
        origins=len(values),interval='two-sided-normal-95-independent-world')


def equal_phase(estimates):
    require(set(estimates)=={head for head in HEADS},'Equal-phase inference requires all three phases.')
    mean=math.fsum(value['mean'] for value in estimates.values())/3
    variance=math.fsum(value['variance']/value['origins'] for value in estimates.values())/9
    error=math.sqrt(variance);margin=NormalDist().inv_cdf(.975)*error
    return dict(mean=mean,standardError=error,lower=mean-margin,upper=mean+margin,
        interval='two-sided-normal-95-equal-phase-independent-worlds',phaseWeights={head:1/3 for head in HEADS})


def effects(item, labels):
    require(len(labels)==128,'Each head retains all128 original label/missing positions.')
    results=[]
    for row,label in zip(item['rows'],labels):
        if row['status']!='READY':
            require(label is None,'Missing/singleton roots have no fabricated paired effects.')
            results.append(dict(index=row['index'],status=row['status'],gain=None));continue
        exact(label,('publicSha256','futureSeeds','actions'),'Detached VAL labels')
        require(label['publicSha256']==row['publicSha256'] and label['futureSeeds']==row['futureSeeds'] and
            len(label['actions'])==len(row['proposedIndices']),
            'VAL labels must bind the sealed public root and complete original paired matrix.')
        rewards=[]
        for index,action in zip(row['proposedIndices'],label['actions']):
            exact(action,('actionIndex','rewards'),'VAL sibling outcome')
            require(action['actionIndex']==index and isinstance(action['rewards'],list) and len(action['rewards'])==2,
                'Every original proposed sibling requires both ordered future returns.')
            rewards.append(tuple(number(value,'Normalized paired reward',0.,1.) for value in action['rewards']))
        values=tuple(math.fsum((row['probabilities'][index]-row['currentProbabilities'][index])*
            (reward[stream]-rewards[0][stream])/row['inclusionProbability']
            for index,reward in zip(row['proposedIndices'][1:],rewards[1:])) for stream in range(2))
        results.append(dict(index=row['index'],status='READY',pairedFutureGains=values,gain=math.fsum(values)/2))
    return dict(counts=item['counts'],estimate=estimate([row['gain'] for row in results if row['gain'] is not None]),perOrigin=results)


def validate_seal_pair(sealed_subjects):
    """Shared intrinsic barrier; callers must not substitute a weaker receipt-shape check."""
    require(isinstance(sealed_subjects,dict) and set(sealed_subjects)=={1,2},
        'Both fixed iteration probability seals must be supplied before any labels.')
    seals={};transactions={}
    for iteration in (1,2):
        seals[iteration],transactions[iteration]=admitted_seal(sealed_subjects[iteration])
        require(seals[iteration]['iteration']==iteration,'Iteration seals cannot be swapped or substituted.')
    first=seals[1];second=seals[2]
    require(second['currentBundleSha256']==first['bundleSha256'] and
        all(second[key]==first[key] for key in ('originalBundleSha256','bundleScopeSha256')) and
        transactions[2]['identity']['previousFinishedSha256']==first['fitFinishedSha256'],
        'VAL analysis requires the sole original-to-first-to-second atomic bundle chain.')
    return seals,transactions


def build_effects(sealed_subjects, load_labels):
    """Require both completed iterations AND both all-head seals before any label callback.

    load_labels(iteration, Head, exact_subject) returns128 detached label positions.
    This owner runs no model forward and never selects an epoch, head or iteration.
    """
    seals,transactions=validate_seal_pair(sealed_subjects)
    second=seals[2]
    reports={}
    for iteration in (1,2):
        heads={head:effects(seals[iteration]['heads'][head],
            tuple(load_labels(iteration,head,dict(sealed_subjects[iteration])))) for head in HEADS}
        reports[str(iteration)]=dict(heads=heads,equalPhase=equal_phase({key:value['estimate'] for key,value in heads.items()}))
    require(all(file_digest(subject['path'])==subject['sha256'] for subject in sealed_subjects.values()),
        'Both original probability barriers must survive every semantic label pass.')
    for iteration in (1,2):
        require(completed(seals[iteration]['fitDirectory'])==transactions[iteration],
            'Both exact complete atomic transactions must survive VAL evaluation.')
    report=dict(kind='cpe-paired-validation-effect-1',probabilitySeals={str(key):value for key,value in sealed_subjects.items()},
        iterations=reports,finalIteration=2,finalBundleSha256=second['bundleSha256'],
        mechanismPassed=reports['2']['equalPhase']['lower']>0,optimizerSteps=0,modelForwards=0,
        selectedCheckpoint=False,individualPhasesDescriptiveOnly=True)
    return report


def verify_effects(subjects, report_path, load_labels):
    expected=build_effects(subjects,load_labels)
    require(canonical(read_json(report_path))==canonical(expected),'Persisted paired VAL arithmetic changed.')
    return dict(passed=True,reportSha256=file_digest(report_path),modelForwards=0,optimizerSteps=0,
        selectedCheckpoint=False)
