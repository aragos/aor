"""Synthetic finite-horizon, compatibility and optimizer-lineage checks."""
import copy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest

import torch

import purchase_gae as gae
import purchase_policy as policy
from purchase_policy_toy import SCHEMA
from test_purchase_policy import collection


class PurchaseGaeMathTest(unittest.TestCase):
    def rows(self, count, reward=1.):
        return [dict(originIndex=0,number=i*7,**{'return':reward},
                     observation={'actions':[{}] if i==1 else [{},{}]}) for i in range(count)]

    def test_hand_computed_three_purchase_return(self):
        # delta=[.2,-.1,.7], including the forced middle purchase's clock tick.
        actual=policy.purchase_gae(self.rows(3),[.2,.4,.3])
        expected=[.2+.95*(-.1+.95*.7),-.1+.95*.7,.7]
        for a,b in zip(actual,expected):self.assertAlmostEqual(a,b,places=14)

    def test_lambda_one_matches_raw_terminal_return_minus_frozen_value(self):
        values=[.8,.1,.5,.9,.3]
        actual=policy.purchase_gae(self.rows(5,.2),values,lambda_value=1.)
        for advantage,value in zip(actual,values):self.assertAlmostEqual(advantage,.2-value,places=14)

    def test_singleton_uses_actual_terminal_reward_and_zero_terminal_value(self):
        self.assertEqual(policy.purchase_gae(self.rows(1,0),[.3]),[-.3])

    def test_empty_game_creates_no_target(self):
        self.assertEqual(policy.purchase_gae([],[]),[])

    def test_adjacent_games_do_not_bootstrap_into_each_other(self):
        rows=[*self.rows(1,.1),dict(originIndex=1,number=2,**{'return':.9})]
        actual=policy.purchase_gae(rows,[.2,.7])
        self.assertAlmostEqual(actual[0],-.1)
        self.assertAlmostEqual(actual[1],.2)

    def test_forced_purchase_is_not_removed_from_lambda_clock(self):
        full=policy.purchase_gae(self.rows(3),[.2,.4,.3])[0]
        reduced=policy.purchase_gae([self.rows(3)[0],self.rows(3)[2]],[.2,.3])[0]
        self.assertNotEqual(full,reduced)

    def test_global_engine_decision_gap_does_not_change_purchase_clock(self):
        rows=self.rows(3)
        changed=[dict(row,number=i*800) for i,row in enumerate(rows)]
        self.assertEqual(policy.purchase_gae(rows,[.2,.4,.3]),policy.purchase_gae(changed,[.2,.4,.3]))

    def test_shuffled_purchase_order_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'before minibatch'):
            policy.purchase_gae(list(reversed(self.rows(3))),[.2,.4,.3])


class PurchaseGaeUpdateTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.model=policy.initialize(SCHEMA,17)
        self.path=self.root/'initial.json';policy.atomic_json(self.path,self.model)
        self.config=policy.UpdateConfig(epochs=1,valueCoefficient=0,maxSeconds=60)

    def batch(self, name, model_path):
        directory=self.root/name;directory.mkdir();source=directory/'collection'
        collection(source,model_path)
        model=policy.read_json(model_path)
        rows,provenance=policy.read_collection(source,model_path,model)
        baseline=directory/'baseline.json'
        policy.atomic_json(baseline,dict(kind='purchase-frozen-state-baselines-1',
            collectionSha256=provenance['collectionSha256'],gameHashes=provenance['gameHashes'],
            behaviorModelSha256=policy.file_digest(model_path),predictor={'fixture':'fixed'},newFits=0,
            predictions=[dict(gameSha256=provenance['gameHashes'][row['originIndex']],
                              decision=row['number'],value=.25) for row in rows]))
        target=directory/'targets.json';gae.targets(rows,provenance,policy.file_digest(model_path),baseline,target)
        return source,rows,provenance,baseline,target

    def test_target_binding_preserves_original_return_logprob_and_behavior_value(self):
        _,rows,provenance,_,target=self.batch('batch',self.path)
        bound,identity=policy.PurchaseAdvantageTargets(str(target)).bind(rows,provenance,policy.file_digest(self.path))
        self.assertEqual(identity['estimator'],gae.ESTIMATOR)
        for original,result in zip(rows,bound):
            self.assertEqual(original,{k:v for k,v in result.items() if k!='_actorOnlyAdvantage'})
            self.assertNotIn('_actorOnlyBaseline',result)

    def test_target_cannot_be_modified_without_failed_recomputation(self):
        _,rows,provenance,_,target=self.batch('batch',self.path)
        document=policy.read_json(target);document['advantages'][0]['advantage']+=.1
        policy.atomic_json(target,document)
        with self.assertRaisesRegex(ValueError,'original ordered'):
            policy.PurchaseAdvantageTargets(str(target)).bind(rows,provenance,policy.file_digest(self.path))

    def test_singleton_gae_and_external_mc_have_identical_updates(self):
        source,_,_,baseline,target=self.batch('batch',self.path)
        for name,objective in [('mc',policy.ActorOnlyObjective('external',str(baseline))),
                               ('gae',policy.PurchaseAdvantageTargets(str(target)))]:
            policy.update(self.path,source,self.config,self.root/name,actor_only=objective)
        self.assertEqual(policy.read_json(self.root/'mc/policy.json'),policy.read_json(self.root/'gae/policy.json'))

    def test_second_batch_carries_only_matching_actor_optimizer(self):
        source,_,_,baseline,_=self.batch('first',self.path)
        policy.update(self.path,source,self.config,self.root/'update1',
                      actor_only=policy.ActorOnlyObjective('external',str(baseline)))
        model=self.root/'update1/policy.json'
        source,_,_,baseline,_=self.batch('second',model)
        policy.update(model,source,replace(self.config,seed=30),self.root/'update2',
            optimizer_from=self.root/'update1/checkpoint.json',
            actor_only=policy.ActorOnlyObjective('external',str(baseline)),
            actor_optimizer_continuation=policy.ActorAdamContinuation())
        old=policy.read_checkpoint(self.root/'update1/checkpoint.json')
        new=policy.read_checkpoint(self.root/'update2/checkpoint.json')
        self.assertEqual(len(new['optimizer']['param_groups'][0]['params']),5)
        self.assertEqual(new['model']['valueWeights'],self.model['valueWeights'])
        self.assertEqual(new['model']['valueBias'],self.model['valueBias'])
        self.assertNotEqual(old['optimizer'],new['optimizer'])
        self.assertEqual(policy.read_json(self.root/'update2/identity.json')['actorOptimizerContinuation']
                         ['checkpointSha256'],policy.file_digest(self.root/'update1/checkpoint.json'))

    def test_optimizer_cannot_cross_mc_and_gae_arms(self):
        source,_,_,baseline,_=self.batch('first',self.path)
        policy.update(self.path,source,self.config,self.root/'update1',
                      actor_only=policy.ActorOnlyObjective('external',str(baseline)))
        model=self.root/'update1/policy.json'
        source,_,_,_,target=self.batch('second',model)
        with self.assertRaisesRegex(ValueError,'cannot cross estimator'):
            policy.update(model,source,self.config,self.root/'update2',
                optimizer_from=self.root/'update1/checkpoint.json',
                actor_only=policy.PurchaseAdvantageTargets(str(target)),
                actor_optimizer_continuation=policy.ActorAdamContinuation())
        self.assertFalse((self.root/'update2').exists())

    def test_optimizer_carry_cannot_change_frozen_predictor(self):
        source,_,_,baseline,_=self.batch('first',self.path)
        policy.update(self.path,source,self.config,self.root/'update1',
                      actor_only=policy.ActorOnlyObjective('external',str(baseline)))
        model=self.root/'update1/policy.json'
        source,_,_,baseline,_=self.batch('second',model)
        document=policy.read_json(baseline);document['predictor']={'fixture':'changed'}
        policy.atomic_json(baseline,document)
        with self.assertRaisesRegex(ValueError,'same frozen state predictor'):
            policy.update(model,source,self.config,self.root/'update2',
                optimizer_from=self.root/'update1/checkpoint.json',
                actor_only=policy.ActorOnlyObjective('external',str(baseline)),
                actor_optimizer_continuation=policy.ActorAdamContinuation())

    def test_default_mc_loss_ignores_precomputed_target_fields(self):
        _,rows,_,_,_=self.batch('batch',self.path)
        actor=policy.ResidualPolicy(self.model,policy.PURCHASE_CONTRACT)
        expected=policy.loss_for_rows(actor,rows,self.config)
        changed=[dict(row,_actorOnlyAdvantage=99.) for row in rows]
        torch.testing.assert_close(policy.loss_for_rows(actor,changed,self.config),expected,rtol=0,atol=0)


if __name__=='__main__':unittest.main()
