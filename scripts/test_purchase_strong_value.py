"""Synthetic-only contract, warm-start and sealed-selection regression checks."""
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
import torch
import purchase_strong_value as study
import purchase_strong_value_data as data
import purchase_strong_value_fit as fit
import purchase_value_benchmark as benchmark
import purchase_value_data as original


def banks():
    return dict(initWarm={'seeds':[1000]},initFresh={'seeds':[1001]},
                epochOrder={'seeds':list(range(1002,1022))},bootstrap={'seeds':[1022]})


class ContractTests(unittest.TestCase):
    def test_late_controller_changes_cannot_invalidate_scientific_result(self):
        with TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('progress.json','corpus.json','numeric.log','study-report.json',
                         'batches/batch-1/batch-receipt.json','value/fits/selected.json',
                         'campaign-state.json','logs/study.log'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('{}')
            pinned={str(p):original.sha(p) for p in study.result_artifacts(root)}
            (root/'campaign-state.json').write_text('{"status":"completed"}')
            (root/'logs/study.log').write_text('controller exited')
            self.assertEqual(len(pinned),6)
            self.assertTrue(all(original.sha(p)==digest for p,digest in pinned.items()))
            (root/'value/fits/selected.json').write_text('{"changed":true}')
            self.assertNotEqual(original.sha(root/'value/fits/selected.json'),
                                pinned[str(root/'value/fits/selected.json')])
    def test_prospective_split_by_batch(self):
        self.assertEqual([data.split(i) for i in range(1,17)],['training']*12+['validation']*2+['test']*2)
    def test_source_names_follow_exact_split(self):
        self.assertEqual([data.bank_name(i) for i in (1,12,13,14,15,16)],
                         ['training-1','training-12','validation-1','validation-2','test-1','test-2'])
    def test_no_postdeclared_batch(self):
        with self.assertRaisesRegex(ValueError,'sixteen'):data.split(17)
    def test_original_optimizer_recipe_preserved(self):
        actual=data.config(banks());expected=replace(original.Config(),smallSeed=1000,largeSeed=1001,
                                                   orderSeed=1002,bootstrapSeed=1022)
        self.assertEqual(actual,expected)
    def test_all_twenty_shuffle_streams_reserved(self):
        altered=banks();altered['epochOrder']['seeds']=list(range(1002,1021))
        with self.assertRaisesRegex(ValueError,'Twenty|twenty'):data.config(altered)
    def test_validation_tie_keeps_warm(self):
        rows={n:dict(testInspected=False,best={'validationMse':.2}) for n in ('warm','fresh')}
        self.assertEqual(fit.selected_arm(rows),'warm')
    def test_validation_selects_fresh_without_test(self):
        rows={'warm':dict(testInspected=False,best={'validationMse':.2}),
              'fresh':dict(testInspected=False,best={'validationMse':.19})}
        self.assertEqual(fit.selected_arm(rows),'fresh')
    def test_test_inspected_fit_cannot_be_selected(self):
        rows={n:dict(testInspected=True,best={'validationMse':.2}) for n in ('warm','fresh')}
        with self.assertRaisesRegex(ValueError,'validation-only'):fit.selected_arm(rows)
    def test_native_roles_are_fixed_g2_and_homogeneous(self):
        protocol=dict(baseline='ordinary',opponents=['ordinary']*4,purchasePolicy='g2',prior='f0',
            expansionModel='expansion',banks={data.bank_name(i):dict(nativeOffset=i) for i in range(1,17)})
        sources=[study.batch_source(protocol,i) for i in range(1,17)]
        self.assertEqual([r['seed'] for r in sources],list(range(1,17)))
        for row in sources:
            self.assertEqual(row['policy'],row['opponentPurchasePolicy'])
            self.assertEqual(row['tokenOrder']['opponentSelectors'],['FIXED_16_TO_15']*4)
            self.assertEqual(row['expansionImitation']['opponentModels'],['expansion']*4)
            self.assertEqual(row['replayIndices'],[0,85,170,255])
    def test_corpus_rejects_world_reuse_across_split(self):
        games=[dict(id=i,index=i%256,batch=f'batch-{i//256+1}',split=data.split(i//256+1),
            dataset=data.KIND,gameSeed=i,sha256=str(i),decisions=1) for i in range(4096)]
        corpus=dict(kind=data.KIND,games=games,batches=[{}]*16,splitCounts=data.SPLITS,rows=4096)
        data.verify_declaration(corpus)
        games[-1]=dict(games[-1],gameSeed=0)
        with self.assertRaisesRegex(ValueError,'unique worlds'):data.verify_declaration(corpus)


class WarmStartTests(unittest.TestCase):
    def exercise(self,initial):
        model=benchmark.Critic((64,),1)
        captured=[];real_adam=torch.optim.Adam
        def adam(params,**kwargs):
            parameters=list(params);optimizer=real_adam(parameters,**kwargs)
            captured.append(([p.detach().clone() for p in parameters],len(optimizer.state)))
            return optimizer
        cache=SimpleNamespace(arrays=dict(starts=np.array([0,1]),stops=np.array([1,2]),
            rowGame=np.array([0,1]),reward=np.array([.4,.6])),
            game_rows=lambda games:np.array(games,dtype='int64'),
            dense=lambda rows,dtype='float32':np.zeros((len(rows),3556),dtype=dtype))
        corpus={'games':[{'id':0,'split':'training'},{'id':1,'split':'validation'}]}
        config=replace(original.Config(),epochs=1,gamesPerBatch=1)
        with TemporaryDirectory() as directory,patch.object(torch.optim,'Adam',side_effect=adam),\
             patch.object(torch.cuda,'reset_peak_memory_stats'),patch.object(torch.cuda,'synchronize'),\
             patch.object(torch.cuda,'max_memory_allocated',return_value=0):
            benchmark.fit_one('synthetic',(64,),1,cache,corpus,Path(directory),config,lambda:None,
                              torch.device('cpu'),initial_state=initial)
        return captured[0],model
    def test_warm_start_loads_exact_weights_before_fresh_adam(self):
        source=benchmark.Critic((64,),7)
        (parameters,moments),_=self.exercise(source.state_dict())
        self.assertEqual(moments,0)
        self.assertTrue(all(torch.equal(a,b) for a,b in zip(parameters,source.parameters())))
    def test_omitted_warm_start_preserves_original_seeded_parameters(self):
        (parameters,moments),original_model=self.exercise(None)
        self.assertEqual(moments,0)
        self.assertTrue(all(torch.equal(a,b) for a,b in zip(parameters,original_model.parameters())))


if __name__=='__main__':unittest.main()
