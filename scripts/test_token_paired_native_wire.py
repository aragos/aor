"""Opt-in constructed native wire proof; refuses any scientific collection or retained game."""
import os
from pathlib import Path
from types import SimpleNamespace
import unittest

import token_paired_campaign_plan as p
import token_paired_corpus as corpus
import token_paired_data as data
import token_paired_policy as policy
import token_paired_storage as storage


@unittest.skipUnless(os.environ.get('AOR_TOKEN_PAIRED_UNIT_WIRE'), 'Explicit constructed native fixture required.')
class NativeWireTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=Path(os.environ['AOR_TOKEN_PAIRED_UNIT_WIRE'])
        marker=p.read(cls.root/'constructed-fixture.json')
        p.require(marker['kind']=='token-paired-wire-unit-1' and marker['constructedOnly'] is True and
            marker['newGames']==marker['fits']==0 and marker['scientificShardComplete'] is False and
            marker['inventedCanonicalAndTerminalRows'] is True,
            'This bridge accepts only explicit invented source fixtures, never scientific data.')
        cls.marker=marker;cls.native=p.read(cls.root/'prepared.json')
        path=cls.root/cls.native['originalActor']['snapshot']
        cls.actor=data.FrozenActor(path.read_bytes(),cls.native['originalActor']['sha256'])
        cls.dictionary=(cls.root/'dictionary.json').read_bytes()
        cls.context=data.Context(iteration=1,source_sha256=p.file_sha(cls.root/'constructed-fixture.json'),
            scope_sha256=cls.native['scopeSha256'],request_bank_sha256=p.digest(cls.native['origins']),
            dictionary_sha256=cls.native['dictionary']['sha256'],
            dictionary_semantic_sha256=p.digest(p.read(cls.root/'dictionary.json')),
            original_model_sha256=cls.actor.sha256,current_model_sha256=cls.actor.sha256)
        cls.context.bind(cls.actor,cls.actor,cls.dictionary)

    def records(self):
        plan=SimpleNamespace(record={'iterations':[{'origins':self.native['origins']}]})
        for receipt in self.marker['origins']:
            for key in ('origin','public','labels'):
                self.assertEqual(p.file_sha(self.root/receipt[key]['file']),receipt[key]['sha256'])
            raw=p.read(self.root/receipt['public']['file'])
            storage.public_record(raw,receipt,self.native,plan,receipt['globalIndex'])
            typed=data.PublicRoot.from_record(corpus.normalized_public(raw),self.context,self.actor.model,split='TRAIN')
            yield receipt,raw,typed

    def test_actual_native_ready_singleton_and_missing_wire(self):
        values=list(self.records())
        self.assertEqual([row[2].record['status'] for row in values],['READY','SINGLETON','MISSING'])
        self.assertEqual([len(data.AnchorWorld.from_record(corpus.normalized_anchor(row[1]),self.actor.model).observations)
            for row in values],[1,1,1])

    def test_actual_native_paired_terminal_sidecars(self):
        for receipt,raw,typed in self.records():
            target=corpus.normalized_labels(p.read(self.root/receipt['labels']['file']),raw,typed)
            if typed.ready:
                result=data.PairedTarget.from_record(typed,target)
                self.assertEqual(result.deltas[0],0.)
                self.assertEqual(len(result.rewards),len(typed.record['proposedIndices']))
            else:self.assertIsNone(target)

    def test_actual_native_full_offer_probabilities_match_python(self):
        actor=policy.TokenPairedPolicy(self.actor.model)
        for _,_,typed in self.records():
            value=typed.record
            if value['status']=='MISSING':continue
            logp,_=actor([value['observation']])
            self.assertLessEqual(max(abs(a-b) for a,b in zip(logp[0].exp().tolist(),value['currentProbabilities'])),2e-12)


if __name__=='__main__':unittest.main()
