"""Actual synthetic FP32 forwards only; no scientific data, fitting or game simulation."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import joint_credit_targets as credit
import joint_state_predictor as predictor
import joint_trajectory as joint
import purchase_policy as policy
import purchase_value_benchmark as benchmark


class SharedPredictorTests(unittest.TestCase):
    def setUp(self):
        predictor.configure()
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.names = ['card.cash','card.epoch','purchase.cash','purchase.epoch']
        self.dictionary = self.root/'dictionary.json'
        policy.atomic_json(self.dictionary,dict(version='joint-state-chronology-1',stateFeatureNames=self.names,
            units='unchanged-existing-phase-encoder-units',missingValue=0.))
        self.original = benchmark.Critic((64,),19,inputs=4)
        self.checkpoint = self.root/'critic.pt';torch.save(self.original.state_dict(),self.checkpoint)
        for name,value in dict(CHECKPOINT=policy.file_digest(self.checkpoint),DICTIONARY=policy.file_digest(self.dictionary),
                               INPUTS=4,PHASE_INPUTS={'card':2,'purchase':2}).items():
            guard=patch.object(predictor,name,value);guard.start();self.addCleanup(guard.stop)
        self.descriptor = predictor.prepare_descriptor(self.checkpoint,self.dictionary,self.root/'frozen')
        self.actor = predictor.FrozenJointPredictor.read(self.descriptor)

    def collection(self, *, empty=False):
        # A minimal synthetic instance of the reader's validated boundary; no production loader
        # or hidden game state is bypassed in the public adapter path.
        value=object.__new__(joint.JointCollection)
        provenance=self.root/'synthetic-origin.json'
        policy.atomic_json(provenance,dict(kind='synthetic-current-collection'))
        value.pins={str(provenance):policy.file_digest(provenance)}
        value.common=dict(collectionSha256=policy.file_digest(provenance),gameHashes=['a'*64,'b'*64],games=2,
            jointEcology=dict(version=joint.JointEcology.CURRENT.value,dictionarySha256=policy.file_digest(self.dictionary)))
        value.bundle={phase:dict(sha256=('c' if phase=='card' else 'd')*64,stateFeatureNames=['cash','epoch'])
                      for phase in joint.PHASES}
        def row(number,cash,epoch):
            return dict(originIndex=0,number=number,turn=1,observation=dict(stateFeatures={'cash':cash,'epoch':epoch},
                actions=[{'command':'private-action','features':{'irrelevant':123}}],baselineIndex=0),
                selectedIndex=0,behaviorLogProbability=-.2,behaviorValue=.8,actorSeed=7,**{'return':.9})
        value.rows=dict(card=[] if empty else [row(2,.25,1/3),row(11,.5,2/3)],
                        purchase=[] if empty else [row(7,.75,1/3)])
        return value

    def test_actual_forward_matches_existing_critic_with_exact_phase_prefixes(self):
        collection=self.collection();merged=credit.chronology(collection.rows,collection.common)
        actual=self.actor.values(merged)
        inputs=torch.tensor([[.25,1/3,0,0],[0,0,.75,1/3],[.5,2/3,0,0]],dtype=torch.float32)
        with torch.no_grad():expected=[float(value) for value in self.original(inputs)]
        self.assertEqual(expected,actual)

    def test_descriptor_binds_actual_checkpoint_dictionary_code_and_numerical_contract(self):
        descriptor=policy.read_json(self.descriptor);contract=policy.read_json(descriptor['inferenceContractPath'])
        self.assertEqual(policy.file_digest(self.checkpoint),descriptor['checkpointSha256'])
        self.assertEqual(policy.file_digest(self.dictionary),descriptor['stateDictionarySha256'])
        self.assertEqual(policy.file_digest(benchmark.__file__),contract['criticOwnerSha256'])
        self.assertEqual(('cpu','float32',1,1),(contract['device'],contract['dtype'],contract['threads'],contract['interopThreads']))
        self.assertFalse(any(parameter.requires_grad for parameter in self.actor._critic.parameters()))

    def test_loader_does_not_advance_surrounding_torch_rng(self):
        before=torch.random.get_rng_state().clone()
        predictor.FrozenJointPredictor.read(self.descriptor)
        self.assertTrue(torch.equal(before,torch.random.get_rng_state()))

    def test_actions_outcomes_and_behavior_metadata_never_enter_values(self):
        collection=self.collection();merged=credit.chronology(collection.rows,collection.common)
        expected=self.actor.values(merged)
        changed=copy.deepcopy(merged)
        for _,_,identity,row in changed:
            row.update(selectedIndex=999,behaviorLogProbability=-999,behaviorValue=-999,actorSeed=-123,**{'return':float('nan')})
            row['observation'].update(actions=[{'private':'sentinel'}],baselineIndex=999)
            identity.update(gameSha256='metadata-only',decision=999,sourceRow=999)
        self.assertEqual(expected,self.actor.values(changed))

    def test_missing_features_are_zero_without_rescaling_units(self):
        collection=self.collection();merged=credit.chronology(collection.rows,collection.common)[:1]
        merged[0][3]['observation']['stateFeatures']={'cash':.25}
        with torch.no_grad():expected=float(self.original(torch.tensor([[.25,0,0,0]],dtype=torch.float32))[0])
        self.assertEqual([expected],self.actor.values(merged))

    def test_unknown_metadata_feature_is_rejected(self):
        collection=self.collection();merged=credit.chronology(collection.rows,collection.common)
        merged[0][3]['observation']['stateFeatures']['selectedIndex']=0
        with self.assertRaisesRegex(ValueError,'never metadata'):self.actor.values(merged)

    def test_unknown_phase_is_rejected(self):
        collection=self.collection();merged=credit.chronology(collection.rows,collection.common)
        merged[0][2]['phase']='expansion'
        with self.assertRaisesRegex(ValueError,'recorded phase'):self.actor.values(merged)

    def test_out_of_encoder_range_is_rejected(self):
        collection=self.collection();merged=credit.chronology(collection.rows,collection.common)
        merged[0][3]['observation']['stateFeatures']['cash']=17
        with self.assertRaisesRegex(ValueError,'encoder units'):self.actor.values(merged)

    def test_checkpoint_tamper_is_rejected_before_prediction(self):
        self.checkpoint.write_bytes(self.checkpoint.read_bytes()+b'changed')
        with self.assertRaisesRegex(ValueError,'Frozen joint inputs'):self.actor.values([])

    def test_descriptor_cannot_change_numeric_environment_even_with_rehashed_contract(self):
        descriptor=policy.read_json(self.descriptor);path=Path(descriptor['inferenceContractPath'])
        contract=policy.read_json(path);contract['dtype']='float64';policy.atomic_json(path,contract)
        descriptor['inferenceContractSha256']=policy.file_digest(path);policy.atomic_json(self.descriptor,descriptor)
        with self.assertRaisesRegex(ValueError,'numerical environment'):predictor.FrozenJointPredictor.read(self.descriptor)

    def test_non_fp32_tensors_are_rejected_intrinsically(self):
        state={key:value.double() for key,value in self.original.state_dict().items()};torch.save(state,self.checkpoint)
        with patch.object(predictor,'CHECKPOINT',policy.file_digest(self.checkpoint)),self.assertRaisesRegex(ValueError,'finite FP32'):
            predictor.load_subjects(self.checkpoint,self.dictionary)

    def test_inferred_dimension_mismatch_is_rejected_intrinsically(self):
        state=self.original.state_dict();state['layers.0.weight']=state['layers.0.weight'][:,:3];torch.save(state,self.checkpoint)
        with patch.object(predictor,'CHECKPOINT',policy.file_digest(self.checkpoint)),self.assertRaisesRegex(ValueError,'tensor shapes'):
            predictor.load_subjects(self.checkpoint,self.dictionary)

    def test_nonfinite_tensor_is_rejected_intrinsically(self):
        state=self.original.state_dict();state['layers.0.bias'][0]=float('nan');torch.save(state,self.checkpoint)
        with patch.object(predictor,'CHECKPOINT',policy.file_digest(self.checkpoint)),self.assertRaisesRegex(ValueError,'finite FP32'):
            predictor.load_subjects(self.checkpoint,self.dictionary)

    def test_dictionary_content_cannot_change_after_loading(self):
        raw=policy.read_json(self.dictionary);raw['stateFeatureNames'][0]='card.chosen';policy.atomic_json(self.dictionary,raw)
        with self.assertRaisesRegex(ValueError,'Frozen joint inputs'):self.actor.values([])

    def test_predictions_bind_every_original_world_and_both_heads_and_feed_existing_targets(self):
        collection=self.collection();output=self.root/'values.json'
        result=predictor.predict(collection,self.actor,output)
        self.assertEqual(collection.common['gameHashes'],result['gameHashes'])
        self.assertEqual({phase:collection.bundle[phase]['sha256'] for phase in joint.PHASES},result['behaviorModels'])
        self.assertEqual([(2,'card',0),(7,'purchase',0),(11,'card',1)],
            [(r['decision'],r['phase'],r['sourceRow']) for r in result['predictions']])
        self.assertNotIn('return',str(result));self.assertNotIn('private-action',str(result))
        targets=credit.build_targets(collection.rows,collection.common,result['behaviorModels'],output)
        self.assertEqual(3,len(targets['chronological']['targets']))

    def test_zero_row_worlds_remain_bound_without_invented_predictions(self):
        collection=self.collection(empty=True);result=predictor.predict(collection,self.actor,self.root/'empty.json')
        self.assertEqual(['a'*64,'b'*64],result['gameHashes']);self.assertEqual([],result['predictions'])

    def test_legacy_collection_cannot_claim_current_predictor_provenance(self):
        collection=self.collection();del collection.common['jointEcology']
        with self.assertRaisesRegex(ValueError,'validated CURRENT'):predictor.predict(collection,self.actor,self.root/'bad.json')
        self.assertFalse((self.root/'bad.json').exists())

    def test_phase_dictionary_mismatch_is_rejected_before_inference(self):
        collection=self.collection();collection.bundle['card']['stateFeatureNames']=['other']
        with self.assertRaisesRegex(ValueError,'two-head state dictionary'):predictor.predict(collection,self.actor,self.root/'bad.json')

    def test_original_collection_tamper_is_rejected_before_inference(self):
        collection=self.collection();Path(next(iter(collection.pins))).write_text('changed')
        with self.assertRaisesRegex(ValueError,'Frozen joint inputs'):predictor.predict(collection,self.actor,self.root/'bad.json')

    def test_prediction_output_cannot_be_overwritten(self):
        collection=self.collection();path=self.root/'values.json';path.write_text('preserve')
        with self.assertRaisesRegex(ValueError,'previously frozen'):predictor.predict(collection,self.actor,path)
        self.assertEqual('preserve',path.read_text())

    def test_descriptor_cannot_be_regenerated_between_batches(self):
        with self.assertRaisesRegex(ValueError,'previously frozen'):
            predictor.prepare_descriptor(self.checkpoint,self.dictionary,self.root/'frozen')


if __name__=='__main__':unittest.main()
