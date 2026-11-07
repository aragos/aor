import copy
import gzip
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import card_policy_coupling_analysis as analysis
import purchase_policy as common
from test_card_continuation_analysis import fixture as reference_fixture, finish as finish_reference


def write(path, value):
    path.write_bytes(gzip.compress(json.dumps(value).encode(), mtime=0))


def row(path): return json.loads(gzip.decompress(path.read_bytes()))


def finish(directory):
    manifest = common.read_json(directory/'collection.json')
    common.atomic_json(directory/'finished.json', {'kind': analysis.KIND, 'signature': manifest['signature'],
        'origins': 64, 'originSha256': [analysis.sha(directory/f'origin-{i}.json.gz') for i in range(64)]})


def trace(root, outcome, schedule, seed, replicate, seat):
    rows = []
    for offset in range(outcome['gameDecisions']-root['number']):
        record = {'number': root['number']+offset, 'batchStart': root['number']+offset,
            'turn': root['turn'], 'stage': 'PLAY_CARDS', 'decision': 'play-cards', 'seat': seat,
            'occurrence': offset, 'seed': root['botSeed'], 'forced': offset == 0}
        if offset: record['seed'] = analysis.policy_seed(schedule, seed, replicate, record)
        rows.append(record)
    return rows


def fixture(base):
    reference = base/'reference'; reference_fixture(reference)
    manifest = common.read_json(reference/'collection.json')
    manifest.update(configuration='fixture', rulesVersion='fixture', dataVersion='fixture', trajectoryEncoding='gzip-json-v1')
    common.atomic_json(reference/'collection.json', manifest)
    for index in range(64):
        path = reference/f'origin-{index}.json.gz'; old = row(path)
        for oid, outcome in old['outcomes'].items():
            outcome['gameDecisions'] = old['root']['number']+3
            outcome['chanceSeed'] = analysis.chance_seed(old['seed'], int(oid.rsplit('-',1)[1]))
        write(path, old)
    finish_reference(reference)
    source = base/'source'; source.mkdir()
    for name in ('baseline.json', 'frozen-purchase-policy.json'): shutil.copyfile(reference/name, source/name)
    declared = {'kind': analysis.KIND, 'schemaVersion': 1, 'configuration': 'fixture',
                'collection': analysis.PLAN, 'origins': manifest['origins'][:64]}
    common.atomic_json(source/'source.json', declared)
    current = {**manifest, 'kind': analysis.KIND, 'schemaVersion': 1, 'signature': 'coupling-fixture',
        'origins': declared['origins'], 'collection': analysis.PLAN, 'continuation': None,
        'replicates': 8, 'maxTailsPerOrigin': 48, 'schedules': list(analysis.SCHEDULES),
        'policySeedVersion': analysis.PLAN['kind'],
        'arms': list(analysis.ARMS), 'tailPolicy': 'F', 'sourceSha256': analysis.sha(source/'source.json')}
    common.atomic_json(source/'collection.json', current)
    common.atomic_json(source/'state.json', {'phase': 'complete', 'signature': 'coupling-fixture'})
    for request in declared['origins']:
        old = row(reference/f"origin-{request['index']}.json.gz")
        result = {**copy.deepcopy(old), 'kind': analysis.KIND, 'schemaVersion': 1,
                  'signature': 'coupling-fixture', 'outcomes': {}, 'policyTraces': {}}
        root = result['root']; root['arms'] = []
        for position, arm in enumerate(analysis.ARMS):
            schedule = analysis.SCHEDULES[position//2]; alternative = position % 2 == 1
            ids = [f'arm-{arm}-{rep}' for rep in range(8)]
            root['arms'].append({'id': arm, 'schedule': schedule,
                'actionIndex': root['alternativeIndex'] if alternative else root['referenceIndex'], 'outcomeIds': ids})
            for rep, oid in enumerate(ids):
                old_arm = old['root']['arms'][1 if alternative else 0]
                outcome = copy.deepcopy(old['outcomes'][old_arm['outcomeIds'][rep % 4]])
                outcome['chanceSeed'] = analysis.chance_seed(request['seed'], rep)
                if schedule != 'L':
                    value = .5+((rep % 2)*2-1)*(.2 if alternative else -.2)*(1 if schedule=='G' else .25)
                    outcome.update(reward=value, winShare=value, rankReward=value)
                result['outcomes'][oid] = outcome
                result['policyTraces'][oid] = trace(root, outcome, schedule, request['seed'], rep, request['seat'])
        write(source/f"origin-{request['index']}.json.gz", result)
    finish(source)
    return source, reference


def reference_contract(reference):
    return patch.object(analysis.previous, 'PRIORITY_SHA256', analysis.sha(reference/'continuation.json'))


class StatisticChecks(unittest.TestCase):
    def test_sample_variance_and_covariance_use_ddof_one(self):
        f = np.arange(8)/10; a = 2*f
        result = analysis.moments(f, a)
        self.assertAlmostEqual(result['varD'], f.var(ddof=1))
        self.assertAlmostEqual(result['covariance'], 2*f.var(ddof=1))

    def test_ratio_of_world_mean_variances_not_mean_ratios(self):
        worlds = []
        for magnitude in (1., 3.):
            g = np.arange(8)*magnitude; s = np.arange(8)
            worlds.append({'l-f': np.zeros(8), 'l-a': g, 'g-f': np.zeros(8), 'g-a': g,
                           's-f': np.zeros(8), 's-a': s})
        report = analysis.statistics(worlds)
        self.assertAlmostEqual(report['primary']['ratio'], .2)
        self.assertNotAlmostEqual(report['primary']['ratio'], (1+1/9)/2)

    def test_zero_denominator_is_not_a_variance_reduction_claim(self):
        report = analysis.statistics([{arm: [0.]*8 for arm in analysis.ARMS}])
        self.assertIsNone(report['primary']['ratio']); self.assertFalse(report['primary']['gatePassed'])
        self.assertEqual(report['primary']['ratioInterval']['undefinedBootstrapDraws'], 10000)

    def test_undefined_bootstrap_draws_are_not_silently_removed(self):
        first = {arm: [0.]*8 for arm in analysis.ARMS}
        second = {**first, 'g-a': list(np.arange(8)), 's-a': list(np.arange(8)*.1)}
        result = analysis.statistics([first, second])
        self.assertIsNone(result['primary']['ratioInterval']['upper95'])
        self.assertFalse(result['primary']['gatePassed'])

    def test_bootstrap_deterministic_and_mean_difference_kept_descriptive(self):
        world = {arm: [0.]*8 for arm in analysis.ARMS}
        world.update({'g-a': list(np.arange(8)/10), 's-a': list(np.arange(8)/20)})
        first = analysis.statistics([world]*4); second = analysis.statistics([world]*4)
        self.assertEqual(first, second)
        self.assertAlmostEqual(first['pairedMeanDifferenceSemanticMinusGlobal']['mean'], -.175)


class TraceChecks(unittest.TestCase):
    def test_legacy_signed_overflow_matches_int32(self):
        expected = analysis.signed32(-2147483648 ^ analysis.signed32(100000*65537+3*8191))
        self.assertEqual(analysis.policy_seed('L', -2147483648, 7, {'batchStart':100000,'seat':3}), expected)

    def test_semantic_seed_ignores_global_number_but_global_hash_does_not(self):
        row = {'turn': 2, 'stage': 'PURCHASE', 'decision': 'purchase-advances', 'seat': 1, 'occurrence': 0, 'batchStart': 80}
        changed = {**row, 'batchStart': 81}
        self.assertEqual(analysis.policy_seed('S', 7, 3, row), analysis.policy_seed('S', 7, 3, changed))
        self.assertNotEqual(analysis.policy_seed('G', 7, 3, row), analysis.policy_seed('G', 7, 3, changed))

    def test_forced_root_keeps_legacy_seed_then_reserves_occurrence(self):
        root = {'number': 10, 'turn': 2, 'botSeed': 123}; outcome = {'gameDecisions': 13}
        rows = trace(root, outcome, 'S', 7, 0, 1)
        keys = analysis.verify_trace(rows, root, outcome, 'S', 7, 0, 1)
        self.assertEqual(len(keys), 2); self.assertTrue(all(key[-1] > 0 for key in keys))
        rows[1]['occurrence'] = 0
        with self.assertRaisesRegex(ValueError, 'unique and consecutive'):
            analysis.verify_trace(rows, root, outcome, 'S', 7, 0, 1)

    def test_bad_hash_and_extra_forced_choice_rejected(self):
        root = {'number': 10, 'turn': 2, 'botSeed': 123}; outcome = {'gameDecisions': 13}
        rows = trace(root, outcome, 'G', 7, 0, 1); rows[1]['seed'] += 1
        with self.assertRaisesRegex(ValueError, 'versioned schedule'):
            analysis.verify_trace(rows, root, outcome, 'G', 7, 0, 1)
        rows = trace(root, outcome, 'G', 7, 0, 1); rows[1]['forced'] = True
        with self.assertRaisesRegex(ValueError, 'Only the initial'):
            analysis.verify_trace(rows, root, outcome, 'G', 7, 0, 1)

    def test_sealed_batch_requires_common_context_and_distinct_seats(self):
        root = {'number': 10, 'turn': 2, 'botSeed': 123}; outcome = {'gameDecisions': 13}
        rows = trace(root, outcome, 'S', 7, 0, 1)
        rows[1].update(stage='PURCHASE', decision='purchase-tokens', seat=0, occurrence=0)
        rows[2].update(stage='PURCHASE', decision='purchase-tokens', seat=2, occurrence=0, batchStart=11)
        for record in rows[1:]: record['seed'] = analysis.policy_seed('S',7,0,record)
        analysis.verify_trace(rows,root,outcome,'S',7,0,1)
        rows[2]['decision'] = 'purchase-shipping'
        rows[2]['seed'] = analysis.policy_seed('S',7,0,rows[2])
        with self.assertRaisesRegex(ValueError,'pre-state context'):
            analysis.verify_trace(rows,root,outcome,'S',7,0,1)


class BoundaryChecks(unittest.TestCase):
    def test_completed_legacy_and_trace_checks_precede_statistics(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base)
            with reference_contract(reference):
                report = analysis.run(source, reference, base/'analysis')
                self.assertTrue(report['legacyReferenceVerified']); self.assertTrue(report['semanticTraceVerified'])
                self.assertTrue(report['coverage']['passed']); self.assertTrue(report['primary']['gatePassed'])
                self.assertEqual(report['classification'], 'variance-reduction-demonstrated')
                self.assertFalse(report['policyPromotion'])
                with patch.object(analysis.Completed, 'origin', side_effect=AssertionError('no outcome re-read')):
                    self.assertEqual(analysis.run(source, reference, base/'analysis', resume=True), report)

    def test_missing_completion_and_changed_compressed_hash_reject_before_decode(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base)
            (source/'origin-63.json.gz').write_bytes(b'changed')
            with reference_contract(reference), patch.object(common, 'read_game', side_effect=AssertionError('must not decode')):
                with self.assertRaisesRegex(ValueError, 'origin bytes changed'):
                    analysis.Completed(source, reference)

    def test_changed_legacy_terminal_is_rejected_even_with_refreshed_file_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base); path = source/'origin-0.json.gz'
            origin = row(path); origin['outcomes']['arm-l-f-0']['canonicalSha256'] = 'changed'
            write(path, origin); finish(source)
            with reference_contract(reference), patch.object(analysis, 'statistics', side_effect=AssertionError('no statistics')):
                with self.assertRaisesRegex(ValueError, 'exactly reproduce'):
                    analysis.run(source, reference, base/'analysis')

    def test_changed_root_observation_rejected_before_statistics(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base); path = source/'origin-0.json.gz'
            origin = row(path); origin['root']['observation']['stateFeatures']['context'] *= -1
            write(path, origin); finish(source)
            with reference_contract(reference):
                bound = analysis.Completed(source, reference)
                with self.assertRaisesRegex(ValueError, 'Root observation'):
                    bound.origin(bound.manifest['origins'][0])

    def test_changed_authority_seed_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base); path = source/'origin-0.json.gz'
            origin = row(path); origin['outcomes']['arm-s-a-7']['chanceSeed'] += 1
            write(path, origin); finish(source)
            with reference_contract(reference):
                bound = analysis.Completed(source, reference)
                with self.assertRaisesRegex(ValueError, 'Authority future randomness'):
                    bound.origin(bound.manifest['origins'][0])

    def test_coordinated_wrong_future_seed_for_all_arms_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base); path = source/'origin-0.json.gz'
            origin = row(path)
            for arm in analysis.ARMS: origin['outcomes'][f'arm-{arm}-7']['chanceSeed'] += 1
            write(path, origin); finish(source)
            with reference_contract(reference):
                bound = analysis.Completed(source, reference)
                with self.assertRaisesRegex(ValueError, 'fixed world/replicate formula'):
                    bound.origin(bound.manifest['origins'][0])

    def test_insufficient_coverage_does_not_pass_despite_reduced_variance(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base)
            real = analysis.Completed.origin
            def limited(bound, request):
                values, audit = real(bound, request)
                return (values, audit) if request['index'] < 47 else (None, {**audit, 'status':'trivial'})
            with reference_contract(reference), patch.object(analysis.Completed,'origin',limited):
                report = analysis.run(source, reference, base/'analysis')
            self.assertFalse(report['coverage']['passed']); self.assertFalse(report['primary']['gatePassed'])
            self.assertEqual(report['classification'],'insufficient-coverage')

    def test_trivial_alternative_has_explicit_aliases_and_no_statistical_world(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); source, reference = fixture(base)
            old = row(reference/'origin-0.json.gz'); new = row(source/'origin-0.json.gz')
            for item in (old, new):
                root = item['root']; root['observation']['actions'] = root['observation']['actions'][:1]
                root['proposalIndices'] = [0]; root['alternativeIndex'] = None
                root['acquisition'] = analysis.advantage.acquisition(root['observation'],root['botSeed'],True)[1]
                for i in range(1,len(root['arms']),2):
                    root['arms'][i]['actionIndex'] = 0
                    root['arms'][i]['outcomeIds'] = root['arms'][i-1]['outcomeIds']
                used = {oid for arm in root['arms'] for oid in arm['outcomeIds']}
                item['outcomes'] = {key:value for key,value in item['outcomes'].items() if key in used}
                if 'policyTraces' in item:
                    item['policyTraces'] = {key:value for key,value in item['policyTraces'].items() if key in used}
            write(reference/'origin-0.json.gz',old); finish_reference(reference)
            write(source/'origin-0.json.gz',new); finish(source)
            with reference_contract(reference):
                bound = analysis.Completed(source,reference); values,audit = bound.origin(bound.manifest['origins'][0])
            self.assertIsNone(values); self.assertEqual(audit['status'],'trivial')
            self.assertEqual(len(new['outcomes']),24)


if __name__ == '__main__': unittest.main()
