"""Invented arrays/world identifiers only; no scientific model, stream or forward."""
import copy
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import coordinated_es_math as es
import token_focus_numeric as numeric
from test_coordinated_es_projection import original_actors


def write(path, value):
    path = Path(path)
    path.write_bytes(es.canonical(value))
    return dict(path=str(path), sha256=numeric.wire.sha(path.read_bytes()))


def read(item):
    return json.loads(Path(item['path']).read_bytes())


def absolute(owner, item):
    return dict(path=str(Path(owner).parent / item['path']), sha256=item['sha256'])


class ActiveMathTests(unittest.TestCase):
    def directions(self):
        return [[0.] * 192 + [1. if index == direction else 0. for index in range(64)]
                for direction in range(16)]

    def test_active64_normalization_is_one_while_full256_rms_is_one_half(self):
        result = numeric.active_step(self.directions(), [.25] * 16)
        self.assertEqual(result['coordinates'][:192], [0.] * 192)
        self.assertAlmostEqual(es.rms(result['coordinates'][192:]), 1.)
        self.assertAlmostEqual(es.rms(result['coordinates']), .5)
        self.assertEqual(result['gradient'], [.25 / 32] * 16 + [0.] * 48)
        old = es.step_coordinates([0.] * 256, self.directions(), [.25] * 16)
        self.assertAlmostEqual(es.rms(old['coordinates'][192:]), 2.)

    def test_nonzero_inactive_coordinates_cannot_enter_the_step(self):
        values = self.directions()
        values[0][191] = .01
        with self.assertRaisesRegex(ValueError, 'final64'):
            numeric.active_step(values, [.25] * 16)

    def test_zero_gradient_is_an_operational_stop(self):
        with self.assertRaisesRegex(es.OperationalStop, 'zero-gradient'):
            numeric.active_step(self.directions(), [0.] * 16)

    def test_nonfinite_gradient_is_an_operational_stop(self):
        with self.assertRaises(es.OperationalStop):
            numeric.active_step(self.directions(), [float('inf')] * 16)

    def test_direction_draw_is16_by64_then_exact_zero_extension_without_renormalization(self):
        rows = np.arange(16 * 64, dtype=np.float64).reshape(16, 64) / 100
        class InventedGenerator:
            def standard_normal(self, shape, dtype):
                if shape != (16, 64) or dtype is not np.float64:
                    raise AssertionError('Wrong declared Gaussian shape or dtype.')
                return rows
        with patch.object(numeric.np.random, 'Generator', return_value=InventedGenerator()):
            table, receipt = numeric.generate_directions(es.Scope('a' * 64, 'b' * 64, 'c' * 64), 97)
        self.assertEqual(table.vectors[0][:192], (0.,) * 192)
        self.assertEqual(table.vectors[0][192:], tuple(rows[0]))
        self.assertEqual(receipt['drawShape'], [16, 64])
        self.assertFalse(receipt['renormalized'])

    def test_linux_owner_cannot_run_the_actual_mac_numerical_cli(self):
        with patch.object(numeric.platform, 'system', return_value='Linux'):
            with self.assertRaisesRegex(ValueError, 'Mac'):
                numeric.mac_owner()


class NumericProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.actors = original_actors()
        output = numeric.Output(cls.root / 'invented-origin')
        item = output.bundle('', {head: actor.raw for head, actor in cls.actors.items()})
        cls.origin = absolute(output.path / 'receipt.json', item)
        cls.asset = write(cls.root / 'asset.json', dict(kind='coordinated-inference-asset-1',
            currentBundle=dict(path='not-the-numerical-origin.json', sha256='d' * 64),
            originalBundle=dict(path='original-subtraction-reference.json', sha256='e' * 64),
            calibration=dict(path='unchanged-calibration.json', sha256='f' * 64)))
        cls.schedule_value = dict(kind='token-focus-step-schedule-1', projectionSeed=97, directionSeed=101,
            banks=dict(training=dict(count=16, seeds=list(range(16))),
                       holdout=dict(count=128, seeds=list(range(16, 144)))))
        cls.schedule = write(cls.root / 'schedule.json', cls.schedule_value)
        cls.protocol = write(cls.root / 'protocol.json', dict(kind='token-focus-step-campaign-1', study=96,
            numericRecipe=numeric.RECIPE, methodSha256=numeric.METHOD_SHA,
            originWeightsSha256=cls.origin['sha256'], inferenceAssetSha256=cls.asset['sha256'],
            seedSchedule=cls.schedule, scheduleSha256=cls.schedule['sha256'], reservedSchedule=cls.schedule_value))
        cls.prepare_request = dict(kind='token-focus-step-numeric-prepare-1', protocol=cls.protocol,
            seedSchedule=cls.schedule, originBundle=cls.origin, inferenceAsset=cls.asset)
        request = write(cls.root / 'prepare-request.json', cls.prepare_request)
        cls.prep_root = cls.root / 'prepared'
        numeric.run('prepare', request['path'], cls.prep_root, owner_guard=lambda: None)
        cls.prep = dict(path=str(cls.prep_root / 'receipt.json'),
                        sha256=numeric.wire.sha((cls.prep_root / 'receipt.json').read_bytes()))
        cls.center0 = absolute(cls.prep['path'], read(cls.prep)['center'])
        request = write(cls.root / 'population-request.json', dict(kind='token-focus-step-numeric-population-1',
            preparation=cls.prep, center=cls.center0))
        cls.pop_root = cls.root / 'population'
        numeric.run('population', request['path'], cls.pop_root, owner_guard=lambda: None)
        cls.pop_receipt = dict(path=str(cls.pop_root / 'receipt.json'),
            sha256=numeric.wire.sha((cls.pop_root / 'receipt.json').read_bytes()))
        cls.pop_item = absolute(cls.pop_receipt['path'], read(cls.pop_receipt)['population'])
        cls.pop = read(cls.pop_item)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.case = self.root / self._testMethodName
        self.case.mkdir()

    def execute(self, mode, value, name='result'):
        request = write(self.case / (name + '-request.json'), value)
        root = self.case / name
        numeric.run(mode, request['path'], root, owner_guard=lambda: None)
        return dict(path=str(root / 'receipt.json'), sha256=numeric.wire.sha((root / 'receipt.json').read_bytes()))

    def outcomes(self):
        return dict(kind='token-focus-step-outcomes-1', complete=True, verified=True,
            populationSha256=self.pop_item['sha256'], generationBankSha256=self.pop['generationBankSha256'],
            worldSeeds=list(self.pop['worldSeeds']), members=[dict(index=row['index'], direction=row['direction'],
                sign=row['sign'], bundleSha256=row['bundle']['sha256'],
                winShares=[1.] * 8 + [0.] * 8 if row['sign'] == 1 else [.25] * 16)
                for row in self.pop['members']])

    def update(self, outcomes=None):
        item = write(self.case / 'outcomes.json', self.outcomes() if outcomes is None else outcomes)
        return self.execute('update', dict(kind='token-focus-step-numeric-update-1',
            preparation=self.prep, population=self.pop_receipt, outcomes=item))

    def test_explicit_numerical_origin_is_distinct_from_asset_current_and_reference(self):
        value = read(self.prep)
        self.assertEqual(value['numericalOriginBundleSha256'], self.origin['sha256'])
        self.assertEqual(value['selectorOriginalBundleSha256'], 'e' * 64)
        self.assertNotEqual(value['numericalOriginBundleSha256'], 'd' * 64)
        origin = read(absolute(self.prep['path'], value['originBundle']))
        for head in es.HEADS:
            self.assertEqual(Path(absolute(self.prep_root / 'origin/bundle.json',
                {key: origin['heads'][head.value][key] for key in ('path', 'sha256')})['path']).read_bytes(),
                self.actors[head].raw)

    def test_all32_mutants_preserve_cpe_raw_bytes_and_all_nonactor_metadata(self):
        self.assertEqual([row['id'] for row in self.pop['members']],
            [f'{sign}_{index:02d}' for index in range(16) for sign in ('PLUS', 'MINUS')])
        for row in self.pop['members']:
            item = absolute(self.pop_item['path'], row['bundle'])
            bundle = read(item)
            for head in es.HEADS:
                source = Path(absolute(item['path'], {key: bundle['heads'][head.value][key]
                    for key in ('path', 'sha256')})['path']).read_bytes()
                if head is not es.Head.TOKEN:
                    self.assertEqual(source, self.actors[head].raw)
                model = json.loads(source)
                original = self.actors[head].model
                self.assertEqual({k: v for k, v in model.items() if k not in numeric.wire.ACTOR_BLOCKS},
                    {k: v for k, v in original.items() if k not in numeric.wire.ACTOR_BLOCKS})

    def test_population_retains_one_shared_copy_of_each_immutable_head(self):
        self.assertEqual(len(list((self.pop_root / 'members/shared').glob('*/policy.json'))), 3)
        self.assertEqual(len(list((self.pop_root / 'members/tokens').glob('*/policy.json'))), 32)
        self.assertEqual(len(list(self.pop_root.rglob('policy.json'))), 35)

    def test_one_update_uses_only_mean_win_difference_and_active64_rms(self):
        receipt = read(self.update())
        table = read(absolute(self.prep['path'], read(self.prep)['directions']))
        gradient = [math.fsum(.25 * row[index] for row in table['vectors']) / 32
                    for index in range(192, 256)]
        expected = [value / es.rms(gradient) for value in gradient]
        center = read(absolute(self.case / 'result/receipt.json', receipt['center']))
        self.assertEqual(center['coordinates'][:192], [0.] * 192)
        for actual, wanted in zip(center['coordinates'][192:], expected):
            self.assertAlmostEqual(actual, wanted, places=13)
        self.assertEqual(receipt['memberWinMeans'], [.5, .25] * 16)
        self.assertAlmostEqual(receipt['step']['fullCenterRms'], .5)

    def test_only_completed_update_can_supply_the_final_mean(self):
        updated = self.update()
        receipt = self.execute('final', dict(kind='token-focus-step-numeric-final-1',
            preparation=self.prep, update=updated), name='final')
        value = read(receipt)
        self.assertEqual(value['update'], updated)
        self.assertEqual(value['center'], absolute(updated['path'], read(updated)['center']))
        bundle = read(absolute(receipt['path'], value['bundle']))
        for head in es.HEADS[:-1]:
            self.assertEqual(Path(absolute(self.case / 'final/bundle.json',
                {key: bundle['heads'][head.value][key] for key in ('path', 'sha256')})['path']).read_bytes(),
                self.actors[head].raw)

    def test_arbitrary_standalone_final_center_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unexpected or missing'):
            self.execute('final', dict(kind='token-focus-step-numeric-final-1',
                preparation=self.prep, center=self.center0))

    def test_final_rejects_a_forged_opposite_step_even_with_refreshed_receipt_hashes(self):
        updated = self.update()
        receipt = read(updated)
        center_item = absolute(updated['path'], receipt['center'])
        center = read(center_item)
        opposite = center['coordinates'][:192] + [-number for number in center['coordinates'][192:]]
        changed = write(center_item['path'], dict(center, coordinates=opposite))
        receipt['files'][receipt['center']['path']] = changed['sha256']
        receipt['center'] = dict(receipt['center'], sha256=changed['sha256'])
        receipt['step'] = dict(receipt['step'], coordinates=opposite)
        forged = write(updated['path'], receipt)
        with self.assertRaisesRegex(ValueError, 'sole verified win-only'):
            self.execute('final', dict(kind='token-focus-step-numeric-final-1',
                preparation=self.prep, update=forged), name='final')

    def test_stale_origin_binding_stops_before_factor_generation(self):
        protocol = read(self.protocol)
        protocol['originWeightsSha256'] = 'a' * 64
        with patch.object(numeric.projection, 'generate') as draw:
            with self.assertRaisesRegex(ValueError, 'numerical origin'):
                self.execute('prepare', dict(self.prepare_request,
                    protocol=write(self.case / 'wrong-protocol.json', protocol)))
            draw.assert_not_called()

    def test_duplicate_numeric_stream_stops_before_factor_generation(self):
        schedule = copy.deepcopy(self.schedule_value)
        schedule['directionSeed'] = schedule['projectionSeed']
        subject = write(self.case / 'wrong-schedule.json', schedule)
        protocol = write(self.case / 'protocol.json', dict(read(self.protocol), seedSchedule=subject,
            scheduleSha256=subject['sha256'], reservedSchedule=schedule))
        with patch.object(numeric.projection, 'generate') as draw:
            with self.assertRaisesRegex(ValueError, 'distinct positive'):
                self.execute('prepare', dict(self.prepare_request,
                    protocol=protocol, seedSchedule=subject))
            draw.assert_not_called()

    def test_overlapping_native_world_banks_stop_before_factor_generation(self):
        schedule = copy.deepcopy(self.schedule_value)
        schedule['banks']['holdout']['seeds'][0] = schedule['banks']['training']['seeds'][0]
        subject = write(self.case / 'wrong-worlds.json', schedule)
        protocol = write(self.case / 'protocol.json', dict(read(self.protocol), seedSchedule=subject,
            scheduleSha256=subject['sha256'], reservedSchedule=schedule))
        with patch.object(numeric.projection, 'generate') as draw:
            with self.assertRaisesRegex(ValueError, '144 distinct'):
                self.execute('prepare', dict(self.prepare_request,
                    protocol=protocol, seedSchedule=subject))
            draw.assert_not_called()

    def test_a_resealed_valid_different_schedule_cannot_bypass_the_frozen_protocol(self):
        schedule = copy.deepcopy(self.schedule_value)
        schedule['projectionSeed'] = 113
        subject = write(self.case / 'different-valid-schedule.json', schedule)
        with patch.object(numeric.projection, 'generate') as draw:
            with self.assertRaisesRegex(ValueError, 'exact subject and body'):
                self.execute('prepare', dict(self.prepare_request, seedSchedule=subject))
            draw.assert_not_called()

    def test_wrong_world_order_cannot_supply_fitness(self):
        value = self.outcomes()
        value['worldSeeds'].reverse()
        with self.assertRaisesRegex(ValueError, 'original complete'):
            self.update(value)

    def test_reordered_members_cannot_supply_fitness(self):
        value = self.outcomes()
        value['members'][0], value['members'][1] = value['members'][1], value['members'][0]
        with self.assertRaisesRegex(ValueError, 'substituted or reordered'):
            self.update(value)

    def test_incomplete16_world_member_cannot_supply_fitness(self):
        value = self.outcomes()
        value['members'][-1]['winShares'].pop()
        with self.assertRaisesRegex(ValueError, 'wrong fixed dimension'):
            self.update(value)

    def test_rank_or_reward_fields_cannot_change_the_win_only_objective(self):
        value = self.outcomes()
        value['members'][0]['rewards'] = [1.] * 16
        with self.assertRaisesRegex(ValueError, 'unexpected or missing'):
            self.update(value)

    def test_zero_gradient_emits_no_usable_next_center(self):
        value = self.outcomes()
        for row in value['members']:
            row['winShares'] = [.25] * 16
        with self.assertRaisesRegex(es.OperationalStop, 'zero-gradient'):
            self.update(value)
        self.assertFalse((self.case / 'result/center.json').exists())

    def test_preparation_does_not_advance_global_numpy_rng(self):
        before = np.random.get_state()
        self.execute('prepare', self.prepare_request)
        after = np.random.get_state()
        self.assertEqual(before[0], after[0])
        self.assertTrue(np.array_equal(before[1], after[1]))
        self.assertEqual(before[2:], after[2:])


if __name__ == '__main__':
    unittest.main()
