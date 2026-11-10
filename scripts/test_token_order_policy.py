"""Counterfactual targets, root weighting and a public-context reversal for token fitting."""

import copy
import tempfile
import time
from pathlib import Path
import unittest

import purchase_policy as b
import territory_value as q
import token_order_policy as learner


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


if __name__ == "__main__":
    unittest.main()
