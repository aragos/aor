"""Continuation model/Adam lineage, fixed rivals and incremental-strength gates."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import card_trajectory_experiment as driver
from test_card_trajectory_experiment import fixture, put
from test_card_trajectory_analysis import records
from test_joint_economy import terminal


def original_checkpoint(root):
    model=driver.card.initialize(dict(encoderVersion=driver.card.ENCODER_VERSION,
        stateFeatureNames=['state'],actionFeatureNames=['action']),17)
    predecessor=root/'predecessor.json';put(predecessor,model)
    policy=driver.backend.ResidualPolicy(model,driver.card.CONTRACT)
    optimizer=driver.backend.torch.optim.Adam(policy.parameters(),lr=.0003,foreach=False)
    for parameter in policy.parameters():parameter.grad=driver.backend.torch.ones_like(parameter)
    optimizer.step()
    output=root/'update-32';output.mkdir()
    model_path=output/'policy.json';put(model_path,policy.artifact())
    identity=dict(schemaVersion=1,actorVersion=driver.card.ACTOR_VERSION,modelSha256=driver.sha(predecessor),
        data=dict(games=256,gameMeanReward=.3),config=dict(driver.analysis.PPO,seed=74632),
        optimizerFromSha256='previous-31',learnerSha256='frozen-original',entrypointSha256='frozen-original',
        torchVersion=driver.backend.torch.__version__,pythonVersion='frozen',deadlineEpochSeconds=1234.)
    put(output/'identity.json',identity)
    history=[dict(epoch=1,accepted=True,priorKl=.02)]
    payload=dict(schemaVersion=1,identitySha256=driver.backend.digest(identity),deadlineEpochSeconds=1234.,
        model=policy.artifact(),optimizer=driver.backend.encode_optimizer(optimizer),epoch=1,history=history,
        stopReason=None,elapsedSeconds=.1)
    driver.backend.save_checkpoint(output/'checkpoint.json',payload)
    put(output/'finished.json',dict(identitySha256=driver.backend.digest(identity),
        inputModelSha256=driver.sha(predecessor),outputModelSha256=driver.sha(model_path),games=256,decisions=10,
        gameMeanReward=.3,acceptedEpochs=1,history=history,stopReason='epochs-complete',elapsedSeconds=.1,strengthClaim=False))
    return model_path,output/'checkpoint.json',output/'identity.json',output/'finished.json'


class OriginalCarry(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.args=original_checkpoint(self.root)

    def test_real_adam_roundtrip_preserves_state_without_step(self):
        before=self.args[1].read_bytes();payload=driver.validate_continuation_checkpoint(*self.args)
        self.assertEqual(before,self.args[1].read_bytes())
        self.assertEqual(driver.read(self.args[0]),payload['model']);self.assertTrue(payload['optimizer']['state'])

    def test_changed_original_config_rejects(self):
        identity=driver.read(self.args[2]);identity['config']['learningRate']=.001;put(self.args[2],identity)
        with self.assertRaisesRegex(ValueError,'original vanilla32 PPO'):driver.validate_continuation_checkpoint(*self.args)

    def test_finite_but_wrong_adam_tensor_shape_rejects(self):
        payload=driver.backend.read_checkpoint(self.args[1])
        next(iter(payload['optimizer']['state'].values()))['exp_avg']['tensor']=[0.]
        driver.backend.save_checkpoint(self.args[1],payload)
        with self.assertRaisesRegex(ValueError,'Adam tensors'):driver.validate_continuation_checkpoint(*self.args)

    def test_checkpoint_model_cannot_be_substituted(self):
        payload=driver.backend.read_checkpoint(self.args[1]);payload['model']['valueBias']+=1.
        driver.backend.save_checkpoint(self.args[1],payload)
        with self.assertRaisesRegex(ValueError,'original vanilla32 PPO'):driver.validate_continuation_checkpoint(*self.args)

    def test_first_update_uses_original_checkpoint_and_model_not_control(self):
        window=object.__new__(driver.TrajectoryWindow);window.lab=self.root
        window.initial=self.root/'initial-control.json';put(window.initial,{'control':True})
        window.start_model,window.start_checkpoint=self.args[:2]
        window.coefficients=[0.]*32;window.scripts=Path(driver.__file__).parent
        output=self.root/'update-01';output.mkdir()
        for name in ('identity.json','checkpoint.json','policy.json','finished.json'):put(output/name,{'complete':True})
        with patch.object(driver,'completed_update',return_value={'complete':True}) as audit:
            window.update(1,'unused',dict(data={'games':256},decisions=10))
        expected=audit.call_args.args[1]
        self.assertEqual(driver.sha(self.args[0]),expected['modelSha256'])
        self.assertEqual(driver.sha(self.args[1]),expected['optimizerFromSha256'])
        self.assertEqual(0.,expected['config']['cardReferenceKlCoefficient'])
        self.assertEqual(self.args[0],audit.call_args.args[-1])


class ConditionalAdmission(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.carry=original_checkpoint(self.root)
        entry=lambda p:dict(file=str(p),sha256=driver.sha(p))
        evidence=self.root/'evidence.json'
        put(evidence,dict(sourcePins={str(p.resolve()):driver.sha(p) for p in self.carry[1:]}))
        protocol=self.root/'barrier-protocol.json';put(protocol,dict(trainingProfile=driver.PROFILE))
        preflight=self.root/'barrier-preflight.json';put(preflight,dict(windowSha256=driver.sha(protocol)))
        result=self.root/'barrier-result.json'
        outcome=dict(disposition='trajectory-no-probability-movement',browserActivated=False,
                     preflightSha256=driver.sha(preflight))
        put(result,outcome);status=self.root/'barrier-status.json';put(status,dict(outcome,stage='finished'))
        self.window=object.__new__(driver.TrajectoryWindow);self.window.vanilla=self.carry[0]
        self.window.pin=lambda row:Path(row['file'])
        self.window.protocol=dict(vanillaComparator=dict(batch=32,superiorityRequired=True,evidence=entry(evidence)),
            continuationStart={key:entry(path) for key,path in zip(('checkpoint','identity','completion'),self.carry[1:])},
            barrierDisposition={key:entry(path) for key,path in zip(('result','status','preflight','protocol'),
                                                                   (result,status,preflight,protocol))})

    def test_bound_nonqualified_barrier_activates_exact_start(self):
        with patch.object(driver,'VANILLA32_SHA',driver.sha(self.carry[0])):self.window.verify_continuation()
        self.assertEqual(self.carry[0],self.window.start_model)
        self.assertEqual(self.carry[1],self.window.start_checkpoint)

    def test_qualified_barrier_cannot_admit_a_continuation(self):
        path=self.root/'barrier-result.json';outcome=driver.read(path)
        outcome.update(disposition='qualified-for-integration',confirmationPassed=True);put(path,outcome)
        put(self.root/'barrier-status.json',dict(outcome,stage='finished'))
        with patch.object(driver,'VANILLA32_SHA',driver.sha(self.carry[0])),\
                self.assertRaisesRegex(ValueError,'nonqualified barrier'):self.window.verify_continuation()
        self.assertFalse(hasattr(self.window,'start_checkpoint'))

    def test_unbound_original_optimizer_evidence_rejects(self):
        put(self.root/'evidence.json',dict(sourcePins={}))
        with patch.object(driver,'VANILLA32_SHA',driver.sha(self.carry[0])),\
                self.assertRaisesRegex(ValueError,'audited vanilla32 lineage'):self.window.verify_continuation()


class RivalBatches(unittest.TestCase):
    def test_alternation_is_fixed_by_batch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);window=object.__new__(driver.TrajectoryWindow)
            window.lab=root;window.initial=root/'initial';window.start_model=root/'vanilla'
            window.vanilla=root/'vanilla';window.continuation=True
            window.base=dict(configuration='four',baseline='/F',frozenPurchasePolicy='/purchase32')
            window.offsets={'training-01':100,'training-02':101};window.banks={key:[1] for key in window.offsets}
            with patch.object(driver,'completed_training',return_value={'complete':True}) as complete:
                window.collect(1);window.collect(2)
            odd=driver.read(root/'training-01-source.json');even=driver.read(root/'training-02-source.json')
            self.assertNotIn('opponentCardPolicy',odd);self.assertEqual(str(window.vanilla),even['opponentCardPolicy'])
            self.assertIsNone(complete.call_args_list[0].args[-1])
            self.assertEqual(window.vanilla,complete.call_args_list[1].args[-1])
            self.assertEqual(['/F']*4,even['opponents']);self.assertEqual('/purchase32',even['frozenPurchasePolicy'])

    def test_optional_rival_snapshot_bound_and_default_rejects_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve();directory,source,seeds,model=fixture(root)
            spec=driver.read(source);spec['opponentCardPolicy']=str(model);put(source,spec)
            (directory/'source.json').write_bytes(source.read_bytes())
            manifest=driver.read(directory/'collection.json');manifest['sourceSha256']=driver.sha(source)
            manifest['opponentCardPolicy']=dict(path=str(model),sha256=driver.sha(model));put(directory/'collection.json',manifest)
            (directory/'opponent-card-policy.json').write_bytes(model.read_bytes())
            self.assertEqual(2,driver.completed_training(directory,source,seeds,model,model)['data']['games'])
            with self.assertRaisesRegex(ValueError,'silently activate'):driver.completed_training(directory,source,seeds,model)
            put(directory/'opponent-card-policy.json',{'changed':True})
            with self.assertRaisesRegex(ValueError,'snapshot changed'):driver.completed_training(directory,source,seeds,model,model)


class ContinuationProgress(unittest.TestCase):
    def test_inherited_kl_is_not_new_learning(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);start=root/'start';current=root/'current';start.write_text('same');current.write_text('same')
            prior=dict(completedUpdates=16,totalAcceptedEpochs=0,latestAcceptedPriorKl=.5,noProgress=True)
            result=driver.continuation_progress(prior,current,start)
            self.assertTrue(result['noProgress']);self.assertEqual(48,result['lifetimeUpdates'])
            self.assertFalse(result['originalPriorKlIsContinuationMovementEvidence'])

    def test_accepted_but_identical_actor_stops(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);start=root/'start';current=root/'current';start.write_text('same');current.write_text('same')
            prior=dict(completedUpdates=16,totalAcceptedEpochs=4,latestAcceptedPriorKl=.5,noProgress=False)
            self.assertTrue(driver.continuation_progress(prior,current,start)['noProgress'])
            current.write_text('new');self.assertFalse(driver.continuation_progress(prior,current,start)['noProgress'])

    def test_profile_requires_zero_reference_everywhere(self):
        profile=dict(trainingProfile=driver.CONTINUATION_PROFILE,cardReferenceVersion=driver.REFERENCE_VERSION,
                     cardReferenceCoefficients=[0.]*32)
        self.assertEqual([0.]*32,driver.reference_coefficients(profile))
        profile['cardReferenceCoefficients'][0]=.1
        with self.assertRaisesRegex(ValueError,'declared training profile'):driver.reference_coefficients(profile)


class CheckpointReporting(unittest.TestCase):
    def test_early_continuation_report_records_actual_lifetime_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);request=root/'request.json'
            put(request,dict(stage='development',seeds=[],controls='controls',controlsSource='controls.json',
                candidate='candidate',candidateSource='candidate.json',base={},initial='initial',model='model',
                priority='priority',output=str(root/'report.json'),
                checkpoint=dict(continuationUpdate=8,lifetimeUpdate=40)))
            with patch.object(driver.analysis,'analyze_development_completed',return_value=dict(eligible=True,harmful=False)):
                driver.analyze_request(request)
            self.assertEqual(dict(continuationUpdate=8,lifetimeUpdate=40),driver.read(root/'report.json')['checkpoint'])


class IncrementalGates(unittest.TestCase):
    def data(self,stage,vanilla_wins):
        seeds,rows=records(stage,lambda index,arm:arm=='candidate' and index<128)
        rows += [dict(seed=seed,controlledSeat=i%4,arm='vanilla',terminal=terminal(i%4,i<vanilla_wins))
                 for i,seed in enumerate(seeds)]
        return seeds,rows

    def test_positive_but_under_three_point_dev_gain_is_ineligible(self):
        report=driver.analysis.analyze('development',*self.data('development',122),True,True,.03)
        self.assertGreater(report['versus']['vanilla']['pooled']['winShare']['mean'],0);self.assertFalse(report['eligible'])

    def test_positive_lower_bound_does_not_replace_three_point_requirement(self):
        report=driver.analysis.analyze('confirmation',*self.data('confirmation',108),True,True,.03)
        self.assertTrue(report['gates']['lower95VsVanilla'])
        self.assertFalse(report['gates']['minimumGainVsVanilla']);self.assertFalse(report['passed'])

    def test_sufficient_increment_preserves_original_gates(self):
        report=driver.analysis.analyze('confirmation',*self.data('confirmation',80),True,True,.03)
        self.assertTrue(report['passed']);self.assertTrue(all(report['gates'].values()));self.assertFalse(report['policyPromotion'])


if __name__=='__main__':unittest.main()
