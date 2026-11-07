import copy
from dataclasses import replace
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import card_advantage as a
import card_advantage_data as data
import card_advantage_train as fit
import purchase_policy as common
from test_card_advantage import SCHEMA, observation, group
from test_card_advantage_data import fixture, finish, read_origin, write_origin


def full_observation():
    result = observation()
    result['baselineIndex'] = 1
    result['actions'] += [
        {'command': 'play hand-2', 'features': {'card.b': 1.}},
        {'command': 'play hand-3', 'features': {'card.b': 1.}},
    ]
    return result


def sampled_group(selected=0, target=.25, identifier='0:root'):
    observed = [i in (1, selected) for i in range(4)]
    targets = [0. if i == 1 else target if i == selected else math.nan for i in range(4)]
    return a.prepare(full_observation(), 0, SCHEMA, targets, identifier, observed=observed)


def root_pair_fixture(directory, sampled=('train',), replicates=1):
    fixture(directory)
    manifest = common.read_json(directory/'collection.json')
    manifest.update(schemaVersion=2,
        collection={'kind': 'root-pairs-1', 'sampledSplits': list(sampled), 'replicates': replicates},
        maxTailsPerOrigin=4*replicates)
    common.atomic_json(directory/'collection.json', manifest)
    for request in manifest['origins']:
        origin = read_origin(directory, request['index'])
        origin['schemaVersion'] = 2
        obs = full_observation()
        origin['observations'] = {'a'*64: obs}
        g = origin['groups'][0]
        observed, acquisition = a.acquisition(obs, g['botSeed'], request['split'] in sampled)
        g.update(proposalIndices=a.proposals(obs, g['botSeed']), referenceIndex=obs['baselineIndex'],
                 observedActionIndices=observed, acquisition=acquisition,
                 returns=[{'actionIndex': action,
                           'outcomeIds': [f'{action}-{rep}' for rep in range(replicates)]}
                          for action in observed])
        origin['outcomes'] = {
            f'{action}-{rep}': {'reward': action/3., 'winShare': action/3., 'rankReward': action/3.,
                'chanceSeed': 500+rep, 'scores': [], 'gameDecisions': 100, 'canonicalSha256': 'unused'}
            for action in observed for rep in range(replicates)
        }
        write_origin(directory, request['index'], origin)
    finish(directory)


class FixedNetwork(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.values = torch.nn.Parameter(torch.tensor([.2, .5, .8, .4], dtype=torch.float64))

    def forward(self, groups):
        return [self.values for _ in groups]


class MaskChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)

    def test_inference_targets_are_unobserved_not_zero_labels(self):
        prepared = a.prepare(full_observation(), 0, SCHEMA)
        self.assertEqual(prepared.observed, (False, True, False, False))
        self.assertEqual(prepared.observed_pairs(), [])
        self.assertTrue(math.isnan(float(prepared.targets[0])))
        network = a.Network(SCHEMA, a.initialize(SCHEMA, 1))
        with self.assertRaisesRegex(ValueError, 'observed non-reference'):
            a.loss(network, [[prepared]])

    def test_unobserved_numeric_zero_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'must be NaN'):
            a.prepare(full_observation(), 0, SCHEMA, [0., 0., 0., 0.],
                      observed=[True, True, False, False])

    def test_observed_nan_is_rejected(self):
        with self.assertRaises(ValueError):
            a.prepare(full_observation(), 0, SCHEMA, [math.nan, 0., .2, .1])

    def test_reference_cannot_be_marked_missing(self):
        with self.assertRaisesRegex(ValueError, 'known F reference'):
            a.prepare(full_observation(), 0, SCHEMA, [1., math.nan, math.nan, math.nan],
                      observed=[True, False, False, False])

    def test_only_observed_nonreference_pair_receives_gradient(self):
        network = FixedNetwork()
        value = a.loss(network, [[sampled_group()]])
        value.backward()
        self.assertAlmostEqual(float(value.detach()), (-.3-.25)**2)
        self.assertEqual(network.values.grad.tolist(), [-1.1, 1.1, 0., 0.])

    def test_uniform_sampled_losses_average_to_dense_loss_without_ipw(self):
        network = FixedNetwork()
        targets = [.25, 0., -.3, .1]
        dense = a.prepare(full_observation(), 0, SCHEMA, targets)
        sampled = [a.loss(network, [[sampled_group(i, targets[i])]]) for i in (0, 2, 3)]
        self.assertAlmostEqual(float(torch.stack(sampled).mean().detach()),
                               float(a.loss(network, [[dense]]).detach()))

    def test_mask_does_not_remove_candidates_from_selection(self):
        g = sampled_group()
        self.assertEqual(g.indices, (0, 1, 2, 3))
        self.assertEqual(a.choose_values(g, [[.5, .5, .8, .5]]*3)['index'], 2)

    def test_ablation_preserves_missing_targets_and_full_proposals(self):
        original = sampled_group()
        masked = fit.ablated([[original]], SCHEMA)[0][0]
        self.assertEqual(masked.observed, original.observed)
        self.assertEqual(masked.indices, original.indices)
        self.assertTrue(torch.equal(torch.isnan(masked.targets), torch.isnan(original.targets)))
        self.assertEqual(masked.targets[masked.observed_pairs()].tolist(),
                         original.targets[original.observed_pairs()].tolist())

    def test_dense_prediction_metrics_reject_sampled_targets(self):
        network = a.Network(SCHEMA, a.initialize(SCHEMA, 1))
        with self.assertRaisesRegex(ValueError, 'Dense evaluation'):
            fit.prediction_metrics(network, [[sampled_group()]])

    def test_dense_selected_return_metrics_reject_sampled_targets(self):
        ensemble = a.Ensemble(a.bundle(SCHEMA, [a.initialize(SCHEMA, seed) for seed in a.SEEDS]))
        with self.assertRaisesRegex(ValueError, 'Dense evaluation'):
            fit.local_metrics(ensemble, [[sampled_group()]])

    def test_all_trivial_epoch_rejects_before_optimizer_step(self):
        network = a.Network(SCHEMA, a.initialize(SCHEMA, 1))
        optimizer = torch.optim.Adam(network.parameters(), lr=.003)
        trivial = a.prepare(observation(), 0, SCHEMA)
        with patch.object(optimizer, 'step', side_effect=AssertionError('must not step')):
            with self.assertRaisesRegex(ValueError, 'observed non-reference'):
                fit.epoch(network, optimizer, [[trivial]], 1, 1, a.CONFIG, math.inf)
        self.assertEqual(len(optimizer.state), 0)

    def test_filtering_trivial_origins_preserves_exact_active_update(self):
        config = replace(a.CONFIG, originsPerBatch=1)
        member = a.initialize(SCHEMA, 1)
        first = a.Network(SCHEMA, member); second = a.Network(SCHEMA, member)
        first_optimizer = torch.optim.Adam(first.parameters(), lr=.003, foreach=False)
        second_optimizer = torch.optim.Adam(second.parameters(), lr=.003, foreach=False)
        active = [[group(.7, 1, '0:a')], [group(-.2, -1, '1:a')]]
        empty = [a.prepare(observation(), 0, SCHEMA)]
        expected = fit.epoch(first, first_optimizer, active, 17, 1, config, math.inf)
        actual = fit.epoch(second, second_optimizer, [empty, active[0], empty, active[1]],
                           17, 1, config, math.inf)
        self.assertEqual(actual, expected)
        self.assertEqual(second.artifact(), first.artifact())
        self.assertEqual(common.encode_optimizer(second_optimizer), common.encode_optimizer(first_optimizer))

    def test_sparse_checkpoint_resume_preserves_exact_model_and_adam(self):
        config = replace(a.CONFIG, epochs=2, checkpoints=(1, 2), originsPerBatch=1)
        training = [[sampled_group(0, .25, '0:a')], [sampled_group(2, -.3, '1:b')]]
        development = [[a.prepare(full_observation(), 0, SCHEMA, [.25, 0., -.3, .1])]]
        identity = {'kind': 'sparse-synthetic-resume'}
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp)/'first'; second = Path(tmp)/'second'; original = fit.epoch
            expected, _ = fit.fit_member(SCHEMA, training, development, 7, config,
                                         first, identity, math.inf)
            def interrupted(*args):
                original(*args)
                raise RuntimeError('interrupt after mutable work')
            with patch.object(fit, 'epoch', side_effect=interrupted):
                with self.assertRaises(RuntimeError):
                    fit.fit_member(SCHEMA, training, development, 7, config, second, identity, math.inf)
            actual, _ = fit.fit_member(SCHEMA, training, development, 7, config,
                                       second, identity, math.inf)
            self.assertEqual(actual, expected)
            self.assertEqual(common.read_checkpoint(first/'checkpoint.json'),
                             common.read_checkpoint(second/'checkpoint.json'))


class AcquisitionChecks(unittest.TestCase):
    def test_shared_unsigned_sampler_goldens(self):
        seeds = [0, 1, -1, 42, 71, 2**31-1, -2**31, 123456789]
        expected = [4, 7, 0, 4, 7, 0, 4, 0]
        obs = {'baselineIndex': 2}
        with patch.object(a, 'proposals', return_value=[0, 2, 4, 7]):
            actual = [a.acquisition(obs, seed, True)[1]['sampledActionIndex'] for seed in seeds]
            self.assertEqual(actual, expected)
            self.assertEqual(a.acquisition(obs, 0, True)[1]['probability'], 1/3)

    def test_exhaustive_acquisition_never_samples_a_target(self):
        indices, provenance = a.acquisition(full_observation(), -1, False)
        self.assertEqual(indices, [0, 1, 2, 3])
        self.assertEqual(provenance['kind'], 'exhaustive')
        self.assertIsNone(provenance['sampledActionIndex'])
        self.assertEqual(provenance['probability'], 1.)

    def test_single_reference_keeps_sampled_kind_without_draw(self):
        obs = observation(); obs['actions'] = obs['actions'][:1]
        with patch.object(a.Stream, 'bounded', side_effect=AssertionError('no draw')):
            indices, provenance = a.acquisition(obs, 0, True)
        self.assertEqual(indices, [0])
        self.assertEqual(provenance['kind'], 'uniform-non-f')
        self.assertIsNone(provenance['sampledActionIndex'])
        self.assertEqual(provenance['probability'], 1.)


class ReaderChecks(unittest.TestCase):
    def test_sparse_training_and_dense_holdouts_preserve_full_proposals(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path)
            dataset, provenance = data.read_collection(path)
            g = dataset['train'][0][0]
            self.assertEqual(g.indices, (0, 1, 2, 3))
            self.assertEqual(sum(g.observed), 2)
            self.assertEqual(len(g.observed_pairs()), 1)
            self.assertEqual(torch.isnan(g.targets).tolist(), [not value for value in g.observed])
            for split in ('development', 'validation'):
                dataset[split][0][0].require_dense_targets()
                self.assertEqual(dataset[split][0][0].replicates, 1)
            self.assertEqual(provenance['counts'], {'train': 1, 'development': 1, 'validation': 1})

    def test_dense_two_stream_schema_two_is_supported(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path, sampled=(), replicates=2)
            dataset, _ = data.read_collection(path)
            g = dataset['train'][0][0]
            g.require_dense_targets()
            self.assertEqual(g.replicates, 2)
            self.assertEqual(g.targets.tolist(), [-1/3, 0., 1/3, 1.-1/3])

    def test_invalid_acquisition_metadata_rejected(self):
        mutations = [('version', 'unknown'), ('kind', 'exhaustive'), ('seed', 17),
                     ('sampledActionIndex', None), ('probability', .5)]
        for key, value in mutations:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp); root_pair_fixture(path)
                origin = read_origin(path, 0); origin['groups'][0]['acquisition'][key] = value
                write_origin(path, 0, origin); finish(path)
                with self.assertRaisesRegex(ValueError, 'Acquisition'):
                    data.read_collection(path)

    def test_unobserved_return_cannot_be_silently_used(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path)
            origin = read_origin(path, 0); g = origin['groups'][0]
            missing = next(i for i in g['proposalIndices'] if i not in g['observedActionIndices'])
            g['returns'].append({'actionIndex': missing, 'outcomeIds': g['returns'][0]['outcomeIds']})
            write_origin(path, 0, origin); finish(path)
            with self.assertRaisesRegex(ValueError, 'only observed actions'):
                data.read_collection(path)

    def test_wrong_observed_set_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path)
            origin = read_origin(path, 0); origin['groups'][0]['observedActionIndices'] = [0, 1, 2, 3]
            write_origin(path, 0, origin); finish(path)
            with self.assertRaisesRegex(ValueError, 'Observed action indices'):
                data.read_collection(path)

    def test_schema_two_rejects_children(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path)
            origin = read_origin(path, 0); origin['groups'][0]['kind'] = 'child'
            write_origin(path, 0, origin); finish(path)
            with self.assertRaisesRegex(ValueError, 'never child groups'):
                data.read_collection(path)

    def test_trivial_root_stays_in_coverage_and_zero_policy_returns(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path)
            origin = read_origin(path, 0)
            obs = observation(); obs['actions'] = obs['actions'][:1]
            origin['observations'] = {'a'*64: obs}
            indices, acquisition = a.acquisition(obs, 0, True)
            origin['groups'][0].update(proposalIndices=indices, referenceIndex=0,
                observedActionIndices=indices, acquisition=acquisition,
                returns=[{'actionIndex': 0, 'outcomeIds': ['0-0']}])
            origin['outcomes'] = {'0-0': {'reward': 0., 'winShare': 0., 'rankReward': 0.,
                'chanceSeed': 500, 'scores': [], 'gameDecisions': 100, 'canonicalSha256': 'unused'}}
            write_origin(path, 0, origin); finish(path)
            dataset, provenance = data.read_collection(path)
            self.assertEqual(provenance['counts']['train'], 1)
            self.assertEqual(dataset['train'][0][0].targets.tolist(), [0.])
            ensemble = a.Ensemble(a.bundle(SCHEMA, [a.initialize(SCHEMA, seed) for seed in a.SEEDS]))
            report = fit.local_metrics(ensemble, dataset['train'])
            self.assertEqual(report['origins'], 1)
            self.assertEqual(report['meanSelectedAdvantage'], 0.)
            self.assertEqual(report['nontrivialOrigins'], 0)
            self.assertEqual(report['nontrivialDecisions'], 0)

    def test_sampled_multiple_streams_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); root_pair_fixture(path, replicates=2)
            with self.assertRaisesRegex(ValueError, 'exactly one continuation'):
                data.read_collection(path)

    def test_legacy_exhaustive_labels_and_merge_identity_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); fixture(path)
            dataset, _ = data.read_collection(path)
            g = dataset['train'][0][0]
            expected = a.prepare(observation(), 0, SCHEMA, [0., 1.])
            self.assertTrue(torch.equal(g.targets, expected.targets))
            self.assertEqual(g.observed, (True, True))
            self.assertEqual(g.replicates, 4)
            expected_key = common.digest({'observation': observation(), 'botSeed': 0, 'F': 0, 'S': [0, 1]})
            self.assertEqual(g.identifier, '0:'+expected_key)

    def test_schema_one_cannot_sneak_in_partial_acquisition(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); fixture(path)
            origin = read_origin(path, 0); origin['groups'][0]['observedActionIndices'] = [0]
            write_origin(path, 0, origin); finish(path)
            with self.assertRaisesRegex(ValueError, 'Legacy decision groups'):
                data.read_collection(path)


if __name__ == '__main__':
    unittest.main()
