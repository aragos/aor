"""Finder metadata is outside numerical identity; executable/data/settings remain strict."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_ecology_environment as environment


class NumericalLibraryIdentityTest(unittest.TestCase):
    def test_finder_bytes_and_presence_do_not_change_library_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package = root / 'package'
            package.mkdir()
            module = package / 'module.py'
            module.write_text('value = 1\n')
            before = environment.library_files(root)
            (package / '.DS_Store').write_bytes(b'Finder directory metadata')
            (root / '.DS_Store').write_bytes(b'different Finder metadata')
            self.assertEqual(environment.library_files(root), before)

    def test_all_real_package_files_remain_pinned(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            names = ('module.py', 'library.so', 'library.dylib', 'table.bin',
                     'METADATA', 'RECORD', 'configuration.json', '.DS_Store.py')
            for name in names:
                (root / name).write_bytes(name.encode())
            self.assertEqual(environment.library_files(root),
                             {root.resolve() / name for name in names})

    def test_existing_python_cache_exclusions_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '__pycache__').mkdir()
            for name in ('module.pyc', 'module.pyo', '__pycache__/module.pyc'):
                (root / name).write_bytes(b'generated cache')
            self.assertEqual(environment.library_files(root), set())

    def test_changed_real_library_digest_is_rejected(self):
        expected = {'files': {'module.py': 'original'}, 'torchThreads': 1}
        with patch.object(environment, 'current', return_value={
                'files': {'module.py': 'changed'}, 'torchThreads': 1}):
            with self.assertRaisesRegex(ValueError, 'Numerical venv'):
                environment.verify(expected)

    def test_changed_numerical_setting_is_rejected(self):
        expected = {'files': {'module.py': 'original'}, 'torchThreads': 1}
        with patch.object(environment, 'current', return_value={
                'files': {'module.py': 'original'}, 'torchThreads': 2}):
            with self.assertRaisesRegex(ValueError, 'Numerical venv'):
                environment.verify(expected)


if __name__ == '__main__':
    unittest.main()
