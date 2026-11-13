"""Pure terminal verification for the finite matched three-head MC study.

This module never imports a learner, constructs an optimizer, forwards a model or starts native
work. Archived original paths are provenance subjects, resolved through verified member indices.
"""
import hashlib
import json
import math
import gzip
from datetime import datetime
from pathlib import Path

import triple_mc_matched as loop
import triple_mc_matched_storage as storage

PHASES=('expansion','card','purchase')
ARMS=('JOINT','E_ONLY')
PARAMETERS=('stateWeights','stateBias','actionWeights','actionBias','actorWeights')
ACTORS=dict(expansion='aor-expansion-policy-2',card='aor-card-policy-1',purchase='aor-purchase-policy-1')
ESTIMATOR=dict(version='triple-terminal-mc-1',baseline=.5,intermediateReward=0.,forcedRowsIncluded=True,
    weighting='uniform-recorded-decision-mean-per-head',terminalReward='original-complete-world')


def require(value,message):
    if not value:raise ValueError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()


def sha(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1048576),b''):value.update(block)
    return value.hexdigest()


def decode(raw):
    def unique(pairs):
        result={}
        for key,value in pairs:
            require(key not in result,'Duplicate matched evidence field: '+key);result[key]=value
        return result
    return json.loads(raw,object_pairs_hook=unique,
        parse_constant=lambda value:require(False,'Nonfinite matched evidence JSON: '+value))


def finite(value,low=-math.inf,high=math.inf):
    return type(value) in (int,float) and math.isfinite(value) and low<=value<=high


def active_phases(arm):
    require(arm in ARMS,'Only the fixed JOINT and E_ONLY arms exist.')
    return PHASES if arm=='JOINT' else ('expansion',)


def adam_steps(payload,model,config,inherited):
    """Check retained shape/recipe/counters only; do not reproduce optimizer arithmetic."""
    optimizer=payload['optimizer'];groups=optimizer['param_groups'];state=optimizer['state']
    require(len(groups)==1 and groups[0]['params']==list(range(5))
        and set(groups[0]) in ({'params','lr','betas','eps','weight_decay','amsgrad','maximize','foreach','capturable','differentiable','fused'},
            {'params','lr','betas','eps','weight_decay','amsgrad','maximize','foreach','capturable','differentiable','fused','decoupled_weight_decay'})
        and all(type(x) is int for x in groups[0]['params'])
        and groups[0]['lr']==config['learningRate'] and groups[0]['betas']==[.9,.999]
        and groups[0]['eps']==1e-8 and groups[0]['weight_decay']==0
        and all(groups[0][key] is False for key in ('amsgrad','maximize','foreach','capturable','differentiable'))
        and groups[0]['fused'] is None and groups[0].get('decoupled_weight_decay',False) is False,
        'A retained actor must keep the original five-slot Adam recipe.')
    require(set(state)==set(map(str,range(5))) or (not state and payload['epoch']==0 and inherited==0),
        'Accepted or inherited steps require all five original moments.')
    counters=[]
    for index,name in enumerate(PARAMETERS):
        slot=state.get(str(index))
        if slot is None:continue
        require(set(slot)=={'step','exp_avg','exp_avg_sq'},'Unexpected actor-only Adam slot.')
        columns=len(model['stateFeatureNames']) if name=='stateWeights' else len(model['actionFeatureNames']) if name=='actionWeights' else None
        for key in ('exp_avg','exp_avg_sq'):
            item=slot[key];values=item['tensor']
            require(set(item)=={'tensor','dtype'} and item['dtype']=='torch.float64'
                and isinstance(values,list) and len(values)==64,'Original Float64 actor moment dimensions changed.')
            if columns is not None:
                require(all(isinstance(row,list) and len(row)==columns for row in values),'Original actor moment columns changed.')
                values=[value for row in values for value in row]
            require(all(finite(value,0 if key=='exp_avg_sq' else -math.inf) for value in values),
                'Actor moments must be finite; squared moments cannot be negative.')
        step=slot['step']
        require(set(step)=={'tensor','dtype'} and step['dtype']=='torch.float32'
            and finite(step['tensor'],1) and int(step['tensor'])==step['tensor'],'Original Adam step must be a positive whole counter.')
        counters.append(int(step['tensor']))
    require(not counters or len(set(counters))==1 and counters[0]>=inherited,
        'All five actor slots must retain the same nondecreasing cumulative counter.')
    return counters[0] if counters else 0


def phase_metadata(phase,identity,finished,checkpoint,model,original,config,behavior,previous=None):
    """Verify one completed phase and return only compact progress facts."""
    require(phase in PHASES and checkpoint['sha256']==digest(checkpoint['payload']),
        'The original phase checkpoint payload hash changed.')
    payload=checkpoint['payload'];objective=identity['tripleMcObjective']
    for name in PARAMETERS:
        values=model[name];columns=len(model['stateFeatureNames']) if name=='stateWeights' else len(model['actionFeatureNames']) if name=='actionWeights' else None
        require(isinstance(values,list) and len(values)==64,'Actor must retain its original64-unit parameter dimensions.')
        if columns is not None:
            require(all(isinstance(row,list) and len(row)==columns for row in values),'Actor dictionary input dimensions changed.')
            values=[value for row in values for value in row]
        require(all(finite(value) for value in values),'All retained actor parameters must be finite.')
    require(identity['actorVersion']==ACTORS[phase] and identity['config']==config
        and identity['modelSha256']==behavior[phase] and objective['version']=='triple-mc-actor-only-1'
        and objective['phase']==phase and objective['estimator']==ESTIMATOR
        and objective['behaviorModels']==behavior and objective['frozenParameters']==['valueWeights','valueBias'],
        'Phase identity must retain its exact original behavior and detached terminal MC objective.')
    require(payload['schemaVersion']==1 and payload['identitySha256']==finished['identitySha256']==digest(identity)
        and payload['deadlineEpochSeconds']==identity['deadlineEpochSeconds']
        and payload['model']==model and finished['inputModelSha256']==behavior[phase]
        and finished['tripleMcObjective']==objective and finished['history']==payload['history']
        and finished['acceptedEpochs']==payload['epoch'],'Phase report, checkpoint and actor identities differ.')
    require(all(model[key]==original[key] for key in ('valueWeights','valueBias')),
        'Original value-head parameters must remain frozen in every phase.')
    history=payload['history'];stop=payload['stopReason'] or 'epochs-complete'
    require(stop==finished['stopReason'] and stop in ('epochs-complete','behavior-kl-limit')
        and len(history)<=config['epochs'] and [row['epoch'] for row in history]==list(range(1,len(history)+1))
        and all(type(row['accepted']) is bool and finite(row['behaviorKl'])
            and row['accepted']==(row['behaviorKl']<=config['maxBehaviorKl']) for row in history)
        and sum(row['accepted'] for row in history)==payload['epoch'],
        'Only complete original epochs or a whole-epoch KL rejection may publish.')
    require((stop=='epochs-complete' and payload['epoch']==config['epochs'] and all(row['accepted'] for row in history))
        or (stop=='behavior-kl-limit' and history and not history[-1]['accepted'] and all(row['accepted'] for row in history[:-1])),
        'Rejected epochs cannot be followed by hidden accepted steps or a shortened completion.')
    inherited=0 if previous is None else previous['actorAdamSteps']
    continuation=identity.get('tripleMcOptimizerContinuation')
    if previous is None:
        require(continuation is None and identity['optimizerFromSha256'] is None
            and objective['optimizerInitialization']=='fresh-adam','Each arm must start with fresh MC Adam.')
    else:
        require(continuation is not None and continuation['version']=='triple-mc-adam-continuation-1'
            and continuation['phase']==phase and continuation['actorAdamSteps']==inherited
            and continuation['checkpointSha256']==identity['optimizerFromSha256']==previous['checkpointSha256']
            and continuation['previousIdentitySha256']==previous['identityDigest']
            and objective['optimizerInitialization']=='matching-triple-mc-adam'
            and objective['ecology']==previous['ecology'],
            'Carry must retain the immediate same-arm phase checkpoint, counter and ecology.')
    steps=adam_steps(payload,model,config,inherited)
    changed=any(model[key]!=original[key] for key in PARAMETERS)
    require(payload['epoch']>0 or not changed,'A zero-accepted update must preserve the original actor.')
    return dict(acceptedEpochs=payload['epoch'],actorChanged=changed,actorAdamSteps=steps,
        previousActorAdamSteps=inherited,identityDigest=digest(identity),ecology=objective['ecology'])


def equal_original_games(first,second):
    """The sole game omission is the top-level source-derived signature; never omit outcomes."""
    require('signature' in first and 'signature' in second,'Both original first-batch signatures are required.')
    require({k:v for k,v in first.items() if k!='signature'}=={k:v for k,v in second.items() if k!='signature'},
        'The original matched first collections differ beyond their source-derived signature.')


def canonical_identity(value,game_sha,signature,index):
    require(value['kind']=='triple-on-policy-canonical-1' and value['signature']==signature
        and value['index']==index and value['trajectorySha256']==game_sha and value['replayVerified'] is True
        and isinstance(value['header'],dict) and isinstance(value['entries'],list)
        and all(isinstance(entry,str) for entry in value['entries'])
        and value['entriesSha256']==hashlib.sha256(json.dumps(value['entries'],ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()).hexdigest(),
        'Canonical history must bind its own exact original trajectory and complete entries.')
    return dict(header=value['header'],entries=value['entries'])


def documents(index,names):
    """One streamed pass for a small, explicitly bounded metadata set."""
    return {name:decode(gzip.decompress(raw) if name.endswith('.gz') else raw)
        for name,raw in index.iter_members(names=names)}


def subject(index,name):
    item=index.files[name]
    return dict(path=item['originalPath'],sha256=item['sha256'])


def recorded_reward(game):
    """Check the existing public-score label identity; this is not a strength summary."""
    terminal=game['terminal'];scores=terminal['scores']
    require(len(scores)==4 and {s['player'] for s in scores}=={'p1','p2','p3','p4'},
        'Every completed world must retain all four original public score records.')
    for score in scores:
        require(all(type(score[k]) is int and 0<=score[k]<2**31 for k in ('advancePoints','cash'))
            and (score['misery'] is None or type(score['misery']) is int and score['misery']>=0)
            and (score['total'] is None or type(score['total']) is int) and type(score['winner']) is bool
            and score['total']==(None if score['misery'] is None else
                score['advancePoints']+score['cash']-score['misery']), 'Original public score arithmetic changed.')
    totals=[s['total'] for s in scores if s['total'] is not None]
    maximum=max(totals) if totals else None
    require(all(s['winner']==(s['total'] is not None and s['total']==maximum) for s in scores),
        'Original terminal winner flags changed.')
    own=next(s for s in scores if s['player']=='p'+str(game['controlledSeat']+1))
    win=1/sum(s['winner'] for s in scores) if own['winner'] else 0.
    rank=0. if own['total'] is None else math.fsum(1. if s['total'] is None or own['total']>s['total']
        else .5 if own['total']==s['total'] else 0. for s in scores if s is not own)/3
    expected=dict(winShare=win,rankReward=rank,reward=.8*win+.2*rank)
    require(all(finite(terminal[k],0,1) and abs(terminal[k]-v)<1e-12 for k,v in expected.items())
        and type(terminal['gameDecisions']) is int and 0<terminal['gameDecisions']<=12000,
        'Complete-world MC labels differ from their original public scores.')
    return expected['reward']


def inspect_game(game,manifest,index,models):
    """Verify original row identities/seeds without any probability or value forward pass."""
    jm=manifest['joint'];behavior=manifest['controlledBehavior']
    expected=dict(kind='triple-on-policy-trajectory-1',schemaVersion=1,signature=manifest['signature'],
        index=index,configuration=jm['configuration'],gameSeed=jm['seeds'][index],controlledSeat=index%4,
        opponentIndices=[0]*4,bundleSha256=jm['bundle']['sha256'],
        **{p+'ModelSha256':behavior[p] for p in PHASES})
    require(set(game)==set(expected)|{'terminal','jointEvidence',*[p+'Decisions' for p in PHASES]}
        and all(game[k]==v for k,v in expected.items()) and isinstance(game['jointEvidence'],list),
        'Original game identity, complete phases or controlled/rival roles changed.')
    reward=recorded_reward(game);occupied=set();turns=[];counts={};branching={}
    salts=dict(expansion=0x9b05688c,card=0x3c6ef372,purchase=0x6a09e667)
    signed=lambda value:(value+2**31)%2**32-2**31
    for phase in PHASES:
        rows=game[phase+'Decisions'];previous=-1;counts[phase]=len(rows);branching[phase]=False
        require(isinstance(rows,list) and len(rows)<=12000,'Every original bounded phase array, including empty, is required.')
        for row in rows:
            fields={'number','turn','decision','observation','selectedIndex','behaviorLogProbability','behaviorValue','actorSeed'}
            if phase=='expansion':fields|={'batchStartNumber','botSeed'}
            number=row['number'];seed=signed(game['gameSeed']^(number*65537+game['controlledSeat']*8191))
            require(set(row)==fields and type(number) is int and previous<number<game['terminal']['gameDecisions']
                and number not in occupied and type(row['turn']) is int and 1<=row['turn']<=12000
                and row['actorSeed']==signed(seed^salts[phase]) and finite(row['behaviorLogProbability'],-1e6,0)
                and finite(row['behaviorValue'],0,1), 'Original row order, sampler seed or behavior evidence changed.')
            require(row['decision'] in (('expand',) if phase=='expansion' else ('play-cards',) if phase=='card'
                else ('purchase-shipping','purchase-advances')), 'Original row phase changed.')
            if phase=='expansion':require(row['batchStartNumber']==number and row['botSeed']==seed,
                'Expansion must retain the absolute PLAYER-decision seed, not a local row index.')
            obs=row['observation'];actions=obs['actions'];dictionary=models[phase]
            require(set(obs)=={'encoderVersion','stateFeatures','baselineIndex','actions'}
                and obs['encoderVersion']==dictionary['encoderVersion'] and isinstance(actions,list) and actions
                and type(obs['baselineIndex']) is int and 0<=obs['baselineIndex']<len(actions)
                and type(row['selectedIndex']) is int and 0<=row['selectedIndex']<len(actions)
                and set(obs['stateFeatures'])<=set(dictionary['stateFeatureNames'])
                and all(finite(v) for v in obs['stateFeatures'].values()), 'Original full-offer observation changed.')
            require(all(set(action)=={'command','features'} and isinstance(action['command'],str)
                and action['command'] and set(action['features'])<=set(dictionary['actionFeatureNames'])
                and all(finite(v) for v in action['features'].values()) for action in actions),
                'Original action dictionary or safe observation boundary changed.')
            previous=number;occupied.add(number);turns.append((number,row['turn']))
            branching[phase]|=len(actions)>1
    ordered=[turn for _,turn in sorted(turns)]
    require(all(a<=b for a,b in zip(ordered,ordered[1:])), 'Merged phase chronology changed.')
    return reward,counts,branching


def exact_config(config,seed):
    expected=dict(learningRate=.0003,epochs=4,batchSize=64,clip=.2,valueCoefficient=0.,entropyCoefficient=0.,
        maxGradientNorm=.5,maxBehaviorKl=.01,seed=seed,threads=1,maxSeconds=900.,
        cardReferenceKlCoefficient=0.,cardReferenceVersion='aor-card-physical-reference-1')
    require(config==expected,'The fixed matched terminal-MC recipe or original shuffle block changed.')


def check_subject(value,pins):
    require(isinstance(value,dict) and {'path','sha256'}<=set(value)
        and pins.get(value['path'])==value['sha256'], 'Original subject is not an immutable input or archived member.')


def verify_collection(index,meta,protocol,plan,arm,batch,pins,previous):
    manifest=meta['collection/collection.json'];done=meta['collection/finished.json']
    source=meta['collection-source.json'];joint=meta['joint-source.json'];jm=manifest['joint']
    root=Path(index.document['originalRoot']);replay=plan.replay(batch)
    cp=protocol['fixedBundle'] if batch==1 or arm=='E_ONLY' else previous['bundle']
    expansion=protocol['initialExpansion'] if batch==1 else previous['heads']['expansion']
    template=decode(Path(protocol['jointSourceTemplate']['path']).read_bytes())
    expected_joint=dict(template,bundle=cp['path'],games=256,seed=plan.offsets[batch-1],replayIndices=[replay])
    require(joint==expected_joint and source==dict(kind='triple-on-policy-collect-1',
        jointSource=subject(index,'joint-source.json'),expansion=expansion),
        'Collection source must use the exact same-arm predecessor and fixed world/role schedule.')
    expected_heads=dict(expansion=expansion,**(protocol['fixedHeads'] if batch==1 or arm=='E_ONLY'
        else {p:previous['heads'][p] for p in ('card','purchase')}))
    behavior={p:s['sha256'] for p,s in expected_heads.items()}
    require(manifest['kind']==done['kind']=='triple-on-policy-collect-1' and manifest['schemaVersion']==1
        and manifest['trajectoryEncoding']=='gzip-json-v1' and done['complete'] is True and done['games']==256
        and manifest['signature']==done['signature']==jm['signature'] and manifest['controlledBehavior']==behavior
        and manifest['replayIndices']==[replay], 'Only complete original typed triple collections are admitted.')
    started=meta['collection/started.json']
    require(set(started)=={'signature','workers','startedAt','deadlineAt'} and started['signature']==manifest['signature']
        and started['workers']==plan.workers and 0<(datetime.fromisoformat(started['deadlineAt'].replace('Z','+00:00'))-
            datetime.fromisoformat(started['startedAt'].replace('Z','+00:00'))).total_seconds()<=7200,
        'Native collection must retain its exact declared worker count and original bounded stage clock.')
    require(jm['games']==256 and jm['seed']==plan.offsets[batch-1]
        and jm['seeds']==[-1000000000+plan.offsets[batch-1]+i*8191 for i in range(256)]
        and jm['source']==str(root/'joint-source.json') and jm['sourceSha256']==index.files['joint-source.json']['sha256']
        and jm['configuration']==template['configuration'] and jm['kind']=='joint-collect' and jm['schemaVersion']==1
        and jm['mode']=='on-policy' and jm['trajectoryEncoding']=='gzip-json-v1'
        and jm['controlledSeat']=='index % playerCount' and jm['opponentRotation']=='(index / playerCount + seat) % opponentCount'
        and jm['reward']=='.8 * winShare + .2 * rankReward' and jm['replayIndices']==[replay],
        'Native collection schedule or typed joint identity changed.')
    expected_snapshots={'source.json':subject(index,'collection-source.json'),
        'joint/source.json':subject(index,'joint-source.json'),'expansion.json':expansion,
        'joint/bundle.json':cp,**{'joint/'+p+'-policy.json':expected_heads[p] for p in ('card','purchase')},
        'joint/opponent-0-bundle.json':protocol['fixedBundle'],
        **{'joint/opponent-0-'+p+'-policy.json':protocol['fixedHeads'][p] for p in ('card','purchase')}}
    def pinned(path):
        require(Path(path).is_absolute() and path in protocol['files'],'Ecology source paths must be explicit frozen inputs.')
        return dict(path=path,sha256=protocol['files'][path])
    expected_snapshots.update({'joint/baseline.json':pinned(template['baseline']),
        'joint/shared-purchase-baseline.json':pinned(template['sharedPurchaseBaseline']),
        'joint/expansion-imitation-candidate.json':pinned(template['expansionImitation']['candidateModel']),
        'joint/expansion-imitation-opponent-0.json':pinned(template['expansionImitation']['opponentModels'][0])})
    require(manifest['snapshots']==expected_snapshots,'Every original ordinary/prior/E/controlled/rival snapshot is required.')
    collection_files={'collection/collection.json','collection/finished.json','collection/started.json',
        f'collection/replay-{replay}.json'}|{'collection/'+n for n in expected_snapshots}|{
        f'collection/game-{i}.json.gz' for i in range(256)}
    require({n for n in index.files if n.startswith('collection/')}==collection_files
        and {n for n in index.files if n.startswith('reproduction/')}=={'reproduction/replay.json'}
        and {n for n in index.files if '/' not in n}==storage.TOP_FILES,
        'A completed capsule must retain exactly its original collection, prescribed reproduction and source/log evidence.')
    for name,value in expected_snapshots.items():
        check_subject(value,pins)
        require(index.files['collection/'+name]['sha256']==value['sha256'],'Archived snapshot differs from original subject: '+name)
    for field,name in (('source','source.json'),('jointSource','joint/source.json'),('expansion','expansion.json')):
        require({k:manifest[field][k] for k in ('path','sha256')}==expected_snapshots[name]
            and manifest[field]['snapshot']==name,'Native source descriptor differs from its owned snapshot.')
    require({k:jm['bundle'][k] for k in ('path','sha256')}==cp
        and len(jm['opponents'])==1 and {k:jm['opponents'][0][k] for k in ('path','sha256')}==protocol['fixedBundle']
        and jm['baseline']==expected_snapshots['joint/baseline.json']
        and jm['sharedPurchaseBaseline']==dict(expected_snapshots['joint/shared-purchase-baseline.json'],snapshot='shared-purchase-baseline.json')
        and jm['tokenOrder']==template['tokenOrder'] and jm['continuationProtocol']==template['continuationProtocol']
        and jm['jointStateChronology']==template['jointStateChronology'], 'Original G3/M8/F0/E/T role composition changed.')
    for phase in ('card','purchase'):
        require({k:jm['bundle'][phase][k] for k in ('path','sha256')}==expected_heads[phase]
            and {k:jm['opponents'][0][phase][k] for k in ('path','sha256')}==protocol['fixedHeads'][phase],
            'Controlled or fixed rival component changed.')
    overlays=jm['expansionImitation']
    require(overlays['candidateModel']==dict(expected_snapshots['joint/expansion-imitation-candidate.json'],snapshot='expansion-imitation-candidate.json')
        and overlays['opponentModels']==[dict(expected_snapshots['joint/expansion-imitation-opponent-0.json'],snapshot='expansion-imitation-opponent-0.json')],
        'All indexed authoritative expansion references must remain exact.')
    names={f'game-{i}.json.gz' for i in range(256)}
    require(set(done['gameSha256'])==names and {n.removeprefix('collection/') for n in index.files if n.startswith('collection/game-')}==names
        and all(done['gameSha256'][n]==index.files['collection/'+n]['sha256'] for n in names)
        and done['canonicalSha256']=={f'replay-{replay}.json':index.files[f'collection/replay-{replay}.json']['sha256']},
        'Exactly the original complete world bank and prescribed canonical history must be retained.')
    return manifest,behavior


def verify_batch(index,receipt,protocol,plan,pins,previous):
    arm,batch=receipt['arm'],receipt['batch'];active=active_phases(arm);root=Path(index.document['originalRoot'])
    names=['collection-source.json','joint-source.json','collection/collection.json','collection/finished.json','collection/started.json',
        'reproduction/replay.json','numeric-complete.json','numeric/identity.json','numeric/finished.json',
        'numeric/triple-bundle.json',f'collection/replay-{plan.replay(batch)}.json']
    if arm=='JOINT':names.append('numeric/bundle.json')
    meta=documents(index,names);manifest,behavior=verify_collection(index,meta,protocol,plan,arm,batch,pins,previous)
    jm=manifest['joint'];numeric=meta['numeric/identity.json'];done=meta['numeric/finished.json']
    kind='triple-mc-update-1' if arm=='JOINT' else 'triple-mc-e-only-control-1'
    require(numeric['kind']==done['kind']==kind and done['complete'] is True
        and done['identitySha256']==digest(numeric) and numeric['behaviorModels']==behavior
        and numeric['maximumAttempts']==1 and numeric['automaticResume'] is False
        and done['allInputHashesUnchanged'] is True and done['strengthClaim'] is False and done['policyPromotion'] is False,
        'The original atomic numerical transaction changed.')
    require((set(numeric['configs'])==set(PHASES) if arm=='JOINT' else 'configs' not in numeric),
        'Only the explicitly active phase configurations may enter the transaction.')
    previous_completion=None if previous is None else previous['completion']
    previous_checkpoints={} if previous is None else {p:previous['checkpoints'][p] for p in active}
    require(receipt['previousCompletion']==previous_completion and receipt['previousCheckpoints']==previous_checkpoints
        and numeric['previous']==(None if previous is None else dict(path=str(Path(previous_completion['path']).parent),
            completionSha256=previous_completion['sha256'])), 'Carry must consume exactly the immediate same-arm complete transaction.')
    require(numeric['inputHashes'] and all(pins.get(path)==value for path,value in numeric['inputHashes'].items()),
        'An original numeric input is neither frozen nor preserved in a verified capsule.')
    expected_ecology=dict(configuration=jm['configuration'],ordinary=dict(jm['baseline'],snapshot='baseline.json'),
        purchasePrior=jm['sharedPurchaseBaseline'],expansionReference=jm['expansionImitation'],
        tokenOrder=jm['tokenOrder'],rules=meta['joint-source.json']['experimentalRulesTransfer'],
        rivalBundle=jm['opponents'][0],dictionaries={p:{k:(manifest['expansion'] if p=='expansion' else jm['bundle'][p])[k]
            for k in ('encoderVersion','stateFeatureNames','actionFeatureNames')} for p in PHASES})
    require(numeric['ecology']==expected_ecology,'Numerical carry ecology must be the actual native source, priors, roles and dictionaries.')
    expected_outputs={f'{p}/{name}.json' for p in active for name in ('identity','finished','policy','checkpoint')}|{'triple-bundle.json'}
    if arm=='JOINT':expected_outputs.add('bundle.json')
    require(set(done['phases'])==set(active) and set(done['outputHashes'])==expected_outputs
        and all(index.files['numeric/'+name]['sha256']==value for name,value in done['outputHashes'].items()),
        'Atomic completion must bind every exact active phase and bundle; E_ONLY cannot manufacture C/P updates.')
    require({n for n in index.files if n.startswith('numeric/')}==({'numeric/'+n for n in expected_outputs}|{
        'numeric/identity.json','numeric/finished.json'}|{f'numeric/{p}/epoch-started.json' for p in active}),
        'Unexpected or partial numerical output is not a completed transaction.')
    dictionaries=dict(expansion=manifest['expansion'],card=jm['bundle']['card'],purchase=jm['bundle']['purchase'])
    totals=dict.fromkeys(PHASES,0);branching=dict.fromkeys(PHASES,0);rewards=[];origins=[];seen=set()
    for name,raw in index.iter_members(names=['collection/game-'+str(i)+'.json.gz' for i in range(256)]):
        i=int(Path(name).name[5:-8]);require(i not in seen,'Duplicate original world.');seen.add(i)
        game=decode(gzip.decompress(raw));reward,counts,branches=inspect_game(game,manifest,i,dictionaries)
        rewards.append(reward);origins.append(dict(index=i,gameSeed=jm['seeds'][i],controlledSeat=i%4,
            **{p+'Decisions':counts[p] for p in PHASES}))
        for p in PHASES:totals[p]+=counts[p];branching[p]+=int(branches[p])
    require(seen==set(range(256)),'Every original complete world, including empty phase worlds, is required.')
    common=numeric['collection'];game_hashes=[index.files[f'collection/game-{i}.json.gz']['sha256'] for i in range(256)]
    require(common['collectionSha256']==index.files['collection/collection.json']['sha256']
        and common['completionSha256']==index.files['collection/finished.json']['sha256']
        and common['collectionSignature']==manifest['signature'] and common['gameHashes']==game_hashes
        and common['games']==256 and common['behaviorModels']==behavior
        and common['originCoverage']==sorted(origins,key=lambda value:value['index'])
        and abs(common['gameMeanReward']-math.fsum(rewards)/256)<1e-12
        and common['collectionReaderSha256']==common['readerSha256']
        and common['readerSha256'] in numeric['inputHashes'].values(), 'Original all-phase row/terminal provenance changed.')
    del rewards,origins
    replay=plan.replay(batch);canonical_name=f'collection/replay-{replay}.json';canonical=meta[canonical_name]
    canonical_identity(canonical,game_hashes[replay],manifest['signature'],replay)
    repro=meta['reproduction/replay.json']
    require(repro['kind']=='triple-on-policy-reproduction-1' and repro['signature']==manifest['signature']
        and repro['index']==replay and repro['passed'] is True and repro['completeTypedRecordsEqual'] is True
        and repro['completeCanonicalEqual'] is True and repro['trajectorySha256']==game_hashes[replay]
        and repro['canonicalSha256']==index.files[canonical_name]['sha256'],
        'Prescribed reproduction must compare the complete original typed world and canonical history.')
    phases={};heads={};checkpoints={};progress={}
    for p in active:
        exact_config(numeric['configs'][p] if arm=='JOINT' else numeric['config'],plan.shuffles[batch-1][p])
        config=numeric['configs'][p] if arm=='JOINT' else numeric['config']
        original_name='collection/expansion.json' if p=='expansion' else 'collection/joint/'+p+'-policy.json'
        phase_names=[f'numeric/{p}/{name}.json' for name in ('identity','finished','checkpoint','policy','epoch-started')]+[original_name]
        values=documents(index,phase_names);identity=values[f'numeric/{p}/identity.json']
        finished=values[f'numeric/{p}/finished.json'];checkpoint=values[f'numeric/{p}/checkpoint.json']
        model=values[f'numeric/{p}/policy.json'];original=values[original_name]
        result=phase_metadata(p,identity,finished,checkpoint,model,original,config,behavior,
            None if previous is None else previous['phases'][p])
        require(values[f'numeric/{p}/epoch-started.json']==dict(kind=p+'-epoch-started',identitySha256=digest(identity)),
            'Every active phase must retain its original once-only epoch-started marker.')
        objective=identity['tripleMcObjective'];check_subject(identity['collectionReaderSource'],pins)
        require(objective['ecology']==numeric['ecology'] and objective['collectionSha256']==common['collectionSha256']
            and objective['gameHashes']==game_hashes and identity['data']==dict(common,phase=p,
                behaviorModelSha256=behavior[p],readerSha256=identity['collectionReaderSource']['sha256'])
            and identity['tripleMcOwnerSha256']==identity['collectionReaderSource']['sha256']
            and all(identity[key] in numeric['inputHashes'].values() for key in ('learnerSha256','entrypointSha256','tripleMcOwnerSha256'))
            and finished['games']==256 and finished['decisions']==totals[p]>0
            and abs(finished['gameMeanReward']-common['gameMeanReward'])<1e-12
            and finished['outputModelSha256']==index.files[f'numeric/{p}/policy.json']['sha256'],
            'Typed phase must use original full behavior rows, detached MC labels and the committed owner sources.')
        require(set(model)==set(original) and all(model[k]==original[k] for k in model if k not in PARAMETERS),
            'Only the five original actor parameter arrays may change, never priors/dictionaries/value heads.')
        summary=dict(policySha256=index.files[f'numeric/{p}/policy.json']['sha256'],
            checkpointSha256=index.files[f'numeric/{p}/checkpoint.json']['sha256'],
            identitySha256=index.files[f'numeric/{p}/identity.json']['sha256'],
            completionSha256=index.files[f'numeric/{p}/finished.json']['sha256'],decisions=totals[p],
            acceptedEpochs=finished['acceptedEpochs'],stopReason=finished['stopReason'])
        require(done['phases'][p]==summary,'Atomic phase summary differs from its original committed files.')
        progress[p]={key:result[key] for key in ('acceptedEpochs','actorChanged','actorAdamSteps','previousActorAdamSteps')}
        progress[p].update(decisions=totals[p],branchingWorlds=branching[p])
        phases[p]=dict(result,checkpointSha256=summary['checkpointSha256'])
        heads[p]=subject(index,f'numeric/{p}/policy.json');checkpoints[p]=subject(index,f'numeric/{p}/checkpoint.json')
        del values,checkpoint,model,original
    bundle=subject(index,'numeric/bundle.json') if arm=='JOINT' else protocol['fixedBundle']
    baseline=jm['bundle']['baselineSha256']
    require(meta['numeric/triple-bundle.json']==dict(schemaVersion=1,kind='triple-actor-bundle-1',
        configuration=jm['configuration'],baselineSha256=baseline,expansion=heads['expansion'],jointBundle=bundle),
        'The atomic public triple bundle must bind its exact complete phase outputs.')
    if arm=='JOINT':
        require(meta['numeric/bundle.json']==dict(schemaVersion=1,kind='joint-card-purchase-1',
            configuration=jm['configuration'],baselineSha256=baseline,**{p:heads[p] for p in ('card','purchase')}),
            'Joint C/P output must contain both exact heads from this transaction.')
    else:
        require(numeric['frozenCardPurchase']==done['frozenCardPurchase']==jm['bundle'],
            'E_ONLY must retain the complete exact fixed G3 C/P descriptor and no C/P carry.')
    update=meta['numeric-complete.json']
    require(update==dict(kind='triple-mc-matched-numeric-1',arm=arm,batch=batch,
        numericCompletionSha256=index.files['numeric/finished.json']['sha256'],phases=progress,
        outputBundle=subject(index,'numeric/triple-bundle.json'),complete=True)
        and receipt['phases']==progress and receipt['numericCompletionSha256']==update['numericCompletionSha256']
        and receipt['outputBundle']==update['outputBundle'] and receipt['sourceSha256']==index.files['collection-source.json']['sha256']
        and receipt['jointSourceSha256']==index.files['joint-source.json']['sha256'],
        'Batch publication must retain the original transaction and independently derived progress/count facts.')
    return dict(phases=phases,heads=heads,checkpoints=checkpoints,bundle=bundle,
        completion=subject(index,'numeric/finished.json'),triple=subject(index,'numeric/triple-bundle.json'))


def terminal_contract(plan,finished,receipts,prefix):
    """Recompute schedule and operational decisions, never strength or outcome effects."""
    expected=[(a,i) for i in range(1,33) for a in plan.order(i)]
    require([(r['arm'],r['batch']) for r in receipts]==expected[:len(receipts)],
        'Completed transactions must be a prefix of the fixed alternating sequential schedule.')
    counts={a:sum(r['arm']==a for r in receipts) for a in ARMS};reason=finished['stopReason']
    require(finished['kind']==loop.VERSION and finished['completedBatches']==counts
        and finished['scientificWorlds']==len(receipts)*256 and finished['historicalReproductions']==len(receipts)
        and finished['complete']==finished['eligibleFinalActors']==(reason is None)
        and finished['qualification'] is False and finished['automaticSuccessor'] is False
        and reason in (None,'operational-prefix-gate','four-no-progress-batches','exception'),
        'Terminal state must report its honest original count and fixed stop reason.')
    require(reason is not None or counts==dict.fromkeys(ARMS,32), 'Only both complete final32 transactions are eligible.')
    stalls={a:{p:0 for p in active_phases(a)} for a in ARMS};hit=None
    for ordinal,receipt in enumerate(receipts):
        arm=receipt['arm']
        for p in active_phases(arm):
            stalls[arm][p]=0 if loop.learning_progress(receipt['phases'][p]) else stalls[arm][p]+1
        if any(n>=4 for rows in stalls.values() for n in rows.values()):
            require(hit is None,'The study continued after its first no-progress stop.');hit=ordinal
    require((hit==len(receipts)-1 if reason=='four-no-progress-batches' else hit is None),
        'Four no-progress batches must stop both arms immediately.')
    reached_prefix=len(receipts)>=8 and not (hit is not None and hit<=7)
    if reached_prefix and not (reason=='exception' and prefix is None and len(receipts)==8):
        require(prefix is not None and prefix['kind']=='triple-mc-operational-prefix-1'
            and prefix['qualityInspected'] is False and finite(prefix['remainingSeconds'],0,plan.maximum_seconds),
            'Original paired-prefix measurements must precede every later batch.')
        require(prefix['passed'] is plan.prefix_passed(receipts[:8],prefix['remainingSeconds'],prefix['roundSeconds']),
            'Recorded prefix admission differs from the fixed measured-cost/exposure gate.')
        require(prefix['passed'] or reason=='operational-prefix-gate' and len(receipts)==8,
            'A failed operational prefix cannot continue one or both arms.')
    else:require(prefix is None,'No operational-prefix receipt exists before its original decision boundary.')
    if reason=='operational-prefix-gate':require(prefix is not None and not prefix['passed'] and len(receipts)==8,
        'A prefix stop requires exactly four complete paired rounds and a failed measured gate.')
    require(finished['finalBundles']=={} or reason is None,'A shortened run cannot publish eligible final bundles.')
    return counts


def verify(protocol,run):
    """Audit a terminal matched run through immutable indices, one game/checkpoint at a time."""
    plan=loop.LoopPlan.read(protocol);run=Path(run).resolve(strict=True)
    finished_path=run/'finished.json';finished=decode(finished_path.read_bytes())
    pins=dict(protocol['files']);artifacts={str(finished_path):sha(finished_path)};indices={};receipts={}
    counts=finished['completedBatches']
    require(set(counts)==set(ARMS) and all(type(n) is int and 0<=n<=32 for n in counts.values()),
        'Both explicit finite arm counts are required.')
    if finished['complete']:complete_inventory(run,counts)
    for arm in ARMS:
        expected_names={f'batch-{i:02d}.json' for i in range(1,counts[arm]+1)}
        directory=run/'arms'/arm/'receipts'
        require({p.name for p in directory.glob('*.json')}==expected_names,'No missing, extra or skipped completed receipt is allowed.')
        for batch in range(1,counts[arm]+1):
            receipt_path=directory/f'batch-{batch:02d}.json';receipt=decode(receipt_path.read_bytes());cap=receipt['capsule']
            archive=run/'arms'/arm/'archives'/f'batch-{batch:02d}.tar.gz';index=storage.BatchIndex(archive)
            require(receipt['kind']=='triple-mc-matched-batch-1' and receipt['arm']==arm and receipt['batch']==batch
                and receipt['complete'] is True and receipt['worlds']==256 and receipt['reproductions']==1
                and index.document['arm']==arm and index.document['batch']==batch
                and index.document['originalRoot']==str(loop.batch_path(run,arm,batch))
                and cap==dict(path=str(archive),sha256=index.document['archiveSha256'],indexPath=str(index.path),indexSha256=index.index_sha)
                and finite(receipt['elapsedSeconds'],0) and type(receipt['retainedBytes']) is int
                and receipt['retainedBytes']==archive.stat().st_size+index.path.stat().st_size,
                'Completed receipt must bind its one original arm, batch, capsule and measured bytes.')
            for path,value in index.original_hashes.items():
                require(path not in pins or pins[path]==value,'One original path cannot acquire different historical bytes.')
                pins[path]=value
            artifacts.update({str(receipt_path):sha(receipt_path),str(archive):cap['sha256'],str(index.path):cap['indexSha256']})
            indices[arm,batch]=index;receipts[arm,batch]=receipt
    ordered=[receipts[a,i] for i in range(1,33) for a in plan.order(i) if (a,i) in receipts]
    receipt_hashes={a:{str(i):artifacts[str(run/'arms'/a/'receipts'/f'batch-{i:02d}.json')]
        for i in range(1,counts[a]+1)} for a in ARMS}
    require(finished['receiptSha256']==receipt_hashes,'Terminal receipt inventory changed.')
    prefix_path=run/'operational-prefix.json';prefix=decode(prefix_path.read_bytes()) if prefix_path.exists() else None
    terminal_contract(plan,finished,ordered,prefix)
    if prefix is not None:
        require(prefix['receiptSha256']=={a:{str(i):receipt_hashes[a][str(i)] for i in range(1,5)} for a in ARMS},
            'Prefix timing/admission must bind the original eight completed batch receipts.')
        artifacts[str(prefix_path)]=sha(prefix_path)
    last={a:None for a in ARMS}
    for receipt in ordered:
        arm,batch=receipt['arm'],receipt['batch'];index=indices[arm,batch]
        last[arm]=verify_batch(index,receipt,protocol,plan,pins,last[arm])
        loose=Path(index.document['originalRoot'])
        if loose.exists():
            actual=storage.members(loose)
            require(set(actual)<=set(index.files) and all(actual[n]==index.files[n] for n in actual),
                'A retained loose duplicate differs from its verified immutable capsule.')
        if finished['complete'] and batch==32:
            require(loose.exists() and {n for n in actual if n.startswith('numeric/')}==
                {n for n in index.files if n.startswith('numeric/')},
                'Both complete final32 transactions must retain all unconsumed loose numerical carry.')
        release_path=run/'arms'/arm/'releases'/f'batch-{batch:02d}.json'
        if batch<counts[arm]:
            next_path=run/'arms'/arm/'receipts'/f'batch-{batch+1:02d}.json';next_receipt=receipts[arm,batch+1]
            require(release_path.is_file(),'Every consumed predecessor carry needs its own verified release receipt.')
            release=decode(release_path.read_bytes())
            require(release==dict(kind='triple-mc-carry-release-1',arm=arm,batch=batch,
                capsuleSha256=index.document['archiveSha256'],consumedBy=dict(path=str(next_path),sha256=sha(next_path)),
                previousCompletion=next_receipt['previousCompletion'],previousCheckpoints=next_receipt['previousCheckpoints'],
                verifiedDuplicatesRemoved=True), 'Carry deletion must bind its actual next same-arm completed consumer.')
            artifacts[str(release_path)]=sha(release_path)
        else:require(not release_path.exists(),'Latest unconsumed carry must not have a fabricated consumer.')
    parity_path=run/'first-collection-parity.json'
    if all(counts[a]>=1 for a in ARMS):
        require(parity_path.is_file() or finished['stopReason']=='exception' and len(ordered)==2,
            'Original first-collection parity must precede every later round.')
        if parity_path.is_file():
            require(decode(parity_path.read_bytes())==loop.first_collection_parity({a:indices[a,1] for a in ARMS}),
                'Persisted first-collection parity differs from complete original archived records.')
            artifacts[str(parity_path)]=sha(parity_path)
    else:require(not parity_path.exists(),'First-collection parity cannot precede both initial arms.')
    if finished['complete']:
        require(finished['finalBundles']=={a:last[a]['triple'] for a in ARMS},'Eligible finals must be exact batch32 outputs.')
    failure=run/'failure.json'
    if finished['stopReason']=='exception':
        require(failure.is_file(),'A failed run must preserve its original failure receipt.')
        value=decode(failure.read_bytes());require(value['kind']==loop.VERSION and value['completedBatches']==len(ordered)
            and value['automaticRetry'] is False and value['eligibleFinalActors'] is False,'Original failure state changed.')
        artifacts[str(failure)]=sha(failure)
    else:require(not failure.exists(),'A successful or operationally stopped run cannot conceal an exception receipt.')
    require(all(sha(path)==value for path,value in artifacts.items())
        and all(sha(path)==value for path,value in protocol['files'].items()),'Evidence or frozen inputs changed during terminal audit.')
    return dict(kind='triple-mc-matched-terminal-audit-1',passed=True,complete=finished['complete'],
        completedBatches=counts,scientificWorlds=finished['scientificWorlds'],historicalReproductions=finished['historicalReproductions'],
        eligibleFinalActors=finished['eligibleFinalActors'],stopReason=finished['stopReason'],
        finalBundles=finished['finalBundles'],artifactPins=artifacts,newInference=0,newFits=0,newGames=0,
        qualityInspected=False)


def complete_inventory(run,counts):
    """Full completion has a closed inventory; partial failures keep their additional diagnostics."""
    def names(path):
        require(path.is_dir() and not path.is_symlink(),'Completed evidence directory is missing or substituted.')
        require(all(not child.is_symlink() for child in path.iterdir()),'Completed evidence cannot contain symlinks.')
        return {child.name for child in path.iterdir() if child.name!='.DS_Store'}
    require(counts==dict.fromkeys(ARMS,32) and names(run)=={'arms','finished.json','first-collection-parity.json','operational-prefix.json'}
        and names(run/'arms')==set(ARMS),'Complete matched output contains missing or unowned top-level evidence.')
    for arm in ARMS:
        root=run/'arms'/arm
        require(names(root)=={'batches','archives','receipts','releases'}
            and names(root/'batches')=={f'batch-{i:02d}' for i in range(1,33)}
            and names(root/'archives')=={f'batch-{i:02d}.tar.gz'+suffix for i in range(1,33) for suffix in ('','.json')}
            and names(root/'receipts')=={f'batch-{i:02d}.json' for i in range(1,33)}
            and names(root/'releases')=={f'batch-{i:02d}.json' for i in range(1,32)},
            'Completed arm must contain exactly32 original capsules/receipts/batches and31 consumed carry releases.')
