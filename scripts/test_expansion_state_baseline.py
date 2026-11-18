"""UNIT-only ridge math and public-state isolation; no retained model or trajectory."""
import copy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np

import expansion_mc as mc
import expansion_state_baseline as baseline
import purchase_policy as policy


def initial(root):
    model=policy.initialize(dict(encoderVersion=mc.CONTRACT.encoder,stateFeatureNames=['cash','decision.expand'],
        actionFeatureNames=['cost']),17,contract=mc.CONTRACT)
    model['stateBias']=[.1]*64
    path=Path(root)/'UNIT-initial.json';policy.atomic_json(path,model)
    return path,model,baseline.FrozenBasis.load(baseline.subject(path))


def state(value):return baseline.PublicState.read(dict(encoderVersion=mc.CONTRACT.encoder,stateFeatures={'cash':value}))


class RidgeTests(unittest.TestCase):
    def test_sum_world_loss_adds_one_identity_not_world_count(self):
        x=np.zeros((2,64));x[:,0]=[-1,1]
        fitted=baseline.weighted_ridge(x,[0.,1.],[0,1],2)
        self.assertAlmostEqual(fitted['slopes'][0],1/3,places=15)
        self.assertEqual(fitted['intercept'],.5);self.assertEqual(fitted['rowWeightSum'],2)

    def test_duplicate_rows_within_one_world_do_not_increase_its_weight(self):
        x=np.zeros((2,64));x[:,0]=[-1,1]
        a=baseline.weighted_ridge(x,[0.,1.],[0,1],2)
        b=baseline.weighted_ridge(np.vstack([x[0]]*5+[x[1]]),[0.]*5+[1.],[0]*5+[1],2)
        np.testing.assert_allclose(a['slopes'],b['slopes'],atol=1e-15,rtol=0)
        self.assertEqual(a['intercept'],b['intercept'])

    def test_constant_target_has_unpenalized_intercept_despite_nonconstant_features(self):
        x=np.zeros((3,64));x[:,0]=[-.8,.1,.9]
        fitted=baseline.weighted_ridge(x,[.75]*3,[0,1,2],3)
        np.testing.assert_array_equal(fitted['slopes'],np.zeros(64))
        self.assertEqual(fitted['intercept'],.75)

    def test_world_mean_return_not_pooled_row_mean_sets_constant_intercept(self):
        fitted=baseline.weighted_ridge(np.zeros((4,64)),[0.,0.,0.,1.],[0,0,0,2],4)
        self.assertEqual(fitted['intercept'],.5);self.assertEqual(fitted['representedWorlds'],2)
        self.assertEqual(fitted['emptyWorlds'],2);self.assertEqual(fitted['rowWeightSum'],2)

    def test_empty_fit_is_rejected_without_invented_observations(self):
        with self.assertRaisesRegex(ValueError,'at least one'):baseline.weighted_ridge(np.zeros((0,64)),[],[],8)

    def test_one_origin_cannot_have_different_terminal_returns(self):
        with self.assertRaisesRegex(ValueError,'one unchanged terminal'):
            baseline.weighted_ridge(np.zeros((2,64)),[0.,1.],[0,0],1)

    def test_targets_are_not_silently_clipped(self):
        with self.assertRaisesRegex(ValueError,'terminal returns'):baseline.weighted_ridge(np.zeros((1,64)),[1.2],[0],1)


class BasisTests(unittest.TestCase):
    def setUp(self):
        policy.torch.set_num_threads(1);self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.path,self.model,self.basis=initial(self.root)

    def test_hidden_function_is_original_state_affine_tanh(self):
        got=self.basis.hidden([state(.7)])[0]
        expected=[policy.math.tanh(self.model['stateWeights'][2*i]*.7+.1) for i in range(64)]
        np.testing.assert_allclose(got,expected,atol=1e-15,rtol=0)

    def test_selected_action_and_action_features_cannot_enter_input(self):
        value=dict(encoderVersion=mc.CONTRACT.encoder,stateFeatures={'cash':.7},selectedIndex=0)
        with self.assertRaisesRegex(ValueError,'only original'):baseline.PublicState.read(value)
        value.pop('selectedIndex');value['actions']=[{'features':{'cost':999}}]
        with self.assertRaisesRegex(ValueError,'only original'):baseline.PublicState.read(value)

    def test_changed_current_actor_weights_do_not_change_original_basis(self):
        before=self.basis.hidden([state(.7)]);changing=copy.deepcopy(self.model)
        changing['stateWeights']=[10.]*len(changing['stateWeights'])
        np.testing.assert_array_equal(before,self.basis.hidden([state(.7)]))

    def test_changed_original_initializer_bytes_reject(self):
        changed=copy.deepcopy(self.model);changed['stateBias'][0]=.3
        policy.atomic_json(self.path,changed)
        with self.assertRaisesRegex(ValueError,'basis changed'):self.basis.hidden([state(.7)])

    def test_trained_actor_cannot_be_substituted_as_original_basis(self):
        changed=copy.deepcopy(self.model);changed['actorWeights'][0]=.3;policy.atomic_json(self.path,changed)
        with self.assertRaisesRegex(ValueError,'prospective'):baseline.FrozenBasis.load(baseline.subject(self.path))

    def test_unknown_state_column_rejects(self):
        value=baseline.PublicState.read(dict(encoderVersion=mc.CONTRACT.encoder,stateFeatures={'future.unknown':1.}))
        with self.assertRaisesRegex(ValueError,'dictionary'):self.basis.hidden([value])

    def test_prediction_clips_only_after_unrestricted_linear_value(self):
        model=baseline.fit(self.basis,[state(0),state(1)],[0.,1.],[0,1],2,{'UNIT':True})
        model['slopes']=[0.]*64;model['intercept']=2.
        self.assertEqual([1.],baseline.predict(model,self.basis,[state(.5)]))
        model['intercept']=-1.;self.assertEqual([0.],baseline.predict(model,self.basis,[state(.5)]))

    def test_mutating_fitted_recipe_does_not_mutate_fixed_recipe_owner(self):
        model=baseline.fit(self.basis,[state(0)],[.5],[0],1,{'UNIT':True})
        model['recipe']['predictionClip'][0]=-.5
        self.assertEqual([0.,1.],baseline.RECIPE['predictionClip'])
        with self.assertRaisesRegex(ValueError,'fixed state-only'):baseline.predict(model,self.basis,[state(0)])


if __name__=='__main__':unittest.main()
