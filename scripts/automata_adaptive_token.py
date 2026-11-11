"""One finite overlay-aware token experiment; historical P48 protocols remain untouched.

The learner and its validation selection are unchanged. A separate full-game screen
compares learned bidding and a fixed minus-two control against the same E+T games.
Only the learned bidder can enter the two fresh qualification banks.
"""
import argparse
import json
from pathlib import Path
import subprocess

import adaptive_token_arena as arena
import automata_campaign as controller
import automata_evolution as evolution
import automata_token_order as legacy

exp, read, sha, document, require = arena.exp, arena.read, arena.sha, arena.exp.document, arena.require
KIND = 'adaptive-token-overlay-campaign-1'
E = '3b7f677595670972253e8f034fd052178237c1547017c321e33e770e3de176b9'
C = '62dae4a44c84fca9ae39a50fdf93e1001d4b3e1ef55db736b1d7e387559a5a44'
F0 = 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0'
COUNTS = {'training': 256, 'validation': 128, 'screen': 512, 'confirm': 2048, 'heldout': 4096}
STAGES = ('collect', 'fit', 'screen', 'confirm', 'heldout')
CEILINGS = (7200, 1800, 2700, 7200, 14400)
REPLAY_ORIGINS = [0, 85, 170, 255, 256, 299, 342, 383]
SCREEN = dict(confidence=.95, minimumWinMean=.03, requirePositiveWinLower=True,
              requirePositiveRewardLower=True, controlAffectsAdmission=False)


def collection_source(protocol):
    binding = arena.bindings(protocol, 'collect')
    source = exp.arena_source(protocol['baseline'], binding['opponents'],
        protocol['banks']['training']['nativeOffset'], 256, protocol['baseline'], 'SCREENING')
    return dict(source, **legacy.purchase.transfer_source(protocol),
        frozenPurchase=protocol['frozenPurchase'], expansionImitation=binding['expansionImitation'],
        tokenOrder=binding['tokenOrder'], trainingRoots=256, validationRoots=128,
        continuationReplicates=2, continuationProtocol='ARENA_OVERLAYS_1', replayOrigins=REPLAY_ORIGINS)


def arena_source(protocol, model, stage, *, control=False):
    bank = protocol['banks'][stage]
    binding = arena.bindings(protocol, stage, None if control else model, control=control)
    source = exp.arena_source(protocol['baseline'], binding['opponents'], bank['nativeOffset'],
        bank['count'], protocol['baseline'], 'SCREENING' if stage == 'screen' else 'CONFIRMATION')
    return dict(source, **legacy.purchase.transfer_source(protocol),
        frozenPurchase=protocol['frozenPurchase'], expansionImitation=binding['expansionImitation'],
        tokenOrder=binding['tokenOrder'])


def validate_inputs(protocol):
    require(protocol['kind'] == KIND and protocol['schemaVersion'] == 1
            and protocol['workers'] == 10 and protocol['maximumSeconds'] == 21600
            and protocol['maximumAttempts'] == 1 and protocol['screenRule'] == SCREEN
            and protocol['qualificationConfidence'] == .99
            and protocol['continuationProtocol'] == 'ARENA_OVERLAYS_1'
            and protocol['replayOrigins'] == REPLAY_ORIGINS
            and protocol['productionActivation'] is False and protocol['automaticNextCampaign'] is False,
            'Use the fixed ten-worker/six-hour adaptive-token experiment and declared admission rules.')
    require(sha(protocol['baseline']) == legacy.F1 and sha(protocol['expansionModel']) == E
            and protocol['expansionSha256'] == E
            and {key: sha(path) for key, path in protocol['frozenPurchase'].items()}
            == protocol['frozenPurchaseSha256'] == {'baseline': F0, 'policy': C},
            'Every ordinary, expansion and Purchase role must retain exact F1/E/C/F0 bytes.')
    require(protocol['dataVersion'] == 'ah1996-catalog-16'
            and protocol['experimentalRulesTransfer'] == legacy.TRANSFER
            and protocol['sourceRulesVersion'] == legacy.TRANSFER['sourceRulesVersion']
            and protocol['targetRulesVersion'] == legacy.TRANSFER['targetRulesVersion'],
            'Keep the explicit original-byte rules transfer and canonical catalog.')
    require(protocol['trainingRoots'] == 256 and protocol['validationRoots'] == 128
            and protocol['continuationReplicates'] == 2 and protocol['heapMiB'] == 1536
            and protocol['maximumTerminalGames'] == 20864
            and protocol['maximumHistoricalArenaReplays'] == 32
            and protocol['maximumHistoricalOriginReplays'] == 8
            and protocol['maximumHistoricalTerminalBranches'] == 168
            and protocol['controlPromotionAllowed'] is False and protocol['controlFallbackAllowed'] is False,
            'Prospective origins, all paired tails, both screen cells and historical replay caps are fixed.')
    require(sha(Path(protocol['scripts'])/'token_order_metadata.py') == protocol['metadataValidatorSha256']
            and sha(Path(protocol['runtime'])/'lib/training.jar') == protocol['runtimeTrainingJarSha256']
            and exp.distribution_hashes(Path(protocol['runtime'])) == protocol['runtimeFiles'],
            'Frozen overlay runtime and new-protocol metadata verifier bytes changed.')
    config = read(protocol['fitConfig'])
    require({key: value for key, value in config.items() if key != 'seed'} == legacy.FIT,
            'Keep the original32 epochs, three regularizations and deterministic fitter configuration.')
    audit = read(protocol['seedAudit'])
    control = read(protocol['screenControlAudit'])
    require(control['seedAuditSha256'] == sha(protocol['seedAudit'])
            and control['nativeOffset'] == protocol['banks']['screen']['nativeOffset']
            and control['count'] == 512 and control['additionalFreshSeeds'] == 0
            and control['additionalTerminalGames'] == 1024 and control['additionalCanonicalReplays'] == 8
            and control['sharedIncumbentParityRequired'] and control['learnerAdmissionUnchanged']
            and not control['controlPromotionAllowed'] and not control['controlFallbackAllowed'],
            'The cheap control must reuse only the predeclared screen worlds without changing admission.')
    require(audit['allIntersectionsZero'] and set(protocol['banks']) == set(COUNTS),
            'Require all five prospectively audited banks.')
    streams = {}
    for name, count in COUNTS.items():
        bank = protocol['banks'][name]
        require(bank == audit['seedBanks'][name] and bank['count'] == count
                and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], count),
                'A world bank differs from its exact audited list.')
        streams[name] = bank['seeds']
        if name in ('training', 'validation'):
            tails = [(seed << 3) ^ replicate ^ 0x6f20a5319d
                     for seed in bank['seeds'] for replicate in range(2)]
            proposals = [seed ^ 0x2e59c17 for seed in bank['seeds']]
            if name == 'training':
                require(config['seed'] == bank['fittingSeed'] == bank['nativeOffset'] + 1000,
                        'The original fitter RNG must retain its audited seed.')
                proposals.append(config['seed'])
            require(bank['continuationSeeds'] == tails and bank['proposalSeeds'] == proposals,
                    'Every paired full-tail, prospective ordinal and fitter RNG stream is reserved.')
            streams[name + '-tails'], streams[name + '-proposals'] = tails, proposals
    require(protocol['banks']['validation']['nativeOffset']
            == protocol['banks']['training']['nativeOffset'] + 1,
            'Native collection requires adjacent training and validation origin namespaces.')
    exp.validate_banks(streams)
    require(sum(map(len, streams.values())) == 8193, 'The complete prospective stream budget changed.')


def screen_passes(metrics):
    return (metrics['winShareDifference']['count'] == 512
        and metrics['rewardDifference']['count'] == 512
        and metrics['winShareDifference']['mean'] >= SCREEN['minimumWinMean']
        and all(metrics[name]['lower95'] is not None and metrics[name]['lower95'] > 0
                for name in ('winShareDifference', 'rewardDifference')))


def final_passes(protocol, metrics, stage):
    require(stage in ('confirm', 'heldout'), 'Only fresh final banks provide qualification evidence.')
    estimates = evolution.qualification_estimates(protocol, metrics)
    passed = all(estimates[name]['count'] == COUNTS[stage]
                 and estimates[name]['lowerConfidence'] is not None
                 and estimates[name]['lowerConfidence'] > 0
                 for name in ('winShareDifference', 'rewardDifference'))
    return passed, estimates


def stage_receipt(protocol, protocol_path, output, stage, passed, summary, metrics, artifacts):
    report = dict(stage=stage, protocolSha256=sha(protocol_path), summary=summary, metrics=metrics,
        qualificationPassed=stage == 'heldout' and passed, productionActivation=False,
        humanStrengthVerified=False, learningNecessityEstablished=False,
        simpleControlPromotion=False, limitation=protocol['limitation'])
    path = output / (stage + '-report.json')
    document(path, report)
    return exp.stage_result(output, stage, passed, summary, report, [*artifacts, path])


def require_previous(protocol_path, output, stage):
    state = read(output / 'campaign-state.json')
    for previous in STAGES[:STAGES.index(stage)]:
        path = output / 'results' / (previous + '.json')
        receipt = controller.validate_result(path, output)
        require(receipt['status'] == state['stages'][previous]['status'] == 'passed'
                and sha(path) == state['stages'][previous]['resultSha256']
                and receipt['metrics'] == read(output / (previous + '-report.json'))
                and receipt['metrics']['protocolSha256'] == sha(protocol_path),
                'A later bank requires every original preceding receipt from this exact protocol.')


def numerical_child(protocol, output, operation):
    import adaptive_token_environment as environment
    environment.verify_numerical(protocol['numerical'])
    import token_order_policy as fitter
    if operation == 'fit-child':
        config = dict(read(protocol['fitConfig']))
        config['weightDecay'] = tuple(config['weightDecay'])
        fitter.fit(output / 'collection', output / 'fit', fitter.FitConfig(**config))
        return
    model, rows, provenance = fitter.collection(output / 'collection', read(protocol['fitConfig'])['seed'])
    directory = output / 'collection'
    source, native, dataset = [read(directory / name) for name in ('source.json', 'run.json', 'dataset.json')]
    require(source == collection_source(protocol) and native['workers'] == protocol['workers']
            and native['sourceSha256'] == sha(protocol['collectionSource'])
            and provenance['metadataValidatorSha256'] == sha(Path(protocol['scripts']) / 'token_order_metadata.py'),
            'The completed collection must retain exact overlay roles, source and metadata validator.')
    require({path.name for path in directory.glob('origin-*.json')}
            == {f'origin-{index}.json' for index in range(384)}, 'Require all384 prospective origins, including skips.')
    arena.verify_overlay(protocol, source, directory, (native, dataset), collection=True)
    document(output / 'initializer.json', model)
    document(output / 'collection-validation.json', dict(provenance=provenance,
        collected={split: sum(row['split'] == split for row in rows) for split in ('TRAINING', 'VALIDATION')},
        initializerSha256=sha(output / 'initializer.json'), initializedModelDigest=fitter.b.digest(model)))


def run_numerical(protocol, protocol_path, output, operation):
    command = [protocol['numerical']['executable'], '-E', '-s', '-B',
        str(Path(protocol['scripts']) / 'automata_adaptive_token.py'), operation,
        '--protocol', str(protocol_path), '--output', str(output)]
    with (output / (operation + '.log')).open('ab') as log:
        child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                               timeout=exp.remaining_seconds(), check=False)
    require(child.returncode == 0, 'Frozen numerical child failed; no retry or partial model admission.')


def verify_reproduction(protocol, output):
    collection, directory = output / 'collection', output / 'collection-reproduction'
    report, finished = [read(directory / name) for name in ('reproduction.json', 'finished.json')]
    native = read(collection / 'run.json')
    require(report['kind'] == finished['kind'] == 'token-order-reproduction'
            and report['collection'] == str(collection)
            and report['signature'] == finished['signature'] == native['signature']
            and report['continuationProtocol'] == 'ARENA_OVERLAYS_1'
            and report['replayOrigins'] == protocol['replayOrigins']
            and report['allOriginsExact'] and report['newOriginWorlds'] == report['newContinuationWorlds'] == 0
            and report['qualificationPassed'] is False and report['productionActivation'] is False
            and finished['reportSha256'] == sha(directory / 'reproduction.json') and bool(finished['at']),
            'Collection reproduction must bind exact historical overlay worlds with no new seeds.')
    required = {'source.json', 'dataset.json', 'run.json', 'finished.json', 'state.json'}
    required.update(f'origin-{index}.json' for index in range(384))
    require(required <= set(report['files']), 'Reproduction must hash the complete original collection.')
    for name, digest in report['files'].items():
        require(Path(name).name == name and sha(collection / name) == digest,
                'A reproduced collection input changed or escaped its source directory.')
    require([row['index'] for row in report['originReceipts']] == protocol['replayOrigins'],
            'Reproduction cannot choose different origins after observing outcomes.')
    for item in report['originReceipts']:
        name = f'origin-{item["index"]}.json'
        original, reconstructed = read(collection / name), read(directory / name)
        alternatives = original.get('alternatives') or []
        require(original == reconstructed and sha(collection / name) == item['originalSha256']
                == item['reproducedSha256'] == sha(directory / name)
                and item['status'] == original['status']
                and item['alternativeCount'] == len(alternatives)
                and item['continuationOutcomeCount'] == sum(len(row['outcomes']) for row in alternatives),
                'Every declared origin, skip and paired continuation must reproduce exactly.')
    return dict(replayedOrigins=len(report['originReceipts']), reportSha256=sha(directory/'reproduction.json'),
                finishedSha256=sha(directory/'finished.json'), newWorlds=0)


def run_reproduction(protocol, output):
    """Unlike arena, this native entry owns creation of its previously absent output."""
    directory = output / 'collection-reproduction'
    require(not directory.exists(), 'Origin reproduction has one attempt into a new directory.')
    remaining = exp.remaining_seconds()
    command = [protocol['java']['executable'], '-Xmx1536m',
        f'-XX:ActiveProcessorCount={protocol["workers"]}', '-XX:ParallelGCThreads=1',
        '-XX:ConcGCThreads=1', '-cp', str(Path(protocol['runtime']) / 'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt', '--mode', 'token-order-replay',
        '--source', str(output/'collection'), '--output', str(directory),
        '--hours', str(min(2., remaining / 3600))]
    with directory.with_suffix('.log').open('ab') as log:
        child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                               timeout=remaining, check=False)
    require(child.returncode == 0, 'Declared origin reproduction failed; no retry or fresh origins.')


def collect(protocol, protocol_path, output):
    require(read(protocol['collectionSource']) == collection_source(protocol)
            and not (output / 'collection').exists(), 'Collection has one new attempt with its frozen source.')
    exp.native(protocol, 'token-order-collect', protocol['collectionSource'], output / 'collection',
               extra=('--workers', str(protocol['workers'])))
    run_numerical(protocol, protocol_path, output, 'validate-collection')
    run_reproduction(protocol, output)
    reproduced = verify_reproduction(protocol, output)
    check = legacy.collection_check(output)
    passed = all(count >= 2 for count in check['collected'].values())
    artifacts = [path for name in ('collection', 'collection-reproduction')
                 for path in (output / name).rglob('*') if path.is_file()]
    return stage_receipt(protocol, protocol_path, output, 'collect', passed,
        'Original prospective collection and declared historical origins verified; no replenishment.',
        dict(check, reproduction=reproduced), artifacts + [output/'initializer.json', output/'collection-validation.json'])


def fit(protocol, protocol_path, output):
    require(not (output / 'fit').exists(), 'Fitting has one fixed grid and no renewed budget.')
    run_numerical(protocol, protocol_path, output, 'fit-child')
    report, admitted = legacy.verify_fit(protocol, output)
    selection = dict(admitted=admitted, fitReportSha256=sha(output/'fit/fit.json'),
                     controlFallback=False, productionActivation=False)
    if admitted:
        selected = evolution.freeze_copy(output/'fit/candidate.json', output/'selected/token-order.json')
        selection.update(model=str(selected), modelSha256=sha(selected))
        for stage in ('screen', 'confirm', 'heldout'):
            document(output/(stage+'-source.json'), arena_source(protocol, selected, stage))
        document(output/'screen-control-source.json', arena_source(protocol, None, 'screen', control=True))
    document(output/'selection.json', selection)
    artifacts = [path for name in ('fit', 'selected') for path in (output/name).rglob('*') if path.is_file()]
    artifacts += list(output.glob('*-source.json')) + [output/'selection.json']
    return stage_receipt(protocol, protocol_path, output, 'fit', admitted,
        'Fixed validation-MSE selection and original scaling admission complete; no control-based model selection.',
        selection, artifacts)


def selected(protocol, output):
    selection = read(output/'selection.json')
    report, admitted = legacy.verify_fit(protocol, output)
    require(admitted and selection['admitted'] and not selection['controlFallback']
            and selection['fitReportSha256'] == sha(output/'fit/fit.json')
            and selection['modelSha256'] == report['candidateSha256'] == sha(selection['model']),
            'Every screen/final cell must retain the model frozen before screen outcomes.')
    return selection


def evaluate(protocol, output, selection, stage, *, control=False):
    name = stage + ('-control' if control else '')
    source, directory = output/(name+'-source.json'), output/name
    require(read(source) == arena_source(protocol, selection['model'], stage, control=control)
            and not directory.exists(), 'Each full-game cell permits one frozen-source attempt.')
    exp.native(protocol, 'arena', source, directory)
    pairs, metrics = arena.verify_arena(protocol, source, directory, protocol['banks'][stage])
    replays = arena.replays(protocol, source, directory, output/'replays'/name, pairs)
    return pairs, metrics, replays


def evaluate_stage(protocol, protocol_path, output, stage):
    selection = selected(protocol, output)
    if stage != 'screen':
        previous = read(output/('screen-report.json' if stage == 'confirm' else 'confirm-report.json'))['metrics']
        require(previous['modelSha256'] == selection['modelSha256'] and previous['learnerPassed']
                and (screen_passes(previous['rawMetrics']) if stage == 'confirm'
                     else final_passes(protocol, previous['rawMetrics'], 'confirm')[0]),
                'Fresh final worlds require the same learner and exact preceding performance gate.')
    pairs, metrics, replays = evaluate(protocol, output, selection, stage)
    cells = dict(learned=dict(metrics=metrics, replays=replays))
    if stage == 'screen':
        control_pairs, control_metrics, control_replays = evaluate(protocol, output, selection, stage, control=True)
        arena.verify_common_incumbent(pairs, control_pairs)
        cells['ordinary-minus-two'] = dict(metrics=control_metrics, replays=control_replays,
                                           descriptiveOnly=True, promotionAllowed=False)
        passed, estimates = screen_passes(metrics), metrics
    else:
        passed, estimates = final_passes(protocol, metrics, stage)
    payload = dict(modelSha256=selection['modelSha256'], rawMetrics=metrics, estimates=estimates,
        learnerPassed=passed, cells=cells, controlAffectsAdmission=False,
        sharedIncumbentParityWorlds=512 if stage == 'screen' else 0)
    names = [stage, 'screen-control'] if stage == 'screen' else [stage]
    artifacts = [path for name in names for directory in (output/name, output/'replays'/name)
                 for path in directory.rglob('*') if path.is_file()]
    return stage_receipt(protocol, protocol_path, output, stage, passed,
        'All declared cells and replays completed; admission depends only on the precommitted learned-policy gate.',
        payload, artifacts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('stage', 'validate-collection', 'fit-child'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=STAGES)
    options = parser.parse_args()
    protocol, output = read(options.protocol), options.output.resolve()
    if options.command != 'stage':
        numerical_child(protocol, output, options.command)
        return
    require(options.stage is not None, 'The pure supervisor requires an explicit declared stage.')
    import adaptive_token_campaign as campaign
    campaign.verify_execution(protocol, options.protocol, output)
    validate_inputs(protocol)
    require_previous(options.protocol, output, options.stage)
    operation = {'collect': collect, 'fit': fit}.get(options.stage)
    receipt = operation(protocol, options.protocol, output) if operation else evaluate_stage(
        protocol, options.protocol, output, options.stage)
    print(json.dumps(dict(stage=options.stage, status=receipt['status'], summary=receipt['summary'])), flush=True)


if __name__ == '__main__':
    main()
