"""Versioned immutable warm-anchor public roots and detached pure-win sibling targets.

Native evidence admission owns authority replay and the frozen94 computation. This numerical
boundary independently verifies recorded distributions, complete offers, split and paired labels.
Legacy TOKEN contexts and their two-round/512-root contracts remain unchanged.
"""
from dataclasses import dataclass, asdict
import json
import hashlib
import re

import purchase_policy as b
import token_paired_data as legacy
import token_paired_objective as objective
import token_anchor_distribution as distribution


@dataclass(frozen=True)
class Context:
    round: int
    train_count: int
    validation_count: int
    source_sha256: str
    scope_sha256: str
    request_bank_sha256: str
    dictionary_sha256: str
    dictionary_semantic_sha256: str
    original_model_sha256: str
    current_model_sha256: str
    anchor_sha256: str
    probability_contract_sha256: str

    def __post_init__(self):
        b.integer(self.round, 'Declared warm-anchor round', 1, 16)
        b.integer(self.train_count, 'Declared TRAIN origins', 1, 4095)
        b.integer(self.validation_count, 'Declared public diagnostic origins', 1, 4095)
        b.require(self.train_count+self.validation_count <= 4096,
            'The declared warm family retains at most4096 total original origins per round.')
        for key in self.__dataclass_fields__:
            if key not in ('round', 'train_count', 'validation_count'):
                legacy.sha(getattr(self, key), key)
        b.require(self.probability_contract_sha256 == b.digest(distribution.RECORD),
            'Warm data must bind the exact shared full-support distribution contract.')

    @property
    def iteration(self):
        return self.round

    @property
    def objective(self):
        return objective.WIN

    def bind(self, original, current, dictionary):
        b.require(isinstance(original, legacy.FrozenActor) and isinstance(current, legacy.FrozenActor) and
            original.sha256 == self.original_model_sha256 and current.sha256 == self.current_model_sha256 and
            original.dictionary_sha256 == current.dictionary_sha256 == self.dictionary_semantic_sha256,
            'Warm context must bind exact zero/current residuals and the complete TOKEN2 dictionary.')
        b.require(type(dictionary) is bytes and b.digest(json.loads(dictionary)) == self.dictionary_semantic_sha256
            and hashlib.sha256(dictionary).hexdigest() == self.dictionary_sha256,
            'Warm context requires both raw and semantic native dictionary identity.')
        legacy.exact(json.loads(dictionary), ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames'),
            'Native TOKEN2 dictionary')
        b.require(all(value == 0. for value in original.model['actorWeights']) and
            all(value == 0. for value in original.model['valueWeights']) and original.model['valueBias'] == 0.,
            'Warm anchor uses a distinct zero-output residual, never the nonzero frozen94 actor.')
        b.require(self.round != 1 or original.raw == current.raw,
            'The first warm round must start from the byte-exact zero residual.')


ROOT_FIELDS = (*(key for key in legacy.ROOT_FIELDS if key != 'inclusionProbability'),
    'anchor94Probabilities', 'referenceIndex', 'rawReferenceIndex', 'mandatoryIndices', 'inclusionProbabilities')
PROPOSAL_BUDGET = 12


def mandatory_indices(record):
    actions = record['observation']['actions']; current = record['currentProbabilities']
    chosen = max(range(len(actions)), key=lambda index: current[index])
    amounts = []
    for action in actions:
        match = re.fullmatch(r'buy-tokens (-?\d+)', action['command'])
        b.require(match is not None, 'Warm TOKEN proposals require original offered buy-tokens commands.')
        amounts.append(int(match[1]))
    center = amounts[chosen]
    return sorted({record['rawReferenceIndex'], record['referenceIndex'], chosen, record['sampledIndex'],
        *(index for index, amount in enumerate(amounts) if center-2 <= amount <= center+2)})


def check_proposals(record):
    count = len(record['observation']['actions']); mandatory = mandatory_indices(record)
    indices = record['proposedIndices']; size = min(PROPOSAL_BUDGET, count)
    b.require(record['mandatoryIndices'] == mandatory and len(mandatory) <= 8,
        'Warm mandatory proposals must be the raw/94/current references, offered signed neighbors and sampled action.')
    b.require(type(indices) is list and len(indices) == size and len(set(indices)) == size and
        indices[0] == record['referenceIndex'] and indices[1:] == sorted(indices[1:]) and
        set(mandatory) <= set(indices), 'Warm proposals retain every mandatory arm and the original sorted random subset.')
    for index in indices:
        b.integer(index, 'Original offered sibling index', 0, count-1)
    remaining = count-len(mandatory); drawn = size-len(mandatory)
    b.require(remaining == 0 or drawn >= 1,
        'A non-census warm proposal must retain at least one uniform random arm with full support.')
    expected = [1. if index in mandatory or size == count else drawn/remaining for index in range(count)]
    b.require(record['inclusionProbabilities'] == expected,
        'Warm per-action inclusion probabilities must distinguish mandatory arms from the uniform remainder.')


def check_anchor(record, model):
    legacy.observation(record['observation'], model)
    count = len(record['observation']['actions'])
    frozen = distribution.anchor_probabilities(record['anchor94Probabilities'], count)
    original = legacy.probabilities(record['originalProbabilities'], count, 'Warm original probabilities')
    b.require(list(original) == distribution.prior(frozen),
        'Warm original probabilities must equal the exact frozen94/C05 plus uniform anchor.')
    return frozen


class PublicRoot(legacy.PublicRoot):
    def __init__(self, record, context, model, *, split):
        b.require(split in ('TRAIN', 'VALIDATION') and isinstance(record, dict) and
            record.get('split') == split, 'Warm split admission precedes observation or label decoding.')
        b.require(isinstance(context, Context), 'Warm public roots require the explicit versioned context.')
        b.require(context.dictionary_semantic_sha256 == b.digest({key: model[key] for key in
            ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')}),
            'Warm public inputs require the exact complete model dictionary.')
        legacy.exact(record, ROOT_FIELDS, 'Warm public root')
        index = b.integer(record['index'], 'Declared origin index', 0,
            context.train_count+context.validation_count-1)
        b.require((index < context.train_count) == (split == 'TRAIN'),
            'Warm root split is determined by its immutable declared index.')
        legacy.sha(record['requestSha256'], 'Original warm request')
        seeds = record['futureSeeds']
        b.require(type(seeds) is list and len(seeds) == 2 and seeds[0] != seeds[1],
            'Every warm root must retain its two original distinct paired future streams.')
        for seed in seeds:
            b.integer(seed, 'Future stream', -(2**63), 2**63-1)
        b.require(record['status'] in ('MISSING', 'SINGLETON', 'READY'), 'Unknown warm support status.')
        if record['status'] == 'MISSING':
            b.require(all(record[key] is None for key in ROOT_FIELDS if key not in
                ('index', 'split', 'status', 'requestSha256', 'futureSeeds')),
                'Missing warm roots may not fabricate observations, anchors or actions.')
        else:
            number = b.integer(record['decisionNumber'], 'Absolute decision', 0, 2**53)
            b.integer(record['batchStartNumber'], 'Original sealed-batch start', 0, number)
            frozen = check_anchor(record, model); count = len(frozen)
            b.require((count > 1) == (record['status'] == 'READY'),
                'Warm status must match its original full offered support.')
            raw_reference = record['observation']['baselineIndex']; maximum = max(frozen)
            reference = raw_reference if frozen[raw_reference] == maximum else frozen.index(maximum)
            b.require(record['rawReferenceIndex'] == raw_reference and record['referenceIndex'] == reference,
                'Raw G3 reference and frozen94 argmax with raw-reference-first ties must remain correct separate indices.')
            b.integer(record['sampledIndex'], 'Actual sampled warm action', 0, count-1)
            legacy.probabilities(record['currentProbabilities'], count, 'Warm behavior probabilities')
            check_proposals(record)
        object.__setattr__(self, 'context', context)
        object.__setattr__(self, 'wire', b.canonical(record))


@dataclass(frozen=True)
class AnchorWorld:
    index: int
    observations: tuple
    subject_sha256: str

    @classmethod
    def from_record(cls, record, model, train_count):
        b.require(type(record) is dict and record.get('split') == 'TRAIN',
            'Fixed warm anchors reject non-TRAIN before decoding observations.')
        legacy.exact(record, ('index', 'split', 'observations'), 'Warm anchor world')
        index = b.integer(record['index'], 'Original anchor origin', 0, train_count-1)
        b.require(type(record['observations']) is list, 'Warm anchors require an immutable recorded row list.')
        keys = set(); rows = []
        for row in record['observations']:
            legacy.exact(row, ('decisionNumber', 'batchStartNumber', 'observation',
                'anchor94Probabilities', 'originalProbabilities'), 'Warm anchor observation')
            number = b.integer(row['decisionNumber'], 'Anchor absolute decision', 0, 2**53)
            start = b.integer(row['batchStartNumber'], 'Anchor batch start', 0, number)
            b.require((number, start) not in keys, 'Warm anchor states must be unique within their world.')
            keys.add((number, start)); check_anchor(row, model); rows.append(b.canonical(row))
        return cls(index, tuple(rows), b.digest(record))


@dataclass(frozen=True)
class Training:
    context: Context
    roots: tuple
    targets: tuple
    anchors: tuple
    validation_ready_count: int

    def __post_init__(self):
        b.require(isinstance(self.context, Context) and all(type(items) is tuple
            for items in (self.roots, self.targets, self.anchors)),
            'Warm fitting requires typed immutable public roots, targets and original anchor groups.')
        b.require(len(self.roots) == len(self.targets) == len(self.anchors) == self.context.train_count,
            'Every declared TRAIN world, including missing and singleton roots, must remain present.')
        b.integer(self.validation_ready_count, 'Public diagnostic coverage', 0, self.context.validation_count)
        b.require(len({root.record['requestSha256'] for root in self.roots}) == len(self.roots) and
            len({seed for root in self.roots for seed in root.record['futureSeeds']}) == 2*len(self.roots),
            'Every warm TRAIN request and paired future stream must have an independent original identity.')
        for index, (root, target, anchor) in enumerate(zip(self.roots, self.targets, self.anchors)):
            b.require(isinstance(root, PublicRoot) and root.context == self.context and
                root.record['index'] == index and root.record['split'] == 'TRAIN' and
                isinstance(anchor, AnchorWorld) and anchor.index == index,
                'Warm TRAIN order, context and complete original anchor bank may not change.')
            b.require((isinstance(target, legacy.WinPairedTarget) and target.public == root) if root.ready
                else target is None, 'Warm READY roots require pure-win targets; other statuses remain unlabeled.')
            if self.context.round == 1 and root.record['status'] != 'MISSING':
                record = root.record
                matches = [json.loads(row) for row in anchor.observations if
                    json.loads(row)['decisionNumber'] == record['decisionNumber'] and
                    json.loads(row)['batchStartNumber'] == record['batchStartNumber']]
                b.require(len(matches) == 1 and all(matches[0][key] == record[key] for key in
                    ('observation', 'anchor94Probabilities', 'originalProbabilities')),
                    'First-round warm roots must match their unique original backbone anchor state.')

    @property
    def ready_count(self):
        return sum(root.ready for root in self.roots)

    @property
    def anchor_sha256(self):
        return b.digest([row.subject_sha256 for row in self.anchors])

    @property
    def sha256(self):
        return b.digest(dict(context=asdict(self.context), roots=[root.sha256 for root in self.roots],
            labels=[None if item is None else item.rewards for item in self.targets],
            anchors=self.anchor_sha256, validationReadyCount=self.validation_ready_count))
