"""Synthetic60 chunk/receipt/storage checks; no native execution or real game evidence."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_experiments as exp
import joint_chrono_evidence as evidence


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.protocol = dict(ordinary='M8',expansion='E',prior='F0',candidateBundle='chronological',
                             anchorBundle='null-card-G2',workers=10,
            banks={name:dict(nativeOffset=60000000+i,count=4096,seeds=exp.arena_seeds(60000000+i,4096))
                   for i,name in enumerate(('confirm','heldout'))})

    def pair(self,index=0,global_index=None):
        global_index = index if global_index is None else global_index
        game = dict(wins=[1.,0.,0.,0.],rewards=[1.,.1,.1,.1],ranks=[1.,2.,3.,4.],
                    scores=[100,None,50,20],decisions=15)
        return dict(index=index,configuration=exp.CONFIGURATION,
                    seed=self.protocol['banks']['confirm']['seeds'][global_index],seat=index%4,
                    opponentIndices=[(index//4+seat)%4 for seat in range(4)],
                    candidate=deepcopy(game),incumbent=deepcopy(game),jointPolicy=dict(roles='UNIT'))

    def chunk(self):
        directory = self.root/'chunk'
        (directory/'native').mkdir(parents=True)
        exp.document(directory/'native/state.json',dict(phase='complete'))
        exp.document(directory/'native/evaluation.json',dict(complete=True))
        exp.document(directory/'native/finished.json',dict(at='UNIT'))
        (directory/'native/pair-0.json').write_text('UNIT original pair\n')
        (directory/'native.log').write_text('UNIT log\n')
        return directory

    def test_source_preserves_exact_joint_roles_and_mixed_opponent_indices(self):
        source = evidence.source(self.protocol,'confirm',0)
        self.assertEqual(source['jointPolicy'],dict(baseline='F0',sharedBundle='null-card-G2',
                         candidateBundle='chronological',incumbentBundle='null-card-G2'))
        self.assertEqual(source['tokenOrder']['opponentSelectors'],
                         ['ORDINARY','ORDINARY','FIXED_16_TO_15','FIXED_16_TO_15'])
        self.assertEqual(source['expansionImitation']['opponentModels'],[None,'E',None,'E'])
        self.assertNotIn('frozenPurchase',source)

    def test_heldout_rivals_all_use_expansion_and_fixed_tokens(self):
        source = evidence.source(self.protocol,'heldout',0)
        self.assertEqual(source['expansionImitation']['opponentModels'],['E']*4)
        self.assertEqual(source['tokenOrder']['opponentSelectors'],['FIXED_16_TO_15']*4)
        self.assertEqual(source['purpose'],'CONFIRMATION')

    def test_all_chunks_preserve_the_whole_stage_seed_bank_and_global_lineups(self):
        seeds=[]
        for chunk in range(8):
            source=evidence.source(self.protocol,'confirm',chunk)
            seeds.extend(exp.arena_seeds(source['seed'],512))
            for index in range(512):
                global_index=chunk*512+index
                self.assertEqual(index%4,global_index%4)
                self.assertEqual([(index//4+s)%4 for s in range(4)],[(global_index//4+s)%4 for s in range(4)])
        self.assertEqual(seeds,self.protocol['banks']['confirm']['seeds'])

    def test_replays_cover_all_seats_with_local_chunk_indices(self):
        self.assertEqual([evidence.replay_slot(i) for i in evidence.REPLAYS],[(0,0),(2,341),(5,170),(7,511)])
        self.assertEqual([i%4 for i in evidence.REPLAYS],[0,1,2,3])

    def test_source_refuses_changed_bank_formula(self):
        self.protocol['banks']['confirm']['seeds'][1]+=1
        with self.assertRaisesRegex(ValueError,'pre-reserved'):
            evidence.source(self.protocol,'confirm',0)

    def test_source_slot_rejects_changed_role(self):
        source=evidence.source(self.protocol,'confirm',0)
        source['jointPolicy']['sharedBundle']='coadapted'
        with self.assertRaisesRegex(ValueError,'all actor roles'):
            evidence.source_slot(self.protocol,source)

    def test_compaction_keeps_local_identity_and_every_terminal_field_including_null(self):
        pair=self.pair(341,1365);before=deepcopy(pair)
        actual=evidence.compact_pair(pair,dict(roles='UNIT'),341,1365,pair['seed'],'pairhash','sourcehash')
        self.assertEqual(actual['index'],341)
        self.assertEqual(actual['globalIndex'],1365)
        self.assertEqual(actual['candidate'],pair['candidate'])
        self.assertNotIn('jointPolicy',actual)
        self.assertEqual(pair,before)

    def test_compaction_checks_each_descriptor_before_discarding_it(self):
        pair=self.pair();pair['jointPolicy']['roles']='CHANGED'
        with self.assertRaisesRegex(ValueError,'descriptor'):
            evidence.compact_pair(pair,dict(roles='UNIT'),0,0,pair['seed'],'pairhash','sourcehash')

    def test_complete_chunk_metrics_and_all_pair_hashes_are_verified(self):
        source=self.root/'source.json';exp.document(source,evidence.source(self.protocol,'confirm',0))
        directory=self.root/'native';directory.mkdir()
        pairs=[self.pair(i) for i in range(512)]
        for index,pair in enumerate(pairs):exp.document(directory/f'pair-{index}.json',pair)
        metrics=evidence.search.comparison(pairs)
        report=dict(complete=True,purpose='CONFIRMATION',promoted=False,humanStrengthVerified=False,
                    overall=metrics,byConfiguration={exp.CONFIGURATION:metrics})
        exp.document(directory/'evaluation.json',report)
        exp.document(directory/'run.json',dict(kind='strategy-arena',signature='UNIT',source=str(source),
                                               workers=10,pairs=512,seed=60000000,opponentModels=4))
        exp.document(directory/'state.json',dict(kind='strategy-arena',signature='UNIT',phase='complete'))
        exp.document(directory/'finished.json',dict(at='UNIT'))
        with patch.object(evidence,'verify_roles',return_value=dict(roles='UNIT')):
            actual=evidence.verify_chunk(self.protocol,source,directory)
        self.assertEqual(actual['metrics'],metrics)
        self.assertEqual(len(actual['rows']),512)
        self.assertEqual(actual['rows'][-1]['pairSha256'],exp.sha(directory/'pair-511.json'))

    def test_archive_verifies_every_member_before_pruning_originals(self):
        directory=self.chunk();expected={name:dict(item) for name,item in evidence.inventory(directory).items()}
        target=self.root/'chunk.tar.gz'
        receipt=evidence.archive(directory,target)
        self.assertFalse(directory.exists())
        self.assertEqual(evidence.verify_archive(receipt['receipt'])['files'],expected)
        self.assertEqual(receipt['archive'],'chunk.tar.gz')

    def test_archive_is_portable_without_original_directory(self):
        receipt=evidence.archive(self.chunk(),self.root/'chunk.tar.gz')
        portable=self.root/'portable';portable.mkdir()
        for name in ('chunk.tar.gz','chunk.tar.gz.json'):(self.root/name).rename(portable/name)
        self.assertEqual(evidence.verify_archive(portable/'chunk.tar.gz.json')['archiveSha256'],receipt['archiveSha256'])

    def test_changed_original_preserves_all_loose_evidence(self):
        directory=self.chunk();original=evidence.storage.verify_members
        def changed(*args):
            original(*args)
            (directory/'native/pair-0.json').write_text('UNIT changed after archive')
        with patch.object(evidence.storage,'verify_members',side_effect=changed),self.assertRaisesRegex(ValueError,'changed before'):
            evidence.archive(directory,self.root/'chunk.tar.gz')
        self.assertTrue((directory/'native/state.json').exists())
        self.assertTrue((directory/'native/pair-0.json').exists())

    def test_symlink_is_rejected_without_deleting_any_original(self):
        directory=self.chunk();(directory/'linked').symlink_to(directory/'native/state.json')
        with self.assertRaisesRegex(ValueError,'symlinks'):
            evidence.archive(directory,self.root/'chunk.tar.gz')
        self.assertTrue((directory/'native/state.json').exists())

    def test_archive_rejects_partial_native_output(self):
        directory=self.chunk();(directory/'native/state.json').write_text(json.dumps(dict(phase='running')))
        with self.assertRaisesRegex(ValueError,'completed native'):
            evidence.archive(directory,self.root/'chunk.tar.gz')
        self.assertTrue(directory.exists())

    def test_archive_receipt_rejects_parent_path_members(self):
        receipt=evidence.archive(self.chunk(),self.root/'chunk.tar.gz')
        path=Path(receipt['receipt']);document=exp.read(path)
        document['files']['../escape']=document['files'].pop('native.log');path.write_text(json.dumps(document))
        with self.assertRaisesRegex(ValueError,'canonical relative'):
            evidence.verify_archive(path)

    def test_incidental_finder_metadata_is_left_loose_not_deleted(self):
        directory=self.chunk();(directory/'.DS_Store').write_bytes(b'UNIT Finder')
        receipt=evidence.archive(directory,self.root/'chunk.tar.gz')
        self.assertNotIn('.DS_Store',receipt['files'])
        self.assertEqual((directory/'.DS_Store').read_bytes(),b'UNIT Finder')
        self.assertEqual(list(directory.iterdir()),[directory/'.DS_Store'])


if __name__ == '__main__':
    unittest.main()
