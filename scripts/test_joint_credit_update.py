"""UNIT-only paired updates on invented records; no simulation or scientific actor training."""
from dataclasses import replace
from pathlib import Path
import unittest
from unittest.mock import patch

import joint_credit_targets as credit
import joint_credit_update as pair
import joint_trajectory as joint
import purchase_policy as policy
import test_joint_phase_actor_only as actor_fixture
import test_joint_current_ecology as current_fixture


class PairUnitTests(unittest.TestCase):
    def setUp(self):
        self.fixture = actor_fixture.JointActorUnitTests(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.configs = dict.fromkeys(joint.PHASES,self.fixture.config)

    def targets(self, name='batch', **kwargs):
        return self.fixture.batch(name, **kwargs).targets

    def test_both_original_heads_publish_one_valid_bundle_after_completion(self):
        targets = self.targets()
        report = pair.update_pair(targets,self.configs,self.root/'pair')
        bundle = joint.read(self.root/'pair/bundle.json')
        self.assertEqual(report['outputBundleSha256'],joint.sha(self.root/'pair/bundle.json'))
        for phase in joint.PHASES:
            self.assertEqual(bundle[phase]['sha256'],report['phases'][phase]['policySha256'])
            self.assertEqual(bundle[phase]['sha256'],joint.sha(bundle[phase]['path']))

    def test_both_phases_keep_same_original_prediction_and_behavior_bundle(self):
        targets = self.targets()
        pair.update_pair(targets,self.configs,self.root/'pair')
        identities = [joint.read(self.root/'pair'/phase/'identity.json') for phase in joint.PHASES]
        for identity in identities:
            actual = identity['jointActorOnlyObjective']
            self.assertEqual(actual['behaviorModels'],targets.identity['behaviorModels'])
            self.assertEqual(actual['frozenStatePredictions'],targets.identity['frozenStatePredictions'])
        self.assertEqual(identities[0]['data']['gameHashes'],identities[1]['data']['gameHashes'])

    def test_second_phase_failure_leaves_first_evidence_without_publishing_bundle(self):
        targets = self.targets(); original = policy.update
        def update(*args, **kwargs):
            if kwargs['contract'] == joint.CONTRACTS['purchase']:
                raise ValueError('Synthetic second-phase failure')
            return original(*args, **kwargs)
        with patch.object(policy,'update',side_effect=update),self.assertRaisesRegex(ValueError,'second-phase'):
            pair.update_pair(targets,self.configs,self.root/'pair')
        self.assertTrue((self.root/'pair/card/finished.json').exists())
        self.assertFalse((self.root/'pair/bundle.json').exists())
        self.assertFalse((self.root/'pair/finished.json').exists())

    def test_global_guard_failure_before_publication_keeps_both_phase_receipts(self):
        targets = self.targets(); calls=[]
        def guard():
            calls.append(True)
            if len(calls)==4: raise ValueError('Synthetic global deadline')
        with self.assertRaisesRegex(ValueError,'global deadline'):
            pair.update_pair(targets,self.configs,self.root/'pair',guard=guard)
        self.assertTrue((self.root/'pair/purchase/finished.json').exists())
        self.assertFalse((self.root/'pair/bundle.json').exists())

    def test_changed_frozen_predictor_after_first_phase_prevents_second_update(self):
        targets = self.targets(); calls=[]
        def guard():
            calls.append(True)
            if len(calls)==3: self.fixture.descriptor.write_text('{}')
        with self.assertRaisesRegex(ValueError,'changed'):
            pair.update_pair(targets,self.configs,self.root/'pair',guard=guard)
        self.assertTrue((self.root/'pair/card/finished.json').exists())
        self.assertFalse((self.root/'pair/purchase').exists())
        self.assertFalse((self.root/'pair/bundle.json').exists())

    def test_entirely_empty_phase_is_rejected_without_invented_update(self):
        targets=self.targets();targets.collection.rows['purchase']=[]
        with patch.object(policy,'update') as update,self.assertRaisesRegex(ValueError,'entirely empty phase'):
            pair.update_pair(targets,self.configs,self.root/'pair')
        update.assert_not_called()
        self.assertFalse((self.root/'pair').exists())

    def test_pair_cannot_reuse_a_completed_output(self):
        targets = self.targets(); pair.update_pair(targets,self.configs,self.root/'pair')
        before = joint.sha(self.root/'pair/bundle.json')
        with self.assertRaisesRegex(ValueError,'new one-attempt'):
            pair.update_pair(targets,self.configs,self.root/'pair')
        self.assertEqual(before,joint.sha(self.root/'pair/bundle.json'))

    def carried(self):
        pair.update_pair(self.targets('first'),self.configs,self.root/'first-pair')
        heads={phase:joint.read(self.root/'first-pair'/phase/'policy.json') for phase in joint.PHASES}
        targets=self.targets('second',heads=heads)
        carries={phase:self.root/'first-pair'/phase/'checkpoint.json' for phase in joint.PHASES}
        return targets,carries

    def test_both_matching_phase_adams_continue_into_next_pair(self):
        targets,carries=self.carried()
        configs={phase:replace(config,seed=24) for phase,config in self.configs.items()}
        pair.update_pair(targets,configs,self.root/'second-pair',checkpoints=carries)
        for phase in joint.PHASES:
            identity=joint.read(self.root/'second-pair'/phase/'identity.json')
            self.assertEqual(identity['jointActorOptimizerContinuation']['checkpointSha256'],joint.sha(carries[phase]))
            self.assertEqual(identity['jointActorOnlyObjective']['optimizerInitialization'],'matching-joint-phase-adam')

    def test_invalid_second_carry_is_rejected_before_either_optimizer_step(self):
        targets,carries=self.carried();carries['purchase']=carries['card']
        with patch.object(policy,'update') as update,self.assertRaises(ValueError):
            pair.update_pair(targets,self.configs,self.root/'invalid',checkpoints=carries)
        update.assert_not_called()
        self.assertFalse((self.root/'invalid').exists())

    def test_mixed_fresh_and_carried_heads_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'fresh Adam or carry both'):
            pair.update_pair(self.targets(),self.configs,self.root/'invalid',
                checkpoints=dict(card=None,purchase=Path('/missing')))
        self.assertFalse((self.root/'invalid').exists())

    def test_changed_clock_cannot_reuse_pair_adam(self):
        targets,carries=self.carried()
        changed=credit.DetachedJointTargets(targets.collection,
            targets.identity['frozenStatePredictions']['path'],
            self.root/'second/targets/phase-separated.json',credit.Clock.PHASE_SEPARATED)
        with self.assertRaisesRegex(ValueError,'cannot cross phase, clock'):
            pair.update_pair(changed,self.configs,self.root/'invalid',checkpoints=carries)
        self.assertFalse((self.root/'invalid').exists())

    def test_kl_rejected_phases_publish_the_unchanged_complete_pair(self):
        targets=self.targets()
        with patch.object(policy,'behavior_metrics',return_value=dict(behaviorKl=3.,priorKl=0.,valueMse=0.)):
            report=pair.update_pair(targets,self.configs,self.root/'pair')
        self.assertEqual([report['phases'][phase]['acceptedEpochs'] for phase in joint.PHASES],[0,0])
        for phase in joint.PHASES:
            self.assertEqual(joint.read(self.root/'pair'/phase/'policy.json'),targets.collection.models[phase])

    def test_current_ecology_reader_connects_to_atomic_pair_without_legacy_relaxation(self):
        fixture=current_fixture.CurrentReader();fixture.setUp();self.addCleanup(fixture.doCleanups)
        collection=fixture.load();values=fixture.root/'values.json'
        policy.atomic_json(values,dict(kind=credit.PREDICTIONS,newFits=0,
            collectionSha256=collection.common['collectionSha256'],gameHashes=collection.common['gameHashes'],
            behaviorModels={phase:collection.bundle[phase]['sha256'] for phase in joint.PHASES},
            predictorDescriptor=dict(path=str(self.fixture.descriptor),sha256=joint.sha(self.fixture.descriptor)),
            predictions=[dict(item[2],value=.25) for item in credit.chronology(collection.rows,collection.common)]))
        credit.write_targets(collection,values,fixture.root/'targets')
        targets=credit.DetachedJointTargets(collection,values,fixture.root/'targets/chronological.json',
            credit.Clock.CHRONOLOGICAL)
        pair.update_pair(targets,self.configs,fixture.root/'pair')
        identity=joint.read(fixture.root/'pair/identity.json')
        self.assertEqual(identity['collection']['jointEcology'],collection.common['jointEcology'])


if __name__=='__main__':unittest.main()
