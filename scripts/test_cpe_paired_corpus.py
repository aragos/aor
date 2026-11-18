"""Constructed boundary checks; no retained worlds, model forwards or optimizer execution."""
import copy
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import cpe_paired_campaign_plan as p
import cpe_paired_corpus as c
from test_token_paired_corpus import public as token_public, labels as token_labels


def public():
    value=token_public()
    value.update(kind='cpe-paired-public-1',bundleScopeSha256='e'*64,
        request=p.request(0,'PURCHASE'),branchingWindows=2,selectedOrdinal=1)
    value['streams']['reservoirSeed']=14
    value['publicRoot'].update(referenceIndex=1,sampledCommandIndex=1,proposedIndices=[1,0])
    return value


def labels(value):
    result=token_labels(value)
    result.update(kind='cpe-paired-labels-1',bundleScopeSha256=value['bundleScopeSha256'])
    result['arms']=list(reversed(result['arms']))
    return result


def typed(value):
    record=c.normalized_public(value)
    return SimpleNamespace(record=record,sha256=p.digest(record))


class CorpusTests(unittest.TestCase):
    def test_network_prior_and_current_sampled_reference_remain_distinct(self):
        value=public();result=c.normalized_public(value)
        self.assertEqual(result['head'],'PURCHASE')
        self.assertEqual(result['observation']['baselineIndex'],0)
        self.assertEqual((result['sampledIndex'],result['referenceIndex']),(1,1))
        self.assertNotIn('actorSeed',result)
        self.assertNotIn('observedWindows',result)
        self.assertNotIn('behaviorValue',result)

    def test_labels_follow_sampled_reference_order(self):
        value=public();result=c.normalized_labels(labels(value),value,typed(value))
        self.assertEqual([row['actionIndex'] for row in result['actions']],[1,0])
        self.assertEqual(result['actions'][0]['rewards'],[1.,1.])

    def test_uniform_correction_uses_reference_not_network_prior(self):
        value=public();root=value['publicRoot'];root['inclusionProbability']=.8
        result=labels(value);result['arms'][1]['inclusionProbability']=.8
        c.normalized_labels(result,value,typed(value))
        result['arms'][0]['inclusionProbability']=.8
        with self.assertRaisesRegex(ValueError,'sampled reference'):
            c.normalized_labels(result,value,typed(value))

    def test_terminal_labels_are_checked_against_controlled_seat_scores(self):
        value=public();result=labels(value)
        result['arms'][0]['outcomes'][0]['terminal']['reward']=.25
        with self.assertRaisesRegex(ValueError,'terminal label'):
            c.normalized_labels(result,value,typed(value))

    def test_labels_cannot_cross_head_or_bundle_scope(self):
        value=public();result=labels(value);result['bundleScopeSha256']='f'*64
        with self.assertRaisesRegex(ValueError,'bundleScope'):
            c.normalized_labels(result,value,typed(value))

    def test_missing_root_keeps_position_without_invented_return(self):
        value=public();result=labels(value)
        value.update(status='MISSING',publicRoot=None);result.update(status='MISSING',arms=[])
        self.assertIsNone(c.normalized_labels(result,value,typed(value)))

    def test_original_anchor_cannot_cross_assigned_head(self):
        value=public();value['anchors']=[dict(head='CARD',iteration=1,globalIndex=0)]
        with self.assertRaisesRegex(ValueError,'assigned head'):
            c.normalized_anchor(value,'f'*64)

    def test_validation_seal_failure_precedes_public_or_label_decode(self):
        corpus=object.__new__(c.Corpus)
        with patch('cpe_paired_validation.validate_seal_pair',side_effect=ValueError('unsealed')):
            with patch.object(corpus,'public') as public_read,patch.object(corpus,'_labels') as label_read:
                with self.assertRaisesRegex(ValueError,'unsealed'):
                    corpus.validation_labels(None,None,{}, {})
                public_read.assert_not_called();label_read.assert_not_called()


if __name__=='__main__':unittest.main()
