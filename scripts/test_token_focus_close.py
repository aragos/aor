"""Narrow legacy status exception and actual shared finalization regression."""
from dataclasses import replace
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import mac_policy_close
import mac_policy_search as search
import robust_token_campaign as c
import token_focus_campaign as learner
import token_focus_close as fix
from test_token_focus_campaign import fixture


def transition_fixture(root):
    protocol=root/'protocol.json';profile=root/'profile.json';protocol.write_text('{}');profile.write_text('{}')
    science=root/'scientific.json';science.write_text('immutable evidence')
    status=root/'status.json';c.write(status,dict(status='running'));old=c.sha(status)
    clock=root/'clock.json';c.write(clock,dict(auditDeadlineEpoch=400))
    audit=root/'completion-audit.json';c.write(audit,dict(kind='token-focus-step-original-audit-1',passed=True,
        complete=True,qualified=False,terminalExecutions=1560,protocolSha256=c.sha(protocol),
        profileSha256=c.sha(profile),clockSha256=c.sha(clock),artifactPins={str(status):old,str(science):c.sha(science)}))
    terminal=root/'terminal.json';c.write(terminal,dict(kind='token-focus-step-terminal-1',study=96,complete=True,
        status='completed',qualified=False,knownCompletedTerminalExecutions=1560,reservedTerminalUpperBound=1560,
        completionAuditSha256=c.sha(audit),protocolSha256=c.sha(protocol),finishedEpoch=250))
    status.write_bytes(c.canonical(dict(kind='token-focus-step-status-1',status='completed',qualified=False,
        knownCompletedTerminalExecutions=1560,reservedTerminalUpperBound=1560,updatedEpoch=260)))
    identity=fix.OriginalIdentity(c.sha(protocol),c.sha(audit),c.sha(terminal),old,c.sha(status))
    return protocol,profile,root,identity


class StatusClosureTests(unittest.TestCase):
    def test_exact_status_exception_preserves_original_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            p,profile,root,identity=transition_fixture(Path(temporary));before=(root/'completion-audit.json').read_bytes()
            result=fix.transition(p,profile,root,identity=identity)
            self.assertEqual(result['scientificPinExceptions'],[]);self.assertFalse(result['originalStatusReconstructed'])
            self.assertEqual((root/'completion-audit.json').read_bytes(),before)

    def test_scientific_artifact_change_is_never_exempted(self):
        with tempfile.TemporaryDirectory() as temporary:
            p,profile,root,identity=transition_fixture(Path(temporary));(root/'scientific.json').write_text('changed')
            with self.assertRaisesRegex(ValueError,'every scientific, numerical and game artifact'):
                fix.transition(p,profile,root,identity=identity)

    def test_original_audit_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            p,profile,root,identity=transition_fixture(Path(temporary));(root/'completion-audit.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'exact root-reviewed original96'):
                fix.transition(p,profile,root,identity=identity)

    def test_another_final_status_is_not_allowed(self):
        with tempfile.TemporaryDirectory() as temporary:
            p,profile,root,identity=transition_fixture(Path(temporary));(root/'status.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'exact root-reviewed original96'):
                fix.transition(p,profile,root,identity=identity)

    def test_status_cannot_claim_human_qualification(self):
        with tempfile.TemporaryDirectory() as temporary:
            p,profile,root,identity=transition_fixture(Path(temporary));path=root/'status.json';value=c.read(path)
            value['qualified']=True;path.write_bytes(c.canonical(value));identity=replace(identity,final_status=c.sha(path))
            with self.assertRaisesRegex(ValueError,'exactly the shared lifecycle completion schema'):
                fix.transition(p,profile,root,identity=identity)

    def test_running_status_can_finalize_after_real_shared_audit_without_mutating_scientific_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);protocol,profile,output,terminal,contract,clock=fixture(root,study=97,separate_control=True)
            (output/'launch.json').write_bytes(c.canonical(dict(pid=100,processGroup=100,
                protocolSha256=c.sha(protocol),profileSha256=c.sha(profile))))
            owner=root/'owner.json';c.write(owner,dict(controllerPid=100,caffeinatePid=103))
            job=object.__new__(search.Campaign)
            job.protocol,job.profile_path,job.output,job.contract=protocol,profile,output,contract
            job.plan=c.read(protocol);job.clock=clock;job.known=job.reserved=1560
            job.cancelled=threading.Event();job.interrupted=False;job.process=None;job.run_work=lambda:terminal
            job.status(status='running',stage='holdout',case='FINAL')
            with patch.object(search.time,'time',return_value=clock['deadlineEpoch']+1):
                job.run(auditor=learner.audit)
            audit=c.read(output/'completion-audit.json')
            self.assertNotIn(str(output/'status.json'),audit['artifactPins'])
            self.assertEqual(c.read(output/'status.json')['status'],'completed')
            self.assertIn(str(output/'training-outcomes.json'),audit['artifactPins'])
            with patch.object(mac_policy_close.metadata,'processes',return_value=[]):
                result=mac_policy_close.close(protocol,profile,output,owner,contract)
            self.assertTrue(result['passed']);self.assertEqual(result['actualCompletedLowerBound'],1560)


if __name__=='__main__':unittest.main()
