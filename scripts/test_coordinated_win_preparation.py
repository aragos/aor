"""Pure relocation/admission checks over one genuinely completed constructed numeric preparation."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import patch

import torch
import coordinated_es_numeric as base
import coordinated_win_numeric as numeric
import coordinated_win_plan as p
import coordinated_win_preparation as preparation
from test_coordinated_es_numeric import put
from test_coordinated_win_numeric import fixture, source_owners


class PreparationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        cls.fixture_temp=tempfile.TemporaryDirectory();cls.addClassCleanup(cls.fixture_temp.cleanup)
        root=Path(cls.fixture_temp.name);fields,_,cls.plan=fixture(root)
        request=put(root,'request.json',dict(kind='coordinated-win-prepare-request-1',sourceOwners=source_owners(),
            numerical=put(root,'environment.json',dict(constructed=True)),deadlineEpoch=time.time()+120,**fields))
        with patch.object(base.environment,'verify'):
            numeric.execute('prepare',request['path'],root/'prepared')
        cls.original_request=request

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)/'copy'
        shutil.copytree(self.fixture_temp.name,self.root)
        folder=self.root/'prepared'
        self.profile=dict(protocolSha256=self.plan.file_sha256,
            originalBundle=p.subject(self.root/'original/bundle.json'),projection=p.subject(folder/'projection.json'),
            warmStart=p.subject(folder/'warm-start.json'),initialCenter=p.subject(folder/'initial-center.json'),
            inheritedCalibration=p.subject(folder/'calibration/inherited-calibration.json'),
            directions=[p.subject(folder/f'directions/generation-{number:02d}.json') for number in range(1,17)],
            numericalPreparation=dict(request=dict(p.subject(self.root/'request.json'),originalPath=self.original_request['path']),
                finished=p.subject(folder/'finished.json')))

    def rewrite_member(self,name,value):
        path=self.root/'prepared'/name;path.write_bytes(p.canonical(value))
        finished=self.root/'prepared/finished.json';receipt=p.read(finished)
        receipt['artifacts'][name]=p.file_sha(path);finished.write_bytes(p.canonical(receipt))
        self.profile['numericalPreparation']['finished']=p.subject(finished)

    def test_relocated_preparation_binds_all_48_real_output_files(self):
        result=preparation.verify(self.plan,self.profile)
        self.assertTrue(result['passed']);self.assertEqual(result['directionTables'],16)
        self.assertEqual(result['preparationFiles'],48)

    def test_relocated_request_must_preserve_original_provenance(self):
        self.profile['numericalPreparation']['request'].pop('originalPath')
        with self.assertRaisesRegex(ValueError,'original request'):preparation.verify(self.plan,self.profile)

    def test_directions_cannot_be_reordered(self):
        rows=self.profile['directions'];rows[0],rows[1]=rows[1],rows[0]
        with self.assertRaisesRegex(ValueError,'original portable layout'):preparation.verify(self.plan,self.profile)

    def test_missing_seed_generation_receipt_is_rejected(self):
        (self.root/'prepared/directions/generation-01-generation.json').unlink()
        with self.assertRaisesRegex(ValueError,'complete'):preparation.verify(self.plan,self.profile)

    def test_self_consistent_modified_seed_receipt_still_rejects(self):
        name='directions/generation-01-generation.json';value=p.read(self.root/'prepared'/name)
        value['seed']+=1;self.rewrite_member(name,value)
        with self.assertRaisesRegex(ValueError,'prospective fresh generation'):preparation.verify(self.plan,self.profile)

    def test_profile_rehash_cannot_replace_prepared_center(self):
        path=self.root/'prepared/initial-center.json';value=p.read(path);value['coordinates'][0]+=.1
        path.write_bytes(p.canonical(value));self.profile['initialCenter']=p.subject(path)
        with self.assertRaisesRegex(ValueError,'byte-exact'):preparation.verify(self.plan,self.profile)

    def test_parent_model_proof_cannot_claim_new_calibration(self):
        name='warm-start-proof.json';value=p.read(self.root/'prepared'/name)
        value['calibrationRecomputedFromNewStates']=True;self.rewrite_member(name,value)
        with self.assertRaisesRegex(ValueError,'without reinitialization/recalibration'):preparation.verify(self.plan,self.profile)


if __name__=='__main__':unittest.main()
