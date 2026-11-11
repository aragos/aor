"""Finite purchase-only adaptation to a frozen stronger opponent population.

Eight on-policy batches carry Adam after a declared fresh start. Checkpoints four
and eight share a descriptive screen; neither can qualify or activate a policy.
"""
import argparse
from pathlib import Path
import shutil
import subprocess

import automata_evolution as evolution
import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import purchase_ecology_data as data
import purchase_storage as storage

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'purchase-ecology-pilot-1'
BATCHES = 8
CHECKPOINTS = [4, 8]
STAGES = ('train', 'screen')


def collection_source(protocol, model, index):
    require(1 <= index <= BATCHES, 'Purchase batch must be in the fixed eight-batch schedule.')
    return dict(schemaVersion=1, configuration=protocol['configuration'], games=256,
        seed=protocol['banks'][f'training-{index}']['nativeOffset'], baseline=protocol['baseline'],
        opponents=protocol['opponents'], policy=str(model), sharedPurchaseBaseline=protocol['prior'],
        opponentPurchasePolicy=protocol['purchasePolicy'], **legacy.transfer_source(protocol),
        continuationProtocol=data.CONTINUATION, replayIndices=data.REPLAY_INDICES,
        **data.bindings(protocol['expansionModel']))


def arena_source(protocol, model):
    bank = protocol['banks']['screen']
    source = exp.arena_source(protocol['baseline'], protocol['opponents'], bank['nativeOffset'],
                              bank['count'], protocol['baseline'], 'SCREENING')
    return dict(source, **legacy.transfer_source(protocol), **data.bindings(protocol['expansionModel'], arena=True),
        frozenPurchase=dict(baseline=protocol['prior'], policy=protocol['purchasePolicy'],
                            candidatePolicy=str(model), incumbentPolicy=protocol['purchasePolicy']))


def validate_inputs(protocol):
    require(protocol['kind'] == KIND and protocol['schemaVersion'] == 1
            and protocol['workers'] == 6 and protocol['maximumSeconds'] == 14400
            and protocol['maximumAttempts'] == 1 and protocol['batches'] == BATCHES
            and protocol['checkpoints'] == CHECKPOINTS and protocol['optimizerInitialization'] == 'fresh-adam'
            and protocol['stallAfterConsecutiveZeroUpdates'] == 3
            and protocol['productionActivation'] is False and protocol['qualificationPassed'] is False
            and protocol['automaticNextCampaign'] is False,
            'Use the fixed eight-batch, six-worker, four-hour descriptive purchase pilot.')
    require(protocol['configuration'] == exp.CONFIGURATION
            and protocol['dataVersion'] == 'ah1996-catalog-16'
            and protocol['experimentalRulesTransfer'] == legacy.RULES_TRANSFER
            and sha(protocol['baseline']) == data.F1 and sha(protocol['prior']) == data.F0
            and sha(protocol['purchasePolicy']) == data.C and sha(protocol['expansionModel']) == data.E
            and protocol['opponents'] == [protocol['baseline']] * 4,
            'The pilot must preserve exact F1/E/C/F0 and explicit indexed rival roles.')
    audit = read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and protocol['banks'] == audit['seedBanks'],
            'Use the complete prospectively audited training, sampling and update streams.')
    expected_names = {f'training-{index}' for index in range(1, BATCHES + 1)} | {'screen'}
    require(set(protocol['banks']) == expected_names, 'The pilot cannot acquire additional game banks.')
    streams = {}
    for name, bank in protocol['banks'].items():
        count = 512 if name == 'screen' else 256
        require(bank['count'] == count and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], count),
                'Purchase world bank differs from the native seed formula.')
        streams[name] = bank['seeds']
        if name != 'screen':
            index = int(name.removeprefix('training-'))
            config = read(Path(protocol['configDirectory']) / f'update-{index}.json')
            require(config == dict(protocol['numerical']['configDefaults'], **legacy.HYPERPARAMETERS,
                                   seed=bank['updateSeed']), 'The PPO recipe or update RNG changed.')
            streams[name + '-update'] = [bank['updateSeed']]
    exp.validate_banks(streams)
    require(protocol['python']['version'] == protocol['numerical']['pythonVersion']
            and protocol['python']['executable'] == protocol['numerical']['resolvedExecutable'],
            'Purchase provenance requires the same interpreter version for supervisor and numerical children.')
    if 'recovery' in protocol:
        prefix = read(protocol['recovery']['prefixReceipt'])
        require(prefix['kind'] == 'purchase-completed-prefix-import-1' and prefix['completedBatches'] == 1
                and prefix['trainingBank'] == protocol['banks']['training-1']
                and prefix['initialPolicySha256'] == data.C
                and prefix['additionalWorlds'] == prefix['additionalUpdates'] == 0
                and prefix['allOriginalUpdateFieldsVerified'] and prefix['canonicalReplaysVerified'] == 4
                and prefix['originalCampaignSha256'] == '2c6f1f9de5f9f9e448674122a3a6cedd48f5a9e4c917d44248dca66a11d05e4a'
                and prefix['originalProtocolSha256'] == '7ba6f893810675431670257b46e24ac8275ff590c3edd59a3558a20eaf420bda',
                'Recovery may import only the completely verified first batch of this original pilot.')
        source = Path(prefix['directory'])
        require({str(path.relative_to(source)) for path in source.rglob('*') if path.is_file()}
                == set(prefix['files']) and all(sha(source/name) == digest for name,digest in prefix['files'].items()),
                'The imported completed batch must retain every original artifact byte.')


def checkpoint(path, model):
    """An empty Adam state is valid after an update rejected before its first epoch."""
    wrapper = read(path)
    payload = wrapper.get('payload')
    require(isinstance(payload, dict) and wrapper.get('sha256') == legacy.digest(payload)
            and payload.get('model') == read(model) and isinstance(payload.get('optimizer'), dict)
            and isinstance(payload['optimizer'].get('state'), dict)
            and payload['optimizer'].get('param_groups')
            and all(group['lr'] == legacy.HYPERPARAMETERS['learningRate']
                    for group in payload['optimizer']['param_groups']),
            'Purchase Adam checkpoint must retain its checksum, actor and original learning rate.')
    return payload


def numerical_update(protocol, index, directory):
    import purchase_policy as learner
    import purchase_ecology_environment as environment
    environment.verify(protocol['numerical'])
    source = read(directory / 'collection-source.json')
    model = Path(source['policy'])
    previous = directory.parent / f'batch-{index - 1}' / 'update/checkpoint.json' if index > 1 else None
    if previous is not None:
        checkpoint(previous, model)
    config = learner.UpdateConfig(**read(Path(protocol['configDirectory']) / f'update-{index}.json'))
    learner.update(model, directory / 'collection', config, directory / 'update', optimizer_from=previous,
                   collection_reader=data.read_collection, entrypoint=Path(__file__))


def verify_update(protocol, index, directory, model, provenance, decisions):
    update = directory / 'update'
    identity, report = read(update / 'identity.json'), read(update / 'finished.json')
    previous = directory.parent / f'batch-{index - 1}' / 'update/checkpoint.json' if index > 1 else None
    config = read(Path(protocol['configDirectory']) / f'update-{index}.json')
    expected = dict(schemaVersion=1, actorVersion=legacy.ACTOR, modelSha256=sha(model), data=provenance,
        config=config, learnerSha256=sha(Path(protocol['scripts']) / 'purchase_policy.py'),
        torchVersion=protocol['numerical']['torchVersion'], pythonVersion=protocol['numerical']['pythonVersion'],
        optimizerFromSha256=sha(previous) if previous is not None else None,
        entrypointSha256=sha(__file__), collectionReaderSource=dict(
            path=str(Path(data.__file__).resolve()), sha256=sha(data.__file__)))
    require({key: value for key, value in identity.items() if key != 'deadlineEpochSeconds'} == expected,
            'Purchase update changed its behavior data, exact recipe, fresh/continued Adam or numerical owner.')
    payload = checkpoint(update / 'checkpoint.json', update / 'policy.json')
    require(payload['identitySha256'] == legacy.digest(identity)
            and payload['deadlineEpochSeconds'] == identity['deadlineEpochSeconds'],
            'Update checkpoint changed its original identity or deadline.')
    expected_report = dict(identitySha256=legacy.digest(identity), inputModelSha256=sha(model),
        outputModelSha256=sha(update / 'policy.json'), games=256, decisions=decisions,
        gameMeanReward=provenance['gameMeanReward'], acceptedEpochs=payload['epoch'],
        history=payload['history'], stopReason=payload['stopReason'] or 'epochs-complete',
        elapsedSeconds=payload['elapsedSeconds'], strengthClaim=False)
    require(all(report.get(key) == value for key, value in expected_report.items())
            and payload['epoch'] == sum(row['accepted'] is True for row in payload['history'])
            and 0 <= payload['epoch'] <= config['epochs'], 'Purchase completion differs from its accepted updates.')
    return report


def run_child(protocol, command, log):
    with Path(log).open('ab') as stream:
        child = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                               timeout=exp.remaining_seconds(), check=False)
    require(child.returncode == 0, 'Purchase child failed; preserve partial evidence without retry.')


def java_command(protocol, mode, source, output):
    hours = 1. if mode == 'strategy-replay' else 2.
    return [protocol['java']['executable'], '-Xmx1536m',
        f'-XX:ActiveProcessorCount={protocol["workers"]}', '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1',
        '-cp', str(Path(protocol['runtime']) / 'lib/*'), 'dev.aragos.aor.training.TrainingMainKt',
        '--mode', mode, '--source', str(source), '--output', str(output),
        '--hours', str(min(hours, exp.remaining_seconds() / 3600))]


def reproduction(protocol, directory):
    target = directory / 'reproduction'
    require(not target.exists(), 'Historical purchase reproduction permits one attempt.')
    run_child(protocol, java_command(protocol, 'purchase-replay', directory / 'collection', target),
              directory / 'reproduction.log')
    # The native completed-bank checker owns trajectory and canonical-history equality.
    report, finished = read(target / 'reproduction.json'), read(target / 'finished.json')
    collection = directory / 'collection'
    signature = read(collection / 'collection.json')['signature']
    require(report['kind'] == finished['kind'] == 'purchase-reproduction'
            and report['replayIndices'] == data.REPLAY_INDICES and report['allGamesExact']
            and report['allCanonicalHistoriesExact'] and report['collection'] == str(collection)
            and report['signature'] == finished['signature'] == signature
            and report['continuationProtocol'] == data.CONTINUATION and report['workers'] == 1
            and report['newGameWorlds'] == 0 and not report['qualificationPassed']
            and not report['productionActivation'] and finished['reportSha256'] == sha(target/'reproduction.json'),
            'Historical replay must reproduce all predeclared trajectories without fresh worlds.')
    required = {'source.json', 'collection.json', 'state.json', 'finished.json',
                *[f'game-{index}.json' for index in range(256)],
                *[f'replay-{index}.json' for index in data.REPLAY_INDICES]}
    require(required <= set(report['files'])
            and all(Path(name).name == name and sha(collection/name) == digest
                    for name, digest in report['files'].items()),
            'Purchase replay must bind the complete unmodified original collection and snapshots.')
    require([row['index'] for row in report['gameReceipts']] == data.REPLAY_INDICES,
            'Purchase replay cannot choose different histories after seeing outcomes.')
    for row in report['gameReceipts']:
        index = row['index']
        for name, key in ((f'game-{index}.json', 'trajectorySha256'), (f'replay-{index}.json', 'replaySha256')):
            require(sha(collection/name) == sha(target/name) == row[key],
                    'Historical trajectory or canonical history differs from its original bytes.')
        require(row['purchaseDecisions'] == len(read(collection/f'game-{index}.json')['decisions']),
                'Replayed purchase decision coverage changed.')
    return report


def result(protocol_path, output, stage, passed, payload):
    report = dict(payload, protocolSha256=sha(protocol_path), stage=stage,
                  qualificationPassed=False, productionActivation=False, humanStrengthVerified=False)
    document(output / (stage + '-report.json'), report)
    directories = ('batches', 'checkpoints') if stage == 'train' else ('screens', 'replays')
    artifacts = [path for name in directories for path in (output/name).rglob('*') if path.is_file()]
    artifacts.append(output / (stage + '-report.json'))
    if stage == 'train':
        artifacts += list(output.glob('screen-*-source.json'))
        if (output/'checkpoints.json').exists():
            artifacts.append(output/'checkpoints.json')
    return exp.stage_result(output, stage, passed, 'Finite purchase adaptation; descriptive evidence only.',
                            report, artifacts)


def train(protocol, protocol_path, output):
    model, updates, stalled = Path(protocol['purchasePolicy']), [], 0
    first = 1
    if 'recovery' in protocol:
        prefix = read(protocol['recovery']['prefixReceipt'])
        directory = output/'batches/batch-1'
        require(not directory.exists(), 'The reviewed prefix is imported once, never resumed or rerun.')
        shutil.copytree(prefix['directory'], directory)
        model = directory/'update/policy.json'
        require(sha(model) == prefix['outputPolicySha256'], 'Imported first-batch actor changed.')
        checkpoint(directory/'update/checkpoint.json', model)
        archive = storage.archive(directory)
        document(directory/'receipt.json', dict(imported=True, prefixReceiptSha256=sha(protocol['recovery']['prefixReceipt']),
            update=prefix['update'], provenance=prefix['provenance'], replay=prefix['reproduction'], storage=archive))
        updates.append(dict(batch=1, policySha256=sha(model), acceptedEpochs=prefix['update']['acceptedEpochs'],
            reproductionSha256=sha(directory/'reproduction/reproduction.json'), imported=True))
        stalled = int(prefix['update']['acceptedEpochs'] == 0)
        first = 2
    for index in range(first, BATCHES + 1):
        directory = output / 'batches' / f'batch-{index}'
        require(not directory.exists(), 'Each on-policy batch permits exactly one fresh attempt.')
        source = directory / 'collection-source.json'
        document(source, collection_source(protocol, model, index))
        exp.native(protocol, 'purchase-collect', source, directory / 'collection',
                   extra=('--workers', str(protocol['workers'])))
        provenance, decisions = data.verify_collection(directory / 'collection')
        replay = reproduction(protocol, directory)
        command = [protocol['numerical']['executable'], '-E', '-s', '-B', str(Path(__file__).resolve()),
            'update-child', '--protocol', str(protocol_path), '--output', str(directory), '--index', str(index)]
        run_child(protocol, command, directory / 'update.log')
        update = verify_update(protocol, index, directory, model, provenance, decisions)
        model = directory / 'update/policy.json'
        updates.append(dict(batch=index, policySha256=sha(model), acceptedEpochs=update['acceptedEpochs'],
                            reproductionSha256=sha(directory/'reproduction/reproduction.json')))
        stalled = stalled + 1 if update['acceptedEpochs'] == 0 else 0
        archive = storage.archive(directory)
        document(directory / 'receipt.json', dict(update=update, provenance=provenance, replay=replay,
                                                  storage=archive))
        print(f'Purchase adaptation batch {index}/8: accepted epochs={update["acceptedEpochs"]}', flush=True)
        if stalled >= protocol['stallAfterConsecutiveZeroUpdates']:
            return result(protocol_path, output, 'train', False, dict(updates=updates, stopReason='three-zero-updates'))
    selected = {}
    for index in CHECKPOINTS:
        model = evolution.freeze_copy(output/f'batches/batch-{index}/update/policy.json',
                                      output/f'checkpoints/policy-{index}.json')
        selected[str(index)] = dict(path=str(model), sha256=sha(model))
        document(output/f'screen-{index}-source.json', arena_source(protocol, model))
    document(output/'checkpoints.json', selected)
    return result(protocol_path, output, 'train', True, dict(updates=updates, checkpoints=selected))


def verify_purchase_roles(source, directory, reports):
    names = {'baseline': 'baseline', 'policy': 'policy', 'candidatePolicy': 'candidate-policy',
             'incumbentPolicy': 'incumbent-policy'}
    expected = {role: dict(path=str(Path(path).resolve()), sha256=sha(path),
                          snapshot=f'frozen-purchase-{names[role]}.json')
                for role, path in source['frozenPurchase'].items()}
    require(all(report.get('frozenPurchase') == expected for report in reports)
            and all(sha(directory/item['snapshot']) == item['sha256'] for item in expected.values()),
            'Historical screen replay changed its exact purchase actor roles or archived bytes.')


def arena_replays(protocol, source_path, pairs, output, *, expected_expansion=data.E,
                  rival_ecology=data.ArenaRivalEcology.MIXED, indices=(0, 170, 341, 511)):
    require(len(indices) == 4 and len(set(indices)) == 4
            and all(type(index) is int and 0 <= index < len(pairs) for index in indices),
            'Declare four distinct historical replay indices within the completed paired bank.')
    source, receipts = read(source_path), []
    for index in indices:
        for side in ('candidate', 'incumbent'):
            target = output/f'{index}-{side}'
            require(not target.exists(), 'Canonical screen replay permits one attempt.')
            target.mkdir(parents=True)
            pair = source_path.parent/'screens'/source_path.stem.removesuffix('-source')/f'pair-{index}.json'
            command = java_command(protocol, 'strategy-replay', source_path, target)
            command += ['--workers', '1', '--index', str(index), '--side', side, '--pair', str(pair)]
            run_child(protocol, command, target.with_suffix('.log'))
            replay, native, state, finished = [read(target/name) for name in
                ('replay.json', 'run.json', 'state.json', 'finished.json')]
            require(replay['replayVerified'] and replay['historicalPairVerified']
                    and replay['side'] == side.upper() and native['source'] == str(source_path)
                    and native['recordedPair'] == str(pair) and native['workers'] == 1
                    and native['signature'] == state['signature'] and state['phase'] == 'complete'
                    and bool(finished['at']) and sha(target/'source.json') == sha(source_path)
                    and all(replay[key] == pairs[index][key] for key in
                            ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
                    and search.same_game(replay['game'], pairs[index][side]),
                    'Canonical purchase-screen replay changed the original historical game.')
            data.verify_overlays(source, target, [native, state], arena=True,
                                 expected_expansion=expected_expansion, rival_ecology=rival_ecology)
            verify_purchase_roles(source, target, [native, state])
            receipts.append(dict(index=index, side=side, replaySha256=sha(target/'replay.json')))
    return receipts


def screen(protocol, protocol_path, output):
    import automata_campaign as controller
    state = read(output/'campaign-state.json')
    prior = controller.validate_result(output/'results/train.json', output)
    require(prior['status'] == state['stages']['train']['status'] == 'passed'
            and sha(output/'results/train.json') == state['stages']['train']['resultSha256'],
            'Descriptive screens require every fixed training batch and its unchanged receipts.')
    checkpoints = read(output/'checkpoints.json')
    require(set(checkpoints) == {'4', '8'}, 'Only the two declared checkpoints may enter screening.')
    cells, common = [], None
    for index in CHECKPOINTS:
        selected = checkpoints[str(index)]
        require(sha(selected['path']) == selected['sha256'], 'Frozen purchase checkpoint changed before screening.')
        source_path, directory = output/f'screen-{index}-source.json', output/f'screens/screen-{index}'
        source = arena_source(protocol, selected['path'])
        require(read(source_path) == source and not directory.exists(),
                'Each screen checkpoint has one attempt against the same frozen incumbent and worlds.')
        exp.native(protocol, 'arena', source_path, directory)
        metrics = legacy.verify_arena(protocol, source_path, directory)
        data.verify_overlays(source, directory, [read(directory/'run.json'), read(directory/'evaluation.json')], arena=True)
        require({path.name for path in directory.glob('pair-*.json')}
                == {f'pair-{number}.json' for number in range(512)}, 'Each screen needs all512 prospective pairs.')
        pairs = [read(directory/f'pair-{number}.json') for number in range(512)]
        if common is not None:
            for first, second in zip(common, pairs):
                require(all(first[key] == second[key] for key in
                            ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
                        and search.same_game(first['incumbent'], second['incumbent']),
                        'Shared checkpoint screens must reproduce the exact same C+E+T incumbent games.')
        common = pairs
        replays = arena_replays(protocol, source_path, pairs, output/f'replays/checkpoint-{index}')
        cells.append(dict(batch=index, modelSha256=selected['sha256'], metrics=metrics, replays=replays))
    return result(protocol_path, output, 'screen', True,
                  dict(cells=cells, sharedIncumbentParityWorlds=512, selectionOnly=True,
                       automaticSelection=False, nextCampaignAllowed=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('stage', 'update-child'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--index', type=int)
    parser.add_argument('--stage', choices=STAGES)
    options = parser.parse_args()
    protocol = read(options.protocol)
    if options.command == 'update-child':
        numerical_update(protocol, options.index, options.output)
        return
    import purchase_ecology_campaign as campaign
    campaign.verify_execution(protocol, options.protocol, options.output)
    validate_inputs(protocol)
    if options.stage == 'train':
        train(protocol, options.protocol, options.output)
    else:
        screen(protocol, options.protocol, options.output)


if __name__ == '__main__':
    main()
