import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import afterstate_corpus as subject
import purchase_storage as storage

class ArchiveTest(unittest.TestCase):
    def fixture(self, root):
        files={'collection/states-0.json.gz':b'original-states','collection/label-0.json':b'opaque-label','reproduction/reproduction.json':b'proof'}
        for name,value in files.items():
            path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(value)
        return {name:dict(sha256=hashlib.sha256(value).hexdigest(),size=len(value)) for name,value in files.items()}
    def test_lossless_archive_verified_before_prune(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);expected=self.fixture(root);receipt=subject.archive(root,expected,list(expected))
            storage.verify_members(root/'evidence.tar.gz',expected,receipt['archiveSha256'])
            self.assertTrue(all(not(root/name).exists() for name in expected))
    def test_failed_verification_preserves_all_originals(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);expected=self.fixture(root)
            with patch.object(storage,'verify_members',side_effect=ValueError('synthetic failed checksum')):
                with self.assertRaises(ValueError):subject.archive(root,expected,list(expected))
            self.assertTrue(all((root/name).exists() for name in expected))
    def test_changed_member_refused_before_archive(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);expected=self.fixture(root);(root/'collection/label-0.json').write_bytes(b'changed')
            with self.assertRaises(ValueError):subject.archive(root,expected,list(expected))
            self.assertFalse((root/'evidence.tar.gz').exists())
    def test_symlink_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);expected=self.fixture(root);path=root/'collection/label-0.json';path.unlink();path.symlink_to(root/'collection/states-0.json.gz')
            with self.assertRaises(ValueError):subject.archive(root,expected,list(expected))
    def test_finder_metadata_remains_non_scientific(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);expected=self.fixture(root);finder=root/'collection/.DS_Store';finder.write_bytes(b'incidental')
            receipt=subject.archive(root,expected,list(expected));self.assertEqual(finder.read_bytes(),b'incidental');self.assertNotIn('collection/.DS_Store',receipt['files'])
    def test_traversal_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);expected={'../outside':dict(sha256='wrong',size=1)}
            with self.assertRaises(ValueError):subject.archive(root,expected,list(expected))
if __name__=='__main__':unittest.main()
