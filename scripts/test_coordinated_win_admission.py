"""Publisher/consumer shape proof; model materialization and native attestations are invented.

The16 scalar continuation updates and pure numerical audit are real. No fixture claims that
its opaque toy head bytes implement those coordinates or that any native game was executed.
"""
from pathlib import Path
import shutil
import tempfile
import unittest

import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_win_admission as admission
import coordinated_win_generation_audit as audit
import coordinated_win_plan as p
from test_coordinated_win_generation_audit import Fixture,put


def final_fixture(root):
    fixture=Fixture(root)
    numerical=put(fixture.root/'numerical-audit.json',audit.verify_learning(fixture.protocol,fixture.run,
        profile_subject=fixture.profile_subject))
    native=put(fixture.root/'native-audit.json',dict(kind='coordinated-win-training-audit-1',passed=True,complete=True,
        completedGenerations=16,protocolSha256=fixture.protocol['sha256'],supportedControls=4096,mutants=131072,reproductions=32,
        constructedNativeAttestation=True))
    directory=fixture.run/'generations/generation-16/center'
    shutil.copytree(fixture.prepared/'warm-bundle',directory)
    put(directory/'center-materialization.json',dict(kind='coordinated-es-continuation-center-materialization-1',
        protocolSha256=fixture.protocol['sha256'],centerSha256=fixture.previous['sha256'],step=16,
        warmStartSha256=fixture.warm.sha256,projectionSha256=fixture.plan.scope.projection_sha256,
        originalBundleSha256=fixture.plan.scope.original_bundle_sha256,
        inheritedCalibrationSha256=fixture.profile['inheritedCalibration']['sha256'],
        bundleSha256=p.file_sha(directory/'bundle.json'),finalCandidate=True))
    fixture.numeric(16,'center',dict(fixture.common(),center=fixture.previous),
        dict(bundle='bundle.json',materialization='center-materialization.json'))
    return fixture,numerical,native,directory


class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    def publish(self,values):
        f,numeric,native,directory=values
        return admission.publish(f.protocol,f.profile,f.run,directory,numeric,native,self.root/'portable')

    def test_real_publisher_and_pure_reader_accept_explicitly_invented16_lineage_shape(self):
        values=final_fixture(self.root);f=values[0];item=self.publish(values);pins=audit.Pins()
        subjects,binding=admission.final_bindings(f.plan,f.protocol['sha256'],item,pins)
        self.assertEqual(subjects['center']['sha256'],f.previous['sha256'])
        self.assertEqual(binding['calibrationSha256'],f.warm.calibration_sha256)
        self.assertNotEqual(binding['calibrationSha256'],binding['inheritedCalibrationSha256'])
        self.assertEqual(len(list((self.root/'portable/preparation').rglob('*.json'))),17)

    def test_portable_lineage_does_not_reopen_original_request_paths(self):
        values=final_fixture(self.root);f=values[0];item=self.publish(values)
        copy=self.root/'received';shutil.copytree(self.root/'portable',copy);shutil.rmtree(f.prepared);shutil.rmtree(f.run)
        subjects,_=admission.final_bindings(f.plan,f.protocol['sha256'],p.subject(copy/'final-admission.json'),audit.Pins())
        self.assertTrue(Path(subjects['numericalPreparation']['path']).is_relative_to(copy.resolve()))

    def test_final_head_bytes_are_opaque_but_must_match_the_admitted_hash(self):
        values=final_fixture(self.root);item=self.publish(values)
        (self.root/'portable/final/heads/CARD/policy.json').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'Immutable subject bytes'):
            admission.final_bindings(values[0].plan,values[0].protocol['sha256'],item,audit.Pins())

    def test_unrelated_preparation_receipt_cannot_replace_the_sealed_directions(self):
        values=final_fixture(self.root);item=self.publish(values);owner=Path(item['path']);record=p.read(owner)
        path=self.root/'portable/preparation/finished.json';prepared=p.read(path)
        prepared['artifacts']['directions/generation-01.json']='9'*64;changed=put(path,prepared)
        record['numericalPreparation']['sha256']=changed['sha256'];item=put(owner,record,True)
        with self.assertRaisesRegex(ValueError,'prospectively prepared direction'):
            admission.final_bindings(values[0].plan,values[0].protocol['sha256'],item,audit.Pins())

    def test_native_prefix_cannot_publish_a_shortened_candidate(self):
        values=final_fixture(self.root);native=p.read(values[2]['path']);native['completedGenerations']=15
        modified=put(values[2]['path'],native)
        with self.assertRaisesRegex(ValueError,'complete numerical and supported-native'):
            self.publish((values[0],values[1],modified,values[3]))

    def test_reward_only_audit_cannot_admit_the_win_continuation(self):
        values=final_fixture(self.root);row=p.read(values[1]['path']);row['objective']='AUTHORITATIVE_NORMALIZED_TERMINAL'
        modified=put(values[1]['path'],row)
        with self.assertRaisesRegex(ValueError,'exact inherited selector'):
            self.publish((values[0],modified,values[2],values[3]))

    def test_changed_wrapper_after_numerical_audit_prevents_publication(self):
        values=final_fixture(self.root);path=values[0].run/'generations/generation-3/receipt.json'
        row=p.read(path);row['elapsedSeconds']+=1;put(path,row)
        with self.assertRaisesRegex(ValueError,'exact wrapper receipt'):self.publish(values)

    def test_final_materialization_must_consume_the_admitted_sixteenth_center(self):
        values=final_fixture(self.root);f=values[0];path=Path(p.read(values[3]/'finished.json')['request']['path'])
        row=p.read(path);row['center']=f.rows[14]['outputCenter'];put(path,row,True)
        with self.assertRaisesRegex(ValueError,'subject changed'):self.publish(values)

    def test_arbitrary_earlier_center_cannot_be_rehashed_as_final(self):
        values=final_fixture(self.root);item=self.publish(values);owner=Path(item['path']);row=p.read(owner)
        row['center']=dict(path='generations/generation-15/center.json',sha256=values[0].rows[14]['outputCenter']['sha256'])
        item=put(owner,row,True)
        with self.assertRaisesRegex(ValueError,'sixteenth completed mean'):
            admission.final_bindings(values[0].plan,values[0].protocol['sha256'],item,audit.Pins())


if __name__=='__main__':unittest.main()
