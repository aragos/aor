#!/usr/bin/env python3
"""Finite four-feature target learning against frozen stronger policy lineups."""
import argparse
from pathlib import Path
import sys

import automata_experiments as exp
import automata_policy_search as search
import expansion_evolution as evolution
import expansion_target_arena as arena
import expansion_target_models as models

read,sha,document,require = exp.read,exp.sha,exp.document,exp.require
VERSION = 'expansion-target-search-1'
COUNTS = evolution.COUNTS
MAX_ARCHIVE = 9
F1 = '0c24593a456816c9b6616b6ade2aa7b74ac3c41f0cbd0745b5b912675caa835b'
E = '3b7f677595670972253e8f034fd052178237c1547017c321e33e770e3de176b9'
RUNTIME_JARS = {
    'ai-jvm.jar': 'aef5aeb7b1e6d96070f7e1a8b22b9e0fe9f16d7a0cde9c6b1293d4908acc3ec6',
    'core-jvm.jar': '4c4036bd18d9244e7ce24d58bd161bbb1373ad7815c5da027bbd541f42cc6f7f',
    'training.jar': '21d5698aa264e7d830a29945bfa5e98e6237ab825b5ebd37b9d3dfc13937a304',
}


def bundle_key(path,protocol):
    bundle = models.verify_bundle(path,protocol)
    return evolution.model_key(read(bundle['ordinary']['path']))


def archive_append(archive,paths,protocol):
    result,seen = list(archive),{bundle_key(path,protocol) for path in archive}
    for path in paths:
        key = bundle_key(path,protocol)
        if key not in seen:
            result.append(path)
            seen.add(key)
    require(len(result) <= MAX_ARCHIVE,'Target archive must remain within nine unique paired models.')
    return result


def source(protocol,bundle_path,bank):
    bundle = models.verify_bundle(bundle_path,protocol)
    origin = protocol['worldBanks'][bank]
    binding = models.bindings(protocol,bundle['ordinary']['path'],bundle['expansion']['path'],
                              'homogeneous' if bank == 'heldout' else 'mixed')
    specification = exp.arena_source(binding['incumbent'],binding['opponents'],origin['nativeOffset'],
                                    origin['count'],binding['candidate'],
                                    'CONFIRMATION' if bank in ('confirm','heldout') else 'SCREENING')
    return dict(specification,**arena.token.purchase.transfer_source(protocol),
        frozenPurchase=protocol['frozenPurchase'],expansionImitation=binding['expansionImitation'],
        tokenOrder=binding['tokenOrder'])


def run_arena(protocol,directory,bundle_path,bank):
    directory.mkdir(parents=True,exist_ok=False)
    source_path = directory/'source.json'
    document(source_path,source(protocol,bundle_path,bank))
    exp.native(protocol,'arena',source_path,directory/'arena')
    pairs,metrics = arena.verify_arena(protocol,source_path,directory/'arena',protocol['worldBanks'][bank])
    return metrics,pairs,source_path


def result(output,stage,passed,summary,metrics,paths):
    report = dict(kind=VERSION,stage=stage,summary=summary,metrics=metrics,
        learningMethod='bounded-whole-game-evolution',qualificationPassed=stage == 'heldout' and passed,
        productionActivation=False,humanStrengthVerified=False)
    path = output/(stage+'-report.json')
    document(path,report)
    return exp.stage_result(output,stage,passed,summary,metrics,[*paths,path])


def evolve(protocol,output):
    baseline,expansion = read(protocol['baseline']),read(protocol['expansionModel'])
    parents,archive,generations = [],[],[]
    any_elite = False
    for generation in range(4):
        root = output/f'generation-{generation}'
        root.mkdir(parents=True,exist_ok=False)
        ordinary_parents = [read(models.verify_bundle(path,protocol)['ordinary']['path']) for path in parents]
        proposals = models.coordinate.population(baseline,ordinary_parents,models.PROFILE,
            protocol['proposalSeeds'][generation],protocol['targetRulesVersion'])
        paths = [models.export_bundle(root/f'model-{row["candidateIndex"]}',row['model'],baseline,expansion)
                 for row in proposals]
        document(root/'proposals.json',dict(generation=generation,proposalSeed=protocol['proposalSeeds'][generation],
            parents=[dict(path=path,sha256=sha(path)) for path in parents],
            profile=dict(featureNames=list(models.FEATURES),**models.PROFILE.normalization(),bounds=list(models.PROFILE.bounds)),
            proposals=[dict({key:value for key,value in row.items() if key != 'model'},
                bundle=paths[row['candidateIndex']],bundleSha256=sha(paths[row['candidateIndex']])) for row in proposals]))
        if generation == 0:
            archive = [paths[0]]
        rows,reference = [],None
        for index,path in enumerate(paths):
            metrics,pairs,_ = run_arena(protocol,root/f'evaluation-{index}',path,f'generation-{generation}')
            if reference is None:
                require(all(search.same_game(pair['candidate'],pair['incumbent']) for pair in pairs),
                        'Exact E+T target anchor failed full gameplay parity.')
                reference = pairs
            else:
                arena.verify_common_baseline(reference,pairs)
            rows.append(dict(candidateIndex=index,bundle=path,bundleSha256=sha(path),metrics=metrics))
        winners = evolution.elites(rows)
        parents = [row['bundle'] for row in winners]
        archive = archive_append(archive,parents,protocol)
        any_elite = any_elite or bool(winners)
        receipt = dict(generation=generation,rows=rows,eliteIndices=[row['candidateIndex'] for row in winners],
                       archive=[dict(path=path,sha256=sha(path)) for path in archive])
        document(root/'receipt.json',receipt)
        generations.append(receipt)
        print(f'Target generation {generation+1}/4 complete: {len(winners)} elites; archive {len(archive)}.',flush=True)
    document(output/'archive.json',dict(anyNonAnchorElite=any_elite,
        models=[dict(path=path,sha256=sha(path)) for path in archive],generations=generations))
    return result(output,'evolve',any_elite,
        'Fixed target generations complete.' if any_elite else 'No positive generation elite; stop before fresh selection.',
        dict(generations=generations,archiveSize=len(archive)),
        [path for i in range(4) for path in (output/f'generation-{i}').rglob('*') if path.is_file()]+[output/'archive.json'])


def select_archive(protocol,output):
    archive = read(output/'archive.json')
    require(archive['anyNonAnchorElite'] and 2 <= len(archive['models']) <= MAX_ARCHIVE,
            'Selection requires a bounded archive and a positive-reward generation elite.')
    rows,reference = [],None
    for index,item in enumerate(archive['models']):
        path = item['path']
        require(sha(path) == item['sha256'],'Archived paired model receipt changed.')
        metrics,pairs,_ = run_arena(protocol,output/'selection'/f'candidate-{index}',path,'selection')
        if reference is None:
            require(bundle_key(path,protocol) == evolution.model_key(read(protocol['baseline']))
                    and all(search.same_game(pair['candidate'],pair['incumbent']) for pair in pairs),
                    'Target archive must begin with exact E+T anchor parity.')
            reference = pairs
        else:
            arena.verify_common_baseline(reference,pairs)
        rows.append(dict(candidateIndex=index,bundle=path,bundleSha256=item['sha256'],metrics=metrics))
    winner = evolution.selected(rows)
    document(output/'selected.json',dict(candidate=winner,rows=rows))
    return result(output,'select',winner is not None,
        'One target candidate frozen.' if winner else 'No dual-positive archive candidate; final banks unopened.',
        dict(candidate=winner,rows=rows),[path for path in (output/'selection').rglob('*') if path.is_file()]+[output/'selected.json'])


def final(protocol,output,stage):
    selection = read(output/'selected.json')
    candidate = selection['candidate']
    require(candidate is not None and evolution.selected(selection['rows']) == candidate
            and sha(candidate['bundle']) == candidate['bundleSha256'],'Final bank requires unchanged selected paired models.')
    if stage == 'heldout':
        confirmation = read(output/'confirm-report.json')['metrics']
        require(confirmation['passed'] and evolution.qualification(confirmation['estimates'],'confirm')[0]
                and confirmation['candidateBundleSha256'] == candidate['bundleSha256'],
                'Homogeneous robustness requires passed mixed confirmation of the exact candidate.')
    metrics,pairs,source_path = run_arena(protocol,output/stage,candidate['bundle'],stage)
    indices = [0,len(pairs)//3,2*len(pairs)//3,len(pairs)-1]
    replays = arena.replays(protocol,source_path,output/stage/'arena',output/'replays'/stage,pairs,indices)
    require(len(replays) == 8,'Every final gate needs eight exact historical replays.')
    passed,estimates = evolution.qualification(metrics,stage)
    payload = dict(passed=passed,candidateBundleSha256=candidate['bundleSha256'],estimates=estimates,replays=replays,
                   lineup='homogeneous-E+T' if stage == 'heldout' else 'mixed-F1-E-T-E+T')
    return result(output,stage,passed,f'{stage} dual99 percent gate {"passed" if passed else "rejected"}.',payload,
        [path for directory in (output/stage,output/'replays'/stage) for path in directory.rglob('*') if path.is_file()])


def validate_protocol(protocol):
    require(protocol['kind'] == VERSION and protocol['schemaVersion'] == 1
            and protocol['learningMethod'] == 'bounded-whole-game-evolution'
            and protocol['featureNames'] == list(models.FEATURES)
            and protocol['normalization'] == models.PROFILE.normalization()
            and protocol['coordinateBounds'] == list(models.PROFILE.bounds)
            and protocol['generations'] == 4 and protocol['populationSize'] == 8
            and protocol['sigmas'] == [.25,.5,1.,2.] and protocol['maximumProposalAttempts'] == 4096
            and protocol['maximumArchiveModels'] == 9 and protocol['initialModels'] == []
            and protocol['maximumSeconds'] == 21600 and protocol['maximumAttempts'] == 1
            and protocol['maximumTerminalGames'] == 29696 and protocol['maximumHistoricalReplays'] == 16
            and protocol['workers'] in (6,10) and protocol['productionActivation'] is False
            and protocol['automaticNextCampaign'] is False,
            'Target search must retain the exact finite four-coordinate protocol.')
    require(sha(protocol['baseline']) == F1 and sha(protocol['expansionModel']) == E,
            'Target search must preserve the exact qualified E+T components.')
    require(protocol['runtimeComponentSha256'] == RUNTIME_JARS
            and all(sha(Path(protocol['runtime'])/'lib'/name) == digest for name,digest in RUNTIME_JARS.items()),
            'Use the exact target-feature AI, engine and training runtime, including the new AI bytes.')
    baseline,expansion = read(protocol['baseline']),read(protocol['expansionModel'])
    require(all(model['weights'].get(name,0) == 0 for model in (baseline,expansion) for name in models.FEATURES),
            'The four new coordinates must begin at the exact zero anchor.')
    audit = read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and set(protocol['worldBanks']) == set(COUNTS)
            and len(protocol['proposalSeeds']) == 4,'Require every fresh world and proposal reservation.')
    values = []
    for name,count in COUNTS.items():
        bank = protocol['worldBanks'][name]
        require(bank == audit['seedBanks'][name] and bank['count'] == count
                and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'],count),
                'Target search must retain its exact independent audited bank.')
        values.extend(bank['seeds'])
    for index,seed in enumerate(protocol['proposalSeeds']):
        require(audit['seedBanks'][f'generation-{index}']['proposalSeeds'] == [seed],
                'Target proposal seed differs from its independent reservation.')
    values += protocol['proposalSeeds']
    require(len(values) == len(set(values)),'Target game/proposal streams must remain distinct.')
    require(Path(sys.executable).resolve() == Path(protocol['python']['executable'])
            and sys.version == protocol['python']['version'],'Frozen target interpreter changed.')
    for path,digest in protocol['frozenFiles'].items():
        require(sha(path) == digest,'Frozen target input changed: '+path)


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=('evolve','select','confirm','heldout'),required=True)
    options = parser.parse_args(arguments)
    protocol = read(options.protocol)
    import target_campaign
    output = options.output.resolve()
    target_campaign.verify_execution(protocol,options.protocol,output)
    validate_protocol(protocol)
    if options.stage == 'evolve':
        evolve(protocol,output)
    elif options.stage == 'select':
        select_archive(protocol,output)
    else:
        final(protocol,output,options.stage)


if __name__ == '__main__':
    main()
