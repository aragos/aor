"""Constructed arithmetic parity and full-offer memory-boundary proofs; no retained inputs."""
import copy
from types import SimpleNamespace
import unittest

import card_policy
import paired_policy_math as shared
import purchase_policy as b
import token_paired_fit as token
import token_paired_policy as token_actor
from test_token_paired_fit import corpus, frozen, model


def original_token_minibatch(actor, optimizer, prepared, slots):
    """Literal pre-extraction family79 minibatch order for the bounded parity fixture."""
    optimizer.zero_grad(set_to_none=True)
    ready=[i for i in slots if prepared.roots[i] is not None]
    gain_value=0.;kl_value=0.
    if ready:
        logs,_=actor([prepared.roots[i] for i in ready]);values=[]
        for i,log in zip(ready,logs):
            root=prepared.root_records[i];target=prepared.training.targets[i]
            values.append(shared.gain(log.exp(),root['currentProbabilities'],root['proposedIndices'],
                target.deltas,root['inclusionProbability']))
        objective=b.torch.stack(values).sum()*512/(len(slots)*prepared.ready_count)
        gain_value=float(objective.detach());(-objective).backward()
    for chunk in shared.batches(prepared.anchor_rows(slots)):
        logs,_=actor([row[0] for row in chunk])
        term=b.torch.stack([shared.original_kl(log,row[1])*row[2] for log,row in zip(logs,chunk)]).sum()*512/(len(slots)*prepared.anchor_count)
        kl_value+=float(term.detach());(.1*term).backward()
    parameters=[getattr(actor,name) for name in b.PARAMETERS[:-2]]
    if not any(parameter.grad is not None for parameter in parameters):
        return dict(gain=gain_value,kl=kl_value,stepped=False)
    b.torch.nn.utils.clip_grad_norm_(parameters,.5,error_if_nonfinite=True);optimizer.step()
    return dict(gain=gain_value,kl=kl_value,stepped=True)


class SharedMathTests(unittest.TestCase):
    def setUp(self):b.torch.set_num_threads(1)

    def test_token_minibatch_preserves_exact_parameter_optimizer_and_scalar_arithmetic(self):
        initial=frozen(model());training,_=corpus(initial)
        left=token_actor.TokenPairedPolicy(initial.model);right=token_actor.TokenPairedPolicy(initial.model)
        a=b.torch.optim.Adam([getattr(left,n) for n in b.PARAMETERS[:-2]],lr=.0003)
        c=b.torch.optim.Adam([getattr(right,n) for n in b.PARAMETERS[:-2]],lr=.0003)
        before=b.digest(initial.model)
        for start in (0,64):
            slots=list(range(start,start+64))
            expected=original_token_minibatch(left,a,token.Prepared(training,left),slots)
            actual=shared.minibatch(right,c,token.Prepared(training,right),slots)
            self.assertEqual(actual,expected)
            self.assertEqual(left.artifact(),right.artifact())
            self.assertEqual(b.encode_optimizer(a),b.encode_optimizer(c))
        self.assertNotEqual(before,b.digest(left.artifact()))

    def test_large_card_offer_chunks_accumulate_before_one_clip_and_adam_step(self):
        artifact=b.initialize(dict(encoderVersion=card_policy.CONTRACT.encoder,
            stateFeatureNames=['cash'],actionFeatureNames=['amount']),119,contract=card_policy.CONTRACT)
        artifact['actorWeights']=[.01*(i%3-1) for i in range(64)]
        actor=b.ResidualPolicy(artifact,card_policy.CONTRACT)
        actor.valueWeights.requires_grad_(False);actor.valueBias.requires_grad_(False)
        view=dict(encoderVersion=card_policy.CONTRACT.encoder,stateFeatures={'cash':.2},baselineIndex=0,
            actions=[dict(command=f'play {i:04}',features={'amount':i/1024}) for i in range(1024)])
        observed=[];original_forward=actor.forward
        def recording(rows):
            observed.append(sum(item.action_count for item in rows));return original_forward(rows)
        actor.forward=recording
        prepared_row=actor.prepare(view)
        old=b.reference_forward(artifact,view,contract=card_policy.CONTRACT)['probabilities']
        prepared=SimpleNamespace(ready_count=17,roots=tuple([prepared_row]*17+[None]*495),
            root_records=tuple([dict(currentProbabilities=old,proposedIndices=[0,1],inclusionProbability=1/1023)]*512),
            training=SimpleNamespace(targets=tuple([SimpleNamespace(deltas=(0.,.2))]*512)),
            anchor_count=1,anchor_rows=lambda slots:())
        optimizer=b.torch.optim.Adam([getattr(actor,n) for n in b.PARAMETERS[:-2]],lr=.0003)
        result=shared.minibatch(actor,optimizer,prepared,list(range(64)))
        reference=b.ResidualPolicy(artifact,card_policy.CONTRACT)
        reference.valueWeights.requires_grad_(False);reference.valueBias.requires_grad_(False)
        reference_optimizer=b.torch.optim.Adam([getattr(reference,n) for n in b.PARAMETERS[:-2]],lr=.0003)
        logs,_=reference([view])
        # Seventeen identical roots in one64-slot batch have total declared gain weight8.
        (-8*shared.gain(logs[0].exp(),old,[0,1],[0.,.2],1/1023)).backward()
        b.torch.nn.utils.clip_grad_norm_([getattr(reference,n) for n in b.PARAMETERS[:-2]],.5)
        reference_optimizer.step()
        for name in b.PARAMETERS:
            self.assertTrue(b.torch.allclose(getattr(actor,name),getattr(reference,name),atol=2e-12,rtol=0),name)
        self.assertTrue(result['stepped']);self.assertEqual(observed,[16384,1024])
        self.assertEqual({int(state['step']) for state in optimizer.state.values()},{1})
        self.assertEqual(len(optimizer.state),5)
        self.assertIsNone(actor.valueWeights.grad);self.assertIsNone(actor.valueBias.grad)
        self.assertEqual(actor.artifact()['valueWeights'],artifact['valueWeights'])


if __name__=='__main__':unittest.main()
