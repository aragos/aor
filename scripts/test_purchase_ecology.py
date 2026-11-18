import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

import purchase_ecology as pilot
import purchase_ecology_data as data


class PurchaseEcologyTest(unittest.TestCase):
    def protocol(self):
        return dict(configuration=pilot.exp.CONFIGURATION, workers=6, baseline='/f1.json', prior='/f0.json',
                    purchasePolicy='/c.json', expansionModel='/e.json', opponents=['/f1.json'] * 4,
                    experimentalRulesTransfer=pilot.legacy.RULES_TRANSFER,
                    banks={'training-1': dict(nativeOffset=32000000),
                           'screen': dict(nativeOffset=32000008, count=512)})

    def test_collection_retains_sampled_actor_and_fixed_rival_purchase(self):
        source = pilot.collection_source(self.protocol(), '/new-actor.json', 1)
        self.assertEqual('/new-actor.json', source['policy'])
        self.assertEqual('/c.json', source['opponentPurchasePolicy'])
        self.assertEqual('/f0.json', source['sharedPurchaseBaseline'])
        self.assertNotIn('frozenPurchase', source)
        self.assertEqual([None, '/e.json', None, '/e.json'], source['expansionImitation']['opponentModels'])
        self.assertEqual(['ORDINARY', 'ORDINARY', data.FIXED, data.FIXED], source['tokenOrder']['opponentSelectors'])
        self.assertNotIn('incumbentModel', source['expansionImitation'])
        self.assertEqual([0, 85, 170, 255], source['replayIndices'])

    def test_arena_changes_only_controlled_purchase_actor(self):
        source = pilot.arena_source(self.protocol(), '/new-actor.json')
        self.assertEqual({'baseline': '/f0.json', 'policy': '/c.json',
                          'candidatePolicy': '/new-actor.json', 'incumbentPolicy': '/c.json'}, source['frozenPurchase'])
        self.assertEqual('/e.json', source['expansionImitation']['candidateModel'])
        self.assertEqual('/e.json', source['expansionImitation']['incumbentModel'])
        self.assertEqual(data.FIXED, source['tokenOrder']['candidateSelector'])
        self.assertEqual(data.FIXED, source['tokenOrder']['incumbentSelector'])
        self.assertEqual('SCREENING', source['purpose'])

    def test_extra_batch_rejected(self):
        with self.assertRaisesRegex(ValueError, 'eight-batch'):
            pilot.collection_source(self.protocol(), '/actor.json', 9)

    def test_arena_replay_respects_native_one_hour_ceiling(self):
        protocol = dict(java={'executable': '/java'}, workers=6, runtime='/runtime')
        with patch.object(pilot.exp, 'remaining_seconds', return_value=14000):
            command = pilot.java_command(protocol, 'strategy-replay', '/source', '/output')
        self.assertEqual('1.0', command[command.index('--hours') + 1])

    def test_purchase_replay_respects_remaining_deadline(self):
        protocol = dict(java={'executable': '/java'}, workers=6, runtime='/runtime')
        with patch.object(pilot.exp, 'remaining_seconds', return_value=900):
            command = pilot.java_command(protocol, 'purchase-replay', '/source', '/output')
        self.assertEqual('0.25', command[command.index('--hours') + 1])

    def test_rejected_initial_update_can_preserve_empty_adam(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = {'weights': [1., 2.]}
            payload = dict(model=model, optimizer={'state': {}, 'param_groups': [{'lr': .0003}]})
            (root/'model.json').write_text(json.dumps(model))
            (root/'checkpoint.json').write_text(json.dumps(dict(payload=payload, sha256=pilot.legacy.digest(payload))))
            self.assertEqual(payload, pilot.checkpoint(root/'checkpoint.json', root/'model.json'))

    def test_changed_optimizer_checksum_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'model.json').write_text('{}')
            (root/'checkpoint.json').write_text(json.dumps(dict(payload={}, sha256='0'*64)))
            with self.assertRaisesRegex(ValueError, 'checksum'):
                pilot.checkpoint(root/'checkpoint.json', root/'model.json')

    def test_custom_reader_preserves_learning_rows_and_binds_environment(self):
        rows = [{'observation': 'actor-visible-only'}]
        provenance = {'games': 256, 'collectionSignature': 'signature'}
        expected = dict(provenance, ecologyFiles={'source.json': 'hash'}, readerSha256='reader')
        learner = types.SimpleNamespace(PURCHASE_CONTRACT='purchase',
            read_collection=lambda *args, **kwargs: (rows, provenance))
        with patch.dict('sys.modules', {'purchase_policy': learner}), \
             patch.object(data, 'verify_collection', return_value=(expected, 1)):
            actual_rows, actual_provenance = data.read_collection('/bank', '/model', {}, contract='purchase')
        self.assertIs(rows, actual_rows)
        self.assertEqual(expected, actual_provenance)

    def test_descriptive_completion_cannot_qualify_even_when_stage_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'protocol.json').write_text('{}')
            (root/'logs').mkdir()
            (root/'logs/live.log').write_text('still changing')
            with patch.object(pilot.exp, 'stage_result') as result:
                pilot.result(root/'protocol.json', root, 'screen', True, {'cells': []})
            report, artifacts = result.call_args.args[4:]
            self.assertFalse(report['qualificationPassed'])
            self.assertFalse(report['productionActivation'])
            self.assertFalse(report['humanStrengthVerified'])
            self.assertNotIn(root/'logs/live.log', artifacts)

    def test_recovery_imports_completed_actor_without_repeating_first_batch(self):
        class NativeReached(Exception):
            pass
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prefix = root/'prefix'
            (prefix/'update').mkdir(parents=True)
            (prefix/'reproduction').mkdir()
            (prefix/'update/policy.json').write_text('{"learned": true}')
            (prefix/'update/checkpoint.json').write_text('{}')
            (prefix/'reproduction/reproduction.json').write_text('{}')
            receipt = dict(directory=str(prefix), outputPolicySha256=pilot.sha(prefix/'update/policy.json'),
                update={'acceptedEpochs': 4}, provenance={}, reproduction={})
            (root/'prefix.json').write_text(json.dumps(receipt))
            protocol = self.protocol()
            protocol['banks']['training-2'] = {'nativeOffset': 32000001}
            protocol['recovery'] = {'prefixReceipt': str(root/'prefix.json')}
            with patch.object(pilot, 'checkpoint'), patch.object(pilot.storage, 'archive', return_value={}), \
                 patch.object(pilot.exp, 'native', side_effect=NativeReached) as native:
                with self.assertRaises(NativeReached):
                    pilot.train(protocol, root/'protocol.json', root/'run')
            source = Path(native.call_args.args[2])
            self.assertEqual('batch-2', source.parent.name)
            self.assertEqual(32000001, json.loads(source.read_text())['seed'])
            self.assertEqual(str(root/'run/batches/batch-1/update/policy.json'), json.loads(source.read_text())['policy'])
            self.assertTrue(json.loads((root/'run/batches/batch-1/receipt.json').read_text())['imported'])


if __name__ == '__main__':
    unittest.main()
