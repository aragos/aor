"""Driver-only synthetic boundaries: no native process, fresh worlds or evaluated policies."""
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import card_advantage_experiment as experiment
import joint_economy_experiment as driver
import joint_economy as search


def put(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    experiment.document(path, value, immutable=False)


class Fixture:
    def __init__(self, root):
        self.root = root.resolve(); self.lab = self.root / 'lab'; self.lab.mkdir()
        self.scripts = self.root / 'runtime/bin'; self.runtime = self.root / 'runtime/lib'
        self.scripts.mkdir(parents=True); self.runtime.mkdir()
        for name in driver.HELPERS: (self.scripts / name).write_text('frozen ' + name)
        (self.runtime / 'native.jar').write_text('native')
        model = {'schemaVersion':1,'rulesVersion':'rules','dataVersion':'catalog',
                 'weights':{key:float(i+1) for i,key in enumerate(k for keys in search.AXES.values() for k in keys)}}
        put(self.lab / 'inputs/baseline.json', model)
        put(self.lab / 'inputs/purchase.json', {'purchase':'fixed'})
        put(self.lab / 'inputs/priority.json', {'priority':'fixed'})
        self.base_sha = experiment.sha(self.lab / 'inputs/baseline.json')
        self.purchase_sha = experiment.sha(self.lab / 'inputs/purchase.json')
        self.priority_sha = experiment.sha(self.lab / 'inputs/priority.json')
        search.generate(self.lab / 'inputs/baseline.json', self.lab / 'candidates')
        put(self.lab / 'binding.json', dict(baselineSha256=self.base_sha,candidateSha256=self.purchase_sha,
            sourceParametersPreserved=True,qualificationPassed=True))
        for name in ('purchase','rivals'):
            put(self.lab / (name+'-evidence.json'),dict(passed=True,candidateSha256=self.purchase_sha))
        put(self.lab / 'priority-fit.json',{'models':{'action-priority':{'sha256':self.priority_sha}}})
        inputs = dict(baselineSha256=self.base_sha,purchaseSha256=self.purchase_sha,
            prioritySha256=self.priority_sha,candidateManifestSha256=experiment.sha(self.lab / 'candidates/manifest.json'))
        for name in ('parity','feasibility'):
            put(self.lab / (name+'.json'),dict(passed=True,inputs=inputs,
                runtimeJars={'native.jar':experiment.sha(self.runtime / 'native.jar')},allPinsUnchanged=True,
                sourceHashes={str(self.scripts / driver.HELPERS[0]):experiment.sha(self.scripts / driver.HELPERS[0])},
                newWorlds=0,exactGames=4,fixedInputs=True,candidateCount=15))
        put(self.lab / 'seeds.json',dict(passed=True,overlaps=[],banks=[
            {'name':'screen','seeds':list(range(64))},{'name':'selection','seeds':list(range(64,256))},
            {'name':'confirmation','seeds':list(range(256,1280))}]))
        now = time.time()-10; stamp=lambda x:datetime.fromtimestamp(x,timezone.utc).isoformat()
        put(self.lab / 'clock.json',dict(startedAt=stamp(now),deadline=stamp(now+10800),
            originalAllowanceSeconds=10800,renewOnResume=False,
            collectionAdmissionDeadline=stamp(now+10800),latestNewCollection=stamp(now)))
        self.pins = {str(p):experiment.sha(p) for folder in (self.scripts,self.runtime,self.lab / 'inputs',self.lab / 'candidates')
                     for p in folder.iterdir()}
        self.pins[str(Path(driver.sys.executable).resolve())]=experiment.sha(driver.sys.executable)
        def entry(path):
            self.pins[str(path)] = experiment.sha(path)
            return dict(file=str(path),sha256=experiment.sha(path))
        self.entry = entry
        recipe = dict(configuration='fixed-profile',stages=driver.STAGES,statistics=driver.STATISTICS,
            pythonRuntime=driver.python_runtime(),
            resources=driver.RESOURCES,evaluation={'batchSize':8},originRequests={},
            seedReservation=entry(self.lab / 'seeds.json'),
            candidateManifest=entry(self.lab / 'candidates/manifest.json'))
        put(self.lab / 'recipe.json',recipe)
        self.protocol = dict(recipe,kind='joint-economy-search-window',schemaVersion=1,
            clock=entry(self.lab / 'clock.json'),recipeDraft=entry(self.lab / 'recipe.json'),
            prerequisites={name:entry(self.lab / file) for name,file in {
                'baselineBindingEvidence':'binding.json','purchaseEvidence':'purchase-evidence.json',
                'purchaseRivalEvidence':'rivals-evidence.json','priorityFitCompletion':'priority-fit.json',
                'candidateManifest':'candidates/manifest.json','priorityCandidate':'inputs/priority.json',
                'incumbentParity':'parity.json','behavioralFeasibility':'feasibility.json'}.items()})
        self.save()

    def save(self):
        put(self.lab / 'window.json',self.protocol)
        put(self.lab / 'preflight.json',dict(readyForCollection=True,
            windowSha256=experiment.sha(self.lab / 'window.json'),pins=self.pins))

    def change_proof(self, name, update):
        entry = self.protocol['prerequisites'][name]; path = Path(entry['file'])
        value=experiment.read(path); update(value); put(path,value)
        entry['sha256']=self.pins[str(path)]=experiment.sha(path);self.save()

    def window(self):
        with patch.object(experiment,'__file__',str(self.scripts / 'card_advantage_experiment.py')), \
             patch.object(driver,'BASE_SHA256',self.base_sha),patch.object(driver,'PURCHASE_SHA256',self.purchase_sha), \
             patch.object(driver,'PRIORITY_SHA256',self.priority_sha):
            return driver.JointEconomyWindow(self.lab,self.lab / 'window.json')


class Boundaries(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.f=Fixture(Path(self.tmp.name))

    def test_constructor_accepts_exact_prospective_inputs_without_games(self):
        window=self.f.window()
        self.assertEqual(14,len(window.ids));self.assertFalse((self.f.lab / 'screen').exists())

    def test_seed_reuse_rejected_even_with_rebound_reservation(self):
        path=self.f.lab / 'seeds.json';value=experiment.read(path)
        value['banks'][1]['seeds'][0]=0;put(path,value)
        self.f.protocol['seedReservation']=self.f.entry(path)
        recipe=experiment.read(self.f.lab / 'recipe.json');recipe['seedReservation']=self.f.protocol['seedReservation']
        put(self.f.lab / 'recipe.json',recipe);self.f.protocol['recipeDraft']=self.f.entry(self.f.lab / 'recipe.json');self.f.save()
        with self.assertRaisesRegex(ValueError,'1280 worlds'):self.f.window()

    def test_clock_cannot_extend_original_allowance(self):
        path=self.f.lab / 'clock.json';value=experiment.read(path);value['originalAllowanceSeconds']=10801
        put(path,value);self.f.protocol['clock']=self.f.entry(path);self.f.save()
        with self.assertRaisesRegex(ValueError,'three-hour clock'):self.f.window()

    def test_changed_numerical_environment_rejected(self):
        identity=dict(driver.python_runtime(),numpyVersion='different')
        with patch.object(driver,'python_runtime',return_value=identity):
            with self.assertRaisesRegex(ValueError,'pinned Python'):self.f.window()

    def test_candidate_byte_mutation_rejected(self):
        (self.f.lab / 'candidates/liquidity-low.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'Frozen preflight'):self.f.window()

    def test_wrong_runtime_even_rebound_proof_rejected(self):
        self.f.change_proof('incumbentParity',lambda d:d['runtimeJars'].update({'native.jar':'wrong'}))
        with self.assertRaisesRegex(ValueError,'current runtime'):self.f.window()

    def test_two_game_parity_is_insufficient(self):
        self.f.change_proof('incumbentParity',lambda d:d.update(exactGames=2))
        with self.assertRaisesRegex(ValueError,'four exact'):self.f.window()

    def test_boolean_game_count_is_not_integer_coverage(self):
        self.f.change_proof('incumbentParity',lambda d:d.update(exactGames=True))
        with self.assertRaisesRegex(ValueError,'four exact'):self.f.window()

    def test_rebound_purchase_evidence_still_requires_qualified_candidate(self):
        self.f.change_proof('purchaseEvidence',lambda d:d.update(candidateSha256='other'))
        with self.assertRaisesRegex(ValueError,'purchase model'):self.f.window()

    def test_missing_behavioral_coverage_rejected(self):
        self.f.change_proof('behavioralFeasibility',lambda d:d.update(candidateCount=14))
        with self.assertRaisesRegex(ValueError,'all fixed candidates'):self.f.window()

    def test_selection_failure_does_not_open_confirmation(self):
        window=self.f.window(); ids=window.ids[:3]
        with patch.object(window,'stage',side_effect=[{'selected':ids},{'winner':ids[0],'mayConfirm':False}]) as stage:
            put(self.f.lab / 'screen-report.json',{'test':'complete'})
            result=window.run()
        self.assertEqual(['screen','selection'],[call.args[0] for call in stage.call_args_list])
        self.assertEqual('joint-economy-selection-gate-failed',result['disposition'])
        self.assertFalse(result['policyPromotion']);self.assertFalse(result['browserActivated'])

    def test_success_freezes_only_selected_candidate_and_never_activates(self):
        window=self.f.window(); ids=window.ids[:3]
        for name in ('screen','selection'):put(self.f.lab / (name+'-report.json'),{'test':'complete'})
        with patch.object(window,'stage',side_effect=[{'selected':ids},{'winner':ids[1],'mayConfirm':True},{'passed':True}]) as stage:
            result=window.run()
        self.assertEqual(('confirmation',[ids[1]]),stage.call_args_list[2].args)
        self.assertEqual(ids[1],result['finalist']['id']);self.assertFalse(result['browserActivated'])
        self.assertFalse(result['policyPromotion'])

    def test_stage_reuses_bound_report_and_rejects_mutation(self):
        window=self.f.window(); output=self.f.lab / 'screen';output.mkdir()
        put(output / 'started.json',{'deadline':time.time()+20})
        def native(mode,name,source,seconds):
            put(self.f.lab / (name+'-source.json'),source);return output
        def execute(name,command,seconds,deadline):
            with patch.object(search,'analyze_completed',return_value={'selected':window.ids[:3]}):
                driver.analyze_request(Path(command[-1]))
        with patch.object(window,'native',side_effect=native),patch.object(window,'execute',side_effect=execute) as process:
            first=window.stage('screen',window.ids);self.assertEqual(first,window.stage('screen',window.ids))
            self.assertEqual(1,process.call_count)
            put(self.f.lab / 'screen-report.json',{'selected':window.ids[1:4]})
            with self.assertRaisesRegex(ValueError,'report changed'):window.stage('screen',window.ids)

    def test_analyzer_never_runs_if_native_completion_fails(self):
        window=self.f.window()
        with patch.object(window,'native',side_effect=ValueError('incomplete')),patch.object(window,'execute') as execute:
            with self.assertRaisesRegex(ValueError,'incomplete'):window.stage('screen',window.ids)
            execute.assert_not_called()

    def test_report_requires_exact_source_hash_before_statistics(self):
        request=self.f.lab / 'request.json';source=self.f.lab / 'source.json';put(source,{})
        put(request,{'source':str(source),'sourceSha256':'wrong'})
        with patch.object(search,'analyze_completed') as analyze:
            with self.assertRaisesRegex(ValueError,'source changed'):driver.analyze_request(request)
            analyze.assert_not_called()


if __name__ == '__main__':unittest.main()
