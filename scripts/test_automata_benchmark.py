"""Portable frozen workloads, exact world parity and bounded host-specific execution."""

import argparse
import copy
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest import mock

import automata_benchmark as benchmark


class PortableBenchmarkTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.source = self.root / "source"
        self.bundle = self.root / "bundle"
        self.offsets = {"collection": 12000000, "arena": 12000001}

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))
        return path

    def audit(self):
        return {"allIntersectionsZero": True, "seedBanks": {
            name: {"nativeOffset": self.offsets[name], "count": count,
                   "seeds": benchmark.worlds(self.offsets[name], count),
                   "historicalIntersectionCount": 0, "otherChallengeBankIntersectionCount": 0}
            for name, count in benchmark.COUNTS.items()}}

    def prepare(self):
        models = {name: self.write(self.source / f"inputs/{name}.json", {"name": name})
                  for name in ("baseline", "prior", "purchasePolicy", "opponent")}
        candidate = self.write(self.source / "run/selected/policy.json", {"name": "candidate"})
        self.write(self.source / "runtime/lib/training.jar", {"frozen": "JAR fixture"})
        protocol = {"kind": "automata-purchase-continuation",
                    **{name: str(path) for name, path in models.items() if name != "opponent"},
                    "opponents": [str(models["opponent"])], "runtime": str(self.source / "runtime")}
        self.write(self.source / "protocol.json", protocol)
        campaign = {"inputs": benchmark.tree_hashes(self.source)}
        self.write(self.source / "campaign.json", campaign)
        self.write(self.source / "provenance.json", {"source": "previous"})
        self.write(self.source / "run/campaign-state.json", {
            "status": "completed", "stages": {
                name: {"status": "passed", "artifacts": {"selected/policy.json": benchmark.sha(candidate)}}
                for name in ("train", "confirm", "robustness")}})
        for name in ("train", "confirm", "robustness"):
            self.write(self.source / f"run/{name}-report.json", {"stage": name})
        audit = self.write(self.root / "seed-audit.json", self.audit())
        benchmark.prepare(argparse.Namespace(campaign=self.source, output=self.bundle, seed_audit=audit,
                                            collection_offset=self.offsets["collection"],
                                            arena_offset=self.offsets["arena"]))

    def test_bundle_survives_relocation_without_original_inputs(self):
        self.prepare()
        relocated = self.root / "destination/bundle"
        relocated.parent.mkdir()
        shutil.move(str(self.bundle), relocated)
        shutil.rmtree(self.source)
        benchmark.bundle_manifest(relocated)
        source = benchmark.read(relocated / "sources/collection.json")
        self.assertEqual({"name": "candidate"}, benchmark.read((relocated / "sources" / source["policy"])))
        self.assertEqual(source["policy"], source["opponentPurchasePolicy"])

    def test_arena_changes_only_controlled_actor_and_keeps_candidate_rivals(self):
        value = benchmark.sources(self.offsets, ["../inputs/opponent-0.json"])["arena"]
        self.assertEqual(128, value["pairsPerConfiguration"])
        self.assertEqual("SCREENING", value["purpose"])
        self.assertEqual(value["candidate"], value["incumbent"])
        self.assertEqual(value["frozenPurchase"]["policy"], value["frozenPurchase"]["candidatePolicy"])
        self.assertNotEqual(value["frozenPurchase"]["policy"], value["frozenPurchase"]["incumbentPolicy"])

    def test_changed_model_is_rejected(self):
        self.prepare()
        self.write(self.bundle / "inputs/candidate.json", {"changed": True})
        with self.assertRaisesRegex(ValueError, "Frozen file changed"):
            benchmark.bundle_manifest(self.bundle)

    def test_unpinned_runtime_dependency_is_rejected(self):
        self.prepare()
        self.write(self.bundle / "runtime/lib/extra.jar", {})
        with self.assertRaisesRegex(ValueError, "unpinned dependencies"):
            benchmark.bundle_manifest(self.bundle)

    def test_audit_must_bind_exact_actual_worlds(self):
        audit = self.audit()
        audit["seedBanks"]["collection"]["seeds"][1] += 1
        with self.assertRaisesRegex(ValueError, "exact fresh"):
            benchmark.verify_audit(audit, self.offsets)

    def test_audit_cannot_reserve_extra_campaigns(self):
        audit = self.audit()
        audit["seedBanks"]["future"] = audit["seedBanks"]["collection"]
        with self.assertRaisesRegex(ValueError, "exactly the two"):
            benchmark.verify_audit(audit, self.offsets)

    def test_collection_parity_keeps_actions_likelihoods_and_terminal_results(self):
        original = {"signature": "host-a", "decisions": [{"selectedIndex": 1, "behaviorLogProbability": -.2}],
                    "terminal": {"reward": .5}}
        relocated = dict(original, signature="host-b")
        self.assertEqual(benchmark.canonical_record(original, "collection"),
                         benchmark.canonical_record(relocated, "collection"))
        changed = copy.deepcopy(relocated)
        changed["decisions"][0]["selectedIndex"] = 2
        self.assertNotEqual(benchmark.canonical_record(original, "collection"),
                            benchmark.canonical_record(changed, "collection"))
        self.assertIn("signature", original)

    def test_arena_parity_ignores_only_elapsed_time(self):
        original = {"candidate": {"elapsedMillis": 1, "decisions": 20, "wins": [1, 0]},
                    "incumbent": {"elapsedMillis": 2, "decisions": 21, "wins": [0, 1]}}
        changed = copy.deepcopy(original)
        changed["candidate"]["elapsedMillis"] = 1000
        self.assertEqual(benchmark.canonical_record(original, "arena"),
                         benchmark.canonical_record(changed, "arena"))
        changed["candidate"]["decisions"] = 22
        self.assertNotEqual(benchmark.canonical_record(original, "arena"),
                            benchmark.canonical_record(changed, "arena"))

    def test_equal_aggregate_but_different_world_results_fail_parity(self):
        reference = {"arena": [{"index": 0, "sha256": "win"}, {"index": 1, "sha256": "loss"}]}
        rows = {"workers-4-arena": {"mode": "arena", "records": [
            {"index": 0, "sha256": "loss"}, {"index": 1, "sha256": "win"}]}}
        _, failures = benchmark.comparisons(rows, reference)
        self.assertEqual(["workers-4-arena"], failures)

    def test_world_receipts_reject_wrong_seed(self):
        self.write(self.root / "game-0.json", {"index": 0, "gameSeed": 123})
        with self.assertRaisesRegex(ValueError, "another world"):
            benchmark.behavior_records(self.root, "collection", 1, self.offsets["collection"])

    def test_host_sweep_skips_unsupported_workers_and_retains_one_attempt_limits(self):
        self.prepare()
        output = self.root / "host"
        java = self.write(self.root / "java", {})
        captured = []

        def execute(arguments):
            spec = benchmark.read(Path(arguments[1]))
            captured.append(spec)
            self.write(output / "run/campaign-state.json", {"stages": {
                stage["id"]: {"status": "passed", "peakRssBytes": 12,
                              "metrics": {"mode": stage["id"].split("-")[-1], "records": []}}
                for stage in spec["stages"]}})
            return 0

        hardware = benchmark.controller.processes.Hardware(cpus=10, memory_bytes=64 * benchmark.GIB)
        with mock.patch.object(benchmark.controller.processes, "detect_hardware", return_value=hardware), \
                mock.patch.object(benchmark, "java_identity", return_value={"executable": str(java),
                                  "files": {str(java): benchmark.sha(java)}}), \
                mock.patch.object(benchmark.controller, "main", side_effect=execute):
            code = benchmark.run(argparse.Namespace(bundle=self.bundle, output=output, java=str(java),
                                                     workers=None, reference=None, smoke=False))
        self.assertEqual(0, code)
        spec = captured[0]
        self.assertEqual(4, len(spec["stages"]))
        self.assertTrue(all(stage["maximumAttempts"] == 1 for stage in spec["stages"]))
        self.assertTrue(all(stage["maximumSeconds"] == 900 for stage in spec["stages"]))
        self.assertEqual(7200, spec["limits"]["maximumSeconds"])
        self.assertEqual(4 * benchmark.GIB, spec["limits"]["maximumRssBytes"])
        self.assertEqual([8, 10], benchmark.read(output / "environment.json")["skippedWorkers"])

    def test_relative_paths_cannot_escape_bundle(self):
        for path in ("../outside", "/absolute"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "confined relative"):
                benchmark.relative(self.root, path)

    def reference(self):
        reference = self.root / "reference"
        environment = self.write(reference / "environment.json", {"host": "source-only provenance"})
        raw = self.write(reference / "run/collection/native/game-0.json", {"decisions": ["actual action"]})
        metrics = {"mode": "collection", "records": [{"index": 0, "sha256": benchmark.sha(raw)}]}
        result = self.write(reference / "run/collection/result.json", {
            "schemaVersion": 1, "status": "passed", "summary": "Fixture completed.",
            "metrics": metrics, "artifacts": {"collection/native/game-0.json": benchmark.sha(raw)}})
        self.write(reference / "execution.json", {"stages": [{"id": "collection", "result": "collection/result.json"}]})
        state = {"stages": {"collection": {"status": "passed", "result": "collection/result.json",
                                           "resultSha256": benchmark.sha(result), "metrics": metrics,
                                           "peakRssBytes": 12}}}
        self.write(reference / "run/campaign-state.json", state)
        self.write(reference / "report.json", {
            "status": "passed", "smoke": False, "bundleSha256": benchmark.sha(self.bundle / "manifest.json"),
            "results": benchmark.completed_rows(state), "behaviors": {"collection": metrics["records"]},
            "environmentSha256": benchmark.sha(environment)})
        return reference

    def test_export_preserves_raw_reference_evidence_and_hashes(self):
        self.prepare()
        reference = self.reference()
        archive = self.root / "portable.tar.gz"
        benchmark.export(argparse.Namespace(bundle=self.bundle, reference=reference, archive=archive))
        with tarfile.open(archive) as stream:
            hashes = json.load(stream.extractfile("aor-benchmark/reference-manifest.json"))
            raw = stream.extractfile("aor-benchmark/reference/run/collection/native/game-0.json").read()
        self.assertEqual(benchmark.hashlib.sha256(raw).hexdigest(), hashes["run/collection/native/game-0.json"])

    def test_export_rejects_raw_evidence_changed_after_completion(self):
        self.prepare()
        reference = self.reference()
        self.write(reference / "run/collection/native/game-0.json", {"decisions": ["changed action"]})
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            benchmark.export(argparse.Namespace(bundle=self.bundle, reference=reference,
                                                archive=self.root / "portable.tar.gz"))

    def test_export_cannot_recursively_archive_itself(self):
        with self.assertRaisesRegex(ValueError, "outside both"):
            benchmark.export(argparse.Namespace(bundle=self.bundle, reference=self.root / "reference",
                                                archive=self.bundle / "portable.tar.gz"))


if __name__ == "__main__":
    unittest.main()
