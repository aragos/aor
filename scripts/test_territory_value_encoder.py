"""Explicit public-context opt-in, legacy compatibility and complete two-phase carry."""
import copy
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import territory_value as q
import territory_value_trajectory as t
from test_territory_value import observation, schema
from test_territory_value_trajectory import fixture, change_game


def schema2():
    result=schema('expansion')
    result.update(encoderVersion=q.EXPANSION_ENCODER_2,
                  stateFeatureNames=sorted(result['stateFeatureNames']+list(q.CATHEDRAL_USAGE_FEATURES)))
    return result


class EncoderVersionTests(unittest.TestCase):
    def setUp(self):
        q.b.torch.set_num_threads(1)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve()

    def test_version_two_is_explicit_and_does_not_relabel_old_contracts(self):
        old=q.initialize(schema('expansion'),103,'expansion')
        new=q.initialize(schema2(),103,'expansion')
        self.assertIs(q.contract_for('expansion',old['encoderVersion']),q.b.EXPANSION_CONTRACT)
        self.assertEqual('aor-expansion-observation-1',q.b.EXPANSION_CONTRACT.encoder)
        with self.assertRaises(ValueError):q.validate_model(new,'token')
        with self.assertRaises(ValueError):q.b.initialize(schema2(),103,contract=q.b.EXPANSION_CONTRACT)

    def test_new_scalar_and_torch_values_use_public_usage_with_the_same_math(self):
        model=q.initialize(schema2(),103,'expansion')
        model['outputWeights']=[.2*(-1)**i for i in range(64)]
        actor=q.ActionValue(model,'expansion')
        unused=dict(observation('expansion'),encoderVersion=q.EXPANSION_ENCODER_2)
        used=copy.deepcopy(unused);used['stateFeatures']['cathedral.used.role.0.role.1']=1.
        for row in (unused,used):
            expected=q.reference_forward(model,row,'expansion')
            q.b.torch.testing.assert_close(actor([row])[0],q.b.torch.tensor(expected,dtype=q.b.DTYPE),rtol=1e-13,atol=1e-13)
        self.assertNotEqual(q.reference_forward(model,unused,'expansion'),q.reference_forward(model,used,'expansion'))

    def test_old_raw_and_cached_rows_cannot_be_silently_zero_filled(self):
        old=q.ActionValue(q.initialize(schema('expansion'),103,'expansion'),'expansion')
        new=q.ActionValue(q.initialize(schema2(),103,'expansion'),'expansion')
        row=observation('expansion')
        with self.assertRaises(ValueError):new([row])
        with self.assertRaises(ValueError):new([old.encoder.prepare(row)])

    def test_initializer_cli_persists_exact_requested_schema_and_seed(self):
        path=self.root/'schema.json';q.b.atomic_json(path,schema2())
        out=self.root/'initial'
        subprocess.run([sys.executable,'-B',t.__file__,'initialize','--schema',str(path),
            '--phase','expansion','--seed','74702','--output',str(out)],check=True,timeout=30)
        model=t.validate_initializer(out/'policy.json','expansion',{})
        self.assertEqual(q.initialize(schema2(),74702,'expansion'),model)

    def test_two_phase_update_and_carry_bind_version_two(self):
        directory,bundle=fixture(self.root/'first-data',expansion2=True)
        first=self.root/'first';config=q.UpdateConfig(seed=103,targetSteps=16)
        t.update_pair(directory,bundle,config,first)
        payload,_,_=t.read_completed(first)
        self.assertEqual(q.EXPANSION_ENCODER_2,payload['online']['expansion']['encoderVersion'])
        frozen=t.read(directory/'source.json')['frozenCardPurchaseBundle']
        next_dir,next_bundle=fixture(self.root/'second-data',models={p:first/(p+'-policy.json') for p in q.PHASES},
            frozen_path=frozen,seed=31,expansion2=True)
        second=self.root/'second'
        t.update_pair(next_dir,next_bundle,config,second,checkpoint_path=first/'checkpoint.json')
        carried,_,_=t.read_completed(second)
        self.assertEqual({'token':8,'expansion':8},carried['optimizerSteps'])
        self.assertEqual(payload['targets'],carried['targets'])

    def test_complete_collection_rejects_old_version_row_under_new_model(self):
        directory,bundle=fixture(self.root/'data',expansion2=True)
        change_game(directory,1,lambda game:game['expansionDecisions'][0]['observation'].update(
            encoderVersion=q.b.EXPANSION_CONTRACT.encoder))
        with self.assertRaisesRegex(ValueError,'encoder'):t.ValueCollection(directory,bundle)

    def test_new_dictionary_must_contain_all_thirty_public_pair_columns(self):
        wrong=schema2();wrong['stateFeatureNames'].remove(q.CATHEDRAL_USAGE_FEATURES[0])
        with self.assertRaisesRegex(ValueError,'every public Cathedral'):q.initialize(wrong,103,'expansion')


if __name__=='__main__':unittest.main()
