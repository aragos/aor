from pathlib import Path
import shutil
import tempfile
import unittest

import token_paired_admission as a
import token_paired_campaign_plan as p
import token_paired_storage as s
from test_token_paired_campaign_plan import fixture


def initial(root):
    original=s.publish_json(root/'original.json',{'constructed':0})
    value=fixture();value['originalActorSha256']=original['sha256']
    protocol=s.publish_json(root/'protocol.json',value)
    return protocol,original


def iteration(root,protocol,original,index,previous=None):
    directory=root/f'input-{index}';directory.mkdir()
    plan=p.Plan.from_record(p.read(protocol['path']))
    current=original['sha256'] if previous is None else previous['outputModelSha256']
    corpus=s.publish_json(directory/'corpus.json',dict(kind='token-paired-corpus-1',complete=True,
        exposurePassed=True,iteration=index,protocolDigest=plan.sha256,origins=640,
        ready={'TRAIN':300,'VALIDATION':80},scopeSha256='c'*64,scienceSha256=str(index)*64,
        currentActorSha256=current))
    context=dict(iteration=index,source_sha256=corpus['sha256'],scope_sha256='c'*64,
        original_model_sha256=original['sha256'],current_model_sha256=current,
        dictionary_sha256=plan.record['dictionarySha256'])
    fit=directory/'fit';fit.mkdir()
    policy=s.publish_json(fit/'policy.json',{'constructed':index})
    identity=dict(context=context,publicCoverage=dict(trainingReady=300,validationReady=80),
        predecessor=None if previous is None else dict(finishedSha256=previous['_sha'],modelSha256=current))
    s.publish_json(fit/'identity.json',identity);s.publish_json(fit/'history.json',[])
    s.publish_json(fit/'checkpoint.json',{'constructed':index})
    done=dict(kind='token-paired-fit-complete-1',complete=True,operationalPassed=True,actorChanged=True,
        acceptedEpochs=20,outputModelSha256=policy['sha256'],identity=identity,
        artifacts={name:p.file_sha(fit/name) for name in a.FIT_FILES if name!='finished.json'})
    finished=s.publish_json(fit/'finished.json',done)
    seal=s.publish_json(directory/'validation.json',dict(kind='token-paired-validation-probabilities-1',
        complete=True,semanticLabelsDecoded=False,context=context,fitFinishedSha256=finished['sha256'],
        modelSha256=policy['sha256'],rows=[{}]*128,counts={'READY':80}))
    return corpus,fit,seal,dict(done,_sha=finished['sha256'])


class AdmissionTests(unittest.TestCase):
    def test_two_fixed_iterations_portably_bind_exact_models(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,original=initial(root);portable=root/'portable'
            first=iteration(root,protocol,original,1)
            a.publish_iteration(protocol,original,*first[:3],portable)
            second=iteration(root,protocol,original,2,first[3])
            a.publish_iteration(protocol,original,*second[:3],portable)
            final=a.publish_final(portable)
            moved=root/'other-host';shutil.copytree(portable,moved)
            self.assertEqual(a.verify_iteration(moved/'iterations/iteration-2/admission.json')['iteration'],2)
            self.assertEqual(p.read(final['path'])['finalActor']['sha256'],second[3]['outputModelSha256'])

    def test_mutated_checkpoint_cannot_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,original=initial(root)
            corpus,fit,seal,_=iteration(root,protocol,original,1)
            (fit/'checkpoint.json').write_text('changed')
            with self.assertRaisesRegex(ValueError,'transaction'):
                a.publish_iteration(protocol,original,corpus,fit,seal,root/'portable')
            self.assertFalse((root/'portable').exists())

    def test_wrong_probability_actor_cannot_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,original=initial(root)
            corpus,fit,seal,_=iteration(root,protocol,original,1)
            value=p.read(seal['path']);value['modelSha256']='f'*64
            Path(seal['path']).write_bytes(p.canonical(value));seal=p.subject(seal['path'])
            with self.assertRaisesRegex(ValueError,'probabilities'):
                a.publish_iteration(protocol,original,corpus,fit,seal,root/'portable')

    def test_final_requires_both_updates_not_an_earlier_actor(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,original=initial(root);first=iteration(root,protocol,original,1)
            a.publish_iteration(protocol,original,*first[:3],root/'portable')
            with self.assertRaises(FileNotFoundError):a.publish_final(root/'portable')
            self.assertFalse((root/'portable/final-admission.json').exists())

    def test_no_change_is_operational_stop_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,original=initial(root)
            corpus,fit,seal,_=iteration(root,protocol,original,1)
            done=p.read(fit/'finished.json');done['actorChanged']=False
            (fit/'finished.json').write_bytes(p.canonical(done))
            with self.assertRaisesRegex(ValueError,'changed fixed fit'):
                a.publish_iteration(protocol,original,corpus,fit,seal,root/'portable')


if __name__=='__main__':unittest.main()
