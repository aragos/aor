"""Paired expected-card-advantage math and versioned portable selection.

Only actor-safe observations enter networks. Absolute sigmoid outputs are not calibrated
values: the supervised objective identifies differences relative to ordinary F actions.
Version two multiplies separately encoded state/action units, giving each action term an
explicit context-dependent sign. Version-one additive networks are not compatible artifacts.
"""
import copy
from dataclasses import dataclass, asdict
import math
import random

import torch
from torch import nn
import purchase_policy as common
import card_policy

ENCODER = 'aor-card-observation-1'
SELECTOR = 'aor-card-advantage-2'
PROPOSAL = 'aor-card-proposals-1'
FREQUENCY = 'aor-card-frequency-1'
HIDDEN = 64
MARGIN = .02
SEEDS = (84501, 84502, 84503)
MEMBER_KEYS = ('stateWeights', 'stateBias', 'actionWeights', 'actionBias', 'outputWeights', 'outputBias')
BUNDLE_KEYS = {'schemaVersion', 'encoderVersion', 'selectorVersion', 'proposalVersion', 'hiddenSize',
               'margin', 'stateFeatureNames', 'actionFeatureNames', 'members'}
require = common.require

@dataclass(frozen=True)
class FitConfig:
    learningRate: float = .003
    weightDecay: float = .0001
    maxGradientNorm: float = 1.0
    originsPerBatch: int = 16
    epochs: int = 64
    checkpoints: tuple = (8, 16, 32, 64)
    seeds: tuple = SEEDS
    threads: int = 1
    maxSeconds: int = 2700

CONFIG = FitConfig()

def validate_schema(schema):
    require(schema.get('encoderVersion') == ENCODER, 'Unsupported card encoder.')
    common.names(schema.get('stateFeatureNames'), 'State dictionary', 8192)
    common.names(schema.get('actionFeatureNames'), 'Action dictionary', 512)
    return schema

def validate_member(member, schema):
    require(set(member) == set(MEMBER_KEYS), 'An advantage member has unexpected or missing fields.')
    sizes = {'stateWeights': HIDDEN*len(schema['stateFeatureNames']), 'stateBias': HIDDEN,
             'actionWeights': HIDDEN*len(schema['actionFeatureNames']), 'actionBias': HIDDEN,
             'outputWeights': HIDDEN}
    for key, size in sizes.items():
        require(isinstance(member[key], list) and len(member[key]) == size,
                'Invalid row-major advantage parameter size: ' + key)
        for value in member[key]: common.number(value, key, -1e6, 1e6)
    common.number(member['outputBias'], 'outputBias', -1e6, 1e6)
    return member

def validate_bundle(bundle):
    require(set(bundle) == BUNDLE_KEYS, 'Unexpected or missing advantage bundle fields.')
    require(type(bundle['schemaVersion']) is int and bundle['schemaVersion'] == 1
            and bundle['encoderVersion'] == ENCODER and bundle['selectorVersion'] == SELECTOR
            and bundle['proposalVersion'] == PROPOSAL and type(bundle['hiddenSize']) is int
            and bundle['hiddenSize'] == HIDDEN and bundle['margin'] == MARGIN,
            'Unsupported advantage selector contract.')
    validate_schema(bundle)
    require(isinstance(bundle['members'], list) and len(bundle['members']) == 3,
            'An advantage ensemble requires exactly three members.')
    for member in bundle['members']: validate_member(member, bundle)
    return bundle

def initialize(schema, seed):
    validate_schema(schema); rng = random.Random(seed)
    def weights(size): return [rng.uniform(-1/math.sqrt(size), 1/math.sqrt(size)) for _ in range(HIDDEN*size)]
    return {'stateWeights': weights(len(schema['stateFeatureNames'])), 'stateBias': [0.0]*HIDDEN,
            'actionWeights': weights(len(schema['actionFeatureNames'])), 'actionBias': [0.0]*HIDDEN,
            'outputWeights': [0.0]*HIDDEN, 'outputBias': 0.0}

def bundle(schema, members):
    return validate_bundle({'schemaVersion': 1, 'encoderVersion': ENCODER, 'selectorVersion': SELECTOR,
        'proposalVersion': PROPOSAL, 'hiddenSize': HIDDEN, 'margin': MARGIN,
        'stateFeatureNames': schema['stateFeatureNames'], 'actionFeatureNames': schema['actionFeatureNames'],
        'members': members})

class Stream:
    def __init__(self, seed, salt): self.state = (seed ^ salt) & 0xffffffff
    def word(self):
        self.state = (self.state + 0x9e3779b9) & 0xffffffff
        value = self.state
        value = ((value ^ (value >> 16)) * 0x85ebca6b) & 0xffffffff
        value = ((value ^ (value >> 13)) * 0xc2b2ae35) & 0xffffffff
        return (value ^ (value >> 16)) & 0xffffffff
    def bounded(self, bound):
        require(type(bound) is int and bound > 0, 'A bounded proposal draw requires a positive size.')
        limit = 2**32 - (2**32 % bound)
        while True:
            word = self.word()
            if word < limit: return word % bound

def proposals(observation, seed):
    common.integer(seed, 'Decision seed', -(2**31), 2**31-1)
    actions = observation['actions']; baseline = observation['baselineIndex']
    commands = [a['command'] for a in actions]
    require(commands == sorted(commands) and len(set(commands)) == len(commands),
            'Proposal commands must be canonical and distinct.')
    common.integer(baseline, 'F baseline index', 0, len(commands)-1)
    chosen = {baseline}; groups = {}
    baseline_reference = commands[baseline].split()[1] if commands[baseline].startswith('play ') else None
    for index, command in enumerate(commands):
        if command == 'pass': chosen.add(index)
        elif command.startswith('play '):
            reference = command.split()[1]
            if reference != baseline_reference: groups.setdefault(reference, []).append(index)
        else: raise ValueError('Card proposals require ordinary pass/play commands.')
    names = sorted(groups); rng = Stream(seed, 0x510e527f)
    for i in range(min(2, len(names))):
        j = i+rng.bounded(len(names)-i); names[i], names[j] = names[j], names[i]
        candidates = groups[names[i]]; chosen.add(candidates[rng.bounded(len(candidates))])
    return sorted(chosen)

def identity_feature(name):
    """Action identity/explicit choice parameters only; excludes quoted/state-derived effects."""
    return name.startswith(('kind.', 'card.', 'cardKind.', 'choice.', 'target.')) or name == 'protection'

def ablation_state_feature(name):
    """The eligible-scope constant prevents a dead zero-head product; it carries no context."""
    return name == 'cardWindow.normal'

@dataclass
class Group:
    state: torch.Tensor
    actions: torch.Tensor
    baseline: int
    indices: tuple
    commands: tuple
    targets: torch.Tensor
    identifier: str
    observation: dict
    seed: int
    sourceGroups: tuple = ()
    replicates: int = 1
    def nontrivial(self): return len(self.indices) > 1

def prepare(observation, seed, schema, targets=None, identifier='', ablation=False):
    validate_schema(schema)
    common.validate_observation(observation, schema, contract=card_policy.CONTRACT)
    indices = proposals(observation, seed)
    state = torch.tensor([0.0 if ablation and not ablation_state_feature(name)
                          else observation['stateFeatures'].get(name, 0.0)
                          for name in schema['stateFeatureNames']], dtype=torch.float64)
    actions = torch.tensor([[observation['actions'][i]['features'].get(name, 0.0)
                            if not ablation or identity_feature(name) else 0.0
                            for name in schema['actionFeatureNames']] for i in indices], dtype=torch.float64)
    expected = [0.0]*len(indices) if targets is None else list(targets)
    require(len(expected) == len(indices), 'Paired targets must match the proposal set.')
    for value in expected: common.number(value, 'Paired reward difference', -1, 1)
    b = indices.index(observation['baselineIndex'])
    require(abs(expected[b]) < 1e-12, 'The F reference must have zero paired advantage.')
    return Group(state, actions, b, tuple(indices), tuple(observation['actions'][i]['command'] for i in indices),
                 torch.tensor(expected, dtype=torch.float64), identifier, copy.deepcopy(observation), seed)

class Network(nn.Module):
    def __init__(self, schema, member):
        super().__init__(); validate_schema(schema); validate_member(member, schema)
        self.schema = {k:copy.deepcopy(schema[k]) for k in ('encoderVersion','stateFeatureNames','actionFeatureNames')}
        for name in MEMBER_KEYS:
            value = torch.tensor(member[name], dtype=torch.float64)
            if name == 'stateWeights': value = value.reshape(HIDDEN, len(schema['stateFeatureNames']))
            if name == 'actionWeights': value = value.reshape(HIDDEN, len(schema['actionFeatureNames']))
            self.register_parameter(name, nn.Parameter(value))
    def artifact(self):
        return {name:float(getattr(self,name).detach()) if name == 'outputBias'
                else getattr(self,name).detach().flatten().tolist() for name in MEMBER_KEYS}
    def forward(self, groups):
        require(groups and len(groups) <= 1024, 'A bounded nonempty decision batch is required.')
        state = torch.stack([g.state for g in groups]); action = torch.cat([g.actions for g in groups])
        owners = torch.tensor([i for i,g in enumerate(groups) for _ in g.indices], dtype=torch.long)
        hidden = torch.tanh(state @ self.stateWeights.T + self.stateBias)
        action_hidden = torch.tanh(action @ self.actionWeights.T + self.actionBias)
        values = torch.sigmoid((hidden[owners] * action_hidden) @ self.outputWeights + self.outputBias)
        return list(values.split([len(g.indices) for g in groups]))

def loss(network, origins):
    """Equal original worlds, then nontrivial groups, then non-reference action pairs."""
    active = [[g for g in origin if g.nontrivial()] for origin in origins]
    active = [groups for groups in active if groups]
    require(active, 'A loss batch requires at least one nontrivial origin.')
    predictions = iter(network([g for groups in active for g in groups])); origin_losses = []
    for groups in active:
        values=[]
        for group in groups:
            q = next(predictions); delta = q-q[group.baseline]
            mask = [i for i in range(len(q)) if i != group.baseline]
            values.append(((delta[mask]-group.targets[mask])**2).mean())
        origin_losses.append(torch.stack(values).mean())
    return torch.stack(origin_losses).mean()

def choose_values(group, values):
    require(len(values) == 3, 'Selection requires all three member outputs.')
    differences = [[float(q[i]-q[group.baseline]) for i in range(len(group.indices))] for q in values]
    means = [sum(d[i] for d in differences)/3 for i in range(len(group.indices))]
    winner = max(range(len(means)), key=lambda i: means[i])  # canonical command order breaks exact ties
    if not (means[winner] > MARGIN and all(d[winner] > 0 for d in differences)): winner = group.baseline
    return {'index': group.indices[winner], 'proposalPosition': winner, 'command':group.commands[winner],
            'meanAdvantages':means, 'memberAdvantages':differences}

class Ensemble:
    def __init__(self, artifact, ablation=False):
        validate_bundle(artifact); self.artifact=artifact; self.ablation=ablation
        self.members=[Network(artifact,m) for m in artifact['members']]
    def choose(self, observation, seed):
        if not normal(observation):
            b=observation['baselineIndex'];return {'index':b,'command':observation['actions'][b]['command'],'proposalPosition':None,'meanAdvantages':[],'memberAdvantages':[]}
        group=prepare(observation,seed,self.artifact,ablation=self.ablation)
        with torch.no_grad(): return choose_values(group,[m([group])[0] for m in self.members])

def scalar_values(schema, member, group):
    """Independent scalar oracle for row-major math and shared-runtime fixtures."""
    validate_member(member,schema); state=group.state.tolist(); actions=group.actions.tolist()
    hidden=[math.tanh(member['stateBias'][u]+sum(member['stateWeights'][u*len(state)+i]*x for i,x in enumerate(state))) for u in range(HIDDEN)]
    result=[]
    for action in actions:
        z=[math.tanh(member['actionBias'][u]+sum(member['actionWeights'][u*len(action)+i]*x for i,x in enumerate(action))) for u in range(HIDDEN)]
        logit=member['outputBias']+sum(w*h*x for w,h,x in zip(member['outputWeights'],hidden,z))
        result.append(1/(1+math.exp(-logit)) if logit>=0 else math.exp(logit)/(1+math.exp(logit)))
    return result

def frequency_control(pass_probability, other_probability):
    for p in (pass_probability,other_probability): common.number(p,'Requested family probability',0,1)
    require(pass_probability+other_probability<=1, 'Requested family probabilities exceed one.')
    return {'schemaVersion':1,'selectorVersion':FREQUENCY,'encoderVersion':ENCODER,'proposalVersion':PROPOSAL,
            'passProbability':pass_probability,'otherProbability':other_probability}

def normal(observation):
    return observation['stateFeatures'].get('cardWindow.normal')==1 and observation['stateFeatures'].get('cardWindow.final',0)==0

def frequency_choice(control, observation, seed):
    require(control == frequency_control(control['passProbability'],control['otherProbability']), 'Unexpected frequency control fields/version.')
    if not normal(observation): return observation['baselineIndex']
    indices=proposals(observation,seed); b=observation['baselineIndex']; rng=Stream(seed,0x1f83d9ab); u=rng.word()/2**32
    if u<control['passProbability']:
        return next((i for i in indices if i!=b and observation['actions'][i]['command']=='pass'),b)
    if u<control['passProbability']+control['otherProbability']:
        choices=[i for i in indices if i!=b and observation['actions'][i]['command']!='pass']
        return choices[rng.bounded(len(choices))] if choices else b
    return b

def calibrate_frequency(ensemble, origins):
    """Outcome-free calibration over all eligible development decisions, including F choices."""
    targets=[0,0]; available=[0,0]; count=0
    for origin in origins:
        for g in origin:
            chosen=ensemble.choose(g.observation,g.seed)['index']; b=g.observation['baselineIndex']; count+=1
            commands=[g.observation['actions'][i]['command'] for i in g.indices if i!=b]
            available[0]+=int('pass' in commands);available[1]+=int(any(c!='pass' for c in commands))
            if chosen!=b: targets[int(g.observation['actions'][chosen]['command']!='pass')]+=1
    require(count>0,'Frequency calibration requires development decisions.')
    probabilities=[targets[i]/available[i] if available[i] else 0 for i in (0,1)]
    scale=max(1.,sum(probabilities));probabilities=[p/scale for p in probabilities]
    control=frequency_control(*probabilities)
    return control,{'decisions':count,'targetRates':[x/count for x in targets],
        'availabilityRates':[x/count for x in available],'expectedRates':[probabilities[i]*available[i]/count for i in (0,1)],
        'probabilityNormalization':scale,'outcomeUse':False}

def materialize_ablation(member, schema):
    """Export a context-free control with only its fixed normal-window state input retained."""
    result=copy.deepcopy(member); state_width=len(schema['stateFeatureNames'])
    for unit in range(HIDDEN):
        for column,name in enumerate(schema['stateFeatureNames']):
            if not ablation_state_feature(name): result['stateWeights'][unit*state_width+column]=0.0
    width=len(schema['actionFeatureNames'])
    for unit in range(HIDDEN):
        for column,name in enumerate(schema['actionFeatureNames']):
            if not identity_feature(name): result['actionWeights'][unit*width+column]=0.0
    return result
