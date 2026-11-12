"""Finite source prerequisite: one retained operational prefix then at most64 expansion batches.

No defaults supply scientific seeds, models or resource gates. A separately reviewed protocol
must bind those subjects before invocation. Existing campaign process groups and original
deadlines own all children; this adapter has no retry, selection, arena or successor path.
"""
import argparse
from dataclasses import dataclass
from pathlib import Path
import math
import os
import time

import automata_experiments as exp
import joint_state_corpus as storage
import purchase_ecology as execution

read, sha, require, document = exp.read, exp.sha, exp.require, exp.document
VERSION = 'expansion-on-policy-loop-1'


@dataclass(frozen=True)
class LoopPlan:
    offsets: tuple
    shuffles: tuple
    workers: int
    prefix: dict

    @classmethod
    def read(cls, protocol):
        require(protocol['kind'] == VERSION and protocol['batches'] == 64 and
            protocol['worldsPerBatch'] == 256 and protocol['prefixBatches'] == 4 and
            protocol['automaticRetry'] is False and protocol['strengthScreens'] == [],
            'The prerequisite fixes4 retained prefix batches within64; no retries or strength stages.')
        offsets = tuple(protocol['offsets']); shuffles = tuple(protocol['shuffleSeeds'])
        require(len(offsets) == len(shuffles) == 64 and all(type(x) is int and 0<=x<=100000000 for x in offsets)
            and all(type(x) is int and 0<=x<=2**31-4 for x in shuffles),
            'Declare all64 native world offsets and finite four-epoch shuffle origins.')
        worlds = {-1000000000+x+i*8191 for x in offsets for i in range(256)}
        rng = {x+i for x in shuffles for i in range(4)}
        require(len(worlds)==16384 and len(rng)==256 and worlds.isdisjoint(rng),
            'Prospective worlds and all actual epoch shuffle streams must be distinct.')
        require((protocol['workers'],protocol['machineCpus']) in ((6,10),(10,16)),
            'Bind the actual Mac10/Desktop16 CPU declaration with its unchanged worker allowance.')
        gates = protocol['prefixGates']
        require(set(gates)=={'minimumBranchingWorlds','minimumCardOfferWorlds','maximumSecondsPerBatch',
            'maximumBytesPerBatch','reserveSeconds'} and
            all(type(x) in (int,float) and math.isfinite(x) and x>0 for x in gates.values()),
            'Freeze positive exposure, throughput, storage and deadline gates before the first world.')
        require(all(type(gates[k]) is int and 1<=gates[k]<=1024
            for k in ('minimumBranchingWorlds','minimumCardOfferWorlds')) and
            gates['maximumBytesPerBatch']*64<=protocol['maximumDataBytes'] and
            type(protocol['updateSeconds']) in (int,float) and 1<=protocol['updateSeconds']<=900,
            'Prefix exposure must be attainable and projected retention must fit the original data cap.')
        require(all(sha(path)==digest for path,digest in protocol['files'].items()),
            'Every explicit scientific source, seed audit, environment and model pin must remain unchanged.')
        require(sha(protocol['actor'])==protocol['actorSha256'] and sha(protocol['arena'])==protocol['arenaSha256'],
            'The initial actor and complete frozen ecology require exact hashes.')
        return cls(offsets,shuffles,protocol['workers'],gates)

    def source(self, protocol, index, actor):
        require(type(index) is int and 1<=index<=64, 'Expansion batch index must belong to the fixed schedule.')
        return dict(kind='expansion-on-policy-collect-1', arena=protocol['arena'],
            actor=dict(path=str(Path(actor).resolve()),sha256=sha(actor)), games=256,
            seed=self.offsets[index-1], replayIndices=[(0,85,170,255)[(index-1)%4]])

    def prefix_passed(self, receipts, remaining):
        """Only factual integrity/exposure/cost; never accept rewards, losses or model comparisons."""
        require(len(receipts)==4 and [r['batch'] for r in receipts]==[1,2,3,4] and
            all(r['complete'] and r['worlds']==256 and r['reproductions']==1 for r in receipts),
            'Operational admission requires the exact first four completed receipts.')
        cost=sum(r['elapsedSeconds'] for r in receipts)/4
        size=sum(r['retainedBytes'] for r in receipts)/4
        return (sum(r['exposure']['branchingWorlds'] for r in receipts)>=self.prefix['minimumBranchingWorlds']
            and sum(r['exposure']['cardOfferWorlds'] for r in receipts)>=self.prefix['minimumCardOfferWorlds']
            and any(learning_progress(r) for r in receipts)
            and cost<=self.prefix['maximumSecondsPerBatch'] and size<=self.prefix['maximumBytesPerBatch']
            and cost*60+self.prefix['reserveSeconds']<=remaining)


def learning_progress(receipt):
    """Accepted actor updates and strictly advancing validated Adam, never policy quality."""
    return (receipt['acceptedEpochs']>0 and receipt['actorChanged'] is True and
        receipt['actorAdamSteps']>receipt['previousActorAdamSteps'])


def native_command(protocol, mode, source, output, extra=()):
    seconds = max(1,min(7200,int(exp.remaining_seconds())))
    return [protocol['java']['executable'], f'-Xmx{protocol["heapMiB"]}m',
        f'-XX:ActiveProcessorCount={protocol["machineCpus"]}', '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1',
        '-cp',str(Path(protocol['runtime'])/'lib/*'),'dev.aragos.aor.training.TrainingMainKt',
        '--mode',mode,'--source',str(source),'--output',str(output),'--seconds',str(seconds),*extra]


def numeric(protocol, directory, index):
    import expansion_mc as mc
    from expansion_on_policy_data import ExpansionCollection
    import purchase_ecology_environment as environment
    import purchase_policy as policy
    plan=LoopPlan.read(protocol);environment.verify(protocol['numerical'])
    source=read(directory/'collection-source.json');actor=Path(source['actor']['path'])
    expected_actor=Path(protocol['actor']) if index==1 else directory.parent/f'batch-{index-1:02d}'/'numeric/policy.json'
    require(actor.resolve()==expected_actor.resolve(),
        'The numerical child must use the exact initializer or immediate preceding completed actor.')
    require(source==plan.source(protocol,index,actor),'The numerical child must use its original batch/source.')
    collection=ExpansionCollection(directory/'collection',actor)
    exp.remaining_seconds()
    # Keep the same configured limit in every identity; the outer original clock remains tighter.
    config=policy.UpdateConfig(seed=plan.shuffles[index-1],valueCoefficient=0,
        maxSeconds=protocol['updateSeconds'])
    previous=None if index==1 else directory.parent/f'batch-{index-1:02d}'/'numeric/checkpoint.json'
    report=mc.update(collection,config,directory/'numeric',checkpoint=previous)
    completed_identity=policy.read_json(directory/'numeric/identity.json')
    prior_steps=completed_identity.get('expansionMcOptimizerContinuation',{}).get('actorAdamSteps',0)
    actual_model=policy.read_json(directory/'numeric/policy.json')
    carry=mc.ExpansionMcAdamContinuation().validate(directory/'numeric/checkpoint.json',
        report['expansionMcObjective'],actual_model,config)
    branching=set();cards=set()
    for row in collection.rows:
        actions=row['observation']['actions']
        if len(actions)>1:branching.add(row['originIndex'])
        if any(a['features'].get('kind.buyCard',0)>0 for a in actions):cards.add(row['originIndex'])
    document(directory/'numeric-complete.json',dict(kind='expansion-on-policy-update-1',
        actorSha256=sha(directory/'numeric/policy.json'),completedSha256=sha(directory/'numeric/finished.json'),
        checkpointSha256=sha(directory/'numeric/checkpoint.json'),data=collection.provenance,
        acceptedEpochs=report['acceptedEpochs'],exposure=dict(branchingWorlds=len(branching),cardOfferWorlds=len(cards)),
        actorChanged=any(actual_model[name]!=collection.model[name] for name in policy.PARAMETERS[:-2]),
        actorAdamSteps=carry['actorAdamSteps'],previousActorAdamSteps=prior_steps,
        frozenEcology=collection.ecology,qualification=False))


def run(protocol, protocol_path, output):
    plan=LoopPlan.read(protocol);output=Path(output)
    require(os.cpu_count()==protocol['machineCpus'] and
        os.environ.get('AOR_TRAINING_MACHINE_CPUS',str(protocol['machineCpus']))==str(protocol['machineCpus']),
        'The actual host CPU count/environment must match the reviewed native resource declaration.')
    require(not output.exists(), 'Expansion on-policy permits one new output and no resume.')
    output.mkdir(parents=True);actor=Path(protocol['actor']);receipts=[];stalled=0
    for index in range(1,65):
        exp.remaining_seconds();started=time.monotonic()
        directory=output/'batches'/f'batch-{index:02d}';directory.mkdir(parents=True)
        source=directory/'collection-source.json';document(source,plan.source(protocol,index,actor))
        execution.run_child(protocol,native_command(protocol,'expansion-on-policy-collect',source,
            directory/'collection',('--workers',str(plan.workers))),directory/'collection.log')
        collection=read(directory/'collection/collection.json');finished=read(directory/'collection/finished.json')
        require(finished['complete'] and finished['games']==256 and
            finished['signature']==collection['signature'], 'Every batch must complete before reproduction or fitting.')
        replay=(0,85,170,255)[(index-1)%4]
        execution.run_child(protocol,native_command(protocol,'expansion-on-policy-reproduce',source,
            directory/'reproduction',('--collection',str(directory/'collection'),'--index',str(replay))),
            directory/'reproduction.log')
        reproduction=read(directory/'reproduction/replay.json')
        require(reproduction['passed'] and reproduction['completeTypedRecordsEqual'] and
            reproduction['completeCanonicalEqual'],'A batch must reproduce before its single numerical update.')
        command=[protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
            'numeric','--protocol',str(protocol_path),'--output',str(directory),'--index',str(index)]
        execution.run_child(protocol,command,directory/'numeric.log')
        update=read(directory/'numeric-complete.json');actor=directory/'numeric/policy.json'
        require(sha(actor)==update['actorSha256'],'Only the completely checked actor may enter the next batch.')
        files={str(p.relative_to(directory)):dict(sha256=sha(p),size=p.stat().st_size)
            for owner in ('collection','reproduction') for p in (directory/owner).rglob('*') if p.is_file()}
        archived=storage.archive(directory,files,sorted(files))
        receipt=dict(kind='expansion-on-policy-batch-1',batch=index,complete=True,worlds=256,reproductions=1,
            sourceSha256=sha(source),actorSha256=sha(actor),updateSha256=sha(directory/'numeric-complete.json'),
            archiveSha256=archived['archiveSha256'],exposure=update['exposure'],
            **{key:update[key] for key in ('acceptedEpochs','actorChanged','actorAdamSteps','previousActorAdamSteps')},
            elapsedSeconds=time.monotonic()-started,
            retainedBytes=sum(p.stat().st_size for p in directory.rglob('*') if p.is_file()))
        document(directory/'receipt.json',receipt);receipts.append(receipt)
        stalled=0 if learning_progress(receipt) else stalled+1
        if stalled==4:
            document(output/'finished.json',dict(kind=VERSION,complete=False,stopReason='four-no-progress-batches',
                completedBatches=index,scientificWorlds=index*256,historicalReproductions=index,eligibleFinalActor=False))
            return
        if index==4:
            remaining=exp.remaining_seconds();passed=plan.prefix_passed(receipts,remaining)
            document(output/'operational-prefix.json',dict(kind='expansion-operational-prefix-1',
                completedBatches=4,passed=passed,remainingSeconds=remaining,
                receiptSha256={str(i):sha(output/'batches'/f'batch-{i:02d}'/'receipt.json') for i in range(1,5)},
                qualityInspected=False))
            if not passed:
                document(output/'finished.json',dict(kind=VERSION,complete=False,stopReason='operational-prefix-gate',
                    completedBatches=4,scientificWorlds=1024,historicalReproductions=4,eligibleFinalActor=False))
                return
    document(output/'finished.json',dict(kind=VERSION,complete=True,completedBatches=64,
        scientificWorlds=16384,historicalReproductions=64,actor=str(actor),actorSha256=sha(actor),
        eligibleFinalActor=True,qualification=False,automaticSuccessor=False))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('run','numeric'));parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--index',type=int)
    args=parser.parse_args();protocol=read(args.protocol)
    if args.mode=='numeric':numeric(protocol,args.output,args.index)
    else:run(protocol,args.protocol,args.output)


if __name__=='__main__':main()
