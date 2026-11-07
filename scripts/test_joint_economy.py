"""Fixed candidate scope, complete evidence boundaries and paired-world gates."""
from copy import deepcopy
import gzip
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import joint_economy as joint


def base_model():
    weights = {key: (-.2 if 'endgame' in key or 'costFraction' in key else .4)
               for keys in joint.AXES.values() for key in keys}
    weights.update({'planning.targetTieBreak': 1.0, 'planning.tokenValue.enabled': 1.0,
                    'cash': .75, 'unknown.learned.zero': 0.0})
    return dict(schemaVersion=1, rulesVersion='rules', dataVersion='catalog', weights=weights)


def write(path, value):
    Path(path).write_bytes(joint.encoded(value))


def terminal(seat, wins, chaos=False):
    totals = [10, 10, 10, 10]
    totals[seat] = 20 if wins else 0
    scores = [dict(player='p' + str(index + 1), advancePoints=0, cash=total,
                   misery=None if chaos and index == seat else 0,
                   total=None if chaos and index == seat else total,
                   winner=bool(total == max(totals) and not (chaos and index == seat)))
              for index, total in enumerate(totals)]
    if chaos:
        maximum = max(score['total'] for score in scores if score['total'] is not None)
        for score in scores:
            score['winner'] = score['total'] is not None and score['total'] == maximum
    win = float(wins and not chaos)
    return dict(winShare=win, rankReward=win, reward=win, scores=scores, gameDecisions=123)


def records(stage, ids, win=lambda world, arm: False):
    seeds = list(range(joint.STAGES[stage][0]))
    rows = [dict(seed=seed, controlledSeat=world % 4, arm=arm,
                 terminal=terminal(world % 4, win(world, arm)))
            for world, seed in enumerate(seeds) for arm in ('incumbent', *ids)]
    return seeds, rows


def candidates(root):
    base = root / 'base.json'
    write(base, base_model())
    joint.generate(base, root / 'candidates')
    write(root / 'purchase.json', {'purchase': 'frozen'})
    write(root / 'priority.json', {'priority': 'frozen'})
    environment = dict(schemaVersion=1, configuration='four-player', baseline=str(base),
                       frozenPurchasePolicy=str(root / 'purchase.json'))
    return environment, root / 'candidates', root / 'priority.json'


def completed_fixture(root):
    root = root.resolve()
    environment, directory, priority = candidates(root)
    ids = list(joint.CANDIDATE_IDS[1:])
    seeds, rows = records('screen', ids)
    source = joint.make_source('screen', seeds, ids, environment, directory, priority)
    source_path = root / 'source.json'
    write(source_path, source)
    native = root / 'native'
    native.mkdir()
    (native / 'source.json').write_bytes(source_path.read_bytes())
    for source_key, snapshot in (('baseline', 'baseline.json'),
                                 ('frozenPurchasePolicy', 'frozen-purchase-policy.json')):
        (native / snapshot).write_bytes(Path(source[source_key]).read_bytes())
    policies = {}
    for name, policy in source['policies'].items():
        path = policy.get('path')
        policies[name] = dict(kind=policy['kind'], path=path,
                              sha256=joint.sha(path) if path else None)
        if path:
            (native / ('policy-' + name + '.json')).write_bytes(Path(path).read_bytes())
    manifest = dict(kind='card-advantage-evaluate', schemaVersion=1, signature='frozen-runtime',
                    economyVersion=joint.VERSION, trajectoryEncoding='gzip-json-v1',
                    source=str(source_path), sourceSha256=joint.sha(source_path),
                    configuration=source['configuration'], policies=policies, jobs=source['jobs'])
    for key in ('baseline', 'frozenPurchasePolicy'):
        manifest[key] = dict(path=source[key], sha256=joint.sha(source[key]))
    write(native / 'collection.json', manifest)
    entries = []
    for index, row in enumerate(rows):
        path = native / ('game-' + str(index) + '.json.gz')
        path.write_bytes(gzip.compress(joint.encoded(dict(row, schemaVersion=1,
                                                       index=index, signature='frozen-runtime'))))
        entries.append(dict(index=index, file=path.name, sha256=joint.sha(path)))
    write(native / 'finished.json', dict(kind='card-advantage-evaluate', signature='frozen-runtime',
                                        games=len(rows), files=entries))
    write(native / 'state.json', dict(phase='complete', signature='frozen-runtime'))
    return seeds, ids, native, source_path, environment, directory, priority


class CandidateTest(unittest.TestCase):
    def test_exact_matrix_preserves_every_other_weight_and_base_bytes(self):
        with tempfile.TemporaryDirectory() as text:
            root = Path(text)
            base, directory, _ = candidates(root)
            manifest, models = joint.load_candidates(directory)
            original = joint.read(base['baseline'])
            self.assertEqual(15, len(models))
            self.assertEqual(list(joint.CANDIDATE_IDS), [row['id'] for row in manifest['candidates']])
            self.assertEqual(Path(base['baseline']).read_bytes(), (directory / 'incumbent.json').read_bytes())
            for name, factors in joint.candidate_specs():
                for axis, keys in joint.AXES.items():
                    for key in keys:
                        self.assertEqual(original['weights'][key] * factors[axis], models[name]['weights'][key])
                for key in ('cash', 'planning.targetTieBreak', 'planning.tokenValue.enabled', 'unknown.learned.zero'):
                    self.assertEqual(original['weights'][key], models[name]['weights'][key])

    def test_candidate_and_manifest_jointly_changed_cannot_authorize_another_weight(self):
        with tempfile.TemporaryDirectory() as text:
            root = Path(text)
            _, directory, _ = candidates(root)
            path = directory / 'liquidity-low.json'
            model = joint.read(path)
            model['weights']['cash'] = 999
            write(path, model)
            manifest = joint.read(directory / 'manifest.json')
            manifest['candidates'][1]['sha256'] = joint.sha(path)
            write(directory / 'manifest.json', manifest)
            with self.assertRaisesRegex(ValueError, 'unauthorized weight'):
                joint.load_candidates(directory)

    def test_generation_never_overwrites_previous_evidence(self):
        with tempfile.TemporaryDirectory() as text:
            root = Path(text)
            base, directory, _ = candidates(root)
            before = joint.sha(directory / 'manifest.json')
            with self.assertRaisesRegex(ValueError, 'fresh directory'):
                joint.generate(base['baseline'], directory)
            self.assertEqual(before, joint.sha(directory / 'manifest.json'))

    def test_missing_key_nonfinite_weight_or_overflow_rejects_before_output(self):
        for mutation in ('missing', 'nan', 'overflow'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as text:
                root = Path(text)
                model = base_model()
                key = joint.AXES['liquidity'][0]
                if mutation == 'missing':
                    del model['weights'][key]
                else:
                    model['weights'][key] = float('nan') if mutation == 'nan' else 1e6
                (root / 'base.json').write_text(json.dumps(model))
                with self.assertRaises(ValueError):
                    joint.generate(root / 'base.json', root / 'output')
                self.assertFalse((root / 'output').exists())

    def test_source_populations_balance_all_four_seats_and_deduplicate_incumbent(self):
        with tempfile.TemporaryDirectory() as text:
            base, directory, priority = candidates(Path(text))
            source = joint.make_source('screen', list(range(64)), list(joint.CANDIDATE_IDS[1:]),
                                       base, directory, priority)
            self.assertEqual(16, len(source['policies']))
            self.assertEqual({'kind': 'baseline'}, source['policies']['incumbent'])
            self.assertEqual(960, len(source['jobs']))
            for population in range(2):
                jobs = [job for job in source['jobs'] if job['arm'] == 'incumbent'
                        and (job['seed'] // 4) % 2 == population]
                self.assertEqual([8, 8, 8, 8], [sum(job['seat'] == seat for job in jobs) for seat in range(4)])
                for job in jobs:
                    self.assertEqual('incumbent', job['rivals'][job['seat']])
                    self.assertEqual({'incumbent' if population == 0 else 'priority64'},
                                     {value for seat, value in enumerate(job['rivals']) if seat != job['seat']})


class StatisticsTest(unittest.TestCase):
    def test_screen_ranks_all_fourteen_with_lexical_ties(self):
        ids = list(joint.CANDIDATE_IDS[1:])
        seeds, rows = records('screen', ids)
        report = joint.analyze('screen', seeds, ids, list(reversed(rows)))
        self.assertEqual(sorted(ids), report['ranking'])
        self.assertEqual(sorted(ids)[:3], report['selected'])
        self.assertFalse(report['policyActivation'])

    def test_stratification_keeps_opposing_constant_population_effects_exactly_balanced(self):
        values = np.array([[1.0 if (index // 4) % 2 == 0 else -1.0] for index in range(64)])
        pooled, first, second = joint.bootstrap(values)
        self.assertEqual((0, 0, 0), tuple(pooled[0][key] for key in ('mean', 'lower95', 'upper95')))
        self.assertEqual(1, first[0]['mean'])
        self.assertEqual(-1, second[0]['mean'])

    def test_selection_requires_both_population_point_gains(self):
        ids = list(joint.CANDIDATE_IDS[1:4])
        seeds, rows = records('selection', ids, lambda world, arm:
                             ((world // 4) % 2 == 0 and arm == ids[0])
                             or ((world // 4) % 2 == 1 and world < 8 and arm == 'incumbent'))
        report = joint.analyze('selection', seeds, ids, rows)
        self.assertEqual(ids[0], report['winner'])
        self.assertTrue(report['gates']['minimumPooledGain'])
        self.assertFalse(report['mayConfirm'])

    def test_confirmation_significance_does_not_replace_minimum_gain(self):
        ids = ['liquidity-low']
        seeds, rows = records('confirmation', ids, lambda world, arm: arm == ids[0] and world < 32)
        report = joint.analyze('confirmation', seeds, ids, rows)
        self.assertTrue(report['gates']['positivePooledLower95'])
        self.assertFalse(report['gates']['minimumPooledGain'])
        self.assertFalse(report['passed'])

    def test_clear_confirmation_passes_without_activating_policy(self):
        ids = ['liquidity-low']
        seeds, rows = records('confirmation', ids, lambda world, arm: arm == ids[0] and world < 128)
        report = joint.analyze('confirmation', seeds, ids, rows)
        self.assertTrue(report['passed'])
        self.assertFalse(report['policyActivation'])
        self.assertEqual(.125, report['comparisons'][ids[0]]['pooled']['winShare']['mean'])

    def test_confirmation_population_uncertainty_can_block_a_large_pooled_gain(self):
        ids = ['liquidity-low']
        seeds, rows = records('confirmation', ids, lambda world, arm:
                             arm == ids[0] if (world // 4) % 2 == 0
                             else (arm == ids[0]) == (world < 512))
        report = joint.analyze('confirmation', seeds, ids, rows)
        self.assertTrue(report['gates']['minimumPooledGain'])
        self.assertTrue(report['gates']['nonnegativePopulations'])
        self.assertFalse(report['gates']['boundedPopulationLoss'])
        self.assertFalse(report['passed'])

    def test_missing_duplicate_or_wrong_seat_records_reject(self):
        ids = list(joint.CANDIDATE_IDS[1:4])
        seeds, rows = records('selection', ids)
        for changed in (rows[:-1], rows[:-1] + rows[:1], [dict(rows[0], controlledSeat=1)] + rows[1:]):
            with self.assertRaisesRegex(ValueError, 'exactly once'):
                joint.analyze('selection', seeds, ids, changed)

    def test_terminal_labels_are_independently_recomputed(self):
        row = dict(controlledSeat=2, terminal=terminal(2, True))
        self.assertEqual(1, joint.terminal_metrics(row)['winShare'])
        row['terminal']['reward'] = .99
        with self.assertRaisesRegex(ValueError, 'labels differ'):
            joint.terminal_metrics(row)

    def test_chaos_does_not_receive_a_fabricated_score(self):
        row = dict(controlledSeat=0, terminal=terminal(0, False, chaos=True))
        result = joint.terminal_metrics(row)
        self.assertIsNone(result['ownTotal'])
        self.assertTrue(result['chaos'])
        self.assertEqual(0, result['reward'])

    def test_draw_credit_is_fractional_and_chaos_rival_is_ranked_below_survivor(self):
        row = dict(controlledSeat=0, terminal=terminal(0, True))
        scores = row['terminal']['scores']
        scores[1].update(cash=20, total=20, winner=True)
        scores[3].update(misery=None, total=None, winner=False)
        row['terminal'].update(winShare=.5, rankReward=2.5 / 3, reward=.4 + .2 * 2.5 / 3)
        result = joint.terminal_metrics(row)
        self.assertEqual(.5, result['winShare'])
        self.assertEqual(2.5 / 3, result['rankReward'])

    def test_strict_integer_seed_and_fixed_stage_sizes(self):
        seeds, rows = records('selection', list(joint.CANDIDATE_IDS[1:4]))
        for altered in ([False, *seeds[1:]], [2**31, *seeds[1:]], seeds[:-1]):
            with self.assertRaisesRegex(ValueError, 'signed 32-bit'):
                joint.analyze('selection', altered, list(joint.CANDIDATE_IDS[1:4]), rows)


class CompletionBoundaryTest(unittest.TestCase):
    def test_complete_source_models_and_terminal_records_bind(self):
        with tempfile.TemporaryDirectory() as text:
            args = completed_fixture(Path(text))
            report = joint.analyze_completed('screen', *args)
            self.assertEqual(960, report['executions'])
            self.assertEqual(joint.sha(args[2] / 'finished.json'), report['inputs']['completionSha256'])

    def test_last_hash_failure_prevents_any_outcome_decode(self):
        with tempfile.TemporaryDirectory() as text:
            args = completed_fixture(Path(text))
            (args[2] / 'game-959.json.gz').write_bytes(b'changed')
            with patch.object(joint.gzip, 'open', side_effect=AssertionError('outcomes opened')):
                with self.assertRaisesRegex(ValueError, 'hashes differ'):
                    joint.analyze_completed('screen', *args)

    def test_source_pool_change_fails_before_outcome_decode(self):
        with tempfile.TemporaryDirectory() as text:
            args = completed_fixture(Path(text))
            source = joint.read(args[3])
            source['jobs'][0]['rivals'][1] = 'priority64'
            write(args[3], source)
            with patch.object(joint.gzip, 'open', side_effect=AssertionError('outcomes opened')):
                with self.assertRaisesRegex(ValueError, 'prospective stage'):
                    joint.analyze_completed('screen', *args)

    def test_absent_completion_never_opens_outcomes(self):
        with tempfile.TemporaryDirectory() as text:
            args = completed_fixture(Path(text))
            (args[2] / 'finished.json').unlink()
            with patch.object(joint.gzip, 'open', side_effect=AssertionError('outcomes opened')):
                with self.assertRaisesRegex(ValueError, 'must complete'):
                    joint.analyze_completed('screen', *args)


if __name__ == '__main__':
    unittest.main()
