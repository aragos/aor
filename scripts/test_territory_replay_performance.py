"""Prepared-input reuse retains raw ownership, validation and exact optimizer arithmetic."""
from dataclasses import replace
import tempfile
from pathlib import Path
import time
import unittest
from unittest.mock import patch

import territory_value as q
import territory_value_trajectory as t
from test_territory_value import actors,game,schema
from test_territory_value_trajectory import fixture

b=q.b


def inputs(games,models):
    return {(row.origin,row.number):models[row.phase].encoder.prepare(row.observation)
            for episode in games for row in episode.transitions}


class RebuiltInputsReplay(q.Replay):
    """The previous preparation lifetime: append clears tensors, prepare rebuilds from raw rows."""
    def append(self,games):
        super().append(games)
        self.prepared={}

    def prepare(self,models):
        self.prepared={}
        return super().prepare(models)


class ReplayCacheTests(unittest.TestCase):
    def setUp(self):b.torch.set_num_threads(1)

    def test_reader_tensors_are_reused_but_raw_observations_remain_defensive_copies(self):
        models=actors();episode=game();prepared=inputs([episode],models)
        replay=q.Replay();replay.append([episode],prepared=prepared)
        episode.transitions[0].observation['stateFeatures']['context']=7.
        self.assertEqual(1.,replay.rows('token')[0].observation['stateFeatures']['context'])
        replay.prepare(models)
        for key,value in prepared.items():self.assertIs(value,replay.prepared[key])

    def test_prepare_passes_only_cached_objects_through_existing_dictionary_guard(self):
        models=actors();episodes=[game()];prepared=inputs(episodes,models)
        replay=q.Replay();replay.append(episodes,prepared=prepared)
        for model in models.values():
            original=model.encoder.prepare
            def guarded(value,original=original):
                self.assertIsInstance(value,b._PreparedObservation)
                return original(value)
            model.encoder.prepare=guarded
        self.assertGreater(replay.prepare(models),0)

    def test_different_actor_dictionary_still_rejects_cached_inputs(self):
        models=actors();episodes=[game()];replay=q.Replay();replay.append(episodes,prepared=inputs(episodes,models))
        other=dict(schema('token'),stateFeatureNames=['context','extra','pool'])
        models['token']=q.ActionValue(q.initialize(other,103,'token'),'token')
        with self.assertRaisesRegex(ValueError,'different feature dictionary'):replay.prepare(models)

    def test_fifo_eviction_prunes_every_prepared_entry_for_old_origins(self):
        models=actors();replay=q.Replay(game_capacity=1)
        old,new=game(1),game(2)
        replay.append([old],prepared=inputs([old],models));replay.prepare(models)
        replay.append([new],prepared=inputs([new],models))
        self.assertEqual({('2',0),('2',1)},set(replay.prepared))
        self.assertEqual(1,replay.evicted_games)

    def test_cache_must_cover_exact_incoming_rows_before_any_replay_mutation(self):
        models=actors();episode=game();prepared=inputs([episode],models)
        prepared[('unrelated',0)]=prepared.pop(('0',0));replay=q.Replay()
        with self.assertRaisesRegex(ValueError,'exactly the incoming'):replay.append([episode],prepared=prepared)
        self.assertEqual(0,len(replay.games));self.assertEqual(set(),replay.seen_origins)

    def test_changed_action_layout_is_rejected_before_append(self):
        models=actors();episode=game();prepared=inputs([episode],models)
        prepared[('0',0)]=replace(prepared[('0',0)],action_count=3);replay=q.Replay()
        with self.assertRaisesRegex(ValueError,'offered-action layout'):replay.append([episode],prepared=prepared)
        self.assertFalse(replay.games)

    def test_model_gradient_graph_cannot_enter_input_cache(self):
        models=actors();episode=game();prepared=inputs([episode],models)
        prepared[('0',0)].state_values.requires_grad_(True)
        with self.assertRaisesRegex(ValueError,'gradient graph'):q.Replay().append([episode],prepared=prepared)

    def test_four_updates_match_rebuilt_input_models_targets_adam_and_losses(self):
        for horizon in (1,16):
            with self.subTest(targetSteps=horizon):
                current,reference=actors(),actors()
                opts={p:b.torch.optim.Adam(current[p].parameters(),lr=.0003,foreach=False) for p in q.PHASES}
                old_opts={p:b.torch.optim.Adam(reference[p].parameters(),lr=.0003,foreach=False) for p in q.PHASES}
                targets,old_targets=q.snapshot(current),q.snapshot(reference)
                replay,old_replay=q.Replay(game_capacity=5),RebuiltInputsReplay(game_capacity=5)
                for update in range(1,5):
                    episodes=[game(update*10+i,('token','expansion','expansion'),reward=(i+1)/3) for i in range(2)]
                    prepared=inputs(episodes,current)
                    frozen={key:tuple(tensor.clone() for tensor in (item.state_indices,item.state_values,item.action_indices,item.action_values))
                            for key,item in prepared.items()}
                    replay.append(episodes,prepared=prepared);old_replay.append(episodes)
                    self.assertEqual(old_replay.games,replay.games)
                    self.assertEqual(old_replay.prepare(reference),replay.prepare(current))
                    config=q.UpdateConfig(seed=103,targetSteps=horizon);counts={'token':2,'expansion':4}
                    reference,old_opts,old_targets,old_report=q.fit_pair(reference,old_opts,old_targets,old_replay,counts,update,config,time.monotonic()+10)
                    current,opts,targets,report=q.fit_pair(current,opts,targets,replay,counts,update,config,time.monotonic()+10)
                    self.assertEqual(old_report,report)
                    for phase in q.PHASES:
                        self.assertEqual(reference[phase].artifact(),current[phase].artifact())
                        self.assertEqual(old_targets[phase].artifact(),targets[phase].artifact())
                        self.assertEqual(b.encode_optimizer(old_opts[phase]),b.encode_optimizer(opts[phase]))
                    for key,item in prepared.items():
                        for before,after in zip(frozen[key],(item.state_indices,item.state_values,item.action_indices,item.action_values)):
                            self.assertTrue(b.torch.equal(before,after))


class ReaderCacheTests(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.directory,self.bundle=fixture(self.root/'data')

    def test_complete_reader_cache_uses_exact_owned_row_identity(self):
        collection=t.ValueCollection(self.directory,self.bundle)
        expected={(episode.origin,row.number) for episode in collection.games for row in episode.transitions}
        self.assertEqual(expected,set(collection.prepared))
        for phase,rows in collection.rows.items():
            for row in rows:
                origin=next(episode.origin for episode in collection.games if episode.reference['index']==row['originIndex'])
                self.assertIs(row['_prepared'],collection.prepared[origin,row['number']])

    def test_rebuild_keeps_validated_tensors_and_raw_completion_provenance(self):
        collection=t.ValueCollection(self.directory,self.bundle);replay=q.Replay()
        replay.append(collection.games);record=t.replay_record(replay);created=[]
        actual=t.ValueCollection
        def tracked(*args,**kwargs):
            result=actual(*args,**kwargs);created.append(result);return result
        pins={}
        with patch.object(t,'ValueCollection',side_effect=tracked):
            rebuilt=t.rebuild_replay({'replay':record},q.UpdateConfig(),pins,collection.baseline_sha,collection.frozen['sha256'])
        self.assertEqual(record,t.replay_record(rebuilt));self.assertTrue(pins)
        for key,value in created[0].prepared.items():self.assertIs(value,rebuilt.prepared[key])
        self.assertEqual(collection.pins,pins)


if __name__=='__main__':unittest.main()
