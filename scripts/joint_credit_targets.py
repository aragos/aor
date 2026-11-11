"""Detached two-clock GAE targets from one frozen shared state-value file.

This prerequisite performs no inference, optimizer step or native execution. The existing
JointCollection owns actor-visible rows and behavior validation. Its current source compatibility
and the purchase-only actor-update guards remain unchanged: this is not a complete joint trainer.
"""
from enum import Enum
from pathlib import Path

import joint_trajectory as joint
import purchase_policy as policy


class Clock(Enum):
    CHRONOLOGICAL = 'chronological'
    PHASE_SEPARATED = 'phase-separated'


PREDICTIONS = 'joint-frozen-state-values-1'
DESCRIPTOR = 'joint-frozen-state-predictor-1'
TARGETS = 'joint-detached-gae-targets-1'


def estimator(clock):
    policy.require(isinstance(clock, Clock), 'Choose an explicit typed joint credit clock.')
    return dict(version=TARGETS, clock=clock.value, gamma=1., **{'lambda': .95},
        forcedRowsIncluded=True, intermediateReward=0., terminalValue=0.,
        terminalRewardPlacement='Last recorded row on this clock, even if play continues.')


def chronology(phase_rows, provenance):
    """Absolute decision order, with sourceRow local to each world's original phase array."""
    policy.require(set(phase_rows) == set(joint.PHASES), 'Retain both original phase views, including empty ones.')
    hashes = provenance['gameHashes']
    policy.require(len(hashes) == provenance['games'] and len(set(hashes)) == len(hashes),
                   'Targets require all distinct original whole-game hashes, including empty worlds.')
    merged = []
    for phase in joint.PHASES:
        source_rows = {}
        previous = None
        for row in phase_rows[phase]:
            origin = policy.integer(row['originIndex'], 'Joint origin index', 0, len(hashes)-1)
            number = policy.integer(row['number'], 'Absolute joint decision', 0, 11999)
            key = origin, number
            policy.require(previous is None or previous < key,
                           'Original phase rows must retain whole-world and absolute decision order.')
            previous = key
            source_row = source_rows.get(origin, 0)
            source_rows[origin] = source_row+1
            identity = dict(gameSha256=hashes[origin], decision=number, phase=phase, sourceRow=source_row)
            merged.append((origin, number, identity, row))
    merged.sort(key=lambda item: item[:2])
    policy.require(len({item[:2] for item in merged}) == len(merged),
                   'Merged controlled decisions must be unique across both phases.')
    return merged


def prediction_values(path, merged, provenance, model_hashes):
    """Validate detached state values and their subjects; never claim to verify predictor math."""
    path = Path(path).resolve(strict=True)
    prediction_hash = policy.file_digest(path)
    raw = policy.read_json(path)
    policy.require(set(model_hashes) == set(joint.PHASES)
        and raw.get('kind') == PREDICTIONS and raw.get('newFits') == 0
        and raw.get('collectionSha256') == provenance['collectionSha256']
        and raw.get('gameHashes') == provenance['gameHashes']
        and raw.get('behaviorModels') == model_hashes,
        'Shared predictions must bind this complete collection and both original behavior heads.')
    descriptor = raw['predictorDescriptor']
    policy.require(isinstance(descriptor, dict) and set(descriptor) == {'path', 'sha256'},
                   'Shared predictions require one separately frozen predictor descriptor.')
    descriptor_path = Path(descriptor['path']).resolve(strict=True)
    policy.require(policy.file_digest(descriptor_path) == descriptor['sha256'],
                   'The shared frozen predictor descriptor changed.')
    subject = policy.read_json(descriptor_path)
    policy.require(subject.get('kind') == DESCRIPTOR and all(
        isinstance(subject.get(key), str) and len(subject[key]) == 64
        and all(char in '0123456789abcdef' for char in subject[key])
        for key in ('checkpointSha256', 'stateDictionarySha256', 'inferenceContractSha256')),
        'The descriptor must explicitly bind a shared checkpoint, state dictionary and inference contract.')
    predictions = raw.get('predictions')
    policy.require(isinstance(predictions, list) and len(predictions) == len(merged),
                   'One shared prediction must cover each recorded controlled decision, including forced rows.')
    values = []
    for prediction, (_, _, identity, _) in zip(predictions, merged):
        policy.require(set(prediction) == set(identity) | {'value'}
                       and type(prediction['decision']) is int and type(prediction['sourceRow']) is int
                       and {key: prediction[key] for key in identity} == identity,
                       'Shared predictions must retain exact game/absolute-decision/phase/sourceRow identity.')
        values.append(policy.number(prediction['value'], 'Frozen shared state value', 0, 1))
    policy.require(policy.file_digest(path) == prediction_hash
                   and policy.file_digest(descriptor_path) == descriptor['sha256'],
                   'Frozen prediction or descriptor bytes changed while deriving targets.')
    binding = dict(path=str(path), sha256=prediction_hash,
                   predictorDescriptor=dict(path=str(descriptor_path), sha256=descriptor['sha256']))
    return values, binding


def build_targets(phase_rows, provenance, model_hashes, predictions_file):
    """Derive both clocks from the same validated values using unchanged purchase GAE arithmetic.

    Chronological GAE advances on every merged controlled card/purchase row. The phase-separated
    control skips other-phase records, giving the terminal reward to each phase's last row. Both
    include forced decisions, gamma1 and lambda.95. No values or targets enter actor observations.
    """
    merged = chronology(phase_rows, provenance)
    values, binding = prediction_values(predictions_file, merged, provenance, model_hashes)
    rows = [item[3] for item in merged]
    chronological = policy.purchase_gae(rows, values)
    separate = [0.] * len(merged)
    for phase in joint.PHASES:
        indices = [index for index, item in enumerate(merged) if item[2]['phase'] == phase]
        advantages = policy.purchase_gae([rows[index] for index in indices], [values[index] for index in indices])
        for index, advantage in zip(indices, advantages):
            separate[index] = advantage
    documents = {}
    for clock, advantages in ((Clock.CHRONOLOGICAL, chronological), (Clock.PHASE_SEPARATED, separate)):
        documents[clock.value] = dict(kind=TARGETS, estimator=estimator(clock),
            collectionSha256=provenance['collectionSha256'], gameHashes=provenance['gameHashes'],
            behaviorModels=dict(model_hashes), frozenStatePredictions=binding,
            targets=[dict(identity, advantage=value) for (_, _, identity, _), value in zip(merged, advantages)],
            newFits=0, actorUpdates=0, targetBuilderSha256=policy.file_digest(__file__),
            arithmeticOwnerSha256=policy.file_digest(policy.__file__))
    return documents


def write_targets(collection, predictions_file, output):
    """Publish immutable detached documents only; no actor or optimizer is created."""
    joint.unchanged(collection.pins)
    output = Path(output)
    policy.require(not output.exists(), 'Preserve every previously published joint target pair.')
    hashes = {phase: collection.bundle[phase]['sha256'] for phase in joint.PHASES}
    documents = build_targets(collection.rows, collection.common, hashes, predictions_file)
    joint.unchanged(collection.pins)
    output.mkdir(parents=True)
    for name, document in documents.items():
        policy.atomic_json(output/(name+'.json'), document)
    return documents


class DetachedJointTargets:
    """Phase-reader adapter only; existing backend guards reject joint actor-only fitting.

    The wrapped reader still owns exact directory/model/contract and behavior checks. This adapter
    rederives the detached target document and adds explicit provenance rather than disguising it
    as purchase-gae-1 or a state-only baseline. Existing row fields are never overwritten.
    """
    def __init__(self, collection, predictions_file, targets_file, clock):
        policy.require(isinstance(clock, Clock), 'Choose an explicit typed joint credit clock.')
        self.collection = collection
        self.pins = dict(collection.pins)
        path = joint.pin(targets_file, self.pins)
        predictions = joint.pin(predictions_file, self.pins)
        hashes = {phase: collection.bundle[phase]['sha256'] for phase in joint.PHASES}
        expected = build_targets(collection.rows, collection.common, hashes, predictions)[clock.value]
        descriptor = expected['frozenStatePredictions']['predictorDescriptor']
        joint.pin(descriptor['path'], self.pins)
        policy.require(policy.read_json(path) == expected,
                       'Detached targets must match the original complete rows, shared values and declared clock.')
        self.identity = dict(version=TARGETS, estimator=estimator(clock), targetsFile=str(path),
            targetsSha256=policy.file_digest(path), frozenStatePredictions=expected['frozenStatePredictions'],
            behaviorModels=hashes, phasePartition='Original phase-reader order; sourceRow is within each world.')
        self.targets = {(item['gameSha256'], item['decision'], item['phase']): item['advantage']
                        for item in expected['targets']}
        joint.unchanged(self.pins)

    def phase_reader(self, directory, model_path, model, *, contract):
        joint.unchanged(self.pins)
        rows, provenance = self.collection.phase_reader(directory, model_path, model, contract=contract)
        phase = contract.label
        policy.require(all('_actorOnlyAdvantage' not in row for row in rows),
                       'Original behavior rows must not already contain detached actor advantages.')
        bound = [{**row, '_actorOnlyAdvantage': self.targets[
            (provenance['gameHashes'][row['originIndex']], row['number'], phase)]} for row in rows]
        return bound, dict(provenance, readerSha256=policy.file_digest(__file__),
            jointCollectionReaderSha256=provenance['readerSha256'],
            jointDetachedTargets=dict(self.identity, phase=phase))
