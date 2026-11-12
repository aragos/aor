import unittest
import json
from dataclasses import replace
import numpy as np
import purchase_macro_value as m

def vector(x=0.):return (x,)+(0.,)*168
def outcome(x):return m.MacroOutcome(x,x,x,0.)
def plan(i,x=1.,supported=True):
 return m.MacroPlan(i,('pass',) if i==0 else (f'purchase {i}',),vector(0. if i==0 else x) if supported else None,supported,None if supported else 'UNSUPPORTED',(outcome(0. if i==0 else .2),)*2 if supported else None)
def origin(i,plans=None):return m.MacroOrigin(i,m.Split.TRAIN if i<384 else m.Split.VALIDATION,'a'*64,(plan(0),plan(1)) if plans is None else tuple(plans))
def model():return m.MacroRidge((1.,)*169,vector(.1),(),160,160)

class Subjects(unittest.TestCase):
 def test_test_rejected_before_plans(self):
  class Forbidden:
   def __iter__(self):raise AssertionError('TEST labels touched')
  with self.assertRaisesRegex(ValueError,'TEST'):m.MacroOrigin(448,'TEST','a'*64,Forbidden())
 def test_wrong_split(self):
  with self.assertRaisesRegex(ValueError,'split'):m.MacroOrigin(384,m.Split.TRAIN,'a'*64,())
 def test_first_action_labels_rejected(self):
  with self.assertRaisesRegex(ValueError,'COMPLETE_PREFIX'):replace(origin(0),labelKind='FIRST_ACTION_ONLY')
 def test_source_digest_required(self):
  with self.assertRaisesRegex(ValueError,'SHA'):replace(origin(0),sourceSha256='arbitrary')
 def test_pass_must_have_zero_features(self):
  with self.assertRaisesRegex(ValueError,'PASS'):origin(0,[replace(plan(0),features=vector(1.)),plan(1)])
 def test_unsupported_is_missing(self):
  value=origin(0,[plan(0),plan(1,supported=False)])
  self.assertFalse(value.eligible);self.assertEqual(value.missingReason,'NO_SUPPORTED_NONPASS')
 def test_unsupported_cannot_keep_labels(self):
  with self.assertRaisesRegex(ValueError,'no labels'):origin(0,[plan(0),replace(plan(1,supported=False),returns=(outcome(.2),)*2)])
 def test_duplicate_plan_rejected(self):
  with self.assertRaisesRegex(ValueError,'unique plans'):origin(0,[plan(0),plan(1),plan(1)])
 def test_two_streams_required(self):
  with self.assertRaisesRegex(ValueError,'both ordered'):origin(0,[plan(0),replace(plan(1),returns=(outcome(.2),))])
 def test_no_root_remains_missing(self):self.assertEqual(origin(0,[]).missingReason,'NO_ROOT')

class RidgeMath(unittest.TestCase):
 def test_exact_fixed_penalty(self):
  scale,weight,zero=m._weighted_solve([vector(-1.),vector(1.)],[-1.,1.],[1.,1.])
  self.assertAlmostEqual(weight[0],1/1.01);self.assertEqual(scale,(1.,)*169);self.assertEqual(zero,tuple(range(1,169)))
 def test_uncentered_rms_equal_origin(self):
  x,y,w=m.training_arrays([origin(0,[plan(0),plan(1,x=1.)]),origin(1,[plan(0),plan(1,x=3.),plan(2,x=3.)])])
  np.testing.assert_array_equal(w,[1.,.5,.5]);scale,_,_=m._weighted_solve(x,y,w);self.assertAlmostEqual(scale[0],np.sqrt(5.))
 def test_no_intercept_from_zero_columns(self):
  scale,weight,zero=m._weighted_solve([vector(),vector()],[1.,1.],[1.,1.])
  self.assertEqual(scale,(1.,)*169);self.assertEqual(weight,(0.,)*169);self.assertEqual(len(zero),169)
 def test_both_paired_streams_used(self):
  a=replace(plan(1),returns=(outcome(.2),outcome(.8)));b=replace(plan(0),returns=(outcome(.1),outcome(.4)))
  self.assertAlmostEqual(m.paired_difference(a,b),.25)
 def test_validation_cannot_scale(self):
  with self.assertRaisesRegex(ValueError,'TRAIN'):m.training_arrays([origin(384)])
 def test_minimum_training(self):
  with self.assertRaisesRegex(ValueError,'Insufficient'):m.fit([origin(i,[] if i>=159 else None) for i in range(384)])
 def test_one_fixed_fit(self):
  fitted=m.fit([origin(i,[] if i>=160 else None) for i in range(384)])
  self.assertEqual(fitted.trainingOrigins,160);self.assertEqual(fitted.trainingPlans,160);self.assertAlmostEqual(m.predict(fitted,vector(1.)),.2/1.01)
 def test_duplicate_origin_rejected(self):
  values=[origin(i) for i in range(384)];values[1]=values[0]
  with self.assertRaisesRegex(ValueError,'every prospective'):m.fit(values)

class Selection(unittest.TestCase):
 def test_nonpositive_is_pass(self):self.assertEqual(m.choose({0:0.,1:0.,2:-1.}),0)
 def test_teacher_order_tie(self):self.assertEqual(m.choose({0:0.,2:.1,1:.1}),1)
 def test_labels_do_not_enter_selection(self):
  before=m.choose({0:0.,1:m.predict(model(),plan(1).features)});changed=replace(plan(1),returns=(outcome(0.),)*2)
  self.assertEqual(before,m.choose({0:0.,1:m.predict(model(),changed.features)}))
 def test_nonfinite_rejected(self):
  with self.assertRaisesRegex(ValueError,'finite'):m.choose({0:0.,1:float('nan')})
 def test_missing_not_zero_effects(self):
  report=m.evaluate_validation(model(),[origin(i,None if i<408 else []) for i in range(384,448)])
  self.assertEqual(json.loads(json.dumps(report))['eligibleOrigins'],24)
  self.assertEqual(report['eligibleOrigins'],24);self.assertEqual(len(report['missingOrigins']),40);self.assertEqual(report['selectionEffectsVsPass']['reward']['origins'],24);self.assertAlmostEqual(report['selectionEffectsVsPass']['reward']['mean'],.2)
 def test_teacher_subset_denominator(self):
  values=[origin(i,[plan(0),plan(1)] if i<396 else [plan(0),plan(1,supported=False),plan(2)]) if i<408 else origin(i,[]) for i in range(384,448)]
  report=m.evaluate_validation(model(),values);self.assertEqual(report['eligibleOrigins'],24);self.assertEqual(report['teacherIndex1']['eligibleOrigins'],12)
 def test_validation_minimum(self):
  with self.assertRaisesRegex(ValueError,'Insufficient'):m.evaluate_validation(model(),[origin(i,None if i<407 else []) for i in range(384,448)])

if __name__=='__main__':unittest.main()
