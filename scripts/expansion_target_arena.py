"""Whole-game target-search model, role, environment and historical replay verification."""
from pathlib import Path
import re
import subprocess

import automata_experiments as exp
import automata_policy_search as search
import automata_token_order as token
import expansion_target_models as models

read, sha, require = exp.read, exp.sha, exp.require
FEATURES = ('tokens', 'progress', 'reach', 'commodity', 'income', 'risk',
            'action.expansion', 'action.pass')


def verify_overlay(protocol, source, directory, reports):
    roles = source.get('expansionImitation', {})
    candidate = source.get('candidate')
    expansion = roles.get('candidateModel')
    require(candidate is not None and expansion is not None,
            'Target search requires paired ordinary and expansion candidate files.')
    matches = [lineup for lineup in models.LINEUPS
               if all(source.get(key) == value for key,value in
                      models.bindings(protocol,candidate,expansion,lineup).items())]
    expected_lineup = 'homogeneous' if source['seed'] == protocol['worldBanks']['heldout']['nativeOffset'] else 'mixed'
    require(matches == [expected_lineup],'Target search must retain its exact bank-specific rival/evaluated roles.')
    models.verify_models(read(candidate),read(expansion),read(protocol['baseline']),read(protocol['expansionModel']))
    token.verify_environment(protocol,source,directory,reports)
    expected = dict(version='expansion-imitation-1',decision='expand',featureNames=list(FEATURES))
    def metadata(path,snapshot):
        if path is None:
            return None
        resolved = Path(path).resolve()
        require(sha(directory/snapshot) == sha(resolved),'Native target expansion snapshot changed.')
        return dict(path=str(resolved),sha256=sha(resolved),snapshot=snapshot)
    for role,value in roles.items():
        expected[role] = ([metadata(path,f'expansion-imitation-opponent-{index}.json')
                           for index,path in enumerate(value)] if role == 'opponentModels'
                          else metadata(value,'expansion-imitation-'+role.removesuffix('Model')+'.json'))
    require(all(report.get('expansionImitation') == expected and report.get('tokenOrder') == source['tokenOrder']
                for report in reports),'Native target model roles or token selectors changed.')


def verify_common_baseline(first,second):
    require(len(first) == len(second) and first,'Target race requires equal nonempty world coverage.')
    for a,b in zip(first,second):
        require(all(a[key] == b[key] for key in ('index','configuration','seed','seat','opponentIndices'))
                and search.same_game(a['incumbent'],b['incumbent']),
                'Target race incumbent parity failed across candidate models.')


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


def token_receipt(record, source, side):
    """Check selector locality using the actual ordinary head, never truncated alternatives."""
    require(side in ('candidate', 'incumbent'), 'Replay token role requires an explicit side.')
    selector = source['tokenOrder'].get(side + 'Selector')
    require(selector in (None, 'FIXED_16_TO_15'), 'Replay token selector is outside this experiment.')
    changes = []
    for row in record['decisions']:
        if row['snapshot']['decision'] != 'buy-tokens':
            continue
        commands = (row.get('ordinaryTokenBaseline'), row['chosen']['command'])
        require(all(isinstance(command, str) and re.fullmatch(r'buy-tokens (0|-?[1-9][0-9]*)', command)
                    for command in commands), 'Replay must retain canonical actual ordinary token-head choices.')
        before, after = (int(command.split()[1]) for command in commands)
        require(after == before or selector == 'FIXED_16_TO_15' and before == 16 and after == 15,
                'Replay token amount violates its explicit side selector.')
        changes.append(after - before)
    require(changes, 'Canonical replay must retain actual token decisions.')
    return dict(tokenDecisions=len(changes), overrides=sum(value != 0 for value in changes),
                tokenAmountChanges=changes)


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
            receipts.append(dict(index=index, side=side, **token_receipt(record, specification, side), replaySha256=sha(directory/'replay.json'),
                                 finishedSha256=sha(directory/'finished.json')))
    return receipts
