"""Full pure archived transactions built from invented scores; no native/scientific execution."""
from pathlib import Path
import tempfile
import time
import unittest

import coordinated_qualification as q
import coordinated_qualification_audit as audit
import coordinated_qualification_plan as p
from test_coordinated_qualification import Fixture, game, native_report


def end(fixture, host, banks, *, stopped=False, qualified=None):
    root = fixture.root; now = time.time()
    clock = dict(kind=p.PREFIX+'-clock-1', protocolSha256=fixture.plan.sha256,
        startedEpoch=now-100, deadlineEpoch=now-100+p.SCIENCE_SECONDS)
    q.storage.publish_json(root/'clock.json', clock)
    q.storage.publish_json(root/'worker-owner.json', dict(kind=p.PREFIX+'-worker-owner-1', pid=100, processGroup=100))
    profile = q.storage.publish_json(root/'unit-inputs/profile.json', dict(kind=p.PREFIX+'-host-1', host=host,
        protocolSha256=fixture.plan.sha256, originProof=fixture.proofs[host], **p.host_limits(host),
        files={str(fixture.protocol): p.file_sha(fixture.protocol), **p.read(fixture.proofs[host]['path'])['inputPins']}))
    q.storage.publish_json(root/'inputs.json', dict(protocol=p.subject(fixture.protocol), profile=profile))
    terminal = dict(kind=p.PREFIX+'-terminal-1', host=host, clock=clock, updatedEpoch=now,
        status='stopped' if stopped else 'completed', reason='mixed-confirmation-failed' if stopped else 'fixed-banks-complete',
        completedBanks=1 if stopped else 2, banks=banks, qualified=qualified is not None, qualification=qualified)
    return terminal


class AuditTests(unittest.TestCase):
    def test_complete_desktop_all64_archives_and_exact_application_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self)
            mixed = fixture.complete('mixed', positive=True)
            gate = q.publish_confirmation(fixture.plan, fixture.root, mixed)
            homogeneous = fixture.complete('homogeneous', positive=True, gate=gate)
            banks = dict(mixed=mixed, homogeneous=homogeneous)
            qualified = q.qualification(fixture.plan, fixture.root, banks)
            q.storage.publish_json(fixture.root/'terminal.json', end(fixture, 'DESKTOP', banks, qualified=qualified))
            report = audit.verify(p.subject(fixture.protocol), fixture.root)
            self.assertTrue(report['passed']); self.assertTrue(report['complete']); self.assertTrue(report['qualified'])
            self.assertEqual(report['counts'], dict(worlds=8192, games=16384, reproductions=16, terminalExecutions=16400))
            self.assertEqual(report['eligibleQualifiedUnit'], qualified)
            self.assertEqual(report['execution'], dict(newGames=0, newFits=0, modelForwards=0))

    def test_negative_mixed_stops_without_any_heldout_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); mixed = fixture.complete('mixed')
            q.storage.publish_json(fixture.root/'terminal.json', end(fixture, 'DESKTOP', dict(mixed=mixed), stopped=True))
            report = audit.verify(p.subject(fixture.protocol), fixture.root)
            self.assertTrue(report['passed']); self.assertFalse(report['complete']); self.assertFalse(report['qualified'])
            self.assertEqual(report['counts']['terminalExecutions'], 8200)
            self.assertFalse((fixture.root/'confirmation').exists())

    def test_negative_mixed_rejects_even_empty_heldout_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); mixed = fixture.complete('mixed')
            (fixture.root/'banks/homogeneous').mkdir()
            q.storage.publish_json(fixture.root/'terminal.json', end(fixture, 'DESKTOP', dict(mixed=mixed), stopped=True))
            with self.assertRaisesRegex(ValueError, 'prospectively opened'):
                audit.verify(p.subject(fixture.protocol), fixture.root)

    def test_mac_honest_stop_proves_only_its_local12_archives(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self)
            for chunk in p.CHUNKS['MAC']: fixture.chunk('mixed', chunk)
            ended = end(fixture, 'MAC', {}, stopped=True)
            peer = dict(ended, host='DESKTOP', banks={'mixed': {'path': '/unit-remote/complete.json', 'sha256': 'a'*64}})
            peer_subject = q.storage.publish_json(fixture.root/'peer-terminal.json', peer)
            ended.update(peerTerminal=peer_subject, originalPeerSubject=dict(path='/unit-remote/terminal.json', sha256=peer_subject['sha256']))
            q.storage.publish_json(fixture.root/'terminal.json', ended)
            report = audit.verify(p.subject(fixture.protocol), fixture.root)
            self.assertTrue(report['passed']); self.assertFalse(report['fullGlobalStatisticsRecomputed'])
            self.assertEqual(report['counts'], dict(worlds=1536, games=3072, reproductions=4, terminalExecutions=3076))

    def test_complete_mac_portable_gate_and_24_local_archives(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(Path(directory)/'mac', self)
            peer = Path(directory)/'unit-peer'; bank = peer/'banks/mixed'; bank.mkdir(parents=True)
            worlds = [dict(index=i, seat=i%4, roles=dict(CANDIDATE=game(i%4, True), CONTROL=game(i%4))) for i in range(4096)]
            q.storage.publish_json(bank/'report/report.json', native_report(worlds))
            # The peer global evidence is deliberately invented; Mac must not claim global recomputation.
            evidence = q.storage.publish_json(bank/'complete.json', dict(kind=p.PREFIX+'-bank-evidence-1',
                complete=True, bank='mixed', protocolSha256=fixture.plan.sha256, candidateBundleSha256=p.CANDIDATE,
                scienceSha256='b'*64,
                worlds=4096, games=8192, reproductions=8, scientificTerminalExecutions=8200, gatePassed=True,
                chunks=[dict(path=f'/unit-peer/chunk-{i}', sha256=f'{i:064x}') for i in range(32)],
                compact=dict(path='report-input/compact.json', sha256='c'*64),
                reportFinished=dict(path='report/finished.json', sha256='d'*64),
                report=q.relative(bank/'report/report.json', bank/'complete.json')))
            q.publish_confirmation(fixture.plan, peer, evidence)
            original = peer/'publications/homogeneous'; publication = p.read(original/'publication.json')
            target = fixture.root/'downloads/homogeneous'; target.mkdir(parents=True)
            q.copied(original/'publication.tar.gz', target/'publication.tar.gz')
            q.transport.unpack(target/'publication.tar.gz', publication, target/'content')
            q.storage.publish_json(target/'publication.json', publication)
            gate = p.subject(target/'content/gate.json')
            for name in p.BANKS:
                for chunk in p.CHUNKS['MAC']: fixture.chunk(name, chunk, gate=gate if name == 'homogeneous' else None)
            q.storage.publish_json(fixture.root/'terminal.json', end(fixture, 'MAC', {}))
            report = audit.verify(p.subject(fixture.protocol), fixture.root)
            self.assertTrue(report['passed']); self.assertTrue(report['complete'])
            self.assertFalse(report['qualified']); self.assertFalse(report['fullGlobalStatisticsRecomputed'])
            self.assertEqual(report['counts']['terminalExecutions'], 6152)

    def test_expired_original_audit_clock_cannot_be_refreshed(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self)
            ended = end(fixture, 'DESKTOP', {}, stopped=True)
            ended['updatedEpoch'] = time.time()-901
            q.storage.publish_json(fixture.root/'terminal.json', ended)
            with self.assertRaises(ValueError): audit.verify(p.subject(fixture.protocol), fixture.root)


if __name__ == '__main__': unittest.main()
