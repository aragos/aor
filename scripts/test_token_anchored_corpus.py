"""Invented score/public/sidecar fixtures only; no native/model/game execution."""
import copy
from types import SimpleNamespace
import unittest

import token_anchored_corpus as owner
from test_token_anchored_policy import fixture
from test_token_paired_win_objective import game


def public_fixture():
    training, _, _ = fixture(); record = training.roots[0].record
    root = dict(number=record['decisionNumber'], batchStartNumber=record['batchStartNumber'],
        sampledCommandIndex=record['sampledIndex'], actorSeed=123, botSeed=456,
        behaviorValue=.5, behaviorLogProbability=-.1,
        **{key: record[key] for key in ('observation', 'currentProbabilities', 'originalProbabilities',
            'proposedIndices', 'anchor94Probabilities', 'referenceIndex', 'rawReferenceIndex',
            'mandatoryIndices', 'inclusionProbabilities')})
    return dict(kind='token-anchored-paired-public-1', signature='a'*64, scopeSha256='b'*64,
        scienceSha256='c'*64, round=1, request=dict(globalIndex=0, seat=0, epoch=1, ordinal=1, split='TRAIN'),
        streams=dict(gameSeed=10, proposalSeed=11, future=record['futureSeeds']), status='READY',
        originSha256='d'*64, missingReason=None, observedWindows=1, publicRoot=root, anchors=[])


def labels_fixture(public):
    result = {key: public[key] for key in ('signature', 'scopeSha256', 'scienceSha256', 'round',
        'request', 'streams', 'status', 'originSha256')}
    result.update(kind='token-anchored-paired-labels-1', arms=[])
    for index in public['publicRoot']['proposedIndices']:
        outcomes = []
        for replicate, seed in enumerate(public['streams']['future']):
            terminal = game([4, 3, 2, 1] if index == 0 else [1, 4, 2, 3])['terminal']
            terminal['decisions'] = terminal.pop('gameDecisions'); terminal['continuationSeed'] = seed
            outcomes.append(dict(replicate=replicate, terminal=terminal,
                canonicalSha256='f'*64, replayVerified=True))
        result['arms'].append(dict(actionIndex=index,
            command=public['publicRoot']['observation']['actions'][index]['command'],
            inclusionProbability=public['publicRoot']['inclusionProbabilities'][index], outcomes=outcomes))
    return result


def typed(public):
    return SimpleNamespace(record=owner.normalized_public(public), sha256='e'*64, ready=public['status'] == 'READY')


class CorpusTests(unittest.TestCase):
    def test_projection_keeps_both_reference_roles_but_excludes_actor_rng_and_returns(self):
        value = owner.normalized_public(public_fixture())
        self.assertEqual(value['referenceIndex'], 1); self.assertEqual(value['rawReferenceIndex'], 0)
        for key in ('actorSeed', 'botSeed', 'behaviorValue', 'behaviorLogProbability', 'arms', 'observedWindows'):
            self.assertNotIn(key, value)

    def test_pure_win_labels_come_only_from_authoritative_terminal_winners(self):
        public = public_fixture(); result = owner.normalized_labels(labels_fixture(public), public, typed(public))
        self.assertEqual(result['actions'], [dict(actionIndex=1, winShares=[0., 0.]),
            dict(actionIndex=0, winShares=[1., 1.]), dict(actionIndex=2, winShares=[0., 0.])])

    def test_reward_and_rank_fields_are_never_read(self):
        public = public_fixture(); labels = labels_fixture(public)
        for arm in labels['arms']:
            for tail in arm['outcomes']:
                tail['terminal']['reward'] = object(); tail['terminal']['rankReward'] = object()
        result = owner.normalized_labels(labels, public, typed(public))
        self.assertEqual(result['actions'][1]['winShares'], [1., 1.])

    def test_mandatory_arm_cannot_retain_a_random_arm_inclusion_weight(self):
        public = public_fixture(); labels = labels_fixture(public)
        labels['arms'][1]['inclusionProbability'] = .5
        with self.assertRaisesRegex(ValueError, 'inclusion'):
            owner.normalized_labels(labels, public, typed(public))

    def test_wrong_future_seed_is_rejected(self):
        public = public_fixture(); labels = labels_fixture(public)
        labels['arms'][0]['outcomes'][0]['terminal']['continuationSeed'] += 1
        with self.assertRaisesRegex(ValueError, 'same-stream'):
            owner.normalized_labels(labels, public, typed(public))

    def test_false_terminal_winner_share_is_rejected(self):
        public = public_fixture(); labels = labels_fixture(public)
        labels['arms'][1]['outcomes'][0]['terminal']['winShare'] = 0.
        with self.assertRaisesRegex(ValueError, 'authoritative winners'):
            owner.normalized_labels(labels, public, typed(public))

    def test_sealed_diagnostic_split_is_rejected_before_any_label_structure(self):
        public = public_fixture(); public['request']['split'] = 'VALIDATION'
        with self.assertRaisesRegex(ValueError, 'sealed diagnostic'):
            owner.normalized_labels({'not': 'a label'}, public, typed(public))

    def test_legacy_label_contract_is_not_reinterpreted_as_warm(self):
        public = public_fixture(); labels = labels_fixture(public); labels['kind'] = 'token-paired-labels-1'
        with self.assertRaisesRegex(ValueError, 'legacy label'):
            owner.normalized_labels(labels, public, typed(public))


if __name__ == '__main__':
    unittest.main()
