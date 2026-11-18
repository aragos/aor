"""Synthetic state-only projection/portable archive boundaries; no real corpus or outcomes."""
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import joint_state_value_data as data


def fixture():
    dictionary = dict(version=data.VERSION, stateFeatureNames=['card.cash', 'purchase.cash'],
                      units='unchanged-existing-phase-encoder-units', missingValue=0.)
    game = dict(index=0, signature='source-path-signature', gameSeed=17, controlledSeat=0,
        cardDecisions=[dict(number=2, turn=1, observation=dict(stateFeatures={'cash': .2},
                            chosenAction='private actor action must not be encoded'))],
        purchaseDecisions=[dict(number=5, turn=1, observation=dict(stateFeatures={'cash': .4}))],
        terminal=dict(reward=.8, winShare=1., rankReward=0., gameDecisions=6))
    identity = dict(version=data.VERSION, signature=game['signature'], originIndex=0,
                    sourceGameSha256='native-hash', dictionarySha256='dictionary-hash')
    state = dict(identity, rows=[dict(number=2, turn=1, phase='CARD', sourceRow=0, stateFeatures={'card.cash': .2}),
                                dict(number=5, turn=1, phase='PURCHASE', sourceRow=0, stateFeatures={'purchase.cash': .4})])
    label = dict(identity, terminal=copy.deepcopy(game['terminal']))
    coverage = dict(identity, independentWorlds=1, cardDecisions=1, purchaseDecisions=1,
                    worldCoverage=dict(zeroCardDecisions=False, zeroPurchaseDecisions=False))
    return game, state, label, coverage, dictionary


def project(values):
    return data.project_world(*values, game_sha256='native-hash', dictionary_sha256='dictionary-hash')


class StateProjectionTest(unittest.TestCase):
    def test_only_original_prefixed_state_values_enter_projection(self):
        rows, reward, counts = project(fixture())
        self.assertEqual([{'card.cash': .2}, {'purchase.cash': .4}], [row['stateFeatures'] for row in rows])
        self.assertEqual(.8, reward)
        self.assertEqual({'CARD': 1, 'PURCHASE': 1}, counts)

    def test_future_label_cannot_be_inserted_as_a_feature(self):
        values = fixture(); values[1]['rows'][0]['stateFeatures']['reward'] = .8
        with self.assertRaisesRegex(ValueError, 'exactly reproduce'): project(values)

    def test_unlisted_source_feature_is_rejected_even_if_sidecar_matches(self):
        values = fixture(); values[0]['cardDecisions'][0]['observation']['stateFeatures']['terminal'] = .8
        values[1]['rows'][0]['stateFeatures']['card.terminal'] = .8
        with self.assertRaisesRegex(ValueError, 'phase encoder'): project(values)

    def test_wrong_phase_or_source_row_cannot_relabel_an_observation(self):
        values = fixture(); values[1]['rows'][0]['phase'] = 'PURCHASE'
        with self.assertRaisesRegex(ValueError, 'exactly reproduce'): project(values)

    def test_terminal_and_state_provenance_must_match(self):
        values = fixture(); values[2]['sourceGameSha256'] = 'other-world'
        with self.assertRaisesRegex(ValueError, 'provenance'): project(values)

    def test_terminal_objective_must_match_the_original_world(self):
        values = fixture(); values[2]['terminal']['reward'] = .1
        with self.assertRaisesRegex(ValueError, 'terminal objective'): project(values)

    def test_empty_phase_is_retained_without_an_invented_state(self):
        values = fixture(); values[0]['cardDecisions'] = []; values[1]['rows'] = values[1]['rows'][1:]
        values[3]['cardDecisions'] = 0; values[3]['worldCoverage']['zeroCardDecisions'] = True
        rows, _, counts = project(values)
        self.assertEqual(1, len(rows)); self.assertEqual(0, counts['CARD'])

    def test_zero_row_world_keeps_its_separate_terminal_label(self):
        values = fixture(); values[0]['cardDecisions'] = []; values[0]['purchaseDecisions'] = []; values[1]['rows'] = []
        values[3].update(cardDecisions=0, purchaseDecisions=0, worldCoverage=dict(zeroCardDecisions=True, zeroPurchaseDecisions=True))
        rows, reward, counts = project(values)
        self.assertEqual([], rows); self.assertEqual(.8, reward); self.assertEqual({'CARD': 0, 'PURCHASE': 0}, counts)

    def test_zero_feature_row_is_an_actual_decision_not_an_empty_world(self):
        values = fixture(); values[0]['cardDecisions'][0]['observation']['stateFeatures'] = {}; values[1]['rows'][0]['stateFeatures'] = {}
        rows, _, counts = project(values)
        self.assertEqual({}, rows[0]['stateFeatures']); self.assertEqual(1, counts['CARD'])

    def test_world_split_is_fixed_by_chunk_before_outcomes(self):
        self.assertEqual(['training', 'training', 'validation', 'validation', 'test', 'test'],
                         [data.split(n) for n in (1, 12, 13, 14, 15, 16)])

    def test_encoder_retains_units_and_excludes_boolean_metadata(self):
        self.assertEqual(([0, 1], [-3.5, .25]), data.encoded_state({'b': .25, 'a': -3.5}, {'a': 0, 'b': 1}))
        with self.assertRaisesRegex(ValueError, 'finite values'): data.encoded_state({'a': True}, {'a': 0})

    def test_portable_reader_rejects_absolute_native_paths(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, 'inside their package'): data.relative_file(root, '/native/mac/source.json')
            with self.assertRaisesRegex(ValueError, 'inside their package'): data.relative_file(root, '../source.json')


class ArchiveTest(unittest.TestCase):
    def test_stream_hashes_remaining_model_snapshots_without_reopening_source_paths(self):
        game, state, label, coverage, _ = fixture()
        documents = [('collection/game-0.json.gz', gzip.compress(json.dumps(game).encode(), mtime=0))]
        documents += [(f'collection/joint-{name}-0.json', json.dumps(value).encode()) for name, value in [('state', state), ('label', label), ('coverage', coverage)]]
        documents.append(('collection/source.json', b'{"model":"/private/original/mac/model.json"}'))
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); path = root/'chunk.tar.gz'
            with tarfile.open(path, 'w:gz') as archive:
                for name, content in documents:
                    member = tarfile.TarInfo(name); member.size = len(content); archive.addfile(member, io.BytesIO(content))
            hashes = {name: hashlib.sha256(content).hexdigest() for name, content in documents}
            batch = dict(archive=path.name, archiveSha256=data.sha(path), files=hashes, signature=game['signature'])
            declared = dict(index=0, gameSeed=17, seat=0, files={k: v for k, v in hashes.items() if k!='collection/source.json'})
            records = list(data.iter_worlds(root, batch, [declared], lambda: None))
            self.assertEqual(game, records[0][1][0])

    def test_changed_retained_metadata_hash_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); path = root/'empty.tar.gz'
            with tarfile.open(path, 'w:gz') as archive:
                member = tarfile.TarInfo('source.json'); member.size = 2; archive.addfile(member, io.BytesIO(b'{}'))
            batch = dict(archive=path.name, archiveSha256=data.sha(path), files={'source.json': '0'*64})
            with self.assertRaisesRegex(ValueError, 'snapshot changed'):
                list(data.iter_worlds(root, batch, [], lambda: None))


class CacheTest(unittest.TestCase):
    def test_sparse_units_phase_identity_and_zero_row_world_survive_dense_projection(self):
        import numpy as np
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            arrays = dict(values=np.array([.2, 2., .4]), columns=np.array([0, 1, 2]),
                rowptr=np.array([0, 2, 3]), rowGame=np.array([0, 2]), phase=np.array([0, 1]),
                reward=np.array([.8, .2, .1]), starts=np.array([0, 1, 1]), stops=np.array([1, 1, 2]))
            for name, values in arrays.items(): np.save(root/(name+'.npy'), values)
            names = ['card.cash', 'card.tokens', 'purchase.cash', 'purchase.markers']
            (root/'finished.json').write_text(json.dumps(dict(kind='joint-state-value-csr-1',
                stateFeatureNames=names, files={path.name: data.sha(path) for path in root.glob('*.npy')})))
            cache = data.Cache(root)
            self.assertEqual(names, cache.feature_names)
            np.testing.assert_array_equal(cache.game_rows([1]), np.array([], dtype='int64'))
            np.testing.assert_array_equal(cache.dense(cache.game_rows([0, 2])),
                np.array([[.2, 2., 0., 0.], [0., 0., .4, 0.]], dtype='float32'))
            np.testing.assert_array_equal(cache.arrays['phase'], np.array([0, 1]))


if __name__ == '__main__': unittest.main()
