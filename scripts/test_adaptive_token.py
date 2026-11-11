"""Prospective policy ownership and admission tests; no fitting or native games."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import adaptive_token_arena as arena
import adaptive_token_campaign as campaign
import automata_adaptive_token as adaptive
import adaptive_token_environment as numerical


def protocol():
    return dict(baseline='/f1.json', expansionModel='/e.json',
        frozenPurchase={'baseline': '/f0.json', 'policy': '/c.json'},
        banks={name: dict(nativeOffset=31000343 + index, count=count,
            seeds=adaptive.exp.arena_seeds(31000343 + index, count))
            for index, (name, count) in enumerate(adaptive.COUNTS.items())},
        experimentalRulesTransfer=adaptive.legacy.TRANSFER)


def metrics(mean=.08, lower=.01, count=512):
    return {name: dict(count=count, mean=mean, lower95=lower, upper95=.15)
            for name in ('winShareDifference', 'rewardDifference')}


class SourceTest(unittest.TestCase):
    def test_collection_controlled_tokens_retain_ordinary_f1(self):
        source = adaptive.collection_source(protocol())
        self.assertEqual({'opponentSelectors': ['ORDINARY', 'ORDINARY', arena.FIXED, arena.FIXED]},
                         source['tokenOrder'])
        self.assertEqual('/e.json', source['expansionImitation']['candidateModel'])
        self.assertNotIn('incumbentModel', source['expansionImitation'])

    def test_collection_declares_replay_origins_before_outcomes(self):
        source = adaptive.collection_source(protocol())
        self.assertEqual('ARENA_OVERLAYS_1', source['continuationProtocol'])
        self.assertEqual([0, 85, 170, 255, 256, 299, 342, 383], source['replayOrigins'])
        self.assertEqual((256, 128, 2), (source['trainingRoots'], source['validationRoots'],
                                      source['continuationReplicates']))

    def test_screen_learner_changes_only_candidate_token_role(self):
        source = adaptive.arena_source(protocol(), '/q.json', 'screen')
        self.assertEqual('/q.json', source['tokenOrder']['candidateModel'])
        self.assertEqual(arena.FIXED, source['tokenOrder']['incumbentSelector'])
        self.assertNotIn('candidateSelector', source['tokenOrder'])
        self.assertEqual('/e.json', source['expansionImitation']['incumbentModel'])

    def test_screen_control_and_learner_share_exact_incumbent_environment(self):
        learned = adaptive.arena_source(protocol(), '/q.json', 'screen')
        control = adaptive.arena_source(protocol(), None, 'screen', control=True)
        self.assertEqual('ORDINARY_MINUS_TWO', control['tokenOrder']['candidateSelector'])
        self.assertNotIn('candidateModel', control['tokenOrder'])
        for key in ('seed', 'pairsPerConfiguration', 'candidate', 'incumbent', 'opponents',
                    'expansionImitation', 'frozenPurchase'):
            self.assertEqual(learned[key], control[key])
        self.assertEqual(learned['tokenOrder']['incumbentSelector'], control['tokenOrder']['incumbentSelector'])

    def test_heldout_uses_only_current_combined_rivals(self):
        source = adaptive.arena_source(protocol(), '/q.json', 'heldout')
        self.assertEqual(['/f1.json'], source['opponents'])
        self.assertEqual(['/e.json'], source['expansionImitation']['opponentModels'])
        self.assertEqual([arena.FIXED], source['tokenOrder']['opponentSelectors'])
        self.assertEqual(4096, source['pairsPerConfiguration'])

    def test_control_cannot_enter_final_bank(self):
        with self.assertRaisesRegex(ValueError, 'only to development'):
            adaptive.arena_source(protocol(), None, 'confirm', control=True)

    def test_collection_rejects_an_evaluated_model(self):
        with self.assertRaisesRegex(ValueError, 'cannot install'):
            arena.bindings(protocol(), 'collect', '/q.json')

    def test_original_p48_collection_source_remains_unchanged(self):
        old = dict(protocol(), opponents=['/old-rival.json'], seed=123,
                   trainingRoots=256, validationRoots=128)
        source = adaptive.legacy.collection_source(old)
        self.assertNotIn('continuationProtocol', source)
        self.assertNotIn('expansionImitation', source)
        self.assertNotIn('tokenOrder', source)


class AdmissionTest(unittest.TestCase):
    def test_screen_accepts_both_positive_bounds_and_mean_threshold(self):
        self.assertTrue(adaptive.screen_passes(metrics()))

    def test_screen_rejects_zero_reward_lower_bound(self):
        value = metrics()
        value['rewardDifference']['lower95'] = 0.
        self.assertFalse(adaptive.screen_passes(value))

    def test_screen_rejects_small_positive_mean(self):
        self.assertFalse(adaptive.screen_passes(metrics(mean=.029)))

    def test_screen_rejects_incomplete_bank(self):
        self.assertFalse(adaptive.screen_passes(metrics(count=511)))

    def test_final_worlds_remain_unopened_after_failed_screen(self):
        prior = dict(metrics=dict(modelSha256='q', learnerPassed=False, rawMetrics=metrics(lower=-.01)))
        with patch.object(adaptive, 'selected', return_value={'modelSha256': 'q'}), \
             patch.object(adaptive, 'read', return_value=prior), \
             patch.object(adaptive, 'evaluate') as evaluate:
            with self.assertRaisesRegex(ValueError, 'preceding performance gate'):
                adaptive.evaluate_stage({}, Path('/protocol.json'), Path('/run'), 'confirm')
            evaluate.assert_not_called()

    def test_both_screen_cells_complete_even_if_learner_fails(self):
        selection = {'modelSha256': 'q', 'model': '/q.json'}
        results = [([{'world': 1}], metrics(lower=-.01), []), ([{'world': 1}], metrics(), [])]
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(adaptive, 'selected', return_value=selection), \
             patch.object(adaptive, 'evaluate', side_effect=results) as evaluate, \
             patch.object(adaptive.arena, 'verify_common_incumbent') as parity, \
             patch.object(adaptive, 'stage_receipt', side_effect=lambda *args: args):
            receipt = adaptive.evaluate_stage({}, Path('/protocol.json'), Path(directory), 'screen')
        self.assertEqual(2, evaluate.call_count)
        parity.assert_called_once()
        self.assertFalse(receipt[4])
        self.assertFalse(receipt[6]['controlAffectsAdmission'])
        self.assertEqual({'learned', 'ordinary-minus-two'}, set(receipt[6]['cells']))


class ExecutionTest(unittest.TestCase):
    def test_numerical_child_preserves_venv_site_packages(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(adaptive.exp, 'remaining_seconds', return_value=20.), \
             patch.object(adaptive.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as child:
            declaration = dict(numerical={'executable': '/venv/bin/python'}, scripts='/scripts')
            adaptive.run_numerical(declaration, Path('/protocol'), Path(directory), 'fit-child')
        command = child.call_args.args[0]
        self.assertEqual(['/venv/bin/python', '-E', '-s', '-B'], command[:4])
        self.assertNotIn('-S', command)

    def test_numerical_inspection_does_not_resolve_away_venv_launcher(self):
        identity = dict(executable='/venv/bin/python', torchThreads=1, deterministicAlgorithms=True)
        with patch.object(numerical.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0,
                stdout=adaptive.json.dumps(identity), stderr='')) as child:
            self.assertEqual(identity, numerical.inspect_numerical('/venv/bin/python'))
        self.assertEqual('/venv/bin/python', child.call_args.args[0][0])

    def test_global_allowance_and_attempts_are_bounded(self):
        spec = campaign.campaign_spec(Path('/package'), {'python': {'executable': '/python'}}, {})
        self.assertEqual(21600, spec['limits']['maximumSeconds'])
        self.assertEqual(list(adaptive.STAGES), [stage['id'] for stage in spec['stages']])
        self.assertTrue(all(stage['maximumAttempts'] == 1 for stage in spec['stages']))
        self.assertTrue(all('stage' in stage['command'] for stage in spec['stages']))

    def test_reproduction_leaves_output_creation_to_native(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            declaration = dict(java={'executable': '/java'}, workers=10, runtime='/runtime')

            def invoke(command, **kwargs):
                self.assertFalse((output/'collection-reproduction').exists())
                self.assertNotIn('--workers', command)
                self.assertLessEqual(float(command[command.index('--hours') + 1]), 2.)
                return subprocess.CompletedProcess(command, 0)

            with patch.object(adaptive.exp, 'remaining_seconds', return_value=500.), \
                 patch.object(adaptive.subprocess, 'run', side_effect=invoke):
                adaptive.run_reproduction(declaration, output)


if __name__ == '__main__':
    unittest.main()
