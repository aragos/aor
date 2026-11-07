"""Collection and evaluation batching boundaries using opaque records and mocked native execution only."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('batch_supervisor', Path(__file__).with_name('card-advantage-run.py'))
run = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run)


class AdvantageBatchSupervisorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source.json'
        self.source.write_text('{"frozen":true}\n')
        self.output = self.root / 'output'
        self.output.mkdir()
        self.runtime = self.root / 'lib'
        self.runtime.mkdir()
        self.value = SimpleNamespace(source=self.source, output=self.output,
            data_root=self.root, mode='evaluate', hours=1., deadline_epoch=time.time() + 3600,
            workers=2, batch_size=2)
        self.baseline = self.root / 'baseline.json'
        self.purchase = self.root / 'purchase.json'
        self.baseline.write_bytes(b'{"baseline":true}')
        self.purchase.write_bytes(b'{"purchase":true}')
        self.manifest = dict(kind='card-advantage-evaluate', signature='synthetic',
            sourceSha256=run.sha(self.source),
            baseline=dict(path=str(self.baseline), sha256=run.sha(self.baseline)),
            frozenPurchasePolicy=dict(path=str(self.purchase), sha256=run.sha(self.purchase)),
            policies={'baseline': {'kind': 'baseline', 'path': None, 'sha256': None}},
            jobs=[{'index': i} for i in range(3)])
        run.STOP.clear()

    def tearDown(self):
        run.STOP.clear()
        self.temporary.cleanup()

    def publish(self, indices):
        for index in indices:
            path, _ = run.record(self.value, index)
            if not path.exists():
                path.write_bytes(f'opaque-complete-record-{index}'.encode())

    @staticmethod
    def indices(selection):
        return [int(x) for x in selection[1].split(',')]

    def options(self, mode='evaluate', batch_size=None):
        arguments = ['--mode', mode, '--source', str(self.source), '--output', str(self.output),
            '--runtime', str(self.runtime), '--workers', '2', '--hours', '1',
            '--deadline-epoch', str(self.value.deadline_epoch), '--data-root', str(self.root)]
        if batch_size is not None:
            arguments += ['--batch-size', str(batch_size)]
        return run.options(arguments)

    def run_stage(self, native):
        with patch.object(run, 'resources'), patch.object(run, 'runtime', return_value=(['fake-native'], {'frozen': True})), \
                patch.object(run.subprocess, 'check_output', return_value=json.dumps(self.manifest)), \
                patch.object(run, 'check_runtime'), patch.object(run, 'launch_native', side_effect=native):
            run.run(self.value)

    def test_default_cli_preserves_one_game_per_jvm(self):
        self.assertEqual(self.options().batch_size, 1)
        self.assertEqual(self.options(mode='collect').batch_size, 1)
        self.assertEqual(self.options(batch_size=64).batch_size, 64)

    def test_cli_allows_collection_batching_but_rejects_outside_bounds(self):
        for size in (0, -1, 65):
            with self.assertRaisesRegex(ValueError, 'Batch size'):
                self.options(batch_size=size)
        self.assertEqual(self.options(mode='collect', batch_size=8).batch_size, 8)

    def test_singleton_chunk_keeps_original_index_flag_and_log_name(self):
        def native(value, base, selection, log_name, deadline):
            self.assertEqual(selection, ['--index', '2'])
            self.assertEqual(log_name, 'worker-2.log')
            self.publish([2])
        with patch.object(run, 'launch_native', side_effect=native):
            result = run.execute_batch(self.value, ['fake'], [2], self.value.deadline_epoch)
        self.assertEqual([r['index'] for r in result], [2])

    def test_batch_skips_bound_records_and_revalidates_unbound_prefix(self):
        self.publish([0, 1])
        run.binding(self.value, 0)
        def native(value, base, selection, log_name, deadline):
            self.assertEqual(selection, ['--indices', '1,2'])
            self.assertEqual(deadline, self.value.deadline_epoch)
            self.publish([2])
        with patch.object(run, 'launch_native', side_effect=native):
            result = run.execute_batch(self.value, ['fake'], [0, 1, 2], self.value.deadline_epoch)
        self.assertEqual([r['index'] for r in result], [0, 1, 2])

    def test_batch_rejects_invalid_indices_without_starting_a_process(self):
        for indices in ([], [0, 0], [True], [-1], ['0'], list(range(65))):
            with patch.object(run, 'launch_native') as launch:
                with self.assertRaises(ValueError):
                    run.execute_batch(self.value, ['fake'], indices, self.value.deadline_epoch)
                launch.assert_not_called()

    def test_bound_record_mutation_stops_before_other_pending_games(self):
        self.publish([0])
        run.binding(self.value, 0)
        run.record(self.value, 0)[0].write_bytes(b'changed')
        with patch.object(run, 'launch_native') as launch:
            with self.assertRaisesRegex(ValueError, 'refusing overwrite'):
                run.execute_batch(self.value, ['fake'], [0, 1], self.value.deadline_epoch)
            launch.assert_not_called()

    def test_failed_chunk_preserves_native_prefix_without_claiming_completion(self):
        def fail(value, base, selection, log_name, deadline):
            self.publish([0])
            raise RuntimeError('native failed after first whole game')
        with patch.object(run, 'launch_native', side_effect=fail):
            with self.assertRaisesRegex(RuntimeError, 'after first'):
                run.execute_batch(self.value, ['fake'], [0, 1], self.value.deadline_epoch)
        self.assertTrue(run.record(self.value, 0)[0].exists())
        self.assertFalse(run.record(self.value, 0)[1].exists())
        self.assertFalse((self.output / 'finished.json').exists())

    def test_partial_stage_resume_keeps_original_clock_and_completes_each_index_once(self):
        def first(value, base, selection, log_name, deadline):
            indices = self.indices(selection)
            if 0 in indices:
                self.publish([0])
                raise RuntimeError('partial chunk')
            self.publish(indices)
        with self.assertRaisesRegex(RuntimeError, 'partial chunk'):
            self.run_stage(first)
        self.assertEqual(run.read(self.output / 'state.json')['phase'], 'incomplete')
        self.assertFalse((self.output / 'finished.json').exists())
        original_clock = (self.output / 'started.json').read_bytes()
        original_first = run.record(self.value, 0)[0].read_bytes()
        deadlines = []
        def resumed(value, base, selection, log_name, deadline):
            deadlines.append(deadline)
            self.publish(self.indices(selection))
        run.STOP.clear()
        self.run_stage(resumed)
        self.assertEqual((self.output / 'started.json').read_bytes(), original_clock)
        self.assertEqual(run.record(self.value, 0)[0].read_bytes(), original_first)
        self.assertTrue(all(d == json.loads(original_clock)['deadline'] for d in deadlines))
        finished = run.read(self.output / 'finished.json')
        self.assertEqual([r['index'] for r in finished['files']], [0, 1, 2])
        self.assertEqual(run.read(self.output / 'state.json')['phase'], 'complete')

    def test_batch_allocation_is_pinned_and_cannot_change_on_resume(self):
        def native(value, base, selection, log_name, deadline):
            self.publish(self.indices(selection))
        self.run_stage(native)
        self.assertEqual(run.read(self.output / 'execution.json')['batchSize'], 2)
        old_clock = (self.output / 'started.json').read_bytes()
        self.value.batch_size = 3
        with self.assertRaisesRegex(ValueError, 'refusing overwrite'):
            self.run_stage(native)
        self.assertEqual((self.output / 'started.json').read_bytes(), old_clock)

    def test_collection_batch_completion_retains_ordered_origin_hashes(self):
        self.value.mode = 'collect'
        self.manifest.update(kind='card-advantage-collect', origins=[{'index':i} for i in range(3)], continuation=None)
        self.manifest.pop('jobs'); self.manifest.pop('policies')
        def native(value, base, selection, log_name, deadline):
            self.publish(self.indices(selection))
        self.run_stage(native)
        finished = run.read(self.output / 'finished.json')
        self.assertEqual(3, finished['origins'])
        self.assertEqual([run.sha(self.output / f'origin-{i}.json.gz') for i in range(3)], finished['originSha256'])
        self.assertNotIn('files', finished)
        self.assertEqual(2, run.read(self.output / 'execution.json')['batchSize'])

    def test_collection_batch_partial_prefix_resumes_without_clock_renewal(self):
        self.value.mode = 'collect'
        self.manifest.update(kind='card-advantage-collect', origins=[{'index':i} for i in range(3)], continuation=None)
        self.manifest.pop('jobs'); self.manifest.pop('policies')
        def interrupted(value, base, selection, log_name, deadline):
            self.publish([self.indices(selection)[0]])
            raise RuntimeError('interrupted collection')
        with self.assertRaisesRegex(RuntimeError, 'interrupted collection'):
            self.run_stage(interrupted)
        clock = (self.output / 'started.json').read_bytes()
        self.assertFalse((self.output / 'finished.json').exists())
        run.STOP.clear()
        def resumed(value, base, selection, log_name, deadline):
            self.assertEqual(json.loads(clock)['deadline'], deadline)
            self.publish(self.indices(selection))
        self.run_stage(resumed)
        self.assertEqual(clock, (self.output / 'started.json').read_bytes())
        self.assertEqual(3, run.read(self.output / 'finished.json')['origins'])

    def test_default_stage_keeps_original_execution_identity_shape(self):
        self.value.batch_size = 1
        seen = []
        def native(value, base, selection, log_name, deadline):
            seen.append(selection)
            self.publish(self.indices(selection))
        self.run_stage(native)
        self.assertNotIn('batchSize', run.read(self.output / 'execution.json'))
        self.assertEqual(sorted(seen), [['--index', '0'], ['--index', '1'], ['--index', '2']])


if __name__ == '__main__':
    unittest.main()
