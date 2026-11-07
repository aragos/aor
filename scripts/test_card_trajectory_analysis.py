"""Independent evidence pairing, fixed gates and optimizer movement semantics."""
import gzip
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import card_trajectory_analysis as analysis
from joint_economy import encoded, sha
from test_joint_economy import terminal


def records(stage, win=lambda world, arm: False):
    seeds = list(range(1024 if stage == 'confirmation' else 256))
    rows = [dict(seed=seed, controlledSeat=index % 4, arm=arm,
                 terminal=terminal(index % 4, win(index, arm)))
            for index, seed in enumerate(seeds) for arm in analysis.ARMS]
    return seeds, rows


def native_fixture(root, stage, seeds, base, initial, candidate, priority):
    models = {'initial': initial} if stage == 'controls' else {'candidate': candidate}
    source = analysis.make_source(stage, seeds, models, base, priority)
    source_path = root / (stage + '.json')
    source_path.write_bytes(encoded(source))
    directory = root / stage
    directory.mkdir()
    (directory / 'source.json').write_bytes(source_path.read_bytes())
    manifest = dict(kind='card-advantage-evaluate', schemaVersion=1, signature='native-frozen',
                    cardActorVersion='aor-card-policy-1', trajectoryEncoding='gzip-json-v1',
                    source=str(source_path), sourceSha256=sha(source_path),
                    configuration=source['configuration'], jobs=source['jobs'], policies={})
    for key, snapshot in (('baseline', 'baseline.json'), ('frozenPurchasePolicy', 'frozen-purchase-policy.json')):
        path = Path(source[key])
        manifest[key] = dict(path=str(path), sha256=sha(path))
        (directory / snapshot).write_bytes(path.read_bytes())
    for name, row in source['policies'].items():
        path = Path(row['path']) if row.get('path') else None
        manifest['policies'][name] = dict(kind=row['kind'], path=str(path) if path else None,
                                         sha256=sha(path) if path else None)
        if path:
            (directory / ('policy-' + name + '.json')).write_bytes(path.read_bytes())
    (directory / 'collection.json').write_bytes(encoded(manifest))
    entries = []
    for job in source['jobs']:
        row = dict(schemaVersion=1, signature='native-frozen', index=job['index'], seed=job['seed'],
                   controlledSeat=job['seat'], arm=job['arm'], terminal=terminal(job['seat'], False))
        path = directory / f"game-{job['index']}.json.gz"
        path.write_bytes(gzip.compress(encoded(row)))
        entries.append(dict(index=job['index'], file=path.name, sha256=sha(path)))
    (directory / 'finished.json').write_bytes(encoded(dict(kind='card-advantage-evaluate',
        signature='native-frozen', games=len(entries), files=entries)))
    (directory / 'state.json').write_bytes(encoded(dict(phase='complete', signature='native-frozen')))
    return directory, source_path


def bank_fixture(root):
    root = root.resolve()
    for name in ('base', 'purchase', 'initial', 'candidate', 'priority'):
        (root / (name + '.json')).write_bytes(encoded({'identity': name}))
    base = dict(configuration='four', baseline=str(root / 'base.json'),
                frozenPurchasePolicy=str(root / 'purchase.json'))
    initial, candidate, priority = (root / (name + '.json') for name in ('initial', 'candidate', 'priority'))
    seeds = list(range(256))
    controls = native_fixture(root, 'controls', seeds, base, initial, candidate, priority)
    current = native_fixture(root, 'development', seeds, base, initial, candidate, priority)
    return seeds, *controls, *current, base, initial, candidate, priority


class TrajectoryAnalysisTest(unittest.TestCase):
    def test_development_requires_improvement_over_initial_and_priority(self):
        seeds, rows = records('development', lambda world, arm: arm in ('candidate', 'priority64') and world < 32)
        result = analysis.analyze('development', seeds, rows)
        self.assertGreater(result['versus']['f']['pooled']['winShare']['mean'], .03)
        self.assertFalse(result['eligible'])

    def test_harm_uses_win_share_not_composite_or_rank(self):
        seeds, rows = records('development', lambda world, arm: arm == 'f' and world < 64)
        self.assertTrue(analysis.analyze('development', seeds, rows)['harmful'])

    def test_final_clear_gains_qualify_without_promotion(self):
        seeds, rows = records('confirmation', lambda world, arm: arm == 'candidate' and world < 128)
        report = analysis.analyze('confirmation', seeds, rows)
        self.assertTrue(report['passed'])
        self.assertFalse(report['policyPromotion'])

    def test_priority_tie_blocks_final_despite_large_gain_over_f(self):
        seeds, rows = records('confirmation', lambda world, arm: arm in ('candidate', 'priority64') and world < 128)
        report = analysis.analyze('confirmation', seeds, rows)
        self.assertTrue(report['gates']['minimumGainVsF'])
        self.assertFalse(report['gates']['lower95VsPriority'])
        self.assertFalse(report['passed'])

    def test_population_harm_blocks_pooled_success(self):
        seeds, rows = records('confirmation', lambda world, arm:
                             arm == 'candidate' if (world // 4) % 2 == 0 else arm == 'f' and world < 64)
        report = analysis.analyze('confirmation', seeds, rows)
        self.assertTrue(report['gates']['minimumGainVsF'])
        self.assertFalse(report['gates']['nonnegativePopulationMeans'])
        self.assertFalse(report['passed'])

    def test_bootstrap_preserves_population_composition_and_independent_count(self):
        values = np.array([[1. if (world // 4) % 2 == 0 else -1.] for world in range(256)])
        pooled, left, right = analysis.intervals(values)
        self.assertEqual((0., 0., 0.), tuple(pooled[0][key] for key in ('mean', 'lower95', 'upper95')))
        self.assertEqual(256, pooled[0]['independentWorlds'])
        self.assertEqual(128, left[0]['independentWorlds'])
        self.assertEqual(-1, right[0]['mean'])

    def test_incomplete_or_duplicate_arm_rejects(self):
        seeds, rows = records('development')
        for altered in (rows[:-1], rows[:-1] + rows[:1]):
            with self.assertRaisesRegex(ValueError, 'exactly once'):
                analysis.analyze('development', seeds, altered)

    def test_last_accepted_kl_survives_rejected_and_zero_epoch_carry(self):
        reports = [dict(inputModelSha256='a', outputModelSha256='b', acceptedEpochs=1,
                        history=[dict(epoch=1, accepted=True, priorKl=.02),
                                 dict(epoch=2, accepted=False, priorKl=.8)]),
                   dict(inputModelSha256='b', outputModelSha256='c', acceptedEpochs=0, history=[])]
        result = analysis.progress(reports)
        self.assertEqual(.02, result['latestAcceptedPriorKl'])
        self.assertEqual(1, result['latestAccepted']['update'])
        self.assertFalse(result['noProgress'])

    def test_zero_accepted_or_small_prior_kl_means_no_progress(self):
        for history in ([], [dict(epoch=1, accepted=True, priorKl=.00009)]):
            result = analysis.progress([dict(inputModelSha256='a', outputModelSha256='b',
                                             acceptedEpochs=len(history), history=history)])
            self.assertTrue(result['noProgress'])
            self.assertFalse(result['strengthClaim'])

    def test_discontinuous_optimizer_model_lineage_rejects(self):
        row = dict(inputModelSha256='a', outputModelSha256='b', acceptedEpochs=0, history=[])
        with self.assertRaisesRegex(ValueError, 'lineage'):
            analysis.progress([row, row])

    def test_selection_uses_only_eligible_checkpoints_and_earliest_tie(self):
        report = dict(eligible=True, harmful=False, versus={'f': {'pooled': {'winShare': {'mean': .06}}}})
        self.assertEqual(8, analysis.select({32: report, 16: report, 8: report}))
        self.assertIsNone(analysis.select({8: dict(report, eligible=False)}))
        with self.assertRaisesRegex(ValueError, 'entire experiment'):
            analysis.select({8: report, 16: dict(report, harmful=True)})

    def test_sources_balance_seats_and_use_original_card_actor_kind(self):
        with tempfile.TemporaryDirectory() as text:
            args = bank_fixture(Path(text))
            source = analysis.read(args[2])
            self.assertEqual('card-actor', source['policies']['initial']['kind'])
            self.assertEqual(768, len(source['jobs']))
            for population in range(2):
                seats = [job['seat'] for job in source['jobs'] if job['arm'] == 'f'
                         and (job['seed'] // 4) % 2 == population]
                self.assertEqual([32] * 4, [seats.count(seat) for seat in range(4)])

    def test_complete_control_and_checkpoint_banks_bind(self):
        with tempfile.TemporaryDirectory() as text:
            result = analysis.analyze_development_completed(*bank_fixture(Path(text)))
            self.assertEqual(1024, result['executions'])
            self.assertEqual(2, len(result['inputs']['sources']))

    def test_incomplete_checkpoint_prevents_even_control_outcome_decode(self):
        with tempfile.TemporaryDirectory() as text:
            args = bank_fixture(Path(text))
            (args[3] / 'finished.json').unlink()
            with patch.object(analysis, 'decode', side_effect=AssertionError('outcomes decoded')):
                with self.assertRaisesRegex(ValueError, 'must complete'):
                    analysis.analyze_development_completed(*args)

    def test_last_checkpoint_hash_failure_prevents_control_decode(self):
        with tempfile.TemporaryDirectory() as text:
            args = bank_fixture(Path(text))
            (args[3] / 'game-255.json.gz').write_bytes(b'changed')
            with patch.object(analysis, 'decode', side_effect=AssertionError('outcomes decoded')):
                with self.assertRaisesRegex(ValueError, 'hashes differ'):
                    analysis.analyze_development_completed(*args)


if __name__ == '__main__':
    unittest.main()
