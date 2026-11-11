"""Exact phase-bound expansion-overlay arena and replay verification."""
from pathlib import Path
import subprocess

import automata_experiments as exp
import automata_policy_search as search
import automata_token_order as token

read, sha, require = exp.read, exp.sha, exp.require
FEATURES = ('tokens', 'progress', 'reach', 'commodity', 'income', 'risk',
            'action.expansion', 'action.pass')


def verify_overlay(protocol, source, directory, reports):
    """Ordinary F1 identities stay separate from the candidate-only expansion model."""
    token.verify_environment(protocol, source, directory, reports)
    require('tokenOrder' not in source and all(report.get('tokenOrder') is None for report in reports),
            'Expansion experiment requires unchanged ordinary token decisions.')
    roles = source.get('expansionImitation')
    require(isinstance(roles, dict) and set(roles) == {'candidateModel'},
            'Only the evaluated candidate receives the declared expansion overlay.')
    path = Path(roles['candidateModel']).resolve()
    snapshot = 'expansion-imitation-candidate.json'
    expected = dict(version='expansion-imitation-1', decision='expand', featureNames=list(FEATURES),
                    candidateModel=dict(path=str(path), sha256=sha(path), snapshot=snapshot))
    require(all(report.get('expansionImitation') == expected for report in reports)
            and sha(directory / snapshot) == sha(path),
            'Native expansion role, feature dictionary or raw model snapshot changed.')


def verify_arena(protocol, source_path, directory, bank):
    """Validate a complete predeclared paired bank without opening any new worlds."""
    source_path, directory = Path(source_path), Path(directory)
    specification = read(source_path)
    require(specification['seed'] == bank['nativeOffset']
            and specification['pairsPerConfiguration'] == bank['count']
            and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], bank['count']),
            'Arena source must retain its exact prospective bank.')
    exp.verify_arena(directory, source_path)
    native, report, state, finished = [read(directory / name) for name in
                                     ('run.json', 'evaluation.json', 'state.json', 'finished.json')]
    require(native['workers'] == protocol['workers'] and native['source'] == str(source_path)
            and native['signature'] == state['signature'] and bool(finished['at'])
            and state['phase'] == 'complete' and native['kind'] == state['kind'] == 'strategy-arena'
            and read(directory / 'source.json') == specification
            and sha(directory / 'source.json') == sha(source_path),
            'Native arena identity, original source or completion changed.')
    verify_overlay(protocol, specification, directory, (native, report))
    require({path.name for path in directory.glob('pair-*.json')}
            == {f'pair-{index}.json' for index in range(bank['count'])},
            'Pair files must cover exactly the prospective bank.')
    pairs = [read(directory / f'pair-{index}.json') for index in range(bank['count'])]
    for index, pair in enumerate(pairs):
        search.validate_pair(pair, index, bank['seeds'][index], len(specification['opponents']))
    metrics = search.comparison(pairs)
    require(report['overall']['pairs'] == bank['count'], 'Native aggregate count changed.')
    search.verify_metrics(report['overall'], metrics)
    search.verify_metrics(report['byConfiguration'][protocol['configuration']], metrics)
    return pairs, metrics


def verify_common_baseline(first, second):
    require(len(first) == len(second) and first, 'Common-world arms must have equal nonempty coverage.')
    for a, b in zip(first, second):
        require(all(a[key] == b[key] for key in
                    ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
                and search.same_game(a['incumbent'], b['incumbent']),
                'Common-world ordinary F1 incumbent parity failed.')


def replays(protocol, source_path, arena_directory, replay_directory, pairs, indices):
    """Recreate the declared historic pairs, on both sides; this adds no new bank."""
    source_path, arena_directory, replay_directory = map(Path,
        (source_path, arena_directory, replay_directory))
    specification = read(source_path)
    receipts = []
    for index in indices:
        for side in ('candidate', 'incumbent'):
            directory = replay_directory / f'{index}-{side}'
            pair_path = arena_directory / f'pair-{index}.json'
            require(not directory.exists(), 'Canonical replay has one attempt only.')
            directory.mkdir(parents=True)
            remaining = exp.remaining_seconds()
            command = [protocol['java']['executable'], '-Xmx1536m',
                       f'-XX:ActiveProcessorCount={protocol["workers"]}', '-XX:ParallelGCThreads=1',
                       '-XX:ConcGCThreads=1', '-cp', str(Path(protocol['runtime']) / 'lib/*'),
                       'dev.aragos.aor.training.TrainingMainKt', '--mode', 'strategy-replay',
                       '--source', str(source_path), '--output', str(directory), '--hours',
                       str(min(1., remaining/3600)), '--workers', '1', '--index', str(index),
                       '--side', side, '--pair', str(pair_path)]
            with directory.with_suffix('.log').open('ab') as log:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                           timeout=remaining, check=False)
            require(completed.returncode == 0, 'Canonical expansion replay failed.')
            record, native, state, finished = [read(directory / name) for name in
                ('replay.json', 'run.json', 'state.json', 'finished.json')]
            require(record['replayVerified'] and record['historicalPairVerified']
                    and record['side'] == side.upper()
                    and all(record[key] == pairs[index][key] for key in
                            ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
                    and search.same_game(record['game'], pairs[index][side])
                    and state['phase'] == 'complete'
                    and native['signature'] == state['signature'] and bool(finished['at'])
                    and native['workers'] == 1 and native['source'] == str(source_path)
                    and native['recordedPair'] == str(pair_path)
                    and sha(directory / 'source.json') == sha(source_path),
                    'Canonical replay differs from the exact historical paired game.')
            verify_overlay(protocol, specification, directory, (native, state))
            receipts.append(dict(index=index, side=side, replaySha256=sha(directory/'replay.json'),
                                 finishedSha256=sha(directory/'finished.json')))
    return receipts
