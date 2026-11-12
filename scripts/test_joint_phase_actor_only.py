"""UNIT synthetic optimizer steps only: typed joint phase/clock/critic and Adam boundaries."""
import copy
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import joint_credit_targets as credit
import joint_phase_objective as objective
import joint_trajectory as joint
import purchase_policy as policy
import test_joint_trajectory as fixtures
from test_joint_credit_targets import untrained_actor


class JointActorUnitTests(unittest.TestCase):
    def setUp(self):
        policy.torch.set_num_threads(1)
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.descriptor = self.root/'predictor.json'
        policy.atomic_json(self.descriptor, dict(kind=credit.DESCRIPTOR, checkpointSha256='a'*64,
            stateDictionarySha256='b'*64, inferenceContractSha256='c'*64))
        self.config = policy.UpdateConfig(epochs=1, valueCoefficient=0, maxBehaviorKl=2, maxSeconds=60)

    def batch(self, name='batch', *, phase=objective.Phase.CARD, heads=None,
              clock=credit.Clock.CHRONOLOGICAL, descriptor=None):
        root = self.root/name
        root.mkdir()

        def actor(directory, key):
            if heads is None or key not in heads:
                return untrained_actor(directory, key)
            model = heads[key]
            path = directory/(key+'-model.json')
            policy.atomic_json(path, model)
            return path, directory/'unused-checkpoint.json', policy.ResidualPolicy(model, joint.CONTRACTS[key])

        with patch.object(fixtures, 'trained_actor', side_effect=actor):
            directory, bundle, _ = fixtures.fixture(root)
        collection = joint.JointCollection(directory, bundle)
        descriptor = descriptor or self.descriptor
        values = root/'values.json'
        policy.atomic_json(values, dict(kind=credit.PREDICTIONS, newFits=0,
            collectionSha256=collection.common['collectionSha256'], gameHashes=collection.common['gameHashes'],
            behaviorModels={key: collection.bundle[key]['sha256'] for key in joint.PHASES},
            predictorDescriptor=dict(path=str(descriptor), sha256=policy.file_digest(descriptor)),
            predictions=[dict(item[2], value=.25)
                for item in credit.chronology(collection.rows, collection.common)]))
        credit.write_targets(collection, values, root/'targets')
        target = credit.DetachedJointTargets(collection, values, root/'targets'/(clock.value+'.json'), clock)
        return objective.JointPhaseActorOnlyObjective(target, phase)

    def update(self, obj, name, *, carry=None, config=None, continuation=None):
        collection, phase = obj.targets.collection, obj.phase.value
        return policy.update(collection.bundle[phase]['path'], collection.directory, config or self.config,
            self.root/name, contract=joint.CONTRACTS[phase], entrypoint=objective.__file__,
            collection_reader=obj.targets.phase_reader, joint_actor_only=obj,
            optimizer_from=carry, joint_optimizer_continuation=continuation)

    def test_card_step_changes_actor_and_preserves_value_parameters(self):
        obj = self.batch()
        original = obj.targets.collection.models['card']
        self.update(obj, 'card-update')
        actual = policy.read_json(self.root/'card-update/policy.json')
        self.assertNotEqual(actual['actorWeights'], original['actorWeights'])
        self.assertEqual(actual['valueWeights'], original['valueWeights'])
        self.assertEqual(actual['valueBias'], original['valueBias'])

    def test_purchase_phase_has_its_own_explicit_joint_objective(self):
        obj = self.batch(phase=objective.Phase.PURCHASE)
        result = self.update(obj, 'purchase-update')
        self.assertEqual(result['jointActorOnlyObjective']['phase'], 'purchase')
        self.assertNotIn('actorOnlyObjective', result)

    def test_adam_contains_only_five_actor_parameter_slots(self):
        self.update(self.batch(), 'update')
        optimizer = policy.read_checkpoint(self.root/'update/checkpoint.json')['optimizer']
        self.assertEqual(optimizer['param_groups'][0]['params'], list(range(5)))
        self.assertEqual(set(optimizer['state']), {str(index) for index in range(5)})

    def test_binding_preserves_observations_likelihoods_values_and_terminal_labels(self):
        obj = self.batch()
        collection = obj.targets.collection
        rows, provenance = obj.targets.phase_reader(collection.directory, collection.bundle['card']['path'],
            collection.models['card'], contract=joint.CONTRACTS['card'])
        bound, identity = obj.bind(rows, provenance, collection.bundle['card']['sha256'])
        self.assertIs(bound, rows)
        for row, original in zip(rows, collection.rows['card']):
            self.assertEqual({key:value for key,value in row.items() if key!='_actorOnlyAdvantage'}, original)
        self.assertEqual(identity['behaviorModels'], obj.targets.identity['behaviorModels'])
        self.assertEqual(identity['sharedPredictor']['subject']['checkpointSha256'], 'a'*64)

    def test_prepared_input_substitution_is_rejected(self):
        obj = self.batch()
        collection = obj.targets.collection
        rows, provenance = obj.targets.phase_reader(collection.directory, collection.bundle['card']['path'],
            collection.models['card'], contract=joint.CONTRACTS['card'])
        changed = [dict(rows[0], _prepared=copy.deepcopy(rows[0]['_prepared']))]
        with self.assertRaisesRegex(ValueError, 'cannot change original actor'):
            obj.bind(changed, provenance, collection.bundle['card']['sha256'])

    def test_wrong_phase_contract_is_rejected_before_output(self):
        obj = self.batch()
        collection = obj.targets.collection
        with self.assertRaisesRegex(ValueError, 'declared CARD or PURCHASE'):
            policy.update(collection.bundle['card']['path'], collection.directory, self.config,
                self.root/'invalid', contract=joint.CONTRACTS['purchase'], entrypoint=objective.__file__,
                collection_reader=obj.targets.phase_reader, joint_actor_only=obj)
        self.assertFalse((self.root/'invalid').exists())

    def test_old_purchase_objective_stays_forbidden_on_card(self):
        obj = self.batch()
        collection = obj.targets.collection
        with self.assertRaisesRegex(ValueError, 'fresh purchase Adam'):
            policy.update(collection.bundle['card']['path'], collection.directory, self.config,
                self.root/'invalid', contract=joint.CONTRACTS['card'], actor_only=policy.ActorOnlyObjective('recorded'))

    def test_joint_objective_cannot_use_an_unbound_reader(self):
        obj = self.batch()
        collection = obj.targets.collection
        with self.assertRaisesRegex(ValueError, 'exact detached phase reader'):
            policy.update(collection.bundle['card']['path'], collection.directory, self.config,
                self.root/'invalid', contract=joint.CONTRACTS['card'], entrypoint=objective.__file__,
                collection_reader=collection.phase_reader, joint_actor_only=obj)

    def test_joint_objective_cannot_add_critic_or_entropy_loss(self):
        obj = self.batch()
        with self.assertRaisesRegex(ValueError, 'zero critic, entropy'):
            self.update(obj, 'invalid', config=replace(self.config, entropyCoefficient=.1))

    def test_epoch_kl_rejection_restores_actor_and_adam(self):
        obj = self.batch()
        original = obj.targets.collection.models['card']
        with patch.object(policy, 'behavior_metrics', return_value=dict(behaviorKl=3., priorKl=0., valueMse=0.)):
            report = self.update(obj, 'rejected')
        actual = policy.read_checkpoint(self.root/'rejected/checkpoint.json')
        self.assertEqual(report['acceptedEpochs'], 0)
        self.assertEqual(actual['model'], original)
        self.assertEqual(actual['optimizer']['state'], {})

    def carried(self, phase=objective.Phase.CARD):
        first = self.batch('first', phase=phase)
        self.update(first, 'first-update')
        model = policy.read_json(self.root/'first-update/policy.json')
        second = self.batch('second', phase=phase, heads={phase.value:model})
        return first, second, self.root/'first-update/checkpoint.json'

    def test_second_batch_carries_matching_phase_actor_moments(self):
        _, second, checkpoint = self.carried()
        self.update(second, 'second-update', carry=checkpoint,
            continuation=objective.JointPhaseAdamContinuation(), config=replace(self.config, seed=23))
        before = policy.read_checkpoint(checkpoint)['optimizer']
        after = policy.read_checkpoint(self.root/'second-update/checkpoint.json')['optimizer']
        self.assertTrue(all(after['state'][key]['step']['tensor'] > before['state'][key]['step']['tensor']
                            for key in before['state']))

    def test_purchase_second_batch_carries_its_matching_actor_moments(self):
        _, second, checkpoint = self.carried(objective.Phase.PURCHASE)
        self.update(second, 'second-update', carry=checkpoint,
            continuation=objective.JointPhaseAdamContinuation())
        before = policy.read_checkpoint(checkpoint)['optimizer']
        after = policy.read_checkpoint(self.root/'second-update/checkpoint.json')['optimizer']
        self.assertTrue(all(after['state'][key]['step']['tensor'] > before['state'][key]['step']['tensor']
                            for key in before['state']))

    def test_carried_epoch_rejection_restores_actor_and_all_five_adam_moments(self):
        _, second, checkpoint = self.carried()
        before = policy.read_checkpoint(checkpoint)
        with patch.object(policy, 'behavior_metrics', return_value=dict(behaviorKl=3., priorKl=0., valueMse=0.)):
            self.update(second, 'rejected', carry=checkpoint,
                continuation=objective.JointPhaseAdamContinuation())
        after = policy.read_checkpoint(self.root/'rejected/checkpoint.json')
        self.assertEqual(after['model'], before['model'])
        self.assertEqual(after['optimizer'], before['optimizer'])

    def test_joint_epoch_cannot_silently_override_legacy_actor_flag(self):
        obj = self.batch()
        with self.assertRaisesRegex(ValueError, 'cannot also select'):
            policy.fit_epoch(None, None, [], self.config, 0, 0,
                             actor_only=True, joint_actor_only=obj)

    def test_old_purchase_adam_cannot_be_used_for_joint_carry(self):
        _, second, checkpoint = self.carried()
        with self.assertRaisesRegex(ValueError, 'separate typed Adam'):
            self.update(second, 'invalid', carry=checkpoint, continuation=policy.ActorAdamContinuation())

    def identity(self, obj):
        collection, phase = obj.targets.collection, obj.phase.value
        rows, provenance = obj.targets.phase_reader(collection.directory, collection.bundle[phase]['path'],
            collection.models[phase], contract=joint.CONTRACTS[phase])
        return obj.bind(rows, provenance, collection.bundle[phase]['sha256'])[1]

    def test_carry_cannot_cross_clocks(self):
        _, second, checkpoint = self.carried()
        identity = self.identity(second)
        identity['estimator'] = credit.estimator(credit.Clock.PHASE_SEPARATED)
        with self.assertRaisesRegex(ValueError, 'cannot cross phase, clock'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, identity,
                second.targets.collection.models['card'], self.config)

    def test_carry_cannot_cross_phase_even_with_same_actor_shapes(self):
        _, second, checkpoint = self.carried()
        identity = self.identity(second)
        identity['phase'] = 'purchase'
        with self.assertRaisesRegex(ValueError, 'cannot cross phase, clock'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, identity,
                second.targets.collection.models['card'], self.config)

    def test_carry_cannot_change_shared_predictor_descriptor(self):
        _, second, checkpoint = self.carried()
        identity = self.identity(second)
        identity['sharedPredictor']['subject']['checkpointSha256'] = '9'*64
        with self.assertRaisesRegex(ValueError, 'real predictor descriptor'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, identity,
                second.targets.collection.models['card'], self.config)

    def test_carry_rejects_missing_predictor_instead_of_none_equality(self):
        _, second, checkpoint = self.carried()
        identity = self.identity(second)
        identity['sharedPredictor'] = None
        with self.assertRaisesRegex(ValueError, 'real predictor descriptor'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, identity,
                second.targets.collection.models['card'], self.config)

    def test_carry_rejects_changed_ppo_recipe(self):
        _, second, checkpoint = self.carried()
        with self.assertRaisesRegex(ValueError, 'PPO recipe'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, self.identity(second),
                second.targets.collection.models['card'], replace(self.config, clip=.1))

    def test_carry_rejects_value_head_slot_substitution(self):
        _, second, checkpoint = self.carried()
        payload = policy.read_checkpoint(checkpoint)
        payload['optimizer']['param_groups'][0]['params'] = [0, 1, 2, 3, 5]
        policy.save_checkpoint(checkpoint, payload)
        with self.assertRaisesRegex(ValueError, 'five actor parameters'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, self.identity(second),
                second.targets.collection.models['card'], self.config)

    def test_carry_rejects_wrong_actor_moment_shape(self):
        _, second, checkpoint = self.carried()
        payload = policy.read_checkpoint(checkpoint)
        payload['optimizer']['state']['0']['exp_avg']['tensor'] = [0.]
        policy.save_checkpoint(checkpoint, payload)
        with self.assertRaisesRegex(ValueError, 'finite parameter shape'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, self.identity(second),
                second.targets.collection.models['card'], self.config)

    def test_carry_rejects_mismatched_completed_history(self):
        _, second, checkpoint = self.carried()
        path = checkpoint.parent/'finished.json'
        report = policy.read_json(path)
        report['acceptedEpochs'] += 1
        policy.atomic_json(path, report)
        with self.assertRaisesRegex(ValueError, 'completed phase actor'):
            objective.JointPhaseAdamContinuation().validate(checkpoint, self.identity(second),
                second.targets.collection.models['card'], self.config)

    def test_joint_loss_is_exact_legacy_purchase_precomputed_math(self):
        obj = self.batch(phase=objective.Phase.PURCHASE)
        collection = obj.targets.collection
        rows, _ = obj.targets.phase_reader(collection.directory, collection.bundle['purchase']['path'],
            collection.models['purchase'], contract=joint.CONTRACTS['purchase'])
        actor = policy.ResidualPolicy(collection.models['purchase'], joint.CONTRACTS['purchase'])
        old = policy.ordinary_loss_for_rows(actor, rows, self.config, actor_only=True, precomputed_advantages=True)
        new = policy.ordinary_loss_for_rows(actor, rows, self.config, joint_actor_only=obj, precomputed_advantages=True)
        self.assertEqual(float(old.detach()), float(new.detach()))


if __name__ == '__main__':
    unittest.main()
