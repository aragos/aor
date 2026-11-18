"""Constructed95 quotes, role sources and native matrices; no games, fits or RNG."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import local_bid_mixture_contract as policy
import learned_bid_contract as bids
import mac_policy_prepare as prepare
import mac_policy_search as search
import robust_token_campaign as c
import test_mac_policy_search as fixtures

TESTDATA=Path(__file__).parent/'testdata'


def source_fixture(case=0,bank='mixed'):
    plan=dict(arenaSha256='a'*64,banks={row['name']:dict(row,seeds=list(range(row['worlds']))) for row in policy.BANKS})
    profile={name:dict(path='/constructed/'+name,sha256=sha) for name,sha in
        [('candidateBundle',policy.CANDIDATE_SHA),('inferenceAsset',policy.ASSET_SHA),
         ('nomination',policy.NOMINATION_SHA),('arena','a'*64)]}
    return policy.native_source(plan,profile,bank,policy.CASES[case],0,'b'*64),plan,profile


def accepted_quote():
    return dict(command='buy-tokens 3',amount=3,originalOfferedAmounts=[-10,-2,-1,0,1,2,10],
        quote=dict(amount=3,cost=3,tokensReceived=2,cashAfter=7,stockAfter=0,poolAfter=2),
        ownerResourceBefore=dict(cash=10,stock=2,pool=0),originalObservationSha256='1'*64,
        acceptedObservationSha256='2'*64,acceptedCandidateSha256='3'*64,
        acceptedQuotedViewSha256='4'*64,retainedForDecisionTrace=True)


def trace_world():
    candidate=dict(controlledBidCounts={'3':1,'2':1},controlledUnofferedTokenBidCounts={'3':1},
        controlledTokenQuoteAdmissions=[accepted_quote()])
    control=dict(controlledBidCounts={'2':1},controlledUnofferedTokenBidCounts={},controlledTokenQuoteAdmissions=[])
    return dict(diagnostics=dict(CANDIDATE=candidate,CONTROL=control))


def baseline_fixture(root):
    protocol=root/'baseline-protocol.json'
    protocol.write_bytes((TESTDATA/'fixed-qualification94-protocol.json').read_bytes())
    audit=root/'baseline-audit.json'
    c.write(audit,dict(kind='fixed-policy-qualification-original-audit-1',passed=True,complete=True,
        qualified=True,protocolSha256=policy.BASELINE_PROTOCOL_SHA,terminalExecutions=2064))
    terminal=root/'baseline-terminal.json'
    c.write(terminal,dict(kind='fixed-policy-qualification-terminal-1',status='completed',complete=True,
        qualified=True,protocolSha256=policy.BASELINE_PROTOCOL_SHA,completionAuditSha256=c.sha(audit)))
    closure=root/'baseline-closure.json'
    c.write(closure,dict(kind='mac-qualification-physical-closure-1',passed=True,scienceComplete=True,
        qualified=True,allOwnedProcessesExited=True,protocolSha256=policy.BASELINE_PROTOCOL_SHA,
        actualCompletedLowerBound=2064,originalClocksRenewed=False,auditReruns=0,
        artifactPins={str(audit):c.sha(audit)}))
    return dict(qualifiedBaseline94={name:dict(path=str(path),sha256=c.sha(path)) for name,path in
        [('protocol',protocol),('terminal',terminal),('closure',closure),('completionAudit',audit)]})


class SourceTests(unittest.TestCase):
    def test_eight_cases_preserve_exact_order_and_mixture_only(self):
        self.assertEqual([case['id'] for case in policy.CASES],[f'n{index:02d}' for index in range(8)])
        self.assertEqual(policy.CASES[0]['tokenNeighborMixture'],dict(kind='local-token-neighbor-mixture-1',
            departureProbability=.25,radius=1,admission='OFFERED_ONLY'))
        self.assertEqual(policy.CASES[-1]['tokenNeighborMixture']['admission'],'QUOTED_LEGAL_NEIGHBORS')

    def test_learned_roles_keep_base94_and_change_only_candidate_mixture(self):
        first,plan,profile=source_fixture();other=policy.native_source(plan,profile,'mixed',policy.CASES[7],0,'b'*64)
        self.assertEqual({key:value for key,value in first.items() if key!='tokenNeighborMixture'},
                         {key:value for key,value in other.items() if key!='tokenNeighborMixture'})
        self.assertEqual(first['selector'],bids.CONTROL_SELECTOR)
        self.assertEqual(first['controlSelector'],bids.CONTROL_SELECTOR)
        self.assertEqual(first['controlBundle'],first['candidateBundle'])
        self.assertNotIn('expansionDenial',first)

    def test_reference_roles_leave_old87_overrides_absent(self):
        source,_,_=source_fixture(bank='reference')
        self.assertNotIn('controlBundle',source)
        self.assertNotIn('controlSelector',source)
        self.assertEqual(source['selector'],bids.CONTROL_SELECTOR)

    def test_source_cannot_broaden_its_original_case(self):
        source,plan,profile=source_fixture();source['selector']={}
        with self.assertRaisesRegex(ValueError,'candidate-only95'):
            policy.check_source(plan,profile,source,'mixed',policy.CASES[0],0,'b'*64)

    def test_exact2648_games_include_all_original_reproductions(self):
        total=0
        for bank in policy.BANKS:
            cases=policy.CASES if bank['name']=='development' else [policy.CASES[0]]
            for case in cases:total+=2*(bank['worlds']+len(bank['replayIndices']))
        self.assertEqual(total,2648)

    def test_unbound_or_wrong_native_manifest_is_never_admitted(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'arbitrary-native.json';path.write_text('{}')
            with self.assertRaisesRegex(ValueError,'exact root-reviewed95 native'):
                policy.native_binding(path)

    def test_prepared_identity_cannot_enable_mixture_on_reference_control(self):
        source,_,_=source_fixture(bank='reference')
        identity=dict(candidateBundleSha256=policy.CANDIDATE_SHA,controlAndRivalsBundleSha256=policy.OLD_CONTROL_SHA,
            selector=policy.CONTROL_SELECTOR,tokenNeighborMixture=source['tokenNeighborMixture'],roles=['CANDIDATE','CONTROL'])
        policy.check_prepared(dict(scienceIdentity=identity),'reference')
        identity['controlSelector']=policy.CONTROL_SELECTOR
        with self.assertRaisesRegex(ValueError,'candidate-only local mixture'):
            policy.check_prepared(dict(scienceIdentity=identity),'reference')

    def test_root_method_hash_and_exact_grid_match(self):
        method=TESTDATA/'local-bid-mixture-method.json'
        self.assertEqual(policy.read_method(method)['caseConfigs'],policy.CASES)

    def test_shared_preparer_checks_exact_declared_model_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'model.json';path.write_text('{}')
            subject=dict(path=str(path),sha256=c.sha(path))
            prepared=dict(inputPins={str(path):c.sha(path)})
            policy.check_inputs(prepared,dict(candidateBundle=subject))
            path.write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError,'numerical reference input changed'):
                policy.check_inputs(prepared,dict(candidateBundle=subject))


class QuoteEvidenceTests(unittest.TestCase):
    def check(self,world):
        source,_,_=source_fixture(4)
        policy.quote_traces([world],source)

    def test_authority_quote_is_distinct_from_original_offered_observation(self):
        self.check(trace_world())

    def test_missing_trace_cannot_hide_accepted_unoffered_bid(self):
        world=trace_world();world['diagnostics']['CANDIDATE']['controlledTokenQuoteAdmissions']=[]
        with self.assertRaisesRegex(ValueError,'one retained original owner-quote'):self.check(world)

    def test_control_may_not_acquire_candidate_mixture(self):
        world=trace_world();world['diagnostics']['CONTROL']=copy.deepcopy(world['diagnostics']['CANDIDATE'])
        with self.assertRaisesRegex(ValueError,'Controls and offered-only'):self.check(world)

    def test_offered_only_case_may_not_claim_unoffered_admission(self):
        source,_,_=source_fixture(0)
        with self.assertRaisesRegex(ValueError,'Controls and offered-only'):policy.quote_traces([trace_world()],source)

    def test_original_offer_list_cannot_be_rewritten_to_include_new_amount(self):
        world=trace_world();world['diagnostics']['CANDIDATE']['controlledTokenQuoteAdmissions'][0]['originalOfferedAmounts'].append(3)
        with self.assertRaisesRegex(ValueError,'originally unoffered'):self.check(world)

    def test_neighbor_must_remain_inside_declared_local_radius(self):
        world=trace_world();world['diagnostics']['CANDIDATE']['controlledTokenQuoteAdmissions'][0]['originalOfferedAmounts']=[-10,0,10]
        with self.assertRaisesRegex(ValueError,'declared local neighbor'):self.check(world)

    def test_accepted_quote_retains_exact_own_resource_consequences(self):
        world=trace_world();world['diagnostics']['CANDIDATE']['controlledTokenQuoteAdmissions'][0]['quote']['tokensReceived']=3
        with self.assertRaisesRegex(ValueError,'affordable signed purchase exactly'):self.check(world)

    def test_the_accepted_observation_must_not_pretend_to_be_the_original(self):
        world=trace_world();record=world['diagnostics']['CANDIDATE']['controlledTokenQuoteAdmissions'][0]
        record['acceptedObservationSha256']=record['originalObservationSha256']
        with self.assertRaisesRegex(ValueError,'provenance must remain distinct'):self.check(world)

    def test_trace_must_have_been_retained_for_the_actual_decision(self):
        world=trace_world();world['diagnostics']['CANDIDATE']['controlledTokenQuoteAdmissions'][0]['retainedForDecisionTrace']=False
        with self.assertRaisesRegex(ValueError,'actual decision trace'):self.check(world)

    def test_unoffered_counts_must_remain_in_total_actual_bid_counts(self):
        world=trace_world();world['diagnostics']['CANDIDATE']['controlledBidCounts']={'2':1}
        with self.assertRaisesRegex(ValueError,'actual controlled bid counts'):self.check(world)


class EvidenceAndGateTests(unittest.TestCase):
    def test_preparation_defers_with_fewer_than_three_original_hours(self):
        value=dict(policy.FIXED,kind=policy.KIND,methodSha256=policy.METHOD_SHA,
            nativeJarSha256='a'*64,nativeSourceReadiness=dict(sha256='b'*64),
            preparedEpoch=policy.GLOBAL_DEADLINE-10799)
        with patch.multiple(policy,NATIVE_SHA='a'*64,NATIVE_READINESS_SHA='b'*64):
            with self.assertRaisesRegex(ValueError,'fewer than three original hours'):
                policy.validate(value)

    def test_exact_qualified94_baseline_metadata_is_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=baseline_fixture(Path(temporary));policy.validate_baseline(value)
            item=value['qualifiedBaseline94']['closure'];record=c.read(item['path']);record['qualified']=False
            Path(item['path']).write_bytes(c.canonical(record));item['sha256']=c.sha(item['path'])
            with self.assertRaisesRegex(ValueError,'originally qualified immutable'):policy.validate_baseline(value)

    def test_baseline_model_or_selector_cannot_be_swapped(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=baseline_fixture(Path(temporary));item=value['qualifiedBaseline94']['protocol']
            record=c.read(item['path']);record['selector']=None
            Path(item['path']).write_bytes(c.canonical(record));item['sha256']=c.sha(item['path'])
            with self.assertRaisesRegex(ValueError,'originally qualified immutable'):policy.validate_baseline(value)

    def test_full95_reservation_keeps_all832_worlds_active(self):
        schedule=TESTDATA/'local-bid-mixture-schedule.json'
        reserved=c.read(schedule)
        value=dict(reservedSchedule=reserved,scheduleSha256=c.sha(schedule),fullReservedWorlds=832,
            activeReservedWorlds=832,unusedReservedWorlds=0,
            banks={bank['name']:dict(bank,seeds=reserved['banks'][bank['name']]['seeds']) for bank in policy.BANKS})
        policy.validate_worlds(value)
        value['banks']['mixed']['seeds']=value['banks']['mixed']['seeds'][:-1]
        with self.assertRaisesRegex(ValueError,'exact full original stream'):policy.validate_worlds(value)

    def test_nomination_uses_only_complete_positive_win_and_concentration(self):
        reports={case['id']:dict(wins=dict(mean=.1),observedBids=dict(CANDIDATE=dict(observedConcentrationPassed=True))) for case in policy.CASES}
        reports['n00']['observedBids']['CANDIDATE']['observedConcentrationPassed']=False
        self.assertEqual(policy.select(reports)['id'],'n01')

    def test_none_eligible_means_no_fallback(self):
        reports={case['id']:dict(wins=dict(mean=0),observedBids=dict(CANDIDATE=dict(observedConcentrationPassed=True))) for case in policy.CASES}
        self.assertIsNone(policy.select(reports))

    def test_reference_does_not_use_the_diagnostic_concentration_gate(self):
        report=dict(wins=dict(lower99=.01),observedBids=dict(CANDIDATE=dict(observedConcentrationPassed=False)))
        self.assertFalse(policy.passed(report,'mixed'))
        self.assertFalse(policy.passed(report,'homogeneous'))
        self.assertTrue(policy.passed(report,'reference'))

    def test_complete_constructed2648_matrix_reaggregates_all_original_gates(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixtures.fixture(Path(temporary),policy_contract=policy)
            result=search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)
            self.assertTrue(result['qualified']);self.assertEqual(result['terminalExecutions'],2648)
            self.assertEqual(result['selectedCase']['id'],'n01')
            self.assertEqual(result['protectedReservation'],dict(fullReservedWorlds=832,activeReservedWorlds=832,
                unusedReservedWorlds=0,fullScheduleSha256='f'*64))

    def test_original95_clock_cannot_renew_or_restart_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixtures.fixture(Path(temporary),policy_contract=policy)
            clock['deadlineEpoch']+=1;(output/'clock.json').write_bytes(c.canonical(clock))
            with self.assertRaisesRegex(ValueError,'unchanged bounded audit'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)

    def test_extra_bank_cannot_disappear_from_accounting(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixtures.fixture(Path(temporary),policy_contract=policy)
            (output/'chunks/extra').mkdir()
            with self.assertRaisesRegex(ValueError,'unreported or ungated'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)

    def test_mixture_cannot_change_repeated_control_worlds(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixtures.fixture(Path(temporary),policy_contract=policy)
            folder=output/'chunks/development/n00/chunk-0';path=folder/'native/worlds.jsonl'
            rows=[json.loads(line) for line in path.read_text().splitlines()]
            next(row for row in rows if row['index']==1)['roles']['CONTROL']=fixtures.game(1)
            path.write_bytes(b''.join(c.canonical(row) for row in rows))
            finished=c.read(folder/'native/finished.json');finished['files']['worlds.jsonl']=c.sha(path)
            (folder/'native/finished.json').write_bytes(c.canonical(finished))
            receipt=c.read(folder/'receipt.json');receipt['finishedSha256']=c.sha(folder/'native/finished.json')
            (folder/'receipt.json').write_bytes(c.canonical(receipt))
            path=output/'reports/development.json';record=c.read(path)
            record['cases']['n00']=policy.aggregate(rows,c.read(protocol)['banks']['development'])
            path.write_bytes(c.canonical(record))
            with self.assertRaisesRegex(ValueError,'common CONTROL worlds'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)


if __name__=='__main__':unittest.main()
