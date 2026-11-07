import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import card_advantage as advantage
import card_advantage_capacity as capacity
import card_advantage_data as data
import purchase_policy as common
from test_card_advantage import SCHEMA
from test_card_advantage_data import fixture, read_origin, write_origin, finish


class CapacityValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.training = self.root / 'training'
        self.validation = self.root / 'validation'
        self.fit = self.root / 'fit'
        for path in (self.training, self.validation, self.fit):
            path.mkdir()
        fixture(self.training)
        fixture(self.validation)
        for path in (self.training, self.validation):
            setup = common.read_json(path / 'collection.json')
            setup['configuration'] = 'synthetic'
            common.atomic_json(path / 'collection.json', setup)
        manifest = common.read_json(self.validation / 'collection.json')
        for request in manifest['origins']:
            request.update(split='validation', seed=request['seed'] + 100)
            origin = read_origin(self.validation, request['index'])
            origin.update(split='validation', seed=request['seed'], request=copy.deepcopy(request))
            write_origin(self.validation, request['index'], origin)
        common.atomic_json(self.validation / 'collection.json', manifest)
        finish(self.validation)
        model = advantage.bundle(SCHEMA, [advantage.initialize(SCHEMA, seed, 8)
                                          for seed in advantage.SEEDS])
        self.reference = self.root / 'reference.json'
        common.atomic_json(self.reference, advantage.bundle(SCHEMA, [
            advantage.initialize(SCHEMA, seed) for seed in advantage.SEEDS]))
        for name in ('full.json', 'action-priority.json'):
            common.atomic_json(self.fit / name, model)
        common.atomic_json(self.fit / 'frequency-control.json', advantage.frequency_control(0., 0.))
        identity = {'config': {'activeHidden': 8}, 'collection': {
            'source': str(self.training), 'fileHashes': {
                'collection.json': common.file_digest(self.training / 'collection.json')}}}
        common.atomic_json(self.fit / 'identity.json', identity)
        common.atomic_json(self.fit / 'finished.json', {
            'identitySha256': common.digest(identity),
            'models': {name: {'sha256': common.file_digest(self.fit / (name + '.json'))}
                       for name in ('full', 'action-priority')},
            'frequencyControlSha256': common.file_digest(self.fit / 'frequency-control.json')})

    def tearDown(self):
        self.temporary.cleanup()

    def analyze(self):
        return capacity.analyze(self.validation, self.fit, self.reference,
                                common.file_digest(self.reference))

    def test_fallback_worlds_remain_in_the_fresh_comparison(self):
        report = self.analyze()
        self.assertEqual(report['candidate']['origins'], 3)
        self.assertEqual(report['candidate']['meanSelectedAdvantage'], 0.)
        self.assertFalse(report['candidate']['localGatePassed'])
        self.assertEqual(report['candidateMinusReference']['independentWorlds'], 3)
        self.assertEqual(report['candidateMinusReference']['mean'], 0.)
        self.assertFalse(report['strengthQualified'])

    def test_reused_world_is_rejected_before_outcomes_are_read(self):
        manifest = common.read_json(self.validation / 'collection.json')
        manifest['origins'][0]['seed'] = 100
        common.atomic_json(self.validation / 'collection.json', manifest)
        with patch.object(data, 'read_collection', side_effect=AssertionError('outcomes opened')):
            with self.assertRaisesRegex(ValueError, 'fresh validation-only'):
                self.analyze()

    def test_candidate_rival_is_rejected_before_outcomes_are_read(self):
        manifest = common.read_json(self.validation / 'collection.json')
        manifest['origins'][0]['opponentUsesCandidate'][1] = True
        common.atomic_json(self.validation / 'collection.json', manifest)
        with patch.object(data, 'read_collection', side_effect=AssertionError('outcomes opened')):
            with self.assertRaisesRegex(ValueError, 'F continuations'):
                self.analyze()

    def test_changed_model_is_rejected_before_outcomes_are_read(self):
        with (self.fit / 'full.json').open('a') as stream:
            stream.write(' ')
        with patch.object(data, 'read_collection', side_effect=AssertionError('outcomes opened')):
            with self.assertRaisesRegex(ValueError, 'model or frequency control changed'):
                self.analyze()

    def test_different_reference_hash_is_rejected_before_outcomes_are_read(self):
        with patch.object(data, 'read_collection', side_effect=AssertionError('outcomes opened')):
            with self.assertRaisesRegex(ValueError, 'frozen P1 hash'):
                capacity.analyze(self.validation, self.fit, self.reference, '0' * 64)

    def test_pairing_weights_worlds_equally_despite_decision_counts(self):
        candidate = {'perOrigin': [dict(origin='a', decisions=10, meanSelectedAdvantage=.2),
                                   dict(origin='b', decisions=1, meanSelectedAdvantage=-.1)]}
        reference = {'perOrigin': [dict(origin='b', decisions=1, meanSelectedAdvantage=0.),
                                   dict(origin='a', decisions=10, meanSelectedAdvantage=.1)]}
        report = capacity.paired_selected(candidate, reference)
        self.assertAlmostEqual(report['mean'], 0.)
        self.assertEqual(report['independentWorlds'], 2)

    def test_pairing_rejects_different_worlds(self):
        left = {'perOrigin': [dict(origin='a', meanSelectedAdvantage=0.)]}
        right = {'perOrigin': [dict(origin='b', meanSelectedAdvantage=0.)]}
        with self.assertRaisesRegex(ValueError, 'same distinct original worlds'):
            capacity.paired_selected(left, right)


if __name__ == '__main__':
    unittest.main()
