from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import coordinated_es_plan as p
import coordinated_es_handoff as h
import token_paired_handoff as legacy
import token_paired_storage as storage
import coordinated_es_storage as es_storage


class HandoffTest(unittest.TestCase):
    def test_all_original_generation_slots_are_distinct(self):
        tags={h.tag(g,c,phase=phase) for g in range(1,17) for c in p.GENERATION_CHUNKS['MAC']
            for phase in ('controls','population')}
        tags|={h.tag(None,c,True) for c in p.SCREEN_CHUNKS['MAC']}
        self.assertEqual(len(tags),102)
        for tag in tags:self.assertEqual(h.allowed_tag(tag),tag)

    def test_no_extra_generation_or_desktop_job_can_be_handed_off(self):
        for args in ((17,5),(1,0)):
            with self.subTest(args=args),self.assertRaises(ValueError):h.tag(*args,phase='controls')

    def test_controls_and_population_cannot_alias(self):
        row=dict(kind='coordinated-es-chunk-receipt-1',generation=1,chunk=5,phase='controls')
        self.assertNotEqual(h.receipt_tag(row),h.receipt_tag(dict(row,phase='population')))

    def test_legacy_stage_contract_rejects_new_generation(self):
        with self.assertRaisesRegex(ValueError,'fixed published'):legacy.stage('/unused','population-1')

    def test_new_stage_can_publish_through_shared_archive_owner(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'constructed.json';source.write_text('{"noModel":true}')
            result=h.bundle(root/'publication','population-16',{'descriptor.json':p.subject(source)},
                dict(generation=16))
            legacy.unpack(root/'publication/publication.tar.gz',result,root/'unpacked')
            self.assertEqual((root/'unpacked/descriptor.json').read_bytes(),source.read_bytes())

    def test_existing_publication_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'constructed.json';source.write_text('{}')
            h.bundle(root/'publication','controls-1',{'descriptor.json':p.subject(source)},{})
            with self.assertRaises(FileExistsError):
                h.bundle(root/'publication','controls-1',{'descriptor.json':p.subject(source)},{})

    def test_failed_worker_ends_probe_before_ready_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            storage.publish_json(root/'terminal.json',dict(kind=h.PREFIX+'-terminal-1',status='failed'))
            storage.publish_json(root/'publications/population-1/publication.json',
                dict(kind=h.PREFIX+'-publication-1',stage='population-1'))
            self.assertEqual(h.stage(root,'population-1')['status'],'terminal')

    def test_absent_generation_cannot_renew_shared_clock(self):
        now=[86399.];calls=[]
        def runner(command,**kwargs):
            calls.append(command);return subprocess.CompletedProcess(command,0,b'{"status":"absent"}',b'')
        client=h.Outbound(dict(address=h.ADDRESS,root='/constructed/root',python='/constructed/python',
            script='/constructed/handoff'),p.Clock(0.,86400.),'/unused',runner=runner,
            now=lambda:now[0],sleep=lambda seconds:now.__setitem__(0,now[0]+seconds))
        with self.assertRaisesRegex(ValueError,'expired'):client.await_stage('population-16')
        self.assertEqual(len(calls),1)

    def test_fixed_prefix_stop_propagates_without_model_choice(self):
        value=dict(kind=h.PREFIX+'-terminal-1',host='DESKTOP',status='stopped',
            reason='prefix-resource-gate',completedGenerations=2)
        self.assertEqual(h.PeerStopped(dict(terminal=value)).response['terminal'],value)

    def test_live_phase_movement_is_not_an_operational_stop(self):
        value=dict(kind=h.PREFIX+'-terminal-1',host='DESKTOP',status='stopped',
            reason='token-did-not-change',completedGenerations=2)
        with self.assertRaisesRegex(ValueError,'operational stop'):h.PeerStopped(dict(terminal=value))

    def test_prefix_stop_at_a_later_generation_is_rejected(self):
        value=dict(kind=h.PREFIX+'-terminal-1',host='DESKTOP',status='stopped',
            reason='prefix-resource-gate',completedGenerations=15)
        with self.assertRaisesRegex(ValueError,'original fixed boundary'):h.PeerStopped(dict(terminal=value))

    def test_expired_receiver_does_not_verify_archive(self):
        with tempfile.TemporaryDirectory() as folder:
            storage.publish_json(Path(folder)/'clock.json',dict(kind=h.PREFIX+'-clock-1',
                startedEpoch=0.,deadlineEpoch=86400.))
            with patch.object(legacy.time,'time',return_value=86401.),patch.object(h,'accept') as accept:
                with self.assertRaisesRegex(ValueError,'expired'):h.serve('accept',folder,'controls-1-chunk-5')
                accept.assert_not_called()

    def resource_fixture(self,root):
        storage.publish_json(root/'clock.json',dict(protocolSha256='1'*64))
        for phase in ('controls','population'):
            for chunk in p.GENERATION_CHUNKS['MAC']:
                storage.publish_json(root/'incoming'/h.tag(1,chunk,phase=phase)/'accepted.json',{})
        return dict(kind=h.PREFIX+'-retention-1',host='MAC',generation=1,protocolSha256='1'*64,
            sharedBaseBytes=7,generationBytes={str(g):9 if g==1 else 0 for g in range(1,17)},
            totalBytes=16,regularFiles=2)

    def test_prefix_bytes_require_all_mac_transfers(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(h,'health',return_value={'status':'active'}):
            root=Path(folder);row=self.resource_fixture(root)
            (root/'incoming/population-1-chunk-7/accepted.json').unlink()
            with self.assertRaisesRegex(ValueError,'all of its original'):
                h.receive_retention(root,__import__('json').dumps(row))

    def test_prefix_bytes_are_published_once(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(h,'health',return_value={'status':'active'}):
            root=Path(folder);row=self.resource_fixture(root);wire=__import__('json').dumps(row)
            result=h.receive_retention(root,wire)
            self.assertEqual(p.read(result['path']),row)
            with self.assertRaisesRegex(ValueError,'repeated'):h.receive_retention(root,wire)

    def test_resource_channel_rejects_outcome_fields(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(h,'health',return_value={'status':'active'}):
            root=Path(folder);row=self.resource_fixture(root);row['reward']=.9
            with self.assertRaisesRegex(ValueError,'original host'):
                h.receive_retention(root,__import__('json').dumps(row))

    def test_late_resource_report_propagates_original_numerical_stop(self):
        terminal=dict(kind=h.PREFIX+'-terminal-1',host='DESKTOP',status='stopped',
            reason='zero-gradient',completedGenerations=0)
        result=dict(status='terminal',terminal=terminal,terminalSubject={'path':'/original','sha256':'a'*64})
        with tempfile.TemporaryDirectory() as folder,patch.object(h,'health',return_value=result):
            root=Path(folder);row=self.resource_fixture(root)
            self.assertEqual(h.receive_retention(root,__import__('json').dumps(row)),result)
        client=h.Outbound(dict(address=h.ADDRESS,root='/constructed/root',python='/constructed/python',
            script='/constructed/handoff'),p.Clock(0.,86400.),'/unused')
        with patch.object(client,'remote',return_value=result),self.assertRaises(h.PeerStopped):
            client.push_retention(row)


if __name__=='__main__':unittest.main()
