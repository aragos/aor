"""Pure, bounded reaggregation of complete original robust-token artifacts; no execution."""
import argparse
import hashlib
import json
from pathlib import Path
import time

import robust_token_campaign as c
from expansion_training_evidence import terminal_reward


def native_chunk(source, native, *, source_path=None):
    """Admit original native bytes in completion order; no simulation or statistical selection."""
    native = Path(native)
    count, start = source['count'], source['globalStart']
    c.require(type(count) is int and 1 <= count <= 256 and type(start) is int and start >= 0 and
        start+count <= len(source['seeds']),
        'A native shard requires its bounded original index interval.')
    roles = ('CANDIDATE',) if source.get('candidateOnly', False) else ('CANDIDATE', 'CONTROL')
    owned = [index for index in source['replayIndices'] if start <= index < start+count]
    completed, prepared = c.read(native/'finished.json'), c.read(native/'prepared.json')
    if source_path is not None:
        c.require(c.read(source_path) == source, 'The original source subject differs from the declared native input.')
    expected_source = c.sha(source_path) if source_path is not None else hashlib.sha256(c.canonical(source)).hexdigest()
    c.require(completed['sourceSha256'] == prepared['sourceSha256'] == expected_source and
        completed['scienceSha256'] == prepared['scienceSha256'] and prepared['passed'] is True and
        prepared['newGames'] == prepared['modelForwards'] == 0,
        'Original native admission/completion must bind the exact source and zero-game preparation.')
    identity = prepared['scienceIdentity']
    c.require(hashlib.sha256(c.canonical(identity)[:-1]).hexdigest() == prepared['scienceSha256'] and
        identity['protocolSha256'] == source['protocolSha256'] and
        identity['seeds'] == source['seeds'] and identity['replayIndices'] == source['replayIndices'] and
        identity['opponentStyle'] == source['opponentStyle'] and identity['selector'] == source['selector'] and
        identity['inferenceAssetSha256'] == source['inferenceAsset']['sha256'] and
        identity['nominationSha256'] == source['nomination']['sha256'] and identity['roles'] == list(roles),
        'Original native scientific identity must reproduce and retain the complete prospectively declared world bank.')
    for source_key, identity_key in (('candidateBundle', 'candidateBundleSha256'),
            ('controlBundle', 'controlAndRivalsBundleSha256')):
        if source_key in source:
            c.require(identity[identity_key] == source[source_key]['sha256'],
                'An explicitly declared model must remain bound to the native scientific identity.')
    if 'controlSelector' in source and source['controlSelector'] is not None:
        c.require(identity['controlSelector'] == source['controlSelector'],
            'The native comparator token sampler differs from its exact frozen input.')
    if 'expansionDenial' in source:
        c.require(identity['expansionDenial'] == source['expansionDenial'],
            'The native intervention must retain the exact declared leader-denial setting.')
    names = {'prepared.json', 'worlds.jsonl'} | ({'reproductions.jsonl'} if owned else set())
    c.require(set(completed['files']) == names,
        'Native completion must retain exactly its original prepared, outcome and prescribed replay files.')
    pins = {}
    for name, digest in completed['files'].items():
        path = native/name
        c.require(c.sha(path) == digest, 'An original native output changed after completion.')
        pins[str(path)] = digest
    original = [json.loads(line) for line in (native/'worlds.jsonl').read_text().splitlines()]
    c.validate_chunk_indices(original, start, count)
    styles = ('QUALIFIED', 'UNDERCUT', 'OVERBID', 'MIXED')
    for world in original:
        index, seat = world['index'], world['seat']
        style = styles[(index//4+seat) % 4] if source['opponentStyle'] == 'BALANCED' else source['opponentStyle']
        c.require(world['seed'] == source['seeds'][index] and seat == index % 4 and
            world['opponentStyle'] == style and set(world['roles']) == set(roles) and
            world['scienceSha256'] == prepared['scienceSha256'],
            'Each original world retains its exact seed, seat, crossed ecology, roles and scientific identity.')
        for game in world['roles'].values():
            c.require(type(game['decisions']) is int and 1 <= game['decisions'] <= 12000 and
                game['elapsedMillis'] == 0 and all(len(game[name]) == 4 for name in ('wins', 'rewards', 'ranks')),
                'A native terminal must retain four normalized seats and a bounded completed game.')
            for player in range(4):
                terminal_reward(dict(controlledSeat=player, terminal=dict(scores=game['scores'],
                    winShare=game['wins'][player], rankReward=game['ranks'][player],
                    reward=game['rewards'][player], gameDecisions=game['decisions'])))
    path = native/'reproductions.jsonl'
    replays = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
    c.require({(row['index'], row['role']) for row in replays} ==
        {(index, role) for index in owned for role in roles} and len(replays) == len(roles)*len(owned),
        'Exactly the prescribed original reproductions must be retained without duplicates.')
    by_index = {row['index']: row for row in original}
    for replay in replays:
        world, role = by_index[replay['index']], replay['role']
        c.require(replay['matched'] is True and replay['terminal'] == world['roles'][role] and
            replay['diagnostics'] == world['diagnostics'][role] and
            replay['canonicalSha256'] == world['canonicalSha256'][role] and
            isinstance(replay['canonicalSha256'], str) and len(replay['canonicalSha256']) == 64,
            'Original reproductions must exactly match normalized terminals, behavior and canonical history hashes.')
    games, executions = count*len(roles), (count+len(owned))*len(roles)
    c.require(completed['complete'] is True and completed['worlds'] == count and
        completed['games'] == games and completed['reproductions'] == len(replays) and
        completed['scientificTerminalExecutions'] == completed['attemptedGames'] ==
        completed['completedGames'] == completed['newGames'] == executions and
        completed['newFits'] == 0 and completed['qualificationClaimed'] is False,
        'Every original native game and reproduction must remain fully counted.')
    return original, dict(worlds=count, games=games, reproductions=len(replays), terminalExecutions=executions), pins


def verify(protocol, output, terminal, *, contract=None, now=time.time):
    contract = contract or c.TokenExperiment()
    plan = contract.validate(c.read(protocol)); output = Path(output)
    clock = c.read(output/'clock.json')
    c.require(clock == c.science_clock(plan, clock['startedEpoch'], c.sha(protocol), contract.prefix),
        'The original science/audit clock cannot be renewed or exceed its fixed user window.')
    c.require(now() <= clock['auditDeadlineEpoch'],
        'An original pure audit cannot restart or extend its fixed audit allowance.')
    c.require(clock['protocolSha256'] == terminal['protocolSha256'] == c.sha(protocol) and
        terminal['complete'] is True and terminal['status'] == 'completed',
        'Only a complete original fixed campaign can receive a completed pure audit.')
    selection = c.read(output/'selection.json')
    cases = {row['id']: row for row in plan['caseConfigs']}
    expected_banks = ['development']
    selected = selection['case']
    if selected is not None:
        expected_banks.append('mixed')
        mixed = c.read(output/'reports/mixed.json')['cases'][selected['id']]
        if mixed['wins']['lower99'] > 0: expected_banks.append('homogeneous')
    c.require({path.stem for path in (output/'reports').glob('*.json')} == set(expected_banks),
        'Only the prospectively gated complete banks may leave native reports.')
    c.require({path.name for path in (output/'chunks').iterdir()} == set(expected_banks),
        'Every original attempted bank must belong to the prospectively opened stages.')
    pins = {}; counts = dict(worlds=0, games=0, reproductions=0, terminalExecutions=0)
    reports = {}; development_controls = []
    for name in expected_banks:
        bank = plan['banks'][name]
        case_list = plan['caseConfigs'] if name == 'development' else [selected]
        c.require({path.name for path in (output/'chunks'/name).iterdir()} ==
            {case['id'] for case in case_list},
            'Every original attempted case must belong to its complete declared bank.')
        reports[name] = {}
        for case in case_list:
            directory = output/'chunks'/name/case['id']
            c.require({path.name for path in directory.iterdir()} ==
                {f'chunk-{chunk}' for chunk in range(len(bank['seeds'])//64)},
                'Each completed case requires its exact fixed original chunk set.')
            rows = []; sciences = set()
            for chunk in range(len(bank['seeds'])//64):
                folder = directory/f'chunk-{chunk}';request=c.read(folder/'request.json')
                source=request['source']; native=folder/'native'; completed=c.read(native/'finished.json')
                receipt=c.read(folder/'receipt.json')
                contract.check_source(plan, source, name, case, chunk, c.sha(protocol))
                c.require(receipt['host'] == contract.task_owner(plan, name, case, chunk),
                    'Original source ownership must retain its exact prospectively assigned host.')
                original, actual_counts, native_pins = native_chunk(source, native)
                sciences.add(completed['scienceSha256']); rows.extend(original); pins.update(native_pins)
                for key, value in actual_counts.items(): counts[key] += value
                for filename in ('request.json','receipt.json','native/finished.json'):
                    path=folder/filename;pins[str(path)]=c.sha(path)
            c.require(len(sciences)==1,'A case cannot combine different host-dependent scientific identities.')
            if name == 'development':
                development_controls.append([row['roles']['CONTROL'] for row in sorted(rows, key=lambda row: row['index'])])
            report=c.aggregate(rows,bank);reports[name][case['id']]=report
            actual=c.read(output/'reports'/f'{name}.json')['cases'][case['id']]
            c.require(report==actual,'All original paired statistics must independently reproduce exactly.')
    c.require(all(control == development_controls[0] for control in development_controls[1:]),
        'Repeated original CONTROL worlds must remain identical across all development cases.')
    c.require(c.chosen_case(reports['development'],plan['caseConfigs'])==selected,
        'The selected case must follow only the fixed ordered development mean-win rule.')
    qualified='homogeneous' in reports and reports['homogeneous'][selected['id']]['wins']['lower99']>0
    c.require(qualified is terminal['qualified'] and counts['terminalExecutions']==terminal['terminalExecutions'] and
        counts['terminalExecutions']<=plan['maximumTerminalExecutions'],'The final qualification and execution count must match complete audited evidence.')
    for name in ('clock.json','launch.json','selection.json'):
        path=output/name;pins[str(path)]=c.sha(path)
    for path in (output/'reports').glob('*.json'):pins[str(path)]=c.sha(path)
    return dict(kind=contract.prefix+'-completion-audit-1',passed=True,complete=True,
        protocolSha256=c.sha(protocol),clockSha256=c.sha(output/'clock.json'),
        qualified=qualified,selectedCase=selected,counts=counts,artifactPins=pins,
        allStatisticsRecomputed=True,originalReproductionsVerified=True,
        execution=dict(newGames=0,newFits=0,modelForwards=0),completedEpoch=now(),
        maximumAuditDeadlineEpoch=clock['auditDeadlineEpoch'],activated=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--receipt',type=Path,required=True)
    args=parser.parse_args()
    c.write(args.receipt,verify(args.protocol,args.output,c.read(args.output/'terminal.json')))
