from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import token_paired_campaign as campaign
import token_paired_campaign_plan as p
import token_paired_storage as storage
from test_token_paired_campaign_plan import fixture


class CampaignTests(unittest.TestCase):
    def test_missing_mac_chunk_never_becomes_recomputed_work(self):
        with tempfile.TemporaryDirectory() as directory:
            now=[21599.]
            with self.assertRaisesRegex(ValueError,'expired'):
                campaign.wait_chunks(directory,p.Clock(0.,21600.),1,now=lambda:now[0],
                    sleep=lambda seconds:now.__setitem__(0,now[0]+seconds))
            self.assertEqual(list(Path(directory).iterdir()),[])

    def test_exposure_failure_stops_before_any_fit_or_second_iteration(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol=root/'protocol.json';value=fixture()
            storage.publish_json(protocol,value);plan=p.Plan.from_record(value)
            model=root/'model.json';storage.publish_json(model,{})
            called=[]
            def collect(_plan,_profile,_root,iteration,chunk,_current):
                called.append((iteration,chunk));path=root/f'receipt-{chunk}.json'
                storage.publish_json(path,{'constructed':chunk});return path,path
            def corpus(*_):return dict(exposurePassed=False)
            with patch.dict(sys.modules,{'token_paired_screen':SimpleNamespace()}),patch.object(campaign.time,'time',lambda:1.), \
                 patch.object(campaign,'collect',collect),patch.object(campaign,'wait_chunks',lambda *_:[]), \
                 patch.object(campaign.storage,'iteration_corpus',corpus), \
                 patch.object(campaign.execution,'run_child',lambda *_:self.fail('Exposure stop cannot fit.')):
                result=campaign.desktop(plan,{'originalActor':p.subject(model)},protocol,root/'profile',
                    p.Clock(0.,21600.),root)
            self.assertEqual(called,[(1,i) for i in p.COLLECTION_CHUNKS['DESKTOP']])
            self.assertEqual(result,dict(status='stopped',reason='public-exposure-gate',completedIterations=0))

    def test_historical_replay_requires_exact_original_index_and_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);prepared=dict(signature='a'*64,scopeSha256='b'*64,scienceSha256='c'*64,
                source={'sha256':'d'*64});origin={'origin':{'sha256':'e'*64}}
            row=dict(kind='token-paired-reproduction-1',signature='a'*64,scopeSha256='b'*64,
                scienceSha256='c'*64,globalIndex=0,request=p.request(0),sourceSha256='d'*64,
                original={'sha256':'e'*64},fullBackboneEqual=True,publicRecordsEqual=True,terminalEqual=True,
                scientificTerminalGames=1,tailGames=0,backboneTerminalSha256='1'*64,
                publicRootSha256='2'*64,anchorsSha256='3'*64)
            path=root/'replay-0/replay-0.json';storage.publish_json(path,row)
            self.assertEqual(campaign.verify_replay(root,0,prepared,origin)['scientificTerminalGames'],1)
            row['sourceSha256']='f'*64;path.write_bytes(p.canonical(row))
            with self.assertRaisesRegex(ValueError,'reproduction'):campaign.verify_replay(root,0,prepared,origin)


if __name__=='__main__':unittest.main()
