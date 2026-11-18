"""Invented qualified-ancestor metadata and native source shapes only; no actual game or fit."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

import learned_bid_contract as bids
import mac_leader_contract as policy
import robust_token_campaign as c


def fixture(root, qualified92=False, qualified91=False, origin=None, qualified94=False):
    certificate87=root/'certificate87.json';certificate87.write_text('{"explicitInvented87Certificate":true}\n')
    audit91=root/'91-completion-audit.json';audit91.write_text('explicit invented91 audit\n')
    audit92=root/'92-completion-audit.json';audit92.write_text('explicit invented92 audit\n')
    audit94=root/'94-completion-audit.json';audit94.write_text('explicit invented94 audit\n')
    path91=root/'study91/completion-audit.json';path94=root/'study94/completion-audit.json'
    path91.parent.mkdir();path94.parent.mkdir()
    closed91=dict(kind='mac-qualification-physical-closure-1',passed=True,
        allOwnedProcessesExited=True,qualified=qualified91,scienceComplete=qualified91,
        protocolSha256='15f47b8113241a3596e2db54446c223d3443dfd39d98e45b0c53c562951a7fac',
        actualCompletedLowerBound=8208 if qualified91 else 0,
        artifactPins={str(path91):c.sha(audit91)} if qualified91 else {})
    if qualified91:
        path91.write_bytes(audit91.read_bytes())
    if qualified94:path94.write_bytes(audit94.read_bytes())
    closed94=dict(kind='mac-qualification-physical-closure-1',passed=True,
        allOwnedProcessesExited=True,qualified=qualified94,scienceComplete=qualified94,
        protocolSha256=bids.FIXED['parentTrialProtocolSha256'],
        actualCompletedLowerBound=2064 if qualified94 else 0,
        artifactPins={str(path94):c.sha(audit94)} if qualified94 else {})
    closed92=dict(kind=policy.PARENT_CLOSURE_KIND,passed=True,allOwnedProcessesExited=True,
        qualified=qualified92,scienceComplete=qualified92,actualCompletedLowerBound=2648 if qualified92 else 0,
        artifactPins={str(audit92):c.sha(audit92)})
    terminal=dict(kind=policy.PARENT_TERMINAL_KIND,complete=qualified92,
        selectedCase=bids.CASES[5],completionAuditSha256=c.sha(audit92))
    for name,record in (('closed91',closed91),('closed92',closed92),('closed94',closed94),('terminal92',terminal)):
        c.write(root/(name+'.json'),record)
    subject=lambda name:dict(path=str(root/(name+'.json')),sha256=c.sha(root/(name+'.json')))
    expected='92' if qualified92 else '94' if qualified94 else '91' if qualified91 else '87'
    origin=origin or expected
    anchor=dict(origin=origin,bundleSha256=bids.CANDIDATE_SHA if expected!='87' else bids.OLD_CONTROL_SHA,
        selector=bids.CASES[5]['selector'] if expected=='92' else bids.CONTROL_SELECTOR if expected in ('94','91') else None,
        qualificationSha256=c.sha(audit92) if expected=='92' else c.sha(audit94) if expected=='94' else c.sha(audit91) if expected=='91' else c.sha(certificate87))
    frozen=dict(kind='mac-leader-search-anchor-freeze-1',passed=True,complete=True,qualified=True,
        methodSha256=policy.METHOD_SHA,study91Closure=subject('closed91'),study94Closure=subject('closed94'),**anchor)
    if expected=='87':
        frozen.update(old87Certificate=dict(path=str(certificate87),sha256=c.sha(certificate87)),
            old87Lineage=dict(qualified=True,bundleSha256=bids.OLD_CONTROL_SHA,
                inferenceAssetSha256=bids.ASSET_SHA,nominationSha256=bids.NOMINATION_SHA))
    c.write(root/'freeze.json',frozen)
    reserved=dict(kind='mac-leader-search-schedule-1',banks={});start=1
    for bank in policy.BANKS:
        reserved['banks'][bank['name']]=dict(count=bank['worlds'],nativeOffset=start,
            seeds=list(range(start,start+bank['worlds'])))
        start+=bank['worlds']
    return dict(copy.deepcopy(policy.FIXED),kind=policy.KIND,methodSha256=policy.METHOD_SHA,
        preparedEpoch=bids.GLOBAL_DEADLINE-10000,anchor=anchor,
        anchorFreeze=dict(path=str(root/'freeze.json'),sha256=c.sha(root/'freeze.json')),
        parentClosure=subject('closed92'),parentTerminal=subject('terminal92'),reservedSchedule=reserved,
        scheduleSha256=hashlib.sha256(c.canonical(reserved)).hexdigest(),
        banks={bank['name']:dict(bank,seeds=reserved['banks'][bank['name']]['seeds']) for bank in policy.BANKS},
        inferenceAssetSha256=bids.ASSET_SHA,nominationSha256=bids.NOMINATION_SHA,arenaSha256='a'*64)


def profile(plan):
    subject=lambda name,digest:dict(path='/constructed/'+name,sha256=digest)
    return dict(candidateBundle=subject('bundle.json',plan['anchor']['bundleSha256']),
        inferenceAsset=subject('asset.json',bids.ASSET_SHA),nomination=subject('nomination.json',bids.NOMINATION_SHA),
        arena=subject('arena.json','a'*64))


class MethodTest(unittest.TestCase):
    def test_exact_prospective_method_and_all_prescribed_executions(self):
        policy.read_method(Path(__file__).parent/'testdata/mac-leader-method.json')
        self.assertEqual(sum((8 if bank['name']=='development' else 1)*2*
            (bank['worlds']+len(bank['replayIndices'])) for bank in policy.BANKS),2128)

    def test_closed_unqualified92_and91_retain_the_qualified87_anchor(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=policy.validate(fixture(Path(temporary)))
            self.assertEqual(plan['anchor']['origin'],'87');self.assertIsNone(plan['anchor']['selector'])

    def test_qualified91_is_selected_after_an_unqualified92(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=policy.validate(fixture(Path(temporary),qualified91=True))
            self.assertEqual(plan['anchor']['origin'],'91');self.assertEqual(plan['anchor']['selector'],bids.CONTROL_SELECTOR)

    def test_complete_qualified92_has_priority_and_preserves_its_exact_sampler(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=policy.validate(fixture(Path(temporary),qualified92=True,qualified91=True))
            self.assertEqual(plan['anchor']['origin'],'92');self.assertEqual(plan['anchor']['selector'],bids.CASES[5]['selector'])

    def test_qualified94_has_priority_over91_after_an_unqualified92(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=policy.validate(fixture(Path(temporary),qualified94=True,qualified91=True))
            self.assertEqual(plan['anchor']['origin'],'94')
            self.assertEqual(plan['anchor']['selector'],bids.CONTROL_SELECTOR)

    def test_qualified94_cannot_be_skipped_for_a91_anchor(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=fixture(Path(temporary),qualified94=True,qualified91=True,origin='91')
            with self.assertRaisesRegex(ValueError,'no skipped winner'):policy.validate(value)

    def test_old91_qualification_metrics_cannot_be_relabelled_as94(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);value=fixture(root,qualified94=True,qualified91=True)
            frozen=c.read(root/'freeze.json');frozen['study94Closure']=frozen['study91Closure']
            (root/'freeze.json').write_bytes(c.canonical(frozen));value['anchorFreeze']['sha256']=c.sha(root/'freeze.json')
            with self.assertRaisesRegex(ValueError,'exact original closed94 trial'):policy.validate(value)

    def test_a_qualified92_winner_cannot_be_skipped_for_a_preferred91_anchor(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=fixture(Path(temporary),qualified92=True,qualified91=True,origin='91')
            with self.assertRaisesRegex(ValueError,'no skipped winner'):policy.validate(plan)

    def test_optional_stage_defers_with_less_than150_minutes_remaining(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=fixture(Path(temporary));plan['preparedEpoch']=bids.GLOBAL_DEADLINE-8999
            with self.assertRaisesRegex(ValueError,'fewer than150 original minutes'):policy.validate(plan)


class RoleAndGateTest(unittest.TestCase):
    def test_all_roles_have_identical_models_and_sampler_and_only_candidate_denial_is_requested(self):
        with tempfile.TemporaryDirectory() as temporary:
            plan=fixture(Path(temporary),qualified91=True)
            source=policy.native_source(plan,profile(plan),'development',policy.CASES[3],0,'b'*64)
            self.assertEqual(source['candidateBundle'],source['controlBundle'])
            self.assertEqual(source['selector'],source['controlSelector'])
            self.assertEqual(source['expansionDenial'],policy.CASES[3]['expansionDenial'])
            self.assertEqual(source['count'],64)

    def test_native_identity_rejects_a_mismatched_control_sampler(self):
        prepared=dict(scienceIdentity=dict(candidateBundleSha256=bids.CANDIDATE_SHA,
            controlAndRivalsBundleSha256=bids.CANDIDATE_SHA,selector=bids.CONTROL_SELECTOR,
            roles=['CANDIDATE','CONTROL'],expansionDenial=policy.CASES[0]['expansionDenial']))
        with self.assertRaisesRegex(ValueError,'identical anchor models'):policy.check_prepared(prepared,'mixed')

    def test_no_bid_concentration_gate_is_silently_added_to_leader_qualification(self):
        report=dict(wins=dict(lower99=.01),observedBids=dict(CANDIDATE=dict(observedConcentrationPassed=False)))
        self.assertTrue(policy.passed(report,'mixed'));self.assertTrue(policy.passed(report,'homogeneous'))

    def test_no_positive_bids_remain_missing_diagnostics_instead_of_a_leader_gate(self):
        summary=bids.bid_summary([dict(diagnostics=dict(CANDIDATE=dict(controlledBidCounts={'0':5})))],
            'CANDIDATE',require_positive=False)
        self.assertIsNone(summary['maximumSinglePositiveAmountShare'])
        self.assertFalse(summary['conditionalUnpredictabilityClaimed'])

    def test_leader_nomination_uses_win_mean_without_bid_concentration_filtering(self):
        reports={case['id']:dict(wins=dict(mean=.1),observedBids=dict(CANDIDATE=dict(observedConcentrationPassed=False)))
            for case in policy.CASES}
        self.assertEqual(policy.select(reports),policy.CASES[0])

    def test_zero_complete_development_means_leave_all_fresh_confirmation_banks_unopened(self):
        reports={case['id']:dict(wins=dict(mean=0)) for case in policy.CASES}
        self.assertIsNone(policy.select(reports))


if __name__=='__main__':unittest.main()
