"""Synthetic bytes and receipts only; no inference, actor updates or games."""
import io
from pathlib import Path
import tarfile
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import automata_experiments as files
from automata_benchmark import digest
import joint_credit_storage as owner


class NumericStorageTest(unittest.TestCase):
    def setUp(self):
        temp=TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.root=Path(temp.name).resolve();self.batch=self.root/'batch-01'
        self.numeric=self.batch/'numeric';self.next=self.root/'batch-02/receipt.json'
        self.write('values.json',{'predictions':[.1,.2]})
        self.write('targets/chronological.json',{'advantages':[.3,.4]})
        self.write('targets/phase-separated.json',{'advantages':[.2,.5]})
        identity=dict(kind=owner.PAIR,objective={'source':'synthetic'})
        self.write('pair/identity.json',identity)
        phases,outputs,bundle={},{},{'kind':'joint-card-purchase-1'}
        for phase in owner.PHASES:
            subject={'phase':phase,'source':'original phase inputs'}
            self.write(f'pair/{phase}/identity.json',subject)
            self.write(f'pair/{phase}/policy.json',{'phase':phase,'weights':[.1,.2]})
            self.write(f'pair/{phase}/checkpoint.json',{'phase':phase,'optimizer':'synthetic'})
            self.write(f'pair/{phase}/finished.json',dict(identitySha256=digest(subject),
                outputModelSha256=self.hash(f'pair/{phase}/policy.json'),stopReason='epochs-complete'))
            self.write(f'pair/{phase}/epoch-started.json',{'epoch':1})
            component=dict(identitySha256=digest(subject),policySha256=self.hash(f'pair/{phase}/policy.json'),
                checkpointSha256=self.hash(f'pair/{phase}/checkpoint.json'),completionSha256=self.hash(f'pair/{phase}/finished.json'))
            phases[phase]=component;self.write(f'pair/{phase}-completed.json',component)
            for name in ('identity','policy','checkpoint','finished'):
                path=self.numeric/f'pair/{phase}/{name}.json';outputs[str(path)]=files.sha(path)
            outputs[str(self.numeric/f'pair/{phase}-completed.json')]=self.hash(f'pair/{phase}-completed.json')
            bundle[phase]=dict(path=str(self.numeric/f'pair/{phase}/policy.json'),sha256=component['policySha256'])
        self.write('pair/bundle.json',bundle)
        self.write('pair/finished.json',dict(kind=owner.PAIR,identitySha256=digest(identity),allInputHashesUnchanged=True,
            outputBundleSha256=self.hash('pair/bundle.json'),outputHashes=outputs,phases=phases))
        self.publish_receipt()
        files.document(self.next,dict(completed=True,artifacts={},previousCheckpoints={phase:dict(
            path=str(self.numeric/f'pair/{phase}/checkpoint.json'),sha256=self.hash(f'pair/{phase}/checkpoint.json'))
            for phase in owner.PHASES}))
        self.originals={str(p.relative_to(self.numeric)):p.read_bytes() for p in self.numeric.rglob('*') if p.is_file()}

    def write(self,name,value):files.document(self.numeric/name,value)
    def hash(self,name):return files.sha(self.numeric/name)
    def archive(self):return owner.archive_numeric(self.batch,self.next)
    def publish_receipt(self):
        files.document(self.batch/'receipt.json',dict(completed=True,artifacts={str(p.relative_to(self.batch)):files.sha(p)
            for p in self.numeric.rglob('*') if p.is_file()}),immutable=False)
    def mutate(self,path,change):
        value=files.read(path);change(value);files.document(path,value,immutable=False)

    def test_lossless_archive_retains_every_document_and_original_path(self):
        receipt=(self.batch/'receipt.json').read_bytes();self.archive();index=owner.index_numeric(self.batch)
        self.assertEqual([],list(self.numeric.rglob('*.json')))
        for name,data in self.originals.items():
            self.assertEqual(owner.json.loads(data),owner.read_numeric(self.batch,name,index=index))
            self.assertEqual(str(self.numeric/name),index.files[name]['originalPath'])
        self.assertEqual(receipt,(self.batch/'receipt.json').read_bytes())

    def test_audit_index_verifies_archive_once_for_multiple_reads(self):
        self.archive()
        with patch.object(owner.storage,'verify_members',wraps=owner.storage.verify_members) as verify:
            index=owner.index_numeric(self.batch);index.read('values.json');index.read('targets/chronological.json')
        self.assertEqual(1,verify.call_count)

    def test_latest_carry_stays_loose_without_next_receipt(self):
        self.next.unlink()
        with self.assertRaisesRegex(ValueError,'regular files'):self.archive()
        self.assertTrue((self.numeric/'pair/card/checkpoint.json').exists())
        self.assertFalse((self.batch/owner.ARCHIVE).exists())

    def test_incomplete_next_batch_cannot_release_old_evidence(self):
        self.mutate(self.next,lambda v:v.update(completed=False))
        with self.assertRaisesRegex(ValueError,'completed batch receipt'):self.archive()
        self.assertEqual(len(self.originals),len(list(self.numeric.rglob('*.json'))))

    def test_both_exact_checkpoint_hashes_are_required(self):
        self.mutate(self.next,lambda v:v['previousCheckpoints']['purchase'].update(sha256='0'*64))
        with self.assertRaisesRegex(ValueError,'both exact preceding'):self.archive()
        self.assertFalse((self.batch/owner.MANIFEST).exists())

    def test_checkpoint_paths_cannot_be_substituted_between_phases(self):
        self.mutate(self.next,lambda v:v['previousCheckpoints']['purchase'].update(path=str(self.numeric/'pair/card/checkpoint.json')))
        with self.assertRaisesRegex(ValueError,'both exact preceding'):self.archive()

    def test_changed_original_is_never_archived_or_deleted(self):
        (self.numeric/'values.json').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'changed after completion'):self.archive()
        self.assertFalse((self.batch/owner.ARCHIVE).exists())
        self.assertEqual(b'changed',(self.numeric/'values.json').read_bytes())

    def test_missing_phase_completion_rejects_even_with_updated_receipt(self):
        (self.numeric/'pair/purchase-completed.json').unlink();self.publish_receipt()
        with self.assertRaisesRegex(ValueError,'complete phase evidence'):self.archive()
        self.assertFalse((self.batch/owner.ARCHIVE).exists())

    def test_pair_output_binding_is_checked_independently_of_storage_receipt(self):
        self.mutate(self.numeric/'pair/finished.json',lambda v:v['phases']['card'].update(checkpointSha256='0'*64))
        self.publish_receipt()
        with self.assertRaisesRegex(ValueError,'complete phase identities'):self.archive()
        self.assertFalse((self.batch/owner.ARCHIVE).exists())

    def test_read_rejects_path_traversal(self):
        index=owner.index_numeric(self.batch)
        with self.assertRaisesRegex(ValueError,'bounded relative'):index.read('../receipt.json')

    def test_receipt_member_cannot_escape_the_numeric_layout(self):
        self.mutate(self.batch/'receipt.json',lambda v:v['artifacts'].update({'numeric/../escape.json':'0'*64}))
        with self.assertRaisesRegex(ValueError,'bounded relative'):self.archive()
        self.assertFalse((self.batch/owner.ARCHIVE).exists())

    def test_archive_member_cannot_escape_with_updated_archive_hash(self):
        self.archive();archive=self.batch/owner.ARCHIVE
        with tarfile.open(archive,'w:gz') as target:
            item=tarfile.TarInfo('../escaped.json');item.size=1;target.addfile(item,io.BytesIO(b'x'))
        self.mutate(self.batch/owner.MANIFEST,lambda v:v.update(archiveSha256=files.sha(archive)))
        with self.assertRaisesRegex(ValueError,'unexpected'):owner.index_numeric(self.batch)
        self.assertFalse((self.batch/'escaped.json').exists())

    def test_symlink_original_is_rejected(self):
        path=self.numeric/'values.json';other=self.root/'elsewhere.json';other.write_bytes(path.read_bytes())
        path.unlink();path.symlink_to(other)
        with self.assertRaisesRegex(ValueError,'symlink'):self.archive()
        self.assertFalse((self.batch/owner.ARCHIVE).exists())

    def test_changed_consuming_receipt_is_detected_after_archive(self):
        self.archive();self.next.write_bytes(self.next.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'consuming receipt changed'):owner.index_numeric(self.batch)

    def test_changed_archive_is_detected_with_originals_absent(self):
        self.archive();path=self.batch/owner.ARCHIVE;path.write_bytes(path.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'checksum changed'):owner.index_numeric(self.batch)

    def test_changed_duplicate_prevents_any_removal(self):
        publish=owner.storage.publish
        def changed(path,write,expected=None):
            publish(path,write,expected)
            if path.name==owner.MANIFEST:(self.numeric/'values.json').write_bytes(b'changed')
        with patch.object(owner.storage,'publish',side_effect=changed),self.assertRaisesRegex(ValueError,'duplicates changed'):self.archive()
        self.assertEqual(len(self.originals),len(list(self.numeric.rglob('*.json'))))

    def test_interrupted_deletion_finishes_from_existing_archive(self):
        unlink=Path.unlink
        def interrupted(path,*args,**kwargs):
            if path==self.numeric/'pair/card/checkpoint.json':raise InterruptedError('UNIT stop')
            return unlink(path,*args,**kwargs)
        with patch.object(Path,'unlink',interrupted),self.assertRaises(InterruptedError):self.archive()
        old=(self.batch/owner.ARCHIVE).read_bytes();manifest=(self.batch/owner.MANIFEST).read_bytes();self.archive()
        self.assertEqual([],list(self.numeric.rglob('*.json')))
        self.assertEqual(old,(self.batch/owner.ARCHIVE).read_bytes())
        self.assertEqual(manifest,(self.batch/owner.MANIFEST).read_bytes())

    def test_published_archive_survives_interruption_before_manifest(self):
        publish=owner.storage.publish
        def interrupted(path,write,expected=None):
            if path.name==owner.MANIFEST:raise InterruptedError('UNIT stop')
            publish(path,write,expected)
        with patch.object(owner.storage,'publish',side_effect=interrupted),self.assertRaises(InterruptedError):self.archive()
        old=(self.batch/owner.ARCHIVE).read_bytes();self.archive()
        self.assertEqual(old,(self.batch/owner.ARCHIVE).read_bytes())

    def test_reused_index_cannot_read_another_batch(self):
        index=owner.index_numeric(self.batch)
        with self.assertRaisesRegex(ValueError,'exact batch'):owner.read_numeric(self.next.parent,'values.json',index=index)

    def test_loose_index_detects_member_change_after_verification(self):
        index=owner.index_numeric(self.batch);(self.numeric/'values.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'after index verification'):index.read('values.json')


if __name__=='__main__':unittest.main()
