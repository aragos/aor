"""Source and recorded-count fixtures only; no scientific RNG, model forward or native game."""
import copy
import hashlib
from pathlib import Path
import unittest

import learned_bid_contract as policy
import robust_token_campaign as c


def plan():
    start = 1
    reserved = dict(kind='learned-bid-search-schedule-1',banks={})
    for bank in policy.BANKS:
        count = bank['worlds']
        reserved['banks'][bank['name']] = dict(nativeOffset=start,count=count,
            seeds=list(range(start,start+count)))
        start += count
    return dict(copy.deepcopy(policy.FIXED), kind=policy.KIND, methodSha256=policy.METHOD_SHA,
        preparedEpoch=policy.GLOBAL_DEADLINE-20000, reservedSchedule=reserved,
        scheduleSha256=hashlib.sha256(c.canonical(reserved)).hexdigest(),
        banks={bank['name']:dict(copy.deepcopy(bank),seeds=reserved['banks'][bank['name']]['seeds'])
            for bank in policy.BANKS}, inferenceAssetSha256=policy.ASSET_SHA,
        nominationSha256=policy.NOMINATION_SHA, arenaSha256='a'*64)


def profile():
    subject = lambda name, digest:dict(path='/constructed/'+name,sha256=digest)
    return dict(candidateBundle=subject('fca/bundle.json',policy.CANDIDATE_SHA),
        inferenceAsset=subject('inference.json',policy.ASSET_SHA),
        nomination=subject('nomination.json',policy.NOMINATION_SHA),
        arena=subject('arena.json','a'*64))


def diagnostics(values):
    return [dict(diagnostics=dict(CANDIDATE=dict(controlledBidCounts=values)))]


def report(mean, counts):
    return dict(wins=dict(mean=mean,lower99=mean-.01),
        observedBids=dict(CANDIDATE=policy.bid_summary(diagnostics(counts),'CANDIDATE')))


class MethodTest(unittest.TestCase):
    def test_exact_root_method_is_retained_without_changing_its_bytes(self):
        path=Path(__file__).parent/'testdata/learned-bid-method.json'
        method=policy.read_method(path)
        self.assertEqual(method['maximumTerminalExecutions'],5208)
        self.assertEqual(method['caseConfigs'],policy.CASES)

    def test_four_banks_have_the_prospectively_complete_counts(self):
        value=policy.validate(plan())
        self.assertEqual([len(bank['seeds']) for bank in value['banks'].values()],
            [128,512,512,512])

    def test_preparation_requires_three_original_hours(self):
        value=plan();value['preparedEpoch']=policy.GLOBAL_DEADLINE-10799
        with self.assertRaisesRegex(ValueError,'fewer than three original hours'):
            policy.validate(value)

    def test_reserved_worlds_cannot_be_replaced_or_topped_up(self):
        value=plan();value['banks']['reference']['seeds'][-1]+=1
        with self.assertRaisesRegex(ValueError,'complete newly reserved'):
            policy.validate(value)

    def test_case_order_and_wider_support_cannot_change_after_review(self):
        value=plan();value['caseConfigs'].reverse()
        with self.assertRaisesRegex(ValueError,'prospective method parameter'):
            policy.validate(value)

    def test_accounting_includes_all_three_separate_confirmations_and_replays(self):
        total=0
        for bank in policy.BANKS:
            cases=8 if bank['name']=='development' else 1
            total+=cases*2*(bank['worlds']+len(bank['replayIndices']))
        self.assertEqual(total,5208)


class RoleSourceTest(unittest.TestCase):
    def test_learned_control_and_every_rival_share_the_exact_candidate_bundle(self):
        source=policy.native_source(plan(),profile(),'mixed',policy.CASES[0],0,'b'*64)
        self.assertEqual(source['candidateBundle'],source['controlBundle'])
        self.assertEqual(source['controlSelector'],policy.CONTROL_SELECTOR)
        self.assertEqual(source['selector'],policy.CASES[0]['selector'])
        self.assertNotIn('expansionDenial',source)

    def test_reference_omits_role_overrides_for_native_exact87_defaults(self):
        source=policy.native_source(plan(),profile(),'reference',policy.CASES[7],0,'b'*64)
        self.assertNotIn('controlBundle',source);self.assertNotIn('controlSelector',source)
        self.assertEqual(source['candidateBundle']['sha256'],policy.CANDIDATE_SHA)
        self.assertEqual(source['opponentStyle'],'QUALIFIED')

    def test_reference_zero_game_identity_must_prove_old87_effective_model(self):
        prepared=dict(scienceIdentity=dict(candidateBundleSha256=policy.CANDIDATE_SHA,
            controlAndRivalsBundleSha256=policy.OLD_CONTROL_SHA,roles=['CANDIDATE','CONTROL']))
        policy.check_prepared(prepared,'reference')
        prepared['scienceIdentity']['controlAndRivalsBundleSha256']=policy.CANDIDATE_SHA
        with self.assertRaisesRegex(ValueError,'unchanged87 comparator'):
            policy.check_prepared(prepared,'reference')

    def test_learned_identity_rejects_a_missing_control_sampler(self):
        prepared=dict(scienceIdentity=dict(candidateBundleSha256=policy.CANDIDATE_SHA,
            controlAndRivalsBundleSha256=policy.CANDIDATE_SHA,roles=['CANDIDATE','CONTROL']))
        with self.assertRaisesRegex(ValueError,'exact learned'):
            policy.check_prepared(prepared,'homogeneous')

    def test_development_is_one128_world_chunk_and_confirmation_two256_chunks(self):
        value=plan();actual=profile()
        development=policy.native_source(value,actual,'development',policy.CASES[0],0,'b'*64)
        last=policy.native_source(value,actual,'mixed',policy.CASES[0],1,'b'*64)
        self.assertEqual((development['globalStart'],development['count']),(0,128))
        self.assertEqual((last['globalStart'],last['count']),(256,256))

    def test_new_hidden_override_cannot_enter_a_reviewed_native_request(self):
        value=plan();actual=profile()
        source=policy.native_source(value,actual,'development',policy.CASES[0],0,'b'*64)
        source['candidateOnly']=True
        with self.assertRaisesRegex(ValueError,'paired roles'):
            policy.check_source(value,actual,source,'development',policy.CASES[0],0,'b'*64)


class ObservedConcentrationTest(unittest.TestCase):
    def test_concentration_includes_only_positive_bids(self):
        result=policy.bid_summary(diagnostics({'21':80,'22':20,'0':1000,'-21':1000}),'CANDIDATE')
        self.assertEqual(result['positiveCount'],100)
        self.assertEqual(result['maximumSinglePositiveAmountShare'],.8)
        self.assertTrue(result['observedConcentrationPassed'])
        self.assertFalse(result['conditionalUnpredictabilityClaimed'])

    def test_replacing21_with_another_constant_bid_is_rejected(self):
        result=policy.bid_summary(diagnostics({'23':100}),'CANDIDATE')
        self.assertEqual(result['positiveBid21Share'],0)
        self.assertFalse(result['observedConcentrationPassed'])

    def test_empty_positive_counts_refuse_admission_instead_of_faking_entropy(self):
        with self.assertRaisesRegex(ValueError,'empty positive controlled bid counts'):
            policy.bid_summary(diagnostics({'0':100,'-10':2}),'CANDIDATE')

    def test_recorded_amount_aliases_cannot_split_one_bid_into_apparent_diversity(self):
        with self.assertRaisesRegex(ValueError,'native wire values'):
            policy.bid_summary(diagnostics({'21':80,'021':20}),'CANDIDATE')

    def test_complete_world_counts_are_aggregated_before_applying_threshold(self):
        rows=diagnostics({'21':81,'22':19})+diagnostics({'21':79,'22':21})
        result=policy.bid_summary(rows,'CANDIDATE')
        self.assertTrue(result['observedConcentrationPassed'])
        self.assertEqual(result['maximumSinglePositiveAmountShare'],.8)

    def test_best_win_candidate_is_filtered_when_its_bids_remain_exploitable(self):
        reports={case['id']:report(.1,{'21':50,'22':50}) for case in policy.CASES}
        reports['s00']=report(.9,{'21':99,'22':1})
        self.assertEqual(policy.select(reports),policy.CASES[1])

    def test_zero_win_or_concentrated_candidates_do_not_open_confirmation(self):
        reports={case['id']:report(0,{'21':50,'22':50}) for case in policy.CASES}
        reports['s00']=report(.9,{'21':100})
        self.assertIsNone(policy.select(reports))

    def test_mixed_and_homogeneous_gate_require_concentration_as_well_as_win(self):
        concentrated=report(.2,{'21':100})
        self.assertFalse(policy.passed(concentrated,'mixed'))
        self.assertFalse(policy.passed(concentrated,'homogeneous'))

    def test_old87_reference_reports_concentration_without_using_it_as_a_gate(self):
        self.assertTrue(policy.passed(report(.2,{'21':100}),'reference'))

    def test_reference99_win_gate_is_strict(self):
        tied=report(.01,{'21':50,'22':50})
        self.assertFalse(policy.passed(tied,'reference'))


if __name__=='__main__':unittest.main()
