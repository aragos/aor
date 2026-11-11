"""Synthetic query, label and artifact ownership; no retained games or fits."""
import unittest

import token_order_policy as token
import token_order_fixed_reference as fixed
import token_order_proposals as proposals
import token_order_reference_neighbors as neighbor
from test_token_order_policy import observation, outcome, schema


def view(amounts=tuple(range(-4, 37)), baseline=16, cash=100, stock=36):
    result = observation(amounts=amounts, baseline=baseline)
    result['stateFeatures'].update({'role.0.cash': cash/200, 'role.0.stock': stock/36})
    return result


def amounts(observation, indices):
    return [int(observation['actions'][i]['command'].split()[1]) for i in indices]


class ReferenceNeighborTest(unittest.TestCase):
    def test_reference_neighbors_replace_ordinary_only_upper_query(self):
        observation = view()
        self.assertEqual([16, 15, 14, 0, -4, 8, 24, 36],
                         amounts(observation, proposals.reference_neighbor_indices(observation)))

    def test_unchanged_reference_retains_exact_original_order(self):
        observation = view(baseline=18)
        self.assertEqual(proposals.ordinary_indices(observation),
                         proposals.reference_neighbor_indices(observation))

    def test_unavailable_fifteen_retains_original_queries(self):
        observation = view(amounts=tuple(i for i in range(-4, 37) if i != 15))
        self.assertEqual(proposals.ordinary_indices(observation),
                         proposals.reference_neighbor_indices(observation))

    def test_negative_reference_retains_original_queries(self):
        observation = view(baseline=-4)
        self.assertEqual(proposals.ordinary_indices(observation),
                         proposals.reference_neighbor_indices(observation))

    def test_original_maximum_seventeen_is_still_a_diverse_query(self):
        observation = view(amounts=tuple(range(-4, 18)), cash=17, stock=36)
        self.assertEqual([16, 15, 14, 0, -4, 8, 17],
                         amounts(observation, proposals.reference_neighbor_indices(observation)))

    def test_new_loader_uses_fixed_reference_paired_difference(self):
        model = token.q.initialize(schema(), 137, 'token')
        observation = view()
        indices = proposals.reference_neighbor_indices(observation)
        values = {14: .7, 15: .6, 16: .4}
        root = dict(index=0, split='TRAINING', observation=observation,
            proposedIndices=indices, baselineCommand='buy-tokens 16',
            referenceIndex=fixed.expected_reference_index(observation),
            alternatives=[dict(index=i, command=observation['actions'][i]['command'],
                outcomes=[outcome(values.get(int(observation['actions'][i]['command'].split()[1]), .3), seed)
                          for seed in (13, 14)]) for i in indices])
        result = token.validate_root(root, model, 2, fixed_reference=True,
            proposal_profile=proposals.ProposalProfile.REFERENCE_NEIGHBORS)
        fourteen = next(i for i in indices if observation['actions'][i]['command']=='buy-tokens 14')
        self.assertAlmostEqual(.1, result['targets'][fourteen])
        self.assertEqual(0., result['targets'][result['referenceIndex']])

    def test_legacy_loader_rejects_new_query_order(self):
        model = token.q.initialize(schema(), 137, 'token')
        observation = view()
        root = dict(observation=observation, proposedIndices=proposals.reference_neighbor_indices(observation))
        with self.assertRaisesRegex(ValueError, 'fixed proposal selector'):
            token.validate_root(root, model, 2, fixed_reference=True)

    def test_new_envelope_rejects_old_residual_artifact(self):
        model = token.q.initialize(schema(), 137, 'token')
        with self.assertRaisesRegex(ValueError, 'exact proposal'):
            neighbor.unwrap(fixed.wrap(model))

    def test_old_envelope_rejects_new_residual_artifact(self):
        model = token.q.initialize(schema(), 137, 'token')
        with self.assertRaisesRegex(ValueError, 'tagged artifact'):
            fixed.unwrap(neighbor.wrap(model))

    def test_new_envelope_rejects_changed_selector(self):
        model = token.q.initialize(schema(), 137, 'token')
        artifact = neighbor.wrap(model)
        artifact['selectorVersion'] = 'token-order-selector-1'
        with self.assertRaisesRegex(ValueError, 'exact proposal'):
            neighbor.unwrap(artifact)


if __name__ == '__main__':
    unittest.main()
