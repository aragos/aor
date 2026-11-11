"""Synthetic-only split, math and resource tests; no retained outcome queries or fitting."""
from pathlib import Path
from tempfile import TemporaryDirectory
import math
import unittest
from unittest.mock import patch
import numpy as np
import torch
import purchase_value_data as data
import purchase_value_benchmark as benchmark
import purchase_value_campaign as campaign

class SplitTests(unittest.TestCase):
    def test_old_rule_banks_are_training(self):
        self.assertTrue(all(data.split(name, 'batch-16') == 'training' for name in data.CORE))
    def test_transfer_boundary_is_fixed(self):
        self.assertEqual(data.split(data.TRANSFER, 'batch-12'), 'training')
        self.assertEqual(data.split(data.TRANSFER, 'batch-13'), 'validation')
    def test_entire_stronger_ecology_is_test(self):
        self.assertTrue(all(data.split(data.TEST, f'batch-{i}') == 'test' for i in range(1, 9)))
    def test_unknown_dataset_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown dataset'): data.split('new', 'batch-1')
    def test_invalid_batch_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown dataset'): data.split(data.TEST, 'batch-9')
    def test_encoder_units_preserved(self):
        self.assertEqual(data.encode_state({'a': 1/3, 'b': -2}, {'b': 0, 'a': 1}), ([0, 1], [-2., 1/3]))
    def test_unknown_feature_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unknown feature'): data.encode_state({'private': 1}, {'public': 0})
    def test_nonfinite_value_rejected(self):
        with self.assertRaisesRegex(ValueError, 'encoder range'): data.encode_state({'a': float('nan')}, {'a': 0})
    def test_evidence_not_overwritten(self):
        with TemporaryDirectory() as temp:
            p=Path(temp)/'receipt.json';data.document(p, {'original': True})
            with self.assertRaisesRegex(ValueError, 'overwrite'): data.document(p, {'changed': True})
            self.assertEqual(data.read(p), {'original': True})

    def test_full_split_uses_actual_seed_and_hash_identity(self):
        games=[];batches=[]
        for dataset in data.DATASETS:
            for batch in range(1,9 if dataset==data.TEST else 17):
                name=f'batch-{batch}'
                batches.append(dict(dataset=dataset,batch=name,archive='retained.tar.gz',statistics={'stateFeatureValues':256}))
                for index in range(256):
                    key=len(games)
                    games.append(dict(dataset=dataset,batch=name,index=index,gameSeed=key,sha256=f'{key:064x}',decisions=1))
        result=data.declaration({'batches':batches},games)
        self.assertEqual(result['splitCounts'],dict(training=15360,validation=1024,test=2048))
        altered=[dict(row) for row in games];altered[-1]['gameSeed']=altered[0]['gameSeed']
        with self.assertRaisesRegex(ValueError,'actual game seed'):
            data.declaration({'batches':batches},altered)

class MathTests(unittest.TestCase):
    def test_parameter_counts(self):
        self.assertEqual(sum(p.numel() for p in benchmark.Critic((64,), 1).parameters()), 227713)
        self.assertEqual(sum(p.numel() for p in benchmark.Critic((256, 256), 2).parameters()), 976641)
    def test_reproducible_fresh_initialization(self):
        a,b=benchmark.Critic((64,),1),benchmark.Critic((64,),1)
        self.assertTrue(all(torch.equal(x,y) for x,y in zip(a.parameters(),b.parameters())))
    def test_scalar_bounded_output(self):
        result=benchmark.Critic((64,),1,inputs=3)(torch.tensor([[0.,1.,-1.],[2.,0.,0.]]))
        self.assertEqual(tuple(result.shape),(2,));self.assertTrue(bool(((result>0)&(result<1)).all()))
    def test_equal_game_weight(self):
        self.assertEqual(benchmark.game_weights(np.array([0,0,1]),np.array([2,1])).tolist(),[.5,.5,1.])
    def test_shared_permutation(self):
        self.assertEqual(benchmark.order(range(100),4,15).tolist(),benchmark.order(range(100),4,15).tolist())
        self.assertNotEqual(benchmark.order(range(100),4,15).tolist(),benchmark.order(range(100),5,15).tolist())
    def test_frozen_critic_scalar_oracle(self):
        m=dict(stateWeights=[.2,-.1]*64,stateBias=[.03]*64,valueWeights=[.01]*64,valueBias=-.02)
        actual=benchmark.FrozenC64(m)(torch.tensor([[.5,-.4]],dtype=torch.float64)).item()
        self.assertAlmostEqual(actual,1/(1+math.exp(-(.64*math.tanh(.17)-.02))),places=14)
    def test_paired_bootstrap_sign(self):
        result=benchmark.paired_bootstrap([-.1,-.1,-.1],7,200)
        self.assertAlmostEqual(result['lower95'],-.1);self.assertAlmostEqual(result['upper95'],-.1)
    def test_empty_calibration_bins_explicit(self):
        bins=benchmark.calibration(np.array([.1]),np.array([.2]),np.array([1.]))
        self.assertEqual(len(bins),10);self.assertEqual(bins[1]['meanReward'],.2);self.assertIsNone(bins[9]['meanReward'])
    def test_lr_sweep_rejected(self):
        with self.assertRaisesRegex(ValueError,'fixed optimizer'):benchmark.Config(learningRate=.01).validate()
    def test_csr_materialization(self):
        fake=object.__new__(data.Cache);fake.np=np
        fake.arrays=dict(rowptr=np.array([0,2,3],dtype='uint64'),columns=np.array([0,2,1],dtype='uint16'),values=np.array([1/3,-.2,.7]))
        dense=fake.dense(np.array([1,0]),dtype='float64')
        self.assertEqual(dense[0,1],.7);self.assertEqual(dense[1,0],1/3);self.assertEqual(dense[1,2],-.2);self.assertEqual(np.count_nonzero(dense),3)

class SupervisorTests(unittest.TestCase):
    def test_clean_environment(self):
        values=campaign.clean_environment();self.assertEqual(values['OMP_NUM_THREADS'],'2')
        self.assertEqual(values['CUBLAS_WORKSPACE_CONFIG'],':4096:8');self.assertNotIn('PYTHONPATH',values)
    def test_absolute_deadline(self):
        with TemporaryDirectory() as temp,patch.object(campaign.time,'time',return_value=11):
            with self.assertRaisesRegex(ValueError,'deadline expired'):campaign.guard(Path(temp),10,{})
    def test_existing_attempt_blocks_launch(self):
        with TemporaryDirectory() as temp:
            root=Path(temp);data.document(root/'attempt.json',{});data.document(root/'environment.json',{})
            with patch.object(campaign,'verify_package',return_value={}):
                with self.assertRaisesRegex(ValueError,'One explicit'):campaign.run(root,'test')

if __name__=='__main__':unittest.main()
