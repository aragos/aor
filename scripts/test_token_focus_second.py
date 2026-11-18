"""Stage identity, sealed origin and rival separation; synthetic evidence only."""
import copy
from pathlib import Path
import tempfile
import unittest

import token_focus_contract as base
import token_focus_second_contract as policy
import token_focus_campaign as learner
import robust_token_campaign as c
from test_token_focus_campaign import fixture,subject

TESTDATA=Path(__file__).parent/'testdata'


def parent(root,*,complete=True,signal=False):
    candidate=root/'sole-final.json';candidate.write_text('invented96final')
    final=subject(candidate);audit=root/'completion-audit.json'
    c.write(audit,dict(kind='token-focus-step-original-audit-1',complete=True,passed=True,qualified=False,
        researchSignal=signal,protocolSha256=policy.PARENT_PROTOCOL_SHA,terminalExecutions=1560,
        artifactPins={str(candidate):final['sha256']}))
    terminal=root/'terminal.json'
    c.write(terminal,dict(kind=policy.PARENT_TERMINAL_KIND,study=96,complete=complete,
        status='completed' if complete else 'interrupted',qualified=False,researchSignal=signal,
        protocolSha256=policy.PARENT_PROTOCOL_SHA,candidateBundle=final,
        knownCompletedTerminalExecutions=1560,reservedTerminalUpperBound=1560,completionAuditSha256=c.sha(audit)))
    closed=root/'closure.json'
    c.write(closed,dict(kind=policy.PARENT_CLOSURE_KIND,study=96,passed=True,qualified=False,
        scienceComplete=complete,protocolSha256=policy.PARENT_PROTOCOL_SHA,allOwnedProcessesExited=True,
        originalClocksRenewed=False,auditReruns=0,actualCompletedLowerBound=1560,
        actualAttemptedLowerBound=1560,actualAttemptedUpperBound=1560,knownCompletedTerminalExecutions=1560,
        controllerReservedTerminalUpperBound=1560,
        artifactPins={str(candidate):final['sha256'],str(audit):c.sha(audit),str(terminal):c.sha(terminal)}))
    return subject(terminal),subject(closed),final


class SecondStageTests(unittest.TestCase):
    def test_registered97_method_is_exact(self):
        self.assertEqual(policy.read_method(TESTDATA/'token-focus-second-method.json')['study'],97)

    def test_96_default_sources_match_original_zero_game_native_inputs(self):
        value=c.read(TESTDATA/'token-focus-96-source-golden.json')
        for bank,expected in value['examples'].items():
            self.assertEqual(base.native_source(value['plan'],value['profile'],bank,dict(id='START'),0,
                value['protocolSha256']),expected)

    def test_96_final_is_admitted_regardless_of_holdout_signal(self):
        for signal in (False,True):
            with self.subTest(signal=signal),tempfile.TemporaryDirectory() as temporary:
                terminal,closed,final=parent(Path(temporary),signal=signal)
                self.assertEqual(policy.origin_policy(terminal,closed)['original96Final'],final)

    def test_partial96_defers_without_reading_a_partial_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);terminal,closed,_=parent(root,complete=False)
            (root/'completion-audit.json').write_text('Do not parse partial effects.')
            with self.assertRaisesRegex(ValueError,'Defer study97'):
                policy.origin_policy(terminal,closed)

    def test_missing96_terminal_or_closure_defers_without_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            with self.assertRaisesRegex(ValueError,'Defer study97 until the original96'):
                policy.origin_policy(dict(path=str(root/'missing-terminal'),sha256='a'*64),
                    dict(path=str(root/'missing-closure'),sha256='b'*64))

    def test_an_alternative_origin_cannot_replace_the_audited_final(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);terminal,closed,_=parent(root)
            value=c.read(terminal['path']);other=root/'alternative.json';other.write_text('unapprovedothercenter')
            value['candidateBundle']=subject(other);Path(terminal['path']).write_bytes(c.canonical(value))
            closure=c.read(closed['path']);closure['artifactPins'][terminal['path']]=c.sha(terminal['path'])
            Path(closed['path']).write_bytes(c.canonical(closure));closed=subject(closed['path'])
            with self.assertRaisesRegex(ValueError,'original audited96 sole FINAL'):
                policy.origin_policy(subject(terminal['path']),closed)

    def test_terminal_changed_after_closure_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);terminal,closed,_=parent(root)
            value=c.read(terminal['path']);value['researchSignal']=not value['researchSignal']
            Path(terminal['path']).write_bytes(c.canonical(value))
            with self.assertRaisesRegex(ValueError,'seal its exact terminal'):
                policy.origin_policy(subject(terminal['path']),closed)

    def test_inconsistent_original_attempted_upper_bound_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);terminal,closed,_=parent(root)
            value=c.read(closed['path']);value['actualAttemptedUpperBound']=1561
            Path(closed['path']).write_bytes(c.canonical(value))
            with self.assertRaisesRegex(ValueError,'all1560 execution counters'):
                policy.origin_policy(terminal,subject(closed['path']))

    def test97_uses96_weights_for_start_and94_weights_for_rivals(self):
        old=c.read(TESTDATA/'token-focus-96-source-golden.json')
        plan=copy.deepcopy(old['plan']);profile=copy.deepcopy(old['profile'])
        profile['controlBundle']=copy.deepcopy(profile['candidateBundle'])
        profile['candidateBundle']=dict(path='/invented/96-final.json',sha256='b'*64)
        plan['study']=97;plan['originWeightsSha256']='b'*64
        source=policy.native_source(plan,profile,'holdout',dict(id='START'),0,'c'*64)
        self.assertEqual(source['candidateBundle']['sha256'],'b'*64)
        self.assertEqual(source['controlBundle']['sha256'],base.CANDIDATE_SHA)

    def test97_refuses_implicit_rival_weights_from_the_new_origin(self):
        old=c.read(TESTDATA/'token-focus-96-source-golden.json');plan=old['plan'];profile=old['profile']
        profile['candidateBundle']=dict(path='/invented/96-final.json',sha256='b'*64);plan['originWeightsSha256']='b'*64
        with self.assertRaisesRegex(ValueError,'original fca full-policy weights'):
            policy.native_source(plan,profile,'holdout',dict(id='START'),0,'c'*64)

    def test_fresh97_reservation_is_complete(self):
        path=TESTDATA/'token-focus-second-schedule.json';reserved=c.read(path)
        policy.validate_worlds(dict(reservedSchedule=reserved,scheduleSha256=c.sha(path),banks=reserved['banks']))

    def test_shared_full_1560_auditor_handles_distinct_start_and_control(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary),study=97,separate_control=True)
            result=learner.audit(*args[:5],now=lambda:args[5]['deadlineEpoch']+1)
            self.assertEqual(result['terminalExecutions'],1560);self.assertFalse(result['qualified'])
            self.assertEqual(result['kind'],'token-focus-second-original-audit-1')

    def test_numerical_receipt_cannot_substitute_registered96_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary),study=97,separate_control=True)
            path=args[2]/'numeric/update/artifacts/receipt.json';value=c.read(path)
            value['stageSpec']['study']=96;path.write_bytes(c.canonical(value))
            with self.assertRaisesRegex(ValueError,'source-registered stage identity'):
                learner.numerical_receipt(args[2],'update')


if __name__=='__main__':unittest.main()
