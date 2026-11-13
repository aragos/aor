"""UNIT optimizer steps on invented rows; no scientific actors, initialization or game execution."""
from dataclasses import replace
from pathlib import Path
import unittest
from unittest.mock import patch

import joint_trajectory as joint
import purchase_policy as policy
import triple_mc_control as control
import triple_mc_update as update
from triple_trajectory import TripleCollection
from test_triple_mc import TripleFixture


class ControlTests(TripleFixture):
    def frozen(self):return dict(path=str(self.root/'bundle.json'),sha256=joint.sha(self.root/'bundle.json'))

    def first(self):
        collection = self.collection();output = self.root/'first'
        result = control.update_control(collection,self.config,output,frozen=self.frozen())
        return collection,output,result

    def test_only_expansion_steps_and_frozen_original_cp_subject_is_published(self):
        collection,output,result = self.first()
        self.assertEqual({'expansion'},set(result['phases']))
        self.assertFalse((output/'card').exists());self.assertFalse((output/'purchase').exists())
        self.assertFalse((output/'bundle.json').exists())
        self.assertEqual(self.frozen(),joint.read(output/'triple-bundle.json')['jointBundle'])
        checkpoint = policy.read_checkpoint(output/'expansion/checkpoint.json')
        self.assertEqual(5,len(checkpoint['optimizer']['state']))
        self.assertNotEqual(collection.models['expansion']['actorWeights'],checkpoint['model']['actorWeights'])
        for key in ('valueWeights','valueBias'):
            self.assertEqual(collection.models['expansion'][key],checkpoint['model'][key])

    def test_only_control_immediate_carry_advances_adam_without_cp_update(self):
        _,previous,_ = self.first()
        before = policy.read_checkpoint(previous/'expansion/checkpoint.json')
        collection = self.collection(name='next',previous=previous,frozen_cp=True)
        output = self.root/'second'
        result = control.update_control(collection,replace(self.config,seed=99),output,
            frozen=self.frozen(),previous=previous)
        after = policy.read_checkpoint(output/'expansion/checkpoint.json')
        self.assertEqual({'expansion'},set(result['phases']))
        self.assertEqual(joint.sha(previous/'expansion/checkpoint.json'),
            joint.read(output/'expansion/identity.json')['optimizerFromSha256'])
        self.assertGreater(after['optimizer']['state']['0']['step']['tensor'],
            before['optimizer']['state']['0']['step']['tensor'])

    def test_joint_completed_transaction_cannot_be_control_carry(self):
        original = self.collection();previous = self.root/'joint'
        update.update_triple(original,self.configs,previous)
        following = self.collection(name='following',previous=previous,frozen_cp=True)
        with patch.object(policy,'update') as optimizer:
            with self.assertRaisesRegex(ValueError,'same-arm'):
                control.update_control(following,self.config,self.root/'refused',frozen=self.frozen(),previous=previous)
        optimizer.assert_not_called()

    def test_control_carry_cannot_enter_joint_publication(self):
        _,previous,_ = self.first()
        following = self.collection(name='following',previous=previous,frozen_cp=True)
        with patch.object(policy,'update') as optimizer:
            with self.assertRaisesRegex(ValueError,'three-head'):
                update.update_triple(following,self.configs,self.root/'refused',previous=previous)
        optimizer.assert_not_called()

    def test_nonidentical_fixed_cp_bundle_rejects_before_optimizer(self):
        collection = self.collection()
        with patch.object(policy,'update') as optimizer:
            with self.assertRaisesRegex(ValueError,'exact original'):
                control.update_control(collection,self.config,self.root/'refused',
                    frozen=dict(path=str(self.root/'bundle.json'),sha256='0'*64))
        optimizer.assert_not_called()

    def test_empty_untrained_card_phase_remains_empty_without_fake_update(self):
        collection = self.collection(empty_phase='card')
        result = control.update_control(collection,self.config,self.root/'output',frozen=self.frozen())
        self.assertEqual({'expansion'},set(result['phases']))
        self.assertEqual([],collection.rows['card'])

    def test_empty_active_expansion_rejects_before_optimizer(self):
        collection = self.collection(empty_phase='expansion')
        with patch.object(policy,'update') as optimizer:
            with self.assertRaises(ValueError):
                control.update_control(collection,self.config,self.root/'refused',frozen=self.frozen())
        optimizer.assert_not_called()

    def test_failure_after_phase_keeps_diagnostics_without_bundle(self):
        collection = self.collection();output = self.root/'partial';calls = 0
        def guard():
            nonlocal calls
            calls += 1
            if calls == 3:raise ValueError('UNIT deadline')
        with self.assertRaisesRegex(ValueError,'UNIT deadline'):
            control.update_control(collection,self.config,output,frozen=self.frozen(),guard=guard)
        self.assertTrue((output/'expansion/finished.json').is_file())
        self.assertFalse((output/'triple-bundle.json').exists());self.assertFalse((output/'finished.json').exists())


if __name__ == '__main__':unittest.main()
