"""Operational continuation boundaries using synthetic artifacts only."""
from datetime import datetime,timezone
import hashlib,json
from pathlib import Path
import tempfile,time,unittest
from unittest.mock import patch

import card_continuation_experiment as continuation
import card_advantage_experiment as experiment
from test_card_advantage_experiment import completed_collection


def put(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);experiment.document(path,value,immutable=False)

def report(output,expected,classification='no-persistent-interaction',covered=True,ridge=True):
    identity={'originalDeadline':1234};put(output/'identity.json',identity)
    digest=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    if ridge:put(output/'ridge.json',{'coefficients':[0]*9})
    value=dict(inputs=expected,identitySha256=digest,ridgeSha256=experiment.sha(output/'ridge.json') if ridge else None,
        diagnosticOnly=True,policyPromotion=False,classification=classification,coverage={'passed':covered},
        persistence={'gatePassed':False},observableProbe={'gatePassed':False})
    put(output/'report.json',value);return value


class Fixture:
    def __init__(self,root):
        self.root=root.resolve();self.lab=self.root/'new';self.lab.mkdir()
        self.bin=self.root/'runtime/bin';self.lib=self.root/'runtime/lib'
        self.bin.mkdir(parents=True);self.lib.mkdir()
        for name in ('card_continuation_experiment.py','card_continuation_analysis.py','card_advantage_experiment.py',
                     'card_advantage_priority_experiment.py','card-advantage-run.py','card_advantage.py',
                     'card_advantage_data.py','card_policy.py','purchase_policy.py'):
            (self.bin/name).write_text('frozen '+name)
        (self.lib/'native.jar').write_text('native')
        for name in ('baseline','purchase','priority','features'):put(self.lab/f'inputs/{name}.json',{'frozen':name})
        self.priority_sha=experiment.sha(self.lab/'inputs/priority.json')
        self.pins={str(p):experiment.sha(p) for p in [*self.bin.iterdir(),*self.lib.iterdir(),*(self.lab/'inputs').iterdir()]}
        old=self.root/'old';put(old/'preflight.json',{'readyForCollection':True})
        put(old/'window-result.json',dict(preflightSha256=experiment.sha(old/'preflight.json'),
            disposition='breadth-local-gate-failed',browserActivated=False))
        put(old/'breadth-fit/finished.json',{'models':{'full':{'sha256':experiment.sha(self.lab/'inputs/features.json')}}})
        decay=self.root/'decay';put(decay/'protocol.json',{'fixed':'probe'})
        put(decay/'report.json',dict(phase='complete',protocolSha256=experiment.sha(decay/'protocol.json'),
            allPinsUnchanged=True,qualified=False,activated=False,newGameWorlds=0))
        profile=self.root/'profile';put(profile/'protocol.json',{'fixed':'proof'})
        put(profile/'report.json',dict(passed=True,allPinsUnchanged=True,worlds=8,newWorlds=0,
            compressedRecordsExactlyEqual=8,legacyRecordsExactlyEqualExceptSignature=2,
            legacySchema1RecordsExactlyEqualExceptSignature=2,referenceRootPairsExactlyEqual=8,
            batchWallSeconds=120,estimatedCollectionSecondsWithGuard=800,admitted=True,
            protocolSha256=experiment.sha(profile/'protocol.json'),runtimeJars={'native.jar':experiment.sha(self.lib/'native.jar')},
            sourceHashes={str(self.bin/'card-advantage-run.py'):experiment.sha(self.bin/'card-advantage-run.py')}))
        rows=[dict(index=i,seed=1000+i,seat=i%4,epoch=(i//4)%3+1,ordinal=(i//12)%8+1,
            cohort='generic',opponentUsesCandidate=[False]*4,split='train' if i<128 else 'validation') for i in range(256)]
        put(self.lab/'requests.json',rows)
        put(self.lab/'seeds.json',dict(passed=True,overlaps=[],banks=[{'name':'continuation','seeds':[r['seed'] for r in rows]}]))
        start=time.time()-10;iso=lambda t:datetime.fromtimestamp(t,timezone.utc).isoformat()
        put(self.lab/'clock.json',dict(startedAt=iso(start),deadline=iso(start+7200),originalAllowanceSeconds=7200,
            renewOnResume=False,collectionAdmissionDeadline=iso(start+7200),latestNewCollection=iso(start)))
        self.protocol=dict(kind='card-continuation-window',schemaVersion=1,configuration='qualified-profile',
            clock=self.entry(self.lab/'clock.json'),seedReservation=self.entry(self.lab/'seeds.json'),
            originRequests={'continuation':self.entry(self.lab/'requests.json')},
            collection={'maximumSeconds':5400,'batchSize':8,'plan':continuation.PLAN},coverage=continuation.COVERAGE,
            analysis=continuation.ANALYSIS,resources={'minimumDiskFreeBytes':8*1024**3,'maximumNewDataBytes':4*1024**3},
            prerequisites={name:self.entry(path) for name,path in {
                'breadthPreflight':old/'preflight.json','breadthResult':old/'window-result.json',
                'breadthFitReport':old/'breadth-fit/finished.json','featureModel':self.lab/'inputs/features.json',
                'priorityCandidate':self.lab/'inputs/priority.json','decayProtocol':decay/'protocol.json',
                'decayReport':decay/'report.json','opsProfile':profile/'report.json','opsProtocol':profile/'protocol.json'}.items()})
        draft={key:self.protocol[key] for key in ('configuration','collection','coverage','analysis','resources','seedReservation','originRequests')}
        put(self.lab/'recipe.json',draft);self.protocol['recipeDraft']=self.entry(self.lab/'recipe.json');self.save()

    def entry(self,path):
        self.pins[str(path)]=experiment.sha(path);return {'file':str(path),'sha256':experiment.sha(path)}
    def save(self):
        put(self.lab/'protocol.json',self.protocol);put(self.lab/'preflight.json',dict(readyForCollection=True,
            windowSha256=experiment.sha(self.lab/'protocol.json'),pins=self.pins))
    def repin(self,entry):
        path=Path(entry['file']);entry['sha256']=self.pins[str(path)]=experiment.sha(path);self.save()
    def window(self):
        with patch.object(experiment,'__file__',str(self.bin/'card_advantage_experiment.py')), \
                patch.object(continuation,'PRIORITY_SHA256',self.priority_sha):
            return continuation.ContinuationWindow(self.lab,self.lab/'protocol.json')
    def source(self):
        path=self.lab/'continuation';put(path/'finished.json',{'complete':'opaque-hashes'});return path


class CompletionTest(unittest.TestCase):
    def test_new_origin_kind_reuses_entire_hash_boundary_without_decoding_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();source=completed_collection(root)
            manifest=experiment.read(root/'collection.json');manifest['kind']='card-continuation-diagnostic'
            put(root/'collection.json',manifest)
            self.assertTrue(experiment.completed_native(root,source))
            (root/'origin-0.json.gz').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'record hashes'):
                experiment.completed_native(root,source)

    def test_null_ridge_is_allowed_only_for_insufficient_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);expected={'fixed':'inputs'}
            value=report(output,expected,'insufficient-coverage',False,False)
            continuation.check_analysis(value,expected,output)
            value.update(classification='no-persistent-interaction');value['coverage']['passed']=True
            with self.assertRaisesRegex(ValueError,'classification'):
                continuation.check_analysis(value,expected,output)

    def test_frozen_ridge_mutation_rejects_report_without_reanalysis(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);expected={'fixed':'inputs'};value=report(output,expected)
            put(output/'ridge.json',{'different':True})
            with self.assertRaisesRegex(ValueError,'ridge coefficients'):
                continuation.check_analysis(value,expected,output)

    def test_observable_probe_without_persistence_stays_no_persistent_interaction(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);expected={'fixed':'inputs'};value=report(output,expected)
            value['observableProbe']['gatePassed']=True
            continuation.check_analysis(value,expected,output)
            value['classification']='persistent-and-predicted-by-fixed-probe'
            with self.assertRaisesRegex(ValueError,'classification'):
                continuation.check_analysis(value,expected,output)


class ContinuationDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.fixture=Fixture(Path(self.temp.name))
    def tearDown(self):self.temp.cleanup()

    def test_constructor_checks_prerequisites_without_decoding_any_origins(self):
        window=self.fixture.window();self.assertEqual(256,len(window.requests))
        self.assertFalse((window.lab/'window-status.json').exists())

    def test_recipe_and_clock_cannot_be_relaxed_on_resume(self):
        self.fixture.protocol['collection']=dict(self.fixture.protocol['collection'],maximumSeconds=7200);self.fixture.save()
        with self.assertRaisesRegex(ValueError,'recipe changed'):self.fixture.window()

    def test_partial_decay_probe_is_not_complete_awareness(self):
        entry=self.fixture.protocol['prerequisites']['decayReport'];path=Path(entry['file'])
        value=experiment.read(path);value['phase']='running';put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'completed no-decay'):self.fixture.window()

    def test_different_priority_model_is_rejected_even_with_new_pin(self):
        entry=self.fixture.protocol['prerequisites']['priorityCandidate'];put(Path(entry['file']),{'changed':True});self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'priority64 continuation'):self.fixture.window()

    def test_both_legacy_schemas_require_two_exact_replayed_records(self):
        entry=self.fixture.protocol['prerequisites']['opsProfile'];path=Path(entry['file'])
        original=experiment.read(path)
        for field in ('legacyRecordsExactlyEqualExceptSignature','legacySchema1RecordsExactlyEqualExceptSignature'):
            with self.subTest(field=field):
                value=dict(original);value[field]=1;put(path,value);self.fixture.repin(entry)
                with self.assertRaisesRegex(ValueError,'consumed-world parity proof'):self.fixture.window()

    def test_profile_admission_requires_positive_finite_bounded_consistent_estimate(self):
        entry=self.fixture.protocol['prerequisites']['opsProfile'];path=Path(entry['file'])
        original=experiment.read(path)
        changes=({'admitted':False},{'admitted':1},
            {'estimatedCollectionSecondsWithGuard':5401},
            {'estimatedCollectionSecondsWithGuard':0},
            {'estimatedCollectionSecondsWithGuard':True},
            {'estimatedCollectionSecondsWithGuard':float('nan')},
            {'estimatedCollectionSecondsWithGuard':float('inf')},
            {'batchWallSeconds':float('inf')},{'batchWallSeconds':121})
        for change in changes:
            with self.subTest(change=change):
                path.write_text(json.dumps(dict(original,**change)))
                self.fixture.repin(entry)
                with self.assertRaisesRegex(ValueError,'guarded collection estimate'):self.fixture.window()

    def test_imported_card_policy_helper_requires_its_preflight_pin(self):
        del self.fixture.pins[str(self.fixture.bin/'card_policy.py')];self.fixture.save()
        with self.assertRaisesRegex(ValueError,'Every executed helper'):self.fixture.window()

    def test_collect_preserves_explicit_f_prefix_and_does_not_open_payloads(self):
        window=self.fixture.window();output=self.fixture.source()
        put(output/'collection.json',dict(kind='card-continuation-diagnostic',schemaVersion=3,
            collection=continuation.PLAN,backbonePolicy='F',opponentPolicy='F'))
        with patch.object(window,'native',return_value=output) as native:
            self.assertEqual(output,window.collect())
        mode,name,source,seconds=native.call_args.args
        self.assertEqual(('collect','continuation',5400),(mode,name,seconds))
        self.assertEqual(str(window.priority),source['continuation']);self.assertEqual(3,source['schemaVersion'])
        self.assertEqual(continuation.PLAN,source['collection'])

    def test_wrong_prefix_cannot_be_accepted_as_an_own_tail_intervention(self):
        window=self.fixture.window();output=self.fixture.source()
        put(output/'collection.json',dict(kind='card-continuation-diagnostic',schemaVersion=3,
            collection=continuation.PLAN,backbonePolicy='priority64',opponentPolicy='F'))
        with patch.object(window,'native',return_value=output):
            with self.assertRaisesRegex(ValueError,'prefix and rival'):window.collect()

    def test_analysis_resume_uses_original_clock_and_explicit_resume_flag(self):
        window=self.fixture.window();source=self.fixture.source()
        def interrupted(*args):
            put(window.lab/'analysis/identity.json',{'partial':True});raise RuntimeError('interrupted')
        with patch.object(window,'execute',side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError,'interrupted'):window.analyze(source)
        original=(window.lab/'analysis-clock.json').read_bytes()
        def resumed(*args):
            report(window.lab/'analysis',experiment.read(window.lab/'analysis-inputs.json'))
        with patch.object(window,'execute',side_effect=resumed) as execute:
            window.analyze(source)
        self.assertEqual(original,(window.lab/'analysis-clock.json').read_bytes())
        self.assertIn('--resume',execute.call_args.args[1]);self.assertEqual(json.loads(original)['deadline'],execute.call_args.args[3])

    def test_partial_analysis_cannot_recreate_a_missing_original_clock(self):
        window=self.fixture.window();source=self.fixture.source();put(window.lab/'analysis/identity.json',{'partial':True})
        with patch.object(window,'execute') as execute:
            with self.assertRaisesRegex(ValueError,'missing original clock'):window.analyze(source)
        execute.assert_not_called()

    def test_completed_analysis_is_reused_without_running_another_process(self):
        window=self.fixture.window();source=self.fixture.source()
        def complete(*args):report(window.lab/'analysis',experiment.read(window.lab/'analysis-inputs.json'))
        with patch.object(window,'execute',side_effect=complete):first=window.analyze(source)
        with patch.object(window,'execute') as execute:
            second=window.analyze(source)
        self.assertEqual(first,second);execute.assert_not_called()

    def test_result_is_diagnostic_only_even_with_positive_classification(self):
        window=self.fixture.window()
        output=window.lab/'analysis';put(output/'report.json',{'complete':True})
        with patch.object(window,'collect',return_value=window.lab/'continuation'), \
                patch.object(window,'analyze',return_value=dict(classification='persistent-and-predicted-by-fixed-probe',coverage={'passed':True})):
            result=window.run()
        self.assertEqual('continuation-diagnostic-complete',result['disposition'])
        self.assertTrue(result['diagnosticOnly']);self.assertFalse(result['policyPromotion']);self.assertFalse(result['browserActivated'])


if __name__=='__main__':unittest.main()
