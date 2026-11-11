"""One warm64 versus fresh64 fit; validation selects, final test opens once.

Only the selected critic and unchanged original fresh64 are evaluated on test worlds. This
measures prediction under fixed G2 play, never action quality or playing strength.
"""
from dataclasses import asdict
from pathlib import Path

import torch

import purchase_baseline_pilot as matched
import purchase_strong_data as strong
import purchase_strong_value_data as contract
import purchase_value_benchmark as benchmark
import purchase_value_campaign as environment
import purchase_value_data as data


def selected_arm(receipts):
    data.require(set(receipts) == {'warm', 'fresh'} and
                 all(not r['testInspected'] for r in receipts.values()),
                 'Select only both completed validation-only fits.')
    return min(('warm', 'fresh'), key=lambda name: receipts[name]['best']['validationMse'])


def run(protocol, output, guard):
    output = Path(output)
    environment.verify_environment(data.read(protocol['gpuEnvironment']))
    benchmark.configure()
    data.require(torch.cuda.is_available(), 'The declared value study requires the existing CUDA device.')
    device = torch.device('cuda:0')
    corpus = data.read(output/'corpus.json');contract.verify_declaration(corpus)
    config = contract.config(protocol['banks'])
    data.require(asdict(config) == protocol['fitConfig'], 'Frozen optimizer and seed contract changed.')
    value = output/'value';value.mkdir()
    data.build_cache(output,value/'cache',guard,corpus_validator=contract.verify_declaration,
                     model_path=protocol['purchasePolicy'],model_sha=strong.G2)
    cache = data.Cache(value/'cache')
    fits = value/'fits';fits.mkdir()
    data.require(data.sha(protocol['critic']) == matched.CRITIC, 'The original fresh64 comparator changed.')
    original = torch.load(protocol['critic'],map_location='cpu',weights_only=True)
    models, receipts = {}, {}
    for name, seed in [('warm',config.smallSeed),('fresh',config.largeSeed)]:
        guard()
        models[name], receipts[name] = benchmark.fit_one(name,(64,),seed,cache,corpus,fits,
            config,guard,device,initial_state=original if name == 'warm' else None)
    selected = selected_arm(receipts)
    selection = dict(kind=contract.KIND,arm=selected,fits=receipts,
        selectedSha256=receipts[selected]['modelSha256'],testInspected=False,
        selection='Lowest validation whole-game MSE, earlier epoch ties, warm arm ties.',
        warmInitialSha256=matched.CRITIC,warmOptimizer='Fresh Adam; no original moments.',
        config=asdict(config),qualificationPassed=False,productionActivation=False)
    data.document(fits/'selected.json',selection)
    # No test prediction is possible before the immutable selection receipt is written.
    data.require(all(data.sha(fits/(name+'-best.pt')) == row['modelSha256']
                 for name,row in receipts.items()), 'Validation-selected weights changed before final testing.')
    baseline = benchmark.Critic((64,),0).to(device)
    baseline.load_state_dict(original,strict=True)
    games = [g['id'] for g in corpus['games'] if g['split'] == 'test']
    data.require(len(games) == 512, 'Exactly the prospectively sealed 512 test worlds may open.')
    predictions = {name:benchmark.predict(model,cache,games,device,guard,
        maximum=config.maximumDenseRows) for name,model in [('selected',models[selected]),('original',baseline)]}
    errors = {name:benchmark.per_game_mse(values,cache,games) for name,values in predictions.items()}
    metrics = {name:dict(overall=benchmark.group_metrics(values,cache,games),
        byStage={stage:benchmark.group_metrics(values,cache,games,cache.arrays['stage'] == index)
                 for index,stage in enumerate(('purchase-shipping','purchase-advances'))},
        byEpoch={str(epoch):benchmark.group_metrics(values,cache,games,cache.arrays['epoch'] == epoch)
                 for epoch in (1,2,3)}) for name,values in predictions.items()}
    comparison = benchmark.paired_bootstrap(errors['selected']-errors['original'],
        config.bootstrapSeed,config.bootstrapReplicates)
    per_game = [dict(id=g,gameSeed=corpus['games'][g]['gameSeed'],
        mse={name:float(values[i]) for name,values in errors.items()}) for i,g in enumerate(games)]
    data.document(value/'test-game-metrics.json',per_game)
    report = dict(kind=contract.KIND,selectedArm=selected,selectedSha256=selection['selectedSha256'],
        originalSha256=matched.CRITIC,selectionSha256=data.sha(fits/'selected.json'),testGames=512,
        models=metrics,primaryComparison=comparison,testNeverUsedForSelection=True,
        nonselectedTestEvaluations=0,gameMetricsSha256=data.sha(value/'test-game-metrics.json'),
        qualificationPassed=False,productionActivation=False,actorTraining=False,humanStrengthVerified=False,
        limitation='Prediction of terminal reward under fixed G2 play; not evidence of useful action ranking or stronger play.')
    data.document(value/'report.json',report)
    guard()
    data.document(value/'finished.json',dict(passed=True,reportSha256=data.sha(value/'report.json'),
        selectedSha256=data.sha(fits/'selected.json'),actualFits=2,newGames=0,
        artifacts={str(p.relative_to(value)):data.sha(p) for p in value.rglob('*') if p.is_file()}))
    return report
