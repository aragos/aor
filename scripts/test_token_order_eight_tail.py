"""Synthetic fresh-bank identity, eight-tail target and frozen-head selection contracts."""
import copy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import token_order_eight_tail as fresh
from test_token_order_policy import overlay_collection_fixture, schema

b, q, token = fresh.b, fresh.q, fresh.token


def fixture(root):
    bank = root/'collection'
    bank.mkdir()
    overlay_collection_fixture(bank)
    source, manifest = [b.read_json(bank/name) for name in ('source.json', 'run.json')]
    for value in (source, manifest):
        value.update(continuationProtocol=fresh.PROTOCOL, continuationReplicates=8)
    manifest['signature'] = 'new-eight-tail-bank'
    b.atomic_json(bank/'source.json', source)
    manifest['sourceSha256'] = b.file_digest(bank/'source.json')
    for index in range(4):
        path = bank/f'origin-{index}.json'
        row = b.read_json(path)
        row['signature'] = manifest['signature']
        for arm in row['alternatives']:
            old = arm['outcomes']
            arm['outcomes'] = [dict(copy.deepcopy(old[i%2]),
                continuationSeed=(row['gameSeed'] << 3)^i^0x6f20a5319d) for i in range(8)]
            if arm['command'] == 'buy-tokens 0':
                for outcome in arm['outcomes'][2:]:
                    outcome.update(reward=0., winShare=0., rankReward=0.)
        b.atomic_json(path, row)
    b.atomic_json(bank/'run.json', manifest)
    b.atomic_json(bank/'dataset.json', dict(manifest, complete=True, counts={'COLLECTED':4}))
    finish(bank)
    initializer = q.initialize(schema(), 137, 'token')
    b.atomic_json(root/'initializer.json', initializer)
    original = dict(kind='token-order-paired-fit', initializedModelSha256=b.digest(initializer),
                    source={'signature':'original-two-tail-bank','files':{'unrelated-original-data':'old'}})
    b.atomic_json(root/'original-basis.json', original)
    return fresh.InputBindings(str(bank.resolve()), b.file_digest(bank/'source.json'),
        b.file_digest(bank/'dataset.json'), b.file_digest(bank/'finished.json'),
        str((root/'initializer.json').resolve()), b.file_digest(root/'initializer.json'),
        b.digest(initializer), str((root/'original-basis.json').resolve()), b.file_digest(root/'original-basis.json'))


def finish(bank):
    b.atomic_json(bank/'finished.json', dict(kind=token.KIND, signature='new-eight-tail-bank',
        originSha256=[b.file_digest(bank/f'origin-{i}.json') for i in range(4)]))


class FreshEightTailTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bindings = fixture(self.root)

    def test_fresh_bank_uses_original_basis_without_reusing_old_bank(self):
        with mock.patch.object(q, 'initialize', side_effect=AssertionError('No regenerated basis')):
            model, rows, provenance = fresh.fresh_collection(self.bindings)
        self.assertEqual(b.read_json(self.bindings.initializer), model)
        self.assertEqual(4, len(rows))
        self.assertEqual('new-eight-tail-bank', provenance['signature'])
        self.assertEqual(b.file_digest(token.metadata.__file__), provenance['metadataValidatorSha256'])

    def test_primary_targets_use_all_eight_streams_not_the_two_stream_prefix(self):
        model, rows, _ = fresh.fresh_collection(self.bindings)
        primary = rows[0]
        action = next(i for i in primary['indices'] if primary['observation']['actions'][i]['command']=='buy-tokens 0')
        diagnostic = fresh.prefix_rows(self.bindings.collection, model, rows, 2)
        self.assertAlmostEqual(-.125, primary['targets'][action])
        self.assertAlmostEqual(.4, diagnostic[0]['targets'][action])

    def test_changed_eighth_stream_is_rejected(self):
        bank = Path(self.bindings.collection)
        row = b.read_json(bank/'origin-0.json')
        row['alternatives'][0]['outcomes'][7]['continuationSeed'] += 1
        b.atomic_json(bank/'origin-0.json', row)
        finish(bank)
        bindings = replace(self.bindings, finishedSha256=b.file_digest(bank/'finished.json'))
        with self.assertRaisesRegex(ValueError, 'precommitted chance streams'):
            fresh.fresh_collection(bindings)

    def test_old_loader_does_not_silently_accept_eight_tail_data(self):
        with self.assertRaises(ValueError):
            token.collection(self.bindings.collection, None, supplied_initializer=b.read_json(self.bindings.initializer))

    def test_original_basis_identity_must_match_the_initializer(self):
        path = Path(self.bindings.originalBasisIdentity)
        identity = b.read_json(path)
        identity['initializedModelSha256'] = '0'*64
        b.atomic_json(path, identity)
        bindings = replace(self.bindings, originalBasisIdentitySha256=b.file_digest(path))
        with self.assertRaisesRegex(ValueError, 'exact original'):
            fresh.fresh_collection(bindings)

    def test_previously_inspected_validation_is_not_fresh_evidence(self):
        with self.assertRaisesRegex(ValueError, 'fresh prospective'):
            replace(self.bindings, validationPreviouslyInspected=True).validate()

    def test_validation_prefixes_are_diagnostics_after_primary_selection(self):
        report = fresh.fit(self.bindings, self.root/'fit', fresh.ridge.FitConfig())
        selected = fresh.ridge.select_candidate(report['fits'], report['initializerValidation']['pairedMse'])
        self.assertEqual(selected, report['selected'])
        frozen = b.read_json(self.root/'fit/selection.json')
        diagnostics = b.read_json(self.root/'fit/prefix-diagnostics.json')
        self.assertEqual(selected, frozen['selected'])
        self.assertFalse(frozen['prefixDiagnosticsUsed'])
        self.assertEqual(b.file_digest(self.root/'fit/selection.json'), diagnostics['selectionSha256'])
        self.assertEqual({'2','4'}, set(diagnostics['prefixes']))
        self.assertTrue(all(fit['onlyOutputWeightsChanged'] for fit in report['fits']))

    def test_negative_policy_mean_rejects_mse_improvement(self):
        selected = {'validation':dict(observedMeanDifference=-.01, overrides=20,
            positiveOrigins=10, largestPositiveFraction=.2)}
        self.assertFalse(fresh.scaling_passes(selected))

    def test_concentrated_positive_gain_rejects_model(self):
        selected = {'validation':dict(observedMeanDifference=.01, overrides=20,
            positiveOrigins=10, largestPositiveFraction=.6)}
        self.assertFalse(fresh.scaling_passes(selected))


if __name__ == '__main__':
    unittest.main()
