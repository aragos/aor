"""One prospective model-free token diagnostic against the frozen combined policy.

All 512 paired worlds are descriptive. No sign-dependent gate, candidate selection,
qualification, activation, automatic retry or subsequent campaign is permitted.
"""
import argparse
from collections import Counter
import json
from pathlib import Path

import adaptive_token_arena as arena
import automata_adaptive_token as components

exp, read, sha, document, require = arena.exp, arena.read, arena.sha, arena.exp.document, arena.require
STAGES = ('screen',)
KIND = 'token-minus-two-diagnostic-1'


def source(protocol):
    return components.arena_source(protocol, None, 'screen', control=True)


def validate_inputs(protocol):
    require(protocol['schemaVersion'] == 1 and protocol['kind'] == KIND
            and protocol['workers'] == 10 and protocol['maximumSeconds'] == 3600
            and protocol['maximumAttempts'] == 1 and protocol['maximumTerminalGames'] == 1024
            and protocol['maximumHistoricalReplays'] == 8 and protocol['confidence'] == .95
            and all(protocol[name] is False for name in ('qualificationPassed', 'productionActivation',
                'humanStrengthVerified', 'automaticNextCampaign', 'modelSelection')),
            'Use the fixed one-hour, ten-worker, 512-world descriptive diagnostic.')
    require(sha(protocol['baseline']) == arena.legacy.F1 and sha(protocol['expansionModel']) == components.E
            and protocol['expansionSha256'] == components.E
            and {name:sha(path) for name,path in protocol['frozenPurchase'].items()}
            == protocol['frozenPurchaseSha256'] == {'baseline':components.F0, 'policy':components.C}
            and protocol['configuration'] == exp.CONFIGURATION and protocol['dataVersion'] == 'ah1996-catalog-16'
            and protocol['experimentalRulesTransfer'] == arena.legacy.TRANSFER,
            'Keep exact ordinary F1, E expansion, C/F0 Purchase and one unchanged configuration.')
    require(exp.distribution_hashes(Path(protocol['runtime'])) == protocol['runtimeFiles']
            and protocol['runtimeTrainingJarSha256'] == sha(Path(protocol['runtime'])/'lib/training.jar'),
            'The reviewed evaluated minus-two runtime changed.')
    audit, bank = read(protocol['seedAudit']), protocol['banks']['screen']
    require(audit['allIntersectionsZero'] and set(protocol['banks']) == {'screen'}
            and protocol['banks'] == audit['seedBanks'] and bank['count'] == 512
            and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], 512)
            and bank['nativeOffset'] >= 33000000,
            'Use only the separate fresh bank; retain the adaptive learner screen reservations.')
    exp.validate_banks({'screen':bank['seeds']})
    require(read(protocol['source']) == source(protocol), 'Frozen diagnostic role ownership changed.')


def descriptive_report(protocol, pairs, metrics, replays):
    require(len(pairs) == 512 and len(replays) == 8, 'Complete all 512 pairs and eight canonical replays.')
    seats = Counter(str(pair['seat']) for pair in pairs)
    rivals = Counter(arena.MIXED[index] for pair in pairs
                     for seat,index in enumerate(pair['opponentIndices']) if seat != pair['seat'])
    return dict(schemaVersion=1, stage='screen', mechanicalVerificationPassed=True,
        qualificationPassed=False, productionActivation=False, humanStrengthVerified=False,
        automaticNextCampaign=False, modelSelection=False, confidence=.95,
        rawMetrics=metrics, controlledSeatCounts=dict(seats), actualRivalRoleCounts=dict(rivals),
        replays=replays, limitation=protocol['limitation'],
        summary='All predeclared minus-two versus E+T pairs and historical replays completed; marginal95 intervals are descriptive only.')


def stage(options):
    import token_minus_two_campaign as campaign
    protocol, output = read(options.protocol), options.output.resolve()
    campaign.verify_execution(protocol, options.protocol, output)
    validate_inputs(protocol)
    require(options.stage == 'screen' and not (output/'arena').exists(),
            'This diagnostic permits exactly one new arena attempt.')
    source_path = Path(protocol['source'])
    exp.native(protocol, 'arena', source_path, output/'arena')
    pairs, metrics = arena.verify_arena(protocol, source_path, output/'arena', protocol['banks']['screen'])
    replays = arena.replays(protocol, source_path, output/'arena', output/'replays', pairs)
    report = descriptive_report(protocol, pairs, metrics, replays)
    report['protocolSha256'] = sha(options.protocol)
    path = output/'screen-report.json'
    document(path, report)
    artifacts = [item for name in ('arena', 'replays') for item in (output/name).rglob('*') if item.is_file()]
    result = exp.stage_result(output, 'screen', True, report['summary'], report, [*artifacts, path])
    print(json.dumps(dict(status=result['status'], qualificationPassed=False, metrics=metrics)), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=STAGES, required=True)
    stage(parser.parse_args())
