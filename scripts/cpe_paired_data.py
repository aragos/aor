"""Immutable phase-specific public inputs and detached paired returns; no archive readers."""
from dataclasses import dataclass
import hashlib
import json
import math

import purchase_policy as b
import cpe_paired_policy as actor
from token_paired_data import exact, probabilities, sha


@dataclass(frozen=True)
class Context:
    """One head's immutable corpus; all hash fields are lowercase SHA-256 subjects.

    source_sha256 is the assembled corpus receipt, not a shard source signature.
    scope is the native head scope; bundle_scope binds all original heads and ecology.
    Bundle hashes bind unchanged portable descriptor bytes; raw dictionary bytes and
    canonical complete dictionary identity remain distinct subjects.
    """
    head: actor.Head
    iteration: int
    source_sha256: str
    scope_sha256: str
    bundle_scope_sha256: str
    ecology_sha256: str
    request_bank_sha256: str
    dictionary_sha256: str
    dictionary_semantic_sha256: str
    original_model_sha256: str
    current_model_sha256: str
    original_bundle_sha256: str
    current_bundle_sha256: str

    def __post_init__(self):
        b.require(isinstance(self.head, actor.Head), 'CPE context requires an explicit typed head.')
        b.integer(self.iteration, 'Iteration', 1, 2)
        for key,value in vars(self).items():
            if key not in ('head','iteration'):sha(value,key)
        b.require(self.scope_sha256==b.digest(dict(bundleScopeSha256=self.bundle_scope_sha256,head=self.head.value)),
            'Head scope must derive from the complete original bundle scope and explicit head.')

    @classmethod
    def from_record(cls, record):
        return cls(**dict(record,head=actor.Head(record['head'])))

    def bind(self, original_bundle, current_bundle, dictionary):
        b.require(isinstance(original_bundle,actor.Bundle) and isinstance(current_bundle,actor.Bundle) and
            original_bundle.sha256 == self.original_bundle_sha256 and
            current_bundle.sha256 == self.current_bundle_sha256,
            'Each phase must bind both exact complete C/P/E bundles.')
        original_bundle.original()
        original=original_bundle.actor(self.head);current=current_bundle.actor(self.head)
        b.require(original.sha256 == self.original_model_sha256 and current.sha256 == self.current_model_sha256 and
            original.dictionary_sha256 == current.dictionary_sha256 == self.dictionary_semantic_sha256,
            'Head/context/model/dictionary identity changed.')
        b.require(type(dictionary) is bytes and hashlib.sha256(dictionary).hexdigest() == self.dictionary_sha256 and
            b.digest(json.loads(dictionary)) == self.dictionary_semantic_sha256,
            'Raw and canonical complete dictionary subjects must both match.')
        exact(json.loads(dictionary),('encoderVersion','stateFeatureNames','actionFeatureNames'),'Native head dictionary')
        for head in actor.HEADS:
            old=original_bundle.actor(head).model;new=current_bundle.actor(head).model
            b.require({k:v for k,v in old.items() if k not in b.PARAMETERS[:-2]} ==
                {k:v for k,v in new.items() if k not in b.PARAMETERS[:-2]},
                'All heads must preserve their original value parameters, metadata and dictionaries.')
        b.require(self.iteration != 1 or original_bundle.raw == current_bundle.raw,
            'Iteration1 consumes the exact original complete bundle.')


def observation(raw, model, head):
    exact(raw,('encoderVersion','stateFeatures','actions','baselineIndex'),'Public head observation')
    for item in raw['actions']:exact(item,('command','features'),'Offered action')
    b.validate_observation(raw,model,contract=head.contract)


ROOT_FIELDS=('head','index','split','status','requestSha256','decisionNumber','batchStartNumber',
    'observation','currentProbabilities','originalProbabilities','sampledIndex','referenceIndex',
    'proposedIndices','inclusionProbability','futureSeeds')


@dataclass(frozen=True,init=False)
class PublicRoot:
    context: Context
    wire: bytes

    @classmethod
    def from_record(cls, record, context, model, *, split):
        return cls(record,context,model,split=split)

    def __init__(self, record, context, model, *, split):
        b.require(split in ('TRAIN','VALIDATION') and isinstance(record,dict) and record.get('split') == split,
            'Public split must be admitted before decoding observation or labels.')
        b.require(isinstance(context,Context) and record.get('head') == context.head.value and
            b.digest({key:model[key] for key in ('encoderVersion','stateFeatureNames','actionFeatureNames')}) ==
                context.dictionary_semantic_sha256, 'Public root requires its exact head and complete dictionary.')
        actor.validate_model(model,context.head);exact(record,ROOT_FIELDS,'CPE public root')
        index=b.integer(record['index'],'World index within head/iteration',0,639)
        b.require((index<512) == (split=='TRAIN'),'Prospective head/world split changed.')
        sha(record['requestSha256'],'Original complete request')
        seeds=record['futureSeeds']
        b.require(isinstance(seeds,list) and len(seeds)==2 and seeds[0]!=seeds[1],
            'Each root requires its two original independent paired streams.')
        for seed in seeds:b.integer(seed,'Future seed',-(2**63),2**63-1)
        status=record['status'];b.require(status in ('READY','MISSING'),
            'CPE reservoir roots are branching READY or MISSING; singleton states are anchors only.')
        if status=='MISSING':
            retained={'head','index','split','status','requestSha256','futureSeeds'}
            b.require(all(record[key] is None for key in ROOT_FIELDS if key not in retained),
                'Missing roots cannot invent observations, choices or probabilities.')
        else:
            number=b.integer(record['decisionNumber'],'Absolute decision',0,2**53)
            b.integer(record['batchStartNumber'],'Sealed batch start',0,number)
            view=record['observation'];observation(view,model,context.head);count=len(view['actions'])
            b.require(count>1,'A selected CPE reservoir root requires at least two complete original offers.')
            b.integer(record['sampledIndex'],'Actual current sampled action',0,count-1)
            b.require(record['referenceIndex']==record['sampledIndex'],
                'Delta reference must be the recorded current sample, independently of baselineIndex.')
            probabilities(record['currentProbabilities'],count,'Current full distribution')
            probabilities(record['originalProbabilities'],count,'Original full distribution')
            if context.head is actor.Head.EXPANSION:
                b.require(record['originalProbabilities']==b.prior_probabilities(count,view['baselineIndex']),
                    'Original E distribution must preserve the explicit scoped reference prior.')
            size=min(8,count-1);indices=record['proposedIndices']
            b.require(isinstance(indices,list) and len(indices)==size+1 and len(set(indices))==len(indices) and
                indices[0]==record['referenceIndex'],'Preserve reference first and every uniform sampled alternate.')
            for action in indices:b.integer(action,'Original action index',0,count-1)
            b.require(record['inclusionProbability']==(size/(count-1) if count>1 else 0.),
                'Paired inclusion probability must retain the exact uniform subset.')
        object.__setattr__(self,'context',context);object.__setattr__(self,'wire',b.canonical(record))

    @property
    def record(self):return json.loads(self.wire)
    @property
    def sha256(self):return hashlib.sha256(self.wire).hexdigest()
    @property
    def ready(self):return self.record['status']=='READY'


@dataclass(frozen=True)
class PairedTarget:
    public: PublicRoot
    rewards: tuple

    def __post_init__(self):
        b.require(isinstance(self.public,PublicRoot) and self.public.ready and type(self.rewards) is tuple and
            len(self.rewards)==len(self.public.record['proposedIndices']),
            'Paired targets retain all original siblings of one typed READY root.')
        for values in self.rewards:
            b.require(type(values) is tuple and len(values)==2,'Every action requires exactly two paired futures.')
            for value in values:b.number(value,'Original normalized terminal reward',0.,1.)

    @classmethod
    def from_record(cls, public, record, *, split='TRAIN'):
        b.require(isinstance(public,PublicRoot) and public.record['split']==split and
            split in ('TRAIN','VALIDATION'),'Split admission precedes semantic labels.')
        b.require(public.ready,'Missing/singleton roots cannot invent paired labels.')
        exact(record,('publicSha256','futureSeeds','actions'),'Detached paired labels')
        source=public.record
        b.require(record['publicSha256']==public.sha256 and record['futureSeeds']==source['futureSeeds'],
            'Labels must bind exact public input and original paired streams.')
        b.require(isinstance(record['actions'],list) and len(record['actions'])==len(source['proposedIndices']),
            'Every original reference/alternate sibling is required.')
        rewards=[]
        for index,item in zip(source['proposedIndices'],record['actions']):
            exact(item,('actionIndex','rewards'),'Paired action return')
            b.require(item['actionIndex']==index and isinstance(item['rewards'],list) and len(item['rewards'])==2,
                'Original sibling order and both future returns must remain complete.')
            rewards.append(tuple(b.number(value,'Normalized reward',0.,1.) for value in item['rewards']))
        return cls(public,tuple(rewards))

    @property
    def deltas(self):
        return tuple(math.fsum(value-base for value,base in zip(values,self.rewards[0]))/2 for values in self.rewards)


@dataclass(frozen=True)
class AnchorWorld:
    head: actor.Head
    original_model_sha256: str
    index: int
    observations: tuple
    subject_sha256: str

    def __post_init__(self):
        b.require(isinstance(self.head,actor.Head),'Anchor world requires its explicit head.')
        sha(self.original_model_sha256,'Anchor original actor');b.integer(self.index,'Original TRAIN world',0,511)
        b.require(type(self.observations) is tuple and all(type(row) is bytes for row in self.observations),
            'Original anchor observations must be immutable bytes.')
        b.require(self.subject_sha256==b.digest(dict(head=self.head.value,originalModelSha256=self.original_model_sha256,
            index=self.index,split='TRAIN',observations=[json.loads(row) for row in self.observations])),
            'Complete original anchor group and actor identity changed.')

    @classmethod
    def from_record(cls, record, original):
        b.require(isinstance(record,dict) and record.get('split')=='TRAIN',
            'Reject non-TRAIN anchors before observation decoding.')
        exact(record,('head','originalModelSha256','index','split','observations'),'Original head anchor world')
        b.require(isinstance(original,actor.FrozenActor) and record['head']==original.head.value and
            record['originalModelSha256']==original.sha256,'Anchor group must bind its original head actor.')
        index=b.integer(record['index'],'Original TRAIN world',0,511);keys=set();rows=[]
        b.require(isinstance(record['observations'],list),'Anchor group requires its full recorded row list.')
        for row in record['observations']:
            exact(row,('decisionNumber','batchStartNumber','observation','originalProbabilities'),'Original public anchor state')
            number=b.integer(row['decisionNumber'],'Anchor absolute decision',0,2**53)
            start=b.integer(row['batchStartNumber'],'Anchor sealed batch start',0,number)
            b.require((number,start) not in keys,'Original anchor state identities cannot repeat.')
            keys.add((number,start));view=row['observation'];observation(view,original.model,original.head)
            probabilities(row['originalProbabilities'],len(view['actions']),'Original anchor full distribution')
            if original.head is actor.Head.EXPANSION:
                b.require(row['originalProbabilities']==b.prior_probabilities(len(view['actions']),view['baselineIndex']),
                    'Original E anchors must retain their scoped prior.')
            rows.append(b.canonical(row))
        return cls(original.head,original.sha256,index,tuple(rows),b.digest(record))


@dataclass(frozen=True)
class Training:
    context: Context
    roots: tuple
    targets: tuple
    anchors: tuple
    validation_ready_count: int

    def __post_init__(self):
        b.integer(self.validation_ready_count,'Public validation READY roots',64,128)
        b.require(isinstance(self.context,Context) and all(type(v) is tuple for v in (self.roots,self.targets,self.anchors)),
            'CPE training requires typed context and immutable complete populations.')
        b.require(len(self.roots)==len(self.targets)==len(self.anchors)==512,
            'Retain all512 requested TRAIN positions, including missing/empty worlds.')
        for index,(root,target,anchor) in enumerate(zip(self.roots,self.targets,self.anchors)):
            b.require(isinstance(root,PublicRoot) and root.context==self.context and
                root.record['index']==index and root.record['split']=='TRAIN' and
                isinstance(anchor,AnchorWorld) and anchor.head is self.context.head and anchor.index==index and
                anchor.original_model_sha256==self.context.original_model_sha256,
                'Head, order, split, source and original anchor actor must be consistent.')
            b.require((isinstance(target,PairedTarget) and target.public==root) if root.ready else target is None,
                'READY roots require complete detached returns; missing/singleton roots remain unlabeled.')
            if self.context.iteration==1 and root.record['status']!='MISSING':
                record=root.record
                matches=[json.loads(row) for row in anchor.observations if
                    json.loads(row)['decisionNumber']==record['decisionNumber'] and
                    json.loads(row)['batchStartNumber']==record['batchStartNumber']]
                b.require(len(matches)==1 and all(matches[0][key]==record[key] for key in ('observation','originalProbabilities')),
                    'Iteration1 root must match one original backbone state without duplicated anchor weight.')
        b.require(len({root.record['requestSha256'] for root in self.roots})==512 and
            len({seed for root in self.roots for seed in root.record['futureSeeds']})==1024,
            'Every head/world request and paired future must retain independent original identity.')

    @property
    def ready_count(self):return sum(root.ready for root in self.roots)
    @property
    def anchor_sha256(self):return b.digest([row.subject_sha256 for row in self.anchors])
    @property
    def sha256(self):
        return b.digest(dict(context=vars(self.context),roots=[root.sha256 for root in self.roots],
            labels=[None if target is None else target.rewards for target in self.targets],
            anchors=self.anchor_sha256,validationReadyCount=self.validation_ready_count))
