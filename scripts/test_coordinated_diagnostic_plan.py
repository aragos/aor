"""Prospective constructed protocol identities only; no reserved streams or scientific subjects."""
import copy
from pathlib import Path
import tempfile
import unittest

import coordinated_diagnostic_plan as p


def protocol_record():
    return dict(kind=p.PROTOCOL_KIND, candidateBundleSha256=p.CANDIDATE,
        originalBundleSha256='1'*64, calibrationSha256='2'*64, originAdmissionSha256='3'*64,
        originArenaSha256=dict(DESKTOP='4'*64, MAC='5'*64), originProofSha256=dict(DESKTOP='a'*64, MAC='b'*64),
        references=dict(qualifiedOrdinarySha256='6'*64, cardM8Sha256='7'*64, purchaseF0Sha256='8'*64,
            expansionESha256='9'*64, tokenControlVersion='FIXED_16_TO_15'), rulesVersion=p.RULES,
        dataVersion=p.DATA, configuration=p.CONFIGURATION,
        bank=dict(nativeOffset=100, count=512, seeds=[-1000000000+100+i*8191 for i in range(512)]),
        heads=dict(p.HEADS), masks=list(range(16)), supportProfiles=list(p.SUPPORTS), ecologies=list(p.ECOLOGIES),
        replayWorlds=list(p.REPLAYS), diagnosticOnly=True, promotionAllowed=False,
        maximumSeconds=14400, auditSeconds=900, maximumTerminalExecutions=32832, interval=dict(p.INTERVAL))


class PlanTests(unittest.TestCase):
    def test_full_complement_grid_and_fixed_host_counts(self):
        plan = p.Plan.from_record(protocol_record())
        self.assertEqual(len(p.CASES), 32)
        self.assertEqual(p.counts(), dict(independentWorlds=512, pairedWorldRecords=16384,
            games=32768, reproductions=64, terminalExecutions=32832))
        self.assertEqual(p.counts('DESKTOP')['terminalExecutions'], 20512)
        self.assertEqual(p.counts('MAC')['terminalExecutions'], 12320)
        self.assertEqual(plan.record['bank']['count'], 512)

    def test_each_mask_context_has_one_balanced_reproduction(self):
        rows = [(selected.ecology, selected.support, selected.masks[role], index)
            for selected in p.CASES for chunk in range(2) for index, role in selected.replays(chunk)]
        self.assertEqual(len({row[:3] for row in rows}), 64)
        self.assertEqual([sum(index%4 == seat for *_, index in rows) for seat in range(4)], [16]*4)
        self.assertTrue(all(len(item.replays(chunk)) == 1 for item in p.CASES for chunk in range(2)))

    def test_one_native_range_per_case_and_host(self):
        self.assertEqual(list(p.indices(0)), list(range(320)))
        self.assertEqual(list(p.indices(1)), list(range(320, 512)))
        with self.assertRaises(ValueError): p.owner(2)

    def test_protocol_roundtrip_and_raw_newline_identity(self):
        plan = p.Plan.from_record(protocol_record())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'protocol.json'; path.write_bytes(plan.wire)
            self.assertEqual(p.Plan.from_file(path), plan)
            path.write_bytes(plan.wire[:-1])
            with self.assertRaisesRegex(ValueError, 'one LF'): p.Plan.from_file(path)

    def test_changed_candidate_rejected(self):
        value = protocol_record(); value['candidateBundleSha256'] = 'c'*64
        with self.assertRaisesRegex(ValueError, 'sole frozen83'): p.Plan.from_record(value)

    def test_promotion_or_missing_mask_rejected(self):
        for key, bad in (('promotionAllowed', True), ('diagnosticOnly', False), ('masks', list(range(15)))):
            with self.subTest(key=key):
                value = protocol_record(); value[key] = bad
                with self.assertRaises(ValueError): p.Plan.from_record(value)

    def test_boolean_bit_or_noncanonical_seed_rejected(self):
        value = protocol_record(); value['heads']['CARD'] = True
        with self.assertRaises(ValueError): p.Plan.from_record(value)
        value = protocol_record(); value['bank']['seeds'][1] += 1
        with self.assertRaisesRegex(ValueError, 'seed expansion'): p.Plan.from_record(value)

    def test_changed_science_or_interval_budget_rejected(self):
        for key, bad in (('maximumSeconds', 14401), ('maximumTerminalExecutions', 32833),
                ('interval', dict(p.INTERVAL, gates=True))):
            with self.subTest(key=key):
                value = protocol_record(); value[key] = bad
                with self.assertRaises(ValueError): p.Plan.from_record(value)

    def test_host_specific_origin_subjects_cannot_cross(self):
        plan = p.Plan.from_record(protocol_record()); selected = p.CASES[0]
        subjects = dict(protocol=dict(path='/UNIT/protocol.json', sha256=plan.sha256),
            originArena=dict(path='/UNIT/origin.json', sha256='5'*64),
            originProof=dict(path='/UNIT/proof.json', sha256='b'*64),
            originAdmission=dict(path='/UNIT/admission.json', sha256='3'*64),
            arena=dict(path='/UNIT/arena.json', sha256='c'*64))
        source = plan.source(selected, 1, subjects)
        self.assertEqual((source['host'], source['globalStart'], source['count']), ('MAC', 320, 192))
        with self.assertRaisesRegex(ValueError, 'host83'): plan.source(selected, 0, subjects)

    def test_clock_cannot_be_renewed(self):
        self.assertEqual(p.Clock(1., 14401.).remaining(2.), 14399.)
        with self.assertRaises(ValueError): p.Clock(1., 14402.)
        with self.assertRaises(ValueError): p.Clock(1., 14401.).remaining(14401.)


if __name__ == '__main__': unittest.main()
