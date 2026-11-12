import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest

import training_evidence_archive as archive


class EvidenceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.completion = self.root / 'completion.json'
        self.completion.write_text(json.dumps(dict(passed=True, status='completed')))
        self.files = [self.root / 'one.json', self.root / 'nested/two.json']
        self.files[1].parent.mkdir()
        for index, path in enumerate(self.files):
            path.write_bytes((f'evidence-{index}\n' * 1000).encode())
            path.chmod(0o640)
            os.utime(path, ns=(1_700_000_000_123_456_789, 1_700_000_000_123_456_789))
        self.plan = self.root / 'plan.json'
        self.archive = self.root / 'retained.tar.gz'
        self.receipt = self.root / 'receipt.json'
        self.document = dict(
            schemaVersion=1, root=str(self.root),
            completion=dict(path='completion.json', sha256=archive.digest(self.completion)),
            files=[dict(path=str(path.relative_to(self.root)), sha256=archive.digest(path))
                   for path in self.files],
        )
        self.write_plan()

    def write_plan(self):
        self.plan.write_text(json.dumps(self.document))

    def pack(self, remove=False):
        return archive.pack(self.plan, self.archive, self.receipt, remove)

    def test_pack_retains_originals_and_verifies_every_member(self):
        receipt = self.pack()
        self.assertEqual(2, len(receipt['files']))
        self.assertTrue(all(path.exists() for path in self.files))
        self.assertLess(receipt['archiveBytes'], receipt['originalBytes'])
        self.assertEqual(receipt, archive.verified_receipt(self.receipt)[0])

    def test_round_trip_restores_exact_content_modes_and_nanosecond_timestamps(self):
        before = [(path.read_bytes(), path.stat().st_mode, path.stat().st_mtime_ns)
                  for path in self.files]
        self.pack(remove=True)
        self.assertTrue(all(not path.exists() for path in self.files))
        archive.restore(self.receipt)
        self.assertEqual(before, [(path.read_bytes(), path.stat().st_mode, path.stat().st_mtime_ns)
                                  for path in self.files])

    def test_partial_prune_can_be_restored(self):
        self.pack()
        self.files[0].unlink()
        archive.restore(self.receipt)
        self.assertEqual(self.document['files'][0]['sha256'], archive.digest(self.files[0]))

    def test_partial_prune_can_be_resumed(self):
        self.pack()
        self.files[0].unlink()
        archive.prune(self.receipt)
        self.assertTrue(all(not path.exists() for path in self.files))

    def test_changed_remaining_member_prevents_all_pruning(self):
        self.pack()
        self.files[1].write_text('new evidence')
        with self.assertRaisesRegex(ValueError, 'changed'):
            archive.prune(self.receipt)
        self.assertTrue(self.files[0].exists())

    def test_restore_does_not_overwrite_a_changed_existing_file(self):
        self.pack(remove=True)
        self.files[1].write_text('new evidence')
        with self.assertRaisesRegex(ValueError, 'changed'):
            archive.restore(self.receipt)
        self.assertFalse(self.files[0].exists())
        self.assertEqual('new evidence', self.files[1].read_text())

    def test_corrupt_archive_prevents_pruning(self):
        self.pack()
        with self.archive.open('ab') as stream:
            stream.write(b'corruption')
        with self.assertRaisesRegex(ValueError, 'archive changed'):
            archive.prune(self.receipt)
        self.assertTrue(all(path.exists() for path in self.files))

    def test_archive_rejects_an_undeclared_member(self):
        self.pack()
        unexpected = self.root / 'unexpected.tar.gz'
        with tarfile.open(unexpected, 'w:gz') as target:
            member = tarfile.TarInfo('../unwanted')
            member.size = 1
            target.addfile(member, io.BytesIO(b'x'))
        with self.assertRaisesRegex(ValueError, 'undeclared'):
            archive.verify_archive(unexpected, archive.read(self.receipt)['files'])

    def test_only_successfully_completed_runs_are_admitted(self):
        self.completion.write_text(json.dumps(dict(passed=True, status='running')))
        self.document['completion']['sha256'] = archive.digest(self.completion)
        self.write_plan()
        with self.assertRaisesRegex(ValueError, 'completed'):
            self.pack(remove=True)
        self.assertFalse(self.archive.exists())
        self.assertTrue(all(path.exists() for path in self.files))

    def test_changed_original_prevents_archive_and_deletion(self):
        self.files[1].write_text('new evidence')
        with self.assertRaisesRegex(ValueError, 'changed'):
            self.pack(remove=True)
        self.assertFalse(self.archive.exists())
        self.assertTrue(self.files[0].exists())

    def test_parent_traversal_and_duplicate_paths_are_rejected(self):
        for names in (['../outside'], ['one.json', 'one.json']):
            with self.subTest(names=names):
                self.document['files'] = [dict(path=name, sha256='0' * 64) for name in names]
                self.write_plan()
                with self.assertRaises(ValueError):
                    self.pack()
                self.assertFalse(self.archive.exists())

    def test_symlinked_members_and_parents_are_rejected(self):
        for directory in (False, True):
            with self.subTest(directory=directory):
                link = self.root / ('linked-directory' if directory else 'linked-file')
                link.symlink_to(self.files[1].parent if directory else self.files[0],
                                target_is_directory=directory)
                name = 'linked-directory/two.json' if directory else 'linked-file'
                self.document['files'] = [dict(path=name, sha256=archive.digest(self.files[0]))]
                self.write_plan()
                with self.assertRaisesRegex(ValueError, 'symlink'):
                    self.pack()

    def test_existing_destinations_are_never_overwritten(self):
        self.archive.write_bytes(b'previous evidence')
        with self.assertRaisesRegex(ValueError, 'new paths'):
            self.pack()
        self.assertEqual(b'previous evidence', self.archive.read_bytes())

    def test_restore_to_another_root_preserves_layout(self):
        self.pack()
        destination = self.root / 'restored'
        destination.mkdir()
        archive.restore(self.receipt, destination)
        for path in self.files:
            restored = destination / path.relative_to(self.root)
            self.assertEqual(path.read_bytes(), restored.read_bytes())


if __name__ == '__main__':
    unittest.main()
