"""Lossless row storage and interrupted deletion, without regenerating any game."""

import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

import automata_experiments as experiments
import purchase_storage as storage


class PurchaseStorageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.batch = self.root / "run/batches/batch-01"
        self.collection = self.batch / "collection"
        self.rows = {f"game-{index}.json": (json.dumps({"index": index, "evidence": "immutable" * 100}) + "\n").encode()
                     for index in range(3)}
        self.collection.mkdir(parents=True)
        for name, data in self.rows.items():
            (self.collection / name).write_bytes(data)
        experiments.document(self.collection / "collection.json", {"games": len(self.rows)})
        experiments.document(self.collection / "finished.json", {
            "gameSha256": [experiments.sha(self.collection / name) for name in self.rows]})

    def test_archive_preserves_every_raw_byte_and_metadata_on_restore(self):
        finished = (self.collection / "finished.json").read_bytes()
        storage.archive(self.batch)
        self.assertEqual([], list(self.collection.glob("game-*.json")))
        storage.restore(self.batch)
        self.assertEqual(self.rows, {name: (self.collection / name).read_bytes() for name in self.rows})
        self.assertEqual(finished, (self.collection / "finished.json").read_bytes())

    def test_corrupt_original_is_not_deleted_or_archived(self):
        (self.collection / "game-1.json").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "differs from completion"):
            storage.archive(self.batch)
        self.assertEqual(3, len(list(self.collection.glob("game-*.json"))))
        self.assertFalse((self.batch / storage.MANIFEST).exists())

    def test_corrupt_archive_cannot_restore_rows(self):
        storage.archive(self.batch)
        with (self.batch / storage.ARCHIVE).open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum"):
            storage.restore(self.batch)
        self.assertEqual([], list(self.collection.glob("game-*.json")))

    def test_archive_member_paths_cannot_escape_the_collection(self):
        storage.archive(self.batch)
        path = self.batch / storage.ARCHIVE
        with tarfile.open(path, "w:gz") as archive:
            item = tarfile.TarInfo("../escaped.json")
            item.size = 1
            archive.addfile(item, io.BytesIO(b"x"))
        manifest = experiments.read(self.batch / storage.MANIFEST)
        manifest["archiveSha256"] = experiments.sha(path)
        experiments.document(self.batch / storage.MANIFEST, manifest, immutable=False)
        with self.assertRaisesRegex(ValueError, "unexpected"):
            storage.restore(self.batch)
        self.assertFalse((self.batch / "escaped.json").exists())

    def test_mid_delete_interruption_restores_and_reuses_the_exact_archive(self):
        unlink = Path.unlink
        def interrupted(path, *args, **kwargs):
            if path == self.collection / "game-1.json":
                raise InterruptedError("simulated termination during deletion")
            return unlink(path, *args, **kwargs)
        with mock.patch.object(Path, "unlink", interrupted), self.assertRaises(InterruptedError):
            storage.archive(self.batch)
        manifest_bytes = (self.batch / storage.MANIFEST).read_bytes()
        archive_bytes = (self.batch / storage.ARCHIVE).read_bytes()
        self.assertFalse((self.collection / "game-0.json").exists())
        self.assertTrue((self.collection / "game-1.json").exists())
        storage.restore(self.batch)
        self.assertEqual(self.rows, {name: (self.collection / name).read_bytes() for name in self.rows})
        storage.archive(self.batch)
        self.assertEqual(manifest_bytes, (self.batch / storage.MANIFEST).read_bytes())
        self.assertEqual(archive_bytes, (self.batch / storage.ARCHIVE).read_bytes())
        self.assertEqual([], list(self.collection.glob("game-*.json")))

    def test_archive_published_before_manifest_can_resume_without_recompression(self):
        publish = storage.publish
        def interrupted(path, write, expected=None):
            if path.name == storage.MANIFEST:
                raise InterruptedError("simulated termination before manifest")
            publish(path, write, expected)
        with mock.patch.object(storage, "publish", interrupted), self.assertRaises(InterruptedError):
            storage.archive(self.batch)
        original = (self.batch / storage.ARCHIVE).read_bytes()
        self.assertEqual(3, len(list(self.collection.glob("game-*.json"))))
        storage.archive(self.batch)
        self.assertEqual(original, (self.batch / storage.ARCHIVE).read_bytes())
        self.assertEqual([], list(self.collection.glob("game-*.json")))

    def test_restore_refuses_to_replace_conflicting_evidence(self):
        storage.archive(self.batch)
        path = self.collection / "game-0.json"
        path.write_bytes(b"conflict")
        with self.assertRaisesRegex(ValueError, "cannot overwrite"):
            storage.restore(self.batch)
        self.assertEqual(b"conflict", path.read_bytes())

    def legacy_archive(self):
        """Small real-shaped legacy receipts retain raw-row hashes after external archival."""
        artifacts = {str(path.relative_to(self.batch)): experiments.sha(path)
                     for path in self.collection.iterdir()}
        receipt = self.batch / "receipt.json"
        experiments.document(receipt, {"artifacts": artifacts})
        train = {"artifacts": {str(path.relative_to(self.root / "run")): experiments.sha(path)
                             for path in self.batch.rglob("*") if path.is_file()}}
        experiments.document(self.root / "run/results/train.json", train)
        store = self.root / "storage-archives"
        store.mkdir()
        files = {str(path.relative_to(self.root)): {"sha256": experiments.sha(path), "size": path.stat().st_size}
                 for path in self.collection.glob("game-*.json")}
        archive = store / "batch-01.tar.gz"
        with tarfile.open(archive, "w:gz") as destination:
            for name in files:
                destination.add(self.root / name, arcname=name)
        manifest = {"sourceRoot": str(self.root), "batch": "batch-01", "receiptPath": str(receipt),
                    "receiptSha256": experiments.sha(receipt), "archive": archive.name,
                    "archiveSha256": experiments.sha(archive), "files": files}
        experiments.document(store / "batch-01.json", manifest)
        index = {"sourceRoot": str(self.root), "sourceTrainReceiptSha256": experiments.sha(self.root / "run/results/train.json"),
                 "archives": {"batch-01": {"manifest": "batch-01.json",
                 "manifestSha256": experiments.sha(store / "batch-01.json"), "archiveSha256": experiments.sha(archive)}}}
        experiments.document(store / "index.json", index)
        for name in self.rows:
            (self.collection / name).unlink()
        return train, store

    def test_legacy_parent_archives_verify_against_original_receipts_without_extraction(self):
        train, _ = self.legacy_archive()
        rows, evidence = storage.archived_parent_rows(self.root, train)
        self.assertEqual(3, len(rows))
        self.assertEqual(3, len(evidence))
        self.assertEqual([], list(self.collection.glob("game-*.json")))

    def test_legacy_manifest_cannot_substitute_for_original_immutable_row_hash(self):
        train, _ = self.legacy_archive()
        train["artifacts"]["batches/batch-01/collection/game-0.json"] = "different"
        with self.assertRaisesRegex(ValueError, "immutable original"):
            storage.archived_parent_rows(self.root, train)


if __name__ == "__main__":
    unittest.main()
