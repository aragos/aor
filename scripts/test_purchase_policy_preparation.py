"""Exact old/new tensor, forward, gradient and optimizer parity for sparse preparation."""

import copy
import math
import random
import unittest
from unittest.mock import patch

import torch

import purchase_policy as learner


def legacy_inputs(policy, observations):
    """Frozen pre-optimization cell assignments; independent of the new preparation code."""
    count = sum(len(observation["actions"]) for observation in observations)
    state = torch.zeros((len(observations), len(policy.state_indices)), dtype=learner.DTYPE)
    action = torch.zeros((count, len(policy.action_indices)), dtype=learner.DTYPE)
    owners = []
    cursor = 0
    for row, observation in enumerate(observations):
        learner.validate_observation(observation, policy.template)
        for name, value in observation["stateFeatures"].items():
            state[row, policy.state_indices[name]] = value
        for candidate in observation["actions"]:
            for name, value in candidate["features"].items():
                action[cursor, policy.action_indices[name]] = value
            owners.append(row)
            cursor += 1
    return state, action, torch.tensor(owners)


class LegacyPolicy(learner.PurchasePolicy):
    def forward(self, observations):
        state, action, owners = legacy_inputs(self, observations)
        hidden = torch.tanh(state @ self.stateWeights.T + self.stateBias)
        action_hidden = torch.tanh(
            hidden[owners] + action @ self.actionWeights.T + self.actionBias)
        residuals = torch.clamp(action_hidden @ self.actorWeights,
                                -learner.RESIDUAL_LIMIT, learner.RESIDUAL_LIMIT)
        log_probabilities = []
        cursor = 0
        for observation in observations:
            size = len(observation["actions"])
            residual = residuals[cursor:cursor + size]
            prior = torch.tensor(learner.prior_probabilities(size, observation["baselineIndex"]),
                                 dtype=learner.DTYPE).log()
            log_probability = torch.log_softmax(prior + residual, dim=0)
            if bool(torch.all(residual == residual[0])):
                log_probability = log_probability + (prior - log_probability).detach()
            log_probabilities.append(log_probability)
            cursor += size
        values = torch.sigmoid(hidden @ self.valueWeights + self.valueBias)
        return log_probabilities, values


def fixtures():
    rng = random.Random(76101)
    schema = {"encoderVersion": learner.ENCODER_VERSION,
              "stateFeatureNames": [f"state.{index}" for index in range(7)],
              "actionFeatureNames": [f"action.{index}" for index in range(5)]}
    model = learner.initialize(schema, 76101)
    for name in learner.PARAMETERS:
        model[name] = (rng.uniform(-0.2, 0.2) if name == "valueBias"
                       else [rng.uniform(-0.2, 0.2) for _ in model[name]])
    observations = []
    for index in range(64):
        size = (1, 2, 4, 13)[index % 4]
        state = {name: rng.uniform(-2, 2) for name in schema["stateFeatureNames"]
                 if rng.random() > 0.3}
        if index % 5 == 0:
            state["state.0"] = -0.0
        if index % 11 == 0:
            state = {}
        actions = [{"command": f"offer-{action:03}",
                    "features": {name: rng.uniform(-2, 2) for name in schema["actionFeatureNames"]
                                 if rng.random() > 0.4}} for action in range(size)]
        observations.append({"encoderVersion": learner.ENCODER_VERSION,
                             "stateFeatures": state, "actions": actions,
                             "baselineIndex": rng.randrange(size)})
    return model, observations


def rows_from(policy, observations):
    with torch.no_grad():
        logps, values = policy(observations)
    return [{"observation": observation, "selectedIndex": index % len(observation["actions"]),
             "behaviorLogProbability": float(logp[index % len(observation["actions"])]),
             "behaviorValue": float(value), "return": (index % 3) / 2}
            for index, (observation, logp, value) in enumerate(zip(observations, logps, values))]


class PreparationParityTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        self.model, self.observations = fixtures()

    def test_tensor_bits_and_forward_match_for_varied_batch_sizes_and_orders(self):
        actor = learner.PurchasePolicy(self.model)
        legacy = LegacyPolicy(self.model)
        order = list(range(len(self.observations)))
        random.Random(76102).shuffle(order)
        for indices in (list(range(64)), list(reversed(range(64))), order):
            for size in (1, 2, 7, 16, 64):
                with self.subTest(size=size, first=indices[0]):
                    observations = [self.observations[index] for index in indices[:size]]
                    prepared = [actor.prepare(observation) for observation in observations]
                    old_inputs = legacy_inputs(legacy, observations)
                    new_inputs = actor._batch_inputs(prepared)
                    for old, new in zip(old_inputs, new_inputs):
                        self.assertTrue(torch.equal(old.view(torch.int64), new.view(torch.int64)))
                    old_logp, old_value = legacy(observations)
                    new_logp, new_value = actor(prepared)
                    self.assertTrue(torch.equal(old_value, new_value))
                    self.assertTrue(all(torch.equal(old, new)
                                        for old, new in zip(old_logp, new_logp)))

    def test_feature_insertion_order_and_empty_maps_preserve_tensor_bits(self):
        actor = learner.PurchasePolicy(self.model)
        observations = copy.deepcopy(self.observations[:7])
        for observation in observations:
            observation["stateFeatures"] = dict(reversed(list(observation["stateFeatures"].items())))
            for action in observation["actions"]:
                action["features"] = dict(reversed(list(action["features"].items())))
        observations.append({"encoderVersion": learner.ENCODER_VERSION, "stateFeatures": {},
                             "actions": [{"command": "pass", "features": {}}],
                             "baselineIndex": 0})
        prepared = [actor.prepare(observation) for observation in observations]
        for original, packed in zip(legacy_inputs(actor, observations), actor._batch_inputs(prepared)):
            self.assertTrue(torch.equal(original.view(torch.int64), packed.view(torch.int64)))
        old_logp, old_value = LegacyPolicy(self.model)(observations)
        new_logp, new_value = actor(prepared)
        self.assertTrue(torch.equal(old_value, new_value))
        self.assertTrue(all(torch.equal(old, new) for old, new in zip(old_logp, new_logp)))

    def test_loss_and_all_parameter_gradients_match_exactly(self):
        for size in (1, 2, 7, 32, 64):
            with self.subTest(size=size):
                actor = learner.PurchasePolicy(self.model)
                legacy = LegacyPolicy(self.model)
                observations = list(reversed(self.observations))[:size]
                rows = rows_from(legacy, observations)
                prepared = [{**row, "_prepared": actor.prepare(row["observation"])} for row in rows]
                config = learner.UpdateConfig(entropyCoefficient=0.03)
                old_loss = learner.loss_for_rows(legacy, rows, config)
                new_loss = learner.loss_for_rows(actor, prepared, config)
                self.assertTrue(torch.equal(old_loss, new_loss))
                old_loss.backward()
                new_loss.backward()
                for old, new in zip(legacy.parameters(), actor.parameters()):
                    self.assertTrue(torch.equal(old.grad, new.grad))

    def test_multiple_shuffled_epochs_preserve_model_adam_and_metrics_exactly(self):
        legacy = LegacyPolicy(self.model)
        actor = learner.PurchasePolicy(self.model)
        rows = rows_from(legacy, self.observations)
        prepared = [{**row, "_prepared": actor.prepare(row["observation"])} for row in rows]
        config = learner.UpdateConfig(batchSize=7, epochs=3, seed=76103)
        old_optimizer = torch.optim.Adam(legacy.parameters(), lr=config.learningRate, foreach=False)
        new_optimizer = torch.optim.Adam(actor.parameters(), lr=config.learningRate, foreach=False)
        for epoch in range(config.epochs):
            learner.fit_epoch(legacy, old_optimizer, rows, config, epoch, math.inf)
            learner.fit_epoch(actor, new_optimizer, prepared, config, epoch, math.inf)
        self.assertEqual(legacy.artifact(), actor.artifact())
        self.assertEqual(learner.encode_optimizer(old_optimizer), learner.encode_optimizer(new_optimizer))
        old_metrics = learner.behavior_metrics(legacy, LegacyPolicy(self.model), rows, batch_size=7)
        new_metrics = learner.behavior_metrics(actor, learner.PurchasePolicy(self.model),
                                               prepared, batch_size=7)
        self.assertEqual(old_metrics, new_metrics)

    def test_prepared_inputs_are_snapshots_and_reusable_across_matching_models(self):
        actor = learner.PurchasePolicy(self.model)
        raw = copy.deepcopy(self.observations[1])
        prepared = actor.prepare(raw)
        expected, expected_value = actor([prepared])
        raw["stateFeatures"]["unknown"] = 1.0
        with patch.object(learner, "validate_observation", side_effect=AssertionError("revalidated")):
            actual, value = learner.PurchasePolicy(self.model)([prepared])
        self.assertTrue(torch.equal(expected_value, value))
        self.assertTrue(torch.equal(expected[0], actual[0]))

    def test_foreign_dictionary_is_rejected_before_tensor_assembly(self):
        actor = learner.PurchasePolicy(self.model)
        prepared = actor.prepare(self.observations[0])
        changed = copy.deepcopy(self.model)
        changed["stateFeatureNames"][-1] = "state.foreign"
        with self.assertRaisesRegex(ValueError, "different feature dictionary"):
            learner.PurchasePolicy(changed)([prepared])

    def test_preparation_storage_scales_with_entries_not_dense_dictionary(self):
        model = learner.initialize({"encoderVersion": learner.ENCODER_VERSION,
                                    "stateFeatureNames": [f"s{index:04}" for index in range(8192)],
                                    "actionFeatureNames": [f"a{index:03}" for index in range(512)]}, 1)
        actor = learner.PurchasePolicy(model)
        raw = {"encoderVersion": learner.ENCODER_VERSION, "stateFeatures": {"s0001": 1.0},
               "actions": [{"command": "pass", "features": {"a001": 1.0}}], "baselineIndex": 0}
        prepared = actor.prepare(raw)
        self.assertEqual(prepared.state_values.numel(), 1)
        self.assertEqual(prepared.action_values.numel(), 1)


if __name__ == "__main__":
    unittest.main()
