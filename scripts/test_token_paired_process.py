from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import token_paired_process as process


class ProcessTests(unittest.TestCase):
    def test_peer_failure_terminates_running_child_without_new_group(self):
        child=SimpleNamespace(returncode=None)
        child.poll=lambda:child.returncode
        child.terminate=lambda:setattr(child,'returncode',-15)
        child.wait=lambda **_:child.returncode
        child.kill=lambda:self.fail('Graceful child exit should not be killed again.')
        clock=SimpleNamespace(remaining=lambda _now:100.)
        ticks=iter((0.,31.));checks=[]
        def live(_profile):
            checks.append(1)
            if len(checks)>1:raise ValueError('peer failed')
        with tempfile.TemporaryDirectory() as directory,patch.object(process,'live',live), \
             patch.object(process.subprocess,'Popen',return_value=child) as spawn, \
             patch.object(process.time,'monotonic',lambda:next(ticks)):
            with self.assertRaisesRegex(ValueError,'peer failed'):
                process.run_child({'_clock':clock},['constructed-native'],Path(directory)/'child.log')
            self.assertEqual(child.returncode,-15)
            self.assertNotIn('start_new_session',spawn.call_args.kwargs)
            self.assertEqual(spawn.call_count,1)

    def test_peer_admission_failure_prevents_child_start(self):
        with patch.object(process,'live',side_effect=ValueError('closed')),patch.object(process.subprocess,'Popen') as spawn:
            with self.assertRaisesRegex(ValueError,'closed'):process.run_child({},['native'],'unused')
            spawn.assert_not_called()

    def test_nonzero_child_is_not_retried(self):
        child=SimpleNamespace(returncode=7,poll=lambda:7)
        with tempfile.TemporaryDirectory() as directory,patch.object(process,'live'), \
             patch.object(process.subprocess,'Popen',return_value=child) as spawn:
            with self.assertRaisesRegex(ValueError,'without retry'):
                process.run_child({},['native'],Path(directory)/'child.log')
            self.assertEqual(spawn.call_count,1)


if __name__=='__main__':unittest.main()
