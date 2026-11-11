"""Prospective eight-tail roles, gates and finite controller checks; no native games."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_token_eight_tail as target
import token_eight_tail_campaign as campaign
from test_adaptive_token import metrics


def protocol():
    return dict(baseline='/m8-ordinary.json',expansionModel='/m8-expansion.json',
        frozenPurchase={'baseline':'/f0.json','policy':'/c64.json'},
        banks={name:dict(nativeOffset=35000424+i,count=count,
            seeds=target.exp.arena_seeds(35000424+i,count)) for i,(name,count) in enumerate(target.COUNTS.items())},
        experimentalRulesTransfer=target.previous.legacy.TRANSFER,
        python={'executable':'/usr/bin/python3.14'})


class SourceTest(unittest.TestCase):
    def test_collection_keeps_ordinary_controlled_token_reference(self):
        source=target.collection_source(protocol())
        self.assertEqual({'opponentSelectors':['ORDINARY','ORDINARY','FIXED_16_TO_15','FIXED_16_TO_15']},source['tokenOrder'])
        self.assertEqual(['/m8-ordinary.json']*4,source['opponents'])
        self.assertEqual('/m8-expansion.json',source['expansionImitation']['candidateModel'])

    def test_collection_uses_only_explicit_eight_stream_protocol(self):
        source=target.collection_source(protocol())
        self.assertEqual((512,128,8),(source['trainingRoots'],source['validationRoots'],source['continuationReplicates']))
        self.assertEqual('ARENA_OVERLAYS_EIGHT_TAILS_1',source['continuationProtocol'])

    def test_predeclared_reproductions_cover_each_epoch_in_both_banks(self):
        self.assertEqual(8,len(target.REPLAY_ORIGINS))
        for lower,upper in ((0,512),(512,640)):
            self.assertEqual({1,2,3},{(index-lower)%3+1 for index in target.REPLAY_ORIGINS if lower<=index<upper})

    def test_candidate_is_only_a_token_overlay_in_fresh_arenas(self):
        source=target.previous.arena_source(protocol(),'/selected-q.json','screen')
        self.assertEqual('/selected-q.json',source['tokenOrder']['candidateModel'])
        self.assertEqual('FIXED_16_TO_15',source['tokenOrder']['incumbentSelector'])
        self.assertEqual(source['candidate'],source['incumbent'])
        self.assertEqual(source['expansionImitation']['candidateModel'],source['expansionImitation']['incumbentModel'])

    def test_final_rivals_use_only_fixed_combined_military_policy(self):
        source=target.previous.arena_source(protocol(),'/selected-q.json','heldout')
        self.assertEqual(['/m8-ordinary.json'],source['opponents'])
        self.assertEqual(['/m8-expansion.json'],source['expansionImitation']['opponentModels'])
        self.assertEqual(['FIXED_16_TO_15'],source['tokenOrder']['opponentSelectors'])


class BoundedControllerTest(unittest.TestCase):
    def test_every_stage_has_one_attempt_and_original_six_hour_global_cap(self):
        spec=campaign.campaign_spec(Path('/isolated'),protocol(),{})
        self.assertEqual(21600,spec['limits']['maximumSeconds'])
        self.assertEqual(list(target.STAGES),[stage['id'] for stage in spec['stages']])
        self.assertEqual(list(target.CEILINGS),[stage['maximumSeconds'] for stage in spec['stages']])
        self.assertTrue(all(stage['maximumAttempts']==1 for stage in spec['stages']))

    def test_fit_stage_is_externally_bounded_to_ten_minutes(self):
        spec=campaign.campaign_spec(Path('/isolated'),protocol(),{})
        fit=next(stage for stage in spec['stages'] if stage['id']=='fit')
        self.assertEqual(600,fit['maximumSeconds'])
        self.assertIn('/isolated/scripts/automata_token_eight_tail.py',fit['command'])

    def test_collection_can_use_remaining_global_allowance_without_renewal(self):
        spec=campaign.campaign_spec(Path('/isolated'),protocol(),{})
        self.assertEqual(21600,spec['stages'][0]['maximumSeconds'])
        self.assertEqual(21600,spec['limits']['maximumSeconds'])

    def test_final_banks_are_conditional_on_previous_stage(self):
        spec=campaign.campaign_spec(Path('/isolated'),protocol(),{})
        self.assertEqual(['screen'],spec['stages'][3]['dependsOn'])
        self.assertEqual(['confirm'],spec['stages'][4]['dependsOn'])

    def test_no_simple_control_or_prefix_model_enters_arena_stages(self):
        self.assertEqual(('collect','fit','screen','confirm','heldout'),target.STAGES)
        self.assertEqual([.0001,.001,.01,.1,1.],target.FIT['ridgePenalties'])

    def test_failed_screen_never_opens_confirmation(self):
        selection={'admitted':True,'fitReportSha256':'fit','modelSha256':'model','model':'/q.json'}
        prior={'metrics':{'modelSha256':'model','learnerPassed':False,'rawMetrics':metrics(lower=-.01)}}
        with patch.object(target,'verify_fit',return_value=({'candidateSha256':'model'},True)), \
             patch.object(target,'read',side_effect=[selection,prior]), \
             patch.object(target,'sha',side_effect=lambda path:'fit' if str(path).endswith('fit.json') else 'model'), \
             patch.object(target.previous,'evaluate') as run:
            with self.assertRaisesRegex(ValueError,'preceding numerical gate'):
                target.evaluate(protocol(),Path('/protocol'),Path('/run'),'confirm')
            run.assert_not_called()

    def test_screen_requires_mean_gain_in_addition_to_positive_marginal_bounds(self):
        self.assertFalse(target.previous.screen_passes(metrics(mean=.029,lower=.001)))


if __name__=='__main__':
    unittest.main()
