"""Check experiment allocation, evidence reuse, and preflight stop behavior."""
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import card_advantage_experiment as experiment


def completed_collection(root):
    source = root / 'input.json'
    baseline = root / 'baseline.json'
    baseline.write_text('{}')
    (root / 'frozen-purchase-policy.json').write_text('{}')
    snapshot = {'path': str(baseline), 'sha256': experiment.sha(baseline)}
    specification = dict(configuration='qualified-profile', baseline=str(baseline),
                         frozenPurchasePolicy=str(baseline), origins=[{}], continuation=None)
    source.write_text(json.dumps(specification))
    (root / 'source.json').write_bytes(source.read_bytes())
    blob = b'complete opaque compressed record'
    (root / 'origin-0.json.gz').write_bytes(blob)
    experiment.document(root / 'collection.json', dict(
        kind='card-advantage-collect', sourceSha256=experiment.sha(source),
        signature='fixed', configuration='qualified-profile', origins=[{}], baseline=snapshot,
        frozenPurchasePolicy=snapshot, continuation=None))
    experiment.document(root / 'finished.json', dict(
        signature='fixed', origins=1, originSha256=[hashlib.sha256(blob).hexdigest()]))
    experiment.document(root / 'state.json', dict(signature='fixed', phase='complete'))
    return source


class ExperimentDriverTest(unittest.TestCase):
    def test_primary_uses_three_fixed_baseline_rivals_and_rotates_controlled_role(self):
        source = experiment.evaluation_source({}, 'primary-confirmation', list(range(1024)),
                                              {'candidate': '/model', 'frequency': '/control'})
        self.assertEqual(3072, len(source['jobs']))
        for index, job in enumerate(source['jobs']):
            self.assertEqual(index // 3, job['seed'])
            self.assertEqual((index // 3) % 4, job['seat'])
            self.assertEqual(['baseline'] * 4, job['rivals'])
        self.assertEqual({'kind': 'frequency', 'path': '/control'}, source['policies']['frequency'])

    def test_robustness_keeps_same_candidate_rivals_in_both_paired_arms(self):
        source = experiment.evaluation_source({}, 'rival-robustness', list(range(256)),
                                              {'candidate': '/model'})
        self.assertEqual(2048, len(source['jobs']))
        for index in range(0, len(source['jobs']), 2):
            candidate, baseline = source['jobs'][index:index + 2]
            self.assertEqual(candidate['seed'], baseline['seed'])
            self.assertEqual(candidate['seat'], baseline['seat'])
            self.assertEqual(candidate['rivals'], baseline['rivals'])
            self.assertEqual(3, candidate['rivals'].count('candidate'))

    def test_late_second_collection_cannot_consume_qualification_reserve(self):
        with tempfile.TemporaryDirectory() as directory:
            window = experiment.Window.__new__(experiment.Window)
            window.lab = Path(directory)
            window.protocol = {'collection': {'secondsByCycle': [7200, 9000]},
                               'fit': {'maximumSecondsPerCycle': 2700}}
            window.collection_cutoff = 100000
            window.admission = 15000
            with patch.object(experiment.time, 'time', return_value=4000):
                with self.assertRaises(experiment.CollectionNotAdmitted):
                    window.cycle(2, Path('/p1'))

    def test_completed_stage_can_be_reused_after_its_clock_expires(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source = completed_collection(root)
            experiment.document(root / 'started.json', dict(startedAt=1, deadline=2))
            self.assertTrue(experiment.completed_native(root, source))

    def test_self_consistent_completion_cannot_substitute_other_root_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source = completed_collection(root)
            manifest = experiment.read(root / 'collection.json')
            manifest['origins'] = [{'seed': 999}]
            experiment.document(root / 'collection.json', manifest, immutable=False)
            with self.assertRaisesRegex(ValueError, 'prospective source'):
                experiment.completed_native(root, source)

    def test_coherent_alternative_model_cannot_replace_the_planned_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source = completed_collection(root)
            other = root / 'another-policy.json'
            other.write_text('{"different":"policy"}')
            manifest = experiment.read(root / 'collection.json')
            manifest['baseline'] = {'path': str(other), 'sha256': experiment.sha(other)}
            experiment.document(root / 'collection.json', manifest, immutable=False)
            with self.assertRaisesRegex(ValueError, 'planned source'):
                experiment.completed_native(root, source)

    def test_fit_with_self_consistent_hash_cannot_be_copied_from_another_collection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fit = root / 'fit'
            fit.mkdir()
            collection = root / 'collection'
            collection.mkdir()
            identity = {'collection': {'source': str(root / 'another-collection'),
                                       'signature': 'same'}}
            digest = hashlib.sha256(json.dumps(identity, sort_keys=True,
                                              separators=(',', ':')).encode()).hexdigest()
            experiment.document(fit / 'identity.json', identity)
            experiment.document(fit / 'finished.json', {'identitySha256': digest})
            experiment.document(collection / 'collection.json', {'signature': 'same'})
            with self.assertRaisesRegex(ValueError, 'different collection'):
                experiment.completed_fit(fit, collection, {}, root)

    def test_fit_report_cannot_substitute_a_different_training_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            experiment.document(root / 'identity.json', {'collection': 'original'})
            experiment.document(root / 'finished.json', {'identitySha256': '0' * 64})
            with self.assertRaisesRegex(ValueError, 'identity changed'):
                experiment.completed_fit(root, root, {}, root)

    def test_finish_publishes_result_and_status_without_duplicate_preflight_keyword(self):
        with tempfile.TemporaryDirectory() as directory:
            window = experiment.Window.__new__(experiment.Window)
            window.lab = Path(directory)
            window.deadline = 1234
            window.preflight_hash = 'fixed-preflight'
            result = window.finish('independent-qualification-failed', primaryPassed=False,
                                   robustnessPassed=True)
            self.assertEqual(experiment.read(window.lab / 'window-result.json'), result)
            status = experiment.read(window.lab / 'window-status.json')
            self.assertEqual(status['stage'], 'finished')
            self.assertEqual(status['preflightSha256'], 'fixed-preflight')
            self.assertFalse(status['browserActivated'])

    def test_optional_evaluation_batching_keeps_default_and_collection_commands_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            window = experiment.Window.__new__(experiment.Window)
            window.lab = root
            window.scripts = root / 'bin'
            window.runtime = root / 'lib'
            window.deadline = 100000
            for mode, batch, expected in [('evaluate', None, False), ('evaluate', 8, True),
                                          ('collect', 8, False)]:
                window.protocol = {} if batch is None else {'evaluation': {'batchSize': batch}}
                with patch.object(experiment, 'completed_native', side_effect=[False, True]), \
                     patch.object(window, 'execute') as execute:
                    window.native(mode, mode + str(batch), {}, 60)
                command = execute.call_args.args[1]
                self.assertEqual('--batch-size' in command, expected)
                if expected:
                    self.assertEqual(command[command.index('--batch-size') + 1], '8')

    def test_evaluation_batch_setting_rejects_boolean_and_out_of_range_values(self):
        window = experiment.Window.__new__(experiment.Window)
        for value in (True, 0, 65, '8'):
            window.protocol = {'evaluation': {'batchSize': value}}
            with self.assertRaisesRegex(ValueError, 'integer'):
                window.native('evaluate', 'unused', {}, 60)

    def test_legacy_fit_config_defaults_to_64_and_compact_requires_explicit_eight(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            collection, fit, scripts = (root / name for name in ('collection', 'fit', 'bin'))
            for path in (collection, fit, scripts):
                path.mkdir()
            completed_collection(collection)
            for name in ('card_advantage_train.py', 'card_advantage.py',
                         'card_advantage_data.py', 'purchase_policy.py'):
                (scripts / name).write_text(name)
            protocol = {'fit': dict(learningRate=.003, weightDecay=.0001, gradientNormLimit=1.,
                wholeOriginsPerBatch=16, epochs=64, checkpoints=[8, 16, 32, 64],
                initializationSeeds=[84501, 84502, 84503], threads=1, maximumSecondsPerCycle=2700)}
            names = {'collection.json', 'finished.json', 'source.json', 'baseline.json',
                     'frozen-purchase-policy.json', 'origin-0.json.gz'}
            identity = {'collection': {'source': str(collection.resolve()), 'signature': 'fixed',
                'fileHashes': {name: experiment.sha(collection / name) for name in names}},
                'sourceHashes': {str(path.resolve()): experiment.sha(path) for path in scripts.iterdir()},
                'config': experiment.fit_config(protocol)}
            del identity['config']['activeHidden']
            for name in ('full.json', 'action-priority.json', 'frequency-control.json'):
                (fit / name).write_text('{}')
            def save():
                experiment.document(fit / 'identity.json', identity, immutable=False)
                digest = hashlib.sha256(json.dumps(identity, sort_keys=True,
                    separators=(',', ':'), allow_nan=False).encode()).hexdigest()
                report = {'identitySha256': digest, 'models': {name: {
                    'sha256': experiment.sha(fit / (name + '.json'))} for name in ('full', 'action-priority')},
                    'frequencyControlSha256': experiment.sha(fit / 'frequency-control.json')}
                experiment.document(fit / 'finished.json', report, immutable=False)
                return report
            report = save()
            self.assertEqual(experiment.completed_fit(fit, collection, protocol, scripts), report)
            protocol['fit']['activeHidden'] = 8
            with self.assertRaisesRegex(ValueError, 'frozen recipe'):
                experiment.completed_fit(fit, collection, protocol, scripts)
            identity['config']['activeHidden'] = 8
            report = save()
            self.assertEqual(experiment.completed_fit(fit, collection, protocol, scripts), report)

    def test_missing_preflight_prevents_any_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            experiment.document(root / 'clock.json', dict(
                deadline='2026-11-07T13:10:00Z',
                collectionAdmissionDeadline='2026-11-07T10:10:00Z',
                latestNewCollection='2026-11-07T09:10:00Z'))
            protocol = root / 'protocol.json'
            experiment.document(protocol, {'clock': {'file': 'clock.json'}})
            with self.assertRaises(FileNotFoundError):
                experiment.Window(root, protocol)

    def test_expired_collection_and_fit_resumes_take_the_timeout_path(self):
        window = experiment.Window.__new__(experiment.Window)
        window.deadline = 100000
        with patch.object(experiment.time, 'time', return_value=4000):
            for stage in ('cycle-2', 'cycle-2-fit'):
                with self.subTest(stage=stage):
                    with self.assertRaises(experiment.StageTimeout):
                        window.execute(stage, ['must-not-run'], 9000, original_deadline=3000)

    def test_coverage_counts_actual_rebate_and_late_context_without_using_returns(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            request = {'index': 0, 'split': 'validation'}
            origin = dict(request=request, signature='fixed', status='collected',
                          groups=[{'kind': 'root', 'epoch': 3, 'observationSha256': 'safe'}],
                          observations={'safe': {
                              'stateFeatures': {'role.0.advance.PRINTED_WORD': 1},
                              'actions': [{'features': {'cardKind.LEADER': 1}}]}},
                          outcomes='Deliberately not a terminal-return object')
            blob = gzip.compress(json.dumps(origin).encode())
            (root / 'origin-0.json.gz').write_bytes(blob)
            experiment.document(root / 'collection.json', dict(signature='fixed', origins=[request]))
            experiment.document(root / 'state.json', dict(signature='fixed', phase='complete'))
            experiment.document(root / 'finished.json', dict(
                signature='fixed', origins=1, originSha256=[hashlib.sha256(blob).hexdigest()]))
            result = experiment.coverage(root, {'validation': {
                'origins': 1, 'epoch3': 1, 'printedWordLeader': 1}})
            self.assertTrue(result['passed'])
            self.assertFalse(result['labelsInspected'])

    def test_completed_marker_without_all_records_cannot_satisfy_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            experiment.document(root / 'collection.json', dict(signature='fixed', origins=[{}]))
            experiment.document(root / 'state.json', dict(signature='fixed', phase='complete'))
            experiment.document(root / 'finished.json', dict(
                signature='fixed', origins=1, originSha256=[]))
            with self.assertRaisesRegex(ValueError, 'complete declared collection'):
                experiment.coverage(root, {'validation': {'origins': 1}})


if __name__ == '__main__':
    unittest.main()
