"""Synthetic frozen-head ridge objectives, provenance, and unchanged inference wire."""

import copy
from dataclasses import replace
from pathlib import Path
import tempfile
import time
import unittest
from unittest import mock

import purchase_policy as b
import territory_value as q
import token_order_policy as token
import token_order_ridge as ridge
from test_token_order_policy import collection_fixture, finish_collection, observation, schema


def row(origin=0, amounts=(0, 1, 2), split="TRAINING", position=1):
    view = observation(position, amounts=amounts)
    targets = {index: {0: .3, 1: 0., 2: -.2}[int(action["command"].split()[1])]
               for index, action in enumerate(view["actions"])}
    return {"observation": view, "indices": token.proposal_indices(view),
            "targets": targets, "origin": origin, "split": split}


def retained_fixture(root, change_validation=False):
    """Create a tiny synthetic bank and its prior identity; never read a real bank."""
    bank = root / "collection"
    bank.mkdir(parents=True)
    collection_fixture(bank)
    if change_validation:
        for index in (2, 3):
            path = bank / f"origin-{index}.json"
            document = b.read_json(path)
            document["observation"]["stateFeatures"]["role.0.capitalChoicePosition"] = .95
            for alternative in document["alternatives"]:
                amount = int(alternative["command"].split()[1])
                for result in alternative["outcomes"]:
                    reward = {0: .1, 1: .8, 2: .9}[amount]
                    result.update(reward=reward, winShare=reward, rankReward=reward)
            b.atomic_json(path, document)
        finish_collection(bank)
    initializer = q.initialize(schema(), 137, "token")
    initializer_path = root / "initializer.json"
    b.atomic_json(initializer_path, initializer)
    _, _, provenance = token.collection(bank, None, supplied_initializer=initializer)
    prior_path = root / "prior-identity.json"
    b.atomic_json(prior_path, {"kind": "token-order-paired-fit",
                              "initializedModelSha256": b.digest(initializer), "source": provenance})
    return ridge.InputBindings(str(bank.resolve()), b.file_digest(bank / "dataset.json"),
        b.file_digest(bank / "finished.json"), str(initializer_path.resolve()),
        b.file_digest(initializer_path), b.digest(initializer), str(prior_path.resolve()),
        b.file_digest(prior_path))


class FrozenHeadTest(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        b.torch.use_deterministic_algorithms(True)
        self.initializer = q.initialize(schema(), 137, "token")

    def design(self, rows=None):
        return ridge.training_design(self.initializer,
            rows if rows is not None else [row(0), row(1, amounts=(0, 1), position=6)], time.time()+30)

    def test_only_output_weights_require_gradients(self):
        actor = ridge.frozen_actor(self.initializer)

        self.assertEqual(["outputWeights"], [name for name, parameter in actor.named_parameters()
                                             if parameter.requires_grad])

    def test_initializer_with_a_pretrained_head_is_rejected(self):
        initializer = copy.deepcopy(self.initializer)
        initializer["outputWeights"][0] = .1

        with self.assertRaisesRegex(ValueError, "zero output weights"):
            ridge.validate_initializer(initializer)

    def test_initializer_bias_must_remain_exactly_one_half(self):
        initializer = copy.deepcopy(self.initializer)
        initializer["outputBias"] = .6

        with self.assertRaisesRegex(ValueError, "unchanged .5 bias"):
            ridge.validate_initializer(initializer)

    def test_gradient_features_reproduce_existing_forward_and_reference_differences(self):
        example = row()
        actor = ridge.frozen_actor(self.initializer)
        features, indices = ridge.paired_features(actor, example)
        changed = copy.deepcopy(self.initializer)
        changed["outputWeights"] = [(index % 7 - 3)/50. for index in range(64)]
        actual = q.ActionValue(changed, "token")([example["observation"]])[0].detach()
        reference = q.reference_forward(changed, example["observation"], "token")
        predicted = features @ b.torch.tensor(changed["outputWeights"], dtype=b.torch.float64)
        baseline = example["observation"]["baselineIndex"]

        for pair, index in enumerate(indices):
            with self.subTest(action=index):
                self.assertAlmostEqual(float(predicted[pair]), float(actual[index]-actual[baseline]), places=12)
                self.assertAlmostEqual(float(predicted[pair]), reference[index]-reference[baseline], places=12)
        self.assertEqual(self.initializer, actor.artifact())

    def test_unequal_proposal_counts_reproduce_the_exact_old_root_mean_loss(self):
        rows = [row(0), row(1, amounts=(0, 1), position=6)]
        design = self.design(rows)
        model = copy.deepcopy(self.initializer)
        model["outputWeights"] = [(index % 5 - 2)/40. for index in range(64)]
        values = q.ActionValue(model, "token")([item["observation"] for item in rows])
        expected = b.torch.stack([token.paired_loss(value, item)
                                  for value, item in zip(values, rows)]).mean()
        weights = b.torch.tensor(model["outputWeights"], dtype=b.torch.float64)
        actual = (design.weights*(design.features @ weights-design.targets).square()).sum()

        self.assertEqual((2, 1), design.proposalCounts)
        self.assertEqual([.25, .25, .5], design.weights.tolist())
        self.assertAlmostEqual(float(expected.detach()), float(actual), places=14)

    def test_rms_uses_root_weights_and_does_not_center_paired_features(self):
        design = self.design()
        expected = b.torch.sqrt((design.weights[:, None]*design.features.square()).sum(dim=0))
        mean = (design.weights[:, None]*design.features).sum(dim=0)
        centered = b.torch.sqrt((design.weights[:, None]*(design.features-mean).square()).sum(dim=0))

        b.torch.testing.assert_close(design.scales, expected, rtol=0, atol=0)
        self.assertTrue(bool((mean.abs() > 1e-6).any()))
        self.assertFalse(b.torch.allclose(design.scales, centered))

    def test_scalar_normalized_ridge_matches_its_closed_form_without_an_intercept(self):
        features = b.torch.zeros((2, 64), dtype=b.torch.float64)
        features[:, 0] = b.torch.tensor([1., 3.], dtype=b.torch.float64)
        weights = b.torch.tensor([.5, .5], dtype=b.torch.float64)
        scales = b.torch.sqrt((weights[:, None]*features.square()).sum(dim=0))
        design = ridge.TrainingDesign(features, b.torch.tensor([2., 4.], dtype=b.torch.float64),
            weights, scales, scales > 0, (0, 1), (1, 1))

        fitted = ridge.fit_head(self.initializer, design, .1)

        self.assertAlmostEqual(7./(5.*1.1), fitted["outputWeights"][0], places=14)
        self.assertEqual([0.]*63, fitted["outputWeights"][1:])

    def test_exact_zero_feature_column_exports_a_zero_weight(self):
        initializer = copy.deepcopy(self.initializer)
        width = len(initializer["actionFeatureNames"])
        initializer["actionWeights"][:width] = [0.]*width
        design = ridge.training_design(initializer, [row(0), row(1)], time.time()+30)

        fitted = ridge.fit_head(initializer, design, .01)

        self.assertEqual(0., float(design.scales[0]))
        self.assertFalse(bool(design.active[0]))
        self.assertEqual(0., fitted["outputWeights"][0])
        self.assertTrue(any(value != 0. for value in fitted["outputWeights"][1:]))

    def test_all_zero_pair_features_retain_the_entire_initializer(self):
        initializer = copy.deepcopy(self.initializer)
        initializer["actionWeights"] = [0.]*len(initializer["actionWeights"])
        design = ridge.training_design(initializer, [row(0), row(1)], time.time()+30)

        fitted = ridge.fit_head(initializer, design, .0001)

        self.assertEqual(initializer, fitted)

    def test_training_design_rejects_validation_rows(self):
        with self.assertRaisesRegex(ValueError, "never validation"):
            self.design([row(0), row(1, split="VALIDATION")])

    def test_training_design_rejects_duplicate_origins(self):
        with self.assertRaisesRegex(ValueError, "exactly once"):
            self.design([row(0), row(0)])

    def test_fitted_artifact_changes_only_the_64_output_weights(self):
        fitted = ridge.fit_head(self.initializer, self.design(), .01)

        self.assertEqual(["outputWeights"], [key for key in self.initializer
                                             if fitted[key] != self.initializer[key]])
        self.assertEqual(64, len(fitted["outputWeights"]))
        self.assertEqual(.5, fitted["outputBias"])
        self.assertEqual(ridge.basis_digest(self.initializer), ridge.basis_digest(fitted))
        self.assertEqual(q.MODEL_FIELDS, set(fitted))
        self.assertEqual(fitted, q.validate_model(fitted, "token"))

    def test_exported_head_retains_reference_inference(self):
        fitted = ridge.fit_head(self.initializer, self.design(), .01)
        view = observation(position=3)

        actual = q.ActionValue(fitted, "token")([view])[0].detach()
        expected = b.torch.tensor(q.reference_forward(fitted, view, "token"), dtype=b.torch.float64)

        b.torch.testing.assert_close(actual, expected, rtol=1e-12, atol=1e-12)

    def test_identical_design_and_penalty_produce_identical_head(self):
        design = self.design()

        first = ridge.fit_head(self.initializer, design, .001)
        second = ridge.fit_head(self.initializer, design, .001)

        self.assertEqual(first, second)

    def test_boolean_penalty_cannot_alias_the_numeric_unit_penalty(self):
        with self.assertRaisesRegex(ValueError, "Normalized ridge penalty must be a finite number"):
            ridge.fit_head(self.initializer, self.design(), True)

    def test_expired_design_deadline_does_not_start_another_fit(self):
        with self.assertRaisesRegex(ValueError, "deadline expired"):
            ridge.training_design(self.initializer, [row()], time.time()-1)


class SelectionTest(unittest.TestCase):
    def test_fixed_penalty_config_rejects_boolean_values(self):
        penalties = (*ridge.PENALTIES[:-1], True)

        with self.assertRaisesRegex(ValueError, "Normalized ridge penalty must be a finite number"):
            ridge.FitConfig(ridgePenalties=penalties).validate()

    def test_fixed_penalty_order_cannot_be_redeclared(self):
        with self.assertRaisesRegex(ValueError, "five normalized ridge penalties"):
            ridge.FitConfig(ridgePenalties=tuple(reversed(ridge.PENALTIES))).validate()

    def test_equal_validation_loss_prefers_stronger_regularization(self):
        weaker = {"ridgePenalty": .001, "validation": {"pairedMse": .1}}
        stronger = {"ridgePenalty": .1, "validation": {"pairedMse": .1}}

        self.assertEqual(stronger, ridge.select_candidate([stronger, weaker], .2))

    def test_initializer_is_retained_without_strict_validation_improvement(self):
        reports = [{"ridgePenalty": .01, "validation": {"pairedMse": .1}},
                   {"ridgePenalty": 1., "validation": {"pairedMse": .2}}]

        self.assertIsNone(ridge.select_candidate(reports, .1))


class RetainedInputTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bindings = retained_fixture(self.root)

    def test_original_initializer_is_loaded_without_a_new_random_basis(self):
        with mock.patch.object(q, "initialize", side_effect=AssertionError("New basis forbidden")):
            initializer, rows, provenance = ridge.retained_collection(self.bindings)

        self.assertEqual(self.bindings.initializerCanonicalSha256, b.digest(initializer))
        self.assertEqual(4, len(rows))
        self.assertEqual("fixed", provenance["signature"])

    def test_changed_raw_initializer_bytes_fail_before_loading(self):
        path = Path(self.bindings.initializer)
        path.write_bytes(path.read_bytes()+b" ")

        with self.assertRaisesRegex(ValueError, "original initializer or prior fit identity changed"):
            ridge.retained_collection(self.bindings)

    def test_changed_collection_manifest_bytes_fail_the_source_pin(self):
        path = Path(self.bindings.collection) / "dataset.json"
        path.write_bytes(path.read_bytes()+b" ")

        with self.assertRaisesRegex(ValueError, "retained collection"):
            ridge.retained_collection(self.bindings)

    def test_coherently_repinned_new_basis_cannot_replace_the_original_initializer(self):
        different = q.initialize(schema(), 991, "token")
        b.atomic_json(self.bindings.initializer, different)
        changed = replace(self.bindings, initializerSha256=b.file_digest(self.bindings.initializer),
                          initializerCanonicalSha256=b.digest(different))

        with self.assertRaisesRegex(ValueError, "exact basis from the previous fit"):
            ridge.retained_collection(changed)

    def test_repinned_origin_labels_cannot_replace_the_previously_validated_collection(self):
        bank = Path(self.bindings.collection)
        path = bank / "origin-0.json"
        document = b.read_json(path)
        document["alternatives"][1]["outcomes"][0].update(reward=.9, winShare=.9, rankReward=.9)
        b.atomic_json(path, document)
        finish_collection(bank)
        changed = replace(self.bindings, finishedSha256=b.file_digest(bank / "finished.json"))

        with self.assertRaisesRegex(ValueError, "exact previously validated collection"):
            ridge.retained_collection(changed)

    def test_reused_validation_cannot_be_declared_fresh(self):
        changed = replace(self.bindings, validationPreviouslyInspected=False)

        with self.assertRaisesRegex(ValueError, "previously inspected validation"):
            changed.validate()


class SyntheticFitTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_full_fit_freezes_five_heads_and_records_selection_only_receipts(self):
        bindings = retained_fixture(self.root / "inputs")
        output = self.root / "fit"

        report = ridge.fit(bindings, output, ridge.FitConfig(maxSeconds=30))

        self.assertEqual(list(ridge.PENALTIES), [item["ridgePenalty"] for item in report["fits"]])
        self.assertTrue(report["selectionOnly"])
        self.assertTrue(report["validationPreviouslyInspected"])
        self.assertFalse(report["qualificationPassed"])
        self.assertFalse(report["productionActivation"])
        identity = b.read_json(output / "identity.json")
        self.assertEqual(token.PROPOSALS, identity["proposalVersion"])
        self.assertIn("omits14 around baseline16", identity["proposalLimitation"])
        receipt = b.read_json(output / "finished.json")
        self.assertEqual(b.file_digest(output / "fit.json"), receipt["reportSha256"])
        for name, digest in receipt["artifacts"].items():
            self.assertEqual(digest, b.file_digest(output / name), name)
        self.assertFalse(b.read_json(output / "training-design.json")["validationUsed"])

    def test_validation_only_label_and_feature_changes_cannot_change_heads_or_scales(self):
        first = retained_fixture(self.root / "original")
        changed = retained_fixture(self.root / "different-validation", change_validation=True)
        first_output, changed_output = self.root / "first-fit", self.root / "changed-fit"

        ridge.fit(first, first_output, ridge.FitConfig(maxSeconds=30))
        ridge.fit(changed, changed_output, ridge.FitConfig(maxSeconds=30))

        self.assertEqual((first_output / "training-design.json").read_bytes(),
                         (changed_output / "training-design.json").read_bytes())
        for index in range(len(ridge.PENALTIES)):
            name = f"model-{index}.json"
            self.assertEqual((first_output / name).read_bytes(), (changed_output / name).read_bytes())


if __name__ == "__main__":
    unittest.main()
