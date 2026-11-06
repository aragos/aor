"""Bounded CPU PPO updates for complete, on-policy purchase trajectories.

The portable model uses fixed named dictionaries, Float64, one shared 64-unit
state embedding, and a residual actor over the qualified policy's 97% prior.
No hidden state, counterfactual labels, or authored dense rewards enter fitting.
PyTorch owns differentiation; the independent stdlib forward is a wire oracle.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import asdict, dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import random
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
PARAMETERS = (
    "stateWeights", "stateBias", "actionWeights", "actionBias",
    "actorWeights", "valueWeights", "valueBias",
)


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


def atomic_json(path, value):
    """Publish one fsynced JSON object; a failed write preserves the previous file."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(value, indent=2, allow_nan=False).encode() + b"\n"
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


def validate_model(model):
    require(model.get("schemaVersion") == 1 and type(model["schemaVersion"]) is int,
            "Purchase model requires schemaVersion 1.")
    require(model.get("encoderVersion") == ENCODER_VERSION,
            "Purchase model encoder version is unsupported.")
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


def initialize(schema, seed):
    """Initialize from the complete catalog dictionary, never observed nonzero keys."""
    require(schema.get("encoderVersion") == ENCODER_VERSION,
            "The frozen feature dictionary requires the current encoder version.")
    states = names(schema.get("stateFeatureNames"), "State features", 8192)
    actions = names(schema.get("actionFeatureNames"), "Action features", 512)
    rng = random.Random(seed)
    def weights(size):
        scale = 1.0 / math.sqrt(size)
        return [rng.uniform(-scale, scale) for _ in range(HIDDEN * size)]
    return validate_model({
        "schemaVersion": 1, "encoderVersion": ENCODER_VERSION,
        "hiddenSize": HIDDEN, "baselineProbability": 0.97,
        "stateFeatureNames": states, "actionFeatureNames": actions,
        "stateWeights": weights(len(states)), "stateBias": [0.0] * HIDDEN,
        "actionWeights": weights(len(actions)), "actionBias": [0.0] * HIDDEN,
        "actorWeights": [0.0] * HIDDEN, "valueWeights": [0.0] * HIDDEN,
        "valueBias": 0.0,
    })


def sparse(values, allowed, label):
    require(isinstance(values, dict), f"{label} must be a sparse feature object.")
    require(set(values) <= allowed, f"{label} contains unknown feature names.")
    for value in values.values():
        number(value, label, -FEATURE_LIMIT, FEATURE_LIMIT)


def validate_observation(observation, model):
    require(observation.get("encoderVersion") == model["encoderVersion"],
            "Observation and policy encoder versions must match.")
    sparse(observation.get("stateFeatures"), set(model["stateFeatureNames"]), "State")
    actions = observation.get("actions")
    require(isinstance(actions, list) and 1 <= len(actions) <= 128,
            "A purchase observation requires 1–128 legal actions.")
    commands = [action.get("command") for action in actions]
    require(all(isinstance(command, str) and command.strip()
                and len(command) <= 200 and "\n" not in command for command in commands)
            and len(set(commands)) == len(commands), "Legal commands must be distinct strings.")
    require(commands == sorted(commands), "Legal commands must use canonical sorted order.")
    for action in actions:
        sparse(action.get("features"), set(model["actionFeatureNames"]), "Action")
    integer(observation.get("baselineIndex"), "baselineIndex", 0, len(actions) - 1)


def prior_probabilities(count, baseline):
    if count == 1:
        return [1.0]
    return [0.97 if index == baseline else 0.03 / (count - 1) for index in range(count)]


def reference_forward(model, observation):
    """Independent scalar Float64 oracle; values/probabilities use only safe features."""
    validate_model(model)
    validate_observation(observation, model)
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


class PurchasePolicy(nn.Module):
    """Compute each state's embedding once, then score a flattened legal-action batch."""
    def __init__(self, model):
        super().__init__()
        self.template = copy.deepcopy(validate_model(model))
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
        return validate_model(model)

    def prepare(self, observation):
        if isinstance(observation, _PreparedObservation):
            require(observation.dictionary == self.dictionary,
                    "Prepared inputs belong to a different feature dictionary.")
            return observation
        validate_observation(observation, self.template)
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
        state = torch.zeros((len(prepared), len(self.state_indices)), dtype=DTYPE)
        action = torch.zeros((sum(item.action_count for item in prepared),
                              len(self.action_indices)), dtype=DTYPE)
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
        return state, action, torch.tensor(owners)

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
            prior = torch.tensor(prior_probabilities(size, observation.baseline_index),
                                 dtype=DTYPE).log()
            log_probability = torch.log_softmax(prior + residual, dim=0)
            if bool(torch.all(residual == residual[0])):
                # Match the common exact-prior forward without destroying the zero-head gradient.
                log_probability = log_probability + (prior - log_probability).detach()
            log_probabilities.append(log_probability)
            cursor += size
        values = torch.sigmoid(hidden @ self.valueWeights + self.valueBias)
        return log_probabilities, values


@dataclass(frozen=True)
class UpdateConfig:
    """maxSeconds bounds wall time since the first update start, including resume pauses."""
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

    def validate(self):
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
        return self


def clipped_policy_loss(log_probability, old_log_probability, advantage, clip):
    ratio = torch.exp(log_probability - old_log_probability)
    return -torch.minimum(ratio * advantage, torch.clamp(ratio, 1 - clip, 1 + clip) * advantage)


def loss_for_rows(policy, rows, config):
    probabilities, values = policy([row.get("_prepared", row["observation"]) for row in rows])
    chosen = torch.stack([logp[row["selectedIndex"]]
                          for logp, row in zip(probabilities, rows)])
    old = torch.tensor([row["behaviorLogProbability"] for row in rows], dtype=DTYPE)
    returns = torch.tensor([row["return"] for row in rows], dtype=DTYPE)
    advantages = returns - torch.tensor([row["behaviorValue"] for row in rows], dtype=DTYPE)
    actor = clipped_policy_loss(chosen, old, advantages, config.clip).mean()
    critic = 0.5 * (values - returns).square().mean()
    entropy = torch.stack([-(value.exp() * value).sum() for value in probabilities]).mean()
    return actor + config.valueCoefficient * critic - config.entropyCoefficient * entropy


def fit_epoch(policy, optimizer, rows, config, epoch, deadline):
    order = list(range(len(rows)))
    random.Random(config.seed + epoch).shuffle(order)
    for start in range(0, len(order), config.batchSize):
        require(time.monotonic() < deadline, "Purchase update exceeded its elapsed-time budget.")
        batch = [rows[index] for index in order[start:start + config.batchSize]]
        optimizer.zero_grad(set_to_none=True)
        loss = loss_for_rows(policy, batch, config)
        require(bool(torch.isfinite(loss)), "Purchase loss must remain finite.")
        loss.backward()
        nn.utils.clip_grad_norm_(policy.parameters(), config.maxGradientNorm,
                                 error_if_nonfinite=True)
        optimizer.step()


def behavior_metrics(policy, behavior, rows, batch_size=64):
    totals = {"behaviorKl": 0.0, "priorKl": 0.0, "valueMse": 0.0}
    with torch.no_grad():
        for start in range(0, len(rows), batch_size):
            batch = rows[start:start + batch_size]
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


def read_collection(directory, model_path, model):
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
    require(collection.get("actorVersion") == ACTOR_VERSION, "Actor version is unsupported.")
    paths = [directory / f"game-{index}.json" for index in range(count)]
    require(all(path.exists() for path in paths), "A completed collection is missing a game.")
    game_hashes = [file_digest(path) for path in paths]
    require(finished.get("gameSha256") == game_hashes,
            "Completed trajectory bytes differ from the collector's frozen game hashes.")
    rows = []
    rewards = []
    origins = set()
    behavior = PurchasePolicy(model)
    for index, path in enumerate(paths):
        game = read_json(path)
        require(game.get("index") == index and type(game["index"]) is int,
                "Game index must match its checkpoint filename.")
        for key, expected in (("signature", signature), ("modelSha256", model_sha),
                              ("encoderVersion", ENCODER_VERSION),
                              ("actorVersion", ACTOR_VERSION)):
            require(game.get(key) == expected, f"Game {key} must match frozen collection.")
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
                    exact = reference_forward(model, row["observation"])["probabilities"]
                    require(select(exact, selection_uniform(row["actorSeed"]))
                            == row["selectedIndex"],
                            "Recorded action differs from the frozen actor's seeded selector.")
    provenance = {"collectionSha256": file_digest(directory / "collection.json"),
                  "collectionSignature": signature,
                  "gameHashes": game_hashes,
                  "games": count, "gameMeanReward": sum(rewards) / len(rewards)}
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
    atomic_json(path, {"sha256": digest(payload), "payload": payload})


def read_checkpoint(path):
    record = read_json(path)
    require(record.get("sha256") == digest(record.get("payload")),
            "Purchase checkpoint checksum differs from its payload.")
    return record["payload"]


def initial_checkpoint(identity, model):
    return {"schemaVersion": 1, "identitySha256": digest(identity), "epoch": 0,
            "model": model, "optimizer": None, "history": [], "elapsedSeconds": 0.0,
            "deadlineEpochSeconds": identity["deadlineEpochSeconds"], "stopReason": None}


def update(model_path, collection_path, config, output, resume=False, optimizer_from=None):
    config.validate()
    torch.set_num_threads(config.threads)
    torch.use_deterministic_algorithms(True)
    model = validate_model(read_json(model_path))
    rows, provenance = read_collection(collection_path, model_path, model)
    identity = {"schemaVersion": 1, "actorVersion": ACTOR_VERSION,
                "modelSha256": file_digest(model_path), "data": provenance,
                "config": asdict(config), "learnerSha256": file_digest(__file__),
                "torchVersion": torch.__version__, "pythonVersion": sys.version,
                "optimizerFromSha256": file_digest(optimizer_from) if optimizer_from else None}
    output = Path(output)
    checkpoint_path = output / "checkpoint.json"
    started_path = output / "epoch-started.json"
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
    started_marker = {"kind": "purchase-epoch-started", "identitySha256": digest(identity)}
    if started_path.exists():
        require(read_json(started_path) == started_marker,
                "Existing epoch marker belongs to a different purchase update.")
    policy = PurchasePolicy(checkpoint["model"])
    behavior = PurchasePolicy(model)
    optimizer = torch.optim.Adam(policy.parameters(), lr=config.learningRate, foreach=False)
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
        before = copy.deepcopy(policy.state_dict())
        old_optimizer = copy.deepcopy(optimizer.state_dict())
        try:
            fit_epoch(policy, optimizer, rows, config, checkpoint["epoch"], deadline)
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
    atomic_json(output / "finished.json", report)
    return report


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
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
    toy = modes.add_parser("toy")
    toy.add_argument("--output", type=Path, required=True)
    options = parser.parse_args(arguments)
    if options.mode == "initialize":
        require(not options.output.exists(), "Initial policy output already exists.")
        schema = read_json(options.schema)
        model = initialize(schema, options.seed)
        options.output.mkdir(parents=True)
        atomic_json(options.output / "policy.json", model)
        atomic_json(options.output / "schema.json", {
            key: model[key]
            for key in ("encoderVersion", "stateFeatureNames", "actionFeatureNames")})
        result = {"model": str(options.output / "policy.json"),
                  "sha256": file_digest(options.output / "policy.json"),
                  "schemaSourceSha256": file_digest(options.schema), "seed": options.seed,
                  "learnerSha256": file_digest(__file__), "torchVersion": torch.__version__}
        atomic_json(options.output / "initialization.json", result)
    elif options.mode == "toy":
        from purchase_policy_toy import run_toy
        require(not options.output.exists(), "Toy output already exists.")
        result = run_toy()
        atomic_json(options.output / "report.json", result)
        require(result["passed"], "Delayed-sequence numerical check failed; inspect its report.")
    else:
        values = read_json(options.config) if options.config else {}
        require("seed" not in values or values["seed"] == options.seed,
                "Explicit update seed disagrees with the configuration file.")
        config = UpdateConfig(**{**values, "seed": options.seed}).validate()
        result = update(options.model, options.collection, config, options.output,
                        options.resume, options.optimizer_from)
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
