"""Typed linear Double-Q control; independent of the existing PPO model/optimizer wire.

Q estimates are finite real values, never probabilities or clipped bootstrap labels. The
sampled transition objective weights phase transitions uniformly, not games uniformly.
"""
from __future__ import annotations

from collections import deque
import copy
from dataclasses import asdict, dataclass, replace
import math
import random
import time
from typing import Literal

import purchase_policy as b

PHASES = ("token", "expansion")
ARCHITECTURE = "aor-territory-linear-q-1"
SELECTORS = {phase: "aor-" + phase + "-value-policy-1" for phase in PHASES}
SALTS = {"token": 0x243f6a88, "expansion": 0x85a308d3}
CONTRACTS = {"token": b.TOKEN_CONTRACT, "expansion": b.EXPANSION_CONTRACT}
PARAMETERS = ("stateWeights", "stateBias", "actionWeights", "actionBias", "outputWeights", "outputBias")
MODEL_FIELDS = set(PARAMETERS) | {"schemaVersion", "architecture", "encoderVersion", "hiddenSize",
                                "stateFeatureNames", "actionFeatureNames"}
MAGNITUDE_LIMIT = 1e6
TargetSteps = Literal[1, 16, "terminal"]


def validate_target_steps(value: TargetSteps) -> TargetSteps:
    b.require((type(value) is int and value in (1, 16))
              or (type(value) is str and value == "terminal"),
              "The return horizon must be integer1, integer16 or the explicit string 'terminal'.")
    return value


@dataclass(frozen=True)
class UpdateConfig:
    seed: int = 103
    maxSeconds: float = 120.0
    learningRate: float = .0003
    passesPerNewBatch: int = 4
    batchSize: int = 64
    maxGradientNorm: float = .5
    replayRowsPerPhase: int = 16384
    replayGames: int = 4096
    targetSyncEveryUpdates: int = 4
    targetSteps: TargetSteps = 1
    gamma: float = 1.0
    epsilon: float = .20
    maximumAbsQ: float = 10.0
    threads: int = 1

    def validate(self):
        b.integer(self.seed, "Replay sampling seed", -(2**31), 2**31-1)
        b.number(self.maxSeconds, "Original update allowance", 0.001, 10800)
        validate_target_steps(self.targetSteps)
        fixed = asdict(UpdateConfig())
        b.require(all(value == fixed[key] for key, value in asdict(self).items()
                      if key not in ("seed", "maxSeconds", "targetSteps")), "Linear-Q algorithm settings are fixed and versioned.")
        return self


def finite_values(values):
    b.require(all(math.isfinite(float(value)) and abs(float(value)) <= MAGNITUDE_LIMIT for value in values),
              "Abort nonfinite or dangerous-magnitude Q estimates; never clip them.")


def validate_model(model, phase):
    b.require(phase in PHASES and isinstance(model, dict) and set(model) == MODEL_FIELDS,
              "A typed Q model requires the exact distinct linear-Q field set.")
    b.require(type(model['schemaVersion']) is int and model['schemaVersion'] == 1
              and model['architecture'] == ARCHITECTURE
              and model['encoderVersion'] == CONTRACTS[phase].encoder
              and type(model['hiddenSize']) is int and model['hiddenSize'] == 64,
              "Unsupported typed Q architecture, encoder or hidden size.")
    states = b.names(model['stateFeatureNames'], "Q state features", 8192)
    actions = b.names(model['actionFeatureNames'], "Q action features", 512)
    sizes = dict(stateWeights=64*len(states), stateBias=64, actionWeights=64*len(actions),
                 actionBias=64, outputWeights=64)
    for name, size in sizes.items():
        b.require(isinstance(model[name], list) and len(model[name]) == size,
                  name + " requires its exact row-major Float64 dimensions.")
        for value in model[name]: b.number(value, name, -MAGNITUDE_LIMIT, MAGNITUDE_LIMIT)
    b.number(model['outputBias'], "Q output bias", -MAGNITUDE_LIMIT, MAGNITUDE_LIMIT)
    return model


def initialize(schema, seed, phase):
    old = b.initialize(schema, seed, contract=CONTRACTS[phase])
    model = {key: old[key] for key in ("encoderVersion", "hiddenSize", "stateFeatureNames", "actionFeatureNames",
                                      "stateWeights", "stateBias", "actionWeights", "actionBias")}
    return validate_model(dict(model, schemaVersion=1, architecture=ARCHITECTURE,
                               outputWeights=[0.0]*64, outputBias=.5), phase)


def reference_forward(model, observation, phase):
    validate_model(model, phase)
    b.validate_observation(observation, model, contract=CONTRACTS[phase])
    x = [observation['stateFeatures'].get(name, 0.) for name in model['stateFeatureNames']]
    h = [math.tanh(model['stateBias'][i] + sum(model['stateWeights'][i*len(x)+j]*v
                                             for j, v in enumerate(x))) for i in range(64)]
    result = []
    for action in observation['actions']:
        a = [action['features'].get(name, 0.) for name in model['actionFeatureNames']]
        result.append(model['outputBias'] + sum(model['outputWeights'][i]*math.tanh(
            h[i]+model['actionBias'][i]+sum(model['actionWeights'][i*len(a)+j]*v
                                          for j, v in enumerate(a))) for i in range(64)))
    finite_values(result)
    return result


class ActionValue(b.nn.Module):
    def __init__(self, model, phase):
        super().__init__()
        self.phase = phase
        self.template = copy.deepcopy(validate_model(model, phase))
        # Reuse only the validated sparse-input preparation. It is not a trainable submodule.
        encoder_model = {key: value for key, value in model.items()
                         if key not in ('architecture', 'outputWeights', 'outputBias')}
        encoder_model.update(baselineProbability=.97, actorWeights=[0.]*64,
                             valueWeights=[0.]*64, valueBias=0.)
        object.__setattr__(self, 'encoder', b.ResidualPolicy(encoder_model, CONTRACTS[phase]))
        for name in PARAMETERS:
            value = b.torch.tensor(model[name], dtype=b.DTYPE)
            if name == 'stateWeights': value = value.reshape(64, len(model['stateFeatureNames']))
            if name == 'actionWeights': value = value.reshape(64, len(model['actionFeatureNames']))
            self.register_parameter(name, b.nn.Parameter(value))

    def artifact(self):
        model = copy.deepcopy(self.template)
        for name in PARAMETERS:
            value = getattr(self, name).detach()
            model[name] = value.item() if name == 'outputBias' else value.flatten().tolist()
        return validate_model(model, self.phase)

    def forward(self, observations):
        b.require(1 <= len(observations) <= 256, "Q forward requires1–256 actual observations.")
        prepared = [self.encoder.prepare(row) for row in observations]
        b.require(sum(row.action_count for row in prepared) <= 16384, "Q forward exceeds the action allocation limit.")
        state, actions, owners = self.encoder._batch_inputs(prepared)
        hidden = b.torch.tanh(state @ self.stateWeights.T + self.stateBias)
        action_hidden = b.torch.tanh(hidden[owners] + actions @ self.actionWeights.T + self.actionBias)
        values = action_hidden @ self.outputWeights + self.outputBias
        finite_values(values.detach())
        return list(values.split([row.action_count for row in prepared]))


def evaluate(actor, observations):
    """Split allocation only; preserve transition order and a single minibatch loss/Adam step."""
    result, batch, count = [], [], 0
    for observation in observations:
        size = observation.action_count if isinstance(observation, b._PreparedObservation) else len(observation['actions'])
        if batch and (len(batch) == 256 or count+size > 16384):
            result.extend(actor(batch)); batch, count = [], 0
        batch.append(observation); count += size
    if batch: result.extend(actor(batch))
    return result


def greedy_index(values, observation):
    values = [float(value) for value in values]
    finite_values(values)
    b.require(len(values) == len(observation['actions']), "Q values must match actual offered actions.")
    best, baseline = max(values), observation['baselineIndex']
    if values[baseline] == best: return baseline
    return min((i for i, value in enumerate(values) if value == best),
               key=lambda i: observation['actions'][i]['command'])


def probabilities(values, observation, epsilon=.20):
    b.number(epsilon, "Q exploration probability", 0., 1.)
    selected, count = greedy_index(values, observation), len(values)
    return [epsilon/count + (1-epsilon if i == selected else 0.) for i in range(count)]


@dataclass(frozen=True)
class Successor:
    origin: str
    number: int
    phase: str
    observation: dict


@dataclass(frozen=True)
class Transition:
    origin: str
    number: int
    phase: str
    observation: dict
    selected: int
    reward: float
    successor: Successor | None


@dataclass(frozen=True)
class Episode:
    origin: str
    gameSeed: int
    reward: float
    transitions: tuple
    reference: dict


def validate_episode(episode):
    b.require(isinstance(episode.origin, str) and episode.origin, "A replay game needs an immutable origin identity.")
    b.integer(episode.gameSeed, "Replay world seed", -(2**31), 2**31-1)
    b.number(episode.reward, "Terminal return", 0., 1.)
    previous = -1
    for i, row in enumerate(episode.transitions):
        b.require(row.origin == episode.origin and row.phase in PHASES and row.number > previous,
                  "Replay rows must retain their game and actual increasing decision numbers.")
        b.integer(row.selected, "Observed selected action", 0, len(row.observation['actions'])-1)
        if i+1 == len(episode.transitions):
            b.require(row.successor is None and row.reward == episode.reward,
                      "Only the last actual learned decision owns the terminal return.")
        else:
            following = episode.transitions[i+1]
            b.require(row.reward == 0. and row.successor == Successor(
                episode.origin, following.number, following.phase, following.observation),
                "Bootstrap only from the actual next owned learned row in the same game.")
        previous = row.number


def backup_transitions(episode, target_steps: TargetSteps):
    """Return sampled target views without changing stored one-step transitions.

    The horizon counts actual owned learned decisions across both phases, not engine commands
    or turns. Only the last actual row has a reward, so a terminal-truncated suffix receives
    that return exactly once. Otherwise its reward remains zero and Double-Q evaluates the
    actual endpoint's legal offers. The uncorrected intervening behavior prefix is off-policy;
    these targets are not claimed to be unbiased optimal-control returns.

    Explicit "terminal" views assign the complete episode's recorded return once per sampled
    transition and never bootstrap. They describe that recorded continuation, not a claim about
    the current greedy policy. Original one-step rows remain the calibration/storage authority.
    """
    validate_target_steps(target_steps)
    validate_episode(episode)
    if target_steps == 1:
        return episode.transitions
    if target_steps == "terminal":
        return tuple(replace(row, reward=episode.reward, successor=None) for row in episode.transitions)
    rows = episode.transitions
    result = []
    for index, row in enumerate(rows):
        end = min(index + target_steps, len(rows))
        result.append(replace(row, reward=episode.reward if end == len(rows) else 0.,
                              successor=None if end == len(rows) else rows[end-1].successor))
    return tuple(result)


class Replay:
    """Whole-game FIFO, capped by both phase row counts and number of retained games."""
    def __init__(self, row_capacity=16384, game_capacity=4096):
        b.integer(row_capacity, "Phase replay row capacity", 1, 16384)
        b.integer(game_capacity, "Replay game capacity", 1, 4096)
        self.row_capacity, self.game_capacity = row_capacity, game_capacity
        self.games = deque()
        self.prepared = {}
        self._sample_rows = None
        self._target_steps = 1
        self.seen_origins, self.seen_seeds = set(), set()
        self.evicted_games = 0

    def rows(self, phase):
        return [row for game in self.games for row in game.transitions if row.phase == phase]

    def counts(self):
        return {phase: len(self.rows(phase)) for phase in PHASES}

    def append(self, games, *, prepared=None):
        """Copy raw episodes; optionally retain their reader-owned immutable sparse inputs.

        Supplied tensors were built while validating these exact observations. They contain
        no model weights or gradients and must remain read-only. Raw observations are still
        defensively copied; current actor dictionary compatibility is checked by prepare().
        This cache is process-local and is never serialized or trusted instead of file checks.
        """
        games = list(games)
        for game in games:
            validate_episode(game)
            b.require(all(sum(row.phase == p for row in game.transitions) <= self.row_capacity for p in PHASES),
                      "A single game exceeds replay capacity; reject without partial retention.")
        b.require(len({x.origin for x in games}) == len(games) and len({x.gameSeed for x in games}) == len(games)
                  and not ({x.origin for x in games} & self.seen_origins)
                  and not ({x.gameSeed for x in games} & self.seen_seeds), "Replay origins/world seeds cannot be recollected.")
        incoming = {(row.origin,row.number):row for game in games for row in game.transitions}
        if prepared is not None:
            b.require(set(prepared) == set(incoming), "Prepared replay inputs must cover exactly the incoming owned rows.")
            for key,item in prepared.items():
                observation = incoming[key].observation
                b.require(isinstance(item,b._PreparedObservation)
                          and item.action_count == len(observation['actions'])
                          and item.baseline_index == observation['baselineIndex'],
                          "Prepared replay inputs must retain the actual offered-action layout.")
                b.require(not any(tensor.requires_grad for tensor in
                          (item.state_indices,item.state_values,item.action_indices,item.action_values)),
                          "Prepared replay features cannot retain a model gradient graph.")
        for game in games:
            self.games.append(copy.deepcopy(game))
            self.seen_origins.add(game.origin); self.seen_seeds.add(game.gameSeed)
            while len(self.games) > self.game_capacity or max(self.counts().values()) > self.row_capacity:
                self.games.popleft(); self.evicted_games += 1
        retained = {(row.origin,row.number) for game in self.games for row in game.transitions}
        self.prepared = {key:item for key,item in {**self.prepared,**(prepared or {})}.items()
                         if key in retained}
        self._sample_rows = None

    def prepare_backups(self, target_steps: TargetSteps):
        """Cache each same-game suffix once per replay/horizon; never rescan on minibatch draws."""
        validate_target_steps(target_steps)
        if self._sample_rows is not None and target_steps == self._target_steps:
            return
        views = {phase: [] for phase in PHASES}
        for game in self.games:
            for row in backup_transitions(game, target_steps):
                views[row.phase].append(row)
        self._target_steps, self._sample_rows = target_steps, views

    def prepare(self, actors):
        """Reuse validated sparse inputs; the existing dictionary guard still runs for every row."""
        self.prepared={(row.origin,row.number):actors[row.phase].encoder.prepare(
                           self.prepared.get((row.origin,row.number),row.observation))
                       for game in self.games for row in game.transitions}
        return sum(tensor.numel()*tensor.element_size() for item in self.prepared.values()
                   for tensor in (item.state_indices,item.state_values,item.action_indices,item.action_values))

    def sample(self, phase, count, rng):
        self.prepare_backups(self._target_steps)
        rows = self._sample_rows[phase]
        b.require(rows, "A positive-step phase requires retained replay transitions.")
        return [rows[rng.randrange(len(rows))] for _ in range(count)]


def snapshot(actors):
    return {phase: copy.deepcopy(actors[phase]).requires_grad_(False) for phase in PHASES}


def targets(rows, selectors, lagged, prepared=None):
    result = [row.reward for row in rows]
    b.require(all(0 <= row.reward <= 1 and (row.successor is None or row.reward == 0.
              and row.successor.origin == row.origin and row.successor.phase in PHASES) for row in rows),
              "Double-Q targets require terminal-only rewards and actual same-origin successors.")
    with b.torch.no_grad():
        for phase in PHASES:
            indices = [i for i, row in enumerate(rows) if row.successor is not None and row.successor.phase == phase]
            if not indices: continue
            observations = [rows[i].successor.observation for i in indices]
            inputs = [(prepared or {}).get((rows[i].origin,rows[i].successor.number),obs)
                      for i,obs in zip(indices,observations)]
            chosen = evaluate(selectors[phase], inputs)
            evaluated = evaluate(lagged[phase], inputs)
            for i, obs, online, target in zip(indices, observations, chosen, evaluated):
                result[i] = float(target[greedy_index(online, obs)])
    finite_values(result)
    return b.torch.tensor(result, dtype=b.DTYPE).detach()


def steps_for_rows(count, config):
    b.integer(count, "New phase row count", 0, 200000)
    return config.passesPerNewBatch * math.ceil(count/config.batchSize)


def fit_phase(actor, optimizer, replay, phase, selectors, lagged, rng, steps, config, deadline):
    losses = []
    for _ in range(steps):
        b.require(time.monotonic() < deadline, "The original Q update deadline expired.")
        rows = replay.sample(phase, config.batchSize, rng)
        y = targets(rows, selectors, lagged, replay.prepared)
        values = evaluate(actor, [replay.prepared.get((row.origin,row.number),row.observation) for row in rows])
        chosen = b.torch.stack([value[row.selected] for value, row in zip(values, rows)])
        loss = .5*(chosen-y).square().mean()
        b.require(bool(b.torch.isfinite(loss)), "Q minibatch loss must remain finite.")
        optimizer.zero_grad(set_to_none=True); loss.backward()
        b.nn.utils.clip_grad_norm_(actor.parameters(), config.maxGradientNorm, error_if_nonfinite=True)
        optimizer.step(); losses.append(float(loss.detach()))
    return dict(steps=len(losses), losses=losses)


def fit_pair(online, optimizers, lagged, replay, new_counts, update_number, config, deadline):
    config.validate()
    b.integer(update_number, "Joint Q update number", 1, 1000000)
    b.require(set(online) == set(optimizers) == set(lagged) == set(new_counts) == set(PHASES),
              "Both typed online/target/Adam views are required atomically.")
    replay.prepare_backups(config.targetSteps)
    selectors, evaluators = snapshot(online), snapshot(lagged)
    pins = {p: (b.digest(selectors[p].artifact()), b.digest(evaluators[p].artifact())) for p in PHASES}
    candidates = {p: copy.deepcopy(online[p]) for p in PHASES}
    next_optimizers, reports = {}, {}
    for index, phase in enumerate(PHASES):
        optimizer = b.torch.optim.Adam(candidates[phase].parameters(), lr=config.learningRate, foreach=False)
        optimizer.load_state_dict(copy.deepcopy(optimizers[phase].state_dict()))
        next_optimizers[phase] = optimizer
        rng = random.Random(config.seed*1000000+update_number*100+index)
        reports[phase] = fit_phase(candidates[phase], optimizer, replay, phase, selectors, evaluators,
                                  rng, steps_for_rows(new_counts[phase], config), config, deadline)
    b.require(time.monotonic() < deadline, "The original Q deadline expired before pair publication.")
    b.require(pins == {p: (b.digest(selectors[p].artifact()), b.digest(evaluators[p].artifact())) for p in PHASES},
              "Both online-selector and target snapshots must stay frozen across both phase fits.")
    next_targets = snapshot(candidates) if update_number % config.targetSyncEveryUpdates == 0 else snapshot(lagged)
    return candidates, next_optimizers, next_targets, reports


def numerical_guard(online, targets, replay, config, deadline):
    """Run acceptance only: no clipping, target relabeling or optimizer changes.

    All offered values on retained/new inputs are checked. Calibration is reported only for
    the selected action of actual terminal transitions, never earlier off-policy Q values.
    """
    started=time.monotonic();result={};passed=True
    with b.torch.no_grad():
        for label,models in (('online',online),('target',targets)):
            result[label]={}
            for phase in PHASES:
                rows=replay.rows(phase);count=outside=terminal_count=0;terminal_error=0.
                minimum=maximum=None;tails={str(t):0 for t in (1,2,5,10)}
                for offset in range(0,len(rows),16):
                    b.require(time.monotonic()<deadline,'Q numerical guard exhausted the original update deadline.')
                    batch=rows[offset:offset+16]
                    predictions=evaluate(models[phase],[replay.prepared.get((row.origin,row.number),row.observation) for row in batch])
                    for row,values in zip(batch,predictions):
                        lo,hi=float(values.min()),float(values.max())
                        minimum=lo if minimum is None else min(minimum,lo)
                        maximum=hi if maximum is None else max(maximum,hi)
                        count+=values.numel();outside+=int(((values<0)|(values>1)).sum())
                        for threshold in tails:tails[threshold]+=int((values.abs()>int(threshold)).sum())
                        passed=passed and max(abs(lo),abs(hi))<=config.maximumAbsQ
                        if row.successor is None:
                            terminal_count+=1;terminal_error+=(float(values[row.selected])-row.reward)**2
                result[label][phase]=dict(count=count,minimum=minimum,maximum=maximum,
                    outsideUnitFraction=outside/count if count else None,absoluteTailCounts=tails,
                    terminalSelectedCount=terminal_count,
                    terminalSelectedMse=terminal_error/terminal_count if terminal_count else None)
    return dict(passed=passed,seconds=time.monotonic()-started,maximumAbsQ=config.maximumAbsQ,values=result)
