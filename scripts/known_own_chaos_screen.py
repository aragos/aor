"""One fixed candidate-only conditional-support diagnostic; no learning or qualification.

Two native chunks preserve one 1,024-world scheduler. The unchanged arena, replay, terminal
metric and lossless archive owners do the work; this profile binds the explicit support identity.
"""
import argparse
import json
import os
from pathlib import Path
import platform

import automata_campaign as campaign
import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import joint_chrono_evidence as chunks
import joint_chrono_qualification as g3
import joint_credit_trial_data as joint
import purchase_baseline_pilot as host
import purchase_ecology_data as ecology

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'known-own-chaos-screen-1'
PROFILE = dict(profile='KNOWN_OWN_CHAOS_1', version='known-own-chaos-support-1')
NATIVE = '8f7381f06dd4b7e0ee64d97763b6e4033aa2eea6'
READINESS = 'c3cb193f148eaa67ab22195eb370c43644468ebd0f2289f733a724d9d928f649'
ANCESTOR = '14d69e3c14e753404755a0deb1ab098f8c51c98af1501aa36d95c38319f8fea4'
QUALIFICATION = '46424285f5d35b32ea6053eead57ba0aa1b8d676588777bbd170c92211bcf546'
PAIRS, CHUNK_PAIRS, REPLAYS = 1024, 512, (0, 341, 682, 1023)
DISCLOSURE = ('One fixed inference support correction on the same native engine and exact G3 '
    'weights. Paired 95% intervals are descriptive; completion never selects a model, admits '
    'strength, activates a policy or launches a successor. No population activation tally.')


def source(protocol, chunk):
    require(type(chunk) is int and 0 <= chunk < 2, 'Use exactly chunks0 and1.')
    bank = protocol['banks']['screen']
    require(bank['count'] == PAIRS and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], PAIRS),
            'Use the exact prospective1,024-world native bank.')
    offset = bank['nativeOffset'] + chunk * CHUNK_PAIRS * 8191
    result = exp.arena_source(protocol['ordinary'], [protocol['ordinary']] * 4,
                              offset, CHUNK_PAIRS, purpose='SCREENING')
    return dict(result, experimentalRulesTransfer=legacy.RULES_TRANSFER,
        **ecology.bindings(protocol['expansion'], arena=True,
                           rival_ecology=ecology.ArenaRivalEcology.HOMOGENEOUS),
        jointPolicy=dict(baseline=protocol['prior'], sharedBundle=protocol['bundle'],
            candidateBundle=protocol['bundle'], incumbentBundle=protocol['bundle'],
            candidateSupport=PROFILE['profile']))


def validate_inputs(protocol):
    fixed = dict(kind=KIND, configuration=exp.CONFIGURATION, hostRole='mac', nativeCommit=NATIVE,
        candidateSupport=PROFILE, workers=6, heapMiB=4096, pairs=PAIRS, chunks=2,
        chunkPairs=CHUNK_PAIRS, maximumSeconds=7200, auditSeconds=900, maximumAttempts=1,
        maximumRssBytes=8*exp.GIB, maximumDataBytes=8*exp.GIB, minimumFreeBytes=8*exp.GIB,
        maximumScientificTerminalGames=2048, maximumHistoricalTerminalReproductions=8,
        maximumTerminalExecutions=2056, maximumFits=0, diagnosticConfidence=.95,
        replayIndices=list(REPLAYS), disclosure=DISCLOSURE)
    require(all(protocol.get(k) == v for k, v in fixed.items()), 'The fixed71 profile changed.')
    require(all(protocol.get(k) is False for k in ('qualificationPassed', 'productionActivation',
        'automaticSuccessor', 'automaticResume', 'interimQualityLooks')),
        'This diagnostic cannot qualify, activate, resume or adapt.')
    for key, digest in (('ordinary', joint.M8), ('expansion', joint.E), ('prior', ecology.F0),
                        ('bundle', g3.CANDIDATE)):
        require(sha(protocol[key]) == digest, 'A frozen model subject changed: ' + key)
    bundle = joint.bundle_subjects(protocol['bundle'])
    require(bundle['card']['sha256'] == g3.CARD and bundle['purchase']['sha256'] == g3.PURCHASE,
            'All roles require both exact qualified G3 heads.')
    pin = protocol['qualificationReview']
    require(pin['sha256'] == QUALIFICATION and sha(pin['path']) == QUALIFICATION,
            'Original G3 qualification evidence changed.')
    review = read(pin['path'])
    require(review['passed'] is True and review['qualificationPassed'] is True
        and review['candidateBundleSha256'] == g3.CANDIDATE
        and review['candidateCardSha256'] == g3.CARD
        and review['candidatePurchaseSha256'] == g3.PURCHASE,
        'The original model qualification must identify both exact G3 heads.')
    pin = protocol['supportReadiness']
    require(pin['sha256'] == READINESS and sha(pin['path']) == READINESS,
            'Exact common/native/JS support evidence must be retained.')
    ready = read(pin['path'])
    require(ready['passed'] is True and ready['commit'] == NATIVE and ready['sourceOnly'] is True,
            'Require the reviewed inference-only source readiness.')
    pin = protocol['seedAudit']
    require(sha(pin['path']) == pin['sha256'], 'The prospective audit changed.')
    audit = read(pin['path'])
    require(audit['allIntersectionsZero'] is True and audit['humanInputsRead'] == 0
        and audit['liveMetadataScans'] == 0 and protocol['banks'] == audit['seedBanks']
        and set(protocol['banks']) == {'screen'} and protocol['frozenAncestorSha256'] == ANCESTOR
        and audit['carriedForwardAudit']['sha256'] == ANCESTOR
        and audit['allDeclared70StreamsPreserved'] == 22548,
        'The single prospective bank must retain the full frozen70 ancestry without scans.')
    for chunk in range(2):
        current = source(protocol, chunk)
        require(exp.arena_seeds(current['seed'], CHUNK_PAIRS)
            == protocol['banks']['screen']['seeds'][chunk*CHUNK_PAIRS:(chunk+1)*CHUNK_PAIRS],
            'Chunking changed the original global seed schedule.')


def verify_roles(protocol, source_path, directory, reports):
    expected = dict(joint.joint_binding(source_path), candidateSupport=PROFILE)
    require(all(row.get('jointPolicy') == expected for row in reports),
            'Every pair/replay must bind candidate-only conditional support and exact roles.')
    subjects = [expected['source'], expected['baseline']]
    for role in ('candidateBundle', 'incumbentBundle', 'sharedBundle'):
        bundle = expected[role]
        require(bundle['sha256'] == g3.CANDIDATE and bundle['card']['sha256'] == g3.CARD
            and bundle['purchase']['sha256'] == g3.PURCHASE,
            'Candidate, incumbent and rivals must retain identical G3 weights.')
        subjects += [bundle, bundle['card'], bundle['purchase']]
    require(all(sha(Path(directory)/item['snapshot']) == item['sha256'] for item in subjects),
            'Original source/prior/bundle/component snapshots changed.')
    current = read(source_path)
    ecology.verify_overlays(current, Path(directory), reports, arena=True, expected_expansion=joint.E,
                           rival_ecology=ecology.ArenaRivalEcology.HOMOGENEOUS)
    references = {role: protocol['ordinary'] for role in
                  ('candidate', 'incumbent', *[f'opponent-{i}' for i in range(4)])}
    for report in reports:
        legacy.verify_transferred_sources(current, report, directory, references)
    return expected


def verify_chunk(protocol, source_path, directory, chunk):
    source_path, directory = Path(source_path), Path(directory)
    current = read(source_path)
    require(current == source(protocol, chunk), 'Completed source differs from the fixed chunk.')
    report, run, state = [read(directory/n) for n in ('evaluation.json', 'run.json', 'state.json')]
    require(report['complete'] is True and report['purpose'] == 'SCREENING'
        and report['promoted'] is False and report['humanStrengthVerified'] is False
        and state['phase'] == 'complete' and bool(read(directory/'finished.json')['at'])
        and run['kind'] == state['kind'] == 'strategy-arena'
        and run['signature'] == state['signature'] and run['source'] == str(source_path)
        and run['workers'] == protocol['workers'] and run['pairs'] == CHUNK_PAIRS
        and run['seed'] == current['seed'] and run['opponentModels'] == 4,
        'Require complete native chunk/source/worker receipts.')
    roles = verify_roles(protocol, source_path, directory, [report, run])
    require({p.name for p in directory.glob('pair-*.json')} == {f'pair-{i}.json' for i in range(CHUNK_PAIRS)},
            'All512 original paired worlds are required.')
    rows = []
    for index in range(CHUNK_PAIRS):
        path = directory/f'pair-{index}.json'; global_index = chunk*CHUNK_PAIRS + index
        rows.append(chunks.compact_pair(read(path), roles, index, global_index,
            protocol['banks']['screen']['seeds'][global_index], sha(path), sha(source_path)))
    metrics = search.comparison(rows)
    search.verify_metrics(report['overall'], metrics)
    require(set(report['byConfiguration']) == {exp.CONFIGURATION}, 'Only one fixed setup is allowed.')
    search.verify_metrics(report['byConfiguration'][exp.CONFIGURATION], metrics)
    return dict(rows=rows, roles=roles)


def verify_replay(protocol, source_path, directory, row, side):
    directory = Path(directory)
    replay, run, state = [read(directory/n) for n in ('replay.json', 'run.json', 'state.json')]
    require(row['globalIndex'] in REPLAYS and row['index'] == row['globalIndex'] % CHUNK_PAIRS
        and side in ('candidate', 'incumbent') and replay['side'].lower() == side
        and sha(source_path) == row['sourceSha256'] and replay['replayVerified'] is True
        and replay['historicalPairVerified'] is True and run['source'] == str(source_path)
        and run['workers'] == 1 and run['signature'] == state['signature']
        and state['phase'] == 'complete' and bool(read(directory/'finished.json')['at'])
        and all(replay[k] == row[k] for k in chunks.IDENTITY)
        and search.same_game(replay['game'], row[side])
        and sha(run['recordedPair']) == row['pairSha256'],
        'Historical replay must reproduce the same source, original pair, side and terminal.')
    roles = verify_roles(protocol, source_path, directory, [run, state])
    require(replay['jointPolicy'] == roles, 'Historical support identity changed.')
    return dict(globalIndex=row['globalIndex'], index=row['index'], side=side,
                replaySha256=sha(directory/'replay.json'), pairSha256=row['pairSha256'])


def metrics(rows):
    require(len(rows) == PAIRS and [r['globalIndex'] for r in rows] == list(range(PAIRS)),
            'Compute descriptive effects only after the whole1,024-pair bank completes.')
    values = {side: {name: [] for name in ('reward', 'winShare', 'rankReward', 'ownChaos')}
              for side in ('candidate', 'incumbent')}
    for row in rows:
        for side in values:
            game, seat = row[side], row['seat']
            for name, field in (('reward', 'rewards'), ('winShare', 'wins'), ('rankReward', 'ranks')):
                values[side][name].append(game[field][seat])
            values[side]['ownChaos'].append(float(game['scores'][seat]['total'] is None))
    return dict(paired={name: exp.interval([a-b for a, b in zip(values['candidate'][name],
                        values['incumbent'][name])]) for name in values['candidate']},
                absolute={side: {name: exp.interval(xs) for name, xs in fields.items()}
                          for side, fields in values.items()})


def run(protocol, protocol_path, output):
    require(not (output/'screen').exists() and not (output/'screen-report.json').exists(),
            'One complete screen attempt only; never resume, refill or retry.')
    root = output/'screen'; root.mkdir(parents=True)
    rows, receipts, replays = [], [], []
    for chunk in range(2):
        exp.remaining_seconds()
        source_path = root/f'chunk-{chunk:02d}-source.json'
        document(source_path, source(protocol, chunk))
        expanded = root/'expanded'/f'chunk-{chunk:02d}'; native = expanded/'native'
        exp.native(protocol, 'arena', source_path, native)
        checked = verify_chunk(protocol, source_path, native, chunk)
        historical = []
        for row in checked['rows']:
            if row['globalIndex'] not in REPLAYS:
                continue
            for side in ('candidate', 'incumbent'):
                target = expanded/'replays'/f'{row["globalIndex"]}-{side}'
                exp.native(protocol, 'strategy-replay', source_path, target,
                    extra=('--workers', '1', '--index', str(row['index']), '--side', side,
                           '--pair', str(native/f'pair-{row["index"]}.json')))
                historical.append(verify_replay(protocol, source_path, target, row, side))
        compact = root/'compact'/f'chunk-{chunk:02d}.jsonl'
        roles = root/'compact'/f'roles-{chunk:02d}.json'
        g3.compact_rows(compact, checked['rows']); document(roles, checked['roles'])
        target = root/'archives'/f'chunk-{chunk:02d}.tar.gz'
        target.parent.mkdir(parents=True, exist_ok=True)
        stored = chunks.archive(expanded, target)
        receipt = root/'receipts'/f'chunk-{chunk:02d}.json'
        document(receipt, dict(kind='known-own-chaos-screen-chunk-1', chunkIndex=chunk,
            source=str(source_path), sourceSha256=sha(source_path), compact=str(compact),
            compactSha256=sha(compact), roles=str(roles), rolesSha256=sha(roles), storage=stored,
            replays=historical, scientificTerminalGames=1024, historicalReproductions=len(historical)))
        receipts.append(dict(path=str(receipt), sha256=sha(receipt)))
        rows.extend(checked['rows']); replays.extend(historical)
        print(f'Completed chunk{chunk+1}/2; no aggregate effects read.', flush=True)
    require([r['seed'] for r in rows] == protocol['banks']['screen']['seeds']
        and [(r['globalIndex'], r['side']) for r in replays]
            == [(i, side) for i in REPLAYS for side in ('candidate', 'incumbent')],
        'The full original bank and all eight historical sides are mandatory.')
    report = dict(kind=KIND, completed=True, protocolSha256=sha(protocol_path),
        candidateSupport=PROFILE, bundleSha256=g3.CANDIDATE, chunks=receipts, replays=replays,
        metrics=metrics(rows), scientificTerminalGames=2048, historicalReproductions=8,
        totalTerminalExecutions=2056, actualFits=0, qualificationPassed=False,
        productionActivation=False, automaticSuccessor=False, disclosure=DISCLOSURE)
    document(output/'screen-report.json', report)
    artifacts = [output/'screen-report.json', *[root/name for name in chunks.inventory(root)]]
    return exp.stage_result(output, 'screen', True,
        'Complete fixed diagnostic; statistical sign does not confer strength or activation.', report, artifacts)


def campaign_spec(root, protocol, files):
    return dict(schemaVersion=1, kind='automata-campaign', inputs=files,
        limits={k: protocol[k] for k in ('maximumSeconds', 'maximumDataBytes',
                                       'maximumRssBytes', 'minimumFreeBytes')},
        stages=[dict(id='screen', dependsOn=[], maximumSeconds=7200, maximumAttempts=1,
            result='results/screen.json', command=[protocol['python']['executable'], '-E', '-s', '-S', '-B',
                str(root/'scripts'/Path(__file__).name), 'stage', '--protocol', str(root/'protocol.json'),
                '--output', '{output}'])])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'stage'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); protocol = read(args.protocol)
    validate_inputs(protocol); host.verify_execution(protocol)
    require(platform.system() == 'Darwin' and platform.platform() == protocol['environment']['platform']
        and platform.machine() == protocol['environment']['machine']
        and os.cpu_count() == protocol['environment']['logicalCpus'], 'The frozen Mac host changed.')
    if args.command == 'preflight':
        require(not args.output.exists(), 'Preflight must precede the exclusive attempt.')
        own = [r for r in campaign.processes.sample_group(os.getpgrp()) if r.pid == os.getpid()]
        require(len(own) == 1 and own[0].rss_bytes > 0, 'Actual process sampling must work.')
        print(json.dumps(dict(passed=True, newGames=0, actualFits=0,
                             externalPsSamplerPassed=True, protocolSha256=sha(args.protocol))))
    else:
        run(protocol, args.protocol, args.output)


if __name__ == '__main__':
    main()
