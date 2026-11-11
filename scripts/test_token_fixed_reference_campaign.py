"""Residual profile ownership and bounded stages; no collected banks or fitting."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import automata_token_eight_tail as target
import token_eight_tail_campaign as campaign
import token_order_fixed_reference as fixed
from test_token_eight_tail_campaign import protocol


def residual():
    return dict(protocol(), kind=fixed.CAMPAIGN_KIND, continuationProtocol=fixed.PROTOCOL)


class ResidualSourceTest(unittest.TestCase):
    def test_controlled_collection_uses_explicit_fixed_reference(self):
        source = target.collection_source(residual())
        self.assertEqual(fixed.PROTOCOL, source['continuationProtocol'])
        self.assertEqual(fixed.SELECTOR, source['tokenOrder']['candidateSelector'])
        self.assertEqual({'candidateSelector','opponentSelectors'}, set(source['tokenOrder']))
        self.assertEqual(['ORDINARY','ORDINARY',fixed.SELECTOR,fixed.SELECTOR],
                         source['tokenOrder']['opponentSelectors'])

    def test_arena_has_tagged_candidate_and_exact_fixed_incumbent(self):
        source = target.previous.arena_source(residual(), '/residual.json', 'screen')
        self.assertEqual('MODEL_FIXED_T_RESIDUAL', source['tokenOrder']['candidateSelector'])
        self.assertEqual('/residual.json', source['tokenOrder']['candidateModel'])
        self.assertEqual(fixed.SELECTOR, source['tokenOrder']['incumbentSelector'])
        self.assertEqual(source['candidate'], source['incumbent'])
        self.assertEqual(source['expansionImitation']['candidateModel'],
                         source['expansionImitation']['incumbentModel'])

    def test_homogeneous_final_does_not_install_learned_rivals(self):
        source = target.previous.arena_source(residual(), '/residual.json', 'heldout')
        self.assertEqual([fixed.SELECTOR], source['tokenOrder']['opponentSelectors'])
        self.assertEqual(['/m8-expansion.json'], source['expansionImitation']['opponentModels'])
        self.assertNotIn('opponentModels', source['tokenOrder'])

    def test_legacy_collection_still_uses_ordinary_tokens(self):
        source = target.collection_source(protocol())
        self.assertEqual(target.CONTINUATION, source['continuationProtocol'])
        self.assertNotIn('candidateSelector', source['tokenOrder'])

    def test_legacy_arena_keeps_original_selector_default(self):
        source = target.previous.arena_source(protocol(), '/ordinary.json', 'screen')
        self.assertNotIn('candidateSelector', source['tokenOrder'])


class ResidualSupervisorTest(unittest.TestCase):
    def test_same_finite_stage_chain_and_gates_are_reused(self):
        spec = campaign.campaign_spec(Path('/package'), residual(), {})
        self.assertEqual(21600, spec['limits']['maximumSeconds'])
        self.assertEqual(4*target.exp.GIB, spec['limits']['maximumRssBytes'])
        self.assertEqual([21600,600,1800,3600,7200], [row['maximumSeconds'] for row in spec['stages']])
        self.assertEqual([[],['collect'],['fit'],['screen'],['confirm']],
                         [row['dependsOn'] for row in spec['stages']])
        self.assertTrue(all(row['maximumAttempts'] == 1 for row in spec['stages']))

    def test_mac_identity_uses_the_reviewed_framework_and_cpu_owner(self):
        self.assertEqual('purchase_ecology_environment', target.numerical_environment(residual()).__name__)
        self.assertEqual('adaptive_token_environment', target.numerical_environment(protocol()).__name__)

    def test_envelope_validation_in_pure_supervisor_needs_no_torch(self):
        before = set(sys.modules)
        raw = {'outputWeights':[0.]*64}
        self.assertIs(raw, fixed.envelope_model(dict(fixed.ENVELOPE,model=raw)))
        self.assertNotIn('torch', set(sys.modules)-before)

    def test_pure_supervisor_rejects_untagged_model(self):
        with self.assertRaisesRegex(ValueError, 'tagged artifact'):
            fixed.envelope_model({'outputWeights':[0.]*64})

    def test_ordinary_fit_receipt_cannot_enter_residual_campaign(self):
        artifact = {'kind':'token-order-fresh-eight-tail-ridge-1'}
        with patch.object(target,'read',return_value=artifact):
            with self.assertRaisesRegex(ValueError,'exact fresh eight-tail'):
                target.verify_fit(residual(),Path('/run'))

    def test_zero_head_proof_rejects_a_different_fixture_or_runtime(self):
        valid = dict(passed=True,newGames=0,actualFits=0,fixtureSha256=target.SAVED_OBSERVATIONS,
            ordinaryModelSha256=target.BASELINE,initializerSha256=target.INITIALIZER,
            trainingJarSha256='jar',tokenObservations=27)
        target.validate_zero_head(valid,'jar')
        for field in ('fixtureSha256','ordinaryModelSha256','initializerSha256','trainingJarSha256','tokenObservations'):
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError,'all 27 saved'):
                    target.validate_zero_head(dict(valid,**{field:'changed'}),'jar')

    def test_freeze_rejects_changed_historical_proof_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root/'observation.json'
            artifact.write_text('changed')
            frozen = dict(java={'files':{}},python={'files':{}},numerical={'files':{}},
                          externalEvidenceFiles={str(artifact):'0'*64})
            with self.assertRaisesRegex(ValueError,'historical inference evidence changed'):
                campaign.input_files(root,frozen)


if __name__ == '__main__':
    unittest.main()
