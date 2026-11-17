"""Invented models/streams only; no saved automata or scientific worlds are opened."""
import copy
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import coordinated_es_math as es
import robust_league_numeric as numeric
from test_coordinated_es_projection import original_actors


def write(path, value):
    path = Path(path)
    path.write_bytes(es.canonical(value))
    return dict(path=str(path), sha256=numeric.sha(path.read_bytes()))


def read(item):
    return json.loads(Path(item['path']).read_bytes())


def absolute(owner, item):
    return dict(path=str(Path(owner).parent / item['path']), sha256=item['sha256'])


class RobustNumericTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.actors = original_actors()
        output = numeric.Output(cls.root / 'invented-origin')
        origin = output.bundle('', {head: value.raw for head, value in cls.actors.items()})
        cls.origin = absolute(output.path / 'receipt.json', origin)
        cls.asset = write(cls.root / 'asset.json', dict(kind='coordinated-inference-asset-1',
            currentBundle=dict(path='invented-origin/bundle.json', sha256=origin['sha256']),
            originalBundle=dict(path='unused-selector-reference.json', sha256='e' * 64),
            calibration=dict(path='unused-original-calibration.json', sha256='f' * 64)))
        cls.protocol = write(cls.root / 'protocol.json', dict(numericRecipe=numeric.RECIPE,
            baseInferenceAssetSha256=cls.asset['sha256']))
        cls.schedule_record = dict(kind='robust-league-schedule-1', projectionSeed=97,
            directionSeeds=list(range(101, 109)), banks={
                'generation-' + str(generation): dict(nativeOffset=1000 + generation,
                    count=128, seeds=[-1000000000 + 1000 + generation + i * 8191 for i in range(128)])
                for generation in range(1, 9)})
        cls.schedule = write(cls.root / 'schedule.json', cls.schedule_record)
        cls.prepare_record = dict(kind='robust-league-numeric-prepare-1',
            protocol=cls.protocol, seedSchedule=cls.schedule, baseInferenceAsset=cls.asset)
        request = write(cls.root / 'prepare-request.json', cls.prepare_record)
        cls.prep_output = cls.root / 'prepared'
        numeric.run('prepare', request['path'], cls.prep_output, owner_guard=lambda: None)
        cls.prep = dict(path=str(cls.prep_output / 'receipt.json'),
            sha256=numeric.sha((cls.prep_output / 'receipt.json').read_bytes()))
        cls.center0 = absolute(cls.prep['path'], read(cls.prep)['center'])
        request = write(cls.root / 'population-request.json', dict(kind='robust-league-numeric-population-1',
            preparation=cls.prep, center=cls.center0, generation=1))
        cls.pop_output = cls.root / 'population'
        numeric.run('population', request['path'], cls.pop_output, owner_guard=lambda: None)
        cls.pop_receipt = dict(path=str(cls.pop_output / 'receipt.json'),
            sha256=numeric.sha((cls.pop_output / 'receipt.json').read_bytes()))
        cls.pop_item = absolute(cls.pop_receipt['path'], read(cls.pop_receipt)['population'])
        cls.pop = read(cls.pop_item)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.case = self.root / self._testMethodName
        self.case.mkdir()

    def execute(self, mode, record):
        request = write(self.case / 'request.json', record)
        return numeric.run(mode, request['path'], self.case / 'result', owner_guard=lambda: None)

    def outcomes(self):
        return dict(kind='robust-league-generation-outcomes-1', complete=True, verified=True,
            generation=1, populationSha256=self.pop_item['sha256'],
            generationBankSha256=self.pop['generationBankSha256'], worldSeeds=self.pop['worldSeeds'],
            members=[dict(index=member['index'], direction=member['direction'], sign=member['sign'],
                bundleSha256=member['bundle']['sha256'],
                winShares=[1.] * 64 + [0.] * 64 if member['sign'] == 1 else [.25] * 128)
                for member in self.pop['members']])

    def update(self, outcomes):
        source = write(self.case / 'outcomes.json', outcomes)
        return self.execute('update', dict(kind='robust-league-numeric-update-1',
            preparation=self.prep, population=self.pop_receipt, outcomes=source))

    def test_zero_origin_copies_raw_heads_and_preserves_distinct_selector_reference(self):
        receipt = read(self.prep)
        origin = read(absolute(self.prep['path'], receipt['originBundle']))
        self.assertEqual(receipt['numericalOriginBundleSha256'], self.origin['sha256'])
        self.assertEqual(receipt['selectorOriginalBundleSha256'], 'e' * 64)
        self.assertNotEqual(receipt['numericalOriginBundleSha256'], receipt['selectorOriginalBundleSha256'])
        owner = self.prep_output / receipt['originBundle']['path']
        for head in es.HEADS:
            self.assertEqual((owner.parent / origin['heads'][head.value]['path']).read_bytes(),
                self.actors[head].raw)

    def test_prepare_uses_local_generators_without_mutating_global_numpy_rng(self):
        before = np.random.get_state()
        self.execute('prepare', self.prepare_record)
        after = np.random.get_state()
        self.assertEqual(before[0], after[0])
        self.assertTrue(np.array_equal(before[1], after[1]))
        self.assertEqual(before[2:], after[2:])

    def test_duplicate_reserved_numeric_stream_is_rejected_before_draws(self):
        schedule = copy.deepcopy(self.schedule_record)
        schedule['directionSeeds'][0] = schedule['projectionSeed']
        record = dict(self.prepare_record,
            seedSchedule=write(self.case / 'bad-schedule.json', schedule))
        with self.assertRaisesRegex(ValueError, 'eight distinct'):
            self.execute('prepare', record)

    def test_stale_base_asset_binding_cannot_prepare(self):
        protocol = write(self.case / 'wrong-protocol.json', dict(numericRecipe=numeric.RECIPE,
            baseInferenceAssetSha256='a' * 64))
        with self.assertRaisesRegex(ValueError, 'fixed protocol'):
            self.execute('prepare', dict(self.prepare_record, protocol=protocol))

    def test_population_contains_all32_exact_signed_members_and_unchanged_values(self):
        self.assertEqual([(member['index'], member['direction'], member['sign'])
            for member in self.pop['members']], [(index, index // 2, 1 if index % 2 == 0 else -1)
            for index in range(32)])
        for member in self.pop['members']:
            item = absolute(self.pop_item['path'], member['bundle'])
            bundle = read(item)
            for head in es.HEADS:
                model = read(absolute(item['path'], {key: bundle['heads'][head.value][key]
                    for key in ('path', 'sha256')}))
                original = self.actors[head].model
                self.assertEqual({key: value for key, value in model.items()
                    if key not in numeric.ACTOR_BLOCKS}, {key: value for key, value in original.items()
                    if key not in numeric.ACTOR_BLOCKS})

    def test_generation_skips_cannot_materialize_a_population(self):
        with self.assertRaisesRegex(ValueError, 'immediate same-scope'):
            self.execute('population', dict(kind='robust-league-numeric-population-1',
                preparation=self.prep, center=self.center0, generation=2))

    def test_update_uses_only_complete_mean_win_shares_and_normalizes_the_ascent(self):
        self.update(self.outcomes())
        receipt = json.loads((self.case / 'result/receipt.json').read_bytes())
        center = read(absolute(self.case / 'result/receipt.json', receipt['center']))
        table = read(absolute(self.prep['path'], read(self.prep)['directions'][0]))
        gradient = [math.fsum(.25 * row[index] for row in table['vectors']) / 32 for index in range(256)]
        norm = math.sqrt(math.fsum(number * number for number in gradient) / 256)
        for actual, expected in zip(center['coordinates'], gradient):
            self.assertAlmostEqual(actual, expected / norm, places=13)
        self.assertEqual(receipt['memberWinMeans'], [.5, .25] * 16)
        self.assertEqual(receipt['fitness'], 'WIN_SHARE_ONLY')
        self.assertEqual(center['generation'], 1)

    def test_reordered_fitness_members_are_rejected(self):
        outcomes = self.outcomes()
        outcomes['members'][0], outcomes['members'][1] = outcomes['members'][1], outcomes['members'][0]
        with self.assertRaisesRegex(ValueError, 'reordered'):
            self.update(outcomes)

    def test_outcomes_from_different_original_worlds_are_rejected(self):
        outcomes = self.outcomes()
        outcomes['worldSeeds'] = list(reversed(outcomes['worldSeeds']))
        with self.assertRaisesRegex(ValueError, 'original members'):
            self.update(outcomes)

    def test_incomplete_world_matrix_cannot_update(self):
        outcomes = self.outcomes()
        outcomes['members'][31]['winShares'].pop()
        with self.assertRaisesRegex(ValueError, 'wrong fixed dimension'):
            self.update(outcomes)

    def test_nonfinite_or_out_of_range_win_shares_cannot_update(self):
        outcomes = self.outcomes()
        outcomes['members'][0]['winShares'][0] = 1.1
        with self.assertRaisesRegex(ValueError, 'unit interval'):
            self.update(outcomes)

    def test_zero_gradient_stops_without_a_next_center(self):
        outcomes = self.outcomes()
        for member in outcomes['members']:
            member['winShares'] = [.25] * 128
        with self.assertRaisesRegex(es.OperationalStop, 'zero-gradient'):
            self.update(outcomes)
        self.assertFalse((self.case / 'result/center.json').exists())

    def test_only_mean6_can_be_the_final_candidate(self):
        center = write(self.case / 'center5.json', numeric.center_record(read(self.prep)['scopeSha256'], 5, [.1] * 256))
        with self.assertRaisesRegex(ValueError, 'immediate same-scope'):
            self.execute('final', dict(kind='robust-league-numeric-final-1',
                preparation=self.prep, center=center))

    def test_final_mean6_keeps_original_selector_reference_and_value_arrays(self):
        center = write(self.case / 'center6.json', numeric.center_record(read(self.prep)['scopeSha256'], 6, [.1] * 256))
        self.execute('final', dict(kind='robust-league-numeric-final-1', preparation=self.prep, center=center))
        receipt = json.loads((self.case / 'result/receipt.json').read_bytes())
        self.assertEqual(receipt['candidate'], 'FINAL_MEAN_6_ONLY')
        self.assertEqual(receipt['generation'], 6)
        self.assertEqual(receipt['selectorOriginalBundleSha256'], 'e' * 64)
        item = absolute(self.case / 'result/receipt.json', receipt['bundle'])
        bundle = read(item)
        for head in es.HEADS:
            model = read(absolute(item['path'], {key: bundle['heads'][head.value][key]
                for key in ('path', 'sha256')}))
            self.assertEqual(model['valueWeights'], self.actors[head].model['valueWeights'])
            self.assertEqual(model['valueBias'], self.actors[head].model['valueBias'])

    def test_held_direction_streams7_and8_are_never_drawn(self):
        with patch.object(numeric.projection, 'generate_directions',
                wraps=numeric.projection.generate_directions) as draw:
            self.execute('prepare', self.prepare_record)
        self.assertEqual([(call.args[1], call.args[2]) for call in draw.call_args_list],
            list(enumerate(self.schedule_record['directionSeeds'][:6], 1)))
        receipt = json.loads((self.case / 'result/receipt.json').read_bytes())
        self.assertEqual(receipt['activeGenerations'], 6)
        self.assertEqual(receipt['heldDirectionSeeds'], self.schedule_record['directionSeeds'][6:])
        self.assertEqual(receipt['heldGenerationBanks'], ['generation-7', 'generation-8'])
        self.assertEqual(receipt['completeSeedScheduleSha256'], self.schedule['sha256'])
        self.assertEqual(len(receipt['directions']), 6)

    def test_scope_binds_complete_schedule_including_held_metadata(self):
        schedule = copy.deepcopy(self.schedule_record)
        schedule['directionSeeds'][7] = 211
        record = dict(self.prepare_record,
            seedSchedule=write(self.case / 'different-held-metadata.json', schedule))
        self.execute('prepare', record)
        receipt = json.loads((self.case / 'result/receipt.json').read_bytes())
        self.assertNotEqual(receipt['scopeSha256'], read(self.prep)['scopeSha256'])
        self.assertEqual(receipt['usedDirectionSeeds'], self.schedule_record['directionSeeds'][:6])

    def test_reserved_generation7_cannot_materialize(self):
        with self.assertRaisesRegex(ValueError, 'reserved7 and8 remain held'):
            self.execute('population', dict(kind='robust-league-numeric-population-1',
                preparation=self.prep, center=self.center0, generation=7))

    def test_default_cli_owner_rejects_arm_mac_before_drawing(self):
        request = write(self.case / 'request.json', self.prepare_record)
        with patch.object(numeric.platform, 'system', return_value='Darwin'):
            with self.assertRaisesRegex(ValueError, 'authoritative Desktop'):
                numeric.run('prepare', request['path'], self.case / 'result')
        self.assertFalse((self.case / 'result').exists())


if __name__ == '__main__':
    unittest.main()
