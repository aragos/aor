"""Pure fixed-policy qualification contracts; no native games or numerical fitting."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as data
import purchase_gae_qualification as validation


class FixedPurchaseQualificationTest(unittest.TestCase):
    def protocol(self):
        return dict(baseline='/m8',opponents=['/m8']*4,prior='/f0',purchasePolicy='/c64',
            candidate='/gae',expansionModel='/em8',experimentalRulesTransfer=validation.legacy.RULES_TRANSFER,
            banks=dict(confirm=dict(nativeOffset=1),heldout=dict(nativeOffset=2)))

    def metrics(self,win=.05,reward=.05,count=4096):
        return {name:dict(count=count,mean=value,lower95=value-.01,upper95=value+.01)
                for name,value in (('winShareDifference',win),('rewardDifference',reward))}

    def test_confirmation_retains_exact_pilot_roles(self):
        protocol=self.protocol();source=validation.arena_source(protocol,'confirm')
        pilot=matched.arena_source(dict(protocol,banks={'screen':{'nativeOffset':1}}),'/gae')
        pilot.update(pairsPerConfiguration=4096,purpose='CONFIRMATION')
        self.assertEqual(pilot,source)

    def test_heldout_changes_only_world_bank_and_rival_nonpurchase_roles(self):
        protocol=self.protocol();source=validation.arena_source(protocol,'heldout')
        expected=validation.arena_source(protocol,'confirm')
        expected['seed']=2
        expected['expansionImitation']['opponentModels']=['/em8']*4
        expected['tokenOrder']['opponentSelectors']=[data.FIXED]*4
        self.assertEqual(expected,source)
        self.assertEqual('/c64',source['frozenPurchase']['policy'])
        self.assertEqual('/c64',source['frozenPurchase']['incumbentPolicy'])

    def test_homogeneous_rivals_cannot_change_collection_defaults(self):
        with self.assertRaisesRegex(ValueError,'only by an arena'):
            data.bindings('/e',rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS)

    def test_rival_ecology_requires_its_explicit_type(self):
        with self.assertRaisesRegex(ValueError,'Explicit homogeneous'):
            data.bindings('/e',arena=True,rival_ecology='homogeneous-qualified-1')

    def test_both_strict99_bounds_must_pass(self):
        self.assertTrue(validation.gate(self.metrics()))
        self.assertFalse(validation.gate(self.metrics(win=.01)))
        self.assertFalse(validation.gate(self.metrics(reward=.01)))

    def test_95_success_does_not_imply_99_success(self):
        metrics=self.metrics(win=.012)
        self.assertGreater(metrics['winShareDifference']['lower95'],0)
        self.assertFalse(validation.gate(metrics))

    def test_partial_or_topped_up_world_counts_cannot_qualify(self):
        self.assertFalse(validation.gate(self.metrics(count=4095)))
        self.assertFalse(validation.gate(self.metrics(count=4097)))

    def test_heldout_refuses_rejected_confirmation_before_native_work(self):
        with tempfile.TemporaryDirectory() as name:
            output=Path(name)
            values={output/'campaign-state.json':{'stages':{'confirm':{'status':'rejected'}}},
                output/'confirm-report.json':{'candidateSha256':validation.CANDIDATE,'metrics':self.metrics()}}
            with patch.object(validation,'read',side_effect=lambda path:values[path]), \
                 patch.object(validation.controller,'validate_result',return_value={'status':'rejected'}), \
                 patch.object(validation.exp,'native') as native:
                with self.assertRaisesRegex(ValueError,'Heldout requires'):
                    validation.stage(self.protocol(),output/'protocol',output,'heldout')
            native.assert_not_called()
            self.assertFalse((output/'heldout-source.json').exists())

    def test_replay_outside_completed_bank_cannot_launch(self):
        with patch.object(ecology,'run_child') as native:
            with self.assertRaisesRegex(ValueError,'four distinct historical'):
                ecology.arena_replays({},Path('/source'),[{}]*512,Path('/output'),indices=validation.REPLAYS)
        native.assert_not_called()

    def test_extra_stage_cannot_build_an_arena(self):
        with self.assertRaisesRegex(ValueError,'two predeclared'):
            validation.arena_source(self.protocol(),'top-up')


if __name__=='__main__':unittest.main()
