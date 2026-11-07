"""Physical-card exploration reference, exact zero compatibility, and update identity checks."""

import copy
from dataclasses import asdict, replace
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import card_policy as card
from card_policy_toy import SCHEMA, observation
import purchase_policy as backend
from test_card_policy import behavior_row, collection


def original_loss(policy, rows, config):
    """Pre-regularizer arithmetic, retained as an exact zero-path regression oracle."""
    probabilities, values = policy([row.get("_prepared", row["observation"]) for row in rows])
    chosen = torch.stack([logp[row["selectedIndex"]] for logp, row in zip(probabilities, rows)])
    old = torch.tensor([row["behaviorLogProbability"] for row in rows], dtype=backend.DTYPE)
    returns = torch.tensor([row["return"] for row in rows], dtype=backend.DTYPE)
    advantages = returns - torch.tensor([row["behaviorValue"] for row in rows], dtype=backend.DTYPE)
    actor = backend.clipped_policy_loss(chosen, old, advantages, config.clip).mean()
    critic = 0.5 * (values - returns).square().mean()
    entropy = torch.stack([-(value.exp() * value).sum() for value in probabilities]).mean()
    return actor + config.valueCoefficient * critic - config.entropyCoefficient * entropy


def reference(commands):
    return torch.tensor(backend.card_reference_probabilities(commands), dtype=backend.DTYPE)


class CardReferenceTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)

    def test_each_physical_card_and_offered_pass_receive_equal_mass(self):
        commands = ["pass", "play hand-1", "play hand-1 protect 0", "play hand-1 player p2",
                    "play hand-2", "play hand-2 player p3"]
        self.assertEqual(backend.card_reference_probabilities(commands),
                         [1 / 3, 1 / 9, 1 / 9, 1 / 9, 1 / 6, 1 / 6])

    def test_two_physical_copies_of_the_same_definition_remain_distinct(self):
        state = observation(0)
        state["actions"] = [
            {"command": "pass", "features": {}},
            {"command": "play hand-1", "features": {"card.wool": 1}},
            {"command": "play hand-2", "features": {"card.wool": 1}},
            {"command": "play hand-2 protect 5", "features": {"card.wool": 1}}]
        self.assertEqual(card.reference_distribution(state), [1 / 3, 1 / 3, 1 / 6, 1 / 6])

    def test_reference_never_invents_pass_or_uses_baseline_recommendation(self):
        state = observation(0)
        state["actions"] = [{"command": "play hand-a", "features": {}},
                            {"command": "play hand-b", "features": {}},
                            {"command": "play hand-b protect 5", "features": {}}]
        first = card.reference_distribution(state)
        state["baselineIndex"] = 2
        self.assertEqual(first, [0.5, 0.25, 0.25])
        self.assertEqual(first, card.reference_distribution(state))

    def test_only_pass_has_zero_kl_and_zero_gradient(self):
        logits = torch.tensor([3.0], dtype=backend.DTYPE, requires_grad=True)
        loss = backend.reference_kl(logits.log_softmax(0), reference(["pass"]))
        loss.backward()
        self.assertEqual(float(loss.detach()), 0)
        self.assertEqual(logits.grad.tolist(), [0])

    def test_invalid_or_duplicate_commands_are_rejected(self):
        for commands in ([], ["pass", "pass"], ["pass later"], ["play"], ["play 2"],
                         ["play hand-1\npass"], ["buy-advance N"], ["pass"] * 1025):
            with self.subTest(commands=commands[:2]), self.assertRaises(ValueError):
                backend.card_reference_probabilities(commands)

    def test_purchase_observation_cannot_request_card_reference(self):
        state = {**observation(0), "encoderVersion": backend.ENCODER_VERSION}
        with self.assertRaisesRegex(ValueError, "card observation"):
            card.reference_distribution(state)

    def test_finite_difference_matches_corrective_gradient_at_rare_actions(self):
        commands = ["pass", "play hand-a", "play hand-a player p2", "play hand-b"]
        target = reference(commands)
        for values in ([0.3, -0.1, 1.4, 0.0], [0.0, -60.0, -50.0, -40.0]):
            with self.subTest(values=values):
                logits = torch.tensor(values, dtype=backend.DTYPE, requires_grad=True)
                loss = 0.1 * backend.reference_kl(logits.log_softmax(0), target)
                loss.backward()
                expected = 0.1 * (logits.detach().softmax(0) - target)
                torch.testing.assert_close(logits.grad, expected, rtol=0, atol=2e-17)
                epsilon = 1e-5
                numerical = []
                for index in range(len(values)):
                    left, right = logits.detach().clone(), logits.detach().clone()
                    left[index] -= epsilon
                    right[index] += epsilon
                    numerical.append(float(0.1 * (
                        backend.reference_kl(right.log_softmax(0), target)
                        - backend.reference_kl(left.log_softmax(0), target)) / (2 * epsilon)))
                torch.testing.assert_close(logits.grad, torch.tensor(numerical, dtype=backend.DTYPE),
                                           rtol=0, atol=2e-10)
                self.assertTrue(bool(torch.isfinite(logits.grad).all()))

    def test_equivalent_variant_splitting_preserves_group_corrective_gradient(self):
        results = []
        for variants in (1, 2, 54):
            commands = ["pass"] + [f"play hand-a protect {index}" for index in range(variants)]
            # Preserve group probability when splitting equivalent alternatives. Equal unadjusted
            # logits would change the unchanged actor's group probability, so are not equivalent.
            logits = torch.tensor([0.7] + [-0.2 - math.log(variants)] * variants,
                                  dtype=backend.DTYPE, requires_grad=True)
            target = reference(commands)
            (0.1 * backend.reference_kl(logits.log_softmax(0), target)).backward()
            self.assertEqual(float(target[0]), 0.5)
            self.assertAlmostEqual(float(target[1:].sum()), 0.5, places=14)
            results.append(torch.stack([logits.grad[0], logits.grad[1:].sum()]))
        for result in results[1:]:
            torch.testing.assert_close(result, results[0], rtol=0, atol=2e-16)

    def test_singleton_groups_match_passed_uniform_toy_objective(self):
        logits = torch.tensor([1.7, -3.2], dtype=backend.DTYPE, requires_grad=True)
        logp = logits.log_softmax(0)
        actual = backend.reference_kl(logp, reference(["pass", "play hand-1"]))
        expected = -(logp.mean() + math.log(2))
        torch.testing.assert_close(actual, expected, rtol=0, atol=1e-15)


class CardReferenceLossTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.model = card.initialize(SCHEMA, 17)
        self.rows = [{**behavior_row(self.model, observation(index % 2), index % 2),
                      "return": float(index % 2)} for index in range(4)]

    def test_zero_default_loss_gradients_and_adam_updates_are_exact_for_both_contracts(self):
        for contract in (card.CONTRACT, backend.PURCHASE_CONTRACT):
            with self.subTest(contract=contract.label):
                model = {**self.model, "encoderVersion": contract.encoder}
                rows = copy.deepcopy(self.rows)
                for row, count in zip(rows, (1, 2, 7, 3)):
                    row["observation"]["actions"] = [
                        {"command": "pass", "features": {"pass": 1.0}}] + [
                        {"command": f"play hand-{index}", "features": {"play": 1.0}}
                        for index in range(count - 1)]
                    row["observation"]["encoderVersion"] = contract.encoder
                    row["selectedIndex"] = min(row["selectedIndex"], count - 1)
                    row["behaviorLogProbability"] = math.log(backend.prior_probabilities(
                        count, row["observation"]["baselineIndex"])[row["selectedIndex"]])
                old, new = [backend.ResidualPolicy(model, contract) for _ in range(2)]
                optimizers = [torch.optim.Adam(actor.parameters(), lr=0.0003, foreach=False)
                              for actor in (old, new)]
                config = backend.UpdateConfig(entropyCoefficient=0.03)
                for _ in range(3):
                    for optimizer in optimizers:
                        optimizer.zero_grad(set_to_none=True)
                    expected = original_loss(old, rows, config)
                    with patch.object(backend, "card_reference_probabilities",
                                      side_effect=AssertionError("zero path grouped commands")):
                        actual = backend.loss_for_rows(new, rows, config)
                    torch.testing.assert_close(actual, expected, rtol=0, atol=0)
                    expected.backward()
                    actual.backward()
                    for left, right in zip(old.parameters(), new.parameters()):
                        torch.testing.assert_close(left.grad, right.grad, rtol=0, atol=0)
                    for optimizer in optimizers:
                        optimizer.step()
                self.assertEqual(old.artifact(), new.artifact())
                self.assertEqual(backend.encode_optimizer(optimizers[0]),
                                 backend.encode_optimizer(optimizers[1]))

    def test_positive_loss_is_mean_over_decisions_and_cache_is_exact(self):
        rows = copy.deepcopy(self.rows[:2])
        rows[0]["observation"]["actions"].append(
            {"command": "play hand-1 protect 5", "features": {"play": 1.0}})
        actor = card.CardPolicy(self.model)
        config = backend.UpdateConfig(cardReferenceKlCoefficient=0.1)
        actual = backend.loss_for_rows(actor, rows, config)
        logps, _ = actor([row["observation"] for row in rows])
        penalties = [backend.reference_kl(logp, reference(
            [action["command"] for action in row["observation"]["actions"]]))
            for logp, row in zip(logps, rows)]
        expected = original_loss(actor, rows, config) + 0.1 * torch.stack(penalties).mean()
        torch.testing.assert_close(actual, expected, rtol=0, atol=0)
        for row in rows:
            row["_cardReference"] = reference(
                [action["command"] for action in row["observation"]["actions"]])
        with patch.object(backend, "card_reference_probabilities",
                          side_effect=AssertionError("cached reference recomputed")):
            torch.testing.assert_close(backend.loss_for_rows(actor, rows, config), actual,
                                       rtol=0, atol=0)

    def test_reference_gradient_reaches_zero_actor_head_without_reward_advantage(self):
        actor = card.CardPolicy(self.model)
        rows = [{**row, "return": row["behaviorValue"]} for row in self.rows]
        config = backend.UpdateConfig(valueCoefficient=0, cardReferenceKlCoefficient=0.1)
        backend.loss_for_rows(actor, rows, config).backward()
        self.assertGreater(float(actor.actorWeights.grad.norm()), 0.001)
        self.assertTrue(bool(torch.isfinite(actor.actorWeights.grad).all()))

    def test_positive_reference_cannot_enter_purchase_loss(self):
        model = {**self.model, "encoderVersion": backend.ENCODER_VERSION}
        with self.assertRaisesRegex(ValueError, "only for card"):
            backend.loss_for_rows(backend.PurchasePolicy(model), [],
                                  backend.UpdateConfig(cardReferenceKlCoefficient=0.1))

    def test_reference_config_defaults_and_version_are_explicit(self):
        config = backend.UpdateConfig(**{"epochs": 2})
        self.assertEqual(config.cardReferenceKlCoefficient, 0)
        self.assertEqual(asdict(config)["cardReferenceVersion"], card.EXPLORATION_REFERENCE_VERSION)
        for changes in ({"cardReferenceVersion": "unknown"},
                        {"cardReferenceKlCoefficient": -0.1},
                        {"cardReferenceKlCoefficient": float("nan")},
                        {"cardReferenceKlCoefficient": 1.1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                replace(config, **changes).validate(card.CONTRACT)


class CardReferenceUpdateTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model_path = self.root / "model.json"
        backend.atomic_json(self.model_path, card.initialize(SCHEMA, 17))
        self.data = self.root / "collection"
        collection(self.data, self.model_path)
        self.config = backend.UpdateConfig(epochs=2, batchSize=2, maxBehaviorKl=0.2,
                                           cardReferenceKlCoefficient=0.1)

    def test_positive_purchase_update_fails_before_input_io(self):
        with patch.object(backend, "read_json", side_effect=AssertionError("read input")):
            with self.assertRaisesRegex(ValueError, "only for card"):
                backend.update("missing-model", "missing-data", self.config, self.root / "bad")

    def test_positive_card_update_resume_is_exact_and_pins_reference(self):
        output = self.root / "update"
        result = card.update(self.model_path, self.data, self.config, output)
        policy = (output / "policy.json").read_bytes()
        checkpoint = backend.read_checkpoint(output / "checkpoint.json")
        resumed = card.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual(result, resumed)
        self.assertEqual(policy, (output / "policy.json").read_bytes())
        self.assertEqual(checkpoint, backend.read_checkpoint(output / "checkpoint.json"))
        self.assertEqual(backend.read_json(output / "identity.json")["config"], asdict(self.config))
        self.assertEqual(checkpoint["epoch"], 2)

    def test_resume_rejects_changed_coefficient_without_replacing_checkpoint(self):
        output = self.root / "update"
        card.update(self.model_path, self.data, self.config, output)
        before = (output / "checkpoint.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "configuration"):
            card.update(self.model_path, self.data, replace(self.config, cardReferenceKlCoefficient=0),
                        output, resume=True)
        self.assertEqual(before, (output / "checkpoint.json").read_bytes())

    def test_carry_uses_previous_adam_state_with_unchanged_sampling_contract(self):
        first = self.root / "first"
        card.update(self.model_path, self.data, self.config, first)
        previous = backend.read_checkpoint(first / "checkpoint.json")
        second_data = self.root / "second-collection"
        collection(second_data, first / "policy.json")
        second = self.root / "second"
        original_fit = backend.fit_epoch
        first_optimizer = []

        def checked_fit(policy, optimizer, rows, config, epoch, deadline):
            if not first_optimizer:
                self.assertEqual(policy.artifact(), previous["model"])
                first_optimizer.append(backend.encode_optimizer(optimizer))
            return original_fit(policy, optimizer, rows, config, epoch, deadline)

        with patch.object(backend, "fit_epoch", side_effect=checked_fit):
            card.update(first / "policy.json", second_data, self.config, second,
                        optimizer_from=first / "checkpoint.json")
        self.assertEqual(first_optimizer[0], previous["optimizer"])
        identity = backend.read_json(second / "identity.json")
        self.assertEqual(identity["optimizerFromSha256"], backend.file_digest(first / "checkpoint.json"))
        self.assertEqual(backend.read_checkpoint(second / "checkpoint.json")["epoch"], 2)


if __name__ == "__main__":
    unittest.main()
