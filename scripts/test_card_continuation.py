"""UNIT-only calibration/interaction checks; no retained tensors, vectors or worlds."""
import math
import json
from pathlib import Path
import tempfile
import unittest
from statistics import NormalDist

import torch
import purchase_policy as policy
from purchase_policy_toy import SCHEMA, observation
import card_continuation_calibrate as admission
import card_continuation_calibration as calibration
import card_continuation_interaction as study


def row(world=0, number=1):
    return calibration.PublicRow(world, 'purchase', number, 'a'*64, observation())


def old_forward(actor, observations):
    # Exact pre-extraction operation order, retained only as a regression oracle.
    prepared = [actor.prepare(o) for o in observations]
    state, actions, owners = actor._batch_inputs(prepared)
    hidden = torch.tanh(state @ actor.stateWeights.T + actor.stateBias)
    action_hidden = torch.tanh(hidden[owners] + actions @ actor.actionWeights.T + actor.actionBias)
    residuals = torch.clamp(action_hidden @ actor.actorWeights, -20, 20)
    result = []; cursor = 0
    for item in prepared:
        residual = residuals[cursor:cursor+item.action_count]
        prior = torch.tensor(policy.prior_probabilities(item.action_count,item.baseline_index),dtype=policy.DTYPE).log()
        logp = torch.log_softmax(prior+residual,dim=0)
        if bool(torch.all(residual == residual[0])): logp += (prior-logp).detach()
        result.append(logp); cursor += item.action_count
    return result, torch.sigmoid(hidden @ actor.valueWeights + actor.valueBias)


class CalibrationTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.model = policy.initialize(SCHEMA,17)

    def test_shared_owner_is_bit_identical_to_previous_forward(self):
        self.model['actorWeights'] = [math.sin(i) for i in range(64)]
        actor = policy.PurchasePolicy(self.model)
        inputs = [observation(),observation('after-left',1)]
        old, old_value = old_forward(actor,inputs)
        new,new_value = actor(inputs)
        self.assertTrue(all(torch.equal(a,b) for a,b in zip(old,new)))
        self.assertTrue(torch.equal(old_value,new_value))

    def test_cached_basis_preserves_full_forward(self):
        basis = calibration.FrozenBasis(self.model,'purchase',[row()])
        fresh,_ = policy.PurchasePolicy(self.model)([observation()])
        self.assertTrue(torch.equal(basis.reference[0],fresh[0]))

    def test_direction_is_deterministic_unit_norm_and_phase_specific(self):
        first = calibration.direction(17,'card')
        self.assertEqual(first,calibration.direction(17,'card'))
        self.assertEqual(sum(x*x for x in first),1)
        self.assertNotEqual(first,calibration.direction(17,'purchase'))

    def test_calibration_reaches_declared_kl_without_other_parameter_changes(self):
        basis = calibration.FrozenBasis(self.model,'purchase',[row()])
        model, proof = calibration.calibrated_head(basis,calibration.direction(17,'purchase'))
        self.assertLessEqual(abs(proof['actualKl']-.02),1e-6)
        self.assertEqual({k:v for k,v in model.items() if k!='actorWeights'},
                         {k:v for k,v in self.model.items() if k!='actorWeights'})
        self.assertEqual(proof['actualFits'],0)

    def test_world_weights_do_not_reward_a_world_with_more_decisions(self):
        basis = calibration.FrozenBasis(self.model,'purchase',[row(0),row(1),row(1,2)])
        self.assertEqual(basis.weights.tolist(),[.5,.25,.25])

    def test_unreachable_direction_stops_instead_of_redrawing(self):
        basis = calibration.FrozenBasis(self.model,'purchase',[row()])
        with self.assertRaisesRegex(ValueError,'not bracketed'):
            calibration.calibrated_head(basis,[0.0]*64)

    def test_detached_labels_cannot_enter_calibration_rows(self):
        raw = dict(world=0,phase='purchase',number=1,gameSha256='a'*64,observation=observation(),reward=1)
        with self.assertRaisesRegex(ValueError,'only original own/public'):
            calibration.PublicRow.read(raw,self.model)

    def public_fixture(self, directory):
        path = Path(directory)/'rows.jsonl'
        raw = dict(world=0,phase='purchase',number=1,gameSha256='a'*64,observation=observation())
        path.write_text(json.dumps(raw)+'\n')
        dictionary = {k:self.model[k] for k in ('encoderVersion','stateFeatureNames','actionFeatureNames')}
        dictionary['actorVersion'] = policy.PURCHASE_CONTRACT.actor
        receipt = dict(kind='card-calibration-public-1',passed=True,
            cohort='53/chronological/batch-32/worlds-0-63',maximumRowsPerHeadWorld=32,
            terminalLabelsDecoded=False,behaviorChoicesDecoded=False,behaviorValuesDecoded=False,
            newGames=0,policyEvaluations=0,actualFits=0,rowsSha256=admission.sha(path),
            originalGameSha256=['a'*64]+[f'{i:064x}' for i in range(1,64)],
            phaseDictionaries={'purchase':dictionary},rows={'card':0,'purchase':1})
        return path,receipt

    def test_projected_public_rows_bind_original_world_and_dictionary(self):
        with tempfile.TemporaryDirectory() as directory:
            path,receipt=self.public_fixture(directory)
            rows=admission.public_rows(receipt,path,{'purchase':self.model})
            self.assertEqual(rows[0]['gameSha256'],'a'*64)

    def test_reassigned_world_hash_rejected_before_calibration(self):
        with tempfile.TemporaryDirectory() as directory:
            path,receipt=self.public_fixture(directory)
            receipt['originalGameSha256'][0]='b'*64
            with self.assertRaisesRegex(ValueError,'world hash'):
                admission.public_rows(receipt,path,{'purchase':self.model})

    def test_receipt_cannot_admit_semantic_behavior_data(self):
        with tempfile.TemporaryDirectory() as directory:
            path,receipt=self.public_fixture(directory)
            receipt['behaviorChoicesDecoded']=True
            with self.assertRaisesRegex(ValueError,'outcome-free'):
                admission.public_rows(receipt,path,{'purchase':self.model})

    def test_forced_rows_cannot_dilute_mean_kl(self):
        obs=observation();obs['actions']=obs['actions'][:1]
        raw=dict(world=0,phase='purchase',number=1,gameSha256='a'*64,observation=obs)
        with self.assertRaisesRegex(ValueError,'Forced'):
            calibration.PublicRow.read(raw,self.model)


def outcome(reward):
    return {m:(reward if m!='chaos' else 1-reward) for m in study.METRICS}


def origin(index, shifts=None, same=False):
    shifts=shifts or {}
    return dict(index=index,status='COLLECTED',sameAction=same,continuations={
        style:dict(incumbent=[outcome(.5)]*2,acquire=[outcome(.5+shifts.get(style,0))]*2)
        for style in study.STYLES})


class InteractionTest(unittest.TestCase):
    def native(self):
        scores=[dict(player='p'+str(i+1),advancePoints=0,cash=10 if i==0 else 0,
                     misery=0,total=10 if i==0 else 0,winner=i==0) for i in range(4)]
        outcomes=[dict(replicate=i,replayVerified=True,terminal=dict(reward=1.,winShare=1.,rankReward=1.,
                    scores=scores,decisions=17,continuationSeed=-100-i)) for i in range(2)]
        return dict(request=dict(index=0,seat=0,epoch=1,ordinal=0),status='COLLECTED',
            exactIncumbentVerified=True,incumbentCommand='buy-expansion-card',
            actorInput=dict(encoded=dict(actions=[dict(command='buy-expansion-card')])),
            streams=dict(future=[-100,-101]),continuations=[dict(style=style,incumbentIndex=0,acquireIndex=0,
                arms=[dict(command='buy-expansion-card',outcomes=outcomes)]) for style in study.STYLES])

    def test_native_aliases_retain_both_logical_arms_and_authoritative_metrics(self):
        value=study.from_native(self.native())
        self.assertTrue(value['sameAction'])
        self.assertEqual(value['continuations']['G3']['incumbent'],value['continuations']['G3']['acquire'])
        self.assertEqual(value['continuations']['G3']['incumbent'][0],
                         dict(reward=1.,winShare=1.,rankReward=1.,chaos=0.))

    def test_native_pair_stream_cannot_be_reassigned(self):
        value=self.native();value['streams']['future'][0]=-77
        with self.assertRaisesRegex(ValueError,'paired stream'):
            study.from_native(value)

    def test_four_family_intervals_detect_dependence_despite_zero_antithetic_mean(self):
        rows=[origin(i,dict(D1_PLUS=.1,D1_MINUS=-.1)) for i in range(1024)]
        report=study.summarize(rows)
        self.assertTrue(report['dependenceDetected'])
        self.assertEqual(report['meanFourInteraction']['mean'],0)
        self.assertAlmostEqual(report['familyZ'],NormalDist().inv_cdf(1-.05/8))
        self.assertIsNone(report['selectedVariant'])

    def test_identical_logical_arms_stay_in_primary_denominator(self):
        report=study.summarize([origin(i,same=True) for i in range(1024)])
        self.assertEqual(report['eligible'],1024)
        self.assertEqual(report['sameAction'],1024)
        self.assertFalse(report['dependenceDetected'])

    def test_missing_world_is_not_a_zero_effect(self):
        rows=[origin(i) for i in range(1024)]
        rows[0]=dict(index=0,status='MISSING',reason='CARD_UNAVAILABLE',continuations={})
        report=study.summarize(rows)
        self.assertEqual(report['eligible'],1023)
        self.assertEqual(report['primary']['D1_PLUS']['count'],1023)

    def test_one_future_or_partial_bank_is_rejected(self):
        rows=[origin(i) for i in range(1024)]
        rows[0]['continuations']['G3']['acquire'].pop()
        with self.assertRaisesRegex(ValueError,'both original future'): study.summarize(rows)
        with self.assertRaisesRegex(ValueError,'complete original bank'): study.summarize(rows[:-1])

    def test_false_alias_is_rejected(self):
        rows=[origin(i) for i in range(1024)]
        rows[0]=origin(0,{'D1_PLUS':.1},same=True)
        with self.assertRaisesRegex(ValueError,'Identical arms'): study.summarize(rows)

    def test_second_future_is_paired_before_world_aggregation(self):
        rows=[origin(i) for i in range(1024)]
        rows[0]['continuations']['D1_PLUS']['acquire']=[outcome(.9),outcome(.1)]
        self.assertEqual(study.summarize(rows)['primary']['D1_PLUS']['mean'],0)


if __name__ == '__main__': unittest.main()
