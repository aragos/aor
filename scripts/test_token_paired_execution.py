from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import sys

import token_paired_campaign_plan as p
import token_paired_execution as execution
import token_paired_storage as storage
from test_token_paired_campaign_plan import fixture


class ExecutionTests(unittest.TestCase):
    def package(self, root, host='DESKTOP'):
        protocol = root/'protocol.json'; profile = root/'profile.json'; clock = root/'clock.json'
        value = fixture(); storage.publish_json(protocol,value)
        storage.publish_json(profile,dict(kind='token-paired-host-1',host=host,
            protocolSha256=p.file_sha(protocol),files={},python={'executable':'/constructed/python'},
            **value['hosts'][host]))
        storage.publish_json(clock,dict(kind='token-paired-clock-1',protocolSha256=p.file_sha(protocol),
            startedEpoch=100.,deadlineEpoch=21700.))
        return protocol,profile,clock

    def test_both_host_specs_share_absolute_deadline_and_one_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            for name in p.HOSTS:
                host=root/name;host.mkdir();protocol,profile,clock=self.package(host,name)
                spec=execution.specification(protocol,profile,clock)
                execution.controller.validate_spec(spec,root/'run')
                self.assertEqual(spec['limits']['absoluteDeadlineEpoch'],21700.)
                self.assertEqual(len(spec['stages']),1)
                self.assertEqual(spec['stages'][0]['maximumAttempts'],1)
                self.assertEqual(spec['stages'][0]['absoluteDeadlineEpoch'],21700.)
                self.assertNotIn('--resume',spec['stages'][0]['command'])

    def test_host_resource_override_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol,profile,clock=self.package(Path(directory))
            value=p.read(profile);value['maximumRssBytes']+=1;profile.write_bytes(p.canonical(value))
            with self.assertRaisesRegex(ValueError,'resource'):
                execution.specification(protocol,profile,clock)

    def test_no_existing_output_can_restart_worker(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            with patch.object(execution.worker,'check_profile',side_effect=AssertionError('No input read')):
                with self.assertRaisesRegex(ValueError,'one new output'):
                    execution.host(root/'protocol',root/'profile',root/'clock',root)

    def test_operational_stop_is_controller_rejection_without_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            def stopped(*args):
                storage.publish_json(root/'study/terminal.json',{'status':'stopped'})
                return {'status':'stopped'}
            with patch.object(execution.worker,'run',stopped):
                receipt=execution.stage(root/'protocol',root/'profile',root/'clock',root)
            self.assertEqual(receipt['status'],'rejected')
            self.assertNotIn('metrics',receipt)
            self.assertEqual(execution.controller.validate_result(root/'stage-result.json',root),receipt)

    def test_failed_worker_cannot_publish_completed_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            with patch.object(execution.worker,'run',side_effect=ValueError('Constructed failure')):
                with self.assertRaisesRegex(ValueError,'Constructed failure'):
                    execution.stage(root/'protocol',root/'profile',root/'clock',root)
            self.assertFalse((root/'stage-result.json').exists())

    def test_modified_clock_binding_is_rejected_before_stage_construction(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol,profile,clock=self.package(Path(directory))
            value=p.read(clock);value['protocolSha256']='0'*64;clock.write_bytes(p.canonical(value))
            with self.assertRaisesRegex(ValueError,'shared protocol'):
                execution.specification(protocol,profile,clock)

    def terminal(self, root):
        protocol,profile,clock=self.package(root)
        output=root/'execution';run=output/'run';run.mkdir(parents=True)
        storage.publish_json(output/'campaign.json',execution.specification(protocol,profile,clock))
        (run/'campaign-spec.json').write_bytes((output/'campaign.json').read_bytes())
        storage.publish_json(run/'study/clock.json',p.read(clock))
        storage.publish_json(run/'study/terminal.json',{'status':'stopped'})
        result=dict(schemaVersion=1,status='rejected',summary='Constructed stop',
            artifacts={'study/terminal.json':p.file_sha(run/'study/terminal.json')})
        storage.publish_json(run/'stage-result.json',result)
        manifest=dict(identity={'sourceSha256':p.file_sha(output/'campaign.json')},
            startedEpoch=110.,deadlineEpoch=21700.)
        storage.publish_json(run/'campaign-manifest.json',manifest)
        state=dict(status='completed',startedEpoch=110.,deadlineEpoch=21700.,updatedEpoch=200.,
            manifestSha256=p.file_sha(run/'campaign-manifest.json'),stages={'token-paired':dict(
                attempts=1,status='rejected',exitCode=0,deadlineEpoch=21700.,result='stage-result.json',
                resultSha256=p.file_sha(run/'stage-result.json'))})
        storage.publish_json(run/'campaign-state.json',state)
        return protocol,output

    def test_original_controller_manifest_and_copy_are_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol,output=self.terminal(Path(directory).resolve())
            audit=SimpleNamespace(verify=lambda *_:dict(passed=True,status='stopped'))
            with patch.dict(sys.modules,{'token_paired_campaign_audit':audit}):
                self.assertEqual(set(execution.verify(protocol,output)['controllerSubjects']),
                    {'specification','state','manifest','copiedSpecification','stageResult'})
            (output/'run/campaign-spec.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'original manifest'):
                execution.verify(protocol,output)

    def test_changed_controller_manifest_fails_before_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol,output=self.terminal(Path(directory).resolve())
            (output/'run/campaign-manifest.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'original manifest'):
                execution.verify(protocol,output)

    def test_accepted_stage_cannot_omit_its_terminal_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol,output=self.terminal(Path(directory).resolve());run=output/'run'
            result=p.read(run/'stage-result.json');result['artifacts']={}
            (run/'stage-result.json').write_bytes(p.canonical(result))
            state=p.read(run/'campaign-state.json')
            state['stages']['token-paired']['resultSha256']=p.file_sha(run/'stage-result.json')
            (run/'campaign-state.json').write_bytes(p.canonical(state))
            with self.assertRaisesRegex(ValueError,'exactly its original terminal'):
                execution.verify(protocol,output)


if __name__=='__main__': unittest.main()
