"""Policy ownership, descriptive negative outcomes and bounded execution; no games."""
from pathlib import Path
import unittest
from unittest.mock import patch

import token_minus_two as diagnostic
import token_minus_two_campaign as campaign


def protocol():
    return dict(baseline='/f1.json', expansionModel='/e.json',
        frozenPurchase={'baseline':'/f0.json', 'policy':'/c.json'},
        experimentalRulesTransfer=diagnostic.arena.legacy.TRANSFER,
        banks={'screen':{'nativeOffset':33000000, 'count':512}}, limitation='Descriptive only.')


class DiagnosticTest(unittest.TestCase):
    def test_minus_two_changes_only_evaluated_candidate_tokens(self):
        source = diagnostic.source(protocol())
        self.assertEqual({'candidateSelector':'ORDINARY_MINUS_TWO', 'incumbentSelector':'FIXED_16_TO_15',
            'opponentSelectors':['ORDINARY', 'ORDINARY', 'FIXED_16_TO_15', 'FIXED_16_TO_15']}, source['tokenOrder'])
        self.assertEqual('/f1.json', source['candidate'])
        self.assertEqual(source['candidate'], source['incumbent'])
        self.assertNotIn('candidateModel', source['tokenOrder'])

    def test_expansion_and_purchase_match_on_both_sides_and_explicit_rivals(self):
        source = diagnostic.source(protocol())
        self.assertEqual(dict(candidateModel='/e.json', incumbentModel='/e.json',
            opponentModels=[None,'/e.json',None,'/e.json']), source['expansionImitation'])
        self.assertEqual(['/f1.json'] * 4, source['opponents'])
        self.assertEqual(protocol()['frozenPurchase'], source['frozenPurchase'])

    def test_negative_result_is_complete_descriptive_evidence(self):
        pairs = [dict(seat=index % 4, opponentIndices=[0,1,2,3]) for index in range(512)]
        metrics = dict(winShareDifference={'mean':-.1, 'lower95':-.2}, rewardDifference={'mean':-.1, 'lower95':-.2})
        report = diagnostic.descriptive_report(protocol(), pairs, metrics, [{}] * 8)
        self.assertTrue(report['mechanicalVerificationPassed'])
        self.assertFalse(report['qualificationPassed'])
        self.assertFalse(report['modelSelection'])
        self.assertEqual(metrics, report['rawMetrics'])
        self.assertEqual({'0':128,'1':128,'2':128,'3':128}, report['controlledSeatCounts'])
        self.assertEqual(1536, sum(report['actualRivalRoleCounts'].values()))

    def test_incomplete_world_count_cannot_report_success(self):
        with self.assertRaisesRegex(ValueError, 'Complete all'):
            diagnostic.descriptive_report(protocol(), [{}] * 511, {}, [{}] * 8)

    def test_single_bank_can_reach_existing_role_verifier(self):
        source = diagnostic.source(protocol())
        with patch.object(diagnostic.arena.legacy, 'verify_environment', side_effect=RuntimeError('boundary reached')):
            with self.assertRaisesRegex(RuntimeError, 'boundary reached'):
                diagnostic.arena.verify_overlay(protocol(), source, Path('/arena'), ())

    def test_single_one_hour_stage_has_no_fitting_or_follow_on(self):
        spec = campaign.campaign_spec(Path('/package'), {'python':{'executable':'/python'}}, {})
        self.assertEqual(3600, spec['limits']['maximumSeconds'])
        self.assertEqual(1, len(spec['stages']))
        self.assertEqual(('screen', [], 3600, 1), tuple(spec['stages'][0][key]
            for key in ('id', 'dependsOn', 'maximumSeconds', 'maximumAttempts')))


if __name__ == '__main__':
    unittest.main()
