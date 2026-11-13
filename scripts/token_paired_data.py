"""Immutable public inputs and detached paired returns for the direct TOKEN2 objective.

The campaign verifies native archives before this boundary. Its normalized records retain
raw source/request subjects; this owner never opens an archive or discovers a data path.
"""
from dataclasses import dataclass
import hashlib
import json
import math
import re

import purchase_policy as b
import token_paired_policy as actor


def sha(value, label):
    b.require(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value),
        f'{label} requires a lowercase SHA256 subject.')
    return value


def exact(value, keys, label):
    b.require(isinstance(value, dict) and set(value) == set(keys),
        f'{label} requires its exact public field set.')


def probabilities(values, count, label):
    b.require(isinstance(values, list) and len(values) == count,
        f'{label} must retain every offered probability.')
    result = tuple(b.number(value, label, 0., 1.) for value in values)
    b.require(all(value > 0 for value in result) and abs(math.fsum(result)-1.) <= 2e-12,
        f'{label} requires a positive normalized full-offer distribution.')
    return result


@dataclass(frozen=True)
class FrozenActor:
    raw: bytes
    sha256: str

    def __post_init__(self):
        sha(self.sha256, 'Actor')
        b.require(type(self.raw) is bytes and hashlib.sha256(self.raw).hexdigest() == self.sha256,
            'Frozen actor bytes must match their original raw subject.')
        actor.validate_model(json.loads(self.raw))

    @property
    def model(self):
        return json.loads(self.raw)

    @property
    def dictionary_sha256(self):
        model = self.model
        return b.digest({key: model[key] for key in (
            'encoderVersion', 'stateFeatureNames', 'actionFeatureNames')})


@dataclass(frozen=True)
class Context:
    """Portable numerical identity after the campaign verifies every native sidecar.

    source_sha256 pins the assembled iteration corpus receipt (including every shard),
    scope_sha256 is the stable native scopeSha256 (fixed ecology/original actor/contracts),
    and request_bank_sha256 pins all640
    prospective requests. Raw shard/public/label subjects remain in that admitted corpus.
    The raw dictionary file subject and canonical dictionary meaning are deliberately separate.
    """
    iteration: int
    source_sha256: str
    scope_sha256: str
    request_bank_sha256: str
    dictionary_sha256: str
    dictionary_semantic_sha256: str
    original_model_sha256: str
    current_model_sha256: str

    def __post_init__(self):
        b.integer(self.iteration, 'Iteration', 1, 2)
        for key, value in vars(self).items():
            if key != 'iteration':
                sha(value, key)

    def bind(self, original, current, dictionary):
        b.require(isinstance(original, FrozenActor) and isinstance(current, FrozenActor) and
            original.sha256 == self.original_model_sha256 and
            current.sha256 == self.current_model_sha256 and
            original.dictionary_sha256 == current.dictionary_sha256 == self.dictionary_semantic_sha256,
            'TOKEN2 context must bind exact original/current actors and complete dictionaries.')
        b.require(type(dictionary) is bytes and hashlib.sha256(dictionary).hexdigest() ==
            self.dictionary_sha256 and b.digest(json.loads(dictionary)) == self.dictionary_semantic_sha256,
            'Raw native dictionary subject and canonical numerical dictionary must both match.')
        exact(json.loads(dictionary), ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames'),
            'Native dictionary')
        model = original.model
        b.require(all(value == 0. for value in model['actorWeights']) and
            all(value == 0. for value in model['valueWeights']) and model['valueBias'] == 0.,
            'The original TOKEN2 anchor must be the fixed zero-output initializer.')
        b.require(self.iteration != 1 or original.raw == current.raw,
            'Iteration1 must use the exact original actor bytes.')


def observation(raw, model):
    exact(raw, ('encoderVersion', 'stateFeatures', 'actions', 'baselineIndex'), 'Observation')
    for action in raw['actions']:
        exact(action, ('command', 'features'), 'Offered action')
    b.validate_observation(raw, model, contract=actor.CONTRACT)
    return b.canonical(raw)


ROOT_FIELDS = ('index', 'split', 'status', 'requestSha256', 'decisionNumber', 'batchStartNumber',
    'observation', 'currentProbabilities', 'originalProbabilities', 'sampledIndex',
    'proposedIndices', 'inclusionProbability', 'futureSeeds')


@dataclass(frozen=True, init=False)
class PublicRoot:
    context: Context
    wire: bytes

    @classmethod
    def from_record(cls, record, context, model, *, split):
        return cls(record, context, model, split=split)

    def __init__(self, record, context, model, *, split):
        # Split access precedes observations and every possible outcome payload.
        b.require(split in ('TRAIN', 'VALIDATION') and isinstance(record, dict) and
            record.get('split') == split, 'Root split must be admitted before decoding its contents.')
        b.require(isinstance(context, Context) and context.dictionary_semantic_sha256 ==
            b.digest({key: model[key] for key in ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')}),
            'Public input requires the exact typed context and complete model dictionary.')
        exact(record, ROOT_FIELDS, 'Public root')
        index = b.integer(record['index'], 'Global origin index', 0, 639)
        b.require((index < 512) == (split == 'TRAIN'), 'Global origin split membership is immutable.')
        sha(record['requestSha256'], 'Original world request')
        seeds = record['futureSeeds']
        b.require(isinstance(seeds, list) and len(seeds) == 2 and seeds[0] != seeds[1],
            'A root requires exactly two distinct original future streams.')
        for seed in seeds:
            b.integer(seed, 'Future stream', -(2**63), 2**63-1)
        status = record['status']
        b.require(status in ('MISSING', 'SINGLETON', 'READY'), 'Unknown public support status.')
        if status == 'MISSING':
            b.require(all(record[key] is None for key in ROOT_FIELDS if key not in
                ('index', 'split', 'status', 'requestSha256', 'futureSeeds')),
                'Missing roots retain no fabricated decision, observation or probabilities.')
            object.__setattr__(self, 'context', context)
            object.__setattr__(self, 'wire', b.canonical(record))
            return
        number = b.integer(record['decisionNumber'], 'Absolute PLAYER decision', 0, 2**53)
        b.integer(record['batchStartNumber'], 'Sealed-batch start', 0, number)
        observation(record['observation'], model)
        count = len(record['observation']['actions'])
        b.require((count > 1) == (status == 'READY'), 'Support status must match the original offer.')
        reference = record['observation']['baselineIndex']
        b.integer(record['sampledIndex'], 'Actual sampled behavior action', 0, count-1)
        probabilities(record['currentProbabilities'], count, 'Behavior probabilities')
        original = probabilities(record['originalProbabilities'], count, 'Original probabilities')
        b.require(list(original) == b.prior_probabilities(count, reference),
            'Original zero-output probabilities must equal the exact current-T prior.')
        indices = record['proposedIndices']; size = min(8, count-1)
        b.require(isinstance(indices, list) and len(indices) == size+1 and
            len(set(indices)) == len(indices) and indices[0] == reference,
            'Proposals require reference first and every distinct prospectively sampled sibling.')
        for index in indices:
            b.integer(index, 'Original proposed action index', 0, count-1)
        b.require(record['inclusionProbability'] == (size/(count-1) if count > 1 else 0.),
            'Nonreference inclusion probability must be the original uniform-subset probability.')
        object.__setattr__(self, 'context', context)
        object.__setattr__(self, 'wire', b.canonical(record))

    @property
    def record(self):
        return json.loads(self.wire)

    @property
    def sha256(self):
        return hashlib.sha256(self.wire).hexdigest()

    @property
    def ready(self):
        return self.record['status'] == 'READY'


@dataclass(frozen=True)
class PairedTarget:
    public: PublicRoot
    rewards: tuple

    def __post_init__(self):
        b.require(isinstance(self.public, PublicRoot) and self.public.ready and
            type(self.rewards) is tuple and len(self.rewards) == len(self.public.record['proposedIndices']),
            'Typed paired targets require every sampled sibling at a READY public root.')
        for row in self.rewards:
            b.require(type(row) is tuple and len(row) == 2, 'Each immutable target has exactly two futures.')
            for value in row:
                b.number(value, 'Detached normalized terminal reward', 0., 1.)

    @classmethod
    def from_record(cls, public, record, *, split='TRAIN'):
        b.require(public.record['split'] == split and split in ('TRAIN', 'VALIDATION'),
            'Paired labels require the explicitly admitted split.')
        b.require(public.ready, 'Missing/singleton roots do not invent paired labels.')
        exact(record, ('publicSha256', 'futureSeeds', 'actions'), 'Paired labels')
        source = public.record
        b.require(record['publicSha256'] == public.sha256 and
            record['futureSeeds'] == source['futureSeeds'],
            'Labels must bind the exact public input and both original shared streams.')
        actions = record['actions']
        b.require(isinstance(actions, list) and len(actions) == len(source['proposedIndices']),
            'Every sampled sibling and the reference require paired returns.')
        rewards = []
        for expected, item in zip(source['proposedIndices'], actions):
            exact(item, ('actionIndex', 'rewards'), 'Paired action')
            b.require(item['actionIndex'] == expected and isinstance(item['rewards'], list) and
                len(item['rewards']) == 2, 'Paired action order/stream count cannot change.')
            rewards.append(tuple(b.number(value, 'Normalized terminal reward', 0., 1.)
                for value in item['rewards']))
        return cls(public, tuple(rewards))

    @property
    def deltas(self):
        reference = self.rewards[0]
        return tuple(math.fsum(value-base for value, base in zip(row, reference))/2
            for row in self.rewards)


@dataclass(frozen=True)
class AnchorWorld:
    index: int
    observations: tuple
    subject_sha256: str

    def __post_init__(self):
        b.integer(self.index, 'Original anchor world', 0, 511)
        b.require(type(self.observations) is tuple and all(type(row) is bytes for row in self.observations),
            'Original anchor rows must be immutable public bytes.')
        rows = [json.loads(row) for row in self.observations]
        for row in rows:
            exact(row, ('decisionNumber', 'batchStartNumber', 'observation', 'originalProbabilities'),
                'Original anchor observation')
        b.require(self.subject_sha256 == b.digest(dict(index=self.index, split='TRAIN', observations=rows)),
            'Original anchor world must retain its complete immutable public subject.')

    @classmethod
    def from_record(cls, record, model):
        b.require(isinstance(record, dict) and record.get('split') == 'TRAIN',
            'Anchor admission rejects non-TRAIN before observation decoding.')
        exact(record, ('index', 'split', 'observations'), 'Original anchor world')
        index = b.integer(record['index'], 'Original anchor TRAIN world', 0, 511)
        b.require(isinstance(record['observations'], list), 'Anchor world requires a recorded row list.')
        rows = []; keys = set()
        for row in record['observations']:
            exact(row, ('decisionNumber', 'batchStartNumber', 'observation', 'originalProbabilities'),
                'Original anchor observation')
            number = b.integer(row['decisionNumber'], 'Anchor absolute decision', 0, 2**53)
            start = b.integer(row['batchStartNumber'], 'Anchor sealed-batch start', 0, number)
            b.require((number, start) not in keys, 'Anchor state identities must be unique per world.')
            keys.add((number, start)); observation(row['observation'], model)
            view = row['observation']
            b.require(row['originalProbabilities'] == b.prior_probabilities(
                len(view['actions']), view['baselineIndex']), 'Anchor must retain exact original pi0.')
            rows.append(b.canonical(row))
        return cls(index, tuple(rows), b.digest(record))


@dataclass(frozen=True)
class Training:
    context: Context
    roots: tuple
    targets: tuple
    anchors: tuple
    validation_ready_count: int

    def __post_init__(self):
        b.integer(self.validation_ready_count, 'Public validation READY count', 64, 128)
        b.require(isinstance(self.context, Context) and
            all(type(values) is tuple for values in (self.roots, self.targets, self.anchors)) and
            all(isinstance(root, PublicRoot) for root in self.roots),
            'Training context and complete populations must be typed and immutable.')
        b.require(len(self.roots) == len(self.targets) == len(self.anchors) == 512,
            'Training retains all512 requested worlds, labels and original anchor groups.')
        b.require(len({root.record['requestSha256'] for root in self.roots}) == 512 and
            len({seed for root in self.roots for seed in root.record['futureSeeds']}) == 1024,
            'Every TRAIN world request and future stream must have its independent original identity.')
        for index, (root, target, anchor) in enumerate(zip(self.roots, self.targets, self.anchors)):
            b.require(isinstance(root, PublicRoot) and root.context == self.context and
                root.record['index'] == index and root.record['split'] == 'TRAIN' and
                isinstance(anchor, AnchorWorld) and anchor.index == index,
                'TRAIN world order, source, scope and complete original anchor bank are fixed.')
            b.require((isinstance(target, PairedTarget) and target.public == root) if root.ready
                else target is None, 'READY roots require exact labels; missing/singleton remain unlabeled.')
            if self.context.iteration == 1 and root.record['status'] != 'MISSING':
                record = root.record
                matches = [json.loads(row) for row in anchor.observations if
                    json.loads(row)['decisionNumber'] == record['decisionNumber'] and
                    json.loads(row)['batchStartNumber'] == record['batchStartNumber']]
                b.require(len(matches) == 1 and all(matches[0][key] == record[key] for key in
                    ('observation', 'originalProbabilities')),
                    'Iteration1 root must match its unique original backbone state, not duplicate it.')

    @property
    def ready_count(self):
        return sum(root.ready for root in self.roots)

    @property
    def anchor_sha256(self):
        return b.digest([anchor.subject_sha256 for anchor in self.anchors])

    @property
    def sha256(self):
        return b.digest(dict(context=vars(self.context), roots=[root.sha256 for root in self.roots],
            labels=[None if item is None else item.rewards for item in self.targets],
            anchors=self.anchor_sha256, validationReadyCount=self.validation_ready_count))
