"""Standard-library verification of legacy and opt-in token collection provenance."""

import json
from pathlib import Path
import tempfile
import unittest

import token_order_metadata as learner


def write(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def collection_fixture(root):
    source = {"schemaVersion": 1, "purpose": "SCREENING", "configurations": [learner.CONFIGURATION],
              "trainingRoots": 2, "validationRoots": 2, "continuationReplicates": 2, "seed": 13000000,
              "opponents": ["opponent.json"], "frozenPurchase": {"baseline": "prior.json", "policy": "actor.json"}}
    write(root / "source.json", source)
    models = ["initial.json", "incumbent.json", "opponent-0.json"]
    for name in models + ["frozen-purchase-baseline.json", "frozen-purchase-policy.json"]:
        write(root / name, {"weights": {}})
    manifest = dict(kind=learner.KIND, signature="fixed", proposalVersion=learner.PROPOSALS,
                    encoderVersion=learner.ENCODER, originSchemaVersion=1, selectionProtocol=learner.SELECTION,
                    configuration=learner.CONFIGURATION,
                    **{key: source[key] for key in ("trainingRoots", "validationRoots", "continuationReplicates", "seed")},
                    sourceSha256=learner.sha(root / "source.json"),
                    ordinaryModelSnapshots={name: learner.sha(root / name) for name in models},
                    frozenPurchase={role: {"snapshot": f"frozen-purchase-{role}.json",
                                          "sha256": learner.sha(root / f"frozen-purchase-{role}.json")}
                                    for role in ("baseline", "policy")})
    write_collection_metadata(root, source, manifest)
    write(root / "finished.json", {"kind": learner.KIND, "signature": "fixed"})


def write_collection_metadata(root, source, manifest):
    write(root / "source.json", source)
    manifest["sourceSha256"] = learner.sha(root / "source.json")
    write(root / "run.json", manifest)
    write(root / "dataset.json", dict(manifest, complete=True, counts={"COLLECTED": 4}))


def add_overlay_metadata(root):
    source, manifest = learner.read(root / "source.json"), learner.read(root / "run.json")
    source.update(continuationProtocol=learner.ARENA_CONTINUATION, replayOrigins=[0, 3],
                  candidate="ordinary.json", incumbent="ordinary.json",
                  opponents=[f"rival-{index}.json" for index in range(4)],
                  tokenOrder={"opponentSelectors": ["ORDINARY", "ORDINARY", "FIXED_16_TO_15", "FIXED_16_TO_15"]},
                  expansionImitation={"candidateModel": "expansion.json",
                      "opponentModels": [None, "expansion-rival-1.json", None, "expansion-rival-3.json"]})
    manifest.update(source=str((root / "source.json").resolve()), continuationProtocol=learner.ARENA_CONTINUATION,
                    replayOrigins=[0, 3], tokenOrder=source["tokenOrder"])
    for name, cash in [("initial.json", 1), ("incumbent.json", 1),
                       *[(f"opponent-{index}.json", index+1) for index in range(4)]]:
        write(root / name, {"schemaVersion": 1, "weights": {"cash": cash, "tokens": 2.1}})
        manifest["ordinaryModelSnapshots"][name] = learner.sha(root / name)
    manifest["tokenReference"] = {"version": learner.TOKEN_REFERENCE_VERSION,
        "ordinaryModelSnapshot": "initial.json", "ordinaryModelSha256": learner.sha(root / "initial.json")}
    for role, path in source["frozenPurchase"].items():
        manifest["frozenPurchase"][role]["path"] = str((root / path).resolve())

    def expansion(reference, name, cash):
        write(root / name, {"schemaVersion": 1, "weights": {"cash": cash, "tokens": .3}})
        return {"path": str((root / reference).resolve()), "snapshot": name, "sha256": learner.sha(root / name)}

    manifest["expansionImitation"] = {"version": "expansion-imitation-1", "decision": "expand",
        "featureNames": list(learner.EXPANSION_FEATURES),
        "candidateModel": expansion("expansion.json", "expansion-imitation-candidate.json", 1),
        "opponentModels": [None, expansion("expansion-rival-1.json", "expansion-imitation-opponent-1.json", 2),
                           None, expansion("expansion-rival-3.json", "expansion-imitation-opponent-3.json", 4)]}
    write_collection_metadata(root, source, manifest)


class LegacyMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        collection_fixture(self.root)

    def collect(self):
        return learner.collection_metadata(self.root, learner.read(self.root / "dataset.json"),
                                           learner.read(self.root / "finished.json"))

    def test_omitted_protocol_retains_exact_legacy_snapshot_pins(self):
        source, pins = self.collect()
        expected = {name: learner.sha(self.root / name) for name in
                    ("source.json", "initial.json", "incumbent.json", "opponent-0.json",
                     "frozen-purchase-baseline.json", "frozen-purchase-policy.json")}
        self.assertEqual(learner.read(self.root / "source.json"), source)
        self.assertEqual(expected, pins)

    def test_source_snapshot_change_is_rejected(self):
        with (self.root / "source.json").open("a") as stream:
            stream.write(" ")
        with self.assertRaisesRegex(ValueError, "source snapshot changed"):
            self.collect()

    def test_changed_purchase_snapshot_is_rejected(self):
        write(self.root / "frozen-purchase-policy.json", {"weights": {"cash": 1}})
        with self.assertRaisesRegex(ValueError, "environment snapshot changed"):
            self.collect()

    def test_boolean_root_count_is_rejected(self):
        source, manifest = learner.read(self.root / "source.json"), learner.read(self.root / "run.json")
        source["trainingRoots"] = manifest["trainingRoots"] = True
        write_collection_metadata(self.root, source, manifest)
        with self.assertRaisesRegex(ValueError, "must be an integer"):
            self.collect()

    def test_nonfinite_json_number_is_rejected_like_the_original_fitter(self):
        (self.root / "initial.json").write_text('{"weights": {"cash": NaN}}')
        with self.assertRaisesRegex(ValueError, "Nonfinite JSON number"):
            self.collect()


class ArenaContinuationMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        collection_fixture(self.root)
        add_overlay_metadata(self.root)

    def update(self, source_change=None, manifest_change=None):
        source, manifest = learner.read(self.root / "source.json"), learner.read(self.root / "run.json")
        if source_change:
            source_change(source)
        if manifest_change:
            manifest_change(manifest)
        write_collection_metadata(self.root, source, manifest)

    def collect(self):
        return learner.collection_metadata(self.root, learner.read(self.root / "dataset.json"),
                                           learner.read(self.root / "finished.json"))

    def test_opt_in_binds_all_non_null_expansion_snapshots(self):
        source, pins = self.collect()
        self.assertEqual(learner.ARENA_CONTINUATION, source["continuationProtocol"])
        self.assertIn("expansion-imitation-candidate.json", pins)
        self.assertIn("expansion-imitation-opponent-3.json", pins)
        self.assertNotIn("expansion-imitation-opponent-0.json", pins)

    def test_opponent_token_selectors_may_be_omitted(self):
        self.update(source_change=lambda source: source.pop("tokenOrder"),
                    manifest_change=lambda manifest: manifest.pop("tokenOrder"))
        source, _ = self.collect()
        self.assertNotIn("tokenOrder", source)

    def test_opponent_expansion_overlays_may_be_omitted(self):
        self.update(source_change=lambda source: source["expansionImitation"].pop("opponentModels"),
                    manifest_change=lambda manifest: manifest["expansionImitation"].pop("opponentModels"))
        _, pins = self.collect()
        self.assertIn("expansion-imitation-candidate.json", pins)
        self.assertNotIn("expansion-imitation-opponent-1.json", pins)

    def test_candidate_expansion_is_mandatory(self):
        self.update(source_change=lambda source: source["expansionImitation"].pop("candidateModel"),
                    manifest_change=lambda manifest: manifest["expansionImitation"].pop("candidateModel"))
        with self.assertRaisesRegex(ValueError, "requires candidate expansion"):
            self.collect()

    def test_expansion_feature_order_is_part_of_the_contract(self):
        self.update(manifest_change=lambda manifest: manifest["expansionImitation"]["featureNames"].reverse())
        with self.assertRaisesRegex(ValueError, "exact phase, feature dictionary"):
            self.collect()

    def test_purchase_environment_cannot_acquire_a_controlled_side_override(self):
        self.update(source_change=lambda source: source["frozenPurchase"].update(candidatePolicy="other.json"))
        with self.assertRaisesRegex(ValueError, "without side overrides"):
            self.collect()

    def test_legacy_source_cannot_silently_activate_arena_continuation(self):
        self.update(source_change=lambda source: source.pop("continuationProtocol"))
        with self.assertRaisesRegex(ValueError, "Legacy token collection"):
            self.collect()

    def test_unknown_continuation_version_is_rejected(self):
        self.update(source_change=lambda source: source.update(continuationProtocol="unknown"))
        with self.assertRaisesRegex(ValueError, "Unsupported token continuation"):
            self.collect()

    def test_controlled_token_selector_is_rejected(self):
        self.update(source_change=lambda source: source["tokenOrder"].update(candidateSelector="FIXED_16_TO_15"))
        with self.assertRaisesRegex(ValueError, "never controlled token overlays"):
            self.collect()

    def test_noncontract_rival_selector_is_rejected(self):
        self.update(source_change=lambda source: source["tokenOrder"].update(opponentSelectors=["ORDINARY_MINUS_TWO"]*4))
        with self.assertRaisesRegex(ValueError, "explicit ordinary-pool index"):
            self.collect()

    def test_reordered_rival_metadata_is_rejected(self):
        self.update(manifest_change=lambda manifest: manifest["tokenOrder"]["opponentSelectors"].reverse())
        with self.assertRaisesRegex(ValueError, "explicit ordinary-pool index"):
            self.collect()

    def test_replay_origins_must_be_distinct_and_in_the_completed_bank(self):
        for indices in ([0, 0], [4], [], [True]):
            with self.subTest(indices=indices):
                self.update(source_change=lambda source: source.update(replayOrigins=indices),
                            manifest_change=lambda manifest: manifest.update(replayOrigins=indices))
                with self.assertRaisesRegex(ValueError, "predeclared replay origins"):
                    self.collect()

    def test_reference_cannot_bind_another_ordinary_snapshot(self):
        self.update(manifest_change=lambda manifest: manifest["tokenReference"].update(ordinaryModelSha256="changed"))
        with self.assertRaisesRegex(ValueError, "exact ordinary candidate snapshot"):
            self.collect()

    def test_fixed_gate_cannot_be_hidden_in_the_ordinary_reference(self):
        for name in ("initial.json", "incumbent.json"):
            model = learner.read(self.root / name)
            model["weights"]["planning.tokenOrderControl.fixed16To15"] = 1
            write(self.root / name, model)

        def update(manifest):
            for name in ("initial.json", "incumbent.json"):
                manifest["ordinaryModelSnapshots"][name] = learner.sha(self.root / name)
            manifest["tokenReference"]["ordinaryModelSha256"] = learner.sha(self.root / "initial.json")

        self.update(manifest_change=update)
        with self.assertRaisesRegex(ValueError, "fixed token gate"):
            self.collect()

    def test_expansion_phase_cannot_change(self):
        self.update(manifest_change=lambda manifest: manifest["expansionImitation"].update(decision="buy-tokens"))
        with self.assertRaisesRegex(ValueError, "exact phase"):
            self.collect()

    def test_unused_incumbent_expansion_role_is_rejected(self):
        self.update(source_change=lambda source: source["expansionImitation"].update(incumbentModel="unused.json"))
        with self.assertRaisesRegex(ValueError, "candidate expansion and optional indexed rivals only"):
            self.collect()

    def test_changed_expansion_bytes_cannot_reuse_the_manifest_hash(self):
        write(self.root / "expansion-imitation-candidate.json", {"schemaVersion": 1, "weights": {}})
        with self.assertRaisesRegex(ValueError, "snapshot identity changed"):
            self.collect()

    def test_expansion_snapshot_cannot_claim_a_different_source_path(self):
        self.update(manifest_change=lambda manifest: manifest["expansionImitation"]["candidateModel"].update(path="/wrong/model.json"))
        with self.assertRaisesRegex(ValueError, "snapshot identity changed"):
            self.collect()

    def test_coherently_hashed_expansion_cannot_change_another_coefficient(self):
        name = "expansion-imitation-opponent-1.json"
        model = learner.read(self.root / name)
        model["weights"]["cash"] = 1
        write(self.root / name, model)
        self.update(manifest_change=lambda manifest: manifest["expansionImitation"]["opponentModels"][1].update(
            sha256=learner.sha(self.root / name)))
        with self.assertRaisesRegex(ValueError, "every serialized coefficient outside"):
            self.collect()

    def test_missing_and_explicit_prior_coefficients_are_not_silently_normalized(self):
        name = "expansion-imitation-candidate.json"
        model = learner.read(self.root / name)
        model["weights"]["military"] = 5.
        write(self.root / name, model)
        self.update(manifest_change=lambda manifest: manifest["expansionImitation"]["candidateModel"].update(
            sha256=learner.sha(self.root / name)))
        with self.assertRaisesRegex(ValueError, "every serialized coefficient outside"):
            self.collect()

    def test_null_rival_binding_cannot_acquire_an_expansion_snapshot(self):
        def update(manifest):
            expansion = manifest["expansionImitation"]
            expansion["opponentModels"][0] = expansion["candidateModel"]
        self.update(manifest_change=update)
        with self.assertRaisesRegex(ValueError, "ordinary rival cannot acquire"):
            self.collect()

    def test_purchase_snapshot_must_bind_its_declared_source_path(self):
        self.update(manifest_change=lambda manifest: manifest["frozenPurchase"]["policy"].update(path="/other/policy.json"))
        with self.assertRaisesRegex(ValueError, "snapshot identity changed"):
            self.collect()


if __name__ == "__main__":
    unittest.main()
