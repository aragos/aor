"""UNIT fixtures only: no retained tensors, original worlds or scientific optimizer steps."""
import copy
import gzip
import json
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import expansion_mc as mc
import joint_trajectory as joint
import card_policy
import expansion_on_policy_data as data
import purchase_policy as policy
from territory_trajectory import bot_seed, signed


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    policy.atomic_json(path, value)
    return path


def fixture(root, model=None, *, empty=False, singleton=False):
    root=root.resolve();root.mkdir(); directory=root/'collection'; directory.mkdir()
    schema=dict(encoderVersion=mc.CONTRACT.encoder, stateFeatureNames=['cash','decision.expand'],
        actionFeatureNames=['cost'])
    original=policy.initialize(dict(encoderVersion=policy.ENCODER_VERSION,
        stateFeatureNames=['cash','decision.purchase-advances'],actionFeatureNames=['cost']),19)
    model=model or mc.initialize(schema,original,23)[0]
    actor=write(root/'actor.json',model)
    frozen=root.parent/'frozen'
    ordinary=write(frozen/'ordinary.json',{'synthetic':True})
    reference=write(frozen/'reference.json',{'syntheticE':True})
    baseline=write(frozen/'baseline.json',{'syntheticF0':True})
    purchase=write(frozen/'purchase.json',original)
    card=write(frozen/'card.json',policy.initialize(dict(encoderVersion=card_policy.CONTRACT.encoder,
        stateFeatureNames=['cash'],actionFeatureNames=['cost']),29,contract=card_policy.CONTRACT))
    configuration='players-4/standard/no-crusades/deck-true/art-true'
    bundle=write(frozen/'bundle.json',dict(schemaVersion=1,kind='joint-card-purchase-1',
        configuration=configuration,baselineSha256=policy.file_digest(baseline),
        card=dict(path=str(card),sha256=policy.file_digest(card)),
        purchase=dict(path=str(purchase),sha256=policy.file_digest(purchase))))
    token=dict(candidateSelector='FIXED_16_TO_15',incumbentSelector='FIXED_16_TO_15',
        opponentSelectors=['FIXED_16_TO_15'])
    arena=write(frozen/'arena.json',dict(candidate=str(ordinary),incumbent=str(ordinary),opponents=[str(ordinary)],
        jointPolicy=dict(baseline=str(baseline),**{r:str(bundle) for r in ('sharedBundle','candidateBundle','incumbentBundle')}),
        tokenOrder=token,expansionImitation=dict(candidateModel=str(reference),incumbentModel=str(reference),
            opponentModels=[str(reference)])))
    for role in ('candidate','incumbent','opponent-0'):
        write(directory/'ecology'/f'{role}.json',{'synthetic':True})
    def subject(path,name):
        target=directory/'ecology'/name;target.parent.mkdir(exist_ok=True)
        target.write_bytes(path.read_bytes())
        return dict(path=str(path),sha256=policy.file_digest(path),snapshot=name)
    roles=dict(source=subject(arena,'source.json'),baseline=subject(baseline,'baseline.json'))
    for role in ('sharedBundle','candidateBundle','incumbentBundle'):
        descriptor,_=joint.load_bundle(bundle,configuration,policy.file_digest(baseline),{})
        descriptor.update(subject(bundle,role+'.json'))
        for phase,path in (('card',card),('purchase',purchase)):
            descriptor[phase].update(subject(path,role+'-'+phase+'.json'))
        roles[role]=descriptor
    bindings=dict(jointPolicy=roles,tokenOrder=token,expansionImitation=dict(
        candidateModel=subject(reference,'E-candidate.json'),incumbentModel=subject(reference,'E-incumbent.json'),
        opponentModels=[subject(reference,'E-opponent.json')]))
    source=write(root/'source.json',dict(kind=data.KIND,arena=str(arena),
        actor=dict(path=str(actor),sha256=policy.file_digest(actor)),games=1,seed=45,replayIndices=[]))
    subjects={}
    for name,path in (('source',source),('arena',arena),('actor',actor)):
        subjects[name]=dict(path=str(path),sha256=policy.file_digest(path),snapshot=name+'.json')
        (directory/(name+'.json')).write_bytes(path.read_bytes())
    manifest=dict(kind=data.KIND,schemaVersion=1,signature='synthetic-signature',configuration=configuration,
        trajectoryEncoding='gzip-json-v1',actorVersion=mc.CONTRACT.actor,**subjects,dictionaries=schema,
        ecologySnapshotDirectory='ecology',policyBindings=bindings,
        frozenCardPurchaseBundleSha256=policy.file_digest(bundle),
        referenceExpansionModelSha256=policy.file_digest(reference),games=1,seed=45,seeds=[-999999955],replayIndices=[])
    observation=dict(encoderVersion=mc.CONTRACT.encoder,stateFeatures={'cash':.5},
        baselineIndex=0,actions=[dict(command='pass',features={'cost':0.})])
    if not singleton:observation['actions'].append(dict(command='place synthetic 1',features={'cost':.2}))
    forward=policy.reference_forward(model,observation,contract=mc.CONTRACT)
    seed=bot_seed(manifest['seeds'][0],3,0);actor_seed=signed(seed^data.ACTOR_SALT)
    selected=policy.select(forward['probabilities'],policy.selection_uniform(actor_seed))
    row=dict(number=3,batchStartNumber=3,botSeed=seed,actorSeed=actor_seed,decision='expand',turn=1,
        observation=observation,selectedIndex=selected,
        behaviorLogProbability=policy.math.log(forward['probabilities'][selected]),behaviorValue=forward['value'])
    scores=[dict(player='p'+str(i+1),advancePoints=4-i,cash=0,misery=0,total=4-i,winner=i==0) for i in range(4)]
    game=dict(schemaVersion=1,kind=data.TRAJECTORY,signature=manifest['signature'],index=0,
        configuration=manifest['configuration'],gameSeed=manifest['seeds'][0],controlledSeat=0,
        opponentIndices=[0]*4,actorModelSha256=policy.file_digest(actor),
        arenaSourceSha256=policy.file_digest(arena),frozenCardPurchaseBundleSha256=manifest['frozenCardPurchaseBundleSha256'],
        referenceExpansionModelSha256=manifest['referenceExpansionModelSha256'],expansionDecisions=[] if empty else [row],
        terminal=dict(reward=1.,winShare=1.,rankReward=1.,scores=scores,gameDecisions=5))
    with gzip.open(directory/'game-0.json.gz','wt') as f:json.dump(game,f)
    write(directory/'collection.json',manifest)
    write(directory/'finished.json',dict(kind=data.KIND,complete=True,signature=manifest['signature'],
        games=1,gameSha256={'game-0.json.gz':policy.file_digest(directory/'game-0.json.gz')},canonicalSha256={}))
    return directory,actor


class ExpansionMcTests(unittest.TestCase):
    def setUp(self):
        policy.torch.set_num_threads(1)
        self.temp=TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.config=policy.UpdateConfig(epochs=1,valueCoefficient=0,maxBehaviorKl=2,maxSeconds=60)

    def batch(self,name='batch',**options):
        return data.ExpansionCollection(*fixture(self.root/name,**options))

    def test_schema2_is_rejected_by_legacy_expansion_contract(self):
        model=self.batch().model
        with self.assertRaisesRegex(ValueError,'schemaVersion 1'):
            policy.validate_model(model,contract=policy.EXPANSION_CONTRACT)

    def test_initial_full_offer_has_exact_e_prior(self):
        row=self.batch().rows[0]
        result=policy.reference_forward(self.batch('other').model,row['observation'],contract=mc.CONTRACT)
        self.assertEqual(result['probabilities'],[.97,.03])

    def test_full_offer_capacity_preserves_e_prior_without_proposal_truncation(self):
        collection=self.batch();model=collection.model
        observation=dict(encoderVersion=mc.CONTRACT.encoder,stateFeatures={'cash':.5},
            baselineIndex=500,actions=[dict(command=f'place {i:04d}',features={'cost':.2}) for i in range(1024)])
        actor=policy.ResidualPolicy(model,mc.CONTRACT)
        with policy.torch.no_grad():logp,_=actor([observation])
        probabilities=logp[0].exp().tolist()
        self.assertEqual(len(probabilities),1024)
        self.assertAlmostEqual(probabilities[500],.97,places=12)
        self.assertAlmostEqual(sum(probabilities),1.,places=12)
        self.assertTrue(all(value>0 for value in probabilities))

    def test_shared_hidden_function_is_transferred_not_just_flat_output(self):
        schema=dict(encoderVersion=policy.ENCODER_VERSION,stateFeatureNames=['cash','decision.purchase'],actionFeatureNames=['x'])
        source=policy.initialize(schema,5);source['stateBias']=[.2]*64
        target,names=mc.initialize(dict(encoderVersion=mc.CONTRACT.encoder,
            stateFeatureNames=['cash','decision.expand','extra'],actionFeatureNames=['y']),source,7)
        self.assertEqual(names,['cash'])
        for i in range(64):
            self.assertEqual(policy.math.tanh(source['stateWeights'][i*2]*.7+.2),
                policy.math.tanh(target['stateWeights'][i*3]*.7+target['stateBias'][i]))
            self.assertEqual(target['stateWeights'][i*3+1:i*3+3],[0.,0.])

    def test_mc_targets_preserve_behavior_fields(self):
        collection=self.batch();obj=mc.ExpansionMcObjective(collection)
        rows,provenance=obj.phase_reader(collection.directory,collection.actor_path,collection.model,contract=mc.CONTRACT)
        actual,identity=obj.bind(rows,provenance,collection.actor_sha)
        self.assertEqual(actual[0]['_actorOnlyAdvantage'],.5)
        self.assertEqual({k:v for k,v in actual[0].items() if k!='_actorOnlyAdvantage'},collection.rows[0])
        self.assertEqual(identity['estimator']['lambda'],1.)

    def test_changed_target_is_rejected(self):
        c=self.batch();obj=mc.ExpansionMcObjective(c)
        rows,p=obj.phase_reader(c.directory,c.actor_path,c.model,contract=mc.CONTRACT)
        rows[0]['_actorOnlyAdvantage']=.4
        with self.assertRaisesRegex(ValueError,'cannot replace'):obj.bind(rows,p,c.actor_sha)

    def test_empty_origin_retained_without_inventing_rows(self):
        c=self.batch(empty=True);self.assertEqual(c.provenance['games'],1);self.assertEqual(c.rows,[])
        with self.assertRaisesRegex(ValueError,'entirely empty'):
            mc.update(c,self.config,self.root/'empty-update')
        self.assertFalse((self.root/'empty-update').exists())

    def test_singleton_row_retains_probability_one(self):
        c=self.batch(singleton=True);self.assertEqual(c.rows[0]['behaviorLogProbability'],0.)

    def test_unit_update_changes_actor_with_only_five_adam_slots(self):
        c=self.batch();report=mc.update(c,self.config,self.root/'update')
        model=policy.read_json(self.root/'update/policy.json');checkpoint=policy.read_checkpoint(self.root/'update/checkpoint.json')
        self.assertNotEqual(model['actorWeights'],c.model['actorWeights'])
        self.assertEqual(model['valueWeights'],c.model['valueWeights']);self.assertEqual(model['valueBias'],c.model['valueBias'])
        self.assertEqual(set(checkpoint['optimizer']['state']),set(map(str,range(5))))
        self.assertEqual(report['expansionMcObjective']['version'],'expansion-mc-actor-only-1')
        self.assertNotIn('jointActorOnlyObjective',report)

    def test_matching_adam_continues_on_next_complete_batch(self):
        first=self.batch();mc.update(first,self.config,self.root/'first')
        next_model=policy.read_json(self.root/'first/policy.json');second=self.batch('second',model=next_model)
        mc.update(second,replace(self.config,seed=77),self.root/'next',checkpoint=self.root/'first/checkpoint.json')
        identity=policy.read_json(self.root/'next/identity.json')
        self.assertGreater(identity['expansionMcOptimizerContinuation']['actorAdamSteps'],0)

    def test_changed_ecology_rejects_carry_before_output(self):
        c=self.batch();mc.update(c,self.config,self.root/'first')
        model=policy.read_json(self.root/'first/policy.json');other=self.batch('second',model=model)
        other.ecology={**other.ecology,'referenceExpansionModelSha256':'c'*64}
        with self.assertRaisesRegex(ValueError,'cannot cross ecology'):
            mc.update(other,self.config,self.root/'invalid',checkpoint=self.root/'first/checkpoint.json')
        self.assertFalse((self.root/'invalid').exists())

    def test_epoch_rejection_restores_actor_and_adam(self):
        c=self.batch()
        with patch.object(policy,'behavior_metrics',return_value={'behaviorKl':3.,'valueMse':0.}):
            result=mc.update(c,self.config,self.root/'rollback')
        self.assertEqual(result['acceptedEpochs'],0)
        checkpoint=policy.read_checkpoint(self.root/'rollback/checkpoint.json')
        self.assertEqual(checkpoint['model'],c.model);self.assertEqual(checkpoint['optimizer']['state'],{})

    def test_original_game_mutation_is_rejected(self):
        c=self.batch();(c.directory/'game-0.json.gz').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'input changed'):c.unchanged()

    def test_critic_coefficient_cannot_enter_expansion_mc(self):
        with self.assertRaisesRegex(ValueError,'actor-only'):
            mc.update(self.batch(),replace(self.config,valueCoefficient=.5),self.root/'invalid')

    def test_changed_role_binding_is_rejected_before_behavior_read(self):
        directory,actor=fixture(self.root/'bad-role')
        path=directory/'collection.json';manifest=policy.read_json(path)
        manifest['policyBindings']['tokenOrder']['candidateSelector']='ORDINARY'
        policy.atomic_json(path,manifest)
        with self.assertRaisesRegex(ValueError,'Every role'):data.ExpansionCollection(directory,actor)

    def test_partial_bundle_subject_cannot_bypass_snapshot_validation(self):
        directory,actor=fixture(self.root/'partial-subject')
        path=directory/'collection.json';manifest=policy.read_json(path)
        subject=manifest['policyBindings']['jointPolicy']['sharedBundle']
        del subject['path']
        policy.atomic_json(path,manifest)
        with self.assertRaisesRegex(ValueError,'complete typed path/hash/snapshot'):
            data.ExpansionCollection(directory,actor)

    def test_empty_component_cannot_stand_in_for_frozen_card_actor(self):
        directory,actor=fixture(self.root/'empty-component')
        path=directory/'collection.json';manifest=policy.read_json(path)
        manifest['policyBindings']['jointPolicy']['candidateBundle']['card']={}
        policy.atomic_json(path,manifest)
        with self.assertRaisesRegex(ValueError,'original typed bundle'):
            data.ExpansionCollection(directory,actor)

    def test_resealed_source_cannot_substitute_same_bytes_actor_path(self):
        directory,actor=fixture(self.root/'actor-path')
        substitute=actor.with_name('other-actor.json');substitute.write_bytes(actor.read_bytes())
        path=directory/'collection.json';manifest=policy.read_json(path)
        source=Path(manifest['source']['path']);value=policy.read_json(source)
        value['actor']['path']=str(substitute);policy.atomic_json(source,value)
        (directory/'source.json').write_bytes(source.read_bytes())
        manifest['source']['sha256']=policy.file_digest(source);policy.atomic_json(path,manifest)
        with self.assertRaisesRegex(ValueError,'exact manifest subjects'):
            data.ExpansionCollection(directory,actor)

    def test_resealed_wrong_likelihood_is_rejected(self):
        directory,actor=fixture(self.root/'bad-row')
        path=directory/'game-0.json.gz'
        with gzip.open(path,'rt') as f:game=json.load(f)
        game['expansionDecisions'][0]['behaviorLogProbability']=-2.
        with gzip.open(path,'wt') as f:json.dump(game,f)
        receipt=policy.read_json(directory/'finished.json');receipt['gameSha256'][path.name]=policy.file_digest(path)
        policy.atomic_json(directory/'finished.json',receipt)
        with self.assertRaisesRegex(ValueError,'likelihood/value'):data.ExpansionCollection(directory,actor)

    def test_resealed_wrong_batch_seed_is_rejected(self):
        directory,actor=fixture(self.root/'bad-seed')
        path=directory/'game-0.json.gz'
        with gzip.open(path,'rt') as f:game=json.load(f)
        game['expansionDecisions'][0]['batchStartNumber']=2
        with gzip.open(path,'wt') as f:json.dump(game,f)
        receipt=policy.read_json(directory/'finished.json');receipt['gameSha256'][path.name]=policy.file_digest(path)
        policy.atomic_json(directory/'finished.json',receipt)
        with self.assertRaisesRegex(ValueError,'sequential'):data.ExpansionCollection(directory,actor)

    def test_partial_adam_cannot_reset_a_parameter(self):
        c=self.batch();mc.update(c,self.config,self.root/'first')
        path=self.root/'first/checkpoint.json';checkpoint=policy.read_checkpoint(path)
        del checkpoint['optimizer']['state']['2'];policy.save_checkpoint(path,checkpoint)
        model=policy.read_json(self.root/'first/policy.json');other=self.batch('second',model=model)
        with self.assertRaisesRegex(ValueError,'all five moments'):
            mc.update(other,self.config,self.root/'invalid',checkpoint=path)

    def test_no_legacy_actor_only_flag_can_admit_expansion(self):
        c=self.batch();objective=mc.ExpansionMcObjective(c)
        with self.assertRaisesRegex(ValueError,'purchase Adam'):
            policy.update(c.actor_path,c.directory,self.config,self.root/'invalid',contract=mc.CONTRACT,
                entrypoint=mc.__file__,collection_reader=objective.phase_reader,
                actor_only=policy.ActorOnlyObjective("recorded"))


if __name__=='__main__':unittest.main()
