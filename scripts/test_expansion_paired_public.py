"""Synthetic-only Jackson boundary tests; no retained origins or model files are read.

Set JAVA_HOME and AOR_EXPANSION_PUBLIC_LIB to use the already installed native72 JDK/libs.
Only Jackson dependencies are used. No engine/controller entrypoint is invoked.
Run with python3 -B -m unittest discover -s scripts -p test_expansion_paired_public.py.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENCODER = "aor-expansion-paired-observation-1"


def origin(index=0):
    commands = ["attack alpine 1", "attack alpine 2", "buy-expansion-card",
                "pass", "place grain 3", "reduce silk 2"]
    selected = [(4, "INCUMBENT"), (3, "PASS"), (2, "CARD_PURCHASE"),
                (0, "DESTINATION_SAMPLE")]
    return {
        "kind": "expansion-paired-origin-1", "signature": "a" * 64,
        "request": {
            "index": index, "seat": index % 4, "epoch": 1 + (index // 4) % 3,
            "ordinal": 2 * ((index // 12) % 4),
            "split": "TRAIN" if index < 768 else "VALIDATION" if index < 896 else "TEST",
        },
        "streams": {"gameSeed": -928000000 + index * 8191,
                    "proposalSeed": 2**62 + index, "future": [-(2**63) + index, 2**63-1-index]},
        "status": "COLLECTED", "missingReason": None,
        "prefix": {"header": {"secret": "PRIVATE_HEADER"},
                   "entries": ["PRIVATE_CANONICAL_HISTORY"], "terminal": False},
        "actorInput": {
            "view": {"private": "PRIVATE_VIEW"},
            "observation": {"private": "PRIVATE_OBSERVATION"},
            "bonus": {"private": "PRIVATE_BONUS"},
            "encoded": {
                "encoderVersion": ENCODER,
                "stateFeatures": {"epoch": 1 / 3, "own.commodity": 0.2, "zero": 0.0},
                "actions": [{"command": command, "features": {"cost": i / 10}}
                            for i, command in enumerate(commands)],
                "baselineIndex": 4,
            },
        },
        "incumbentCommand": commands[4], "exactIncumbentVerified": True,
        "arms": [{"proposal": {"actionIndex": i, "command": commands[i], "reason": reason},
                  "outcomes": [{"reward": "FORBIDDEN_REWARD", "terminal": ["PRIVATE_OUTCOME"],
                                "canonicalEntries": ["PRIVATE_SUFFIX"]}]}
                 for i, reason in selected],
    }


def missing(index=767):
    value = origin(index)
    value.update(status="MISSING", missingReason="TARGET_WINDOW_NOT_REACHED",
                 actorInput=None, arms=[], incumbentCommand=None, exactIncumbentVerified=False)
    return value


class ExpansionPairedPublicTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="expansion-public-unit-")
        cls.addClassCleanup(cls.build.cleanup)
        cls.classes = Path(cls.build.name)
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        default_lib = ROOT.parent / "expansion-paired-data/training/build/install/training/lib"
        lib = Path(os.environ.get("AOR_EXPANSION_PUBLIC_LIB", default_lib))
        jars = [p for prefix in ("jackson-core-", "jackson-databind-", "jackson-annotations-")
                for p in sorted(lib.glob(prefix + "*.jar"))]
        if not cls.java or not javac or len(jars) != 3:
            raise RuntimeError("Use the installed JDK21 and native72 Jackson lib directory.")
        cls.classpath = os.pathsep.join([str(cls.classes), *(str(p) for p in jars)])
        compiled = subprocess.run(
            [javac, "-J-XX:ActiveProcessorCount=1", "-J-Xmx256m", "-cp", cls.classpath,
             "-d", str(cls.classes), str(ROOT / "scripts/ExpansionPairedPublic.java")],
            text=True, capture_output=True, timeout=60,
        )
        if compiled.returncode:
            raise AssertionError(compiled.stderr)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="expansion-public-fixture-")
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name)

    def write(self, value=None, name="origin.json.gz", raw=None):
        path = self.path / name
        content = raw if raw is not None else json.dumps(value, allow_nan=False).encode()
        path.write_bytes(gzip.compress(content, mtime=0))
        return path

    def run_export(self, *pairs):
        return subprocess.run(
            [self.java, "-XX:ActiveProcessorCount=1", "-Xmx256m", "-cp", self.classpath,
             "ExpansionPairedPublic", *(str(p) for pair in pairs for p in pair)],
            text=True, capture_output=True, timeout=30,
        )

    def export(self, value=None, raw=None):
        source = self.write(value, raw=raw)
        output = self.path / "public.json"
        result = self.run_export((source, output))
        self.assertEqual(0, result.returncode, result.stderr)
        public = json.loads(output.read_text())
        metadata = json.loads(output.with_name(output.name + ".metadata.json").read_text())
        return public, metadata, source

    def reject(self, value=None, raw=None):
        source = self.write(value, raw=raw)
        output = self.path / "public.json"
        result = self.run_export((source, output))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())
        self.assertFalse(output.with_name(output.name + ".metadata.json").exists())
        return result

    def test_subset_preserves_original_features_and_canonical_order(self):
        original = origin()
        public, _, _ = self.export(original)
        encoded = original["actorInput"]["encoded"]
        self.assertEqual([0, 2, 3, 4], public["actionIndices"])
        self.assertEqual(encoded["stateFeatures"], public["encoded"]["stateFeatures"])
        self.assertEqual([encoded["actions"][i] for i in [0, 2, 3, 4]],
                         public["encoded"]["actions"])

    def test_baseline_is_remapped_to_selected_action_index(self):
        public, metadata, _ = self.export(origin())
        self.assertEqual(3, public["encoded"]["baselineIndex"])
        self.assertEqual(3, metadata["baselineIndex"])
        self.assertEqual(4, metadata["originalBaselineIndex"])

    def test_projection_is_accepted_by_the_typed_research_reader(self):
        import expansion_paired_fit_data as data
        projected, metadata, _ = self.export(origin())
        names = dict(stateFeatureNames=['epoch', 'own.commodity', 'zero'],
                     actionFeatureNames=['cost'])
        self.assertEqual(projected, data.public_origin(projected, metadata, names))

    def test_public_has_no_provenance_in_encoder(self):
        public, _, _ = self.export(origin())
        self.assertEqual({"kind", "index", "signature", "sourceSha256", "status",
                          "encoded", "actionIndices"}, set(public))
        self.assertEqual({"encoderVersion", "stateFeatures", "actions", "baselineIndex"},
                         set(public["encoded"]))

    def test_private_views_prefix_and_poisoned_outcomes_are_skipped(self):
        raw = json.dumps(origin()).replace('"FORBIDDEN_REWARD"', "1e999")
        # Duplicate fields are rejected in decoded public trees. Inside opaque skipped trees
        # they cannot enter that decoder; this is stronger than only checking output omission.
        raw = raw.replace('"PRIVATE_OUTCOME"', '{"duplicate":1,"duplicate":2}')
        public, metadata, _ = self.export(raw=raw.encode())
        wire = json.dumps([public, metadata])
        self.assertNotIn("PRIVATE", wire)
        self.assertNotIn("reward", wire)
        self.assertNotIn("duplicate", wire)

    def test_metadata_binds_raw_gzip_and_exact_signed_streams(self):
        original = origin(795)
        public, metadata, source = self.export(original)
        expected = hashlib.sha256(source.read_bytes()).hexdigest()
        self.assertEqual(expected, public["sourceSha256"])
        self.assertEqual(expected, metadata["sourceSha256"])
        self.assertEqual(original["request"], metadata["request"])
        self.assertEqual(original["streams"], metadata["streams"])
        self.assertEqual(original["streams"]["future"], metadata["futureSeeds"])
        self.assertEqual("VALIDATION", metadata["split"])

    def test_missing_origin_has_no_fabricated_features_or_actions(self):
        public, metadata, _ = self.export(missing())
        self.assertIsNone(public["encoded"])
        self.assertEqual([], public["actionIndices"])
        self.assertEqual([], metadata["commands"])
        self.assertIsNone(metadata["baselineIndex"])

    def test_two_action_pass_incumbent_keeps_baseline(self):
        value = origin()
        encoded = value["actorInput"]["encoded"]
        encoded["actions"] = [encoded["actions"][3], encoded["actions"][4]]
        encoded["baselineIndex"] = 0
        value["arms"] = [
            {"proposal": {"actionIndex": i, "command": action["command"],
                          "reason": "INCUMBENT" if i == 0 else "DESTINATION_SAMPLE"},
             "outcomes": "NOT_DECODED"}
            for i, action in enumerate(encoded["actions"])
        ]
        public, _, _ = self.export(value)
        self.assertEqual([0, 1], public["actionIndices"])
        self.assertEqual(0, public["encoded"]["baselineIndex"])

    def test_more_than_eight_arms_rejected(self):
        value = origin()
        value["arms"] = value["arms"] * 3
        result = self.reject(value)
        self.assertIn("At most eight original proposals", result.stderr)

    def test_changed_future_evidence_cannot_change_public_inputs(self):
        first = origin()
        second = origin()
        second["prefix"] = {"unknownFuture": "POISONED_CANONICAL"}
        for arm in second["arms"]:
            arm["outcomes"] = {"reward": {"not": "a number"}, "drawnCard": "PRIVATE"}
        sources = [self.write(first, "first.gz"), self.write(second, "second.gz")]
        outputs = [self.path / "first.json", self.path / "second.json"]
        result = self.run_export(*zip(sources, outputs))
        self.assertEqual(0, result.returncode, result.stderr)
        projected = [json.loads(path.read_text()) for path in outputs]
        self.assertNotEqual(projected[0].pop("sourceSha256"), projected[1].pop("sourceSha256"))
        self.assertEqual(projected[0], projected[1])

    def test_multiple_sources_export_in_one_jvm(self):
        sources = [self.write(origin(0), "first.gz"), self.write(missing(768), "second.gz")]
        outputs = [self.path / "first.json", self.path / "second.json"]
        result = self.run_export(*zip(sources, outputs))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([0, 768], [json.loads(p.read_text())["index"] for p in outputs])

    def test_field_order_does_not_change_public_values(self):
        original = origin()
        original["actorInput"] = dict(reversed(list(original["actorInput"].items())))
        original["arms"] = [dict(reversed(list(arm.items()))) for arm in original["arms"]]
        public, _, _ = self.export(dict(reversed(list(original.items()))))
        self.assertEqual([0, 2, 3, 4], public["actionIndices"])

    def test_proposal_command_must_match_original_action(self):
        value = origin()
        value["arms"][1]["proposal"]["command"] = "buy-expansion-card"
        self.reject(value)

    def test_first_proposal_must_be_the_exact_e_baseline(self):
        value = origin()
        value["actorInput"]["encoded"]["baselineIndex"] = 0
        self.reject(value)

    def test_duplicate_proposal_indices_rejected(self):
        value = origin()
        value["arms"][3]["proposal"] = dict(value["arms"][1]["proposal"])
        self.reject(value)

    def test_noncanonical_original_commands_rejected(self):
        value = origin()
        value["actorInput"]["encoded"]["actions"].reverse()
        self.reject(value)

    def test_extra_encoded_metadata_rejected(self):
        value = origin()
        value["actorInput"]["encoded"]["futureReward"] = 1
        self.reject(value)

    def test_nonfinite_public_feature_rejected(self):
        raw = json.dumps(origin()).replace('"own.commodity": 0.2', '"own.commodity": 1e999')
        self.reject(raw=raw.encode())

    def test_duplicate_public_feature_rejected(self):
        raw = json.dumps(origin()).replace('"own.commodity": 0.2', '"same": 1,"same": 2')
        self.reject(raw=raw.encode())

    def test_missing_origin_cannot_contain_captured_inputs(self):
        value = origin()
        value["status"] = "MISSING"
        self.reject(value)

    def test_partition_cannot_be_reassigned(self):
        value = origin(900)
        value["request"]["split"] = "TRAIN"
        self.reject(value)

    def test_streams_cannot_be_rounded_float_identities(self):
        value = origin()
        value["streams"]["proposalSeed"] = float(2**62)
        self.reject(value)

    def test_duplicate_future_seed_rejected(self):
        value = origin()
        value["streams"]["future"] = [101, 101]
        self.reject(value)

    def test_offered_card_buy_cannot_be_dropped(self):
        value = origin()
        del value["arms"][2]
        self.reject(value)

    def test_offered_pass_cannot_be_dropped(self):
        value = origin()
        del value["arms"][1]
        self.reject(value)

    def test_trailing_json_rejected(self):
        self.reject(raw=json.dumps(origin()).encode() + b" {}")

    def test_legacy_encoder_tag_rejected(self):
        value = origin()
        value["actorInput"]["encoded"]["encoderVersion"] = "aor-expansion-observation-2"
        self.reject(value)

    def test_duplicate_public_output_is_rejected_before_any_export(self):
        source = self.write(origin())
        output = self.path / "public.json"
        result = self.run_export((source, output), (source, output))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

    def test_existing_output_is_preserved(self):
        source = self.write(origin())
        output = self.path / "public.json"
        output.write_bytes(b"KEEP_ORIGINAL")
        result = self.run_export((source, output))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"KEEP_ORIGINAL", output.read_bytes())

    def test_original_gzip_is_unchanged(self):
        source = self.write(origin())
        before = source.read_bytes()
        result = self.run_export((source, self.path / "public.json"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, source.read_bytes())


if __name__ == "__main__":
    unittest.main()
