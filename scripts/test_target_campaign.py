"""Bounded launch and exact-child validation must fail before any scientific execution."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import target_campaign as campaign
import prepare_expansion_target_search as prepare


class TargetCampaignTest(unittest.TestCase):
    def test_fixed_six_hour_once_sequential_campaign(self):
        spec=campaign.campaign_spec(Path('/package'),dict(python=dict(executable='/python')),{'/input':'hash'})
        self.assertEqual(spec['limits']['maximumSeconds'],21600)
        self.assertEqual([row['id'] for row in spec['stages']],['evolve','select','confirm','heldout'])
        for index,row in enumerate(spec['stages']):
            self.assertEqual(row['maximumAttempts'],1)
            self.assertEqual(row['dependsOn'],[] if index==0 else [spec['stages'][index-1]['id']])
            self.assertEqual(row['command'][1:5],['-E','-s','-S','-B'])

    def test_child_sampler_failure_prevents_preflight_receipt(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name)
            campaign.document(root/'protocol.json',{})
            with mock.patch.object(campaign,'verify_execution'),mock.patch.object(campaign.target,'validate_protocol'),mock.patch.object(campaign.controller.processes,'sample_group',return_value=[]):
                with self.assertRaisesRegex(ValueError,'external process sampler'):
                    campaign.preflight_child(root/'protocol.json',root/'run')

    def test_preflight_refuses_existing_attempt(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);(root/'run').mkdir();campaign.document(root/'protocol.json',{})
            with mock.patch.object(campaign,'verify_execution'),mock.patch.object(campaign.target,'validate_protocol'):
                with self.assertRaisesRegex(ValueError,'before any target campaign attempt'):
                    campaign.preflight_child(root/'protocol.json',root/'run')

    def test_unqualified_combination_rejected_before_artifact_reuse(self):
        documents={
            'completion-audit.json':dict(campaignSha256=prepare.COMBINATION_CAMPAIGN,protocolSha256=prepare.COMBINATION_PROTOCOL,status='completed',qualificationPassed=False),
            'campaign.json':{},'protocol.json':{},'campaign-state.json':dict(status='completed',stages=dict(confirm=dict(status='passed'),heldout=dict(status='rejected')))}
        with mock.patch.object(prepare,'read',side_effect=lambda path:documents[path.name]),mock.patch.object(prepare,'sha',side_effect=lambda path:prepare.COMBINATION_CAMPAIGN if path.name=='campaign.json' else prepare.COMBINATION_PROTOCOL),mock.patch.object(campaign.controller,'verify_completed') as verify:
            with self.assertRaisesRegex(ValueError,'both final stages passed'):prepare.verify_combination(Path('/parent'))
        verify.assert_not_called()

    def test_native_worker_allowance_uses_actual_machine_cpu_count(self):
        with tempfile.TemporaryDirectory() as name:
            protocol=dict(java=dict(executable='/java'),heapMiB=1536,workers=6,runtime='/runtime')
            with mock.patch.object(campaign.exp,'remaining_seconds',return_value=600), \
                 mock.patch.object(campaign.exp.os,'cpu_count',return_value=16), \
                 mock.patch.object(campaign.exp.subprocess,'run',return_value=mock.Mock(returncode=0)) as native:
                campaign.exp.native(protocol,'arena',Path('/source.json'),Path(name)/'native')
            self.assertEqual(native.call_args.kwargs['env']['AOR_TRAINING_MACHINE_CPUS'],'16')
            self.assertIn('-XX:ActiveProcessorCount=6',native.call_args.args[0])
            self.assertEqual(native.call_args.args[0][-2:],['--workers','6'])

    def test_changed_preflight_verifier_blocks_freeze(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name)
            campaign.document(root/'protocol-draft.json',{})
            campaign.document(root/'preflight-protocol.json',{})
            campaign.document(root/'environment.json',{})
            (root/'scripts').mkdir();(root/'scripts/target_campaign.py').write_text('changed')
            campaign.document(root/'exact-child-preflight.json',dict(passed=True,nativeGames=0,
                protocolSha256=campaign.sha(root/'preflight-protocol.json'),environmentSha256=campaign.sha(root/'environment.json'),verifierSha256='old'))
            with self.assertRaisesRegex(ValueError,'exact successful child'):campaign.freeze(root)
            self.assertFalse((root/'campaign.json').exists())


if __name__=='__main__':unittest.main()
