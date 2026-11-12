"""Fixed offline purchase improvement on explicitly supported first actions.

This owner has no data loader, CLI, TEST-label argument, rollout or deployment path. A separate
audited reader must establish actor-safe observations, exact F0 baseline indices, paired streams
and first-action-only provenance. The existing Float64 network owns all numerical inference;
only its 64 actor weights can change. This is an offline objective, not a PPO trajectory update.
"""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import math
import time

import torch

import purchase_policy as policy

ALPHA = .5
KL_COEFFICIENT = .01
LEARNING_RATE = .001
UPDATES = 500
VALIDATE_EVERY = 10
G2_SHA256 = '09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af'
SPLIT_SHA256 = 'a5e75cca16b3190a1d32f8a792df305de95f778cf0849e8496116c4f9b3450fd'
require = policy.require


class Split(Enum):
    TRAIN = 'TRAIN'
    VALIDATION = 'VALIDATION'
    TEST = 'TEST'


@dataclass(frozen=True)
class FrozenReference:
    """Original raw artifact bytes, retaining their file digest rather than a re-encoding hash."""
    encoded: bytes
    sha256: str

    def __post_init__(self):
        require(type(self.encoded) is bytes and hashlib.sha256(self.encoded).hexdigest() == self.sha256,
                'Frozen reference bytes must match the declared artifact SHA-256.')
        policy.validate_model(self.model())

    def model(self):
        return json.loads(self.encoded)


@dataclass(frozen=True)
class SupportedOrigin:
    """One independent origin; each label is exactly two shared-stream normalized returns.

    Observation bytes and numeric tuples detach this object from mutable caller records. Returns
    never enter an observation. Indices use the original full canonical action list, including
    unsupported actions. TEST records are rejected before their labels are inspected.
    """
    origin_index: int
    split: Split
    observation_bytes: bytes
    pass_index: int
    supported_indices: tuple
    first_only_returns: tuple

    def __post_init__(self):
        policy.integer(self.origin_index, 'Prospective origin index', 0, 511)
        expected = Split.TRAIN if self.origin_index < 384 else (
            Split.VALIDATION if self.origin_index < 448 else Split.TEST)
        require(self.split in (Split.TRAIN, Split.VALIDATION) and self.split is expected,
                'Use the frozen whole-origin TRAIN/VALIDATION split; TEST labels are not admitted.')
        require(type(self.observation_bytes) is bytes, 'Observation must have detached JSON bytes.')
        observation = self.observation()
        count = len(observation['actions'])
        policy.integer(self.pass_index, 'Pass index', 0, count - 1)
        require(observation['actions'][self.pass_index]['command'] == 'pass',
                'The reference label must identify the original offered pass command.')
        require(type(self.supported_indices) is tuple and 2 <= len(self.supported_indices) <= 5
                and all(type(i) is int and 0 <= i < count for i in self.supported_indices)
                and tuple(sorted(set(self.supported_indices))) == self.supported_indices
                and self.pass_index in self.supported_indices,
                'Support requires pass and at most four distinct canonical offered first actions.')
        require(type(self.first_only_returns) is tuple
                and len(self.first_only_returns) == len(self.supported_indices),
                'Every supported action requires its own paired first-action-only returns.')
        for pair in self.first_only_returns:
            require(type(pair) is tuple and len(pair) == 2,
                    'Exactly two paired future-stream returns are required for every action.')
            for value in pair:
                policy.number(value, 'First-action-only normalized return', 0, 1)

    @classmethod
    def from_record(cls, record, model):
        require(isinstance(record, dict) and record.get('split') in ('TRAIN', 'VALIDATION'),
                'TEST labels must not be loaded or evaluated by this fitter.')
        require(set(record) == {'originIndex', 'split', 'observation', 'passIndex',
                                'supportedIndices', 'firstOnlyReturns'},
                'Supported records contain only original observations and first-action-only labels.')
        policy.validate_observation(record['observation'], model)
        support, labels = record['supportedIndices'], record['firstOnlyReturns']
        require(isinstance(support, list) and isinstance(labels, list)
                and all(isinstance(row, dict) and set(row) == {'actionIndex', 'returns'}
                        and type(row['actionIndex']) is int
                        and isinstance(row['returns'], list) for row in labels)
                and [row['actionIndex'] for row in labels] == support,
                'Paired labels must cover the exact supported action indices in canonical order.')
        return cls(record['originIndex'], Split(record['split']),
                   policy.canonical(record['observation']), record['passIndex'], tuple(support),
                   tuple(tuple(policy.number(v, 'First-only return', 0, 1) for v in row['returns'])
                         for row in labels))

    def observation(self):
        return json.loads(self.observation_bytes)

    def advantages(self):
        reference = self.first_only_returns[self.supported_indices.index(self.pass_index)]
        return tuple(sum(value - baseline for value, baseline in zip(pair, reference)) / 2
                     for pair in self.first_only_returns)

    def record(self):
        return dict(originIndex=self.origin_index, split=self.split.value,
                    observation=self.observation(), passIndex=self.pass_index,
                    supportedIndices=list(self.supported_indices), firstOnlyReturns=[
                        dict(actionIndex=index, returns=list(pair))
                        for index, pair in zip(self.supported_indices, self.first_only_returns)])


def rows_digest(rows):
    """Canonical digest of already validated eligible rows, not a raw collection-file digest."""
    return policy.digest([row.record() for row in rows])


def effective_probabilities(reference_logp, candidate_logp, indices):
    """Keep unsupported probabilities bit-identical; redistribute only half the support mass."""
    require(reference_logp.ndim == candidate_logp.ndim == 1
            and reference_logp.shape == candidate_logp.shape
            and reference_logp.dtype == candidate_logp.dtype == policy.DTYPE
            and reference_logp.device.type == candidate_logp.device.type == 'cpu',
            'Supported inference requires matching CPU Float64 full-action log probabilities.')
    reference = reference_logp.detach().exp()
    supported = torch.tensor(indices, dtype=torch.long)
    old = reference[supported]
    conditional = torch.softmax(candidate_logp[supported], dim=0)
    replacement = (1 - ALPHA) * old + ALPHA * old.sum() * conditional
    if torch.equal(reference_logp.detach(), candidate_logp.detach()):
        # Preserve exact G2 output while keeping the first optimizer step's real gradient.
        replacement = replacement + (old - replacement).detach()
    return reference.index_copy(0, supported, replacement)


@dataclass(frozen=True)
class PreparedBatch:
    rows: tuple
    observations: tuple
    reference_logp: tuple


class SupportedActor:
    """One fresh optimizer, fixed G2 basis/value parameters, and a 64-number offline head."""
    def __init__(self, reference):
        require(isinstance(reference, FrozenReference), 'Use an explicitly bound frozen reference.')
        require(torch.get_num_threads() == 1, 'Supported fitting requires one CPU inference thread.')
        self.reference = reference
        self.actor = policy.PurchasePolicy(reference.model())
        self.original = policy.PurchasePolicy(reference.model())
        self.original.requires_grad_(False)
        for name, parameter in self.actor.named_parameters():
            parameter.requires_grad_(name == 'actorWeights')
        self.optimizer = torch.optim.Adam([self.actor.actorWeights], lr=LEARNING_RATE, foreach=False)

    def prepare(self, rows, split):
        require(split in (Split.TRAIN, Split.VALIDATION) and rows,
                'Prepare only a nonempty TRAIN or VALIDATION set; never TEST labels.')
        require(all(isinstance(row, SupportedOrigin) and row.split is split for row in rows),
                'Every prepared origin must belong to its declared split.')
        ids = [row.origin_index for row in rows]
        require(ids == sorted(set(ids)), 'Each independent origin appears exactly once in sorted order.')
        batches, current, actions = [], [], 0
        for row in rows:
            observation = self.actor.prepare(row.observation())
            if current and (len(current) == 256 or actions + observation.action_count > 16384):
                batches.append(self._batch(current))
                current, actions = [], 0
            current.append((row, observation))
            actions += observation.action_count
        if current:
            batches.append(self._batch(current))
        return tuple(batches)

    def _batch(self, rows):
        observations = tuple(observation for _, observation in rows)
        with torch.no_grad():
            reference_logp, _ = self.original(observations)
        return PreparedBatch(tuple(row for row, _ in rows), observations, tuple(reference_logp))

    def batch_terms(self, batch):
        """One term per whole origin; no action-count or tail-count reweighting."""
        candidate_logp, _ = self.actor(batch.observations)
        gains, divergences, changes = [], [], []
        for row, old_logp, new_logp in zip(batch.rows, batch.reference_logp, candidate_logp):
            probabilities = effective_probabilities(old_logp, new_logp, row.supported_indices)
            original = old_logp.exp()
            advantages = torch.tensor(row.advantages(), dtype=policy.DTYPE)
            gains.append(((probabilities - original)[list(row.supported_indices)] * advantages).sum())
            divergences.append((probabilities * (probabilities.log() - original.log())).sum())
            changes.append((probabilities - original).abs().max())
        gain, kl = torch.stack(gains), torch.stack(divergences)
        return gain - KL_COEFFICIENT * kl, gain, kl, torch.stack(changes)

    def step(self, batches):
        """Accumulate the exact whole-TRAIN mean gradient, then make one fresh-Adam step."""
        count = sum(len(batch.rows) for batch in batches)
        require(count > 0 and all(row.split is Split.TRAIN for batch in batches for row in batch.rows),
                'Optimizer steps require only eligible TRAIN origins.')
        self.optimizer.zero_grad(set_to_none=True)
        for batch in batches:
            objective, _, _, _ = self.batch_terms(batch)
            require(bool(torch.isfinite(objective).all()), 'Offline objectives must remain finite.')
            (-objective.sum() / count).backward()
        require(bool(torch.isfinite(self.actor.actorWeights.grad).all()),
                'Offline actor gradient must remain finite.')
        self.optimizer.step()
        require(bool(torch.isfinite(self.actor.actorWeights).all()), 'Offline actor weights must remain finite.')

    def evaluate(self, batches):
        count = sum(len(batch.rows) for batch in batches)
        require(count > 0, 'Evaluate a nonempty admitted origin set.')
        totals = [0., 0., 0.]
        maximum = 0.
        with torch.no_grad():
            for batch in batches:
                objective, gain, kl, change = self.batch_terms(batch)
                for i, term in enumerate((objective, gain, kl)):
                    totals[i] += float(term.sum())
                maximum = max(maximum, float(change.max()))
        require(all(math.isfinite(v) for v in [*totals, maximum]), 'Offline metrics must remain finite.')
        return dict(origins=count, objective=totals[0] / count, gain=totals[1] / count,
                    meanKl=totals[2] / count, maximumProbabilityChange=maximum)

    def artifact(self):
        model = self.actor.artifact()
        original = self.reference.model()
        require(all(value == original[name] for name, value in model.items() if name != 'actorWeights'),
                'Only the 64 actor weights may change; original basis and value head remain frozen.')
        return model


@dataclass(frozen=True)
class FitAdmission:
    """Reader-proven eligibility counts and exact row subjects; TEST contributes only its count."""
    reference_sha256: str
    split_sha256: str
    training_rows_sha256: str
    validation_rows_sha256: str
    eligible_train: int
    eligible_validation: int
    eligible_test: int

    def __post_init__(self):
        require(self.reference_sha256 == G2_SHA256 and self.split_sha256 == SPLIT_SHA256,
                'Scientific fitting requires exact G2 initialization and the frozen 59 origin split.')
        require(all(isinstance(value, str) and len(value) == 64
                    and all(character in '0123456789abcdef' for character in value)
                    for value in (self.training_rows_sha256, self.validation_rows_sha256)),
                'Admitted TRAIN/VALIDATION rows require exact canonical SHA-256 identities.')
        for value, label, minimum, maximum in (
                (self.eligible_train, 'TRAIN eligibility', 160, 384),
                (self.eligible_validation, 'VALIDATION eligibility', 24, 64),
                (self.eligible_test, 'TEST count-only eligibility', 24, 64)):
            policy.integer(value, label, minimum, maximum)

    def validate(self, reference, training, validation):
        require(isinstance(reference, FrozenReference) and reference.sha256 == self.reference_sha256,
                'Scientific fitting requires the exact original G2 artifact bytes.')
        require(len(training) == self.eligible_train and len(validation) == self.eligible_validation
                and rows_digest(training) == self.training_rows_sha256
                and rows_digest(validation) == self.validation_rows_sha256,
                'Fit the complete admitted TRAIN/VALIDATION rows, with no origin selection or refill.')


@dataclass
class ValidationSelection:
    """Strict improvements only; ties preserve the earliest checkpoint, including unchanged G2."""
    objective: float
    update: int = 0
    last_evaluated: int = 0

    def __post_init__(self):
        policy.number(self.objective, 'Initial validation objective')

    def consider(self, update, metrics):
        policy.integer(update, 'Validation update', VALIDATE_EVERY, UPDATES)
        require(update == self.last_evaluated + VALIDATE_EVERY,
                'Validation must advance exactly ten updates without extra looks.')
        self.last_evaluated = update
        objective = policy.number(metrics['objective'], 'Validation objective')
        if objective > self.objective:
            self.objective, self.update = objective, update
            return True
        return False


def fit_supported(reference, training, validation, *, admission, deadline):
    """One fixed 500-step full-TRAIN recipe; caller owns one-attempt lifecycle and persistence.

    A finite monotonic deadline aborts rather than publishing a partially searched candidate.
    There is no resume, tuning, TEST evaluation, hard PPO KL cap, or automatic qualification.
    """
    require(isinstance(admission, FitAdmission), 'Fitting requires typed frozen-source admission.')
    admission.validate(reference, training, validation)
    policy.number(deadline, 'Monotonic deadline', time.monotonic())
    learner = SupportedActor(reference)
    train = learner.prepare(training, Split.TRAIN)
    valid = learner.prepare(validation, Split.VALIDATION)
    initial = learner.evaluate(valid)
    selection = ValidationSelection(initial['objective'])
    selected = learner.artifact()
    history = [dict(update=0, training=learner.evaluate(train), validation=initial)]
    for update in range(1, UPDATES + 1):
        require(time.monotonic() < deadline, 'Original offline fitting deadline expired; no retry.')
        learner.step(train)
        if update % VALIDATE_EVERY == 0:
            metrics = learner.evaluate(valid)
            history.append(dict(update=update, training=learner.evaluate(train), validation=metrics))
            if selection.consider(update, metrics):
                selected = learner.artifact()
    require(time.monotonic() < deadline, 'Original offline fitting deadline expired; no partial selection.')
    learner.artifact()  # Check the final frozen basis even when an earlier model was selected.
    return dict(kind='purchase-supported-offline-fit-1', model=selected, selectedUpdate=selection.update,
                referenceSha256=reference.sha256, splitSha256=admission.split_sha256,
                trainingRowsSha256=admission.training_rows_sha256,
                validationRowsSha256=admission.validation_rows_sha256,
                updates=UPDATES, alpha=ALPHA, klCoefficient=KL_COEFFICIENT,
                learningRate=LEARNING_RATE, learnedParameters=['actorWeights'],
                optimizerInitialization='fresh-adam', history=history, testLabelsRead=False,
                wholeGameStrengthVerified=False, activation=False)
