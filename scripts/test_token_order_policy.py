"""Counterfactual targets, root weighting and a public-context reversal for token fitting."""

import copy
import tempfile
import time
from pathlib import Path
import unittest
from unittest import mock

import purchase_policy as b
import territory_value as q
import token_order_policy as learner
from test_token_order_metadata import add_overlay_metadata


def schema():
    return {"encoderVersion": q.TOKEN_ENCODER_2,
            "stateFeatureNames": sorted([*q.TOKEN_ORDER_FEATURES, "role.0.cash", "role.0.stock"]),
            "actionFeatureNames": sorted([*q.TOKEN_AMOUNT_FEATURES, "amount"])}


def observation(position=1, amounts=(0, 1, 2), baseline=1):
    actions = sorted([{"command": f"buy-tokens {amount}",
                       "features": {f"bid.amount.{amount}": 1., "amount": amount/36.}}
                      for amount in amounts], key=lambda action: action["command"])
    return {"encoderVersion": q.TOKEN_ENCODER_2,
            "stateFeatures": {"role.0.cash": 2/200., "role.0.stock": 2/36.,
                              "role.0.capitalChoicePosition": position/6.},
            "actions": actions,
            "baselineIndex": [action["command"] for action in actions].index(f"buy-tokens {baseline}")}


def outcome(reward, seed):
    return {"reward": reward, "winShare": reward, "rankReward": reward,
            "continuationSeed": seed, "scores": [], "decisions": 12}


def raw_root(position=1):
    view = observation(position)
    indices = learner.proposal_indices(view)
    returns = {0: (.6, .8), 1: (.2, .4), 2: (.1, .3)}
    return {"index": 0, "split": "TRAINING", "configuration": "fixture", "status": "COLLECTED",
            "observation": view, "baselineCommand": "buy-tokens 1", "proposedIndices": indices,
            "alternatives": [{"index": index, "command": view["actions"][index]["command"],
                              "outcomes": [outcome(value, seed) for value, seed in zip(returns[index], (13, 29))]}
                             for index in indices]}


def collection_fixture(root):
    source = {"schemaVersion": 1, "purpose": "SCREENING", "configurations": [learner.CONFIGURATION],
              "trainingRoots": 2, "validationRoots": 2, "continuationReplicates": 2, "seed": 13000000,
              "opponents": ["opponent.json"], "frozenPurchase": {"baseline": "prior.json", "policy": "actor.json"}}
    b.atomic_json(root / "source.json", source)
    models = ["initial.json", "incumbent.json", "opponent-0.json"]
    for name in models + ["frozen-purchase-baseline.json", "frozen-purchase-policy.json"]:
        b.atomic_json(root / name, {"weights": {}})
    requests = [{"index": index, "split": "TRAINING" if index < 2 else "VALIDATION",
                 "gameSeed": -987000000 + index // 2 + (index % 2)*8191,
                 "seat": index % 2, "opponentIndices": [0]*4,
                 "requestedEpoch": index % 2 + 1, "requestedOrdinal": 1} for index in range(4)]
    manifest = dict(schema(), kind=learner.KIND, signature="fixed", proposalVersion=learner.PROPOSALS,
                    originSchemaVersion=1, selectionProtocol=learner.SELECTION, configuration=learner.CONFIGURATION,
                    **{key: source[key] for key in ("trainingRoots", "validationRoots", "continuationReplicates", "seed")},
                    sourceSha256=learner.sha(root / "source.json"), rootRequests=requests,
                    originFiles=[f"origin-{index}.json" for index in range(4)],
                    ordinaryModelSnapshots={name: learner.sha(root / name) for name in models},
                    frozenPurchase={role: {"snapshot": f"frozen-purchase-{role}.json",
                                          "sha256": learner.sha(root / f"frozen-purchase-{role}.json")}
                                    for role in ("baseline", "policy")})
    b.atomic_json(root / "run.json", manifest)
    b.atomic_json(root / "dataset.json", dict(manifest, complete=True, counts={"COLLECTED": 4}))
    for request in requests:
        row = dict(raw_root(), **request, schemaVersion=1, signature="fixed", configuration=learner.CONFIGURATION,
                   actualEpoch=request["requestedEpoch"], rootDecision=5, originDecisions=5)
        for alternative in row["alternatives"]:
            for replicate, result in enumerate(alternative["outcomes"]):
                result["continuationSeed"] = (request["gameSeed"] << 3) ^ replicate ^ 0x6f20a5319d
                result["scores"] = [0]*4
        b.atomic_json(root / f"origin-{request['index']}.json", row)
    finish_collection(root)


def finish_collection(root):
    b.atomic_json(root / "finished.json", {"kind": learner.KIND, "signature": "fixed",
                  "originSha256": [learner.sha(root / f"origin-{index}.json") for index in range(4)]})


def overlay_collection_fixture(root):
    collection_fixture(root)
    add_overlay_metadata(root)
    manifest = learner.read(root / "run.json")
    for request in manifest["rootRequests"]:
        request["opponentIndices"] = list(range(4))
        path = root / f"origin-{request['index']}.json"
        row = learner.read(path)
        row["opponentIndices"] = request["opponentIndices"]
        b.atomic_json(path, row)
    b.atomic_json(root / "run.json", manifest)
    b.atomic_json(root / "dataset.json", dict(manifest, complete=True, counts={"COLLECTED": 4}))
    finish_collection(root)


class TokenOrderPolicyTest(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        b.torch.use_deterministic_algorithms(True)
        self.model = q.initialize(schema(), 137, "token")

    def test_new_encoder_requires_public_order_and_complete_signed_amount_vocabulary(self):
        missing = copy.deepcopy(self.model)
        missing["stateFeatureNames"].remove("role.0.capitalChoicePosition")
        with self.assertRaisesRegex(ValueError, "capital-choice"):
            q.validate_model(missing, "token")
        legacy = dict(schema(), encoderVersion=b.TOKEN_CONTRACT.encoder)
        legacy["stateFeatureNames"] = ["role.0.cash"]
        legacy["actionFeatureNames"] = ["amount"]
        self.assertEqual(b.TOKEN_CONTRACT.encoder, q.initialize(legacy, 137, "token")["encoderVersion"])

    def test_proposals_include_one_token_undercut_and_overbid_before_legacy_alternatives(self):
        view = observation(amounts=range(-36, 37), baseline=16)
        view["stateFeatures"].update({"role.0.cash": 100/200., "role.0.stock": 36/36.})
        commands = [view["actions"][index]["command"] for index in learner.proposal_indices(view)]
        self.assertEqual([f"buy-tokens {amount}" for amount in (16, 15, 17, 0, -4, 8, 24, 36)], commands)

    def test_targets_average_paired_returns_instead_of_labelling_world_winners(self):
        result = learner.validate_root(raw_root(), self.model, 2)
        self.assertAlmostEqual(.4, result["targets"][0])
        self.assertAlmostEqual(0, result["targets"][1])
        self.assertAlmostEqual(-.1, result["targets"][2])

    def test_different_chance_stream_order_is_rejected(self):
        row = raw_root()
        row["alternatives"][-1]["outcomes"].reverse()
        with self.assertRaisesRegex(ValueError, "same distinct chance"):
            learner.validate_root(row, self.model, 2)

    def test_missing_neighbour_is_rejected_even_when_remaining_labels_are_valid(self):
        row = raw_root()
        row["proposedIndices"] = row["proposedIndices"][:2]
        row["alternatives"] = row["alternatives"][:2]
        with self.assertRaisesRegex(ValueError, "fixed proposal"):
            learner.validate_root(row, self.model, 2)

    def test_selector_keeps_baseline_at_exact_margin_and_ignores_unproposed_actions(self):
        row = {"observation": observation(), "indices": [1, 0], "targets": {1: 0, 0: .1}}
        self.assertEqual(1, learner.select([learner.MARGIN, 0., 20.], row))
        self.assertEqual(0, learner.select([learner.MARGIN + .001, 0., 20.], row))

    def test_loss_weights_each_origin_once_regardless_of_proposal_count(self):
        view = observation()
        row = {"observation": view, "indices": [1, 0, 2], "targets": {0: .2, 1: 0, 2: -.2}}
        values = b.torch.zeros(3, dtype=b.DTYPE)
        self.assertAlmostEqual(.04, float(learner.paired_loss(values, row)))
        smaller = dict(row, indices=[1, 0])
        self.assertAlmostEqual(.04, float(learner.paired_loss(values, smaller)))

    def test_expired_fit_deadline_cannot_restart_training(self):
        row = learner.validate_root(raw_root(), self.model, 2)
        with self.assertRaisesRegex(ValueError, "deadline expired"):
            learner.fit_one(self.model, [row], learner.FitConfig(epochs=1), 0., time.time()-1)

    def test_paired_fitter_recovers_an_action_reversal_from_public_capital_priority(self):
        rows = [{"observation": observation(position), "indices": [1, 0, 2],
                 "targets": {0: target, 1: 0., 2: -target}, "origin": index, "split": "TRAINING"}
                for index, (position, target) in enumerate([(1, .3), (6, -.3)] * 8)]
        config = learner.FitConfig(epochs=160, batchSize=16, learningRate=.01)
        actor = learner.fit_one(self.model, rows, config, 0., time.time()+60)
        heldout = [dict(rows[0], origin=100, split="VALIDATION"),
                   dict(rows[1], origin=101, split="VALIDATION")]
        report = learner.measure(actor, heldout)
        self.assertEqual([0, 2], [row["index"] for row in report["selections"]])
        self.assertLess(report["pairedMse"], .005)

    def test_collection_checks_completion_hashes_before_reading_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            collection_fixture(root)
            row = learner.read(root / "origin-0.json")
            row["signature"] = "changed"
            b.atomic_json(root / "origin-0.json", row)
            with self.assertRaisesRegex(ValueError, "origin changed"):
                learner.collection(root, 137)

    def test_collection_binds_independent_origins_and_environment_snapshots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            collection_fixture(root)
            _, rows, provenance = learner.collection(root, 137)
            self.assertEqual(4, len(rows))
            self.assertIn("frozen-purchase-policy.json", provenance["files"])

    def test_hashed_root_from_another_run_cannot_be_relabelled_as_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            collection_fixture(root)
            row = learner.read(root / "origin-2.json")
            row["gameSeed"] -= 1
            b.atomic_json(root / "origin-2.json", row)
            finish_collection(root)
            with self.assertRaisesRegex(ValueError, "prospective request"):
                learner.collection(root, 137)

    def test_coherently_hashed_wrong_continuation_worlds_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            collection_fixture(root)
            row = learner.read(root / "origin-0.json")
            for alternative in row["alternatives"]:
                for result in alternative["outcomes"]:
                    result["continuationSeed"] += 16
            b.atomic_json(root / "origin-0.json", row)
            finish_collection(root)
            with self.assertRaisesRegex(ValueError, "precommitted chance"):
                learner.collection(root, 137)

    def test_changed_purchase_environment_cannot_reuse_completion_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            collection_fixture(root)
            b.atomic_json(root / "frozen-purchase-policy.json", {"weights": {"other": 1}})
            with self.assertRaisesRegex(ValueError, "environment snapshot changed"):
                learner.collection(root, 137)

    def test_transfer_keeps_original_model_identity_without_importing_qualification(self):
        source = {"experimentalRulesTransfer": dict(learner.RULES_TRANSFER),
                  "candidate": "ordinary.json", "incumbent": "ordinary.json", "opponents": [None]}
        snapshots = {"initial.json": "same-source", "incumbent.json": "same-source", "opponent-0.json": "prior"}
        manifest = {"source": "/original/source.json", "rulesVersion": "ah1996-three-epoch-5",
                    "experimentalRulesTransfer": dict(learner.RULES_TRANSFER,
                         initializationOnly=True, qualificationTransferred=False),
                    "ordinaryModelSources": {role: {"path": "/original/ordinary.json",
                         "sha256": "same-source", "snapshot": name}
                         for role, name in (("candidate", "initial.json"), ("incumbent", "incumbent.json"))}}
        learner.transfer_metadata(source, manifest, snapshots)
        manifest["experimentalRulesTransfer"]["qualificationTransferred"] = True
        with self.assertRaisesRegex(ValueError, "unqualified rules4"):
            learner.transfer_metadata(source, manifest, snapshots)

    def test_undeclared_transfer_cannot_silently_change_the_fitting_environment(self):
        with self.assertRaisesRegex(ValueError, "undeclared rules transfer"):
            learner.transfer_metadata({}, {"experimentalRulesTransfer": learner.RULES_TRANSFER}, {})


class SuppliedInitializerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        collection_fixture(self.root)

    def test_omission_and_explicit_none_retain_default_model_rows_and_provenance(self):
        expected = q.initialize(learner.read(self.root / "dataset.json"), 137, "token")

        omitted = learner.collection(self.root, 137)
        explicit = learner.collection(self.root, 137, supplied_initializer=None)

        self.assertEqual(expected, omitted[0])
        self.assertEqual(omitted, explicit)
        self.assertEqual({"source", "signature", "files", "collected", "skipped"}, set(omitted[2]))

    def test_supplied_model_is_used_without_initializing_or_reseeding(self):
        supplied = q.initialize(schema(), 991, "token")
        _, expected_rows, expected_provenance = learner.collection(self.root, 137)
        with mock.patch.object(q, "initialize", side_effect=AssertionError("Unexpected initialization")), \
                mock.patch.object(learner.random, "seed", side_effect=AssertionError("Unexpected reseeding")):
            actual, rows, provenance = learner.collection(self.root, None, supplied_initializer=supplied)

        self.assertEqual(supplied, actual)
        self.assertEqual(expected_rows, rows)
        self.assertEqual(expected_provenance, provenance)

    def test_returned_model_does_not_share_nested_weights_with_the_supplied_model(self):
        supplied = q.initialize(schema(), 991, "token")
        expected = copy.deepcopy(supplied)

        actual, _, _ = learner.collection(self.root, 137, supplied_initializer=supplied)
        actual["stateWeights"][0] += 1.

        self.assertEqual(expected, supplied)
        self.assertNotEqual(supplied, actual)

    def test_supplied_feature_dictionaries_must_match_the_collection_exactly(self):
        for key in ("stateFeatureNames", "actionFeatureNames"):
            with self.subTest(dictionary=key):
                different = schema()
                different[key] = sorted([*different[key], "unrecorded.feature"])
                supplied = q.initialize(different, 991, "token")

                with self.assertRaisesRegex(ValueError, "exact collection feature dictionaries"):
                    learner.collection(self.root, 137, supplied_initializer=supplied)

    def test_supplied_legacy_encoder_cannot_bind_a_version_two_collection(self):
        legacy = dict(schema(), encoderVersion=b.TOKEN_CONTRACT.encoder)
        supplied = q.initialize(legacy, 991, "token")

        with self.assertRaisesRegex(ValueError, "version 2 encoder"):
            learner.collection(self.root, 137, supplied_initializer=supplied)


class ArenaContinuationParityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        overlay_collection_fixture(self.root)

    def test_opt_in_keeps_training_rows_and_initialized_model_equal_to_legacy(self):
        with tempfile.TemporaryDirectory() as directory:
            legacy = Path(directory)
            collection_fixture(legacy)
            expected_model, expected_rows, legacy_provenance = learner.collection(legacy, 137)

        actual_model, actual_rows, provenance = learner.collection(self.root, 137)

        self.assertEqual(expected_model, actual_model)
        self.assertEqual(expected_rows, actual_rows)
        self.assertNotIn("metadataValidatorSha256", legacy_provenance)
        self.assertEqual(learner.sha(learner.metadata.__file__), provenance["metadataValidatorSha256"])
        self.assertNotIn("expansion-imitation-candidate.json", legacy_provenance["files"])
        self.assertIn("expansion-imitation-candidate.json", provenance["files"])
        self.assertIn("expansion-imitation-opponent-3.json", provenance["files"])
        self.assertNotIn("expansion-imitation-opponent-0.json", provenance["files"])


if __name__ == "__main__":
    unittest.main()
