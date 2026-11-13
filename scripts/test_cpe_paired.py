"""Constructed typed cohorts only; no retained model, world, outcome or reserved seed is read."""
import copy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import purchase_policy as b
import cpe_paired_policy as policy
import cpe_paired_data as data
import cpe_paired_fit as fit
import cpe_paired_validation as validation
import paired_policy_math as math_owner


def digest_bytes(raw):return hashlib.sha256(raw).hexdigest()


def bundle():
    actors=[];heads={}
    for ordinal,head in enumerate(policy.HEADS):
        schema=dict(encoderVersion=head.contract.encoder,stateFeatureNames=['role.0.cash'],actionFeatureNames=['amount'])
        model=b.initialize(schema,113+ordinal,contract=head.contract)
        if head is not policy.Head.EXPANSION:model['actorWeights']=[.03*(index%3-1) for index in range(64)]
        model['valueWeights']=[.07]*64;model['valueBias']=.23
        raw=b.canonical(model);actor=policy.FrozenActor(head,raw,digest_bytes(raw));actors.append(actor)
        heads[head.value]=dict(path=f'heads/{head.value}/policy.json',sha256=actor.sha256,dictionarySha256=actor.dictionary_sha256)
    raw=b.canonical(dict(kind=policy.BUNDLE_KIND,heads=heads))
    return policy.Bundle(raw,digest_bytes(raw),tuple(actors))


def view(head,count=3):
    return dict(encoderVersion=head.contract.encoder,stateFeatures={'role.0.cash':.2},baselineIndex=0,
        actions=[dict(command=f'unit-{index:02}',features={'amount':index/36}) for index in range(count)])


def context(original,head,current=None,iteration=1):
    current=current or original;actor=original.actor(head)
    dictionary=b.canonical({key:actor.model[key] for key in ('encoderVersion','stateFeatureNames','actionFeatureNames')})
    common='a'*64
    return data.Context(head,iteration,b.digest(['corpus',head.value,iteration]),
        b.digest(dict(bundleScopeSha256=common,head=head.value)),common,'b'*64,
        b.digest(['bank',head.value,iteration]),digest_bytes(dictionary),actor.dictionary_sha256,
        actor.sha256,current.actor(head).sha256,original.sha256,current.sha256),dictionary


def public_record(ctx,original,current,index=0,status='READY',count=3):
    missing=status=='MISSING';head=ctx.head;observation=None if missing else view(head,count)
    number=policy.HEADS.index(head)*100000+ctx.iteration*10000+index*2
    original_probs=None if missing else b.reference_forward(original.actor(head).model,observation,contract=head.contract)['probabilities']
    current_probs=None if missing else b.reference_forward(current.actor(head).model,observation,contract=head.contract)['probabilities']
    reference=None if missing else count-1
    indices=None if missing else [reference,*[i for i in range(count) if i!=reference][:8]]
    return dict(head=head.value,index=index,split='TRAIN' if index<512 else 'VALIDATION',status=status,
        requestSha256=b.digest(['request',head.value,ctx.iteration,index]),decisionNumber=None if missing else 8,
        batchStartNumber=None if missing else 7,observation=observation,currentProbabilities=current_probs,
        originalProbabilities=original_probs,sampledIndex=reference,referenceIndex=reference,proposedIndices=indices,
        inclusionProbability=None if missing else (min(8,count-1)/(count-1) if count>1 else 0.),futureSeeds=[-number-1,-number-2])


def label(root):
    row=root.record
    return dict(publicSha256=root.sha256,futureSeeds=row['futureSeeds'],actions=[
        dict(actionIndex=index,rewards=[.2,.4] if ordinal==0 else [.4,.6])
        for ordinal,index in enumerate(row['proposedIndices'])])


def cohort(original,head,current=None,iteration=1,ready=256):
    current=current or original;ctx,dictionary=context(original,head,current,iteration)
    roots=[];targets=[];anchors=[];initial=original.actor(head)
    # The repeated constructed state has one precomputed public distribution per actor.
    template=public_record(ctx,original,current)
    for index in range(512):
        row=dict(template,index=index,requestSha256=b.digest(['request',head.value,iteration,index]),
            futureSeeds=[-(policy.HEADS.index(head)*100000+iteration*10000+index*2)-1,
                -(policy.HEADS.index(head)*100000+iteration*10000+index*2)-2])
        if index>=ready:
            row.update(status='MISSING')
            for key in data.ROOT_FIELDS:
                if key not in ('head','index','split','status','requestSha256','futureSeeds'):row[key]=None
        root=data.PublicRoot.from_record(row,ctx,current.actor(head).model,split='TRAIN');roots.append(root)
        targets.append(data.PairedTarget.from_record(root,label(root)) if root.ready else None)
        observations=[] if index>=384 else [dict(decisionNumber=8,batchStartNumber=7,
            observation=view(head),originalProbabilities=template['originalProbabilities'])]
        anchors.append(data.AnchorWorld.from_record(dict(head=head.value,originalModelSha256=initial.sha256,
            index=index,split='TRAIN',observations=observations),initial))
    return data.Training(ctx,tuple(roots),tuple(targets),tuple(anchors),64),dictionary


def all_cohorts(original,current=None,iteration=1):
    rows={};dictionaries={};configs={};deadline=time.time()+600
    for ordinal,head in enumerate(policy.HEADS):
        rows[head],dictionaries[head]=cohort(original,head,current,iteration)
        configs[head]=fit.Config(tuple(range(iteration*1000+ordinal*20,iteration*1000+ordinal*20+20)),deadline)
    return rows,dictionaries,configs


def synthetic_epoch(actor,optimizer,prepared,seed,config):
    """Transaction test double: genuine five-slot Adam step, no scientific objective substitution."""
    optimizer.zero_grad(set_to_none=True)
    loss=sum((getattr(actor,name)**2).sum() for name in b.PARAMETERS[:-2])*.001
    loss.backward();optimizer.step()
    return dict(accepted=True,anchor=dict(mean=0.,maximum=0.,worlds=384,states=384,singletonStates=0),
        minibatchGainMean=0.,minibatchKlMean=0.,attemptedAdamSteps=1)


class DataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):b.torch.set_num_threads(1);cls.original=bundle()

    def test_cpe_heads_preserve_nonzero_original_card_purchase(self):
        self.original.original()
        for head in (policy.Head.CARD,policy.Head.PURCHASE):
            result=b.reference_forward(self.original.actor(head).model,view(head),contract=head.contract)
            self.assertNotEqual(result['probabilities'],b.prior_probabilities(3,0))

    def test_reference_sample_can_differ_from_observation_prior(self):
        head=policy.Head.CARD;ctx,_=context(self.original,head)
        row=public_record(ctx,self.original,self.original)
        root=data.PublicRoot.from_record(row,ctx,self.original.actor(head).model,split='TRAIN')
        self.assertEqual(root.record['referenceIndex'],2);self.assertEqual(root.record['observation']['baselineIndex'],0)
        self.assertEqual(data.PairedTarget.from_record(root,label(root)).deltas[0],0.)

    def test_reference_must_equal_current_sample(self):
        head=policy.Head.PURCHASE;ctx,_=context(self.original,head);row=public_record(ctx,self.original,self.original)
        row['referenceIndex']=0
        with self.assertRaisesRegex(ValueError,'Delta reference'):
            data.PublicRoot.from_record(row,ctx,self.original.actor(head).model,split='TRAIN')

    def test_singleton_root_is_rejected_but_singleton_anchor_is_valid(self):
        head=policy.Head.CARD;ctx,_=context(self.original,head);row=public_record(ctx,self.original,self.original,status='SINGLETON',count=1)
        with self.assertRaisesRegex(ValueError,'singleton states are anchors only'):
            data.PublicRoot.from_record(row,ctx,self.original.actor(head).model,split='TRAIN')
        anchor=data.AnchorWorld.from_record(dict(head=head.value,originalModelSha256=self.original.actor(head).sha256,
            index=0,split='TRAIN',observations=[dict(decisionNumber=8,batchStartNumber=7,observation=view(head,1),originalProbabilities=[1.])]),self.original.actor(head))
        self.assertEqual(len(anchor.observations),1)

    def test_cross_head_model_cannot_alias_contract(self):
        with self.assertRaisesRegex(ValueError,'encoder version'):
            policy.validate_model(self.original.actor(policy.Head.CARD).model,policy.Head.PURCHASE)

    def test_head_scope_intrinsically_binds_common_scope(self):
        ctx,_=context(self.original,policy.Head.CARD)
        with self.assertRaisesRegex(ValueError,'Head scope'):
            replace(ctx,bundle_scope_sha256='c'*64)

    def test_complete_bundle_requires_all_three_exact_components(self):
        value=json.loads(self.original.raw);value['heads']['CARD']['sha256']='0'*64;raw=b.canonical(value)
        with self.assertRaisesRegex(ValueError,'exact raw actor'):
            policy.Bundle(raw,digest_bytes(raw),self.original.actors)

    def test_validation_split_rejected_before_observation_access(self):
        class Poison(dict):
            def __getitem__(self,key):raise AssertionError('No observation access allowed')
        with self.assertRaisesRegex(ValueError,'Public split'):
            data.PublicRoot.from_record(Poison(split='VALIDATION'),None,None,split='TRAIN')

    def test_whole_bundle_context_binds_value_parameters_of_other_heads(self):
        ctx,dictionary=context(self.original,policy.Head.CARD,iteration=2)
        actors=list(self.original.actors);model=actors[1].model;model['valueBias']+=.1;raw=b.canonical(model)
        actors[1]=policy.FrozenActor(policy.Head.PURCHASE,raw,digest_bytes(raw))
        descriptor=json.loads(self.original.raw);descriptor['heads']['PURCHASE']['sha256']=actors[1].sha256
        raw=b.canonical(descriptor);current=policy.Bundle(raw,digest_bytes(raw),tuple(actors))
        ctx=replace(ctx,current_bundle_sha256=current.sha256)
        with self.assertRaisesRegex(ValueError,'original value parameters'):
            ctx.bind(self.original,current,dictionary)

    def test_mutating_return_copy_cannot_change_immutable_training(self):
        training,_=cohort(self.original,policy.Head.CARD);before=training.sha256
        row=training.roots[0].record;row['observation']['baselineIndex']=2
        self.assertEqual(training.sha256,before)


class MathTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):b.torch.set_num_threads(1);cls.original=bundle()

    def test_all_five_actor_arrays_train_and_nonzero_value_arrays_stay_frozen(self):
        for head in policy.HEADS:
            actor=policy.CpePolicy(self.original.actor(head).model,head)
            if head is policy.Head.EXPANSION:
                with b.torch.no_grad():actor.actorWeights.fill_(.03)
            logs,_=actor([view(head)]);(-logs[0][2]).backward()
            self.assertTrue(all(getattr(actor,key).requires_grad for key in b.PARAMETERS[:-2]))
            self.assertTrue(all(getattr(actor,key).grad is not None for key in b.PARAMETERS[:-2]))
            self.assertIsNone(actor.valueWeights.grad);self.assertIsNone(actor.valueBias.grad)

    def test_nonzero_original_probabilities_verified_before_updates(self):
        training,_=cohort(self.original,policy.Head.CARD)
        prepared=fit.Prepared(training,policy.CpePolicy(self.original.actor(policy.Head.CARD).model,policy.Head.CARD))
        fit.verify_original(prepared,policy.CpePolicy(self.original.actor(policy.Head.CARD).model,policy.Head.CARD))
        other=self.original.actor(policy.Head.CARD).model;other['actorWeights']=[0.]*64
        with self.assertRaisesRegex(ValueError,'original full probabilities'):
            fit.verify_original(prepared,policy.CpePolicy(other,policy.Head.CARD))

    def test_reference_offset_does_not_change_full_action_improvement(self):
        probabilities=b.torch.tensor([.5,.3,.2],dtype=b.DTYPE,requires_grad=True);old=[.6,.1,.3];returns=[.2,.8,.5]
        expected=sum((probabilities[i]-old[i])*returns[i] for i in range(3))
        actual=math_owner.gain(probabilities,old,[2,0,1],[0.,returns[0]-returns[2],returns[1]-returns[2]],1.)
        self.assertAlmostEqual(float(actual.detach()),float(expected.detach()),places=15)
        # Equal probability mass is the invariant; compare logits gradients to remove the additive constant.
        logits=b.torch.tensor([.1,.2,.3],dtype=b.DTYPE,requires_grad=True);p=logits.softmax(0)
        left=math_owner.gain(p,old,[2,0,1],[0.,-.3,.3],1.);right=sum((p[i]-old[i])*returns[i] for i in range(3))
        self.assertTrue(b.torch.allclose(b.torch.autograd.grad(left,logits,retain_graph=True)[0],b.torch.autograd.grad(right,logits)[0],atol=1e-16,rtol=0))

    def test_equal_phase_variance_uses_independent_world_counts(self):
        values={head.value:dict(mean=value,variance=variance,origins=count) for head,value,variance,count in
            zip(policy.HEADS,[.1,.2,.6],[.2,.3,.4],[64,80,128])}
        result=validation.equal_phase(values)
        self.assertAlmostEqual(result['mean'],.3)
        self.assertAlmostEqual(result['standardError']**2,(.2/64+.3/80+.4/128)/9)


class TransactionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        b.torch.set_num_threads(1);cls.original=bundle()
        cls.trainings,cls.dictionaries,cls.configs=all_cohorts(cls.original)

    def test_all_head_fit_publishes_one_portable_complete_bundle(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(math_owner,'epoch',side_effect=synthetic_epoch):
            result=fit.fit_bundle(self.trainings,self.original,self.original,self.dictionaries,Path(temp)/'fit',self.configs)
            self.assertTrue(result['operationalPassed']);self.assertEqual(set(result['identity']['contexts']),{'CARD','PURCHASE','EXPANSION'})
            self.assertEqual(fit.completed(Path(temp)/'fit'),result)
            published=fit.load_completed_bundle(Path(temp)/'fit')
            for actor in published.actors:
                self.assertEqual(actor.model['valueWeights'],self.original.actor(actor.head).model['valueWeights'])
                self.assertEqual(actor.model['valueBias'],.23)
            self.assertEqual(len(result['artifacts']),17)

    def test_second_head_failure_leaves_no_consumable_bundle(self):
        original_fit=fit._fit_head
        def fail_second(training,*args,**kwargs):
            if training.context.head is policy.Head.PURCHASE:raise ValueError('constructed second-head failure')
            return original_fit(training,*args,**kwargs)
        with tempfile.TemporaryDirectory() as temp,patch.object(math_owner,'epoch',side_effect=synthetic_epoch),patch.object(fit,'_fit_head',side_effect=fail_second):
            output=Path(temp)/'fit'
            with self.assertRaisesRegex(ValueError,'second-head failure'):
                fit.fit_bundle(self.trainings,self.original,self.original,self.dictionaries,output,self.configs)
            self.assertTrue((output/'heads/CARD/finished.json').exists());self.assertTrue((output/'failed.json').exists())
            self.assertFalse((output/'bundle.json').exists());self.assertFalse((output/'finished.json').exists())

    def test_zero_changed_child_stops_before_other_heads_or_publication(self):
        def rejected(actor,optimizer,prepared,seed,config):
            return dict(accepted=False,anchor=dict(mean=.021,maximum=.03),minibatchGainMean=0.,minibatchKlMean=0.,attemptedAdamSteps=8)
        with tempfile.TemporaryDirectory() as temp,patch.object(math_owner,'epoch',side_effect=rejected):
            output=Path(temp)/'fit';result=fit.fit_bundle(self.trainings,self.original,self.original,self.dictionaries,output,self.configs)
            self.assertEqual(result['reason'],'no-accepted-actor-change');self.assertFalse((output/'heads/PURCHASE').exists())
            self.assertFalse((output/'bundle.json').exists())

    def test_exposure_failure_precedes_any_child_fit(self):
        training,_=cohort(self.original,policy.Head.CARD,ready=255)
        with tempfile.TemporaryDirectory() as temp,patch.object(fit,'_fit_head') as update:
            with self.assertRaisesRegex(ValueError,'at least256'):
                fit.fit_bundle(dict(self.trainings,CARD=training),self.original,self.original,self.dictionaries,Path(temp)/'fit',self.configs)
            update.assert_not_called()

    def test_shared_shuffle_is_rejected_before_any_fit(self):
        configs=dict(self.configs);configs[policy.Head.PURCHASE]=configs[policy.Head.CARD]
        with tempfile.TemporaryDirectory() as temp,patch.object(fit,'_fit_head') as update:
            with self.assertRaisesRegex(ValueError,'60 distinct'):
                fit.fit_bundle(self.trainings,self.original,self.original,self.dictionaries,Path(temp)/'fit',configs)
            update.assert_not_called()

    def test_negative_second_moment_rejected(self):
        actor=policy.CpePolicy(self.original.actor(policy.Head.CARD).model,policy.Head.CARD)
        optimizer=b.torch.optim.Adam([getattr(actor,name) for name in b.PARAMETERS[:-2]],lr=.0003)
        synthetic_epoch(actor,optimizer,None,None,None);encoded=b.encode_optimizer(optimizer)
        encoded['state']['0']['exp_avg_sq']['tensor'][0][0]=-1.
        with self.assertRaisesRegex(ValueError,'Finite actor moment'):
            fit.validate_optimizer(encoded,actor.artifact(),1)


if __name__=='__main__':unittest.main()
