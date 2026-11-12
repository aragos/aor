"""Independent fixed-policy qualification in bounded native chunks; no learning or selection.

The exact chronological terminal actor was selected after exploratory53. Its superiority over
the separated recipe was not established. Eight512-pair native chunks preserve each4096-world
bank's seed/seat/rival scheduling. Only complete bank aggregates decide the two99% gates.
"""
import argparse
import json
import os
from pathlib import Path

import automata_campaign as controller
import automata_evolution as evolution
import automata_experiments as exp
import automata_policy_search as search
import joint_chrono_evidence as evidence
import joint_credit_trial_data as original
import purchase_baseline_pilot as host

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'joint-chrono-fixed-qualification-1'
STAGES = ('confirm', 'heldout')
REPLAYS = (0, 1365, 2730, 4095)
CANDIDATE = '65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d'
CARD = '916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf'
PURCHASE = '6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79'
AUDIT = '11f1eab02ae2bf051f7b74880aa86a7b90a20bedad1dc26162c7b5c923f34e52'
DEVELOPMENT = '032b9bfcca6fa5c2dd54b9dafec74d84cbc9d1819f1f44f996ef0065583fdcd4'
PROOF = '984a72a3f1d9d52f07f07768bcfad8aff93fe3990c396d8ab0e3a1dcd0cdcb09'
TERMINAL_REVIEW = 'a167ae41f09c267925c1e8c152dc25d534255e57efd86a3b6ae1cba38231f473'
DISCLOSURE = ('One exact chronological terminal bundle selected for its higher exploratory53 mean; '
    'the primary chronological-versus-separated comparison was inconclusive.53 native screening '
    'remains operationally incomplete; the direct statistical contrast used proven complete '
    'common-world candidate games. No separated fallback or claim of clock superiority.')


def validate_inputs(protocol):
    if protocol.get('kind') == 'joint-successor-fixed-qualification-1':
        import joint_successor_qualification as successor
        successor.validate_inputs(protocol)
        return
    require(protocol['kind'] == KIND and protocol['configuration'] == exp.CONFIGURATION
        and protocol['workers'] == 10 and protocol['heapMiB'] == 4096
        and protocol['maximumSeconds'] == 28800 and protocol['stageSeconds'] == 14400
        and protocol['maximumRssBytes'] == 10*exp.GIB
        and protocol['maximumDataBytes'] == 8*exp.GIB and protocol['minimumFreeBytes'] == 8*exp.GIB
        and protocol['maximumAttempts'] == 1 and protocol['maximumScientificTerminalGames'] == 16384
        and protocol['maximumHistoricalTerminalReproductions'] == 16
        and protocol['maximumTerminalExecutions'] == 16400 and protocol['maximumFits'] == 0
        and protocol['qualificationConfidence'] == .99 and protocol['replayIndices'] == list(REPLAYS)
        and protocol['selectionDisclosure'] == DISCLOSURE
        and all(protocol[key] is False for key in ('interimChunkLooks','reselectionAllowed',
            'automaticResume','automaticSuccessor','productionActivation')),
        'Use the fixed two-bank, eight-chunk qualification with one attempt and no fallback.')
    subjects = [('ordinary',original.M8),('expansion',original.E),('prior',original.overlays.F0),
                ('candidateBundle',CANDIDATE),('seedAudit',AUDIT),('developmentAnalysis',DEVELOPMENT),
                ('developmentProof',PROOF),('candidateTerminalReview',TERMINAL_REVIEW)]
    require(all(sha(protocol[key]) == digest for key,digest in subjects),
            'A fixed candidate, anchor, evidence or fresh-bank subject changed.')
    candidate, anchor = [original.bundle_subjects(protocol[key])
                         for key in ('candidateBundle','anchorBundle')]
    require(candidate['card']['sha256'] == CARD and candidate['purchase']['sha256'] == PURCHASE
        and anchor['card'] is None and anchor['purchase']['sha256'] == original.G2,
        'Candidate exact two-head actor and actual raw-M8/G2 anchor must remain distinct.')
    development = read(protocol['developmentAnalysis'])
    require(development['passed'] and development['nativeTrialOperationallyComplete'] is False
        and development['nativeDirectParityChecks'] == 1667
        and development['missingNativeDirectPairs'] == 381
        and development['missingDirectHistoricalSideReplays'] == 8
        and development['proofSha256'] == PROOF,
        'Preserve the incomplete native53 record and its separately authorized derived analysis.')
    audit = read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and audit['humanInputsRead'] == audit['liveMetadataScans'] == 0
        and protocol['banks'] == audit['seedBanks'] and set(protocol['banks']) == set(STAGES),
        'Use exact frozen ancestry, never live human data or adaptive world selection.')
    for stage,bank in protocol['banks'].items():
        require(bank['count'] == 4096 and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'],4096),
                'Each aggregate qualification requires4096 independent paired worlds.')
        for index in range(8):
            source = evidence.source(protocol,stage,index)
            require(source['pairsPerConfiguration'] == 512
                and exp.arena_seeds(source['seed'],512) == bank['seeds'][index*512:(index+1)*512],
                'Native chunks must preserve the original aggregate chance bank.')
    exp.validate_banks({stage:bank['seeds'] for stage,bank in protocol['banks'].items()})


def estimates(metrics):
    return evolution.qualification_estimates({'qualificationConfidence':.99},metrics)


def gate(metrics):
    values = estimates(metrics)
    return all(values[name]['count'] == 4096 and values[name]['lowerConfidence'] is not None
               and values[name]['lowerConfidence'] > 0
               for name in ('winShareDifference','rewardDifference'))


def candidate_identity(protocol=None):
    if protocol is not None and protocol.get('kind') == 'joint-successor-fixed-qualification-1':
        import joint_successor_qualification as successor
        return successor.CANDIDATE, successor.CARD, successor.PURCHASE
    return CANDIDATE, CARD, PURCHASE


def heldout_ready(output, protocol=None):
    state = read(output/'campaign-state.json')['stages']['confirm']
    result = controller.validate_result(output/'results/confirm.json',output)
    report = read(output/'confirm-report.json')
    require(state['status'] == result['status'] == 'passed'
        and state['resultSha256'] == sha(output/'results/confirm.json')
        and report['candidateBundleSha256'] == candidate_identity(protocol)[0] and gate(report['metrics'])
        and result['metrics'] == report,
        'Heldout opens only after the same exact actor passes both complete confirmation99% gates.')


def replay_chunk(protocol, source_path, native, rows, target):
    receipts = []
    for row in rows:
        if row['globalIndex'] not in REPLAYS:
            continue
        for side in ('candidate','incumbent'):
            directory = target/f'{row["globalIndex"]}-{side}'
            require(not directory.exists(),'Each historical sampled side permits exactly one execution.')
            pair = native/f'pair-{row["index"]}.json'
            exp.native(protocol,'strategy-replay',source_path,directory,
                extra=('--workers','1','--index',str(row['index']),'--side',side,'--pair',str(pair)))
            verified = evidence.verify_replay(protocol,source_path,directory,row)
            require(verified['side'] == side,'Historical replay side must match its prescribed request.')
            receipts.append(dict(globalIndex=row['globalIndex'],index=row['index'],side=side,
                                 replaySha256=sha(directory/'replay.json')))
    return receipts


def compact_rows(path, rows):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x') as stream:
        for row in rows:
            stream.write(json.dumps(row,sort_keys=True,separators=(',',':'),allow_nan=False)+'\n')


def stage(protocol, protocol_path, output, name):
    require(name in STAGES,'Only the two fixed qualification stages are permitted.')
    if name == 'heldout':
        heldout_ready(output, protocol)
    root = output/name
    require(not root.exists(),'No chunk retry, top-up or resumed scientific stage is permitted.')
    root.mkdir(parents=True)
    receipts, all_rows, replays = [], [], []
    for index in range(8):
        exp.remaining_seconds()
        source_path = Path(protocol['sources'][name][index])
        require(read(source_path) == evidence.source(protocol,name,index),'Frozen chunk source changed.')
        work = root/'expanded'/f'chunk-{index:02d}'
        native = work/'native'
        exp.native(protocol,'arena',source_path,native)
        checked = evidence.verify_chunk(protocol,source_path,native)
        rows = checked['rows']
        replay_rows = replay_chunk(protocol,source_path,native,rows,work/'replays')
        compact = root/'compact'/f'chunk-{index:02d}.jsonl'
        roles = root/'compact'/f'roles-{index:02d}.json'
        compact_rows(compact,rows)
        document(roles,checked['roles'])
        archive = root/'archives'/f'chunk-{index:02d}.tar.gz'
        archive.parent.mkdir(parents=True,exist_ok=True)
        stored = evidence.archive(work,archive)
        receipt = dict(kind='joint-chrono-completed-chunk-1',stage=name,chunkIndex=index,
            source=str(source_path),sourceSha256=sha(source_path),compact=str(compact),compactSha256=sha(compact),
            roles=str(roles),rolesSha256=sha(roles),storage=stored,replays=replay_rows,
            scientificTerminalGames=1024,historicalReproductions=len(replay_rows))
        receipt_path = root/'receipts'/f'chunk-{index:02d}.json'
        document(receipt_path,receipt)
        receipts.append(dict(path=str(receipt_path),sha256=sha(receipt_path)))
        all_rows.extend(rows)
        replays.extend(replay_rows)
        print(f'Completed {name} chunk {index+1}/8; no aggregate gate evaluated yet.',flush=True)
    require(len(all_rows) == 4096 and [row['globalIndex'] for row in all_rows] == list(range(4096))
        and [row['seed'] for row in all_rows] == protocol['banks'][name]['seeds']
        and [(row['globalIndex'],row['side']) for row in replays]
            == [(index,side) for index in REPLAYS for side in ('candidate','incumbent')],
        'Only complete declared worlds and all eight exact historical sides can enter the gate.')
    metrics = search.comparison(all_rows)
    passed = gate(metrics)
    candidate, card, purchase = candidate_identity(protocol)
    report = dict(kind=protocol['kind'],stage=name,protocolSha256=sha(protocol_path),
        candidateBundleSha256=candidate,candidateCardSha256=card,candidatePurchaseSha256=purchase,
        anchorBundleSha256=sha(protocol['anchorBundle']),metrics=estimates(metrics),
        chunks=receipts,replays=replays,passed=passed,qualificationPassed=name=='heldout' and passed,
        scientificTerminalGames=8192,historicalReproductions=8,totalTerminalExecutions=8200,
        actualFits=0,productionActivation=False,selectionDisclosure=protocol['selectionDisclosure'])
    path = output/f'{name}-report.json'
    document(path,report)
    artifacts = [path,*[file for file in root.rglob('*') if file.is_file()]]
    return exp.stage_result(output,name,passed,
        'Both complete4096-world99% gates passed; no activation.' if passed else
        'Exact candidate qualification rejected; stop with heldout unopened after confirmation failure.',
        report,artifacts)


def campaign_spec(root, protocol, files):
    return dict(schemaVersion=1,kind='automata-campaign',inputs=files,
        limits={key:protocol[key] for key in ('maximumSeconds','maximumDataBytes','maximumRssBytes','minimumFreeBytes')},
        stages=[dict(id=name,dependsOn=[] if name=='confirm' else ['confirm'],
            maximumSeconds=protocol['stageSeconds'],maximumAttempts=1,result=f'results/{name}.json',
            command=[protocol['python']['executable'],'-E','-s','-S','-B',str(root/'scripts'/Path(__file__).name),
                     'stage','--protocol',str(root/'protocol.json'),'--output','{output}','--stage',name])
            for name in STAGES])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage'))
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=STAGES)
    args = parser.parse_args()
    protocol = read(args.protocol)
    validate_inputs(protocol)
    host.verify_execution(protocol)
    if args.command == 'preflight':
        require(not args.output.exists(),'Preflight must precede the only scientific attempt.')
        own = [row for row in controller.processes.sample_group(os.getpgrp()) if row.pid == os.getpid()]
        require(len(own) == 1 and own[0].rss_bytes > 0,'The exact child needs working external process sampling.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    else:
        stage(protocol,args.protocol,args.output,args.stage)


if __name__ == '__main__':
    main()
