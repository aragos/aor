"""One fixed62 continuation with actual G3 Adam carry and terminal-only checkpoint screens.

This orchestration reuses unchanged native, predictor, GAE, PPO, atomic pair and archive owners.
The learned rivals stay frozen. No critic fitting, checkpoint fallback or automatic qualification.
"""
import argparse
import json
import os
from pathlib import Path
import platform

import automata_campaign as controller
import automata_evolution as evolution
import automata_experiments as exp
import automata_policy_search as search
import joint_chrono_continuation_data as data
import joint_chrono_evidence as chunks
import joint_chrono_qualification as qualification
import joint_credit_trial as original
import joint_credit_trial_data as original_data
import joint_state_corpus as corpus
import purchase_baseline_pilot as host
import purchase_ecology as execution

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'joint-chrono-continuation-1'


def batch_directory(output, index):
    data.replay_index(index)
    return Path(output)/'batches'/f'batch-{index:02d}'


def previous_checkpoints(protocol, directory, index):
    data.replay_index(index)
    if index == 1:
        require(set(protocol['initialCheckpoints']) == set(data.PHASES),
                'The first batch must carry both original G3 phase checkpoints.')
        return {phase: Path(pin['path']) for phase, pin in protocol['initialCheckpoints'].items()}
    return {phase: directory.parent/f'batch-{index-1:02d}'/'numeric/pair'/phase/'checkpoint.json'
            for phase in data.PHASES}


def next_state(previous, accepted):
    require(previous['stopReason'] is None and previous['completedBatches'] < data.BATCHES
        and set(accepted) == set(data.PHASES)
        and all(type(value) is int and 0 <= value <= 4 for value in accepted.values()),
        'Only a running continuation with both completed phase updates can advance.')
    count = previous['completedBatches'] + 1
    streak = previous['consecutiveZeroAcceptedBatches'] + 1 if sum(accepted.values()) == 0 else 0
    return dict(completedBatches=count,
        acceptedEpochs={phase: previous['acceptedEpochs'][phase] + accepted[phase] for phase in data.PHASES},
        consecutiveZeroAcceptedBatches=streak,
        stopReason='four-consecutive-zero-accepted-batches' if streak == 4 else
            'all64-batches-complete' if count == data.BATCHES else None)


def eligible_checkpoints(state):
    require(state['stopReason'] in ('four-consecutive-zero-accepted-batches', 'all64-batches-complete')
        and type(state['completedBatches']) is int and 4 <= state['completedBatches'] <= data.BATCHES
        and (state['stopReason'] != 'all64-batches-complete' or state['completedBatches'] == data.BATCHES)
        and (state['stopReason'] != 'four-consecutive-zero-accepted-batches'
             or state['consecutiveZeroAcceptedBatches'] == 4),
        'Checkpoint admission requires scientific termination under the original stopping rule.')
    return [index for index in data.CHECKPOINTS if index <= state['completedBatches']]


def validate_inputs(protocol):
    data.validate_inputs(protocol)
    require(protocol['kind'] == KIND and protocol['mode'] in ('learn', 'screens')
        and protocol['maximumDataBytes'] == 8 * exp.GIB
        and protocol['minimumFreeBytes'] == 8 * exp.GIB,
        'Use the finite continuation or final-screen profile with explicit storage/free-space bounds.')
    if protocol['mode'] == 'learn':
        require(protocol['hostRole'] == 'mac' and protocol['workers'] == 6
            and protocol['maximumSeconds'] == 21600,
            'Learning retains one six-hour Mac attempt with six native workers.')
        data.initial_carry_subjects(protocol)
    else:
        require(protocol['hostRole'] == 'desktop' and protocol['workers'] == 10
            and protocol['maximumSeconds'] == 7200 and protocol['replayIndices'] == list(data.REPLAYS),
            'Terminal screens retain one two-hour desktop attempt and eight historical sides per checkpoint.')


def validate_initial_optimizer(protocol):
    """Zero-update admission through the original strict typed continuation and predictor owners."""
    import joint_credit_targets as credit
    import joint_phase_objective as objective
    import joint_state_predictor as predictor
    import joint_trajectory as joint
    import purchase_ecology_environment as environment
    import purchase_policy as policy
    environment.verify(protocol['numerical'])
    predictor.configure()
    frozen = predictor.FrozenJointPredictor.read(protocol['predictor'])
    subjects = original_data.bundle_subjects(protocol['initialBundle'])
    checkpoints = data.initial_carry_subjects(protocol)
    result = {}
    for phase in data.PHASES:
        identity = read(checkpoints[phase].parent/'identity.json')
        expected = identity['jointActorOnlyObjective']
        require(expected['phase'] == phase
            and expected['estimator'] == credit.estimator(credit.Clock.CHRONOLOGICAL)
            and expected['sharedPredictor'] == dict(path=str(frozen.path), sha256=sha(frozen.path),
                                                   subject=frozen.descriptor)
            and identity['pythonVersion'] == protocol['numerical']['pythonVersion']
            and identity['torchVersion'] == protocol['numerical']['torchVersion'],
            'Original Adam carry must retain its actual chronological predictor and Mac numerical identity.')
        model = policy.validate_model(read(subjects[phase]['path']), contract=joint.CONTRACTS[phase])
        result[phase] = objective.JointPhaseAdamContinuation().validate(checkpoints[phase], expected,
            model, policy.UpdateConfig(**data.configs(protocol, 1)[phase]))
    return result


def numeric(protocol, directory, index):
    import joint_credit_targets as credit
    import joint_credit_update as update
    import joint_current_ecology as ecology
    import joint_state_predictor as predictor
    import joint_trajectory as joint
    import purchase_ecology_environment as environment
    import purchase_policy as policy
    environment.verify(protocol['numerical'])
    if index == 1:
        validate_initial_optimizer(protocol)
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
    clock = credit.Clock.CHRONOLOGICAL
    targets = credit.DetachedJointTargets(collection,output/'values.json',
        output/'targets'/(clock.value+'.json'),clock)
    update.update_pair(targets,{phase:policy.UpdateConfig(**config)
        for phase,config in data.configs(protocol,index).items()},output/'pair',
        checkpoints=previous_checkpoints(protocol,directory,index),guard=exp.remaining_seconds)


def verify_pair(protocol, directory, index, bundle):
    pair = directory/'numeric/pair'; report = read(pair/'finished.json'); identity = read(pair/'identity.json')
    expected_previous = previous_checkpoints(protocol,directory,index)
    previous = {phase:None if expected_previous is None else
        dict(path=str(expected_previous[phase]),sha256=sha(expected_previous[phase])) for phase in data.PHASES}
    require(report['kind'] == identity['kind'] == 'joint-credit-two-actor-update-1'
        and report['identitySha256'] == execution.legacy.digest(identity)
        and report['behaviorBundleSha256'] == sha(bundle)
        and report['outputBundleSha256'] == sha(pair/'bundle.json')
        and report['allInputHashesUnchanged'] is True
        and identity['configs'] == data.configs(protocol,index)
        and identity['optimizerFrom'] == previous
        and report['clock']['clock'] == 'chronological'
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
    artifacts = {str(file.relative_to(directory)):sha(file) for file in (directory/'numeric').rglob('*') if file.is_file() and file.name != '.DS_Store'}
    stored = corpus.archive(directory,expected,order)
    receipt = dict(kind='joint-chrono-continuation-batch-1', completed=True, arm='chronological', batch=index,
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


def scientific_files(root):
    return [path for path in root.rglob('*') if path.is_file() and path.name != '.DS_Store']


def train(protocol, path, output):
    require(not (output/'batches').exists() and not (output/'progress.json').exists(),
            'One fresh continuation attempt only; no resume, reset or renewed deadline.')
    # Fail before collecting any worlds if the original numerical environment or carry differs.
    command = [protocol['numerical']['executable'], '-E', '-s', '-B', str(Path(__file__).resolve()),
               'numeric-preflight', '--protocol', str(path), '--output', str(output)]
    execution.run_child(protocol, command, output/'numeric-preflight.log')
    state = dict(completedBatches=0, acceptedEpochs=dict.fromkeys(data.PHASES, 0),
                 consecutiveZeroAcceptedBatches=0, stopReason=None)
    bundle = Path(protocol['initialBundle']); batches, exports = [], {}
    for index in range(1, data.BATCHES + 1):
        bundle, receipt = train_batch(protocol, path, output, index, bundle)
        receipt_path = batch_directory(output, index)/'receipt.json'
        batches.append(dict(index=index, path=str(receipt_path), sha256=sha(receipt_path)))
        state = next_state(state, receipt['acceptedEpochs'])
        if index in data.CHECKPOINTS:
            exports[str(index)] = original.export_terminal(bundle, output/'checkpoints'/f'batch-{index:02d}')
        document(output/'progress.json', dict(state=state, reachedCheckpoints=sorted(map(int, exports)),
            scientificTerminalGames=index * data.WORLDS, historicalReproductions=index), immutable=False)
        print(f'Completed continuation batch {index}/64; no intermediate arena.', flush=True)
        if state['stopReason'] is not None:
            break
    require(sorted(map(int, exports)) == eligible_checkpoints(state),
            'Only prescribed checkpoints reached before scientific termination may be exported.')
    report = dict(kind='joint-chrono-continuation-learning-1', completed=True,
        protocolSha256=sha(path), initialBundleSha256=sha(protocol['initialBundle']),
        initialCheckpoints=protocol['initialCheckpoints'], initialCarryReview=protocol['initialCarryReview'],
        state=state, batches=batches, checkpoints=exports,
        terminalBundle=dict(path=str(bundle), sha256=sha(bundle)),
        scientificTerminalGames=len(batches) * data.WORLDS, historicalReproductions=len(batches),
        totalTerminalExecutions=len(batches) * (data.WORLDS + 1), actualPhaseUpdates=2 * len(batches),
        actualCriticFits=0, intermediateLooks=False, qualificationPassed=False,
        productionActivation=False, selectionDisclosure=data.DISCLOSURE)
    document(output/'learning-report.json', report)
    return exp.stage_result(output, 'learn', True,
        'Continuation reached its predeclared scientific stop; checkpoint strength remains untested.',
        report, [output/'learning-report.json', output/'progress.json', output/'numeric-preflight.log',
                 *scientific_files(output/'batches'), *scientific_files(output/'checkpoints')])


def admitted_checkpoints(protocol):
    """Separate terminal admission; relocated exports change paths only, never actor bytes."""
    admission = protocol['learningAdmission']
    audit, report = data.subject(admission['audit']), data.subject(admission['report'])
    require(audit['passed'] is True and audit['allOwnedProcessesExited'] is True
        and audit['learningReportSha256'] == admission['report']['sha256']
        and report['kind'] == 'joint-chrono-continuation-learning-1' and report['completed'] is True
        and report['initialBundleSha256'] == qualification.CANDIDATE,
        'Screening requires an independently audited complete learning recipe and cleanup.')
    indices = eligible_checkpoints(report['state'])
    require(set(report['checkpoints']) == set(admission['checkpoints']) == set(map(str, indices)),
            'Admit all and only prescribed reached checkpoints, never an arbitrary stopped actor.')
    bundles = {}
    for index in indices:
        key = str(index); pin = admission['checkpoints'][key]
        data.subject(pin)
        bundle = original_data.bundle_subjects(pin['path'])
        exported = report['checkpoints'][key]
        require(pin['sha256'] == exported['sha256']
            and bundle['card'] is not None and bundle['purchase'] is not None,
            'A portable reached checkpoint must preserve the exact complete exported bundle bytes.')
        bundles[index] = pin['path']
    return bundles, admission


def replay_chunk(protocol, source, native, rows, target):
    receipts = []
    for row in rows:
        if row['globalIndex'] not in data.REPLAYS:
            continue
        for side in ('candidate', 'incumbent'):
            directory = target/f'{row["globalIndex"]}-{side}'
            require(not directory.exists(), 'One execution per prescribed historical sampled side.')
            exp.native(protocol, 'strategy-replay', source, directory,
                extra=('--workers', '1', '--index', str(row['index']), '--side', side,
                       '--pair', str(native/f'pair-{row["index"]}.json')))
            receipt = data.verify_replay(protocol, source, directory, row)
            require(receipt['side'] == side, 'Historical replay side differs from its request.')
            receipts.append(receipt)
    return receipts


def screen_checkpoint(protocol, output, index, bundle):
    root = output/'screens'/f'checkpoint-{index:02d}'
    require(not root.exists(), 'Each reached checkpoint permits one complete screen attempt.')
    root.mkdir(parents=True)
    all_rows, receipts, replays = [], [], []
    for chunk in range(2):
        exp.remaining_seconds()
        source = root/f'chunk-{chunk:02d}-source.json'
        document(source, data.arena_source(protocol, bundle, chunk))
        expanded = root/'expanded'/f'chunk-{chunk:02d}'
        native = expanded/'native'
        exp.native(protocol, 'arena', source, native)
        checked = data.verify_chunk(protocol, source, native, bundle, chunk)
        rows = checked['rows']
        historical = replay_chunk(protocol, source, native, rows, expanded/'replays')
        compact = root/'compact'/f'chunk-{chunk:02d}.jsonl'
        roles = root/'compact'/f'roles-{chunk:02d}.json'
        qualification.compact_rows(compact, rows)
        document(roles, checked['roles'])
        target = root/'archives'/f'chunk-{chunk:02d}.tar.gz'
        target.parent.mkdir(parents=True, exist_ok=True)
        stored = chunks.archive(expanded, target)
        receipt_path = root/'receipts'/f'chunk-{chunk:02d}.json'
        document(receipt_path, dict(kind='joint-chrono-continuation-screen-chunk-1',
            checkpoint=index, chunkIndex=chunk, source=str(source), sourceSha256=sha(source),
            compact=str(compact), compactSha256=sha(compact), roles=str(roles), rolesSha256=sha(roles),
            storage=stored, replays=historical, scientificTerminalGames=1024,
            historicalReproductions=len(historical)))
        receipts.append(dict(path=str(receipt_path), sha256=sha(receipt_path)))
        all_rows.extend(rows); replays.extend(historical)
        print(f'Completed checkpoint {index} chunk {chunk + 1}/2; no selection yet.', flush=True)
    require(len(all_rows) == data.PAIRS
        and [row['globalIndex'] for row in all_rows] == list(range(data.PAIRS))
        and [row['seed'] for row in all_rows] == protocol['banks']['screen']['seeds']
        and [(row['globalIndex'], row['side']) for row in replays]
            == [(i, side) for i in data.REPLAYS for side in ('candidate', 'incumbent')],
        'A checkpoint screen requires all1024 common pairs and eight original historical sides.')
    return all_rows, dict(checkpoint=index, bundleSha256=sha(bundle), chunks=receipts, replays=replays)


def final_screens(protocol, path, output):
    require(not (output/'screens').exists() and not (output/'screen-report.json').exists(),
            'One terminal screening attempt only; no retries, top-ups or adaptive checkpoint replacement.')
    bundles, admission = admitted_checkpoints(protocol)
    screens, evidence = {}, {}
    for index, bundle in bundles.items():
        screens[index], evidence[index] = screen_checkpoint(protocol, output, index, bundle)
    if screens:
        data.common_incumbent(screens)
    # No aggregate performance or nominee is computed until every eligible checkpoint completes.
    metrics = {index: search.comparison(rows) for index, rows in screens.items()}
    selected = data.select_checkpoint(metrics)
    estimates = {str(index): evolution.qualification_estimates({'qualificationConfidence': .95}, value)
                 for index, value in metrics.items()}
    report = dict(kind='joint-chrono-continuation-screen-1', completed=True,
        protocolSha256=sha(path), learningAdmission=admission, checkpoints=evidence,
        metrics=estimates, nominee=selected,
        nomineeBundle=None if selected is None else dict(path=bundles[selected], sha256=sha(bundles[selected])),
        scientificTerminalGames=len(bundles) * data.PAIRS * 2,
        historicalReproductions=len(bundles) * 8, totalTerminalExecutions=len(bundles) * 2056,
        actualFits=0, qualificationPassed=False, productionActivation=False,
        noEligibleCheckpoint=not bool(bundles), selectionDisclosure=data.DISCLOSURE)
    document(output/'screen-report.json', report)
    return exp.stage_result(output, 'screens', True,
        'All prescribed development comparisons completed; any nominee still requires fresh qualification.',
        report, [output/'screen-report.json', *scientific_files(output/'screens')])


def campaign_spec(root, protocol, files):
    mode = protocol['mode']
    return dict(schemaVersion=1, kind='automata-campaign', inputs=files,
        limits={key: protocol[key] for key in ('maximumSeconds', 'maximumDataBytes',
                                             'maximumRssBytes', 'minimumFreeBytes')},
        stages=[dict(id=mode, dependsOn=[], maximumSeconds=protocol['maximumSeconds'], maximumAttempts=1,
            result=f'results/{mode}.json', command=[protocol['python']['executable'], '-E', '-s', '-S', '-B',
                str(root/'scripts'/Path(__file__).name), 'stage', '--protocol', str(root/'protocol.json'),
                '--output', '{output}'])])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'numeric-preflight', 'numeric', 'stage'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--index', type=int)
    args = parser.parse_args(); protocol = read(args.protocol)
    validate_inputs(protocol); host.verify_execution(protocol)
    require(platform.platform() == protocol['environment']['platform']
        and platform.machine() == protocol['environment']['machine']
        and os.cpu_count() == protocol['environment']['logicalCpus'], 'The frozen continuation host changed.')
    if args.command == 'preflight':
        require(not args.output.exists(), 'Preflight must precede the one attempt.')
        own = [row for row in controller.processes.sample_group(os.getpgrp()) if row.pid == os.getpid()]
        require(len(own) == 1 and own[0].rss_bytes > 0, 'Actual external process sampling must work.')
        print(json.dumps(dict(passed=True, newGames=0, actualFits=0,
                              externalPsSamplerPassed=True, protocolSha256=sha(args.protocol))))
    elif args.command == 'numeric-preflight':
        require(protocol['mode'] == 'learn', 'Only the learning host admits original Adam carry.')
        print(json.dumps(dict(passed=True, newGames=0, actualFits=0,
                              continuations=validate_initial_optimizer(protocol))))
    elif args.command == 'numeric':
        require(protocol['mode'] == 'learn', 'Screens cannot invoke actor updates.')
        numeric(protocol, args.output, args.index)
    elif protocol['mode'] == 'learn':
        train(protocol, args.protocol, args.output)
    else:
        final_screens(protocol, args.protocol, args.output)


if __name__ == '__main__':
    main()


