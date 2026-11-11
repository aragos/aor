"""One native Cathedral diagnostic after two exact historical controller regressions.

No fitting, selection, qualification or activation. Historical and fresh worlds have separate
roles; every declared attempt is consumed before its child starts and is never resumed here.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path

import automata_campaign as controller
import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as data

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'native-cathedral-screen-1'
NATIVE = '46a2cd8f7728d7916034df6bc15a14f9dc20f282'
G2 = '09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af'
G1 = '47f009bd670d1e8f77d10520f8af5e73d3dc96e873cdc7e33658ccae600266c7'
AUDIT = '622d806700b9b19e22909d01929c9896a73c2838cfcd556965f19a5444537f34'
SEEDS = '33606c97c8db134962072b191c29a65549648a41cfafa821e10c352a3b6f9ddd'
RECEIPT = 'cd0e3881b73f23f35b38f5e53033e844f1317730813a2168cfdab1fe2414dc9b'
REVIEW = '3c546c4abc3be13a8744d750f9f18f671f968e34dd180cb9dc71d22fc532d4f6'
STAGES = ('historical', 'screen')
REPLAYS = (0, 341, 682, 1023)
HISTORICAL = (0, 4095)
HISTORICAL_SOURCE = '8c34f85740081ea960a5b1ac5656b1452749891842be0b4c6848dcc18d99083f'
GATE = dict(version='public-cathedral-two-action-1', candidateEnabled=True)
METADATA = dict(GATE, nonpassRootLimit=16, followupLimit=16, victoriesAreLeaves=True, diceTriples=216)
ECOLOGY = data.ArenaRivalEcology.HOMOGENEOUS


def pinned(binding, expected=None):
    require(isinstance(binding, dict) and set(binding) == {'path', 'sha256'}
            and isinstance(binding['path'], str) and Path(binding['path']).is_absolute()
            and isinstance(binding['sha256'], str) and len(binding['sha256']) == 64
            and all(c in '0123456789abcdef' for c in binding['sha256']),
            'An immutable input requires an absolute path and SHA-256.')
    require((expected is None or binding['sha256'] == expected)
            and sha(binding['path']) == binding['sha256'], 'A frozen diagnostic input changed.')
    return read(binding['path'])


def arena_source(protocol):
    source = exp.arena_source(protocol['baseline'], protocol['opponents'],
        protocol['banks']['screen']['nativeOffset'], 1024, protocol['baseline'], 'SCREENING')
    return dict(source, **legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'], arena=True, rival_ecology=ECOLOGY),
        frozenPurchase=dict(baseline=protocol['prior'], policy=protocol['purchasePolicy'],
            candidatePolicy=protocol['purchasePolicy'], incumbentPolicy=protocol['purchasePolicy']),
        cathedralTactics=dict(GATE))


def historical_inputs(protocol):
    audit = pinned(protocol['referenceAudit'], AUDIT)
    require(audit['passed'] is True and audit['qualificationPassed'] is True
            and audit['candidateSha256'] == G2 and audit['status'] == 'completed',
            'Historical inputs must come from the completed qualified47 audit.')
    original = protocol['historical']
    require(set(original) == {'source', 'rows'}
            and [row['index'] for row in original['rows']] == list(HISTORICAL),
            'Reproduce only the two predeclared historical candidate histories.')
    source = pinned(original['source'], HISTORICAL_SOURCE)
    bindings = [original['source']]
    for row in original['rows']:
        require(set(row) == {'index', 'pair', 'referenceReplay'}, 'Historical roles must be explicit.')
        pair, replay = pinned(row['pair']), pinned(row['referenceReplay'])
        bindings += [row['pair'], row['referenceReplay']]
        require(pair['index'] == replay['index'] == row['index']
                and replay['side'] == 'CANDIDATE' and replay['replayVerified'] is True
                and replay['historicalPairVerified'] is True
                and search.same_game(pair['candidate'], replay['game'])
                and all(replay[key] == pair[key] for key in
                        ('configuration', 'seed', 'seat', 'opponentIndices'))
                and 'cathedralTactics' not in pair and 'cathedralTactics' not in replay,
                'Original historical receipts must bind matching completed candidate games.')
    require(all(audit['artifactSha256'].get(item['path']) == item['sha256'] for item in bindings),
            'Every original source, pair and replay must be pinned by the qualified47 audit.')
    require('cathedralTactics' not in source and source['seed'] == 47000088
            and source['pairsPerConfiguration'] == 4096 and source['schemaVersion'] == 1
            and source['configurations'] == [exp.CONFIGURATION]
            and source['experimentalRulesTransfer'] == legacy.RULES_TRANSFER
            and sha(source['candidate']) == sha(source['incumbent']) == matched.ORDINARY
            and len(source['opponents']) == 4
            and all(sha(path) == matched.ORDINARY for path in source['opponents'])
            and source['frozenPurchase'].keys() == {'baseline', 'policy', 'candidatePolicy', 'incumbentPolicy'}
            and sha(source['frozenPurchase']['baseline']) == data.F0
            and sha(source['frozenPurchase']['candidatePolicy']) == G2
            and sha(source['frozenPurchase']['policy']) == sha(source['frozenPurchase']['incumbentPolicy']) == G1,
            'Historical regression retains the exact47 source, G2 candidate and original G1 rivals.')
    expected = data.bindings(source['expansionImitation']['candidateModel'], arena=True, rival_ecology=ECOLOGY)
    require(all(source[key] == value for key, value in expected.items())
            and sha(source['expansionImitation']['candidateModel']) == matched.EXPANSION,
            'Historical native E/T roles cannot be substituted.')
    return source


def validate_inputs(protocol):
    limits = dict(kind=KIND, nativeSourceCommit=NATIVE, workers=10, maximumSeconds=3600,
        maximumAttempts=1, maximumScientificTerminalGames=2048,
        maximumHistoricalTerminalReproductions=10, maximumTerminalExecutions=2058,
        diagnosticConfidence=.95, maximumFits=0, productionActivation=False,
        automaticNextCampaign=False, reselectionAllowed=False)
    require(all(type(protocol.get(key)) is type(value) and protocol[key] == value
                for key, value in limits.items())
            and protocol['replayIndices'] == list(REPLAYS),
            'Use the fixed1024-pair diagnostic, two historical gates and eight side replays once.')
    require(protocol['configuration'] == exp.CONFIGURATION
            and protocol['experimentalRulesTransfer'] == legacy.RULES_TRANSFER
            and sha(protocol['baseline']) == matched.ORDINARY
            and sha(protocol['expansionModel']) == matched.EXPANSION
            and sha(protocol['purchasePolicy']) == G2 and sha(protocol['prior']) == data.F0
            and protocol['opponents'] == [protocol['baseline']] * 4,
            'New science uses the exact same raw G2/E/T/M8/F0 in every seat.')
    receipt = pinned(protocol['nativeReceipt'], RECEIPT)
    pinned(protocol['sourceReview'], REVIEW)
    require(receipt['sourceCommit'] == NATIVE and receipt['humanDataIncluded'] is False
            and receipt['nativeGate'] == {'cathedralTactics': GATE},
            'Native implementation and reviewed fixed gate must match the frozen receipt.')
    runtime = Path(protocol['runtime'])
    require({str(p.relative_to(runtime)): sha(p) for p in runtime.rglob('*') if p.is_file()}
            == receipt['runtimePins'], 'The research native runtime differs from its implementation receipt.')
    require(sha(protocol['seedAudit']) == SEEDS, 'Use the audited frozen family50 ancestry.')
    audit = read(protocol['seedAudit'])
    bank = protocol['banks']['screen']
    require(audit['allIntersectionsZero'] is True and protocol['banks'] == audit['seedBanks']
            and set(protocol['banks']) == {'screen'} and bank['nativeOffset'] == 50000000
            and bank['count'] == 1024 and bank['seeds'] == exp.arena_seeds(50000000, 1024),
            'The sole fresh bank requires exactly the1024 predeclared family50 worlds.')
    exp.validate_banks({'screen': bank['seeds']})
    historical_inputs(protocol)


def commands(replay):
    return [row['chosen']['command'] for row in replay['decisions']]


def compare_historical(actual, reference):
    require(all(actual[key] == reference[key] for key in
                ('index', 'configuration', 'seed', 'seat', 'side', 'opponentIndices'))
            and search.same_game(actual['game'], reference['game'])
            and commands(actual) == commands(reference),
            'Historical disabled controller changed a terminal array or controlled canonical command.')
    return hashlib.sha256(json.dumps(commands(actual), separators=(',', ':')).encode()).hexdigest()


def verify_replay(source_path, pair_path, target, side, *, cathedral):
    source, pair = read(source_path), read(pair_path)
    replay, native, state, finished = [read(target / name) for name in
        ('replay.json', 'run.json', 'state.json', 'finished.json')]
    require(replay['replayVerified'] is True and replay['historicalPairVerified'] is True
            and replay['side'] == side.upper() and native['source'] == str(source_path)
            and native['recordedPair'] == str(pair_path) and native['workers'] == 1
            and native['signature'] == state['signature'] and state['phase'] == 'complete'
            and bool(finished['at']) and sha(target / 'source.json') == sha(source_path)
            and all(replay[key] == pair[key] for key in
                    ('index', 'configuration', 'seed', 'seat', 'opponentIndices'))
            and search.same_game(replay['game'], pair[side]),
            'Native replay must reproduce the exact completed pair side and canonical history.')
    expected = METADATA if cathedral else None
    require(all(item.get('cathedralTactics') == expected for item in (pair, replay, native, state))
            and (('cathedralDecisions' in replay) == cathedral),
            'Historical replay changed the declared Cathedral wrapper or legacy omission.')
    data.verify_overlays(source, target, [native, state], arena=True,
                         expected_expansion=matched.EXPANSION, rival_ecology=ECOLOGY)
    ecology.verify_purchase_roles(source, target, [native, state])
    models = {'candidate': source['candidate'], 'incumbent': source['incumbent'],
              **{f'opponent-{i}': path for i, path in enumerate(source['opponents'])}}
    for report in (native, state):
        legacy.verify_transferred_sources(source, report, target, models)
    return replay


def run_replay(protocol, source, pair, target, index, side, *, cathedral):
    require(not target.exists(), 'Each historical replay permits one attempt; preserve partial failures.')
    target.mkdir(parents=True)
    command = ecology.java_command(protocol, 'strategy-replay', source, target)
    command += ['--workers', '1', '--index', str(index), '--side', side, '--pair', str(pair)]
    ecology.run_child(protocol, command, target.with_suffix('.log'))
    return verify_replay(source, pair, target, side, cathedral=cathedral)


def report_stage(path, output, stage, payload, roots):
    report = dict(payload, stage=stage, protocolSha256=sha(path), qualificationPassed=False,
                  productionActivation=False, automaticNextCampaign=False, actualFits=0)
    report_path = output / f'{stage}-report.json'
    document(report_path, report)
    files = [report_path] + [p for root in roots for p in root.rglob('*') if p.is_file()]
    files += [root.with_suffix('.log') for root in roots if root.with_suffix('.log').is_file()]
    if stage == 'screen':
        files.append(output / 'screen-source.json')
    return exp.stage_result(output, stage, True, 'Bounded diagnostic completed; no qualification or activation.',
                            report, files)


def historical(protocol, path, output):
    historical_inputs(protocol)
    directory = output / 'historical'
    require(not directory.exists(), 'Historical regression has one complete attempt, without retries.')
    directory.mkdir(parents=True)
    receipts = []
    for row in protocol['historical']['rows']:
        target = directory / f'{row["index"]}-candidate'
        actual = run_replay(protocol, Path(protocol['historical']['source']['path']),
            Path(row['pair']['path']), target, row['index'], 'candidate', cathedral=False)
        digest = compare_historical(actual, pinned(row['referenceReplay']))
        receipts.append(dict(index=row['index'], side='candidate',
            referenceReplaySha256=row['referenceReplay']['sha256'], replaySha256=sha(target / 'replay.json'),
            controlledCommandsSha256=digest, controlledDecisions=len(commands(actual)),
            terminalExact=True, controlledCommandsExact=True))
    return report_stage(path, output, 'historical', dict(replays=receipts,
        historical=protocol['historical'], historicalTerminalReproductions=2, newWorlds=0), [directory])


def historical_ready(protocol, path, output):
    state = read(output / 'campaign-state.json')['stages']['historical']
    prior = controller.validate_result(output / 'results/historical.json', output)
    report = read(output / 'historical-report.json')
    require(state['status'] == prior['status'] == 'passed'
            and state['resultSha256'] == sha(output / 'results/historical.json')
            and prior['metrics'] == report and report['protocolSha256'] == sha(path)
            and report['historical'] == protocol['historical']
            and report['historicalTerminalReproductions'] == 2 and report['newWorlds'] == 0
            and [row['index'] for row in report['replays']] == list(HISTORICAL)
            and all(row['terminalExact'] is True and row['controlledCommandsExact'] is True
                    for row in report['replays']),
            'Screen requires both complete exact historical regressions and their unchanged receipts.')
    for requested, completed in zip(protocol['historical']['rows'], report['replays']):
        relative = f"historical/{requested['index']}-candidate/replay.json"
        target = output / relative
        require(completed['side'] == 'candidate'
                and completed['referenceReplaySha256'] == requested['referenceReplay']['sha256']
                and prior.get('artifacts', {}).get(relative) == completed['replaySha256']
                and sha(target) == completed['replaySha256'],
                'Historical gate requires both actual completed replay artifacts, not flags alone.')
        actual = read(target)
        require(compare_historical(actual, pinned(requested['referenceReplay']))
                == completed['controlledCommandsSha256']
                and len(commands(actual)) == completed['controlledDecisions'],
                'Historical gate replay commands or terminal evidence changed.')


def diagnostic_signal(metrics):
    return all(metric['count'] == 1024 and type(metric['lower95']) in (int, float)
               and math.isfinite(metric['lower95']) and metric['lower95'] > 0
               for metric in (metrics['winShareDifference'], metrics['rewardDifference']))


def replay_diagnostics(replay):
    rows = replay['cathedralDecisions']
    if replay['side'] == 'INCUMBENT':
        require(rows == [], 'The incumbent cannot invoke the candidate-only selector.')
    require(len({row['number'] for row in rows}) == len(rows), 'Replay tactical decisions must be unique.')
    expansions = [row for row in replay['decisions'] if row['snapshot']['decision'] == 'expand']
    if replay['side'] == 'CANDIDATE':
        require([row['number'] for row in rows] == [row['snapshot']['number'] for row in expansions]
                and [row['result']['command'] for row in rows] == [row['chosen']['command'] for row in expansions],
                'Candidate replay diagnostics must cover every actual expansion choice in order.')
    active = [row for row in rows if row['result']['activated']]
    overrides = [row for row in active if row['incumbent'] != row['result']['command']]
    for row in rows:
        result = row['result']
        require(0 <= result['retainedNonpassRoots'] <= min(16, result['legalNonpassRoots']) and result['resolvedLeaves'] <= 6400
                and result['stopEvaluations'] <= 80 and result['truncatedFollowupOptions'] >= 0,
                'Replay tactical search exceeded its frozen bounds.')
        if result['activated']:
            require(result['command'] in result['rootValues'] and row['incumbent'] in result['rootValues'],
                    'Activated search must retain the actual incumbent and offered chosen root.')
        else:
            require(result['command'] == row['incumbent'], 'A disabled gate changed its complete incumbent.')
    return dict(expansionCalls=len(rows), activations=len(active), overrides=len(overrides),
        attackToPass=sum(row['result']['command'] == 'pass' for row in overrides),
        attackToAttack=sum(row['result']['command'].startswith('attack ') for row in overrides),
        attackToPeaceful=sum(row['result']['command'].startswith('place ') for row in overrides),
        truncatedRoots=sum(row['result']['legalNonpassRoots']-row['result']['retainedNonpassRoots'] for row in active),
        truncatedFollowups=sum(row['result']['truncatedFollowupOptions'] for row in active),
        firstActionVictoriesStopped=sum(row['result']['firstActionVictoriesStopped'] for row in active))


def screen(protocol, path, output):
    historical_ready(protocol, path, output)
    source_path, directory = output / 'screen-source.json', output / 'screens/screen'
    require(not source_path.exists() and not directory.exists(), 'The fresh bank has one attempt, without top-ups.')
    source = arena_source(protocol)
    document(source_path, source)
    exp.native(protocol, 'arena', source_path, directory)
    metrics = legacy.verify_arena(protocol, source_path, directory)
    reports = [read(directory / name) for name in ('run.json', 'evaluation.json')]
    data.verify_overlays(source, directory, reports, arena=True,
                         expected_expansion=matched.EXPANSION, rival_ecology=ECOLOGY)
    require(all(report['cathedralTactics'] == METADATA for report in reports),
            'Arena metadata changed its candidate-only wrapper.')
    require({p.name for p in directory.glob('pair-*.json')} == {f'pair-{i}.json' for i in range(1024)},
            'The diagnostic requires every one of its1024 frozen pairs.')
    pairs = [read(directory / f'pair-{i}.json') for i in range(1024)]
    require(all(pair['cathedralTactics'] == METADATA for pair in pairs), 'A pair changed its wrapper binding.')
    replays = []
    replay_root = output / 'replays/screen'
    for index in REPLAYS:
        for side in ('candidate', 'incumbent'):
            target = replay_root / f'{index}-{side}'
            replay = run_replay(protocol, source_path, directory / f'pair-{index}.json', target,
                                index, side, cathedral=True)
            replays.append(dict(index=index, side=side, replaySha256=sha(target / 'replay.json'),
                                diagnostics=replay_diagnostics(replay)))
    return report_stage(path, output, 'screen', dict(metrics=metrics,
        diagnosticSignal=diagnostic_signal(metrics), completePairs=1024, scientificTerminalGames=2048,
        historicalTerminalReproductions=10, maximumTerminalExecutions=2058, replays=replays,
        diagnosticsCoverage='Four candidate histories; descriptive only, not1024-pair activation estimates.',
        horizonLimitation='Victories stop while settled losses and peaceful roots may continue once.'),
        [directory, replay_root])


def stage(protocol, path, output, name):
    require(name in STAGES, 'Only historical regression then the fixed diagnostic screen may execute.')
    validate_inputs(protocol)
    return historical(protocol, path, output) if name == 'historical' else screen(protocol, path, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'stage'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=STAGES)
    args = parser.parse_args()
    protocol = read(args.protocol)
    validate_inputs(protocol)
    matched.verify_execution(protocol)
    if args.command == 'preflight':
        require(not args.output.exists(), 'Preflight cannot reuse an execution attempt.')
        own = [row for row in controller.processes.sample_group(os.getpgrp()) if row.pid == os.getpid()]
        require(len(own) == 1 and own[0].rss_bytes > 0, 'The exact child requires external process sampling.')
        print(json.dumps(dict(passed=True, newGames=0, actualFits=0, externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    else:
        stage(protocol, args.protocol, args.output, args.stage)


if __name__ == '__main__':
    main()
