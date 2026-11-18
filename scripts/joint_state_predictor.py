"""Frozen52 shared state-value inference for CURRENT raw joint collections.

The existing Critic owns FP32 math. Only unchanged, phase-prefixed actor stateFeatures become
inputs; world identities, decisions, actions, behavior values and terminal labels stay outside.
Prepare one stable descriptor per numerical environment, then reuse it across all arm batches.
"""
from pathlib import Path

import torch

import joint_credit_targets as credit
import joint_current_ecology as ecology
import joint_state_value_data as data
import joint_trajectory as joint
import purchase_policy as policy
import purchase_value_benchmark as benchmark

CHECKPOINT = 'ffdceb830f97513d07b3ab63c5b7f425545f91749dba3fba81e80ed3e63773eb'
DICTIONARY = 'b8e11b9d6fb4e3e66e64ecaf4ab39e139d78a8b70e6704bf6235c0e34554d883'
INPUTS = 7241
PHASE_INPUTS = {'card': 3685, 'purchase': 3556}
MAXIMUM_ROWS = 256
CONTRACT = 'joint-state-inference-contract-1'
read, sha, require = policy.read_json, policy.file_digest, policy.require


def configure():
    """Match the declared one-thread CPU inference environment without touching any game RNG."""
    torch.set_num_threads(1)
    if torch.get_num_interop_threads() != 1:
        torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)


def inference_contract():
    return dict(kind=CONTRACT, checkpointSha256=CHECKPOINT, stateDictionarySha256=DICTIONARY,
        network=dict(inputs=INPUTS, hidden=[64], activation='tanh', output='sigmoid'),
        phaseInputs=PHASE_INPUTS, inputUnits='unchanged-existing-phase-encoder-units', missingValue=0.,
        device='cpu', dtype='float32', threads=1, interopThreads=1, torchVersion=torch.__version__,
        maximumRows=MAXIMUM_ROWS, adapterSha256=sha(__file__), criticOwnerSha256=sha(benchmark.__file__),
        encoderOwnerSha256=sha(data.__file__))


def load_subjects(checkpoint, dictionary):
    """Validate raw subjects and finite tensor shapes before constructing the fixed critic."""
    checkpoint, dictionary = Path(checkpoint).resolve(strict=True), Path(dictionary).resolve(strict=True)
    require(sha(checkpoint) == CHECKPOINT and sha(dictionary) == DICTIONARY,
            'Use only the exact selected52 critic and complete phase-prefixed dictionary.')
    names = data.dictionary_names(read(dictionary))
    require(len(names) == INPUTS and {phase: sum(name.startswith(phase+'.') for name in names)
            for phase in PHASE_INPUTS} == PHASE_INPUTS,
            'The frozen shared critic requires the original inferred input dimensions.')
    tensors = torch.load(checkpoint, map_location='cpu', weights_only=True)
    shapes = {'layers.0.weight': (64, len(names)), 'layers.0.bias': (64,),
              'layers.2.weight': (1, 64), 'layers.2.bias': (1,)}
    require(isinstance(tensors, dict) and set(tensors) == set(shapes)
            and all(isinstance(tensors[key], torch.Tensor) and tensors[key].dtype == torch.float32
                    and tuple(tensors[key].shape) == shape and bool(torch.isfinite(tensors[key]).all())
                    for key, shape in shapes.items()),
            'The frozen shared critic must contain only its finite FP32 64-unit tensor shapes.')
    # nn.Linear initializes before the owner's explicit generator overwrite. Do not advance the
    # surrounding actor process's global torch RNG merely to construct this frozen inference object.
    with torch.random.fork_rng(devices=[]):
        critic = benchmark.Critic((64,), 0, inputs=len(names))
    critic.load_state_dict(tensors, strict=True)
    critic.requires_grad_(False); critic.eval()
    require(sha(checkpoint) == CHECKPOINT and sha(dictionary) == DICTIONARY,
            'Frozen critic subjects changed while loading.')
    return checkpoint, dictionary, names, critic


def prepare_descriptor(checkpoint, dictionary, output):
    """Publish one immutable honest descriptor/contract pair; no state predictions or fitting."""
    output = Path(output).resolve()
    require(not output.exists(), 'Preserve every previously frozen shared predictor descriptor.')
    configure()
    checkpoint, dictionary, _, _ = load_subjects(checkpoint, dictionary)
    output.mkdir(parents=True)
    contract_path = output/'inference-contract.json'
    policy.atomic_json(contract_path, inference_contract())
    descriptor = dict(kind=credit.DESCRIPTOR, checkpointPath=str(checkpoint), checkpointSha256=CHECKPOINT,
        stateDictionaryPath=str(dictionary), stateDictionarySha256=DICTIONARY,
        inferenceContractPath=str(contract_path), inferenceContractSha256=sha(contract_path))
    policy.atomic_json(output/'predictor.json', descriptor)
    return output/'predictor.json'


class FrozenJointPredictor:
    """Exactly the audited52 Critic64; the descriptor's identity stays stable across an arm."""
    def __init__(self, descriptor_path):
        configure()
        self.path = Path(descriptor_path).resolve(strict=True)
        self.pins = {}
        joint.pin(self.path, self.pins)
        descriptor = read(self.path)
        require(set(descriptor) == {'kind', 'checkpointPath', 'checkpointSha256', 'stateDictionaryPath',
                'stateDictionarySha256', 'inferenceContractPath', 'inferenceContractSha256'}
                and descriptor['kind'] == credit.DESCRIPTOR,
                'The shared predictor requires its separately frozen complete subject descriptor.')
        for path_key, hash_key in (('checkpointPath', 'checkpointSha256'),
                                  ('stateDictionaryPath', 'stateDictionarySha256'),
                                  ('inferenceContractPath', 'inferenceContractSha256')):
            path = Path(descriptor[path_key])
            require(path.is_absolute() and sha(joint.pin(path, self.pins)) == descriptor[hash_key],
                    'A frozen shared predictor descriptor subject changed: '+path_key)
        require(read(descriptor['inferenceContractPath']) == inference_contract(),
                'Frozen joint inference code, dimensions, units or numerical environment changed.')
        _, _, self.names, self._critic = load_subjects(descriptor['checkpointPath'], descriptor['stateDictionaryPath'])
        require(descriptor['checkpointSha256'] == CHECKPOINT and descriptor['stateDictionarySha256'] == DICTIONARY,
                'The shared predictor descriptor must name the selected52 subjects.')
        self.descriptor = descriptor
        joint.unchanged(self.pins)

    @classmethod
    def read(cls, descriptor_path):
        return cls(descriptor_path)

    def values(self, merged):
        joint.unchanged(self.pins)
        require(torch.get_num_threads() == torch.get_num_interop_threads() == 1
                and torch.are_deterministic_algorithms_enabled(),
                'Frozen joint state inference retains one deterministic CPU thread.')
        values = state_values(self._critic, merged, self.names)
        joint.unchanged(self.pins)
        return values


def state_values(critic, merged, names):
    """Bounded original-unit state projection; no action/return/recommendation fields are read."""
    indices = {name: index for index, name in enumerate(names)}
    result = []
    with torch.no_grad():
        for start in range(0, len(merged), MAXIMUM_ROWS):
            rows = merged[start:start+MAXIMUM_ROWS]
            inputs = torch.zeros((len(rows), len(names)), dtype=torch.float32)
            for index, (_, _, identity, row) in enumerate(rows):
                phase = identity['phase']
                require(phase in PHASE_INPUTS, 'Shared state inference requires a declared recorded phase.')
                features = {phase+'.'+name: value for name,value in row['observation']['stateFeatures'].items()}
                columns, values = data.encoded_state(features, indices)
                require(all(-16 <= value <= 16 for value in values),
                        'Shared critic states retain original finite encoder units within [-16,16].')
                inputs[index, columns] = torch.tensor(values, dtype=torch.float32)
            predicted = critic(inputs)
            require(predicted.shape == (len(rows),) and bool(torch.isfinite(predicted).all())
                    and bool(((0 <= predicted) & (predicted <= 1)).all()),
                    'Frozen joint predictions must be finite terminal-reward values.')
            result.extend(float(value) for value in predicted)
    return result


def predict(collection, predictor, output):
    """Freeze all original worlds and both heads before either phase actor can be updated."""
    output = Path(output)
    require(not output.exists(), 'Preserve previously frozen shared joint predictions.')
    require(isinstance(collection, joint.JointCollection) and isinstance(predictor, FrozenJointPredictor),
            'Shared predictions require the typed raw collection and frozen52 predictor.')
    joint.unchanged(collection.pins); joint.unchanged(predictor.pins)
    current = collection.common.get('jointEcology', {})
    require(current.get('version') == ecology.JointEcology.CURRENT.value
            and current.get('dictionarySha256') == predictor.descriptor['stateDictionarySha256']
            and sorted(phase+'.'+name for phase in joint.PHASES
                       for name in collection.bundle[phase]['stateFeatureNames']) == predictor.names,
            'Predict only the validated CURRENT collection with its exact two-head state dictionary.')
    merged = credit.chronology(collection.rows, collection.common)
    values = predictor.values(merged)
    result = dict(kind=credit.PREDICTIONS, newFits=0,
        collectionSha256=collection.common['collectionSha256'], gameHashes=collection.common['gameHashes'],
        behaviorModels={phase: collection.bundle[phase]['sha256'] for phase in joint.PHASES},
        predictorDescriptor=dict(path=str(predictor.path), sha256=sha(predictor.path)),
        predictions=[dict(identity, value=value) for (_, _, identity, _),value in zip(merged,values)])
    joint.unchanged(collection.pins); joint.unchanged(predictor.pins)
    policy.atomic_json(output, result)
    return result
