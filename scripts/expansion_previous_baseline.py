"""Separate typed previous-batch state baseline; existing MC/PPO identities remain strict.

Each transaction publishes actor/ first, then its next-batch baseline, then finished.json.
Partial output is diagnostic evidence and never a consumable predecessor. Only batches1..7
fit a baseline. No baseline fitted on the current cohort can enter that cohort's actor loss.
"""
import copy
from dataclasses import asdict, dataclass
from pathlib import Path

import expansion_mc as mc
from expansion_on_policy_data import ExpansionCollection
import expansion_state_baseline as baseline
import purchase_policy as policy

VERSION='expansion-previous-batch-state-baseline-1'
CARRY='expansion-previous-batch-adam-1'
TRANSACTION='expansion-previous-batch-update-1'
REPLICAS=('PRIMARY_DESKTOP','MAC_REPLICATION')
ESTIMATOR=dict(gamma=1.,intermediateReward=0.,forcedRowsIncluded=True,
    weighting='uniform-recorded-decision-mean',terminalReward='original-complete-world',
    baseline='constant-half-first-then-completed-previous-batch-state-ridge')


def _semantic(row):
    return {key:value for key,value in row.items() if key!='_prepared'}


def _prepared(value):
    return {key:dict(dtype=str(item.dtype),shape=list(item.shape),values=item.tolist())
        if isinstance(item,policy.torch.Tensor) else item for key,item in vars(value).items()}


def _inventory(root):
    files={}
    for path in sorted(root.rglob('*')):
        policy.require(not path.is_symlink(),'Numeric transaction cannot contain symlinks.')
        if path.is_file() and path!=root/'finished.json':files[path.relative_to(root).as_posix()]=policy.file_digest(path)
    return files


def _actor_steps(root,config):
    model=policy.read_json(root/'policy.json');checkpoint,identity=policy.actor_adam_checkpoint(root/'checkpoint.json',model)
    finished=policy.read_json(root/'finished.json');objective=identity.get('expansionBaselineObjective',{})
    policy.require(objective.get('version')==VERSION and objective.get('arm')=='TREATMENT'
        and identity['actorVersion']==mc.CONTRACT.actor and finished.get('expansionBaselineObjective')==objective
        and finished['history']==checkpoint['history'] and finished['acceptedEpochs']==checkpoint['epoch']
        and finished['inputModelSha256']==identity['modelSha256']==objective['behaviorModelSha256']
        and finished['stopReason'] in ('epochs-complete','behavior-kl-limit')
        and {k:v for k,v in identity['config'].items() if k!='seed'}==
            {k:v for k,v in asdict(config).items() if k!='seed'},
        'Treatment actor requires its exact completed objective, epoch history and PPO recipe.')
    carry=identity.get('expansionBaselineOptimizerContinuation')
    inherited=0 if carry is None else policy.integer(carry['actorAdamSteps'],'Inherited treatment steps',0,2**53)
    return policy.validated_actor_adam_steps(checkpoint,model,config,mc.CONTRACT,inherited),inherited


def completed(root,basis,config,*,batch,replica,protocol_subject,_receipt=None):
    """Validate a completed local transaction before its actor and baseline can be consumed."""
    root=Path(root).resolve();baseline.check_subject(protocol_subject)
    receipt=policy.read_json(root/'finished.json') if _receipt is None else _receipt
    identity=policy.read_json(root/'identity.json')
    expected={'identity.json','detached-targets.json'}|{f'actor/{name}.json' for name in ('identity','checkpoint','epoch-started','policy','finished')}
    if batch<8:expected.add('baseline.json')
    policy.require(receipt.get('kind')==TRANSACTION and receipt.get('complete') is True
        and receipt.get('batch')==batch and receipt.get('replica')==replica
        and receipt.get('protocol')==protocol_subject and receipt.get('arm')=='TREATMENT'
        and receipt.get('identity')==baseline.subject(root/'identity.json')
        and receipt.get('artifacts')==_inventory(root) and set(receipt['artifacts'])==expected,
        'Only an unchanged completed same-replica transaction can supply previous actor and baseline.')
    policy.require(identity.get('kind')==TRANSACTION and identity.get('batch')==batch
        and identity.get('replica')==replica and identity.get('protocol')==protocol_subject
        and identity.get('basis')==basis.descriptor() and identity.get('arm')=='TREATMENT'
        and identity.get('previousTransaction')==receipt.get('previousTransaction')
        and {k:v for k,v in identity['config'].items() if k!='seed'}==
            {k:v for k,v in asdict(config).items() if k!='seed'},
        'Completed transaction must retain its original basis, replica, protocol and actor recipe.')
    for name in ('policy','checkpoint','identity','finished'):
        policy.require(receipt['actor'][name]==baseline.subject(root/'actor'/f'{name}.json'),
            'Transaction actor subjects must name its own exact completed child.')
    actor_identity=policy.read_json(root/'actor/identity.json');objective=actor_identity['expansionBaselineObjective']
    policy.require(all(objective[key]==identity[key] for key in
        ('batch','replica','protocol','basis','previousTransaction')) and objective['replicaProtocolSha256']==protocol_subject['sha256']
        and actor_identity['config']==identity['config'] and objective['previousBaseline']==identity['previousBaseline']
        and identity['detachedTargets']==objective['detachedTargets']==baseline.subject(root/'detached-targets.json')
        and actor_identity['data']==identity['data'] and objective['behaviorModelSha256']==identity['behaviorModel']['sha256'],
        'Outer transaction must bind the actual actor objective and unchanged behavior cohort.')
    steps,inherited=_actor_steps(root/'actor',config);actor_done=policy.read_json(root/'actor/finished.json')
    input_model=policy.read_json(baseline.check_subject(identity['behaviorModel']))
    output_model=policy.read_json(root/'actor/policy.json')
    actor_changed=any(input_model[name]!=output_model[name] for name in policy.PARAMETERS[:-2])
    policy.require(receipt['actorAdamSteps']==steps and receipt['previousActorAdamSteps']==inherited
        and receipt['acceptedEpochs']==actor_done['acceptedEpochs']
        and receipt['actorChanged']==actor_changed,
        'Transaction progress must equal the validated completed actor.')
    if batch<8:
        policy.require(receipt['nextBaseline']==baseline.subject(root/'baseline.json'),
            'A nonterminal treatment transaction must retain its next-batch baseline.')
        fitted=policy.read_json(root/'baseline.json');baseline.validate(fitted,basis)
        policy.require(fitted['provenance']==dict(batch=batch,replica=replica,protocol=protocol_subject,
            collectionSha256=identity['data']['collectionSha256'],gameHashes=identity['data']['gameHashes'],
            actorCompletion=receipt['actor']['finished']),
            'Baseline fit must follow this completed actor and use its original complete cohort.')
    else:
        policy.require(receipt['nextBaseline'] is None and not (root/'baseline.json').exists(),
            'Final batch8 must not fit an unused baseline.')
    return receipt


class ExpansionBaselineObjective:
    """Detached state predictions plus seals on every original row/prepared tensor/subject."""
    def __init__(self,collection,basis,*,batch,replica,protocol_subject,previous=None):
        policy.require(type(collection) is ExpansionCollection and type(basis) is baseline.FrozenBasis,
            'Treatment requires the exact expansion collection and frozen initial state basis.')
        policy.integer(batch,'Treatment batch',1,8)
        policy.require(replica in REPLICAS,'Treatment replica must be explicit.')
        baseline.check_subject(protocol_subject);basis.unchanged();collection.unchanged()
        policy.require((batch==1)==(previous is None),'Treatment must consume only its immediate completed predecessor.')
        self.collection=collection;self.basis=basis;self.batch=batch;self.replica=replica
        self.protocol=copy.deepcopy(protocol_subject);self.previous=None if previous is None else Path(previous).resolve()
        self.row_seals=tuple(policy.digest(_semantic(row)) for row in collection.rows)
        self.prepared=tuple(row['_prepared'] for row in collection.rows)
        self.prepared_seals=tuple(policy.digest(_prepared(value)) for value in self.prepared)
        self.collection_seal=self._collection_seal()
        self.previous_transaction=None if previous is None else baseline.subject(self.previous/'finished.json')
        self.previous_baseline=None if previous is None else baseline.subject(self.previous/'baseline.json')
        self.states=tuple(baseline.PublicState.read({key:row['observation'][key]
            for key in ('encoderVersion','stateFeatures')}) for row in collection.rows)
        self.predictions=None;self.admission=None
        self.detached_targets=None;self.prediction_seal=None;self.input_seal=self._input_seal()

    def _input_seal(self):
        return policy.digest(dict(batch=self.batch,replica=self.replica,protocol=self.protocol,
            previous=None if self.previous is None else str(self.previous),basis=self.basis.descriptor(),
            previousTransaction=self.previous_transaction,previousBaseline=self.previous_baseline,
            states=[asdict(state) for state in self.states]))

    def _collection_seal(self):
        c=self.collection
        return policy.digest(dict(model=c.model,actor_path=str(c.actor_path),actor_sha=c.actor_sha,
            directory=str(c.directory),pins=c.pins,provenance=c.provenance,ecology=c.ecology,origins=c.origins))

    def unchanged(self):
        c=self.collection;c.unchanged();self.basis.unchanged();baseline.check_subject(self.protocol)
        policy.require(self.input_seal==self._input_seal() and (self.predictions is None or
            self.prediction_seal==policy.digest(self.predictions)),
            'Detached treatment input or prediction identity changed.')
        policy.require(self.collection_seal==self._collection_seal() and len(c.rows)==len(self.row_seals),
            'Treatment collection/provenance changed after construction.')
        for row,seal,prepared,prepared_seal in zip(c.rows,self.row_seals,self.prepared,self.prepared_seals):
            policy.require(policy.digest(_semantic(row))==seal and row['_prepared'] is prepared
                and policy.digest(_prepared(prepared))==prepared_seal,
                'Treatment original rows or prepared tensors changed after construction.')
        if self.previous is not None:
            baseline.check_subject(self.previous_transaction);baseline.check_subject(self.previous_baseline)
        if self.detached_targets is not None:
            path=baseline.check_subject(self.detached_targets)
            policy.require(policy.read_json(path)==self._target_payload(),'Detached target evidence changed.')

    def validate(self,contract,config):
        policy.require(contract==mc.CONTRACT and config.valueCoefficient==config.entropyCoefficient==
            config.cardReferenceKlCoefficient==0 and config.threads==1,
            'Previous-batch expansion baseline is actor-only CPU1 with no auxiliary loss.')

    def admit(self,config):
        self.validate(mc.CONTRACT,config);self.unchanged();c=self.collection
        policy.require(c.rows,'An entirely empty treatment batch cannot fit an actor or baseline.')
        policy.require(c.model['stateFeatureNames']==list(self.basis.names),
            'Current actor must retain the original initializer state dictionary.')
        if self.previous is None:
            policy.require(c.actor_sha==self.basis.source_sha,'First treatment actor must be the exact original initializer.')
            values=[.5]*len(c.rows)
        else:
            previous=completed(self.previous,self.basis,config,batch=self.batch-1,
                replica=self.replica,protocol_subject=self.protocol)
            policy.require(baseline.subject(c.actor_path)==previous['actor']['policy'],
                'Current behavior must use the exact immediately preceding actor path and bytes.')
            values=baseline.predict(policy.read_json(self.previous_baseline['path']),self.basis,self.states)
        if self.predictions is None:
            self.predictions=tuple(values);self.prediction_seal=policy.digest(self.predictions)
        policy.require(self.predictions==tuple(values),'Detached previous-batch predictions changed.')
        self.admission=policy.digest(dict(predictions=self.predictions,config=asdict(config)))
        self.unchanged()

    def admit_optimizer(self,checkpoint,config):
        self.admit(config)
        policy.require(self.detached_targets is not None,'Detached target evidence must precede actor optimization.')
        expected=None if self.previous is None else self.previous/'actor/checkpoint.json'
        policy.require((checkpoint is None and expected is None) or
            (checkpoint is not None and expected is not None and Path(checkpoint).resolve()==expected),
            'Treatment Adam must come from its exact immediate same-replica transaction.')

    def _target_payload(self):
        c=self.collection
        return dict(kind='expansion-detached-previous-baseline-targets-1',batch=self.batch,replica=self.replica,
            protocol=self.protocol,basis=self.basis.descriptor(),previousBaseline=self.previous_baseline,
            previousTransaction=self.previous_transaction,collectionSha256=c.provenance['collectionSha256'],
            gameHashes=c.provenance['gameHashes'],rows=[dict(originIndex=row['originIndex'],number=row['number'],
                prediction=value,advantage=row['return']-value) for row,value in zip(c.rows,self.predictions)])

    def persist_targets(self,path):
        self.unchanged();policy.require(self.admission is not None and self.detached_targets is None
            and not Path(path).exists(),'Detached targets require admitted inputs and a new evidence path.')
        policy.atomic_json(path,self._target_payload());self.detached_targets=baseline.subject(path);self.unchanged()

    def phase_reader(self,directory,model_path,model,*,contract):
        self.unchanged();policy.require(self.admission is not None,'Treatment needs detached admission before row reading.')
        rows,provenance=self.collection.phase_reader(directory,model_path,model,contract=contract)
        return [dict(row,_actorOnlyAdvantage=row['return']-value) for row,value in zip(rows,self.predictions)],dict(
            provenance,readerSha256=policy.file_digest(__file__))

    def bind(self,rows,provenance,model_sha):
        self.unchanged();c=self.collection
        policy.require(model_sha==c.actor_sha and provenance==dict(c.provenance,readerSha256=policy.file_digest(__file__))
            and len(rows)==len(c.rows),'Treatment must retain its exact complete behavior cohort.')
        for row,original,value in zip(rows,c.rows,self.predictions):
            policy.require(row.get('_prepared') is original['_prepared'] and
                {k:v for k,v in row.items() if k!='_actorOnlyAdvantage'}==original and
                row.get('_actorOnlyAdvantage')==original['return']-value,
                'Treatment cannot replace original observations, likelihood/value, returns or detached advantages.')
        identity=dict(version=VERSION,phase='expansion',arm='TREATMENT',batch=self.batch,replica=self.replica,
            protocol=self.protocol,replicaProtocolSha256=self.protocol['sha256'],basis=self.basis.descriptor(),
            estimator=copy.deepcopy(ESTIMATOR),previousTransaction=self.previous_transaction,
            previousBaseline=self.previous_baseline,detachedTargets=self.detached_targets,predictionsSha256=policy.digest(self.predictions),
            targetsSha256=policy.digest([row['_actorOnlyAdvantage'] for row in rows]),
            ecology=c.ecology,collectionSha256=provenance['collectionSha256'],gameHashes=provenance['gameHashes'],
            behaviorModelSha256=model_sha,frozenParameters=['valueWeights','valueBias'],optimizerInitialization='fresh-adam')
        return rows,copy.deepcopy(identity)


@dataclass(frozen=True)
class ExpansionBaselineAdamContinuation:
    previous: Path

    def validate(self,checkpoint_path,objective,model,config):
        root=Path(self.previous).resolve()
        policy.require(Path(checkpoint_path).resolve()==root/'actor/checkpoint.json'
            and objective['previousTransaction']==baseline.subject(root/'finished.json')
            and objective['previousBaseline']==baseline.subject(root/'baseline.json'),
            'Treatment carry must bind its exact completed previous transaction and baseline.')
        checkpoint,identity=policy.actor_adam_checkpoint(checkpoint_path,model)
        old=identity.get('expansionBaselineObjective',{})
        policy.require(old.get('version')==VERSION and old.get('batch')==objective['batch']-1 and
            all(old.get(key)==objective[key] for key in ('version','phase','arm','replica','protocol',
                'replicaProtocolSha256','basis','estimator','ecology','frozenParameters')),
            'Treatment carry cannot cross replica, protocol, basis, cohort order, phase or ecology.')
        steps,_=_actor_steps(root/'actor',config)
        return dict(version=CARRY,phase='expansion',arm='TREATMENT',replica=objective['replica'],
            replicaProtocolSha256=objective['replicaProtocolSha256'],batch=objective['batch'],
            actorAdamSteps=steps,checkpointSha256=policy.file_digest(checkpoint_path),
            previousIdentitySha256=policy.digest(identity),previousTransaction=objective['previousTransaction'])


def update(collection,basis,config,output,*,batch,replica,protocol_subject,previous=None):
    """One actor attempt followed by next-batch ridge; only the last receipt publishes both."""
    objective=ExpansionBaselineObjective(collection,basis,batch=batch,replica=replica,
        protocol_subject=protocol_subject,previous=previous)
    checkpoint=None if previous is None else Path(previous).resolve()/'actor/checkpoint.json'
    objective.admit(config)
    output=Path(output).resolve();policy.require(not output.exists(),'Treatment requires a new output directory.')
    _,provenance=objective.phase_reader(collection.directory,collection.actor_path,collection.model,contract=mc.CONTRACT)
    output.mkdir(parents=True);objective.persist_targets(output/'detached-targets.json')
    identity=dict(kind=TRANSACTION,arm='TREATMENT',batch=batch,replica=replica,protocol=objective.protocol,
        basis=basis.descriptor(),config=asdict(config),data=provenance,behaviorModel=baseline.subject(collection.actor_path),
        previousTransaction=objective.previous_transaction,previousBaseline=objective.previous_baseline,
        detachedTargets=objective.detached_targets)
    policy.atomic_json(output/'identity.json',identity)
    report=policy.update(collection.actor_path,collection.directory,config,output/'actor',contract=mc.CONTRACT,
        entrypoint=__file__,collection_reader=objective.phase_reader,expansion_baseline=objective,optimizer_from=checkpoint,
        expansion_baseline_continuation=None if previous is None else ExpansionBaselineAdamContinuation(Path(previous)))
    objective.unchanged();steps,inherited=_actor_steps(output/'actor',config)
    actor={name:baseline.subject(output/'actor'/f'{name}.json') for name in ('policy','checkpoint','identity','finished')}
    next_baseline=None
    if batch<8:
        provenance=dict(batch=batch,replica=replica,protocol=objective.protocol,
            collectionSha256=collection.provenance['collectionSha256'],gameHashes=collection.provenance['gameHashes'],
            actorCompletion=actor['finished'])
        fitted=baseline.fit(basis,objective.states,[row['return'] for row in collection.rows],
            [row['originIndex'] for row in collection.rows],len(collection.origins),provenance)
        baseline.validate(fitted,basis);policy.atomic_json(output/'baseline.json',fitted)
        next_baseline=baseline.subject(output/'baseline.json')
    objective.unchanged()
    output_model=policy.read_json(output/'actor/policy.json')
    receipt=dict(kind=TRANSACTION,complete=True,arm='TREATMENT',batch=batch,replica=replica,protocol=objective.protocol,
        identity=baseline.subject(output/'identity.json'),actor=actor,nextBaseline=next_baseline,
        previousTransaction=objective.previous_transaction,acceptedEpochs=report['acceptedEpochs'],
        actorChanged=any(collection.model[name]!=output_model[name]
            for name in policy.PARAMETERS[:-2]),actorAdamSteps=steps,
        previousActorAdamSteps=inherited,artifacts=_inventory(output))
    completed(output,basis,config,batch=batch,replica=replica,protocol_subject=protocol_subject,_receipt=receipt)
    policy.atomic_json(output/'finished.json',receipt)
    return receipt
