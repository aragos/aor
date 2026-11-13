"""Fixed 32-round matched terminal-MC study; source prerequisite, no embedded scientific inputs."""
import argparse
from dataclasses import dataclass
from enum import Enum
import gzip
import json
import math
import os
from pathlib import Path
import time

import automata_experiments as exp
import purchase_ecology as execution
import triple_mc_matched_storage as storage

read, sha, require, document = exp.read, exp.sha, exp.require, exp.document
VERSION = 'triple-mc-matched-loop-1'
ARMS = ('JOINT','E_ONLY')
PHASES = ('expansion','card','purchase')
GIB = 1024**3
PREFIX = dict(minimumBranchingWorlds=128,maximumSecondsPerRound=600,
    maximumBytesPerRound=384*1024**2,reserveSeconds=900,suffixFactor=1.15,transientReserveBytes=2*GIB)
LIMITS = dict(rounds=32,worldsPerBatch=256,prefixRounds=4,workers=10,machineCpus=16,heapMiB=6144,
    maximumSeconds=21600,auditSeconds=900,maximumDataBytes=16*GIB,maximumRssBytes=12*GIB,
    minimumFreeBytes=16*GIB,updateSeconds=900)


class ResourceProfile(Enum):
    DESKTOP = 'DESKTOP_16_WORKER_10_6H_1'
    MAC = 'MAC_10_WORKER_6_8H_1'

    @property
    def limits(self):
        return LIMITS if self is ResourceProfile.DESKTOP else dict(
            LIMITS,workers=6,machineCpus=10,maximumSeconds=28800)


def resource_profile(protocol):
    name=protocol.get('resourceProfile',ResourceProfile.DESKTOP.value)
    require(name in {p.value for p in ResourceProfile}, 'Use one explicit admitted resource profile.')
    profile=ResourceProfile(name)
    require(all(protocol[k]==v for k,v in profile.limits.items()),
        'The matched study has one fixed recipe and exact resource profile; mixed host limits are invalid.')
    return profile


def subject(path):return dict(path=str(Path(path).resolve()),sha256=sha(path))


def active(arm):
    require(arm in ARMS, 'Use an explicit matched arm, never an inferred phase alias.')
    return PHASES if arm == 'JOINT' else ('expansion',)


def learning_progress(phase):
    return (phase['acceptedEpochs'] > 0 and phase['actorChanged'] is True
        and phase['actorAdamSteps'] > phase['previousActorAdamSteps'])


@dataclass(frozen=True)
class LoopPlan:
    offsets: tuple
    shuffles: tuple
    workers: int
    resource: ResourceProfile

    @property
    def maximum_seconds(self):return self.resource.limits['maximumSeconds']

    @property
    def machine_cpus(self):return self.resource.limits['machineCpus']

    @classmethod
    def read(cls, protocol):
        profile=resource_profile(protocol)
        require(protocol['kind'] == VERSION and protocol['automaticRetry'] is False
            and protocol['strengthScreens'] == []
            and protocol['prefixGates'] == PREFIX, 'The matched study has one fixed recipe, gates and resource profile.')
        offsets, shuffles = tuple(protocol['offsets']), tuple(protocol['shuffleSeeds'])
        require(len(offsets) == len(shuffles) == 32
            and all(type(o) is int and 0 <= o <= 100000000 for o in offsets)
            and all(set(row) == set(PHASES) and all(type(v) is int and 0 <= v <= 2**31-4
                for v in row.values()) for row in shuffles), 'Declare all 32 world banks and three four-epoch shuffle blocks.')
        worlds = {-1000000000+o+i*8191 for o in offsets for i in range(256)}
        rng = {v+epoch for row in shuffles for v in row.values() for epoch in range(4)}
        require(len(worlds) == 8192 and len(rng) == 384 and worlds.isdisjoint(rng),
            'World banks and actual shuffle streams must be distinct; only declared across-arm reuse is allowed.')
        require(all(sha(path) == digest for path,digest in protocol['files'].items()),
            'Frozen scientific, source, audit, environment and admission pins changed.')
        subjects = [protocol[k] for k in ('initialExpansion','fixedBundle','jointSourceTemplate')]
        require(set(protocol['fixedHeads']) == {'card','purchase'}, 'Declare both unchanged G3 head subjects.')
        subjects.extend(protocol['fixedHeads'].values())
        require(all(set(s) == {'path','sha256'} and Path(s['path']).is_absolute()
            and protocol['files'].get(s['path']) == s['sha256'] == sha(s['path']) for s in subjects),
            'Every original model, bundle and template subject must be an explicit exact frozen pin.')
        bundle = read(protocol['fixedBundle']['path']);template = read(protocol['jointSourceTemplate']['path'])
        bundle_parent = Path(protocol['fixedBundle']['path']).parent
        require(all(dict(path=str((bundle_parent/bundle[p]['path']).resolve()),sha256=bundle[p]['sha256'])
                == protocol['fixedHeads'][p] for p in ('card','purchase'))
            and template['bundle'] == protocol['fixedBundle']['path']
            and template['opponents'] == [protocol['fixedBundle']['path']],
            'Both arms must start from and face the same exact raw G3 C/P subjects.')
        return cls(offsets,shuffles,protocol['workers'],profile)

    @staticmethod
    def order(index):
        require(type(index) is int and 1 <= index <= 32, 'Matched round must be 1..32.')
        return ARMS if index%2 else ARMS[::-1]

    active = staticmethod(active)

    @staticmethod
    def replay(index):
        LoopPlan.order(index)
        return (0,85,170,255)[(index-1)%4]

    def source(self, protocol, arm, index, directory):
        active(arm);self.order(index);directory = Path(directory).resolve()
        previous = directory.parent/f'batch-{index-1:02d}'/'numeric'
        bundle = Path(protocol['fixedBundle']['path']) if index == 1 or arm == 'E_ONLY' else previous/'bundle.json'
        actor = Path(protocol['initialExpansion']['path']) if index == 1 else previous/'expansion/policy.json'
        joint = dict(read(protocol['jointSourceTemplate']['path']),bundle=str(bundle),games=256,
            seed=self.offsets[index-1],replayIndices=[self.replay(index)])
        outer = dict(kind='triple-on-policy-collect-1',jointSource=subject(directory/'joint-source.json'),
            expansion=subject(actor)) if (directory/'joint-source.json').exists() else None
        return joint,outer

    def prefix_passed(self, receipts, remaining, round_seconds):
        require(len(receipts) == 8 and {(r['arm'],r['batch']) for r in receipts} ==
            {(a,i) for a in ARMS for i in range(1,5)} and all(r['complete'] is True and
            r['worlds'] == 256 and r['reproductions'] == 1 for r in receipts),
            'The retained operational prefix requires all four complete paired rounds.')
        require(len(round_seconds) == 4 and all(type(t) in (int,float) and math.isfinite(t) and t >= 0
            and t >= sum(r['elapsedSeconds'] for r in receipts if r['batch'] == i)
            for i,t in enumerate(round_seconds,1)), 'Prefix wall time must include both complete batches and cleanup.')
        cost = math.fsum(round_seconds)/4
        size = math.fsum(r['retainedBytes'] for r in receipts)/4
        require(all(math.isfinite(r[k]) and r[k] >= 0 for r in receipts
            for k in ('elapsedSeconds','retainedBytes')), 'Prefix costs must be finite nonnegative measurements.')
        exposure = all(sum(r['phases'][p]['branchingWorlds'] for r in receipts if r['arm'] == a) >= 128
            and any(learning_progress(r['phases'][p]) for r in receipts if r['arm'] == a)
            for a in ARMS for p in active(a))
        return (exposure and cost <= 600 and size <= PREFIX['maximumBytesPerRound']
            and 1.15*cost*28+900 <= remaining and size*32+2*GIB <= 16*GIB)


def batch_path(run, arm, index):
    active(arm);LoopPlan.order(index)
    return Path(run)/'arms'/arm/'batches'/f'batch-{index:02d}'


def native_command(protocol, mode, source, output, extra=()):
    profile=resource_profile(protocol)
    seconds = min(7200,int(exp.remaining_seconds()))
    require(seconds >= 1, 'The original deadline must permit the native child.')
    return [protocol['java']['executable'], '-Xmx6144m',
        '-XX:ActiveProcessorCount='+str(profile.limits['machineCpus']),
        '-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',str(Path(protocol['runtime'])/'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt','--mode',mode,'--source',str(source),'--output',str(output),
        '--seconds',str(seconds),*extra]


def numeric(protocol, directory, arm, index):
    import purchase_ecology_environment as environment
    import purchase_policy as policy
    import triple_mc as mc
    import triple_mc_update as update
    import triple_mc_control as control
    from triple_trajectory import TripleCollection
    plan = LoopPlan.read(protocol);environment.verify(protocol['numerical']);directory = Path(directory).resolve()
    require(directory.name == f'batch-{index:02d}' and directory.parent.name == 'batches'
        and directory.parent.parent.name == arm, 'Numeric admission must retain its explicit original arm/batch path.')
    joint, outer = plan.source(protocol,arm,index,directory)
    require(read(directory/'joint-source.json') == joint and read(directory/'collection-source.json') == outer,
        'Numeric admission requires exactly the fixed source and immediate same-arm predecessor.')
    collection = TripleCollection(directory/'collection');exp.remaining_seconds()
    configs = {p:policy.UpdateConfig(seed=plan.shuffles[index-1][p],valueCoefficient=0,maxSeconds=900) for p in active(arm)}
    previous = None if index == 1 else directory.parent/f'batch-{index-1:02d}'/'numeric'
    if arm == 'JOINT':result = update.update_triple(collection,configs,directory/'numeric',previous=previous,
        guard=exp.remaining_seconds)
    else:result = control.update_control(collection,configs['expansion'],directory/'numeric',
        frozen=protocol['fixedBundle'],previous=previous,guard=exp.remaining_seconds)
    phases = {}
    for p in active(arm):
        root = directory/'numeric'/p;identity = read(root/'identity.json');done = read(root/'finished.json')
        model = read(root/'policy.json')
        carry = mc.TripleMcAdamContinuation().validate(root/'checkpoint.json',done['tripleMcObjective'],model,configs[p])
        phases[p] = dict(acceptedEpochs=done['acceptedEpochs'],
            actorChanged=any(model[k] != collection.models[p][k] for k in policy.PARAMETERS[:-2]),
            actorAdamSteps=carry['actorAdamSteps'],
            previousActorAdamSteps=identity.get('tripleMcOptimizerContinuation',{}).get('actorAdamSteps',0),
            decisions=len(collection.rows[p]),branchingWorlds=len({r['originIndex'] for r in collection.rows[p]
                if len(r['observation']['actions']) > 1}))
    document(directory/'numeric-complete.json',dict(kind='triple-mc-matched-numeric-1',arm=arm,batch=index,
        numericCompletionSha256=sha(directory/'numeric/finished.json'),phases=phases,
        outputBundle=subject(directory/'numeric/triple-bundle.json'),complete=result['complete']))


def first_collection_parity(indices):
    """Original per-world equality; only source-derived signatures may differ between arms."""
    require(set(indices) == set(ARMS), 'Initial parity requires both complete first collections.')
    left,right = (indices[a] for a in ARMS)
    names = [f'collection/game-{i}.json.gz' for i in range(256)]
    # Both capsules use the same declared sorted inventory; no records are dropped.
    def games(index):
        for name,raw in index.iter_members(names=names):yield name,json.loads(gzip.decompress(raw))
    seen = set()
    for (name,a),(other,b) in zip(games(left),games(right),strict=True):
        require(name == other and name not in seen, 'Initial parity game inventories differ.')
        seen.add(name)
        require({k:v for k,v in a.items() if k != 'signature'} == {k:v for k,v in b.items() if k != 'signature'},
            'The two initial G3/E worlds differ beyond their source signature.')
    require(seen == set(names), 'All 256 initial world records must match.')
    sides = []
    for idx in (left,right):
        done = idx.read('collection/finished.json');canonical = idx.read('collection/replay-0.json')
        require(canonical['trajectorySha256'] == idx.files['collection/game-0.json.gz']['sha256']
            == done['gameSha256']['game-0.json.gz'] and canonical['replayVerified'] is True
            and done['canonicalSha256']['replay-0.json'] == idx.files['collection/replay-0.json']['sha256'],
            'Each initial canonical sidecar must bind its own original game bytes.')
        sides.append({k:canonical[k] for k in ('header','entries')})
    require(sides[0] == sides[1], 'Initial full canonical headers and entries differ.')
    return dict(kind='triple-mc-initial-parity-1',passed=True,games=256,gameSignatureOnlyExcluded=True,
        canonicalHeaderEntriesEqual=True,capsuleSha256={a:indices[a].document['archiveSha256'] for a in ARMS})


def train_batch(protocol, protocol_path, plan, output, arm, index):
    started = time.monotonic();directory = batch_path(output,arm,index);directory.mkdir(parents=True)
    joint,_ = plan.source(protocol,arm,index,directory);document(directory/'joint-source.json',joint)
    _,outer = plan.source(protocol,arm,index,directory);source = directory/'collection-source.json';document(source,outer)
    execution.run_child(protocol,native_command(protocol,'triple-on-policy-collect',source,directory/'collection',
        ('--workers','10')),directory/'collection.log')
    finished = read(directory/'collection/finished.json');manifest = read(directory/'collection/collection.json')
    require(finished['complete'] is True and finished['games'] == 256 and finished['signature'] == manifest['signature'],
        'Every original world must complete before the single reproduction or update.')
    execution.run_child(protocol,native_command(protocol,'triple-on-policy-reproduce',source,directory/'reproduction',
        ('--collection',str(directory/'collection'),'--index',str(plan.replay(index)))),directory/'reproduction.log')
    repro = read(directory/'reproduction/replay.json')
    require(repro['passed'] is True and repro['completeTypedRecordsEqual'] is True and repro['completeCanonicalEqual'] is True
        and repro['kind'] == 'triple-on-policy-reproduction-1'
        and repro['index'] == plan.replay(index) and repro['signature'] == manifest['signature']
        and repro['trajectorySha256'] == finished['gameSha256'][f'game-{plan.replay(index)}.json.gz']
        and repro['canonicalSha256'] == finished['canonicalSha256'][f'replay-{plan.replay(index)}.json'],
        'The predeclared original full-record and canonical reproduction must pass before updating.')
    command = [protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),'numeric',
        '--protocol',str(protocol_path),'--output',str(directory),'--arm',arm,'--index',str(index)]
    execution.run_child(protocol,command,directory/'numeric.log')
    update = read(directory/'numeric-complete.json');previous = directory.parent/f'batch-{index-1:02d}'/'numeric'
    require(update['complete'] is True and update['arm'] == arm and update['batch'] == index
        and update['numericCompletionSha256'] == sha(directory/'numeric/finished.json')
        and update['outputBundle'] == subject(directory/'numeric/triple-bundle.json'),
        'Only the checked complete numeric transaction may enter a batch receipt.')
    archive = Path(output)/'arms'/arm/'archives'/f'batch-{index:02d}.tar.gz'
    capsule = storage.archive_batch(directory,archive,arm,index)
    receipt = dict(kind='triple-mc-matched-batch-1',arm=arm,batch=index,complete=True,worlds=256,reproductions=1,
        sourceSha256=sha(source),jointSourceSha256=sha(directory/'joint-source.json'),capsule=capsule,
        numericCompletionSha256=update['numericCompletionSha256'],outputBundle=update['outputBundle'],
        previousCompletion=None if index == 1 else subject(previous/'finished.json'),
        previousCheckpoints={} if index == 1 else {p:subject(previous/p/'checkpoint.json') for p in active(arm)},
        phases=update['phases'],elapsedSeconds=time.monotonic()-started,
        retainedBytes=archive.stat().st_size+Path(capsule['indexPath']).stat().st_size)
    receipt_path = Path(output)/'arms'/arm/'receipts'/f'batch-{index:02d}.json';document(receipt_path,receipt)
    current = storage.BatchIndex(archive);storage.prune(current)
    if index > 1:
        releases = Path(output)/'arms'/arm/'releases';releases.mkdir(exist_ok=True)
        storage.release_numeric(storage.BatchIndex(archive.parent/f'batch-{index-1:02d}.tar.gz'),
            receipt_path,releases/f'batch-{index-1:02d}.json')
    return receipt


def receipt_hashes(output, receipts):
    return {a:{str(r['batch']):sha(Path(output)/'arms'/a/'receipts'/f'batch-{r["batch"]:02d}.json')
        for r in receipts if r['arm'] == a} for a in ARMS}


def terminal(output, receipts, reason):
    counts = {a:sum(r['arm'] == a for r in receipts) for a in ARMS};complete = reason is None
    require(not complete or counts == dict.fromkeys(ARMS,32), 'Only both original final32 transactions are eligible.')
    result = dict(kind=VERSION,complete=complete,completedBatches=counts,scientificWorlds=len(receipts)*256,
        historicalReproductions=len(receipts),eligibleFinalActors=complete,stopReason=reason,
        finalBundles={a:subject(batch_path(output,a,32)/'numeric/triple-bundle.json') for a in ARMS} if complete else {},
        receiptSha256=receipt_hashes(output,receipts),qualification=False,automaticSuccessor=False)
    document(Path(output)/'finished.json',result)
    return result


def run(protocol, protocol_path, output):
    plan = LoopPlan.read(protocol);output = Path(output).resolve()
    require(os.cpu_count() == plan.machine_cpus and os.environ.get('AOR_TRAINING_MACHINE_CPUS',
        str(plan.machine_cpus)) == str(plan.machine_cpus),
        'The declared resource profile must match the actual host and native CPU budget.')
    require(not output.exists(), 'The matched study permits one new output, never resume or retry.')
    require(exp.remaining_seconds() <= protocol['maximumSeconds'],
        'The external controller cannot extend the original fixed study allowance.')
    output.mkdir(parents=True);receipts = [];round_seconds = []
    stalls = {a:{p:0 for p in active(a)} for a in ARMS}
    try:
        for index in range(1,33):
            round_started = time.monotonic()
            for arm in plan.order(index):
                exp.remaining_seconds();receipt = train_batch(protocol,protocol_path,plan,output,arm,index)
                receipts.append(receipt)
                for p in active(arm):stalls[arm][p] = 0 if learning_progress(receipt['phases'][p]) else stalls[arm][p]+1
                if any(n >= 4 for counts in stalls.values() for n in counts.values()):
                    return terminal(output,receipts,'four-no-progress-batches')
            if index == 1:
                indices = {a:storage.BatchIndex(output/'arms'/a/'archives/batch-01.tar.gz') for a in ARMS}
                document(output/'first-collection-parity.json',first_collection_parity(indices))
            round_seconds.append(time.monotonic()-round_started)
            if index == 4:
                remaining = exp.remaining_seconds();passed = plan.prefix_passed(receipts,remaining,round_seconds)
                document(output/'operational-prefix.json',dict(kind='triple-mc-operational-prefix-1',passed=passed,
                    remainingSeconds=remaining,roundSeconds=round_seconds,
                    receiptSha256=receipt_hashes(output,receipts),qualityInspected=False))
                if not passed:return terminal(output,receipts,'operational-prefix-gate')
        return terminal(output,receipts,None)
    except Exception as failure:
        document(output/'failure.json',dict(kind=VERSION,failure=str(failure),completedBatches=len(receipts),
            automaticRetry=False,eligibleFinalActors=False))
        terminal(output,receipts,'exception')
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('run','numeric'));parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--arm',choices=ARMS);parser.add_argument('--index',type=int)
    args = parser.parse_args();protocol = read(args.protocol)
    if args.mode == 'numeric':numeric(protocol,args.output,args.arm,args.index)
    else:run(protocol,args.protocol,args.output)


if __name__ == '__main__':main()
