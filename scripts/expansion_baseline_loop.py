"""Finite matched eight-batch baseline test; existing collectors/learners own all science.

The original campaign clock bounds every child. No scientific subject or stream has a default;
only both completed final arms can enter the separately typed three-role development stage.
"""
import argparse
from dataclasses import dataclass
from itertools import zip_longest
from pathlib import Path
import os
import time

import automata_experiments as exp
import expansion_on_policy_loop as legacy
import expansion_training_evidence as evidence
import joint_state_corpus as storage
import purchase_ecology as execution

read, sha, require, document = exp.read, exp.sha, exp.require, exp.document
ARMS = ('CONTROL', 'TREATMENT')
VERSION = 'expansion-baseline-control-loop-1'
HOSTS = {'PRIMARY_DESKTOP': (10,16), 'MAC_REPLICATION': (6,10)}
UPDATE_RECIPE = dict(learningRate=.0003,epochs=4,batchSize=64,clip=.2,valueCoefficient=0.,
    entropyCoefficient=0.,maxGradientNorm=.5,maxBehaviorKl=.01,threads=1,cardReferenceKlCoefficient=0.)
SCIENCE_SOURCES = ('expansion_mc.py','expansion_on_policy_data.py','expansion_on_policy_loop.py',
    'expansion_state_baseline.py','expansion_previous_baseline.py','expansion_baseline_loop.py',
    'expansion_training_evidence.py','expansion_baseline_audit.py','expansion_baseline_screen.py',
    'purchase_policy.py','joint_trajectory.py','territory_trajectory.py','joint_economy.py')


@dataclass(frozen=True)
class Plan:
    offsets: tuple
    shuffles: tuple
    workers: int
    gates: dict

    @classmethod
    def read(cls, protocol):
        require(protocol['kind'] == VERSION and protocol['rounds'] == 8 and
                protocol['worldsPerBatch'] == 256 and protocol['prefixRounds'] == 2 and
                protocol['arms'] == list(ARMS) and protocol['automaticRetry'] is False and
                protocol['automaticSuccessor'] is False and protocol['intermediateScreens'] == [],
                'Only the fixed two-arm eight-round experiment is supported.')
        offsets, shuffles = tuple(protocol['offsets']), tuple(protocol['shuffleSeeds'])
        require(len(offsets) == len(shuffles) == 8 and
                all(type(x) is int and 0 <= x <= 100000000 for x in offsets) and
                all(type(x) is int and 0 <= x <= 2**31-4 for x in shuffles),
                'Freeze eight native banks and eight four-epoch shuffle origins.')
        worlds = {-1000000000+x+i*8191 for x in offsets for i in range(256)}
        rng = {x+i for x in shuffles for i in range(4)}
        require(len(worlds) == 2048 and len(rng) == 32 and worlds.isdisjoint(rng),
                'Matched arms intentionally share banks, but distinct rounds/epoch streams cannot collide.')
        screen = protocol['screenBank']
        require(set(screen) == {'nativeOffset','count'} and screen['count'] == 1024 and
                type(screen['nativeOffset']) is int and 0 <= screen['nativeOffset'] <= 100000000,
                'Each replica needs exactly one prospective1024-world development bank.')
        development = {-1000000000+screen['nativeOffset']+i*8191 for i in range(1024)}
        require(development.isdisjoint(worlds | rng) and protocol['maximumTerminalExecutions'] == 7198,
                'Fresh development must remain separate from training and preserve the finite execution cap.')
        require(protocol['replica'] in HOSTS and
                (protocol['workers'], protocol['machineCpus']) == HOSTS[protocol['replica']],
                'Use the exact predeclared replica and actual host CPU/worker profile.')
        gates = protocol['prefixGates']
        require(set(gates) == {'minimumBranchingWorldsPerArm','minimumCardOfferWorldsPerArm',
                'maximumSecondsPerRound','maximumBytesPerRound','screenReserveSeconds'} and
                all(evidence.finite(x, 0) and x > 0 for x in gates.values()),
                'Explicit positive exposure/cost/storage/final-screen gates are required.')
        require(all(type(gates[k]) is int and 1 <= gates[k] <= 512 for k in
                    ('minimumBranchingWorldsPerArm','minimumCardOfferWorldsPerArm')) and
                gates['maximumBytesPerRound']*8+protocol['screenMaximumBytes'] <= protocol['maximumDataBytes'] and
                evidence.finite(protocol['updateSeconds'], 1, 900),
                'Operational exposure must be attainable and all retention must fit the original cap.')
        require(protocol['baselineRecipe'] == dict(kind='initial-expansion-hidden-ridge-1',
                hiddenUnits=64, penalty=1., interceptPenalized=False, rowWeight='one-over-world-rows',
                normalization='sum-world-mean-squared-errors', clipPrediction=[0.,1.], fits=7),
                'The fixed previous-batch baseline recipe cannot be tuned.')
        require(all(sha(p) == h for p,h in protocol['files'].items()) and
                sha(protocol['actor']) == protocol['actorSha256'] and
                sha(protocol['arena']) == protocol['arenaSha256'] and
                sha(protocol['prepared']['path']) == protocol['prepared']['sha256'],
                'Every frozen source/model/ecology/preparation/environment subject must match.')
        require(set(protocol['scientificSources']) == set(SCIENCE_SOURCES) and all(
                Path(protocol['scientificSources'][name]['path']).resolve() == Path(__file__).resolve().with_name(name)
                and protocol['scientificSources'][name]['sha256'] == sha(Path(__file__).with_name(name))
                and protocol['files'].get(str(Path(__file__).resolve().with_name(name))) == protocol['scientificSources'][name]['sha256']
                for name in SCIENCE_SOURCES),
                'The actual executing learning/likelihood sources must match the fixed replica science.')
        return cls(offsets, shuffles, protocol['workers'], gates)

    def source(self, protocol, index, actor):
        require(type(index) is int and 1 <= index <= 8, 'Only the eight prescribed batches exist.')
        return dict(kind='expansion-on-policy-collect-1', arena=protocol['arena'],
                    actor=dict(path=str(Path(actor).resolve()), sha256=sha(actor)), games=256,
                    seed=self.offsets[index-1], replayIndices=[(0,85,170,255)[(index-1)%4]])

    def prefix_passed(self, rounds, remaining):
        require(len(rounds) == 2 and [r['batch'] for r in rounds] == [1,2] and
                all(r['complete'] and set(r['arms']) == set(ARMS) for r in rounds),
                'The exact two complete matched rounds own operational admission.')
        cost = sum(r['elapsedSeconds'] for r in rounds)/2
        size = sum(r['retainedBytes'] for r in rounds)/2
        for arm in ARMS:
            rows = [r['arms'][arm] for r in rounds]
            require(all(r['worlds'] == 256 and r['reproductions'] == 1 for r in rows),
                    'Every operational arm must retain all original worlds and its reproduction.')
            if (sum(r['exposure']['branchingWorlds'] for r in rows) < self.gates['minimumBranchingWorldsPerArm']
                or sum(r['exposure']['cardOfferWorlds'] for r in rows) < self.gates['minimumCardOfferWorldsPerArm']
                or not any(legacy.learning_progress(r) for r in rows)):
                return False
        return (cost <= self.gates['maximumSecondsPerRound'] and
                size <= self.gates['maximumBytesPerRound'] and
                cost*6+self.gates['screenReserveSeconds'] <= remaining)


def order(index):
    require(type(index) is int and 1 <= index <= 8, 'Execution order is fixed for batches1–8 only.')
    return ARMS if index % 2 else tuple(reversed(ARMS))


def actor_directory(root, index, arm):
    require(arm in ARMS and 1 <= index <= 8, 'A fixed arm and batch are required.')
    return Path(root)/'batches'/f'batch-{index:02d}'/arm/'numeric/actor'


def expected_actor(protocol, root, index, arm):
    return Path(protocol['actor']) if index == 1 else actor_directory(root,index-1,arm)/'policy.json'


def numeric(protocol, protocol_path, root, index, arm):
    import expansion_mc as mc
    from expansion_on_policy_data import ExpansionCollection
    import purchase_ecology_environment as environment
    import purchase_policy as policy
    plan = Plan.read(protocol)
    environment.verify(protocol['numerical'])
    directory = Path(root)/'batches'/f'batch-{index:02d}'/arm
    source = read(directory/'collection-source.json')
    actor = expected_actor(protocol,root,index,arm)
    require(source == plan.source(protocol,index,actor),
            'The numerical child must use the exact initializer or preceding same-arm actor.')
    collection = ExpansionCollection(directory/'collection',actor)
    require(collection.rows, 'An empty actor batch stops the finite attempt; no invented targets.')
    exp.remaining_seconds()
    config = policy.UpdateConfig(**UPDATE_RECIPE,seed=plan.shuffles[index-1],maxSeconds=protocol['updateSeconds'])
    output = directory/'numeric'
    if arm == 'CONTROL':
        previous = None if index == 1 else actor_directory(root,index-1,arm)/'checkpoint.json'
        report = mc.update(collection,config,output/'actor',checkpoint=previous)
        identity = read(output/'actor/identity.json')
        model = read(output/'actor/policy.json')
        carry = mc.ExpansionMcAdamContinuation().validate(output/'actor/checkpoint.json',
                    report['expansionMcObjective'],model,config)
        facts = dict(acceptedEpochs=report['acceptedEpochs'],
            actorChanged=any(model[k] != collection.model[k] for k in policy.PARAMETERS[:-2]),
            actorAdamSteps=carry['actorAdamSteps'],
            previousActorAdamSteps=identity.get('expansionMcOptimizerContinuation',{}).get('actorAdamSteps',0))
        transaction = None
    else:
        import expansion_state_baseline as state
        import expansion_previous_baseline as treatment
        basis = state.FrozenBasis.load(dict(path=protocol['actor'],sha256=protocol['actorSha256']))
        previous = None if index == 1 else actor_directory(root,index-1,arm).parent
        report = treatment.update(collection,basis,config,output,batch=index,previous=previous,
            replica=protocol['replica'],protocol_subject=dict(path=str(Path(protocol_path).resolve()),
                                                            sha256=sha(protocol_path)))
        facts = {k: report[k] for k in ('acceptedEpochs','actorChanged','actorAdamSteps','previousActorAdamSteps')}
        transaction = dict(path=str(output/'finished.json'),sha256=sha(output/'finished.json'))
    branching, cards = set(), set()
    for row in collection.rows:
        actions = row['observation']['actions']
        if len(actions)>1:
            branching.add(row['originIndex'])
        if any(a['features'].get('kind.buyCard',0)>0 for a in actions):
            cards.add(row['originIndex'])
    document(directory/'numeric-complete.json',dict(kind='expansion-baseline-numeric-1',
        arm=arm,batch=index,replica=protocol['replica'],protocolSha256=sha(protocol_path),
        actorSha256=sha(output/'actor/policy.json'),
        checkpointSha256=sha(output/'actor/checkpoint.json'),identitySha256=sha(output/'actor/identity.json'),
        completedSha256=sha(output/'actor/finished.json'),transaction=transaction,
        data=collection.provenance,exposure=dict(branchingWorlds=len(branching),cardOfferWorlds=len(cards)),
        **facts,qualification=False))


def verify_first_batch_parity(root):
    """Original full trajectories and numerical state, never a partial performance comparison."""
    root = Path(root)
    pins = evidence.Pins()
    archives = [evidence.Archived(root/'batches/batch-01'/arm,pins) for arm in ARMS]
    indices = set()
    for first,second in zip_longest(*(a.games() for a in archives)):
        require(first is not None and second is not None and first == second,
                'Initial matched full trajectories must be exactly equal, including outcomes and commands.')
        require(first[0] not in indices, 'First-batch parity cannot count a world twice.')
        indices.add(first[0])
    require(len(indices) == 256, 'First-batch parity must cover every original matched world.')
    for name in archives[0].metadata:
        if name.startswith('collection/replay-'):
            a,b = (a.metadata[name] for a in archives)
            require(a == b, 'The first original canonical history must match exactly.')
    actors = [actor_directory(root,1,arm) for arm in ARMS]
    require(sha(actors[0]/'policy.json') == sha(actors[1]/'policy.json'),
            'The first constant-baseline actor update must have exactly equal bytes.')
    checkpoint = [pins.read(p/'checkpoint.json')['payload'] for p in actors]
    require(all(checkpoint[0][k] == checkpoint[1][k] for k in ('model','optimizer','epoch','stopReason')),
            'The first constant-baseline actor/Adam/accepted epoch result must be identical.')
    pins.unchanged()
    result = dict(kind='expansion-baseline-first-batch-parity-1',passed=True,worlds=256,
        typedWorldsExactlyEqual=True,canonicalExactlyEqual=True,actorSha256=sha(actors[0]/'policy.json'),
        optimizerDigest=evidence.digest(checkpoint[0]['optimizer']),acceptedEpochs=checkpoint[0]['epoch'],
        artifactPins=pins.values,newGames=0,newFits=0,inferenceCalls=0)
    return result


def first_batch_parity(root):
    result = verify_first_batch_parity(root)
    document(Path(root)/'first-batch-parity.json',result)
    return result


def finish(root, rounds, reason, protocol, protocol_path):
    complete = reason == 'both-eight-complete'
    require((complete and len(rounds) == 8) or
            (reason == 'operational-prefix-gate' and len(rounds) == 2) or
            (reason == 'four-no-progress-batches' and 4 <= len(rounds) <= 8),
            'A shortened or unrecognized completion cannot become a final candidate.')
    result = dict(kind=VERSION,replica=protocol['replica'],protocolSha256=sha(protocol_path),
        complete=complete,stopReason=reason,completedRounds=len(rounds),
        completedBatches={arm:len(rounds) for arm in ARMS},scientificWorlds=len(rounds)*512,
        historicalReproductions=len(rounds)*2,eligibleFinalActors=complete,
        qualification=False,automaticSuccessor=False)
    document(Path(root)/'finished.json',result)
    return result


def learn(protocol, protocol_path, root):
    plan = Plan.read(protocol)
    require(os.cpu_count() == protocol['machineCpus'] and
            os.environ.get('AOR_TRAINING_MACHINE_CPUS',str(protocol['machineCpus'])) == str(protocol['machineCpus']),
            'Actual host/native CPU declarations must match the reviewed protocol.')
    root = Path(root)
    require(not root.exists(), 'One new finite learning root is permitted; no resume/retry.')
    root.mkdir(parents=True)
    rounds, stalls = [], {arm:0 for arm in ARMS}
    for index in range(1,9):
        exp.remaining_seconds()
        started = time.monotonic()
        round_root = root/'batches'/f'batch-{index:02d}'
        round_rows = {}
        for arm in order(index):
            directory = round_root/arm
            directory.mkdir(parents=True)
            actor = expected_actor(protocol,root,index,arm)
            source = directory/'collection-source.json'
            document(source,plan.source(protocol,index,actor))
            execution.run_child(protocol,legacy.native_command(protocol,'expansion-on-policy-collect',source,
                directory/'collection',('--workers',str(plan.workers))),directory/'collection.log')
            manifest, completed = read(directory/'collection/collection.json'), read(directory/'collection/finished.json')
            require(completed['complete'] and completed['games'] == 256 and
                    completed['signature'] == manifest['signature'], 'Only complete original worlds may advance.')
            replay = (0,85,170,255)[(index-1)%4]
            execution.run_child(protocol,legacy.native_command(protocol,'expansion-on-policy-reproduce',source,
                directory/'reproduction',('--collection',str(directory/'collection'),'--index',str(replay))),
                directory/'reproduction.log')
            reproduced = read(directory/'reproduction/replay.json')
            require(reproduced['passed'] and reproduced['completeTypedRecordsEqual'] and
                    reproduced['completeCanonicalEqual'], 'The exact same-factory reproduction must pass.')
            command = [protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
                'numeric','--protocol',str(protocol_path),'--output',str(root),'--index',str(index),'--arm',arm]
            execution.run_child(protocol,command,directory/'numeric.log')
            update = read(directory/'numeric-complete.json')
            require(update['actorSha256'] == sha(directory/'numeric/actor/policy.json'),
                    'Only an exact completely published actor enters the next collection.')
            files = {str(p.relative_to(directory)):dict(sha256=sha(p),size=p.stat().st_size)
                for owner in ('collection','reproduction') for p in (directory/owner).rglob('*') if p.is_file()}
            archived = storage.archive(directory,files,sorted(files))
            receipt = dict(kind='expansion-baseline-batch-1',arm=arm,batch=index,complete=True,
                replica=protocol['replica'],protocolSha256=sha(protocol_path),
                worlds=256,reproductions=1,sourceSha256=sha(source),actorSha256=update['actorSha256'],
                checkpointSha256=update['checkpointSha256'],identitySha256=update['identitySha256'],
                updateSha256=sha(directory/'numeric-complete.json'),archiveSha256=archived['archiveSha256'],
                exposure=update['exposure'],**{k:update[k] for k in
                    ('acceptedEpochs','actorChanged','actorAdamSteps','previousActorAdamSteps')})
            document(directory/'receipt.json',receipt)
            round_rows[arm] = receipt
            stalls[arm] = 0 if legacy.learning_progress(receipt) else stalls[arm]+1
        round_receipt = dict(kind='expansion-baseline-round-1',replica=protocol['replica'],
            protocolSha256=sha(protocol_path),batch=index,complete=True,arms=round_rows,
            executionOrder=list(order(index)),elapsedSeconds=time.monotonic()-started,
            retainedBytes=sum(p.stat().st_size for p in round_root.rglob('*')
                if p.is_file() and p.name!='.DS_Store'))
        document(round_root/'receipt.json',round_receipt)
        rounds.append(round_receipt)
        if index == 1:
            first_batch_parity(root)
        if any(value >= 4 for value in stalls.values()):
            return finish(root,rounds,'four-no-progress-batches',protocol,protocol_path)
        if index == 2:
            remaining = exp.remaining_seconds()
            passed = plan.prefix_passed(rounds,remaining)
            document(root/'operational-prefix.json',dict(kind='expansion-baseline-operational-prefix-1',
                replica=protocol['replica'],protocolSha256=sha(protocol_path),
                completedRounds=2,passed=passed,remainingSeconds=remaining,
                receiptSha256={str(i):sha(root/'batches'/f'batch-{i:02d}'/'receipt.json') for i in (1,2)},
                firstBatchParitySha256=sha(root/'first-batch-parity.json'),qualityInspected=False))
            if not passed:
                return finish(root,rounds,'operational-prefix-gate',protocol,protocol_path)
    return finish(root,rounds,'both-eight-complete',protocol,protocol_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('run','numeric'))
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--index',type=int)
    parser.add_argument('--arm',choices=ARMS)
    args = parser.parse_args()
    protocol = read(args.protocol)
    if args.mode == 'numeric':
        numeric(protocol,args.protocol,args.output,args.index,args.arm)
    else:
        import expansion_baseline_screen as screen
        result = learn(protocol,args.protocol,args.output/'learning')
        if result['complete']:
            screen.run(protocol,args.protocol,args.output)
        else:
            import expansion_baseline_audit as audit
            audit_path=args.output/'learning-audit.json'
            document(audit_path,audit.audit(protocol,args.protocol,args.output/'learning'))
            document(args.output/'finished.json',dict(result,kind='expansion-baseline-replica-1',
                learningAuditSha256=sha(audit_path),screenOpened=False,
                terminalExecutions=result['scientificWorlds']+result['historicalReproductions']))


if __name__ == '__main__':
    main()
