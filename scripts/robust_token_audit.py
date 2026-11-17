"""Pure, bounded reaggregation of complete original robust-token artifacts; no execution."""
import argparse
import hashlib
import json
from pathlib import Path
import time

import robust_token_campaign as c


def verify(protocol, output, terminal):
    plan = c.validate(c.read(protocol)); output = Path(output)
    clock = c.read(output/'clock.json')
    c.require(time.time() <= clock['auditDeadlineEpoch'],
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
    pins = {}; counts = dict(worlds=0, games=0, reproductions=0, terminalExecutions=0)
    reports = {}
    for name in expected_banks:
        bank = plan['banks'][name]
        case_list = plan['caseConfigs'] if name == 'development' else [selected]
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
                prepared=c.read(native/'prepared.json'); receipt=c.read(folder/'receipt.json')
                c.require(source['protocolSha256'] == c.sha(protocol) and
                    source['selector'] == case['selector'] and source['seeds'] == bank['seeds'] and
                    source['opponentStyle'] == bank['opponentStyle'] and source['globalStart'] == chunk*64 and
                    source['count'] == 64 and source['replayIndices'] == bank['replayIndices'] and
                    receipt['host'] == c.owner(chunk),
                    'Every actual source retains the fixed selected config, whole bank and original host range.')
                expected_source=hashlib.sha256(c.canonical(source)).hexdigest()
                c.require(completed['sourceSha256'] == prepared['sourceSha256'] == expected_source and
                    completed['scienceSha256'] == prepared['scienceSha256'],
                    'The original native preparation and completion must bind exact source and science bytes.')
                sciences.add(completed['scienceSha256'])
                for filename,digest in completed['files'].items():
                    path=native/filename
                    c.require(c.sha(path)==digest,'An original native output changed after completion.')
                    pins[str(path)]=digest
                original=[json.loads(line) for line in (native/'worlds.jsonl').read_text().splitlines()]
                c.require(len(original)==64 and [row['index'] for row in original]==list(range(chunk*64,(chunk+1)*64)),
                    'Each compact task retains its64 original global outcomes.')
                rows.extend(original)
                owned=[index for index in bank['replayIndices'] if chunk*64<=index<(chunk+1)*64]
                reproduction_path=native/'reproductions.jsonl'
                replays=[json.loads(line) for line in reproduction_path.read_text().splitlines()] if reproduction_path.exists() else []
                c.require({(row['index'],row['role']) for row in replays}==
                    {(index,role) for index in owned for role in ('CANDIDATE','CONTROL')} and
                    len(replays)==2*len(owned),'Exactly the prescribed real original reproductions must be retained.')
                by_index={row['index']:row for row in original}
                for replay in replays:
                    world=by_index[replay['index']];role=replay['role']
                    c.require(replay['matched'] is True and replay['terminal']==world['roles'][role] and
                        replay['diagnostics']==world['diagnostics'][role] and
                        replay['canonicalSha256']==world['canonicalSha256'][role] and
                        isinstance(replay['canonicalSha256'],str) and len(replay['canonicalSha256'])==64,
                        'A prescribed reproduction must retain exact normalized outcomes, diagnostics and canonical history hash.')
                expected_games=128+len(replays)
                c.require(completed['complete'] is True and completed['worlds']==64 and completed['games']==128 and
                    completed['reproductions']==len(replays) and
                    completed['scientificTerminalExecutions']==completed['attemptedGames']==
                    completed['completedGames']==expected_games and completed['newFits']==0 and
                    completed['qualificationClaimed'] is False,
                    'Every original native execution and reproduction must remain fully counted.')
                counts['worlds']+=64;counts['games']+=128;counts['reproductions']+=len(replays)
                counts['terminalExecutions']+=expected_games
                for filename in ('request.json','receipt.json','native/finished.json'):
                    path=folder/filename;pins[str(path)]=c.sha(path)
            c.require(len(sciences)==1,'A case cannot combine different host-dependent scientific identities.')
            report=c.aggregate(rows,bank);reports[name][case['id']]=report
            actual=c.read(output/'reports'/f'{name}.json')['cases'][case['id']]
            c.require(report==actual,'All original paired statistics must independently reproduce exactly.')
    c.require(c.chosen_case(reports['development'],plan['caseConfigs'])==selected,
        'The selected case must follow only the fixed ordered development mean-win rule.')
    qualified='homogeneous' in reports and reports['homogeneous'][selected['id']]['wins']['lower99']>0
    c.require(qualified is terminal['qualified'] and counts['terminalExecutions']==terminal['terminalExecutions'] and
        counts['terminalExecutions']<=16464,'The final qualification and execution count must match complete audited evidence.')
    for name in ('clock.json','launch.json','selection.json'):
        path=output/name;pins[str(path)]=c.sha(path)
    for path in (output/'reports').glob('*.json'):pins[str(path)]=c.sha(path)
    return dict(kind='robust-token-completion-audit-1',passed=True,complete=True,
        protocolSha256=c.sha(protocol),clockSha256=c.sha(output/'clock.json'),
        qualified=qualified,selectedCase=selected,counts=counts,artifactPins=pins,
        allStatisticsRecomputed=True,originalReproductionsVerified=True,
        execution=dict(newGames=0,newFits=0,modelForwards=0),completedEpoch=time.time(),
        maximumAuditDeadlineEpoch=clock['auditDeadlineEpoch'],activated=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--receipt',type=Path,required=True)
    args=parser.parse_args()
    c.write(args.receipt,verify(args.protocol,args.output,c.read(args.output/'terminal.json')))
