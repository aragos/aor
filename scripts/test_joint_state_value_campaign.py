"""Bounded driver and legacy environment compatibility; no corpus, CUDA work or fits."""
from dataclasses import asdict
from pathlib import Path
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

import joint_state_value_campaign as campaign
import joint_state_value_data as data
import purchase_value_campaign as legacy


def fixture():
    banks = {name: dict(seeds=seeds) for name,seeds in dict(warmInitialization=[52001001],
        freshInitialization=[52001002], epochOrder=list(range(52001003,52001023)),
        bootstrap=[52001023]).items()}
    names = [f'input-{index:04d}' for index in range(3556)]
    dictionary = dict(version=data.VERSION, stateFeatureNames=sorted(
        ['purchase.'+name for name in names]+[f'card.input-{index:04d}' for index in range(3685)]),
        units='unchanged-existing-phase-encoder-units', missingValue=0.)
    protocol = dict(kind=campaign.KIND, maximumSeconds=3600, maximumAttempts=1, cpuThreads=1,
        maximumRssBytes=6*campaign.GIB, maximumDataBytes=8*campaign.GIB,
        minimumFreeBytes=8*campaign.GIB, minimumGpuFreeBytes=4*campaign.GIB, maximumFits=2,
        newGames=0, actorTraining=False, qualificationPassed=False, productionActivation=False,
        automaticNextCampaign=False, automaticResume=False, seedAudit='seeds', banks=banks,
        fitConfig=asdict(data.config(banks)), purchaseDictionary='purchase', jointDictionary='joint',
        critic='critic', warmParity='parity', gpuEnvironment='environment')
    files = dict(seeds=dict(allIntersectionsZero=True,seedBanks=banks), purchase=names,
        joint=dictionary, parity=dict(passed=True,newGames=0,actualFits=0,
            criticSha256=campaign.REFRESHED,jointDictionarySha256='joint-digest',
            purchaseDictionarySha256='purchase-digest',
            actualTrainingStates=dict(rows=144,maximumAbsoluteError=0.),
            validationOrTestStatesUsed=False,warmSeed=52001001),
        environment=dict(cpuThreads=1,variables=campaign.VARIABLES))
    return protocol, files


class DriverTest(unittest.TestCase):
    def validate(self, protocol, files):
        hashes = {'critic':campaign.REFRESHED,'seeds':campaign.SEED_AUDIT,
                  'joint':'joint-digest','purchase':'purchase-digest'}
        with patch.object(data, 'read', side_effect=lambda path:files[str(path)]), \
                patch.object(data, 'sha', side_effect=lambda path:hashes[str(path)]):
            return campaign.validate_inputs(protocol, corpus_required=False)

    def test_synthetic_preflight_validation_does_not_open_corpus(self):
        protocol, files = fixture()
        self.assertIsNone(self.validate(protocol,files))

    def test_one_thread_is_required_even_if_other_settings_match(self):
        protocol, files = fixture(); protocol['cpuThreads']=2
        with self.assertRaisesRegex(ValueError,'finite two-critic'): self.validate(protocol,files)

    def test_scientific_fits_cannot_expand_to_a_model_sweep(self):
        protocol, files = fixture(); protocol['maximumFits']=3
        with self.assertRaisesRegex(ValueError,'finite two-critic'): self.validate(protocol,files)

    def test_heldout_warm_parity_inputs_are_rejected(self):
        protocol, files = fixture(); files['parity']['validationOrTestStatesUsed']=True
        with self.assertRaisesRegex(ValueError,'training-only'): self.validate(protocol,files)

    def test_parity_cannot_be_reused_for_an_unrelated_dictionary(self):
        protocol, files = fixture(); files['parity']['jointDictionarySha256']='another-dictionary'
        with self.assertRaisesRegex(ValueError,'training-only'): self.validate(protocol,files)

    def test_corpus_requires_its_completed_identity(self):
        protocol, files = fixture()
        with patch.object(data,'read',side_effect=lambda path:files[str(path)]), \
                patch.object(data,'sha',side_effect=lambda path:{'critic':campaign.REFRESHED,
                    'seeds':campaign.SEED_AUDIT,'joint':'joint-digest','purchase':'purchase-digest'}[str(path)]):
            with self.assertRaises(KeyError): campaign.validate_inputs(protocol,corpus_required=True)

    def test_sparse_payload_estimate_counts_all_eight_arrays_and_empty_worlds(self):
        corpus=dict(nonzeros=100,rows=20,games=[{}, {}, {}])
        self.assertEqual(10*100+13*20+24*3+8+1024**2,campaign.estimated_cache_bytes(corpus))

    def test_result_artifacts_exclude_live_supervisor_and_log_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('cache/finished.json','fits/report.json','campaign-state.json','stdout.log'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('{}')
            self.assertEqual({'cache/finished.json','fits/report.json'},
                {str(path.relative_to(root)) for path in campaign.completed_artifacts(root)})


class EnvironmentTest(unittest.TestCase):
    def verify(self, variables, *, explicit):
        import numpy as np
        import torch
        expected=dict(executable=str(Path(sys.executable).absolute()),prefix=str(Path(sys.prefix).resolve()),
            python=sys.version,torch=torch.__version__,numpy=np.__version__,cuda=torch.version.cuda,
            gpu='fixture',capability=[8,9],files={},treeRoots=[],driver='fixture')
        with patch.dict(os.environ,variables,clear=True), \
                patch.object(torch.cuda,'is_available',return_value=True), \
                patch.object(torch.cuda,'get_device_name',return_value='fixture'), \
                patch.object(torch.cuda,'get_device_capability',return_value=(8,9)), \
                patch.object(legacy.subprocess,'check_output',return_value='fixture'):
            legacy.verify_environment(expected, **({'variables':variables} if explicit else {}))

    def test_omitted_override_preserves_original_two_thread_environment(self):
        self.verify(legacy.VARIABLES,explicit=False)

    def test_joint_explicit_environment_accepts_one_thread(self):
        self.verify(campaign.VARIABLES,explicit=True)

    def test_legacy_default_does_not_silently_admit_one_thread(self):
        with self.assertRaisesRegex(ValueError,'thread environment'):
            self.verify(campaign.VARIABLES,explicit=False)


if __name__ == '__main__': unittest.main()
