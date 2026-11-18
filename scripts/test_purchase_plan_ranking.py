"""UNIT synthetic orchestration/denominator proofs; no retained inputs, inference or games."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import automata_experiments as exp
import purchase_plan_ranking as ranking


def bank(ready=2):
    origins, decisions, results = [], [], []
    streams = [[10000+i*4+r for r in range(4)] for i in range(256)]
    for i in range(256):
        plans = [dict(index=p, status='SUPPORTED', exclusion=None, aliasOf=None) for p in range(2)] if i < ready else []
        status = 'READY' if i < ready else 'MISSING'
        origins.append(dict(originIndex=i, status=status, plans=plans))
        decisions.append(dict(originIndex=i, status=status, selectedIndex=1 if i < ready else None))
        tails = []
        for p in range(len(plans)):
            for r in range(4):
                value = .4 + p*.1
                outcome = dict(reward=value, winShare=value, rankReward=value,
                               continuationSeed=streams[i][r], scores=[dict(total=100)]*4)
                tails.append(dict(planIndex=p, replica=r, continuationSeed=streams[i][r], outcome=outcome))
        results.append(dict(originIndex=i, status=status,
            logicalPlanExecutions={str(p): p for p in range(len(plans))}, tails=tails))
    return origins, decisions, results, streams


class ReportTest(unittest.TestCase):
    def test_missing_origins_are_not_imputed_as_zero_effects(self):
        result = ranking.summarize(*bank())
        self.assertEqual(2, result['selectedMinusG3']['reward']['count'])
        self.assertAlmostEqual(.1, result['selectedMinusG3']['reward']['mean'])
        self.assertEqual(254, result['statusCounts']['MISSING'])

    def test_unchanged_selections_stay_in_primary_denominator(self):
        args = bank()
        args[1][0]['selectedIndex'] = 0
        result = ranking.summarize(*args)
        self.assertEqual(2, result['selectedMinusG3']['reward']['count'])
        self.assertAlmostEqual(.05, result['selectedMinusG3']['reward']['mean'])

    def test_four_streams_average_within_origin_before_interval(self):
        args = bank()
        for tail in args[2][0]['tails']:
            if tail['planIndex'] == 1:
                tail['outcome']['reward'] = [.4, .6, .8, 1.][tail['replica']]
        result = ranking.summarize(*args)
        self.assertEqual(2, result['selectedMinusG3']['reward']['count'])
        self.assertAlmostEqual(.2, result['selectedMinusG3']['reward']['mean'])

    def test_unsupported_pass_has_separate_secondary_denominator(self):
        args = bank()
        args[0][0]['plans'][1].update(status='UNSUPPORTED', exclusion='INFORMATION')
        args[1][0]['selectedIndex'] = 0
        args[2][0]['logicalPlanExecutions'] = {'0': 0}
        args[2][0]['tails'] = args[2][0]['tails'][:4]
        result = ranking.summarize(*args)
        self.assertEqual(2, result['selectedMinusG3']['reward']['count'])
        self.assertEqual(1, result['selectedMinusPass']['reward']['count'])

    def test_alias_only_root_is_retained_with_exact_shared_returns(self):
        args = bank()
        args[0][0]['plans'][1]['aliasOf'] = 0
        args[1][0]['selectedIndex'] = 1
        args[2][0]['logicalPlanExecutions']['1'] = 0
        args[2][0]['tails'] = args[2][0]['tails'][:4]
        result = ranking.summarize(*args)
        self.assertEqual(2, result['selectedMinusG3']['reward']['count'])
        self.assertEqual(1, result['aliases'])
        self.assertEqual(1, result['interventions'])
        self.assertAlmostEqual(.05, result['selectedMinusG3']['reward']['mean'])

    def test_missing_paired_sibling_is_rejected(self):
        args = bank()
        args[2][0]['tails'].pop()
        with self.assertRaises(ValueError): ranking.summarize(*args)

    def test_changed_future_stream_is_rejected(self):
        args = bank()
        args[2][0]['tails'][0]['continuationSeed'] += 1
        with self.assertRaises(ValueError): ranking.summarize(*args)

    def test_null_score_is_chaos_without_a_fabricated_score(self):
        args = bank()
        for tail in args[2][0]['tails'][4:]:
            tail['outcome']['scores'] = [dict(total=None), *[dict(total=100)]*3]
        result = ranking.summarize(*args)
        self.assertAlmostEqual(.5, result['selectedMinusG3']['chaos']['mean'])

    def test_labels_cannot_change_frozen_selection(self):
        args = bank()
        before = copy.deepcopy(args[1])
        for tail in args[2][0]['tails'][:4]: tail['outcome']['reward'] = 1.
        result = ranking.summarize(*args)
        self.assertEqual(before, args[1])
        self.assertEqual(1, result['rows'][0]['selectedIndex'])
        self.assertLess(result['rows'][0]['selectedMinusG3']['reward'], 0.)

    def test_zero_ready_origins_have_missing_metrics(self):
        result = ranking.summarize(*bank(0))
        self.assertEqual(dict(count=0, mean=None, lower95=None, upper95=None), result['selectedMinusG3']['reward'])


class SealTest(unittest.TestCase):
    def make_bank(self, root):
        public = root / 'public'
        public.mkdir()
        descriptor = root / 'descriptor.json'
        exp.document(descriptor, dict(synthetic=True))
        exp.document(public / 'manifest.json', dict(signature='fixture', inputs=[],
            predictorDescriptorSha256=exp.sha(descriptor)))
        artifacts = {'manifest.json': exp.sha(public / 'manifest.json')}
        states = {}
        for i in range(256):
            exp.document(public / f'public-{i}.json', dict(originIndex=i))
            # Not valid JSON: the prediction stage must never decode executor evidence.
            (public / f'backbone-{i}.json').write_bytes(b'opaque canonical executor bytes')
            for kind in ('public', 'backbone'):
                artifacts[f'{kind}-{i}.json'] = exp.sha(public / f'{kind}-{i}.json')
            states[str(i)] = artifacts[f'public-{i}.json']
        exp.document(public / 'finished.json', dict(kind='purchase-plan-public-complete-1',
            signature='fixture', origins=256, terminalTails=0, publicSha256=states,
            artifactSha256=artifacts))
        return public, descriptor

    def test_prediction_seal_never_decodes_private_backbones(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            public, descriptor = self.make_bank(root)
            with mock.patch('purchase_plan_predictor.FrozenPlanPredictor') as predictor, \
                 mock.patch('purchase_plan_predictor.rank_origin', side_effect=lambda _, row:
                    dict(originIndex=row['originIndex'], status='MISSING', selectedIndex=None)):
                path = ranking.predict_bank(public, descriptor, root / 'predictions')
            self.assertTrue(exp.read(path)['allPredictionsComplete'])
            self.assertFalse(exp.read(path)['terminalTailsStarted'])
            self.assertEqual(256, len(exp.read(path)['decisions']))
            predictor.return_value.unchanged.assert_called()

    def test_even_self_consistent_metadata_replacement_cannot_rebind_predictions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            public, descriptor = self.make_bank(root)
            def changed(_, row):
                if row['originIndex'] == 255:
                    path = public / 'finished.json'
                    path.write_bytes(path.read_bytes() + b'\n')
                return dict(originIndex=row['originIndex'], status='MISSING', selectedIndex=None)
            with mock.patch('purchase_plan_predictor.FrozenPlanPredictor'), \
                 mock.patch('purchase_plan_predictor.rank_origin', side_effect=changed):
                with self.assertRaises(ValueError):
                    ranking.predict_bank(public, descriptor, root / 'predictions')
            self.assertFalse((root / 'predictions').exists())


if __name__ == '__main__': unittest.main()
