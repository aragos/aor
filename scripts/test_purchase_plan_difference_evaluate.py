"""Synthetic selection/TEST barrier and grouped-policy evidence; no retained model/data."""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock

import numpy as np

import purchase_plan_difference_data as data
import purchase_plan_difference_evaluate as evaluation
from test_purchase_plan_difference_data import corpus_files


def policy_fixture():
    origins, starts, stops, plans, labels, decisions = [], [0]*2048, [0]*2048, [], {}, []
    for i in range(2048):
        ready = i >= 1792
        n = 2 if ready and i % 2 == 0 else 1 if ready else 0
        own = [dict(index=p, status='SUPPORTED', aliasOf=None) for p in range(n)]
        if ready:
            own.extend(dict(index=p, status='SUPPORTED', aliasOf=0) for p in range(n,8))
        origins.append(dict(index=i,status='READY' if ready else 'MISSING', plans=own, informative=n==2))
        starts[i] = len(plans); plans.extend(range(n)); stops[i] = len(plans)
        if ready:
            labels[i] = {0:[[.4,.375,.5,0]]*4}
            if n == 2: labels[i][1] = [[.6,.625,.5,0]]*4
            decisions.append(dict(originIndex=i,status='READY',selectedIndex=1 if n==2 else 0,frozen67Index=0))
    cache = SimpleNamespace(arrays=dict(starts=np.asarray(starts),stops=np.asarray(stops),planIndex=np.asarray(plans)))
    selected = np.asarray([.6 if p else .4 for p in plans]); frozen = np.full(len(plans), .4)
    return dict(origins=origins),cache,decisions,selected,frozen,labels


class EvidenceTest(unittest.TestCase):
    def test_no_alternative_world_stays_in_policy_denominator(self):
        report = evaluation.summarize(*policy_fixture())
        self.assertEqual(256, report['selectedMinusG3']['reward']['count'])
        self.assertAlmostEqual(.1, report['selectedMinusG3']['reward']['mean'])
        self.assertEqual(128, report['pairedMse']['selected']['count'])
        self.assertEqual(128, report['noAlternativeOrigins'])

    def test_return_changes_cannot_reselect_the_sealed_action(self):
        args = list(policy_fixture())
        args[-1][1792][1] = [[0,0,0,1]]*4
        row = evaluation.summarize(*args)['rows'][0]
        self.assertEqual(1, row['selectedExecution'])
        self.assertAlmostEqual(-.4, row['selectedMinusG3']['reward'])

    def test_alias_selected_index_does_not_count_as_intervention(self):
        args = list(policy_fixture()); args[2][0]['selectedIndex'] = 2
        report = evaluation.summarize(*args)
        self.assertFalse(report['rows'][0]['intervention'])
        self.assertEqual(127, report['interventions'])

    def test_missing_worlds_are_not_zero_effects(self):
        args = list(policy_fixture())
        for i in range(1792,1800):
            args[0]['origins'][i].update(status='MISSING', plans=[])
            args[2][i-1792].update(status='MISSING',selectedIndex=None,frozen67Index=None)
            args[-1][i] = {}
        report = evaluation.summarize(*args)
        self.assertEqual(248, report['selectedMinusG3']['reward']['count'])
        self.assertEqual(8, report['statusCounts']['MISSING'])


class TestBarrierTest(unittest.TestCase):
    def prepare(self, root):
        corpus, names = corpus_files(root)
        self.enterContext(mock.patch.object(data.states,'dictionary_names',return_value=names))
        self.enterContext(mock.patch.object(data,'DICTIONARY',corpus['dictionarySha256']))
        cache = data.build_cache(root/'corpus.json',root/'features','TEST_FEATURES')
        model = root/'selected.pt'; model.write_bytes(b'synthetic fixed checkpoint')
        selected = dict(kind='purchase-plan-paired-difference-selection-1',unchanged=False,arm='head',epoch=2,
            testPermitted=True,testInspected=False,warmCheckpointSha256=data.CHECKPOINT,
            dictionarySha256=data.DICTIONARY,sourceSha256=corpus['sourceSha256'],corpusSha256=data.sha(root/'corpus.json'),
            modelFile=model.name,modelSha256=data.sha(model))
        data.document(root/'selected.json',selected)
        data.document(root/'predictor.json',dict(kind='purchase-plan-difference-predictor-1',
            checkpointSha256=selected['modelSha256'],warmCheckpointSha256=data.CHECKPOINT,
            dictionarySha256=data.DICTIONARY,selectionSha256=data.sha(root/'selected.json'),
            corpusSha256=data.sha(root/'corpus.json')))
        values=np.full(len(cache.arrays['rowOrigin']),.5)
        np.save(root/'selected.npy',values); np.save(root/'frozen67.npy',values)
        data.document(root/'decisions.json',data.choices(cache,corpus,values,values))
        receipt=dict(kind='purchase-plan-difference-test-predictions-1',
            selection=dict(path=str(root/'selected.json'),sha256=data.sha(root/'selected.json')),
            predictionsComplete=True,semanticLabelsDecoded=False,corpusSha256=data.sha(root/'corpus.json'),
            cacheSha256=data.sha(cache.directory/'cache.json'),modelSha256=selected['modelSha256'],
            dictionarySha256=data.DICTIONARY,testIds=list(range(1792,2048)),
            predictorDescriptorSha256=data.sha(root/'predictor.json'),
            files={n:data.sha(root/n) for n in ('selected.npy','frozen67.npy','decisions.json')})
        data.document(root/'predictions.json',receipt)
        return cache,receipt

    def test_semantic_labels_require_complete_persisted_predictions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);cache,_=self.prepare(root)
            labels=data.test_labels(root/'corpus.json',cache,root/'predictions.json')
            self.assertEqual(set(range(1792,2048)),set(labels))
            self.assertTrue(np.isnan(cache.arrays['deltaTarget']).all())

    def test_changed_prediction_bytes_rejected_before_label_decode(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);cache,_=self.prepare(root)
            np.save(root/'selected.npy',np.zeros(len(cache.arrays['rowOrigin'])))
            with mock.patch.object(data,'documents',side_effect=AssertionError('Label archive opened')):
                with self.assertRaises(ValueError): data.test_labels(root/'corpus.json',cache,root/'predictions.json')

    def test_changed_selection_cannot_open_test(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);cache,_=self.prepare(root)
            selected=data.read(root/'selected.json');selected['epoch']=3
            data.document(root/'selected.json',selected,immutable=False)
            with mock.patch.object(data,'documents',side_effect=AssertionError('Label archive opened')):
                with self.assertRaises(ValueError): data.test_labels(root/'corpus.json',cache,root/'predictions.json')

    def test_unchanged_validation_winner_never_builds_test_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);_,_=self.prepare(root)
            selected=data.read(root/'selected.json')
            selected.update(unchanged=True,arm='unchanged',epoch=0,testPermitted=False,modelSha256=data.CHECKPOINT)
            data.document(root/'selected.json',selected,immutable=False)
            with mock.patch.object(data,'build_cache',side_effect=AssertionError('TEST features opened')):
                report=evaluation.evaluate(root/'selected.json',root/'corpus.json',root/'evaluation',lambda:None,
                    checkpoint='unused',dictionary='unused')
            self.assertFalse(report['testLabelsDecoded'])


if __name__ == '__main__': unittest.main()
