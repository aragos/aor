"""Exact phase ownership for learned tokens in the frozen F1/E/C/F0 environment."""
from pathlib import Path
import subprocess

import automata_experiments as exp
import automata_policy_search as search
import automata_token_order as legacy
import token_order_fixed_reference as fixed
import token_order_reference_neighbors as neighbors

read, sha, require = exp.read, exp.sha, exp.require
FEATURES = ('tokens', 'progress', 'reach', 'commodity', 'income', 'risk',
            'action.expansion', 'action.pass')
MIXED = ('F1', 'E', 'T', 'E+T')
FIXED = 'FIXED_16_TO_15'


def bindings(protocol, stage, model=None, *, control=False):
    """The explicit residual profile fixes collection tokens; all arena references use T."""
    require(stage in ('collect', 'screen', 'confirm', 'heldout'), 'Unknown token experiment phase.')
    derived = protocol.get('kind') == neighbors.CAMPAIGN_KIND
    require(not derived or (stage != 'collect' and not control),
            'Neighbor evidence uses explicit augmentation; it cannot recollect or add screen controls.')
    roles = ('E+T',)*4 if derived and stage == 'heldout' else (('E+T',) if stage == 'heldout' else MIXED)
    expansion = dict(candidateModel=protocol['expansionModel'], opponentModels=[
        protocol['expansionModel'] if role in ('E', 'E+T') else None for role in roles])
    tokens = dict(opponentSelectors=[FIXED if role in ('T', 'E+T') else 'ORDINARY'
                                    for role in roles])
    if derived and stage == 'heldout':
        tokens['opponentSelectors'] = ['FIXED_16_TO_13','FIXED_16_TO_14',FIXED,'ORDINARY']
    require(not control or stage == 'screen', 'The minus-two control belongs only to development screening.')
    if stage != 'collect':
        require((model is None) == control, 'Use either the frozen learned model or the model-free screen control.')
        expansion['incumbentModel'] = protocol['expansionModel']
        tokens['incumbentSelector'] = FIXED
        if control:
            tokens['candidateSelector'] = 'ORDINARY_MINUS_TWO'
        else:
            tokens['candidateModel'] = str(model)
            if derived:
                tokens['candidateSelector'] = neighbors.SELECTOR
            elif protocol.get('kind') == fixed.CAMPAIGN_KIND:
                tokens['candidateSelector'] = 'MODEL_FIXED_T_RESIDUAL'
    else:
        require(model is None, 'Collection cannot install an evaluated token override.')
        if protocol.get('kind') == fixed.CAMPAIGN_KIND:
            tokens['candidateSelector'] = FIXED
    return dict(candidate=protocol['baseline'], incumbent=protocol['baseline'],
                opponents=[protocol['baseline']] * len(roles),
                expansionImitation=expansion, tokenOrder=tokens)


def verify_overlay(protocol, source, directory, reports, *, collection=False):
    """Verify native role metadata and raw snapshots, separately from ordinary F1 bytes."""
    directory = Path(directory)
    if collection:
        stage, model, control = 'collect', None, False
    else:
        matches = [name for name in ('screen', 'confirm', 'heldout')
                   if source['seed'] == protocol['banks'][name]['nativeOffset']]
        require(len(matches) == 1, 'The arena must belong to one declared world bank.')
        stage, model = matches[0], source.get('tokenOrder', {}).get('candidateModel')
        control = source.get('tokenOrder', {}).get('candidateSelector') == 'ORDINARY_MINUS_TWO'
    require(all(source.get(key) == value for key, value in
                bindings(protocol, stage, model, control=control).items()),
            'Evaluated and rival phase owners differ from the frozen experiment.')
    legacy.verify_environment(protocol, source, directory, reports,
                              'initial.json' if collection else 'candidate.json')
    expansion = dict(version='expansion-imitation-1', decision='expand', featureNames=list(FEATURES))

    def archived(path, snapshot):
        if path is None:
            return None
        path = Path(path).resolve()
        require(sha(path) == protocol['expansionSha256'] and sha(directory / snapshot) == sha(path),
                'An expansion role or its archived model differs from exact E.')
        return dict(path=str(path), sha256=sha(path), snapshot=snapshot)

    for role, value in source['expansionImitation'].items():
        expansion[role] = ([archived(path, f'expansion-imitation-opponent-{index}.json')
                            for index, path in enumerate(value)] if role == 'opponentModels'
                           else archived(value, 'expansion-imitation-' + role.removesuffix('Model') + '.json'))
    tokens = dict(source['tokenOrder'])
    if not collection and not control:
        path = Path(model).resolve()
        snapshot = 'token-order-candidate-model.json'
        require(sha(directory / snapshot) == sha(path), 'The learned token snapshot changed.')
        tokens['candidateModel'] = dict(path=str(path), sha256=sha(path), snapshot=snapshot)
    require(all(report.get('expansionImitation') == expansion and report.get('tokenOrder') == tokens
                for report in reports), 'Native expansion/token metadata changed its explicit role bindings.')
    if collection:
        reference = dict(version='ordinary-token-reference-1', ordinaryModelSnapshot='initial.json',
                         ordinaryModelSha256=sha(protocol['baseline']))
        continuation = protocol.get('continuationProtocol', 'ARENA_OVERLAYS_1')
        allowed = ('ARENA_OVERLAYS_1', 'ARENA_OVERLAYS_EIGHT_TAILS_1')
        if protocol.get('kind') == fixed.CAMPAIGN_KIND:
            reference.update(version=fixed.REFERENCE, selector=FIXED,
                             proposalReference='ordinary-token-reference-1')
            allowed = (fixed.PROTOCOL,)
        require(continuation in allowed
                and source['continuationProtocol'] == continuation
                and source['replayOrigins'] == protocol['replayOrigins']
                and all(report.get('continuationProtocol') == continuation
                        and report.get('replayOrigins') == protocol['replayOrigins']
                        and report.get('tokenReference') == reference for report in reports),
                'Collection must retain its ordinary token head and declared overlay continuation contract.')


def verify_arena(protocol, source_path, directory, bank):
    source_path, directory = Path(source_path), Path(directory)
    source = read(source_path)
    require(source['seed'] == bank['nativeOffset'] and source['pairsPerConfiguration'] == bank['count']
            and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], bank['count']),
            'Arena source must retain its exact prospective bank.')
    exp.verify_arena(directory, source_path)
    native, report, state, finished = [read(directory / name) for name in
        ('run.json', 'evaluation.json', 'state.json', 'finished.json')]
    require(native['workers'] == protocol['workers'] and native['source'] == str(source_path)
            and native['signature'] == state['signature'] and state['phase'] == 'complete'
            and bool(finished['at']) and sha(directory / 'source.json') == sha(source_path),
            'Arena source, worker ownership or completion identity changed.')
    verify_overlay(protocol, source, directory, (native, report))
    require({path.name for path in directory.glob('pair-*.json')}
            == {f'pair-{index}.json' for index in range(bank['count'])}, 'Require exact paired-world coverage.')
    pairs = [read(directory / f'pair-{index}.json') for index in range(bank['count'])]
    for index, pair in enumerate(pairs):
        search.validate_pair(pair, index, bank['seeds'][index], len(source['opponents']))
    metrics = search.comparison(pairs)
    require(report['overall']['pairs'] == bank['count'], 'Native aggregate pair count changed.')
    search.verify_metrics(report['overall'], metrics)
    search.verify_metrics(report['byConfiguration'][protocol['configuration']], metrics)
    return pairs, metrics


def verify_common_incumbent(first, second):
    require(first and len(first) == len(second), 'Screen cells require equal complete common-world banks.')
    for learned, control in zip(first, second):
        require(all(learned[key] == control[key] for key in
                    ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
                and search.same_game(learned['incumbent'], control['incumbent']),
                'The two screen cells must reproduce the exact same E+T incumbent games.')


def replays(protocol, source_path, arena_directory, replay_directory, pairs):
    """Eight historical side replays; one attempt, original chance seeds and frozen Q model."""
    source_path, arena_directory, replay_directory = map(Path,
        (source_path, arena_directory, replay_directory))
    source, receipts = read(source_path), []
    indices = [0, len(pairs) // 3, 2 * len(pairs) // 3, len(pairs) - 1]
    for index in indices:
        for side in ('candidate', 'incumbent'):
            directory = replay_directory / f'{index}-{side}'
            require(not directory.exists(), 'Historical replay permits exactly one attempt.')
            directory.mkdir(parents=True)
            remaining = exp.remaining_seconds()
            pair_path = arena_directory / f'pair-{index}.json'
            command = [protocol['java']['executable'], '-Xmx1536m',
                f'-XX:ActiveProcessorCount={protocol["workers"]}', '-XX:ParallelGCThreads=1',
                '-XX:ConcGCThreads=1', '-cp', str(Path(protocol['runtime']) / 'lib/*'),
                'dev.aragos.aor.training.TrainingMainKt', '--mode', 'strategy-replay',
                '--source', str(source_path), '--output', str(directory), '--hours', str(min(1., remaining/3600)),
                '--workers', '1', '--index', str(index), '--side', side, '--pair', str(pair_path)]
            with directory.with_suffix('.log').open('ab') as log:
                child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                       timeout=remaining, check=False)
            require(child.returncode == 0, 'Canonical adaptive-token replay failed.')
            record, native, state, finished = [read(directory / name) for name in
                ('replay.json', 'run.json', 'state.json', 'finished.json')]
            require(record['replayVerified'] and record['historicalPairVerified']
                    and record['side'] == side.upper() and native['workers'] == 1
                    and native['source'] == str(source_path) and native['recordedPair'] == str(pair_path)
                    and native['signature'] == state['signature'] and state['phase'] == 'complete'
                    and bool(finished['at']) and sha(directory / 'source.json') == sha(source_path)
                    and all(record[key] == pairs[index][key] for key in
                            ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
                    and search.same_game(record['game'], pairs[index][side]),
                    'Canonical replay differs from the frozen historical paired game.')
            verify_overlay(protocol, source, directory, (native, state))
            receipts.append(dict(index=index, side=side, replaySha256=sha(directory/'replay.json'),
                                 finishedSha256=sha(directory/'finished.json')))
    return receipts
