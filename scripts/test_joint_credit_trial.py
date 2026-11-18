"""Synthetic driver/role contracts; no native games or scientific actor updates."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_experiments as exp
import joint_credit_trial as trial
import joint_credit_trial_data as data
import joint_credit_storage as storage


class TrialContracts(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name).resolve()
        self.protocol=dict(ordinary='M8',prior='F0',expansion='E',anchorBundle='anchor',
            initialBundle='initial',arm='chronological',workers=6,
            banks={**{f'training-{i}':dict(nativeOffset=53000017+i) for i in range(1,33)},
                   'screen':dict(nativeOffset=53000050), 'updates':dict(seeds=list(range(53001000,53001256)))},
            updateConfig=dict(epochs=4,valueCoefficient=0))

    def state(self,count=0,streak=0):
        return dict(completedBatches=count,acceptedEpochs=dict(card=0,purchase=0),
                    consecutiveZeroAcceptedBatches=streak,stopReason=None)

    def test_one_head_accepting_resets_whole_bundle_stall(self):
        actual=trial.next_state(self.state(3,3),dict(card=0,purchase=1))
        self.assertEqual(actual['consecutiveZeroAcceptedBatches'],0)
        self.assertIsNone(actual['stopReason'])

    def test_four_both_head_zeros_stop_only_this_arm(self):
        actual=trial.next_state(self.state(3,3),dict(card=0,purchase=0))
        self.assertEqual(actual['stopReason'],'four-consecutive-zero-accepted-batches')
        self.assertEqual(actual['completedBatches'],4)

    def test_batch32_is_only_budget_terminal_candidate(self):
        self.assertEqual(trial.next_state(self.state(31),dict(card=2,purchase=1))['stopReason'],
                         'all32-batches-complete')

    def test_stopped_arm_cannot_consume_next_bank(self):
        stopped=dict(self.state(4,4),stopReason='four-consecutive-zero-accepted-batches')
        with self.assertRaisesRegex(ValueError,'running arm'):
            trial.next_state(stopped,dict(card=1,purchase=1))

    def test_phase_shuffles_share_across_arms_but_never_overlap_each_other(self):
        seeds=[]
        for index in range(1,33):
            configs=data.configs(self.protocol,index)
            self.assertEqual(configs,data.configs(dict(self.protocol,arm='phase-separated'),index))
            for phase in data.PHASES:seeds.extend(range(configs[phase]['seed'],configs[phase]['seed']+4))
        self.assertEqual(seeds,self.protocol['banks']['updates']['seeds'])

    def test_controlled_source_evolves_bundle_with_fixed_raw_card_rivals(self):
        actual=data.collection_source(self.protocol,2,'updated-bundle')
        self.assertEqual(actual['bundle'],'updated-bundle')
        self.assertEqual(actual['opponents'],['anchor'])
        self.assertEqual(actual['replayIndices'],[85])
        self.assertEqual(actual['sharedPurchaseBaseline'],'F0')
        self.assertEqual(actual['tokenOrder'],dict(candidateSelector='FIXED_16_TO_15',
                                                  opponentSelectors=['FIXED_16_TO_15']))

    def test_all32_reproductions_are_prescribed_and_seat_balanced(self):
        self.assertEqual([data.replay_index(i) for i in range(1,33)],list((0,85,170,255))*8)

    def test_initial_adam_is_fresh_then_both_actual_prior_phase_checkpoints(self):
        self.assertIsNone(trial.previous_checkpoints(self.root/'batches/batch-01',1))
        self.assertEqual(trial.previous_checkpoints(self.root/'batches/batch-02',2),
            {phase:self.root/'batches/batch-01/numeric/pair'/phase/'checkpoint.json' for phase in data.PHASES})

    def test_anchor_arena_has_nullable_card_bundle_and_separate_sampled_candidates(self):
        source=data.arena_source(self.protocol,dict(chronological='learned-card-and-purchase'),
                                 'chronological-anchor')
        self.assertNotIn('frozenPurchase',source)
        self.assertEqual(source['jointPolicy'],dict(baseline='F0',sharedBundle='anchor',
            candidateBundle='learned-card-and-purchase',incumbentBundle='anchor'))
        self.assertEqual(source['pairsPerConfiguration'],2048)
        self.assertEqual(source['expansionImitation']['opponentModels'],['E']*4)

    def test_direct_contrast_preserves_same_anchor_rivals(self):
        source=data.arena_source(self.protocol,{'chronological':'A','phase-separated':'B'},
                                 'chronological-separated')
        self.assertEqual(source['jointPolicy'],dict(baseline='F0',sharedBundle='anchor',
            candidateBundle='A',incumbentBundle='B'))

    def bundle(self,card=True):
        for phase in data.PHASES:
            exp.document(self.root/(phase+'.json'),dict(encoderVersion=f'aor-{phase}-observation-1',
                stateFeatureNames=['x'],actionFeatureNames=['a']))
        path=self.root/'bundle.json'
        exp.document(path,dict(schemaVersion=1,kind='joint-card-purchase-1',configuration=exp.CONFIGURATION,
            baselineSha256=data.overlays.F0,card=dict(path='card.json',sha256=exp.sha(self.root/'card.json')) if card else None,
            purchase=dict(path='purchase.json',sha256=exp.sha(self.root/'purchase.json'))))
        return path

    def test_null_card_is_preserved_in_raw_bundle_binding(self):
        subject=data.bundle_subjects(self.bundle(card=False))
        self.assertIsNone(subject['card'])
        self.assertEqual(subject['purchase']['actorVersion'],'aor-purchase-policy-1')

    def test_tampered_component_is_rejected_before_native_execution(self):
        path=self.bundle();(self.root/'card.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'component changed'):data.bundle_subjects(path)

    def test_terminal_export_only_changes_paths_and_preserves_raw_actor_bytes(self):
        original=self.bundle();before=exp.read(original)
        receipt=trial.export_terminal(original,self.root/'terminal')
        exported=exp.read(receipt['path'])
        for phase in data.PHASES:
            self.assertEqual(exported[phase]['sha256'],before[phase]['sha256'])
            self.assertEqual(exp.sha(self.root/'terminal'/exported[phase]['path']),before[phase]['sha256'])
        self.assertEqual({key:value for key,value in before.items() if key not in data.PHASES},
                         {key:value for key,value in exported.items() if key not in data.PHASES})
        self.assertEqual(exp.read(original),before)

    def test_joint_binding_includes_raw_source_and_every_present_phase_snapshot(self):
        bundle=self.bundle();prior=self.root/'F0.json';exp.document(prior,{})
        source=self.root/'source.json';exp.document(source,dict(jointPolicy=dict(baseline=str(prior),
            sharedBundle=str(bundle),candidateBundle=str(bundle),incumbentBundle=str(bundle))))
        binding=data.joint_binding(source)
        self.assertEqual(binding['source']['sha256'],exp.sha(source))
        self.assertEqual(binding['candidateBundle']['card']['snapshot'],'joint-candidate-card-policy.json')

    def test_pair_parity_detects_changed_anchor_game(self):
        game=dict.fromkeys(data.search.GAME_FIELDS, [1])
        row=dict(index=0,configuration='x',seed=1,seat=0,opponentIndices=[0],candidate=game,incumbent=game)
        pairs={name:[deepcopy(row)] for name in data.COMPARISONS}
        pairs['phase-separated-anchor'][0]['incumbent']['scores']=[2]
        with patch.object(data,'PAIRS',1),self.assertRaisesRegex(ValueError,'Shared final worlds'):
            data.common_games(pairs)

    def test_prior_carry_is_archived_only_after_next_completed_receipt(self):
        protocol=dict(self.protocol,numerical=dict(executable='unused'))
        source=self.root/'protocol.json';exp.document(source,{})
        directory=trial.batch_directory(self.root,2)
        bundle=self.root/'old-bundle.json';exp.document(bundle,{})
        report=dict(outputBundleSha256='new',phases={p:dict(acceptedEpochs=1) for p in data.PHASES})
        events=[]
        def native(*args,**kwargs):
            target=args[3];target.mkdir(parents=True)
            exp.document(target/'collection.json',dict(seeds=protocol['banks']['training-2']['seeds'],
                signature='synthetic',sourceSha256=exp.sha(directory/'collection-source.json')))
        def child(*args):
            if args[2].name=='numeric.log':
                (directory/'numeric').mkdir();exp.document(directory/'numeric/values.json',{})
        def raw_archive(*args):
            events.append('raw-archive');exp.document(directory/corpus_manifest,{});return dict(archiveSha256='raw')
        def old_archive(prior,receipt):
            self.assertTrue(exp.read(receipt)['completed']);events.append('old-carry-archive')
        protocol['banks']['training-2']['seeds']=[1]
        corpus_manifest=trial.corpus.MANIFEST
        with patch.object(trial.exp,'native',side_effect=native),patch.object(trial.execution,'run_child',side_effect=child),\
             patch.object(trial.corpus,'completed_files',return_value=({},[])),\
             patch.object(trial,'verify_pair',return_value=(report,{})),\
             patch.object(trial.corpus,'archive',side_effect=raw_archive),\
             patch.object(storage,'archive_numeric',side_effect=old_archive),\
             patch.object(trial.exp,'remaining_seconds',return_value=100),\
             patch.object(trial.execution,'java_command',return_value=[]):
            trial.train_batch(protocol,source,self.root,2,bundle)
        self.assertEqual(events,['raw-archive','old-carry-archive'])


class JointArenaMemory(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / 'source.json'
        exp.document(self.source, {})
        self.binding = {'policySha256': 'fixed', 'stateFeatureNames': [f'feature.{i}' for i in range(2000)]}
        self.protocol = dict(workers=6, ordinary='M8', banks={'screen': {'seeds': [101, 102, 103, 104]}})
        self.originals = []
        for index in range(4):
            candidate = dict(wins=[1, 0, 0, 0], rewards=[1., .5, .25, 0.],
                             ranks=[1., .5, .25, 0.], scores=[100, 70, 40, 10], decisions=10)
            incumbent = dict(wins=[0, 1, 0, 0], rewards=[.5, 1., 0., .25],
                             ranks=[.5, 1., 0., .25], scores=[70, 100, 10, 40], decisions=11)
            row = dict(index=index, seed=101 + index, seat=index, configuration=exp.CONFIGURATION,
                       opponentIndices=[0, 1, 2, 3], candidate=candidate, incumbent=incumbent,
                       jointPolicy=deepcopy(self.binding))
            self.originals.append(row)
            exp.document(self.root / f'pair-{index}.json', row)
        self.expected_metrics = data.search.comparison(self.originals)
        exp.document(self.root / 'evaluation.json', {'overall': self.expected_metrics})
        exp.document(self.root / 'run.json', dict(kind='strategy-arena', signature='fixed',
                     source=str(self.source), workers=6))
        exp.document(self.root / 'state.json', dict(kind='strategy-arena', signature='fixed'))

    def verify(self):
        with patch.object(data, 'PAIRS', 4), \
             patch.object(exp, 'verify_arena'), \
             patch.object(data, 'verify_joint_roles', return_value=self.binding), \
             patch.object(data.legacy, 'verify_transferred_sources'):
            return data.verify_arena(self.protocol, self.source, self.root)

    def test_full_pair_values_metrics_and_source_bytes_survive_descriptor_sharing(self):
        before = {path.name: path.read_bytes() for path in self.root.iterdir()}
        pairs, metrics = self.verify()
        self.assertEqual(self.originals, pairs)
        self.assertEqual(self.expected_metrics, metrics)
        self.assertTrue(all(pair['jointPolicy'] is self.binding for pair in pairs))
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.root.iterdir()})

    def test_one_wrong_descriptor_is_rejected_before_it_can_be_shared(self):
        path = self.root / 'pair-3.json'
        altered = exp.read(path)
        altered['jointPolicy']['policySha256'] = 'different'
        path.write_text(json.dumps(altered))
        with self.assertRaisesRegex(ValueError, 'exact sampled role identities'):
            self.verify()


if __name__=='__main__':unittest.main()
