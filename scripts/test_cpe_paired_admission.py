"""Portable publication bridge made solely from constructed models/cohorts and unit Adam steps."""
from dataclasses import replace
import copy
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import patch

import cpe_paired_admission as admission
import cpe_paired_campaign_plan as p
import cpe_paired_data as data
import cpe_paired_fit as fit
import cpe_paired_policy as policy
import cpe_paired_storage as storage
import cpe_paired_validation as validation
from test_cpe_paired import bundle, cohort, public_record, synthetic_epoch
from test_cpe_paired_campaign_plan import fixture


def construct_publication(root):
    """No real reservation/models/rows/games; the production exact75 initializer gate is not exercised."""
    import purchase_policy as b
    b.torch.set_num_threads(1);root=Path(root);root.mkdir(parents=True,exist_ok=False)
    original=bundle();initial=root/'inputs/original';initial.mkdir(parents=True)
    (initial/'bundle.json').write_bytes(original.raw)
    dictionaries={}
    for head in policy.HEADS:
        model=original.actor(head);path=initial/f'heads/{head.value}/policy.json';path.parent.mkdir(parents=True)
        path.write_bytes(model.raw)
        dictionaries[head]=b.canonical({key:model.model[key] for key in
            ('encoderVersion','stateFeatureNames','actionFeatureNames')})
    record=fixture();record.update(originalBundleSha256=original.sha256,
        bundleScopeSha256='a'*64,ecologySha256='b'*64)
    for head in policy.HEADS:
        record['heads'][head.value]=dict(originalActorSha256=original.actor(head).sha256,
            dictionarySha256=__import__('hashlib').sha256(dictionaries[head]).hexdigest(),
            semanticDictionarySha256=original.actor(head).dictionary_sha256,
            scopeSha256=p.digest(dict(bundleScopeSha256='a'*64,head=head.value)))
    # Explicitly constructed protocol: do not mislabel the invented actor as the real8268 subject.
    plan=p.Plan(p.canonical(record));protocol=storage.publish_json(root/'protocol.json',record)
    current=original;previous=None;deadline=time.time()+600
    for iteration in (1,2):
        location=root/f'iteration-{iteration}';location.mkdir();subjects={};rows={};configs={};training={}
        for head in policy.HEADS:
            bank=plan.cohort(iteration,head.value)
            row=dict(kind='cpe-paired-corpus-1',head=head.value,iteration=iteration,complete=True,
                exposurePassed=True,origins=640,trainOrigins=512,validationOrigins=128,protocolDigest=plan.sha256,
                bundleScopeSha256='a'*64,scopeSha256=record['heads'][head.value]['scopeSha256'],
                ecologySha256='b'*64,scienceSha256=p.digest([iteration,head.value]),chunks=[],
                originalBundleSha256=original.sha256,currentBundleSha256=current.sha256,
                ready={'TRAIN':256,'VALIDATION':64})
            subjects[head.value]=storage.publish_json(location/(head.value+'.json'),row);rows[head.value]=row
            old,dictionary=cohort(original,head,current,iteration)
            context=replace(old.context,source_sha256=subjects[head.value]['sha256'],
                request_bank_sha256=p.digest(bank['origins']))
            roots=tuple(data.PublicRoot.from_record(dict(root.record,
                futureSeeds=bank['origins'][root.record['index']]['future']),
                context,current.actor(head).model,split='TRAIN') for root in old.roots)
            targets=tuple(None if target is None else data.PairedTarget(root,target.rewards)
                for root,target in zip(roots,old.targets))
            training[head]=replace(old,context=context,roots=roots,targets=targets)
            configs[head]=fit.Config(tuple(bank['shuffleSeeds']),deadline)
        aggregate=dict(kind='cpe-paired-iteration-corpus-1',iteration=iteration,protocolDigest=plan.sha256,
            complete=True,exposurePassed=True,independentOrigins=1920,cohorts=subjects,
            originalBundleSha256=original.sha256,currentBundleSha256=current.sha256,
            bundleScopeSha256='a'*64,ecologySha256='b'*64)
        corpus=storage.publish_json(location/'corpus.json',aggregate)
        with patch('paired_policy_math.epoch',side_effect=synthetic_epoch):
            fit.fit_bundle(training,original,current,dictionaries,location/'fit',configs,previous=previous)
        def public(head):
            context=training[head].context;bank=plan.cohort(iteration,head.value)
            return tuple(data.PublicRoot.from_record(dict(public_record(context,original,current,index=index,
                status='READY' if index<576 else 'MISSING'),
                futureSeeds=bank['origins'][index]['future']),
                context,current.actor(head).model,split='VALIDATION')
                for index in range(512,640))
        seal=validation.seal_validation(location/'fit',original,current,dictionaries,location/'validation.json',public)
        with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
            admission.publish_iteration(protocol,p.subject(initial/'bundle.json'),corpus,location/'fit',seal,root/'portable')
        previous=location/'fit';current=fit.load_completed_bundle(previous)
    with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
        final=admission.publish_final(root/'portable')
    storage.publish_json(root/'CONSTRUCTED-FIXTURE.json',dict(kind='cpe-constructed-publication-fixture-1',
        scientificAdmission=False,newGames=0,scientificFits=0,unitHeadTransactions=6,
        unitOptimizerSteps=120,realInitializerRead=False,
        limitation='Invented cohorts have no native archive evidence; fixed8268 Plan gate deliberately bypassed only by this unit builder.',
        finalAdmission=final))
    return root/'portable'


class AdmissionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace=tempfile.TemporaryDirectory()
        cls.portable=construct_publication(Path(cls.workspace.name)/'constructed')

    @classmethod
    def tearDownClass(cls):cls.workspace.cleanup()

    def test_both_atomic_publications_survive_relative_relocation(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'portable';shutil.copytree(self.portable,target)
            with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
                result=admission.verify_iteration(target/'iterations/iteration-2/admission.json')
            self.assertEqual(result['iteration'],2)
            self.assertEqual(set(result['acceptedEpochs']),set(p.HEADS))

    def test_real_plan_rejects_invented_initializer_even_with_complete_fixture(self):
        with self.assertRaisesRegex(ValueError,'untrained75'):
            p.Plan.from_record(p.read(self.portable/'protocol.json'))

    def test_changed_child_model_cannot_become_atomic_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'portable';shutil.copytree(self.portable,target)
            path=target/'iterations/iteration-2/fit/heads/PURCHASE/policy.json';path.write_text('{}')
            with self.assertRaisesRegex(ValueError,'exactly all three'):
                admission.fit_receipt(target/'iterations/iteration-2/fit')

    def test_original_descriptor_component_bytes_remain_required(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'portable';shutil.copytree(self.portable,target)
            path=target/'original/heads/CARD/policy.json';path.write_text('{}')
            with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
                with self.assertRaisesRegex(ValueError,'subject bytes changed'):
                    admission.verify_iteration(target/'iterations/iteration-2/admission.json')

    def test_second_head_must_name_its_actual_first_child_receipt(self):
        checked=admission.fit_receipt
        def changed(directory):
            value=checked(directory)
            if Path(directory).parent.name=='iteration-2':
                value=copy.deepcopy(value)
                value['identity']['predecessors']['PURCHASE']['headFinishedSha256']='0'*64
            return value
        with patch.object(p.Plan,'from_record',side_effect=lambda value:p.Plan(p.canonical(value))):
            with patch.object(admission,'fit_receipt',side_effect=changed):
                with self.assertRaisesRegex(ValueError,'exact previous atomic child'):
                    admission.verify_iteration(self.portable/'iterations/iteration-2/admission.json')

    def test_sealed_future_pair_must_equal_the_prospective_protocol(self):
        path=self.portable/'iterations/iteration-2/admission.json';row=p.read(path)
        directory=admission.resolve(row['fitCompletion'],path).parent
        seal=p.read(admission.resolve(row['validationProbabilitySeal'],path))
        seal['heads']['CARD']['rows'][0]['futureSeeds']=[-11025,-11026]
        plan=p.Plan(p.canonical(p.read(self.portable/'protocol.json')))
        cohorts={head:(subject,p.read(admission.resolve(subject,path))) for head,subject in row['cohorts'].items()}
        with self.assertRaisesRegex(ValueError,'original128 positions'):
            admission.bindings(plan,p.read(admission.resolve(row['corpus'],path)),
                admission.fit_receipt(directory),seal,directory,cohorts)


if __name__=='__main__':unittest.main()
