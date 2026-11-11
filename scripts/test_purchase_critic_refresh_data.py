"""Synthetic-only frozen critic checks; no collection, fitting or game execution."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import purchase_actor_baseline as baseline
import purchase_critic_refresh_data as adapter
import purchase_policy as policy


class CriticDescriptorTest(unittest.TestCase):
    def test_old_descriptor_names_original_checkpoint_and_audit(self):
        value=adapter.inference_contract(adapter.CriticRole.OLD,'2.14.0+cpu')
        self.assertEqual(value['checkpointSha256'],adapter.CriticRole.OLD.checkpoint_sha256)
        self.assertEqual(value['checkpointAuditSha256'],adapter.OLD_AUDIT)
        self.assertEqual(value['role'],'old')

    def test_refreshed_descriptor_names_its_actual_checkpoint_and_audit(self):
        value=adapter.inference_contract(adapter.CriticRole.REFRESHED,'2.14.0+cpu')
        self.assertEqual(value['checkpointSha256'],adapter.CriticRole.REFRESHED.checkpoint_sha256)
        self.assertEqual(value['checkpointAuditSha256'],adapter.REFRESH_AUDIT)
        self.assertNotEqual(value['checkpointSha256'],adapter.CriticRole.OLD.checkpoint_sha256)
        self.assertEqual(value['baseInferenceContractSha256'],adapter.BASE_CONTRACT)

    def test_descriptor_binds_state_only_original_unit_math(self):
        value=adapter.inference_contract(adapter.CriticRole.REFRESHED,'2.14.0+cpu')
        expected=dict(inputs='actor-visible-stateFeatures-only',stateFeatures=3556,widths=[64],
                      missingValue=0.,normalization='original-units',dtype='float32',newCriticFits=0)
        self.assertEqual({k:value[k] for k in expected},expected)
        self.assertEqual(value['stateValueSourceSha256'],adapter.exp.sha(baseline.__file__))

    def test_descriptor_rejects_implicit_string_role(self):
        with self.assertRaisesRegex(ValueError,'explicit old/refreshed role'):
            adapter.inference_contract('old','2.14.0')

    def test_adapter_rejects_implicit_string_role(self):
        with self.assertRaisesRegex(ValueError,'explicit frozen critic role'):
            adapter.FrozenCriticAdapter('refreshed')

    def identity(self,role,checkpoint_hash=None,dictionary_hash=None,descriptor=None):
        actual_sha=adapter.exp.sha
        pins={'/checkpoint':checkpoint_hash or role.checkpoint_sha256,
              '/dictionary':dictionary_hash or adapter.FEATURES}
        value=descriptor or adapter.inference_contract(role,'2.14.0+cpu')
        with patch.object(adapter.exp,'sha',side_effect=lambda path:pins[str(path)]
                          if str(path) in pins else actual_sha(path)), \
                patch.object(adapter.exp,'read',return_value=value):
            return adapter.predictor_identity(role,'/checkpoint',Path(__file__),'/dictionary','2.14.0+cpu')

    def test_predictor_keeps_refreshed_checkpoint_and_descriptor_hash(self):
        value=self.identity(adapter.CriticRole.REFRESHED)
        self.assertEqual(value['checkpointSha256'],adapter.CriticRole.REFRESHED.checkpoint_sha256)
        self.assertEqual(value['inferenceContractSha256'],adapter.exp.sha(__file__))
        self.assertEqual(value['criticRole'],'refreshed')
        self.assertEqual(value['contractVersion'],'purchase-frozen-critic-contract-1')

    def test_checkpoint_from_other_role_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'critic role'):
            self.identity(adapter.CriticRole.OLD,checkpoint_hash=adapter.CriticRole.REFRESHED.checkpoint_sha256)

    def test_changed_dictionary_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'state dictionary'):
            self.identity(adapter.CriticRole.OLD,dictionary_hash='0'*64)

    def test_descriptor_from_other_role_is_rejected(self):
        other=adapter.inference_contract(adapter.CriticRole.OLD,'2.14.0+cpu')
        with self.assertRaisesRegex(ValueError,'inference descriptor'):
            self.identity(adapter.CriticRole.REFRESHED,descriptor=other)

    def test_changed_numerical_environment_is_rejected(self):
        other=adapter.inference_contract(adapter.CriticRole.REFRESHED,'different-torch')
        with self.assertRaisesRegex(ValueError,'inference descriptor'):
            self.identity(adapter.CriticRole.REFRESHED,descriptor=other)

    def test_both_roles_keep_the_same_strong_collection_reader(self):
        for role in adapter.CriticRole:
            with self.subTest(role=role):
                self.assertIs(adapter.FrozenCriticAdapter(role).read_collection,adapter.strong.read_collection)


class CriticAdapterNumericalTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.names=[f'state-{i:04d}' for i in range(3556)]
        self.model=policy.initialize(dict(encoderVersion=policy.ENCODER_VERSION,
            stateFeatureNames=self.names,actionFeatureNames=['left','right']),17)
        self.model_path=self.root/'actor.json';policy.atomic_json(self.model_path,self.model)
        self.features=self.root/'features.json';policy.atomic_json(self.features,self.names)
        self.critic=baseline.benchmark.Critic((64,),7)
        self.checkpoint=self.root/'synthetic.pt';torch.save(self.critic.state_dict(),self.checkpoint)
        self.provenance=dict(collectionSha256='synthetic-collection',gameHashes=['game-0'],
                             readerSha256=adapter.exp.sha(adapter.strong.__file__))
        self.rows=[dict(originIndex=0,number=i,selectedIndex=0,behaviorValue=.9,
            behaviorLogProbability=-.1,**{'return':float(i%2)},observation=dict(
                stateFeatures={self.names[0]:float(i)/3.,self.names[5]:-1.},baselineIndex=0,
                actions=[dict(command='left',features={'left':1.}),
                         dict(command='right',features={'right':1.})])) for i in range(3)]
        # Admission is tested separately; these synthetic weights never claim audited model pins.
        self.identity=dict(syntheticOnly=True,checkpointSha256=adapter.exp.sha(self.checkpoint),
                           inferenceContractSha256='synthetic-contract',criticRole='refreshed')

    def predict(self,rows,name):
        with patch.object(adapter,'predictor_identity',return_value=self.identity), \
                patch.object(adapter.strong,'read_collection',return_value=(rows,self.provenance)):
            return adapter.FrozenCriticAdapter(adapter.CriticRole.REFRESHED).predict(
                self.root/'no-collection',self.model_path,self.checkpoint,self.root/'no-contract',
                self.features,self.root/name,expected_model_sha=adapter.exp.sha(self.model_path))

    def test_adapter_equals_original_state_value_owner_exactly(self):
        expected=baseline.state_values(self.critic,self.rows,self.names)
        actual=self.predict(self.rows,'predictions.json')
        self.assertEqual([row['value'] for row in actual['predictions']],expected)

    def test_actions_recommendations_and_outcomes_cannot_change_values(self):
        changed=[dict(row,selectedIndex=1,behaviorValue=-100.,behaviorLogProbability=-999.,
            **{'return':999.},observation=dict(row['observation'],baselineIndex=1,
                actions=[dict(command='unrelated',features={'unknown-action':10000.})])) for row in self.rows]
        before=self.predict(self.rows,'before.json');after=self.predict(changed,'after.json')
        self.assertEqual(before['predictions'],after['predictions'])

    def test_prediction_preserves_behavior_rows_and_actor_bytes(self):
        original=copy.deepcopy(self.rows);actor=self.model_path.read_bytes()
        self.predict(self.rows,'predictions.json')
        self.assertEqual(self.rows,original);self.assertEqual(self.model_path.read_bytes(),actor)

    def test_reader_and_adapter_provenance_stay_separate_from_predictor(self):
        result=self.predict(self.rows,'predictions.json')
        self.assertEqual(result['predictor'],self.identity)
        self.assertEqual(result['collectionReader'],dict(version='purchase-strong-collection-reader-1',
            sha256=adapter.exp.sha(adapter.strong.__file__),rivalActorSha256=adapter.strong.G2,
            ecology=adapter.strong.ECOLOGY.value))
        self.assertEqual(result['predictionAdapter']['role'],'refreshed')
        self.assertEqual(result['predictionAdapter']['sha256'],adapter.exp.sha(adapter.__file__))
        self.assertEqual(result['newFits'],0)

    def test_missing_state_features_equal_explicit_zero(self):
        missing=[dict(row,observation=dict(row['observation'],stateFeatures={})) for row in self.rows]
        zeros=[dict(row,observation=dict(row['observation'],stateFeatures={name:0. for name in self.names}))
               for row in self.rows]
        self.assertEqual(baseline.state_values(self.critic,missing,self.names),
                         baseline.state_values(self.critic,zeros,self.names))


if __name__=='__main__':unittest.main()
