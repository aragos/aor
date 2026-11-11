"""Synthetic-only clock arithmetic and detached identity/phase-adapter checks; no fits."""
import copy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import joint_credit_targets as targets
import joint_trajectory as joint
import purchase_policy as policy
import test_joint_trajectory as fixtures


def row(origin, number, reward=1., forced=False):
    return dict(originIndex=origin, number=number, **{'return': reward},
        observation=dict(stateFeatures={'cash': .2}, actions=[{}] if forced else [{}, {}]),
        selectedIndex=0, behaviorLogProbability=-.5, behaviorValue=.123)


def predictions(root, rows, provenance, hashes, values):
    descriptor = root/'descriptor.json'
    policy.atomic_json(descriptor, dict(kind=targets.DESCRIPTOR, checkpointSha256='a'*64,
        stateDictionarySha256='b'*64, inferenceContractSha256='c'*64))
    path = root/'shared-values.json'
    policy.atomic_json(path, dict(kind=targets.PREDICTIONS, newFits=0,
        collectionSha256=provenance['collectionSha256'], gameHashes=provenance['gameHashes'],
        behaviorModels=hashes,
        predictorDescriptor=dict(path=str(descriptor), sha256=policy.file_digest(descriptor)),
        predictions=[dict(item[2], value=value)
                     for item, value in zip(targets.chronology(rows, provenance), values)]))
    return path


class TargetTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.rows = dict(card=[row(0, 2), row(0, 11)], purchase=[row(0, 7, forced=True)])
        self.provenance = dict(collectionSha256='d'*64, gameHashes=['e'*64, 'f'*64], games=2)
        self.hashes = dict(card='1'*64, purchase='2'*64)
        self.values = [.2, .4, .3]

    def pair(self):
        path = predictions(self.root, self.rows, self.provenance, self.hashes, self.values)
        return targets.build_targets(self.rows, self.provenance, self.hashes, path)

    def test_chronological_clock_matches_hand_computed_interleaving(self):
        actual = [item['advantage'] for item in self.pair()['chronological']['targets']]
        expected = [.2+.95*(-.1+.95*.7), -.1+.95*.7, .7]
        for left, right in zip(actual, expected):
            self.assertAlmostEqual(left, right, places=14)

    def test_phase_separated_clock_skips_other_phase_rows(self):
        actual = [item['advantage'] for item in self.pair()['phase-separated']['targets']]
        expected = [.1+.95*.7, .6, .7]
        for left, right in zip(actual, expected):
            self.assertAlmostEqual(left, right, places=14)

    def test_both_clocks_bind_one_identical_state_value_file(self):
        pair = self.pair()
        self.assertEqual(pair['chronological']['frozenStatePredictions'],
                         pair['phase-separated']['frozenStatePredictions'])

    def test_forced_middle_row_remains_a_chronological_tick(self):
        full = self.pair()['chronological']['targets'][0]['advantage']
        self.rows['purchase'] = []
        reduced = policy.purchase_gae(self.rows['card'], [.2, .3])[0]
        self.assertNotEqual(full, reduced)

    def test_purchase_only_outputs_are_bit_identical_to_existing_arithmetic(self):
        self.rows = dict(card=[], purchase=[row(0, 2), row(0, 7), row(0, 11)])
        original = policy.purchase_gae(self.rows['purchase'], self.values)
        for document in self.pair().values():
            self.assertEqual([value.hex() for value in original],
                             [item['advantage'].hex() for item in document['targets']])

    def test_original_state_actions_behavior_and_returns_are_unmodified(self):
        before = copy.deepcopy(self.rows)
        self.pair()
        self.assertEqual(self.rows, before)

    def test_terminal_reward_and_zero_next_value_apply_to_each_clock_last_row(self):
        self.rows = dict(card=[row(0, 2, reward=.5)], purchase=[row(0, 9, reward=.5)])
        self.values = [.2, .4]
        pair = self.pair()
        self.assertAlmostEqual(pair['chronological']['targets'][-1]['advantage'], .1)
        self.assertAlmostEqual(pair['phase-separated']['targets'][0]['advantage'], .3)

    def test_empty_worlds_remain_in_binding_without_invented_rows(self):
        pair = self.pair()
        self.assertEqual(pair['chronological']['gameHashes'], self.provenance['gameHashes'])
        self.assertEqual(len(pair['chronological']['targets']), 3)

    def test_entirely_empty_batch_produces_no_targets(self):
        self.rows, self.values = dict(card=[], purchase=[]), []
        self.assertTrue(all(document['targets'] == [] for document in self.pair().values()))

    def test_worlds_never_bootstrap_into_each_other(self):
        self.rows = dict(card=[row(0, 2, .3), row(1, 0, .9)], purchase=[])
        self.values = [.2, .7]
        actual = self.pair()['chronological']['targets']
        self.assertAlmostEqual(actual[0]['advantage'], .1)
        self.assertAlmostEqual(actual[1]['advantage'], .2)

    def test_source_row_resets_within_each_phase_and_world(self):
        self.rows['card'].append(row(1, 3))
        self.values.append(.5)
        actual = self.pair()['chronological']['targets']
        self.assertEqual([(item['phase'], item['sourceRow']) for item in actual],
                         [('card', 0), ('purchase', 0), ('card', 1), ('card', 0)])

    def test_duplicate_absolute_decisions_across_phases_are_rejected(self):
        self.rows['purchase'][0]['number'] = 2
        with self.assertRaisesRegex(ValueError, 'unique across both phases'):
            targets.chronology(self.rows, self.provenance)

    def test_shuffled_phase_rows_are_rejected_before_merging(self):
        self.rows['card'].reverse()
        with self.assertRaisesRegex(ValueError, 'original phase rows|Original phase rows'):
            targets.chronology(self.rows, self.provenance)

    def changed_prediction(self, change, message):
        path = predictions(self.root, self.rows, self.provenance, self.hashes, self.values)
        document = policy.read_json(path)
        change(document)
        policy.atomic_json(path, document)
        with self.assertRaisesRegex(ValueError, message):
            targets.build_targets(self.rows, self.provenance, self.hashes, path)

    def test_wrong_behavior_head_is_rejected(self):
        self.changed_prediction(lambda doc: doc['behaviorModels'].update(card='9'*64), 'both original behavior')

    def test_wrong_source_row_is_rejected(self):
        self.changed_prediction(lambda doc: doc['predictions'][0].update(sourceRow=1), 'sourceRow identity')

    def test_wrong_phase_is_rejected(self):
        self.changed_prediction(lambda doc: doc['predictions'][0].update(phase='purchase'), 'sourceRow identity')

    def test_wrong_absolute_decision_is_rejected(self):
        self.changed_prediction(lambda doc: doc['predictions'][0].update(decision=3), 'absolute-decision')

    def test_float_absolute_decision_cannot_alias_an_integer_identity(self):
        self.changed_prediction(lambda doc: doc['predictions'][0].update(decision=2.), 'absolute-decision')

    def test_boolean_source_row_cannot_alias_an_integer_identity(self):
        self.changed_prediction(lambda doc: doc['predictions'][0].update(sourceRow=False), 'sourceRow identity')

    def test_missing_forced_value_is_rejected(self):
        self.changed_prediction(lambda doc: doc['predictions'].pop(1), 'including forced rows')

    def test_changed_predictor_descriptor_is_rejected(self):
        path = predictions(self.root, self.rows, self.provenance, self.hashes, self.values)
        descriptor = self.root/'descriptor.json'
        document = policy.read_json(descriptor)
        document['checkpointSha256'] = '9'*64
        policy.atomic_json(descriptor, document)
        with self.assertRaisesRegex(ValueError, 'predictor descriptor changed'):
            targets.build_targets(self.rows, self.provenance, self.hashes, path)


def untrained_actor(root, phase):
    """Real typed initial actors for the existing native-shaped fixture, without optimizer steps."""
    contract = joint.CONTRACTS[phase]
    model = policy.initialize(dict(encoderVersion=contract.encoder,
        stateFeatureNames=['state'], actionFeatureNames=['choice']), 19, contract=contract)
    folder = root/(phase+'-start')
    folder.mkdir()
    path = folder/'policy.json'
    policy.atomic_json(path, model)
    return path, folder/'unused-checkpoint.json', policy.ResidualPolicy(model, contract)


class AdapterTests(unittest.TestCase):
    def setUp(self):
        policy.torch.set_num_threads(1)
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        with patch.object(fixtures, 'trained_actor', side_effect=untrained_actor):
            self.directory, self.bundle, _ = fixtures.fixture(self.root)
        self.collection = joint.JointCollection(self.directory, self.bundle)
        hashes = {phase: self.collection.bundle[phase]['sha256'] for phase in joint.PHASES}
        self.values = predictions(self.root, self.collection.rows, self.collection.common, hashes, [.2, .4])
        targets.write_targets(self.collection, self.values, self.root/'targets')
        self.path = self.root/'targets/chronological.json'

    def adapter(self):
        return targets.DetachedJointTargets(self.collection, self.values, self.path, targets.Clock.CHRONOLOGICAL)

    def test_phase_round_trip_preserves_every_existing_row_field(self):
        adapter = self.adapter()
        for phase in joint.PHASES:
            rows, provenance = adapter.phase_reader(self.directory,
                self.collection.bundle[phase]['path'], self.collection.models[phase], contract=joint.CONTRACTS[phase])
            for actual, original in zip(rows, self.collection.rows[phase]):
                self.assertEqual({key: value for key, value in actual.items() if key != '_actorOnlyAdvantage'}, original)
            self.assertEqual(provenance['jointDetachedTargets']['phase'], phase)
            self.assertEqual(provenance['readerSha256'], policy.file_digest(targets.__file__))

    def test_existing_reader_model_identity_guard_still_rejects_wrong_phase(self):
        with self.assertRaisesRegex(ValueError, 'original typed behavior view'):
            self.adapter().phase_reader(self.directory, self.collection.bundle['card']['path'],
                self.collection.models['card'], contract=joint.CONTRACTS['purchase'])

    def test_modified_advantage_fails_exact_rederivation(self):
        document = policy.read_json(self.path)
        document['targets'][0]['advantage'] += .01
        policy.atomic_json(self.path, document)
        with self.assertRaisesRegex(ValueError, 'must match the original complete rows'):
            self.adapter()

    def test_clock_identity_cannot_be_relabelled(self):
        with self.assertRaisesRegex(ValueError, 'declared clock'):
            targets.DetachedJointTargets(self.collection, self.values, self.path, targets.Clock.PHASE_SEPARATED)

    def test_prediction_change_after_binding_is_rejected(self):
        adapter = self.adapter()
        document = policy.read_json(self.values)
        document['predictions'][0]['value'] = .5
        policy.atomic_json(self.values, document)
        with self.assertRaisesRegex(ValueError, 'Frozen joint inputs changed'):
            adapter.phase_reader(self.directory, self.collection.bundle['card']['path'],
                self.collection.models['card'], contract=joint.CONTRACTS['card'])

    def test_existing_target_pair_cannot_be_overwritten(self):
        with self.assertRaisesRegex(ValueError, 'previously published'):
            targets.write_targets(self.collection, self.values, self.root/'targets')

    def test_adapter_cannot_bypass_existing_purchase_only_update_guard(self):
        with self.assertRaisesRegex(ValueError, 'fresh purchase Adam'):
            policy.update(self.collection.bundle['card']['path'], self.directory,
                policy.UpdateConfig(valueCoefficient=0), self.root/'forbidden-update',
                contract=joint.CONTRACTS['card'], actor_only=self.adapter())
        self.assertFalse((self.root/'forbidden-update').exists())


if __name__ == '__main__':
    unittest.main()
