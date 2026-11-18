"""Constructed four-head actors only; no retained inputs, world or scientific seed."""
import copy
import hashlib
import json
import math
import unittest

import numpy as np
import torch
import coordinated_es_math as es
import coordinated_es_projection as p


def original_actors():
    result={}
    for ordinal,head in enumerate(es.HEADS):
        states=['own.cash','own.tokens'];actions=['amount','risk']
        if head is es.Head.TOKEN:
            states=sorted([*states,*p.token_paired_policy.ORDER_NAMES])
            actions=sorted([*actions,*p.token_paired_policy.AMOUNT_NAMES])
        model=p.b.initialize(dict(encoderVersion=p.contract(head).encoder,
            stateFeatureNames=states,actionFeatureNames=actions),113+ordinal,contract=p.contract(head))
        model['actorWeights']=[.03*(index%3-1) for index in range(64)]
        model['valueWeights']=[.07]*64;model['valueBias']=.23
        raw=es.canonical(model);result[head]=p.Original(head,raw,hashlib.sha256(raw).hexdigest())
    return result


def observation(model,count=3):
    return dict(encoderVersion=model['encoderVersion'],baselineIndex=1 if count>1 else 0,
        stateFeatures={name:((index%7)+1)/8 for index,name in enumerate(model['stateFeatureNames'])},
        actions=[dict(command=f'unit-action-{row:04d}',features={name:(-1 if (index+row)%2 else 1)*
            ((index+row)%5+1)/8 for index,name in enumerate(model['actionFeatureNames'])}) for row in range(count)])


class ProjectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        cls.original=original_actors();cls.projection=p.generate(cls.original,'a'*64,'b'*64,17)

    def test_zero_coordinates_copy_all_four_original_raw_files(self):
        actual=p.materialize(self.projection,self.original,[0.]*256)
        self.assertEqual(actual,{head:value.raw for head,value in self.original.items()})

    def test_nonzero_vector_updates_all_five_actor_blocks(self):
        actual=p.materialize(self.projection,self.original,[.2,-.3]*128)
        for head in es.HEADS:
            model=json.loads(actual[head])
            self.assertTrue(all(model[name]!=self.original[head].model[name] for name in p.b.PARAMETERS[:-2]))

    def test_value_arrays_and_metadata_are_unchanged(self):
        actual=p.materialize(self.projection,self.original,[.1]*256)
        for head in es.HEADS:
            value=json.loads(actual[head]);original=self.original[head].model
            self.assertEqual({name:row for name,row in value.items() if name not in p.b.PARAMETERS[:-2]},
                {name:row for name,row in original.items() if name not in p.b.PARAMETERS[:-2]})

    def test_coordinates_are_owned_by_exact_head(self):
        actual=p.materialize(self.projection,self.original,[0.]*128+[.5]*64+[0.]*64)
        self.assertEqual([head for head in es.HEADS if actual[head]!=self.original[head].raw],[es.Head.EXPANSION])

    def test_single_coordinate_has_prescribed_outer_product_scale(self):
        z=[.0]*256;z[0]=.25
        actual=json.loads(p.materialize(self.projection,self.original,z)[es.Head.CARD])
        factors=self.projection.record['heads']['CARD']['factors'];old=self.original[es.Head.CARD].model
        expected=old['stateWeights'][3]+factors['stateLeft'][0][1]*.25*factors['stateRight'][0][1]/(8*math.sqrt(2))
        self.assertEqual(actual['stateWeights'][3],expected)
        self.assertEqual(actual['actorWeights'][0],old['actorWeights'][0]+factors['actorWeights'][0][0]*.25/64)

    def test_all_factors_have_row_rms_one(self):
        for head in self.projection.record['heads'].values():
            for rows in head['factors'].values():
                self.assertLess(float(np.max(np.abs(np.sqrt(np.mean(np.asarray(rows)**2,axis=1))-1))),2e-12)

    def test_projection_is_repeatable_without_global_rng_mutation(self):
        before=np.random.get_state()
        actual=p.generate(self.original,'a'*64,'b'*64,17)
        after=np.random.get_state()
        self.assertEqual(actual.wire,self.projection.wire)
        self.assertEqual(before[0],after[0]);self.assertTrue(np.array_equal(before[1],after[1]));self.assertEqual(before[2:],after[2:])

    def test_direction_normals_are_not_renormalized(self):
        directions,receipt=p.generate_directions(self.projection.scope,1,29)
        expected=np.random.Generator(np.random.PCG64(29)).standard_normal((16,256),dtype=np.float64)
        self.assertEqual(directions.record['vectors'],expected.tolist())
        self.assertNotEqual(es.rms(directions.vectors[0]),1.)
        self.assertFalse(receipt['renormalized'])

    def test_zero_factor_row_stops_without_redraw(self):
        with self.assertRaisesRegex(es.OperationalStop,'zero-or-nonfinite'):p.normalize_rows(np.zeros((64,2)))

    def test_nonfinite_factor_stops_without_redraw(self):
        values=np.ones((64,2));values[3,1]=np.inf
        with self.assertRaisesRegex(es.OperationalStop,'zero-or-nonfinite'):p.normalize_rows(values)

    def test_actor_source_tamper_is_rejected(self):
        original=dict(self.original);head=es.Head.CARD;model=original[head].model;model['actorWeights'][0]+=.1
        raw=es.canonical(model);original[head]=p.Original(head,raw,hashlib.sha256(raw).hexdigest())
        with self.assertRaisesRegex(ValueError,'exact original head'):p.materialize(self.projection,original,[.1]*256)

    def test_raw_tensor_matches_independent_numpy_on_all_heads_and_columns(self):
        for head,source in self.original.items():
            model=source.model;view=observation(model,17);actor=p.RawPolicy(model,head)
            actual=actor.raw([actor.public(view)])[0];expected=p.raw_residuals(model,head,view)
            self.assertLess(max(abs(a-b) for a,b in zip(actual,expected)),2e-12)
            self.assertTrue(all(not parameter.requires_grad for parameter in actor.parameters()))

    def test_raw_selector_does_not_inherit_old_residual_clipping(self):
        head=es.Head.EXPANSION;model=self.original[head].model
        model['actorWeights']=[10.]*64;model['actionBias']=[3.]*64
        view=observation(model);actor=p.RawPolicy(model,head)
        raw=actor.raw([actor.public(view)])[0]
        self.assertGreater(min(raw),20.)
        self.assertLess(max(abs(a-b) for a,b in zip(raw,p.raw_residuals(model,head,view))),2e-12)

    def test_raw_public_boundary_rejects_outcome_payload(self):
        head=es.Head.TOKEN;model=self.original[head].model;view=observation(model);view['reward']=1.
        with self.assertRaisesRegex(ValueError,'unexpected'):p.RawPolicy(model,head).public(view)

    def test_full_offer_batching_preserves_order_and_action_budget(self):
        head=es.Head.EXPANSION;model=self.original[head].model;actor=p.RawPolicy(model,head)
        views=[observation(model,1024) for _ in range(17)];prepared=[actor.public(view) for view in views]
        guards=[];actual=p.raw_all(actor,prepared,lambda:guards.append(True))
        self.assertEqual(len(guards),2);self.assertEqual(len(actual),17)
        self.assertEqual(actual[0],actual[-1])

    def test_prepared_dictionary_mismatch_is_rejected(self):
        head=es.Head.CARD;model=self.original[head].model;actor=p.RawPolicy(model,head)
        prepared=actor.public(observation(model));model['stateFeatureNames']=['other.cash','other.tokens']
        with self.assertRaisesRegex(ValueError,'different feature dictionary'):p.RawPolicy(model,head).raw([prepared])


if __name__=='__main__':unittest.main()
