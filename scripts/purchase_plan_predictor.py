"""Frozen67 public OWNER_EXIT values and fixed68 plan ranking; no fitting or outcome labels.

The native owner certifies public-plan feasibility and exact terminal/own-Chaos bypasses.
Commands and aliases remain evidence only. Only stateFeatures enter the unchanged FP32 critic.
Prepare one immutable descriptor per numerical environment; no model or threshold selection.
"""
import math
from pathlib import Path

import torch

import afterstate_value_data as data
import purchase_policy as policy
import purchase_value_benchmark as benchmark

CHECKPOINT = 'd45f59654e5fcd6c5d36ea1eea163781596f0433feb810b4a5dff1d71ca6202b'
DICTIONARY = 'aba7c81037b6549a0ae1c6c3b77f00d377cc7c6e248e580aa36155d2326bd4a8'
INPUTS = 7244
VERSION = 'purchase-plan-ranking-1'
DESCRIPTOR = 'purchase-plan-predictor-1'
CONTRACT = 'purchase-plan-inference-contract-1'
KINDS = ('INCUMBENT', 'ADVANCE_PASS', 'SAMPLED', 'SAMPLED', 'SAMPLED',
         'UNIFORM', 'UNIFORM', 'UNIFORM')
EXCLUSIONS = ('HYPOTHETICAL', 'INVALID', 'INFORMATION', 'INTERVENING_CHOICE', 'CHOICE_CAP')
DECISIONS = ('purchase-advances', 'purchase-stabilization', 'purchase-relief')
OWNER_EXIT = 'purchase.decision.purchase-owner-exit'
read, sha, require = policy.read_json, policy.file_digest, policy.require


def configure():
    torch.set_num_threads(1)
    if torch.get_num_interop_threads() != 1:
        torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)


def inference_contract():
    return dict(kind=CONTRACT, checkpointSha256=CHECKPOINT, dictionarySha256=DICTIONARY,
        inputs=INPUTS, hidden=[64], activation='tanh', output='sigmoid', context='OWNER_EXIT',
        units='unchanged-existing-phase-encoder-units', missingValue=0., device='cpu',
        dtype='float32', threads=1, interopThreads=1, torchVersion=torch.__version__,
        adapterSha256=sha(__file__), criticOwnerSha256=sha(benchmark.__file__),
        dictionaryOwnerSha256=sha(data.__file__), maximumPlans=8,
        selection='strict-improvement-over-incumbent; ties-incumbent-then-plan-index')


def load_subjects(checkpoint, dictionary):
    checkpoint, dictionary = Path(checkpoint).resolve(strict=True), Path(dictionary).resolve(strict=True)
    require(sha(checkpoint) == CHECKPOINT and sha(dictionary) == DICTIONARY,
            'Plan values require exactly the admitted67 checkpoint and dictionary.')
    names = data.dictionary_names(read(dictionary))
    require(len(names) == INPUTS and OWNER_EXIT in names,
            'The admitted67 critic requires its complete ordered7244-column dictionary.')
    state = torch.load(checkpoint, map_location='cpu', weights_only=True)
    shapes = {'layers.0.weight': (64, INPUTS), 'layers.0.bias': (64,),
              'layers.2.weight': (1, 64), 'layers.2.bias': (1,)}
    require(isinstance(state, dict) and set(state) == set(shapes)
        and all(isinstance(state[key], torch.Tensor) and state[key].dtype == torch.float32
                and tuple(state[key].shape) == shape and bool(torch.isfinite(state[key]).all())
                for key, shape in shapes.items()),
        'The admitted67 checkpoint must contain only finite FP32 width64 critic tensors.')
    # nn.Linear initializes transient parameters; every tensor is then overwritten. Preserve
    # all caller RNG states, and do not introduce any scientific initialization stream.
    with torch.random.fork_rng(devices=list(range(torch.cuda.device_count()))):
        critic = benchmark.Critic((64,), 0, inputs=INPUTS)
    critic.load_state_dict(state, strict=True)
    critic.requires_grad_(False)
    critic.eval()
    require(sha(checkpoint) == CHECKPOINT and sha(dictionary) == DICTIONARY,
            'Frozen plan predictor subjects changed while loading.')
    return checkpoint, dictionary, names, critic


def prepare_descriptor(checkpoint, dictionary, output):
    """Freeze code, numerical identity and subjects without scoring any retained state."""
    output = Path(output).resolve()
    require(not output.exists(), 'Preserve the original frozen plan predictor descriptor.')
    configure()
    checkpoint, dictionary, _, _ = load_subjects(checkpoint, dictionary)
    output.mkdir(parents=True)
    contract = output / 'inference-contract.json'
    policy.atomic_json(contract, inference_contract())
    policy.atomic_json(output / 'predictor.json', dict(kind=DESCRIPTOR,
        checkpointPath=str(checkpoint), checkpointSha256=CHECKPOINT,
        dictionaryPath=str(dictionary), dictionarySha256=DICTIONARY,
        inferenceContractPath=str(contract), inferenceContractSha256=sha(contract)))
    return output / 'predictor.json'


def fields(value, expected, message):
    require(isinstance(value, dict) and set(value) == set(expected), message)


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate_leaf(leaf, names):
    fields(leaf, ('context', 'stateFeatures', 'exactValue', 'exactReason'),
           'A plan leaf contains only the public context, state features and typed exact bypass.')
    features = leaf['stateFeatures']
    require(leaf['context'] == 'OWNER_EXIT' and isinstance(features, dict)
        and set(features) <= names and all(key.startswith('purchase.') for key in features)
        and all(finite(value) and -16 <= value <= 16 for value in features.values())
        and features.get(OWNER_EXIT) == 1.
        and all(value == 0. for key, value in features.items()
                if key.startswith('purchase.decision.') and key != OWNER_EXIT),
        'Only actor-safe OWNER_EXIT features in the original finite units may enter the critic.')
    reason, value = leaf['exactReason'], leaf['exactValue']
    require(reason in (None, 'OWN_CHAOS', 'GAME_TERMINAL')
        and ((reason is None and value is None)
             or (reason is not None and finite(value) and 0 <= value <= 1)),
        'An exact bypass requires a typed authoritative terminal or own-Chaos value.')
    if reason == 'OWN_CHAOS':
        require(value == 0. and features.get('purchase.role.0.chaos') == 1.,
                'Own Chaos bypass must be authoritative own elimination with exact value zero.')


def validate_origin(origin, names):
    fields(origin, ('version', 'originIndex', 'status', 'plans'),
           'Plan predictor payload excludes seeds, hidden state, outcomes and extra metadata.')
    require(origin['version'] == VERSION and type(origin['originIndex']) is int
        and 0 <= origin['originIndex'] < 256
        and origin['status'] in ('READY', 'MISSING', 'INCUMBENT_UNSUPPORTED')
        and isinstance(origin['plans'], list), 'An origin requires the fixed68 public protocol.')
    plans = origin['plans']
    if origin['status'] == 'MISSING':
        require(not plans, 'A missing origin retains no fabricated plans.')
        return
    require(len(plans) == 8, 'Every captured root retains all eight ordered logical plans.')
    for index, plan in enumerate(plans):
        fields(plan, ('index', 'kind', 'actions', 'status', 'exclusion', 'leaf', 'aliasOf'),
               'Plan metadata must retain only its declared native fields.')
        require(type(plan['index']) is int and plan['index'] == index and plan['kind'] == KINDS[index]
            and plan['status'] in ('SUPPORTED', 'UNSUPPORTED')
            and isinstance(plan['actions'], list) and len(plan['actions']) <= 64,
            'Plan index, proposal kind and bounded command order are fixed prospectively.')
        for action in plan['actions']:
            fields(action, ('decision', 'command'), 'Plan actions contain only decision and command.')
            require(action['decision'] in DECISIONS and isinstance(action['command'], str)
                and bool(action['command']), 'A plan command must belong to the own purchase phase.')
        if plan['status'] == 'SUPPORTED':
            require(plan['exclusion'] is None and bool(plan['actions']),
                    'A supported plan has actual owner commands and no exclusion.')
            validate_leaf(plan['leaf'], names)
        else:
            require(plan['leaf'] is None and plan['exclusion'] in EXCLUSIONS,
                    'An unsupported plan keeps its reason and no fabricated leaf value.')
        alias = plan['aliasOf']
        require(alias is None or type(alias) is int and 0 <= alias < index,
                'A logical alias must reference an earlier retained plan.')
        if alias is not None:
            require(plans[alias]['aliasOf'] is None and all(plan[key] == plans[alias][key] for key in
                        ('actions', 'status', 'exclusion', 'leaf')),
                    'An alias must retain exactly the same commands, feasibility and public leaf.')
    require((origin['status'] == 'READY') == (plans[0]['status'] == 'SUPPORTED'),
            'Unsupported incumbent status must trigger the explicit normal-policy fallback.')


class FrozenPlanPredictor:
    def __init__(self, descriptor_path):
        configure()
        self.path = Path(descriptor_path).resolve(strict=True)
        descriptor = read(self.path)
        fields(descriptor, ('kind', 'checkpointPath', 'checkpointSha256', 'dictionaryPath',
            'dictionarySha256', 'inferenceContractPath', 'inferenceContractSha256'),
            'Plan inference requires its complete frozen subject descriptor.')
        require(descriptor['kind'] == DESCRIPTOR, 'Unsupported plan predictor descriptor version.')
        self.pins = {str(self.path): sha(self.path)}
        for path_key, hash_key in (('checkpointPath', 'checkpointSha256'),
                ('dictionaryPath', 'dictionarySha256'), ('inferenceContractPath', 'inferenceContractSha256')):
            path = Path(descriptor[path_key])
            require(path.is_absolute() and sha(path) == descriptor[hash_key],
                    'A frozen plan predictor subject changed: ' + path_key)
            self.pins[str(path)] = descriptor[hash_key]
        require(descriptor['checkpointSha256'] == CHECKPOINT and descriptor['dictionarySha256'] == DICTIONARY
            and read(descriptor['inferenceContractPath']) == inference_contract(),
            'Plan inference preserves exact67 weights, dictionary, code and numerical environment.')
        _, _, self.names, self.critic = load_subjects(descriptor['checkpointPath'], descriptor['dictionaryPath'])
        self.descriptor = descriptor
        self.unchanged()

    def unchanged(self):
        require(all(sha(path) == digest for path, digest in self.pins.items()),
                'A frozen plan predictor input changed.')
        require(torch.get_num_threads() == torch.get_num_interop_threads() == 1
            and torch.are_deterministic_algorithms_enabled(),
            'Frozen plan inference requires one deterministic CPU thread.')

    def values(self, leaves):
        """Exact bypasses never call the model; inferred rows contain only stateFeatures."""
        self.unchanged()
        require(len(leaves) <= 8, 'One origin contains at most eight logical plans.')
        indices = {name: index for index, name in enumerate(self.names)}
        result, pending = [], []
        for leaf in leaves:
            validate_leaf(leaf, indices.keys())
            result.append(leaf['exactValue'])
            if leaf['exactReason'] is None:
                pending.append((len(result) - 1, leaf['stateFeatures']))
        if pending:
            inputs = torch.zeros((len(pending), INPUTS), dtype=torch.float32)
            for row, (_, features) in enumerate(pending):
                for key, value in features.items():
                    inputs[row, indices[key]] = value
            with torch.no_grad():
                predictions = self.critic(inputs)
            require(predictions.shape == (len(pending),) and bool(torch.isfinite(predictions).all())
                and bool(((0 <= predictions) & (predictions <= 1)).all()),
                'Plan values must be finite terminal reward predictions in [0,1].')
            for (index, _), value in zip(pending, predictions, strict=True):
                result[index] = float(value)
        self.unchanged()
        return result


def rank_origin(predictor, origin):
    """Rank fixed public proposals before any tail labels; strict gain, incumbent on ties."""
    require(isinstance(predictor, FrozenPlanPredictor), 'Ranking requires the frozen67 predictor.')
    predictor.unchanged()
    validate_origin(origin, set(predictor.names))
    plans = origin['plans']
    scores = [None] * len(plans)
    chosen = 0 if plans else None
    fallback = None if origin['status'] == 'READY' else origin['status']
    if fallback is None:
        canonical = [plan for plan in plans
                     if plan['status'] == 'SUPPORTED' and plan['aliasOf'] is None]
        values = predictor.values([plan['leaf'] for plan in canonical])
        for plan, value in zip(canonical, values, strict=True):
            scores[plan['index']] = value
        # Identical logical plans share one computed value, independently of FP32 batch kernels.
        for plan in plans:
            if plan['aliasOf'] is not None:
                scores[plan['index']] = scores[plan['aliasOf']]
        for index, value in enumerate(scores):
            if value is not None and value > scores[chosen]:
                chosen = index
    predictor.unchanged()
    return dict(kind='purchase-plan-ranking-decision-1', originIndex=origin['originIndex'],
        status=origin['status'], selectedIndex=chosen, fallbackReason=fallback,
        predictorDescriptorSha256=sha(predictor.path), checkpointSha256=CHECKPOINT,
        dictionarySha256=DICTIONARY, plans=[dict(index=plan['index'], status=plan['status'],
            exclusion=plan['exclusion'], aliasOf=plan['aliasOf'], value=scores[plan['index']],
            valueSource=None if scores[plan['index']] is None else plan['leaf']['exactReason'] or 'CRITIC')
            for plan in plans])
