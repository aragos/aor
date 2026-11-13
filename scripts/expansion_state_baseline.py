"""Fixed original-initializer state basis and deterministic next-batch value baseline.

The objective is SUM_world mean_rows(error**2) + ||slopes||**2. Row weights
sum to the number of nonempty worlds, not one. The intercept is unpenalized;
there is no feature standardization, target clipping, random initialization or
action input. Only predictions are clipped, after the linear65 value is formed.
"""
from collections import Counter
import copy
from dataclasses import dataclass
from pathlib import Path

import numpy as np

import expansion_mc as mc
import purchase_policy as policy

VERSION='expansion-frozen-state-ridge-1'
RECIPE=dict(hiddenSize=64,penalty=1.,interceptPenalty=0.,featureScaling='none',
    loss='sum-world-mean-row-squared-error-plus-slope-l2',rowWeight='1/rows-in-origin',
    predictionClip=[0.,1.],targetClip=False,randomInitialization=False)


def subject(path):
    path=Path(path).resolve(strict=True)
    return dict(path=str(path),sha256=policy.file_digest(path))


def check_subject(value):
    policy.require(isinstance(value,dict) and set(value)=={'path','sha256'} and
        Path(value['path']).is_absolute() and policy.file_digest(value['path'])==value['sha256'],
        'A baseline subject must retain its exact original absolute path and bytes.')
    return Path(value['path'])


@dataclass(frozen=True)
class PublicState:
    """Only the recorded pre-action encoder/state map; no action or row metadata."""
    encoder: str
    features: tuple

    @classmethod
    def read(cls,value):
        policy.require(isinstance(value,dict) and set(value)=={'encoderVersion','stateFeatures'}
            and value['encoderVersion']==mc.CONTRACT.encoder and isinstance(value['stateFeatures'],dict),
            'The detached baseline accepts only original expansion-v2 state features.')
        policy.require(all(isinstance(name,str) for name in value['stateFeatures']),'State feature names must be strings.')
        features=tuple(sorted((name,policy.number(v,'Frozen state feature',-16,16))
            for name,v in value['stateFeatures'].items()))
        policy.require(all(isinstance(name,str) for name,_ in features),'State feature names must be strings.')
        return cls(value['encoderVersion'],features)


@dataclass(frozen=True)
class FrozenBasis:
    """Raw original initializer columns, sealed independently from each changing actor."""
    source_path: str
    source_sha: str
    names: tuple
    weights: tuple
    bias: tuple
    seal: str

    @classmethod
    def load(cls,source):
        path=check_subject(source);model=policy.validate_model(policy.read_json(path),contract=mc.CONTRACT)
        policy.require(all(v==0 for v in model['actorWeights']+model['valueWeights']) and model['valueBias']==0,
            'The baseline basis must come from the prospective zero-residual initializer, never a trained actor.')
        value=dict(names=tuple(model['stateFeatureNames']),weights=tuple(model['stateWeights']),bias=tuple(model['stateBias']))
        result=cls(str(path),source['sha256'],**value,seal=policy.digest(value));result.unchanged();return result

    def unchanged(self):
        policy.require(policy.file_digest(self.source_path)==self.source_sha and self.seal==policy.digest(
            dict(names=self.names,weights=self.weights,bias=self.bias)),
            'The original initializer state basis changed.')

    def descriptor(self):
        self.unchanged()
        return dict(kind='expansion-original-state-basis-1',initialModel=dict(path=self.source_path,sha256=self.source_sha),
            encoderVersion=mc.CONTRACT.encoder,hiddenSize=64,stateFeatureNamesSha256=policy.digest(self.names),
            stateWeightsBiasSha256=policy.digest(dict(weights=self.weights,bias=self.bias)))

    def hidden(self,states):
        self.unchanged();positions={name:i for i,name in enumerate(self.names)}
        weights=np.asarray(self.weights,dtype=np.float64).reshape(64,len(self.names));bias=np.asarray(self.bias,dtype=np.float64)
        result=np.empty((len(states),64),dtype=np.float64)
        for start in range(0,len(states),128):
            batch=states[start:start+128];dense=np.zeros((len(batch),len(self.names)),dtype=np.float64)
            for row,state in enumerate(batch):
                policy.require(type(state) is PublicState and state.encoder==mc.CONTRACT.encoder
                    and len({name for name,_ in state.features})==len(state.features),
                    'Baseline inference requires the explicit public state type.')
                for name,value in state.features:
                    policy.require(name in positions,'State feature is outside the original initializer dictionary.')
                    dense[row,positions[name]]=policy.number(value,'Public state value',-16,16)
            result[start:start+len(batch)]=np.tanh(dense@weights.T+bias)
        policy.require(np.isfinite(result).all(),'Frozen hidden states must remain finite.')
        self.unchanged();return result


def weighted_ridge(hidden,rewards,origins,total_worlds):
    """Pure Float64 closed form. Empty worlds contribute no invented observations."""
    x=np.asarray(hidden,dtype=np.float64);y=np.asarray(rewards,dtype=np.float64)
    policy.require(type(total_worlds) is int and total_worlds>0 and x.ndim==2 and x.shape[1]==64
        and y.shape==(len(x),) and len(origins)==len(x) and len(x)>0,
        'Ridge requires at least one represented complete world and64 hidden columns.')
    policy.require(np.isfinite(x).all() and np.all(np.abs(x)<=1) and np.isfinite(y).all() and np.all((y>=0)&(y<=1))
        and all(type(i) is int and 0<=i<total_worlds for i in origins),
        'Original terminal returns and origin indices must be finite and valid.')
    counts=Counter(origins);terminal={}
    for origin,reward in zip(origins,y):
        policy.require(origin not in terminal or terminal[origin]==reward,'One whole origin has one unchanged terminal return.')
        terminal[origin]=reward
    weights=np.asarray([1./counts[i] for i in origins],dtype=np.float64)
    mass=float(weights.sum());mean_x=weights@x/mass;mean_y=float(weights@y/mass)
    centered_x=x-mean_x;centered_y=y-mean_y
    normal=(centered_x.T*weights)@centered_x+np.eye(64,dtype=np.float64)
    slopes=np.linalg.solve(normal,(centered_x.T*weights)@centered_y)
    intercept=mean_y-float(mean_x@slopes)
    policy.require(np.isfinite(slopes).all() and np.isfinite(intercept),'Closed-form baseline must remain finite.')
    return dict(slopes=slopes.tolist(),intercept=intercept,representedWorlds=len(counts),
        emptyWorlds=total_worlds-len(counts),worlds=total_worlds,rows=len(x),rowWeightSum=mass)


def fit(basis,states,rewards,origins,total_worlds,provenance):
    policy.require(type(basis) is FrozenBasis,'Ridge must use its frozen original-initializer basis.')
    solved=weighted_ridge(basis.hidden(states),rewards,origins,total_worlds)
    return dict(kind=VERSION,recipe=copy.deepcopy(RECIPE),basis=basis.descriptor(),provenance=copy.deepcopy(provenance),**solved)


def validate(model,basis):
    policy.require(isinstance(model,dict) and set(model)=={'kind','recipe','basis','provenance','slopes','intercept',
        'representedWorlds','emptyWorlds','worlds','rows','rowWeightSum'} and model['kind']==VERSION
        and model['recipe']==RECIPE and model['basis']==basis.descriptor() and len(model['slopes'])==64,
        'Only the exact fixed state-only ridge contract and original basis are admitted.')
    for value in model['slopes']+[model['intercept']]:policy.number(value,'Baseline coefficient')
    for key in ('representedWorlds','worlds','rows'):policy.integer(model[key],key,1,200000)
    policy.integer(model['emptyWorlds'],'Empty baseline worlds',0,200000)
    policy.require(model['representedWorlds']+model['emptyWorlds']==model['worlds'] and
        model['rows']>=model['representedWorlds'] and abs(model['rowWeightSum']-model['representedWorlds'])<=1e-9,
        'Baseline weight/count metadata must retain all represented and empty worlds.')


def predict(model,basis,states):
    validate(model,basis)
    raw=basis.hidden(states)@np.asarray(model['slopes'],dtype=np.float64)+model['intercept']
    policy.require(np.isfinite(raw).all(),'Linear baseline predictions must remain finite before clipping.')
    return np.clip(raw,0.,1.).tolist()
