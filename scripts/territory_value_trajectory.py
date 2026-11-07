"""Complete native Q trajectories, whole-game replay and atomic online/target/Adam pairs.

The reader hashes all compressed origins before any decoding, including when rebuilding only
retained replay indices. Observations and terminal rewards never cross game boundaries.
"""
from dataclasses import asdict
from pathlib import Path
import math
import sys

import purchase_policy as backend
import territory_value as q
import time
import copy
import sys
import resource
import joint_trajectory as joint
from joint_economy import terminal_metrics

PHASES=('token','expansion')
CONTRACTS=q.CONTRACTS
CONFIGURATION=joint.CONFIGURATION
require,read,sha=backend.require,backend.read_json,backend.file_digest
pin,unchanged,resolve,immutable_json=joint.pin,joint.unchanged,joint.resolved,joint.immutable_json


def signed(value):return (value+2**31)%2**32-2**31


def bot_seed(world,batch_start,seat):
    return signed(world ^ (batch_start*65537+seat*8191))


def load_bundle(path,configuration,baseline_sha,frozen_sha,pins,controlled=True):
    path=pin(path,pins);raw=read(path)
    require(set(raw)=={'schemaVersion','kind','configuration','baselineSha256',
                       'frozenCardPurchaseBundleSha256','token','expansion'}
            and raw['schemaVersion']==1 and raw['kind']=='territory-token-expansion-values-1'
            and raw['configuration']==configuration and raw['baselineSha256']==baseline_sha
            and raw['frozenCardPurchaseBundleSha256']==frozen_sha,
            'Territory bundle must bind its exact setup, F prior and frozen card/purchase actors.')
    descriptor=dict(path=str(path),sha256=sha(path),kind=raw['kind'],configuration=configuration,
        baselineSha256=baseline_sha,frozenCardPurchaseBundleSha256=frozen_sha)
    models={}
    for phase in PHASES:
        value=raw[phase]
        if value is None:
            require(not controlled,'A collecting territory bundle requires both typed actors.')
            descriptor[phase]=None
            continue
        require(isinstance(value,dict) and set(value)=={'path','sha256'},
                'A territory actor requires an exact model path and byte hash.')
        model_path=pin(resolve(value['path'],path.parent),pins)
        require(sha(model_path)==value['sha256'],'Territory actor differs from its bundle hash.')
        contract=CONTRACTS[phase]
        model=q.validate_model(read(model_path),phase);models[phase]=model
        descriptor[phase]=dict(path=str(model_path),sha256=sha(model_path),encoderVersion=contract.encoder,
            selectorVersion=q.SELECTORS[phase],architecture=q.ARCHITECTURE,stateFeatureNames=model['stateFeatureNames'],actionFeatureNames=model['actionFeatureNames'])
    return descriptor,models


class ValueCollection:
    def __init__(self,directory,bundle_path,indices=None):
        self.directory=Path(directory).resolve(strict=True);self.pins={};root=self.directory
        for name in ('collection.json','finished.json','state.json','source.json'):pin(root/name,self.pins)
        manifest,done,state,snapshot=(read(root/name) for name in
            ('collection.json','finished.json','state.json','source.json'))
        require(manifest['kind']==done['kind']==state['kind']=='territory-value-collect'
                and manifest['schemaVersion']==1 and state['phase']=='complete'
                and manifest['signature']==done['signature']==state['signature']
                and isinstance(manifest['signature'],str) and manifest['signature']
                and manifest['mode']=='epsilon-greedy' and manifest['trajectoryEncoding']=='gzip-json-v1',
                'Both territory phases require one completely finished native collection.')
        require(manifest['configuration']==CONFIGURATION,'Territory learning currently requires the qualified four-player setup.')
        source=pin(manifest['source'],self.pins)
        require(sha(source)==manifest['sourceSha256'] and source.read_bytes()==(root/'source.json').read_bytes()
                and snapshot['schemaVersion']==1 and snapshot['kind']=='territory-value-collect'
                and snapshot['epsilon']==manifest['epsilon']==.20,
                'Territory source bytes and snapshot must agree.')
        count=backend.integer(manifest['games'],'Territory games',1,4096)
        offset=backend.integer(snapshot['seed'],'Territory seed offset',0,100000000)
        require(done['games']==snapshot['games']==state['completedGames']==count
                and manifest['seed']==offset and manifest['configuration']==snapshot['configuration']
                and manifest['seeds']==[-1000000000+offset+i*8191 for i in range(count)],
                'Territory collection must retain all exact assigned game origins.')
        baseline=pin(resolve(snapshot['baseline'],source.parent),self.pins)
        require(manifest['baseline']==dict(path=str(baseline),sha256=sha(baseline)),'Territory F prior changed.')
        self._snapshot(baseline,'baseline.json')
        frozen_path=resolve(snapshot['frozenCardPurchaseBundle'],source.parent)
        self.frozen,_=joint.load_bundle(frozen_path,CONFIGURATION,sha(baseline),self.pins,controlled=False)
        require(manifest['frozenCardPurchaseBundle']==self.frozen,'Frozen card/purchase descriptor changed.')
        self._snapshot(self.frozen['path'],'frozen-card-purchase-bundle.json')
        for phase in joint.PHASES:
            if self.frozen[phase] is not None:self._snapshot(self.frozen[phase]['path'],f'frozen-{phase}-policy.json')
        own=resolve(snapshot['bundle'],source.parent)
        require(own==Path(bundle_path).resolve(strict=True),'Only the collecting territory bundle may be trained.')
        self.bundle,self.models=load_bundle(own,CONFIGURATION,sha(baseline),self.frozen['sha256'],self.pins)
        require(manifest['bundle']==self.bundle,'Territory phase versions, models or dictionaries changed.')
        self._bundle_snapshots(self.bundle,'bundle.json','')
        opponents=[]
        require(isinstance(snapshot['opponents'],list) and 1<=len(snapshot['opponents'])<=16,
                'Territory collection requires a bounded frozen opponent pool.')
        for index,path in enumerate(snapshot['opponents']):
            opponent,_=load_bundle(resolve(path,source.parent),CONFIGURATION,sha(baseline),self.frozen['sha256'],
                                  self.pins,controlled=False)
            opponents.append(opponent);self._bundle_snapshots(opponent,f'opponent-{index}-bundle.json',f'opponent-{index}-')
        require(manifest['opponents']==opponents,'Territory rival bundles changed.')
        paths=[root/f'game-{index}.json.gz' for index in range(count)]
        hashes=[sha(pin(path,self.pins)) for path in paths]
        require(done['gameSha256']==hashes and set(root.glob('game-*'))==set(paths),
                'Bind every compressed game hash before decoding either territory phase or outcome.')
        self.rows={phase:[] for phase in PHASES};self.origins=[];self.games=[];rewards=[]
        if indices is None: indices=list(range(count))
        require(isinstance(indices,list) and indices==sorted(set(indices))
                and all(type(i) is int and 0<=i<count for i in indices),
                'Replay requests exact distinct existing source indices.')
        actors={phase:q.ActionValue(model,phase) for phase,model in self.models.items()}
        for index in indices:
            path=paths[index]
            game=backend.read_game(path,CONTRACTS['token'])
            expected=dict(schemaVersion=1,signature=manifest['signature'],index=index,configuration=CONFIGURATION,
                gameSeed=manifest['seeds'][index],controlledSeat=index%4,
                opponentIndices=[(index//4+seat)%len(opponents) for seat in range(4)],
                bundleSha256=self.bundle['sha256'],frozenCardPurchaseBundleSha256=self.frozen['sha256'],
                tokenModelSha256=self.bundle['token']['sha256'],expansionModelSha256=self.bundle['expansion']['sha256'])
            require(all(game.get(key)==value for key,value in expected.items()),'Territory game provenance differs from its collection.')
            reward=terminal_metrics(game)['reward'];rewards.append(reward)
            counts={};occupied=set();turns=[];merged=[]
            for phase in PHASES:
                entries=game.get(phase+'Decisions')
                require(isinstance(entries,list) and len(entries)<=12000,'Retain each bounded typed array, including empty arrays.')
                counts[phase]=len(entries);previous=-1
                for row in entries:
                    number=backend.integer(row['number'],'Territory decision number',0,11999)
                    batch=backend.integer(row['batchStartNumber'],'Territory batch start',0,number)
                    require(number>previous and number not in occupied and number<game['terminal']['gameDecisions'],
                            'Typed territory decisions must be ordered and globally disjoint.')
                    require((phase=='token' and number-batch<=3) or (phase=='expansion' and batch==number),
                            'Sealed token decisions retain their true batch start; expansion is sequential.')
                    backend.integer(row['botSeed'],'Territory bot seed',-(2**31),2**31-1)
                    backend.integer(row['selectorSeed'],'Territory actor seed',-(2**31),2**31-1)
                    expected_bot=bot_seed(game['gameSeed'],batch,game['controlledSeat'])
                    require(row['botSeed']==expected_bot and row['selectorSeed']==signed(expected_bot^q.SALTS[phase]),
                            'Use actual sealed-batch policy seed and the exact typed actor salt.')
                    require(row['decision']==('buy-tokens' if phase=='token' else 'expand'),'A typed row belongs to another phase.')
                    previous=number;occupied.add(number)
                    turns.append((number,backend.integer(row['turn'],'Territory turn',1,12000)))
                    prepared=actors[phase].encoder.prepare(row['observation'])
                    backend.integer(row['selectedIndex'],'Territory selected action',0,len(row['observation']['actions'])-1)
                    require(set(row)=={'number','batchStartNumber','botSeed','selectorSeed','decision','turn',
                        'observation','selectedIndex','selectedQ','behaviorProbability'},'Unknown Q decision fields.')
                    backend.number(row['selectedQ'],'Selected Q',-1e6,1e6)
                    backend.number(row['behaviorProbability'],'Q behavior probability',0,1)
                    merged.append((number,phase,row))
                    self.rows[phase].append({**row,'originIndex':index,'return':reward,'_prepared':prepared})
            require(len(occupied)<=12000,'Combined territory rows exceed the native game bound.')
            ordered=[turn for _,turn in sorted(turns)]
            require(all(a<=z for a,z in zip(ordered,ordered[1:])),'Merged territory turns must not move backward.')
            reference=dict(directory=str(root),bundlePath=str(own),index=index,gameSha256=hashes[index],
                completionSha256=sha(root/'finished.json'),collectionSha256=sha(root/'collection.json'),phaseRows=counts)
            self.games.append(episode_from_rows(reference,game['gameSeed'],reward,merged))
            self.origins.append(dict(index=index,gameSeed=game['gameSeed'],controlledSeat=index%4,
                tokenDecisions=counts['token'],expansionDecisions=counts['expansion']))
        for phase in PHASES:
            require(len(self.rows[phase])<=200000,'Territory view exceeds its bounded update size.')
            self._behavior(phase,actors[phase])
        self.common=dict(collectionSha256=sha(root/'collection.json'),collectionSignature=manifest['signature'],
            completionSha256=sha(root/'finished.json'),gameHashes=hashes,games=count,
            decodedIndices=indices,bundleSha256=self.bundle['sha256'],
            frozenCardPurchaseBundleSha256=self.frozen['sha256'],originCoverage=self.origins,readerSha256=sha(__file__))
        self.baseline_sha=sha(baseline);unchanged(self.pins)
        # Keep the already validated feature tensors alongside their exact immutable row identity.
        # Consumers may share these read-only inputs; every collection is still decoded/checked.
        origins={game.reference['index']:game.origin for game in self.games}
        self.prepared={(origins[row['originIndex']],row['number']):row['_prepared']
                       for phase in PHASES for row in self.rows[phase]}

    def _snapshot(self,original,name):
        require(pin(self.directory/name,self.pins).read_bytes()==Path(original).read_bytes(),'Territory snapshot changed: '+name)

    def _bundle_snapshots(self,bundle,name,prefix):
        self._snapshot(bundle['path'],name)
        for phase in PHASES:
            if bundle[phase] is not None:self._snapshot(bundle[phase]['path'],prefix+phase+'-policy.json')

    def _behavior(self,phase,actor):
        with backend.torch.no_grad():
            for start in range(0,len(self.rows[phase]),16):
                rows=self.rows[phase][start:start+16]
                values=q.evaluate(actor,[row['_prepared'] for row in rows])
                for value,row in zip(values,rows):
                    observation=row['observation']; selected=row['selectedIndex']
                    probabilities=q.probabilities(value,observation,.20)
                    uniform=backend.selection_uniform(row['selectorSeed'])
                    if (backend.select(probabilities,uniform)!=selected
                            or abs(float(value[selected])-row['selectedQ'])>1e-9
                            or abs(probabilities[selected]-row['behaviorProbability'])>1e-12):
                        value=q.reference_forward(self.models[phase],observation,phase)
                        probabilities=q.probabilities(value,observation,.20)
                    require(abs(float(value[selected])-row['selectedQ'])<=1e-9
                            and abs(probabilities[selected]-row['behaviorProbability'])<=1e-12
                            and backend.select(probabilities,uniform)==selected,
                            'Q behavior values, probabilities or seeded choice changed.')


def episode_from_rows(reference,seed,reward,merged):
    ordered=sorted(merged,key=lambda item:item[0])
    origin=reference['collectionSha256']+':'+str(reference['index'])
    transitions=[]
    for i,(number,phase,row) in enumerate(ordered):
        following=ordered[i+1] if i+1<len(ordered) else None
        successor=(q.Successor(origin,following[0],following[1],following[2]['observation'])
                   if following is not None else None)
        transitions.append(q.Transition(origin,number,phase,row['observation'],row['selectedIndex'],
                                        0. if successor is not None else reward,successor))
    game=q.Episode(origin,seed,reward,tuple(transitions),reference)
    q.validate_episode(game)
    return game


def initialize_model(schema_path, seed, phase, output):
    """Explicit fresh Q initialization; no PPO or trained-Q checkpoint conversion."""
    output=Path(output).resolve()
    require(not output.exists(),'Initialize Q into a new directory.')
    schema=read(schema_path);model=q.initialize(schema,seed,phase)
    immutable_json(output/'schema.json',schema)
    immutable_json(output/'policy.json',model)
    immutable_json(output/'initialization.json',dict(kind='territory-value-initializer',phase=phase,seed=seed,
        modelSha256=sha(output/'policy.json'),sourceHashes={str(Path(p).resolve()):sha(p)
            for p in (__file__,q.__file__,backend.__file__)}))
    return model


def validate_initializer(path,phase,pins):
    path=pin(path,pins);root=path.parent
    metadata=read(pin(root/'initialization.json',pins));schema=read(pin(root/'schema.json',pins))
    require(metadata['kind']=='territory-value-initializer' and metadata['phase']==phase
            and metadata['modelSha256']==sha(path)
            and metadata['sourceHashes']=={str(Path(p).resolve()):sha(p) for p in (__file__,q.__file__,backend.__file__)},
            'A first Q update needs the exact typed reproducible zero-output initializer.')
    model=q.validate_model(read(path),phase)
    require(model==q.initialize(schema,metadata['seed'],phase),'Q initializer seed/schema/model disagree.')
    return model


def restore_pair(payload,config):
    require(payload['kind']=='territory-double-q-checkpoint' and payload['schemaVersion']==1
            and set(payload['online'])==set(payload['targets'])==set(payload['optimizers'])==set(PHASES),
            'A Q checkpoint must contain both online/target/optimizer phases.')
    count=backend.integer(payload['optimizationUpdate'],'Optimization update count',0,1000000)
    require(payload['lastTargetSyncUpdate']==count//config.targetSyncEveryUpdates*config.targetSyncEveryUpdates,
            'Target synchronization must follow committed nonempty joint updates.')
    online={p:q.ActionValue(payload['online'][p],p) for p in PHASES}
    targets={p:q.ActionValue(payload['targets'][p],p).requires_grad_(False) for p in PHASES}
    optimizers={}
    for phase in PHASES:
        steps=backend.integer(payload['optimizerSteps'][phase],'Lifetime phase optimizer steps',0,1000000000)
        optimizer=backend.torch.optim.Adam(online[phase].parameters(),lr=config.learningRate,foreach=False)
        defaults=[{k:v for k,v in g.items() if k!='params'} for g in backend.encode_optimizer(optimizer)['param_groups']]
        backend.restore_optimizer(optimizer,payload['optimizers'][phase])
        require(defaults==[{k:v for k,v in g.items() if k!='params'}
                           for g in backend.encode_optimizer(optimizer)['param_groups']],
                'Q Adam hyperparameters must retain the fixed optimizer recipe.')
        require(len(optimizer.state)==(len(q.PARAMETERS) if steps else 0),'Q Adam must retain all six parameter states or genuine empty carry.')
        for parameter,state in optimizer.state.items():
            require(set(state)=={'step','exp_avg','exp_avg_sq'} and float(state['step'])==steps,
                    'Q Adam states must have the exact common phase step count.')
            for name in ('exp_avg','exp_avg_sq'):
                value=state[name]
                require(value.shape==parameter.shape and value.dtype==backend.DTYPE
                        and bool(backend.torch.isfinite(value).all()),'Q Adam tensor shape/dtype/value changed.')
            require(bool((state['exp_avg_sq']>=0).all()),'Adam second moments cannot be negative.')
        require(backend.encode_optimizer(optimizer)==payload['optimizers'][phase],'Q optimizer round-trip changed.')
        optimizers[phase]=optimizer
    return online,optimizers,targets


def read_completed(output,config=None):
    """No fitting or renewed allowance on reuse; a completion binds the entire pair."""
    output=Path(output).resolve(strict=True)
    identity=read(output/'identity.json');done=read(output/'finished.json')
    require(done['kind']=='territory-double-q-update' and done['identitySha256']==backend.digest(identity),
            'Q completion identity changed.')
    expected_files={'started.json','identity.json','checkpoint.json','bundle.json','numerical-guard.json',
                    'token-policy.json','expansion-policy.json'}
    require(set(done['outputHashes'])=={str(output/name) for name in expected_files},
            'Q completion must bind the exact complete output file set.')
    unchanged(identity['inputHashes']);unchanged(done['outputHashes'])
    payload=backend.read_checkpoint(output/'checkpoint.json')
    require(payload['identitySha256']==done['identitySha256'] and payload['optimizationUpdate']==done['optimizationUpdate']
            and payload['optimizerSteps']==done['optimizerSteps'] and payload['replay']==done['replay']
            and payload['reports']==done['phases'],'Q checkpoint and completed progress disagree.')
    recipe=q.UpdateConfig(**identity['config']).validate()
    if config is not None:
        require({k:v for k,v in asdict(recipe).items() if k not in ('seed','maxSeconds')}
                =={k:v for k,v in asdict(config).items() if k not in ('seed','maxSeconds')},
                'Carried Q checkpoint cannot change the learning algorithm.')
    restore_pair(payload,recipe)
    validate_progress(output,payload,identity,recipe)
    guard=read(output/'numerical-guard.json')
    require(guard==done['numericalGuard'] and guard['passed'] is True
            and guard['maximumAbsQ']==recipe.maximumAbsQ,'Q completion requires its frozen numerical guard.')
    for group in guard['values'].values():
        for values in group.values():
            require(values['count']==0 or max(abs(values['minimum']),abs(values['maximum']))<=recipe.maximumAbsQ,
                    'Completed Q guard cannot conceal dangerous value magnitudes.')
    bundle=read(output/'bundle.json')
    require(bundle['kind']=='territory-token-expansion-values-1'
            and bundle['configuration']==CONFIGURATION
            and bundle['baselineSha256']==identity['baselineSha256']
            and bundle['frozenCardPurchaseBundleSha256']==identity['frozenCardPurchaseBundleSha256'],
            'Published Q bundle changed its setup or frozen card/purchase actors.')
    for phase in PHASES:
        require(bundle[phase]==dict(path=str(output/(phase+'-policy.json')),sha256=sha(output/(phase+'-policy.json')))
                and payload['online'][phase]==read(output/(phase+'-policy.json')),
                'Published Q phase differs from its atomic checkpoint.')
    require(done['bundleSha256']==sha(output/'bundle.json'),'Published Q bundle hash changed.')
    return payload,identity,done


def validate_progress(output,payload,identity,config):
    """Bind progress/targets and zero-step carries to the actual prior published pair."""
    started=read(output/'started.json')
    require(started==dict(collectionDirectory=identity['collectionDirectory'],behaviorBundlePath=identity['behaviorBundlePath'],
        previousCheckpoint=identity['previousCheckpoint'],config=identity['config'],
        deadlineEpochSeconds=identity['deadlineEpochSeconds']), 'Q attempt clock/request changed.')
    own=read(identity['behaviorBundlePath'])
    models={p:read(resolve(own[p]['path'],Path(identity['behaviorBundlePath']).parent)) for p in PHASES}
    if identity['previousCheckpoint'] is None:
        old_online=models;old_targets=models;old_count=0;old_steps=dict.fromkeys(PHASES,0)
        old_optimizers={p:backend.encode_optimizer(backend.torch.optim.Adam(q.ActionValue(models[p],p).parameters(),
            lr=config.learningRate,foreach=False)) for p in PHASES}
        for p in PHASES:validate_initializer(resolve(own[p]['path'],Path(identity['behaviorBundlePath']).parent),p,{})
        old_replay=dict(games=[],seenOrigins=[],seenSeeds=[],evictedGames=0)
    else:
        previous=backend.read_checkpoint(identity['previousCheckpoint'])
        old_online=previous['online'];old_targets=previous['targets'];old_count=previous['optimizationUpdate']
        old_steps=previous['optimizerSteps'];old_optimizers=previous['optimizers']
        require(old_online==models,'Q behavior models disagree with carried checkpoint.')
        old_replay=previous['replay']
    nonempty=any(identity['newPhaseRows'].values());expected_count=old_count+int(nonempty)
    require(payload['optimizationUpdate']==identity['optimizationUpdate']==expected_count,
            'Only committed nonempty Q updates advance the optimization/target clock.')
    for p in PHASES:
        steps=q.steps_for_rows(identity['newPhaseRows'][p],config);report=payload['reports'][p]
        require(set(report)=={'steps','losses'} and report['steps']==steps and len(report['losses'])==steps
                and payload['optimizerSteps'][p]==old_steps[p]+steps,
                'Q step counts must match new observed rows and original lifetime Adam progress.')
        for value in report['losses']:backend.number(value,'Finite Q loss',0)
        if not steps:
            require(payload['online'][p]==old_online[p] and payload['optimizers'][p]==old_optimizers[p],
                    'A zero-step Q head must carry exact online weights and Adam.')
        target=payload['online'][p] if nonempty and expected_count%4==0 else old_targets[p]
        require(payload['targets'][p]==target,'Q targets must sync exactly every fourth nonempty joint update.')
    require(payload['replay']==identity['replay'],'Q replay membership cannot change while fitting.')
    current=identity['collection']
    require(current['decodedIndices']==list(range(current['games']))
            and [row['index'] for row in current['originCoverage']]==list(range(current['games'])),
            'A Q update processes every current origin, including empty games.')
    new_refs=[dict(directory=identity['collectionDirectory'],bundlePath=identity['behaviorBundlePath'],index=row['index'],
        gameSha256=current['gameHashes'][row['index']],completionSha256=current['completionSha256'],
        collectionSha256=current['collectionSha256'],
        phaseRows={p:row[p+'Decisions'] for p in PHASES}) for row in current['originCoverage']]
    retained=old_replay['games']+new_refs;evicted=old_replay['evictedGames']
    while len(retained)>config.replayGames or any(sum(ref['phaseRows'][p] for ref in retained)>config.replayRowsPerPhase for p in PHASES):
        retained=retained[1:];evicted+=1
    expected_origins=set(old_replay['seenOrigins'])|{current['collectionSha256']+':'+str(row['index']) for row in current['originCoverage']}
    expected_seeds=set(old_replay['seenSeeds'])|{row['gameSeed'] for row in current['originCoverage']}
    require(payload['replay']['games']==retained and payload['replay']['evictedGames']==evicted
            and payload['replay']['seenOrigins']==sorted(expected_origins)
            and payload['replay']['seenSeeds']==sorted(expected_seeds)
            and payload['replay']['phaseRows']=={p:sum(ref['phaseRows'][p] for ref in retained) for p in PHASES},
            'Replay must retain the exact FIFO suffix and complete processed origin/seed history.')


def rebuild_replay(payload,config,pins,baseline_sha,frozen_sha):
    replay=q.Replay(config.replayRowsPerPhase,config.replayGames)
    groups={}
    for ref in payload['replay']['games']:
        key=(ref['directory'],ref['bundlePath'])
        groups.setdefault(key,[]).append(ref)
    loaded={};prepared={}
    for (directory,bundle),refs in groups.items():
        collection=ValueCollection(directory,bundle,indices=sorted(ref['index'] for ref in refs))
        require(collection.baseline_sha==baseline_sha and collection.frozen['sha256']==frozen_sha,
                'Replay cannot change the F baseline or frozen card/purchase environment.')
        pins.update(collection.pins)
        prepared.update(collection.prepared)
        for game in collection.games:loaded[(directory,game.reference['index'])]=game
    games=[]
    for ref in payload['replay']['games']:
        game=loaded[(ref['directory'],ref['index'])]
        require(game.reference==ref,'Retained replay file/hash/source index changed.')
        games.append(game)
    replay.append(games,prepared=prepared)
    require(replay.counts()==payload['replay']['phaseRows'] and len(replay.games)==len(games)
            and not replay.evicted_games,'Saved replay exceeds its whole-game capacity.')
    replay.seen_origins=set(payload['replay']['seenOrigins'])
    replay.seen_seeds=set(payload['replay']['seenSeeds'])
    require(len(replay.seen_origins)==len(payload['replay']['seenOrigins'])
            and len(replay.seen_seeds)==len(payload['replay']['seenSeeds'])
            and {game.origin for game in games}<=replay.seen_origins
            and {game.gameSeed for game in games}<=replay.seen_seeds,
            'Replay retained games must belong to distinct processed origin/seed histories.')
    replay.evicted_games=backend.integer(payload['replay']['evictedGames'],'Evicted whole games',0,10000000)
    require(len(replay.seen_origins)==len(replay.seen_seeds)==len(replay.games)+replay.evicted_games,
            'Replay processed/retained/evicted counts disagree.')
    return replay


def replay_record(replay):
    return dict(games=[game.reference for game in replay.games],phaseRows=replay.counts(),
                seenOrigins=sorted(replay.seen_origins),seenSeeds=sorted(replay.seen_seeds),evictedGames=replay.evicted_games)


def update_pair(directory,bundle_path,config,output,*,checkpoint_path=None,resume=False):
    """Publish one complete new Q pair. Interrupted attempts remain unpublishable, without retry.

    Only completed-output reuse is supported. A fresh explicit update owns one nonrenewable
    allowance including reader/replay checks; no sibling or partial optimizer state is reused.
    Empty new phases take zero steps; a wholly empty batch does not advance target-sync time.
    """
    config.validate();backend.torch.set_num_threads(1);backend.torch.use_deterministic_algorithms(True)
    started=time.time();deadline=time.monotonic()+config.maxSeconds
    output=Path(output).resolve()
    if output.exists():
        require(resume and (output/'finished.json').is_file(),
                'Q reuse requires an already completed atomic pair; preserve interrupted evidence without retry.')
        _,identity,done=read_completed(output,config)
        require(identity['collectionDirectory']==str(Path(directory).resolve())
                and identity['behaviorBundlePath']==str(Path(bundle_path).resolve())
                and asdict(q.UpdateConfig(**identity['config']).validate())==asdict(config)
                and identity['previousCheckpoint']==(str(Path(checkpoint_path).resolve()) if checkpoint_path else None),
                'Completed Q reuse requires exactly the original request.')
        return done
    immutable_json(output/'started.json',dict(collectionDirectory=str(Path(directory).resolve()),
        behaviorBundlePath=str(Path(bundle_path).resolve()),
        previousCheckpoint=str(Path(checkpoint_path).resolve()) if checkpoint_path else None,
        config=asdict(config),deadlineEpochSeconds=started+config.maxSeconds))
    collection=ValueCollection(directory,bundle_path);pins=dict(collection.pins)
    for path in (__file__,q.__file__,backend.__file__,joint.__file__):pin(path,pins)
    new_counts={p:len(collection.rows[p]) for p in PHASES}
    require(all(n<=config.replayRowsPerPhase for n in new_counts.values())
            and len(collection.games)<=config.replayGames,
            'A new batch must fit replay without evicting any fresh untrained game.')
    if checkpoint_path is None:
        online={p:q.ActionValue(validate_initializer(collection.bundle[p]['path'],p,pins),p) for p in PHASES}
        optimizers={p:backend.torch.optim.Adam(online[p].parameters(),lr=config.learningRate,foreach=False) for p in PHASES}
        lagged=q.snapshot(online);replay=q.Replay(config.replayRowsPerPhase,config.replayGames)
        previous_count=0;old_steps=dict.fromkeys(PHASES,0)
    else:
        checkpoint_path=pin(checkpoint_path,pins)
        require(checkpoint_path.name=='checkpoint.json','Carry the exact completed Q pair checkpoint.')
        previous,old_identity,old_done=read_completed(checkpoint_path.parent,config)
        for name in ('identity.json','finished.json','bundle.json'):pin(checkpoint_path.parent/name,pins)
        pins.update(old_done['outputHashes']);pins.update(old_identity['inputHashes'])
        require(all(previous['online'][p]==collection.models[p] for p in PHASES)
                and sha(checkpoint_path.parent/'bundle.json')==collection.bundle['sha256'],
                'New Q trajectories must use the exact preceding atomic online bundle.')
        online,optimizers,lagged=restore_pair(previous,config)
        replay=rebuild_replay(previous,config,pins,collection.baseline_sha,collection.frozen['sha256'])
        previous_count=previous['optimizationUpdate'];old_steps=previous['optimizerSteps']
    before_evictions=replay.evicted_games
    replay.append(collection.games,prepared=collection.prepared)
    require(all(n==0 or replay.rows(p) for p,n in new_counts.items()),'A positive-step phase lost its replay.')
    nonempty=any(new_counts.values());update_number=previous_count+int(nonempty)
    identity=dict(kind='territory-double-q-update',schemaVersion=1,config=asdict(config),
        collectionDirectory=str(collection.directory),behaviorBundlePath=str(Path(bundle_path).resolve()),
        previousCheckpoint=str(checkpoint_path) if checkpoint_path else None,collection=collection.common,
        baselineSha256=collection.baseline_sha,frozenCardPurchaseBundleSha256=collection.frozen['sha256'],
        optimizationUpdate=update_number,newPhaseRows=new_counts,replay=replay_record(replay),
        deadlineEpochSeconds=started+config.maxSeconds,inputHashes=pins,pythonVersion=sys.version,
        torchVersion=backend.torch.__version__)
    require(time.monotonic()<deadline,'Q validation exhausted the original allowance before fitting.')
    immutable_json(output/'identity.json',identity)
    prepare_started=time.monotonic();prepared_bytes=replay.prepare(online)
    preparation_seconds=time.monotonic()-prepare_started
    fit_started=time.monotonic()
    if nonempty:
        candidates,next_optimizers,next_targets,reports=q.fit_pair(online,optimizers,lagged,replay,new_counts,
                                                                  update_number,config,deadline)
    else:
        candidates,next_optimizers,next_targets=online,optimizers,lagged
        reports={p:dict(steps=0,losses=[]) for p in PHASES}
    fit_seconds=time.monotonic()-fit_started
    guard=q.numerical_guard(candidates,next_targets,replay,config,deadline)
    immutable_json(output/'numerical-guard.json',guard)
    require(guard['passed'],'Q candidate/target exceeded the prospective magnitude guard; no pair publication.')
    steps={p:old_steps[p]+reports[p]['steps'] for p in PHASES}
    payload=dict(kind='territory-double-q-checkpoint',schemaVersion=1,identitySha256=backend.digest(identity),
        optimizationUpdate=update_number,lastTargetSyncUpdate=update_number//4*4,
        online={p:candidates[p].artifact() for p in PHASES},targets={p:next_targets[p].artifact() for p in PHASES},
        optimizers={p:backend.encode_optimizer(next_optimizers[p]) for p in PHASES},optimizerSteps=steps,
        replay=replay_record(replay),reports=reports)
    restore_pair(payload,config)
    validate_progress(output,payload,identity,config)
    output_pins={}
    for name in ('started.json','identity.json','numerical-guard.json'):pin(output/name,output_pins)
    serialization_started=time.monotonic()
    for phase in PHASES:
        immutable_json(output/(phase+'-policy.json'),payload['online'][phase]);pin(output/(phase+'-policy.json'),output_pins)
    backend.atomic_json(output/'checkpoint.json',dict(sha256=backend.digest(payload),payload=payload),compact=True)
    pin(output/'checkpoint.json',output_pins)
    bundle=dict(schemaVersion=1,kind='territory-token-expansion-values-1',configuration=CONFIGURATION,
        baselineSha256=collection.baseline_sha,frozenCardPurchaseBundleSha256=collection.frozen['sha256'],
        **{p:dict(path=str(output/(p+'-policy.json')),sha256=sha(output/(p+'-policy.json'))) for p in PHASES})
    unchanged(pins);unchanged(output_pins)
    require(time.monotonic()<deadline,'Q deadline expired before atomic pair publication.')
    immutable_json(output/'bundle.json',bundle);pin(output/'bundle.json',output_pins)
    report=dict(kind='territory-double-q-update',schemaVersion=1,identitySha256=backend.digest(identity),
        optimizationUpdate=update_number,optimizerSteps=steps,bundleSha256=sha(output/'bundle.json'),
        phases=reports,replay=replay_record(replay),newPhaseRows=new_counts,newlyEvictedGames=replay.evicted_games-before_evictions,
        targetSynchronized=nonempty and update_number%4==0,elapsedSeconds=time.time()-started,
        numericalGuard=guard,
        resources=dict(preparedTensorBytes=prepared_bytes,preparationSeconds=preparation_seconds,
            fitSeconds=fit_seconds,serializationSeconds=time.monotonic()-serialization_started,
            peakRssBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if sys.platform=='darwin' else 1024),
            retainedGames=len(replay.games),retainedPhaseRows=replay.counts()),
        outputHashes=output_pins,allInputsUnchanged=True,strengthClaim=False,policyPromotion=False)
    unchanged(pins);unchanged(output_pins)
    require(time.monotonic()<deadline,'Q deadline expired before completion publication.')
    immutable_json(output/'finished.json',report)
    return report
