"""Constructed prospective identities only; no reserved stream or scientific subject is used."""
import copy
from pathlib import Path
import tempfile
import unittest

import coordinated_qualification_plan as p


def protocol_record():
    return dict(kind=p.PROTOCOL_KIND, profile=p.PROFILE, supportProfile=p.SUPPORT,
        candidateBundleSha256=p.CANDIDATE, originalBundleSha256='1'*64, calibrationSha256='2'*64,
        originAdmissionSha256='3'*64, originArenaSha256=dict(DESKTOP='4'*64, MAC='5'*64),
        originProofSha256=dict(DESKTOP='a'*64, MAC='b'*64),
        rulesVersion=p.RULES, dataVersion=p.DATA, configuration=p.CONFIGURATION,
        references=dict(qualifiedOrdinarySha256='6'*64, cardM8Sha256='7'*64,
            purchaseF0Sha256='8'*64, expansionESha256='9'*64, tokenControlVersion='FIXED_16_TO_15'),
        banks={name: dict(nativeOffset=offset, count=4096,
            seeds=[-1000000000+offset+index*8191 for index in range(4096)])
            for name, offset in zip(p.BANKS, (100, 200))}, maximumSeconds=p.SCIENCE_SECONDS,
        auditSeconds=p.AUDIT_SECONDS, maximumTerminalExecutions=p.MAXIMUM_TERMINALS, interval=copy.deepcopy(p.INTERVAL))


class PlanTests(unittest.TestCase):
    def test_exact_two_bank_global_ownership(self):
        plan = p.Plan.from_record(protocol_record())
        self.assertEqual([p.owner(i) for i in range(32)], ['DESKTOP']*20+['MAC']*12)
        self.assertEqual(sum(256+2*len(p.replays(i)) for i in range(32)), 8200)
        self.assertEqual(plan.record['maximumTerminalExecutions'], 16400)

    def test_host_origin_bytes_are_separate_from_shared_protocol(self):
        plan = p.Plan.from_record(protocol_record())
        subjects = dict(protocol=dict(path='/unit/protocol.json', sha256=plan.sha256),
            originArena=dict(path='/unit/arena.json', sha256='5'*64),
            originAdmission=dict(path='/unit/admission.json', sha256='3'*64),
            originProof=dict(path='/unit/proof.json', sha256='b'*64),
            arena=dict(path='/unit/evaluation.json', sha256='a'*64), confirmation=None)
        source = plan.source('mixed', 20, subjects)
        self.assertEqual(source['host'], 'MAC')
        with self.assertRaisesRegex(ValueError, 'original83'):
            plan.source('mixed', 0, subjects)

    def test_noncanonical_protocol_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'protocol.json'; path.write_bytes(p.canonical(protocol_record()))
            with self.assertRaisesRegex(ValueError, 'one LF'): p.Plan.from_file(path)

    def test_duplicate_bank_values_rejected(self):
        value = protocol_record(); value['banks']['homogeneous'] = value['banks']['mixed']
        with self.assertRaisesRegex(ValueError, 'disjoint'): p.Plan.from_record(value)

    def test_changed_candidate_rejected(self):
        value = protocol_record(); value['candidateBundleSha256'] = 'a'*64
        with self.assertRaisesRegex(ValueError, 'sole fixed candidate'): p.Plan.from_record(value)

    def test_shortened_banks_or_weaker_intervals_rejected(self):
        for key, number in (('count', 2048), ('nativeOffset', True)):
            value = protocol_record(); value['banks']['mixed'][key] = number
            with self.assertRaises(ValueError): p.Plan.from_record(value)
        value = protocol_record(); value['interval']['confidence'] = .95
        with self.assertRaises(ValueError): p.Plan.from_record(value)

    def test_clock_cannot_renew_or_extend(self):
        self.assertEqual(p.Clock(1., 28801.).remaining(2.), 28799.)
        with self.assertRaises(ValueError): p.Clock(1., 28802.)
        with self.assertRaises(ValueError): p.Clock(1., 28801.).remaining(28801.)

    def test_source_requires_confirmation_only_after_mixed(self):
        plan = p.Plan.from_record(protocol_record())
        subjects = dict(protocol=dict(path='/unit/protocol', sha256=plan.sha256),
            originArena=dict(path='/unit/origin', sha256='4'*64),
            originAdmission=dict(path='/unit/admission', sha256='3'*64),
            originProof=dict(path='/unit/proof', sha256='a'*64),
            arena=dict(path='/unit/evaluation', sha256='a'*64), confirmation=None)
        with self.assertRaisesRegex(ValueError, 'first-bank gate'): plan.source('homogeneous', 0, subjects)


if __name__ == '__main__': unittest.main()
