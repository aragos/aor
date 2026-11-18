"""Small unit claims, separate from the retained real joined numerical CLI proof."""
import json
import unittest

import coordinated_es_joined_fixture as fixture
import coordinated_es_math as es
import coordinated_es_projection as projection
import coordinated_es_plan as plan_owner
import expansion_training_evidence as evidence


class ConstructedFixtureTests(unittest.TestCase):
    def test_coordinator_evidence_rejects_a_different_predecessor(self):
        with self.assertRaisesRegex(ValueError, 'exact produced previousUpdate'):
            fixture.context_evidence('/unopened-constructed-context',
                {'previousUpdate': {'sha256': 'a' * 64}},
                {'previousUpdate': {'sha256': 'b' * 64}})

    def test_toy_original_expectations_are_confined_and_restored(self):
        original = fixture.models()
        value = fixture.protocol_fixture()
        for head, row in original.items():
            value['original']['heads'][head.value]['actorSha256'] = row.sha256
        before = dict(plan_owner.ORIGINAL_ACTORS)
        accepted = fixture.constructed_plan(value, original)
        self.assertEqual(accepted.record, value)
        self.assertEqual(plan_owner.ORIGINAL_ACTORS, before)
        with self.assertRaisesRegex(ValueError, 'prescribed original'):
            plan_owner.Plan.from_record(value)

    def test_handwritten_direction_changes_only_actor_readout(self):
        originals = fixture.models()
        factors = fixture.factor_map(originals, 'a' * 64)
        vector = fixture.directions(factors.scope, 1).vectors[0]
        actual = projection.materialize(factors, originals, vector)
        for head, raw in actual.items():
            model = json.loads(raw)
            original = originals[head].model
            self.assertEqual({key: value for key, value in model.items() if key != 'actorWeights'},
                {key: value for key, value in original.items() if key != 'actorWeights'})
            self.assertEqual(model['actorWeights'], [value + 1. for value in original['actorWeights']])

    def test_prescribed_mean_is_the_predeclared_alternating_unit_vector(self):
        originals = fixture.models()
        factors = fixture.factor_map(originals, 'a' * 64)
        directions = fixture.directions(factors.scope, 1)
        self.assertEqual(es.prescribed_mean_probe(directions)['center']['coordinates'], list(directions.vectors[0]))

    def test_invented_score_order_gives_known_plus_and_minus_rewards(self):
        for seat in range(4):
            for plus in (True, False):
                terminal = fixture.terminal(seat, plus)
                reward = evidence.terminal_reward(dict(controlledSeat=seat, terminal=dict(
                    scores=terminal['scores'], winShare=terminal['wins'][seat],
                    rankReward=terminal['ranks'][seat], reward=terminal['rewards'][seat], gameDecisions=9)))
                self.assertEqual(reward, 1. if plus else 0.)


if __name__ == '__main__':
    unittest.main()
