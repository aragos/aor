from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import cpe_paired_campaign_plan as p
import cpe_paired_handoff as h
import token_paired_handoff as legacy
import token_paired_storage as storage


class HandoffTests(unittest.TestCase):
    def test_each_phase_and_iteration_has_separate_fixed_slot(self):
        slots={h.tag(i,c,head=head) for i in (1,2) for head in p.HEADS
            for c in p.COLLECTION_CHUNKS['MAC']}
        self.assertEqual(len(slots),24)
        for slot in slots:self.assertEqual(h.allowed_tag(slot),slot)
        with self.assertRaises(ValueError):legacy.allowed_tag(next(iter(slots)))
        with self.assertRaises(ValueError):h.allowed_tag('iteration-1-chunk-5')

    def test_missing_head_cannot_alias_collection_or_screen(self):
        with self.assertRaisesRegex(ValueError,'explicit'):h.tag(1,5)
        with self.assertRaisesRegex(ValueError,'no cohort'):h.tag(None,5,True,head='CARD')

    def test_receipt_head_must_match_mailbox(self):
        row=dict(kind='cpe-paired-chunk-receipt-1',iteration=1,head='CARD',chunk=5)
        self.assertEqual(h.receipt_tag(row),'iteration-1-CARD-chunk-5')
        self.assertNotEqual(h.receipt_tag(dict(row,head='PURCHASE')),h.receipt_tag(row))

    def test_wrong_family_terminal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            storage.publish_json(Path(directory)/'terminal.json',
                dict(kind='token-paired-terminal-1',status='completed'))
            with self.assertRaisesRegex(ValueError,'identity'):h.terminal(directory)

    def test_same_bounded_transport_uses_eighteen_hour_clock(self):
        with tempfile.TemporaryDirectory() as directory:
            storage.publish_json(Path(directory)/'clock.json',dict(kind='cpe-paired-clock-1',
                startedEpoch=0.,deadlineEpoch=64800.))
            with patch.object(legacy.time,'time',return_value=64795.),\
                 patch.object(legacy.signal,'signal'),patch.object(legacy.signal,'setitimer') as timer,\
                 patch.object(h,'accept',return_value={'constructed':True}):
                self.assertEqual(h.serve('accept',directory,'iteration-1-CARD-chunk-5'),{'constructed':True})
                self.assertEqual([call.args[1] for call in timer.call_args_list],[5.,0])

    def test_cpe_peer_stop_is_admitted_but_legacy_stop_is_not(self):
        terminal=dict(kind='cpe-paired-terminal-1',host='DESKTOP',status='stopped',
            reason='public-exposure-gate',completedIterations=1)
        self.assertEqual(h.PeerStopped({'terminal':terminal}).response['terminal'],terminal)
        with self.assertRaisesRegex(ValueError,'exact coordinator'):
            h.PeerStopped({'terminal':dict(terminal,kind='token-paired-terminal-1')})

    def test_absent_peer_does_not_renew_clock_or_retry_transport(self):
        now=[64799.];calls=[]
        def run(command,**options):
            calls.append(options['timeout'])
            return subprocess.CompletedProcess(command,0,b'{"status":"absent"}',b'')
        client=h.Outbound(dict(address=h.ADDRESS,root='/constructed/root',python='/constructed/python',
            script='/constructed/helper'),p.Clock(0.,64800.),'/unused',runner=run,now=lambda:now[0],
            sleep=lambda seconds:now.__setitem__(0,now[0]+seconds))
        with self.assertRaisesRegex(ValueError,'expired'):client.await_active('a'*64)
        self.assertEqual(calls,[1.])

    def test_cpe_publication_keeps_exact_member_bytes_and_new_kind(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'bundle.json';source.write_text('{"constructed":true}')
            result=h.bundle(root/'published','iteration-2',{'bundle.json':p.subject(source)}, {})
            self.assertEqual(result['kind'],'cpe-paired-publication-1')
            legacy.unpack(root/'published/publication.tar.gz',result,root/'download')
            self.assertEqual((root/'download/bundle.json').read_bytes(),source.read_bytes())


if __name__=='__main__':unittest.main()
