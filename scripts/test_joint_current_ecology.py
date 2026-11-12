"""Synthetic CURRENT raw evidence, strict opt-in and unchanged legacy behavior; no native games."""
import gzip
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import joint_current_ecology as current
import joint_trajectory as joint
from test_joint_trajectory import fixture, put

b = joint.backend


def complete(directory, count=2, replay_indices=(0,)):
    done = joint.read(directory/'finished.json')
    done['gameSha256'] = [joint.sha(directory/f'game-{i}.json.gz') for i in range(count)]
    for kind, field in [('state', 'jointStateSha256'), ('label', 'jointLabelSha256'),
                        ('coverage', 'jointCoverageSha256')]:
        done[field] = [joint.sha(directory/f'joint-{kind}-{i}.json') for i in range(count)]
    done['canonicalReplaySha256'] = {f'replay-{i}.json': joint.sha(directory/f'replay-{i}.json')
                                     for i in replay_indices}
    done['jointCoverageSummarySha256'] = joint.sha(directory/'joint-coverage-summary.json')
    put(directory/'finished.json', done)


class CurrentReader(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.directory, self.bundle, _ = fixture(self.root)
        self.baseline = self.root/'M8.json'; self.expansion = self.root/'E.json'
        put(self.baseline, {'weights': {'synthetic-m8': 1.}})
        put(self.expansion, {'weights': {'synthetic-expansion': 2.}})
        self.source = joint.read(self.root/'source.json')
        self.source.update(baseline=str(self.baseline), jointStateChronology=current.VERSION,
            sharedPurchaseBaseline=str(self.root/'F.json'), experimentalRulesTransfer=current.RULES,
            continuationProtocol=current.CONTINUATION, replayIndices=[0],
            expansionImitation=dict(candidateModel=str(self.expansion), opponentModels=[str(self.expansion)]),
            tokenOrder=dict(candidateSelector='FIXED_16_TO_15', opponentSelectors=['FIXED_16_TO_15']))
        self.manifest = joint.read(self.directory/'collection.json')
        self.manifest.update(jointStateChronology=current.VERSION, continuationProtocol=current.CONTINUATION,
            rulesVersion='ah1996-three-epoch-5', dataVersion='ah1996-catalog-16', replayIndices=[0],
            experimentalRulesTransfer=dict(current.RULES, initializationOnly=True, qualificationTransferred=False),
            tokenOrder=self.source['tokenOrder'])
        def snapshot(path, name):
            shutil.copyfile(path, self.directory/name)
            return dict(path=str(path), sha256=joint.sha(path), snapshot=name)
        ordinary = snapshot(self.baseline, 'baseline.json')
        self.manifest['baseline'] = {key: ordinary[key] for key in ('path', 'sha256')}
        self.manifest['sharedPurchaseBaseline'] = snapshot(self.root/'F.json', 'shared-purchase-baseline.json')
        self.manifest['expansionImitation'] = dict(version='expansion-imitation-1', decision='expand',
            featureNames=current.EXPANSION_FEATURES,
            candidateModel=snapshot(self.expansion, 'expansion-imitation-candidate.json'),
            opponentModels=[snapshot(self.expansion, 'expansion-imitation-opponent-0.json')])
        self.publish_source()
        names = sorted(phase+'.'+name for phase in joint.PHASES
                       for name in self.manifest['bundle'][phase]['stateFeatureNames'])
        put(self.directory/'joint-state-dictionary.json', dict(version=current.VERSION,
            stateFeatureNames=names, units='unchanged-existing-phase-encoder-units', missingValue=0.))
        expected = dict(M8=joint.sha(self.baseline), F0=joint.sha(self.root/'F.json'),
            EXPANSION=joint.sha(self.expansion), DICTIONARY=joint.sha(self.directory/'joint-state-dictionary.json'),
            PHASE_DICTIONARIES={phase: b.digest({key: self.manifest['bundle'][phase][key]
                for key in ('stateFeatureNames', 'actionFeatureNames')}) for phase in joint.PHASES})
        for key, value in expected.items():
            guard = patch.object(current, key, value); guard.start(); self.addCleanup(guard.stop)
        self.publish_sidecars()

    def publish_source(self):
        put(self.root/'source.json', self.source)
        shutil.copyfile(self.root/'source.json', self.directory/'source.json')
        self.manifest['sourceSha256'] = joint.sha(self.root/'source.json')
        put(self.directory/'collection.json', self.manifest)

    def publish_sidecars(self):
        counts = {key: 0 for key in current.WORLD_FLAGS}
        for index in range(2):
            game = b.read_game(self.directory/f'game-{index}.json.gz', joint.GZIP_CONTRACT)
            provenance = dict(version=current.VERSION, signature=self.manifest['signature'], originIndex=index,
                sourceGameSha256=joint.sha(self.directory/f'game-{index}.json.gz'), dictionarySha256=current.DICTIONARY)
            rows = sorted([dict(number=row['number'], turn=row['turn'], phase=phase.upper(), sourceRow=i,
                stateFeatures={phase+'.'+key: value for key,value in row['observation']['stateFeatures'].items()})
                for phase in joint.PHASES for i,row in enumerate(game[phase+'Decisions'])], key=lambda row:row['number'])
            put(self.directory/f'joint-state-{index}.json', dict(provenance, rows=rows))
            put(self.directory/f'joint-label-{index}.json', dict(provenance, terminal=game['terminal']))
            flags = dict.fromkeys(current.WORLD_FLAGS, False)
            flags.update(zeroCardDecisions=not game['cardDecisions'], zeroPurchaseDecisions=not game['purchaseDecisions'])
            for key,value in flags.items(): counts[key] += value
            put(self.directory/f'joint-coverage-{index}.json', dict(provenance, independentWorlds=1,
                cardDecisions=len(game['cardDecisions']), purchaseDecisions=len(game['purchaseDecisions']),
                rows=[dict(number=row['number'],turn=row['turn']) for row in game['cardDecisions']],
                observedSelectedCommandQuotes=game.get('jointEvidence', []), worldCoverage=flags))
            if index in self.source.get('replayIndices', []):
                put(self.directory/f'replay-{index}.json', dict(kind='joint-canonical-replay-1',
                    signature=self.manifest['signature'], index=index, trajectorySha256=provenance['sourceGameSha256'],
                    replayVerified=True, header={'synthetic':True}, entries=[]))
        put(self.directory/'joint-coverage-summary.json', dict(kind='joint-collect', signature=self.manifest['signature'],
            independentWorlds=2, worldCounts=counts))
        complete(self.directory, replay_indices=self.source.get('replayIndices', []))

    def load(self):
        return joint.JointCollection(self.directory, self.bundle, ecology=joint.JointEcology.CURRENT)

    def test_current_separates_priors_and_preserves_exact_behavior_views(self):
        batch = self.load()
        self.assertEqual(current.F0, batch.baseline_sha)
        self.assertEqual({'card':current.M8,'purchase':current.F0}, batch.common['jointEcology']['phasePriorSha256'])
        self.assertEqual([1,0], [row['cardDecisions'] for row in batch.origins])
        self.assertEqual([0,1], [row['purchaseDecisions'] for row in batch.origins])
        for phase in joint.PHASES:
            rows, provenance = batch.phase_reader(self.directory,batch.bundle[phase]['path'],batch.models[phase],contract=joint.CONTRACTS[phase])
            original = b.read_game(self.directory/f'game-{rows[0]["originIndex"]}.json.gz',joint.GZIP_CONTRACT)[phase+'Decisions'][0]
            self.assertTrue(all(rows[0][key]==value for key,value in original.items()))
            self.assertEqual(batch.common['jointEcology'],provenance['jointEcology'])

    def test_current_source_does_not_silently_load_under_legacy_default(self):
        with self.assertRaisesRegex(ValueError,'F prior'):joint.JointCollection(self.directory,self.bundle)

    def test_untyped_ecology_opt_in_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'typed joint ecology'):
            joint.JointCollection(self.directory,self.bundle,ecology='current-etm8-joint-1')

    def test_missing_source_chronology_is_rejected_before_decoding(self):
        del self.source['jointStateChronology'];self.publish_source()
        with patch.object(b,'read_game') as decode,self.assertRaisesRegex(ValueError,'explicit chronology'):self.load()
        decode.assert_not_called()

    def test_changed_purchase_prior_snapshot_is_rejected(self):
        put(self.directory/'shared-purchase-baseline.json',{'wrong':True})
        with self.assertRaisesRegex(ValueError,'role bytes'):self.load()

    def test_changed_indexed_expansion_snapshot_is_rejected(self):
        put(self.directory/'expansion-imitation-opponent-0.json',{'wrong':True})
        with self.assertRaisesRegex(ValueError,'role bytes'):self.load()

    def test_rival_token_role_cannot_become_ordinary(self):
        self.source['tokenOrder']['opponentSelectors']=['ORDINARY'];self.publish_source()
        with self.assertRaisesRegex(ValueError,'qualified role'):self.load()

    def test_rules_qualification_cannot_transfer(self):
        self.manifest['experimentalRulesTransfer']['qualificationTransferred']=True;self.publish_source()
        with self.assertRaisesRegex(ValueError,'rules transfer'):self.load()

    def test_late_corrupt_sidecar_blocks_all_trajectory_decoding(self):
        path=self.directory/'joint-state-1.json';path.write_bytes(path.read_bytes()+b' ')
        with patch.object(b,'read_game') as decode,self.assertRaisesRegex(ValueError,'sidecar before decoding'):self.load()
        decode.assert_not_called()

    def test_state_sidecar_cannot_add_selected_action_even_with_updated_completion(self):
        path=self.directory/'joint-state-0.json';x=joint.read(path);x['rows'][0]['stateFeatures']['chosen']=1;put(path,x);complete(self.directory)
        with self.assertRaisesRegex(ValueError,'state-only rows'):self.load()

    def test_terminal_sidecar_cannot_relabel_reward_even_with_updated_completion(self):
        path=self.directory/'joint-label-0.json';x=joint.read(path);x['terminal']['reward']=.123;put(path,x);complete(self.directory)
        with self.assertRaisesRegex(ValueError,'separate terminal labels'):self.load()

    def test_replay_cannot_bind_another_trajectory_even_with_updated_completion(self):
        path=self.directory/'replay-0.json';x=joint.read(path);x['trajectorySha256']='0'*64;put(path,x);complete(self.directory)
        with self.assertRaisesRegex(ValueError,'canonical history'):self.load()

    def test_nonprospective_replay_is_rejected(self):
        shutil.copyfile(self.directory/'replay-0.json',self.directory/'replay-1.json')
        with self.assertRaisesRegex(ValueError,'omit or add'):self.load()

    def test_dictionary_names_cannot_change_when_model_weights_are_allowed_to_evolve(self):
        with patch.dict(current.PHASE_DICTIONARIES,card='0'*64),self.assertRaisesRegex(ValueError,'card state/action dictionary'):self.load()

    def test_current_without_prospective_histories_binds_empty_replay_completion(self):
        del self.source['replayIndices'];self.manifest['replayIndices']=[];self.publish_source()
        (self.directory/'replay-0.json').unlink();self.publish_sidecars()
        self.assertEqual({},self.load().common['jointEcology']['canonicalReplaySha256'])

    def test_behavior_seed_checks_are_not_bypassed_by_current_profile(self):
        path=self.directory/'game-0.json.gz';game=b.read_game(path,joint.GZIP_CONTRACT)
        game['cardDecisions'][0]['actorSeed']=17;path.write_bytes(gzip.compress(b.canonical(game),mtime=0));self.publish_sidecars()
        with self.assertRaisesRegex(ValueError,'typed native sampling'):self.load()

    def test_current_heads_are_bundle_bound_instead_of_fixed_to_initial_training_hashes(self):
        batch=self.load()
        self.assertEqual(self.manifest['bundle']['card']['sha256'],batch.bundle['card']['sha256'])
        self.assertEqual(self.manifest['bundle']['purchase']['sha256'],batch.bundle['purchase']['sha256'])
        self.assertNotEqual('98dd71c7911da94a6dc03151cde304589b1bc513b7c319e2d3c525bc3cc8689c',batch.bundle['card']['sha256'])
        self.assertNotEqual('09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af',batch.bundle['purchase']['sha256'])

    def test_rival_bundle_cannot_replace_its_purchase_prior_with_ordinary_m8(self):
        path=self.root/'rival.json';raw=joint.read(path);raw['baselineSha256']=current.M8;put(path,raw)
        shutil.copyfile(path,self.directory/'opponent-0-bundle.json')
        self.manifest['opponents'][0].update(baselineSha256=current.M8,sha256=joint.sha(path));self.publish_source()
        with self.assertRaisesRegex(ValueError,'F prior'):self.load()

    def test_coverage_quote_cannot_change_even_with_updated_completion(self):
        path=self.directory/'joint-coverage-0.json';raw=joint.read(path)
        raw['observedSelectedCommandQuotes']=[{'inventedCash':123}];put(path,raw);complete(self.directory)
        with self.assertRaisesRegex(ValueError,'original row identities, quotes'):self.load()

    def test_bound_current_sidecar_change_is_detected_by_original_phase_reader(self):
        batch=self.load();path=self.directory/'joint-coverage-0.json';path.write_bytes(path.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'Frozen joint inputs'):
            batch.phase_reader(self.directory,batch.bundle['card']['path'],batch.models['card'],contract=joint.CONTRACTS['card'])


class LegacyCompatibility(unittest.TestCase):
    def test_omitted_and_explicit_none_keep_identical_legacy_provenance(self):
        b.torch.set_num_threads(1)
        with tempfile.TemporaryDirectory() as temporary:
            directory,bundle,_=fixture(Path(temporary))
            omitted=joint.JointCollection(directory,bundle)
            explicit=joint.JointCollection(directory,bundle,ecology=None)
            self.assertEqual(omitted.common,explicit.common)
            self.assertEqual(omitted.pins,explicit.pins)
            self.assertNotIn('jointEcology',omitted.common)
            self.assertEqual(joint.sha(Path(temporary)/'F.json'),omitted.baseline_sha)


if __name__=='__main__':unittest.main()
