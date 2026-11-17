"""Pure final verification of complete original ES populations and independent test worlds."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

import robust_token_campaign as c
import robust_league_campaign as league


def cohort(plan,output,bank,cases,candidate_only,pins,counts):
    result={}
    for case in cases:
        base=output/'chunks'/bank/case['id'];chunks=len(plan['banks'][bank]['seeds'])//64
        c.require({p.name for p in base.iterdir()}=={f'chunk-{i}' for i in range(chunks)},
            'An original case must retain every exact once-only chunk.')
        rows=[];sciences=set()
        for chunk in range(chunks):
            folder=base/f'chunk-{chunk}';request=c.read(folder/'request.json');source=request['source']
            native=folder/'native';finished=c.read(native/'finished.json');prepared=c.read(native/'prepared.json')
            original=[json.loads(line) for line in (native/'worlds.jsonl').read_text().splitlines()]
            c.validate_chunk_indices(original,chunk*64,64)
            c.require(source['seeds']==plan['banks'][bank]['seeds'] and source['globalStart']==chunk*64 and
                source['count']==64 and source['candidateOnly'] is candidate_only and
                source['selector']==case['selector'] and source['protocolSha256']==c.sha(output/'protocol-copy.json'),
                'Every source retains its exact bank, selected strategy and original controlled role.')
            expected_bundle=case.get('bundleSha256')
            c.require((source.get('candidateBundle') or {}).get('sha256')==expected_bundle,
                'A member cannot use a different numerical candidate than its original signed population entry.')
            c.require(finished['sourceSha256']==prepared['sourceSha256']==
                hashlib.sha256(c.canonical(source)).hexdigest() and
                finished['scienceSha256']==prepared['scienceSha256'] and
                all(row['scienceSha256']==finished['scienceSha256'] for row in original),
                'Every original compact outcome must bind exact source and scientific identity.')
            sciences.add(finished['scienceSha256'])
            for name,digest in finished['files'].items():
                path=native/name;c.require(c.sha(path)==digest,'An original native artifact changed.');pins[str(path)]=digest
            roles=['CANDIDATE'] if candidate_only else ['CANDIDATE','CONTROL']
            indices=league.replay_indices(bank,case.get('member'))
            expected={(index,role) for index in indices if chunk*64<=index<(chunk+1)*64 for role in roles}
            path=native/'reproductions.jsonl'
            replays=[json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
            c.require(len(replays)==len(expected) and {(row['index'],row['role']) for row in replays}==expected,
                'Original reproductions must exactly match the prospectively prescribed members and world indices.')
            by_index={row['index']:row for row in original}
            for replay in replays:
                world=by_index[replay['index']];role=replay['role']
                c.require(replay['matched'] is True and replay['terminal']==world['roles'][role] and
                    replay['diagnostics']==world['diagnostics'][role] and
                    replay['canonicalSha256']==world['canonicalSha256'][role],
                    'Every prescribed reproduction must retain exact outcomes, diagnostics and replayed history hash.')
            executions=64*len(roles)+len(replays)
            c.require(finished['complete'] is True and finished['worlds']==64 and
                finished['games']==64*len(roles) and finished['reproductions']==len(replays) and
                finished['scientificTerminalExecutions']==finished['attemptedGames']==
                finished['completedGames']==executions and finished['roles']==roles and finished['newFits']==0,
                'Every actual scientific execution and reproduction must remain counted exactly.')
            receipt=c.read(folder/'receipt.json')
            expected_host=league.owner(case['member'],chunk,int(bank.split('-')[1])) if candidate_only else league.test_owner(chunk)
            c.require(receipt['host']==expected_host and receipt['finishedSha256']==c.sha(native/'finished.json'),
                'Completed native chunks cannot migrate or acquire a substituted final receipt.')
            counts['games']+=64*len(roles);counts['reproductions']+=len(replays)
            counts['terminalExecutions']+=executions
            for name in ('request.json','receipt.json','native/finished.json'):
                path=folder/name;pins[str(path)]=c.sha(path)
            rows.extend(original)
        c.require(len(sciences)==1,'The same case cannot combine different host-dependent scientific identities.')
        if bank!='homogeneous':rows=league.check_worlds(rows,plan['banks'][bank]['seeds'],candidate_only)
        else:rows=sorted(rows,key=lambda row:row['index'])
        result[case['id']]=rows
    return result


def verify(protocol,output,terminal):
    output=Path(output);plan=league.validate(c.read(protocol));clock=c.read(output/'clock.json')
    c.require(time.time()<=clock['auditDeadlineEpoch'] and terminal['complete'] is True,
        'Only a complete original experiment may receive its bounded pure audit.')
    c.require(c.sha(protocol)==clock['protocolSha256']==terminal['protocolSha256'],
        'The original protocol and immutable shared clock must remain exact.')
    c.require((output/'protocol-copy.json').read_bytes()==Path(protocol).read_bytes(),
        'The originally executed protocol must remain byte exact.')
    pins={};counts=dict(games=0,reproductions=0,terminalExecutions=0)
    selector=terminal['selector'];last_center=None
    for generation in range(1,league.GENERATIONS+1):
        name=f'generation-{generation}'
        pop_base=output/'numeric'/(name+'-population')/'artifacts'
        population=c.read(pop_base/'population.json');pop_receipt=c.read(pop_base/'receipt.json')
        c.require(population['generation']==generation and population['worldSeeds']==plan['banks'][name]['seeds'] and
            len(population['members'])==32,'Every fixed generation requires its32 original signed members.')
        if last_center is not None:c.require(population['centerSha256']==last_center,'ES generations cannot skip or replace a center.')
        cases=[dict(id=f'member-{member["index"]:02d}',member=member['index'],selector=selector,
            bundleSha256=member['bundle']['sha256']) for member in population['members']]
        rows=cohort(plan,output,name,cases,True,pins,counts)
        expected=league.outcome_matrix(generation,population,{case['member']:rows[case['id']] for case in cases})
        expected['populationSha256']=c.sha(pop_base/'population.json')
        path=output/'generations'/name/'outcomes.json'
        c.require(c.read(path)==expected,'The complete native win matrix must reproduce from every original controlled-seat outcome.')
        update_base=output/'numeric'/(name+'-update')/'artifacts';update=c.read(update_base/'receipt.json')
        means=[sum(row['winShares'])/128 for row in expected['members']]
        c.require(update['generation']==generation and update['populationSha256']==expected['populationSha256'] and
            update['outcomesSha256']==c.sha(path) and
            len(update['memberWinMeans'])==32 and
            all(abs(a-b)<1e-14 for a,b in zip(means,update['memberWinMeans'])),
            'Only the exact complete original win-only matrix may produce the next numerical center.')
        center=c.read(update_base/'center.json');last_center=c.sha(update_base/'center.json')
        c.require(center['generation']==generation and len(center['coordinates'])==256 and
            sum(value*value for value in center['coordinates'])/256<=16+1e-12,
            'Every immediate mean retains its fixed generation, dimensions and RMS4 allowance.')
        for p in (path,pop_base/'population.json',pop_base/'receipt.json',update_base/'receipt.json',update_base/'center.json'):
            pins[str(p)]=c.sha(p)
    final_base=output/'numeric/final-mean-6/artifacts';final=c.read(final_base/'receipt.json')
    c.require(final['generation']==6 and final['centerSha256']==last_center and
        final['bundle']['sha256']==terminal['candidateBundle']['sha256'] and
        final['candidate']=='FINAL_MEAN_6_ONLY','Only the sole fully trained final mean6 may enter development.')
    final_case=dict(id='final',selector=selector,bundleSha256=final['bundle']['sha256'])
    start_case=dict(id='start',selector=selector)
    rows=cohort(plan,output,'development',[final_case,start_case],False,pins,counts)
    dev=league.development(rows['final'],rows['start'],plan['banks']['development']['seeds'])
    c.require(dev==c.read(output/'reports/development.json'),'Both original development95 gates must independently reproduce.')
    qualified=False;expected_reports={'development'}
    if dev['passed']:
        for bank in ('mixed','homogeneous'):
            expected_reports.add(bank)
            rows=cohort(plan,output,bank,[final_case],False,pins,counts)['final']
            statistics=c.aggregate(rows,dict(seeds=plan['banks'][bank]['seeds'],
                opponentStyle='QUALIFIED' if bank=='homogeneous' else 'BALANCED'))
            original=c.read(output/'reports'/f'{bank}.json')
            c.require(original['statistics']==statistics and original['win99Passed']==(statistics['wins']['lower99']>0),
                'Every complete independent qualification99 interval must reproduce from original paired worlds.')
            if statistics['wins']['lower99']<=0:break
            if bank=='homogeneous':qualified=True
    c.require({path.stem for path in (output/'reports').glob('*.json')}==expected_reports and
        qualified is terminal['qualified'] and counts['terminalExecutions']==terminal['knownCompletedTerminalExecutions'] and
        counts['terminalExecutions']<=36920,'Final qualification and all actual executions must match the fixed complete gated method.')
    dynamic=c.read(output/'dynamic-mac-inputs.json')
    for path,digest in dynamic.items():c.require(c.sha(path)==digest,'A copied original Mac model changed.');pins[path]=digest
    for path in (output/'numeric').glob('*/artifacts/**/*.json'):
        if path.is_file():pins[str(path)]=c.sha(path)
    for path in (output/'reports').glob('*.json'):pins[str(path)]=c.sha(path)
    numerical_path=output/'numerical-physical-review/proof.json';numerical=c.read(numerical_path)
    c.require(numerical['passed'] is True and numerical['complete'] is True and
        numerical['clockSha256']==c.sha(output/'clock.json') and len(numerical['receipts'])==14 and
        numerical['newGames']==numerical['newFits']==numerical['modelForwards']==0,
        'All14 original numerical steps and their retained physical byte closure must be independently rehashed.')
    pins[str(numerical_path)]=c.sha(numerical_path)
    return dict(kind='robust-league-completion-audit-1',passed=True,complete=True,
        protocolSha256=c.sha(protocol),clockSha256=c.sha(output/'clock.json'),qualified=qualified,
        counts=counts,artifactPins=pins,allOriginalWinMatricesRecomputed=True,
        allDevelopmentAndQualificationIntervalsRecomputed=True,allActualReproductionsVerified=True,
        dynamicMacModelFilesRehashed=len(dynamic),execution=dict(newGames=0,newFits=0,modelForwards=0),
        originalAuditDeadlineEpoch=clock['auditDeadlineEpoch'],completedEpoch=time.time(),activated=False)
