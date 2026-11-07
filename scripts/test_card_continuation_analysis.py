import copy
import gzip
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch

import card_advantage as a
import card_continuation_analysis as d
import purchase_policy as common

SCHEMA = {'encoderVersion':a.ENCODER,'stateFeatureNames':['cardWindow.normal','context'],
          'actionFeatureNames':['card.test','kind.pass','kind.play']}


def observation(context):
    return {'encoderVersion':a.ENCODER,'stateFeatures':{'cardWindow.normal':1.,'context':context},
            'baselineIndex':0,'actions':[{'command':'pass','features':{'kind.pass':1.}},
            {'command':'play hand-1','features':{'kind.play':1.,'card.test':1.}}]}


def model():
    member = a.initialize(SCHEMA,1,8)
    for key in a.MEMBER_KEYS:
        member[key] = 0. if key=='outputBias' else [0.]*len(member[key])
    member['stateWeights'][1] = .4
    member['actionWeights'][0] = .7
    return a.bundle(SCHEMA,[copy.deepcopy(member) for _ in range(3)])


def write_origin(directory,index,row):
    (directory/f'origin-{index}.json.gz').write_bytes(gzip.compress(json.dumps(row).encode(),mtime=0))


def load_origin(directory,index):
    return json.loads(gzip.decompress((directory/f'origin-{index}.json.gz').read_bytes()))


def finish(directory):
    common.atomic_json(directory/'finished.json',{'kind':d.KIND,'signature':'fixture','origins':256,
        'originSha256':[d.sha(directory/f'origin-{index}.json.gz') for index in range(256)]})


def fixture(directory):
    directory=Path(directory); directory.mkdir(parents=True,exist_ok=True)
    for name in ('baseline.json','frozen-purchase-policy.json','continuation.json'):
        common.atomic_json(directory/name,{'fixture':name})
    feature_model=directory/'feature-model.json'; common.atomic_json(feature_model,model())
    requests=[{'index':index,'split':'train' if index<128 else 'validation','seed':10000+index,
        'seat':index%4,'epoch':(index//4)%3+1,'ordinal':(index//12)%8+1,
        'cohort':'generic','opponentUsesCandidate':[False]*4} for index in range(256)]
    source={'schemaVersion':3,'configuration':'fixture','collection':d.PLAN,'origins':requests}
    common.atomic_json(directory/'source.json',source)
    manifest={'kind':d.KIND,'schemaVersion':3,'signature':'fixture',**SCHEMA,
        'selectorVersion':a.SELECTOR,'proposalVersion':a.PROPOSAL,'collection':d.PLAN,
        'replicates':4,'maxTailsPerOrigin':16,'arms':list(d.ARMS),'backbonePolicy':'F','opponentPolicy':'F',
        'sourceSha256':d.sha(directory/'source.json'),'origins':requests,
        'baseline':{'path':'fixture','sha256':d.sha(directory/'baseline.json')},
        'frozenPurchasePolicy':{'path':'fixture','sha256':d.sha(directory/'frozen-purchase-policy.json')},
        'continuation':{'path':'fixture','sha256':d.sha(directory/'continuation.json')}}
    common.atomic_json(directory/'collection.json',manifest)
    common.atomic_json(directory/'state.json',{'phase':'complete','signature':'fixture'})
    for request in requests:
        index=request['index']; context=1. if index%2 else -1.; obs=observation(context)
        _, acquisition=a.acquisition(obs,index,True)
        outcomes={}; arms=[]
        for arm_index,name in enumerate(d.ARMS):
            ids=[f'arm-{name}-{replicate}' for replicate in range(4)]
            arms.append({'id':name,'actionIndex':arm_index%2,
                         'continuation':'F' if arm_index<2 else 'priority64','outcomeIds':ids})
            for replicate,oid in enumerate(ids):
                reward=.4+(.05 if arm_index%2 else 0.)
                if arm_index==3: reward+=.1*context+[-.01,.01,-.02,.02][replicate]
                outcomes[oid]={'reward':reward,'winShare':reward,'rankReward':reward,
                    'scores':[{'player':f'p{seat+1}','total':1000+int(100*reward)} for seat in range(4)],
                    'gameDecisions':100,'chanceSeed':index*10+replicate,'canonicalSha256':'a'*64}
        row={'kind':d.KIND,'schemaVersion':3,'signature':'fixture',
             **{key:request[key] for key in ('index','split','seed','seat')},'request':request,
             'status':'collected','skipReason':None,'backboneDecisions':10,'backboneCanonicalSha256':'b'*64,
             'root':{'number':10,'botSeed':index,'turn':1,'epoch':request['epoch'],'observation':obs,
                 'proposalIndices':[0,1],'referenceIndex':0,'acquisition':acquisition,'alternativeIndex':1,'arms':arms},
             'outcomes':outcomes}
        write_origin(directory,index,row)
    finish(directory)
    return feature_model


def contracts(directory,feature_model):
    return patch.multiple(d,FEATURE_MODEL_SHA256=d.sha(feature_model),
                         PRIORITY_SHA256=d.sha(directory/'continuation.json'))


class NumericalChecks(unittest.TestCase):
    def test_difference_in_differences_preserves_replicate_pairing(self):
        arms={'f-f':[.1,.2,.3,.4],'a-f':[.2,.3,.4,.5],
              'f-priority':[.3,.4,.5,.6],'a-priority':[.6,.7,.8,.9]}
        np.testing.assert_allclose(d.interaction(arms),[.2]*4,atol=1e-15)

    def test_missing_arm_rejected(self):
        with self.assertRaisesRegex(ValueError,'all four arms'):
            d.interaction({'f-f':[0.]*4})

    def test_constant_interaction_has_zero_covariance_but_positive_second_moment(self):
        result=d.persistence([[.1]*4]*12,samples=100)
        self.assertAlmostEqual(result['populationCovarianceHalves'],0.)
        self.assertAlmostEqual(result['meanProductHalves'],.01)
        self.assertAlmostEqual(result['rootMeanSquarePersistentInteraction'],.1)
        self.assertEqual(result['noiseVarianceOfFourStreamMean'],0.)
        self.assertTrue(result['gatePassed'])

    def test_noise_of_four_stream_mean_uses_quarter_half_difference(self):
        result=d.persistence([[.2,.2,-.2,-.2]]*12,samples=100)
        self.assertAlmostEqual(result['meanProductHalves'],-.04)
        self.assertAlmostEqual(result['noiseVarianceOfFourStreamMean'],.04)
        self.assertEqual(result['rootMeanSquarePersistentInteraction'],0.)
        self.assertFalse(result['gatePassed'])

    def test_bootstrap_resamples_world_values_deterministically(self):
        first=d.bootstrap_mean([-.2,.1,.5,.7],samples=100)
        second=d.bootstrap_mean([-.2,.1,.5,.7],samples=100)
        self.assertEqual(first,second)
        self.assertEqual(first['worlds'],4)

    def test_ridge_mean_loss_and_unpenalized_intercept_have_closed_form(self):
        x=np.zeros((2,8)); x[:,0]=[-1.,1.]
        fitted=d.fit_ridge(x,[1.,3.])
        self.assertAlmostEqual(fitted['intercept'],2.)
        self.assertAlmostEqual(fitted['coefficients'][0],.5)
        np.testing.assert_allclose(d.predict_ridge(fitted,x),[1.5,2.5])

    def test_training_constant_feature_is_zero_on_validation_even_if_it_changes(self):
        x=np.ones((4,8))*.1
        fitted=d.fit_ridge(x,[1.,2.,3.,4.])
        self.assertEqual(fitted['standardDeviation'],[0.]*8)
        self.assertEqual(fitted['coefficients'],[0.]*8)
        np.testing.assert_allclose(d.predict_ridge(fitted,np.ones((3,8))*100),[2.5]*3)

    def test_validation_constant_is_training_mean_not_validation_mean(self):
        fitted=d.fit_ridge(np.zeros((3,8)),[1.,1.,1.])
        report=d.observable_metrics(fitted,np.zeros((3,8)),[3.,3.,3.],samples=100)
        self.assertEqual(report['trainingMeanConstant'],1.)
        self.assertEqual(report['constantMse'],4.)
        self.assertEqual(report['probeMse'],4.)
        self.assertFalse(report['gatePassed'])


class ReaderChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):torch.set_num_threads(1)

    def test_completed_reader_and_frozen_latent_formula(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); feature_model=fixture(path)
            with contracts(path,feature_model):
                collection=d.CompletedCollection(path)
                root=collection.read_origin(collection.requests[1])
                actual=d.FixedFeatures(feature_model,collection.manifest)(root)
                np.testing.assert_allclose(actual,[math.tanh(.4)*math.tanh(.7)]+[0.]*7,atol=1e-14)
                np.testing.assert_allclose(d.interaction(root.metrics['reward']).mean(),.1,atol=1e-14)

    def test_all_completed_hashes_checked_before_any_outcome_decode(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); feature_model=fixture(path)
            (path/'origin-255.json.gz').write_bytes(b'changed')
            with contracts(path,feature_model),patch.object(d.CompletedCollection,'read_origin',side_effect=AssertionError('no decode')):
                with self.assertRaisesRegex(ValueError,'origin bytes changed'):
                    d.CompletedCollection(path)

    def test_missing_completion_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); feature_model=fixture(path)
            common.atomic_json(path/'state.json',{'phase':'running','signature':'fixture'})
            with contracts(path,feature_model):
                with self.assertRaisesRegex(ValueError,'must finish'):
                    d.CompletedCollection(path)

    def test_changed_alternative_provenance_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); feature_model=fixture(path); row=load_origin(path,0)
            row['root']['alternativeIndex']=0;write_origin(path,0,row);finish(path)
            with contracts(path,feature_model):
                collection=d.CompletedCollection(path)
                with self.assertRaisesRegex(ValueError,'Alternative acquisition'):
                    collection.read_origin(collection.requests[0])

    def test_changed_future_pair_seed_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); feature_model=fixture(path); row=load_origin(path,0)
            row['outcomes']['arm-a-priority-0']['chanceSeed']=99;write_origin(path,0,row);finish(path)
            with contracts(path,feature_model):
                collection=d.CompletedCollection(path)
                with self.assertRaisesRegex(ValueError,'paired future seed'):
                    collection.read_origin(collection.requests[0])

    def test_trivial_initial_actions_require_explicit_continuation_aliases(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); feature_model=fixture(path); row=load_origin(path,0)
            obs=observation(1.);obs['actions']=obs['actions'][:1]
            _,acquisition=a.acquisition(obs,0,True)
            row['root'].update(observation=obs,proposalIndices=[0],alternativeIndex=None,acquisition=acquisition)
            for position,arm in enumerate(row['root']['arms']):
                arm['actionIndex']=0
                if position in (1,3):arm['outcomeIds']=row['root']['arms'][position-1]['outcomeIds']
            used={oid for arm in row['root']['arms'] for oid in arm['outcomeIds']}
            row['outcomes']={key:value for key,value in row['outcomes'].items() if key in used}
            write_origin(path,0,row);finish(path)
            with contracts(path,feature_model):
                collection=d.CompletedCollection(path);root=collection.read_origin(collection.requests[0])
                self.assertFalse(root.nontrivial)
                np.testing.assert_equal(d.interaction(root.metrics['reward']),[0.]*4)


class HeldOutChecks(unittest.TestCase):
    def test_ridge_is_frozen_before_first_validation_origin_is_decoded(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input';feature_model=fixture(path);output=Path(tmp)/'analysis'
            original=d.CompletedCollection.read_origin;seen=[]
            def guarded(collection,request):
                if request['split']=='validation':
                    self.assertTrue((output/'ridge.json').exists())
                    seen.append(request['index'])
                return original(collection,request)
            with contracts(path,feature_model),patch.object(d.CompletedCollection,'read_origin',guarded):
                report=d.analyze(path,feature_model,output)
            self.assertEqual(len(seen),128)
            self.assertTrue(report['coverage']['passed'])
            self.assertEqual(report['classification'],'persistent-and-predicted-by-fixed-probe')
            self.assertFalse(report['policyPromotion'])

    def test_insufficient_training_coverage_never_decodes_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input';feature_model=fixture(path);output=Path(tmp)/'analysis'
            for index in range(33):
                row=load_origin(path,index);row.update(status='skipped',skipReason='requested-root-not-reached',root=None,outcomes={})
                write_origin(path,index,row)
            finish(path);original=d.CompletedCollection.read_origin
            def guarded(collection,request):
                self.assertEqual(request['split'],'train')
                return original(collection,request)
            with contracts(path,feature_model),patch.object(d.CompletedCollection,'read_origin',guarded):
                report=d.analyze(path,feature_model,output)
            self.assertEqual(report['classification'],'insufficient-coverage')
            self.assertIsNone(report['ridgeSha256'])
            self.assertFalse((output/'ridge.json').exists())

    def test_corrupt_validation_payload_is_not_decoded_before_ridge_freeze(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input';feature_model=fixture(path);output=Path(tmp)/'analysis'
            (path/'origin-128.json.gz').write_bytes(gzip.compress(b'not-json',mtime=0));finish(path)
            with contracts(path,feature_model):
                with self.assertRaises(json.JSONDecodeError):d.analyze(path,feature_model,output)
            self.assertTrue((output/'ridge.json').exists())
            self.assertFalse((output/'report.json').exists())

    def test_resume_after_training_freeze_preserves_deadline_and_coefficients(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input';feature_model=fixture(path);output=Path(tmp)/'analysis'
            original=d.CompletedCollection.read_split
            def interrupted(collection,split,deadline):
                if split=='validation':raise RuntimeError('interrupt after ridge publication')
                return original(collection,split,deadline)
            with contracts(path,feature_model):
                with patch.object(d.CompletedCollection,'read_split',interrupted):
                    with self.assertRaises(RuntimeError):d.analyze(path,feature_model,output)
                identity=(output/'identity.json').read_bytes();ridge=(output/'ridge.json').read_bytes()
                report=d.analyze(path,feature_model,output,resume=True)
            self.assertEqual((output/'identity.json').read_bytes(),identity)
            self.assertEqual((output/'ridge.json').read_bytes(),ridge)
            self.assertTrue(report['observableProbe']['gatePassed'])


if __name__=='__main__':unittest.main()
