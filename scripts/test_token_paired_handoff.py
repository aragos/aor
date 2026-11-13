from pathlib import Path
import subprocess
import tempfile
import unittest

import token_paired_campaign_plan as p
import token_paired_handoff as h
import token_paired_storage as s


PROFILE=dict(address=h.ADDRESS,root='/reviewed/research',python='/reviewed/python',script='/reviewed/handoff.py')


class HandoffTests(unittest.TestCase):
    def test_fixed_handoffs_have_no_desktop_or_extra_slot(self):
        self.assertEqual(len({h.tag(i,c) for i in (1,2) for c in p.COLLECTION_CHUNKS['MAC']}),8)
        with self.assertRaisesRegex(ValueError,'Mac-owned'):h.tag(1,0)
        with self.assertRaisesRegex(ValueError,'eleven'):h.allowed_tag('iteration-3-chunk-5')

    def test_transport_failure_is_not_retried(self):
        calls=[]
        def run(command,**kwargs):
            calls.append(command);return subprocess.CompletedProcess(command,255,b'',b'no peer')
        client=h.Outbound(PROFILE,p.Clock(0.,21600.),'/unused',runner=run,now=lambda:1.)
        with self.assertRaisesRegex(ValueError,'no network'):client.remote('probe','screen')
        self.assertEqual(len(calls),1)

    def test_expired_clock_prevents_any_ssh_call(self):
        client=h.Outbound(PROFILE,p.Clock(0.,21600.),'/unused',runner=lambda *_args,**_kwargs:self.fail(),
            now=lambda:21600.)
        with self.assertRaisesRegex(ValueError,'expired'):client.remote('probe','screen')

    def test_absent_stage_wait_is_bounded_by_original_clock(self):
        now=[21595.];calls=[]
        def run(command,**kwargs):
            calls.append(command);return subprocess.CompletedProcess(command,0,b'{"status":"absent"}',b'')
        client=h.Outbound(PROFILE,p.Clock(0.,21600.),'/unused',runner=run,now=lambda:now[0],
            sleep=lambda seconds:now.__setitem__(0,now[0]+seconds))
        with self.assertRaisesRegex(ValueError,'expired'):client.await_stage('screen')
        self.assertEqual(len(calls),1)

    def test_peer_terminal_ends_worker_without_stage(self):
        client=h.Outbound(PROFILE,p.Clock(0.,21600.),'/unused',now=lambda:1.,
            runner=lambda command,**kwargs:subprocess.CompletedProcess(command,0,b'{"status":"terminal"}',b''))
        with self.assertRaisesRegex(ValueError,'Coordinator ended'):client.await_stage('screen')

    def test_only_explicit_research_endpoint_is_accepted(self):
        with self.assertRaisesRegex(ValueError,'predeclared'):
            h.Outbound(dict(PROFILE,address='elsewhere'),p.Clock(0.,21600.),'/unused')

    def test_readonly_stage_probe_sees_failure_before_existing_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            s.publish_json(root/'publications/screen/publication.json',dict(kind='token-paired-publication-1',stage='screen'))
            s.publish_json(root/'terminal.json',dict(kind='token-paired-terminal-1',status='failed'))
            self.assertEqual(h.stage(root,'screen')['status'],'terminal')

    def test_reservation_is_once_only(self):
        with tempfile.TemporaryDirectory() as directory:
            h.reserve(directory,'iteration-1-chunk-5')
            with self.assertRaises(FileExistsError):h.reserve(directory,'iteration-1-chunk-5')

    def test_portable_publication_is_byte_exact_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'constructed.json';source.write_text('{"weights":[]}')
            publication=h.bundle(root/'publication','iteration-2',{'actor/policy.json':p.subject(source)},
                dict(scopeSha256='a'*64))
            h.unpack(root/'publication/publication.tar.gz',publication,root/'download')
            self.assertEqual((root/'download/actor/policy.json').read_bytes(),source.read_bytes())
            with self.assertRaises(FileExistsError):
                h.unpack(root/'publication/publication.tar.gz',publication,root/'download')

    def test_bad_member_cannot_escape_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'constructed.json';source.write_text('{}')
            with self.assertRaisesRegex(ValueError,'relative'):
                h.bundle(root/'publication','screen',{'../outside':p.subject(source)},{})


if __name__=='__main__':unittest.main()
