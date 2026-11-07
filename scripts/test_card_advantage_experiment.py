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
