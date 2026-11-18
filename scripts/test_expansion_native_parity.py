"""Consume only the synthetic fixture emitted by ExpansionOnPolicyParityTest on the JVM."""
import math
from pathlib import Path
import unittest

import expansion_mc as mc
import purchase_policy as policy


FIXTURE = Path(__file__).resolve().parents[1]/'training/build/test-fixtures/expansion-schema2-parity.json'
COLLECTION = FIXTURE.parent/'expansion-collection'


class NativeParityTests(unittest.TestCase):
    @unittest.skipUnless(FIXTURE.exists(), 'Run the focused native parity fixture first.')
    def test_native_scalar_torch_probabilities_values_and_twenty_four_choices(self):
        policy.torch.set_num_threads(1)
        fixture=policy.read_json(FIXTURE)
        self.assertEqual(fixture['kind'],'expansion-schema2-synthetic-parity-1')
        self.assertEqual([c['name'] for c in fixture['cases']],['nonzero','zero-head','singleton'])
        count=0
        for case in fixture['cases']:
            with self.subTest(case=case['name']):
                model,obs,expected=case['model'],case['observation'],case['evaluation']
                scalar=policy.reference_forward(model,obs,contract=mc.CONTRACT)
                actor=policy.ResidualPolicy(model,mc.CONTRACT)
                with policy.torch.no_grad():logp,value=actor([obs])
                for key in ('logits','probabilities'):
                    for actual,native in zip(scalar[key],expected[key]):self.assertAlmostEqual(actual,native,places=12)
                for actual,native in zip(logp[0].exp().tolist(),expected['probabilities']):
                    self.assertAlmostEqual(actual,native,places=12)
                self.assertAlmostEqual(scalar['value'],expected['value'],places=12)
                self.assertAlmostEqual(float(value[0]),expected['value'],places=12)
                for item in case['choices']:
                    uniform=policy.selection_uniform(item['seed']);self.assertEqual(uniform,item['uniform'])
                    index=policy.select(scalar['probabilities'],uniform)
                    self.assertEqual(index,item['choice']['index'])
                    self.assertEqual(obs['actions'][index]['command'],item['choice']['command'])
                    self.assertAlmostEqual(math.log(scalar['probabilities'][index]),item['choice']['logProbability'],places=12)
                    count+=1
        self.assertEqual(count,24)

    @unittest.skipUnless((COLLECTION/'collection/finished.json').exists(),
        'Run the focused constructed native collection export first.')
    def test_native_completed_contract_preserves_actor_row_and_empty_origin(self):
        from expansion_on_policy_data import ExpansionCollection
        policy.torch.set_num_threads(1)
        collection=ExpansionCollection(COLLECTION/'collection',COLLECTION/'inputs/expansion-actor.json')
        self.assertEqual(collection.provenance['games'],2)
        self.assertEqual([origin['decisions'] for origin in collection.origins],[1,0])
        self.assertEqual(len(collection.rows),1)
        self.assertEqual(collection.rows[0]['originIndex'],0)
        collection.unchanged()


if __name__=='__main__':unittest.main()
