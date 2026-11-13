"""UNIT-only resource alternatives; no scientific models, rows or children."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import triple_mc_matched as loop
import triple_mc_matched_audit as audit
from test_triple_mc_matched import protocol,receipt
from test_triple_mc_matched_audit import ArchivedFixture


class ResourceTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.protocol=protocol(self.root/'inputs')

    def mac(self):
        self.protocol.update(loop.ResourceProfile.MAC.limits,
            resourceProfile=loop.ResourceProfile.MAC.value)
        return loop.LoopPlan.read(self.protocol)

    def test_omitted_profile_keeps_exact_desktop_command(self):
        plan=loop.LoopPlan.read(self.protocol)
        self.assertEqual((10,16,21600),(plan.workers,plan.machine_cpus,plan.maximum_seconds))
        with patch.object(loop.exp,'remaining_seconds',return_value=73.9):
            command=loop.native_command(self.protocol,'mode','source','output')
        self.assertEqual(['UNIT-java','-Xmx6144m','-XX:ActiveProcessorCount=16',
            '-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp','/UNIT/runtime/lib/*',
            'dev.aragos.aor.training.TrainingMainKt','--mode','mode','--source','source',
            '--output','output','--seconds','73'],command)

    def test_mac_requires_explicit_profile_and_exact_resource_tuple(self):
        self.protocol.update(loop.ResourceProfile.MAC.limits)
        with self.assertRaisesRegex(ValueError,'exact resource profile'):loop.LoopPlan.read(self.protocol)

    def test_explicit_mac_changes_only_native_cpu_and_original_allowance(self):
        old=loop.LoopPlan.read(self.protocol);plan=self.mac()
        self.assertEqual((6,10,28800),(plan.workers,plan.machine_cpus,plan.maximum_seconds))
        self.assertEqual((old.offsets,old.shuffles),(plan.offsets,plan.shuffles))
        with patch.object(loop.exp,'remaining_seconds',return_value=27000):
            command=loop.native_command(self.protocol,'mode','source','output')
        self.assertIn('-XX:ActiveProcessorCount=10',command)
        self.assertEqual('7200',command[command.index('--seconds')+1])

    def test_mac_mixed_worker_count_rejects(self):
        self.mac();self.protocol['workers']=10
        with self.assertRaisesRegex(ValueError,'exact resource profile'):loop.LoopPlan.read(self.protocol)

    def test_unknown_profile_rejects_without_fallback(self):
        self.protocol['resourceProfile']='UNIT-unknown'
        with self.assertRaisesRegex(ValueError,'explicit admitted'):loop.LoopPlan.read(self.protocol)

    def test_mac_wrong_actual_host_rejects_before_child_or_output(self):
        self.mac()
        with patch.object(loop.os,'cpu_count',return_value=16),patch.object(loop,'train_batch') as child:
            with self.assertRaisesRegex(ValueError,'actual host'):loop.run(self.protocol,'UNIT',self.root/'run')
        child.assert_not_called();self.assertFalse((self.root/'run').exists())

    def test_mac_prefix_does_not_relax_six_hundred_second_cost_limit(self):
        plan=self.mac();rows=[receipt(a,i) for i in range(1,5) for a in plan.order(i)]
        self.assertFalse(plan.prefix_passed(rows,27000,[601]*4))
        self.assertFalse(plan.prefix_passed(rows,1000,[500]*4))
        self.assertTrue(plan.prefix_passed(rows,27000,[500]*4))

    def test_audit_uses_mac_original_remaining_clock(self):
        plan=self.mac();rows=[receipt(a,i) for i in range(1,5) for a in plan.order(i)]
        finished=dict(kind=loop.VERSION,stopReason='exception',complete=False,eligibleFinalActors=False,
            completedBatches=dict.fromkeys(loop.ARMS,4),scientificWorlds=2048,historicalReproductions=8,
            qualification=False,automaticSuccessor=False,finalBundles={})
        prefix=dict(kind='triple-mc-operational-prefix-1',qualityInspected=False,remainingSeconds=26000,
            passed=True,roundSeconds=[21]*4)
        self.assertEqual(dict.fromkeys(loop.ARMS,4),audit.terminal_contract(plan,finished,rows,prefix))
        prefix['remainingSeconds']=28801
        with self.assertRaisesRegex(ValueError,'measurements'):audit.terminal_contract(plan,finished,rows,prefix)

    def test_mac_six_worker_capsule_uses_unchanged_intrinsic_audit(self):
        with TemporaryDirectory() as temporary:
            fixture=ArchivedFixture(Path(temporary).resolve())
            fixture.protocol.update(loop.ResourceProfile.MAC.limits,resourceProfile=loop.ResourceProfile.MAC.value)
            fixture.plan=loop.LoopPlan.read(fixture.protocol)
            fixture.batch('JOINT',1);fixture.finish()
            result=audit.verify(fixture.protocol,fixture.run)
        self.assertTrue(result['passed']);self.assertEqual(256,result['scientificWorlds'])
        self.assertFalse(result['eligibleFinalActors'])


if __name__=='__main__':unittest.main()
