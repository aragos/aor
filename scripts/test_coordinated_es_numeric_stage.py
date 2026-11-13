from pathlib import Path
import tempfile
import unittest

import coordinated_es_numeric_stage as n
import coordinated_es_plan as p
import coordinated_es_storage as s


class ClosureTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.output=self.root/'output';self.output.mkdir()
        self.input=self.root/'input.json';self.input.write_text('{}')
        self.request=self.root/'request.json'
        s.publish_json(self.request,dict(protocol=p.subject(self.input),sourceOwners={str(self.input):p.file_sha(self.input)},
            deadlineEpoch=200.))
        s.publish_json(self.output/'started.json',{'constructed':True})

    def complete(self):
        n.publish_numeric(self.output/'center.json',{'generation':1,'constructed':True})
        row=dict(kind=p.PREFIX+'-numeric-complete-1',passed=True,complete=True,verb='update',
            request=p.subject(self.request),protocolSha256=p.file_sha(self.input),
            sourceOwners={str(self.input):p.file_sha(self.input)},inputPins={str(self.input):p.file_sha(self.input)},
            artifacts={name:item['sha256'] for name,item in s.inventory(self.output).items()},
            outputs={'center':'center.json'})
        n.publish_numeric(self.output/'finished.json',row);return row

    def test_complete_child_retains_all_artifacts_and_exact_inputs(self):
        expected=self.complete();self.assertEqual(n.verify_complete(self.output,self.request,'update'),expected)

    def test_unlisted_file_cannot_hide_in_completed_numeric_output(self):
        self.complete();(self.output/'extra.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'entire output'):n.verify_complete(self.output,self.request,'update')

    def test_changed_original_input_rejects_completed_child(self):
        self.complete();self.input.write_text('{"changed":true}')
        with self.assertRaises(ValueError):n.verify_complete(self.output,self.request,'update')

    def test_stop_cannot_be_relabelled_from_expired_deadline(self):
        row=dict(kind=p.PREFIX+'-numeric-stop-1',passed=False,complete=False,verb='update',
            request=p.subject(self.request),reason='original-deadline',deadlineEpoch=200.,stoppedEpoch=201.,
            inputPins={str(self.input):p.file_sha(self.input)},
            artifacts={name:item['sha256'] for name,item in s.inventory(self.output).items()})
        n.publish_numeric(self.output/'stopped.json',row)
        with self.assertRaisesRegex(ValueError,'original calibration/arithmetic stop'):
            n.verify_stop(self.output,self.request,'update')

    def test_numeric_serialization_has_no_lf_and_equal_semantic_sha(self):
        row={'number':.0003,'values':[.1,2.]};item=n.publish_numeric(self.output/'math.json',row)
        self.assertEqual(item['sha256'],p.digest(row));self.assertFalse(Path(item['path']).read_bytes().endswith(b'\n'))


class PortableTests(unittest.TestCase):
    def test_calibration_population_uses_unchanged_member_relative_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);material=root/'material';calibration=root/'calibration';calibration.mkdir()
            n.publish_numeric(calibration/'calibration.json',dict(constructed=True))
            n.publish_numeric(calibration/'gap-EXPANSION.json',dict(constructed=True))
            members=[]
            for index in range(32):
                name=f'members/{s.role(index)}/bundle.json'
                item=n.publish_numeric(material/name,dict(constructed=True,index=index))
                members.append(dict(path=name,bundleSha256=item['sha256']))
            descriptor=n.publish_numeric(calibration/'population.json',dict(kind=p.PREFIX+'-population-1',members=members))
            published=n.population_publication(material,descriptor,calibration,root/'portable')
            self.assertEqual(published['sha256'],descriptor['sha256'])
            self.assertEqual(s.inventory(root/'portable/members'),s.inventory(material/'members'))
            self.assertEqual((root/'portable/calibration/gap-EXPANSION.json').read_bytes(),
                (calibration/'gap-EXPANSION.json').read_bytes())


if __name__=='__main__':unittest.main()
