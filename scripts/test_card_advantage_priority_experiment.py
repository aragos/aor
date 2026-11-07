"""Synthetic priority evaluation checks: no fit, native process or game outcomes are required."""
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import card_advantage_priority_experiment as priority
import card_advantage_experiment as experiment
from test_card_advantage_experiment import completed_collection


def put(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    experiment.document(path, value, immutable=False)


def screen_rows():
    seeds = list(range(256))
    rows = [dict(seed=seed, controlledSeat=index % 4, arm=arm,
                 terminal=dict(winShare=0., rankReward=0., reward=0.))
            for index, seed in enumerate(seeds) for arm in ('candidate', 'baseline')]
    return seeds, rows


class Fixture:
    def __init__(self, root):
        self.root = root.resolve()
        self.lab = self.root / 'priority'
        self.lab.mkdir()
        self.original = self.root / 'original'
        training = self.original / 'cycle-1'
        training.mkdir(parents=True)
        source = completed_collection(training)
        self.source = self.original / 'cycle-1-source.json'
        self.source.write_bytes(source.read_bytes())
        self.bin = self.root / 'runtime/bin'
        self.lib = self.root / 'runtime/lib'
        self.bin.mkdir(parents=True)
        self.lib.mkdir()
        for name in ('card_advantage_priority_experiment.py', 'card_advantage_experiment.py',
                     'card_advantage_evaluation.py', 'card-advantage-run.py', 'calibrate.py'):
            (self.bin / name).write_text('frozen ' + name)
        (self.lib / 'native.jar').write_text('native bytes')
        put(self.lab / 'inputs/baseline.json', {})
        put(self.lab / 'inputs/purchase.json', {})
        (self.lab / 'inputs/baseline.json').write_bytes((training / 'baseline.json').read_bytes())
        (self.lab / 'inputs/purchase.json').write_bytes((training / 'frozen-purchase-policy.json').read_bytes())
        put(self.lab / 'inputs/candidate.json', {'fixed': 'original action-priority'})
        self.candidate_sha = experiment.sha(self.lab / 'inputs/candidate.json')
        put(self.original / 'preflight.json', {'readyForCollection': True})
        put(self.original / 'cycle-1-fit/finished.json', {'models': {'action-priority': {'sha256': self.candidate_sha}}})
        compact = self.root / 'compact'
        put(compact / 'preflight.json', dict(readyForCollection=True,
            pins={str(self.original / 'preflight.json'): experiment.sha(self.original / 'preflight.json')}))
        put(compact / 'window-result.json', dict(disposition='compact-common-development-gate-failed',
            preflightSha256=experiment.sha(compact / 'preflight.json'), browserActivated=False))
        put(self.lab / 'inputs/frequency.json', {'fixed': 'frequency control'})
        put(self.lab / 'calibration.json', dict(candidateSha256=self.candidate_sha,
            collection=str(training), collectionCompletionSha256=experiment.sha(training / 'finished.json'),
            collectionSignature='fixed', split='development',
            controlSha256=experiment.sha(self.lab / 'inputs/frequency.json'),
            sourceHashes={str(self.bin / 'calibrate.py'): experiment.sha(self.bin / 'calibrate.py')},
            calibration={'outcomeUse': False, 'decisions': 12}))
        profile = self.root / 'profile'
        put(profile / 'protocol.json', dict(runtime=str(self.lib), workers=6, batchSizes=[1, 8],
            pins={str(self.lib / 'native.jar'): experiment.sha(self.lib / 'native.jar')}))
        put(profile / 'report.json', dict(passed=True, allPinsUnchanged=True,
            compressedRecordsExactlyEqual=64, oldRecordsExactlyEqualExceptSignature=64,
            exactOldPublicTerminalsAndSteps=64, newWorlds=0,
            protocolSha256=experiment.sha(profile / 'protocol.json')))
        put(self.lab / 'seeds.json', dict(passed=True, overlaps=[], banks=[
            {'name': priority.SCREEN, 'seeds': list(range(256))},
            {'name': 'primary-confirmation', 'seeds': list(range(1000,2024))},
            {'name': 'rival-robustness', 'seeds': list(range(3000,3256))}]))
        start = time.time() - 10
        iso = lambda value: datetime.fromtimestamp(value, timezone.utc).isoformat()
        put(self.lab / 'clock.json', dict(startedAt=iso(start), deadline=iso(start+10800),
            originalAllowanceSeconds=10800, renewOnResume=False,
            collectionAdmissionDeadline=iso(start+10800), latestNewCollection=iso(start)))
        self.pins = {str(path): experiment.sha(path) for path in [*self.bin.iterdir(), *self.lib.iterdir()]}
        def entry(path):
            self.pins[str(path)] = experiment.sha(path)
            return {'file': str(path), 'sha256': experiment.sha(path)}
        self.protocol = dict(kind='card-advantage-priority-window', schemaVersion=1,
            configuration='qualified-profile', clock=entry(self.lab / 'clock.json'),
            seedReservation=entry(self.lab / 'seeds.json'), originRequests={},
            screen={'worlds':256, 'minimumWinShareGain':.02, 'maximumSeconds':1800},
            evaluation={'batchSize':8, 'maximumSecondsPerStage':7200},
            resources={'minimumDiskFreeBytes':8*1024**3, 'maximumNewDataBytes':4*1024**3},
            prerequisites=dict(compactPreflight=entry(compact / 'preflight.json'),
                compactResult=entry(compact / 'window-result.json'),
                originalPreflight=entry(self.original / 'preflight.json'), trainingSource=entry(self.source),
                fitReport=entry(self.original / 'cycle-1-fit/finished.json'),
                candidate=entry(self.lab / 'inputs/candidate.json'),
                frequencyControl=entry(self.lab / 'inputs/frequency.json'),
                frequencyCalibration=entry(self.lab / 'calibration.json'),
                batchProfile=entry(profile / 'report.json'), batchProfileProtocol=entry(profile / 'protocol.json')))
        self.save()

    def save(self):
        put(self.lab / 'protocol.json', self.protocol)
        put(self.lab / 'preflight.json', dict(readyForCollection=True,
            windowSha256=experiment.sha(self.lab / 'protocol.json'), pins=self.pins))

    def change(self, name, update):
        entry = self.protocol['prerequisites'][name]
        path = Path(entry['file'])
        value = experiment.read(path)
        update(value)
        put(path, value)
        entry['sha256'] = self.pins[str(path)] = experiment.sha(path)
        self.save()

    def window(self):
        with patch.object(experiment, '__file__', str(self.bin / 'card_advantage_experiment.py')), \
                patch.object(priority, 'CANDIDATE_SHA256', self.candidate_sha):
            return priority.PriorityWindow(self.lab, self.lab / 'protocol.json')


class ScreenTest(unittest.TestCase):
    def test_source_is_exact_paired_rotating_role_grid(self):
        source = priority.screen_source({}, list(range(256)), Path('/fixed-candidate'))
        self.assertEqual(512, len(source['jobs']))
        for index, job in enumerate(source['jobs']):
            self.assertEqual(index, job['index'])
            self.assertEqual(index // 2, job['seed'])
            self.assertEqual((index // 2) % 4, job['seat'])
            self.assertEqual(['baseline'] * 4, job['rivals'])
            self.assertEqual(('candidate', 'baseline')[index % 2], job['arm'])
        self.assertEqual({'kind':'advantage', 'path':'/fixed-candidate'}, source['policies']['candidate'])

    def test_two_point_gain_can_continue_without_positive_interval(self):
        seeds, rows = screen_rows()
        for i, row in enumerate(rows):
            win = float((i // 2 < 60 and row['arm']=='candidate')
                        or (60 <= i // 2 < 114 and row['arm']=='baseline'))
            row['terminal'] = dict(winShare=win, rankReward=win, reward=win)
        report = priority.analyze_screen(seeds, rows)
        self.assertEqual(6/256, report['comparisons']['winShare']['mean'])
        self.assertLess(report['comparisons']['winShare']['lower95'], 0)
        self.assertTrue(report['mayContinue'])
        self.assertFalse(report['qualification'])

    def test_below_two_points_fails_despite_positive_interval(self):
        seeds, rows = screen_rows()
        for row in rows:
            win = float(row['seed']<5 and row['arm']=='candidate')
            row['terminal'] = dict(winShare=win, rankReward=win, reward=win)
        report = priority.analyze_screen(seeds, rows)
        self.assertGreater(report['comparisons']['winShare']['lower95'], 0)
        self.assertFalse(report['mayContinue'])

    def test_missing_duplicate_and_wrong_role_records_are_rejected(self):
        seeds, rows = screen_rows()
        for broken in (rows[:-1], rows[:-1]+rows[:1], [dict(rows[0], controlledSeat=3)]+rows[1:]):
            with self.subTest(broken=broken[-1]):
                with self.assertRaisesRegex(ValueError, 'exactly once'):
                    priority.analyze_screen(seeds, broken)

    def test_nonfinite_boolean_or_inconsistent_rewards_are_rejected(self):
        seeds, rows = screen_rows()
        for terminal in (dict(winShare=float('nan'),rankReward=0,reward=0),
                         dict(winShare=True,rankReward=0,reward=.8),
                         dict(winShare=1,rankReward=0,reward=1)):
            with self.subTest(terminal=terminal), self.assertRaises(ValueError):
                priority.analyze_screen(seeds, [dict(rows[0],terminal=terminal)]+rows[1:])

    def test_shared_stage_registry_is_unchanged(self):
        self.assertNotIn(priority.SCREEN, priority.evaluation.STAGES)
        self.assertEqual(128, priority.evaluation.STAGES['p1-screen'][0])


class PriorityDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.temp.name))
    def tearDown(self):
        self.temp.cleanup()

    def test_complete_prerequisites_validate_without_execution(self):
        window = self.fixture.window()
        self.assertEqual(self.fixture.candidate_sha, experiment.sha(window.candidate))
        self.assertFalse((window.lab / 'window-status.json').exists())

    def test_other_compact_disposition_does_not_authorize_experiment(self):
        self.fixture.change('compactResult', lambda value: value.update(disposition='qualified-for-integration'))
        with self.assertRaisesRegex(ValueError, 'failed compact development'):
            self.fixture.window()

    def test_changed_candidate_even_with_revised_pin_is_rejected(self):
        self.fixture.change('candidate', lambda value: value.update(different=True))
        with self.assertRaisesRegex(ValueError, 'exact existing C1'):
            self.fixture.window()

    def test_completed_collection_model_identity_must_match_new_games(self):
        put(self.fixture.lab / 'inputs/purchase.json', {'changed':True})
        with self.assertRaisesRegex(ValueError, 'purchase32'):
            self.fixture.window()

    def test_frequency_calibration_must_use_development_and_same_candidate(self):
        self.fixture.change('frequencyCalibration', lambda value: value.update(split='validation'))
        with self.assertRaisesRegex(ValueError, 'development inputs only'):
            self.fixture.window()

    def test_calibration_source_changes_reject_before_games(self):
        (self.fixture.bin / 'calibrate.py').write_text('different')
        with self.assertRaisesRegex(ValueError, 'Frozen preflight/runtime'):
            self.fixture.window()

    def test_changed_native_runtime_cannot_reuse_profile(self):
        path = self.fixture.lib / 'native.jar'
        path.write_text('different runtime')
        self.fixture.pins[str(path)] = experiment.sha(path)
        self.fixture.save()
        with self.assertRaisesRegex(ValueError, 'byte-identical'):
            self.fixture.window()

    def test_three_hour_clock_cannot_be_extended(self):
        path = self.fixture.lab / 'clock.json'
        clock = experiment.read(path); clock['originalAllowanceSeconds'] = 14400
        put(path, clock)
        self.fixture.protocol['clock']['sha256'] = self.fixture.pins[str(path)] = experiment.sha(path)
        self.fixture.save()
        with self.assertRaisesRegex(ValueError, 'three-hour clock'):
            self.fixture.window()

    def test_failed_screen_stops_before_any_confirmation_or_finalist(self):
        window = self.fixture.window()
        with patch.object(window, 'screen', return_value={'mayContinue':False}), \
                patch.object(window, 'evaluate') as evaluate:
            result = window.run()
        evaluate.assert_not_called()
        self.assertEqual('priority-screen-gate-failed', result['disposition'])
        self.assertFalse((window.lab / 'finalist.json').exists())

    def test_both_independent_gates_run_on_same_frozen_candidate_without_activation(self):
        window = self.fixture.window()
        with patch.object(window, 'screen', return_value={'mayContinue':True}), \
                patch.object(window, 'evaluate', side_effect=[{'passed':False},{'passed':True}]) as evaluate:
            result = window.run()
        self.assertEqual(['primary-confirmation','rival-robustness'], [c.args[0] for c in evaluate.call_args_list])
        self.assertEqual(window.candidate, evaluate.call_args_list[0].args[1]['candidate'])
        self.assertEqual(window.frequency, evaluate.call_args_list[0].args[1]['frequency'])
        self.assertEqual('independent-qualification-failed', result['disposition'])
        self.assertFalse(result['browserActivated'])

    def test_screen_has_fixed_thirty_minute_native_cap(self):
        window = self.fixture.window()
        seeds, rows = screen_rows()
        with patch.object(window, 'native', return_value=window.lab / priority.SCREEN) as native, \
                patch.object(priority.evaluation, 'load_completed', return_value=(rows, {})):
            window.screen()
        self.assertEqual(1800, native.call_args.args[3])
        self.assertEqual(seeds, window.banks[priority.SCREEN])

    def test_incomplete_screen_never_opens_terminal_records(self):
        window = self.fixture.window()
        with patch.object(window, 'native', side_effect=RuntimeError('partial')), \
                patch.object(priority.evaluation, 'load_completed') as load:
            with self.assertRaisesRegex(RuntimeError, 'partial'):
                window.screen()
        load.assert_not_called()


if __name__ == '__main__':
    unittest.main()
