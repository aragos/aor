"""Synthetic contracts only: never invokes a native game, reads a human save or fits a model."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import cathedral_native_screen as screen


class CathedralScreenTest(unittest.TestCase):
    def protocol(self):
        return dict(baseline='/m8', expansionModel='/em8', purchasePolicy='/g2', prior='/f0',
            opponents=['/m8']*4, experimentalRulesTransfer=screen.legacy.RULES_TRANSFER,
            banks={'screen': {'nativeOffset': 50000000}}, historical={'source': {}, 'rows': []})

    def replay(self, command='attack florence'):
        return dict(index=0, configuration='fixture', seed=42, seat=0, side='CANDIDATE',
            opponentIndices=[0,1,2,3], game={key: [] for key in screen.search.GAME_FIELDS},
            decisions=[{'chosen': {'command': command},
                        'snapshot': {'number': 7, 'decision': 'expand', 'public': 'fixture'}}])

    def metrics(self, count=1024, lower=.01):
        return {key: dict(count=count, mean=.02, lower95=lower, upper95=.03)
                for key in ('winShareDifference', 'rewardDifference')}

    def result(self, **changes):
        value = dict(command='pass', activated=True, rootValues={'pass': 0., 'attack florence': -.1},
            legalNonpassRoots=20, retainedNonpassRoots=16, truncatedFollowupOptions=3,
            resolvedLeaves=42, stopEvaluations=5, responseQueries=20, firstActionVictoriesStopped=2)
        return dict(value, **changes)

    def diagnostic(self, **changes):
        replay = self.replay('pass')
        replay['cathedralDecisions'] = [dict(number=7, incumbent='attack florence',
                                           result=self.result(**changes))]
        return replay

    def ready(self, output, protocol):
        rows, receipts, artifacts = [], [], []
        for index in screen.HISTORICAL:
            reference = self.replay(); reference['index'] = index
            reference_path = output/f'reference-{index}.json'; screen.document(reference_path, reference)
            actual_path = output/f'historical/{index}-candidate/replay.json'
            screen.document(actual_path, reference); artifacts.append(actual_path)
            rows.append(dict(index=index, referenceReplay={
                'path':str(reference_path),'sha256':screen.sha(reference_path)}))
            receipts.append(dict(index=index, side='candidate', terminalExact=True, controlledCommandsExact=True,
                referenceReplaySha256=screen.sha(reference_path), replaySha256=screen.sha(actual_path),
                controlledCommandsSha256=screen.compare_historical(reference,reference), controlledDecisions=1))
        protocol['historical']['rows'] = rows
        path = output/'protocol.json'; screen.document(path, protocol)
        report = dict(protocolSha256=screen.sha(path), historical=protocol['historical'],
            historicalTerminalReproductions=2, newWorlds=0, replays=receipts)
        screen.document(output/'historical-report.json', report)
        screen.exp.stage_result(output, 'historical', True, 'synthetic', report,
                                [output/'historical-report.json'] + artifacts)
        screen.document(output/'campaign-state.json', {'stages': {'historical': {
            'status': 'passed', 'resultSha256': screen.sha(output/'results/historical.json')}}})
        return path

    def historical_fixture(self):
        protocol = self.protocol()
        source = screen.arena_source(protocol)
        del source['cathedralTactics']
        source.update(seed=47000088, pairsPerConfiguration=4096, purpose='CONFIRMATION')
        source['frozenPurchase'].update(policy='/g1', incumbentPolicy='/g1')
        objects = {'/original-source': source}
        hashes = {'/original-source': screen.HISTORICAL_SOURCE, '/audit': screen.AUDIT,
                  '/m8': screen.matched.ORDINARY, '/em8': screen.matched.EXPANSION,
                  '/g2': screen.G2, '/g1': screen.G1, '/f0': screen.data.F0}
        rows = []
        artifact_hashes = {'/original-source': screen.HISTORICAL_SOURCE}
        for index in screen.HISTORICAL:
            replay = self.replay(); replay.update(index=index, replayVerified=True, historicalPairVerified=True)
            pair = {key: replay[key] for key in ('index','configuration','seed','seat','opponentIndices')}
            pair['candidate'] = replay['game']
            pair_path, replay_path = f'/pair-{index}', f'/replay-{index}'
            objects.update({pair_path: pair, replay_path: replay})
            hashes.update({pair_path: f'{index+1:064x}', replay_path: f'{index+2:064x}'})
            artifact_hashes.update({pair_path: hashes[pair_path], replay_path: hashes[replay_path]})
            rows.append(dict(index=index, pair={'path': pair_path, 'sha256': hashes[pair_path]},
                referenceReplay={'path': replay_path, 'sha256': hashes[replay_path]}))
        objects['/audit'] = dict(passed=True, qualificationPassed=True, candidateSha256=screen.G2,
                               status='completed', artifactSha256=artifact_hashes)
        protocol.update(referenceAudit={'path':'/audit','sha256':screen.AUDIT},
            historical={'source': {'path':'/original-source','sha256':screen.HISTORICAL_SOURCE}, 'rows': rows})
        return protocol, objects, hashes

    def validated_protocol(self, directory):
        protocol = self.protocol()
        protocol.update(kind=screen.KIND, nativeSourceCommit=screen.NATIVE, workers=10,
            maximumSeconds=3600, maximumAttempts=1, maximumScientificTerminalGames=2048,
            maximumHistoricalTerminalReproductions=10, maximumTerminalExecutions=2058,
            diagnosticConfidence=.95, maximumFits=0, productionActivation=False,
            automaticNextCampaign=False, reselectionAllowed=False,
            replayIndices=list(screen.REPLAYS), configuration=screen.exp.CONFIGURATION,
            runtime=str(directory), seedAudit='/seed-audit', nativeReceipt={'path':'/receipt','sha256':screen.RECEIPT},
            sourceReview={'path':'/review','sha256':screen.REVIEW})
        protocol['banks']['screen'].update(count=1024, seeds=screen.exp.arena_seeds(50000000,1024))
        self.input_objects = {
            '/receipt': {'sourceCommit': screen.NATIVE, 'humanDataIncluded': False,
                         'nativeGate': {'cathedralTactics': screen.GATE}, 'runtimePins': {}},
            '/review': {},
            '/seed-audit': {'allIntersectionsZero': True, 'seedBanks': copy.deepcopy(protocol['banks'])}}
        self.input_hashes = {'/receipt':screen.RECEIPT, '/review':screen.REVIEW,
            '/seed-audit':screen.SEEDS, '/m8':screen.matched.ORDINARY, '/em8':screen.matched.EXPANSION,
            '/g2':screen.G2, '/f0':screen.data.F0}
        return protocol

    def validate(self, protocol):
        with patch.object(screen, 'sha', side_effect=lambda p:self.input_hashes[str(p)]), \
             patch.object(screen, 'read', side_effect=lambda p:self.input_objects[str(p)]), \
             patch.object(screen, 'historical_inputs'):
            screen.validate_inputs(protocol)

    def test_exact_frozen_protocol_passes_without_games(self):
        with tempfile.TemporaryDirectory() as directory:
            self.validate(self.validated_protocol(Path(directory).resolve()))

    def test_boolean_attempt_count_cannot_equal_one(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = self.validated_protocol(Path(directory).resolve()); protocol['maximumAttempts'] = True
            with self.assertRaisesRegex(ValueError, 'fixed1024-pair'):
                self.validate(protocol)

    def test_replay_indices_cannot_change(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = self.validated_protocol(Path(directory).resolve()); protocol['replayIndices'][-1] = 1022
            with self.assertRaisesRegex(ValueError, 'fixed1024-pair'):
                self.validate(protocol)

    def test_runtime_must_match_the_implementation_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = self.validated_protocol(Path(directory).resolve())
            self.input_objects['/receipt']['runtimePins'] = {'missing.jar':'0'*64}
            with self.assertRaisesRegex(ValueError, 'native runtime differs'):
                self.validate(protocol)

    def test_earlier_purchase_actor_cannot_replace_g2_science(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = self.validated_protocol(Path(directory).resolve()); self.input_hashes['/g2'] = screen.G1
            with self.assertRaisesRegex(ValueError, 'same raw G2'):
                self.validate(protocol)

    def test_different_bank_is_not_accepted_even_with_matching_audit_data(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = self.validated_protocol(Path(directory).resolve())
            protocol['banks']['screen']['nativeOffset'] += 1
            self.input_objects['/seed-audit']['seedBanks'] = copy.deepcopy(protocol['banks'])
            with self.assertRaisesRegex(ValueError, 'predeclared family50'):
                self.validate(protocol)

    def test_new_arena_changes_only_candidate_wrapper(self):
        source = screen.arena_source(self.protocol())
        self.assertEqual(screen.GATE, source['cathedralTactics'])
        self.assertEqual('/m8', source['candidate']); self.assertEqual('/m8', source['incumbent'])
        self.assertEqual(['/em8']*4, source['expansionImitation']['opponentModels'])
        self.assertEqual([screen.data.FIXED]*4, source['tokenOrder']['opponentSelectors'])
        self.assertEqual(dict(baseline='/f0',policy='/g2',candidatePolicy='/g2',incumbentPolicy='/g2'),
                         source['frozenPurchase'])
        self.assertEqual(1024, source['pairsPerConfiguration'])

    def test_historical_roles_preserve_original_g1_rivals(self):
        protocol, objects, hashes = self.historical_fixture()
        with patch.object(screen, 'read', side_effect=lambda p: objects[str(p)]), \
             patch.object(screen, 'sha', side_effect=lambda p: hashes[str(p)]):
            source = screen.historical_inputs(protocol)
        self.assertEqual('/g1', source['frozenPurchase']['policy'])
        self.assertNotIn('cathedralTactics', source)

    def test_historical_input_not_in_terminal_audit_is_rejected(self):
        protocol, objects, hashes = self.historical_fixture()
        del objects['/audit']['artifactSha256']['/replay-4095']
        with patch.object(screen, 'read', side_effect=lambda p: objects[str(p)]), \
             patch.object(screen, 'sha', side_effect=lambda p: hashes[str(p)]):
            with self.assertRaisesRegex(ValueError, 'pinned by the qualified47'):
                screen.historical_inputs(protocol)

    def test_historical_source_cannot_upgrade_rivals(self):
        protocol, objects, hashes = self.historical_fixture()
        objects['/original-source']['frozenPurchase']['policy'] = '/g2'
        with patch.object(screen, 'read', side_effect=lambda p: objects[str(p)]), \
             patch.object(screen, 'sha', side_effect=lambda p: hashes[str(p)]):
            with self.assertRaisesRegex(ValueError, 'original G1 rivals'):
                screen.historical_inputs(protocol)

    def test_historical_rows_cannot_be_reselected(self):
        protocol, objects, hashes = self.historical_fixture()
        protocol['historical']['rows'][1]['index'] = 341
        with patch.object(screen, 'read', side_effect=lambda p: objects[str(p)]), \
             patch.object(screen, 'sha', side_effect=lambda p: hashes[str(p)]):
            with self.assertRaisesRegex(ValueError, 'two predeclared'):
                screen.historical_inputs(protocol)

    def test_historical_command_mismatch_rejects_equal_terminal(self):
        with self.assertRaisesRegex(ValueError, 'canonical command'):
            screen.compare_historical(self.replay('pass'), self.replay())

    def test_historical_terminal_mismatch_rejects_equal_commands(self):
        changed = self.replay(); changed['game']['wins'] = [1]
        with self.assertRaisesRegex(ValueError, 'terminal array'):
            screen.compare_historical(changed, self.replay())

    def test_new_observation_transport_does_not_break_canonical_regression(self):
        reference, actual = self.replay(), self.replay()
        actual['decisions'][0]['snapshot']['newPublicField'] = {'typed': True}
        self.assertEqual(64, len(screen.compare_historical(actual, reference)))

    def test_exact_completed_historical_receipt_allows_screen_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); protocol = self.protocol(); path = self.ready(output, protocol)
            screen.historical_ready(protocol, path, output)

    def test_tampered_report_is_rejected_by_completed_artifact_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); protocol = self.protocol(); path = self.ready(output, protocol)
            report = screen.read(output/'historical-report.json'); report['newWorlds'] = 1
            (output/'historical-report.json').write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'artifact changed'):
                screen.historical_ready(protocol, path, output)

    def test_historical_flags_without_replay_artifacts_cannot_unlock_screen(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); protocol = self.protocol(); path = self.ready(output, protocol)
            result_path = output/'results/historical.json'; result = screen.read(result_path)
            result['artifacts'] = {'historical-report.json': screen.sha(output/'historical-report.json')}
            result_path.write_text(json.dumps(result))
            state_path = output/'campaign-state.json'; state = screen.read(state_path)
            state['stages']['historical']['resultSha256'] = screen.sha(result_path)
            state_path.write_text(json.dumps(state))
            with self.assertRaisesRegex(ValueError, 'not flags alone'):
                screen.historical_ready(protocol, path, output)

    def test_screen_refuses_incomplete_history_before_source_or_native(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve()
            with patch.object(screen, 'historical_ready', side_effect=ValueError('incomplete history')), \
                 patch.object(screen.exp, 'native') as native:
                with self.assertRaisesRegex(ValueError, 'incomplete history'):
                    screen.screen(self.protocol(), output/'protocol.json', output)
            native.assert_not_called(); self.assertFalse((output/'screen-source.json').exists())

    def test_second_screen_attempt_refuses_native(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); (output/'screen-source.json').touch()
            with patch.object(screen, 'historical_ready'), patch.object(screen.exp, 'native') as native:
                with self.assertRaisesRegex(ValueError, 'one attempt'):
                    screen.screen(self.protocol(), output/'protocol.json', output)
            native.assert_not_called()

    def test_second_historical_attempt_refuses_native(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); (output/'historical').mkdir()
            with patch.object(screen, 'historical_inputs'), patch.object(screen, 'run_replay') as native:
                with self.assertRaisesRegex(ValueError, 'one complete attempt'):
                    screen.historical(self.protocol(), output/'protocol.json', output)
            native.assert_not_called()

    def test_replay_attempt_refuses_existing_partial_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(screen.ecology, 'run_child') as native:
                with self.assertRaisesRegex(ValueError, 'one attempt'):
                    screen.run_replay({}, Path('/s'), Path('/p'), Path(directory).resolve(), 0, 'candidate', cathedral=True)
            native.assert_not_called()

    def test_unknown_stage_refused_before_validation_or_execution(self):
        with patch.object(screen, 'validate_inputs') as validate:
            with self.assertRaisesRegex(ValueError, 'Only historical'):
                screen.stage({}, Path('/p'), Path('/o'), 'retry')
        validate.assert_not_called()

    def test_both_95_bounds_required_for_diagnostic_signal(self):
        self.assertTrue(screen.diagnostic_signal(self.metrics()))
        metrics = self.metrics(); metrics['rewardDifference']['lower95'] = 0.
        self.assertFalse(screen.diagnostic_signal(metrics))

    def test_partial_bank_cannot_have_diagnostic_signal(self):
        self.assertFalse(screen.diagnostic_signal(self.metrics(count=1023)))

    def test_candidate_diagnostics_count_actual_pass_override(self):
        result = screen.replay_diagnostics(self.diagnostic())
        self.assertEqual(1, result['activations']); self.assertEqual(1, result['attackToPass'])
        self.assertEqual(4, result['truncatedRoots']); self.assertEqual(3, result['truncatedFollowups'])

    def test_candidate_trace_cannot_omit_actual_expansion(self):
        replay = self.diagnostic(); replay['cathedralDecisions'] = []
        with self.assertRaisesRegex(ValueError, 'every actual expansion'):
            screen.replay_diagnostics(replay)

    def test_incumbent_trace_must_be_empty(self):
        replay = self.diagnostic(); replay['side'] = 'INCUMBENT'
        with self.assertRaisesRegex(ValueError, 'candidate-only selector'):
            screen.replay_diagnostics(replay)

    def test_trace_cannot_claim_a_different_applied_choice(self):
        replay = self.diagnostic(); replay['decisions'][0]['chosen']['command'] = 'attack florence'
        with self.assertRaisesRegex(ValueError, 'actual expansion choice'):
            screen.replay_diagnostics(replay)

    def test_trace_rejects_search_cap_overrun(self):
        with self.assertRaisesRegex(ValueError, 'frozen bounds'):
            screen.replay_diagnostics(self.diagnostic(resolvedLeaves=6401))

    def test_inactive_trace_cannot_change_incumbent(self):
        with self.assertRaisesRegex(ValueError, 'disabled gate changed'):
            screen.replay_diagnostics(self.diagnostic(activated=False))

    def test_completed_negative_report_remains_a_nonqualifying_success(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); protocol = output/'protocol.json'; screen.document(protocol,{})
            (output/'screen-source.json').write_text('{}')
            result = screen.report_stage(protocol, output, 'screen',
                dict(diagnosticSignal=False, metrics=self.metrics(lower=-.03)), [])
            self.assertEqual('passed', result['status'])
            self.assertFalse(result['metrics']['qualificationPassed'])
            self.assertFalse(result['metrics']['productionActivation'])
            self.assertFalse(result['metrics']['automaticNextCampaign'])

    def test_report_does_not_pin_mutable_campaign_state(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve(); protocol = output/'protocol.json'; screen.document(protocol,{})
            (output/'campaign-state.json').write_text('{}')
            root = output/'historical'; root.mkdir(); (root/'receipt.json').write_text('{}')
            result = screen.report_stage(protocol, output, 'historical', {}, [root])
            self.assertEqual({'historical-report.json','historical/receipt.json'}, set(result['artifacts']))


if __name__ == '__main__':
    unittest.main()
