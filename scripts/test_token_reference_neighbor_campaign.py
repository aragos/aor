"""Role ownership and gates for the bounded inherited-data experiment."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import automata_token_reference_neighbors as campaign
import adaptive_token_arena as arena
import token_order_reference_neighbors as codec
from test_token_order_augmentation import fixture
import token_order_augmentation as data


class NeighborCampaignTest(unittest.TestCase):
    def setUp(self):
        self.protocol=dict(kind=codec.CAMPAIGN_KIND,baseline='/M8.json',expansionModel='/E-M8.json')

    def test_heldout_varies_actual_rival_indices_with_expansion_on_all(self):
        binding=arena.bindings(self.protocol,'heldout','/candidate.json')
        self.assertEqual(['/M8.json']*4,binding['opponents'])
        self.assertEqual(['/E-M8.json']*4,binding['expansionImitation']['opponentModels'])
        self.assertEqual(['FIXED_16_TO_13','FIXED_16_TO_14','FIXED_16_TO_15','ORDINARY'],
                         binding['tokenOrder']['opponentSelectors'])
        self.assertEqual('FIXED_16_TO_15',binding['tokenOrder']['incumbentSelector'])
        self.assertEqual(codec.SELECTOR,binding['tokenOrder']['candidateSelector'])

    def test_screen_keeps_mixed_ecology_and_exact_current_incumbent(self):
        binding=arena.bindings(self.protocol,'screen','/candidate.json')
        self.assertEqual([None,'/E-M8.json',None,'/E-M8.json'],binding['expansionImitation']['opponentModels'])
        self.assertEqual(['ORDINARY','ORDINARY','FIXED_16_TO_15','FIXED_16_TO_15'],
                         binding['tokenOrder']['opponentSelectors'])
        self.assertEqual('/E-M8.json',binding['expansionImitation']['incumbentModel'])

    def test_profile_cannot_silently_recollect_parent(self):
        with self.assertRaisesRegex(ValueError,'cannot recollect'):
            arena.bindings(self.protocol,'collect')

    def test_no_automatic_screen_control(self):
        with self.assertRaisesRegex(ValueError,'screen controls'):
            arena.bindings(self.protocol,'screen',control=True)

    def test_legacy_residual_heldout_pool_remains_exact(self):
        protocol=dict(self.protocol,kind=data.fixed.CAMPAIGN_KIND)
        binding=arena.bindings(protocol,'heldout','/candidate.json')
        self.assertEqual(['/M8.json'],binding['opponents'])
        self.assertEqual(['FIXED_16_TO_15'],binding['tokenOrder']['opponentSelectors'])
        self.assertEqual('MODEL_FIXED_T_RESIDUAL',binding['tokenOrder']['candidateSelector'])

    def test_screen_requires_positive_intervals_and_mean_point_zero_three(self):
        metrics=dict(winShareDifference=dict(count=512,mean=.029,lower95=.001),
                     rewardDifference=dict(count=512,mean=.05,lower95=.001))
        self.assertFalse(campaign.previous.screen_passes(metrics))
        metrics['winShareDifference']['mean']=.03
        self.assertTrue(campaign.previous.screen_passes(metrics))
        metrics['rewardDifference']['lower95']=0.
        self.assertFalse(campaign.previous.screen_passes(metrics))

    def test_final_requires_both_strict_ninety_nine_lower_bounds(self):
        estimates=dict(winShareDifference=dict(count=2048,lowerConfidence=0.),
                       rewardDifference=dict(count=2048,lowerConfidence=.02))
        with mock.patch.object(campaign.evolution,'qualification_estimates',return_value=estimates):
            self.assertFalse(campaign.previous.final_passes({}, {}, 'confirm')[0])
        estimates['winShareDifference']['lowerConfidence']=.001
        with mock.patch.object(campaign.evolution,'qualification_estimates',return_value=estimates):
            self.assertTrue(campaign.previous.final_passes({}, {}, 'confirm')[0])

    def test_remaining_stage_dependencies_include_augmentation_first(self):
        self.assertEqual(('augment','fit','screen','confirm','heldout'),campaign.STAGES)
        self.assertEqual((21600,600,1800,3600,7200),campaign.CEILINGS)


if __name__=='__main__':
    unittest.main()
