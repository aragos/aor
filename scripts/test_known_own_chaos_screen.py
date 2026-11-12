"""Synthetic source/receipt checks only; no native process, retained states or new seeds."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_experiments as exp
import known_own_chaos_screen as screen


class ScreenTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.protocol = dict(ordinary='M8', expansion='E', prior='F0', bundle='G3', workers=6,
            banks={'screen': dict(nativeOffset=71000000, count=1024,
                                 seeds=exp.arena_seeds(71000000, 1024))})

    def pair(self, index, chunk=0):
        global_index = chunk*512 + index
        game = dict(wins=[1., 0., 0., 0.], rewards=[1., 0., .1, .05],
            ranks=[1., 0., .5, .25], scores=[{'total': x} for x in (100, None, 50, 20)], decisions=15)
        return dict(index=index, configuration=exp.CONFIGURATION,
            seed=self.protocol['banks']['screen']['seeds'][global_index], seat=index % 4,
            opponentIndices=[(index//4+s) % 4 for s in range(4)],
            candidate=deepcopy(game), incumbent=deepcopy(game), jointPolicy={'UNIT': True})

    def chunk(self, chunk=0):
        source = self.root/'source.json'; exp.document(source, screen.source(self.protocol, chunk))
        directory = self.root/'native'; directory.mkdir()
        pairs = [self.pair(i, chunk) for i in range(512)]
        for i, pair in enumerate(pairs):
            exp.document(directory/f'pair-{i}.json', pair)
        metrics = screen.search.comparison(pairs)
        exp.document(directory/'evaluation.json', dict(complete=True, purpose='SCREENING',
            promoted=False, humanStrengthVerified=False, overall=metrics,
            byConfiguration={exp.CONFIGURATION: metrics}))
        exp.document(directory/'run.json', dict(kind='strategy-arena', signature='UNIT',
            source=str(source), workers=6, pairs=512, seed=screen.source(self.protocol, chunk)['seed'],
            opponentModels=4))
        exp.document(directory/'state.json', dict(kind='strategy-arena', signature='UNIT', phase='complete'))
        exp.document(directory/'finished.json', dict(at='UNIT'))
        return source, directory

    def test_only_candidate_behavior_differs_from_identical_g3_roles(self):
        value = screen.source(self.protocol, 0)
        self.assertEqual(value['jointPolicy'], dict(baseline='F0', sharedBundle='G3',
            candidateBundle='G3', incumbentBundle='G3', candidateSupport='KNOWN_OWN_CHAOS_1'))
        self.assertNotIn('frozenPurchase', value)
        self.assertEqual(value['purpose'], 'SCREENING')

    def test_all_rivals_keep_the_same_expansion_and_token_overlays(self):
        value = screen.source(self.protocol, 0)
        self.assertEqual(value['expansionImitation']['opponentModels'], ['E']*4)
        self.assertEqual(value['tokenOrder']['opponentSelectors'], ['FIXED_16_TO_15']*4)

    def test_two_chunks_preserve_every_original_world_seat_and_lineup(self):
        actual = []
        for chunk in range(2):
            actual.extend(exp.arena_seeds(screen.source(self.protocol, chunk)['seed'], 512))
            for local in range(512):
                global_index = chunk*512+local
                self.assertEqual(local % 4, global_index % 4)
                self.assertEqual([(local//4+s) % 4 for s in range(4)],
                                 [(global_index//4+s) % 4 for s in range(4)])
        self.assertEqual(actual, self.protocol['banks']['screen']['seeds'])

    def test_reproduction_indices_cover_each_seat_with_original_local_index(self):
        self.assertEqual([divmod(i, 512) for i in screen.REPLAYS], [(0, 0), (0, 341), (1, 170), (1, 511)])
        self.assertEqual([i % 4 for i in screen.REPLAYS], [0, 1, 2, 3])

    def test_changed_bank_formula_is_rejected(self):
        self.protocol['banks']['screen']['seeds'][1] += 1
        with self.assertRaisesRegex(ValueError, 'prospective'):
            screen.source(self.protocol, 0)

    def test_unplanned_chunk_is_rejected(self):
        for chunk in (-1, 2, True):
            with self.subTest(chunk=chunk), self.assertRaisesRegex(ValueError, 'chunks'):
                screen.source(self.protocol, chunk)

    def test_all_pair_descriptors_are_checked_before_compaction(self):
        source, directory = self.chunk()
        path = directory/'pair-511.json'; row = exp.read(path); row['jointPolicy'] = {}
        path.write_text(json.dumps(row))
        with patch.object(screen, 'verify_roles', return_value={'UNIT': True}), \
                self.assertRaisesRegex(ValueError, 'descriptor'):
            screen.verify_chunk(self.protocol, source, directory, 0)

    def test_complete_second_chunk_keeps_original_index_and_global_position(self):
        source, directory = self.chunk(1)
        with patch.object(screen, 'verify_roles', return_value={'UNIT': True}):
            actual = screen.verify_chunk(self.protocol, source, directory, 1)
        self.assertEqual(len(actual['rows']), 512)
        self.assertEqual(actual['rows'][-1]['index'], 511)
        self.assertEqual(actual['rows'][-1]['globalIndex'], 1023)
        self.assertEqual(actual['rows'][-1]['pairSha256'], exp.sha(directory/'pair-511.json'))
        self.assertIsNone(actual['rows'][0]['candidate']['scores'][1]['total'])

    def test_missing_pair_fails_before_any_aggregate(self):
        source, directory = self.chunk(); (directory/'pair-0.json').unlink()
        with patch.object(screen, 'verify_roles', return_value={'UNIT': True}), \
                self.assertRaisesRegex(ValueError, 'All512'):
            screen.verify_chunk(self.protocol, source, directory, 0)

    def test_changed_support_source_fails_even_if_original_role_models_match(self):
        source, directory = self.chunk(); value = exp.read(source)
        del value['jointPolicy']['candidateSupport']; source.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'source differs'):
            screen.verify_chunk(self.protocol, source, directory, 0)

    def test_original_native_metrics_are_recomputed(self):
        source, directory = self.chunk(); path = directory/'evaluation.json'
        value = exp.read(path); value['overall']['rewardDifference']['mean'] = .1
        path.write_text(json.dumps(value))
        with patch.object(screen, 'verify_roles', return_value={'UNIT': True}), \
                self.assertRaisesRegex(ValueError, 'aggregate'):
            screen.verify_chunk(self.protocol, source, directory, 0)

    def test_effects_retain_all_roots_and_explicit_chaos_not_zero_score(self):
        rows = [dict(self.pair(i % 512, i//512), globalIndex=i) for i in range(1024)]
        actual = screen.metrics(rows)
        self.assertTrue(all(v['count'] == 1024 and v['mean'] == 0 for v in actual['paired'].values()))
        self.assertEqual(actual['absolute']['candidate']['ownChaos']['mean'], .25)
        self.assertEqual(actual['absolute']['candidate']['rankReward']['mean'], .4375)

    def test_partial_bank_cannot_produce_effects(self):
        with self.assertRaisesRegex(ValueError, 'whole1,024'):
            screen.metrics([dict(self.pair(0), globalIndex=0)])

    def test_role_verifier_refuses_omitted_conditional_identity(self):
        with patch.object(screen.joint, 'joint_binding', return_value={'UNIT': True}), \
                self.assertRaisesRegex(ValueError, 'conditional support'):
            screen.verify_roles(self.protocol, self.root/'source.json', self.root,
                                [{'jointPolicy': {'UNIT': True}}])

    def test_role_verifier_refuses_an_unknown_support_version(self):
        roles = dict(UNIT=True, candidateSupport=dict(screen.PROFILE, version='future'))
        with patch.object(screen.joint, 'joint_binding', return_value={'UNIT': True}), \
                self.assertRaisesRegex(ValueError, 'conditional support'):
            screen.verify_roles(self.protocol, self.root/'source.json', self.root,
                                [{'jointPolicy': roles}])

    def replay(self):
        source, native = self.chunk(1)
        pair_path = native/'pair-170.json'; pair = exp.read(pair_path)
        row = screen.chunks.compact_pair(pair, {'UNIT': True}, 170, 682,
            pair['seed'], exp.sha(pair_path), exp.sha(source))
        target = self.root/'replay'; target.mkdir()
        replay = dict(row, side='CANDIDATE', replayVerified=True, historicalPairVerified=True,
                      game=pair['candidate'], jointPolicy={'UNIT': True})
        exp.document(target/'replay.json', replay)
        exp.document(target/'run.json', dict(source=str(source), workers=1, signature='UNIT',
                                            recordedPair=str(pair_path)))
        exp.document(target/'state.json', dict(signature='UNIT', phase='complete'))
        exp.document(target/'finished.json', dict(at='UNIT'))
        return source, target, row

    def test_historical_replay_keeps_global_slot_but_original_local_pair_index(self):
        source, target, row = self.replay()
        with patch.object(screen, 'verify_roles', return_value={'UNIT': True}):
            actual = screen.verify_replay(self.protocol, source, target, row, 'candidate')
        self.assertEqual((actual['globalIndex'], actual['index']), (682, 170))
        self.assertEqual(actual['pairSha256'], row['pairSha256'])

    def test_historical_replay_rejects_changed_support_metadata(self):
        source, target, row = self.replay(); path = target/'replay.json'; replay = exp.read(path)
        replay['jointPolicy'] = {}; path.write_text(json.dumps(replay))
        with patch.object(screen, 'verify_roles', return_value={'UNIT': True}), \
                self.assertRaisesRegex(ValueError, 'support identity'):
            screen.verify_replay(self.protocol, source, target, row, 'candidate')

    def test_historical_replay_rejects_a_different_terminal_even_if_identity_matches(self):
        source, target, row = self.replay(); path = target/'replay.json'; replay = exp.read(path)
        replay['game']['decisions'] += 1; path.write_text(json.dumps(replay))
        with self.assertRaisesRegex(ValueError, 'same source'):
            screen.verify_replay(self.protocol, source, target, row, 'candidate')

    def test_negative_effects_do_not_become_qualification_or_selection(self):
        rows = [dict(self.pair(i % 512, i//512), globalIndex=i) for i in range(1024)]
        for row in rows:
            row['candidate']['rewards'][row['seat']] = 0.
        result = screen.metrics(rows)
        self.assertLess(result['paired']['reward']['mean'], 0)
        self.assertEqual(set(result), {'paired', 'absolute'})

    def test_campaign_is_one_fixed_stage_with_original_resource_clock(self):
        protocol = dict(self.protocol, maximumSeconds=7200, maximumDataBytes=8*exp.GIB,
            maximumRssBytes=8*exp.GIB, minimumFreeBytes=8*exp.GIB, python={'executable': 'python'})
        value = screen.campaign_spec(self.root, protocol, {'UNIT': 'hash'})
        self.assertEqual(len(value['stages']), 1)
        self.assertEqual(value['stages'][0]['maximumAttempts'], 1)
        self.assertEqual(value['stages'][0]['maximumSeconds'], value['limits']['maximumSeconds'])
        self.assertEqual(value['stages'][0]['dependsOn'], [])


if __name__ == '__main__':
    unittest.main()
