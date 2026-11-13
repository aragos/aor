"""Constructed completion receipts exercise the probability/label barrier without a fit."""
import copy
from dataclasses import asdict
from pathlib import Path
import tempfile
import unittest

import purchase_policy as b
import token_paired_data as data
import token_paired_fit as fit
import token_paired_validation as validation
from test_token_paired_fit import model, frozen, context, root_record, label


def completed_fixture(directory, original, current, iteration, previous=None, changed=True):
    directory.mkdir()
    ctx, dictionary = context(original, current, iteration)
    output = current.model
    if changed:
        output['actorWeights'][0] += .01
    identity = dict(recipe=fit.RECIPE, context=asdict(ctx),
        inputModelCanonicalSha256=b.digest(current.model),
        frozenModelFieldsSha256=b.digest({key:value for key,value in current.model.items()
            if key not in b.PARAMETERS[:-2]}), publicCoverage=dict(trainingReady=256,validationReady=64),
        anchorSha256='b'*64, epochSeeds=list(range(1000+iteration*100,1020+iteration*100)),
        predecessor=None if previous is None else dict(finishedSha256=b.file_digest(previous/'finished.json')))
    history = [dict(epoch=0,accepted=True,actorSha256=b.digest(current.model))]+[
        dict(epoch=index,accepted=True,actorSha256=b.digest(output),anchor=dict(mean=.001,maximum=.01))
        for index in range(1,21)]
    b.atomic_json(directory/'identity.json',identity)
    b.atomic_json(directory/'policy.json',output)
    b.atomic_json(directory/'history.json',history)
    b.atomic_json(directory/'checkpoint.json',dict(kind='token-paired-adam-1',model=output,
        acceptedEpochs=20,adamSteps=160,optimizer={}))
    b.atomic_json(directory/'finished.json',dict(kind='token-paired-fit-complete-1',complete=True,
        identity=identity,outputModelSha256=b.file_digest(directory/'policy.json'),acceptedEpochs=20,
        attemptedEpochs=20,adamSteps=160,actorChanged=changed,operationalPassed=changed,stopReason='epoch-limit',
        artifacts={name:b.file_digest(directory/name) for name in
            ('identity.json','policy.json','history.json','checkpoint.json')}))
    return ctx,dictionary


class ValidationTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.original=frozen(model())
        self.first=self.root/'first';self.ctx,self.dictionary=completed_fixture(
            self.first,self.original,self.original,1)
        self.current=data.FrozenActor((self.first/'policy.json').read_bytes(),b.file_digest(self.first/'policy.json'))
        self.second=self.root/'second';completed_fixture(self.second,self.original,self.current,2,self.first)
        self.roots=tuple(data.PublicRoot.from_record(root_record(512+index,
            'READY' if index<64 else 'SINGLETON' if index<96 else 'MISSING'),
            self.ctx,self.original.model,split='VALIDATION') for index in range(128))

    def seal(self):
        return validation.seal_validation(self.first,self.original,self.original,self.dictionary,
            self.root/'probabilities.json',lambda:self.roots)

    def test_seal_contains_every_offer_before_label_callback(self):
        subject=self.seal();calls=[]
        def labels(receipt):
            self.assertEqual(receipt,subject)
            value=b.read_json(receipt['path'])
            self.assertFalse(value['semanticLabelsDecoded'])
            self.assertEqual(value['counts'],dict(READY=64,SINGLETON=32,MISSING=32))
            self.assertEqual(len(value['rows'][0]['probabilities']),3)
            calls.append(True)
            return [label(root) if root.ready else None for root in self.roots]
        result=validation.evaluate_validation(subject,self.root/'effect.json',labels,
            final_fit_directory=self.second)
        self.assertEqual(calls,[True]);self.assertEqual(result['estimate']['origins'],64)
        self.assertEqual(result['modelForwards'],0);self.assertEqual(result['optimizerSteps'],0)
        self.assertEqual(sum(row['gain'] is None for row in result['perOrigin']),64)

    def test_unchanged_fit_rejects_before_public_validation_callback(self):
        other=self.root/'unchanged';completed_fixture(other,self.original,self.original,1,changed=False)
        def forbidden():raise AssertionError('Validation features opened')
        with self.assertRaisesRegex(ValueError,'changed fit'):
            validation.seal_validation(other,self.original,self.original,self.dictionary,
                self.root/'absent.json',forbidden)

    def test_both_iterations_must_finish_before_either_label_cohort(self):
        subject=self.seal()
        def forbidden(_):raise AssertionError('Validation labels opened')
        with self.assertRaisesRegex(ValueError,'Both fixed iterations'):
            validation.evaluate_validation(subject,self.root/'absent.json',forbidden,
                final_fit_directory=self.first)

    def test_changed_selected_model_rejects_before_label_callback(self):
        subject=self.seal();value=b.read_json(self.first/'policy.json');value['actorWeights'][0]+=.01
        b.atomic_json(self.first/'policy.json',value)
        def forbidden(_):raise AssertionError('Validation labels opened')
        with self.assertRaisesRegex(ValueError,'artifact changed'):
            validation.evaluate_validation(subject,self.root/'absent.json',forbidden,
                final_fit_directory=self.second)

    def test_changed_probability_seal_rejects_before_labels(self):
        subject=self.seal();value=b.read_json(subject['path']);value['rows'][0]['probabilities'].reverse()
        b.atomic_json(subject['path'],value)
        def forbidden(_):raise AssertionError('Validation labels opened')
        with self.assertRaisesRegex(ValueError,'seal changed'):
            validation.evaluate_validation(subject,self.root/'absent.json',forbidden,
                final_fit_directory=self.second)

    def test_validation_effects_use_complete_sibling_matrix(self):
        subject=self.seal()
        def labels(_):
            values=[label(root) if root.ready else None for root in self.roots]
            values[0]['actions'].pop()
            return values
        with self.assertRaisesRegex(ValueError,'sibling matrix'):
            validation.evaluate_validation(subject,self.root/'absent.json',labels,
                final_fit_directory=self.second)

    def test_validation_coverage_cannot_change_after_fit(self):
        with self.assertRaisesRegex(ValueError,'pre-fit coverage'):
            validation.seal_validation(self.first,self.original,self.original,self.dictionary,
                self.root/'absent.json',lambda:tuple(data.PublicRoot.from_record(root_record(512+index),
                    self.ctx,self.original.model,split='VALIDATION') for index in range(128)))

    def test_resealed_value_parameter_change_fails_intrinsic_completion(self):
        value=b.read_json(self.first/'policy.json');value['valueBias']=.1
        b.atomic_json(self.first/'policy.json',value)
        done=b.read_json(self.first/'finished.json');done['artifacts']['policy.json']=b.file_digest(self.first/'policy.json')
        done['outputModelSha256']=done['artifacts']['policy.json'];b.atomic_json(self.first/'finished.json',done)
        with self.assertRaisesRegex(ValueError,'history'):
            fit.completed(self.first)


if __name__=='__main__':unittest.main()
