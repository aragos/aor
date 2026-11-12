"""Synthetic public/label/storage boundaries; no retained inputs or native games."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import purchase_plan_difference_data as data

NAMES = ['purchase.decision.purchase-owner-exit', 'purchase.role.0.cash', 'purchase.role.0.chaos']


def public():
    plans = []
    for i, kind in enumerate(data.KINDS):
        canonical = i if i < 2 else 0
        plans.append(dict(index=i, kind=kind, actions=[dict(decision='purchase-advances', command='pass' if canonical == 0 else 'buy medicine')],
            status='SUPPORTED', exclusion=None, leaf=dict(context='OWNER_EXIT',
                stateFeatures={NAMES[0]: 1., NAMES[1]: .3 + canonical * .1}, exactValue=None, exactReason=None),
            aliasOf=None if i < 2 else 0))
    return dict(version='purchase-plan-ranking-1', originIndex=0, status='READY', plans=plans)


def outcome(seed, reward=.3):
    return dict(reward=reward, winShare=(reward - .1) / .8, rankReward=.5,
        scores=[dict(player=f'p{i+1}', total=1) for i in range(4)], decisions=100, continuationSeed=seed)


def labels():
    origin = data.metadata(public(), 1, 'a'*64, 'b'*64, 'signature', 123)
    streams = [11, 12, 13, 14]
    tails, files = [], {}
    for p in (0, 1):
        for s, seed in enumerate(streams):
            name = f'tail-0-{p}-{s}.json'
            files['tails/' + name] = dict(sha256='c'*64, size=0)
            tails.append(dict(planIndex=p, replica=s, continuationSeed=seed, canonicalFile=name,
                canonicalSha256='c'*64, outcome=outcome(seed, .3 + .2*p)))
    result = dict(originIndex=0, status='READY', logicalPlanExecutions={str(i): i if i < 2 else 0 for i in range(8)}, tails=tails)
    return result, origin, dict(futureStreams=[streams], files=files)


class PublicBoundaryTest(unittest.TestCase):
    def test_original_public_owner_exit_and_aliases_are_accepted(self):
        value = public()
        self.assertIs(data.public_origin(value, 0, NAMES), value)

    def test_hidden_outcome_field_is_rejected(self):
        value = public(); value['terminalReward'] = .9
        with self.assertRaises(ValueError): data.public_origin(value, 0, NAMES)

    def test_action_metadata_never_becomes_an_input_column(self):
        value = public(); value['plans'][0]['leaf']['stateFeatures']['planIndex'] = 0.
        with self.assertRaises(ValueError): data.public_origin(value, 0, NAMES)

    def test_card_feature_is_rejected_at_purchase_endpoint(self):
        value = public(); value['plans'][0]['leaf']['stateFeatures']['card.cash'] = 1.
        with self.assertRaises(ValueError): data.public_origin(value, 0, NAMES + ['card.cash'])

    def test_other_purchase_decision_flag_is_rejected(self):
        value = public(); value['plans'][0]['leaf']['stateFeatures']['purchase.decision.purchase-relief'] = 1.
        with self.assertRaises(ValueError): data.public_origin(value, 0, NAMES + ['purchase.decision.purchase-relief'])

    def test_alias_cannot_change_the_endpoint(self):
        value = public(); value['plans'][2]['leaf']['stateFeatures'][NAMES[1]] += .1
        with self.assertRaises(ValueError): data.public_origin(value, 0, NAMES)

    def test_exact_chaos_is_retained_with_its_public_fact(self):
        value = public()
        for plan in value['plans']:
            if plan['index'] != 1:
                plan['leaf'].update(exactValue=0., exactReason='OWN_CHAOS')
                plan['leaf']['stateFeatures'][NAMES[2]] = 1.
        self.assertEqual(data.public_origin(value, 0, NAMES)['plans'][0]['leaf']['exactValue'], 0.)

    def test_no_alternative_ready_world_is_not_pair_informative(self):
        value = public()
        value['plans'][1].update(actions=copy.deepcopy(value['plans'][0]['actions']), leaf=copy.deepcopy(value['plans'][0]['leaf']), aliasOf=0)
        data.public_origin(value, 0, NAMES)
        row = data.metadata(value, 1, 'a'*64, 'b'*64, 's', 1)
        self.assertEqual(row['status'], 'READY')
        self.assertFalse(row['informative'])
        self.assertEqual(row['canonicalPlans'], 1)

    def test_boolean_plan_index_is_rejected(self):
        value = public(); value['plans'][1]['index'] = True
        with self.assertRaises(ValueError): data.public_origin(value, 0, NAMES)

    def test_missing_origin_remains_missing(self):
        value = dict(version='purchase-plan-ranking-1', originIndex=0, status='MISSING', plans=[])
        data.public_origin(value, 0, NAMES)
        self.assertFalse(data.metadata(value, 8, 'a'*64, 'b'*64, 's', 1)['informative'])


class LabelBoundaryTest(unittest.TestCase):
    def test_all_four_paired_returns_are_retained(self):
        result, origin, chunk = labels()
        samples = data.paired_labels(result, origin, chunk)
        self.assertEqual(set(samples), {0, 1})
        self.assertEqual(len(samples[1]), 4)
        self.assertAlmostEqual(sum(a[0] - b[0] for a, b in zip(samples[1], samples[0])) / 4, .2)

    def test_changed_future_identity_is_rejected(self):
        result, origin, chunk = labels(); result['tails'][0]['continuationSeed'] = 99
        with self.assertRaises(ValueError): data.paired_labels(result, origin, chunk)

    def test_missing_sibling_replica_is_rejected(self):
        result, origin, chunk = labels(); result['tails'].pop()
        with self.assertRaises(ValueError): data.paired_labels(result, origin, chunk)

    def test_changed_composite_reward_is_rejected(self):
        result, origin, chunk = labels(); result['tails'][0]['outcome']['reward'] = .99
        with self.assertRaises(ValueError): data.paired_labels(result, origin, chunk)

    def test_chaos_cannot_be_given_positive_terminal_reward(self):
        result, origin, chunk = labels(); result['tails'][0]['outcome']['scores'][0]['total'] = None
        with self.assertRaises(ValueError): data.paired_labels(result, origin, chunk)


class StorageBoundaryTest(unittest.TestCase):
    def test_opaque_outcome_bytes_are_not_json_decoded_for_features(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); archive = root / 'evidence.tar.gz'
            members = {'public/public-0.json': b'{"safe":true}', 'tails/result-0.json': b'NOT SEMANTIC JSON'}
            with tarfile.open(archive, 'w:gz') as target:
                for name, value in members.items():
                    info = tarfile.TarInfo(name); info.size = len(value); target.addfile(info, io.BytesIO(value))
            chunk = dict(archive=archive.name, archiveSha256=data.sha(archive),
                files={n: dict(sha256=hashlib.sha256(v).hexdigest(), size=len(v)) for n, v in members.items()})
            self.assertEqual(list(data.documents(root, chunk, {'public/public-0.json'})), [('public/public-0.json', {'safe': True})])

    def test_archive_verifies_exact_bytes_before_pruning(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'public').mkdir(); path = root / 'public/public-0.json'; path.write_bytes(b'original')
            files = {'public/public-0.json': dict(sha256=data.sha(path), size=8)}
            receipt = data.archive(root, files)
            self.assertFalse(path.exists())
            data.storage.verify_members(root / receipt['archive'], files, receipt['archiveSha256'])

    def test_changed_original_refuses_archive_without_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); path = root / 'member'; path.write_bytes(b'original')
            files = {'member': dict(sha256=data.sha(path), size=8)}; path.write_bytes(b'changed!')
            with self.assertRaises(ValueError): data.archive(root, files)
            self.assertEqual(path.read_bytes(), b'changed!')

    def test_relative_member_cannot_escape_owner(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError): data.relative(temporary, '../other')


# This full-shaped corpus contains only deterministic synthetic public rows and outcomes.
def corpus_files(root):
    names = NAMES + [f'purchase.synthetic.{i}' for i in range(7244-len(NAMES))]
    dictionary = root / 'dictionary.json'
    data.document(dictionary, {'synthetic': names})
    members, values = {}, []
    for index in range(256):
        value = public(); value['originIndex'] = index
        result, _, _ = labels(); result['originIndex'] = index
        for tail in result['tails']:
            tail['canonicalFile'] = f'tail-{index}-{tail["planIndex"]}-{tail["replica"]}.json'
            raw = b'{}'
            members['tails/' + tail['canonicalFile']] = raw
            tail['canonicalSha256'] = hashlib.sha256(raw).hexdigest()
        members[f'public/public-{index}.json'] = json.dumps(value, sort_keys=True).encode()
        members[f'tails/result-{index}.json'] = json.dumps(result, sort_keys=True).encode()
        values.append(value)
    archive = root / 'synthetic.tar.gz'
    with tarfile.open(archive, 'w:gz') as target:
        for name, raw in members.items():
            info = tarfile.TarInfo(name); info.size = len(raw); target.addfile(info, io.BytesIO(raw))
    files = {n: dict(sha256=hashlib.sha256(raw).hexdigest(), size=len(raw)) for n, raw in members.items()}
    chunks, origins = [], []
    for c in range(1, 9):
        seeds = list(range((c-1)*256, c*256))
        chunks.append(dict(index=c, split=data.split((c-1)*256), sourceSha256='b'*64,
            signature='synthetic', gameSeeds=seeds, futureStreams=[[11,12,13,14]]*256,
            archive=archive.name, archiveSha256=data.sha(archive), files=files))
        origins.extend(data.metadata(value, c, files[f'public/public-{i}.json']['sha256'], 'b'*64,
            'synthetic', seeds[i]) for i, value in enumerate(values))
    corpus = dict(kind=data.KIND, dictionaryFile=dictionary.name, dictionary=data.read(dictionary),
        dictionarySha256=data.sha(dictionary), stateFeatureNames=names, warmCheckpointSha256=data.CHECKPOINT,
        sourceSha256='e'*64, origins=origins, chunks=chunks)
    data.document(root/'corpus.json', corpus)
    return corpus, names


class CacheIntegrationTest(unittest.TestCase):
    def test_actual_cache_builder_retains_empty_test_ranges_at_final_development_cursor(self):
        from unittest import mock
        import purchase_plan_difference_fit as fit
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); corpus, names = corpus_files(root)
            with mock.patch.object(data.states, 'dictionary_names', return_value=names), \
                 mock.patch.object(data, 'DICTIONARY', corpus['dictionarySha256']), \
                 mock.patch.object(fit.frozen, 'DICTIONARY', corpus['dictionarySha256']):
                cache = data.build_cache(root/'corpus.json', root/'development', 'DEVELOPMENT')
                groups = fit.validate_cache(cache, corpus, names)
                self.assertEqual(1536, len(groups['TRAIN']))
                self.assertEqual(256, len(groups['VALIDATION']))
                self.assertTrue((cache.arrays['starts'][1792:] == 3584).all())
                self.assertTrue((cache.arrays['stops'][1792:] == 3584).all())
                self.assertEqual(set(cache.arrays['rowOrigin']), set(range(1792)))

    def test_test_features_never_call_label_decoder(self):
        from unittest import mock
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); corpus, names = corpus_files(root)
            with mock.patch.object(data.states, 'dictionary_names', return_value=names), \
                 mock.patch.object(data, 'DICTIONARY', corpus['dictionarySha256']), \
                 mock.patch.object(data, 'paired_labels', side_effect=AssertionError('TEST labels opened')):
                cache = data.build_cache(root/'corpus.json', root/'test-features', 'TEST_FEATURES')
                self.assertEqual(set(cache.arrays['rowOrigin']), set(range(1792,2048)))
                self.assertTrue((cache.arrays['starts'][:1792] == 0).all())
                self.assertTrue(__import__('numpy').isnan(cache.arrays['deltaTarget']).all())


if __name__ == '__main__':
    unittest.main()
