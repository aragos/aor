"""Behavioral checks for complete-game pairing, gates and evidence opening."""
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import card_advantage_evaluation as evaluation


def records(stage, wins):
    worlds, arms, all_roles = evaluation.STAGES[stage]
    seeds = list(range(worlds))
    result = []
    for index, seed in enumerate(seeds):
        for seat in (range(4) if all_roles else (index % 4,)):
            for arm in arms:
                win = wins(index, seat, arm)
                result.append(dict(seed=seed, controlledSeat=seat, arm=arm,
                                   terminal=dict(winShare=win, rankReward=win, reward=win)))
    return seeds, result


class FullGameEvaluationTest(unittest.TestCase):
    def test_robustness_clusters_four_correlated_roles_in_each_world(self):
        seeds, rows = records('rival-robustness',
                              lambda world, seat, arm: float(arm == 'candidate' and world % 2))
        report = evaluation.analyze('rival-robustness', seeds, rows)
        actual = report['comparisons']['candidate-minus-baseline']['winShare']
        expected = evaluation.interval([float(world % 2) for world in seeds])
        self.assertEqual(256, actual['independentWorlds'])
        self.assertEqual(1024, actual['pairedRoles'])
        self.assertEqual(expected['lower95'], actual['lower95'])

    def test_pairing_keeps_world_and_role_even_when_results_are_reordered(self):
        seeds, rows = records('rival-robustness',
                              lambda world, seat, arm: float((world + seat) % 2))
        report = evaluation.analyze('rival-robustness', seeds, list(reversed(rows)))
        actual = report['comparisons']['candidate-minus-baseline']['winShare']
        self.assertEqual(0, actual['mean'])
        self.assertEqual(0, actual['lower95'])

    def test_missing_arm_cannot_be_silently_imputed(self):
        seeds, rows = records('p1-screen', lambda *args: 0)
        with self.assertRaisesRegex(ValueError, 'exactly once'):
            evaluation.analyze('p1-screen', seeds, rows[:-1])

    def test_duplicate_game_does_not_replace_a_missing_world(self):
        seeds, rows = records('p1-screen', lambda *args: 0)
        with self.assertRaisesRegex(ValueError, 'exactly once'):
            evaluation.analyze('p1-screen', seeds, rows[:-1] + rows[:1])

    def test_significant_small_gain_does_not_meet_five_point_margin(self):
        seeds, rows = records('primary-confirmation',
                              lambda world, seat, arm: float(arm == 'candidate' and world < 40))
        report = evaluation.analyze('primary-confirmation', seeds, rows)
        self.assertGreater(report['comparisons']['candidate-minus-baseline']['winShare']['lower95'], 0)
        self.assertFalse(report['gates']['incumbent'])
        self.assertFalse(report['passed'])

    def test_frequency_control_tie_blocks_activation_despite_incumbent_gain(self):
        seeds, rows = records('primary-confirmation',
                              lambda world, seat, arm: float(arm != 'baseline' and world < 128))
        report = evaluation.analyze('primary-confirmation', seeds, rows)
        self.assertTrue(report['gates']['incumbent'])
        self.assertFalse(report['gates']['frequencyControl'])
        self.assertFalse(report['passed'])

    def test_clear_primary_gain_passes_both_predeclared_controls(self):
        seeds, rows = records('primary-confirmation',
                              lambda world, seat, arm: float(arm == 'candidate' and world < 128))
        report = evaluation.analyze('primary-confirmation', seeds, rows)
        self.assertTrue(report['passed'])
        self.assertFalse(report['humanStrengthClaim'])

    def test_robustness_requires_nonnegative_mean_even_with_acceptable_bound(self):
        seeds, rows = records('rival-robustness',
                              lambda world, seat, arm: float(arm == 'baseline' and world == 0))
        report = evaluation.analyze('rival-robustness', seeds, rows)
        self.assertTrue(report['gates']['boundedLoss'])
        self.assertFalse(report['gates']['nonnegativeMean'])
        self.assertFalse(report['passed'])

    def test_clear_repeated_policy_harm_stops_second_cycle(self):
        seeds, rows = records('p1-screen',
                              lambda world, seat, arm: float(arm == 'baseline' and world < 32))
        report = evaluation.analyze('p1-screen', seeds, rows)
        self.assertTrue(report['harmfulTransfer'])
        self.assertFalse(report['mayContinue'])

    def test_inconclusive_screen_is_not_a_qualification(self):
        seeds, rows = records('p1-screen', lambda *args: 0)
        report = evaluation.analyze('p1-screen', seeds, rows)
        self.assertTrue(report['mayContinue'])
        self.assertFalse(report['qualification'])

    def test_development_prefers_p1_for_less_than_one_point_difference(self):
        seeds, rows = records('common-development',
                              lambda world, seat, arm: float(arm == 'p2' and world < 2))
        report = evaluation.analyze('common-development', seeds, rows)
        self.assertEqual('p1', report['selected'])

    def test_development_selects_p2_when_margin_is_met(self):
        seeds, rows = records('common-development',
                              lambda world, seat, arm: float(arm == 'p2' and world < 8))
        report = evaluation.analyze('common-development', seeds, rows)
        self.assertEqual('p2', report['selected'])

    def test_reward_contract_cannot_change_between_arms(self):
        seeds, rows = records('p1-screen', lambda *args: 0)
        rows[0]['terminal']['reward'] = .2
        with self.assertRaisesRegex(ValueError, 'frozen reward'):
            evaluation.analyze('p1-screen', seeds, rows)

    def test_all_hashes_are_checked_before_any_outcome_is_decoded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            blob = b'not a compressed outcome'
            (root / 'game-0.json.gz').write_bytes(blob)
            (root / 'game-1.json.gz').write_bytes(blob)
            manifest = dict(kind='card-advantage-evaluate', signature='test', games=2, files=[
                dict(index=0, file='game-0.json.gz', sha256=hashlib.sha256(blob).hexdigest()),
                dict(index=1, file='game-1.json.gz', sha256='0' * 64)])
            (root / 'finished.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'hash differs: game-1'):
                evaluation.load_completed(root)

    def test_completed_record_must_match_frozen_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            blob = gzip.compress(json.dumps(dict(schemaVersion=1, index=0,
                                                signature='different')).encode())
            (root / 'game-0.json.gz').write_bytes(blob)
            (root / 'finished.json').write_text(json.dumps(dict(
                kind='card-advantage-evaluate', signature='expected', games=1, files=[
                    dict(index=0, file='game-0.json.gz', sha256=hashlib.sha256(blob).hexdigest())])))
            with self.assertRaisesRegex(ValueError, 'frozen runtime/input identity'):
                evaluation.load_completed(root)


if __name__ == '__main__':
    unittest.main()
