"""Synthetic fixed62 orchestration checks; no scientific worlds or actor updates."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_experiments as exp
import joint_chrono_continuation as trial
import joint_chrono_continuation_data as data
import joint_credit_trial as old_trial
import joint_credit_trial_data as old_data


class ContinuationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.protocol = dict(ordinary='M8', expansion='E', prior='F0', initialBundle='G3', workers=10,
            initialCheckpoints={phase: dict(path=f'/original/batch-32/numeric/pair/{phase}/checkpoint.json',
                                           sha256=phase) for phase in data.PHASES},
            updateConfig=deepcopy(data.UPDATE_CONFIG), banks={
                **{f'training-{i}': dict(nativeOffset=62000061+i) for i in range(1, 65)},
                'screen': dict(nativeOffset=62000126, count=1024, seeds=exp.arena_seeds(62000126, 1024)),
                'updates': dict(seeds=list(range(62001000, 62001512)))})

    def state(self, count=0, streak=0):
        return dict(completedBatches=count, acceptedEpochs=dict(card=0, purchase=0),
                    consecutiveZeroAcceptedBatches=streak, stopReason=None)

    def metric(self, win=.1, reward=.1):
        return dict(winShareDifference=dict(mean=win), rewardDifference=dict(mean=reward))

    def rows(self):
        game = dict(wins=[1., 0., 0., 0.], rewards=[1., .1, .1, .1],
                    ranks=[1., 2., 3., 4.], scores=[100, None, 50, 20], decisions=20)
        return [dict(index=i % 512, globalIndex=i, configuration=exp.CONFIGURATION,
            seed=self.protocol['banks']['screen']['seeds'][i], seat=i % 4,
            opponentIndices=[(i // 4 + seat) % 4 for seat in range(4)],
            candidate=deepcopy(game), incumbent=deepcopy(game)) for i in range(1024)]

    def test_first_batch_carries_both_original_checkpoints(self):
        actual = trial.previous_checkpoints(self.protocol, self.root/'batches/batch-01', 1)
        self.assertEqual(actual, {phase: Path(pin['path'])
            for phase, pin in self.protocol['initialCheckpoints'].items()})

    def test_missing_first_phase_carry_is_rejected(self):
        del self.protocol['initialCheckpoints']['card']
        with self.assertRaisesRegex(ValueError, 'both original'):
            trial.previous_checkpoints(self.protocol, self.root/'batches/batch-01', 1)

    def test_second_batch_uses_its_actual_predecessor(self):
        actual = trial.previous_checkpoints(self.protocol, self.root/'batches/batch-02', 2)
        self.assertEqual(actual, {phase: self.root/f'batches/batch-01/numeric/pair/{phase}/checkpoint.json'
                                 for phase in data.PHASES})

    def test_legacy_first_batch_still_starts_fresh(self):
        self.assertIsNone(old_trial.previous_checkpoints(self.root/'batches/batch-01', 1))

    def test_all512_epoch_order_seeds_are_assigned_once(self):
        actual = [seed for index in range(1, 65) for config in data.configs(self.protocol, index).values()
                  for seed in range(config['seed'], config['seed'] + 4)]
        self.assertEqual(actual, self.protocol['banks']['updates']['seeds'])

    def test_existing_recipe_math_and_first32_scheduling_are_unchanged(self):
        self.assertTrue(all(data.configs(self.protocol, index) == old_data.configs(self.protocol, index)
                            for index in range(1, 33)))

    def test_new_collection_rivals_keep_both_fixed_g3_heads(self):
        source = data.collection_source(self.protocol, 64, 'evolved-pair')
        self.assertEqual(source['bundle'], 'evolved-pair')
        self.assertEqual(source['opponents'], ['G3'])
        self.assertEqual(source['sharedPurchaseBaseline'], 'F0')
        self.assertEqual(source['expansionImitation'], dict(candidateModel='E', opponentModels=['E']))
        self.assertEqual(source['tokenOrder'], dict(candidateSelector='FIXED_16_TO_15',
                                                   opponentSelectors=['FIXED_16_TO_15']))

    def test_all64_historical_origins_follow_fixed_balanced_schedule(self):
        self.assertEqual([data.replay_index(i) for i in range(1, 65)], [0, 85, 170, 255] * 16)

    def test_one_head_accepting_resets_joint_stall(self):
        result = trial.next_state(self.state(8, 3), dict(card=0, purchase=1))
        self.assertEqual(result['consecutiveZeroAcceptedBatches'], 0)
        self.assertIsNone(result['stopReason'])

    def test_four_both_head_zeros_stop_the_recipe(self):
        result = trial.next_state(self.state(19, 3), dict(card=0, purchase=0))
        self.assertEqual(result['stopReason'], 'four-consecutive-zero-accepted-batches')
        self.assertEqual(trial.eligible_checkpoints(result), [16])

    def test_batch64_ends_the_fixed_budget(self):
        result = trial.next_state(self.state(63), dict(card=1, purchase=4))
        self.assertEqual(result['stopReason'], 'all64-batches-complete')
        self.assertEqual(trial.eligible_checkpoints(result), [16, 32, 64])

    def test_stopped_recipe_cannot_take_another_batch(self):
        state = dict(self.state(4, 4), stopReason='four-consecutive-zero-accepted-batches')
        with self.assertRaisesRegex(ValueError, 'running continuation'):
            trial.next_state(state, dict(card=1, purchase=1))

    def test_live_checkpoint_cannot_enter_a_screen(self):
        with self.assertRaisesRegex(ValueError, 'scientific termination'):
            trial.eligible_checkpoints(self.state(32))

    def test_early_stall_has_no_arbitrary_terminal_fallback(self):
        state = dict(self.state(12, 4), stopReason='four-consecutive-zero-accepted-batches')
        self.assertEqual(trial.eligible_checkpoints(state), [])

    def test_nominee_requires_both_positive_means(self):
        self.assertIsNone(data.select_checkpoint({16: self.metric(.2, 0), 32: self.metric(-.1, .2)}))

    def test_nominee_uses_win_mean_then_later_checkpoint(self):
        self.assertEqual(data.select_checkpoint({16: self.metric(.2, .9),
            32: self.metric(.1, .9), 64: self.metric(.2, .01)}), 64)

    def test_unprescribed_checkpoint_cannot_be_selected(self):
        with self.assertRaisesRegex(ValueError, 'prescribed'):
            data.select_checkpoint({40: self.metric()})

    def test_two_chunks_preserve_all_common_worlds_and_global_role_schedule(self):
        seeds = []
        for chunk in range(2):
            source = data.arena_source(self.protocol, 'checkpoint', chunk)
            seeds.extend(exp.arena_seeds(source['seed'], 512))
            for local in range(512):
                global_index = chunk * 512 + local
                self.assertEqual(local % 4, global_index % 4)
                self.assertEqual([(local // 4 + seat) % 4 for seat in range(4)],
                                 [(global_index // 4 + seat) % 4 for seat in range(4)])
        self.assertEqual(seeds, self.protocol['banks']['screen']['seeds'])

    def test_screen_roles_keep_g3_for_incumbent_and_every_rival(self):
        source = data.arena_source(self.protocol, 'checkpoint', 0)
        self.assertEqual(source['jointPolicy'], dict(baseline='F0', sharedBundle='G3',
                         candidateBundle='checkpoint', incumbentBundle='G3'))
        self.assertEqual(source['expansionImitation']['opponentModels'], ['E'] * 4)
        self.assertEqual(source['tokenOrder']['opponentSelectors'], ['FIXED_16_TO_15'] * 4)
        self.assertNotIn('frozenPurchase', source)

    def test_replay_indices_cover_both_chunks_and_every_seat(self):
        self.assertEqual([divmod(i, 512) for i in data.REPLAYS], [(0, 0), (0, 341), (1, 170), (1, 511)])
        self.assertEqual([i % 4 for i in data.REPLAYS], [0, 1, 2, 3])

    def test_common_incumbent_allows_candidate_changes(self):
        first = self.rows(); second = deepcopy(first)
        second[0]['candidate']['decisions'] += 1
        data.common_incumbent({16: first, 32: second})

    def test_common_incumbent_rejects_changed_baseline_game(self):
        first = self.rows(); second = deepcopy(first)
        second[0]['incumbent']['decisions'] += 1
        with self.assertRaisesRegex(ValueError, 'reproduce exactly'):
            data.common_incumbent({16: first, 32: second})

    def test_common_incumbent_rejects_changed_world_identity(self):
        first = self.rows(); second = deepcopy(first)
        second[0]['seed'] += 1
        with self.assertRaisesRegex(ValueError, 'reproduce exactly'):
            data.common_incumbent({16: first, 32: second})

    def test_learning_and_screen_campaigns_have_separate_single_attempt_bounds(self):
        for mode, seconds in (('learn', 21600), ('screens', 7200)):
            protocol = dict(mode=mode, maximumSeconds=seconds, maximumDataBytes=8 * exp.GIB,
                maximumRssBytes=8 * exp.GIB, minimumFreeBytes=8 * exp.GIB, python=dict(executable='/python'))
            actual = trial.campaign_spec(self.root, protocol, {})
            self.assertEqual(actual['limits']['maximumSeconds'], seconds)
            self.assertEqual([(s['id'], s['maximumSeconds'], s['maximumAttempts']) for s in actual['stages']],
                             [(mode, seconds, 1)])

    def test_failed_learning_admission_cannot_start_native_screen(self):
        with patch.object(trial, 'admitted_checkpoints', side_effect=ValueError('not audited')):
            with patch.object(exp, 'native') as native:
                with self.assertRaisesRegex(ValueError, 'not audited'):
                    trial.final_screens({}, self.root/'protocol.json', self.root)
        native.assert_not_called()
        self.assertFalse((self.root/'screens').exists())

    def test_stage_receipt_excludes_mutable_root_controller_files(self):
        (self.root/'campaign-state.json').write_text('mutable')
        protocol = self.root/'protocol.json'; exp.document(protocol, {})
        with patch.object(trial, 'admitted_checkpoints', return_value=({}, {})):
            trial.final_screens({}, protocol, self.root)
        result = exp.read(self.root/'results/screens.json')
        self.assertNotIn('campaign-state.json', result['artifacts'])
        self.assertEqual(exp.read(self.root/'screen-report.json')['nominee'], None)

    def carry_fixture(self):
        run = self.root/'original/run'; batch = run/'batches/batch-32'
        artifacts, heads, checkpoints = {}, {}, {}
        for phase in data.PHASES:
            directory = batch/f'numeric/pair/{phase}'
            for name in ('policy', 'checkpoint', 'finished', 'identity'):
                path = directory/(name + '.json')
                exp.document(path, dict(unit=phase, artifact=name))
                artifacts[str(path.relative_to(batch))] = exp.sha(path)
            heads[phase] = dict(sha256=exp.sha(directory/'policy.json'))
            checkpoints[phase] = dict(path=str(directory/'checkpoint.json'),
                                     sha256=exp.sha(directory/'checkpoint.json'))
        receipt_path = batch/'receipt.json'
        exp.document(receipt_path, dict(completed=True, batch=32, arm='chronological', artifacts=artifacts))
        report_path = run/'arm-report.json'
        exp.document(report_path, dict(portableBundle=dict(sha256=data.qualified.CANDIDATE),
            batches=[dict(index=32, path=str(receipt_path), sha256=exp.sha(receipt_path))]))
        review_path = self.root/'review.json'
        exp.document(review_path, dict(passed=True, arm='chronological',
            terminalBundleSha256=data.qualified.CANDIDATE, receiptChainVerified=32,
            scientificTerminalGames=8192, historicalReproductions=32, screenAdmitted=True,
            armReportSha256=exp.sha(report_path)))
        protocol = dict(self.protocol, initialCheckpoints=checkpoints,
                        initialCarryReview=dict(path=str(review_path), sha256=exp.sha(review_path)))
        return protocol, heads

    def test_initial_carry_follows_reviewed_report_receipt_and_phase_files(self):
        protocol, heads = self.carry_fixture()
        with patch.object(data, 'CARRY_REVIEW', protocol['initialCarryReview']['sha256']), \
             patch.object(data, 'INITIAL_CHECKPOINTS', {p: v['sha256'] for p, v in protocol['initialCheckpoints'].items()}), \
             patch.object(old_data, 'bundle_subjects', return_value=heads):
            result = data.initial_carry_subjects(protocol)
        self.assertEqual(result, {phase: Path(value['path']) for phase, value in protocol['initialCheckpoints'].items()})

    def test_changed_original_checkpoint_is_rejected_before_numeric_use(self):
        protocol, heads = self.carry_fixture()
        frozen = {p: v['sha256'] for p, v in protocol['initialCheckpoints'].items()}
        checkpoint = Path(protocol['initialCheckpoints']['card']['path'])
        checkpoint.write_text('{}')
        protocol['initialCheckpoints']['card']['sha256'] = exp.sha(checkpoint)
        with patch.object(data, 'CARRY_REVIEW', protocol['initialCarryReview']['sha256']), \
             patch.object(data, 'INITIAL_CHECKPOINTS', frozen), \
             patch.object(old_data, 'bundle_subjects', return_value=heads):
            with self.assertRaisesRegex(ValueError, 'actual final53 Adam'):
                data.initial_carry_subjects(protocol)

    def test_changed_carry_identity_is_rejected_by_original_receipt(self):
        protocol, heads = self.carry_fixture()
        Path(protocol['initialCheckpoints']['card']['path']).with_name('identity.json').write_text('{}')
        with patch.object(data, 'CARRY_REVIEW', protocol['initialCarryReview']['sha256']), \
             patch.object(data, 'INITIAL_CHECKPOINTS', {p: v['sha256'] for p, v in protocol['initialCheckpoints'].items()}), \
             patch.object(old_data, 'bundle_subjects', return_value=heads):
            with self.assertRaisesRegex(ValueError, 'carry sibling changed'):
                data.initial_carry_subjects(protocol)

    def test_initial_numerical_admission_failure_prevents_any_collection(self):
        self.protocol['numerical'] = dict(executable='/synthetic-python')
        with patch.object(trial.execution, 'run_child', side_effect=ValueError('carry rejected')):
            with patch.object(trial, 'train_batch') as batch:
                with self.assertRaisesRegex(ValueError, 'carry rejected'):
                    trial.train(self.protocol, self.root/'protocol.json', self.root)
        batch.assert_not_called()
        self.assertFalse((self.root/'batches').exists())

    def test_last_unprescribed_actor_is_not_exported_after_a_stall(self):
        self.protocol.update(numerical=dict(executable='/synthetic-python'), initialCarryReview={'unit': True})
        model = self.root/'unit-bundle.json'; exp.document(model, {'unit': True})
        self.protocol['initialBundle'] = str(model)
        protocol_path = self.root/'protocol.json'; exp.document(protocol_path, self.protocol)

        def batch(protocol, path, output, index, bundle):
            directory = trial.batch_directory(output, index)
            receipt = dict(acceptedEpochs=dict(card=0, purchase=0) if index > 16 else dict(card=1, purchase=1))
            exp.document(directory/'receipt.json', receipt)
            return model, receipt

        with patch.object(trial.execution, 'run_child'), patch.object(trial, 'train_batch', side_effect=batch), \
             patch.object(old_trial, 'export_terminal', return_value={'unit': True}) as export, \
             patch.object(exp, 'stage_result', return_value={}):
            trial.train(self.protocol, protocol_path, self.root)
        export.assert_called_once_with(model, self.root/'checkpoints/batch-16')
        report = exp.read(self.root/'learning-report.json')
        self.assertEqual(report['state']['completedBatches'], 20)
        self.assertEqual(set(report['checkpoints']), {'16'})

    def test_completed_chunk_keeps_exact_all512_pair_fields_and_source_identity(self):
        source = self.root/'source.json'; exp.document(source, data.arena_source(self.protocol, 'checkpoint', 0))
        native = self.root/'native'; native.mkdir()
        rows = self.rows()[:512]
        for index, row in enumerate(rows):
            exp.document(native/f'pair-{index}.json', dict(row, jointPolicy={'unit': True}))
        metrics = data.search.comparison(rows)
        exp.document(native/'evaluation.json', dict(complete=True, purpose='SCREENING', promoted=False,
            humanStrengthVerified=False, overall=metrics, byConfiguration={exp.CONFIGURATION: metrics}))
        exp.document(native/'run.json', dict(kind='strategy-arena', signature='UNIT', source=str(source),
            workers=10, pairs=512, seed=self.protocol['banks']['screen']['nativeOffset'], opponentModels=4))
        exp.document(native/'state.json', dict(kind='strategy-arena', signature='UNIT', phase='complete'))
        exp.document(native/'finished.json', dict(at='UNIT'))
        with patch.object(old_data, 'verify_joint_roles', return_value={'unit': True}), \
             patch.object(data.legacy, 'verify_transferred_sources'):
            checked = data.verify_chunk(self.protocol, source, native, 'checkpoint', 0)
        self.assertEqual(len(checked['rows']), 512)
        self.assertEqual(checked['rows'][1]['candidate']['scores'], [100, None, 50, 20])
        self.assertNotIn('jointPolicy', checked['rows'][0])
        self.assertEqual(checked['rows'][511]['pairSha256'], exp.sha(native/'pair-511.json'))


if __name__ == '__main__':
    unittest.main()
