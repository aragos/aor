"""Synthetic complete-batch, optimizer-lineage, deadline and fixed-schedule checks; no games/fits."""
from copy import deepcopy
import gzip
import json
import math
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import card_trajectory_experiment as driver


def put(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);driver.document(path,value,immutable=False)


def fixture(root):
    model=driver.card.initialize({'encoderVersion':driver.card.ENCODER_VERSION,'stateFeatureNames':['state'],'actionFeatureNames':['action']},17)
    initial=root/'initial.json';initial.write_text(json.dumps(model))
    baseline=root/'base.json';purchase=root/'purchase.json';put(baseline,{});put(purchase,{'fixed':'purchase'})
    source=root/'source.json';seeds=[-999999990,-999991799]
    spec=dict(schemaVersion=1,configuration='fixed',games=2,seed=10,baseline=str(baseline),
              frozenPurchasePolicy=str(purchase),opponents=[str(baseline)]*4,policy=str(initial))
    put(source,spec);directory=root/'training';directory.mkdir()
    for name,path in [('source.json',source),('baseline.json',baseline),('frozen-purchase-policy.json',purchase),
                      ('policy.json',initial),*[(f'opponent-{i}.json',baseline) for i in range(4)]]:
        (directory/name).write_bytes(path.read_bytes())
    identity=lambda path:dict(path=str(path),sha256=driver.sha(path))
    manifest=dict(kind='card-collect',signature='fixed-signature',schemaVersion=1,trajectoryEncoding='gzip-json-v1',
        mode='on-policy',configuration='fixed',source=str(source),sourceSha256=driver.sha(source),games=2,seed=10,seeds=seeds,
        baseline=identity(baseline),frozenPurchasePolicy=identity(purchase),opponents=[identity(baseline)]*4,
        policy=str(initial),modelSha256=driver.sha(initial),actorVersion=driver.card.ACTOR_VERSION,
        encoderVersion=driver.card.ENCODER_VERSION,stateFeatureNames=model['stateFeatureNames'],actionFeatureNames=model['actionFeatureNames'])
    put(directory/'collection.json',manifest)
    put(directory/'state.json',dict(kind='card-collect',signature='fixed-signature',phase='complete',completedGames=2))
    scores=[dict(player=f'p{i}',advancePoints=0,cash=10,misery=0,total=10,winner=True) for i in range(1,5)]
    for index,seed in enumerate(seeds):
        row=dict(index=index,signature='fixed-signature',configuration='fixed',gameSeed=seed,controlledSeat=index,
            opponentIndices=[(index//4+seat)%4 for seat in range(4)],mode='on-policy',modelSha256=driver.sha(initial),
            encoderVersion=driver.card.ENCODER_VERSION,actorVersion=driver.card.ACTOR_VERSION,
            frozenPurchasePolicySha256=driver.sha(purchase),decisions=[{'test':'one card row'}],
            terminal=dict(reward=.3,winShare=.25,rankReward=.5,scores=scores,gameDecisions=1))
        (directory/f'game-{index}.json.gz').write_bytes(gzip.compress(json.dumps(row).encode()))
    finish(directory)
    return directory,source,seeds,initial


def finish(directory):
    put(directory/'finished.json',dict(kind='card-collect',signature='fixed-signature',games=2,
        gameSha256=[driver.sha(directory/f'game-{i}.json.gz') for i in range(2)]))


class CompleteTraining(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.args=fixture(self.root)

    def test_complete_gzip_batch_retains_exact_actor_purchase_and_summary(self):
        result=driver.completed_training(*self.args)
        self.assertEqual(2,result['decisions']);self.assertEqual(.3,result['data']['gameMeanReward'])
        self.assertEqual(driver.sha(self.root/'purchase.json'),result['data']['frozenPurchasePolicySha256'])

    def test_partial_batch_is_not_decoded(self):
        (self.args[0]/'finished.json').unlink()
        with patch.object(driver.backend,'read_game') as decode:
            self.assertIsNone(driver.completed_training(*self.args));decode.assert_not_called()

    def test_all_compressed_hashes_checked_before_first_decode(self):
        (self.args[0]/'game-1.json.gz').write_bytes(b'corrupt')
        with patch.object(driver.backend,'read_game') as decode:
            with self.assertRaisesRegex(ValueError,'compressed game'):driver.completed_training(*self.args)
            decode.assert_not_called()

    def test_mutated_frozen_purchase_snapshot_rejected(self):
        put(self.args[0]/'frozen-purchase-policy.json',{'different':True})
        with self.assertRaisesRegex(ValueError,'snapshot changed'):driver.completed_training(*self.args)

    def test_changed_source_even_same_semantic_json_rejected(self):
        self.args[1].write_text(self.args[1].read_text()+' ')
        with self.assertRaisesRegex(ValueError,'exact complete'):driver.completed_training(*self.args)

    def test_unbound_extra_game_rejected(self):
        (self.args[0]/'game-999.json.gz').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError,'compressed game'):driver.completed_training(*self.args)

    def test_public_score_arithmetic_rejects_incorrect_reward(self):
        path=self.args[0]/'game-0.json.gz';row=json.loads(gzip.decompress(path.read_bytes()))
        row['terminal']['reward']=.9;path.write_bytes(gzip.compress(json.dumps(row).encode()));finish(self.args[0])
        with self.assertRaisesRegex(ValueError,'labels differ'):driver.completed_training(*self.args)

    def test_wrong_controlled_seat_cannot_be_rehashed_into_collection(self):
        path=self.args[0]/'game-1.json.gz';row=json.loads(gzip.decompress(path.read_bytes()))
        row['controlledSeat']=0;path.write_bytes(gzip.compress(json.dumps(row).encode()));finish(self.args[0])
        with self.assertRaisesRegex(ValueError,'origin/policy'):driver.completed_training(*self.args)


class UpdateLineage(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.args=fixture(self.root)
        self.collected=driver.completed_training(*self.args);self.output=self.root/'update';self.output.mkdir()
        self.expected=dict(modelSha256=driver.sha(self.args[3]),optimizerFromSha256='fixed-adam',data=self.collected['data'])
        self.identity=dict(self.expected,deadlineEpochSeconds=time.time()+100)
        self.payload=dict(schemaVersion=1,identitySha256=driver.backend.digest(self.identity),deadlineEpochSeconds=self.identity['deadlineEpochSeconds'],
            model=driver.read(self.args[3]),optimizer={'state':{}},epoch=0,history=[],stopReason='behavior-kl-limit',elapsedSeconds=1.)
        self.save()

    def save(self):
        put(self.output/'identity.json',self.identity);put(self.output/'policy.json',self.payload['model'])
        driver.backend.save_checkpoint(self.output/'checkpoint.json',self.payload)
        put(self.output/'finished.json',dict(schemaVersion=1,identitySha256=driver.backend.digest(self.identity),
            inputModelSha256=self.expected['modelSha256'],outputModelSha256=driver.sha(self.output/'policy.json'),
            games=2,decisions=2,gameMeanReward=.3,acceptedEpochs=self.payload['epoch'],history=self.payload['history'],
            stopReason=self.payload['stopReason'] or 'epochs-complete',elapsedSeconds=1.,strengthClaim=False))

    def result(self):return driver.completed_update(self.output,self.expected,self.collected,self.args[3])

    def test_reserialized_zero_epoch_model_is_valid_unchanged_carry(self):
        self.assertNotEqual(driver.sha(self.args[3]),driver.sha(self.output/'policy.json'))
        self.assertEqual(0,self.result()['acceptedEpochs'])

    def test_zero_epoch_parameters_cannot_change_even_with_matching_checkpoint_hashes(self):
        self.payload['model']['valueBias']=.125;self.save()
        with self.assertRaisesRegex(ValueError,'identical preceding'):self.result()

    def test_changed_optimizer_source_is_rejected(self):
        expected=dict(self.expected,optimizerFromSha256='replacement')
        with self.assertRaisesRegex(ValueError,'carry identity'):
            driver.completed_update(self.output,expected,self.collected,self.args[3])

    def test_checksum_does_not_authorize_different_original_deadline(self):
        self.payload['deadlineEpochSeconds']+=1;self.save()
        with self.assertRaisesRegex(ValueError,'checkpoint/model/optimizer'):self.result()

    def test_partial_update_does_not_become_next_actor(self):
        (self.output/'finished.json').unlink();self.assertIsNone(self.result())


class ActualBackendIdentity(unittest.TestCase):
    def test_real_tiny_card_update_matches_driver_identity_and_resume_binding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve();directory,source,seeds,initial=fixture(root)
            purchase=driver.backend.initialize({'encoderVersion':driver.backend.ENCODER_VERSION,
                'stateFeatureNames':['p'],'actionFeatureNames':['a']},31)
            put(root/'purchase.json',purchase)
            (directory/'frozen-purchase-policy.json').write_bytes((root/'purchase.json').read_bytes())
            purchase_sha=driver.sha(root/'purchase.json');manifest=driver.read(directory/'collection.json')
            manifest['frozenPurchasePolicy']['sha256']=purchase_sha;put(directory/'collection.json',manifest)
            model=driver.read(initial)
            for index in range(2):
                path=directory/f'game-{index}.json.gz';row=json.loads(gzip.decompress(path.read_bytes()))
                observation=dict(encoderVersion=driver.card.ENCODER_VERSION,stateFeatures={'state':float(index)},
                    actions=[{'command':'pass','features':{'action':0.}},
                             {'command':'play card','features':{'action':1.}}],baselineIndex=0)
                prediction=driver.card.reference_forward(model,observation)
                selected=driver.backend.select(prediction['probabilities'],driver.backend.selection_uniform(index))
                row['frozenPurchasePolicySha256']=purchase_sha;row['purchaseAudit']=[]
                row['decisions']=[dict(number=0,actorSeed=index,decision='play-cards',turn=1,observation=observation,
                    selectedIndex=selected,behaviorLogProbability=math.log(prediction['probabilities'][selected]),
                    behaviorValue=prediction['value'])]
                path.write_bytes(gzip.compress(json.dumps(row).encode()))
            finish(directory)
            collected=driver.completed_training(directory,source,seeds,initial)
            window=object.__new__(driver.TrajectoryWindow)
            window.lab=root;window.initial=initial;window.scripts=Path(driver.__file__).parent
            window.deadline=time.time()+1000
            def execute(name,command,seconds,deadline):
                argument=lambda key:Path(command[command.index(key)+1])
                config=driver.backend.UpdateConfig(**driver.read(argument('--config')))
                driver.card.update(argument('--model'),argument('--source'),config,argument('--output'))
            with patch.object(window,'execute',side_effect=execute) as process:
                result=window.update(1,directory,collected)
                self.assertEqual(4,result['acceptedEpochs'])
                self.assertEqual(result,window.update(1,directory,collected))
                self.assertEqual(1,process.call_count)
            identity=driver.read(root/'update-01/identity.json')
            self.assertEqual(driver.sha(window.scripts/'card_policy.py'),identity['entrypointSha256'])
            self.assertEqual(driver.sha(window.scripts/'purchase_policy.py'),identity['learnerSha256'])
            self.assertEqual(purchase_sha,identity['data']['frozenPurchasePolicySha256'])
            self.assertIsNone(identity['optimizerFromSha256'])


class ReferenceSchedule(unittest.TestCase):
    def profile(self, values):
        return dict(trainingProfile=driver.PROFILE, cardReferenceVersion=driver.REFERENCE_VERSION,
                    cardReferenceCoefficients=values)

    def test_original_profile_has_explicit_backend_zero_defaults(self):
        self.assertIsNone(driver.reference_coefficients({}))
        config = driver.update_config(1)
        self.assertEqual(0., config['cardReferenceKlCoefficient'])
        self.assertEqual(driver.REFERENCE_VERSION, config['cardReferenceVersion'])
        self.assertEqual(driver.backend.asdict(driver.backend.UpdateConfig(**dict(driver.analysis.PPO, seed=74601))), config)

    def test_prospective_schedule_selects_exact_update_without_outcomes(self):
        values = [.1] * 16 + [0.] * 16
        self.assertEqual(values, driver.reference_coefficients(self.profile(values)))
        self.assertEqual(.1, driver.update_config(16, values)['cardReferenceKlCoefficient'])
        self.assertEqual(0., driver.update_config(17, values)['cardReferenceKlCoefficient'])
        self.assertEqual(74632, driver.update_config(32, values)['seed'])

    def test_unfrozen_malformed_schedule_rejects(self):
        for values in ([.1] * 31, [0.] * 32, [True] * 32, [float('nan')] * 32, [-.1] * 32):
            with self.subTest(values=values[:1]), self.assertRaisesRegex(ValueError, '32 finite'):
                driver.reference_coefficients(self.profile(values))
        with self.assertRaisesRegex(ValueError, 'no extra'):
            driver.reference_coefficients({'cardReferenceCoefficients': [.1] * 32})


class DiagnosticReporting(unittest.TestCase):
    def test_early_exploration_report_retains_diagnostics_without_eligibility_or_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / 'request.json'
            request = dict(stage='development', seeds=[], controls='controls', controlsSource='controls.json',
                candidate='candidate', candidateSource='candidate.json', base={}, initial='initial',
                model='model', priority='priority', output=str(root / 'report.json'), diagnosticOnlyCheckpoint=True)
            put(path, request)
            with patch.object(driver.analysis, 'analyze_development_completed',
                              return_value=dict(eligible=True, harmful=True)):
                driver.analyze_request(path)
            report = driver.read(root / 'report.json')
            self.assertFalse(report['eligible']); self.assertFalse(report['harmful'])
            self.assertTrue(report['diagnosticEligible']); self.assertTrue(report['diagnosticHarmDetected'])
            self.assertEqual('diagnostic-only exploration', report['checkpointUse'])


class FixedSchedule(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.window=object.__new__(driver.TrajectoryWindow)
        self.window.lab=self.root;self.window.deadline=time.time()+10800;self.window.preflight_hash='fixed'
        self.window.initial=self.root/'initial.json';put(self.window.initial,{'initial':True})
        for i in range(1,33):
            put(self.root/f'update-{i:02}/policy.json',{'checkpoint':i})
            put(self.root/f'development-{i:02}-report.json',{'complete':i})

    def lifecycle(self,harm=None,movement=True,eligible=True):
        def report(stage,name,output,model,controls=None,diagnostic_only=False):
            if stage=='confirmation':return {'passed':True}
            index=int(name[-2:]);return dict(harmful=index==harm,eligible=eligible(index) if callable(eligible) else eligible,
                versus={'f':{'pooled':{'winShare':{'mean':index/100}}}})
        return [patch.object(self.window,'collect',return_value=('collection',{})),
            patch.object(self.window,'update',return_value={}),
            patch.object(driver.analysis,'progress',return_value={'noProgress':not movement}),
            patch.object(self.window,'analyze',side_effect=report),
            patch.object(self.window,'evaluate_stage',side_effect=lambda stage,name,*args:self.root/name),
            patch.object(self.window,'status')]

    def run_fake(self,**kwargs):
        patches=self.lifecycle(**kwargs);mocks=[p.start() for p in patches]
        try:return self.window.run(),mocks
        finally:
            for p in reversed(patches):p.stop()

    def test_controls_cached_once_and_only_best_eligible_checkpoint_confirmed(self):
        result,mocks=self.run_fake()
        self.assertEqual(32,result['finalist']['batch']);self.assertFalse(result['browserActivated'])
        self.assertEqual(32,mocks[0].call_count)
        self.assertEqual(1,sum(call.args[0]=='controls' for call in mocks[4].call_args_list))
        self.assertEqual('confirmation',mocks[4].call_args_list[-1].args[0])

    def test_harm_stops_without_rescuing_an_earlier_checkpoint(self):
        result,mocks=self.run_fake(harm=16)
        self.assertEqual('trajectory-development-harm',result['disposition']);self.assertEqual(16,mocks[0].call_count)
        self.assertFalse(any(call.args[0]=='confirmation' for call in mocks[4].call_args_list))

    def test_no_probability_movement_stops_at16_without_new_evaluation(self):
        result,mocks=self.run_fake(movement=False)
        self.assertEqual('trajectory-no-probability-movement',result['disposition']);self.assertEqual(16,mocks[0].call_count)
        self.assertEqual(2,mocks[4].call_count)

    def test_no_eligible_checkpoint_does_not_open_final_bank(self):
        result,mocks=self.run_fake(eligible=False)
        self.assertEqual('trajectory-no-eligible-checkpoint',result['disposition'])
        self.assertFalse(any(call.args[0]=='confirmation' for call in mocks[4].call_args_list))

    def test_exploration_early_harm_is_diagnostic_but_late_harm_stops(self):
        self.window.selection_milestones = (24, 32)
        result, mocks = self.run_fake(harm=16)
        self.assertEqual(32, result['finalist']['batch'])
        development = [call for call in mocks[3].call_args_list if call.args[0] == 'development']
        self.assertEqual([True, True, False, False], [call.kwargs['diagnostic_only'] for call in development])

    def test_exploration_cannot_select_early_checkpoint_even_if_report_says_eligible(self):
        self.window.selection_milestones = (24, 32)
        result, mocks = self.run_fake(eligible=lambda index: index in (8, 16))
        self.assertEqual('trajectory-no-eligible-checkpoint', result['disposition'])
        self.assertFalse(any(call.args[0] == 'confirmation' for call in mocks[4].call_args_list))

    def test_exploration_harm_at24_stops_without_confirmation(self):
        self.window.selection_milestones = (24, 32)
        result, mocks = self.run_fake(harm=24)
        self.assertEqual('trajectory-development-harm', result['disposition'])
        self.assertEqual(24, mocks[0].call_count)

    def test_stage_deadline_is_not_renewed_on_resume(self):
        first=self.window.stage_clock('test',{'model':'fixed'},300,False)
        self.assertEqual(first,self.window.stage_clock('test',{'model':'fixed'},300,True))
        with self.assertRaisesRegex(ValueError,'original stage deadline'):
            self.window.stage_clock('test',{'model':'changed'},300,True)

    def test_missing_stage_clock_after_progress_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'cannot be renewed'):
            self.window.stage_clock('test',{},300,True)

    def test_training_source_keeps_F_rivals_and_frozen_purchase(self):
        base=dict(schemaVersion=1,configuration='fixed',baseline='/F',frozenPurchasePolicy='/purchase32')
        spec=driver.training_source(base,20261701,Path('/actor'))
        self.assertEqual(['/F']*4,spec['opponents']);self.assertEqual('/purchase32',spec['frozenPurchasePolicy'])
        self.assertNotIn('opponentCardPolicy',spec);self.assertEqual(256,spec['games'])


if __name__=='__main__':unittest.main()
