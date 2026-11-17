"""Constructed subprocess and lossless-copy checks; no scientific models or random draws."""
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import robust_token_campaign as c
import robust_league_remote as remote


class RemoteLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.root=Path(self.temporary.name).resolve()
        self.script=self.root/'constructed-numeric.py'
        self.script.write_text("""import hashlib,json,pathlib,sys
out=pathlib.Path(sys.argv[sys.argv.index('--output')+1]);out.mkdir(parents=True)
raw=b'{"generation":0,"coordinates":[0,0]}';(out/'center.json').write_bytes(raw)
receipt={'kind':'constructed-fixture','complete':True,'files':{'center.json':hashlib.sha256(raw).hexdigest()}}
data=json.dumps(receipt).encode();(out/'receipt.json').write_bytes(data)
print(json.dumps({'path':'receipt.json','sha256':hashlib.sha256(data).hexdigest()}))
""")
        self.profile=dict(root=str(self.root),numericPython=sys.executable,numericScript=str(self.script),
            minimumFreeBytes=0,maximumDataBytes=1024**2,maximumRssBytes=1024**3)
        self.request=dict(taskId='prepare',mode='prepare',clock=dict(deadlineEpoch=time.time()+5),
            request=dict(kind='constructed-numeric-fixture-1'))

    def tearDown(self):self.temporary.cleanup()

    def test_numerical_receipt_and_center_are_transported_without_rematerializing(self):
        with patch.object(remote,'admitted',return_value=(self.profile,self.root)),\
                patch.object(remote.native,'group_rss',return_value=0):
            result=remote.numeric(self.root/'unused-profile',self.request)
        self.assertTrue(result['complete'])
        self.assertEqual(result['newGames'],0)
        self.assertEqual(set(result['metadata']),{'receipt.json','center.json'})

    def test_hung_numerical_process_is_reaped_at_the_original_deadline(self):
        self.script.write_text('import time\ntime.sleep(60)\n')
        self.request['clock']['deadlineEpoch']=time.time()+.2
        with patch.object(remote,'admitted',return_value=(self.profile,self.root)),\
                patch.object(remote.native,'group_rss',return_value=0):
            with self.assertRaisesRegex(ValueError,'original deadline'):
                remote.numeric(self.root/'unused-profile',self.request)
        directory=self.root/'numeric-tasks/prepare'
        self.assertTrue(c.read(directory/'failed.json')['processExited'])
        pid=c.read(directory/'owner.json')['nativePid']
        with self.assertRaises(ProcessLookupError):os.kill(pid,0)

    def test_second_numerical_attempt_cannot_overwrite_the_original(self):
        with patch.object(remote,'admitted',return_value=(self.profile,self.root)),\
                patch.object(remote.native,'group_rss',return_value=0):
            remote.numeric(self.root/'unused-profile',self.request)
            with self.assertRaisesRegex(ValueError,'once-only'):
                remote.numeric(self.root/'unused-profile',self.request)

    def test_model_archive_contains_only_declared_unchanged_actor_subjects(self):
        base=self.root/'numerical/population';bundle=base/'members/member-02/bundle.json'
        heads={}
        for name in ('CARD','PURCHASE','EXPANSION','TOKEN'):
            path=bundle.parent/'heads'/name/'policy.json';c.write(path,{'constructedActor':[0.1]})
            heads[name]=dict(path=str(path.relative_to(bundle.parent)),sha256=c.sha(path))
        c.write(bundle,{'kind':'constructed-bundle','heads':heads})
        request=dict(taskId='generation-1',clock=dict(deadlineEpoch=time.time()+5),bundleRoot=str(base),
            bundles=[dict(path=str(bundle),sha256=c.sha(bundle))])
        with patch.object(remote,'admitted',return_value=(self.profile,self.root)):
            result=remote.archive(self.root/'unused-profile',request)
        self.assertEqual(len(result['files']),5)
        self.assertEqual(result['newMaterializations'],0)
        self.assertEqual(result['archive']['sha256'],c.sha(result['archive']['path']))


if __name__=='__main__':unittest.main()
