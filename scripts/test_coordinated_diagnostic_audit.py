"""Full constructed diagnostic closure, separately labeled from native execution evidence."""
from pathlib import Path
import tempfile
import time
import unittest

import coordinated_diagnostic as q
import coordinated_diagnostic_plan as p
import coordinated_diagnostic_audit as audit
from test_coordinated_diagnostic import Fixture


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.fixture = Fixture(self.temp.name, self)

    def terminal(self, host, *, complete=True):
        fixture = self.fixture; root = fixture.root
        start = time.time()-10; deadline = start+14400
        clock = dict(kind=p.PREFIX+'-clock-1', protocolSha256=fixture.plan.sha256,
            startedEpoch=start, deadlineEpoch=deadline)
        q.storage.publish_json(root/'clock.json', clock)
        q.storage.publish_json(root/'worker-owner.json', dict(kind=p.PREFIX+'-worker-owner-1', pid=111, processGroup=111))
        profile = dict(kind=p.PREFIX+'-host-1', host=host, protocolSha256=fixture.plan.sha256,
            **p.host_limits(host), originProof=fixture.proofs[host],
            files={str(path): p.file_sha(path) for path in fixture.inputs.iterdir() if path.is_file()})
        path = root/'profile.json'; q.storage.publish_json(path, profile)
        q.storage.publish_json(root/'inputs.json', dict(protocol=p.subject(fixture.protocol), profile=p.subject(path)))
        cases = {}
        if complete:
            for selected in p.CASES:
                if host == 'DESKTOP':
                    local = fixture.chunk(selected, 0); remote = fixture.chunk(selected, 1, remote=True)
                    cases[selected.identity] = q.complete_case(fixture.plan,
                        dict(host=host, _clock=p.Clock(start, deadline)), root, selected, [local, remote])
                else: fixture.chunk(selected, 1)
        report = q.complete_report(fixture.plan, root, cases, deadline) if complete and host == 'DESKTOP' else None
        ended = dict(kind=p.PREFIX+'-terminal-1', host=host, clock=clock, updatedEpoch=time.time(),
            status='completed' if complete else 'failed', reason='fixed-diagnostic-complete' if complete else 'UNIT failure',
            completedCases=32 if complete else 0, cases=cases, report=report,
            diagnosticOnly=True, promotionAllowed=False, qualified=False, nominee=None)
        q.storage.publish_json(root/'terminal.json', ended)
        return ended

    def test_full_desktop64_archives_recompute_only_fixed_nonpromoting_diagnostics(self):
        self.terminal('DESKTOP')
        value = audit.verify(p.subject(self.fixture.protocol), self.fixture.root)
        self.assertTrue(value['passed']); self.assertTrue(value['fullGlobalStatisticsRecomputed'])
        self.assertEqual(value['counts'], p.counts())
        self.assertEqual(sum(path.endswith('/evidence.tar.gz') for path in value['artifactPins']), 64)
        self.assertFalse(value['qualified']); self.assertIsNone(value['nominee'])

    def test_full_mac32_archives_attest_only_own12320_executions(self):
        self.terminal('MAC')
        value = audit.verify(p.subject(self.fixture.protocol), self.fixture.root)
        self.assertTrue(value['passed']); self.assertFalse(value['fullGlobalStatisticsRecomputed'])
        self.assertEqual(value['counts'], p.counts('MAC'))
        self.assertEqual(sum(path.endswith('/evidence.tar.gz') for path in value['artifactPins']), 32)

    def test_failure_is_not_honest_quality_stop_or_completed_diagnostic(self):
        self.terminal('DESKTOP', complete=False)
        value = audit.verify(p.subject(self.fixture.protocol), self.fixture.root)
        self.assertFalse(value['passed']); self.assertFalse(value['complete'])
        self.assertEqual(value['counts'], {})

    def test_nomination_flag_rejected_before_effects(self):
        value = self.terminal('DESKTOP', complete=False); value['nominee'] = dict(path='UNIT', sha256='a'*64)
        (self.fixture.root/'terminal.json').write_bytes(p.canonical(value)+b'\n')
        with self.assertRaisesRegex(ValueError, 'selection or qualification'):
            audit.verify(p.subject(self.fixture.protocol), self.fixture.root)


if __name__ == '__main__': unittest.main()
