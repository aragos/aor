"""Finite strong-pool roles/continuation routing; no native games or real fitting."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_ecology_data as data
import purchase_gae as gae
import purchase_gae_learning_curve as curve
import purchase_strong_curriculum as strong
import purchase_strong_data as reader


class StrongCurriculumTest(unittest.TestCase):
    def protocol(self):
        return dict(baseline='/m8',opponents=['/m8']*4,prior='/f0',purchasePolicy='/initial/policy.json',
            initialActor='/initial/policy.json',initialOptimizer='/initial/checkpoint.json',
            expansionModel='/e',experimentalRulesTransfer=strong.legacy.RULES_TRANSFER,
            banks={**{f'training-{i}':dict(nativeOffset=48000000+i) for i in range(1,33)},
                   'checkpoint-32':dict(nativeOffset=48000100)})

    def test_collection_changes_only_current_actor_while_every_rival_stays_initial(self):
        source=strong.batch_source(self.protocol(),12,'/batch-11/policy.json')
        self.assertEqual(source['policy'],'/batch-11/policy.json')
        self.assertEqual(source['opponentPurchasePolicy'],'/initial/policy.json')
        self.assertEqual(source['expansionImitation'],dict(candidateModel='/e',opponentModels=['/e']*4))
        self.assertEqual(source['tokenOrder'],dict(candidateSelector=data.FIXED,opponentSelectors=[data.FIXED]*4))
        self.assertEqual(source['sharedPurchaseBaseline'],'/f0')

    def test_legacy_collection_bindings_remain_mixed(self):
        source=data.bindings('/e')
        self.assertEqual(source['expansionImitation']['opponentModels'],[None,'/e',None,'/e'])
        self.assertEqual(source['tokenOrder']['opponentSelectors'],['ORDINARY','ORDINARY',data.FIXED,data.FIXED])

    def test_collection_homogeneity_requires_its_explicit_type(self):
        with self.assertRaisesRegex(ValueError,'collection ecology'):
            data.bindings('/e',collection_ecology='homogeneous-purchase-collection-1')

    def test_collection_ecology_cannot_override_arena_role_owner(self):
        with self.assertRaisesRegex(ValueError,'collection ecology'):
            data.bindings('/e',arena=True,collection_ecology=reader.ECOLOGY)

    def test_final_compares_against_initial_and_shared_rivals_on1024_worlds(self):
        source=strong.arena_source(self.protocol(),'/final32')
        self.assertEqual(source['pairsPerConfiguration'],1024)
        self.assertEqual(source['frozenPurchase'],dict(baseline='/f0',policy='/initial/policy.json',
            candidatePolicy='/final32',incumbentPolicy='/initial/policy.json'))
        self.assertEqual(source['tokenOrder']['opponentSelectors'],[data.FIXED]*4)
        self.assertEqual(source['expansionImitation']['opponentModels'],['/e']*4)

    def test_extra_training_batch_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'32 fixed'):
            strong.batch_source(self.protocol(),33,'/candidate')

    def test_strong_verifier_passes_explicit_fixed_actor_and_collection_profile(self):
        with patch.object(data,'verify_collection',return_value=({},0)) as verify:
            reader.verify_collection('/collection')
        self.assertEqual(verify.call_args.kwargs['expected_purchase'],reader.G2)
        self.assertIs(verify.call_args.kwargs['collection_ecology'],reader.ECOLOGY)

    def test_reader_identity_binds_new_validation_owner(self):
        with patch.object(data,'read_collection',return_value=(['row'],{'readerSha256':'old'})) as read:
            rows,provenance=reader.read_collection('/c','/m',{},contract='purchase')
        self.assertEqual(rows,['row'])
        self.assertEqual(provenance['readerSha256'],strong.sha(reader.__file__))
        self.assertEqual(read.call_args.kwargs['expected_purchase'],reader.G2)

    def test_gae_optional_reader_is_explicit_and_default_unchanged(self):
        custom=lambda *args,**kwargs:None
        with patch.object(gae.policy,'update') as update:
            gae.update('/c','/m',{},'/b','/t','/out','gae',optimizer_from='/previous',collection_reader=custom)
        self.assertIs(update.call_args.kwargs['collection_reader'],custom)
        self.assertIsInstance(update.call_args.kwargs['actor_optimizer_continuation'],gae.policy.ActorAdamContinuation)

    def test_legacy_gae_reader_stays_original(self):
        with patch.object(gae.policy,'update') as update:
            gae.update('/c','/m',{},'/b','/t','/out','gae')
        self.assertIs(update.call_args.kwargs['collection_reader'],gae.baseline.read_collection)

    def fake_run(self,accepted):
        batches=[];looks=[]
        def batch(protocol,path,output,index,model,**owners):
            self.assertEqual(str(model),'/initial/policy.json' if index==1 else f'/actor-{index-1}')
            self.assertIs(owners['source_builder'],strong.batch_source)
            self.assertIs(owners['collection_verifier'],reader.verify_collection)
            batches.append(index)
            return Path(f'/actor-{index}'),dict(modelSha256=f'actor-{index}',update=dict(acceptedEpochs=accepted))
        def final(protocol,path,output,model):
            looks.append(str(model));return dict(batch=32)
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)
            with patch.object(curve,'train_batch',side_effect=batch),patch.object(strong,'final_checkpoint',side_effect=final), \
                 patch.object(strong.exp,'remaining_seconds',return_value=1000), \
                 patch.object(curve,'sha',return_value='protocol'),patch('builtins.print'):
                result=strong.train(self.protocol(),output/'protocol',output)
        return batches,looks,result['metrics']

    def test_all32_batches_precede_the_only_evaluation(self):
        batches,looks,report=self.fake_run(4)
        self.assertEqual(batches,list(range(1,33)))
        self.assertEqual(looks,['/actor-32'])
        self.assertTrue(report['finalCheckpointEligibleForReview'])
        self.assertEqual(report['scientificTerminalGames']+report['historicalReproductions'],10376)

    def test_four_zero_batches_stop_without_an_arena_or_fallback(self):
        batches,looks,report=self.fake_run(0)
        self.assertEqual(batches,[1,2,3,4]);self.assertEqual(looks,[])
        self.assertEqual(report['stopReason'],'four-consecutive-zero-accepted-batches')
        self.assertFalse(report['finalCheckpointEligibleForReview'])

    def test_partial_run_cannot_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary);(output/'batches').mkdir()
            with patch.object(curve,'train_batch') as execute:
                with self.assertRaisesRegex(ValueError,'no automatic resume'):
                    strong.train(self.protocol(),output/'protocol',output)
            execute.assert_not_called()

    def test_supervisor_binds_one_four_hour_attempt_to_strong_entrypoint(self):
        spec=strong.campaign_spec(Path('/package'),dict(python=dict(executable='/python')),{'/pin':'hash'})
        self.assertEqual(spec['limits']['maximumSeconds'],14400)
        self.assertEqual(spec['stages'][0]['maximumSeconds'],14400)
        self.assertEqual(spec['stages'][0]['maximumAttempts'],1)
        self.assertIn('/package/scripts/purchase_strong_curriculum.py',spec['stages'][0]['command'])


if __name__=='__main__':unittest.main()
