"""One prospective eight-tail token experiment; frozen basis, labels and final gates."""
import argparse
import json
from pathlib import Path
import subprocess

import automata_adaptive_token as previous

arena, exp, evolution, controller = previous.arena, previous.exp, previous.evolution, previous.controller
read, sha, document, require = previous.read, previous.sha, previous.document, previous.require
KIND = 'token-eight-tail-campaign-1'
CONTINUATION = 'ARENA_OVERLAYS_EIGHT_TAILS_1'
BASELINE = 'b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4'
EXPANSION = 'feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'
INITIALIZER = 'b3c32299854068bacdb9ab34d29cdea478bd185dc8664635d815b746d62dfb3c'
BASIS_IDENTITY = 'b532aaade82c798b8b338bebaa8d46f258f9c4e8cec7e6868889e02611dc56a9'
COUNTS = dict(training=512, validation=128, screen=512, confirm=2048, heldout=4096)
STAGES = previous.STAGES
CEILINGS = (21600, 600, 1800, 3600, 7200)
REPLAY_ORIGINS = [0, 170, 341, 511, 512, 555, 598, 639]
FIT = dict(ridgePenalties=[.0001, .001, .01, .1, 1.], maxSeconds=600.)
SCREEN = previous.SCREEN


def collection_source(protocol):
    bindings = arena.bindings(protocol, 'collect')
    source = exp.arena_source(protocol['baseline'], bindings['opponents'],
        protocol['banks']['training']['nativeOffset'], 512, protocol['baseline'], 'SCREENING')
    return dict(source, **previous.legacy.purchase.transfer_source(protocol),
        frozenPurchase=protocol['frozenPurchase'], expansionImitation=bindings['expansionImitation'],
        tokenOrder=bindings['tokenOrder'], trainingRoots=512, validationRoots=128,
        continuationReplicates=8, continuationProtocol=CONTINUATION, replayOrigins=REPLAY_ORIGINS)


def validate_inputs(protocol):
    require(protocol['kind'] == KIND and protocol['schemaVersion'] == 1
            and protocol['workers'] == 6 and protocol['maximumSeconds'] == 21600
            and protocol['maximumAttempts'] == 1 and protocol['screenRule'] == SCREEN
            and protocol['qualificationConfidence'] == .99
            and protocol['continuationProtocol'] == CONTINUATION
            and protocol['continuationReplicates'] == 8 and protocol['trainingRoots'] == 512
            and protocol['validationRoots'] == 128 and protocol['replayOrigins'] == REPLAY_ORIGINS
            and not protocol['productionActivation'] and not protocol['automaticNextCampaign'],
            'Require the exact six-worker, six-hour, prospective eight-tail experiment.')
    require(sha(protocol['baseline']) == protocol['baselineSha256'] == BASELINE
            and sha(protocol['expansionModel']) == protocol['expansionSha256'] == EXPANSION
            and {key:sha(path) for key,path in protocol['frozenPurchase'].items()}
                == protocol['frozenPurchaseSha256'] == {'baseline':previous.F0, 'policy':previous.C}
            and sha(protocol['initializer']) == INITIALIZER
            and sha(protocol['originalBasisIdentity']) == BASIS_IDENTITY,
            'Freeze qualified M8 ordinary/expansion, C64/F0 and the original unchanged zero-head basis.')
    require(read(protocol['fitConfig']) == FIT and protocol['diagnosticPrefixes'] == [2, 4]
            and protocol['diagnosticPrefixesAffectSelection'] is False
            and protocol['maximumTerminalGames'] == 54912
            and protocol['maximumHistoricalArenaReplays'] == 24
            and protocol['maximumHistoricalOriginReplays'] == 8
            and protocol['maximumHistoricalTerminalBranches'] == 512 and protocol['heapMiB'] == 1536,
            'Keep all eight-tail labels, five ridge penalties, diagnostic-only prefixes and finite game caps.')
    require(protocol['dataVersion'] == 'ah1996-catalog-16'
            and protocol['experimentalRulesTransfer'] == previous.legacy.TRANSFER
            and protocol['sourceRulesVersion'] == previous.legacy.TRANSFER['sourceRulesVersion']
            and protocol['targetRulesVersion'] == previous.legacy.TRANSFER['targetRulesVersion']
            and exp.distribution_hashes(Path(protocol['runtime'])) == protocol['runtimeFiles']
            and sha(Path(protocol['scripts'])/'token_order_metadata.py') == protocol['metadataValidatorSha256'],
            'Explicit rules transfer, runtime and metadata-verifier identities changed.')
    audit = read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and set(protocol['banks']) == set(COUNTS),
            'All five prospective banks must be freshly audited, including unused final reservations.')
    streams = {}
    for name,count in COUNTS.items():
        bank = protocol['banks'][name]
        require(bank == audit['seedBanks'][name] and bank['count'] == count
                and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], count),
                'A declared bank differs from its exact audited world list.')
        streams[name] = bank['seeds']
        if name in ('training', 'validation'):
            tails = [(seed<<3)^replicate^0x6f20a5319d for seed in bank['seeds'] for replicate in range(8)]
            ordinals = [seed^0x2e59c17 for seed in bank['seeds']]
            require(bank['continuationSeeds'] == tails and bank['proposalSeeds'] == ordinals,
                    'All eight full-tail streams and original ordinal streams must be reserved.')
            streams[name+'-tails'], streams[name+'-ordinals'] = tails, ordinals
    require(protocol['banks']['validation']['nativeOffset'] == protocol['banks']['training']['nativeOffset']+1,
            'Native training and validation origin offsets must be adjacent.')
    exp.validate_banks(streams)
    require(sum(map(len, streams.values())) == 13056, 'The complete prospective stream budget changed.')


def numerical_child(protocol, output, operation):
    import adaptive_token_environment as environment
    environment.verify_numerical(protocol['numerical'])
    import token_order_eight_tail as fitter
    directory = output/'collection'
    bindings = fitter.InputBindings(str(directory), sha(protocol['collectionSource']),
        sha(directory/'dataset.json'), sha(directory/'finished.json'), protocol['initializer'],
        INITIALIZER, fitter.b.digest(read(protocol['initializer'])), protocol['originalBasisIdentity'], BASIS_IDENTITY)
    if operation == 'fit-child':
        document(output/'fit-inputs.json', fitter.asdict(bindings))
        fitter.fit(bindings, output/'fit', fitter.ridge.FitConfig())
        return
    model, rows, provenance = fitter.fresh_collection(bindings)
    source, native, dataset = [read(directory/name) for name in ('source.json','run.json','dataset.json')]
    require(source == collection_source(protocol) and native['workers'] == 6
            and native['sourceSha256'] == sha(protocol['collectionSource'])
            and provenance['metadataValidatorSha256'] == protocol['metadataValidatorSha256'],
            'Completed collection differs from the predeclared eight-tail source/runtime ownership.')
    arena.verify_overlay(protocol, source, directory, (native,dataset), collection=True)
    require({path.name for path in directory.glob('origin-*.json')} == {f'origin-{i}.json' for i in range(640)},
            'Require exact prospective coverage, including every skipped root.')
    document(output/'collection-validation.json', dict(provenance=provenance,
        collected={split:sum(row['split']==split for row in rows) for split in ('TRAINING','VALIDATION')},
        initializerSha256=INITIALIZER, initializedModelDigest=fitter.b.digest(model)))


def run_numerical(protocol, protocol_path, output, operation):
    command = [protocol['numerical']['executable'],'-E','-s','-B',
        str(Path(protocol['scripts'])/'automata_token_eight_tail.py'),operation,
        '--protocol',str(protocol_path),'--output',str(output)]
    with (output/(operation+'.log')).open('x') as log:
        child = subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,
                               timeout=min(exp.remaining_seconds(),600),check=False)
    require(child.returncode == 0, 'Numerical validation/fit failed; no retry or alternate learner.')


def collect(protocol, protocol_path, output):
    require(read(protocol['collectionSource']) == collection_source(protocol)
            and not (output/'collection').exists(), 'One original collection attempt only.')
    exp.native(protocol,'token-order-collect',protocol['collectionSource'],output/'collection',
               extra=('--workers','6'))
    run_numerical(protocol,protocol_path,output,'validate-collection')
    previous.run_reproduction(protocol,output)
    reproduced = previous.verify_reproduction(protocol,output)
    check = read(output/'collection-validation.json')
    passed = min(check['collected'].values()) >= 2
    artifacts = [path for name in ('collection','collection-reproduction')
                 for path in (output/name).rglob('*') if path.is_file()]
    return previous.stage_receipt(protocol,protocol_path,output,'collect',passed,
        'All640 prospective origins and eight complete historical origin reproductions verified.',
        dict(check,reproduction=reproduced),artifacts+[output/'collection-validation.json'])


def verify_fit(protocol, output):
    directory = output/'fit'
    report, finished, identity = [read(directory/name) for name in ('fit.json','finished.json','identity.json')]
    require(report['kind'] == identity['kind'] == finished['kind'] == 'token-order-fresh-eight-tail-ridge-1'
            and finished['reportSha256'] == sha(directory/'fit.json')
            and report['identitySha256'] == sha(directory/'identity.json')
            and identity['config'] == FIT and identity['inputs'] == read(output/'fit-inputs.json')
            and identity['source'] == read(output/'collection-validation.json')['provenance']
            and identity['primaryContinuationReplicates'] == report['primaryContinuationReplicates'] == 8
            and not identity['diagnosticPrefixesAffectSelection'] and not report['validationPreviouslyInspected'],
            'Fit must retain exact fresh eight-tail inputs, unchanged basis/grid and primary selection semantics.')
    for name,digest in finished['artifacts'].items():
        require(Path(name).name == name and sha(directory/name) == digest,'A completed fit artifact changed.')
    original = read(protocol['initializer'])
    fits = report['fits']
    require([row['ridgePenalty'] for row in fits] == FIT['ridgePenalties'], 'The fixed ridge grid changed.')
    for index,row in enumerate(fits):
        model = read(directory/f'model-{index}.json')
        require(row == read(directory/f'fit-{index}.json') and row['index'] == index
                and row['modelSha256'] == sha(directory/f'model-{index}.json')
                and len(model['outputWeights']) == 64
                and {key:value for key,value in model.items() if key!='outputWeights'}
                    == {key:value for key,value in original.items() if key!='outputWeights'},
                'Only the original64 output weights may change in the five declared models.')
    eligible = [row for row in fits if row['validation']['pairedMse'] < report['initializerValidation']['pairedMse']]
    chosen = min(eligible,key=lambda row:(row['validation']['pairedMse'],-row['ridgePenalty'])) if eligible else None
    value = chosen['validation'] if chosen else None
    admitted = bool(value and value['observedMeanDifference'] > 0 and value['overrides'] >= 8
                    and value['positiveOrigins'] >= 4 and value['largestPositiveFraction'] <= .5)
    require(report['selected'] == chosen and report['scalingDiagnosticPassed'] == admitted,
            'Selection and original positive-policy scaling gate must use eight-tail validation only.')
    selection = read(directory/'selection.json')
    diagnostics = read(directory/'prefix-diagnostics.json')
    require(selection['selected'] == chosen and selection['scalingDiagnosticPassed'] == admitted
            and not selection['prefixDiagnosticsUsed'] and not diagnostics['selectionAffected']
            and diagnostics['selectionSha256'] == sha(directory/'selection.json')
            and set(diagnostics['prefixes']) == {'2','4'}, 'Prefix diagnostics cannot replace primary selection.')
    if chosen:
        require(sha(directory/'candidate.json') == report['candidateSha256'] == chosen['modelSha256'],
                'The frozen candidate differs from the sole eight-tail selected model.')
    else:
        require(not (directory/'candidate.json').exists(), 'A rejected selection cannot produce a candidate.')
    return report, admitted


def fit(protocol, protocol_path, output):
    require(not (output/'fit').exists(), 'One fixed ridge fit only, without retries.')
    run_numerical(protocol,protocol_path,output,'fit-child')
    report, admitted = verify_fit(protocol,output)
    selection = dict(admitted=admitted,fitReportSha256=sha(output/'fit/fit.json'),productionActivation=False)
    if admitted:
        model = evolution.freeze_copy(output/'fit/candidate.json',output/'selected/token-order.json')
        selection.update(model=str(model),modelSha256=sha(model))
        for stage in ('screen','confirm','heldout'):
            document(output/(stage+'-source.json'),previous.arena_source(protocol,model,stage))
    document(output/'selection.json',selection)
    artifacts = [path for name in ('fit','selected') for path in (output/name).rglob('*') if path.is_file()]
    artifacts += list(output.glob('*-source.json'))+[output/'selection.json',output/'fit-inputs.json']
    return previous.stage_receipt(protocol,protocol_path,output,'fit',admitted,
        'Eight-tail validation selection and original scaling gate completed; no prefix fallback.',selection,artifacts)


def evaluate(protocol, protocol_path, output, stage):
    report, admitted = verify_fit(protocol,output)
    selection = read(output/'selection.json')
    require(admitted and selection['admitted'] and selection['fitReportSha256'] == sha(output/'fit/fit.json')
            and selection['modelSha256'] == report['candidateSha256'] == sha(selection['model']),
            'Every fresh arena uses the exact learner frozen before any screen outcomes.')
    if stage != 'screen':
        earlier = 'screen' if stage == 'confirm' else 'confirm'
        prior = read(output/(earlier+'-report.json'))['metrics']
        require(prior['modelSha256'] == selection['modelSha256'] and prior['learnerPassed']
                and (previous.screen_passes(prior['rawMetrics']) if stage == 'confirm'
                     else previous.final_passes(protocol,prior['rawMetrics'],'confirm')[0]),
                'Fresh final worlds require the exact preceding numerical gate and frozen candidate.')
    pairs, metrics, replays = previous.evaluate(protocol,output,selection,stage)
    passed, estimates = ((previous.screen_passes(metrics),metrics) if stage=='screen'
                         else previous.final_passes(protocol,metrics,stage))
    artifacts = [path for root in (output/stage,output/'replays'/stage) for path in root.rglob('*') if path.is_file()]
    return previous.stage_receipt(protocol,protocol_path,output,stage,passed,
        'Frozen learner completed its prospective paired bank and eight historical side replays.',
        dict(modelSha256=selection['modelSha256'],rawMetrics=metrics,estimates=estimates,learnerPassed=passed,replays=replays),artifacts)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('stage','validate-collection','fit-child'))
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=STAGES)
    options=parser.parse_args()
    protocol,output=read(options.protocol),options.output.resolve()
    if options.command!='stage':
        numerical_child(protocol,output,options.command)
        return
    import token_eight_tail_campaign as campaign
    campaign.verify_execution(protocol,options.protocol,output)
    validate_inputs(protocol)
    previous.require_previous(options.protocol,output,options.stage)
    operation={'collect':collect,'fit':fit}.get(options.stage)
    receipt=operation(protocol,options.protocol,output) if operation else evaluate(protocol,options.protocol,output,options.stage)
    print(json.dumps(dict(stage=options.stage,status=receipt['status'])),flush=True)


if __name__=='__main__':
    main()
