"""Terminal-return views are distinct from actual terminal rows and never cross origins."""
import copy
from dataclasses import asdict, replace
from pathlib import Path
import random
import tempfile
import time
import unittest
from unittest.mock import patch

import territory_value as q
import territory_value_trajectory as t
from test_territory_value import actors, game
from test_territory_value_trajectory import fixture

b = q.b


def suffix_return(episode, start):
    """Independent one-step traversal oracle; use recorded successor identity, not a horizon."""
    rows = {row.number: row for row in episode.transitions}
    row, total = start, 0.
    while True:
        total += row.reward
        if row.successor is None:
            return total
        row = rows[row.successor.number]


class TerminalReturnTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)

    def test_only_explicit_terminal_string_extends_the_two_integer_modes(self):
        for value in (1, 16, 'terminal'):
            self.assertEqual(value, q.UpdateConfig(targetSteps=value).validate().targetSteps)
        for value in (True, False, 1., 16., '1', '16', 'Terminal', 'terminal ', 'monte-carlo', '', None):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'return horizon'):
                q.UpdateConfig(targetSteps=value).validate()

    def test_omitted_mode_keeps_the_exact_original_config_default(self):
        self.assertEqual(asdict(q.UpdateConfig(targetSteps=1)), asdict(q.UpdateConfig()))
        episode = game()
        self.assertIs(episode.transitions, q.backup_transitions(episode, 1))

    def test_full_trajectory_oracle_matches_every_mixed_phase_sample(self):
        episode = game(phases=('token', 'expansion', 'expansion')*27, reward=.75)
        original = copy.deepcopy(episode)
        views = q.backup_transitions(episode, 'terminal')
        for raw, view in zip(episode.transitions, views):
            self.assertEqual(suffix_return(episode, raw), view.reward)
            self.assertIsNone(view.successor)
            self.assertEqual((raw.origin, raw.number, raw.phase, raw.selected),
                             (view.origin, view.number, view.phase, view.selected))
            self.assertIs(raw.observation, view.observation)
        self.assertEqual(original, episode)
        self.assertEqual(sum(row.reward for row in episode.transitions), episode.reward)

    def test_decision_number_gaps_do_not_change_the_terminal_label(self):
        original = game(phases=('token', 'expansion', 'token', 'expansion'), reward=.25)
        rows = original.transitions
        gapped = tuple(replace(row, number=13+i*19, successor=(None if i+1 == len(rows)
            else q.Successor(row.origin, 13+(i+1)*19, rows[i+1].phase, rows[i+1].observation)))
            for i, row in enumerate(rows))
        episode = replace(original, transitions=gapped)
        self.assertEqual([.25]*4, [row.reward for row in q.backup_transitions(episode, 'terminal')])

    def test_singleton_keeps_actual_terminal_facts(self):
        episode = game(phases=('token',), reward=.4)
        self.assertEqual(episode.transitions, q.backup_transitions(episode, 'terminal'))
        self.assertEqual((.4, None), (episode.transitions[0].reward, episode.transitions[0].successor))

    def test_empty_owned_phase_game_has_no_invented_transition(self):
        episode = game(phases=(), reward=.75)
        self.assertEqual((), q.backup_transitions(episode, 'terminal'))

    def test_zero_terminal_return_is_not_replaced_by_bootstrap(self):
        episode = game(phases=('expansion',)*30, reward=0.)
        self.assertEqual([0.]*30, [row.reward for row in q.backup_transitions(episode, 'terminal')])

    def test_every_terminal_view_bypasses_both_networks_and_is_detached(self):
        rows = q.backup_transitions(game(phases=('token', 'expansion')*12, reward=.25), 'terminal')
        with patch.object(q, 'evaluate', side_effect=AssertionError('No successor network calls')):
            values = q.targets(rows, {}, {})
        self.assertEqual([.25]*24, values.tolist())
        self.assertEqual(b.DTYPE, values.dtype)
        self.assertFalse(values.requires_grad)

    def test_different_games_keep_their_own_return(self):
        replay = q.Replay()
        replay.append([game(1, reward=.25), game(2, reward=.75)])
        replay.prepare_backups('terminal')
        class Every:
            def __init__(self): self.index = 0
            def randrange(self, count):
                selected = self.index; self.index += 1; return selected
        rows = replay.sample('token', 2, Every())
        self.assertEqual([('1', .25), ('2', .75)], [(row.origin, row.reward) for row in rows])

    def test_unfinished_episode_is_rejected_before_target_views(self):
        episode = game(phases=('token', 'expansion', 'token'))
        with self.assertRaisesRegex(ValueError, 'last actual learned decision'):
            q.backup_transitions(replace(episode, transitions=episode.transitions[:-1]), 'terminal')

    def test_inconsistent_terminal_reward_is_rejected_by_episode_owner(self):
        episode = game(reward=.75)
        altered = replace(episode.transitions[-1], reward=.5)
        with self.assertRaisesRegex(ValueError, 'last actual learned decision'):
            q.backup_transitions(replace(episode, transitions=(episode.transitions[0], altered)), 'terminal')

    def test_intermediate_receipt_cannot_be_double_counted(self):
        episode = game(reward=.75)
        altered = replace(episode.transitions[0], reward=.25)
        with self.assertRaisesRegex(ValueError, 'actual next owned learned row'):
            q.backup_transitions(replace(episode, transitions=(altered, episode.transitions[1])), 'terminal')

    def test_cross_origin_successor_is_rejected_before_terminal_conversion(self):
        episode = game()
        altered = replace(episode.transitions[0], successor=replace(episode.transitions[0].successor, origin='other'))
        with self.assertRaisesRegex(ValueError, 'actual next owned learned row'):
            q.backup_transitions(replace(episode, transitions=(altered, episode.transitions[1])), 'terminal')

    def test_replay_sampling_consumes_identical_rng_draws_in_identical_order(self):
        replay = q.Replay()
        replay.append([game(1, ('token', 'expansion'), reward=.25),
                       game(2, ('expansion', 'token', 'expansion'), reward=.75)])
        for phase in q.PHASES:
            old_rng, terminal_rng = random.Random(891), random.Random(891)
            replay.prepare_backups(1); old = replay.sample(phase, 80, old_rng)
            replay.prepare_backups('terminal'); terminal = replay.sample(phase, 80, terminal_rng)
            self.assertEqual([(row.origin, row.number, row.selected) for row in old],
                             [(row.origin, row.number, row.selected) for row in terminal])
            self.assertEqual(old_rng.getstate(), terminal_rng.getstate())

    def test_sample_cache_changes_mode_and_evicts_whole_games(self):
        replay = q.Replay(game_capacity=1); replay.append([game(1, ('token',)*20)])
        replay.prepare_backups('terminal')
        self.assertIsNone(replay.sample('token', 1, random.Random(1))[0].successor)
        replay.prepare_backups(1)
        self.assertTrue(any(row.successor is not None for row in replay._sample_rows['token']))
        replay.prepare_backups('terminal'); replay.append([game(2, reward=.25)])
        rows = replay.sample('token', 3, random.Random(1))
        self.assertEqual([('2', .25)]*3, [(row.origin, row.reward) for row in rows])

    def test_calibration_still_counts_only_original_terminal_decisions(self):
        replay = q.Replay(); replay.append([game()]); replay.prepare_backups('terminal')
        online = actors(); replay.prepare(online)
        result = q.numerical_guard(online, q.snapshot(online), replay,
                                  q.UpdateConfig(targetSteps='terminal'), time.monotonic()+10)
        self.assertIsNotNone(replay.rows('token')[0].successor)
        self.assertEqual(0, result['values']['online']['token']['terminalSelectedCount'])
        self.assertEqual(1, result['values']['online']['expansion']['terminalSelectedCount'])


class TerminalIdentityTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.directory, self.bundle = fixture(self.root/'data')

    def test_terminal_mode_round_trips_completed_identity_without_refitting(self):
        config = q.UpdateConfig(targetSteps='terminal'); output = self.root/'terminal'
        done = t.update_pair(self.directory, self.bundle, config, output)
        self.assertEqual('terminal', t.read(output/'identity.json')['config']['targetSteps'])
        with patch.object(q, 'fit_pair', side_effect=AssertionError('Completed reuse must not fit')):
            self.assertEqual(done, t.update_pair(self.directory, self.bundle, config, output, resume=True))

    def test_terminal_output_cannot_be_reused_as_one_or_sixteen_step(self):
        output = self.root/'terminal'
        t.update_pair(self.directory, self.bundle, q.UpdateConfig(targetSteps='terminal'), output)
        for horizon in (1, 16):
            with self.subTest(horizon=horizon), self.assertRaisesRegex(ValueError, 'cannot change the learning algorithm'):
                t.read_completed(output, q.UpdateConfig(targetSteps=horizon))

    def test_terminal_update_still_requires_exact_previous_behavior_bundle(self):
        first = self.root/'first'; config = q.UpdateConfig(targetSteps='terminal')
        t.update_pair(self.directory, self.bundle, config, first)
        # New world metadata but the old initializer is deliberately not the first fitted actor.
        frozen = t.read(self.directory/'source.json')['frozenCardPurchaseBundle']
        next_directory, old_bundle = fixture(self.root/'next', frozen_path=frozen, seed=31)
        with patch.object(q, 'fit_pair', side_effect=AssertionError('Never fit a mismatched behavior chain')):
            with self.assertRaisesRegex(ValueError, 'exact preceding atomic online bundle'):
                t.update_pair(next_directory, old_bundle, config, self.root/'second',
                              checkpoint_path=first/'checkpoint.json')


if __name__ == '__main__':
    unittest.main()
