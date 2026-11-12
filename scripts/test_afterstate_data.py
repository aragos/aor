from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import afterstate_data as s

class DriverTest(unittest.TestCase):
    def test_split_keeps_complete_world_chunks(self):
        audit={'seedBanks':{f'chunk-{i:02d}':dict(nativeOffset=i,count=256,seeds=list(range(256)),split='TRAIN' if i<=12 else 'VALIDATION' if i<=14 else 'TEST') for i in range(1,17)}}
        plan=s.chunk_plan(audit)
        self.assertEqual([c['split'] for c in plan],['training']*12+['validation']*2+['test']*2)
        self.assertEqual([c['replayIndex'] for c in plan],[0,85,170,255]*4)
    def test_every_seat_has_same_g3_and_separate_prior(self):
        p=dict(ordinary='M8',prior='F0',expansion='E',bundle='G3');c=dict(nativeOffset=71,replayIndex=85)
        source=s.joint_source(p,c)
        self.assertEqual(source['opponents'],['G3']);self.assertEqual(source['bundle'],'G3')
        self.assertEqual(source['baseline'],'M8');self.assertEqual(source['sharedPurchaseBaseline'],'F0')
        self.assertEqual(source['tokenOrder'],dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']))
    def test_historical_cli_caps_one_hour_and_one_worker(self):
        with tempfile.TemporaryDirectory() as d:
            p=dict(java=dict(executable='java'),workers=10,heapMiB=6144,runtime=d)
            with patch.object(s.exp,'remaining_seconds',return_value=7200),patch.object(s.execution,'run_child') as run:
                s.native(p,'afterstate-replay',Path(d)/'original',Path(d)/'new')
            command=run.call_args.args[1]
            self.assertEqual(command[command.index('--hours')+1],'1.0');self.assertEqual(command[command.index('--workers')+1],'1')
            self.assertFalse((Path(d)/'new').exists())
    def test_partial_child_output_never_reused(self):
        with tempfile.TemporaryDirectory() as d:
            output=Path(d)/'partial';output.mkdir()
            with self.assertRaises(ValueError):s.native({},'afterstate-collect','source',output)
    def test_native_call_has_original_remaining_deadline(self):
        with tempfile.TemporaryDirectory() as d:
            p=dict(java=dict(executable='java'),workers=10,heapMiB=6144,runtime=d)
            with patch.object(s.exp,'remaining_seconds',return_value=18),patch.object(s.execution,'run_child') as run:
                s.native(p,'afterstate-collect','source',Path(d)/'new')
            command=run.call_args.args[1]
            self.assertEqual(float(command[command.index('--hours')+1])*3600,18)
if __name__=='__main__':unittest.main()
