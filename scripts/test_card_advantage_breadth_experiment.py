"""Synthetic breadth contracts; no native games, numerical fits, or Torch imports."""
from datetime import datetime, timezone
import gzip
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import card_advantage_breadth_experiment as breadth
import card_advantage_experiment as experiment
from test_card_advantage_compact_experiment import fit_recipe
from test_card_advantage_experiment import completed_collection


def put(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    experiment.document(path, value, immutable=False)


def metrics(mean=.1, overrides=20, fraction=.1, mse=.02, zero=.04):
    return dict(meanSelectedAdvantage=mean, overriddenOrigins=overrides,
        largestPositiveOriginFraction=fraction, predictionMse=mse, zeroPredictionMse=zero,
        localGatePassed=mean>1e-12 and overrides>=16 and fraction is not None and fraction<=.25,
        nontrivialOrigins=300, lower95=-.1)


class Fixture:
    def __init__(self, root):
        self.root = root.resolve(); self.lab = self.root / 'breadth'; self.lab.mkdir()
        self.bin = self.root / 'runtime/bin'; self.bin.mkdir(parents=True)
        self.lib = self.root / 'runtime/lib'; self.lib.mkdir()
        for name in ('card_advantage_breadth_experiment.py','card_advantage_experiment.py',
                     'card_advantage_priority_experiment.py','card_advantage_evaluation.py',
                     'card-advantage-run.py','card_advantage_train.py','card_advantage.py',
                     'card_advantage_data.py','purchase_policy.py'):
            (self.bin / name).write_text('frozen ' + name)
        (self.lib / 'native.jar').write_text('exact native runtime')
        put(self.lab / 'inputs/baseline.json', {'fixed':'F'})
        put(self.lab / 'inputs/purchase.json', {'fixed':'purchase32'})
        self.pins = {str(p):experiment.sha(p) for p in [*self.bin.iterdir(),*self.lib.iterdir(),*(self.lab/'inputs').iterdir()]}
        self.requests = [dict(index=i,seed=10000+i,seat=i%4,epoch=(i//4)%3+1,ordinal=(i//12)%8+1,
            cohort='generic',opponentUsesCandidate=[False]*4,
            split='train' if i<3072 else 'development' if i<3584 else 'validation') for i in range(4096)]
        put(self.lab/'requests.json',self.requests)
        put(self.lab/'seeds.json',dict(passed=True,overlaps=[],banks=[
            {'name':'breadth','seeds':[r['seed'] for r in self.requests]},
            {'name':breadth.SCREEN,'seeds':list(range(20000,20256))},
            {'name':'primary-confirmation','seeds':list(range(21000,22024))},
            {'name':'rival-robustness','seeds':list(range(23000,23256))}]))
        start=time.time()-10;iso=lambda value:datetime.fromtimestamp(value,timezone.utc).isoformat()
        put(self.lab/'clock.json',dict(startedAt=iso(start),deadline=iso(start+18000),
            collectionAdmissionDeadline=iso(start+18000),latestNewCollection=iso(start),
            originalAllowanceSeconds=18000,renewOnResume=False))
        prior=self.root/'priority';put(prior/'preflight.json',{'readyForCollection':True})
        put(prior/'window-result.json',dict(preflightSha256=experiment.sha(prior/'preflight.json'),
            disposition='priority-screen-gate-failed',browserActivated=False))
        profile=self.root/'profile';put(profile/'protocol.json',{'consumedWorlds':64})
        put(profile/'report.json',dict(passed=True,admitted=True,protocolSha256=experiment.sha(profile/'protocol.json'),
            worlds=64,newWorlds=0,compressedRecordsExactlyEqual=64,legacyRecordsExactlyEqualExceptSignature=2,
            batchWallSeconds=30,runtimeJars={'native.jar':experiment.sha(self.lib/'native.jar')},
            sourceHashes={str(self.bin/'card-advantage-run.py'):experiment.sha(self.bin/'card-advantage-run.py')}))
        self.protocol=dict(kind='card-advantage-breadth-window',schemaVersion=1,configuration='qualified-profile',
            clock=self.entry(self.lab/'clock.json'),seedReservation=self.entry(self.lab/'seeds.json'),
            originRequests={'breadth':self.entry(self.lab/'requests.json')},
            collection={'maximumSeconds':7200,'batchSize':8,'plan':breadth.PLAN},coverage=breadth.COVERAGE,
            fit=fit_recipe(),screen={'worlds':256,'minimumWinShareGain':.02,'maximumSeconds':1800},
            evaluation={'batchSize':8,'maximumSecondsPerStage':7200},localGate=breadth.LOCAL_GATE,
            resources={'minimumDiskFreeBytes':8*1024**3,'maximumNewDataBytes':4*1024**3},
            prerequisites={'priorityPreflight':self.entry(prior/'preflight.json'),
                'priorityResult':self.entry(prior/'window-result.json'),'opsProfile':self.entry(profile/'report.json'),
                'opsProtocol':self.entry(profile/'protocol.json')})
        draft={key:self.protocol[key] for key in ('configuration','collection','coverage','fit','screen',
            'evaluation','localGate','resources','seedReservation','originRequests')}
        put(self.lab/'recipe.json',draft);self.protocol['recipeDraft']=self.entry(self.lab/'recipe.json');self.save()

    def entry(self,path):
        self.pins[str(path)]=experiment.sha(path)
        return dict(file=str(path),sha256=experiment.sha(path))

    def save(self):
        put(self.lab/'protocol.json',self.protocol)
        put(self.lab/'preflight.json',dict(readyForCollection=True,
            windowSha256=experiment.sha(self.lab/'protocol.json'),pins=self.pins))

    def window(self):
        with patch.object(experiment,'__file__',str(self.bin/'card_advantage_experiment.py')):
            return breadth.BreadthWindow(self.lab,self.lab/'protocol.json')

    def repin(self,entry):
        path=Path(entry['file']);entry['sha256']=self.pins[str(path)]=experiment.sha(path);self.save()

    def fit_files(self):
        path=self.lab/'breadth-fit'
        for name in ('full.json','action-priority.json','frequency-control.json','finished.json'):
            put(path/name,{'fixed':name})
        return path


def coverage_fixture(root):
    root=root.resolve()
    root.mkdir()
    source=completed_collection(root)
    requests=[dict(index=i,split='validation',epoch=3) for i in range(2)]
    specification=experiment.read(source);specification.update(schemaVersion=2,origins=requests,collection=breadth.PLAN)
    put(source,specification);(root/'source.json').write_bytes(source.read_bytes())
    manifest=experiment.read(root/'collection.json');manifest.update(schemaVersion=2,origins=requests,
        sourceSha256=experiment.sha(source),collection=breadth.PLAN);put(root/'collection.json',manifest)
    for request in requests:
        row=dict(schemaVersion=2,request=request,signature='fixed',status='collected',
            groups=[dict(kind='root',epoch=3,proposalIndices=[0] if request['index']==0 else [0,1],observationSha256='safe')],
            observations={'safe':{'stateFeatures':{'role.0.advance.PRINTED_WORD':1},
                'actions':[{'features':{'cardKind.LEADER':1}}]}},outcomes='not a return object')
        (root/f"origin-{request['index']}.json.gz").write_bytes(gzip.compress(json.dumps(row).encode()))
    put(root/'finished.json',dict(signature='fixed',origins=2,
        originSha256=[experiment.sha(root/f'origin-{i}.json.gz') for i in range(2)]))
    return source


class CoverageAndGateTest(unittest.TestCase):
    def test_coverage_counts_nontrivial_roots_and_epochs_without_reading_return_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'collection';source=coverage_fixture(root)
            result=breadth.coverage(root,source)
            actual=result['counts']['validation']
            self.assertEqual(2,actual['totalCollected']);self.assertEqual(1,actual['trivial'])
            self.assertEqual(1,actual['origins']);self.assertEqual(1,actual['epoch3'])
            self.assertEqual(2,actual['printedWordLeader']);self.assertFalse(result['labelsInspected'])
            self.assertFalse(result['passed'])

    def test_all_compressed_records_validate_before_first_origin_is_opened(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'collection';source=coverage_fixture(root)
            (root/'origin-1.json.gz').write_bytes(b'changed')
            with patch.object(breadth.gzip,'open') as opened:
                with self.assertRaisesRegex(ValueError,'record hashes'):
                    breadth.coverage(root,source)
            opened.assert_not_called()

    def test_lower_prediction_error_is_required_even_when_original_gate_passes(self):
        report={'validation':{'full':metrics(mse=.04,zero=.04)}}
        gate=breadth.local_gate(report)
        self.assertTrue(gate['originalLocalGatePassed']);self.assertFalse(gate['passed'])

    def test_interval_is_descriptive_and_priority_model_cannot_replace_failed_full(self):
        report={'validation':{'full':metrics(), 'action-priority':metrics(mean=10)}}
        self.assertTrue(breadth.local_gate(report)['passed'])
        report['validation']['full']=metrics(mean=-.1)
        self.assertFalse(breadth.local_gate(report)['passed'])

    def test_local_gate_rejects_nonfinite_or_disagreeing_fitter_metrics(self):
        for changed in ({'predictionMse':float('nan')},{'localGatePassed':False}):
            with self.subTest(changed=changed),self.assertRaises(ValueError):
                breadth.local_gate({'validation':{'full':dict(metrics(),**changed)}})


class BreadthDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.fixture=Fixture(Path(self.temp.name))
    def tearDown(self):self.temp.cleanup()

    def test_complete_prerequisites_do_not_launch_work(self):
        window=self.fixture.window()
        self.assertEqual(4096,len(window.requests));self.assertFalse((window.lab/'window-status.json').exists())

    def test_original_recipe_cannot_change_after_freeze(self):
        self.fixture.protocol['fit']['activeHidden']=16;self.fixture.save()
        with self.assertRaisesRegex(ValueError,'recipe changed'):
            self.fixture.window()

    def test_slow_profile_cannot_admit_fresh_collection(self):
        entry=self.fixture.protocol['prerequisites']['opsProfile'];path=Path(entry['file'])
        value=experiment.read(path);value['batchWallSeconds']=91;put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'throughput'):
            self.fixture.window()

    def test_profiled_native_runtime_is_exact_not_merely_compatible(self):
        path=self.fixture.lib/'native.jar';path.write_bytes(b'new')
        self.fixture.pins[str(path)]=experiment.sha(path);self.fixture.save()
        with self.assertRaisesRegex(ValueError,'measured root-pair collector'):
            self.fixture.window()

    def test_partial_prior_result_is_not_complete_awareness(self):
        entry=self.fixture.protocol['prerequisites']['priorityResult'];path=Path(entry['file'])
        value=experiment.read(path);value['disposition']='running';put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'complete prior'):
            self.fixture.window()

    def test_prior_qualification_is_not_a_new_failure_requirement(self):
        entry=self.fixture.protocol['prerequisites']['priorityResult'];path=Path(entry['file'])
        value=experiment.read(path);value['disposition']='qualified-for-integration';put(path,value);self.fixture.repin(entry)
        self.fixture.window()

    def test_collection_source_contains_only_root_pair_plan_and_f_population(self):
        window=self.fixture.window()
        with patch.object(window,'native',return_value=window.lab/'breadth') as native, \
                patch.object(breadth,'coverage',return_value={'passed':False}):
            window.collect()
        mode,name,source,seconds=native.call_args.args
        self.assertEqual(('collect','breadth',7200),(mode,name,seconds))
        self.assertEqual(2,source['schemaVersion']);self.assertIsNone(source['continuation'])
        self.assertEqual(breadth.PLAN,source['collection']);self.assertEqual(window.requests,source['origins'])

    def test_insufficient_coverage_stops_before_fitting(self):
        window=self.fixture.window()
        with patch.object(window,'collect',return_value=(window.lab/'breadth',{'passed':False})), \
                patch.object(window,'fit') as fit:
            result=window.run()
        fit.assert_not_called();self.assertEqual('breadth-coverage-failed',result['disposition'])

    def test_fit_resume_preserves_original_deadline_and_explicit_eight_units(self):
        window=self.fixture.window();fit=window.lab/'breadth-fit'
        put(fit/'identity.json',{'deadlineEpochSeconds':1234});put(fit/'frequency-calibration.json',{'outcomeUse':False})
        with patch.object(window,'execute') as execute, patch.object(experiment,'completed_fit',return_value={'done':True}):
            window.fit(window.lab/'breadth')
        self.assertEqual(1234,execute.call_args.args[3]);self.assertIn('--resume',execute.call_args.args[1])
        self.assertEqual(['--active-hidden','8'],execute.call_args.args[1][-3:-1])

    def test_failed_full_local_gate_stops_even_if_priority_would_pass(self):
        window=self.fixture.window();fit=self.fixture.fit_files()
        covered={'passed':True,'counts':{'validation':{'origins':300}}}
        report={'validation':{'full':metrics(mse=.1), 'action-priority':metrics()}}
        with patch.object(window,'collect',return_value=(window.lab/'breadth',covered)), \
                patch.object(window,'fit',return_value=(fit,report)),patch.object(window,'screen') as screen:
            result=window.run()
        screen.assert_not_called();self.assertEqual('breadth-local-gate-failed',result['disposition'])

    def test_full_model_alone_reaches_both_independent_stages(self):
        window=self.fixture.window();fit=self.fixture.fit_files()
        covered={'passed':True,'counts':{'validation':{'origins':300}}}
        with patch.object(window,'collect',return_value=(window.lab/'breadth',covered)), \
                patch.object(window,'fit',return_value=(fit,{'validation':{'full':metrics()}})), \
                patch.object(window,'screen',return_value={'mayContinue':True}) as screen, \
                patch.object(window,'evaluate',side_effect=[{'passed':True},{'passed':False}]) as evaluate:
            result=window.run()
        screen.assert_called_once_with(fit/'full.json')
        self.assertTrue(all(call.args[1]['candidate']==fit/'full.json' for call in evaluate.call_args_list))
        self.assertEqual('independent-qualification-failed',result['disposition']);self.assertFalse(result['browserActivated'])


if __name__=='__main__':unittest.main()
