"""Linear-Q math, actual successor offers and whole-origin replay contracts."""
import copy
from dataclasses import replace
import random
import time
import unittest
from unittest.mock import patch

import territory_value as q

b=q.b


def schema(phase):
    return dict(encoderVersion=q.CONTRACTS[phase].encoder,stateFeatureNames=['context','pool'],
                actionFeatureNames=['choice'])


def observation(phase='token',two=True):
    commands=['buy-tokens 1','buy-tokens 2'] if phase=='token' else (['expand far','expand near'] if two else ['expand near'])
    return dict(encoderVersion=q.CONTRACTS[phase].encoder,stateFeatures={'context':1.,'pool':float(two)},
                actions=[dict(command=s,features={'choice':float(i)}) for i,s in enumerate(commands)],baselineIndex=len(commands)-1)


def actors():return {p:q.ActionValue(q.initialize(schema(p),103,p),p) for p in q.PHASES}


def game(index=0,phases=('token','expansion'),reward=1.):
    origin=str(index);obs=[observation(p) for p in phases]
    transitions=tuple(q.Transition(origin,i,p,obs[i],0,reward if i==len(phases)-1 else 0.,
        None if i==len(phases)-1 else q.Successor(origin,i+1,phases[i+1],obs[i+1])) for i,p in enumerate(phases))
    return q.Episode(origin,index,reward,transitions,{'index':index})


class MathTests(unittest.TestCase):
    def setUp(self):b.torch.set_num_threads(1)

    def test_initializer_preserves_original_hidden_draws_and_constant_half(self):
        for phase in q.PHASES:
            model=q.initialize(schema(phase),103,phase)
            old=b.initialize(schema(phase),103,contract=q.CONTRACTS[phase])
            for name in q.PARAMETERS[:4]:self.assertEqual(old[name],model[name])
            self.assertEqual([.5,.5],q.reference_forward(model,observation(phase),phase))
            self.assertEqual([.1,.9],q.probabilities([.5,.5],observation(phase)))

    def test_torch_matches_scalar_nonzero_unbounded_values(self):
        model=q.initialize(schema('expansion'),103,'expansion')
        model['outputWeights']=[.2*(-1)**i for i in range(64)];model['outputBias']=-2.
        actor=q.ActionValue(model,'expansion');obs=observation('expansion')
        expected=q.reference_forward(model,obs,'expansion')
        self.assertLess(max(expected),0.)
        b.torch.testing.assert_close(actor([obs])[0],b.torch.tensor(expected,dtype=b.DTYPE),rtol=1e-13,atol=1e-13)

    def test_model_cannot_be_confused_with_ppo_or_other_phase(self):
        model=q.initialize(schema('token'),103,'token')
        with self.assertRaises(ValueError):q.validate_model(model,'expansion')
        with self.assertRaises(ValueError):q.validate_model(dict(model,baselineProbability=.97),'token')
        with self.assertRaises(ValueError):b.validate_model(model,contract=b.TOKEN_CONTRACT)

    def test_linear_corrective_gradient_does_not_saturate_near_zero(self):
        actor=actors()['expansion']
        with b.torch.no_grad():actor.outputBias.fill_(.003)
        loss=.5*(actor([observation('expansion')])[0][0]-1).square();loss.backward()
        self.assertAlmostEqual(-.997,float(actor.outputBias.grad),places=14)

    def test_argmax_ties_prefer_f_then_canonical(self):
        obs=observation();self.assertEqual(1,q.greedy_index([2.,2.],obs))
        obs['actions'].append(dict(command='pass',features={}));obs['baselineIndex']=2
        self.assertEqual(0,q.greedy_index([2.,2.,1.],obs))

    def test_terminal_targets_never_query_networks(self):
        row=game(phases=('token',)).transitions[0]
        with patch.object(q,'evaluate',side_effect=AssertionError('terminal must not evaluate')):
            # Empty phase batches deliberately avoid touching the supplied network objects.
            actual=q.targets([row],{}, {})
        self.assertEqual([1.],actual.tolist());self.assertFalse(actual.requires_grad)

    def test_online_argmax_is_evaluated_at_same_lagged_index(self):
        row=game().transitions[0]
        online=object();target=object()
        def values(actor,observations):
            if not observations:return []
            return [b.torch.tensor([3.,2.] if actor is online else [5.,9.],dtype=b.DTYPE)]
        with patch.object(q,'evaluate',side_effect=values):
            result=q.targets([row],dict.fromkeys(q.PHASES,online),dict.fromkeys(q.PHASES,target))
        self.assertEqual([5.],result.tolist())

    def test_singleton_successor_cannot_use_unoffered_far(self):
        row=game().transitions[0]
        row=replace(row,successor=replace(row.successor,observation=observation('expansion',False)))
        def values(actor,observations):
            return [b.torch.tensor([.4],dtype=b.DTYPE) for _ in observations]
        with patch.object(q,'evaluate',side_effect=values):
            self.assertEqual([.4],q.targets([row],dict.fromkeys(q.PHASES,None),dict.fromkeys(q.PHASES,None)).tolist())

    def test_successor_must_not_cross_origins(self):
        episode=game();bad=replace(episode.transitions[0],successor=replace(episode.transitions[0].successor,origin='other'))
        with self.assertRaises(ValueError):q.validate_episode(replace(episode,transitions=(bad,episode.transitions[1])))

    def test_new_row_steps_are_exact_and_zero_is_not_a_batch(self):
        self.assertEqual([0,4,4,8,16],[q.steps_for_rows(n,q.UpdateConfig()) for n in (0,1,64,65,256)])

    def test_fifo_evicts_whole_games_and_keeps_both_views(self):
        replay=q.Replay(row_capacity=3,game_capacity=10)
        replay.append([game(0,('token','expansion','expansion')),game(1),game(2)])
        self.assertEqual(['1','2'],[x.origin for x in replay.games])
        self.assertEqual({'token':2,'expansion':2},replay.counts());self.assertEqual(1,replay.evicted_games)

    def test_oversize_game_rejects_without_mutating_fifo(self):
        replay=q.Replay(row_capacity=1);replay.append([game(0)])
        with self.assertRaises(ValueError):replay.append([game(1,('expansion','expansion'))])
        self.assertEqual(['0'],[x.origin for x in replay.games])

    def test_zero_row_games_remain_and_duplicate_origin_is_rejected(self):
        replay=q.Replay();replay.append([game(0,())])
        self.assertEqual(1,len(replay.games));self.assertEqual({'token':0,'expansion':0},replay.counts())
        with self.assertRaises(ValueError):replay.append([game(0,())])

    def test_phase_sampling_is_uniform_transitions_not_uniform_games(self):
        replay=q.Replay();replay.append([game(0,('expansion',)),game(1,('expansion',)*3)])
        class Cycle:
            def __init__(self):self.index=-1
            def randrange(self,n):self.index+=1;return self.index%n
        self.assertEqual(['0','1','1','1'],[x.origin for x in replay.sample('expansion',4,Cycle())])

    def test_pair_failure_never_mutates_original_online_target_or_adam(self):
        online=actors();lagged=q.snapshot(online)
        optimizers={p:b.torch.optim.Adam(online[p].parameters(),lr=.0003,foreach=False) for p in q.PHASES}
        before={p:(copy.deepcopy(online[p].artifact()),b.encode_optimizer(optimizers[p])) for p in q.PHASES}
        def fail(actor,optimizer,replay,phase,*args):
            with b.torch.no_grad():actor.outputBias.add_(10.)
            if phase=='expansion':raise ValueError('second head failed')
            return dict(steps=4,losses=[0.]*4)
        with patch.object(q,'fit_phase',side_effect=fail):
            with self.assertRaises(ValueError):q.fit_pair(online,optimizers,lagged,q.Replay(),dict(token=1,expansion=1),1,q.UpdateConfig(),time.monotonic()+10)
        self.assertEqual(before,{p:(online[p].artifact(),b.encode_optimizer(optimizers[p])) for p in q.PHASES})

    def test_snapshot_pair_is_frozen_across_sibling_updates(self):
        online=actors();lagged=q.snapshot(online)
        opts={p:b.torch.optim.Adam(online[p].parameters(),lr=.0003,foreach=False) for p in q.PHASES}
        def fit(actor,optimizer,replay,phase,selectors,targets,*args):
            self.assertEqual(.5,float(selectors['token'].outputBias));self.assertEqual(.5,float(targets['token'].outputBias))
            with b.torch.no_grad():actor.outputBias.add_(.1)
            return dict(steps=0,losses=[])
        with patch.object(q,'fit_phase',side_effect=fit):
            new,_,targets,_=q.fit_pair(online,opts,lagged,q.Replay(),dict.fromkeys(q.PHASES,1),4,q.UpdateConfig(),time.monotonic()+10)
        self.assertEqual(.6,float(new['token'].outputBias.detach()));self.assertEqual(.6,float(targets['token'].outputBias))

    def test_guard_checks_unselected_actions_without_clipping(self):
        online=actors();replay=q.Replay();replay.append([game()]);replay.prepare(online)
        with b.torch.no_grad():online['expansion'].outputBias.fill_(11.)
        result=q.numerical_guard(online,q.snapshot(online),replay,q.UpdateConfig(),time.monotonic()+10)
        self.assertFalse(result['passed']);self.assertEqual(11.,result['values']['online']['expansion']['maximum'])
        self.assertEqual(1.,result['values']['online']['expansion']['outsideUnitFraction'])
        self.assertEqual(0,result['values']['online']['token']['terminalSelectedCount'])

    def test_cached_preparation_preserves_loss_and_gradients(self):
        online=actors();replay=q.Replay();replay.append([game()])
        row=replay.rows('token')[0]
        raw=online['token']([row.observation])[0].sum();raw.backward()
        gradients=[p.grad.clone() for p in online['token'].parameters()]
        online['token'].zero_grad();self.assertGreater(replay.prepare(online),0)
        prepared=online['token']([replay.prepared[(row.origin,row.number)]])[0].sum();prepared.backward()
        self.assertEqual(float(raw.detach()),float(prepared.detach()))
        for before,after in zip(gradients,online['token'].parameters()):
            self.assertTrue(b.torch.equal(before,after.grad))


if __name__=='__main__':unittest.main()
