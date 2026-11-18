"""Constructed warm-history inputs, with the original simulator policy and data untouched."""
import hashlib
import unittest

import purchase_policy as b
import token_anchor_distribution as distribution
import token_anchored_data as data
import token_anchored_policy as policy
from test_token_paired_fit import model, view


class WarmBidHistoryTest(unittest.TestCase):
    def setUp(self):
        self.anchor = model(True)
        dictionary = dict(encoderVersion=policy.HISTORY_CONTRACT.encoder,
            stateFeatureNames=sorted(self.anchor['stateFeatureNames'] + list(policy.HISTORY_STATE)),
            actionFeatureNames=sorted(self.anchor['actionFeatureNames'] + list(policy.HISTORY_ACTION)))
        self.extended = policy.initialize(self.anchor, dictionary=dictionary)
        self.observation = view()
        self.observation['encoderVersion'] = dictionary['encoderVersion']
        self.observation['stateFeatures']['bidHistory.sampleShare'] = .25
        self.observation['actions'][0]['features']['bidOrder.bestBox'] = 1/6
        self.p94 = [0., 1., 0.]

    def test_initial_distribution_is_unchanged_and_new_columns_are_zero(self):
        raw = b.canonical(self.extended)
        subject = data.FrozenActor(raw, hashlib.sha256(raw).hexdigest())

        policy.require_zero_history_weights(subject.model)
        self.assertEqual(policy.reference_forward(subject.model, self.observation, self.p94)['probabilities'],
            distribution.prior(self.p94))

    def test_masked_update_keeps_history_columns_zero_even_with_action_gradients(self):
        actor = policy.TokenAnchoredPolicy(self.extended, history_enabled=False)
        optimizer = b.torch.optim.Adam(actor.parameters(), lr=.001)

        for _ in range(2):
            optimizer.zero_grad()
            log_probability, _ = actor.forward([actor.prepare(self.observation, self.p94)])
            (-log_probability[0][0]).backward()
            optimizer.step()
        result = actor.artifact()

        self.assertNotEqual(self.extended['actorWeights'], result['actorWeights'])
        policy.require_zero_history_weights(result)
        off = policy.TokenAnchoredPolicy(result, history_enabled=False)
        on = policy.TokenAnchoredPolicy(result)
        self.assertTrue(b.torch.equal(off.forward([off.prepare(self.observation, self.p94)])[0][0],
            on.forward([on.prepare(self.observation, self.p94)])[0][0]))


if __name__ == '__main__':
    unittest.main()
