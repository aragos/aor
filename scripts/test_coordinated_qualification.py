"""Invented terminal/archive fixtures. No native game, scientific actor, or statistical claim."""
import copy
import gzip
import hashlib
import math
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import coordinated_qualification as q
import coordinated_qualification_plan as p
from token_paired_campaign_audit import Pins
from test_coordinated_qualification_plan import protocol_record


def game(seat, candidate=False):
    scores = [dict(player=f'p{i+1}', cash=20 if candidate and i == seat else 10,
        advancePoints=0, misery=0, total=20 if candidate and i == seat else 10,
        winner=(i == seat if candidate else True)) for i in range(4)]
    wins = [float(i == seat) if candidate else .25 for i in range(4)]
    ranks = [1. if i == seat else 1/3 for i in range(4)] if candidate else [.5]*4
    return dict(wins=wins, ranks=ranks, rewards=[.8*w+.2*r for w, r in zip(wins, ranks)], scores=scores, decisions=1)


def native_report(worlds):
    """Constructed report serializer; production verify_report independently reaggregates these values."""
    metrics = {}
    for metric in ('wins', 'rewards', 'ranks', 'chaos'):
        values = [0. if metric == 'chaos' else world['roles']['CANDIDATE'][metric][world['seat']]-
            world['roles']['CONTROL'][metric][world['seat']] for world in worlds]
        mean = sum(values)/len(values)
        error = math.sqrt(sum((value-mean)**2 for value in values)/(len(values)-1)/len(values))
        # Match the existing native pilotInterval→SE99 conversion, including its rounding.
        error = ((mean+1.96*error)-mean)/1.96
        metrics[metric] = dict(count=len(values), mean=mean, standardError=error,
            lower99=mean-p.MULTIPLIER*error, upper99=mean+p.MULTIPLIER*error)
    gates = {name: metrics[name]['lower99'] > 0 for name in ('wins', 'rewards')}
    return dict(kind=p.PREFIX+'-screen-report-1', worlds=len(worlds), confidence=.99,
        intervalMultiplier=p.MULTIPLIER, candidateMinusControl=metrics,
        gates=dict(gates, passed=all(gates.values())), qualification=False, activated=False)


class Fixture:
    """All bytes are newly invented metadata/scores; scientific-original admission is not exercised."""
    def __init__(self, root, test):
        self.root = Path(root); self.root.mkdir(parents=True, exist_ok=True)
        self.inputs = self.root/'unit-inputs'; self.inputs.mkdir()
        self.origin = {host: q.storage.publish_json(self.inputs/f'origin-{host}.json', dict(unit=host)) for host in p.HOSTS}
        self.admission = q.storage.publish_json(self.inputs/'admission.json', dict(unit='original-admission'))
        self.historical = q.storage.publish_json(self.inputs/'historical.json', dict(unit='hash-only-history'))
        self.arena = {bank: q.storage.publish_json(self.inputs/f'{bank}-arena.json', dict(unit=bank)) for bank in p.BANKS}
        record = protocol_record()
        subject_names = ('protocol', 'originalBundle', 'projection', 'calibration', 'center', 'finalBundle',
            'centerMaterialization', 'generationAudit', 'historical')
        proof_subjects = {name: q.storage.publish_json(self.inputs/f'proof-{name}.json', dict(unit=name)) for name in subject_names}
        original_pin = p.CANDIDATE
        override = patch.object(p, 'CANDIDATE', proof_subjects['finalBundle']['sha256'])
        override.start()
        def restore():
            override.stop()
            test.assertEqual(p.CANDIDATE, original_pin)
        test.addCleanup(restore)
        runtime = {f'unit-{i}.jar': q.storage.publish_json(self.inputs/f'unit-{i}.jar', dict(unit=i)) for i in range(9)}
        record.update(originalBundleSha256=proof_subjects['originalBundle']['sha256'],
            calibrationSha256=proof_subjects['calibration']['sha256'], candidateBundleSha256=p.CANDIDATE)
        self.proofs = {}
        for host in p.HOSTS:
            pins = {item['path']: item['sha256'] for item in
                (self.admission, self.origin[host], *runtime.values(), *proof_subjects.values())}
            self.proofs[host] = q.storage.publish_json(self.inputs/f'origin-proof-{host}.json',
                dict(kind=p.PREFIX+'-origin-proof-1', host=host, passed=True, complete=True,
                    admission=self.admission, arena=self.origin[host], runtime=runtime,
                    scopeIdentity={}, scopeSha256='a'*64, ecologySha256='b'*64,
                    subjects=proof_subjects, inputPins=pins, execution=dict(newGames=0, policyCalls=0, actualFits=0)))
        record.update(originArenaSha256={host: item['sha256'] for host, item in self.origin.items()},
            originProofSha256={host: item['sha256'] for host, item in self.proofs.items()},
            originAdmissionSha256=self.admission['sha256'])
        self.protocol = self.inputs/'protocol.json'; q.storage.publish_json(self.protocol, record)
        self.plan = p.Plan.from_file(self.protocol)

    def binding(self, candidate):
        value = dict(version='coordinated-support-arena-1', supportProfile=p.SUPPORT, supportRoles='ALL_SEATS_CP',
            originalBundle=dict(sha256=self.plan.record['originalBundleSha256']),
            execution='CALIBRATED' if candidate else 'ZERO_REFERENCE',
            roles={'CANDIDATE': dict(sha256=p.CANDIDATE)} if candidate else {})
        if candidate: value['calibration'] = dict(sha256=self.plan.record['calibrationSha256'])
        return dict(coordinatedEs=value)

    def chunk(self, bank, chunk, *, positive=False, gate=None, remote=False):
        host = p.owner(chunk)
        directory = self.root/('incoming/'+q.tag(bank, chunk) if remote else f'banks/{bank}/chunks/chunk-{chunk}')
        directory.mkdir(parents=True)
        output = directory/'native'; output.mkdir()
        subjects = dict(protocol=p.subject(self.protocol), originArena=self.origin[host], originProof=self.proofs[host],
            originAdmission=self.admission, arena=self.arena[bank], confirmation=gate)
        source = directory/'source.json'; q.storage.publish_json(source, self.plan.source(bank, chunk, subjects))
        snapshots = dict(source=(p.subject(source), 'study-source.json'), protocol=(p.subject(self.protocol), 'protocol.json'),
            originArena=(self.origin[host], 'origin-arena.json'), arena=(self.arena[bank], 'arena-source.json'),
            originProof=(self.proofs[host], 'origin-proof.json'),
            admission=(self.admission, 'admission.json'), historicalAdmission=(self.historical, 'historical-admission.json'))
        if gate is not None: snapshots['confirmation'] = (gate, 'confirmation.json')
        for item, name in snapshots.values(): (output/name).write_bytes(Path(item['path']).read_bytes())
        identity = dict(kind=q.KIND, protocolSha256=self.plan.sha256,
            originAdmissionSha256=self.admission['sha256'], originScopeSha256='a'*64, originEcologySha256='b'*64,
            ecologySha256='c'*64, ecology={}, bankName=bank, profile=p.PROFILE, supportProfile=p.SUPPORT,
            **{key: self.plan.record[key] for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256')},
            seeds=self.plan.record['banks'][bank]['seeds'], roles=list(p.ROLES), replayIndices=list(p.REPLAYS))
        science = p.digest(identity); signature = p.digest(dict(unit=bank, chunk=chunk))
        manifest = dict(kind=q.KIND, signature=signature, scienceSha256=science, scienceIdentity=identity,
            globalStart=chunk*128, count=128,
            **{key: dict(item, snapshot=name) for key, (item, name) in snapshots.items()},
            policyBindings=self.binding(True), controlPolicyBindings=self.binding(False), inputPins={},
            ownedReplayIndices=list(p.replays(chunk)), maximumTerminalExecutions=256+2*len(p.replays(chunk)))
        q.storage.publish_json(output/'manifest.json', manifest)
        q.storage.publish_json(output/'started.json', dict(signature=signature, workers=10 if host == 'DESKTOP' else 6, maximumSeconds=100))
        worlds = []; rows = []
        for index in p.indices(chunk):
            world = dict(kind=q.KIND, signature=signature, scienceSha256=science, index=index,
                seed=self.plan.record['banks'][bank]['seeds'][index], seat=index%4, configuration=p.CONFIGURATION,
                opponentIndices=[(index//4+seat)%4 for seat in range(4)] if bank == 'mixed' else [0]*4,
                roles=dict(CANDIDATE=game(index%4, positive), CONTROL=game(index%4)))
            name = f'world-{index}.json.gz'; (output/name).write_bytes(gzip.compress(p.canonical(world), mtime=0))
            rows.append(dict(index=index, file=name, sha256=p.file_sha(output/name), semanticSha256='d'*64))
            worlds.append(world)
            if index in p.replays(chunk):
                canonical = dict(header=dict(unit=True, index=index), entries=[])
                for role in p.ROLES:
                    q.storage.publish_json(output/f'canonical-{index}-{role}.json', canonical)
                    replay = dict(**{key: world[key] for key in ('index', 'seed', 'seat', 'configuration', 'opponentIndices')},
                        **canonical, side='CANDIDATE', replayVerified=True, game=world['roles'][role],
                        coordinatedEs=self.binding(role == 'CANDIDATE')['coordinatedEs'])
                    q.storage.publish_json(output/f'replay-{index}-{role}.json', replay)
        count = 256+2*len(p.replays(chunk))
        finished = dict(kind=q.KIND, complete=True, stage='chunk', signature=signature,
            scienceSha256=science, scienceIdentity=identity, globalStart=chunk*128, count=128, worlds=rows,
            reproductions=2*len(p.replays(chunk)), scientificTerminalExecutions=count,
            finalAdmissionSha256=self.admission['sha256'], historicalAdmissionSha256=self.historical['sha256'],
            attemptedGames=count, completedGames=count,
            files={name: row['sha256'] for name, row in q.storage.inventory(output).items()})
        q.storage.publish_json(output/'finished.json', finished)
        receipt = dict(kind=p.PREFIX+'-screen-chunk-receipt-1', protocolSha256=self.plan.sha256,
            bank=bank, chunk=chunk, host=host, sourceSha256=p.file_sha(source), files=q.storage.inventory(output),
            completionJson=(output/'finished.json').read_text(), scienceSha256=science,
            worlds=128, games=256, reproductions=2*len(p.replays(chunk)), scientificTerminalExecutions=count)
        archive = directory/'evidence.tar.gz'; q.storage.archive_collection(output, archive, receipt)
        receipt_path = directory/'evidence.tar.gz.json'
        if remote:
            receipt_path.rename(directory/'receipt.json'); receipt_path = directory/'receipt.json'
            q.storage.publish_json(directory/'accepted.json', dict(kind=p.PREFIX+'-handoff-1', tag=q.tag(bank, chunk),
                receipt=p.subject(receipt_path), archive=p.subject(archive)))
        elif host == 'MAC':
            q.storage.publish_json(directory/'handoff.json', dict(kind=p.PREFIX+'-handoff-1', tag=q.tag(bank, chunk),
                receipt=dict(path='/unit-remote/receipt.json', sha256=p.file_sha(receipt_path)),
                archive=dict(path='/unit-remote/evidence.tar.gz', sha256=p.file_sha(archive))))
        return p.subject(receipt_path), worlds, finished

    def complete(self, bank, *, positive=False, gate=None):
        items = []; worlds = []; completed = []
        for chunk in range(32):
            item, rows, finished = self.chunk(bank, chunk, positive=positive, gate=gate, remote=p.owner(chunk) == 'MAC')
            items.append(item); worlds.extend(rows); completed.append(finished)
        directory = self.root/'banks'/bank; report_input = directory/'report-input'; report_input.mkdir()
        compact_path = report_input/'compact.json'; chunks = []
        for chunk, item in enumerate(items):
            receipt = p.read(item['path']); target = report_input/f'chunk-{chunk}-finished.json'
            target.write_text(receipt['completionJson']); chunks.append(q.relative(target, compact_path))
        q.storage.publish_json(compact_path, dict(kind=p.PREFIX+'-screen-compact-1', worlds=worlds, chunks=chunks))
        report_root = directory/'report'; report_root.mkdir()
        q.storage.publish_json(report_root/'report.json', native_report(worlds))
        q.storage.publish_json(report_root/'inputs.json', dict(sourceSha256=p.file_sha(compact_path), chunks=chunks,
            scienceSha256=completed[0]['scienceSha256'], finalAdmissionSha256=self.admission['sha256'],
            historicalAdmissionSha256=self.historical['sha256']))
        q.storage.publish_json(report_root/'finished.json', dict(kind=p.PREFIX+'-screen-report-complete-1', complete=True,
            worlds=4096, newGames=0, newFits=0, files={name: row['sha256'] for name, row in q.storage.inventory(report_root).items()}))
        owner = directory/'complete.json'
        return q.storage.publish_json(owner, dict(kind=p.PREFIX+'-bank-evidence-1', complete=True, bank=bank,
            protocolSha256=self.plan.sha256, candidateBundleSha256=p.CANDIDATE, scienceSha256=completed[0]['scienceSha256'],
            worlds=4096, games=8192, reproductions=8, scientificTerminalExecutions=8200, gatePassed=positive,
            chunks=items, compact=q.relative(compact_path, owner), report=q.relative(report_root/'report.json', owner),
            reportFinished=q.relative(report_root/'finished.json', owner)))


class RunnerTests(unittest.TestCase):
    def test_visible_clock_always_has_worker_owner_before_peer_health(self):
        class StopBeforeProfile(Exception): pass
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); protocol = base/'protocol.json'
            protocol.write_text('{}\n')
            root = base/'study'; started = time.time(); observed = []
            publish = q.storage.publish_json
            def observe(path, value):
                result = publish(path, value)
                observed.append((Path(path).name, q.health(root)['status']))
                if Path(path).name == 'clock.json': raise StopBeforeProfile()
                return result
            with patch.object(q.storage, 'publish_json', side_effect=observe), \
                    patch.object(q, 'worker_alive', return_value=True), \
                    patch.object(q, 'check_profile') as profile:
                with self.assertRaises(StopBeforeProfile):
                    q.worker(protocol, base/'unused-profile.json', root, started, started+p.SCIENCE_SECONDS)
                profile.assert_not_called()
            self.assertEqual(observed, [('worker-owner.json', 'absent'), ('clock.json', 'active')])

    def test_pure_import_closure_has_no_numerical_library(self):
        self.assertNotIn('torch', sys.modules); self.assertNotIn('numpy', sys.modules)

    def test_actual_archive_roundtrip_preserves_native_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); item, worlds, finished = fixture.chunk('mixed', 0)
            pins = Pins(); receipt, actual, rows, _ = q.archived(fixture.plan, item, pins)
            self.assertEqual(actual, finished); self.assertEqual(rows, worlds)
            self.assertEqual(receipt['scientificTerminalExecutions'], 258)
            self.assertEqual(len(pins.archives), 1)

    def test_archive_tamper_rejected_before_world_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); item, _, _ = fixture.chunk('mixed', 1)
            path = Path(item['path']); receipt = p.read(path); receipt['files']['world-128.json.gz']['sha256'] = 'f'*64
            path.write_bytes(p.canonical(receipt)+b'\n')
            with self.assertRaisesRegex(ValueError, 'before semantic decoding'):
                q.archived(fixture.plan, p.subject(path), Pins())

    def test_complete_positive_report_uses_strict_paired99(self):
        worlds = [dict(index=i, seat=i%4, roles=dict(CANDIDATE=game(i%4, True), CONTROL=game(i%4))) for i in range(4096)]
        self.assertTrue(q.verify_report(worlds, native_report(worlds)))

    def test_zero_lower_bound_does_not_pass(self):
        worlds = [dict(index=i, seat=i%4, roles=dict(CANDIDATE=game(i%4), CONTROL=game(i%4))) for i in range(4096)]
        self.assertFalse(q.verify_report(worlds, native_report(worlds)))

    def test_partial_bank_cannot_open_effects(self):
        with self.assertRaisesRegex(ValueError, 'whole fixed bank'):
            q.verify_report([], {})

    def test_report_gate_refuses_relabelled_95_bounds(self):
        worlds = [dict(index=i, seat=i%4, roles=dict(CANDIDATE=game(i%4, i%2 == 0), CONTROL=game(i%4))) for i in range(4096)]
        report = native_report(worlds); report['intervalMultiplier'] = 1.96
        with self.assertRaisesRegex(ValueError, 'paired99'): q.report_gate(report)

    def test_mailbox_has_exact_fixed_mac_ownership(self):
        self.assertEqual(q.allowed_tag('mixed-chunk-20'), 'mixed-chunk-20')
        with self.assertRaises(ValueError): q.tag('mixed', 19)
        with self.assertRaises(ValueError): q.allowed_tag('homogeneous-chunk-32')

    def test_origin_proof_rejects_policy_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); proof = p.read(fixture.proofs['DESKTOP']['path'])
            proof['execution']['policyCalls'] = 1
            with self.assertRaisesRegex(ValueError, 'zero-game'):
                q.origin_proof(fixture.plan, 'DESKTOP', proof)

    def test_origin_proof_requires_every_named_runtime_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); proof = p.read(fixture.proofs['DESKTOP']['path'])
            del proof['inputPins'][proof['runtime']['unit-0.jar']['path']]
            with self.assertRaisesRegex(ValueError, 'immutable input closure'):
                q.origin_proof(fixture.plan, 'DESKTOP', proof)

    def test_new_scope_cannot_replace_old_verified_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory, self); proof = p.read(fixture.proofs['DESKTOP']['path'])
            with self.assertRaisesRegex(ValueError, 'original83 scope'):
                q.origin_proof(fixture.plan, 'DESKTOP', proof,
                    dict(originScopeSha256='c'*64, originEcologySha256=proof['ecologySha256']))

    def test_peer_failures_cannot_become_honest_stops(self):
        with self.assertRaises(ValueError):
            q.PeerStopped(dict(terminal=dict(kind=p.PREFIX+'-terminal-1', host='DESKTOP', status='failed',
                reason='mixed-confirmation-failed', completedBanks=1, qualified=False)))

    def test_actual_finite_worker_stops_after_mixed_refusal(self):
        # Only orchestration seams are constructed here; archive admission has separate unmocked coverage.
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(Path(directory)/'inputs', self); root = Path(directory)/'run'
            profile_path = Path(directory)/'profile.json'; q.storage.publish_json(profile_path, dict(unit=True))
            started = time.time()-1; deadline = started+p.SCIENCE_SECONDS
            profile = dict(host='DESKTOP', protocolSha256=fixture.plan.sha256)
            def complete(plan, profile, root, bank, receipts):
                self.assertEqual(bank, 'mixed'); self.assertEqual(len(receipts), 20)
                return q.storage.publish_json(root/'mixed-unit.json', dict(gatePassed=False))
            with patch.object(q, 'check_profile', return_value=(fixture.plan, profile)), \
                    patch.object(q, 'collect', return_value=dict(path='/unit/shard', sha256='a'*64)) as collect, \
                    patch.object(q, 'wait_receipts', side_effect=lambda profile, root, bank, rows: rows), \
                    patch.object(q, 'complete_bank', side_effect=complete), \
                    patch.object(q, 'publish_confirmation', side_effect=AssertionError('Heldout must stay closed')), \
                    patch.dict(os.environ, {'AOR_CAMPAIGN_DEADLINE_EPOCH': str(deadline),
                        'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH': str(deadline)}):
                ended = q.worker(fixture.protocol, profile_path, root, started, deadline)
            self.assertEqual(collect.call_count, 20)
            self.assertEqual(ended['status'], 'stopped'); self.assertEqual(ended['completedBanks'], 1)
            self.assertEqual(ended['clock']['deadlineEpoch'], deadline)


@unittest.skipUnless(os.environ.get('AOR_QUALIFICATION_NATIVE_FIXTURE'), 'Explicit constructed native exporter required.')
class NativeFixtureBridgeTests(unittest.TestCase):
    def test_actual_native_serializer_to_strict_python_reader(self):
        root = Path(os.environ['AOR_QUALIFICATION_NATIVE_FIXTURE'])
        marker = root/'CONSTRUCTED-FIXTURE.json'; self.assertTrue(marker.is_file())
        output = root/'chunk'; protocol = output/'protocol.json'
        record = p.read(protocol); original_pin = p.CANDIDATE
        if record['candidateBundleSha256'] != original_pin:
            with self.assertRaises(ValueError): p.Plan.from_file(protocol)
        # Explicit test-only original expectation substitution; neither native nor numerical math is replaced.
        with patch.object(p, 'CANDIDATE', record['candidateBundleSha256']):
            plan = p.Plan.from_file(protocol); files = q.storage.inventory(output)
            values = {}
            for name in q.wanted(files):
                raw = (output/name).read_bytes()
                self.assertEqual(hashlib.sha256(raw).hexdigest(), files[name]['sha256'])
                values[name] = q.decode(gzip.decompress(raw) if name.endswith('.json.gz') else raw)
            source = values['study-source.json']; chunk = source['globalStart']//128
            manifest, finished, worlds = q.chunk_evidence(plan, source['bankName'], chunk, files, values,
                p.file_sha(output/'study-source.json'))
            self.assertEqual(len(worlds), 128); self.assertEqual(finished['completedGames'], 256)
            self.assertEqual(source['host'], 'DESKTOP')
            target = os.environ.get('AOR_QUALIFICATION_NATIVE_PROOF')
            with tempfile.TemporaryDirectory() as temporary:
                archive_root = Path(target).parent/'native-archive' if target else Path(temporary)/'retained'
                archive_root.mkdir(parents=True, exist_ok=False)
                clone = Path(temporary)/'native-copy'; shutil.copytree(output, clone)
                receipt = dict(kind=p.PREFIX+'-screen-chunk-receipt-1', protocolSha256=plan.sha256,
                    bank=source['bankName'], chunk=chunk, host='DESKTOP', sourceSha256=p.file_sha(output/'study-source.json'),
                    files=files, completionJson=(output/'finished.json').read_text(), scienceSha256=manifest['scienceSha256'],
                    worlds=128, games=256, reproductions=finished['reproductions'],
                    scientificTerminalExecutions=finished['scientificTerminalExecutions'])
                q.storage.archive_collection(clone, archive_root/'evidence.tar.gz', receipt)
                archived_subject = p.subject(archive_root/'evidence.tar.gz.json')
                pins = Pins(); _, archived_finished, archived_worlds, _ = q.archived(plan, archived_subject, pins)
                self.assertEqual(archived_finished, finished); self.assertEqual(archived_worlds, worlds)
                self.assertEqual(len(pins.archives), 1); self.assertFalse(clone.exists())
                archive_subject = p.subject(archive_root/'evidence.tar.gz')
        self.assertEqual(p.CANDIDATE, original_pin)
        if record['candidateBundleSha256'] != original_pin:
            with self.assertRaises(ValueError): p.Plan.from_file(protocol)
        if target:
            q.storage.publish_json(Path(target), dict(kind=p.PREFIX+'-constructed-native-python-bridge-1', passed=True,
                fixture=p.subject(marker), protocol=p.subject(protocol), source=p.subject(output/'study-source.json'),
                manifest=p.subject(output/'manifest.json'), finished=p.subject(output/'finished.json'),
                archive=archive_subject, archiveReceipt=archived_subject,
                worlds=128, recordedInventedTerminalExecutions=256, realGames=0, realModels=0,
                modelForwards=0, qualificationClaimed=False,
                substitutedExpectedCandidate=record['candidateBundleSha256'], restoredScientificExpectation=original_pin,
                artifactPins={str(output/name): value['sha256'] for name, value in files.items()}))


if __name__ == '__main__': unittest.main()
