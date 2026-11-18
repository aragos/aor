"""Frozen all-layer factor map and raw residual reference; no simulation or optimizer."""
from dataclasses import dataclass
import hashlib
import json
import math

import numpy as np
import torch
import card_policy
import expansion_mc
import purchase_policy as b
import token_paired_policy
import coordinated_es_math as es

BLOCKS=('stateLeft','stateRight','actionLeft','actionRight','stateBias','actionBias','actorWeights')
RECIPE=dict(kind='coordinated-es-factor-map-1',coordinatesPerHead=64,hiddenSize=64,
    factorDistribution='independent-standard-normal',generator='numpy-PCG64',
    drawOrder=list(BLOCKS),headOrder=[head.value for head in es.HEADS],
    factorRowNormalization='Float64 row/sqrt(mean(row*row)); zero/nonfinite stop, no redraw',
    coordinateScale=1/8,stateMatrixScale='1/sqrt(stateFeatureCount)',
    actionMatrixScale='1/sqrt(actionFeatureCount)',stateBiasScale=1/8,actionBiasScale=1/8,readoutScale=1/8)


def contract(head):
    es.require(isinstance(head,es.Head),'An explicit four-head identifier is required.')
    return {es.Head.CARD:card_policy.CONTRACT,es.Head.PURCHASE:b.PURCHASE_CONTRACT,
        es.Head.EXPANSION:expansion_mc.CONTRACT,es.Head.TOKEN:token_paired_policy.CONTRACT}[head]


def validate_model(model,head):
    b.validate_model(model,contract=contract(head))
    if head is es.Head.TOKEN:token_paired_policy.validate_model(model)
    return model


def dictionary_sha(model):
    # Native dictionary semantics use canonical JSON without formatting or a trailing LF.
    return es.digest({key:model[key] for key in ('encoderVersion','stateFeatureNames','actionFeatureNames')})


@dataclass(frozen=True)
class Original:
    head: es.Head
    raw: bytes
    sha256: str

    def __post_init__(self):
        es.require(type(self.raw) is bytes and hashlib.sha256(self.raw).hexdigest()==es.sha(self.sha256,'Original actor'),
            'Original actors require their unchanged raw model subject.')
        model=validate_model(json.loads(self.raw),self.head)
        if self.head in (es.Head.CARD,es.Head.PURCHASE):
            es.require(any(value!=0 for value in model['actorWeights']),
                'Original C/P must retain nonzero qualified actors, not a zero-output prior alias.')

    @property
    def model(self):return json.loads(self.raw)


def originals(values):
    es.require(type(values) is dict and set(values)==set(es.HEADS) and
        all(isinstance(values[head],Original) and values[head].head is head for head in es.HEADS),
        'Exactly four explicit immutable original actors are required.')
    return values


def seed(value):
    es.require(type(value) is int and 1<=value<2**31,'A prospectively reserved positive integer seed is required.')
    return value


def shapes(states,actions):
    return dict(stateLeft=(64,64),stateRight=(64,states),actionLeft=(64,64),
        actionRight=(64,actions),stateBias=(64,64),actionBias=(64,64),actorWeights=(64,64))


def normalize_rows(values):
    array=np.asarray(values,dtype=np.float64)
    es.require(array.ndim==2 and array.shape[0]==64 and array.shape[1]>0,'Factor arrays require64 nonempty rows.')
    try:
        with np.errstate(over='raise',invalid='raise',divide='raise'):
            norm=np.sqrt(np.mean(array*array,axis=1))
            if not np.isfinite(array).all() or not np.isfinite(norm).all() or np.any(norm==0):
                raise es.OperationalStop('projection-zero-or-nonfinite-factor')
            return array/norm[:,None]
    except FloatingPointError as failure:
        raise es.OperationalStop('projection-zero-or-nonfinite-factor') from failure


@dataclass(frozen=True)
class Projection:
    wire: bytes

    def __post_init__(self):
        es.require(type(self.wire) is bytes,'A fixed projection is immutable serialized evidence.')
        value=json.loads(self.wire)
        es.exact(value,('kind','scopeSha256','originalBundleSha256','recipe','projectionSeed','numpyVersion','heads'),
            'ES projection')
        es.require(value['kind']=='coordinated-es-projection-1' and value['recipe']==RECIPE,
            'Projection dimensions, block scales and factor rules must equal the frozen recipe.')
        es.sha(value['scopeSha256'],'Original scientific scope');es.sha(value['originalBundleSha256'],'Original bundle')
        seed(value['projectionSeed'])
        es.require(isinstance(value['numpyVersion'],str) and value['numpyVersion'],'Projection generator version must be retained.')
        es.require(type(value['heads']) is dict and set(value['heads'])=={head.value for head in es.HEADS},
            'The fixed map contains exactly all four heads.')
        for head in es.HEADS:
            row=value['heads'][head.value]
            es.exact(row,('modelSha256','dictionarySha256','stateCount','actionCount','factors'),'Projection head')
            es.sha(row['modelSha256'],'Projection original actor');es.sha(row['dictionarySha256'],'Projection complete dictionary')
            es.require(type(row['stateCount']) is int and 1<=row['stateCount']<=8192 and
                type(row['actionCount']) is int and 1<=row['actionCount']<=512,'Projection feature counts must be complete and bounded.')
            es.exact(row['factors'],BLOCKS,'Projection factors')
            for name,shape in shapes(row['stateCount'],row['actionCount']).items():
                factor=row['factors'][name]
                es.require(type(factor) is list and len(factor)==shape[0] and all(type(r) is list and len(r)==shape[1]
                    and all(type(x) in (float,int) and math.isfinite(x) for x in r) for r in factor),
                    'Each factor retains its exact finite Float64 shape.')
                array=np.asarray(factor,dtype=np.float64)
                with np.errstate(over='ignore',invalid='ignore'):
                    norm=np.sqrt(np.mean(array*array,axis=1))
                es.require(np.isfinite(norm).all() and np.max(np.abs(norm-1))<=2e-12,
                    'Serialized factor rows must retain their original RMS1 normalization.')
        es.require(es.canonical(value)==self.wire,'Projection identity is exact canonical JSON without LF.')

    @property
    def record(self):return json.loads(self.wire)

    @property
    def sha256(self):return hashlib.sha256(self.wire).hexdigest()

    @property
    def scope(self):
        value=self.record
        return es.Scope(value['scopeSha256'],value['originalBundleSha256'],self.sha256)

    @classmethod
    def from_record(cls,value):return cls(es.canonical(value))


def generate(original,scope_sha256,original_bundle_sha256,projection_seed):
    """Source preparation only: explicit local PCG64, fixed order, never a global RNG."""
    originals(original);es.sha(scope_sha256,'Original scientific scope');es.sha(original_bundle_sha256,'Original bundle')
    rng=np.random.Generator(np.random.PCG64(seed(projection_seed)));heads={}
    for head in es.HEADS:
        model=original[head].model;states=len(model['stateFeatureNames']);actions=len(model['actionFeatureNames'])
        factors={name:normalize_rows(rng.standard_normal(shape,dtype=np.float64)).tolist()
            for name,shape in shapes(states,actions).items()}
        heads[head.value]=dict(modelSha256=original[head].sha256,dictionarySha256=dictionary_sha(model),
            stateCount=states,actionCount=actions,factors=factors)
    return Projection.from_record(dict(kind='coordinated-es-projection-1',scopeSha256=scope_sha256,
        originalBundleSha256=original_bundle_sha256,recipe=RECIPE,projectionSeed=projection_seed,
        numpyVersion=np.__version__,heads=heads))


def generate_directions(scope,generation,direction_seed):
    rng=np.random.Generator(np.random.PCG64(seed(direction_seed)))
    table=es.DirectionTable(scope,generation,tuple(map(tuple,rng.standard_normal((16,256),dtype=np.float64).tolist())))
    return table,dict(kind='coordinated-es-direction-generation-1',generation=generation,
        seed=direction_seed,generator='numpy-PCG64',distribution='independent-standard-normal',
        shape=[16,256],renormalized=False,numpyVersion=np.__version__,directionsSha256=es.digest(table.record))


def materialize(projection,original,coordinates):
    """Materialize all five actor blocks; values/metadata remain those of the originals."""
    es.require(isinstance(projection,Projection),'Only the frozen typed factor map can produce actors.')
    originals(original);z=es.vector(coordinates,256,'projection-coordinate');value=projection.record;result={}
    for index,head in enumerate(es.HEADS):
        source=original[head];model=source.model;row=value['heads'][head.value]
        es.require(source.sha256==row['modelSha256'] and dictionary_sha(model)==row['dictionarySha256'],
            'Materialization must use the exact original head and complete feature dictionary.')
        local=np.asarray(z[index*64:(index+1)*64],dtype=np.float64)
        if not np.any(local):
            result[head]=source.raw
            continue
        f={name:np.asarray(rows,dtype=np.float64) for name,rows in row['factors'].items()}
        matrices=(('stateWeights','stateLeft','stateRight',row['stateCount']),
                  ('actionWeights','actionLeft','actionRight',row['actionCount']))
        with np.errstate(over='raise',invalid='raise'):
            try:
                for name,left,right,width in matrices:
                    # optimize=False uses the fixed contraction without a threaded BLAS dispatch.
                    delta=np.einsum('ki,k,kj->ij',f[left],local,f[right],optimize=False)/(8*math.sqrt(width))
                    model[name]=(np.asarray(model[name],dtype=np.float64).reshape(64,width)+delta).reshape(-1).tolist()
                for name in ('stateBias','actionBias','actorWeights'):
                    delta=np.einsum('ki,k->i',f[name],local,optimize=False)/64
                    model[name]=(np.asarray(model[name],dtype=np.float64)+delta).tolist()
            except FloatingPointError as failure:
                raise es.OperationalStop('nonfinite-projected-actor') from failure
        validate_model(model,head)
        es.require(all(model[name]==source.model[name] for name in ('valueWeights','valueBias')),
            'The fixed projection never alters value arrays.')
        result[head]=es.canonical(model)
    return result


def raw_residuals(model,head,observation):
    """No old sampled prior and NO ±20 clamp; this is the new E/T r(theta,s,a)."""
    validate_model(model,head)
    es.exact(observation,('encoderVersion','stateFeatures','baselineIndex','actions'),'Public ES observation')
    for action in observation['actions']:es.exact(action,('command','features'),'Public ES action')
    b.validate_observation(observation,model,contract=contract(head))
    state=np.asarray([observation['stateFeatures'].get(name,0.) for name in model['stateFeatureNames']],dtype=np.float64)
    actions=np.asarray([[row['features'].get(name,0.) for name in model['actionFeatureNames']]
        for row in observation['actions']],dtype=np.float64)
    ws=np.asarray(model['stateWeights'],dtype=np.float64).reshape(64,-1)
    wa=np.asarray(model['actionWeights'],dtype=np.float64).reshape(64,-1)
    with np.errstate(over='raise',invalid='raise'):
        try:
            hidden=np.tanh(np.einsum('ij,j->i',ws,state,optimize=False)+model['stateBias'])
            action_hidden=np.tanh(hidden+np.einsum('ij,aj->ai',wa,actions,optimize=False)+model['actionBias'])
            residual=np.einsum('ai,i->a',action_hidden,np.asarray(model['actorWeights']),optimize=False)
        except FloatingPointError as failure:
            raise es.OperationalStop('nonfinite-raw-residual') from failure
    if not np.isfinite(residual).all():raise es.OperationalStop('nonfinite-raw-residual')
    return residual.tolist()


class RawPolicy(b.ResidualPolicy):
    """Prepared public observations and existing Float64 hidden arithmetic, without a prior.

    This new selector never calls the old clipped sampled-policy forward. It has no
    optimizer, trainable parameters, value evaluation or observation/outcome callback.
    Prepared sparse inputs can be shared across the exact same dictionary's probes.
    """
    def __init__(self,model,head):
        super().__init__(validate_model(model,head),contract(head))
        for parameter in self.parameters():parameter.requires_grad_(False)
        es.require(all(p.dtype==torch.float64 and p.device.type=='cpu' for p in self.parameters()),
            'Public calibration uses only the explicitly guarded CPU Float64 owner.')

    def public(self,observation):
        es.exact(observation,('encoderVersion','stateFeatures','baselineIndex','actions'),'Public ES observation')
        for action in observation['actions']:es.exact(action,('command','features'),'Public ES action')
        return self.prepare(observation)

    def raw(self,prepared):
        es.require(1<=len(prepared)<=256 and sum(item.action_count for item in prepared)<=16384,
            'A raw residual batch retains the existing256-state/16384-action bound.')
        prepared=[self.prepare(item) for item in prepared]
        with torch.inference_mode():
            state,action,owners=self._batch_inputs(prepared)
            hidden=torch.tanh(state @ self.stateWeights.T+self.stateBias)
            action_hidden=torch.tanh(hidden[owners]+action @ self.actionWeights.T+self.actionBias)
            residual=action_hidden @ self.actorWeights
            if not torch.isfinite(residual).all():raise es.OperationalStop('nonfinite-raw-residual')
            return [row.tolist() for row in torch.split(residual,[item.action_count for item in prepared])]


def raw_all(actor,prepared,guard=lambda:None):
    """Bound memory by complete offers; preserve their exact original state/action order."""
    result=[];batch=[];actions=0
    for item in prepared:
        if batch and (len(batch)==256 or actions+item.action_count>16384):
            guard();result.extend(actor.raw(batch));batch=[];actions=0
        batch.append(item);actions+=item.action_count
    if batch:guard();result.extend(actor.raw(batch))
    return result
