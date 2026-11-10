"""Numerical, delayed-credit and artifact-boundary tests for purchase learning."""

import copy
from contextlib import redirect_stdout
from dataclasses import replace
import io
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import purchase_policy as learner
from purchase_policy_toy import SCHEMA, observation, run_toy


def behavior_row(model, state, selected, reward=1.0, number=0):
    prediction = learner.reference_forward(model, state)
    seed = next(seed for seed in range(10000)
                if learner.select(prediction["probabilities"], learner.selection_uniform(seed))
                == selected)
    return {"number": number, "actorSeed": seed, "observation": state,
            "selectedIndex": selected,
            "behaviorLogProbability": math.log(prediction["probabilities"][selected]),
            "behaviorValue": prediction["value"], "return": reward}


def collection(directory, model_path, games=4):
    directory.mkdir()
    model = learner.read_json(model_path)
    model_sha = learner.file_digest(model_path)
    common = {"signature": "fixture-signature", "modelSha256": model_sha,
              "encoderVersion": learner.ENCODER_VERSION, "actorVersion": learner.ACTOR_VERSION}
    learner.atomic_json(directory / "collection.json", {
        **common, "mode": "on-policy", "games": games,
        "seeds": list(range(games)), "configuration": "fixture",
        "stateFeatureNames": model["stateFeatureNames"],
        "actionFeatureNames": model["actionFeatureNames"]})
    learner.atomic_json(directory / "state.json", {**common, "phase": "complete"})
    (directory / "policy.json").write_bytes(model_path.read_bytes())
    for index in range(games):
        reward = float(index % 2)
        learner.atomic_json(directory / f"game-{index}.json", {
            **common, "index": index, "configuration": "fixture", "gameSeed": index,
            "controlledSeat": index % 4, "opponentIndices": [0, 1, 2, 3],
            "decisions": [behavior_row(model, observation(), index % 2, reward)],
            "terminal": {"reward": reward, "winShare": reward, "rankReward": reward,
                         "scores": [], "gameDecisions": 1}})
    learner.atomic_json(directory / "finished.json", {
        **common, "games": games,
        "gameSha256": [learner.file_digest(directory / f"game-{index}.json")
                       for index in range(games)]})


def republish_game(path, game):
    """Construct an internally frozen malformed fixture to exercise deeper boundary checks."""
    learner.atomic_json(path, game)
    finished_path = path.parent / "finished.json"
    finished = learner.read_json(finished_path)
    finished["gameSha256"][game["index"]] = learner.file_digest(path)
    learner.atomic_json(finished_path, finished)


class ForwardTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.model = learner.initialize(SCHEMA, seed=17)

    def test_zero_head_is_exact_declared_prior_for_variable_legal_counts(self):
        for count in (1, 2, 7, 33):
            state = observation()
            state["actions"] = [{"command": f"{index:03}", "features": {}}
                                for index in range(count)]
            state["baselineIndex"] = count - 1
            expected = learner.prior_probabilities(count, count - 1)
            prediction = learner.reference_forward(self.model, state)
            self.assertEqual(prediction["probabilities"], expected)
            logp, value = learner.PurchasePolicy(self.model)([state])
            torch.testing.assert_close(logp[0].exp(), torch.tensor(expected, dtype=learner.DTYPE),
                                       rtol=0, atol=1e-15)
            self.assertEqual(float(value[0].detach()), 0.5)

    def test_zero_actor_receives_policy_gradient(self):
        actor = learner.PurchasePolicy(self.model)
        rows = [behavior_row(self.model, observation(), selected=1)]
        config = learner.UpdateConfig(valueCoefficient=0, entropyCoefficient=0)
        learner.loss_for_rows(actor, rows, config).backward()
        self.assertGreater(float(actor.actorWeights.grad.abs().sum()), 0.1)

    def test_nonzero_forward_matches_scalar_oracle_and_known_single_unit(self):
        model = learner.initialize(SCHEMA, seed=17)
        for name in learner.PARAMETERS:
            model[name] = 0.0 if name == "valueBias" else [0.0] * len(model[name])
        model["stateWeights"][2] = 0.3
        model["stateBias"][0] = 0.2
        model["actionWeights"][0:3] = [0.1, 0.4, -0.2]
        model["actionBias"][0] = -0.1
        model["actorWeights"][0] = 1.2
        model["valueWeights"][0] = 0.8
        model["valueBias"] = -0.3
        state = observation()
        expected = learner.reference_forward(model, state)
        hidden = math.tanh(0.5)
        self.assertAlmostEqual(expected["logits"][0],
                               math.log(0.97) + 1.2 * math.tanh(hidden + 0.3), places=14)
        self.assertAlmostEqual(expected["value"], 1 / (1 + math.exp(-(0.8 * hidden - 0.3))),
                               places=14)
        logp, value = learner.PurchasePolicy(model)([state])
        torch.testing.assert_close(logp[0].exp(),
                                   torch.tensor(expected["probabilities"], dtype=learner.DTYPE),
                                   rtol=1e-13, atol=1e-14)
        self.assertAlmostEqual(float(value[0].detach()), expected["value"], places=14)

    def test_clamped_residual_keeps_all_legal_actions_positive(self):
        self.model["actorWeights"] = [1000.0] * learner.HIDDEN
        state = observation()
        prediction = learner.reference_forward(self.model, state)
        self.assertTrue(all(value > 0 for value in prediction["probabilities"]))
        logp, _ = learner.PurchasePolicy(self.model)([state])
        torch.testing.assert_close(logp[0].exp(),
                                   torch.tensor(prediction["probabilities"], dtype=learner.DTYPE),
                                   rtol=1e-12, atol=1e-15)

    def test_unknown_even_zero_feature_and_nonfinite_input_are_rejected(self):
        for features in ({"hidden-deck": 0.0}, {"root": float("nan")}, {"root": 17.0}):
            state = observation()
            state["stateFeatures"] = features
            with self.subTest(features=features), self.assertRaises(ValueError):
                learner.PurchasePolicy(self.model)([state])

    def test_unsorted_dictionary_or_actions_are_rejected(self):
        model = copy.deepcopy(self.model)
        model["stateFeatureNames"] = list(reversed(model["stateFeatureNames"]))
        with self.assertRaisesRegex(ValueError, "sorted dictionary"):
            learner.validate_model(model)
        state = observation()
        state["actions"] = list(reversed(state["actions"]))
        with self.assertRaisesRegex(ValueError, "sorted order"):
            learner.validate_observation(state, self.model)

    def test_uniform_boundary_uses_strict_first_crossing(self):
        self.assertEqual(learner.select([0.97, 0.03], 0.97), 1)
        self.assertEqual(learner.select([0.97, 0.03], 0.0), 0)
        self.assertEqual(learner.selection_uniform(0), 0.0)
        self.assertEqual(learner.selection_uniform(-1), learner.selection_uniform(2**32 - 1))
        with self.assertRaisesRegex(ValueError, "strictly below"):
            learner.select([1.0], 1.0)

    def test_torch_gradient_matches_finite_difference(self):
        actor = learner.PurchasePolicy(self.model)
        state = observation()
        logp, _ = actor([state])
        (-logp[0][1]).backward()
        gradient = float(actor.actorWeights.grad[0])
        changed = copy.deepcopy(self.model)
        epsilon = 1e-6
        changed["actorWeights"][0] = epsilon
        positive = -math.log(learner.reference_forward(changed, state)["probabilities"][1])
        changed["actorWeights"][0] = -epsilon
        negative = -math.log(learner.reference_forward(changed, state)["probabilities"][1])
        self.assertAlmostEqual(gradient, (positive - negative) / (2 * epsilon), places=8)


class PpoMathTest(unittest.TestCase):
    def test_update_clips_actual_global_gradient_before_optimizer_step(self):
        model = learner.initialize(SCHEMA, 17)
        actor = learner.PurchasePolicy(model)
        optimizer = torch.optim.Adam(actor.parameters(), lr=0.001, foreach=False)
        config = learner.UpdateConfig(maxGradientNorm=0.001)
        rows = [behavior_row(model, observation(), selected=1)]
        gradients = []
        original = optimizer.step
        def observed_step():
            gradients.append(math.sqrt(sum(float(parameter.grad.square().sum())
                                           for parameter in actor.parameters()
                                           if parameter.grad is not None)))
            original()
        with patch.object(optimizer, "step", side_effect=observed_step):
            learner.fit_epoch(actor, optimizer, rows, config, 0, math.inf)
        self.assertEqual(len(gradients), 1)
        self.assertGreater(gradients[0], 0)
        self.assertLessEqual(gradients[0], config.maxGradientNorm)

    def test_clipping_blocks_excess_good_ratio_and_excess_bad_ratio(self):
        logp = torch.tensor([math.log(1.5), math.log(0.5)],
                            dtype=learner.DTYPE, requires_grad=True)
        loss = learner.clipped_policy_loss(logp, torch.zeros(2, dtype=learner.DTYPE),
                                            torch.tensor([1.0, -1.0], dtype=learner.DTYPE), 0.2)
        torch.testing.assert_close(loss, torch.tensor([-1.2, 0.8], dtype=learner.DTYPE))
        loss.sum().backward()
        torch.testing.assert_close(logp.grad, torch.zeros(2, dtype=learner.DTYPE))

    def test_clipping_does_not_hide_wrong_direction_changes(self):
        logp = torch.tensor([math.log(0.5), math.log(1.5)],
                            dtype=learner.DTYPE, requires_grad=True)
        loss = learner.clipped_policy_loss(logp, torch.zeros(2, dtype=learner.DTYPE),
                                            torch.tensor([1.0, -1.0], dtype=learner.DTYPE), 0.2)
        loss.sum().backward()
        torch.testing.assert_close(logp.grad, torch.tensor([-0.5, 1.5], dtype=learner.DTYPE))


class CollectionAndCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model_path = self.root / "model.json"
        self.model = learner.initialize(SCHEMA, 17)
        learner.atomic_json(self.model_path, self.model)
        self.data = self.root / "collection"
        collection(self.data, self.model_path)
        self.config = learner.UpdateConfig(epochs=2, batchSize=2, maxBehaviorKl=0.2)

    def test_incomplete_collection_is_rejected_before_game_read(self):
        (self.data / "finished.json").unlink()
        (self.data / "game-0.json").write_text("malformed must not be read")
        with self.assertRaisesRegex(ValueError, "completed collection"):
            learner.read_collection(self.data, self.model_path, self.model)

    def shared_baseline(self):
        snapshot = self.data / "shared-purchase-baseline.json"
        learner.atomic_json(snapshot, {"schemaVersion": 1, "weights": {}})
        path = self.data / "collection.json"
        manifest = learner.read_json(path)
        manifest["sharedPurchaseBaseline"] = {
            "path": str(self.root / "archived-source.json"),
            "sha256": learner.file_digest(snapshot)}
        learner.atomic_json(path, manifest)
        return snapshot

    def test_shared_baseline_uses_archived_bytes_without_requiring_original_source(self):
        self.shared_baseline()

        rows, provenance = learner.read_collection(self.data, self.model_path, self.model)

        self.assertEqual(len(rows), 4)
        self.assertEqual(provenance["collectionSha256"],
                         learner.file_digest(self.data / "collection.json"))
        self.assertEqual(set(provenance), {"collectionSha256", "collectionSignature",
                                          "gameHashes", "games", "gameMeanReward"})

    def test_malformed_shared_baseline_provenance_is_rejected(self):
        self.shared_baseline()
        path = self.data / "collection.json"
        manifest = learner.read_json(path)
        original = manifest["sharedPurchaseBaseline"]
        for invalid in (None, "baseline.json", {}, {**original, "path": "relative.json"},
                        {**original, "sha256": "not-a-hash"}, {**original, "extra": True}):
            with self.subTest(invalid=invalid):
                learner.atomic_json(path, {**manifest, "sharedPurchaseBaseline": invalid})

                with self.assertRaisesRegex(ValueError, "exact path and SHA-256"):
                    learner.read_collection(self.data, self.model_path, self.model)

    def test_missing_shared_baseline_snapshot_is_rejected(self):
        self.shared_baseline().unlink()

        with self.assertRaisesRegex(ValueError, "Shared purchase baseline snapshot"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_changed_shared_baseline_snapshot_is_rejected(self):
        snapshot = self.shared_baseline()
        snapshot.write_bytes(snapshot.read_bytes() + b"\n")

        with self.assertRaisesRegex(ValueError, "Shared purchase baseline snapshot"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_cli_initialization_accepts_full_collection_dictionary(self):
        output = self.root / "initial"
        with redirect_stdout(io.StringIO()):
            learner.main(["initialize", "--schema", str(self.data / "collection.json"),
                          "--output", str(output), "--seed", "74101"])
        model = learner.read_json(output / "policy.json")
        self.assertEqual(model["stateFeatureNames"], SCHEMA["stateFeatureNames"])
        self.assertEqual(model["actorWeights"], [0.0] * learner.HIDDEN)
        self.assertEqual(learner.read_json(output / "initialization.json")["seed"], 74101)

    def test_cli_update_honors_source_seed_and_policy_filename(self):
        output = self.root / "cli-fit"
        config = self.root / "config.json"
        learner.atomic_json(config, {"epochs": 1})
        with redirect_stdout(io.StringIO()):
            learner.main(["update", "--source", str(self.data), "--model", str(self.model_path),
                          "--output", str(output), "--config", str(config), "--seed", "74102"])
        identity = learner.read_json(output / "identity.json")
        self.assertEqual(identity["config"]["seed"], 74102)
        self.assertEqual(identity["config"]["maxBehaviorKl"], 0.01)
        self.assertTrue((output / "policy.json").exists())

    def test_old_behavior_probability_is_rejected(self):
        path = self.data / "game-0.json"
        game = learner.read_json(path)
        game["decisions"][0]["behaviorLogProbability"] -= 0.2
        republish_game(path, game)
        with self.assertRaisesRegex(ValueError, "Recorded behavior"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_wrong_seeded_choice_is_rejected_even_when_log_probability_matches(self):
        path = self.data / "game-1.json"
        game = learner.read_json(path)
        game["decisions"][0]["actorSeed"] = 0
        republish_game(path, game)
        with self.assertRaisesRegex(ValueError, "seeded selector"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_duplicate_origins_are_rejected(self):
        path = self.data / "collection.json"
        manifest = learner.read_json(path)
        manifest["seeds"][1] = 0
        learner.atomic_json(path, manifest)
        with self.assertRaisesRegex(ValueError, "distinct whole-game"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_terminal_return_reconstruction_is_enforced(self):
        path = self.data / "game-0.json"
        game = learner.read_json(path)
        game["terminal"]["reward"] = 0.1
        republish_game(path, game)
        with self.assertRaisesRegex(ValueError, "0.8 win-share"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_atomic_write_failure_preserves_existing_checkpoint(self):
        path = self.root / "atomic.json"
        learner.save_checkpoint(path, {"step": 1})
        original = path.read_bytes()
        with patch.object(learner.os, "replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                learner.save_checkpoint(path, {"step": 2})
        self.assertEqual(path.read_bytes(), original)
        self.assertEqual(list(self.root.glob(".atomic.json.*")), [])

    def test_checkpoint_tampering_is_rejected(self):
        path = self.root / "checkpoint.json"
        learner.save_checkpoint(path, {"epoch": 1})
        value = learner.read_json(path)
        value["payload"]["epoch"] = 2
        learner.atomic_json(path, value)
        with self.assertRaisesRegex(ValueError, "checksum"):
            learner.read_checkpoint(path)

    def test_post_completion_reward_changes_are_rejected(self):
        path = self.data / "game-0.json"
        game = learner.read_json(path)
        game["terminal"].update(reward=1.0, winShare=1.0, rankReward=1.0)
        learner.atomic_json(path, game)
        with self.assertRaisesRegex(ValueError, "frozen game hashes"):
            learner.read_collection(self.data, self.model_path, self.model)

    def test_epoch_resume_matches_uninterrupted_model_and_optimizer(self):
        continuous = self.root / "continuous"
        resumed = self.root / "resumed"
        learner.update(self.model_path, self.data, self.config, continuous)
        original = learner.fit_epoch
        def interrupted(policy, optimizer, rows, config, epoch, deadline):
            if epoch == 1:
                raise RuntimeError("simulated process interruption")
            return original(policy, optimizer, rows, config, epoch, deadline)
        with patch.object(learner, "fit_epoch", side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError, "interruption"):
                learner.update(self.model_path, self.data, self.config, resumed)
        learner.update(self.model_path, self.data, self.config, resumed, resume=True)
        first = learner.read_checkpoint(continuous / "checkpoint.json")
        second = learner.read_checkpoint(resumed / "checkpoint.json")
        self.assertEqual(first["model"], second["model"])
        self.assertEqual(first["optimizer"], second["optimizer"])
        self.assertEqual(first["history"], second["history"])

    def test_resume_rejects_changed_configuration(self):
        output = self.root / "fit"
        learner.update(self.model_path, self.data, self.config, output)
        with self.assertRaisesRegex(ValueError, "Resume inputs"):
            learner.update(self.model_path, self.data, replace(self.config, seed=18),
                           output, resume=True)

    def test_kl_limit_rolls_back_model_and_optimizer(self):
        output = self.root / "rollback"
        config = replace(self.config, learningRate=0.1, maxBehaviorKl=0.000001)
        report = learner.update(self.model_path, self.data, config, output)
        self.assertEqual(report["stopReason"], "behavior-kl-limit")
        self.assertEqual(report["acceptedEpochs"], 0)
        self.assertEqual(learner.read_json(output / "policy.json"), self.model)
        checkpoint = learner.read_checkpoint(output / "checkpoint.json")
        self.assertEqual(checkpoint["optimizer"]["state"], {})

    def test_resume_does_not_restart_wall_clock_budget(self):
        output = self.root / "expired"
        with patch.object(learner, "fit_epoch", side_effect=RuntimeError("interrupted")):
            with self.assertRaises(RuntimeError):
                learner.update(self.model_path, self.data, self.config, output)
        with patch.object(learner.time, "time", return_value=1e12):
            report = learner.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual(report["stopReason"], "time-budget")
        self.assertEqual(report["acceptedEpochs"], 0)

    def test_interrupted_initial_checkpoint_publication_can_resume(self):
        output = self.root / "initialization-interruption"
        with patch.object(learner, "save_checkpoint", side_effect=RuntimeError("interrupted")):
            with self.assertRaises(RuntimeError):
                learner.update(self.model_path, self.data, self.config, output)
        identity = learner.read_json(output / "identity.json")
        self.assertFalse((output / "checkpoint.json").exists())
        report = learner.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual(report["acceptedEpochs"], self.config.epochs)
        self.assertEqual(learner.read_json(output / "identity.json"), identity)

    def test_missing_checkpoint_after_epoch_start_is_not_reinitialized(self):
        output = self.root / "lost-checkpoint"
        with patch.object(learner, "fit_epoch", side_effect=RuntimeError("interrupted")):
            with self.assertRaises(RuntimeError):
                learner.update(self.model_path, self.data, self.config, output)
        (output / "checkpoint.json").unlink()
        marker = (output / "epoch-started.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "missing after epoch"):
            learner.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual((output / "epoch-started.json").read_bytes(), marker)
        self.assertFalse((output / "checkpoint.json").exists())

    def test_mismatched_epoch_marker_preserves_present_checkpoint(self):
        output = self.root / "bad-marker"
        learner.update(self.model_path, self.data, self.config, output)
        original = (output / "checkpoint.json").read_bytes()
        learner.atomic_json(output / "epoch-started.json", {"identitySha256": "wrong"})
        with self.assertRaisesRegex(ValueError, "epoch marker"):
            learner.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual((output / "checkpoint.json").read_bytes(), original)

    def test_optimizer_carries_only_into_matching_actor(self):
        first = self.root / "first"
        learner.update(self.model_path, self.data, self.config, first)
        with self.assertRaisesRegex(ValueError, "exact input actor"):
            learner.update(self.model_path, self.data, self.config, self.root / "invalid",
                           optimizer_from=first / "checkpoint.json")

    def test_matching_optimizer_state_carries_into_next_on_policy_batch(self):
        first = self.root / "first"
        learner.update(self.model_path, self.data, self.config, first)
        data = self.root / "next-data"
        collection(data, first / "policy.json")
        second = self.root / "second"
        learner.update(first / "policy.json", data, self.config, second,
                       optimizer_from=first / "checkpoint.json")
        before = learner.read_checkpoint(first / "checkpoint.json")
        after = learner.read_checkpoint(second / "checkpoint.json")
        before_step = before["optimizer"]["state"]["0"]["step"]["tensor"]
        after_step = after["optimizer"]["state"]["0"]["step"]["tensor"]
        self.assertGreater(after_step, before_step)


class DelayedSequenceTest(unittest.TestCase):
    def test_terminal_only_learning_and_payoff_reversal_for_declared_seeds(self):
        report = run_toy()
        self.assertTrue(report["passed"], report)


if __name__ == "__main__":
    unittest.main()
