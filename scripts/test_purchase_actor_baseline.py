"""Synthetic-only checks for the explicit matched actor-only baseline objective."""
import copy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest

import torch

import purchase_actor_baseline as baseline
import purchase_policy as policy
from purchase_policy_toy import SCHEMA
from test_purchase_policy import collection


class ActorOnlyBaselineTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model = policy.initialize(SCHEMA,17)
        self.model['valueWeights'][0] = .25
        self.model['valueBias'] = -.1
        self.path = self.root/'initial.json'
        policy.atomic_json(self.path,self.model)
        self.source = self.root/'collection'
        collection(self.source,self.path)
        self.rows,self.provenance = policy.read_collection(self.source,self.path,self.model)
        self.config = policy.UpdateConfig(epochs=1,valueCoefficient=0,maxSeconds=60)

    def external(self, values=None):
        document = dict(kind='purchase-frozen-state-baselines-1',
            collectionSha256=self.provenance['collectionSha256'],
            gameHashes=self.provenance['gameHashes'],behaviorModelSha256=policy.file_digest(self.path),
            predictor={'checkpointSha256':'fixture'},newFits=0,
            predictions=[dict(gameSha256=self.provenance['gameHashes'][row['originIndex']],
                decision=row['number'],value=row['behaviorValue'] if values is None else values[i])
                for i,row in enumerate(self.rows)])
        path = self.root/'baselines.json'
        policy.atomic_json(path,document)
        return path,document

    def test_recorded_baseline_preserves_original_behavior_fields(self):
        objective = policy.ActorOnlyObjective('recorded')
        rows,_ = objective.bind(self.rows,self.provenance,policy.file_digest(self.path))
        for actual,original in zip(rows,self.rows):
            self.assertIsNot(actual,original)
            self.assertEqual({k:v for k,v in actual.items() if k!='_actorOnlyBaseline'},original)
            self.assertNotIn('_actorOnlyBaseline',original)

    def test_external_values_do_not_replace_behavior_value(self):
        path,_ = self.external([.1,.2,.3,.4])
        rows,identity = policy.ActorOnlyObjective('external',str(path)).bind(
            self.rows,self.provenance,policy.file_digest(self.path))
        self.assertEqual([row['_actorOnlyBaseline'] for row in rows],[.1,.2,.3,.4])
        self.assertEqual([row['behaviorValue'] for row in rows],
                         [row['behaviorValue'] for row in self.rows])
        self.assertEqual(identity['externalSha256'],policy.file_digest(path))

    def test_external_game_and_decision_coverage_is_exact(self):
        path,document = self.external()
        document['predictions'][1] = document['predictions'][0]
        policy.atomic_json(path,document)
        with self.assertRaisesRegex(ValueError,'exactly once'):
            policy.ActorOnlyObjective('external',str(path)).bind(
                self.rows,self.provenance,policy.file_digest(self.path))

    def test_external_collection_identity_cannot_change(self):
        path,document = self.external()
        document['collectionSha256'] = 'wrong'
        policy.atomic_json(path,document)
        with self.assertRaisesRegex(ValueError,'complete collection'):
            policy.ActorOnlyObjective('external',str(path)).bind(
                self.rows,self.provenance,policy.file_digest(self.path))

    def test_external_baseline_must_be_finite(self):
        path,_ = self.external([.1,.2,2.,.4])
        with self.assertRaisesRegex(ValueError,'finite number'):
            policy.ActorOnlyObjective('external',str(path)).bind(
                self.rows,self.provenance,policy.file_digest(self.path))

    def test_recorded_arm_preserves_original_zero_critic_update_model(self):
        policy.update(self.path,self.source,self.config,self.root/'legacy')
        policy.update(self.path,self.source,self.config,self.root/'recorded',
                      actor_only=policy.ActorOnlyObjective('recorded'))
        self.assertEqual(policy.read_json(self.root/'legacy/policy.json'),
                         policy.read_json(self.root/'recorded/policy.json'))

    def test_external_equal_to_recorded_produces_identical_update(self):
        path,_ = self.external()
        for name,objective in [('recorded',policy.ActorOnlyObjective('recorded')),
                               ('external',policy.ActorOnlyObjective('external',str(path)))]:
            policy.update(self.path,self.source,self.config,self.root/name,actor_only=objective)
        self.assertEqual(policy.read_json(self.root/'recorded/policy.json'),
                         policy.read_json(self.root/'external/policy.json'))

    def test_value_head_parameters_and_optimizer_slots_are_excluded(self):
        policy.update(self.path,self.source,self.config,self.root/'fit',
                      actor_only=policy.ActorOnlyObjective('recorded'))
        checkpoint = policy.read_checkpoint(self.root/'fit/checkpoint.json')
        for name in ('valueWeights','valueBias'):
            self.assertEqual(checkpoint['model'][name],self.model[name])
        self.assertEqual(len(checkpoint['optimizer']['param_groups'][0]['params']),5)
        self.assertLessEqual(len(checkpoint['optimizer']['state']),5)

    def test_carried_optimizer_is_rejected_before_data_or_output(self):
        with self.assertRaisesRegex(ValueError,'fresh purchase Adam'):
            policy.update(self.path,self.source,self.config,self.root/'fit',
                optimizer_from=self.root/'absent.json',actor_only=policy.ActorOnlyObjective('recorded'))
        self.assertFalse((self.root/'fit').exists())

    def test_critic_coefficient_cannot_be_enabled_in_actor_only_update(self):
        with self.assertRaisesRegex(ValueError,'fresh purchase Adam'):
            policy.update(self.path,self.source,replace(self.config,valueCoefficient=.5),self.root/'fit',
                          actor_only=policy.ActorOnlyObjective('recorded'))

    def test_default_loss_ignores_external_private_row_field(self):
        original = policy.loss_for_rows(policy.ResidualPolicy(self.model,policy.PURCHASE_CONTRACT),self.rows,self.config)
        rows = [{**row,'_actorOnlyBaseline':1.} for row in self.rows]
        actual = policy.loss_for_rows(policy.ResidualPolicy(self.model,policy.PURCHASE_CONTRACT),rows,self.config)
        torch.testing.assert_close(actual,original,rtol=0,atol=0)

    def test_predictor_only_reads_actor_visible_state_features(self):
        names = self.model['stateFeatureNames']
        critic = baseline.benchmark.Critic((64,),17,inputs=len(names))
        expected = baseline.state_values(critic,self.rows,names)
        changed = copy.deepcopy(self.rows)
        for row in changed:
            row['return'] = 1-row['return']
            row['behaviorValue'] = 0
            row['selectedIndex'] = 99
            row['observation']['actions'] = []
            row['observation']['baselineIndex'] = 99
        self.assertEqual(baseline.state_values(critic,changed,names),expected)


if __name__ == '__main__':
    unittest.main()
