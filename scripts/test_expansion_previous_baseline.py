"""UNIT optimizer/lineage proofs using constructed one-world records only."""
import copy
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import expansion_mc as mc
import expansion_on_policy_data as data
import expansion_previous_baseline as treatment
import expansion_state_baseline as baseline
import purchase_policy as policy
from test_expansion_mc import fixture,write


class PreviousBaselineTests(unittest.TestCase):
    def setUp(self):
        policy.torch.set_num_threads(1)
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.first=data.ExpansionCollection(*fixture(self.root/'batch1'))
        self.basis=baseline.FrozenBasis.load(baseline.subject(self.first.actor_path))
        self.protocol=baseline.subject(write(self.root/'UNIT-protocol.json',{'unit':True}))
        self.config=policy.UpdateConfig(epochs=1,valueCoefficient=0,maxBehaviorKl=2,maxSeconds=60)
        self.replica='PRIMARY_DESKTOP'

    def objective(self,collection=None,*,batch=1,previous=None,replica=None,protocol=None):
        return treatment.ExpansionBaselineObjective(collection or self.first,self.basis,batch=batch,
            replica=replica or self.replica,protocol_subject=protocol or self.protocol,previous=previous)

    def update(self,collection=None,*,batch=1,previous=None,name=None,config=None,replica=None,protocol=None):
        out=self.root/(name or f'numeric{batch}')
        result=treatment.update(collection or self.first,self.basis,config or self.config,out,batch=batch,
            replica=replica or self.replica,protocol_subject=protocol or self.protocol,previous=previous)
        return out,result

    def next_collection(self,previous,batch=2):
        actor=previous/'actor/policy.json'
        directory,_=fixture(self.root/f'batch{batch}',model=policy.read_json(actor))
        source_path=directory.parent/'source.json';source=policy.read_json(source_path)
        source['actor']=baseline.subject(actor);write(source_path,source)
        (directory/'source.json').write_bytes(source_path.read_bytes())
        manifest=policy.read_json(directory/'collection.json')
        manifest['source']['sha256']=policy.file_digest(source_path);manifest['actor']['path']=str(actor)
        write(directory/'collection.json',manifest)
        return data.ExpansionCollection(directory,actor)

    def test_first_batch_loss_and_gradients_equal_unchanged_mc(self):
        old=mc.ExpansionMcObjective(self.first);new=self.objective();new.admit(self.config)
        old_rows,_=old.phase_reader(self.first.directory,self.first.actor_path,self.first.model,contract=mc.CONTRACT)
        new_rows,_=new.phase_reader(self.first.directory,self.first.actor_path,self.first.model,contract=mc.CONTRACT)
        left=policy.ResidualPolicy(self.first.model,mc.CONTRACT);right=policy.ResidualPolicy(self.first.model,mc.CONTRACT)
        a=policy.loss_for_rows(left,old_rows,self.config,expansion_mc=old,precomputed_advantages=True)
        b=policy.loss_for_rows(right,new_rows,self.config,expansion_baseline=new,precomputed_advantages=True)
        self.assertEqual(a.item(),b.item());a.backward();b.backward()
        for name in policy.PARAMETERS:self.assertTrue(policy.torch.equal(getattr(left,name).grad,getattr(right,name).grad))

    def test_first_batch_actor_and_all_adam_slots_equal_unchanged_mc(self):
        mc.update(self.first,self.config,self.root/'control')
        output,receipt=self.update()
        old=policy.read_checkpoint(self.root/'control/checkpoint.json');new=policy.read_checkpoint(output/'actor/checkpoint.json')
        self.assertEqual(new['model'],old['model']);self.assertEqual(new['optimizer'],old['optimizer'])
        self.assertEqual(receipt['actorAdamSteps'],1);self.assertEqual(receipt['previousActorAdamSteps'],0)
        self.assertEqual(set(new['optimizer']['state']),set(map(str,range(5))))

    def test_baseline_is_fitted_only_after_completed_actor(self):
        original=baseline.fit
        def checked(*args):
            actor_done=baseline.check_subject(args[-1]['actorCompletion'])
            self.assertTrue(actor_done.is_file())
            identity=policy.read_json(actor_done.parent/'identity.json')
            self.assertEqual(identity['expansionBaselineObjective']['predictionsSha256'],policy.digest([.5]))
            return original(*args)
        with patch.object(baseline,'fit',side_effect=checked):output,_=self.update()
        fitted=policy.read_json(output/'baseline.json');self.assertEqual(fitted['intercept'],1.)

    def test_next_batch_uses_previous_baseline_and_preserves_behavior_bytes(self):
        previous,_=self.update();c=self.next_collection(previous);obj=self.objective(c,batch=2,previous=previous)
        obj.admit(self.config);rows,p=obj.phase_reader(c.directory,c.actor_path,c.model,contract=mc.CONTRACT)
        _,identity=obj.bind(rows,p,c.actor_sha)
        self.assertEqual(rows[0]['_actorOnlyAdvantage'],0.)
        self.assertEqual({k:v for k,v in rows[0].items() if k!='_actorOnlyAdvantage'},c.rows[0])
        self.assertEqual(identity['previousBaseline'],baseline.subject(previous/'baseline.json'))

    def test_same_replica_continues_five_slot_adam(self):
        previous,_=self.update();c=self.next_collection(previous)
        output,receipt=self.update(c,batch=2,previous=previous,config=replace(self.config,seed=91))
        self.assertEqual(receipt['previousActorAdamSteps'],1);self.assertEqual(receipt['actorAdamSteps'],2)
        identity=policy.read_json(output/'actor/identity.json')
        self.assertEqual(identity['expansionBaselineOptimizerContinuation']['replica'],self.replica)

    def test_wrong_replica_rejected_before_optimizer(self):
        previous,_=self.update();c=self.next_collection(previous)
        with patch.object(policy,'update') as update:
            with self.assertRaisesRegex(ValueError,'same-replica'):
                self.update(c,batch=2,previous=previous,replica='MAC_REPLICATION')
        update.assert_not_called()

    def test_wrong_protocol_rejected_before_optimizer(self):
        previous,_=self.update();c=self.next_collection(previous)
        other=baseline.subject(write(self.root/'other-protocol.json',{'unit':'other'}))
        with patch.object(policy,'update') as update:
            with self.assertRaisesRegex(ValueError,'same-replica'):self.update(c,batch=2,previous=previous,protocol=other)
        update.assert_not_called()

    def test_skip_batch_rejected_before_optimizer(self):
        previous,_=self.update();c=self.next_collection(previous,batch=3)
        with patch.object(policy,'update') as update:
            with self.assertRaisesRegex(ValueError,'same-replica'):self.update(c,batch=3,previous=previous)
        update.assert_not_called()

    def test_same_bytes_actor_at_different_path_rejected(self):
        previous,_=self.update();c=data.ExpansionCollection(*fixture(self.root/'copy',model=policy.read_json(previous/'actor/policy.json')))
        with self.assertRaisesRegex(ValueError,'preceding actor path'):self.update(c,batch=2,previous=previous)

    def test_changed_previous_baseline_rejected(self):
        previous,_=self.update();c=self.next_collection(previous)
        value=policy.read_json(previous/'baseline.json');value['intercept']=.25;write(previous/'baseline.json',value)
        with self.assertRaisesRegex(ValueError,'unchanged completed'):self.update(c,batch=2,previous=previous)

    def test_resealed_baseline_cannot_claim_wrong_completed_actor(self):
        previous,_=self.update();c=self.next_collection(previous)
        value=policy.read_json(previous/'baseline.json');value['provenance']['actorCompletion']=baseline.subject(previous/'identity.json')
        write(previous/'baseline.json',value);done=policy.read_json(previous/'finished.json')
        done['nextBaseline']=baseline.subject(previous/'baseline.json');done['artifacts']=treatment._inventory(previous)
        write(previous/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'fit must follow'):self.update(c,batch=2,previous=previous)

    def test_uncompleted_predecessor_rejected(self):
        previous,_=self.update();c=self.next_collection(previous);(previous/'finished.json').unlink()
        with self.assertRaises(FileNotFoundError):self.update(c,batch=2,previous=previous)

    def test_return_mutation_after_objective_creation_is_rejected(self):
        obj=self.objective();self.first.rows[0]['return']=.2
        with self.assertRaisesRegex(ValueError,'original rows'):obj.admit(self.config)

    def test_observation_mutation_after_objective_creation_is_rejected(self):
        obj=self.objective();self.first.rows[0]['observation']['stateFeatures']['cash']=.2
        with self.assertRaisesRegex(ValueError,'original rows'):obj.admit(self.config)

    def test_prepared_tensor_mutation_is_rejected(self):
        obj=self.objective();self.first.rows[0]['_prepared'].state_values[0]=.2
        with self.assertRaisesRegex(ValueError,'prepared tensors'):obj.admit(self.config)

    def test_provenance_mutation_is_rejected(self):
        obj=self.objective();self.first.provenance['gameMeanReward']=.2
        with self.assertRaisesRegex(ValueError,'collection/provenance'):obj.admit(self.config)

    def test_prediction_mutation_after_detachment_is_rejected(self):
        obj=self.objective();obj.admit(self.config);obj.predictions=(.2,)
        with self.assertRaisesRegex(ValueError,'prediction identity'):obj.unchanged()

    def test_other_objective_cannot_alias_previous_baseline(self):
        obj=self.objective()
        with self.assertRaisesRegex(ValueError,'separate actor Adam'):
            policy.update(self.first.actor_path,self.first.directory,self.config,self.root/'bad',contract=mc.CONTRACT,
                entrypoint=treatment.__file__,collection_reader=obj.phase_reader,expansion_baseline=obj,
                expansion_mc=mc.ExpansionMcObjective(self.first))

    def test_later_objective_cannot_reset_adam(self):
        previous,_=self.update();c=self.next_collection(previous);obj=self.objective(c,batch=2,previous=previous)
        obj.admit(self.config);obj.persist_targets(self.root/'reset-targets.json')
        with self.assertRaisesRegex(ValueError,'Adam must come'):
            policy.update(c.actor_path,c.directory,self.config,self.root/'bad',contract=mc.CONTRACT,
                entrypoint=treatment.__file__,collection_reader=obj.phase_reader,expansion_baseline=obj)

    def test_rejection_restores_fresh_actor_and_optimizer(self):
        with patch.object(policy,'behavior_metrics',return_value={'behaviorKl':3.,'valueMse':0.}):output,receipt=self.update()
        checkpoint=policy.read_checkpoint(output/'actor/checkpoint.json')
        self.assertEqual(checkpoint['model'],self.first.model);self.assertEqual(checkpoint['optimizer']['state'],{})
        self.assertEqual(receipt['actorAdamSteps'],0);self.assertEqual(receipt['acceptedEpochs'],0)
        self.assertFalse(receipt['actorChanged'])

    def test_later_rejection_restores_all_inherited_moments(self):
        previous,_=self.update();c=self.next_collection(previous)
        with patch.object(policy,'behavior_metrics',return_value={'behaviorKl':3.,'valueMse':0.}):
            output,receipt=self.update(c,batch=2,previous=previous)
        old=policy.read_checkpoint(previous/'actor/checkpoint.json');new=policy.read_checkpoint(output/'actor/checkpoint.json')
        self.assertEqual(new['model'],old['model']);self.assertEqual(new['optimizer'],old['optimizer'])
        self.assertEqual(receipt['actorAdamSteps'],1)

    def test_empty_batch_rejected_before_actor_or_baseline_fit(self):
        c=data.ExpansionCollection(*fixture(self.root/'empty',empty=True))
        with patch.object(policy,'update') as actor,patch.object(baseline,'fit') as fitted:
            with self.assertRaisesRegex(ValueError,'entirely empty'):self.update(c)
        actor.assert_not_called();fitted.assert_not_called()

    def test_failed_baseline_preserves_actor_without_complete_transaction(self):
        with patch.object(baseline,'fit',side_effect=ValueError('UNIT ridge failure')):
            with self.assertRaisesRegex(ValueError,'UNIT ridge failure'):self.update()
        self.assertTrue((self.root/'numeric1/actor/finished.json').exists())
        self.assertFalse((self.root/'numeric1/finished.json').exists())

    def test_final_batch_does_not_fit_unused_baseline(self):
        previous,_=self.update()
        for batch in range(2,8):previous,_=self.update(self.next_collection(previous,batch),batch=batch,previous=previous)
        c=self.next_collection(previous,8)
        with patch.object(baseline,'fit') as fitted:output,receipt=self.update(c,batch=8,previous=previous)
        fitted.assert_not_called();self.assertIsNone(receipt['nextBaseline']);self.assertFalse((output/'baseline.json').exists())

    def test_detached_evidence_precedes_actor_update_and_retains_original_row_keys(self):
        original=policy.update
        def checked(*args,**kwargs):
            target=policy.read_json(self.root/'numeric1/detached-targets.json')
            self.assertEqual(target['rows'],[dict(originIndex=0,number=3,prediction=.5,advantage=.5)])
            return original(*args,**kwargs)
        with patch.object(policy,'update',side_effect=checked):self.update()

    def test_changed_detached_evidence_rejects_before_update(self):
        obj=self.objective();obj.admit(self.config);path=self.root/'targets.json';obj.persist_targets(path)
        target=policy.read_json(path);target['rows'][0]['prediction']=.2;write(path,target)
        with self.assertRaisesRegex(ValueError,'baseline subject'):obj.admit_optimizer(None,self.config)

    def test_partial_adam_slots_rejected_even_after_outer_hash_resealing(self):
        previous,_=self.update();c=self.next_collection(previous)
        path=previous/'actor/checkpoint.json';checkpoint=policy.read_checkpoint(path)
        del checkpoint['optimizer']['state']['0'];policy.save_checkpoint(path,checkpoint)
        done=policy.read_json(previous/'finished.json');done['actor']['checkpoint']=baseline.subject(path)
        done['artifacts']=treatment._inventory(previous);write(previous/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'all five moments'):self.update(c,batch=2,previous=previous)

    def test_interrupted_actor_cannot_fit_or_publish_baseline(self):
        with patch.object(policy,'behavior_metrics',side_effect=ValueError('Purchase update exceeded its elapsed-time budget.')):
            with self.assertRaises(ValueError):self.update()
        self.assertFalse((self.root/'numeric1/baseline.json').exists())
        self.assertFalse((self.root/'numeric1/finished.json').exists())


if __name__=='__main__':unittest.main()
