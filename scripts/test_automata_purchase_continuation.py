"""Purchase-only roles, original Adam carry, bounded gates and immutable clocks."""

import argparse
import contextlib
import copy
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import automata_evolution as evolution
import automata_experiments as experiments
import automata_purchase_continuation as continuation
import purchase_storage as storage


def metrics(win=.1, reward=.2, lower=.05, count=4096):
    return {name: {"mean": mean, "count": count, "lower95": lower, "upper95": mean + .01}
            for name, mean in (("winShareDifference", win), ("rewardDifference", reward))}


class PurchaseContinuationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.baseline, self.prior, self.policy, self.adam = [self.root / name for name in
            ("f1.json", "f0.json", "p32.json", "adam.json")]
        experiments.document(self.baseline, {"schemaVersion": 1, "weights": {"cash": 2.5}})
        experiments.document(self.prior, {"schemaVersion": 1, "weights": {"cash": 1.0}})
        experiments.document(self.policy, {"schemaVersion": 1, "actorWeights": [0.1]})
        self.payload = {"schemaVersion": 1, "model": experiments.read(self.policy),
                        "optimizer": {"state": {"0": {"step": 4}}, "param_groups": [{"lr": .0003}]},
                        "deadlineEpochSeconds": 10}
        self.checkpoint(self.adam, self.payload)

    def checkpoint(self, path, payload):
        experiments.document(path, {"sha256": continuation.digest(payload), "payload": payload}, immutable=False)

    def protocol(self, profile="search"):
        return {"profile": profile, **continuation.PROFILES[profile], "baseline": str(self.baseline),
                "prior": str(self.prior), "purchasePolicy": str(self.policy), "optimizerFrom": str(self.adam),
                "opponents": [str(self.prior)], "robustOpponents": [str(self.baseline)],
                "configuration": experiments.CONFIGURATION, "seed": 7300000, "qualificationConfidence": .99,
                "workers": 6, "scripts": str(Path(continuation.__file__).parent),
                "numerical": {"executable": "/pinned/python", "torchVersion": "2.8.0", "pythonVersion": "test-python",
                              "configDefaults": dict(continuation.HYPERPARAMETERS, seed=0,
                                                     cardReferenceKlCoefficient=0, cardReferenceVersion="frozen-reference")}}

    def test_collection_keeps_ordinary_f1_and_shared_f0_prior_with_p32_rivals(self):
        protocol = self.protocol()
        source = continuation.collection_source(protocol, "updated.json", 2)
        self.assertEqual(str(self.baseline), source["baseline"])
        self.assertEqual(str(self.prior), source["sharedPurchaseBaseline"])
        self.assertEqual(str(self.policy), source["opponentPurchasePolicy"])
        self.assertEqual("updated.json", source["policy"])
        self.assertEqual(7300002, source["seed"])
        self.assertEqual(256, source["games"])

    def test_arena_changes_only_the_controlled_candidate_purchase_actor(self):
        protocol = self.protocol()
        for bank in ("development", "confirmation", "robustness"):
            source = continuation.arena_source(protocol, "updated.json", bank)
            with self.subTest(bank=bank):
                self.assertEqual(str(self.baseline), source["candidate"])
                self.assertEqual(str(self.baseline), source["incumbent"])
                self.assertEqual({"baseline": str(self.prior), "policy": str(self.policy),
                                  "candidatePolicy": "updated.json", "incumbentPolicy": str(self.policy)}, source["frozenPurchase"])
                self.assertEqual(protocol["robustOpponents" if bank == "robustness" else "opponents"], source["opponents"])

    def test_optimizer_carry_requires_the_exact_input_actor(self):
        changed = dict(self.payload, model={"schemaVersion": 1, "actorWeights": [.2]})
        self.checkpoint(self.adam, changed)
        with self.assertRaisesRegex(ValueError, "exact input actor"):
            continuation.checkpoint(self.adam, self.policy)

    def test_optimizer_carry_rejects_changed_learning_rate(self):
        changed = copy.deepcopy(self.payload)
        changed["optimizer"]["param_groups"][0]["lr"] = .001
        self.checkpoint(self.adam, changed)
        with self.assertRaisesRegex(ValueError, "original learning rate"):
            continuation.checkpoint(self.adam, self.policy)

    def test_optimizer_checkpoint_requires_its_payload_checksum(self):
        experiments.document(self.adam, {"payload": self.payload, "sha256": "wrong"}, immutable=False)
        with self.assertRaisesRegex(ValueError, "checksum"):
            continuation.checkpoint(self.adam, self.policy)

    def test_fresh_update_config_retains_original_objective_and_uses_fresh_seed(self):
        config = continuation.update_config(self.protocol(), 1)
        self.assertEqual(dict(continuation.HYPERPARAMETERS, seed=7301001,
                              cardReferenceKlCoefficient=0, cardReferenceVersion="frozen-reference"), config)

    def test_actual_world_banks_are_distinct_and_share_only_declared_development_bank(self):
        banks = continuation.seed_banks(7300000, continuation.PROFILES["search"], [])
        worlds = [seed for bank in banks.values() for seed in bank]
        self.assertEqual(16 * 256 + 512 + 2048 + 4096, len(worlds))
        self.assertEqual(len(worlds), len(set(worlds)))
        self.assertEqual(experiments.arena_seeds(7300020, 512), banks["development"])
        self.assertEqual(experiments.arena_seeds(7300022, 4096), banks["robustness"])

    def test_seed_overlap_is_rejected_even_after_arena_stride_shift(self):
        reserved = experiments.arena_seeds(7300001, 4)
        with self.assertRaisesRegex(ValueError, "overlap"):
            continuation.seed_banks(7300000 + 8191, continuation.PROFILES["smoke"], reserved)

    def test_conservative_offset_audit_rejects_actual_overlap_without_expanding_world_lists(self):
        with self.assertRaisesRegex(ValueError, "historical bank"):
            continuation.seed_banks(7300000 + 8191, continuation.PROFILES["search"], [], [7300001])
        banks = continuation.seed_banks(7300000 + 3 * 8191, continuation.PROFILES["smoke"], [], [7300001], 2)
        self.assertEqual(2, len(banks["training-01"]))

    def test_original_collapse_guard_requires_negative_mean_and_negative_upper_bound(self):
        value = metrics(reward=-.11)
        self.assertTrue(continuation.collapsed(value))
        value["rewardDifference"]["upper95"] = 0
        self.assertFalse(continuation.collapsed(value))
        self.assertFalse(continuation.collapsed(metrics(reward=-.1)))

    def test_any_later_collapse_disqualifies_an_earlier_positive_checkpoint(self):
        rows = [{"batch": 4, "metrics": metrics()}, {"batch": 8, "metrics": metrics(reward=-.2)},
                {"batch": 16, "metrics": metrics()}]
        self.assertIsNone(continuation.select_checkpoint(rows, [4, 8, 16]))

    def test_selection_requires_all_checkpoints_and_both_positive_means(self):
        with self.assertRaisesRegex(ValueError, "every declared"):
            continuation.select_checkpoint([{ "batch": 4, "metrics": metrics()}], [4, 8, 16])
        rows = [{"batch": 4, "metrics": metrics(win=.2, reward=0)},
                {"batch": 8, "metrics": metrics(win=.1)}, {"batch": 16, "metrics": metrics(win=.1)}]
        self.assertEqual(8, continuation.select_checkpoint(rows, [4, 8, 16])["batch"])

    def test_final_99_percent_gate_rejects_positive_95_percent_signal_and_smoke(self):
        weak = metrics(win=.02, reward=.02, lower=.001)
        self.assertFalse(evolution.qualification_passes(self.protocol(), weak, "robustness"))
        positive = metrics(lower=.09)
        self.assertTrue(evolution.qualification_passes(self.protocol(), positive, "robustness"))
        self.assertFalse(evolution.qualification_passes(self.protocol("smoke"), positive, "robustness"))
        self.assertFalse(evolution.qualification_passes(self.protocol(), metrics(lower=.09, count=4095), "robustness"))

    def test_update_clock_is_fresh_from_old_adam_but_never_renewed_on_resume(self):
        path = self.root / "update.clock.json"
        environment = {"AOR_CAMPAIGN_DEADLINE_EPOCH": "3000", "AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH": "2000"}
        with mock.patch.dict(os.environ, environment), mock.patch.object(continuation.time, "time", return_value=1000):
            self.assertEqual(2000, continuation.update_clock(path))
        original = path.read_bytes()
        with mock.patch.dict(os.environ, environment), mock.patch.object(continuation.time, "time", return_value=1500):
            self.assertEqual(2000, continuation.update_clock(path))
        self.assertEqual(original, path.read_bytes())
        self.assertNotEqual(self.payload["deadlineEpochSeconds"], experiments.read(path)["deadlineEpoch"])
        with mock.patch.dict(os.environ, dict(environment, AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH="2100")), \
                mock.patch.object(continuation.time, "time", return_value=1500), self.assertRaisesRegex(ValueError, "original controller clock"):
            continuation.update_clock(path)

    def test_expired_original_update_clock_cannot_resume(self):
        path = self.root / "update.clock.json"
        experiments.document(path, {"startedEpoch": 1000, "deadlineEpoch": 2000})
        with mock.patch.dict(os.environ, AOR_CAMPAIGN_DEADLINE_EPOCH="3000", AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH="2000"), \
                mock.patch.object(continuation.time, "time", return_value=2001), self.assertRaisesRegex(ValueError, "expired"):
            continuation.update_clock(path)

    def test_update_binding_names_exact_adam_and_omits_extra_objectives(self):
        protocol = self.protocol()
        config = self.root / "config.json"
        experiments.document(config, continuation.update_config(protocol, 1))
        binding = continuation.update_binding(protocol, self.policy, self.adam, config, {"games": 2})
        self.assertEqual(experiments.sha(self.adam), binding["optimizerFromSha256"])
        self.assertEqual(experiments.sha(self.policy), binding["modelSha256"])
        self.assertEqual({"schemaVersion", "actorVersion", "modelSha256", "data", "config", "learnerSha256",
                          "torchVersion", "pythonVersion", "optimizerFromSha256"}, set(binding))

    def test_completed_update_resume_retains_actor_optimizer_identity_and_clock(self):
        protocol = self.protocol("smoke")
        config, directory = self.root / "config.json", self.root / "update"
        experiments.document(config, continuation.update_config(protocol, 1))
        data = {"games": 2, "gameMeanReward": .3}
        identity = dict(continuation.update_binding(protocol, self.policy, self.adam, config, data),
                        deadlineEpochSeconds=1500)
        experiments.document(directory / "identity.json", identity)
        actor = {"schemaVersion": 1, "actorWeights": [.2]}
        experiments.document(directory / "policy.json", actor)
        payload = dict(self.payload, model=actor, deadlineEpochSeconds=1500,
                       identitySha256=continuation.digest(identity), epoch=1,
                       history=[{"accepted": True}], stopReason=None, elapsedSeconds=.5)
        self.checkpoint(directory / "checkpoint.json", payload)
        report = {"identitySha256": continuation.digest(identity), "inputModelSha256": experiments.sha(self.policy),
                  "outputModelSha256": experiments.sha(directory / "policy.json"), "games": 2, "decisions": 4,
                  "gameMeanReward": .3, "acceptedEpochs": 1, "history": payload["history"],
                  "stopReason": "epochs-complete", "elapsedSeconds": .5, "strengthClaim": False}
        experiments.document(directory / "finished.json", report)
        environment = {"AOR_CAMPAIGN_DEADLINE_EPOCH": "3000", "AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH": "2000"}
        with mock.patch.dict(os.environ, environment), mock.patch.object(continuation.time, "time", return_value=1000), \
                mock.patch.object(continuation.subprocess, "run") as run:
            self.assertEqual(report, continuation.update(protocol, self.policy, self.adam, config,
                             self.root / "collection", directory, data, 4))
        original_clock = directory.with_suffix(".clock.json").read_bytes()
        with mock.patch.dict(os.environ, environment), mock.patch.object(continuation.time, "time", return_value=1200), \
                mock.patch.object(continuation.subprocess, "run") as resumed:
            self.assertEqual(report, continuation.update(protocol, self.policy, self.adam, config,
                             self.root / "collection", directory, data, 4))
        run.assert_not_called()
        resumed.assert_not_called()
        self.assertEqual(original_clock, directory.with_suffix(".clock.json").read_bytes())
        self.assertEqual(1500, experiments.read(directory / "identity.json")["deadlineEpochSeconds"])

    def collection_fixture(self):
        protocol = self.protocol("smoke")
        source, directory = self.root / "collection-source.json", self.root / "collection"
        specification = continuation.collection_source(protocol, self.policy, 1)
        experiments.document(source, specification)
        count = specification["games"]
        seeds = experiments.arena_seeds(specification["seed"], count)
        manifest = {"kind": "purchase-collect", "signature": "frozen-collection", "source": str(source),
            "sourceSha256": experiments.sha(source), "configuration": protocol["configuration"],
            "games": count, "seed": specification["seed"], "seeds": seeds,
            "baseline": continuation.path_identity(self.baseline),
            "opponents": list(map(continuation.path_identity, protocol["opponents"])),
            "policy": str(self.policy), "modelSha256": experiments.sha(self.policy), "mode": "on-policy",
            "encoderVersion": continuation.ENCODER, "actorVersion": continuation.ACTOR,
            "sharedPurchaseBaseline": continuation.path_identity(self.prior),
            "opponentPurchasePolicy": continuation.path_identity(self.policy)}
        experiments.document(directory / "collection.json", manifest)
        snapshots = {"source.json": source, "baseline.json": self.baseline, "policy.json": self.policy,
                     "shared-purchase-baseline.json": self.prior, "opponent-purchase-policy.json": self.policy,
                     "opponent-0.json": self.prior}
        for name, path in snapshots.items():
            evolution.freeze_copy(path, directory / name)
        hashes = []
        for index, seed in enumerate(seeds):
            row = {"index": index, "signature": manifest["signature"], "configuration": protocol["configuration"],
                   "gameSeed": seed, "controlledSeat": index % 4, "opponentIndices": [0] * 4,
                   "mode": "on-policy", "modelSha256": experiments.sha(self.policy),
                   "encoderVersion": continuation.ENCODER, "actorVersion": continuation.ACTOR,
                   "terminal": {"reward": .3}, "decisions": [{"number": 0}]}
            path = directory / f"game-{index}.json"
            experiments.document(path, row)
            hashes.append(experiments.sha(path))
        identity = {"kind": "purchase-collect", "signature": manifest["signature"], "games": count}
        experiments.document(directory / "state.json", dict(identity, phase="complete", completedGames=count))
        experiments.document(directory / "finished.json", dict(identity, gameSha256=hashes))
        return protocol, source, directory

    def test_completed_collection_binds_roles_snapshots_and_ordered_world_provenance(self):
        protocol, source, directory = self.collection_fixture()
        data, decisions = continuation.verify_collection(protocol, source, directory)
        self.assertEqual(2, data["games"])
        self.assertEqual(2, decisions)
        self.assertEqual(.3, data["gameMeanReward"])
        self.assertEqual({"collectionSha256", "collectionSignature", "gameHashes", "games", "gameMeanReward"}, set(data))

    def test_completed_collection_rejects_replacing_shared_f0_snapshot_with_ordinary_f1(self):
        protocol, source, directory = self.collection_fixture()
        (directory / "shared-purchase-baseline.json").write_bytes(self.baseline.read_bytes())
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            continuation.verify_collection(protocol, source, directory)

    def test_preparation_freezes_assets_configuration_and_absolute_stage_caps(self):
        source, runtime, java = self.root / "qualified", self.root / "runtime", self.root / "java"
        experiments.document(source / "campaign.json", {"kind": "automata-campaign"})
        experiments.document(source / "protocol.json", {})
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"new-native-runtime")
        java.write_bytes(b"jdk")
        java_identity = {"executable": str(java), "files": {str(java): experiments.sha(java)}}
        source_protocol = dict(self.protocol(), java=java_identity, seedBanks={"old": experiments.arena_seeds(7200000, 4)})
        config = self.root / "original-config.json"
        experiments.document(config, dict(continuation.HYPERPARAMETERS, seed=74232))
        numerical_file = self.root / "numpy-native"
        numerical_file.write_bytes(b"numpy-and-torch-pinned")
        numerical = dict(self.protocol()["numerical"], files={str(numerical_file): experiments.sha(numerical_file)})
        audit = self.root / "historical-audit.json"
        experiments.document(audit, {"conservativelyExpandedOffsets": [7200000, 2000000, 2000001, 2000002],
                                    "conservativePairsPerOffset": 10000, "inspectedMetadataSha256": {str(config): experiments.sha(config)}})
        output = self.root / "prepared"
        options = argparse.Namespace(from_campaign=source, baseline=self.baseline, prior=self.prior,
            purchase_policy=self.policy, optimizer_from=self.adam, runtime=runtime, output=output,
            workers=1, maximum_seconds=28800, update_config=config, opponent=[], robust_opponent=[],
            seed=7300000, profile="search", numerical_python=Path("/pinned/python"), seed_audit=audit)
        with mock.patch.object(continuation, "P32_SHA", experiments.sha(self.policy)), \
                mock.patch.object(continuation, "ADAM_SHA", experiments.sha(self.adam)), \
                mock.patch.object(continuation, "qualified_source", return_value=source_protocol), \
                mock.patch.object(experiments, "java_identity", return_value=java_identity), \
                mock.patch.object(continuation, "numerical_identity", return_value=numerical), \
                contextlib.redirect_stdout(io.StringIO()):
            campaign = continuation.prepare(options)
        protocol = experiments.read(output / "protocol.json")
        self.assertEqual([14400, 7200, 7200], [stage["maximumSeconds"] for stage in campaign["stages"]])
        self.assertEqual(4 * experiments.GIB, campaign["limits"]["maximumDataBytes"])
        self.assertEqual(2 * experiments.GIB, campaign["limits"]["minimumFreeBytes"])
        self.assertEqual([[], ["train"], ["confirm"]], [stage["dependsOn"] for stage in campaign["stages"]])
        self.assertEqual([4, 8, 16], protocol["checkpoints"])
        self.assertEqual(4096, protocol["robustnessPairs"])
        self.assertEqual(.99, protocol["qualificationConfidence"])
        for path, digest in campaign["inputs"].items():
            self.assertEqual(experiments.sha(output / path), digest)
        self.assertEqual(experiments.read(self.adam), experiments.read(protocol["optimizerFrom"]))
        self.assertEqual([7200000, 2000000, 2000001, 2000002], protocol["reservedOffsets"])
        self.assertEqual(audit.read_bytes(), Path(protocol["seedAudit"]).read_bytes())
        self.assertEqual(16, len(list((output / "configs").glob("*.json"))))
        self.assertEqual("lossless-batch-archive-v1", protocol["collectionStorage"])

    def parent_fixture(self):
        root = self.root / "parent"
        protocol = dict(self.protocol(), kind="automata-purchase-continuation", configDirectory=str(root / "configs"),
                        originalUpdateConfig=str(root / "inputs/original-config.json"),
                        seedBanks={"old": experiments.arena_seeds(7200000, 4)})
        experiments.document(Path(protocol["originalUpdateConfig"]), dict(continuation.HYPERPARAMETERS, seed=1))
        for index in range(1, 17):
            experiments.document(root / f"configs/update-{index:02}.json", continuation.update_config(protocol, index))
        experiments.document(root / "protocol.json", protocol)
        inputs = {str(path.relative_to(root)): experiments.sha(path) for path in root.rglob("*") if path.is_file()}
        inputs.update({str(path): experiments.sha(path) for path in (self.baseline, self.prior, self.policy, self.adam,
            Path(protocol["scripts"]) / "purchase_policy.py")})
        experiments.document(root / "campaign.json", {"kind": "automata-campaign", "inputs": inputs})
        batch = root / "run/batches/batch-16"
        evolution.freeze_copy(self.policy, batch / "update/policy.json")
        evolution.freeze_copy(self.adam, batch / "update/checkpoint.json")
        experiments.document(batch / "receipt.json", {"artifacts": evolution.artifact_hashes(batch)})
        selected = evolution.freeze_copy(self.policy, root / "run/selected/policy.json")
        rows = [{"batch": index, "policy": str(batch / "update/policy.json"),
                 "policySha256": experiments.sha(self.policy), "metrics": metrics(win=index / 100)}
                for index in (4, 8, 16)]
        experiments.document(root / "run/selection.json", {"development": rows, "selected": rows[-1],
                             "policy": str(selected), "policySha256": experiments.sha(selected)})
        for bank in ("confirmation", "robustness"):
            experiments.document(root / "run" / (bank + "-source.json"),
                                 continuation.arena_source(protocol, selected, bank))
        stages = {}
        for name, bank, count in (("train", None, None), ("confirm", "confirmation", 2048), ("robustness", "robustness", 4096)):
            if bank:
                values = evolution.qualification_estimates(protocol, metrics(count=count, lower=.09))
                values["policySha256"] = experiments.sha(selected)
                experiments.document(root / "run" / (name + "-report.json"),
                    {"metrics": values, "accepted": name == "robustness"})
                files = [root / "run" / (name + "-report.json")]
            else:
                files = [root / "run/selection.json", root / "run/confirmation-source.json", root / "run/robustness-source.json",
                         selected, *[path for path in batch.rglob("*") if path.is_file()]]
            result = {"status": "passed", "artifacts": {str(path.relative_to(root / "run")): experiments.sha(path) for path in files}}
            path = root / "run/results" / (name + ".json")
            experiments.document(path, result)
            stages[name] = dict(result, resultSha256=experiments.sha(path))
        experiments.document(root / "run/campaign-state.json", {"status": "completed", "stages": stages})
        return root

    def test_qualified_purchase_parent_binds_selected_actor_and_exact_adam(self):
        root = self.parent_fixture()
        evidence, protocol = continuation.purchase_parent(root, self.baseline, self.prior, self.policy, self.adam)
        self.assertEqual(16, evidence["selectedBatch"])
        self.assertEqual(experiments.sha(self.adam), evidence["optimizerSha256"])
        self.assertIn(str(root / "run/batches/batch-16/update/checkpoint.json"), evidence["verifiedFiles"])
        self.assertEqual(str(self.baseline), protocol["baseline"])

    def test_parent_preparation_freezes_selected_lineage_and_incumbent_roles(self):
        parent = self.parent_fixture()
        source, runtime, output = self.root / "ordinary-source", self.root / "runtime", self.root / "prepared"
        experiments.document(source / "campaign.json", {"kind": "automata-campaign"})
        experiments.document(source / "protocol.json", {})
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"runtime")
        java = self.root / "java"
        java.write_bytes(b"java")
        java_identity = {"executable": str(java), "files": {str(java): experiments.sha(java)}}
        numerical = dict(self.protocol()["numerical"], files={str(java): experiments.sha(java)})
        original = dict(self.protocol(), java=java_identity, seedBanks={"old": experiments.arena_seeds(7100000, 4)})
        audit = self.root / "audit.json"
        experiments.document(audit, {"conservativelyExpandedOffsets": [7100000, 7200000]})
        options = argparse.Namespace(from_campaign=source, from_purchase_campaign=parent,
            baseline=self.baseline, prior=self.prior, purchase_policy=self.policy, optimizer_from=self.adam,
            runtime=runtime, output=output, workers=1, maximum_seconds=28800,
            update_config=parent / "inputs/original-config.json", opponent=[], robust_opponent=[],
            seed=7300000, profile="search", numerical_python=Path("/pinned/python"), seed_audit=audit)
        with mock.patch.object(continuation, "qualified_source", return_value=original) as qualified, \
                mock.patch.object(experiments, "java_identity", return_value=java_identity), \
                mock.patch.object(continuation, "numerical_identity", return_value=numerical), \
                contextlib.redirect_stdout(io.StringIO()):
            campaign = continuation.prepare(options)
        qualified.assert_called_once_with(source, self.baseline, self.prior, None)
        protocol = experiments.read(output / "protocol.json")
        evidence = experiments.read(protocol["purchaseParent"])
        self.assertEqual(experiments.sha(self.adam), evidence["optimizerSha256"])
        self.assertEqual(self.policy.read_bytes(), Path(protocol["purchasePolicy"]).read_bytes())
        self.assertEqual(str(Path(protocol["purchasePolicy"])), continuation.collection_source(protocol, "next.json", 1)["opponentPurchasePolicy"])
        self.assertEqual((parent / "run/selection.json").read_bytes(),
                         (output / "inputs/purchase-parent/run/selection.json").read_bytes())
        for path, expected in campaign["inputs"].items():
            self.assertEqual(expected, experiments.sha(output / path))

    def test_purchase_parent_rejects_different_adam_even_when_actor_matches(self):
        root = self.parent_fixture()
        different = self.root / "different-adam.json"
        payload = copy.deepcopy(self.payload)
        payload["optimizer"]["state"]["0"]["step"] = 5
        self.checkpoint(different, payload)
        with self.assertRaisesRegex(ValueError, "exact selected batch"):
            continuation.purchase_parent(root, self.baseline, self.prior, self.policy, different)

    def test_purchase_parent_rejects_changed_ordinary_environment(self):
        root = self.parent_fixture()
        with self.assertRaisesRegex(ValueError, "ordinary model"):
            continuation.purchase_parent(root, self.prior, self.prior, self.policy, self.adam)

    def test_purchase_parent_rejects_missing_nonrow_evidence(self):
        root = self.parent_fixture()
        (root / "run/confirm-report.json").unlink()
        with self.assertRaisesRegex(ValueError, "Unexplained missing"):
            continuation.purchase_parent(root, self.baseline, self.prior, self.policy, self.adam)

    def test_parent_requires_completed_qualification(self):
        root = self.parent_fixture()
        state = experiments.read(root / "run/campaign-state.json")
        state["stages"]["robustness"]["status"] = "rejected"
        experiments.document(root / "run/campaign-state.json", state, immutable=False)
        with self.assertRaisesRegex(ValueError, "both 99%"):
            continuation.purchase_parent(root, self.baseline, self.prior, self.policy, self.adam)

    def test_completed_compressed_batch_resume_does_not_restore_or_recollect(self):
        protocol, source, collection = self.collection_fixture()
        protocol.update(collectionStorage="lossless-batch-archive-v1", batches=1, checkpoints=[1])
        output = self.root / "run"
        batch = output / "batches/batch-01"
        batch.mkdir(parents=True)
        collection.rename(batch / "collection")
        source = batch / "collection-source.json"
        experiments.document(source, continuation.collection_source(protocol, self.policy, 1))
        evolution.freeze_copy(self.policy, batch / "update/policy.json")
        evolution.freeze_copy(self.adam, batch / "update/checkpoint.json")
        storage.archive(batch)
        descriptor = {"batch": 1, "sourceSha256": experiments.sha(source), "policySha256": experiments.sha(self.policy),
                      "optimizerSha256": experiments.sha(self.adam)}
        measured = {"batch": 1, "policy": str(batch / "update/policy.json"), "policySha256": experiments.sha(self.policy),
                    "metrics": metrics(win=0, reward=0)}
        experiments.document(batch / "receipt.json", {"descriptor": descriptor, "update": {"acceptedEpochs": 1},
                             "development": measured, "artifacts": evolution.artifact_hashes(batch)})
        with mock.patch.object(storage, "restore") as restore, mock.patch.object(experiments, "native") as native, \
                contextlib.redirect_stdout(io.StringIO()):
            continuation.train_stage(protocol, output)
        restore.assert_not_called()
        native.assert_not_called()
        self.assertEqual([], list((batch / "collection").glob("game-*.json")))

    def test_unreceipted_archived_batch_restores_rows_without_recollecting_or_replacing_completion(self):
        protocol, original_source, collection = self.collection_fixture()
        protocol.update(collectionStorage="lossless-batch-archive-v1", batches=1, checkpoints=[],
                        configDirectory=str(self.root / "configs"))
        output = self.root / "run"
        batch = output / "batches/batch-01"
        batch.mkdir(parents=True)
        collection.rename(batch / "collection")
        source = batch / "collection-source.json"
        evolution.freeze_copy(original_source, source)
        manifest = experiments.read(batch / "collection/collection.json")
        manifest["source"] = str(source)
        experiments.document(batch / "collection/collection.json", manifest, immutable=False)
        evolution.freeze_copy(self.policy, batch / "update/policy.json")
        evolution.freeze_copy(self.adam, batch / "update/checkpoint.json")
        storage.archive(batch)
        archive_hash = experiments.sha(batch / storage.ARCHIVE)
        finished = (batch / "collection/finished.json").read_bytes()
        def resumed_update(*arguments):
            self.assertEqual(2, len(list((batch / "collection").glob("game-*.json"))))
            return {"acceptedEpochs": 1}
        with mock.patch.object(continuation, "update", side_effect=resumed_update), \
                mock.patch.object(experiments, "native") as native, contextlib.redirect_stdout(io.StringIO()):
            continuation.train_stage(protocol, output)
        native.assert_not_called()
        self.assertEqual(finished, (batch / "collection/finished.json").read_bytes())
        self.assertEqual(archive_hash, experiments.sha(batch / storage.ARCHIVE))
        self.assertEqual([], list((batch / "collection").glob("game-*.json")))
        self.assertIn(storage.ARCHIVE, experiments.read(batch / "receipt.json")["artifacts"])


if __name__ == "__main__":
    unittest.main()
