"""One fixed53 learning arm, or three terminal comparisons, over the existing native owners.

Each arm has its own immutable host/clock/Adam lineage and original bounded controller deadline.
Host arithmetic is a declared recipe difference. No intermediate evaluation or actor selection.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil

import automata_campaign as controller
import automata_experiments as exp
import automata_policy_search as search
import joint_credit_trial_data as data
import joint_state_corpus as corpus
import purchase_baseline_pilot as host
import purchase_ecology as execution

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'joint-credit-trial-1'


def batch_directory(output, index):
    data.replay_index(index)
    return Path(output)/'batches'/f'batch-{index:02d}'


def previous_checkpoints(directory, index):
    data.replay_index(index)
    return None if index == 1 else {phase:directory.parent/f'batch-{index-1:02d}'/
        'numeric/pair'/phase/'checkpoint.json' for phase in data.PHASES}


def next_state(previous, accepted):
    require(previous['stopReason'] is None and previous['completedBatches'] < data.BATCHES
        and set(accepted) == set(data.PHASES)
        and all(type(value) is int and 0 <= value <= 4 for value in accepted.values()),
        'Only a running arm with two completed phase updates can advance.')
    count = previous['completedBatches']+1
    streak = previous['consecutiveZeroAcceptedBatches']+1 if sum(accepted.values()) == 0 else 0
    return dict(completedBatches=count,
        acceptedEpochs={phase:previous['acceptedEpochs'][phase]+accepted[phase] for phase in data.PHASES},
        consecutiveZeroAcceptedBatches=streak,
        stopReason='four-consecutive-zero-accepted-batches' if streak == 4 else
                   'all32-batches-complete' if count == data.BATCHES else None)


def validate_inputs(protocol):
    data.validate_inputs(protocol)
    require(protocol['kind'] == KIND and protocol['mode'] in ('arm','screens'),
            'Use the explicit finite arm or terminal-screen stage.')
    if protocol['mode'] == 'arm':
        arm = protocol['arm']
        require(arm in data.ARMS and protocol['maximumSeconds'] == 14400
            and protocol['workers'] == (6 if arm == 'chronological' else 10)
            and protocol['hostRole'] == ('mac' if arm == 'chronological' else 'desktop')
            and protocol['maximumDataBytes'] == (1536*1024**2 if arm == 'chronological' else 8*exp.GIB),
            'The two fixed clocks retain their predeclared host, worker and four-hour bounds.')
    else:
        require(protocol['workers'] == 10 and protocol['maximumSeconds'] == 7200
            and protocol['hostRole'] == 'desktop' and protocol['replayIndices'] == list(data.REPLAYS)
            and protocol['maximumDataBytes'] == 8*exp.GIB,
            'Only the desktop two-hour terminal screen with24 retained-side reproductions is permitted.')


def numeric(protocol, directory, index):
    import joint_credit_targets as credit
    import joint_credit_update as update
    import joint_current_ecology as ecology
    import joint_state_predictor as predictor
    import joint_trajectory as joint
    import purchase_ecology_environment as environment
    import purchase_policy as policy
    environment.verify(protocol['numerical'])
    require(directory.name == f'batch-{index:02d}' and directory.parent.name == 'batches',
            'The numerical update must belong to its exact declared arm batch.')
    source = read(directory/'collection-source.json')
    require(source == data.collection_source(protocol,index,source['bundle']),
            'Numerical data must come from the unchanged sampled source and world bank.')
    collection = joint.JointCollection(directory/'collection',Path(source['bundle']),
                                       ecology=ecology.JointEcology.CURRENT)
    output = directory/'numeric'
    require(not output.exists(), 'One numerical attempt per arm batch; retain every partial failure.')
    output.mkdir()
    frozen = predictor.FrozenJointPredictor.read(protocol['predictor'])
    predictor.predict(collection,frozen,output/'values.json')
    credit.write_targets(collection,output/'values.json',output/'targets')
    clock = credit.Clock(protocol['arm'])
    targets = credit.DetachedJointTargets(collection,output/'values.json',
        output/'targets'/(clock.value+'.json'),clock)
    update.update_pair(targets,{phase:policy.UpdateConfig(**config)
        for phase,config in data.configs(protocol,index).items()},output/'pair',
        checkpoints=previous_checkpoints(directory,index),guard=exp.remaining_seconds)


def verify_pair(protocol, directory, index, bundle):
    pair = directory/'numeric/pair'; report = read(pair/'finished.json'); identity = read(pair/'identity.json')
    expected_previous = previous_checkpoints(directory,index)
    previous = {phase:None if expected_previous is None else
        dict(path=str(expected_previous[phase]),sha256=sha(expected_previous[phase])) for phase in data.PHASES}
    require(report['kind'] == identity['kind'] == 'joint-credit-two-actor-update-1'
        and report['identitySha256'] == execution.legacy.digest(identity)
        and report['behaviorBundleSha256'] == sha(bundle)
        and report['outputBundleSha256'] == sha(pair/'bundle.json')
        and report['allInputHashesUnchanged'] is True
        and identity['configs'] == data.configs(protocol,index)
        and identity['optimizerFrom'] == previous
        and report['clock']['clock'] == protocol['arm']
        and [origin['index'] for origin in report['commonOrigins']] == list(range(data.WORLDS))
        and [origin['gameSeed'] for origin in report['commonOrigins']] == protocol['banks'][f'training-{index}']['seeds']
        and [origin['controlledSeat'] for origin in report['commonOrigins']] == [i%4 for i in range(data.WORLDS)]
        and len(report['gameHashes']) == data.WORLDS,
        'Completed atomic pair must preserve both original heads, all worlds, clock and exact prior carry.')
    for path,digest in identity['inputHashes'].items():
        require(sha(path) == digest, 'A numerical input changed before completion archiving: '+path)
    for path,digest in report['outputHashes'].items():
        require(sha(path) == digest, 'A completed atomic output changed: '+path)
    for phase in data.PHASES:
        phase_identity = read(pair/phase/'identity.json'); finished = read(pair/phase/'finished.json')
        require(phase_identity['config'] == data.configs(protocol,index)[phase]
            and phase_identity['torchVersion'] == protocol['numerical']['torchVersion']
            and phase_identity['pythonVersion'] == protocol['numerical']['pythonVersion']
            and finished['outputModelSha256'] == report['phases'][phase]['policySha256']
            and finished['acceptedEpochs'] == report['phases'][phase]['acceptedEpochs'],
            'Both phase reports must bind their exact matched recipe and numerical host.')
    return report,previous


def train_batch(protocol, path, output, index, bundle):
    import joint_credit_storage as storage
    directory = batch_directory(output,index)
    require(not directory.exists(), 'Every arm/batch is a single attempt; never resume failed work.')
    source = directory/'collection-source.json'
    document(source,data.collection_source(protocol,index,bundle))
    exp.native(protocol,'joint-collect',source,directory/'collection',extra=('--workers',str(protocol['workers'])))
    execution.run_child(protocol,execution.java_command(protocol,'joint-replay',directory/'collection',
        directory/'reproduction'),directory/'reproduction.log')
    expected,order = corpus.completed_files(directory,data.WORLDS,data.replay_index(index))
    command = [protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
        'numeric','--protocol',str(path),'--output',str(directory),'--index',str(index)]
    execution.run_child(protocol,command,directory/'numeric.log')
    report,previous = verify_pair(protocol,directory,index,bundle)
    native = read(directory/'collection/collection.json')
    require(native['seeds'] == protocol['banks'][f'training-{index}']['seeds']
        and native['sourceSha256'] == sha(source), 'The collected native worlds/source changed.')
    artifacts = {str(file.relative_to(directory)):sha(file) for file in (directory/'numeric').rglob('*') if file.is_file()}
    stored = corpus.archive(directory,expected,order)
    receipt = dict(kind='joint-credit-trial-batch-1', completed=True, arm=protocol['arm'], batch=index,
        protocolSha256=sha(path), sourceSha256=sha(source), signature=native['signature'],
        behaviorBundleSha256=sha(bundle), outputBundleSha256=report['outputBundleSha256'],
        previousCheckpoints=previous, acceptedEpochs={phase:report['phases'][phase]['acceptedEpochs']
            for phase in data.PHASES}, scientificTerminalGames=data.WORLDS,historicalReproductions=1,
        storageManifestSha256=sha(directory/corpus.MANIFEST),archiveSha256=stored['archiveSha256'],
        artifacts=artifacts)
    document(directory/'receipt.json',receipt)
    if index > 1:
        storage.archive_numeric(batch_directory(output,index-1),directory/'receipt.json')
    exp.remaining_seconds()
    return directory/'numeric/pair/bundle.json',receipt


def train(protocol,path,output):
    require(not (output/'batches').exists() and not (output/'progress.json').exists(),
            'One fresh arm attempt only; no resume, retry or renewed deadline.')
    state = dict(completedBatches=0,acceptedEpochs=dict.fromkeys(data.PHASES,0),
                 consecutiveZeroAcceptedBatches=0,stopReason=None)
    bundle = Path(protocol['initialBundle']); batches=[]
    for index in range(1,data.BATCHES+1):
        bundle,receipt = train_batch(protocol,path,output,index,bundle)
        batches.append(dict(index=index,path=str(batch_directory(output,index)/'receipt.json'),
                            sha256=sha(batch_directory(output,index)/'receipt.json')))
        state = next_state(state,receipt['acceptedEpochs'])
        document(output/'progress.json',dict(arm=protocol['arm'],state=state,
            scientificTerminalGames=index*data.WORLDS,historicalReproductions=index),immutable=False)
        print(f'Completed {protocol["arm"]} batch {index}/32; no intermediate arena.',flush=True)
        if state['stopReason'] is not None: break
    require(state['stopReason'] is not None, 'Only a recipe-terminal actor may enter final comparisons.')
    portable = export_terminal(bundle,output/'terminal')
    report = dict(kind='joint-credit-trial-arm-1',completed=True,arm=protocol['arm'],state=state,
        protocolSha256=sha(path),batches=batches,terminalBundle=dict(path=str(bundle),sha256=sha(bundle)),
        portableBundle=portable,
        scientificTerminalGames=len(batches)*data.WORLDS,historicalReproductions=len(batches),
        totalTerminalExecutions=len(batches)*(data.WORLDS+1),actualCriticFits=0,
        qualificationPassed=False,productionActivation=False,checkpointSelection=False)
    document(output/'arm-report.json',report)
    artifacts = [output/'arm-report.json',output/'progress.json',*list((output/'terminal').iterdir()),
                 *[file for file in (output/'batches').rglob('*') if file.is_file()]]
    return exp.stage_result(output,'learn',True,'Fixed joint-credit arm completed; no selection.',report,artifacts)


def export_terminal(bundle, output):
    """Only relocate the two finished raw model files; retain original bundle bytes/provenance."""
    original = read(bundle); subject = data.bundle_subjects(bundle)
    require(not output.exists(), 'A completed terminal bundle has one portable export attempt.')
    output.mkdir(parents=True)
    exported = dict(original)
    for phase in data.PHASES:
        require(subject[phase] is not None, 'A learned terminal bundle retains both original actor families.')
        target = output/(phase+'.json'); shutil.copyfile(subject[phase]['path'],target)
        require(sha(target) == subject[phase]['sha256'], 'Terminal export changed raw actor bytes.')
        exported[phase] = dict(path=target.name,sha256=subject[phase]['sha256'])
    document(output/'bundle.json',exported)
    receipt = dict(kind='joint-credit-terminal-export-1',originalBundle=dict(path=str(bundle),sha256=sha(bundle)),
        path=str(output/'bundle.json'),sha256=sha(output/'bundle.json'),
        components={phase:dict(path=phase+'.json',sha256=subject[phase]['sha256']) for phase in data.PHASES},
        changedFields=['card.path','purchase.path'],newFits=0)
    document(output/'export.json',receipt)
    return dict(path=str(output/'bundle.json'),sha256=receipt['sha256'],exportSha256=sha(output/'export.json'))


def arena_replays(protocol,source,pairs,output):
    receipts=[]
    for index in data.REPLAYS:
        for side in ('candidate','incumbent'):
            target = output/f'{index}-{side}'; pair = source.parent/'screens'/source.stem.removesuffix('-source')/f'pair-{index}.json'
            require(not target.exists(), 'Every historical arena side permits one attempt.')
            target.mkdir(parents=True)
            command = execution.java_command(protocol,'strategy-replay',source,target)
            command += ['--workers','1','--index',str(index),'--side',side,'--pair',str(pair)]
            execution.run_child(protocol,command,target.with_suffix('.log'))
            replay,native,state,finished = [read(target/name) for name in ('replay.json','run.json','state.json','finished.json')]
            require(replay['replayVerified'] and replay['historicalPairVerified']
                and replay['side'] == side.upper() and native['source'] == str(source)
                and native['recordedPair'] == str(pair) and native['workers'] == 1
                and native['signature'] == state['signature'] and state['phase'] == 'complete'
                and bool(finished['at']) and sha(target/'source.json') == sha(source)
                and all(replay[key] == pairs[index][key] for key in
                        ('index','configuration','seed','seat','opponentIndices'))
                and search.same_game(replay['game'],pairs[index][side]),
                'Canonical joint replay must exactly reproduce its original sampled side.')
            require(replay.get('jointPolicy') == data.joint_binding(source),
                    'Canonical replay receipt must preserve its complete sampled joint role binding.')
            data.verify_joint_roles(source,target,[native,state])
            receipts.append(dict(index=index,side=side,replaySha256=sha(target/'replay.json')))
    return receipts


def final_screens(protocol,path,output):
    bundles={}
    require(set(protocol['terminalArms']) == set(data.ARMS), 'Both independently audited terminal arms are required.')
    for arm,subject in protocol['terminalArms'].items():
        require(sha(subject['audit']) == subject['auditSha256'] and sha(subject['report']) == subject['reportSha256'],
                'Terminal arm admission receipts changed.')
        audit,report = read(subject['audit']),read(subject['report'])
        require(audit['passed'] is True and audit['allOwnedProcessesExited'] is True
            and audit['armReportSha256'] == subject['reportSha256'] and report['completed'] is True
            and report['arm'] == arm and report['state']['stopReason'] in
                ('four-consecutive-zero-accepted-batches','all32-batches-complete')
            and sha(subject['bundle']) == subject['bundleSha256'] == report['portableBundle']['sha256'],
            'Only each declared recipe-terminal bundle, with complete cleanup, can enter the screen.')
        bundles[arm] = subject['bundle']
    pairs_by_name,cells={},[]
    for name in data.COMPARISONS:
        source = output/(name+'-source.json'); directory = output/'screens'/name
        require(not source.exists() and not directory.exists(), 'Each final comparison permits one attempt.')
        document(source,data.arena_source(protocol,bundles,name))
        exp.native(protocol,'arena',source,directory)
        pairs,metrics = data.verify_arena(protocol,source,directory); pairs_by_name[name]=pairs
        replays=arena_replays(protocol,source,pairs,output/'replays'/name)
        cells.append(dict(comparison=name,metrics=metrics,replays=replays,sourceSha256=sha(source)))
    data.common_games(pairs_by_name)
    report=dict(kind='joint-credit-trial-screen-1',completed=True,protocolSha256=sha(path),comparisons=cells,
        primary=cells[-1]['metrics'],scientificTerminalGames=12288,historicalReproductions=24,
        totalTerminalExecutions=12312,qualificationPassed=False,productionActivation=False,selectionPerformed=False,
        interpretation='Two complete clock/host recipes; no exact causal isolation from hardware or trace horizon.')
    document(output/'screen-report.json',report)
    artifacts=[output/'screen-report.json',*output.glob('*-source.json')]
    artifacts.extend(file for root in ('screens','replays') for file in (output/root).rglob('*') if file.is_file())
    return exp.stage_result(output,'screen',True,'Three final-only joint contrasts completed; no qualification.',report,artifacts)


def campaign_spec(root,protocol,files):
    mode=protocol['mode'];stage='learn' if mode=='arm' else 'screen'
    return dict(schemaVersion=1,kind='automata-campaign',inputs=files,
        limits=dict(maximumSeconds=protocol['maximumSeconds'],maximumDataBytes=protocol['maximumDataBytes'],
                    maximumRssBytes=6*exp.GIB,minimumFreeBytes=8*exp.GIB),
        stages=[dict(id=stage,dependsOn=[],maximumSeconds=protocol['maximumSeconds'],maximumAttempts=1,
            result=f'results/{stage}.json',command=[protocol['python']['executable'],'-E','-s','-S','-B',
                str(root/'scripts/joint_credit_trial.py'),'stage','--protocol',str(root/'protocol.json'),
                '--output','{output}'])])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage','numeric'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--index',type=int);args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);host.verify_execution(protocol)
    require(platform.platform()==protocol['environment']['platform']
        and platform.machine()==protocol['environment']['machine']
        and os.cpu_count()==protocol['environment']['logicalCpus'], 'The frozen trial host changed.')
    if args.command=='preflight':
        require(not args.output.exists(),'Preflight must precede the one attempt.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'Actual external process sampling must work.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    elif args.command=='numeric':numeric(protocol,args.output,args.index)
    elif protocol['mode']=='arm':train(protocol,args.protocol,args.output)
    else:final_screens(protocol,args.protocol,args.output)


if __name__=='__main__':main()
