import json
import tempfile
import unittest
from pathlib import Path

from sanitize_recorded_game import digest, export, sanitize


class RecordedGameSanitizerTest(unittest.TestCase):
    def fixture(self):
        return {"baseRevision": 0, "previousArchive": None, "authentication": "SECRET",
                "header": {"players": [{"id": "p1", "name": "Player", "auth": "SECRET"}],
                           "creatorId": "p1", "rulesVersion": "rules", "dataVersion": "data",
                           "drawRenaissanceFromTurn": 1, "server": "SECRET"},
                "revisions": [{"replaceFrom": None, "entries": ["a", "b", "c"],
                               "author": "SECRET"},
                              {"replaceFrom": 1, "entries": ["d", "e"]},
                              {"replaceFrom": None, "entries": ["f"]}]}

    def test_effective_replacements_are_applied_in_order(self):
        self.assertEqual(sanitize(self.fixture(), "pin")["entries"], ["a", "d", "e", "f"])

    def test_only_engine_whitelist_survives(self):
        self.assertNotIn("SECRET", json.dumps(sanitize(self.fixture(), "pin")))

    def test_invalid_replacement_is_rejected(self):
        original = self.fixture()
        original["revisions"][1]["replaceFrom"] = 4
        with self.assertRaisesRegex(ValueError, "Replacement"):
            sanitize(original, "pin")

    def test_archive_prefix_cannot_be_silently_dropped(self):
        original = self.fixture()
        original["baseRevision"] = 3
        with self.assertRaisesRegex(ValueError, "archive prefix"):
            sanitize(original, "pin")

    def test_missing_archive_declaration_is_rejected(self):
        original = self.fixture()
        del original["previousArchive"]
        with self.assertRaisesRegex(ValueError, "archive prefix"):
            sanitize(original, "pin")

    def test_source_pin_failure_creates_no_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            source.write_text(json.dumps(self.fixture()))
            output = Path(directory) / "private" / "history.json"
            with self.assertRaisesRegex(ValueError, "source pin"):
                export(source, output, "incorrect")
            self.assertFalse(output.exists())

    def test_export_preserves_original_bytes_and_private_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            original = json.dumps(self.fixture()).encode()
            source.write_bytes(original)
            output = root / "private" / "history.json"
            export(source, output, digest(original))
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(output.parent.stat().st_mode & 0o777, 0o700)

    def test_export_cannot_overwrite_existing_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            source.write_text(json.dumps(self.fixture()))
            before = source.read_bytes()
            with self.assertRaises(FileExistsError):
                export(source, source, digest(before))
            self.assertEqual(source.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
