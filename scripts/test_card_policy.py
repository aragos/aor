"""Version separation, compressed evidence and bounded numerical tests for card learning."""

import copy
from contextlib import redirect_stdout
import gzip
import io
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import card_policy as card
from card_policy_toy import SCHEMA, observation
import purchase_policy as backend
from purchase_policy_toy import SCHEMA as PURCHASE_SCHEMA


def behavior_row(model, state, selected, number=0):
    prediction = card.reference_forward(model, state)
    seed = next(seed for seed in range(10000)
                if backend.select(prediction["probabilities"], backend.selection_uniform(seed))
                == selected)
    return {"number": number, "actorSeed": seed, "decision": "play-cards", "turn": 1,
            "observation": state, "selectedIndex": selected,
            "behaviorLogProbability": math.log(prediction["probabilities"][selected]),
            "behaviorValue": prediction["value"]}


def write_game(path, game):
    path.write_bytes(gzip.compress(backend.canonical(game), mtime=0))


def collection(directory, model_path, games=4):
    directory.mkdir()
    model = backend.read_json(model_path)
    model_sha = backend.file_digest(model_path)
    frozen_path = directory / "frozen-purchase-policy.json"
    backend.atomic_json(frozen_path, backend.initialize(PURCHASE_SCHEMA, 31))
    frozen_sha = backend.file_digest(frozen_path)
    common = {"signature": "card-fixture-signature", "modelSha256": model_sha,
              "encoderVersion": card.ENCODER_VERSION, "actorVersion": card.ACTOR_VERSION}
    backend.atomic_json(directory / "collection.json", {
        **common, "mode": "on-policy", "games": games, "seeds": list(range(games)),
        "configuration": "fixture", "trajectoryEncoding": "gzip-json-v1",
        "frozenPurchasePolicy": {"path": str(frozen_path), "sha256": frozen_sha},
        "stateFeatureNames": model["stateFeatureNames"],
        "actionFeatureNames": model["actionFeatureNames"]})
    backend.atomic_json(directory / "state.json", {**common, "phase": "complete"})
    (directory / "policy.json").write_bytes(model_path.read_bytes())
    for index in range(games):
        reward = float(index % 2)
        write_game(directory / f"game-{index}.json.gz", {
            **common, "schemaVersion": 1, "mode": "on-policy", "index": index,
            "configuration": "fixture", "gameSeed": index,
            "frozenPurchasePolicySha256": frozen_sha,
            "controlledSeat": index % 4, "opponentIndices": [0, 1, 2, 3],
            "purchaseAudit": [],
            "decisions": [behavior_row(model, observation(index % 2), index % 2)],
            "terminal": {"reward": reward, "winShare": reward, "rankReward": reward,
                         "scores": [], "gameDecisions": 1}})
    finish(directory, common, games)


def finish(directory, common, games):
    backend.atomic_json(directory / "finished.json", {
        **common, "games": games,
        "gameSha256": [backend.file_digest(directory / f"game-{index}.json.gz")
                       for index in range(games)]})


class CardContractTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.model = card.initialize(SCHEMA, 17)

    def test_card_zero_head_uses_exact_prior_through_1024_actions(self):
        for count in (1, 2, 129, 1024):
            state = observation(0)
            state["actions"] = [{"command": f"play hand-{index:04}", "features": {}}
                                for index in range(count)]
            state["baselineIndex"] = count - 1
            expected = backend.prior_probabilities(count, count - 1)
            self.assertEqual(card.reference_forward(self.model, state)["probabilities"], expected)
            logp, value = card.CardPolicy(self.model)([state])
            torch.testing.assert_close(logp[0].exp(), torch.tensor(expected, dtype=backend.DTYPE),
                                       rtol=0, atol=1e-15)
            self.assertEqual(float(value[0].detach()), 0.5)

    def test_card_does_not_widen_purchase_entrypoint_contracts(self):
        for call in (lambda: backend.validate_model(self.model),
                     lambda: backend.initialize(SCHEMA, 17),
                     lambda: backend.PurchasePolicy(self.model),
                     lambda: backend.reference_forward(self.model, observation(0)),
                     lambda: backend.validate_observation(observation(0), self.model)):
            with self.subTest(call=call), self.assertRaisesRegex(ValueError, "encoder"):
                call()

    def test_card_rejects_purchase_model_and_schema(self):
        purchase = backend.initialize(PURCHASE_SCHEMA, 17)
        for call in (lambda: card.validate_model(purchase),
                     lambda: card.initialize(PURCHASE_SCHEMA, 17),
                     lambda: card.CardPolicy(purchase)):
            with self.subTest(call=call), self.assertRaisesRegex(ValueError, "encoder"):
                call()

    def test_card_boundaries_allow_long_complete_commands_but_not_line_breaks(self):
        state = observation(0)
        state["actions"][1]["command"] = "play " + "x" * 1995
        card.CardPolicy(self.model).prepare(state)
        for command in ("play " + "x" * 1996, "play\rx", "play\nx"):
            state["actions"][1]["command"] = command
            with self.subTest(command=command[:12]), self.assertRaisesRegex(ValueError, "commands"):
                card.CardPolicy(self.model).prepare(state)

    def test_card_rejects_action_overflow_and_unknown_features(self):
        state = observation(0)
        state["actions"] = [{"command": f"play {index:04}", "features": {}}
                            for index in range(1025)]
        with self.assertRaisesRegex(ValueError, "1024"):
            card.CardPolicy(self.model).prepare(state)
        state = observation(0)
        state["stateFeatures"]["opponent-private-hand"] = 0.0
        with self.assertRaisesRegex(ValueError, "unknown"):
            card.CardPolicy(self.model).prepare(state)

    def test_initial_card_actor_receives_nonzero_delayed_return_gradient(self):
        actor = card.CardPolicy(self.model)
        row = {**behavior_row(self.model, observation(1), 1), "return": 1.0}
        backend.loss_for_rows(actor, [row], backend.UpdateConfig(valueCoefficient=0)).backward()
        self.assertGreater(float(actor.actorWeights.grad.abs().sum()), 0.1)

    def test_card_and_purchase_networks_are_numerically_identical_with_renamed_schema(self):
        self.model["actorWeights"] = [math.sin(index) / 3 for index in range(64)]
        self.model["valueWeights"] = [math.cos(index) / 4 for index in range(64)]
        purchase = {**self.model, "encoderVersion": backend.ENCODER_VERSION}
        states = [observation(index % 2) for index in range(7)]
        purchase_states = [{**state, "encoderVersion": backend.ENCODER_VERSION}
                           for state in states]
        card_logp, card_values = card.CardPolicy(self.model)(states)
        purchase_logp, purchase_values = backend.PurchasePolicy(purchase)(purchase_states)
        for left, right in zip(card_logp, purchase_logp):
            torch.testing.assert_close(left, right, rtol=0, atol=0)
        torch.testing.assert_close(card_values, purchase_values, rtol=0, atol=0)

    def test_card_batches_preserve_every_row_and_bound_total_candidates(self):
        rows = [{"number": index, "observation": {
            "actions": [None] * (1024 if index % 2 else 1)}} for index in range(70)]
        batches = list(backend.row_batches(rows, 64))
        self.assertEqual([row for batch in batches for row in batch], rows)
        self.assertTrue(all(len(batch) <= 64 for batch in batches))
        self.assertTrue(all(sum(len(row["observation"]["actions"]) for row in batch) <= 16384
                            for batch in batches))
        self.assertGreater(len(batches), 2)


class CardCollectionTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model = card.initialize(SCHEMA, 17)
        self.model_path = self.root / "model.json"
        backend.atomic_json(self.model_path, self.model)
        self.data = self.root / "collection"
        collection(self.data, self.model_path)
        self.config = backend.UpdateConfig(epochs=2, batchSize=2, maxBehaviorKl=0.2)

    def test_card_reader_binds_compressed_bytes_and_frozen_purchase_policy(self):
        rows, provenance = card.read_collection(self.data, self.model_path, self.model)
        self.assertEqual(len(rows), 4)
        self.assertEqual(provenance["frozenPurchasePolicySha256"],
                         backend.file_digest(self.data / "frozen-purchase-policy.json"))
        self.assertEqual(provenance["gameHashes"][0],
                         backend.file_digest(self.data / "game-0.json.gz"))

    def test_completed_guard_precedes_compressed_outcome_reads(self):
        (self.data / "finished.json").unlink()
        (self.data / "game-0.json.gz").write_bytes(b"invalid compressed outcome")
        with self.assertRaisesRegex(ValueError, "completed collection"):
            card.read_collection(self.data, self.model_path, self.model)

    def test_changed_compressed_bytes_rejected_before_decompression(self):
        (self.data / "game-0.json.gz").write_bytes(b"invalid compressed outcome")
        with self.assertRaisesRegex(ValueError, "frozen game hashes"):
            card.read_collection(self.data, self.model_path, self.model)

    def test_card_reader_requires_explicit_compression_contract(self):
        manifest = backend.read_json(self.data / "collection.json")
        manifest.pop("trajectoryEncoding")
        backend.atomic_json(self.data / "collection.json", manifest)
        with self.assertRaisesRegex(ValueError, "gzip-json-v1"):
            card.read_collection(self.data, self.model_path, self.model)

    def test_expanded_gzip_document_limit_is_enforced(self):
        path = self.root / "large.json.gz"
        path.write_bytes(gzip.compress(b" " * (64 * 1024 * 1024 + 1), mtime=0))
        with self.assertRaisesRegex(ValueError, "64 MiB"):
            backend.read_game(path, card.CONTRACT)

    def test_changed_frozen_purchase_snapshot_is_rejected(self):
        backend.atomic_json(self.data / "frozen-purchase-policy.json", self.model)
        with self.assertRaisesRegex(ValueError, "Frozen purchase policy snapshot"):
            card.read_collection(self.data, self.model_path, self.model)

    def test_game_cannot_name_another_frozen_purchase_actor(self):
        path = self.data / "game-0.json.gz"
        game = backend.read_game(path, card.CONTRACT)
        game["frozenPurchasePolicySha256"] = "0" * 64
        write_game(path, game)
        completed = backend.read_json(self.data / "finished.json")
        finish(self.data, completed, 4)
        with self.assertRaisesRegex(ValueError, "Game frozen purchase"):
            card.read_collection(self.data, self.model_path, self.model)

    def test_card_update_preserves_purchase_bytes_and_roundtrips_resume(self):
        frozen_path = self.data / "frozen-purchase-policy.json"
        before = frozen_path.read_bytes()
        output = self.root / "update"
        result = card.update(self.model_path, self.data, self.config, output)
        policy = (output / "policy.json").read_bytes()
        checkpoint = backend.read_checkpoint(output / "checkpoint.json")
        resumed = card.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual(result, resumed)
        self.assertEqual(policy, (output / "policy.json").read_bytes())
        self.assertEqual(checkpoint, backend.read_checkpoint(output / "checkpoint.json"))
        self.assertEqual(before, frozen_path.read_bytes())
        identity = backend.read_json(output / "identity.json")
        self.assertEqual(identity["actorVersion"], card.ACTOR_VERSION)
        self.assertEqual(identity["entrypointSha256"], backend.file_digest(card.__file__))

    def test_purchase_audit_never_becomes_a_fitting_row_or_feature(self):
        path = self.data / "game-0.json.gz"
        game = backend.read_game(path, card.CONTRACT)
        game["purchaseAudit"] = [{"number": 1, "turn": 1, "decision": "purchase-advances",
                                  "command": "buy-advance N", "quotedPrice": 10,
                                  "leaderCredits": [{"leaderCardId": "dionysus-exiguus",
                                                     "baseAmount": 20, "writtenRecordBonus": 0,
                                                     "marginalPriceSaving": 20}]}]
        write_game(path, game)
        finish(self.data, backend.read_json(self.data / "finished.json"), 4)
        rows, _ = card.read_collection(self.data, self.model_path, self.model)
        self.assertEqual(len(rows), 4)
        self.assertEqual(rows[0]["observation"], observation(0))
        self.assertNotIn("purchaseAudit", rows[0])

    def test_purchase_audit_rejects_undeclared_fields(self):
        with self.assertRaisesRegex(ValueError, "declared public fields"):
            backend.validate_purchase_audit({"purchaseAudit": [{"authorityState": {}}]})

    def test_interrupted_card_initialization_recovers_original_deadline(self):
        output = self.root / "interrupted"
        with patch.object(backend, "save_checkpoint", side_effect=OSError("interruption")):
            with self.assertRaises(OSError):
                card.update(self.model_path, self.data, self.config, output)
        identity = backend.read_json(output / "identity.json")
        card.update(self.model_path, self.data, self.config, output, resume=True)
        self.assertEqual(identity, backend.read_json(output / "identity.json"))

    def test_card_cli_initialize_and_update_keep_explicit_versions(self):
        schema = self.root / "schema.json"
        backend.atomic_json(schema, SCHEMA)
        initialized = self.root / "initialized"
        with redirect_stdout(io.StringIO()):
            card.main(["initialize", "--schema", str(schema), "--seed", "17",
                       "--output", str(initialized)])
        self.assertEqual(backend.read_json(initialized / "policy.json"), self.model)
        config = self.root / "config.json"
        backend.atomic_json(config, {"epochs": 1, "batchSize": 2})
        output = self.root / "cli-update"
        with redirect_stdout(io.StringIO()):
            card.main(["update", "--model", str(self.model_path), "--source", str(self.data),
                       "--config", str(config), "--seed", "17", "--output", str(output)])
        self.assertEqual(backend.read_json(output / "policy.json")["encoderVersion"],
                         card.ENCODER_VERSION)
        self.assertFalse(backend.read_json(output / "finished.json")["strengthClaim"])

    def test_purchase_cli_rejects_card_schema_without_creating_output(self):
        output = self.root / "wrong-cli"
        schema = self.root / "schema.json"
        backend.atomic_json(schema, SCHEMA)
        with self.assertRaisesRegex(ValueError, "encoder"):
            backend.main(["initialize", "--schema", str(schema), "--seed", "17",
                          "--output", str(output)])
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
