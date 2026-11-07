"""Compare one frozen compact fit and P1 on a new, complete F-continuation collection.

Earlier cycle-1 validation is development evidence for this corrective experiment. Only this
prospectively reserved collection supplies its local progression gate; no model is refitted here.
"""
import argparse
from pathlib import Path

import torch

import card_advantage as advantage
import card_advantage_data as data
import card_advantage_evaluation as evaluation
import card_advantage_train as fitting
import purchase_policy as common


def paired_selected(candidate, reference):
    """Pair equally weighted original worlds, retaining fallback choices with zero return."""
    left = {row['origin']: row['meanSelectedAdvantage'] for row in candidate['perOrigin']}
    right = {row['origin']: row['meanSelectedAdvantage'] for row in reference['perOrigin']}
    advantage.require(len(left) == len(candidate['perOrigin'])
                      and len(right) == len(reference['perOrigin']) and left.keys() == right.keys(),
                      'Local comparisons require the same distinct original worlds.')
    return evaluation.interval(left[key] - right[key] for key in left)


def analyze(source, fit, reference_model, reference_sha256):
    source, fit, reference_model = map(Path, (source, fit, reference_model))
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    identity = common.read_json(fit / 'identity.json')
    finished = common.read_json(fit / 'finished.json')
    advantage.require(finished['identitySha256'] == common.digest(identity)
                      and identity['config']['activeHidden'] == 8,
                      'Fresh capacity validation requires the exact completed eight-unit fit.')
    training = Path(identity['collection']['source'])
    training_manifest = common.read_json(training / 'collection.json')
    advantage.require(common.file_digest(training / 'collection.json')
                      == identity['collection']['fileHashes']['collection.json'],
                      'The fitted collection manifest changed.')
    manifest = common.read_json(source / 'collection.json')
    requests = manifest['origins']
    old_seeds = {row['seed'] for row in training_manifest['origins']}
    advantage.require(manifest['continuation'] is None
                      and all(row['split'] == 'validation' and row['seed'] not in old_seeds
                              and not any(row['opponentUsesCandidate']) for row in requests),
                      'Capacity validation requires fresh validation-only worlds with F continuations.')
    for field in ('baseline', 'frozenPurchasePolicy'):
        advantage.require(manifest[field]['sha256'] == training_manifest[field]['sha256'],
                          'Capacity validation changed the frozen incumbent: ' + field)
    advantage.require(manifest['configuration'] == training_manifest['configuration'],
                      'Capacity validation changed the fitted game configuration.')
    files = {'candidate': fit / 'full.json', 'actionPriority': fit / 'action-priority.json',
             'reference': reference_model, 'frequency': fit / 'frequency-control.json'}
    hashes = {name: common.file_digest(path) for name, path in files.items()}
    advantage.require(hashes['reference'] == reference_sha256,
                      'The reference model must match the frozen P1 hash supplied by preflight.')
    advantage.require(hashes['candidate'] == finished['models']['full']['sha256']
                      and hashes['actionPriority'] == finished['models']['action-priority']['sha256']
                      and hashes['frequency'] == finished['frequencyControlSha256'],
                      'A completed fit model or frequency control changed before validation.')
    artifacts = {name: advantage.validate_bundle(common.read_json(files[name]))
                 for name in ('candidate', 'actionPriority', 'reference')}
    for name in ('candidate', 'actionPriority'):
        for member in artifacts[name]['members']:
            advantage.validate_padding(member, artifacts[name], 8)
    schema_keys = ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')
    advantage.require(all(all(artifact[key] == manifest[key] for key in schema_keys)
                          for artifact in artifacts.values()),
                      'Every local comparator must use the collection feature dictionary.')
    control = common.read_json(files['frequency'])
    advantage.require(control == advantage.frequency_control(control['passProbability'],
                                                             control['otherProbability']),
                      'The frozen frequency control has an incompatible contract.')
    # The reader validates all completion hashes before opening any outcome payload.
    dataset, provenance = data.read_collection(source, splits=('validation',))
    origins = dataset['validation']
    advantage.require(len(origins) >= 2, 'Paired capacity analysis needs independent validation worlds.')
    models = {name: advantage.Ensemble(artifact) for name, artifact in artifacts.items()}
    metrics = {name: fitting.local_metrics(model, origins) for name, model in models.items()}
    metrics['frequency'] = fitting.local_metrics(models['candidate'], origins, control)
    advantage.require(all(common.file_digest(files[name]) == expected
                          for name, expected in hashes.items()),
                      'A frozen local comparator changed during analysis.')
    return {'kind': 'card-advantage-capacity-validation', 'schemaVersion': 1,
            'candidateActiveHidden': 8, 'referenceRole': 'frozen P1 comparator',
            'referenceWireHiddenSize': artifacts['reference']['hiddenSize'],
            'inputs': {'source': str(source.resolve()),
                       'completionSha256': common.file_digest(source / 'finished.json'),
                       'fit': str(fit.resolve()),
                       'fitCompletionSha256': common.file_digest(fit / 'finished.json'),
                       'candidateSha256': hashes['candidate'],
                       'referenceModel': str(reference_model.resolve()),
                       'referenceSha256': hashes['reference'],
                       'helperSha256': common.file_digest(Path(__file__))},
            'collection': provenance, 'fitIdentitySha256': finished['identitySha256'],
            'modelHashes': hashes, **metrics,
            'candidateMinusReference': paired_selected(metrics['candidate'], metrics['reference']),
            'candidateMinusActionPriority': paired_selected(metrics['candidate'], metrics['actionPriority']),
            'candidateMseMinusReference': metrics['candidate']['predictionMse']
                                          - metrics['reference']['predictionMse'],
            'earlierValidationRole': 'development-only; never used for this progression gate',
            'strengthQualified': False, 'automaticPromotion': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--fit', type=Path, required=True)
    parser.add_argument('--reference-model', type=Path, required=True)
    parser.add_argument('--reference-sha256', required=True,
                        help='Exact P1 model hash bound by the experiment preflight.')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    advantage.require(not args.output.exists(), 'Preserve previous capacity analysis; use a fresh output.')
    report = analyze(args.source, args.fit, args.reference_model, args.reference_sha256)
    common.atomic_json(args.output, report)
    print(__import__('json').dumps({key: report['candidate'][key] for key in
          ('origins', 'meanSelectedAdvantage', 'overriddenOrigins', 'localGatePassed')}))


if __name__ == '__main__':
    main()
