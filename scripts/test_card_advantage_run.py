"""Operational boundary checks; synthetic files only, no games or model selection."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('card_advantage_run', Path(__file__).with_name('card-advantage-run.py'))
run = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run)


class AdvantageSupervisorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source.json'
        self.source.write_text('{"frozen":true}\n')
        self.output = self.root / 'output'
        self.output.mkdir()
        self.value = SimpleNamespace(source=self.source, output=self.output,
            data_root=self.root, mode='collect', hours=1., deadline_epoch=time.time() + 7200,
            workers=2)
        self.identity = {'sourceSha256': run.sha(self.source), 'workers': 2}
        run.STOP.clear()
        run.LAST_SPACE_CHECK = 0.

    def tearDown(self):
        run.STOP.clear()
        self.temporary.cleanup()

    def test_fresh_clock_and_resume_retain_exact_original_deadline(self):
        first = run.clock(self.value, self.identity)
        original = (self.output / 'started.json').read_bytes()
        with patch.object(run.time, 'time', return_value=time.time() + 900):
            resumed = run.clock(self.value, self.identity)
        self.assertEqual(first, resumed)
        self.assertEqual(original, (self.output / 'started.json').read_bytes())

    def test_workflow_deadline_caps_stage_allowance(self):
        self.value.deadline_epoch = time.time() + 60
        self.assertEqual(run.clock(self.value, self.identity), self.value.deadline_epoch)

    def test_changed_allowance_cannot_extend_a_stage(self):
        run.clock(self.value, self.identity)
        self.value.hours = 2.
        with self.assertRaisesRegex(ValueError, 'deadline changed'):
            run.clock(self.value, self.identity)

    def test_changed_workers_or_source_identity_rejects_resume(self):
        run.clock(self.value, self.identity)
        with self.assertRaisesRegex(ValueError, 'identity'):
            run.clock(self.value, {**self.identity, 'workers': 6})
        with self.assertRaisesRegex(ValueError, 'identity'):
            run.clock(self.value, {**self.identity, 'sourceSha256': 'different'})

    def test_missing_original_clock_after_record_is_not_recreated(self):
        (self.output / 'origin-0.json.gz').write_bytes(b'complete-record')
        with self.assertRaisesRegex(ValueError, 'clock is missing'):
            run.clock(self.value, self.identity)
        self.assertFalse((self.output / 'started.json').exists())

    def test_replaced_clock_bytes_reject_even_if_numerically_valid(self):
        run.clock(self.value, self.identity)
        path = self.output / 'started.json'
        old = run.read(path)
        old['startedAt'] -= 5
        old['deadline'] -= 5
        path.write_text(json.dumps(old))
        with self.assertRaisesRegex(ValueError, 'refusing overwrite'):
            run.clock(self.value, self.identity)

    def test_completed_gzip_bytes_are_hashed_without_outcome_decode(self):
        path, _ = run.record(self.value, 0)
        path.write_bytes(b'opaque-complete-gzip-bytes')
        result = run.binding(self.value, 0)
        self.assertEqual(result, {'index': 0, 'file': path.name, 'sha256': run.sha(path)})
        self.assertEqual(run.execute(self.value, ['unused'], 0, time.time() - 1), result)

    def test_modified_completed_record_rejects_without_overwriting_binding(self):
        path, bound = run.record(self.value, 0)
        path.write_bytes(b'first')
        run.binding(self.value, 0)
        original = bound.read_bytes()
        path.write_bytes(b'second')
        with self.assertRaisesRegex(ValueError, 'refusing overwrite'):
            run.binding(self.value, 0)
        self.assertEqual(bound.read_bytes(), original)

    def test_missing_record_never_starts_after_original_deadline(self):
        with patch.object(run.subprocess, 'Popen') as start:
            with self.assertRaisesRegex(ValueError, 'deadline elapsed'):
                run.execute(self.value, ['unused'], 0, time.time() - 1)
            start.assert_not_called()

    def test_expiring_owned_child_is_terminated_and_publishes_no_completion(self):
        children = []
        native_popen = subprocess.Popen
        def start(*args, **kwargs):
            child = native_popen(*args, **kwargs)
            children.append(child)
            return child
        with patch.object(run, 'resources'), patch.object(run.subprocess, 'Popen', side_effect=start):
            with self.assertRaisesRegex(ValueError, 'deadline elapsed'):
                run.execute(self.value, [sys.executable, '-c', 'import time; time.sleep(60)'], 0, time.time() + .15)
        self.assertEqual(len(children), 1)
        self.assertIsNotNone(children[0].poll())
        self.assertFalse((self.output / 'finished.json').exists())

    def test_raw_model_snapshot_requires_declared_hash(self):
        baseline = self.root / 'baseline.json'
        purchase = self.root / 'purchase.json'
        baseline.write_bytes(b'{"weights":{}}')
        purchase.write_bytes(b'{"actor":"purchase"}')
        manifest = dict(sourceSha256=run.sha(self.source), continuation=None,
            baseline=dict(path=str(baseline), sha256=run.sha(baseline)),
            frozenPurchasePolicy=dict(path=str(purchase), sha256=run.sha(purchase)))
        run.snapshots(self.value, manifest)
        expected = (self.output / 'frozen-purchase-policy.json').read_bytes()
        purchase.write_bytes(b'{"actor":"different"}')
        with self.assertRaisesRegex(ValueError, 'policy/source changed'):
            run.snapshots(self.value, manifest)
        self.assertEqual((self.output / 'frozen-purchase-policy.json').read_bytes(), expected)

    def test_empty_present_snapshot_is_evidence_and_never_overwritten(self):
        path = self.output / 'source.json'
        path.write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'refusing overwrite'):
            run.atomic(path, b'new')
        self.assertEqual(path.read_bytes(), b'')

    def test_data_cap_stops_without_deleting_records(self):
        path = self.root / 'large-record'
        with path.open('wb') as stream:
            stream.truncate(4 * run.GIB + 1)
        with patch.object(run.shutil, 'disk_usage', return_value=SimpleNamespace(free=20 * run.GIB)):
            with self.assertRaisesRegex(ValueError, 'no records are dropped'):
                run.resources(self.root, force=True)
        self.assertTrue(path.exists())

    def test_low_disk_stops_before_any_child(self):
        with patch.object(run.shutil, 'disk_usage', return_value=SimpleNamespace(free=run.GIB)):
            with self.assertRaisesRegex(ValueError, 'fewer than8GiB'):
                run.resources(self.root, force=True)

    def test_runtime_extra_or_replaced_jar_rejects(self):
        java = self.root / 'java'
        java.write_bytes(b'java')
        jar = self.root / 'engine.jar'
        jar.write_bytes(b'engine')
        identity = dict(java=str(java), javaSha256=run.sha(java),
            supervisorSha256=run.sha(Path(run.__file__)), jars={str(jar): run.sha(jar)})
        run.check_runtime(identity)
        (self.root / 'extra.jar').write_bytes(b'new')
        with self.assertRaisesRegex(ValueError, 'membership changed'):
            run.check_runtime(identity)
        (self.root / 'extra.jar').unlink()
        jar.write_bytes(b'replaced')
        with self.assertRaisesRegex(ValueError, 'JAR changed'):
            run.check_runtime(identity)


if __name__ == '__main__':
    unittest.main()
