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

    def successor_protocol(self):
        return dict(self.protocol, jointRivalProfile='g3-successor-league-1',
                    anchorBundle='learned-G3',
                    rivalBundles=dict(C64='raw-card-C64', G1='raw-card-G1', G2='raw-card-G2', G3='learned-G3'))

    def test_successor_confirmation_uses_the_fixed_four_bundle_league_twice(self):
        protocol = self.successor_protocol()
        sources = [evidence.source(protocol,'confirm',chunk) for chunk in range(8)]
        self.assertEqual([source['jointPolicy']['sharedBundle'] for source in sources],
                         ['raw-card-C64','raw-card-G1','raw-card-G2','learned-G3'] * 2)
        self.assertTrue(all(source['jointPolicy']['incumbentBundle'] == 'learned-G3' for source in sources))
        self.assertTrue(all(source['jointPolicy']['candidateBundle'] == 'chronological' for source in sources))

    def test_successor_confirmation_keeps_all_sides_and_rivals_on_exact_expansion_tokens(self):
        source = evidence.source(self.successor_protocol(),'confirm',0)
        self.assertEqual(source['expansionImitation'], dict(candidateModel='E',incumbentModel='E',opponentModels=['E']*4))
        self.assertEqual(source['tokenOrder'], dict(candidateSelector='FIXED_16_TO_15',
            incumbentSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']*4))

    def test_successor_heldout_has_only_the_actual_g3_shared_bundle(self):
        protocol = self.successor_protocol()
        self.assertEqual([evidence.source(protocol,'heldout',chunk)['jointPolicy']['sharedBundle']
                          for chunk in range(8)], ['learned-G3']*8)

    def test_omitted_profile_ignores_new_protocol_metadata_and_keeps_all_legacy_sources_identical(self):
        extra = dict(self.protocol,rivalBundles=self.successor_protocol()['rivalBundles'])
        for stage in evidence.STAGES:
            for chunk in range(8):
                self.assertEqual(evidence.source(self.protocol,stage,chunk), evidence.source(extra,stage,chunk))

    def test_unknown_explicit_profile_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Unsupported explicit'):
            evidence.source(dict(self.successor_protocol(),jointRivalProfile='unknown'),'confirm',0)

    def test_incomplete_successor_league_is_rejected(self):
        protocol = self.successor_protocol()
        del protocol['rivalBundles']['G1']
        with self.assertRaisesRegex(ValueError,'four explicit'):
            evidence.source(protocol,'confirm',0)

    def test_changed_successor_chunk_bundle_is_not_another_valid_source(self):
        protocol = self.successor_protocol()
        source = evidence.source(protocol,'confirm',1)
        source['jointPolicy']['sharedBundle'] = protocol['rivalBundles']['G3']
        with self.assertRaisesRegex(ValueError,'all actor roles'):
            evidence.source_slot(protocol,source)

    def successor_evidence(self, *, anchor_card=True):
        prior = self.root/'F0.json'
        exp.document(prior,dict(synthetic='prior'))
        guard = patch.object(evidence.ecology,'F0',exp.sha(prior))
        guard.start();self.addCleanup(guard.stop)
        def bundle(name,card):
            components = {}
            for phase in evidence.joint.PHASES:
                if phase == 'card' and not card:
                    components[phase] = None
                    continue
                path = self.root/f'{name}-{phase}.json'
                exp.document(path,dict(encoderVersion=f'aor-{phase}-observation-1',
                    stateFeatureNames=['synthetic-state'], actionFeatureNames=['synthetic-action'], role=name))
                components[phase] = dict(path=path.name,sha256=exp.sha(path))
            path = self.root/f'{name}-bundle.json'
            exp.document(path,dict(schemaVersion=1,kind='joint-card-purchase-1',configuration=exp.CONFIGURATION,
                baselineSha256=exp.sha(prior),**components))
            return str(path)
        rivals = {name:bundle(name, anchor_card if name == 'G3' else False) for name in ('C64','G1','G2','G3')}
        protocol = dict(self.successor_protocol(),prior=str(prior),anchorBundle=rivals['G3'],
                        candidateBundle=bundle('candidate',True),rivalBundles=rivals)
        source = self.root/'source.json'
        exp.document(source,evidence.source(protocol,'confirm',0))
        expected = evidence.joint.joint_binding(source)
        directory = self.root/'snapshots';directory.mkdir()
        subjects = [expected['source'],expected['baseline']]
        for role in ('sharedBundle','candidateBundle','incumbentBundle'):
            current = expected[role]
            subjects += [current] + [current[p] for p in evidence.joint.PHASES if current[p] is not None]
        for item in subjects:
            (directory/item['snapshot']).write_bytes(Path(item['path']).read_bytes())
        return protocol,source,directory,expected

    def test_successor_roles_bind_actual_bundle_components_and_homogeneous_overlays(self):
        protocol,source,directory,expected = self.successor_evidence()
        with patch.object(evidence.ecology,'verify_overlays') as overlays, \
             patch.object(evidence.legacy,'verify_transferred_sources'):
            actual = evidence.verify_roles(protocol,source,directory,[dict(jointPolicy=expected)])
        self.assertEqual(expected,actual)
        self.assertEqual(evidence.ecology.ArenaRivalEcology.HOMOGENEOUS,overlays.call_args.kwargs['rival_ecology'])
        self.assertIsNotNone(actual['incumbentBundle']['card'])
        self.assertIsNone(actual['sharedBundle']['card'])

    def test_successor_rejects_null_card_g3_anchor(self):
        protocol,source,directory,expected = self.successor_evidence(anchor_card=False)
        with self.assertRaisesRegex(ValueError,'learned-card G3'):
            evidence.verify_roles(protocol,source,directory,[dict(jointPolicy=expected)])

    def test_successor_rejects_changed_raw_component_snapshot(self):
        protocol,source,directory,expected = self.successor_evidence()
        (directory/expected['incumbentBundle']['card']['snapshot']).write_text('{}')
        with self.assertRaisesRegex(ValueError,'snapshots changed'):
            evidence.verify_roles(protocol,source,directory,[dict(jointPolicy=expected)])

    def test_successor_rejects_consistently_relabelled_wrong_g3_bundle(self):
        protocol,source,directory,expected = self.successor_evidence()
        protocol['rivalBundles']['G3'] = protocol['candidateBundle']
        with self.assertRaisesRegex(ValueError,'same actual learned-card G3'):
            evidence.verify_roles(protocol,source,directory,[dict(jointPolicy=expected)])

    def test_successor_rejects_changed_original_model_component(self):
        protocol,source,directory,expected = self.successor_evidence()
        Path(expected['incumbentBundle']['purchase']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError,'component changed'):
            evidence.verify_roles(protocol,source,directory,[dict(jointPolicy=expected)])

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
