"""Conditional-stage contracts with synthetic files only: no native games, fitting or imports of Torch."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import card_advantage_compact_experiment as compact
import card_advantage_experiment as experiment
from test_card_advantage_experiment import completed_collection


def put(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True))


def fit_recipe(active=8):
    value = dict(learningRate=.003, weightDecay=.0001, gradientNormLimit=1.,
                 wholeOriginsPerBatch=16, epochs=64, checkpoints=[8, 16, 32, 64],
                 initializationSeeds=[84501, 84502, 84503], threads=1, maximumSecondsPerCycle=2700)
    if active is not None:
        value['activeHidden'] = active
    return value


class Fixture:
    def __init__(self, root):
        self.root = root.resolve()
        self.lab = self.root / 'compact'
        self.lab.mkdir()
        self.prior = self.root / 'prior'
        self.training = self.prior / 'cycle-1'
        self.training.mkdir(parents=True)
        old_source = completed_collection(self.training)
        self.training_source = self.prior / 'cycle-1-source.json'
        self.training_source.write_bytes(old_source.read_bytes())
        self.bin = self.root / 'runtime/bin'
        self.lib = self.root / 'runtime/lib'
        self.bin.mkdir(parents=True)
        self.lib.mkdir()
        for name in ('card_advantage_compact_experiment.py', 'card_advantage_experiment.py',
                     'card_advantage_capacity.py', 'card-advantage-run.py',
                     'card_advantage_evaluation.py', 'card_advantage_train.py',
                     'card_advantage.py', 'card_advantage_data.py', 'purchase_policy.py'):
            (self.bin / name).write_text('frozen ' + name)
        for name in ('ai.jar', 'core.jar', 'training.jar'):
            (self.lib / name).write_text('frozen ' + name)
        put(self.prior / 'preflight.json', {'readyForCollection': True})
        put(self.prior / 'window-result.json', dict(disposition='independent-qualification-failed',
            preflightSha256=experiment.sha(self.prior / 'preflight.json'), browserActivated=False,
            primaryPassed=False, robustnessPassed=True))
        put(self.prior / 'cycle-1-fit/full.json', {'reference': True})
        put(self.prior / 'cycle-1-fit/finished.json', {'models': {'full': {
            'sha256': experiment.sha(self.prior / 'cycle-1-fit/full.json')}}})
        prior_result=experiment.read(self.prior/'window-result.json')
        prior_result['finalist']={'modelSha256':experiment.sha(self.prior/'cycle-1-fit/full.json')}
        put(self.prior/'window-result.json',prior_result)
        put(self.lab / 'inputs/baseline.json', {})
        put(self.lab / 'inputs/purchase.json', {})
        requests = [dict(index=i, split='validation', seed=1000+i, seat=i%4, epoch=3,
                         cohort='generic', ordinal=1, opponentUsesCandidate=[False]*4) for i in range(128)]
        put(self.lab / 'origins.json', requests)
        put(self.lab / 'seeds.json', dict(passed=True, overlaps=[], banks=[
            {'name': 'compact-validation', 'seeds': [r['seed'] for r in requests]},
            {'name': 'common-development', 'seeds': list(range(2000,2256))},
            {'name': 'primary-confirmation', 'seeds': list(range(3000,4024))},
            {'name': 'rival-robustness', 'seeds': list(range(5000,5256))}]))
        start = time.time() - 10
        iso = lambda value: datetime.fromtimestamp(value, timezone.utc).isoformat()
        put(self.lab / 'clock.json', dict(startedAt=iso(start), deadline=iso(start+21600),
            collectionAdmissionDeadline=iso(start+10800), latestNewCollection=iso(start+10800),
            originalAllowanceSeconds=21600, renewOnResume=False))
        self.pins = {}
        def entry(path):
            self.pins[str(path)] = experiment.sha(path)
            return dict(file=str(path), sha256=experiment.sha(path))
        self.protocol = dict(kind='card-advantage-compact-window', schemaVersion=1,
            configuration='qualified-profile', clock=entry(self.lab/'clock.json'),
            seedReservation=entry(self.lab/'seeds.json'),
            originRequests={'compact-validation': entry(self.lab/'origins.json')},
            fit=fit_recipe(), collection={'maximumSeconds':3600},
            coverage={'validation':{'origins':96,'epoch3':8,'printedWordLeader':4}},
            evaluation={'batchSize':8,'maximumSecondsPerStage':7200}, analysis={'maximumSeconds':600},
            resources={'minimumDiskFreeBytes':8*1024**3,'maximumNewDataBytes':4*1024**3})
        toy = self.root / 'toy'
        put(toy/'protocol.json', dict(config=experiment.fit_config(self.protocol), maximumSeconds=600,
            sourceHashes={str(self.bin/name):experiment.sha(self.bin/name)
                          for name in ('card_advantage.py','card_advantage_train.py')}))
        put(toy/'report.json', dict(passed=True,status='complete',actualGames=0,cycles=[1,2,3,4],
            protocolSha256=experiment.sha(toy/'protocol.json')))
        profile = self.root/'profile'
        put(profile/'protocol.json', dict(runtime=str(self.lib),pins={str(p):experiment.sha(p) for p in self.lib.glob('*.jar')}))
        put(profile/'report.json', dict(passed=True,allPinsUnchanged=True,compressedRecordsExactlyEqual=64,
            oldRecordsExactlyEqualExceptSignature=64,exactOldPublicTerminalsAndSteps=64,newWorlds=0,
            protocolSha256=experiment.sha(profile/'protocol.json')))
        self.protocol['prerequisites'] = dict(trainingCollection=str(self.training),
            priorAudit=entry(self.prior/'preflight.json'), priorResult=entry(self.prior/'window-result.json'),
            trainingSource=entry(self.training_source), referenceModel=entry(self.prior/'cycle-1-fit/full.json'),
            compactToy=entry(toy/'report.json'), compactToyProtocol=entry(toy/'protocol.json'),
            batchProfile=entry(profile/'report.json'), batchProfileProtocol=entry(profile/'protocol.json'))
        draft={key:self.protocol[key] for key in ('configuration','fit','collection','coverage','evaluation',
                                                 'analysis','resources','seedReservation','originRequests')}
        draft['trainingCollection']=str(self.training)
        put(self.lab/'recipe-draft.json',draft)
        self.protocol['recipeDraft']=entry(self.lab/'recipe-draft.json')
        self.pins.update({str(p):experiment.sha(p) for p in [*self.bin.iterdir(),*self.lib.iterdir()]})
        self.save()

    def save(self):
        put(self.lab/'protocol.json',self.protocol)
        put(self.lab/'preflight.json',dict(readyForCollection=True,
            windowSha256=experiment.sha(self.lab/'protocol.json'),pins=self.pins))

    def repin(self, entry):
        path=Path(entry['file']);entry['sha256']=experiment.sha(path);self.pins[str(path)]=entry['sha256'];self.save()

    def window(self):
        with patch.object(experiment,'__file__',str(self.bin/'card_advantage_experiment.py')):
            return compact.CompactWindow(self.lab,self.lab/'protocol.json')

    def models(self):
        fit=self.lab/'compact-fit'
        for name in ('full.json','frequency-control.json','finished.json'):
            put(fit/name,{'fixed':name})
        return fit


class CompactDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.fixture=Fixture(Path(self.temp.name))
    def tearDown(self):self.temp.cleanup()

    def test_complete_matching_prerequisites_admit_no_work_by_themselves(self):
        window=self.fixture.window()
        self.assertEqual(window.training,self.fixture.training)
        self.assertFalse((self.fixture.lab/'compact-fit').exists())

    def test_prior_qualified_policy_forbids_conditional_run(self):
        entry=self.fixture.protocol['prerequisites']['priorResult'];path=Path(entry['file'])
        value=experiment.read(path);value.update(disposition='qualified-for-integration',primaryPassed=True)
        put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'qualified policy forbids'):self.fixture.window()

    def test_prior_partial_or_missing_result_is_not_an_implicit_failure(self):
        entry=self.fixture.protocol['prerequisites']['priorResult'];path=Path(entry['file'])
        value=experiment.read(path);value['disposition']='cycle-1-development-gate-failed'
        put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'completed and failed'):self.fixture.window()
        path.unlink()
        with self.assertRaises(FileNotFoundError):self.fixture.window()

    def test_prepared_recipe_cannot_be_retuned_while_prerequisites_are_frozen(self):
        self.fixture.protocol['fit']={**self.fixture.protocol['fit'],'learningRate':.1};self.fixture.save()
        with self.assertRaisesRegex(ValueError,'scientific recipe changed'):self.fixture.window()

    def test_seven_hour_clock_or_renewal_flag_is_rejected(self):
        entry=self.fixture.protocol['clock'];path=Path(entry['file']);clock=experiment.read(path)
        clock['renewOnResume']=True;put(path,clock);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'six-hour clock'):self.fixture.window()

    def test_matching_contents_at_a_different_training_source_do_not_replace_c1(self):
        path=self.fixture.prior/'another-source.json';path.write_bytes(self.fixture.training_source.read_bytes())
        entry=self.fixture.protocol['prerequisites']['trainingSource'];entry['file']=str(path);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'original C1 source'):self.fixture.window()

    def test_reference_must_be_the_exact_failed_prior_finalist(self):
        entry=self.fixture.protocol['prerequisites']['priorResult'];path=Path(entry['file'])
        value=experiment.read(path);value['finalist']['modelSha256']='different-model'
        put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'prior finalist'):self.fixture.window()

    def test_failed_toy_cannot_be_replaced_by_a_passing_profile(self):
        entry=self.fixture.protocol['prerequisites']['compactToy'];path=Path(entry['file'])
        value=experiment.read(path);value['passed']=False;put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'four-cycle'):self.fixture.window()

    def test_profile_requires_all_64_exact_old_and_new_game_records(self):
        entry=self.fixture.protocol['prerequisites']['batchProfile'];path=Path(entry['file'])
        value=experiment.read(path);value['oldRecordsExactlyEqualExceptSignature']=63;put(path,value);self.fixture.repin(entry)
        with self.assertRaisesRegex(ValueError,'parity proof'):self.fixture.window()

    def test_even_pinned_different_jars_cannot_adopt_another_profiles_parity(self):
        jar=self.fixture.lib/'training.jar';jar.write_text('changed runtime')
        self.fixture.pins[str(jar)]=experiment.sha(jar);self.fixture.save()
        with self.assertRaisesRegex(ValueError,'byte-identical'):self.fixture.window()

    def test_late_new_fit_respects_full_fit_collection_analysis_reserve(self):
        window=self.fixture.window()
        with patch.object(compact.time,'time',return_value=window.admission-6899):
            with patch.object(window,'execute') as execute:
                with self.assertRaises(experiment.CollectionNotAdmitted):window.fit_compact()
                execute.assert_not_called()

    def test_original_validation_failure_is_descriptive_and_not_a_progression_gate(self):
        window=self.fixture.window()
        def execute(name,command,seconds,original_deadline):self.fixture.models()
        with patch.object(window,'execute',side_effect=execute) as execute, \
             patch.object(experiment,'completed_fit',return_value={'validation':{'full':{'localGatePassed':False}}}):
            fit=window.fit_compact()
        self.assertEqual(fit,self.fixture.lab/'compact-fit')
        self.assertIn('--active-hidden',execute.call_args.args[1])
        self.assertEqual(execute.call_args.args[1][-1],'8')
        self.assertFalse(experiment.read(self.fixture.lab/'compact-fit-disposition.json')['originalLocalGateUsedForAdmission'])

    def test_fit_resume_passes_its_original_deadline_without_refreezing_it(self):
        window=self.fixture.window();fit=self.fixture.lab/'compact-fit'
        put(fit/'identity.json',{'deadlineEpochSeconds':123.0})
        def execute(*_):put(fit/'finished.json',{'complete':True})
        with patch.object(window,'execute',side_effect=execute) as execute,patch.object(experiment,'completed_fit',return_value={}):
            window.fit_compact()
        self.assertEqual(execute.call_args.args[3],123.0)
        self.assertIn('--resume',execute.call_args.args[1])

    def test_failed_fresh_local_gate_cannot_start_complete_game_evaluation(self):
        window=self.fixture.window();fit=self.fixture.models()
        with patch.object(window,'fit_compact',return_value=fit), \
             patch.object(window,'validate_compact',return_value={'candidate':{'localGatePassed':False}}), \
             patch.object(window,'evaluate') as evaluate:
            result=window.run()
        self.assertEqual(result['disposition'],'compact-fresh-local-gate-failed');evaluate.assert_not_called()

    def test_reference_win_or_tie_against_incumbent_stops_without_reselecting_p1(self):
        for selected,mean in [('p1',.3),('p2',.25)]:
            with self.subTest(selected=selected):
                result_path=self.fixture.lab/'window-result.json'
                if result_path.exists():result_path.unlink()
                window=self.fixture.window();fit=self.fixture.models()
                with patch.object(window,'fit_compact',return_value=fit), \
                     patch.object(window,'validate_compact',return_value={'candidate':{'localGatePassed':True}}), \
                     patch.object(window,'evaluate',return_value={'selected':selected,'meanWinShares':{'p2':mean,'baseline':.25}}) as evaluate:
                    result=window.run()
                self.assertEqual(result['disposition'],'compact-common-development-gate-failed')
                self.assertEqual(evaluate.call_count,1);self.assertFalse((self.fixture.lab/'finalist.json').exists())

    def test_only_selected_compact_and_its_control_enter_both_fixed_final_banks(self):
        window=self.fixture.window();fit=self.fixture.models()
        reports=[{'selected':'p2','meanWinShares':{'p2':.3,'baseline':.25}}, {'passed':True},{'passed':False}]
        with patch.object(window,'fit_compact',return_value=fit), \
             patch.object(window,'validate_compact',return_value={'candidate':{'localGatePassed':True}}), \
             patch.object(window,'evaluate',side_effect=reports) as evaluate:
            result=window.run()
        self.assertEqual([call.args[0] for call in evaluate.call_args_list],
                         ['common-development','primary-confirmation','rival-robustness'])
        self.assertEqual(evaluate.call_args_list[1].args[1],{'candidate':fit/'full.json','frequency':fit/'frequency-control.json'})
        self.assertEqual(result['disposition'],'independent-qualification-failed')
        self.assertFalse(result['browserActivated'])

    def test_capacity_report_resume_retains_original_clock_and_exact_input_binding(self):
        window=self.fixture.window();fit=self.fixture.models();output=self.fixture.lab/'compact-validation'
        put(output/'started.json',{'started':True});put(output/'finished.json',{'complete':True})
        def analyze(*_):
            expected=experiment.read(self.fixture.lab/'compact-analysis-inputs.json')
            put(self.fixture.lab/'compact-capacity-report.json',{'inputs':expected,'candidate':{'localGatePassed':True}})
        with patch.object(window,'native'),patch.object(experiment,'coverage',return_value={'passed':True}), \
             patch.object(window,'execute',side_effect=analyze) as execute:
            window.validate_compact(fit)
            clock=(self.fixture.lab/'compact-analysis-clock.json').read_bytes()
            window.validate_compact(fit)
        self.assertEqual(execute.call_count,1)
        self.assertEqual((self.fixture.lab/'compact-analysis-clock.json').read_bytes(),clock)

    def test_lost_analysis_clock_binding_cannot_renew_an_already_started_analysis(self):
        window=self.fixture.window();fit=self.fixture.models();output=self.fixture.lab/'compact-validation'
        put(output/'started.json',{'started':True});put(output/'finished.json',{'complete':True})
        (self.fixture.lab/'compact-capacity-analysis.log').write_text('started')
        with patch.object(window,'native'),patch.object(experiment,'coverage',return_value={'passed':True}):
            with self.assertRaisesRegex(ValueError,'missing original clock'):window.validate_compact(fit)
        self.assertFalse((self.fixture.lab/'compact-analysis-clock.json').exists())

    def test_existing_capacity_report_with_changed_reference_is_rejected(self):
        window=self.fixture.window();fit=self.fixture.models();output=self.fixture.lab/'compact-validation'
        put(output/'started.json',{'started':True});put(output/'finished.json',{'complete':True})
        def analyze(*_):
            expected=experiment.read(self.fixture.lab/'compact-analysis-inputs.json');expected['referenceSha256']='changed'
            put(self.fixture.lab/'compact-capacity-report.json',{'inputs':expected,'candidate':{'localGatePassed':True}})
        with patch.object(window,'native'),patch.object(experiment,'coverage',return_value={'passed':True}), \
             patch.object(window,'execute',side_effect=analyze):
            with self.assertRaisesRegex(ValueError,'exact completed fresh validation'):window.validate_compact(fit)


if __name__=='__main__':unittest.main()
