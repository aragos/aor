"""Constructed protocol identities; no reserved streams or scientific subjects."""
import copy
import unittest

import coordinated_qualification_plan as legacy
import coordinated_win_qualification_plan as p
from test_coordinated_qualification_plan import protocol_record as old_record


def protocol_record():
    value = old_record()
    for key in ('originAdmissionSha256', 'originArenaSha256', 'originProofSha256'):
        del value[key]
    return dict(value, kind=p.PROTOCOL_KIND, profile=p.PROFILE,
        candidateBundleSha256='c'*64, inferenceAssetSha256='a'*64,
        nominationSha256='b'*64, interval=copy.deepcopy(p.INTERVAL))


class PlanTests(unittest.TestCase):
    def test_nominated_candidate_has_no_family_constant(self):
        value = protocol_record()
        self.assertEqual(p.Plan.from_record(value).record['candidateBundleSha256'], 'c'*64)
        value['candidateBundleSha256'] = 'd'*64
        self.assertEqual(p.Plan.from_record(value).record['candidateBundleSha256'], 'd'*64)

    def test_old_and_new_protocols_are_not_interchangeable(self):
        with self.assertRaises(ValueError): p.Plan.from_record(old_record())
        with self.assertRaises(ValueError): legacy.Plan.from_record(protocol_record())

    def test_reward_gate_cannot_be_added_to_new_version(self):
        value = protocol_record(); value['interval'] = copy.deepcopy(legacy.INTERVAL)
        with self.assertRaisesRegex(ValueError, 'win-only'): p.Plan.from_record(value)

    def test_source_binds_nomination_before_heldout(self):
        plan = p.Plan.from_record(protocol_record())
        subjects = {key: dict(path='/unit/'+key, sha256=sha) for key, sha in
            [('protocol', plan.sha256), ('inferenceAsset', 'a'*64), ('nomination', 'b'*64), ('arena', 'd'*64)]}
        subjects['confirmation'] = None
        self.assertEqual(plan.source('mixed', 20, subjects)['host'], 'MAC')
        with self.assertRaises(ValueError): plan.source('homogeneous', 20, subjects)
        subjects['nomination']['sha256'] = 'e'*64
        with self.assertRaisesRegex(ValueError, 'nominated'): plan.source('mixed', 20, subjects)

    def test_fixed_world_and_terminal_counts_preserved(self):
        plan = p.Plan.from_record(protocol_record())
        self.assertEqual(sum(256+2*len(p.replays(i)) for i in range(32)), 8200)
        self.assertEqual(plan.record['maximumTerminalExecutions'], 16400)
        value = protocol_record(); value['banks']['homogeneous'] = value['banks']['mixed']
        with self.assertRaisesRegex(ValueError, 'disjoint'): p.Plan.from_record(value)


if __name__ == '__main__': unittest.main()
