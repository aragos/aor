"""Fixed delayed-reward numerical smoke; this is not a game-strength experiment."""

import math
import random
import time

import torch

import purchase_policy as learner


SCHEMA = {"encoderVersion": learner.ENCODER_VERSION,
          "stateFeatureNames": ["after-left", "after-right", "root"],
          "actionFeatureNames": ["cost", "left", "right"]}
SEEDS = (7, 19, 41)
UPDATES_PER_DIRECTION = 64
EPISODES_PER_UPDATE = 256


def observation(stage="root", baseline=0):
    return {"encoderVersion": learner.ENCODER_VERSION,
            "stateFeatures": {stage: 1.0}, "baselineIndex": baseline,
            "actions": [{"command": "left", "features": {"left": 1.0}},
                        {"command": "right", "features": {"right": 1.0}}]}


def delayed_rows(actor, seed, target):
    """Only completing both choices pays; every recorded intermediate reward is zero."""
    rng = random.Random(seed)
    rows = []
    with torch.no_grad():
        root = observation()
        root_logp, root_value = actor([root])
        root_probabilities = root_logp[0].exp().tolist()
        children = [observation("after-left"), observation("after-right")]
        child_logp, child_values = actor(children)
        for episode in range(EPISODES_PER_UPDATE):
            first = learner.select(root_probabilities, rng.random())
            second = learner.select(child_logp[first].exp().tolist(), rng.random())
            reward = float(first == target and second == 0)
            for state, choice, logp, value in (
                    (root, first, root_logp[0], root_value[0]),
                    (children[first], second, child_logp[first], child_values[first])):
                rows.append({"observation": state, "selectedIndex": choice,
                             "behaviorLogProbability": float(logp[choice]),
                             "behaviorValue": float(value), "return": reward,
                             "intermediateReward": 0.0, "originIndex": episode})
    return rows


def sequence_probability(actor, target):
    with torch.no_grad():
        child = observation("after-left" if target == 0 else "after-right")
        logp, _ = actor([observation(), child])
    return float(logp[0][target].exp() * logp[1][0].exp())


def run_toy():
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    config = learner.UpdateConfig(learningRate=0.003, epochs=1, batchSize=64,
                                   valueCoefficient=0.5, maxBehaviorKl=0.2)
    started = time.monotonic()
    results = []
    for seed in SEEDS:
        actor = learner.PurchasePolicy(learner.initialize(SCHEMA, seed))
        optimizer = torch.optim.Adam(actor.parameters(), lr=config.learningRate, foreach=False)
        initial = sequence_probability(actor, target=1)
        for iteration in range(UPDATES_PER_DIRECTION):
            rows = delayed_rows(actor, seed * 10000 + iteration, target=1)
            learner.fit_epoch(actor, optimizer, rows, config, iteration, math.inf)
        learned = sequence_probability(actor, target=1)
        before_reversal = sequence_probability(actor, target=0)
        for iteration in range(UPDATES_PER_DIRECTION, 2 * UPDATES_PER_DIRECTION):
            rows = delayed_rows(actor, seed * 10000 + iteration, target=0)
            learner.fit_epoch(actor, optimizer, rows, config, iteration, math.inf)
        reversed_probability = sequence_probability(actor, target=0)
        passed = (learned > 0.8 and learned > initial + 0.7
                  and reversed_probability > 0.8
                  and reversed_probability > before_reversal + 0.7)
        results.append({"seed": seed, "initialSequenceProbability": initial,
                        "learnedSequenceProbability": learned,
                        "beforeReversalProbability": before_reversal,
                        "reversedSequenceProbability": reversed_probability, "passed": passed})
    return {"schemaVersion": 1, "kind": "delayed-sequence-numerical-check",
            "seeds": list(SEEDS), "updatesPerDirection": UPDATES_PER_DIRECTION,
            "episodesPerUpdate": EPISODES_PER_UPDATE, "intermediateRewards": 0,
            "learningRate": config.learningRate, "clip": config.clip,
            "note": "Tests updater credit flow, not full-game strength or the collection protocol.",
            "elapsedSeconds": time.monotonic() - started, "results": results,
            "passed": all(result["passed"] for result in results)}
