"""Pure invented evidence checks: no simulator, Torch, model forward or fit."""
import ast
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import random
import subprocess
import sys
import tarfile
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

import expansion_baseline_audit as audit


def put(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,sort_keys=True,allow_nan=False)+'\n')
    return path


def target_objective(batch=1):
    return dict(batch=batch,replica='PRIMARY_DESKTOP',protocol={'sha256':'a'*64,'path':'/UNIT/protocol'},
        basis={'UNIT':'state-only'},previousBaseline=None,previousTransaction=None,
        collectionSha256='b'*64,gameHashes=['c'*64])


def targets(rows, batch=1):
    objective = target_objective(batch)
    records = [dict(originIndex=i,number=n,prediction=v,advantage=r-v) for i,n,r,v in rows]
    objective.update(predictionsSha256=audit.digest([r['prediction'] for r in records]),
        targetsSha256=audit.digest([r['advantage'] for r in records]))
    document = dict(kind='expansion-detached-previous-baseline-targets-1',
        **{k:v for k,v in objective.items() if k not in ('predictionsSha256','targetsSha256')},rows=records)
    return objective,document,[(i,n,r) for i,n,r,_ in rows]


def round_receipt(index, progress=True):
    return dict(batch=index,complete=True,elapsedSeconds=1.,retainedBytes=100,
        arms={arm:dict(worlds=256,reproductions=1,exposure=dict(branchingWorlds=1,cardOfferWorlds=1),
            acceptedEpochs=int(progress),actorChanged=progress,previousActorAdamSteps=index-1,
            actorAdamSteps=index if progress else index-1) for arm in audit.loop.ARMS})


def plan():
    return audit.loop.Plan((),(),10,dict(minimumBranchingWorldsPerArm=2,minimumCardOfferWorldsPerArm=2,
        maximumSecondsPerRound=10,maximumBytesPerRound=1000,screenReserveSeconds=10))


def finished(count, reason='both-eight-complete'):
    complete = reason == 'both-eight-complete'
    return dict(completedRounds=count,completedBatches={arm:count for arm in audit.loop.ARMS},
        scientificWorlds=count*512,historicalReproductions=count*2,qualification=False,automaticSuccessor=False,
        complete=complete,eligibleFinalActors=complete,stopReason=reason)


def payload(accepted=4, inherited=0, rows=65, negative_kl=False, steps=None):
    count = inherited+accepted*((rows+63)//64) if steps is None else steps
    group = dict(params=list(range(5)),lr=.0003,betas=[.9,.999],eps=1e-8,weight_decay=0,
        amsgrad=False,maximize=False,foreach=False,capturable=False,differentiable=False,fused=None)
    state = {str(i):dict(step={'dtype':'torch.float32','tensor':float(count)},**{
        key:dict(dtype='torch.float64',tensor=[[.1] for _ in range(64)] if i in (0,2) else [.1]*64)
        for key in ('exp_avg','exp_avg_sq')}) for i in range(5)} if count else {}
    history = [dict(epoch=i+1,accepted=True,behaviorKl=-1e-15 if negative_kl else .001) for i in range(accepted)]
    if accepted < 4: history.append(dict(epoch=accepted+1,accepted=False,behaviorKl=.02))
    stop = None if accepted == 4 else 'behavior-kl-limit'
    value = dict(epoch=accepted,history=history,stopReason=stop,optimizer=dict(param_groups=[group],state=state))
    report = dict(acceptedEpochs=accepted,stopReason='epochs-complete' if stop is None else stop)
    return value,report


class DetachedTargetTests(unittest.TestCase):
    def test_retained_predictions_verify_exact_subtraction_without_forward(self):
        objective,document,rows = targets([(0,3,1.,.5),(2,8,1/3,.5)])
        audit.detached_targets(document,rows,objective)

    def test_first_batch_cannot_use_a_fitted_prediction(self):
        objective,document,rows = targets([(0,3,1.,.7)])
        with self.assertRaisesRegex(ValueError,'previous prediction'):
            audit.detached_targets(document,rows,objective)

    def test_later_batch_prediction_may_differ_from_constant_half(self):
        objective,document,rows = targets([(0,3,1.,.7)],batch=2)
        audit.detached_targets(document,rows,objective)

    def test_resealed_target_still_must_subtract_original_return(self):
        objective,document,rows = targets([(0,3,1.,.5)])
        document['rows'][0]['advantage'] = .1
        objective['targetsSha256'] = audit.digest([.1])
        with self.assertRaisesRegex(ValueError,'previous prediction'):
            audit.detached_targets(document,rows,objective)

    def test_changed_origin_order_rejects(self):
        objective,document,rows = targets([(0,3,1.,.5),(2,8,.3,.5)])
        document['rows'].reverse()
        with self.assertRaisesRegex(ValueError,'chronology'):
            audit.detached_targets(document,rows,objective)

    def test_other_replica_prediction_evidence_rejects(self):
        objective,document,rows = targets([(0,3,1.,.5)])
        document['replica'] = 'MAC_REPLICATION'
        with self.assertRaisesRegex(ValueError,'replica'):
            audit.detached_targets(document,rows,objective)

    def test_out_of_range_prediction_rejects_without_clipping_it_again(self):
        objective,document,rows = targets([(0,3,1.,1.1)],batch=2)
        with self.assertRaisesRegex(ValueError,'previous prediction'):
            audit.detached_targets(document,rows,objective)

    def test_changed_prediction_digest_rejects(self):
        objective,document,rows = targets([(0,3,1.,.5)])
        objective['predictionsSha256'] = '0'*64
        with self.assertRaisesRegex(ValueError,'seal'):
            audit.detached_targets(document,rows,objective)


class ActorHistoryTests(unittest.TestCase):
    model = dict(stateFeatureNames=['x'],actionFeatureNames=['a'])
    config = dict(learningRate=.0003,batchSize=64,maxBehaviorKl=.01,seed=0)

    def test_exact_accepted_minibatches_advance_all_five_slots(self):
        value,report = payload(inherited=7)
        self.assertEqual(audit.actor_history(value,report,[1]*65,7,self.model,self.config),(4,15))

    def test_negative_roundoff_kl_remains_valid_original_owner_behavior(self):
        value,report = payload(negative_kl=True)
        self.assertEqual(audit.actor_history(value,report,[1]*65,0,self.model,self.config),(4,8))

    def test_rejected_epoch_preserves_inherited_counters(self):
        value,report = payload(accepted=0,inherited=7)
        self.assertEqual(audit.actor_history(value,report,[1]*65,7,self.model,self.config),(0,7))

    def test_fresh_rejected_epoch_keeps_valid_empty_optimizer(self):
        value,report = payload(accepted=0)
        self.assertEqual(audit.actor_history(value,report,[1]*65,0,self.model,self.config),(0,0))

    def test_uniform_but_wrong_step_count_rejects(self):
        value,report = payload()
        for slot in value['optimizer']['state'].values(): slot['step']['tensor'] = 9.
        with self.assertRaisesRegex(ValueError,'complete accepted epochs'):
            audit.actor_history(value,report,[1]*65,0,self.model,self.config)

    def test_partial_five_slot_carry_rejects(self):
        value,report = payload()
        del value['optimizer']['state']['4']
        with self.assertRaisesRegex(ValueError,'Missing actor moments'):
            audit.actor_history(value,report,[1]*65,0,self.model,self.config)

    def test_acceptance_above_original_kl_limit_rejects(self):
        value,report = payload()
        value['history'][0]['behaviorKl'] = .02
        with self.assertRaisesRegex(ValueError,'KL acceptance'):
            audit.actor_history(value,report,[1]*65,0,self.model,self.config)

    def test_action_budget_splits_small_decision_batches(self):
        value,report = payload(inherited=7,steps=15)
        self.assertEqual(audit.actor_history(value,report,[1024]*17,7,self.model,self.config),(4,15))

    def test_exact_action_limit_does_not_split_early(self):
        self.assertEqual(audit.accepted_minibatches([1024]*16,self.config,4),4)

    def test_original_epoch_shuffle_changes_accepted_step_total(self):
        counts = [1024]*40+[1]*150
        value,report = payload(inherited=7,steps=22)
        self.assertEqual(audit.actor_history(value,report,counts,7,self.model,self.config),(4,22))

    def test_rejected_action_bounded_epoch_is_rolled_back(self):
        counts = [1024]*40+[1]*150
        value,report = payload(accepted=1,inherited=7,steps=10)
        self.assertEqual(audit.actor_history(value,report,counts,7,self.model,self.config),(1,10))

    def test_counts_match_original_pure_owner_without_importing_torch(self):
        # Compile only the original two pure function ASTs, never execute module imports.
        path = Path(__file__).with_name('purchase_policy.py')
        functions = [node for node in ast.parse(path.read_text()).body
                     if isinstance(node,ast.FunctionDef) and node.name in ('row_batches','epoch_batches')]
        self.assertEqual({node.name for node in functions},{'row_batches','epoch_batches'})
        namespace = {'random':random}
        exec(compile(ast.Module(body=functions,type_ignores=[]),str(path),'exec'),namespace)
        for counts in ([],[1]*65,[1024]*16,[1024]*17,[1024]*40+[1]*150):
            for seed in (-7,0,123):
                with self.subTest(rows=len(counts),seed=seed):
                    config = dict(self.config,seed=seed)
                    rows = [{'observation':{'actions':[None]*count}} for count in counts]
                    expected = sum(sum(1 for _ in namespace['epoch_batches'](
                        rows,SimpleNamespace(**config),epoch)) for epoch in range(4))
                    self.assertEqual(audit.accepted_minibatches(counts,config,4),expected)


class StopTests(unittest.TestCase):
    def test_both_complete_eighth_actors_can_be_eligible(self):
        audit.stopped_result(finished(8),[round_receipt(i) for i in range(1,9)],plan(),1000)

    def test_cost_failed_two_round_prefix_is_retained_without_nominee(self):
        audit.stopped_result(finished(2,'operational-prefix-gate'),[round_receipt(1),round_receipt(2)],plan(),0)

    def test_passing_prefix_cannot_claim_operational_failure(self):
        with self.assertRaisesRegex(ValueError,'exact operational reason'):
            audit.stopped_result(finished(2,'operational-prefix-gate'),[round_receipt(1),round_receipt(2)],plan(),1000)

    def test_four_later_stalls_stop_both_after_passing_prefix(self):
        rounds = [round_receipt(i,i <= 2) for i in range(1,7)]
        audit.stopped_result(finished(6,'four-no-progress-batches'),rounds,plan(),1000)

    def test_learning_cannot_continue_after_first_four_stalls(self):
        rounds = [round_receipt(i,i <= 2 or i == 7) for i in range(1,8)]
        with self.assertRaisesRegex(ValueError,'continued'):
            audit.stopped_result(finished(7,'four-no-progress-batches'),rounds,plan(),1000)

    def test_intermediate_complete_nominee_rejects(self):
        with self.assertRaisesRegex(ValueError,'both completed eighth'):
            audit.stopped_result(finished(4),[round_receipt(i) for i in range(1,5)],plan(),1000)


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.directory = self.root/'learning/batches/batch-01/TREATMENT'
        self.numeric = self.directory/'numeric'
        self.pins = audit.e.Pins()
        self.protocol = audit.subject(put(self.root/'UNIT-protocol.json',{'replica':'PRIMARY_DESKTOP'}),self.pins)
        self.basis = {'UNIT':'frozen initial state basis'}
        self.data = dict(collectionSha256='a'*64,gameHashes=['b'*64]*256,
            origins=[dict(index=i,decisions=2 if i == 0 else 0) for i in range(256)])
        self.health = dict(acceptedEpochs=1,actorChanged=True,actorAdamSteps=1,previousActorAdamSteps=0)
        for name in ('identity','checkpoint','policy','finished','epoch-started'):
            put(self.numeric/'actor'/f'{name}.json',{'UNIT':name})
        target = audit.subject(put(self.numeric/'detached-targets.json',{'UNIT':'retained predictions'}),self.pins)
        self.objective = dict(protocol=self.protocol,replicaProtocolSha256=self.protocol['sha256'],
            replica='PRIMARY_DESKTOP',batch=1,basis=self.basis,previousTransaction=None,previousBaseline=None,detachedTargets=target)
        self.identity = dict(kind=audit.TRANSACTION,arm='TREATMENT',batch=1,replica='PRIMARY_DESKTOP',protocol=self.protocol,
            basis=self.basis,previousTransaction=None,previousBaseline=None,detachedTargets=target,data=self.data)
        put(self.numeric/'identity.json',self.identity)
        self.actor = {name:audit.subject(self.numeric/'actor'/f'{name}.json',self.pins)
                      for name in ('identity','checkpoint','policy','finished')}
        self.fitted = dict(kind='expansion-frozen-state-ridge-1',recipe=audit.RIDGE_RECIPE,basis=self.basis,
            slopes=[0.]*64,intercept=.5,worlds=256,representedWorlds=1,emptyWorlds=255,rows=2,rowWeightSum=1.,
            provenance=dict(batch=1,replica='PRIMARY_DESKTOP',protocol=self.protocol,
                collectionSha256=self.data['collectionSha256'],gameHashes=self.data['gameHashes'],actorCompletion=self.actor['finished']))
        put(self.numeric/'baseline.json',self.fitted)
        self.seal()

    def seal(self):
        done = dict(kind=audit.TRANSACTION,arm='TREATMENT',batch=1,replica='PRIMARY_DESKTOP',protocol=self.protocol,
            complete=True,identity={'path':str(self.numeric/'identity.json'),'sha256':audit.sha(self.numeric/'identity.json')},
            previousTransaction=None,actor=self.actor,nextBaseline={'path':str(self.numeric/'baseline.json'),
                'sha256':audit.sha(self.numeric/'baseline.json')},**self.health,
            artifacts={k:v for k,v in audit.inventory(self.numeric).items() if k != 'finished.json'})
        put(self.numeric/'finished.json',done)

    def verify(self):
        return audit.transaction(self.directory,1,self.protocol,'PRIMARY_DESKTOP',self.basis,self.data,
                                 self.objective,self.health,audit.e.Pins())

    def test_complete_transaction_binds_actor_and_next_baseline(self):
        self.assertEqual(self.verify()['sha256'],audit.sha(self.numeric/'finished.json'))

    def test_resealed_wrong_actor_completion_cannot_justify_baseline(self):
        self.fitted['provenance']['actorCompletion'] = self.actor['identity']
        put(self.numeric/'baseline.json',self.fitted); self.seal()
        with self.assertRaisesRegex(ValueError,'completed actor/cohort'): self.verify()

    def test_resealed_wrong_weight_mass_rejects(self):
        self.fitted['rowWeightSum'] = 2.
        put(self.numeric/'baseline.json',self.fitted); self.seal()
        with self.assertRaisesRegex(ValueError,'World-weighted'): self.verify()

    def test_extra_unpublished_numeric_file_rejects_even_if_resealed(self):
        put(self.numeric/'extra.json',{'UNIT':'undeclared'}); self.seal()
        with self.assertRaisesRegex(ValueError,'undeclared'): self.verify()

    def test_same_baseline_from_other_replica_rejects(self):
        self.objective['replica'] = 'MAC_REPLICATION'
        with self.assertRaisesRegex(ValueError,'same-replica'): self.verify()

    def test_baseline_claiming_current_instead_of_previous_batch_rejects(self):
        self.objective['previousBaseline'] = {'path':str(self.numeric/'baseline.json'),'sha256':audit.sha(self.numeric/'baseline.json')}
        with self.assertRaisesRegex(ValueError,'same-replica'): self.verify()

    def test_missing_atomic_completion_cannot_publish_actor(self):
        (self.numeric/'finished.json').unlink()
        with self.assertRaisesRegex(ValueError,'regular file'): self.verify()


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()

    def archive(self):
        members = {'collection/collection.json':b'{"UNIT":true}',
            'collection/game-0.json.gz':gzip.compress(b'{"index":0,"expansionDecisions":[]}')}
        path = self.root/'evidence.tar.gz'
        with tarfile.open(path,'w:gz') as target:
            for name,raw in members.items():
                member = tarfile.TarInfo(name); member.size = len(raw); target.addfile(member,io.BytesIO(raw))
        files = {name:dict(size=len(raw),sha256=hashlib.sha256(raw).hexdigest()) for name,raw in members.items()}
        put(self.root/'storage.json',dict(kind='joint-state-chunk-storage-1',archive=path.name,
            archiveSha256=audit.sha(path),files=files,memberOrder=list(members)))
        return files

    def test_empty_original_world_survives_archive_hash_resolution(self):
        files = self.archive(); archived = audit.e.Archived(self.root,audit.e.Pins())
        self.assertEqual(archived.hash('collection/game-0.json.gz'),files['collection/game-0.json.gz']['sha256'])
        self.assertEqual(list(archived.games()),[('collection/game-0.json.gz',dict(index=0,expansionDecisions=[]))])

    def test_changed_member_cannot_hide_behind_archive_receipt(self):
        self.archive(); path = self.root/'storage.json'; value = json.loads(path.read_text())
        value['files']['collection/game-0.json.gz']['sha256'] = '0'*64; put(path,value)
        with self.assertRaisesRegex(ValueError,'differs'): audit.e.Archived(self.root,audit.e.Pins())

    def test_auditor_import_does_not_load_torch_or_numpy(self):
        scripts = Path(__file__).resolve().parent
        code = f"import sys;sys.path.insert(0,{str(scripts)!r});import expansion_baseline_audit;assert 'torch' not in sys.modules;assert 'numpy' not in sys.modules"
        subprocess.run([sys.executable,'-E','-s','-B','-c',code],check=True,capture_output=True)


if __name__ == '__main__': unittest.main()
