"""Predeclared conditional delayed-credit check, not a game-strength experiment.

Both visible contexts offer identical actions with the same baseline recommendation. Their
correct first actions are opposite; the second decision must complete the sequence to earn a
terminal reward. After learning, the two context payoffs reverse. There is no intermediate reward.
"""

import math
import random
import time

import torch

import card_policy as card
import purchase_policy as backend


SCHEMA = {"encoderVersion": card.ENCODER_VERSION,
          "stateFeatureNames": ["after-pass", "after-play", "context", "root"],
          "actionFeatureNames": ["pass", "play"]}
SEEDS = (7, 19, 41)
UPDATES_PER_DIRECTION = 96
EPISODES_PER_UPDATE = 256
MINIMUM_SEQUENCE_PROBABILITY = 0.7


def observation(context, stage="root"):
    return {"encoderVersion": card.ENCODER_VERSION,
            "stateFeatures": {stage: 1.0, "context": float(2 * context - 1)},
            "baselineIndex": 0,
            "actions": [{"command": "pass", "features": {"pass": 1.0}},
                        {"command": "play hand-1", "features": {"play": 1.0}}]}


def delayed_rows(actor, seed, reversed_payoff):
    rng = random.Random(seed)
    rows = []
    states = [observation(context, stage) for context in (0, 1)
              for stage in ("root", "after-pass", "after-play")]
    with torch.no_grad():
        logp, values = actor(states)
        for episode in range(EPISODES_PER_UPDATE):
            context = episode % 2
            root = context * 3
            first = backend.select(logp[root].exp().tolist(), rng.random())
            child = root + first + 1
            second = backend.select(logp[child].exp().tolist(), rng.random())
            target = 1 - context if reversed_payoff else context
            reward = float(first == target and second == 0)
            for index, selected in ((root, first), (child, second)):
                rows.append({"observation": states[index], "selectedIndex": selected,
                             "behaviorLogProbability": float(logp[index][selected]),
                             "behaviorValue": float(values[index]), "return": reward,
                             "intermediateReward": 0.0, "originIndex": episode})
    return rows


def sequence_probabilities(actor, reversed_payoff):
    result = []
    with torch.no_grad():
        for context in (0, 1):
            target = 1 - context if reversed_payoff else context
            child = "after-play" if target else "after-pass"
            logp, _ = actor([observation(context), observation(context, child)])
            result.append(float(logp[0][target].exp() * logp[1][0].exp()))
    return result


def run_toy():
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    config = backend.UpdateConfig(learningRate=0.003, epochs=1, batchSize=64,
                                  valueCoefficient=0.5, maxBehaviorKl=0.2)
    started = time.monotonic()
    results = []
    for seed in SEEDS:
        actor = card.CardPolicy(card.initialize(SCHEMA, seed))
        optimizer = torch.optim.Adam(actor.parameters(), lr=config.learningRate, foreach=False)
        initial = sequence_probabilities(actor, False)
        for iteration in range(UPDATES_PER_DIRECTION):
            rows = delayed_rows(actor, seed * 10000 + iteration, False)
            backend.fit_epoch(actor, optimizer, rows, config, iteration, math.inf)
        learned = sequence_probabilities(actor, False)
        before_reversal = sequence_probabilities(actor, True)
        for iteration in range(UPDATES_PER_DIRECTION, 2 * UPDATES_PER_DIRECTION):
            rows = delayed_rows(actor, seed * 10000 + iteration, True)
            backend.fit_epoch(actor, optimizer, rows, config, iteration, math.inf)
        reversed_probabilities = sequence_probabilities(actor, True)
        passed = all(value > MINIMUM_SEQUENCE_PROBABILITY
                     for value in learned + reversed_probabilities)
        results.append({"seed": seed, "initialByContext": initial,
                        "learnedByContext": learned, "beforeReversalByContext": before_reversal,
                        "reversedByContext": reversed_probabilities, "passed": passed})
    return {"schemaVersion": 1, "kind": "conditional-delayed-card-numerical-check",
            "seeds": list(SEEDS), "updatesPerDirection": UPDATES_PER_DIRECTION,
            "episodesPerUpdate": EPISODES_PER_UPDATE, "intermediateRewards": 0,
            "minimumSequenceProbability": MINIMUM_SEQUENCE_PROBABILITY,
            "learningRate": config.learningRate, "clip": config.clip,
            "note": "Conditional credit-flow check only; no game-strength evidence.",
            "elapsedSeconds": time.monotonic() - started, "results": results,
            "passed": all(result["passed"] for result in results)}
