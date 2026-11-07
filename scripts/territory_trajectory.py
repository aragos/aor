"""Complete token/expansion origins and atomic separately typed updates with frozen card/purchase play.

Sealed token rows retain the actual batch start and bot seed; their ordinal is not the seed
origin. Both views use exactly the same complete games and terminal labels, including games
with empty phase arrays. An entirely empty phase rejects before either fit. No partial pair
is published. Old card, purchase and joint readers/identities are not adapted to this schema.
"""
import argparse
from dataclasses import asdict
from pathlib import Path
import math
import sys

import purchase_policy as backend
import token_policy as token
import expansion_policy as expansion
import joint_trajectory as joint
from joint_economy import terminal_metrics

PHASES=('token','expansion')
MODULES={'token':token,'expansion':expansion}
CONTRACTS={phase:module.CONTRACT for phase,module in MODULES.items()}
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
            and raw['schemaVersion']==1 and raw['kind']=='territory-token-expansion-1'
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
        model=backend.validate_model(read(model_path),contract=contract);models[phase]=model
        descriptor[phase]=dict(path=str(model_path),sha256=sha(model_path),encoderVersion=contract.encoder,
            actorVersion=contract.actor,stateFeatureNames=model['stateFeatureNames'],actionFeatureNames=model['actionFeatureNames'])
    return descriptor,models


class TerritoryCollection:
    def __init__(self,directory,bundle_path):
        self.directory=Path(directory).resolve(strict=True);self.pins={};root=self.directory
        for name in ('collection.json','finished.json','state.json','source.json'):pin(root/name,self.pins)
        manifest,done,state,snapshot=(read(root/name) for name in
            ('collection.json','finished.json','state.json','source.json'))
        require(manifest['kind']==done['kind']==state['kind']=='territory-collect'
                and manifest['schemaVersion']==1 and state['phase']=='complete'
                and manifest['signature']==done['signature']==state['signature']
                and isinstance(manifest['signature'],str) and manifest['signature']
                and manifest['mode']=='on-policy' and manifest['trajectoryEncoding']=='gzip-json-v1',
                'Both territory phases require one completely finished native collection.')
        require(manifest['configuration']==CONFIGURATION,'Territory learning currently requires the qualified four-player setup.')
        source=pin(manifest['source'],self.pins)
        require(sha(source)==manifest['sourceSha256'] and source.read_bytes()==(root/'source.json').read_bytes()
                and snapshot['schemaVersion']==1 and snapshot['kind']=='territory-collect',
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
        self.rows={phase:[] for phase in PHASES};self.origins=[];rewards=[]
        actors={phase:backend.ResidualPolicy(model,CONTRACTS[phase]) for phase,model in self.models.items()}
        for index,path in enumerate(paths):
            game=backend.read_game(path,token.CONTRACT)
            expected=dict(schemaVersion=1,signature=manifest['signature'],index=index,configuration=CONFIGURATION,
                gameSeed=manifest['seeds'][index],controlledSeat=index%4,
                opponentIndices=[(index//4+seat)%len(opponents) for seat in range(4)],
                bundleSha256=self.bundle['sha256'],frozenCardPurchaseBundleSha256=self.frozen['sha256'],
                tokenModelSha256=self.bundle['token']['sha256'],expansionModelSha256=self.bundle['expansion']['sha256'])
            require(all(game.get(key)==value for key,value in expected.items()),'Territory game provenance differs from its collection.')
            reward=terminal_metrics(game)['reward'];rewards.append(reward)
            counts={};occupied=set();turns=[]
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
                    backend.integer(row['actorSeed'],'Territory actor seed',-(2**31),2**31-1)
                    expected_bot=bot_seed(game['gameSeed'],batch,game['controlledSeat'])
                    require(row['botSeed']==expected_bot and row['actorSeed']==signed(expected_bot^MODULES[phase].ACTOR_SALT),
                            'Use actual sealed-batch policy seed and the exact typed actor salt.')
                    require(row['decision']==('buy-tokens' if phase=='token' else 'expand'),'A typed row belongs to another phase.')
                    previous=number;occupied.add(number)
                    turns.append((number,backend.integer(row['turn'],'Territory turn',1,12000)))
                    prepared=actors[phase].prepare(row['observation'])
                    backend.integer(row['selectedIndex'],'Territory selected action',0,len(row['observation']['actions'])-1)
                    backend.number(row['behaviorLogProbability'],'Territory behavior log probability')
                    backend.number(row['behaviorValue'],'Territory behavior value',0,1)
                    self.rows[phase].append({**row,'originIndex':index,'return':reward,'_prepared':prepared})
            require(len(occupied)<=12000,'Combined territory rows exceed the native game bound.')
            ordered=[turn for _,turn in sorted(turns)]
            require(all(a<=z for a,z in zip(ordered,ordered[1:])),'Merged territory turns must not move backward.')
            self.origins.append(dict(index=index,gameSeed=game['gameSeed'],controlledSeat=index%4,
                tokenDecisions=counts['token'],expansionDecisions=counts['expansion']))
        for phase in PHASES:
            require(len(self.rows[phase])<=200000,'Territory view exceeds its bounded update size.')
            self._behavior(phase,actors[phase])
        self.common=dict(collectionSha256=sha(root/'collection.json'),collectionSignature=manifest['signature'],
            completionSha256=sha(root/'finished.json'),gameHashes=hashes,games=count,
            gameMeanReward=math.fsum(rewards)/count,bundleSha256=self.bundle['sha256'],
            frozenCardPurchaseBundleSha256=self.frozen['sha256'],originCoverage=self.origins,readerSha256=sha(__file__))
        self.baseline_sha=sha(baseline);unchanged(self.pins)

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
                logps,values=actor([row['_prepared'] for row in rows])
                for logp,value,row in zip(logps,values,rows):
                    require(abs(float(logp[row['selectedIndex']])-row['behaviorLogProbability'])<=1e-9
                            and abs(float(value)-row['behaviorValue'])<=1e-9,'Territory behavior likelihood/value changed.')
                    uniform=backend.selection_uniform(row['actorSeed'])
                    if backend.select(logp.exp().tolist(),uniform)!=row['selectedIndex']:
                        exact=backend.reference_forward(self.models[phase],row['observation'],contract=CONTRACTS[phase])
                        require(backend.select(exact['probabilities'],uniform)==row['selectedIndex'],'Territory seeded choice differs.')

    def phase_reader(self,directory,model_path,model,*,contract):
        phase=contract.label
        require(phase in PHASES and contract==CONTRACTS[phase] and Path(directory).resolve()==self.directory
                and Path(model_path).resolve()==Path(self.bundle[phase]['path']) and model==self.models[phase],
                'Territory fitter must use its exact original typed behavior view.')
        unchanged(self.pins)
        require(self.rows[phase],'An entirely empty territory phase cannot publish a fitted pair.')
        return self.rows[phase],dict(self.common,phase=phase,behaviorModelSha256=self.bundle[phase]['sha256'])


def validate_initializer(model_path,contract,pins):
    path=pin(model_path,pins);root=path.parent
    identity=read(pin(root/'initialization.json',pins));schema=read(pin(root/'schema.json',pins))
    model=backend.validate_model(read(path),contract=contract)
    require(identity['sha256']==sha(path) and identity['learnerSha256']==sha(backend.__file__)
            and identity['entrypointSha256']==sha(MODULES[contract.label].__file__)
            and backend.initialize(schema,identity['seed'],contract=contract)==model,
            'A fresh territory actor requires its reproducible complete-dictionary zero-head initializer.')
    return model


def validate_carry(model_path,checkpoint_path,contract,config,pins,rows=None):
    if checkpoint_path is None:return validate_initializer(model_path,contract,pins)
    checkpoint_path=pin(checkpoint_path,pins);payload=backend.read_checkpoint(checkpoint_path)
    if payload['optimizer']['state']:
        return joint.validate_carry(model_path,checkpoint_path,contract,config,pins)
    # A newly initialized actor can reject its first block. It carries an unchanged empty Adam,
    # rather than fabricating seven states or treating partial-epoch progress as zero progress.
    model=backend.validate_model(read(pin(model_path,pins)),contract=contract)
    identity=read(pin(checkpoint_path.parent/'identity.json',pins));done=read(pin(checkpoint_path.parent/'finished.json',pins))
    require(checkpoint_path.name=='checkpoint.json' and identity['actorVersion']==contract.actor
            and payload['identitySha256']==done['identitySha256']==backend.digest(identity)
            and payload['model']==model and done['outputModelSha256']==sha(model_path)
            and done['inputModelSha256']==identity['modelSha256']==sha(model_path)
            and done['history']==payload['history'] and done['acceptedEpochs']==payload['epoch']
            and model['actorWeights']==model['valueWeights']==[0.]*64 and model['valueBias']==0.,
            'An empty territory Adam is valid only for an unchanged never-updated zero head.')
    previous=backend.UpdateConfig(**identity['config']).validate(contract)
    zero_cursor=dict(epoch=0,minibatchOffset=0)
    require(payload['schemaVersion']==done['schemaVersion']==2
            and identity['updateMode']==payload['updateMode']==done['updateMode']==backend.CHECKED_PREFIX_MODE
            and payload['deadlineEpochSeconds']==identity['deadlineEpochSeconds']
            and payload['epoch']==done['completedEpochs']==0
            and payload['acceptedSteps']==payload['acceptedBlocks']==done['acceptedSteps']==done['acceptedBlocks']==0
            and payload['cursor']==done['cursor']==zero_cursor and done['partialEpochAccepted'] is False
            and done['lastAcceptedMetrics'] is None and payload['stopReason']==done['stopReason']=='behavior-kl-limit'
            and len(payload['history'])==1,'Empty Adam carry must record only its rejected first block.')
    block=payload['history'][0]
    require(set(block)=={'accepted','cursor','nextCursor','attemptedSteps','acceptedSteps','metrics','stopReason'}
            and block['accepted'] is False and block['cursor']==block['nextCursor']==zero_cursor
            and block['acceptedSteps']==0 and block['stopReason']=='behavior-kl-limit'
            and type(block['attemptedSteps']) is int and 1<=block['attemptedSteps']<=8
            and set(block['metrics'])=={'behaviorKl','priorKl','valueMse'},
            'Empty Adam history must retain the exact rejected first-block cursor and no accepted steps.')
    for name,value in block['metrics'].items():
        backend.number(value,name,-1e-12 if name.endswith('Kl') else 0)
    require(block['metrics']['behaviorKl']>previous.maxBehaviorKl,'Empty Adam rejection must exceed the original KL cap.')
    if rows is not None:
        summary=backend.validate_checked_prefix_checkpoint(payload,identity,rows)
        require(all(done[key]==value for key,value in summary.items()),'Empty Adam completion summary changed.')
    optimizer=backend.torch.optim.Adam(backend.ResidualPolicy(model,contract).parameters(),lr=config.learningRate,foreach=False)
    require(backend.encode_optimizer(optimizer)==payload['optimizer'],'Empty Adam must retain exact default hyperparameters.')
    return payload


def completed_phase(output,collection,phase,config,carry,objective):
    output=Path(output);identity,done=read(output/'identity.json'),read(output/'finished.json')
    payload=backend.read_checkpoint(output/'checkpoint.json');descriptor=collection.bundle[phase]
    _,data=collection.phase_reader(collection.directory,descriptor['path'],collection.models[phase],contract=CONTRACTS[phase])
    expected=dict(schemaVersion=1,actorVersion=CONTRACTS[phase].actor,modelSha256=descriptor['sha256'],data=data,
        config=asdict(config),learnerSha256=sha(backend.__file__),entrypointSha256=sha(__file__),
        torchVersion=backend.torch.__version__,pythonVersion=sys.version,optimizerFromSha256=sha(carry) if carry else None,
        updateMode=backend.CHECKED_PREFIX_MODE,collectionReaderSource=dict(path=str(Path(__file__).resolve()),sha256=sha(__file__)))
    if objective is not None:expected['offeredReferenceObjective']=asdict(objective)
    require({key:value for key,value in identity.items() if key!='deadlineEpochSeconds'}==expected,
            'Territory fit identity must bind the exact behavior, complete shared data, objective and Adam.')
    summary=backend.validate_checked_prefix_checkpoint(payload,identity,collection.rows[phase])
    require(done['schemaVersion']==2 and done['updateMode']==backend.CHECKED_PREFIX_MODE
            and done['identitySha256']==backend.digest(identity) and done['inputModelSha256']==descriptor['sha256']
            and done['outputModelSha256']==sha(output/'policy.json') and read(output/'policy.json')==payload['model']
            and done['history']==payload['history'] and done['acceptedEpochs']==payload['epoch']
            and done['games']==collection.common['games'] and done['decisions']==len(collection.rows[phase])
            and done['gameMeanReward']==collection.common['gameMeanReward']
            and done['stopReason']==(payload['stopReason'] or 'epochs-complete')
            and done.get('offeredReferenceObjective')==expected.get('offeredReferenceObjective')
            and all(done[key]==value for key,value in summary.items()),'Territory completion disagrees with its typed checkpoint.')
    require(done['stopReason']!='time-budget','A timed-out phase cannot publish half a new territory bundle.')
    validate_carry(output/'policy.json',output/'checkpoint.json',CONTRACTS[phase],config,{},collection.rows[phase])
    return dict(identitySha256=backend.digest(identity),policySha256=sha(output/'policy.json'),
        checkpointSha256=sha(output/'checkpoint.json'),completionSha256=sha(output/'finished.json'),
        decisions=len(collection.rows[phase]),**summary)


def update_pair(directory,bundle_path,configs,checkpoints,output,*,objectives=None,resume=False):
    require(set(configs)==set(checkpoints)==set(PHASES),'Provide both typed territory configs and carries.')
    objectives=objectives or dict.fromkeys(PHASES)
    require(set(objectives)==set(PHASES),'Specify each territory reference explicitly.')
    for phase in PHASES:
        configs[phase].validate(CONTRACTS[phase]);require(configs[phase].threads==1,'Territory fitting uses one CPU.')
        backend.validate_offered_reference(objectives[phase],CONTRACTS[phase],backend.CHECKED_PREFIX_MODE)
    backend.torch.set_num_threads(1)
    collection=TerritoryCollection(directory,bundle_path)
    require(all(collection.rows.values()),'Reject an entirely empty phase before either fit or pair publication.')
    pins=dict(collection.pins)
    for phase in PHASES:validate_carry(collection.bundle[phase]['path'],checkpoints[phase],CONTRACTS[phase],configs[phase],pins)
    for path in (__file__,backend.__file__,token.__file__,expansion.__file__,joint.__file__):pin(path,pins)
    identity=dict(kind='territory-two-actor-update',schemaVersion=1,collection=collection.common,
        behaviorBundle=collection.bundle,frozenCardPurchaseBundle=collection.frozen,
        configs={phase:asdict(configs[phase]) for phase in PHASES},
        optimizerFrom={phase:dict(path=str(Path(path).resolve()),sha256=sha(path)) if path else None for phase,path in checkpoints.items()},
        offeredReferenceObjectives={phase:asdict(value) if value else None for phase,value in objectives.items()},
        updateMode=backend.CHECKED_PREFIX_MODE,inputHashes=pins)
    output=Path(output).resolve()
    require(not output.exists() or resume and (output/'identity.json').exists(),'Use a fresh territory output or its exact original resume identity.')
    immutable_json(output/'identity.json',identity);reports={};output_pins={}
    for phase in PHASES:
        target=output/phase
        if not (target/'finished.json').exists():
            backend.update(collection.bundle[phase]['path'],collection.directory,configs[phase],target,
                resume=target.exists(),optimizer_from=checkpoints[phase],contract=CONTRACTS[phase],
                entrypoint=__file__,update_mode=backend.CHECKED_PREFIX_MODE,offered_reference=objectives[phase],
                collection_reader=collection.phase_reader)
        reports[phase]=completed_phase(target,collection,phase,configs[phase],checkpoints[phase],objectives[phase])
        immutable_json(output/(phase+'-completed.json'),reports[phase])
        for path in (target/'policy.json',target/'checkpoint.json',target/'identity.json',target/'finished.json',output/(phase+'-completed.json')):
            pin(path,output_pins)
    unchanged(pins);unchanged(output_pins)
    bundle=dict(schemaVersion=1,kind='territory-token-expansion-1',configuration=CONFIGURATION,
        baselineSha256=collection.baseline_sha,frozenCardPurchaseBundleSha256=collection.frozen['sha256'],
        **{phase:dict(path=str(output/phase/'policy.json'),sha256=reports[phase]['policySha256']) for phase in PHASES})
    immutable_json(output/'bundle.json',bundle)
    report=dict(kind='territory-two-actor-update',schemaVersion=1,identitySha256=backend.digest(identity),
        behaviorBundleSha256=collection.bundle['sha256'],outputBundleSha256=sha(output/'bundle.json'),
        frozenCardPurchaseBundleSha256=collection.frozen['sha256'],commonOrigins=collection.origins,
        gameHashes=collection.common['gameHashes'],phases=reports,outputHashes=output_pins,
        allInputHashesUnchanged=True,strengthClaim=False,policyPromotion=False)
    immutable_json(output/'finished.json',report)
    return report


def phase_main(arguments,contract,entrypoint):
    args=list(sys.argv[1:] if arguments is None else arguments)
    if args and args[0]=='initialize':
        return backend.main(args,contract=contract,entrypoint=entrypoint)
    parser=argparse.ArgumentParser(description='One typed territory update; only update_pair publishes a joint bundle.')
    parser.add_argument('mode',choices=['update']);parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--bundle',type=Path,required=True);parser.add_argument('--model',type=Path,required=True)
    parser.add_argument('--config',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--optimizer-from',type=Path);parser.add_argument('--reference-objective',type=Path)
    parser.add_argument('--resume',action='store_true')
    options=parser.parse_args(args)
    config=backend.UpdateConfig(**read(options.config)).validate(contract)
    objective=backend.OfferedReferenceObjective.from_json(read(options.reference_objective)) if options.reference_objective else None
    collection=TerritoryCollection(options.source,options.bundle)
    backend.update(options.model,options.source,config,options.output,resume=options.resume,
        optimizer_from=options.optimizer_from,contract=contract,entrypoint=entrypoint,
        update_mode=backend.CHECKED_PREFIX_MODE,offered_reference=objective,collection_reader=collection.phase_reader)
