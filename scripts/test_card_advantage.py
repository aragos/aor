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

SCHEMA={'encoderVersion':a.ENCODER,'stateFeatureNames':['cardWindow.normal','context'],
        'actionFeatureNames':['card.b','effect.role.0.cashDelta','kind.pass']}
def observation(context=1):
    return {'encoderVersion':a.ENCODER,'stateFeatures':{'cardWindow.normal':1.,'context':float(context)},
        'baselineIndex':0,'actions':[{'command':'pass','features':{'kind.pass':1.}},
        {'command':'play hand-1','features':{'card.b':1.,'effect.role.0.cashDelta':.5}}]}
def group(target=1.,context=1,identifier='0:g'):
    return a.prepare(observation(context),0,SCHEMA,[0.,target],identifier)
class Checks(unittest.TestCase):
 @classmethod
 def setUpClass(cls):torch.set_num_threads(1);torch.use_deterministic_algorithms(True)
 def test_shared_proposal_goldens(self):
    commands=['pass','play hand-a','play hand-a protect 0','play hand-b','play hand-c',
      'play hand-c protect 5','play hand-d','play hand-d player p2','play hand-d player p3','play hand-e']
    obs={'baselineIndex':5,'actions':[{'command':c} for c in commands]}
    expected=[[0,3,5,8],[0,2,5,9],[0,3,5,6],[0,5,8,9],[0,2,3,5],[0,1,3,5],[0,2,5,9],[0,2,5,9]]
    self.assertEqual([a.proposals(obs,s) for s in [0,1,2,17,-1,-2**31,2**31-1,-979738860]],expected)
 def test_exact_zero_network_falls_back(self):
    ensemble=a.Ensemble(a.bundle(SCHEMA,[a.initialize(SCHEMA,s) for s in a.SEEDS]))
    self.assertEqual(ensemble.choose(observation(),17)['index'],0)
 def test_highest_mean_veto_does_not_pick_lower_eligible(self):
    g=group();g.indices=(0,1,2);g.commands=('pass','play hand-1','play hand-2');g.baseline=0
    values=[[.5,.9,.6],[.5,.9,.6],[.5,.49,.6]]
    self.assertEqual(a.choose_values(g,values)['index'],0)
 def test_margin_and_unanimity_are_strict(self):
    g=group();self.assertEqual(a.choose_values(g,[[.5,.5]]*3)['index'],0)
    self.assertEqual(a.choose_values(g,[[.5,.8],[.5,.8],[.5,.5]])['index'],0)
 def test_nonzero_all_unit_scalar_and_torch_parity(self):
    member=a.initialize(SCHEMA,76101)
    for i in range(64):member['outputWeights'][i]=(i-31)*.007;member['stateBias'][i]=(i-31)*.003;member['actionBias'][i]=(31-i)*.004
    member['outputBias']=.13;g=group();actual=a.Network(SCHEMA,member)([g])[0].detach().tolist()
    self.assertTrue(all(abs(x-y)<1e-12 for x,y in zip(actual,a.scalar_values(SCHEMA,member,g))))
 def test_zero_output_has_nonzero_policy_gradient(self):
    network=a.Network(SCHEMA,a.initialize(SCHEMA,84501));a.loss(network,[[group()] ]).backward()
    self.assertGreater(float(network.outputWeights.grad.norm()),0)
 def test_equal_origin_group_and_action_weighting(self):
    network=a.Network(SCHEMA,a.initialize(SCHEMA,1));origins=[[group(1.),group(0.)],[group(0.)]]
    self.assertAlmostEqual(float(a.loss(network,origins)),.25)
 def test_duplicate_child_groups_do_not_multiply_group_weight(self):
    obs=observation();child=observation(-1)
    records=[{'observation':obs,'seed':0,'targets':[0.,0.],'replicates':4,'id':'root'}]+[
      {'observation':child,'seed':0,'targets':[0.,1.],'replicates':1,'id':f'child-{i}'} for i in range(4)]
    groups=data.merge_groups(records,SCHEMA,0)
    self.assertEqual(len(groups),2);self.assertEqual(groups[1].replicates,4)
    self.assertAlmostEqual(float(a.loss(a.Network(SCHEMA,a.initialize(SCHEMA,1)),[groups])),.5)
 def test_different_seed_remains_distinct_decision_group(self):
    records=[{'observation':observation(),'seed':s,'targets':[0.,1.],'replicates':1,'id':str(s)} for s in (0,1)]
    self.assertEqual(len(data.merge_groups(records,SCHEMA,0)),2)
 def test_materialized_ablation_matches_masked_input(self):
    member=a.initialize(SCHEMA,7);member['outputWeights']=[.1]*64;member['stateBias']=[.03]*64
    masked=a.Network(SCHEMA,member)([a.prepare(observation(),0,SCHEMA,ablation=True)])[0]
    exported=a.Network(SCHEMA,a.materialize_ablation(member,SCHEMA))([group()])[0]
    self.assertTrue(torch.equal(masked,exported))
 def test_normal_guard_avoids_evaluation(self):
    ensemble=a.Ensemble(a.bundle(SCHEMA,[a.initialize(SCHEMA,s) for s in a.SEEDS]));obs=observation();obs['stateFeatures']['cardWindow.normal']=0
    with patch.object(a.Network,'forward',side_effect=AssertionError('must not evaluate')):
      self.assertEqual(ensemble.choose(obs,0)['index'],0)
 def test_unknown_model_fields_rejected(self):
    artifact=a.bundle(SCHEMA,[a.initialize(SCHEMA,s) for s in a.SEEDS]);artifact['members'][0]['actorBias']=0
    with self.assertRaisesRegex(ValueError,'unexpected'):a.validate_bundle(artifact)
 def test_frequency_missing_family_falls_back(self):
    control=a.frequency_control(1.,0.)
    self.assertEqual(a.frequency_choice(control,observation(),4),0)
 def test_validation_counts_fallback_zero(self):
    ensemble=a.Ensemble(a.bundle(SCHEMA,[a.initialize(SCHEMA,s) for s in a.SEEDS]));report=fit.local_metrics(ensemble,[[group()]])
    self.assertEqual(report['meanSelectedAdvantage'],0);self.assertEqual(report['overriddenOrigins'],0)
 def test_checkpoint_resume_after_interrupted_epoch_is_exact(self):
    config=replace(a.CONFIG,epochs=2,checkpoints=(1,2),originsPerBatch=1)
    origins=[[group(1.,1,'0:a')],[group(-1.,-1,'1:a')]];identity={'fixed':'unit-test'}
    with tempfile.TemporaryDirectory() as tmp:
      first=Path(tmp)/'first';second=Path(tmp)/'second';original=fit.epoch
      expected,_=fit.fit_member(SCHEMA,origins,origins,7,config,first,identity,math.inf)
      def interrupted(*args):original(*args);raise RuntimeError('interrupt after mutable work')
      with patch.object(fit,'epoch',side_effect=interrupted):
        with self.assertRaises(RuntimeError):fit.fit_member(SCHEMA,origins,origins,7,config,second,identity,math.inf)
      actual,_=fit.fit_member(SCHEMA,origins,origins,7,config,second,identity,math.inf)
      self.assertEqual(actual,expected)
      self.assertEqual(common.read_checkpoint(first/'checkpoint.json'),common.read_checkpoint(second/'checkpoint.json'))
 def test_checkpoint_mutation_rejected_without_overwrite(self):
    config=replace(a.CONFIG,epochs=1,checkpoints=(1,));origins=[[group()]]
    with tempfile.TemporaryDirectory() as tmp:
      out=Path(tmp)/'fit';fit.fit_member(SCHEMA,origins,origins,1,config,out,{},math.inf)
      p=out/'checkpoint.json';raw=common.read_json(p);raw['payload']['epoch']=2;common.atomic_json(p,raw);before=p.read_bytes()
      with self.assertRaises(ValueError):fit.fit_member(SCHEMA,origins,origins,1,config,out,{},math.inf)
      self.assertEqual(p.read_bytes(),before)
if __name__=='__main__':unittest.main()
