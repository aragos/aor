"""Synthetic fixed-reference loader, paired-label and unchanged-basis export contracts."""
import copy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import token_order_eight_tail as fresh
import token_order_fixed_reference as fixed
from test_token_order_eight_tail import fixture, finish
from test_token_order_policy import observation, outcome, schema

b, q, token, ridge = fresh.b, fresh.q, fresh.token, fresh.ridge


def raw_root(amounts=(0, 15, 16, 17), baseline=16, replicates=2):
    view = observation(amounts=amounts, baseline=baseline)
    view['stateFeatures'].update({'role.0.cash': .5, 'role.0.stock': 1.})
    indices = token.proposal_indices(view)
    rewards = {0: .3, 15: .8, 16: .2, 17: .1}
    return dict(index=0, split='TRAINING', observation=view,
        baselineCommand=f'buy-tokens {baseline}', proposedIndices=indices,
        referenceIndex=fixed.expected_reference_index(view), alternatives=[dict(index=index,
            command=view['actions'][index]['command'],
            outcomes=[outcome(rewards[int(view['actions'][index]['command'].split()[1])], seed)
                      for seed in range(13, 13+replicates)]) for index in indices])


def residual_fixture(root):
    bindings = fixture(root)
    bank = Path(bindings.collection)
    source, manifest = [b.read_json(bank/name) for name in ('source.json', 'run.json')]
    for value in (source, manifest):
        value['continuationProtocol'] = fixed.PROTOCOL
        value['tokenOrder']['candidateSelector'] = fixed.SELECTOR
    manifest['tokenReference'].update(version=fixed.REFERENCE, selector=fixed.SELECTOR,
                                      proposalReference=token.TOKEN_REFERENCE_VERSION)
    for index in range(4):
        path = bank/f'origin-{index}.json'
        row = b.read_json(path)
        replacement = raw_root(replicates=8)
        for key in ('observation', 'baselineCommand', 'proposedIndices', 'referenceIndex', 'alternatives'):
            row[key] = replacement[key]
        for arm in row['alternatives']:
            for replicate, result in enumerate(arm['outcomes']):
                result.update(continuationSeed=(row['gameSeed'] << 3)^replicate^0x6f20a5319d,
                              scores=[0]*4)
        b.atomic_json(path, row)
    b.atomic_json(bank/'source.json', source)
    manifest['sourceSha256'] = b.file_digest(bank/'source.json')
    b.atomic_json(bank/'run.json', manifest)
    b.atomic_json(bank/'dataset.json', dict(manifest, complete=True, counts={'COLLECTED':4}))
    finish(bank)
    return replace(bindings, sourceSha256=b.file_digest(bank/'source.json'),
                   datasetSha256=b.file_digest(bank/'dataset.json'),
                   finishedSha256=b.file_digest(bank/'finished.json'))


class FixedReferenceTest(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.model = q.initialize(schema(), 137, 'token')

    def test_fixed_reference_keeps_ordinary_observation_and_proposals(self):
        raw = raw_root()
        result = token.validate_root(raw, self.model, 2, fixed_reference=True)
        self.assertEqual('buy-tokens 16', raw['observation']['actions'][result['observation']['baselineIndex']]['command'])
        self.assertEqual(token.proposal_indices(raw['observation']), result['indices'])
        self.assertEqual('buy-tokens 15', raw['observation']['actions'][result['referenceIndex']]['command'])
        self.assertAlmostEqual(-.6, result['targets'][result['observation']['baselineIndex']])
        self.assertEqual(0., result['targets'][result['referenceIndex']])

    def test_unoffered_fifteen_retains_ordinary_reference(self):
        raw = raw_root(amounts=(0, 16, 17))
        result = token.validate_root(raw, self.model, 2, fixed_reference=True)
        self.assertEqual(result['observation']['baselineIndex'], result['referenceIndex'])

    def test_other_ordinary_amount_is_not_shifted(self):
        raw = raw_root(baseline=17)
        result = token.validate_root(raw, self.model, 2, fixed_reference=True)
        self.assertEqual(result['observation']['baselineIndex'], result['referenceIndex'])

    def test_invalid_reference_indices_are_rejected(self):
        for reference in (None, True, 99, raw_root()['observation']['baselineIndex']):
            with self.subTest(reference=reference):
                raw = raw_root()
                raw['referenceIndex'] = reference
                with self.assertRaisesRegex(ValueError, 'exact offered fixed-T'):
                    token.validate_root(raw, self.model, 2, fixed_reference=True)

    def test_missing_reference_is_rejected(self):
        raw = raw_root()
        del raw['referenceIndex']
        with self.assertRaisesRegex(ValueError, 'exact offered fixed-T'):
            token.validate_root(raw, self.model, 2, fixed_reference=True)

    def test_ordinary_loader_cannot_silently_use_residual_reference(self):
        with self.assertRaisesRegex(ValueError, 'Ordinary roots'):
            token.validate_root(raw_root(), self.model, 2)

    def test_selector_uses_fixed_reference_for_zero_head_and_margin(self):
        row = token.validate_root(raw_root(), self.model, 2, fixed_reference=True)
        values = [0.]*len(row['observation']['actions'])
        self.assertEqual(row['referenceIndex'], token.select(values, row))
        baseline = row['observation']['baselineIndex']
        values[baseline] = token.MARGIN
        self.assertEqual(row['referenceIndex'], token.select(values, row))
        values[baseline] += .001
        self.assertEqual(baseline, token.select(values, row))

    def test_loss_and_affine_features_use_fifteen_as_reference(self):
        row = token.validate_root(raw_root(), self.model, 2, fixed_reference=True)
        values = b.torch.tensor([.01, .02, .03, .04], dtype=b.torch.float64)
        reference = row['referenceIndex']
        alternatives = [i for i in row['indices'] if i != reference]
        expected = sum(float(values[i]-values[reference]-row['targets'][i])**2
                       for i in alternatives)/len(alternatives)
        self.assertAlmostEqual(expected, float(token.paired_loss(values, row)))
        features, indices = ridge.paired_features(ridge.frozen_actor(self.model), row)
        fitted = copy.deepcopy(self.model)
        fitted['outputWeights'] = [i/1000. for i in range(64)]
        actual = q.ActionValue(fitted, 'token')([row['observation']])[0]
        predictions = features @ b.torch.tensor(fitted['outputWeights'], dtype=b.torch.float64)
        for index, prediction in zip(indices, predictions):
            self.assertAlmostEqual(float((actual[index]-actual[reference]).detach()),
                                   float(prediction), places=12)

    def test_measure_counts_overrides_from_fixed_reference(self):
        row = token.validate_root(raw_root(), self.model, 2, fixed_reference=True)
        report = token.measure(q.ActionValue(self.model, 'token'), [row])
        self.assertEqual(0, report['overrides'])
        self.assertEqual(0., report['observedMeanDifference'])
        self.assertEqual(row['referenceIndex'], report['selections'][0]['baseline'])

    def test_envelope_roundtrip_retains_exact_raw_q(self):
        self.assertEqual(self.model, fixed.unwrap(fixed.wrap(self.model)))
        with self.assertRaisesRegex(ValueError, 'field set'):
            q.validate_model(fixed.wrap(self.model), 'token')

    def test_residual_codec_rejects_plain_or_changed_contract(self):
        malformed = [self.model, dict(fixed.wrap(self.model), extra=True)]
        for key in fixed.ENVELOPE:
            malformed.append(dict(fixed.wrap(self.model), **{key: 'changed'}))
        for value in malformed:
            with self.subTest(keys=sorted(value)):
                with self.assertRaises(ValueError):
                    fixed.unwrap(value)


class FixedReferenceCollectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bindings = residual_fixture(self.root)

    def test_opt_in_loader_uses_original_basis_and_new_reference_provenance(self):
        with mock.patch.object(q, 'initialize', side_effect=AssertionError('No regenerated basis')):
            model, rows, provenance = fresh.fresh_collection(self.bindings, fixed_reference=True)
        self.assertEqual(b.read_json(self.bindings.initializer), model)
        self.assertEqual(4, len(rows))
        self.assertEqual(fixed.REFERENCE, provenance['tokenReference']['version'])
        self.assertEqual(b.file_digest(fixed.__file__), provenance['referenceCodecSha256'])

    def test_ordinary_eight_tail_loader_rejects_residual_protocol(self):
        with self.assertRaisesRegex(ValueError, 'explicit versioned'):
            fresh.fresh_collection(self.bindings)

    def test_fixed_reference_requires_eight_tail_loader(self):
        with self.assertRaisesRegex(ValueError, 'explicit eight-tail'):
            token.collection(self.bindings.collection, 137, fixed_reference=True)

    def test_wrong_metadata_reference_is_rejected(self):
        bank = Path(self.bindings.collection)
        manifest = b.read_json(bank/'run.json')
        manifest['tokenReference']['ordinaryModelSha256'] = '0'*64
        b.atomic_json(bank/'run.json', manifest)
        b.atomic_json(bank/'dataset.json', dict(manifest, complete=True, counts={'COLLECTED':4}))
        with self.assertRaisesRegex(ValueError, 'exact ordinary candidate'):
            token.collection(bank, 137, eight_tails=True, fixed_reference=True)

    def test_controlled_model_or_different_selector_is_rejected(self):
        bank = Path(self.bindings.collection)
        original_source = b.read_json(bank/'source.json')
        original_manifest = b.read_json(bank/'run.json')
        for change in ({'candidateModel': 'learned.json'}, {'candidateSelector': 'MODEL'}):
            with self.subTest(change=change):
                source, manifest = copy.deepcopy(original_source), copy.deepcopy(original_manifest)
                source['tokenOrder'].update(change)
                manifest['tokenOrder'] = source['tokenOrder']
                b.atomic_json(bank/'source.json', source)
                manifest['sourceSha256'] = b.file_digest(bank/'source.json')
                b.atomic_json(bank/'run.json', manifest)
                b.atomic_json(bank/'dataset.json', dict(manifest, complete=True, counts={'COLLECTED':4}))
                with self.assertRaisesRegex(ValueError, 'Fixed-reference collection'):
                    token.collection(bank, 137, eight_tails=True, fixed_reference=True)

    def test_prefix_diagnostics_keep_fixed_reference(self):
        model, rows, _ = fresh.fresh_collection(self.bindings, fixed_reference=True)
        diagnostic = fresh.prefix_rows(self.bindings.collection, model, rows, 2, fixed_reference=True)
        self.assertEqual(rows[0]['referenceIndex'], diagnostic[0]['referenceIndex'])
        self.assertEqual(rows[0]['targets'], diagnostic[0]['targets'])

    def test_skips_cannot_contain_a_reference(self):
        bank = Path(self.bindings.collection)
        path = bank/'origin-0.json'
        row = b.read_json(path)
        row.update(status='SKIPPED', observation=None, alternatives=[], proposedIndices=[],
                   skipReason='REQUESTED_ROOT_NOT_REACHED')
        b.atomic_json(path, row)
        finish(bank)
        with self.assertRaisesRegex(ValueError, 'Skipped origins'):
            token.collection(bank, 137, eight_tails=True, fixed_reference=True)

    def test_synthetic_fit_exports_tagged_heads_without_changing_basis_or_selection(self):
        report = fresh.fit(self.bindings, self.root/'fit', ridge.FitConfig(), fixed_reference=True)
        original = b.read_json(self.bindings.initializer)
        self.assertEqual(fresh.FIXED_REFERENCE_KIND, report['kind'])
        for index in range(5):
            artifact = fixed.unwrap(b.read_json(self.root/f'fit/model-{index}.json'))
            self.assertEqual(ridge.basis_digest(original), ridge.basis_digest(artifact))
            self.assertEqual(.5, artifact['outputBias'])
        self.assertEqual(ridge.select_candidate(report['fits'], report['initializerValidation']['pairedMse']),
                         report['selected'])
        self.assertFalse(b.read_json(self.root/'fit/selection.json')['prefixDiagnosticsUsed'])
        self.assertFalse(report['qualificationPassed'])


if __name__ == '__main__':
    unittest.main()
