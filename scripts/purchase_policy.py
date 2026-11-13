"""Bounded CPU PPO updates for complete, on-policy purchase trajectories.

The portable model uses fixed named dictionaries, Float64, one shared 64-unit
state embedding, and a residual actor over the qualified policy's 97% prior.
No hidden state, counterfactual labels, or authored dense rewards enter fitting.
PyTorch owns differentiation; the independent stdlib forward is a wire oracle.

Updates default to the legacy whole-epoch KL rollback. Explicit --update-mode
aor-ppo-checked-prefix-1 commits eight-minibatch blocks (or an epoch remainder),
checking cumulative KL on the entire collection against its original behavior.
Its schema-2 checkpoints retain partial-epoch model and Adam progress together;
resume preserves the original deadline and starts at the next uncommitted block.
Portable models and inference are unchanged; prefix acceptance is not strength evidence.

Purchase checked-prefix updates may explicitly supply --purchase-reference-objective with
{"version":"aor-purchase-offered-reference-1","coefficient":0.1}. This adds mean-decision
KL(uniform offered actions || policy) to training only. Omit it for unchanged PPO semantics.
"""

from __future__ import annotations

import argparse
import copy
from collections import Counter
from dataclasses import asdict, dataclass
import gzip
import hashlib
import inspect
import json
import math
import os
from pathlib import Path
import random
import re
import sys
import tempfile
import time

import torch
from torch import nn


ACTOR_VERSION = "aor-purchase-policy-1"
ENCODER_VERSION = "aor-purchase-observation-1"
HIDDEN = 64
FEATURE_LIMIT = 16.0
RESIDUAL_LIMIT = 20.0
DTYPE = torch.float64
CARD_REFERENCE_VERSION = "aor-card-physical-reference-1"
PURCHASE_REFERENCE_VERSION = "aor-purchase-offered-reference-1"
PARAMETERS = (
    "stateWeights", "stateBias", "actionWeights", "actionBias",
    "actorWeights", "valueWeights", "valueBias",
)


@dataclass(frozen=True)
class PolicyContract:
    """Explicit phase/version boundary; never inferred from untrusted model contents."""
    encoder: str
    actor: str
    label: str
    maxActions: int
    frozenPurchase: bool = False
    maxCommand: int = 200
    trajectoryEncoding: str = "json-v1"
    schemaVersion: int = 1


PURCHASE_CONTRACT = PolicyContract(ENCODER_VERSION, ACTOR_VERSION, "purchase", 128)
TOKEN_CONTRACT = PolicyContract("aor-token-observation-1", "aor-token-policy-1", "token", 128,
                                trajectoryEncoding="gzip-json-v1")
EXPANSION_CONTRACT = PolicyContract("aor-expansion-observation-1", "aor-expansion-policy-1", "expansion", 1024,
                                    trajectoryEncoding="gzip-json-v1")
OFFERED_REFERENCE_CONTRACTS = {
    "aor-token-offered-reference-1": TOKEN_CONTRACT,
    "aor-expansion-offered-reference-1": EXPANSION_CONTRACT,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, name, minimum, maximum):
    require(type(value) is int and minimum <= value <= maximum,
            f"{name} must be an integer in [{minimum}, {maximum}].")
    return value


def number(value, name, minimum=-math.inf, maximum=math.inf):
    require(type(value) in (float, int) and math.isfinite(value)
            and minimum <= value <= maximum, f"{name} must be a finite number in range.")
    return float(value)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    def reject_constant(text):
        raise ValueError(f"Nonfinite JSON number: {text}")
    return json.loads(Path(path).read_text(), parse_constant=reject_constant)


def read_game(path, contract):
    if contract.trajectoryEncoding == "json-v1":
        return read_json(path)
    require(contract.trajectoryEncoding == "gzip-json-v1", "Unsupported trajectory encoding.")
    maximum = 64 * 1024 * 1024
    with gzip.open(path, "rb") as stream:
        contents = stream.read(maximum + 1)
    require(len(contents) <= maximum, "A card trajectory exceeds the 64 MiB expanded limit.")
    def reject_constant(text):
        raise ValueError(f"Nonfinite JSON number: {text}")
    return json.loads(contents, parse_constant=reject_constant)


def validate_purchase_audit(game):
    """Validate the card collector's public diagnostic sidecar; never return fitting inputs."""
    entries = game.get("purchaseAudit")
    require(isinstance(entries, list) and len(entries) <= 12000,
            "A card trajectory requires its bounded purchase audit list.")
    previous = -1
    for entry in entries:
        require(isinstance(entry, dict) and set(entry) == {
            "number", "turn", "decision", "command", "quotedPrice", "leaderCredits"},
            "A purchase audit entry requires the declared public fields only.")
        number_index = integer(entry["number"], "Purchase audit decision", 0, 11999)
        require(number_index > previous, "Purchase audit decisions must increase strictly.")
        previous = number_index
        integer(entry["turn"], "Purchase audit turn", 1, 12000)
        require(entry["decision"] in ("purchase-shipping", "purchase-advances"),
                "Purchase audit entries must describe shipping or advance decisions.")
        command = entry["command"]
        require(isinstance(command, str) and command.strip() and len(command) <= 200
                and "\n" not in command and "\r" not in command,
                "A purchase audit command must be a bounded canonical string.")
        integer(entry["quotedPrice"], "Purchase audit price", 0, 2**31 - 1)
        credits = entry["leaderCredits"]
        require(isinstance(credits, list) and len(credits) <= 128,
                "Purchase audit leader credits must be a bounded list.")
        for credit in credits:
            require(isinstance(credit, dict) and set(credit) == {
                "leaderCardId", "baseAmount", "writtenRecordBonus", "marginalPriceSaving"},
                "A purchase audit leader credit requires the declared public fields only.")
            require(isinstance(credit["leaderCardId"], str)
                    and 1 <= len(credit["leaderCardId"]) <= 160,
                    "A purchase audit credit requires a stable card identifier.")
            for key in ("baseAmount", "writtenRecordBonus", "marginalPriceSaving"):
                integer(credit[key], f"Purchase audit {key}", 0, 2**31 - 1)


def atomic_json(path, value, *, compact=False):
    """Publish one fsynced JSON object; a failed write preserves the previous file."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    formatting = {"separators": (",", ":")} if compact else {"indent": 2}
    data = json.dumps(value, allow_nan=False, **formatting).encode() + b"\n"
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name == "posix":
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def names(value, label, maximum):
    require(isinstance(value, list) and 1 <= len(value) <= maximum,
            f"{label} requires a bounded, nonempty dictionary.")
    require(all(isinstance(name, str) and 1 <= len(name) <= 160 for name in value)
            and len(set(value)) == len(value), f"{label} requires distinct nonempty names.")
    require(value == sorted(value), f"{label} must use canonical sorted dictionary order.")
    return value


def validate_model(model, *, contract=PURCHASE_CONTRACT):
    require(model.get("schemaVersion") == contract.schemaVersion and type(model["schemaVersion"]) is int,
            f"{contract.label.capitalize()} model requires schemaVersion {contract.schemaVersion}.")
    require(model.get("encoderVersion") == contract.encoder,
            f"{contract.label.capitalize()} model encoder version is unsupported.")
    require(model.get("hiddenSize") == HIDDEN and type(model["hiddenSize"]) is int,
            "Purchase model requires 64 hidden units.")
    require(model.get("baselineProbability") == 0.97,
            "Purchase model requires the frozen 0.97 baseline probability.")
    states = names(model.get("stateFeatureNames"), "State features", 8192)
    actions = names(model.get("actionFeatureNames"), "Action features", 512)
    sizes = {"stateWeights": HIDDEN * len(states), "stateBias": HIDDEN,
             "actionWeights": HIDDEN * len(actions), "actionBias": HIDDEN,
             "actorWeights": HIDDEN, "valueWeights": HIDDEN}
    for key, size in sizes.items():
        values = model.get(key)
        require(isinstance(values, list) and len(values) == size,
                f"{key} requires {size} row-major numbers.")
        for value in values:
            number(value, key, -1e6, 1e6)
    number(model.get("valueBias"), "valueBias", -1e6, 1e6)
    return model


def initialize(schema, seed, *, contract=PURCHASE_CONTRACT):
    """Initialize from the complete catalog dictionary, never observed nonzero keys."""
    require(schema.get("encoderVersion") == contract.encoder,
            "The frozen feature dictionary requires the current encoder version.")
    states = names(schema.get("stateFeatureNames"), "State features", 8192)
    actions = names(schema.get("actionFeatureNames"), "Action features", 512)
    rng = random.Random(seed)
    def weights(size):
        scale = 1.0 / math.sqrt(size)
        return [rng.uniform(-scale, scale) for _ in range(HIDDEN * size)]
    return validate_model({
        "schemaVersion": contract.schemaVersion, "encoderVersion": contract.encoder,
        "hiddenSize": HIDDEN, "baselineProbability": 0.97,
        "stateFeatureNames": states, "actionFeatureNames": actions,
        "stateWeights": weights(len(states)), "stateBias": [0.0] * HIDDEN,
        "actionWeights": weights(len(actions)), "actionBias": [0.0] * HIDDEN,
        "actorWeights": [0.0] * HIDDEN, "valueWeights": [0.0] * HIDDEN,
        "valueBias": 0.0,
    }, contract=contract)


def sparse(values, allowed, label):
    require(isinstance(values, dict), f"{label} must be a sparse feature object.")
    require(set(values) <= allowed, f"{label} contains unknown feature names.")
    for value in values.values():
        number(value, label, -FEATURE_LIMIT, FEATURE_LIMIT)


def validate_observation(observation, model, *, contract=PURCHASE_CONTRACT):
    require(observation.get("encoderVersion") == model["encoderVersion"] == contract.encoder,
            "Observation and policy encoder versions must match.")
    sparse(observation.get("stateFeatures"), set(model["stateFeatureNames"]), "State")
    actions = observation.get("actions")
    require(isinstance(actions, list) and 1 <= len(actions) <= contract.maxActions,
            f"A {contract.label} observation requires 1–{contract.maxActions} legal actions.")
    commands = [action.get("command") for action in actions]
    require(all(isinstance(command, str) and command.strip()
                and len(command) <= contract.maxCommand and "\n" not in command
                and (not contract.frozenPurchase or "\r" not in command)
                for command in commands)
            and len(set(commands)) == len(commands), "Legal commands must be distinct strings.")
    require(commands == sorted(commands), "Legal commands must use canonical sorted order.")
    for action in actions:
        sparse(action.get("features"), set(model["actionFeatureNames"]), "Action")
    integer(observation.get("baselineIndex"), "baselineIndex", 0, len(actions) - 1)


def prior_probabilities(count, baseline):
    if count == 1:
        return [1.0]
    return [0.97 if index == baseline else 0.03 / (count - 1) for index in range(count)]


def reference_forward(model, observation, *, contract=PURCHASE_CONTRACT):
    """Independent scalar Float64 oracle; values/probabilities use only safe features."""
    validate_model(model, contract=contract)
    validate_observation(observation, model, contract=contract)
    state = [observation["stateFeatures"].get(name, 0.0)
             for name in model["stateFeatureNames"]]
    hidden = [math.tanh(model["stateBias"][unit] + sum(
        model["stateWeights"][unit * len(state) + index] * value
        for index, value in enumerate(state))) for unit in range(HIDDEN)]
    residuals = []
    for action in observation["actions"]:
        values = [action["features"].get(name, 0.0) for name in model["actionFeatureNames"]]
        result = sum(model["actorWeights"][unit] * math.tanh(
            hidden[unit] + model["actionBias"][unit] + sum(
                model["actionWeights"][unit * len(values) + index] * value
                for index, value in enumerate(values))) for unit in range(HIDDEN))
        residuals.append(max(-RESIDUAL_LIMIT, min(RESIDUAL_LIMIT, result)))
    prior = prior_probabilities(len(residuals), observation["baselineIndex"])
    logits = [math.log(probability) + residual
              for probability, residual in zip(prior, residuals)]
    if all(value == residuals[0] for value in residuals):
        probabilities = prior
    else:
        shifted = [math.exp(value - max(logits)) for value in logits]
        probabilities = [value / sum(shifted) for value in shifted]
    value_logit = model["valueBias"] + sum(
        weight * value for weight, value in zip(model["valueWeights"], hidden))
    value = (1.0 / (1.0 + math.exp(-value_logit)) if value_logit >= 0
             else math.exp(value_logit) / (1.0 + math.exp(value_logit)))
    return {"logits": logits, "probabilities": probabilities, "value": value}


def select(probabilities, uniform):
    number(uniform, "Selection uniform", 0.0, 1.0)
    require(uniform < 1.0, "Selection uniform must be strictly below one.")
    cumulative = 0.0
    for index, probability in enumerate(probabilities):
        cumulative += probability
        if uniform < cumulative:
            return index
    return len(probabilities) - 1


def selection_uniform(seed):
    """Portable unsigned 32-bit mixer; independent of the world's chance RNG."""
    value = seed & 0xffffffff
    value = ((value ^ (value >> 16)) * -2048144789) & 0xffffffff
    value = ((value ^ (value >> 13)) * -1028477387) & 0xffffffff
    value ^= value >> 16
    return value / 4294967296.0


@dataclass(frozen=True, eq=False)
class _PreparedObservation:
    """Internal validated sparse numbers; storage grows with input entries, not vocabulary size.

    Indices identify unique cells, so minibatch index_copy_ never sums or reorders arithmetic.
    Preparation is independent of model weights and reusable by matching behavior/current actors.
    """
    dictionary: str
    state_indices: torch.Tensor
    state_values: torch.Tensor
    action_indices: torch.Tensor
    action_values: torch.Tensor
    action_count: int
    baseline_index: int


class ResidualPolicy(nn.Module):
    """Compute each state's embedding once, then score a flattened legal-action batch."""
    def __init__(self, model, contract):
        super().__init__()
        self.contract = contract
        self.template = copy.deepcopy(validate_model(model, contract=contract))
        self.state_indices = {name: index for index, name in enumerate(model["stateFeatureNames"])}
        self.action_indices = {
            name: index for index, name in enumerate(model["actionFeatureNames"])}
        self.dictionary = digest({key: model[key] for key in (
            "encoderVersion", "stateFeatureNames", "actionFeatureNames")})
        for name in PARAMETERS:
            value = torch.tensor(model[name], dtype=DTYPE)
            if name == "stateWeights":
                value = value.reshape(HIDDEN, len(self.state_indices))
            elif name == "actionWeights":
                value = value.reshape(HIDDEN, len(self.action_indices))
            self.register_parameter(name, nn.Parameter(value))

    def artifact(self):
        model = copy.deepcopy(self.template)
        for name in PARAMETERS:
            tensor = getattr(self, name).detach().cpu()
            model[name] = tensor.item() if name == "valueBias" else tensor.flatten().tolist()
        return validate_model(model, contract=self.contract)

    def prepare(self, observation):
        if isinstance(observation, _PreparedObservation):
            require(observation.dictionary == self.dictionary,
                    "Prepared inputs belong to a different feature dictionary.")
            return observation
        validate_observation(observation, self.template, contract=self.contract)
        state = list(observation["stateFeatures"].items())
        actions = [(row * len(self.action_indices) + self.action_indices[name], value)
                   for row, action in enumerate(observation["actions"])
                   for name, value in action["features"].items()]
        return _PreparedObservation(
            dictionary=self.dictionary,
            state_indices=torch.tensor([self.state_indices[name] for name, _ in state],
                                       dtype=torch.long),
            state_values=torch.tensor([value for _, value in state], dtype=DTYPE),
            action_indices=torch.tensor([index for index, _ in actions], dtype=torch.long),
            action_values=torch.tensor([value for _, value in actions], dtype=DTYPE),
            action_count=len(observation["actions"]),
            baseline_index=observation["baselineIndex"],
        )

    def _batch_inputs(self, prepared):
        state = self._zeros((len(prepared), len(self.state_indices)))
        action = self._zeros((sum(item.action_count for item in prepared),
                              len(self.action_indices)))
        state_indices, action_indices, owners = [], [], []
        cursor = 0
        for row, item in enumerate(prepared):
            state_indices.append(item.state_indices + row * len(self.state_indices))
            action_indices.append(item.action_indices + cursor * len(self.action_indices))
            owners.extend([row] * item.action_count)
            cursor += item.action_count
        state.view(-1).index_copy_(0, torch.cat(state_indices),
                                  torch.cat([item.state_values for item in prepared]))
        action.view(-1).index_copy_(0, torch.cat(action_indices),
                                   torch.cat([item.action_values for item in prepared]))
        return state, action, self._owners(prepared, owners)

    def _zeros(self, shape):
        return torch.zeros(shape, dtype=DTYPE)

    def _owners(self, prepared, owners):
        return torch.tensor(owners)

    def _prior(self, observation):
        return torch.tensor(prior_probabilities(observation.action_count,
                            observation.baseline_index), dtype=DTYPE).log()

    def _exact_prior(self, log_probability, prior, residual):
        if bool(torch.all(residual == residual[0])):
            # Preserve the zero-head gradient while matching common exact-prior inference.
            return log_probability + (prior - log_probability).detach()
        return log_probability

    def forward(self, observations):
        require(1 <= len(observations) <= 256, "A numerical batch requires 1–256 decisions.")
        count = sum(item.action_count if isinstance(item, _PreparedObservation)
                    else len(item["actions"]) for item in observations)
        require(count <= 16384, "A numerical batch exceeds 16384 candidate actions.")
        prepared = [self.prepare(item) for item in observations]
        state, action, owners = self._batch_inputs(prepared)
        hidden = torch.tanh(state @ self.stateWeights.T + self.stateBias)
        action_hidden = torch.tanh(
            hidden[owners] + action @ self.actionWeights.T + self.actionBias)
        residuals = torch.clamp(action_hidden @ self.actorWeights,
                                -RESIDUAL_LIMIT, RESIDUAL_LIMIT)
        log_probabilities = []
        cursor = 0
        for observation in prepared:
            size = observation.action_count
            residual = residuals[cursor:cursor + size]
            prior = self._prior(observation)
            log_probability = torch.log_softmax(prior + residual, dim=0)
            log_probability = self._exact_prior(log_probability, prior, residual)
            log_probabilities.append(log_probability)
            cursor += size
        values = torch.sigmoid(hidden @ self.valueWeights + self.valueBias)
        return log_probabilities, values


class PurchasePolicy(ResidualPolicy):
    """Backward-compatible purchase-only boundary around the shared numerical network."""
    def __init__(self, model):
        super().__init__(model, PURCHASE_CONTRACT)


def card_reference_probabilities(commands):
    """Equal mass per offered physical hand card and Pass, then per command within a card.

    This uses the complete offered action list, including every target/protection variant of
    the baseline's card. References identify physical cards, never stable card definitions.
    Pass is a group only when offered. The reference changes the training objective only;
    it is neither a sampling distribution nor an input feature.
    """
    require(isinstance(commands, (list, tuple)) and 1 <= len(commands) <= 1024,
            "Card reference requires between 1 and 1024 offered commands.")
    require(all(isinstance(command, str) and 1 <= len(command) <= 2000
                and "\n" not in command and "\r" not in command for command in commands),
            "Card reference commands must be bounded single-line strings.")
    require(len(set(commands)) == len(commands), "Card reference commands must be distinct.")
    groups = []
    for command in commands:
        if command == "pass":
            groups.append("pass")
            continue
        match = re.fullmatch(r"play ([a-z][a-z0-9-]*)(?: .+)?", command)
        require(match is not None, "Card reference requires offered Pass or play commands.")
        groups.append("play " + match[1])
    counts = Counter(groups)
    return [1.0 / (len(counts) * counts[group]) for group in groups]


def reference_kl(log_probability, reference):
    """KL(reference || policy) for a fixed normalized reference and differentiable log pi."""
    return (reference * (reference.log() - log_probability)).sum()


@dataclass(frozen=True)
class UpdateConfig:
    """Fixed update identity, including the optional card-only physical-group KL objective.

    maxSeconds includes resume pauses; batchSize respects the 16384-action allocation cap.
    A zero cardReferenceKlCoefficient executes the original loss arithmetic exactly. A positive
    value adds coefficient * mean_decision KL(reference || pi), independently of Shannon entropy.
    """
    learningRate: float = 0.0003
    epochs: int = 4
    batchSize: int = 64
    clip: float = 0.2
    valueCoefficient: float = 0.5
    entropyCoefficient: float = 0.0
    maxGradientNorm: float = 0.5
    maxBehaviorKl: float = 0.01
    seed: int = 17
    threads: int = 1
    maxSeconds: float = 900.0
    cardReferenceKlCoefficient: float = 0.0
    cardReferenceVersion: str = CARD_REFERENCE_VERSION

    def validate_reference(self, contract=None):
        number(self.cardReferenceKlCoefficient, "cardReferenceKlCoefficient", 0, 1)
        require(self.cardReferenceVersion == CARD_REFERENCE_VERSION,
                "Unsupported card exploration reference version.")
        require(self.cardReferenceKlCoefficient == 0 or contract is None
                or contract.label == "card",
                "A positive card reference KL coefficient is supported only for card updates.")
        return self

    def validate(self, contract=None):
        number(self.learningRate, "learningRate", 1e-8, 0.1)
        integer(self.epochs, "epochs", 1, 32)
        integer(self.batchSize, "batchSize", 1, 256)
        number(self.clip, "clip", 0.001, 0.5)
        number(self.valueCoefficient, "valueCoefficient", 0, 10)
        number(self.entropyCoefficient, "entropyCoefficient", 0, 1)
        number(self.maxGradientNorm, "maxGradientNorm", 0.001, 100)
        number(self.maxBehaviorKl, "maxBehaviorKl", 0.000001, 2)
        integer(self.seed, "seed", 0, 2**31 - 1)
        integer(self.threads, "threads", 1, 8)
        number(self.maxSeconds, "maxSeconds", 1, 86400)
        return self.validate_reference(contract)



@dataclass(frozen=True)
class PurchaseReferenceObjective:
    """Training-only KL(uniform offered commands || pi), with no action grouping or prior change.

    Every authoritative offered purchase action, including Pass when offered, gets mass 1/N.
    The positive coefficient multiplies a mean over purchase decisions. None disables this
    objective and preserves the original configuration/identity. It is supported only by the
    explicitly selected checked-prefix update, independently of card physical-group reference.
    """
    version: str = PURCHASE_REFERENCE_VERSION
    coefficient: float = 0.10

    def validate(self, contract):
        require(self.version == PURCHASE_REFERENCE_VERSION,
                "Unsupported purchase offered-action reference version.")
        require(contract == PURCHASE_CONTRACT,
                "The purchase offered-action reference requires the typed purchase contract.")
        number(self.coefficient, "Purchase reference coefficient", 0, 1)
        require(self.coefficient > 0, "Use no purchase reference objective to disable it.")
        return self

    @classmethod
    def from_json(cls, value):
        require(isinstance(value, dict) and set(value) == {"version", "coefficient"},
                "Purchase reference objective requires exactly version and coefficient.")
        return cls(**value).validate(PURCHASE_CONTRACT)


def validate_purchase_reference(objective, contract, update_mode):
    if objective is None:
        return
    require(isinstance(objective, PurchaseReferenceObjective),
            "Purchase reference must be an explicit typed objective.")
    objective.validate(contract)
    require(update_mode == CHECKED_PREFIX_MODE,
            "The purchase offered-action reference requires checked-prefix updates.")


@dataclass(frozen=True)
class OfferedReferenceObjective:
    """Explicit territory-only mean KL(uniform authoritative offered commands || policy).

    Token and expansion objectives have separate versions. No action is invented, grouped or
    removed; N offered commands each receive reference mass1/N. This changes only training,
    never inference, the .97 prior, behavior probabilities or terminal-return advantages.
    None disables the objective and adds no fields to legacy fit identities.
    """
    version: str
    coefficient: float = 0.10

    def validate(self, contract):
        require(self.version in OFFERED_REFERENCE_CONTRACTS
                and OFFERED_REFERENCE_CONTRACTS[self.version] == contract,
                "Offered reference version must match its exact token or expansion contract.")
        number(self.coefficient, "Offered reference coefficient", 0, 1)
        require(self.coefficient > 0, "Use no offered reference objective to disable it.")
        return self

    @classmethod
    def from_json(cls, value):
        require(isinstance(value, dict) and set(value) == {"version", "coefficient"},
                "Offered reference requires exactly version and coefficient.")
        result = cls(**value)
        require(result.version in OFFERED_REFERENCE_CONTRACTS, "Unsupported offered reference version.")
        return result.validate(OFFERED_REFERENCE_CONTRACTS[result.version])


def validate_offered_reference(objective, contract, update_mode):
    if objective is None:
        return
    require(isinstance(objective, OfferedReferenceObjective), "Offered reference must be explicitly typed.")
    objective.validate(contract)
    require(update_mode == CHECKED_PREFIX_MODE, "Territory references require checked-prefix updates.")

def clipped_policy_loss(log_probability, old_log_probability, advantage, clip):
    ratio = torch.exp(log_probability - old_log_probability)
    return -torch.minimum(ratio * advantage, torch.clamp(ratio, 1 - clip, 1 + clip) * advantage)


@dataclass(frozen=True)
class ActorOnlyObjective:
    """Purchase-only baseline comparison, with explicit optimizer lineage and frozen value head.

    External predictions are made before either update, keyed by immutable game bytes and
    decision number. They never replace the recorded behavior value or likelihood. Shared
    state parameters still receive actor gradients; freezing the head does not freeze its output.
    """
    baseline: str
    externalFile: str | None = None

    def bind(self, rows, provenance, model_sha):
        require(self.baseline in ("recorded", "external"), "Unsupported actor-only baseline.")
        require((self.externalFile is not None) == (self.baseline == "external"),
                "Only the external objective requires a frozen prediction file.")
        identity = {"version": "purchase-actor-only-baseline-1", "baseline": self.baseline,
                    "frozenParameters": ["valueWeights", "valueBias"],
                    "optimizerInitialization": "fresh-adam"}
        if self.baseline == "recorded":
            return [{**row, "_actorOnlyBaseline": row["behaviorValue"]} for row in rows], identity
        path = Path(self.externalFile).resolve()
        external = read_json(path)
        require(external.get("kind") == "purchase-frozen-state-baselines-1"
                and external.get("collectionSha256") == provenance["collectionSha256"]
                and external.get("gameHashes") == provenance["gameHashes"]
                and external.get("behaviorModelSha256") == model_sha,
                "External baselines must bind this complete collection and behavior actor.")
        require(isinstance(external.get("predictor"), dict) and external["predictor"]
                and external.get("newFits") == 0,
                "External baselines require their frozen predictor provenance and no fitting.")
        expected = [(provenance["gameHashes"][row["originIndex"]], row["number"]) for row in rows]
        predictions = external.get("predictions")
        require(isinstance(predictions, list) and len(predictions) == len(expected)
                and [(row.get("gameSha256"), row.get("decision")) for row in predictions] == expected
                and len(set(expected)) == len(expected),
                "External predictions must cover every decision exactly once in original order.")
        values = [number(row.get("value"), "Frozen state baseline", 0, 1) for row in predictions]
        identity.update(externalFile=str(path), externalSha256=file_digest(path),
                        predictor=external["predictor"])
        return [{**row, "_actorOnlyBaseline": value} for row, value in zip(rows, values)], identity


PURCHASE_GAE_ESTIMATOR = {
    "version": "purchase-gae-1", "gamma": 1., "lambda": .95,
    "clock": "recorded-controlled-purchase-decision-1", "forcedRowsIncluded": True,
    "intermediateReward": 0., "terminalValue": 0.,
}


def purchase_gae(rows, values, lambda_value=.95):
    """Gamma1 GAE, with one tick per recorded purchase (including forced decisions).

    Intermediate reward is zero. The last recorded purchase receives the actual terminal
    composite reward; its next value is zero even if play continues after that purchase.
    Empty games create no rows. Values and targets are detached Python scalars.
    """
    number(lambda_value, "Purchase GAE lambda", 0, 1)
    require(len(rows) == len(values), "Every purchase row requires its frozen state value.")
    groups = {}
    for index, (row, value) in enumerate(zip(rows, values)):
        number(value, "Frozen purchase state value", 0, 1)
        groups.setdefault(row["originIndex"], []).append(index)
    advantages = [0.] * len(rows)
    for indices in groups.values():
        require(all(rows[a]["number"] < rows[b]["number"] for a, b in zip(indices, indices[1:])),
                "Derive GAE from ordered purchases before minibatch shuffling.")
        reward = number(rows[indices[-1]]["return"], "Terminal composite reward", 0, 1)
        require(all(row["return"] == reward for row in (rows[index] for index in indices)),
                "All purchases from one game must retain the same actual terminal reward.")
        next_value = next_advantage = 0.
        for index in reversed(indices):
            immediate = reward if index == indices[-1] else 0.
            delta = immediate + next_value - values[index]
            advantages[index] = delta + lambda_value * next_advantage
            next_value, next_advantage = values[index], advantages[index]
    return advantages


@dataclass(frozen=True)
class PurchaseAdvantageTargets:
    """Explicit sampled-continuation targets, never represented as state-only baselines."""
    targetsFile: str

    def bind(self, rows, provenance, model_sha):
        path = Path(self.targetsFile).resolve()
        targets = read_json(path)
        estimator = PURCHASE_GAE_ESTIMATOR
        require(targets.get("kind") == "purchase-precomputed-advantages-1"
                and targets.get("estimator") == estimator
                and targets.get("collectionSha256") == provenance["collectionSha256"]
                and targets.get("gameHashes") == provenance["gameHashes"]
                and targets.get("behaviorModelSha256") == model_sha,
                "Precomputed advantages require the declared estimator and exact completed collection.")
        baseline = targets["frozenStatePredictions"]
        require(file_digest(baseline["path"]) == baseline["sha256"],
                "GAE must preserve its separately frozen state predictions.")
        bound, state_identity = ActorOnlyObjective("external", baseline["path"]).bind(
            rows, provenance, model_sha)
        expected = purchase_gae(rows, [row["_actorOnlyBaseline"] for row in bound])
        actual = targets.get("advantages")
        require(isinstance(actual, list) and len(actual) == len(rows),
                "GAE must cover each recorded purchase exactly once.")
        for row, value, target in zip(rows, expected, actual):
            require(target.get("gameSha256") == provenance["gameHashes"][row["originIndex"]]
                    and type(target.get("decision")) is int and target["decision"] == row["number"]
                    and abs(number(target.get("advantage"), "Precomputed actor advantage") - value) <= 1e-12,
                    "A precomputed advantage differs from its original ordered purchase continuation.")
        identity = {"version": "purchase-precomputed-advantage-1", "estimator": estimator,
                    "targetsFile": str(path), "targetsSha256": file_digest(path),
                    "frozenStatePredictions": {key: state_identity[key] for key in
                                               ("externalFile", "externalSha256", "predictor")},
                    "frozenParameters": ["valueWeights", "valueBias"],
                    "optimizerInitialization": "fresh-adam"}
        return [{**row, "_actorOnlyAdvantage": value} for row, value in zip(rows, expected)], identity


def actor_adam_checkpoint(checkpoint_path, model):
    """Intrinsic completed-checkpoint binding, shared by separately typed continuation owners."""
    path = Path(checkpoint_path)
    previous, identity = read_checkpoint(path), read_json(path.parent / "identity.json")
    finished = read_json(path.parent / "finished.json")
    require(previous["identitySha256"] == finished["identitySha256"] == digest(identity)
            and previous["model"] == model and finished["outputModelSha256"] == file_digest(path.parent / "policy.json")
            and read_json(path.parent / "policy.json") == model,
            "Carried actor-only Adam requires its exact completed preceding actor and identity.")
    return previous, identity


def actor_adam_slots(previous):
    """Value-head parameters and their momentum never belong to an actor-only checkpoint."""
    optimizer = previous.get("optimizer")
    require(isinstance(optimizer, dict) and len(optimizer["param_groups"]) == 1
            and len(optimizer["param_groups"][0]["params"]) == len(PARAMETERS) - 2
            and len(optimizer["state"]) <= len(PARAMETERS) - 2,
            "Carried actor-only Adam must exclude value-head parameters and momentum.")


def validated_actor_adam_steps(previous, model, config, contract, inherited_steps):
    """Validate intrinsic five-slot Adam payload; typed callers own lineage and recipe identity."""
    actor_adam_slots(previous)
    optimizer = previous['optimizer']
    parameters = list(range(len(PARAMETERS)-2))
    require(optimizer['param_groups'][0]['params'] == parameters
        and all(type(index) is int for index in optimizer['param_groups'][0]['params'])
        and set(optimizer['state']) <= {str(index) for index in parameters},
        'Actor-only Adam slots must identify exactly the five actor parameters in their original order.')
    actor = ResidualPolicy(model, contract)
    fresh = torch.optim.Adam([getattr(actor,name) for name in PARAMETERS[:-2]],
                                   lr=config.learningRate, foreach=False)
    require(optimizer['param_groups'] == encode_optimizer(fresh)['param_groups'],
        'Actor-only Adam must retain the exact original learning rate and intrinsic optimizer recipe.')
    complete = {str(index) for index in parameters}
    require(set(optimizer['state']) == complete or
        (not optimizer['state'] and previous['epoch'] == 0 and inherited_steps == 0),
        'Actor-only Adam must retain all five moments after any accepted step.')
    steps = []
    for index, name in enumerate(PARAMETERS[:-2]):
        state = optimizer['state'].get(str(index))
        if state is None:
            continue
        require(set(state) == {'step','exp_avg','exp_avg_sq'},
            'Actor-only Adam must retain only its step and two original moments.')
        for key in ('exp_avg','exp_avg_sq'):
            item = state[key]
            require(set(item) == {'tensor','dtype'} and item['dtype'] == str(DTYPE),
                'Actor-only moments must retain their original Float64 representation.')
            value = torch.tensor(item['tensor'], dtype=DTYPE)
            require(value.shape == getattr(actor,name).shape
                and bool(torch.isfinite(value).all()),
                'Actor-only moments must match the corresponding finite parameter shape.')
            if key == 'exp_avg_sq':
                require(bool((value >= 0).all()),
                    'Actor-only Adam squared moments must remain nonnegative.')
        step = state['step']
        require(set(step) == {'tensor','dtype'} and step['dtype'] == 'torch.float32',
            'Actor-only Adam steps must retain the original Float32 scalar counter.')
        step_count = number(step['tensor'],'Actor-only Adam step',0)
        require(step_count == int(step_count) and step_count > 0,
            'Populated actor-only Adam slots require positive whole step counters.')
        steps.append(int(step_count))
    require(not steps or len(set(steps)) == 1 and steps[0] >= inherited_steps,
        'Every actor-only Adam slot must retain the same cumulative step count.')
    return steps[0] if steps else 0


@dataclass(frozen=True)
class ActorAdamContinuation:
    """Carry only a completed matching actor-only optimizer within the same estimator arm."""
    version: str = "purchase-actor-adam-continuation-1"

    def validate(self, checkpoint_path, objective, model, config):
        require(self.version == "purchase-actor-adam-continuation-1",
                "Unsupported actor-only optimizer continuation.")
        previous, identity = actor_adam_checkpoint(checkpoint_path, model)
        old = identity.get("actorOnlyObjective", {})
        fields = ("version", "baseline", "estimator", "frozenParameters")
        require(all(old.get(name) == objective.get(name) for name in fields)
                and {k:v for k,v in identity["config"].items() if k != "seed"}
                == {k:v for k,v in asdict(config).items() if k != "seed"},
                "Actor-only Adam cannot cross estimator arms or change the PPO recipe.")
        def predictor(value):
            return value.get("predictor", value.get("frozenStatePredictions", {}).get("predictor"))
        require(predictor(old) == predictor(objective),
                "Actor-only continuation must retain the same frozen state predictor and numerical contract.")
        actor_adam_slots(previous)
        return {"version": self.version, "checkpointSha256": file_digest(checkpoint_path),
                "previousIdentitySha256": digest(identity)}


def ordinary_loss_for_rows(policy, rows, config, *, actor_only=False,
                           precomputed_advantages=False, joint_actor_only=None, expansion_mc=None,
                           expansion_baseline=None):
    config.validate_reference(policy.contract)
    probabilities, values = policy([row.get("_prepared", row["observation"]) for row in rows])
    chosen = torch.stack([logp[row["selectedIndex"]]
                          for logp, row in zip(probabilities, rows)])
    old = torch.tensor([row["behaviorLogProbability"] for row in rows], dtype=DTYPE)
    returns = torch.tensor([row["return"] for row in rows], dtype=DTYPE)
    if actor_only:
        require(policy.contract == PURCHASE_CONTRACT and config.valueCoefficient == 0,
                "Actor-only objectives require purchase rows and zero critic coefficient.")
    if joint_actor_only is not None:
        from joint_phase_objective import JointPhaseActorOnlyObjective
        require(isinstance(joint_actor_only, JointPhaseActorOnlyObjective) and not actor_only
                and precomputed_advantages, "Joint credit requires its explicit typed precomputed objective.")
        joint_actor_only.validate(policy.contract, config)
    if expansion_mc is not None:
        from expansion_mc import ExpansionMcObjective
        require(isinstance(expansion_mc, ExpansionMcObjective) and not actor_only
                and joint_actor_only is None and precomputed_advantages,
                "Expansion MC requires its separate typed detached objective.")
        expansion_mc.validate(policy.contract, config)
    if expansion_baseline is not None:
        from expansion_previous_baseline import ExpansionBaselineObjective
        require(type(expansion_baseline) is ExpansionBaselineObjective and not actor_only
                and joint_actor_only is None and expansion_mc is None and precomputed_advantages,
                "Previous-batch expansion baseline requires its separate typed detached objective.")
        expansion_baseline.validate(policy.contract, config)
    if precomputed_advantages:
        require(actor_only or joint_actor_only is not None or expansion_mc is not None or expansion_baseline is not None,
                "Explicit precomputed advantages require the actor-only objective.")
        advantages = torch.tensor([row["_actorOnlyAdvantage"] for row in rows], dtype=DTYPE)
    else:
        baseline_key = "_actorOnlyBaseline" if actor_only else "behaviorValue"
        advantages = returns - torch.tensor([row[baseline_key] for row in rows], dtype=DTYPE)
    actor = clipped_policy_loss(chosen, old, advantages, config.clip).mean()
    critic = 0.5 * (values - returns).square().mean()
    entropy = torch.stack([-(value.exp() * value).sum() for value in probabilities]).mean()
    loss = actor + config.valueCoefficient * critic - config.entropyCoefficient * entropy
    if config.cardReferenceKlCoefficient == 0:
        return loss
    divergences = []
    for logp, row in zip(probabilities, rows):
        reference = row.get("_cardReference")
        if reference is None:
            reference = torch.tensor(card_reference_probabilities(
                [action["command"] for action in row["observation"]["actions"]]), dtype=DTYPE)
        divergences.append(reference_kl(logp, reference))
    return loss + config.cardReferenceKlCoefficient * torch.stack(divergences).mean()



def loss_for_rows(policy, rows, config, *, purchase_reference=None, offered_reference=None,
                  actor_only=False, precomputed_advantages=False, joint_actor_only=None, expansion_mc=None,
                  expansion_baseline=None):
    require(expansion_baseline is None or (not actor_only and joint_actor_only is None and expansion_mc is None
            and purchase_reference is None and offered_reference is None),
            "Previous-batch expansion baseline cannot combine another objective.")
    require(expansion_mc is None or (not actor_only and joint_actor_only is None
            and purchase_reference is None and offered_reference is None),
            "Expansion MC cannot combine another objective.")
    require(joint_actor_only is None or (not actor_only and purchase_reference is None and offered_reference is None),
            "Joint actor credit cannot combine another actor-only or reference objective.")
    require(purchase_reference is None or offered_reference is None,
            "A typed actor cannot use two offered reference objectives.")
    if offered_reference is not None:
        require(isinstance(offered_reference, OfferedReferenceObjective), "Offered reference must be explicitly typed.")
        offered_reference.validate(policy.contract)
    if purchase_reference is not None:
        require(isinstance(purchase_reference, PurchaseReferenceObjective),
                "Purchase reference must be an explicit typed objective.")
        purchase_reference.validate(policy.contract)
    ordinary = ordinary_loss_for_rows(policy, rows, config, actor_only=actor_only,
                                     precomputed_advantages=precomputed_advantages,
                                     joint_actor_only=joint_actor_only, expansion_mc=expansion_mc,
                                     expansion_baseline=expansion_baseline)
    if purchase_reference is None and offered_reference is None:
        return ordinary
    # Match the reviewed LAB objective's separate forward and operation order exactly.
    # Raw terminal return minus recorded value advantages remain in the ordinary PPO loss.
    logps, _ = policy([row.get("_prepared", row["observation"]) for row in rows])
    penalty = torch.stack([-logp.mean() - math.log(logp.numel()) for logp in logps]).mean()
    objective = purchase_reference if purchase_reference is not None else offered_reference
    return ordinary + objective.coefficient * penalty

def row_batches(rows, size):
    """Preserve row order, bounding both decisions and total offered actions per allocation.

    Purchase batches within the existing numerical limit remain exactly unchanged. Card states
    can offer 1024 actions, so a configured decision batch may need several smaller updates.
    """
    start, actions = 0, 0
    for index, row in enumerate(rows):
        count = (row["_prepared"].action_count if "_prepared" in row
                 else len(row["observation"]["actions"]))
        if index > start and (index - start == size or actions + count > 16384):
            yield rows[start:index]
            start, actions = index, 0
        actions += count
    if start < len(rows):
        yield rows[start:]


LEGACY_UPDATE_MODE = "aor-ppo-epoch-1"
CHECKED_PREFIX_MODE = "aor-ppo-checked-prefix-1"
CHECKED_PREFIX_MINIBATCHES = 8


def epoch_batches(rows, config, epoch):
    """The original deterministic shuffle and action-bounded minibatch partition."""
    order = list(range(len(rows)))
    random.Random(config.seed + epoch).shuffle(order)
    return row_batches([rows[index] for index in order], config.batchSize)


def fit_minibatch(policy, optimizer, batch, config, deadline, *, purchase_reference=None,
                  offered_reference=None, actor_only=False, precomputed_advantages=False,
                  joint_actor_only=None, expansion_mc=None, expansion_baseline=None):
    require(time.monotonic() < deadline, "Purchase update exceeded its elapsed-time budget.")
    optimizer.zero_grad(set_to_none=True)
    if expansion_baseline is not None:
        require(not actor_only and joint_actor_only is None and expansion_mc is None
                and purchase_reference is None and offered_reference is None,
                "Previous-batch baseline cannot add another objective.")
        loss = loss_for_rows(policy, batch, config, expansion_baseline=expansion_baseline, precomputed_advantages=True)
    elif expansion_mc is not None:
        require(not actor_only and joint_actor_only is None and purchase_reference is None
                and offered_reference is None, "Expansion MC cannot add another objective.")
        loss = loss_for_rows(policy, batch, config, expansion_mc=expansion_mc, precomputed_advantages=True)
    elif joint_actor_only is not None:
        require(not actor_only and purchase_reference is None and offered_reference is None,
                "Joint actor credit cannot add a legacy or reference objective.")
        loss = loss_for_rows(policy, batch, config, joint_actor_only=joint_actor_only,
                             precomputed_advantages=True)
    elif actor_only:
        require(purchase_reference is None and offered_reference is None,
                "Actor-only baseline comparisons cannot add another objective.")
        loss = loss_for_rows(policy, batch, config, actor_only=True,
                             precomputed_advantages=precomputed_advantages)
    elif offered_reference is not None:
        loss = loss_for_rows(policy, batch, config, purchase_reference=purchase_reference,
                             offered_reference=offered_reference)
    else:
        loss = (loss_for_rows(policy, batch, config) if purchase_reference is None else
                loss_for_rows(policy, batch, config, purchase_reference=purchase_reference))
    require(bool(torch.isfinite(loss)), "Purchase loss must remain finite.")
    loss.backward()
    nn.utils.clip_grad_norm_(policy.parameters(), config.maxGradientNorm,
                             error_if_nonfinite=True)
    optimizer.step()


def fit_epoch(policy, optimizer, rows, config, epoch, deadline, *, actor_only=False,
              precomputed_advantages=False, joint_actor_only=None, expansion_mc=None, expansion_baseline=None):
    require(expansion_baseline is None or (not actor_only and joint_actor_only is None and expansion_mc is None),
            "A previous-batch baseline epoch cannot also select another objective.")
    require(joint_actor_only is None or not actor_only,
            "A joint epoch cannot also select the legacy purchase-only objective.")
    require(expansion_mc is None or (not actor_only and joint_actor_only is None),
            "An expansion MC epoch cannot also select another objective.")
    for batch in epoch_batches(rows, config, epoch):
        if expansion_baseline is not None:
            fit_minibatch(policy, optimizer, batch, config, deadline, expansion_baseline=expansion_baseline)
        elif expansion_mc is not None:
            fit_minibatch(policy, optimizer, batch, config, deadline, expansion_mc=expansion_mc)
        elif joint_actor_only is not None:
            fit_minibatch(policy, optimizer, batch, config, deadline, joint_actor_only=joint_actor_only)
        elif actor_only:
            fit_minibatch(policy, optimizer, batch, config, deadline, actor_only=True,
                          precomputed_advantages=precomputed_advantages)
        else:
            fit_minibatch(policy, optimizer, batch, config, deadline)


def checked_block_bounds(rows, config, cursor):
    require(isinstance(cursor, dict) and set(cursor) == {"epoch", "minibatchOffset"},
            "Checked-prefix cursor requires epoch and minibatchOffset.")
    epoch = integer(cursor["epoch"], "Cursor epoch", 0, config.epochs - 1)
    offset = integer(cursor["minibatchOffset"], "Cursor minibatch offset", 0, len(rows))
    batches = list(epoch_batches(rows, config, epoch))
    require(offset < len(batches) and offset % CHECKED_PREFIX_MINIBATCHES == 0,
            "Checked-prefix cursor must identify an uncommitted block boundary.")
    end = min(offset + CHECKED_PREFIX_MINIBATCHES, len(batches))
    next_cursor = ({"epoch": epoch + 1, "minibatchOffset": 0} if end == len(batches)
                   else {"epoch": epoch, "minibatchOffset": end})
    return batches[offset:end], next_cursor


def fit_checked_block(policy, optimizer, behavior, rows, config, cursor, deadline,
                      *, purchase_reference=None, offered_reference=None):
    """Attempt at most eight minibatches, retaining both parameters and Adam only on acceptance.

    Cursor epochs are zero-based; offset counts actual action-bounded minibatches in that epoch's
    original deterministic shuffle. An epoch remainder is a shorter block. The behavior actor must
    remain the ORIGINAL collection actor throughout the update. Every check uses ALL collection
    rows, never the block alone or a rolling reference. This function performs no checkpoint I/O.
    Exceptions restore the block before propagating; timeout and KL rejection return an unchanged
    cursor. Callers must atomically persist accepted model, Adam, cursor and counters together.
    """
    validate_purchase_reference(purchase_reference, policy.contract, CHECKED_PREFIX_MODE)
    validate_offered_reference(offered_reference, policy.contract, CHECKED_PREFIX_MODE)
    require(purchase_reference is None or offered_reference is None,
            "A typed actor cannot use two offered reference objectives.")
    batches, next_cursor = checked_block_bounds(rows, config, cursor)
    before, old_optimizer = copy.deepcopy(policy.state_dict()), copy.deepcopy(optimizer.state_dict())
    attempted, metrics, reason = 0, None, None
    try:
        for batch in batches:
            if offered_reference is not None:
                fit_minibatch(policy, optimizer, batch, config, deadline,
                              offered_reference=offered_reference)
            elif purchase_reference is None:
                fit_minibatch(policy, optimizer, batch, config, deadline)
            else:
                fit_minibatch(policy, optimizer, batch, config, deadline,
                              purchase_reference=purchase_reference)
            attempted += 1
        metrics = behavior_metrics(policy, behavior, rows, config.batchSize)
        require(all(math.isfinite(value) for value in metrics.values()),
                "Checked-prefix whole-collection metrics must remain finite.")
        require(time.monotonic() < deadline, "Purchase update exceeded its elapsed-time budget.")
        if metrics["behaviorKl"] > config.maxBehaviorKl:
            reason = "behavior-kl-limit"
    except BaseException as failure:
        policy.load_state_dict(before)
        optimizer.load_state_dict(old_optimizer)
        if not isinstance(failure, ValueError) or "elapsed-time budget" not in str(failure):
            raise
        reason = "time-budget"
    if reason is not None:
        policy.load_state_dict(before)
        optimizer.load_state_dict(old_optimizer)
    accepted = reason is None
    return {"accepted": accepted, "cursor": dict(cursor),
            "nextCursor": next_cursor if accepted else dict(cursor),
            "attemptedSteps": attempted, "acceptedSteps": attempted if accepted else 0,
            "metrics": metrics, "stopReason": reason}


def behavior_metrics(policy, behavior, rows, batch_size=64):
    totals = {"behaviorKl": 0.0, "priorKl": 0.0, "valueMse": 0.0}
    with torch.no_grad():
        for batch in row_batches(rows, batch_size):
            observations = [row.get("_prepared", row["observation"]) for row in batch]
            current, values = policy(observations)
            old, _ = behavior(observations)
            for logp, old_logp, value, row in zip(current, old, values, batch):
                observation = row["observation"]
                prior = torch.tensor(prior_probabilities(len(logp), observation["baselineIndex"]),
                                     dtype=DTYPE)
                totals["behaviorKl"] += float((old_logp.exp() * (old_logp - logp)).sum())
                totals["priorKl"] += float((prior * (prior.log() - logp)).sum())
                totals["valueMse"] += (float(value) - row["return"]) ** 2
    return {name: value / len(rows) for name, value in totals.items()}


def read_collection(directory, model_path, model, *, contract=PURCHASE_CONTRACT):
    """Completion guards run before opening any game outcomes; all behavior is recomputed."""
    directory = Path(directory)
    require((directory / "state.json").exists() and (directory / "finished.json").exists(),
            "Purchase fitting requires a completed collection.")
    state = read_json(directory / "state.json")
    require(state.get("phase") == "complete", "Purchase collection is not complete.")
    collection = read_json(directory / "collection.json")
    finished = read_json(directory / "finished.json")
    signature = collection.get("signature")
    require(isinstance(signature, str) and signature
            and state.get("signature") == signature and finished.get("signature") == signature,
            "Collection completion signatures must match.")
    count = integer(collection.get("games"), "Collected games", 1, 4096)
    require(finished.get("games") == count, "Collection completion count must match.")
    seeds = collection.get("seeds")
    require(isinstance(seeds, list) and len(seeds) == count,
            "A collection requires its complete ordered game seed list.")
    for seed in seeds:
        integer(seed, "Collected game seed", -(2**31), 2**31 - 1)
    require(len(set(seeds)) == count, "On-policy batches require distinct whole-game origins.")
    require(collection.get("mode") == "on-policy", "PPO requires on-policy trajectories.")
    model_sha = file_digest(model_path)
    require(collection.get("modelSha256") == model_sha
            and file_digest(directory / "policy.json") == model_sha,
            "Trajectories must use the exact supplied policy bytes.")
    for key in ("encoderVersion", "stateFeatureNames", "actionFeatureNames"):
        require(collection.get(key) == model[key], f"Collection {key} differs from policy.")
    require(collection.get("actorVersion") == contract.actor, "Actor version is unsupported.")
    if "sharedPurchaseBaseline" in collection:
        shared = collection["sharedPurchaseBaseline"]
        require(contract == PURCHASE_CONTRACT and isinstance(shared, dict)
                and set(shared) == {"path", "sha256"}
                and isinstance(shared["path"], str) and Path(shared["path"]).is_absolute()
                and isinstance(shared["sha256"], str) and len(shared["sha256"]) == 64
                and all(character in "0123456789abcdef" for character in shared["sha256"]),
                "Shared purchase baseline requires its exact path and SHA-256 provenance.")
        shared_path = directory / "shared-purchase-baseline.json"
        require(shared_path.is_file() and file_digest(shared_path) == shared["sha256"],
                "Shared purchase baseline snapshot differs from the collection manifest.")
    frozen_purchase = None
    if contract.frozenPurchase:
        require(collection.get("trajectoryEncoding") == contract.trajectoryEncoding,
                "Card collection requires the explicit gzip-json-v1 trajectory encoding.")
        frozen_purchase = collection.get("frozenPurchasePolicy")
        require(isinstance(frozen_purchase, dict)
                and isinstance(frozen_purchase.get("path"), str)
                and Path(frozen_purchase["path"]).is_absolute(),
                "Card collection requires its frozen purchase policy provenance.")
        frozen_path = directory / "frozen-purchase-policy.json"
        require(frozen_path.is_file()
                and file_digest(frozen_path) == frozen_purchase.get("sha256"),
                "Frozen purchase policy snapshot differs from the collection manifest.")
        validate_model(read_json(frozen_path))
    suffix = ".json.gz" if contract.trajectoryEncoding == "gzip-json-v1" else ".json"
    paths = [directory / f"game-{index}{suffix}" for index in range(count)]
    require(all(path.exists() for path in paths), "A completed collection is missing a game.")
    game_hashes = [file_digest(path) for path in paths]
    require(finished.get("gameSha256") == game_hashes,
            "Completed trajectory bytes differ from the collector's frozen game hashes.")
    rows = []
    rewards = []
    origins = set()
    behavior = ResidualPolicy(model, contract)
    for index, path in enumerate(paths):
        game = read_game(path, contract)
        require(game.get("index") == index and type(game["index"]) is int,
                "Game index must match its checkpoint filename.")
        for key, expected in (("signature", signature), ("modelSha256", model_sha),
                              ("encoderVersion", contract.encoder),
                              ("actorVersion", contract.actor)):
            require(game.get(key) == expected, f"Game {key} must match frozen collection.")
        if frozen_purchase is not None:
            require(game.get("frozenPurchasePolicySha256") == frozen_purchase["sha256"],
                    "Game frozen purchase policy differs from the collection manifest.")
            validate_purchase_audit(game)
        origin = (game.get("configuration"), game.get("gameSeed"))
        require(isinstance(origin[0], str) and origin[0], "A game requires its configuration ID.")
        integer(origin[1], "Game seed", -(2**31), 2**31 - 1)
        require(origin == (collection.get("configuration"), seeds[index]),
                "Game configuration and seed must match the frozen collection.")
        require(origin not in origins, "On-policy batches require distinct whole-game origins.")
        origins.add(origin)
        terminal = game["terminal"]
        reward = number(terminal.get("reward"), "Terminal reward", 0, 1)
        win = number(terminal.get("winShare"), "Win share", 0, 1)
        rank = number(terminal.get("rankReward"), "Rank reward", 0, 1)
        require(abs(reward - (0.8 * win + 0.2 * rank)) <= 1e-12,
                "Terminal reward must equal 0.8 win-share + 0.2 rank-reward.")
        rewards.append(reward)
        decisions = game.get("decisions")
        require(isinstance(decisions, list) and len(decisions) <= 12000,
                "A game requires a bounded list of controlled decisions.")
        previous = -1
        for decision in decisions:
            sequence = integer(decision.get("number"), "Decision number", 0, 11999)
            integer(decision.get("actorSeed"), "Actor seed", -(2**31), 2**31 - 1)
            require(sequence > previous,
                    "Controlled decisions must be in strictly increasing order.")
            previous = sequence
            observation = decision["observation"]
            prepared = behavior.prepare(observation)
            integer(decision.get("selectedIndex"), "Selected action", 0,
                    len(observation["actions"]) - 1)
            number(decision.get("behaviorLogProbability"), "Behavior log probability")
            number(decision.get("behaviorValue"), "Behavior value", 0, 1)
            rows.append({**decision, "return": reward, "originIndex": index,
                         "_prepared": prepared})
    require(1 <= len(rows) <= 200000, "A purchase update requires 1–200000 controlled decisions.")
    with torch.no_grad():
        for start in range(0, len(rows), 16):
            batch = rows[start:start + 16]
            probabilities, values = behavior([row["_prepared"] for row in batch])
            for logp, value, row in zip(probabilities, values, batch):
                require(abs(float(logp[row["selectedIndex"]])
                            - row["behaviorLogProbability"]) <= 1e-9
                        and abs(float(value) - row["behaviorValue"]) <= 1e-9,
                        "Recorded behavior probabilities/value differ from the frozen actor.")
                chosen = select(logp.exp().tolist(), selection_uniform(row["actorSeed"]))
                if chosen != row["selectedIndex"]:
                    # A scalar fallback resolves vanishingly rare floating-point boundary cases.
                    exact = reference_forward(model, row["observation"],
                                              contract=contract)["probabilities"]
                    require(select(exact, selection_uniform(row["actorSeed"]))
                            == row["selectedIndex"],
                            "Recorded action differs from the frozen actor's seeded selector.")
    provenance = {"collectionSha256": file_digest(directory / "collection.json"),
                  "collectionSignature": signature,
                  "gameHashes": game_hashes,
                  "games": count, "gameMeanReward": sum(rewards) / len(rewards)}
    if frozen_purchase is not None:
        provenance["frozenPurchasePolicySha256"] = frozen_purchase["sha256"]
    return rows, provenance


def encode_optimizer(optimizer):
    def encode(value):
        if isinstance(value, torch.Tensor):
            return {"tensor": value.tolist(), "dtype": str(value.dtype)}
        if isinstance(value, dict):
            return {str(key): encode(item) for key, item in value.items()}
        if isinstance(value, (list, tuple)):
            return [encode(item) for item in value]
        return value
    return encode(optimizer.state_dict())


def restore_optimizer(optimizer, encoded):
    def decode(value):
        if isinstance(value, dict) and set(value) == {"tensor", "dtype"}:
            require(value["dtype"] in ("torch.float32", "torch.float64"),
                    "Optimizer tensor dtype is unsupported.")
            dtype = DTYPE if value["dtype"] == "torch.float64" else torch.float32
            return torch.tensor(value["tensor"], dtype=dtype)
        if isinstance(value, dict):
            return {key: decode(item) for key, item in value.items()}
        if isinstance(value, list):
            return [decode(item) for item in value]
        return value
    state = decode(encoded)
    state["state"] = {int(key): value for key, value in state["state"].items()}
    optimizer.load_state_dict(state)


def save_checkpoint(path, payload):
    # Prefix updates publish after every accepted block. Compact JSON uses the accelerated
    # encoder while retaining the same payload/checksum, atomic write and old-reader support.
    # Legacy checkpoints keep their exact existing bytes; either formatting remains readable.
    atomic_json(path, {"sha256": digest(payload), "payload": payload},
                compact=payload.get("schemaVersion") == 2)


def read_checkpoint(path):
    record = read_json(path)
    require(record.get("sha256") == digest(record.get("payload")),
            "Purchase checkpoint checksum differs from its payload.")
    return record["payload"]


def initial_checkpoint(identity, model):
    checkpoint = {"schemaVersion": 1, "identitySha256": digest(identity), "epoch": 0,
                  "model": model, "optimizer": None, "history": [], "elapsedSeconds": 0.0,
                  "deadlineEpochSeconds": identity["deadlineEpochSeconds"], "stopReason": None}
    if identity.get("updateMode") == CHECKED_PREFIX_MODE:
        checkpoint.update(schemaVersion=2, updateMode=CHECKED_PREFIX_MODE,
                          cursor={"epoch": 0, "minibatchOffset": 0},
                          acceptedSteps=0, acceptedBlocks=0)
    return checkpoint



def validate_checked_prefix_checkpoint(checkpoint, identity, rows):
    """Validate schema-2 progress and return the summary required by prefix-aware orchestration.

    `rows` are the validated ORIGINAL collection, in its stored order. `epoch`/acceptedEpochs
    count only completed epochs, so zero epochs does NOT imply an unchanged actor. Accepted
    minibatch and block counts apply to this update only, excluding carried Adam steps. Legacy
    schema-1 completed-update validators must not interpret these checkpoints or reports.
    """
    require(identity.get("updateMode") == CHECKED_PREFIX_MODE
            and checkpoint.get("schemaVersion") == 2
            and checkpoint.get("updateMode") == CHECKED_PREFIX_MODE,
            "Checked-prefix validation requires the explicit mode and schema-2 checkpoint.")
    require(checkpoint.get("identitySha256") == digest(identity)
            and checkpoint.get("deadlineEpochSeconds") == identity.get("deadlineEpochSeconds"),
            "Checked-prefix checkpoint identity or original deadline changed.")
    config = UpdateConfig(**identity["config"]).validate()
    if "purchaseReferenceObjective" in identity:
        objective = PurchaseReferenceObjective.from_json(identity["purchaseReferenceObjective"])
        require(identity.get("actorVersion") == ACTOR_VERSION,
                "A purchase reference checkpoint must identify the purchase actor.")
        validate_purchase_reference(objective, PURCHASE_CONTRACT, identity["updateMode"])
        config.validate(PURCHASE_CONTRACT)
    if "offeredReferenceObjective" in identity:
        objective = OfferedReferenceObjective.from_json(identity["offeredReferenceObjective"])
        contract = OFFERED_REFERENCE_CONTRACTS[objective.version]
        require(identity.get("actorVersion") == contract.actor and "purchaseReferenceObjective" not in identity,
                "Territory reference checkpoint must identify exactly its own actor objective.")
        validate_offered_reference(objective, contract, identity["updateMode"])
        config.validate(contract)
    history = checkpoint.get("history")
    require(isinstance(history, list), "Checked-prefix history must be an ordered block list.")
    cursor, steps, blocks, last_metrics, stop = {"epoch": 0, "minibatchOffset": 0}, 0, 0, None, None
    for entry in history:
        require(stop is None and isinstance(entry, dict) and set(entry) == {
            "accepted", "cursor", "nextCursor", "attemptedSteps", "acceptedSteps", "metrics",
            "stopReason"}, "Checked-prefix history cannot continue after a rejected block.")
        require(type(entry["accepted"]) is bool and entry["cursor"] == cursor,
                "Checked-prefix block starts at an inconsistent cursor.")
        batches, next_cursor = checked_block_bounds(rows, config, cursor)
        attempted = integer(entry["attemptedSteps"], "Attempted minibatches", 0, len(batches))
        accepted_steps = integer(entry["acceptedSteps"], "Accepted minibatches", 0, len(batches))
        metrics = entry["metrics"]
        if metrics is not None:
            require(isinstance(metrics, dict)
                    and set(metrics) == {"behaviorKl", "priorKl", "valueMse"},
                    "Checked-prefix metrics require whole-collection KL and value error.")
            for name, value in metrics.items():
                number(value, name, -1e-12 if name.endswith("Kl") else 0)
        if entry["accepted"]:
            require(attempted == accepted_steps == len(batches)
                    and entry["nextCursor"] == next_cursor and entry["stopReason"] is None
                    and metrics is not None and metrics["behaviorKl"] <= config.maxBehaviorKl,
                    "Accepted block must complete its bounded steps under the original KL cap.")
            cursor, last_metrics = next_cursor, metrics
            steps += accepted_steps
            blocks += 1
        else:
            stop = entry["stopReason"]
            require(accepted_steps == 0 and entry["nextCursor"] == cursor
                    and stop in ("time-budget", "behavior-kl-limit"),
                    "Rejected block must preserve the last accepted cursor and retain no steps.")
            if stop == "behavior-kl-limit":
                require(attempted == len(batches) and metrics is not None
                        and metrics["behaviorKl"] > config.maxBehaviorKl,
                        "KL rejection requires a complete block exceeding the original cap.")
    require(checkpoint.get("cursor") == cursor and checkpoint.get("epoch") == cursor["epoch"]
            and checkpoint.get("stopReason") == stop,
            "Checked-prefix checkpoint cursor or stop reason differs from its history.")
    require(integer(checkpoint.get("acceptedSteps"), "Checkpoint accepted steps", 0, len(rows) * config.epochs) == steps
            and integer(checkpoint.get("acceptedBlocks"), "Checkpoint accepted blocks", 0, len(rows) * config.epochs) == blocks,
            "Checked-prefix accepted counters differ from committed history.")
    number(checkpoint.get("elapsedSeconds"), "Checkpoint elapsed seconds", 0)
    return {"acceptedSteps": steps, "acceptedBlocks": blocks, "completedEpochs": cursor["epoch"],
            "cursor": cursor, "partialEpochAccepted": cursor["minibatchOffset"] > 0,
            "lastAcceptedMetrics": last_metrics}

def update(model_path, collection_path, config, output, resume=False, optimizer_from=None,
           *, contract=PURCHASE_CONTRACT, entrypoint=None, update_mode=LEGACY_UPDATE_MODE,
           purchase_reference=None, collection_reader=None, offered_reference=None,
           actor_only=None, actor_optimizer_continuation=None,
           joint_actor_only=None, joint_optimizer_continuation=None,
           expansion_mc=None, expansion_optimizer_continuation=None,
           expansion_baseline=None, expansion_baseline_continuation=None):
    """Fit one typed actor; optional readers own completed-data/likelihood validation.

    A custom reader has read_collection's signature and returns rows plus provenance containing
    its source readerSha256. Both its inspectable implementation file and the explicit entrypoint
    are bound independently. Default readers/objectives add no fields to legacy identities.
    """
    require(update_mode in (LEGACY_UPDATE_MODE, CHECKED_PREFIX_MODE), "Unsupported PPO update mode.")
    config.validate(contract)
    if actor_only is not None:
        require(isinstance(actor_only, (ActorOnlyObjective, PurchaseAdvantageTargets)) and contract == PURCHASE_CONTRACT
                and update_mode == LEGACY_UPDATE_MODE and not resume
                and (optimizer_from is None or isinstance(actor_optimizer_continuation, ActorAdamContinuation))
                and config.valueCoefficient == config.entropyCoefficient == 0
                and config.cardReferenceKlCoefficient == 0
                and purchase_reference is None and offered_reference is None,
                "Actor-only comparisons require fresh purchase Adam or typed continuation, epoch rollback and no other loss.")
    require(actor_optimizer_continuation is None or
            (isinstance(actor_optimizer_continuation, ActorAdamContinuation)
             and actor_only is not None and optimizer_from is not None and not resume),
            "Explicit actor-only continuation requires its preceding optimizer and typed objective.")
    if joint_actor_only is not None:
        from joint_phase_objective import JointPhaseActorOnlyObjective, JointPhaseAdamContinuation
        require(isinstance(joint_actor_only, JointPhaseActorOnlyObjective)
                and actor_only is None and actor_optimizer_continuation is None
                and update_mode == LEGACY_UPDATE_MODE and not resume
                and purchase_reference is None and offered_reference is None
                and collection_reader == joint_actor_only.targets.phase_reader
                and (optimizer_from is None or isinstance(joint_optimizer_continuation, JointPhaseAdamContinuation)),
                "Joint credit requires its exact detached phase reader, epoch rollback and separate typed Adam.")
        joint_actor_only.validate(contract, config)
    if joint_optimizer_continuation is not None:
        from joint_phase_objective import JointPhaseAdamContinuation
        require(isinstance(joint_optimizer_continuation, JointPhaseAdamContinuation)
                and joint_actor_only is not None and optimizer_from is not None and not resume,
                "Joint continuation requires its preceding phase optimizer and explicit joint objective.")
    if expansion_mc is not None:
        from expansion_mc import ExpansionMcObjective, ExpansionMcAdamContinuation
        require(isinstance(expansion_mc, ExpansionMcObjective)
                and actor_only is None and actor_optimizer_continuation is None
                and joint_actor_only is None and joint_optimizer_continuation is None
                and update_mode == LEGACY_UPDATE_MODE and not resume
                and purchase_reference is None and offered_reference is None
                and collection_reader == expansion_mc.phase_reader
                and (optimizer_from is None or isinstance(expansion_optimizer_continuation, ExpansionMcAdamContinuation)),
                "Expansion MC requires its original typed reader, epoch rollback and separate actor Adam.")
        expansion_mc.validate(contract, config)
    if expansion_optimizer_continuation is not None:
        from expansion_mc import ExpansionMcAdamContinuation
        require(isinstance(expansion_optimizer_continuation, ExpansionMcAdamContinuation)
                and expansion_mc is not None and optimizer_from is not None and not resume,
                "Expansion continuation requires its previous exact checkpoint and MC objective.")
    if expansion_baseline is not None:
        from expansion_previous_baseline import ExpansionBaselineObjective, ExpansionBaselineAdamContinuation
        require(type(expansion_baseline) is ExpansionBaselineObjective and actor_only is None
                and actor_optimizer_continuation is None and joint_actor_only is None
                and joint_optimizer_continuation is None and expansion_mc is None
                and expansion_optimizer_continuation is None and update_mode == LEGACY_UPDATE_MODE
                and not resume and purchase_reference is None and offered_reference is None
                and collection_reader == expansion_baseline.phase_reader
                and (optimizer_from is None or type(expansion_baseline_continuation) is ExpansionBaselineAdamContinuation),
                "Previous-batch expansion baseline requires its exact reader, epoch rollback and separate typed carry.")
        expansion_baseline.validate(contract, config)
        expansion_baseline.admit_optimizer(optimizer_from, config)
    if expansion_baseline_continuation is not None:
        from expansion_previous_baseline import ExpansionBaselineAdamContinuation
        require(type(expansion_baseline_continuation) is ExpansionBaselineAdamContinuation
                and expansion_baseline is not None and optimizer_from is not None and not resume,
                "Previous-batch baseline carry requires its completed predecessor and typed objective.")
    validate_purchase_reference(purchase_reference, contract, update_mode)
    validate_offered_reference(offered_reference, contract, update_mode)
    require(purchase_reference is None or offered_reference is None,
            "A typed actor cannot use two offered reference objectives.")
    require(collection_reader is None or (callable(collection_reader) and entrypoint is not None),
            "A custom typed collection reader requires an explicit owning entrypoint.")
    torch.set_num_threads(config.threads)
    torch.use_deterministic_algorithms(True)
    model = validate_model(read_json(model_path), contract=contract)
    reader = read_collection if collection_reader is None else collection_reader
    reader_source = None
    if collection_reader is not None:
        source = inspect.getsourcefile(collection_reader)
        require(source is not None and Path(source).is_file(),
                "A custom collection reader must have an inspectable implementation source file.")
        reader_source = {"path": str(Path(source).resolve()), "sha256": file_digest(source)}
    rows, provenance = reader(collection_path, model_path, model, contract=contract)
    actor_only_identity = None
    if actor_only is not None:
        rows, actor_only_identity = actor_only.bind(rows, provenance, file_digest(model_path))
    joint_identity = None
    if joint_actor_only is not None:
        rows, joint_identity = joint_actor_only.bind(rows, provenance, file_digest(model_path))
    expansion_identity = None
    if expansion_mc is not None:
        rows, expansion_identity = expansion_mc.bind(rows, provenance, file_digest(model_path))
    baseline_identity = None
    if expansion_baseline is not None:
        rows, baseline_identity = expansion_baseline.bind(rows, provenance, file_digest(model_path))
    carry_identity = None
    if actor_optimizer_continuation is not None:
        carry_identity = actor_optimizer_continuation.validate(
            optimizer_from, actor_only_identity, model, config)
        actor_only_identity["optimizerInitialization"] = "matching-actor-adam"
    joint_carry_identity = None
    if joint_optimizer_continuation is not None:
        joint_carry_identity = joint_optimizer_continuation.validate(
            optimizer_from, joint_identity, model, config)
        joint_identity["optimizerInitialization"] = "matching-joint-phase-adam"
    expansion_carry_identity = None
    if expansion_optimizer_continuation is not None:
        expansion_carry_identity = expansion_optimizer_continuation.validate(
            optimizer_from, expansion_identity, model, config)
        expansion_identity["optimizerInitialization"] = "matching-expansion-mc-adam"
    baseline_carry_identity = None
    if expansion_baseline_continuation is not None:
        baseline_carry_identity = expansion_baseline_continuation.validate(
            optimizer_from, baseline_identity, model, config)
        baseline_identity["optimizerInitialization"] = "matching-expansion-previous-batch-adam"
    if reader_source is not None:
        require(file_digest(reader_source["path"]) == reader_source["sha256"]
                and provenance.get("readerSha256") == reader_source["sha256"],
                "Custom collection reader source must stay fixed and match its provenance.")
    if config.cardReferenceKlCoefficient > 0:
        for row in rows:
            row["_cardReference"] = torch.tensor(card_reference_probabilities(
                [action["command"] for action in row["observation"]["actions"]]), dtype=DTYPE)
    identity = {"schemaVersion": 1, "actorVersion": contract.actor,
                "modelSha256": file_digest(model_path), "data": provenance,
                "config": asdict(config), "learnerSha256": file_digest(__file__),
                "torchVersion": torch.__version__, "pythonVersion": sys.version,
                "optimizerFromSha256": file_digest(optimizer_from) if optimizer_from else None}
    if entrypoint is not None:
        identity["entrypointSha256"] = file_digest(entrypoint)
    if update_mode == CHECKED_PREFIX_MODE:
        identity["updateMode"] = CHECKED_PREFIX_MODE
    if purchase_reference is not None:
        identity["purchaseReferenceObjective"] = asdict(purchase_reference)
    if offered_reference is not None:
        identity["offeredReferenceObjective"] = asdict(offered_reference)
    if reader_source is not None:
        identity["collectionReaderSource"] = reader_source
    if actor_only_identity is not None:
        identity["actorOnlyObjective"] = actor_only_identity
    if carry_identity is not None:
        identity["actorOptimizerContinuation"] = carry_identity
    if joint_identity is not None:
        identity["jointActorOnlyObjective"] = joint_identity
        identity["jointActorObjectiveSha256"] = file_digest(inspect.getsourcefile(type(joint_actor_only)))
    if joint_carry_identity is not None:
        identity["jointActorOptimizerContinuation"] = joint_carry_identity
    if expansion_identity is not None:
        identity["expansionMcObjective"] = expansion_identity
        identity["expansionMcOwnerSha256"] = file_digest(inspect.getsourcefile(type(expansion_mc)))
    if expansion_carry_identity is not None:
        identity["expansionMcOptimizerContinuation"] = expansion_carry_identity
    if baseline_identity is not None:
        identity["expansionBaselineObjective"] = baseline_identity
        identity["expansionBaselineOwnerSha256"] = file_digest(inspect.getsourcefile(type(expansion_baseline)))
    if baseline_carry_identity is not None:
        identity["expansionBaselineOptimizerContinuation"] = baseline_carry_identity
    output = Path(output)
    checkpoint_path = output / "checkpoint.json"
    started_path = output / ("block-started.json" if update_mode == CHECKED_PREFIX_MODE
                             else "epoch-started.json")
    if resume:
        require((output / "identity.json").exists(), "Resume requires an existing update identity.")
        previous_identity = read_json(output / "identity.json")
        number(previous_identity.get("deadlineEpochSeconds"), "Update deadline", 0)
        require({key: value for key, value in previous_identity.items()
                 if key != "deadlineEpochSeconds"} == identity,
                "Resume inputs, runtime, configuration or trajectory bytes changed.")
        identity = previous_identity
        # No epoch can start before the initial checkpoint is published. Recover an interrupted
        # initialization deterministically, retaining the original non-renewable wall deadline.
        if checkpoint_path.exists():
            checkpoint = read_checkpoint(checkpoint_path)
        else:
            require(not started_path.exists() and not (output / "finished.json").exists()
                    and not (output / "policy.json").exists(),
                    "A checkpoint is missing after epoch execution; preserve evidence for inspection.")
            checkpoint = initial_checkpoint(identity, model)
        require(checkpoint.get("identitySha256") == digest(identity),
                "Checkpoint belongs to another purchase update.")
        require(checkpoint.get("deadlineEpochSeconds") == identity["deadlineEpochSeconds"],
                "Checkpoint cannot change its initial wall-clock deadline.")
    else:
        require(not output.exists(), "A fresh purchase update requires an unused output directory.")
        identity["deadlineEpochSeconds"] = time.time() + config.maxSeconds
        output.mkdir(parents=True)
        atomic_json(output / "identity.json", identity)
        checkpoint = initial_checkpoint(identity, model)
    if update_mode == CHECKED_PREFIX_MODE:
        validate_checked_prefix_checkpoint(checkpoint, identity, rows)
    started_kind = "block" if update_mode == CHECKED_PREFIX_MODE else "epoch"
    started_marker = {"kind": f"{contract.label}-{started_kind}-started",
                      "identitySha256": digest(identity)}
    if started_path.exists():
        require(read_json(started_path) == started_marker,
                "Existing epoch marker belongs to a different purchase update.")
    policy = ResidualPolicy(checkpoint["model"], contract)
    behavior = ResidualPolicy(model, contract)
    if actor_only is not None or joint_actor_only is not None or expansion_mc is not None or expansion_baseline is not None:
        policy.valueWeights.requires_grad_(False)
        policy.valueBias.requires_grad_(False)
    parameters = (policy.parameters() if actor_only is None and joint_actor_only is None and expansion_mc is None and expansion_baseline is None else
                  [parameter for parameter in policy.parameters() if parameter.requires_grad])
    optimizer = torch.optim.Adam(parameters, lr=config.learningRate, foreach=False)
    if checkpoint["optimizer"] is not None:
        restore_optimizer(optimizer, checkpoint["optimizer"])
    elif optimizer_from:
        previous = read_checkpoint(optimizer_from)
        require(previous.get("model") == model,
                "Carried optimizer must match the exact input actor.")
        require(previous.get("optimizer") is not None,
                "Carried checkpoint has no optimizer state.")
        restore_optimizer(optimizer, previous["optimizer"])
        require(all(group["lr"] == config.learningRate for group in optimizer.param_groups),
                "Carried optimizer learning rate differs from frozen update configuration.")
    checkpoint["optimizer"] = encode_optimizer(optimizer)
    save_checkpoint(checkpoint_path, checkpoint)
    started = time.monotonic()
    prior_elapsed = checkpoint["elapsedSeconds"]
    deadline = started + max(0.0, checkpoint["deadlineEpochSeconds"] - time.time())
    while checkpoint["epoch"] < config.epochs and checkpoint["stopReason"] is None:
        if not started_path.exists():
            atomic_json(started_path, started_marker)
        if update_mode == CHECKED_PREFIX_MODE:
            options = {} if purchase_reference is None else {"purchase_reference": purchase_reference}
            if offered_reference is not None:
                options["offered_reference"] = offered_reference
            block = fit_checked_block(policy, optimizer, behavior, rows, config,
                                      checkpoint["cursor"], deadline, **options)
            checkpoint["history"].append(block)
            checkpoint.update(cursor=block["nextCursor"], epoch=block["nextCursor"]["epoch"],
                              acceptedSteps=checkpoint["acceptedSteps"] + block["acceptedSteps"],
                              acceptedBlocks=checkpoint["acceptedBlocks"] + int(block["accepted"]),
                              stopReason=block["stopReason"], model=policy.artifact(),
                              optimizer=encode_optimizer(optimizer),
                              elapsedSeconds=prior_elapsed + time.monotonic() - started)
            save_checkpoint(checkpoint_path, checkpoint)
            continue
        before = copy.deepcopy(policy.state_dict())
        old_optimizer = copy.deepcopy(optimizer.state_dict())
        try:
            if expansion_baseline is not None:
                fit_epoch(policy, optimizer, rows, config, checkpoint["epoch"], deadline, expansion_baseline=expansion_baseline)
            elif expansion_mc is not None:
                fit_epoch(policy, optimizer, rows, config, checkpoint["epoch"], deadline, expansion_mc=expansion_mc)
            elif joint_actor_only is not None:
                fit_epoch(policy, optimizer, rows, config, checkpoint["epoch"], deadline,
                          joint_actor_only=joint_actor_only)
            elif actor_only is None:
                fit_epoch(policy, optimizer, rows, config, checkpoint["epoch"], deadline)
            else:
                fit_epoch(policy, optimizer, rows, config, checkpoint["epoch"], deadline,
                          actor_only=True, precomputed_advantages=isinstance(actor_only, PurchaseAdvantageTargets))
        except ValueError as failure:
            if "elapsed-time budget" not in str(failure):
                raise
            policy.load_state_dict(before)
            optimizer.load_state_dict(old_optimizer)
            checkpoint["stopReason"] = "time-budget"
        if checkpoint["stopReason"] is None:
            metrics = behavior_metrics(policy, behavior, rows, config.batchSize)
            accepted = metrics["behaviorKl"] <= config.maxBehaviorKl
            checkpoint["history"].append({"epoch": checkpoint["epoch"] + 1,
                                          "accepted": accepted, **metrics})
            if accepted:
                checkpoint["epoch"] += 1
            else:
                policy.load_state_dict(before)
                optimizer.load_state_dict(old_optimizer)
                checkpoint["stopReason"] = "behavior-kl-limit"
        checkpoint.update(model=policy.artifact(), optimizer=encode_optimizer(optimizer),
                          elapsedSeconds=prior_elapsed + time.monotonic() - started)
        save_checkpoint(checkpoint_path, checkpoint)
    atomic_json(output / "policy.json", policy.artifact())
    report = {"schemaVersion": 1, "identitySha256": digest(identity),
              "inputModelSha256": identity["modelSha256"],
              "outputModelSha256": file_digest(output / "policy.json"),
              "games": provenance["games"], "decisions": len(rows),
              "gameMeanReward": provenance["gameMeanReward"],
              "acceptedEpochs": checkpoint["epoch"], "history": checkpoint["history"],
              "stopReason": checkpoint["stopReason"] or "epochs-complete",
              "elapsedSeconds": checkpoint["elapsedSeconds"],
              "lossWeighting": "Uniform decision mean; whole-game outcomes are report units.",
              "strengthClaim": False}
    if update_mode == CHECKED_PREFIX_MODE:
        report.update(schemaVersion=2, updateMode=CHECKED_PREFIX_MODE,
                      **validate_checked_prefix_checkpoint(checkpoint, identity, rows))
    if purchase_reference is not None:
        report["purchaseReferenceObjective"] = asdict(purchase_reference)
    if offered_reference is not None:
        report["offeredReferenceObjective"] = asdict(offered_reference)
    if actor_only_identity is not None or joint_identity is not None or expansion_identity is not None or baseline_identity is not None:
        require(policy.artifact()["valueWeights"] == model["valueWeights"]
                and policy.artifact()["valueBias"] == model["valueBias"],
                "Actor-only optimization must preserve both original value-head parameters.")
        require(len(optimizer.param_groups) == 1
                and len(optimizer.param_groups[0]["params"]) == len(PARAMETERS) - 2,
                "Actor-only Adam must exclude both value-head parameters and their momentum.")
        if actor_only_identity is not None:
            report["actorOnlyObjective"] = actor_only_identity
        if joint_identity is not None:
            report["jointActorOnlyObjective"] = joint_identity
        if expansion_identity is not None:
            report["expansionMcObjective"] = expansion_identity
        if baseline_identity is not None:
            report["expansionBaselineObjective"] = baseline_identity
    atomic_json(output / "finished.json", report)
    return report


def main(arguments=None, *, contract=PURCHASE_CONTRACT, entrypoint=None, toy_runner=None,
         description=None):
    parser = argparse.ArgumentParser(description=description or __doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    initialization = modes.add_parser("initialize")
    initialization.add_argument("--schema", type=Path, required=True)
    initialization.add_argument("--seed", type=int, required=True)
    initialization.add_argument("--output", type=Path, required=True)
    fitting = modes.add_parser("update")
    fitting.add_argument("--model", type=Path, required=True)
    fitting.add_argument("--source", "--collection", dest="collection", type=Path, required=True)
    fitting.add_argument("--config", type=Path)
    fitting.add_argument("--seed", type=int, required=True)
    fitting.add_argument("--output", type=Path, required=True)
    fitting.add_argument("--resume", action="store_true")
    fitting.add_argument("--optimizer-from", type=Path)
    fitting.add_argument("--purchase-reference-objective", type=Path,
                         help="Explicit version/coefficient JSON; purchase checked-prefix updates only.")
    fitting.add_argument("--update-mode", choices=(LEGACY_UPDATE_MODE, CHECKED_PREFIX_MODE),
                         default=LEGACY_UPDATE_MODE,
                         help="Versioned KL rollback/checkpoint unit; default retains whole epochs.")
    toy = modes.add_parser("toy")
    toy.add_argument("--output", type=Path, required=True)
    options = parser.parse_args(arguments)
    if options.mode == "initialize":
        require(not options.output.exists(), "Initial policy output already exists.")
        schema = read_json(options.schema)
        model = initialize(schema, options.seed, contract=contract)
        options.output.mkdir(parents=True)
        atomic_json(options.output / "policy.json", model)
        atomic_json(options.output / "schema.json", {
            key: model[key]
            for key in ("encoderVersion", "stateFeatureNames", "actionFeatureNames")})
        result = {"model": str(options.output / "policy.json"),
                  "sha256": file_digest(options.output / "policy.json"),
                  "schemaSourceSha256": file_digest(options.schema), "seed": options.seed,
                  "learnerSha256": file_digest(__file__), "torchVersion": torch.__version__}
        if entrypoint is not None:
            result["entrypointSha256"] = file_digest(entrypoint)
        atomic_json(options.output / "initialization.json", result)
    elif options.mode == "toy":
        if toy_runner is None:
            from purchase_policy_toy import run_toy
            toy_runner = run_toy
        require(not options.output.exists(), "Toy output already exists.")
        result = toy_runner()
        atomic_json(options.output / "report.json", result)
        require(result["passed"], "Delayed-sequence numerical check failed; inspect its report.")
    else:
        values = read_json(options.config) if options.config else {}
        require("seed" not in values or values["seed"] == options.seed,
                "Explicit update seed disagrees with the configuration file.")
        config = UpdateConfig(**{**values, "seed": options.seed}).validate()
        objective = (PurchaseReferenceObjective.from_json(read_json(options.purchase_reference_objective))
                     if options.purchase_reference_objective else None)
        result = update(options.model, options.collection, config, options.output,
                        options.resume, options.optimizer_from,
                        contract=contract, entrypoint=entrypoint, update_mode=options.update_mode,
                        purchase_reference=objective)
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
