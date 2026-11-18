"""Real archive/bank/publication/audit joins using explicitly invented complete terminal banks."""
from pathlib import Path
import tempfile
import time
import unittest

import coordinated_win_qualification as q
import coordinated_win_qualification_audit as audit
import coordinated_win_qualification_plan as p
from test_coordinated_win_qualification import Fixture


def terminal(fixture, banks, qualified=None, stopped=False):
    now=time.time();root=fixture.root
    clock=dict(kind=p.PREFIX+'-clock-1',protocolSha256=fixture.plan.sha256,
        startedEpoch=now-100,deadlineEpoch=now-100+p.SCIENCE_SECONDS)
    q.storage.publish_json(root/'clock.json',clock)
    q.storage.publish_json(root/'worker-owner.json',dict(kind=p.PREFIX+'-worker-owner-1',pid=100,processGroup=100))
    profile=q.storage.publish_json(root/'profile.json',fixture.profile())
    q.storage.publish_json(root/'inputs.json',dict(protocol=p.subject(fixture.protocol),profile=profile))
    q.storage.publish_json(root/'terminal.json',dict(kind=p.PREFIX+'-terminal-1',host='DESKTOP',clock=clock,
        status='stopped' if stopped else 'completed',reason='mixed-confirmation-failed' if stopped else 'fixed-banks-complete',
        completedBanks=1 if stopped else 2,banks=banks,qualified=qualified is not None,
        qualification=qualified,updatedEpoch=now))


class AuditTests(unittest.TestCase):
    def test_complete_two_bank_archive_to_minimal_certificate_and_pure_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory)
            mixed=fixture.complete('mixed',positive=True)
            gate=q.publish_confirmation(fixture.plan,fixture.root,mixed)
            homogeneous=fixture.complete('homogeneous',positive=True,gate=gate)
            banks=dict(mixed=mixed,homogeneous=homogeneous)
            certificate=q.qualification(fixture.plan,fixture.root,banks)
            terminal(fixture,banks,certificate)
            result=audit.verify(p.subject(fixture.protocol),fixture.root)
            self.assertTrue(result['passed']);self.assertTrue(result['qualified'])
            self.assertEqual(result['counts'],dict(worlds=8192,games=16384,reproductions=16,terminalExecutions=16400))
            value=p.read(certificate['path'])
            self.assertEqual(set(value),set(q.certificate_fields(fixture.plan))|{'banks'})
            self.assertEqual(set(value['banks']['mixed']),{'complete','pairs','win99Passed','evidence','report'})
            self.assertNotIn('reward99Passed',value['banks']['homogeneous'])

    def test_negative_mixed_bank_stops_without_heldout_or_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory);mixed=fixture.complete('mixed')
            terminal(fixture,dict(mixed=mixed),stopped=True)
            result=audit.verify(p.subject(fixture.protocol),fixture.root)
            self.assertTrue(result['passed']);self.assertFalse(result['complete']);self.assertFalse(result['qualified'])
            self.assertEqual(result['counts']['terminalExecutions'],8200)
            self.assertFalse((fixture.root/'banks/homogeneous').exists())
            self.assertFalse((fixture.root/'qualification.json').exists())


if __name__ == '__main__':unittest.main()
