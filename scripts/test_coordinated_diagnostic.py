"""Constructed statistical and native-wire fixtures; never scientific policies or games."""
import math
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import patch

import coordinated_diagnostic as q
import coordinated_diagnostic_plan as p
from test_coordinated_diagnostic_plan import protocol_record
from test_coordinated_qualification import game


class Fixture:
    """Invented descriptors, attestation and terminal/replay records; no native execution claim."""
    def __init__(self, root, test):
        self.root = Path(root).resolve(); self.root.mkdir(parents=True, exist_ok=True)
        self.inputs = self.root/'unit-inputs'; self.inputs.mkdir()
        def put(name, value): return q.storage.publish_json(self.inputs/name, value)
        self.original = dict(kind='coordinated-es-bundle-1', heads={head: dict(put(f'original-{head}.json',
            dict(constructed=True, head=head, final=False)), dictionarySha256=p.digest(dict(unitDictionary=head))) for head in p.HEADS})
        self.final = dict(kind='coordinated-es-bundle-1', heads={head: dict(put(f'final-{head}.json',
            dict(constructed=True, head=head, final=True)), dictionarySha256=p.digest(dict(unitDictionary=head))) for head in p.HEADS})
        self.original_subject = put('original-bundle.json', self.original)
        self.final_subject = put('final-bundle.json', self.final)
        self.calibration_record = dict(kind='coordinated-es-calibration-2', passed=True, complete=True,
            originalBundleSha256=self.original_subject['sha256'], scales=dict(EXPANSION=2., TOKEN=3.))
        self.calibration = put('calibration.json', self.calibration_record)
        self.baseline = put('baseline.json', dict(unit='F0 reference'))
        self.masks = {mask: put(f'mask-{mask}.json', dict(kind='coordinated-es-bundle-1', heads={
            head: (self.final if mask & bit else self.original)['heads'][head] for head, bit in p.HEADS.items()}))
            for mask in range(16)}
        self.origins = {host: put(f'origin-{host}.json', dict(unit=host)) for host in p.HOSTS}
        self.admission = put('admission.json', dict(unit='invented original admission'))
        self.historical = put('historical.json', dict(unit='hash-only historical provenance'))
        self.arenas = {item.identity: put('arena-'+item.identity+'.json', dict(unit=item.identity)) for item in p.CASES}
        subjects = {name: put('old-'+name+'.json', dict(unit=name))
            for name in ('protocol', 'projection', 'center', 'generationAudit', 'centerMaterialization')}
        subjects.update(originalBundle=self.original_subject, finalBundle=self.final_subject,
            calibration=self.calibration, historical=self.historical)
        runtime = {f'unit-{i}.jar': put(f'unit-{i}.jar', dict(unit=i)) for i in range(9)}
        self.proofs = {}
        for host in p.HOSTS:
            pins = {row['path']: row['sha256'] for row in
                (self.admission, self.origins[host], *subjects.values(), *runtime.values())}
            self.proofs[host] = put(f'proof-{host}.json', dict(kind='coordinated-qualification-origin-proof-1',
                host=host, passed=True, complete=True, admission=self.admission, arena=self.origins[host],
                runtime=runtime, subjects=subjects, inputPins=pins, scopeIdentity={}, scopeSha256='a'*64,
                ecologySha256='b'*64, execution=dict(newGames=0, policyCalls=0, actualFits=0)))
        original_pin = p.CANDIDATE; override = patch.object(p, 'CANDIDATE', self.final_subject['sha256']); override.start()
        def restore():
            override.stop(); test.assertEqual(p.CANDIDATE, original_pin)
        test.addCleanup(restore)
        record = protocol_record()
        record.update(originalBundleSha256=self.original_subject['sha256'], calibrationSha256=self.calibration['sha256'],
            originAdmissionSha256=self.admission['sha256'], originArenaSha256={host: row['sha256'] for host, row in self.origins.items()},
            originProofSha256={host: row['sha256'] for host, row in self.proofs.items()})
        record['references']['purchaseF0Sha256'] = self.baseline['sha256']
        self.protocol = self.inputs/'protocol.json'; q.storage.publish_json(self.protocol, record)
        self.plan = p.Plan.from_file(self.protocol)

    def binding(self, selected):
        heads = q.mask_bindings(selected, self.original, self.final)
        value = dict(version='coordinated-support-arena-1' if selected.support == 'SUPPORT1' else 'coordinated-es-arena-1',
            execution='CALIBRATED', reference='complete-joint-token-expansion-composition',
            originalBundle=dict(self.original_subject, heads={head: {key: row[key] for key in ('sha256', 'dictionarySha256')}
                for head, row in self.original['heads'].items()}), baseline=self.baseline, calibration=self.calibration,
            selectors={head: dict(kind='incumbent-residual-margin-1', margin=1., scale=scale)
                for head, scale in self.calibration_record['scales'].items()},
            roles={role: dict(self.masks[mask], heads=heads[role]) for role, mask in selected.masks.items()})
        if selected.support == 'SUPPORT1': value.update(supportProfile='known-own-chaos-support-1', supportRoles='ALL_SEATS_CP')
        return dict(coordinatedEs=value)

    def chunk(self, selected, chunk, remote=False):
        host = p.owner(chunk)
        directory = self.root/('incoming/'+q.tag(selected.identity, chunk) if remote else
            f'cases/{selected.identity}/chunks/chunk-{chunk}')
        directory.mkdir(parents=True); native = directory/'native'; native.mkdir()
        source = directory/'source.json'
        q.storage.publish_json(source, self.plan.source(selected, chunk, dict(protocol=p.subject(self.protocol),
            originArena=self.origins[host], originAdmission=self.admission, originProof=self.proofs[host],
            arena=self.arenas[selected.identity])))
        snapshots = dict(source=(p.subject(source), 'study-source.json'), protocol=(p.subject(self.protocol), 'protocol.json'),
            originArena=(self.origins[host], 'origin-arena.json'), originProof=(self.proofs[host], 'origin-proof.json'),
            arena=(self.arenas[selected.identity], 'arena-source.json'), admission=(self.admission, 'admission.json'),
            historicalAdmission=(self.historical, 'historical-admission.json'), originalBundle=(self.original_subject, 'original-bundle.json'),
            finalBundle=(self.final_subject, 'final-bundle.json'), calibration=(self.calibration, 'calibration.json'))
        for row, name in snapshots.values(): (native/name).write_bytes(Path(row['path']).read_bytes())
        masks = q.mask_bindings(selected, self.original, self.final); replays = selected.replays(chunk)
        role_replays = {role: p.REPLAYS[mask%4] for role, mask in selected.masks.items()}
        identity = dict(kind=p.KIND, protocolSha256=self.plan.sha256,
            originAdmissionSha256=self.admission['sha256'], originScopeSha256='a'*64, originEcologySha256='b'*64,
            ecologySha256='c'*64, ecologyIdentity={}, ecology=selected.ecology, support=selected.support, pair=selected.pair,
            roleMasks=selected.masks, maskBindings=masks, seeds=self.plan.record['bank']['seeds'], roles=list(p.ROLES),
            replayIndices=sorted(role_replays.values()), roleReplayIndices=role_replays, diagnosticOnly=True, promotionAllowed=False,
            **{key: self.plan.record[key] for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256')})
        science = p.digest(identity); signature = p.digest(dict(unit=selected.identity, chunk=chunk))
        start, count = p.RANGES[chunk]
        input_pins = {str(path): p.file_sha(path) for path in self.inputs.iterdir() if path.is_file()}
        input_pins[str(source)] = p.file_sha(source)
        manifest = dict(kind=p.KIND, host=host, signature=signature, scienceSha256=science, scienceIdentity=identity,
            globalStart=start, count=count, **{key: dict(row, snapshot=name) for key, (row, name) in snapshots.items()},
            roleMasks=selected.masks, maskBindings=masks, policyBindings=self.binding(selected), inputPins=input_pins,
            ownedReplayIndices=sorted(index for index, _ in replays),
            ownedRoleReplays=[dict(index=index, role=role) for index, role in sorted(replays)], maximumTerminalExecutions=2*count+1)
        q.storage.publish_json(native/'manifest.json', manifest)
        q.storage.publish_json(native/'started.json', dict(signature=signature, workers=p.host_limits(host)['workers'], maximumSeconds=60))
        rows = []
        for index in p.indices(chunk):
            world = dict(kind=p.KIND, signature=signature, scienceSha256=science, index=index,
                seed=self.plan.record['bank']['seeds'][index], seat=index%4, configuration=p.CONFIGURATION,
                opponentIndices=[(index//4+seat)%4 for seat in range(4)] if selected.ecology == 'MIXED' else [0]*4,
                roles={role: game(index%4, bool(mask & 1)) for role, mask in selected.masks.items()})
            name = f'world-{index}.json.gz'; (native/name).write_bytes(gzip.compress(p.canonical(world), mtime=0))
            rows.append(dict(index=index, file=name, sha256=p.file_sha(native/name), semanticSha256='d'*64))
            for wanted_index, role in replays:
                if wanted_index != index: continue
                canonical = dict(header=dict(unit=True, index=index, role=role), entries=[])
                q.storage.publish_json(native/f'canonical-{index}-{role}.json', canonical)
                q.storage.publish_json(native/f'replay-{index}-{role}.json', dict(
                    **{key: world[key] for key in ('index', 'seed', 'seat', 'configuration', 'opponentIndices')},
                    **canonical, side=role, replayVerified=True, game=world['roles'][role],
                    coordinatedEs=manifest['policyBindings']['coordinatedEs']))
        finished = dict(kind=p.KIND, complete=True, stage='chunk', signature=signature, scienceSha256=science,
            scienceIdentity=identity, globalStart=start, count=count, worlds=rows, reproductions=1,
            scientificTerminalExecutions=2*count+1, finalAdmissionSha256=self.admission['sha256'],
            historicalAdmissionSha256=self.historical['sha256'], attemptedGames=2*count+1, completedGames=2*count+1,
            files={name: value['sha256'] for name, value in q.storage.inventory(native).items()})
        q.storage.publish_json(native/'finished.json', finished)
        receipt = dict(kind=p.PREFIX+'-screen-chunk-receipt-1', protocolSha256=self.plan.sha256, caseId=selected.identity,
            chunk=chunk, host=host, sourceSha256=p.file_sha(source), files=q.storage.inventory(native),
            completionJson=(native/'finished.json').read_text(), scienceSha256=science,
            pairedWorldRecords=count, games=2*count, reproductions=1, scientificTerminalExecutions=2*count+1)
        archive = directory/'evidence.tar.gz'; q.storage.archive_collection(native, archive, receipt)
        receipt_path = archive.with_name(archive.name+'.json')
        if remote:
            target = directory/'receipt.json'; target.write_bytes(receipt_path.read_bytes()); receipt_path = target
            q.storage.publish_json(directory/'accepted.json', dict(kind=p.PREFIX+'-handoff-1', tag=q.tag(selected.identity, chunk),
                receipt=p.subject(receipt_path), archive=p.subject(archive)))
        elif host == 'MAC':
            q.storage.publish_json(directory/'handoff.json', dict(kind=p.PREFIX+'-handoff-1', tag=q.tag(selected.identity, chunk),
                receipt=p.subject(receipt_path), archive=p.subject(archive)))
        return p.subject(receipt_path)

    def decoded(self, item):
        path = Path(item['path']); record = p.read(path)
        values = q.stream_archive(path.with_name(record['archive']), record, q.Pins(), q.wanted(record['files']))
        return record, values


def arithmetic_cases():
    """Invented bounded metric vectors exercise the estimator, not native terminal admission."""
    result = {}
    for selected in p.CASES:
        rows = []
        for index in range(512):
            roles = {}
            for role, mask in selected.masks.items():
                c, buy, expand, token = [int(bool(mask & bit)) for bit in p.HEADS.values()]
                support = int(selected.support == 'SUPPORT1'); ecology = int(selected.ecology == 'MIXED')
                win = (.1+.01*c+.02*buy+.03*expand+.04*token+.025*c*buy+.005*support+.003*support*c+
                    .01*ecology+.007*ecology*token+.004*(index%2))
                rank = .4+.01*c; reward = .8*win+.2*rank
                scores = [dict(player=f'p{seat+1}', total=None if (index+mask)%17 == 0 else 10)
                    for seat in reversed(range(4))]
                roles[role] = dict(wins=[win]*4, ranks=[rank]*4, rewards=[reward]*4, scores=scores)
            rows.append(dict(index=index, seat=index%4, roles=roles))
        result[selected.identity] = rows
    return result


class AggregateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = p.Plan.from_record(protocol_record())
        cls.cases = arithmetic_cases()
        cls.report = q.aggregate(cls.plan, cls.cases)

    def test_exact_fixed_contrast_inventory_has_no_selection_gate(self):
        report = self.report
        self.assertEqual([len(report[key]) for key in ('cellMeans', 'maskMinusZero', 'headEffects',
            'headPairInteractions', 'supportMaskContrasts', 'supportHeadEffectContrasts',
            'ecologyMaskContrasts', 'ecologyHeadEffectContrasts')], [64, 64, 16, 24, 32, 8, 32, 8])
        self.assertTrue(report['diagnosticOnly']); self.assertFalse(report['promotionAllowed'])
        self.assertFalse(report['qualified']); self.assertIsNone(report['nominee'])

    def test_head_effect_averages_backgrounds_before_world_interval(self):
        row = next(row for row in self.report['headEffects'] if row['ecology'] == 'HOMOGENEOUS' and
            row['support'] == 'RAW' and row['head'] == 'CARD')
        self.assertEqual(row['withinWorldBackgroundCount'], 8)
        self.assertEqual(row['metrics']['wins']['count'], 512)
        self.assertAlmostEqual(row['metrics']['wins']['mean'], .01+.025/2)
        self.assertLess(row['metrics']['wins']['standardError'], 1e-16)

    def test_pair_interaction_is_mean_of_four_differences_in_differences(self):
        row = next(row for row in self.report['headPairInteractions'] if row['ecology'] == 'HOMOGENEOUS' and
            row['support'] == 'RAW' and row['heads'] == ['CARD', 'PURCHASE'])
        self.assertEqual(row['withinWorldBackgroundCount'], 4)
        self.assertEqual(row['metrics']['wins']['count'], 512)
        self.assertAlmostEqual(row['metrics']['wins']['mean'], .025)

    def test_support_and_ecology_differences_of_head_effects(self):
        support = next(row for row in self.report['supportHeadEffectContrasts'] if row['ecology'] == 'HOMOGENEOUS'
            and row['head'] == 'CARD')
        ecology = next(row for row in self.report['ecologyHeadEffectContrasts'] if row['support'] == 'RAW'
            and row['head'] == 'TOKEN')
        self.assertAlmostEqual(support['metrics']['wins']['mean'], .003)
        self.assertAlmostEqual(ecology['metrics']['wins']['mean'], .007)

    def test_mask_zero_reference_has_exact_zero_differences(self):
        rows = [row for row in self.report['maskMinusZero'] if row['mask'] == 0]
        self.assertEqual(len(rows), 4)
        self.assertTrue(all(value[key] == 0 for row in rows for value in row['metrics'].values()
            for key in ('mean', 'standardError', 'lower95', 'upper95')))

    def test_partial_case_set_cannot_publish_any_report(self):
        with self.assertRaisesRegex(ValueError, 'partial diagnostic'):
            q.aggregate(self.plan, {name: rows for name, rows in self.cases.items() if name != p.CASES[0].identity})

    def test_replaced_world_id_rejected(self):
        cases = dict(self.cases); name = p.CASES[0].identity
        cases[name] = cases[name][1:]+[cases[name][-1]]
        with self.assertRaisesRegex(ValueError, 'without replacement'): q.aggregate(self.plan, cases)

    def test_interval_is_sample_standard_error_over_512_units(self):
        value = q.interval([0., 1.]*256)
        self.assertEqual(value['mean'], .5)
        self.assertAlmostEqual(value['standardError'], math.sqrt(128/(511*512)))
        with self.assertRaisesRegex(ValueError, '512'): q.interval([0.]*4096)


class WireTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.fixture = Fixture(self.temp.name, self)

    def test_native_range_archive_retains_role_specific_replay_and_counts(self):
        selected = p.Case('MIXED', 'SUPPORT1', 2)
        item = self.fixture.chunk(selected, 1)
        receipt, _, worlds, manifest = q.archived(self.fixture.plan, item, q.Pins())
        self.assertEqual(len(worlds), 192)
        self.assertEqual(receipt['scientificTerminalExecutions'], 385)
        self.assertEqual(manifest['ownedRoleReplays'], [dict(index=341, role='CANDIDATE')])

    def test_changed_mask_binding_rejected(self):
        selected = p.CASES[0]; item = self.fixture.chunk(selected, 0)
        record, values = self.fixture.decoded(item)
        values['manifest.json']['maskBindings']['CANDIDATE']['CARD']['sha256'] = 'c'*64
        with self.assertRaises(ValueError):
            q.chunk_evidence(self.fixture.plan, selected, 0, record['files'], values)

    def test_extra_role_reproduction_rejected(self):
        selected = p.CASES[0]; item = self.fixture.chunk(selected, 0)
        record, values = self.fixture.decoded(item)
        record['files']['replay-0-INCUMBENT.json'] = dict(sha256='a'*64, size=1)
        with self.assertRaisesRegex(ValueError, 'only prescribed'):
            q.chunk_evidence(self.fixture.plan, selected, 0, record['files'], values)

    def test_raw_context_cannot_acquire_all_seat_support(self):
        selected = p.CASES[0]; item = self.fixture.chunk(selected, 0)
        record, values = self.fixture.decoded(item)
        values['manifest.json']['policyBindings']['coordinatedEs']['supportProfile'] = 'known-own-chaos-support-1'
        with self.assertRaisesRegex(ValueError, 'support composition'):
            q.chunk_evidence(self.fixture.plan, selected, 0, record['files'], values)

    def test_missing_world_cannot_be_replaced_by_another_range(self):
        selected = p.CASES[0]; item = self.fixture.chunk(selected, 0)
        record, values = self.fixture.decoded(item)
        values['world-0.json.gz']['index'] = 400
        with self.assertRaisesRegex(ValueError, 'original global worlds'):
            q.chunk_evidence(self.fixture.plan, selected, 0, record['files'], values)

    def test_visible_clock_is_published_only_after_worker_owner(self):
        class StopBeforeProfile(Exception): pass
        root = self.fixture.root/'worker'; observed = []; publish = q.storage.publish_json
        def observe(path, record):
            result = publish(path, record); observed.append((Path(path).name, q.health(root)['status']))
            if Path(path).name == 'clock.json': raise StopBeforeProfile()
            return result
        start = time.time()
        with patch.object(q.storage, 'publish_json', side_effect=observe), patch.object(q, 'worker_alive', return_value=True), \
                patch.object(q, 'check_profile') as check:
            with self.assertRaises(StopBeforeProfile): q.worker(self.fixture.protocol, self.fixture.root/'unused', root, start, start+14400)
            check.assert_not_called()
        self.assertEqual(observed, [('worker-owner.json', 'absent'), ('clock.json', 'active')])

    def test_failed_first_range_cannot_publish_effects_or_nominee(self):
        root = self.fixture.root/'worker'; start = time.time(); deadline = start+14400
        profile = dict(host='DESKTOP', protocolSha256=self.fixture.plan.sha256)
        path = self.fixture.root/'profile.json'; q.storage.publish_json(path, profile)
        with patch.object(q, 'check_profile', return_value=(self.fixture.plan, profile)), \
                patch.object(q, 'collect', side_effect=ValueError('UNIT native failure')), \
                patch.object(q, 'complete_report') as report, patch.dict(os.environ,
                    AOR_CAMPAIGN_DEADLINE_EPOCH=str(deadline), AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH=str(deadline)):
            with self.assertRaisesRegex(ValueError, 'UNIT native failure'):
                q.worker(self.fixture.protocol, path, root, start, deadline)
            report.assert_not_called()
        ended = p.read(root/'terminal.json')
        self.assertEqual((ended['status'], ended['completedCases'], ended['report']), ('failed', 0, None))
        self.assertFalse(ended['qualified']); self.assertIsNone(ended['nominee'])


class NativeBridgeTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('AOR_DIAGNOSTIC_NATIVE_FIXTURE'), 'The optional bridge uses only the explicit retained constructed exporter.')
    def test_native_copy_lossless_archive_and_strict_python_reader(self):
        fixture = Path(os.environ['AOR_DIAGNOSTIC_NATIVE_FIXTURE']).resolve()
        marker = p.read(fixture/'CONSTRUCTED-FIXTURE.json')
        self.assertTrue(marker['constructedOnly']); self.assertEqual(marker['newGames'], 0)
        self.assertFalse(marker['scientificOriginalAdmission'])
        protocol = fixture/'chunk/protocol.json'
        original = p.CANDIDATE
        with self.assertRaises(ValueError): p.Plan.from_file(protocol)
        destination = Path(os.environ['AOR_DIAGNOSTIC_BRIDGE_OUTPUT']).resolve()
        destination.mkdir(parents=True, exist_ok=False)
        shutil.copytree(fixture/'chunk', destination/'native')
        inventory = q.storage.inventory(fixture/'chunk')
        self.assertEqual(q.storage.inventory(destination/'native'), inventory)
        with patch.object(p, 'CANDIDATE', marker['candidateBundleSha256']):
            plan = p.Plan.from_file(protocol)
            source = p.read(destination/'native/study-source.json')
            selected = p.Case(source['ecology'], source['support'], source['pair'])
            manifest = p.read(destination/'native/manifest.json'); finished = p.read(destination/'native/finished.json')
            record = dict(kind=p.PREFIX+'-screen-chunk-receipt-1', protocolSha256=plan.sha256,
                caseId=selected.identity, chunk=0, host='DESKTOP', sourceSha256=inventory['study-source.json']['sha256'],
                files=inventory, completionJson=(destination/'native/finished.json').read_text(),
                scienceSha256=manifest['scienceSha256'], pairedWorldRecords=320, games=640, reproductions=1,
                scientificTerminalExecutions=641)
            archive = destination/'evidence.tar.gz'
            q.storage.archive_collection(destination/'native', archive, record)
            pins = q.Pins(); item = p.subject(archive.with_name(archive.name+'.json'))
            receipt, checked, worlds, _ = q.archived(plan, item, pins)
            self.assertEqual(checked, finished); self.assertEqual(len(worlds), 320)
            self.assertEqual(receipt['scientificTerminalExecutions'], 641)
            self.assertEqual(len(pins.archives), 1)
            q.storage.publish_json(destination/'proof.json', dict(kind=p.PREFIX+'-native-python-bridge-1', passed=True,
                nativeFixture=p.subject(fixture/'CONSTRUCTED-FIXTURE.json'), protocol=p.subject(protocol),
                archive=p.subject(archive), receipt=item, nativeMembers=inventory,
                testOnlyCandidateExpectedPin=marker['candidateBundleSha256'], originalCandidateExpectedPin=original,
                worlds=320, declaredInventedTerminals=641, newGames=0, modelForwards=0, actualFits=0,
                scientificOriginalAdmission=False,
                limitation='Native DTO/finish serialization and exact archive/Python joins only; origin, scores and replay are invented.'))
        self.assertEqual(p.CANDIDATE, original)
        with self.assertRaises(ValueError): p.Plan.from_file(protocol)
        self.assertEqual(q.storage.inventory(fixture/'chunk'), inventory)


if __name__ == '__main__': unittest.main()
