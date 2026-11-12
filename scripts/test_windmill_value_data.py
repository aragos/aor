import json
from pathlib import Path
import tempfile
import unittest

import windmill_value as value
import windmill_value_data as data


def fixture(index=0):
    features = [0.] * 273
    features[value.FEATURE_NAMES.index('bias')] = 1.
    public = dict(version=data.VERSION, chunk=1, index=index, globalIndex=index,
                  split='TRAIN' if index < 192 else 'VALIDATION', sourceSha256='a'*64,
                  signature='signed', chanceSeeds=[10, 11, 12, 13], status='COLLECTED',
                  featureNames=list(value.FEATURE_NAMES), features=features, incumbentCommand='pass')
    labels = {key: public[key] for key in ('chunk', 'index', 'globalIndex', 'split',
              'sourceSha256', 'signature', 'chanceSeeds', 'status')}
    outcome = dict(reward=.5, winShare=.5, rankReward=.5, chaos=0)
    labels.update(labelKind=data.LABEL_KIND, publicLineSha256='b'*64,
                  actions={'pass': [outcome.copy() for _ in range(4)],
                           'windmill': [outcome.copy() for _ in range(4)]})
    return public, labels


class DataTest(unittest.TestCase):
    def test_exact_public_and_label_binding(self):
        public, labels = fixture()
        row = data.bind(public, labels, 'b'*64)
        self.assertEqual(row.originIndex, 0)
        self.assertEqual(row.incumbent, value.Action.PASS)
        self.assertEqual(len(row.attemptReturns), 4)

    def test_test_rejected_before_labels_are_touched(self):
        public, _ = fixture(224)
        public['split'] = 'TEST'
        with self.assertRaisesRegex(ValueError, 'TEST'):
            data.bind(public, None, 'b'*64)

    def test_test_rejected_before_source_file_access(self):
        with self.assertRaisesRegex(ValueError, 'TEST'):
            data.detached_labels(dict(chunk=1, index=224, split='TEST', path='/missing'), None, '')

    def test_test_rejected_before_label_json_decode(self):
        public = json.dumps(dict(chunk=1, index=224, split='TEST')).encode()
        with self.assertRaisesRegex(ValueError, 'TEST'):
            data.bind_lines(public, b'not a decoded label document')

    def test_altered_public_line_hash_is_rejected(self):
        public, labels = fixture()
        with self.assertRaisesRegex(ValueError, 'immutable public line'):
            data.bind(public, labels, 'c'*64)

    def test_paired_stream_order_cannot_change(self):
        public, labels = fixture()
        labels['chanceSeeds'] = list(reversed(labels['chanceSeeds']))
        with self.assertRaisesRegex(ValueError, 'chanceSeeds'):
            data.bind(public, labels, 'b'*64)

    def test_missing_origin_remains_without_label_or_features(self):
        public, labels = fixture()
        public.update(status='MISSING', features=None, incumbentCommand=None)
        labels.update(status='MISSING', actions={})
        row = data.bind(public, labels, 'b'*64)
        self.assertEqual(row.missingReason, 'NO_ROOT')
        self.assertIsNone(row.publicFeatures)

    def test_missing_origin_cannot_acquire_a_zero_label(self):
        public, labels = fixture()
        public.update(status='MISSING', features=None, incumbentCommand=None)
        labels['status'] = 'MISSING'
        with self.assertRaisesRegex(ValueError, 'Missing'):
            data.bind(public, labels, 'b'*64)

    def test_feature_order_is_exact(self):
        public, labels = fixture()
        public['featureNames'] = list(reversed(public['featureNames']))
        with self.assertRaisesRegex(ValueError, 'columns'):
            data.bind(public, labels, 'b'*64)

    def test_label_projection_omits_later_private_and_action_data(self):
        public, _ = fixture()
        terminal = dict(reward=.5, winShare=.5, rankReward=.5,
                        scores=[dict(player='p1', misery=10)], continuationSeed=0)
        raw = dict(kind='windmill-origin-2', request=dict(index=0), status='COLLECTED',
                   signature='signed', gameSeed=7, chanceSeeds=[10, 11, 12, 13],
                   provenance=dict(actor='p1', incumbentCommand='pass', exactPrefixAndIncumbentVerified=True),
                   arms=[dict(command=command, outcomes=[dict(replicate=i, replayVerified=True,
                         terminal=dict(terminal, continuationSeed=seed), canonicalEntries=['private'],
                         immediateOfferCard={'secret': 99}) for i, seed in enumerate([10, 11, 12, 13])])
                         for command in ('windmill', 'pass')])
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'origin.json'
            path.write_text(json.dumps(raw))
            public['sourceSha256'] = data.sha(path)
            subject = dict(chunk=1, index=0, split='TRAIN', path=str(path), sha256=data.sha(path),
                           status='COLLECTED', signature='signed', gameSeed=7, chanceSeeds=[10, 11, 12, 13])
            labels = data.detached_labels(subject, public, 'b'*64)
        self.assertEqual(len(labels['actions']['windmill']), 4)
        self.assertNotIn('secret', json.dumps(labels))
        self.assertNotIn('private', json.dumps(labels))


if __name__ == '__main__':
    unittest.main()
