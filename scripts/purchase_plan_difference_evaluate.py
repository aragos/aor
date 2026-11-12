"""Once-fixed TEST prediction barrier and descriptive paired-world evidence for family70.

Only the nominated critic is evaluated. Missing references stay missing; no-alternative READY
origins stay in policy comparisons. This module never optimizes, selects an epoch, or executes
an action. Policy contrasts concern one recorded public root, not deployed whole-game strength.
"""
from pathlib import Path
import statistics

import purchase_plan_difference_data as data
from purchase_plan_ranking import interval


def canonical(origin, index):
    plan = origin['plans'][index]
    data.require(plan['status'] == 'SUPPORTED', 'A chosen plan must have prospectively supported execution.')
    return index if plan['aliasOf'] is None else plan['aliasOf']


def summarize(corpus, cache, decisions, selected, frozen, labels):
    """One four-future mean per independent READY origin; never choose using observed returns."""
    a = cache.arrays
    data.require(len(decisions) == 256 and set(labels) == set(range(1792, 2048)),
                 'Retain the complete prospective TEST world bank.')
    rows, counts = [], {'READY': 0, 'MISSING': 0, 'INCUMBENT_UNSUPPORTED': 0}
    errors = []
    for origin, decision in zip(corpus['origins'][1792:], decisions, strict=True):
        i = origin['index']
        data.require(decision['originIndex'] == i and decision['status'] == origin['status'],
                     'Sealed public choice identity changed.')
        counts[origin['status']] += 1
        if origin['status'] != 'READY':
            data.require(not labels[i], 'Unavailable references cannot acquire invented outcomes.')
            continue
        observed = labels[i]
        canonical_ids = [p['index'] for p in origin['plans'] if p['status'] == 'SUPPORTED' and p['aliasOf'] is None]
        data.require(sorted(observed) == canonical_ids and all(len(v) == 4 for v in observed.values()),
                     'All canonical sibling returns must remain present.')
        values = {p: {key: statistics.fmean(v[k] for v in replicas) for k, key in enumerate(data.METRICS)}
                  for p, replicas in observed.items()}
        choice = canonical(origin, decision['selectedIndex'])
        old = canonical(origin, decision['frozen67Index'])
        pass_id = canonical(origin, 1) if origin['plans'][1]['status'] == 'SUPPORTED' else None
        differences = lambda reference: {key: values[choice][key] - values[reference][key] for key in data.METRICS}
        mixture = {key: statistics.fmean(values[p][key] for p in canonical_ids) for key in data.METRICS}
        start, stop = int(a['starts'][i]), int(a['stops'][i])
        predictions = {int(a['planIndex'][r]): dict(selected=float(selected[r]), frozen67=float(frozen[r]))
                       for r in range(start, stop)}
        paired = None
        if origin['informative']:
            per_plan = []
            for p in canonical_ids[1:]:
                target = values[p]['reward'] - values[0]['reward']
                per_plan.append(dict(selected=(predictions[p]['selected'] - predictions[0]['selected'] - target)**2,
                    frozen67=(predictions[p]['frozen67'] - predictions[0]['frozen67'] - target)**2, zero=target**2))
            paired = {key: statistics.fmean(v[key] for v in per_plan) for key in ('selected', 'frozen67', 'zero')}
            errors.append(paired)
        rows.append(dict(originIndex=i, informative=origin['informative'], selectedIndex=decision['selectedIndex'],
            selectedExecution=choice, frozen67Index=decision['frozen67Index'], frozen67Execution=old,
            intervention=choice != 0, selectedMinusG3=differences(0), selectedMinusFrozen67=differences(old),
            selectedMinusPass=None if pass_id is None else differences(pass_id),
            selectedMinusCanonicalMixture={key: values[choice][key] - mixture[key] for key in data.METRICS},
            pairedMse=paired, predictions=predictions, canonicalMeanReturns=values,
            canonicalFourFutureReturns=observed))
    data.require(counts['READY'] >= 160 and len(rows) == counts['READY'],
                 'Public READY TEST admission must precede labels; do not refill.')
    contrasts = {}
    for contrast in ('selectedMinusG3', 'selectedMinusFrozen67', 'selectedMinusPass', 'selectedMinusCanonicalMixture'):
        contrasts[contrast] = {key: interval(r[contrast][key] for r in rows if r[contrast] is not None) for key in data.METRICS}
    paired_mse = {key: interval(v[key] for v in errors) for key in ('selected', 'frozen67', 'zero')}
    for reference in ('frozen67', 'zero'):
        paired_mse['selectedMinus' + reference[0].upper() + reference[1:]] = interval(v['selected'] - v[reference] for v in errors)
    primary = contrasts['selectedMinusG3']['reward']
    return dict(kind='purchase-plan-difference-test-report-1', statusCounts=counts, readyOrigins=len(rows),
        informativeOrigins=len(errors), noAlternativeOrigins=len(rows)-len(errors),
        interventions=sum(r['intervention'] for r in rows), rows=rows, pairedMse=paired_mse, **contrasts,
        primaryDenominator='all READY TEST origins, including no-alternative and unchanged selections',
        secondaryDenominator='supported PASS and original G3 reference', missingTreatment='MISSING; no zero imputation',
        mixture='equal unique supported canonical executions within each READY origin, including G3',
        interval='unadjusted normal95 across whole-origin four-future paired means',
        futureResearchGate=primary['lower95'] is not None and primary['lower95'] > 0,
        strengthQualification=False, runtimeActivation=False, actorFits=0)


def selection_subject(selection_path, corpus_path):
    selection_path, corpus_path = Path(selection_path).resolve(strict=True), Path(corpus_path).resolve(strict=True)
    selected, corpus = data.read(selection_path), data.read(corpus_path)
    data.verify_corpus(corpus)
    data.require(selected['kind'] == 'purchase-plan-paired-difference-selection-1'
        and selected['warmCheckpointSha256'] == data.CHECKPOINT and selected['dictionarySha256'] == data.DICTIONARY
        and selected['sourceSha256'] == corpus['sourceSha256'] and selected['corpusSha256'] == data.sha(corpus_path)
        and selected['testInspected'] is False and selected['testPermitted'] == (not selected['unchanged']),
        'Only the completed once-fixed validation nomination for this corpus is admitted.')
    if selected['unchanged']:
        data.require(selected['arm'] == 'unchanged' and selected['epoch'] == 0
            and selected['modelSha256'] == data.CHECKPOINT, 'Unchanged selection must preserve original67 exactly.')
    else:
        data.require(selected['arm'] in ('head', 'full') and type(selected['epoch']) is int and 1 <= selected['epoch'] <= 20,
                     'TEST cannot select or replace the validation-nominated learned model.')
    return selected, corpus


def evaluate(selection_path, corpus_path, output, guard, *, checkpoint, dictionary):
    """Persist public predictions before the only semantic TEST label pass; unchanged67 skips it."""
    selection_path, corpus_path, output = Path(selection_path).resolve(strict=True), Path(corpus_path).resolve(strict=True), Path(output)
    selected, corpus = selection_subject(selection_path, corpus_path)
    data.require(not output.exists(), 'One TEST evaluation only; no retries, thresholds or checkpoint reselection.')
    output.mkdir(parents=True)
    if selected['unchanged']:
        result = dict(kind='purchase-plan-difference-test-skipped-1', reason='UNCHANGED67_VALIDATION_WINNER',
            selectionSha256=data.sha(selection_path), modelSha256=data.CHECKPOINT,
            testFeaturesDecoded=False, testLabelsDecoded=False, futureResearchGate=False,
            strengthQualification=False, runtimeActivation=False)
        data.document(output / 'report.json', result)
        return result
    import numpy as np
    import torch
    import purchase_plan_difference_fit as fit
    data.require(torch.get_num_threads() == torch.get_num_interop_threads() == 1
        and torch.are_deterministic_algorithms_enabled() and not torch.backends.cuda.matmul.allow_tf32
        and not torch.backends.cudnn.allow_tf32, 'TEST uses the preflighted deterministic one-thread FP32 owner.')
    selection_sha, corpus_sha = data.sha(selection_path), data.sha(corpus_path)
    model_path = data.relative(selection_path.parent, selected['modelFile'])
    data.require(data.sha(model_path) == selected['modelSha256'], 'Validation-selected tensor bytes changed.')
    checkpoint, dictionary, names, original = fit.frozen.load_subjects(checkpoint, dictionary)
    state = torch.load(model_path, map_location='cpu', weights_only=True)
    model = fit.make_model(state, selected['arm'], 'cpu')
    for parameter in model.parameters():
        parameter.requires_grad_(False)
    cache = data.build_cache(corpus_path, output / 'features', 'TEST_FEATURES', guard)
    data.require(cache.feature_names == names, 'TEST dictionary cannot change the selected critic inputs.')
    worlds = [o['index'] for o in corpus['origins'][1792:] if o['status'] == 'READY']
    predictions = fit.predict(model, cache, worlds, 'cpu', guard)
    reference = fit.predict(original, cache, worlds, 'cpu', guard)
    decisions = data.choices(cache, corpus, predictions, reference)
    np.save(output / 'selected.npy', predictions, allow_pickle=False)
    np.save(output / 'frozen67.npy', reference, allow_pickle=False)
    data.document(output / 'decisions.json', decisions)
    descriptor = dict(kind='purchase-plan-difference-predictor-1', checkpointSha256=selected['modelSha256'],
        warmCheckpointSha256=data.CHECKPOINT, dictionarySha256=data.DICTIONARY,
        selectionSha256=selection_sha, corpusSha256=corpus_sha,
        architecture=dict(inputs=7244, hidden=64, activation='tanh', output='sigmoid', dtype='float32'),
        inference=dict(device='cpu', threads=1, torchVersion=torch.__version__,
            exactBypasses='authoritative Float64 constants', aliases='copy canonical execution value'),
        modelOwnerSha256=data.sha(fit.benchmark.__file__), predictorOwnerSha256=data.sha(fit.__file__))
    data.document(output / 'predictor.json', descriptor)
    data.require(data.sha(model_path) == selected['modelSha256'] and data.sha(selection_path) == selection_sha
        and data.sha(corpus_path) == corpus_sha, 'Model/corpus subjects changed before the TEST barrier.')
    receipt = dict(kind='purchase-plan-difference-test-predictions-1',
        selection=dict(path=str(selection_path), sha256=selection_sha), predictionsComplete=True, semanticLabelsDecoded=False,
        corpusSha256=corpus_sha, cacheSha256=data.sha(cache.directory / 'cache.json'),
        modelSha256=selected['modelSha256'], dictionarySha256=data.DICTIONARY,
        predictorDescriptorSha256=data.sha(output / 'predictor.json'), testIds=list(range(1792, 2048)),
        files={name: data.sha(output / name) for name in ('selected.npy', 'frozen67.npy', 'decisions.json')})
    data.document(output / 'predictions.json', receipt)
    guard()
    labels = data.test_labels(corpus_path, cache, output / 'predictions.json')
    result = summarize(corpus, cache, decisions, predictions, reference, labels)
    result.update(selectionSha256=selection_sha, modelSha256=selected['modelSha256'], corpusSha256=corpus_sha,
        predictionsSha256=data.sha(output / 'predictions.json'), semanticLabelsDecoded=True)
    data.document(output / 'report.json', result)
    return result
