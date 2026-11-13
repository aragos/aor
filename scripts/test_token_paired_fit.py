"""Constructed models/worlds only; no retained actor, observations or outcomes are opened."""
import copy
from dataclasses import FrozenInstanceError, replace
import hashlib
import itertools
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import purchase_policy as b
import token_paired_policy as policy
import token_paired_data as data
import token_paired_fit as fit
import token_paired_validation as validation
import token_paired_identity as identity_owner


def model(nonzero=False):
    schema = dict(encoderVersion=policy.CONTRACT.encoder,
        stateFeatureNames=sorted([*policy.ORDER_NAMES, 'role.0.cash', 'decision.token']),
        actionFeatureNames=sorted([*policy.AMOUNT_NAMES, 'amount']))
    result = b.initialize(schema, 113, contract=policy.CONTRACT)
    if nonzero:
        result['actorWeights'] = [.03*(index%3-1) for index in range(64)]
    return result


def frozen(value):
    raw = b.canonical(value)
    return data.FrozenActor(raw, hashlib.sha256(raw).hexdigest())


def view(count=3):
    return dict(encoderVersion=policy.CONTRACT.encoder,
        stateFeatures={'role.0.cash': .2}, baselineIndex=0,
        actions=[dict(command=f'buy-tokens {index:02}', features={'amount': index/36})
            for index in range(count)])


def context(original, current=None, iteration=1):
    current = current or original
    dictionary = b.canonical({key: original.model[key] for key in
        ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')})
    result = data.Context(iteration, str(iteration)*64, 'a'*64, str(iteration+2)*64,
        hashlib.sha256(dictionary).hexdigest(), original.dictionary_sha256,
        original.sha256, current.sha256)
    return result, dictionary


def root_record(index=0, status='READY', current=None, count=3):
    missing = status == 'MISSING'; count = 1 if status == 'SINGLETON' else count
    observation = None if missing else view(count)
    prior = None if missing else b.prior_probabilities(count, 0)
    probabilities = prior if current is None or missing else b.reference_forward(
        current.model, observation, contract=policy.CONTRACT)['probabilities']
    return dict(index=index, split='TRAIN' if index < 512 else 'VALIDATION', status=status,
        requestSha256=hashlib.sha256(f'UNIT-{index}'.encode()).hexdigest(),
        decisionNumber=None if missing else 8, batchStartNumber=None if missing else 7,
        observation=observation, currentProbabilities=probabilities, originalProbabilities=prior,
        sampledIndex=None if missing else 0,
        proposedIndices=None if missing else [0, *range(1, min(8, count-1)+1)],
        inclusionProbability=None if missing else (min(8, count-1)/(count-1) if count>1 else 0.),
        futureSeeds=[-5000-index*2, -5001-index*2])


def label(root):
    row = root.record
    return dict(publicSha256=root.sha256, futureSeeds=row['futureSeeds'], actions=[
        dict(actionIndex=index, rewards=[.2, .4] if ordinal == 0 else [.4, .6])
        for ordinal, index in enumerate(row['proposedIndices'])])


def corpus(original, current=None, iteration=1, ready=256):
    ctx, dictionary = context(original, current, iteration)
    current = current or original
    roots = []; labels = []; anchors = []
    for index in range(512):
        record = root_record(index, 'READY' if index < ready else 'MISSING', current)
        root = data.PublicRoot.from_record(record, ctx, current.model, split='TRAIN')
        roots.append(root)
        labels.append(data.PairedTarget.from_record(root, label(root)) if root.ready else None)
        anchor = dict(index=index, split='TRAIN', observations=[])
        if index < 384:
            anchor['observations'] = [dict(decisionNumber=8, batchStartNumber=7,
                observation=view(), originalProbabilities=b.prior_probabilities(3, 0))]
        anchors.append(data.AnchorWorld.from_record(anchor, original.model))
    return data.Training(ctx, tuple(roots), tuple(labels), tuple(anchors), 128), dictionary


class PolicyTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)

    def test_schema3_is_separate_from_old_token_and_expansion(self):
        value = model(); policy.validate_model(value)
        for contract in (b.TOKEN_CONTRACT, b.EXPANSION_CONTRACT):
            with self.assertRaisesRegex(ValueError, 'schemaVersion'):
                b.validate_model(value, contract=contract)

    def test_exact_prior_includes_singleton_and_full75(self):
        actor = policy.TokenPairedPolicy(model())
        for count in (1, 2, 75):
            output = b.reference_forward(model(), view(count), contract=policy.CONTRACT)
            self.assertEqual(output['probabilities'], b.prior_probabilities(count, 0))
            logs, _ = actor([view(count)])
            self.assertLess(max(abs(a-c) for a, c in
                zip(logs[0].exp().tolist(), output['probabilities'])), 2e-15)

    def test_warm_transfer_copies_only_shared_nondecision_columns(self):
        target = model(); source_schema = dict(encoderVersion=b.ENCODER_VERSION,
            stateFeatureNames=['decision.token', 'role.0.cash'], actionFeatureNames=['amount'])
        source = b.initialize(source_schema, 17)
        source['stateBias'] = [.2]*64
        schema = {key: target[key] for key in ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')}
        initialized, proof = policy.initialize(schema, source, 111)
        expected = b.initialize(schema, 111, contract=policy.CONTRACT)
        cash = target['stateFeatureNames'].index('role.0.cash')
        decision = target['stateFeatureNames'].index('decision.token')
        width = len(target['stateFeatureNames'])
        for unit in range(64):
            self.assertEqual(initialized['stateWeights'][unit*width+cash], source['stateWeights'][unit*2+1])
            self.assertEqual(initialized['stateWeights'][unit*width+decision], expected['stateWeights'][unit*width+decision])
        self.assertEqual(initialized['stateBias'], [.2]*64)
        self.assertEqual(proof['sharedStateFeatures'], ['role.0.cash'])
        self.assertEqual(initialized['actionWeights'], expected['actionWeights'])
        self.assertEqual(initialized['actorWeights'], [0.]*64)

    def test_full_actor_gradient_and_frozen_value_scope(self):
        actor = policy.TokenPairedPolicy(model(True))
        logs, _ = actor([view()]); (-logs[0][1]).backward()
        for name in b.PARAMETERS[:-2]:
            self.assertGreater(float(getattr(actor, name).grad.abs().sum()), 0.)
        self.assertIsNone(actor.valueWeights.grad); self.assertIsNone(actor.valueBias.grad)


class ObjectiveTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)

    def test_exhaustive_uniform_subsets_equal_full_expectation_and_gradient(self):
        def calculate(indices, inclusion):
            logits = b.torch.tensor([.5, -.3, .7, .1], dtype=b.DTYPE, requires_grad=True)
            probabilities = logits.softmax(0)
            deltas = [0., .3, -.2, .7]
            value = fit.gain(probabilities, [.25]*4, indices,
                [deltas[index] for index in indices], inclusion)
            value.backward()
            return float(value.detach()), logits.grad
        full, gradient = calculate([0, 1, 2, 3], 1.)
        subsets = [calculate([0, *subset], 2/3) for subset in itertools.combinations((1,2,3), 2)]
        self.assertAlmostEqual(sum(value for value, _ in subsets)/3, full, places=15)
        self.assertTrue(b.torch.allclose(sum(grad for _, grad in subsets)/3, gradient, atol=1e-16, rtol=0))

    def test_no_additional_sampled_action_mean_or_renormalization(self):
        probabilities = b.torch.tensor([.6, .2, .1, .1], dtype=b.DTYPE)
        actual = fit.gain(probabilities, [.7, .1, .1, .1], [0,1,2], [0.,.3,.4], 2/3)
        self.assertAlmostEqual(float(actual), .045, places=15)

    def test_reference_delta_must_be_zero(self):
        with self.assertRaisesRegex(ValueError, 'Reference delta'):
            fit.gain(b.torch.tensor([.5,.5]), [.5,.5], [0,1], [.1,.2], 1.)

    def test_kl_orientation_is_current_to_original(self):
        probabilities = b.torch.tensor([.6,.3,.1], dtype=b.DTYPE)
        actual = fit.original_kl(probabilities.log(), [.97,.015,.015])
        expected = sum(p*__import__('math').log(p/q) for p,q in zip([.6,.3,.1],[.97,.015,.015]))
        self.assertAlmostEqual(float(actual), expected, places=15)

    def test_unsampled_offer_still_has_original_kl_gradient(self):
        logits = b.torch.tensor([.1,.2,.3], dtype=b.DTYPE, requires_grad=True)
        fit.original_kl(logits.log_softmax(0), [.97,.015,.015]).backward()
        self.assertNotEqual(float(logits.grad[2]), 0.)

    def test_singleton_kl_is_exact_zero_with_zero_gradient(self):
        log = b.torch.tensor([0.], dtype=b.DTYPE, requires_grad=True)
        value = fit.original_kl(log, [1.]); value.backward()
        self.assertEqual(float(value.detach()), 0.); self.assertEqual(float(log.grad[0]), 0.)


class DataTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.original = frozen(model()); self.ctx, self.dictionary = context(self.original)

    def public(self, record=None):
        return data.PublicRoot.from_record(record or root_record(), self.ctx, self.original.model, split='TRAIN')

    def test_reject_validation_before_touching_observation_or_labels(self):
        class Explosive(dict):
            def __getitem__(self, key):
                raise AssertionError('Contents decoded')
        with self.assertRaisesRegex(ValueError, 'split'):
            self.public(Explosive(split='VALIDATION'))

    def test_hidden_or_outcome_fields_are_rejected(self):
        for where in ('root', 'observation', 'action'):
            record = root_record()
            target = record if where == 'root' else record['observation'] if where == 'observation' \
                else record['observation']['actions'][0]
            target['futureOutcome'] = .8
            with self.assertRaisesRegex(ValueError, 'exact public field'):
                self.public(record)

    def test_missing_root_has_no_fake_state_or_label(self):
        root = self.public(root_record(status='MISSING'))
        self.assertFalse(root.ready)
        with self.assertRaisesRegex(ValueError, 'Missing/singleton'):
            data.PairedTarget.from_record(root, {})

    def test_singleton_has_no_fake_pair_target(self):
        root = self.public(root_record(status='SINGLETON'))
        self.assertFalse(root.ready)
        self.assertEqual(root.record['currentProbabilities'], [1.])

    def test_input_mutation_cannot_change_public_or_target(self):
        record = root_record(); root = self.public(record); source = label(root)
        target = data.PairedTarget.from_record(root, source)
        record['observation']['stateFeatures']['role.0.cash'] = 8.
        source['actions'][1]['rewards'][0] = 1.
        self.assertEqual(root.record['observation']['stateFeatures']['role.0.cash'], .2)
        self.assertAlmostEqual(target.deltas[1], .2)
        with self.assertRaises(FrozenInstanceError):
            root.wire = b'bad'

    def test_paired_stream_and_public_subject_are_intrinsic(self):
        root = self.public(); source = label(root); source['futureSeeds'].reverse()
        with self.assertRaisesRegex(ValueError, 'shared streams'):
            data.PairedTarget.from_record(root, source)

    def test_all_original_siblings_required(self):
        root = self.public(); source = label(root); source['actions'].pop()
        with self.assertRaisesRegex(ValueError, 'Every sampled sibling'):
            data.PairedTarget.from_record(root, source)

    def test_exact_uniform_inclusion_probability_required(self):
        record = root_record(count=10); record['inclusionProbability'] = 1.
        with self.assertRaisesRegex(ValueError, 'inclusion probability'):
            self.public(record)

    def test_raw_and_semantic_dictionary_subjects_are_distinct_and_checked(self):
        formatted = json.dumps(json.loads(self.dictionary), indent=2).encode()
        ctx = replace(self.ctx, dictionary_sha256=hashlib.sha256(formatted).hexdigest())
        ctx.bind(self.original, self.original, formatted)
        with self.assertRaisesRegex(ValueError, 'Raw native dictionary'):
            ctx.bind(self.original, self.original, self.dictionary)

    def test_complete_training_worlds_are_required(self):
        training, _ = corpus(self.original)
        with self.assertRaisesRegex(ValueError, 'all512'):
            replace(training, roots=training.roots[:-1])

    def test_mutable_training_population_is_rejected(self):
        training, _ = corpus(self.original)
        with self.assertRaisesRegex(ValueError, 'typed and immutable'):
            replace(training, roots=list(training.roots))

    def test_public_dictionary_cannot_be_substituted(self):
        changed = model(); changed['stateFeatureNames'][0] = 'changed-column'
        with self.assertRaisesRegex(ValueError, 'complete model dictionary'):
            data.PublicRoot.from_record(root_record(), self.ctx, changed, split='TRAIN')

    def test_train_streams_cannot_be_reused_across_worlds(self):
        training, _ = corpus(self.original)
        record = training.roots[-1].record; record['futureSeeds'] = training.roots[-2].record['futureSeeds']
        changed = self.public(record)
        with self.assertRaisesRegex(ValueError,'independent original identity'):
            replace(training, roots=(*training.roots[:-1],changed))

    def test_anchor_minibatch_weights_reproduce_global_union(self):
        training, _ = corpus(self.original,iteration=2)
        prepared = fit.Prepared(training,policy.TokenPairedPolicy(self.original.model))
        batch_mass = [sum(weight for _,_,weight in prepared.anchor_rows(range(start,start+64)))*
            512/(64*prepared.anchor_count) for start in range(0,512,64)]
        self.assertAlmostEqual(sum(batch_mass)/8,1.)

    def test_original_anchor_deduplicates_first_root_by_exact_identity(self):
        training, _ = corpus(self.original)
        prepared = fit.Prepared(training, policy.TokenPairedPolicy(self.original.model))
        self.assertEqual(prepared.anchor_count, 384)
        self.assertEqual(len(list(prepared.anchor_rows(range(512)))), 384)

    def test_iteration2_union_is_equal_world_not_fixed_half_mixture(self):
        training, _ = corpus(self.original, iteration=2)
        prepared = fit.Prepared(training, policy.TokenPairedPolicy(self.original.model))
        self.assertEqual(prepared.anchor_count, 384+256)
        self.assertEqual(sum(len(groups) for groups in prepared.groups[:256]), 512)
        mass = sum(weight for _, _, weight in prepared.anchor_rows(range(512)))
        self.assertEqual(mass, 640.)

    def test_duplicate_original_anchor_identity_is_rejected(self):
        row = dict(decisionNumber=8,batchStartNumber=7,observation=view(),
            originalProbabilities=b.prior_probabilities(3,0))
        with self.assertRaisesRegex(ValueError, 'unique'):
            data.AnchorWorld.from_record(dict(index=0,split='TRAIN',observations=[row,row]), model())

    def test_anchor_rejects_validation_before_decoding(self):
        with self.assertRaisesRegex(ValueError, 'non-TRAIN'):
            data.AnchorWorld.from_record({'split':'VALIDATION'}, model())


class FitTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.original = frozen(model()); self.training, self.dictionary = corpus(self.original)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)/'fit'
        self.config = fit.Config(tuple(range(1000,1020)), time.time()+120)

    def test_epoch_rollback_restores_actor_and_all_adam_state(self):
        actor = policy.TokenPairedPolicy(model()); prepared = fit.Prepared(self.training, actor)
        optimizer = b.torch.optim.Adam([getattr(actor,name) for name in b.PARAMETERS[:-2]], lr=.0003)
        before = copy.deepcopy(actor.state_dict()); old_optimizer = copy.deepcopy(optimizer.state_dict())
        with patch.object(fit, 'anchor_metrics', return_value=dict(mean=.021,maximum=.03)):
            result = fit.epoch(actor,optimizer,prepared,1000,self.config)
        self.assertFalse(result['accepted'])
        for key,value in before.items(): self.assertTrue(b.torch.equal(value,actor.state_dict()[key]))
        self.assertEqual(optimizer.state_dict(),old_optimizer)

    def test_exposure_rejects_before_output_or_optimizer(self):
        training, _ = corpus(self.original, ready=255)
        with self.assertRaisesRegex(ValueError, 'at least256'):
            fit.fit(training,self.original,self.original,self.dictionary,self.output,self.config)
        self.assertFalse(self.output.exists())

    def test_iteration2_cannot_silently_reset_original_anchor_or_skip_predecessor(self):
        training, _ = corpus(self.original, iteration=2)
        with self.assertRaisesRegex(ValueError, 'sole completed'):
            fit.admit_previous(training,self.original,self.original,self.dictionary,None)

    def test_fixed_config_has_no_epoch_learning_rate_or_threshold_override(self):
        with self.assertRaises(TypeError):
            fit.Config(tuple(range(20)),time.time()+10,epochs=1)
        with self.assertRaisesRegex(ValueError,'Exactly20'):
            fit.Config((1,),time.time()+10)

    def test_expired_original_clock_rejects_before_output(self):
        with self.assertRaisesRegex(ValueError,'original numerical deadline'):
            fit.fit(self.training,self.original,self.original,self.dictionary,self.output,
                fit.Config(tuple(range(1000,1020)),time.time()-1))
        self.assertFalse(self.output.exists())

    def test_synthetic_fixed_fit_publishes_value_frozen_last_accepted_model(self):
        result = fit.fit(self.training,self.original,self.original,self.dictionary,self.output,self.config)
        self.assertTrue(result['complete']); self.assertGreater(result['acceptedEpochs'],0)
        self.assertTrue(result['actorChanged'])
        output = b.read_json(self.output/'policy.json')
        self.assertEqual(output['valueWeights'],self.original.model['valueWeights'])
        self.assertEqual(output['valueBias'],self.original.model['valueBias'])
        history = b.read_json(self.output/'history.json')
        self.assertEqual(result['attemptedEpochs'],len(history)-1)
        self.assertEqual(result,fit.completed(self.output))
        checkpoint=b.read_json(self.output/'checkpoint.json')
        self.assertEqual(set(checkpoint['optimizer']['state']),set(map(str,range(5))))
        with self.assertRaisesRegex(ValueError,'once-only'):
            fit.fit(self.training,self.original,self.original,self.dictionary,self.output,self.config)

    def test_epoch_zero_bound_is_diagnostic_only(self):
        with patch.object(fit,'anchor_metrics',return_value=dict(mean=.05,maximum=.2)), \
                patch.object(fit,'epoch',return_value=dict(accepted=True,anchor=dict(mean=0.,maximum=0.),
                    attemptedAdamSteps=0)):
            result=fit.fit(self.training,self.original,self.original,self.dictionary,self.output,self.config)
        self.assertEqual(result['acceptedEpochs'],20)
        self.assertFalse(result['operationalPassed'])

    def test_numeric_spelling_does_not_claim_actor_parameter_movement(self):
        value=model();value['stateBias'][0]=0;value['actorWeights'][0]=-.0
        original=frozen(value);training,dictionary=corpus(original)
        with patch.object(fit,'anchor_metrics',return_value=dict(mean=0.,maximum=0.)), \
                patch.object(fit,'epoch',return_value=dict(accepted=True,anchor=dict(mean=0.,maximum=0.),
                    attemptedAdamSteps=0)):
            result=fit.fit(training,original,original,dictionary,self.output,self.config)
        output=b.read_json(self.output/'policy.json')
        self.assertNotEqual(b.digest(output),result['identity']['inputModelCanonicalSha256'])
        self.assertFalse(result['actorChanged']);self.assertFalse(result['operationalPassed'])
        self.assertEqual(result,fit.completed(self.output))


class IdentityTests(unittest.TestCase):
    def test_equal_json_number_spellings_have_one_actor_identity(self):
        left={name:[0,1,-.0] for name in identity_owner.ACTOR_PARAMETERS}
        right={name:[.0,1.,.0] for name in identity_owner.ACTOR_PARAMETERS}
        self.assertEqual(identity_owner.actor_parameters_sha256(left),identity_owner.actor_parameters_sha256(right))

    def test_real_parameter_change_changes_identity(self):
        left={name:[.0]*64 for name in identity_owner.ACTOR_PARAMETERS};right=copy.deepcopy(left)
        right['stateWeights'][0]=1e-12
        self.assertNotEqual(identity_owner.actor_parameters_sha256(left),identity_owner.actor_parameters_sha256(right))

    def test_nonfinite_parameter_identity_rejected(self):
        value={name:[.0]*64 for name in identity_owner.ACTOR_PARAMETERS};value['actorWeights'][0]=float('nan')
        with self.assertRaisesRegex(ValueError,'finite'):identity_owner.actor_parameters_sha256(value)


if __name__ == '__main__':
    unittest.main()
