"""Constructed two-iteration/all-head transaction and semantic-label barriers."""
import copy
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_policy as b
import cpe_paired_data as data
import cpe_paired_fit as fit
import cpe_paired_policy as policy
import cpe_paired_validation as validation
import paired_policy_math as math_owner
from test_cpe_paired import all_cohorts,bundle,cohort,context,label,public_record,synthetic_epoch


def public_validation(original,current,iteration,head):
    ctx,_=context(original,head,current,iteration);model=current.actor(head).model
    return tuple(data.PublicRoot.from_record(public_record(ctx,original,current,index,
        status='READY' if index<576 else 'MISSING'),ctx,model,split='VALIDATION') for index in range(512,640))


class ValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        b.torch.set_num_threads(1);cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name)
        cls.original=bundle();cls.fitdirs={};cls.seals={};cls.public={};current=cls.original
        with patch.object(math_owner,'epoch',side_effect=synthetic_epoch):
            for iteration in (1,2):
                training,dictionaries,configs=all_cohorts(cls.original,current,iteration)
                directory=cls.root/f'fit-{iteration}';cls.fitdirs[iteration]=directory
                fit.fit_bundle(training,cls.original,current,dictionaries,directory,configs,
                    previous=cls.fitdirs.get(1) if iteration==2 else None)
                for head in policy.HEADS:cls.public[(iteration,head)]=public_validation(cls.original,current,iteration,head)
                cls.seals[iteration]=validation.seal_validation(directory,cls.original,current,dictionaries,
                    cls.root/f'seal-{iteration}.json',lambda head,i=iteration:cls.public[(i,head)])
                current=fit.load_completed_bundle(directory)

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def labels(self,iteration,head,subject):
        self.assertEqual(subject,self.seals[iteration])
        return tuple(label(root) if root.ready else None for root in self.public[(iteration,head)])

    def test_two_atomic_bundles_are_chained_without_carrying_adam(self):
        first=fit.completed(self.fitdirs[1]);second=fit.completed(self.fitdirs[2])
        self.assertEqual(second['identity']['currentBundleSha256'],first['outputBundleSha256'])
        self.assertEqual(second['identity']['previousFinishedSha256'],b.file_digest(self.fitdirs[1]/'finished.json'))
        for head in policy.HEADS:
            child=fit.child_completed(self.fitdirs[2]/'heads'/head.value)
            self.assertEqual(child['adamSteps'],20)
            self.assertEqual(child['identity']['predecessor']['modelSha256'],
                fit.child_completed(self.fitdirs[1]/'heads'/head.value)['outputModelSha256'])

    def test_complete_probabilities_precede_every_label_callback(self):
        calls=[]
        def labels(iteration,head,subject):
            validation.validate_seal_pair(self.seals);calls.append((iteration,head))
            return self.labels(iteration,head,subject)
        with tempfile.TemporaryDirectory() as temp,patch.object(policy,'CpePolicy',side_effect=AssertionError('No effect forward')):
            report=validation.evaluate_validation(self.seals,Path(temp)/'effects.json',labels)
        self.assertEqual(calls,[(iteration,head) for iteration in (1,2) for head in policy.HEADS])
        self.assertEqual(report['finalIteration'],2);self.assertFalse(report['selectedCheckpoint'])
        self.assertEqual(report['modelForwards'],0)
        for iteration in ('1','2'):
            self.assertEqual(len(report['iterations'][iteration]['heads']),3)
            self.assertTrue(all(value['estimate']['origins']==64 for value in report['iterations'][iteration]['heads'].values()))

    def test_missing_second_seal_fails_before_labels(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(self,'labels') as callback:
            with self.assertRaisesRegex(ValueError,'Both fixed iteration'):
                validation.evaluate_validation({1:self.seals[1]},Path(temp)/'effects.json',callback)
            callback.assert_not_called()

    def test_tampered_seal_subject_fails_before_labels(self):
        subjects=copy.deepcopy(self.seals);subjects[2]['sha256']='0'*64
        with tempfile.TemporaryDirectory() as temp,patch.object(self,'labels') as callback:
            with self.assertRaisesRegex(ValueError,'seal changed'):
                validation.evaluate_validation(subjects,Path(temp)/'effects.json',callback)
            callback.assert_not_called()

    def test_incomplete_head_seal_fails_before_labels(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(self,'labels') as callback:
            path=Path(temp)/'bad.json';value=b.read_json(self.seals[2]['path']);del value['heads']['CARD']
            b.atomic_json(path,value);subjects=dict(self.seals);subjects[2]=dict(path=str(path),sha256=b.file_digest(path))
            with self.assertRaisesRegex(ValueError,'complete public all-head'):
                validation.evaluate_validation(subjects,Path(temp)/'effects.json',callback)
            callback.assert_not_called()

    def test_world_or_stream_mismatch_cannot_produce_effect(self):
        def changed(iteration,head,subject):
            rows=list(self.labels(iteration,head,subject));rows[0]['futureSeeds']=[1,2];return rows
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp)/'effects.json'
            with self.assertRaisesRegex(ValueError,'complete original paired matrix'):
                validation.evaluate_validation(self.seals,output,changed)
            self.assertFalse(output.exists())

    def test_all_missing_worlds_retained_without_zero_imputation(self):
        item=b.read_json(self.seals[2]['path'])['heads']['CARD']
        effect=validation._effects(item,self.labels(2,policy.Head.CARD,self.seals[2]))
        self.assertEqual(effect['estimate']['origins'],64)
        self.assertEqual(sum(row['gain'] is None for row in effect['perOrigin']),64)

    def test_no_partial_seal_when_later_head_public_loading_fails(self):
        first=fit.completed(self.fitdirs[1]);dictionaries={head:context(self.original,head)[1] for head in policy.HEADS}
        def load(head):
            if head is policy.Head.PURCHASE:raise ValueError('constructed public failure')
            return self.public[(1,head)]
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp)/'seal.json'
            with self.assertRaisesRegex(ValueError,'public failure'):
                validation.seal_validation(self.fitdirs[1],self.original,self.original,dictionaries,output,load)
            self.assertFalse(output.exists());self.assertEqual(fit.completed(self.fitdirs[1]),first)

    def test_iteration2_cannot_change_original_anchor_worlds(self):
        current=fit.load_completed_bundle(self.fitdirs[1]);training,dictionaries,configs=all_cohorts(self.original,current,2)
        row=training[policy.Head.CARD];anchors=list(row.anchors)
        source=json.loads(b.canonical(dict(head='CARD',originalModelSha256=self.original.actor(policy.Head.CARD).sha256,
            index=511,split='TRAIN',observations=[])))
        source['observations']=[json.loads(row.anchors[0].observations[0])]
        anchors[511]=data.AnchorWorld.from_record(source,self.original.actor(policy.Head.CARD))
        training[policy.Head.CARD]=replace(row,anchors=tuple(anchors))
        with tempfile.TemporaryDirectory() as temp,patch.object(fit,'_fit_head') as child:
            with self.assertRaisesRegex(ValueError,'retains its original anchors'):
                fit.fit_bundle(training,self.original,current,dictionaries,Path(temp)/'fit',configs,previous=self.fitdirs[1])
            child.assert_not_called()


if __name__=='__main__':unittest.main()
