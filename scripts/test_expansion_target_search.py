"""Paired target exports, fixed stronger lineups, finite learning and final gates."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import expansion_target_search as target
import expansion_target_models as models
import expansion_target_arena as arena


def metrics(reward=.1,win=.1,count=128,lower=.01):
    return {name:dict(mean=value,count=count,lower95=lower,upper95=value+.1)
            for name,value in (('rewardDifference',reward),('winShareDifference',win))}


class TargetSearchTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name).resolve()
        self.baseline=dict(schemaVersion=1,rulesVersion='ah1996-three-epoch-4',dataVersion='data',weights={'tokens':1.,'income':4.,'cards':17.})
        self.expansion=dict(self.baseline,rulesVersion='ah1996-three-epoch-5',weights={'tokens':-2.,'income':9.,'cards':17.})
        base,expanded=self.root/'baseline.json',self.root/'expansion.json'
        target.document(base,self.baseline);target.document(expanded,self.expansion)
        self.protocol=dict(baseline=str(base),expansionModel=str(expanded),targetRulesVersion='ah1996-three-epoch-5',
            sourceRulesVersion='ah1996-three-epoch-4',proposalSeeds=[101,102,103,104],
            experimentalRulesTransfer=dict(sourceRulesVersion='ah1996-three-epoch-4',targetRulesVersion='ah1996-three-epoch-5'),
            frozenPurchase=dict(baseline='F0',policy='C'),
            worldBanks={name:dict(nativeOffset=28000003+i,count=count)
                        for i,(name,count) in enumerate(target.COUNTS.items())})

    def bundle(self,values=(1.,-2.,3.,-4.),name='model'):
        ordinary=models.coordinate.export_model(values,self.baseline,models.PROFILE,'ah1996-three-epoch-5')
        return models.export_bundle(self.root/name,ordinary,self.baseline,self.expansion)

    def test_both_exported_models_receive_identical_four_raw_additions(self):
        bundle=models.verify_bundle(self.bundle(),self.protocol)
        ordinary,expanded=[target.read(bundle[role]['path']) for role in ('ordinary','expansion')]
        self.assertEqual([ordinary['weights'][name] for name in models.FEATURES],[8.,-16.,24.,-32.])
        self.assertEqual([expanded['weights'][name] for name in models.FEATURES],[8.,-16.,24.,-32.])
        self.assertEqual(ordinary['weights']['tokens'],1.)
        self.assertEqual(expanded['weights']['tokens'],-2.)
        self.assertEqual(ordinary['weights']['cards'],expanded['weights']['cards'])

    def test_non_target_coefficient_drift_rejected(self):
        bundle=models.verify_bundle(self.bundle(),self.protocol)
        ordinary,expanded=[target.read(bundle[role]['path']) for role in ('ordinary','expansion')]
        ordinary['weights']['income']+=1
        with self.assertRaisesRegex(ValueError,'declared coordinate'):
            models.verify_models(ordinary,expanded,self.baseline,self.expansion)

    def test_mismatched_pair_additions_rejected(self):
        bundle=models.verify_bundle(self.bundle(),self.protocol)
        ordinary,expanded=[target.read(bundle[role]['path']) for role in ('ordinary','expansion')]
        expanded['weights'][models.FEATURES[0]]+=1
        with self.assertRaisesRegex(ValueError,'match exactly'):
            models.verify_models(ordinary,expanded,self.baseline,self.expansion)

    def test_out_of_bounds_pair_rejected(self):
        bundle=models.verify_bundle(self.bundle(),self.protocol)
        ordinary,expanded=[target.read(bundle[role]['path']) for role in ('ordinary','expansion')]
        ordinary['weights'][models.FEATURES[0]]=65
        with self.assertRaisesRegex(ValueError,'raw'):
            models.verify_models(ordinary,expanded,self.baseline,self.expansion)

    def test_changed_raw_model_file_invalidates_bundle(self):
        path=self.bundle();bundle=target.read(path)
        Path(bundle['ordinary']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError,'bytes changed'):models.verify_bundle(path,self.protocol)

    def test_training_source_has_fixed_evaluated_tokens_and_cyclic_four_roles(self):
        source=target.source(self.protocol,self.bundle(),'generation-0')
        self.assertEqual(source['opponents'],[self.protocol['baseline']]*4)
        self.assertEqual(source['incumbent'],self.protocol['baseline'])
        self.assertNotEqual(source['candidate'],source['incumbent'])
        self.assertEqual(source['expansionImitation']['opponentModels'],[None,self.protocol['expansionModel'],None,self.protocol['expansionModel']])
        self.assertEqual(source['tokenOrder'],dict(candidateSelector='FIXED_16_TO_15',incumbentSelector='FIXED_16_TO_15',opponentSelectors=['ORDINARY','ORDINARY','FIXED_16_TO_15','FIXED_16_TO_15']))
        self.assertEqual(source['frozenPurchase'],dict(baseline='F0',policy='C'))
        self.assertEqual(source['purpose'],'SCREENING')

    def test_heldout_source_uses_only_homogeneous_combined_rivals(self):
        source=target.source(self.protocol,self.bundle(),'heldout')
        self.assertEqual(source['opponents'],[self.protocol['baseline']])
        self.assertEqual(source['expansionImitation']['opponentModels'],[self.protocol['expansionModel']])
        self.assertEqual(source['tokenOrder']['opponentSelectors'],['FIXED_16_TO_15'])
        self.assertEqual(source['purpose'],'CONFIRMATION')
        self.assertEqual(source['pairsPerConfiguration'],4096)

    def fake_arena(self,protocol,directory,bundle,bank,positive=True):
        index=int(directory.name.rsplit('-',1)[-1])
        game=dict(wins=[0.,1.,0.,0.],rewards=[.1,.4,.2,.3],ranks=[4.,1.,3.,2.],scores=[1,4,2,3],decisions=100)
        pair=dict(index=0,seed=100,seat=0,configuration='test',opponentIndices=[0,1,2,3],candidate=game,incumbent=game)
        return metrics(reward=(index if positive else -index)/100,count=target.COUNTS[bank]),[pair],directory/'source.json'

    def test_four_generations_archive_at_most_nine_models(self):
        with mock.patch.object(target,'run_arena',side_effect=self.fake_arena) as native:
            receipt=target.evolve(self.protocol,self.root/'run')
        archive=target.read(self.root/'run/archive.json')
        self.assertEqual(native.call_count,32)
        self.assertEqual(len(archive['models']),9)
        self.assertEqual(receipt['status'],'passed')
        self.assertEqual([row['eliteIndices'] for row in archive['generations']],[[7,6]]*4)

    def test_no_positive_generation_elite_stops_before_selection(self):
        with mock.patch.object(target,'run_arena',side_effect=lambda *args:self.fake_arena(*args,positive=False)) as native:
            receipt=target.evolve(self.protocol,self.root/'run')
        self.assertEqual(native.call_count,32)
        self.assertEqual(receipt['status'],'rejected')
        self.assertFalse((self.root/'run/selection').exists())

    def test_proposals_and_both_model_files_exist_before_any_outcome(self):
        def fake(*args):
            directory=args[1]
            receipt=target.read(directory.parent/'proposals.json')
            self.assertEqual(len(receipt['proposals']),8)
            for row in receipt['proposals']:
                self.assertEqual(target.sha(row['bundle']),row['bundleSha256'])
                models.verify_bundle(row['bundle'],self.protocol)
            return self.fake_arena(*args)
        with mock.patch.object(target,'run_arena',side_effect=fake):target.evolve(self.protocol,self.root/'run')

    def test_existing_generation_refuses_retry(self):
        (self.root/'run/generation-0').mkdir(parents=True)
        with mock.patch.object(target,'run_arena') as native,self.assertRaises(FileExistsError):
            target.evolve(self.protocol,self.root/'run')
        native.assert_not_called()

    def test_incumbent_drift_fails_closed(self):
        def fake(*args):
            metrics,pairs,path=self.fake_arena(*args)
            if args[1].name=='evaluation-1':pairs[0]['incumbent']=dict(pairs[0]['incumbent'],decisions=101)
            return metrics,pairs,path
        with mock.patch.object(target,'run_arena',side_effect=fake),self.assertRaisesRegex(ValueError,'incumbent parity'):
            target.evolve(self.protocol,self.root/'run')

    def test_final_qualification_keeps_dual_ninety_nine_bounds(self):
        self.assertFalse(target.evolution.qualification(metrics(count=2048,lower=.001),'confirm')[0])
        self.assertTrue(target.evolution.qualification(metrics(count=4096,lower=.09),'heldout')[0])

    def test_heldout_never_opens_after_failed_confirmation(self):
        path=self.bundle();output=self.root/'run';output.mkdir()
        row=dict(candidateIndex=1,bundle=path,bundleSha256=target.sha(path),metrics=metrics(count=512))
        target.document(output/'selected.json',dict(candidate=row,rows=[row]))
        target.document(output/'confirm-report.json',dict(metrics=dict(passed=False)))
        with mock.patch.object(target,'run_arena') as native,self.assertRaisesRegex(ValueError,'passed mixed confirmation'):
            target.final(self.protocol,output,'heldout')
        native.assert_not_called()


if __name__=='__main__':unittest.main()
