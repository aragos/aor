"""Synthetic UNIT math/optimizer fixtures; no retained games, models or scientific seeds."""
import copy
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import expansion_paired_fit as fit
import expansion_paired_fit_data as data
import purchase_policy as policy
import territory_value


def purchase():
    schema = dict(encoderVersion=policy.PURCHASE_CONTRACT.encoder,
                  stateFeatureNames=['decision.purchase-advances', 'decision.purchase-shipping', 'raw.cash'],
                  actionFeatureNames=['purchase.cost'])
    result = policy.initialize(schema, 17)
    result['stateBias'] = [(i-32)/300 for i in range(64)]
    result['actorWeights'] = [.3]*64
    return result


def names():
    return dict(stateFeatureNames=['decision.expand', 'new.cards', 'raw.cash'],
                actionFeatureNames=['action.cost', 'action.kind'])


def row(index=0, actions=2):
    commands = ['buy-expansion-card', 'pass', 'place market 1'][:actions]
    return dict(index=index, status='COLLECTED', encoded=dict(encoderVersion=data.ENCODER,
        stateFeatures={'new.cards': .5, 'raw.cash': .3}, baselineIndex=1,
        actions=[dict(command=c, features={'action.cost': .2*i, 'action.kind': float(i)})
                 for i,c in enumerate(commands)]))


def labels(rows):
    return {r['index']: [dict(reward=.8 if i != 1 else .2, winShare=float(i != 1), chaos=0.)
                        for i in range(len(r['encoded']['actions']))] for r in rows}


class MathTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fit.torch.set_num_threads(1)

    def test_transfer_copies_only_shared_state_columns_and_bias(self):
        old = purchase(); model, mapping = fit.initialize(old, names(), 23)
        self.assertEqual([dict(name='raw.cash', source=2, target=2)], mapping)
        self.assertEqual(old['stateBias'], model['stateBias'])
        for i in range(64):
            self.assertEqual([0., 0., old['stateWeights'][i*3+2]], model['stateWeights'][i*3:i*3+3])
        self.assertEqual([0.]*64, model['outputWeights'])
        self.assertEqual([0.]*64, model['actionBias'])
        self.assertEqual(0., model['outputBias'])

    def test_actual_hidden_function_matches_and_detects_corruption_despite_flat_q(self):
        old = purchase(); model, mapping = fit.initialize(old, names(), 23)
        self.assertLessEqual(fit.warm_parity(old,model,mapping)['maximumAbsoluteError'], 1e-12)
        model['stateBias'][0] += .1
        self.assertEqual([.5,.5], fit.ActionValue(model)([row()['encoded']])[0].tolist())
        with self.assertRaisesRegex(ValueError, 'hidden-function'):
            fit.warm_parity(old,model,mapping)

    def test_initial_unmatched_columns_have_no_effect(self):
        model, _ = fit.initialize(purchase(), names(), 23); actor = fit.ActionValue(model)
        a = fit.torch.tensor([[0.,0.,.5]],dtype=policy.DTYPE)
        b = fit.torch.tensor([[1.,16.,.5]],dtype=policy.DTYPE)
        fit.torch.testing.assert_close(actor.hidden(a), actor.hidden(b), rtol=0, atol=0)

    def test_missing_raw_column_is_rejected(self):
        bad = names(); bad['stateFeatureNames'].remove('raw.cash')
        with self.assertRaisesRegex(ValueError, 'complete original'):
            fit.initialize(purchase(), bad, 23)

    def test_glorot_action_basis_is_deterministic_without_actor_weight_transfer(self):
        model, _ = fit.initialize(purchase(), names(), 23)
        repeat, _ = fit.initialize(purchase(), names(), 23)
        other, _ = fit.initialize(purchase(), names(), 24)
        self.assertEqual(model, repeat)
        self.assertNotEqual(model['actionWeights'], other['actionWeights'])
        self.assertLessEqual(max(map(abs,model['actionWeights'])), math.sqrt(6/66))

    def test_scalar_reference_matches_nonzero_forward(self):
        model, _ = fit.initialize(purchase(), names(), 23)
        model['outputWeights'] = [(.1 if i%2 else -.2) for i in range(64)]
        model['outputBias'] = .07; observation = row(actions=3)['encoded']
        x = [observation['stateFeatures'].get(n,0.) for n in model['stateFeatureNames']]
        h = [math.tanh(sum(x[j]*model['stateWeights'][i*3+j] for j in range(3))+model['stateBias'][i])
             for i in range(64)]
        expected = []
        for action in observation['actions']:
            a = [action['features'].get(n,0.) for n in model['actionFeatureNames']]
            z = [math.tanh(h[i]+sum(a[j]*model['actionWeights'][i*2+j] for j in range(2))) for i in range(64)]
            expected.append(1/(1+math.exp(-(sum(w*v for w,v in zip(model['outputWeights'],z))+.07))))
        fit.torch.testing.assert_close(fit.ActionValue(model)([observation])[0],
                                      fit.torch.tensor(expected,dtype=policy.DTYPE),rtol=1e-13,atol=1e-13)

    def test_old_loaders_reject_research_model(self):
        model, _ = fit.initialize(purchase(), names(), 23)
        with self.assertRaises(ValueError): territory_value.validate_model(model,'expansion')
        with self.assertRaises(ValueError): policy.validate_model(model)

    def test_loss_matches_nonbaseline_pair_mean_and_all_arm_anchor(self):
        q = fit.torch.tensor([.7,.3,.5],dtype=policy.DTYPE,requires_grad=True)
        targets = [dict(reward=.9),dict(reward=.4),dict(reward=.2)]
        expected = ((.4-.5)**2+(.2-(-.2))**2)/2 + .1*((.7-.9)**2+(.3-.4)**2+(.5-.2)**2)/3
        self.assertAlmostEqual(expected,float(fit.origin_loss(q,1,targets).detach()),places=14)

    def test_origin_equal_weight_is_independent_of_proposal_count(self):
        model, _ = fit.initialize(purchase(), names(), 23); actor = fit.ActionValue(model)
        rows = [row(0,2),row(1,3)]; target = labels(rows)
        values = actor([r['encoded'] for r in rows])
        expected = (fit.origin_loss(values[0],1,target[0])+fit.origin_loss(values[1],1,target[1]))/2
        fit.torch.testing.assert_close(fit.batch_loss(actor,rows,target),expected,rtol=0,atol=0)

    def test_fresh_adam_updates_scalar_and_later_basis_with_no_imported_momentum(self):
        model, _ = fit.initialize(purchase(), names(), 23); actor = fit.ActionValue(model)
        optimizer = fit.torch.optim.Adam(actor.parameters(),lr=.001)
        self.assertEqual({},dict(optimizer.state))
        rows = [row(actions=3)]
        for _ in range(2):
            optimizer.zero_grad(); loss=fit.batch_loss(actor,rows,labels(rows)); loss.backward(); optimizer.step()
        changed=actor.artifact()
        for name in fit.PARAMETERS:
            self.assertNotEqual(model[name],changed[name],name)
        self.assertEqual(6,len(optimizer.state))

    def test_selector_prefers_e_on_equal_q_and_canonical_non_e_tie(self):
        r=row(actions=3)
        self.assertEqual(1,data.choose(r,[.5,.5,.5]))
        self.assertEqual(0,data.choose(r,[.6,.5,.6]))

    def test_decision_gain_selects_above_better_mse_and_e_owns_zero(self):
        metrics={e:dict(gain={'reward':{'mean':-.1}},pairedMse=0.) for e in range(1,21)}
        metrics[2]=dict(gain={'reward':{'mean':.1}},pairedMse=.5)
        metrics[3]=dict(gain={'reward':{'mean':.09}},pairedMse=0.)
        self.assertEqual(2,fit.select(metrics))
        metrics[2]['gain']['reward']['mean']=0.;metrics[3]['gain']['reward']['mean']=0.
        self.assertIsNone(fit.select(metrics))

    def test_positive_gain_ties_use_mse_then_earlier_epoch(self):
        metrics={e:dict(gain={'reward':{'mean':.1}},pairedMse=.2) for e in range(1,21)}
        metrics[7]['pairedMse']=.1;metrics[8]['pairedMse']=.1
        self.assertEqual(7,fit.select(metrics))

    def test_fixed_recipe_rejects_missing_or_repeated_streams(self):
        self.assertEqual('cpu',fit.Recipe(1,tuple(range(2,22)),'cpu').validate().device)
        with self.assertRaises(ValueError):fit.Recipe(1,tuple(range(1,21)),'cpu').validate()
        with self.assertRaises(ValueError):fit.Recipe(1,(2,),'cpu').validate()


class SyntheticCorpus:
    """UNIT sequencing spy: deliberately tiny artificial rows, not a native collection."""
    sha256='a'*64
    names=names()
    def __init__(self):
        self.events=[]
        self.counts={p:dict(ready=256 if p=='TRAIN' else 64,card=64,destination=64)
                     for p in ('TRAIN','VALIDATION','TEST')}
    def public(self,part,selection=None):
        self.events.append(('public',part))
        if part=='TEST':selection.read(self.sha256)
        return [row(0,2),row(1,3)]
    def public_hashes(self,part):return {'0':'b'*64,'1':'c'*64}
    def labels(self,part,seal=None,selection=None):
        self.events.append(('labels',part))
        if part!='TRAIN':
            sealed=seal.read()
            self.assertions(sealed,part)
            if part=='TEST':
                selected=selection.read(self.sha256)
                assert sealed['epochs']==[selected['epoch']]
                assert sealed['selectionSha256']==selection.sha256
        return labels([row(0,2),row(1,3)])
    @staticmethod
    def assertions(sealed,part):
        assert sealed['partition']==part
        if part=='VALIDATION':assert sealed['epochs']==list(range(1,21))


class PipelineUnitTests(unittest.TestCase):
    def run_synthetic(self,corpus,epoch):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); p=root/'unit-purchase.json';data.document(p,purchase())
            with patch.object(fit,'G3_PURCHASE_SHA',data.sha(p)),patch.object(fit.numerical,'configure'), \
                    patch.object(fit,'select',return_value=epoch):
                result=fit.fit(corpus,p,fit.Recipe(1,tuple(range(2,22)),'cpu'),root/'fit',seconds=60)
            self.assertEqual(20,len(list((root/'fit').glob('epoch-*.json'))))
            return result

    def test_twenty_complete_epochs_precede_val_labels_and_selected_only_test_is_sealed(self):
        corpus=SyntheticCorpus();result=self.run_synthetic(corpus,20)
        self.assertTrue(result['testOpened'])
        self.assertEqual([('public','TRAIN'),('public','VALIDATION'),('labels','TRAIN'),
                          ('labels','VALIDATION'),('public','TEST'),('labels','TEST')],corpus.events)

    def test_e_selection_never_opens_test_features_or_labels(self):
        corpus=SyntheticCorpus();result=self.run_synthetic(corpus,None)
        self.assertFalse(result['testOpened'])
        self.assertNotIn(('public','TEST'),corpus.events)
        self.assertNotIn(('labels','TEST'),corpus.events)

    def test_failed_test_metadata_floor_never_opens_test(self):
        corpus=SyntheticCorpus();corpus.counts['TEST']['ready']=63
        self.assertFalse(self.run_synthetic(corpus,20)['testOpened'])
        self.assertNotIn(('public','TEST'),corpus.events)

    def test_insufficient_training_coverage_opens_no_features_or_labels(self):
        corpus=SyntheticCorpus();corpus.counts['TRAIN']['destination']=63
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError,'coverage floors'):
                fit.fit(corpus,Path(directory)/'absent',fit.Recipe(1,tuple(range(2,22)),'cpu'),Path(directory)/'out')
        self.assertEqual([],corpus.events)


if __name__=='__main__':unittest.main()
