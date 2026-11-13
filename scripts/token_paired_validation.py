"""Public validation probabilities are immutable before the only semantic label callback."""
from dataclasses import asdict
import json
import math
from pathlib import Path
from statistics import NormalDist

import purchase_policy as b
import token_paired_data as data
import token_paired_fit as fit
import token_paired_policy as policy


def seal_validation(fit_directory, original, current, dictionary, output, load_public):
    """The callback opens only public VALIDATION sidecars, after completed actor admission."""
    directory = Path(fit_directory)
    done = fit.completed(directory)
    b.require(done['operationalPassed'] is True, 'Only a completed changed fit may open validation features.')
    context = data.Context(**done['identity']['context'])
    context.bind(original, current, dictionary)
    output = Path(output)
    b.require(not output.exists(), 'Validation probability publication is once-only.')
    fit_sha = b.file_digest(directory/'finished.json')
    model_sha = b.file_digest(directory/'policy.json')
    model = data.FrozenActor((directory/'policy.json').read_bytes(), model_sha)
    roots = tuple(load_public())
    b.require(len(roots) == 128 and all(isinstance(root, data.PublicRoot) and
        root.context == context and root.record['split'] == 'VALIDATION' and
        root.record['index'] == 512+index for index, root in enumerate(roots)),
        'Validation requires every original128 public worlds in exact order and context.')
    b.require(sum(root.ready for root in roots) >= 64,
        'Public validation coverage requires at least64 READY branching roots.')
    b.require(sum(root.ready for root in roots) == done['identity']['publicCoverage']['validationReady'],
        'Validation support must equal the outcome-blind pre-fit coverage admission.')
    selected = policy.TokenPairedPolicy(model.model); behavior = policy.TokenPairedPolicy(current.model)
    entries = []
    with b.torch.no_grad():
        for root in roots:
            record = root.record
            values = None
            if record['status'] != 'MISSING':
                view = record['observation']
                old, _ = behavior([view]); new, _ = selected([view])
                b.require(max(abs(left-right) for left, right in zip(
                    old[0].exp().tolist(), record['currentProbabilities'])) <= 2e-12,
                    'Validation behavior probabilities must match the original current actor.')
                values = new[0].exp().tolist()
                data.probabilities(values, len(view['actions']), 'Frozen validation probabilities')
            entries.append(dict(index=record['index'], status=record['status'],
                publicSha256=root.sha256, requestSha256=record['requestSha256'],
                futureSeeds=record['futureSeeds'], proposedIndices=record['proposedIndices'],
                inclusionProbability=record['inclusionProbability'],
                currentProbabilities=record['currentProbabilities'], probabilities=values))
    b.require(fit.completed(directory) == done and b.file_digest(directory/'finished.json') == fit_sha and
        b.file_digest(directory/'policy.json') == model_sha,
        'The same completed actor and identity must survive probability generation.')
    receipt = dict(kind='token-paired-validation-probabilities-1', complete=True,
        context=asdict(context), fitFinishedSha256=fit_sha, modelSha256=model_sha,
        fitDirectory=str(directory.resolve()),
        originalModelSha256=original.sha256, currentModelSha256=current.sha256,
        semanticLabelsDecoded=False, rows=entries,
        counts={status: sum(row['status'] == status for row in entries)
            for status in ('READY', 'SINGLETON', 'MISSING')})
    b.atomic_json(output, receipt)
    return dict(path=str(output.resolve()), sha256=b.file_digest(output))


def evaluate_validation(sealed_subject, output, load_labels, *, final_fit_directory):
    """No model call or optimizer: labels are admitted only after the exact persisted seal."""
    data.exact(sealed_subject, ('path', 'sha256'), 'Validation probability subject')
    path = Path(sealed_subject['path']); data.sha(sealed_subject['sha256'], 'Validation seal')
    b.require(b.file_digest(path) == sealed_subject['sha256'], 'Validation probability seal changed.')
    seal = b.read_json(path)
    b.require(seal['kind'] == 'token-paired-validation-probabilities-1' and seal['complete'] is True and
        seal['semanticLabelsDecoded'] is False and len(seal['rows']) == 128 and
        seal['counts']['READY'] >= 64, 'The complete public probability barrier must precede labels.')
    done = fit.completed(seal['fitDirectory'])
    b.require(b.file_digest(Path(seal['fitDirectory'])/'finished.json') == seal['fitFinishedSha256'] and
        done['operationalPassed'] is True and done['outputModelSha256'] == seal['modelSha256'] and
        done['identity']['context'] == seal['context'] and
        done['identity']['publicCoverage']['validationReady'] == seal['counts']['READY'],
        'Validation effects require the same immutable completed actor/identity used to seal probabilities.')
    final = fit.completed(final_fit_directory)
    final_context = final['identity']['context']
    b.require(final['operationalPassed'] is True and final_context['iteration'] == 2 and
        all(final_context[key] == seal['context'][key] for key in
            ('scope_sha256', 'dictionary_sha256', 'dictionary_semantic_sha256', 'original_model_sha256')) and
        (b.file_digest(Path(final_fit_directory)/'finished.json') == seal['fitFinishedSha256']
            if seal['context']['iteration'] == 2 else
            final['identity']['predecessor']['finishedSha256'] == seal['fitFinishedSha256']),
        'Both fixed iterations must finish before either validation label cohort can open.')
    output = Path(output); b.require(not output.exists(), 'Validation evaluation is once-only.')
    labels = tuple(load_labels(dict(sealed_subject)))
    b.require(len(labels) == 128, 'Validation retains every original128 label/missing position.')
    per_origin = []
    for row, label in zip(seal['rows'], labels):
        if row['status'] != 'READY':
            b.require(label is None, 'Missing/singleton origins have no invented intervention return.')
            per_origin.append(dict(index=row['index'], status=row['status'], gain=None))
            continue
        data.exact(label, ('publicSha256', 'futureSeeds', 'actions'), 'Validation paired labels')
        b.require(label['publicSha256'] == row['publicSha256'] and
            label['futureSeeds'] == row['futureSeeds'] and
            len(label['actions']) == len(row['proposedIndices']),
            'Validation labels must bind original public probabilities, sibling matrix and streams.')
        rewards = []
        for index, action in zip(row['proposedIndices'], label['actions']):
            data.exact(action, ('actionIndex', 'rewards'), 'Validation action labels')
            b.require(action['actionIndex'] == index and len(action['rewards']) == 2,
                'Validation must retain both original streams for every prospectively included sibling.')
            rewards.append(tuple(b.number(value, 'Validation normalized reward', 0., 1.)
                for value in action['rewards']))
        estimates = tuple(math.fsum((row['probabilities'][index]-row['currentProbabilities'][index])*
            (reward[stream]-rewards[0][stream])/row['inclusionProbability']
            for index, reward in zip(row['proposedIndices'][1:], rewards[1:])) for stream in range(2))
        per_origin.append(dict(index=row['index'], status='READY',
            pairedFutureGains=estimates, gain=math.fsum(estimates)/2))
    b.require(b.file_digest(path) == sealed_subject['sha256'], 'Validation seal changed while decoding labels.')
    values = [row['gain'] for row in per_origin if row['gain'] is not None]
    mean = math.fsum(values)/len(values)
    variance = math.fsum((value-mean)**2 for value in values)/(len(values)-1)
    margin = NormalDist().inv_cdf(.975)*math.sqrt(variance/len(values))
    report = dict(kind='token-paired-validation-effect-1', probabilitySeal=sealed_subject,
        context=seal['context'], modelSha256=seal['modelSha256'], counts=seal['counts'],
        estimate=dict(mean=mean, lower=mean-margin, upper=mean+margin, origins=len(values),
            interval='two-sided-normal-95-independent-origin'), perOrigin=per_origin,
        optimizerSteps=0, modelForwards=0, selectedCheckpoint=False)
    b.atomic_json(output, report)
    return report
