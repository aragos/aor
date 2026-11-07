"""Explicit territory objectives preserve old typed boundaries and numerical defaults."""
from dataclasses import replace
import math
import time
import unittest

import torch
import purchase_policy as b
import token_policy as token
import expansion_policy as expansion


def fixture(module, count=2):
    schema=dict(encoderVersion=module.ENCODER_VERSION,stateFeatureNames=['context'],actionFeatureNames=['choice'])
    model=module.initialize(schema,103)
    observation=dict(encoderVersion=module.ENCODER_VERSION,stateFeatures={'context':1.},baselineIndex=0,
        actions=[dict(command=f'action-{i:04}',features={'choice':float(i+1)}) for i in range(count)])
    actor=b.ResidualPolicy(model,module.CONTRACT)
    with torch.no_grad():logp,value=actor([observation])
    row=dict(observation=observation,selectedIndex=0,behaviorLogProbability=float(logp[0][0]),
             behaviorValue=float(value[0]),**{'return':1.})
    return actor,row


class TerritoryReferenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):torch.set_num_threads(1)

    def test_offered_loss_and_gradient_match_explicit_uniform_formula(self):
        for module in (token,expansion):
            actor,row=fixture(module,7)
            oracle=b.ResidualPolicy(actor.artifact(),module.CONTRACT)
            config=b.UpdateConfig()
            objective=b.OfferedReferenceObjective(module.REFERENCE_VERSION,.1)
            actual=b.loss_for_rows(actor,[row],config,offered_reference=objective)
            ordinary=b.ordinary_loss_for_rows(oracle,[row],config)
            logp,_=oracle([row['observation']])
            expected=ordinary+.1*(-logp[0].mean()-math.log(7))
            self.assertEqual(float(actual.detach()),float(expected.detach()))
            actual.backward();expected.backward()
            for left,right in zip(actor.parameters(),oracle.parameters()):
                torch.testing.assert_close(left.grad,right.grad,rtol=0,atol=0)

    def test_single_offered_action_has_zero_reference_loss_and_gradient(self):
        actor,row=fixture(expansion,1)
        oracle=b.ResidualPolicy(actor.artifact(),expansion.CONTRACT)
        actual=b.loss_for_rows(actor,[row],b.UpdateConfig(),
            offered_reference=b.OfferedReferenceObjective(expansion.REFERENCE_VERSION,.1))
        old=b.loss_for_rows(oracle,[row],b.UpdateConfig())
        self.assertEqual(float(actual.detach()),float(old.detach()))
        actual.backward();old.backward()
        for left,right in zip(actor.parameters(),oracle.parameters()):
            torch.testing.assert_close(left.grad,right.grad,rtol=0,atol=0)

    def test_tiny_action_probability_receives_finite_corrective_gradient(self):
        logits=torch.tensor([15.,-15.],dtype=torch.float64,requires_grad=True)
        penalty=.1*(-torch.log_softmax(logits,0).mean()-math.log(2))
        penalty.backward()
        self.assertTrue(torch.isfinite(logits.grad).all().item())
        self.assertLess(float(logits.grad[1]),-.049)
        epsilon=1e-5
        def loss(x):return float(.1*(-torch.log_softmax(torch.tensor(x,dtype=torch.float64),0).mean()-math.log(2)))
        finite=(loss([15.,-15.+epsilon])-loss([15.,-15.-epsilon]))/(2*epsilon)
        self.assertAlmostEqual(finite,float(logits.grad[1]),places=9)

    def test_phase_version_cannot_cross_actor_boundary(self):
        for contract in (b.PURCHASE_CONTRACT,b.EXPANSION_CONTRACT):
            with self.assertRaises(ValueError):
                b.OfferedReferenceObjective(token.REFERENCE_VERSION).validate(contract)
        with self.assertRaises(ValueError):b.PurchaseReferenceObjective().validate(token.CONTRACT)

    def test_old_purchase_entrypoint_rejects_territory_model(self):
        actor,_=fixture(token)
        with self.assertRaises(ValueError):b.validate_model(actor.artifact())

    def test_reference_requires_checked_prefix_and_positive_coefficient(self):
        objective=b.OfferedReferenceObjective(token.REFERENCE_VERSION)
        with self.assertRaises(ValueError):b.validate_offered_reference(objective,token.CONTRACT,b.LEGACY_UPDATE_MODE)
        for coefficient in (0.,-1.,float('nan'),True):
            with self.assertRaises(ValueError):b.OfferedReferenceObjective(token.REFERENCE_VERSION,coefficient).validate(token.CONTRACT)

    def test_zero_default_path_is_identical_to_ordinary_loss(self):
        actor,row=fixture(token)
        oracle=b.ResidualPolicy(actor.artifact(),token.CONTRACT)
        actual=b.loss_for_rows(actor,[row],b.UpdateConfig(),offered_reference=None)
        expected=b.ordinary_loss_for_rows(oracle,[row],b.UpdateConfig())
        self.assertEqual(float(actual.detach()),float(expected.detach()))
        actual.backward();expected.backward()
        for left,right in zip(actor.parameters(),oracle.parameters()):
            torch.testing.assert_close(left.grad,right.grad,rtol=0,atol=0)

    def test_new_actors_reject_positive_card_reference(self):
        actor,row=fixture(token)
        with self.assertRaises(ValueError):
            b.loss_for_rows(actor,[row],b.UpdateConfig(cardReferenceKlCoefficient=.1))

    def test_checked_block_uses_explicit_offered_reference_and_fixed_behavior(self):
        actor,row=fixture(token)
        current=b.ResidualPolicy(actor.artifact(),token.CONTRACT)
        optimizer=torch.optim.Adam(current.parameters(),lr=.0003,foreach=False)
        before=b.digest(actor.artifact())
        block=b.fit_checked_block(current,optimizer,actor,[row]*8,b.UpdateConfig(batchSize=4),
            dict(epoch=0,minibatchOffset=0),time.monotonic()+10,
            offered_reference=b.OfferedReferenceObjective(token.REFERENCE_VERSION))
        self.assertTrue(block['accepted'])
        self.assertEqual(2,block['acceptedSteps'])
        self.assertEqual(before,b.digest(actor.artifact()))
        self.assertNotEqual(before,b.digest(current.artifact()))


if __name__=='__main__':unittest.main()
