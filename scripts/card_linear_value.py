"""Fixed convex card-value diagnostic; completed TRAIN/DEV only, never policy promotion.

The score is w·(phi(s,a)-phi(s,F))/TRAIN_RMS. There is no intercept, centering,
sigmoid or learned feature extractor. An unobserved label stays masked, not zero.
The contextual representation appends explicit public-state/action interactions to
all original action columns. Its action-only control can never replace the candidate.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import math
import os
from pathlib import Path
import signal
import time

# Set before numerical imports; the CLI is a bounded, single numerical CPU task.
for _thread_variable in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS',
                         'VECLIB_MAXIMUM_THREADS', 'NUMEXPR_NUM_THREADS'):
    os.environ[_thread_variable] = '1'
import numpy as np
import torch

import card_advantage as advantage
import card_advantage_data as data
import card_policy
import purchase_policy as common

SELECTOR = 'aor-card-linear-advantage-1'
FEATURE_VERSION = 'aor-card-linear-features-1'
REPRESENTATIONS = ('action-only', 'contextual')
CONTEXTS = ('epoch', 'ending', 'role.0.cash', 'role.0.income', 'role.0.hand',
            'role.0.misery', 'stabilization', 'role.0.advance.PRINTED_WORD')
CHANNELS = ('pass', 'commodity', 'event', 'leader', 'protection', 'ownCashDelta',
            'ownMiseryDelta', 'rivalCashDelta', 'rivalMiseryDelta', 'rivalMarkersLost',
            'rivalTokensLost', 'effectiveCredit')
EXTRAS = ('targetOpponent.cash', 'targetOpponent.income', 'targetOpponent.misery',
          'targetOpponent.markers', 'credit.unownedEffective',
          'credit.currentlyAvailableEffective', 'selectedCard.handBurdenCount')
CONFIG = {'lambdas': [.01, .1, 1.0], 'threads': 1, 'maxSeconds': 600,
          'margin': .02, 'normalization': 'equal-origin-delta-rms-no-centering',
          'intercept': False, 'trainingSplits': ['train'],
          'selectionSplit': 'development', 'forbiddenDecodeSplits': ['validation'],
          'candidate': 'contextual', 'control': 'action-only'}
MODEL_KEYS = {'schemaVersion', 'encoderVersion', 'selectorVersion', 'proposalVersion',
              'featureVersion', 'representation', 'margin', 'stateFeatureNames',
              'actionFeatureNames', 'featureNames', 'scales', 'coefficients', 'ridgeLambda'}
require = common.require


def feature_contract(schema):
    """Pure vocabulary/formula contract; no observations, labels or random sampling are read.

    Raw values retain the encoder's units. Roles 1..5 are nonacting registration-relative
    seats, not turn order. Credit is nominal effective credit (including Written Record),
    not a cash receipt, price-clipped saving or promise of future use. Current availability
    is the existing pre-card quote flag; its absence cannot diagnose why a purchase is blocked.
    Printed Word's actual rebate minus protection escrow already belongs to quoted cashDelta.
    Hand burden is the stable-definition count in the current own hand, not a forecast saving.
    """
    advantage.validate_schema(schema)
    raw = ['raw.'+name for name in schema['actionFeatureNames']]
    contextual = raw + ['cross.'+state+'.'+channel for state in CONTEXTS for channel in CHANNELS] + list(EXTRAS)
    return {'encoderVersion': advantage.ENCODER, 'proposalVersion': advantage.PROPOSAL,
            'selectorVersion': SELECTOR, 'featureVersion': FEATURE_VERSION,
            'featureNames': {'action-only': raw, 'contextual': contextual},
            'contexts': list(CONTEXTS), 'channels': list(CHANNELS), 'extras': list(EXTRAS),
            'formula': 'delta=phi(s,a)-phi(s,F); score=sum(coefficients*delta/scales); zero scales disable columns',
            'crossFormula': 'context raw value times channel raw value; no additional clipping',
            'rivalFormula': 'sum effect.role.r.<effect> for r=1..5',
            'targetFormula': 'sum target.role.r * state.role.r.<amount> for r=1..5',
            'creditFormula': 'effectiveCredit=sum credit.X.effective; unowned=sum effective*(1-ownAdvance.X); currentlyAvailable=sum effective*offer.X.available',
            'burdenFormula': 'sum action.card.ID * state.handBurden.ID over the complete stable card dictionary',
            'controlSemantics': 'action-only retains state-dependent authoritative quotes and endpoints; it is not context-free priority64',
            'selection': 'original proposal S; first exact maximum in S order; F score exactly zero; override only score strictly greater than .02',
            'uncertainty': 'one fitted linear model; the margin is not an uncertainty or unanimity estimate',
            'cashSemantics': 'quoted own cashDelta already includes actual Printed Word rebate minus protection escrow; credits are not receipts',
            'availabilitySemantics': 'current pre-card availability includes affordability; no counterfactual discounted eligibility is inferred',
            'burdenSemantics': 'current own-hand definition count, not promised stabilization saving'}


class Features:
    """Deterministic actor-safe feature owner; no command parsing, seeds or outcomes as inputs."""
    def __init__(self, schema, representation):
        require(representation in REPRESENTATIONS, 'Unknown linear representation.')
        self.schema = {key: schema[key] for key in ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')}
        self.representation = representation
        self.names = feature_contract(schema)['featureNames'][representation]
        self.advances = tuple(name[len('credit.'):-len('.effective')]
                              for name in schema['actionFeatureNames']
                              if name.startswith('credit.') and name.endswith('.effective'))
        self.cards = tuple(name[len('card.'):] for name in schema['actionFeatureNames'] if name.startswith('card.'))

    def vector(self, state, action):
        raw = [action.get(name, 0.0) for name in self.schema['actionFeatureNames']]
        if self.representation == 'action-only': return np.asarray(raw, dtype=np.float64)
        def rivals(effect):
            return sum(action.get(f'effect.role.{role}.{effect}', 0.0) for role in range(1, 6))
        credit = sum(action.get(f'credit.{advance}.effective', 0.0) for advance in self.advances)
        channels = (action.get('kind.pass', 0.0), action.get('cardKind.COMMODITY', 0.0),
                    action.get('cardKind.EVENT', 0.0), action.get('cardKind.LEADER', 0.0),
                    action.get('protection', 0.0), action.get('effect.role.0.cashDelta', 0.0),
                    action.get('effect.role.0.miseryDelta', 0.0), rivals('cashDelta'),
                    rivals('miseryDelta'), rivals('markersLost'), rivals('tokensLost'), credit)
        crosses = [state.get(context, 0.0)*value for context in CONTEXTS for value in channels]
        targeted = [sum(action.get(f'target.role.{role}', 0.0)*state.get(f'role.{role}.{amount}', 0.0)
                        for role in range(1, 6)) for amount in ('cash', 'income', 'misery', 'markers')]
        unowned = sum(action.get(f'credit.{advance}.effective', 0.0)*
                      (1.0-state.get(f'role.0.advance.{advance}', 0.0)) for advance in self.advances)
        available = sum(action.get(f'credit.{advance}.effective', 0.0)*
                        state.get(f'offer.{advance}.available', 0.0) for advance in self.advances)
        burden = sum(action.get('card.'+card, 0.0)*state.get('handBurden.'+card, 0.0) for card in self.cards)
        return np.asarray(raw+crosses+targeted+[unowned, available, burden], dtype=np.float64)

    def differences(self, observation, indices):
        common.validate_observation(observation, self.schema, contract=card_policy.CONTRACT)
        state = observation['stateFeatures']
        base = self.vector(state, observation['actions'][observation['baselineIndex']]['features'])
        result = np.stack([self.vector(state, observation['actions'][index]['features'])-base for index in indices])
        require(np.isfinite(result).all(), 'Derived linear features must be finite.')
        return result


@dataclass(frozen=True)
class Rows:
    x: np.ndarray
    y: np.ndarray
    weights: np.ndarray
    origin_count: int


def training_rows(origins, features):
    """Each active world has total weight one, shared among groups then observed non-F pairs."""
    rows, targets, weights = [], [], []
    active = [[group for group in origin if group.observed_pairs()] for origin in origins]
    active = [groups for groups in active if groups]
    require(active, 'Linear fitting requires observed non-F pairs.')
    for groups in active:
        for group in groups:
            mask = group.observed_pairs()
            x = features.differences(group.observation, group.indices)
            rows.extend(x[mask]); targets.extend(float(group.targets[index]) for index in mask)
            weights.extend([1.0/(len(active)*len(groups)*len(mask))]*len(mask))
    result = Rows(np.asarray(rows, dtype=np.float64), np.asarray(targets, dtype=np.float64),
                  np.asarray(weights, dtype=np.float64), len(active))
    require(np.isfinite(result.y).all() and abs(float(result.weights.sum())-1.0) < 1e-12,
            'Only finite observed labels with equal-world weights may be fitted.')
    return result


def rms_scales(rows):
    """No centering: a disabled training-zero column remains zero even on later inputs."""
    return np.sqrt(np.sum(rows.weights[:, None]*rows.x*rows.x, axis=0))


def normalized(x, scales):
    return np.divide(x, scales, out=np.zeros_like(x), where=scales != 0)


def ridge(rows, scales, penalty):
    """Unique minimizer of weighted mean paired MSE + lambda*||coefficients||²."""
    require(penalty in CONFIG['lambdas'], 'The diagnostic permits only its fixed ridge grid.')
    x = normalized(rows.x, scales)
    gram = x.T @ (rows.weights[:, None]*x)
    gram.flat[::len(scales)+1] += penalty
    coefficients = np.linalg.solve(gram, x.T @ (rows.weights*rows.y))
    coefficients[scales == 0] = 0.0
    require(np.isfinite(coefficients).all(), 'Ridge produced nonfinite coefficients.')
    return coefficients


def verify_solution(rows, scales, coefficients, penalty):
    """Strict convexity makes the normal equations a cheap resumed-fit integrity check."""
    x = normalized(rows.x, scales)
    gradient = x.T @ (rows.weights*(x @ coefficients-rows.y))+penalty*coefficients
    require(np.isfinite(coefficients).all() and np.isfinite(gradient).all()
            and np.max(np.abs(gradient)) <= 1e-10*(1+np.max(np.abs(coefficients))),
            'A ridge checkpoint no longer solves its frozen weighted training problem.')


def artifact(schema, representation, scales, coefficients, penalty):
    return validate_model({'schemaVersion': 1, 'encoderVersion': advantage.ENCODER,
        'selectorVersion': SELECTOR, 'proposalVersion': advantage.PROPOSAL,
        'featureVersion': FEATURE_VERSION, 'representation': representation, 'margin': CONFIG['margin'],
        'stateFeatureNames': schema['stateFeatureNames'], 'actionFeatureNames': schema['actionFeatureNames'],
        'featureNames': feature_contract(schema)['featureNames'][representation],
        'scales': scales.tolist(), 'coefficients': coefficients.tolist(), 'ridgeLambda': penalty})


def validate_model(model):
    require(isinstance(model, dict) and set(model) == MODEL_KEYS, 'Unexpected or missing linear model fields.')
    common.number(model['ridgeLambda'], 'Ridge regularization', .01, 1)
    common.number(model['margin'], 'Linear override margin', .02, .02)
    require(type(model['schemaVersion']) is int and model['schemaVersion'] == 1
            and model['selectorVersion'] == SELECTOR and model['featureVersion'] == FEATURE_VERSION
            and model['proposalVersion'] == advantage.PROPOSAL and model['margin'] == CONFIG['margin']
            and model['representation'] in REPRESENTATIONS and model['ridgeLambda'] in CONFIG['lambdas'],
            'Unsupported fixed linear model contract.')
    names = feature_contract(model)['featureNames'][model['representation']]
    require(model['featureNames'] == names, 'Linear feature names/order differ from the fixed formula.')
    for key in ('scales', 'coefficients'):
        require(isinstance(model[key], list) and len(model[key]) == len(names), 'Invalid linear parameter size: '+key)
        for value in model[key]: common.number(value, key, 0 if key == 'scales' else -1e6, 1e6)
    require(all(scale != 0 or coefficient == 0 for scale, coefficient in zip(model['scales'], model['coefficients'])),
            'Training-zero columns must remain disabled.')
    return model


class LinearModel:
    def __init__(self, model):
        self.model = validate_model(model)
        self.features = Features(model, model['representation'])
        self.scales = np.asarray(model['scales'], dtype=np.float64)
        self.coefficients = np.asarray(model['coefficients'], dtype=np.float64)

    def scores(self, group):
        values = normalized(self.features.differences(group.observation, group.indices), self.scales) @ self.coefficients
        values[group.baseline] = 0.0
        return values

    def choose(self, observation, seed):
        # Unsupported final windows stay F without changing the ordinary actor.
        if not advantage.normal(observation):
            return {'index': observation['baselineIndex'], 'scores': [], 'proposalPosition': None}
        group = advantage.prepare(observation, seed, self.model)
        scores = self.scores(group)
        selected = select(scores, group.baseline)
        return {'index': group.indices[selected], 'scores': scores.tolist(), 'proposalPosition': selected}


def select(scores, baseline):
    """Original S order, first exact maximum, strict .02 margin; no invented uncertainty gate."""
    require(np.isfinite(scores).all() and scores[baseline] == 0.0, 'F must have an exact zero finite score.')
    chosen = int(np.argmax(scores))
    return chosen if scores[chosen] > CONFIG['margin'] else baseline


def metrics(model, origins, dense):
    per_origin = []
    for origin in origins:
        if not origin: continue
        errors, zero, selections, overrides = [], [], [], 0
        for group in origin:
            if dense: group.require_dense_targets()
            scores = model.scores(group)
            mask = group.observed_pairs()
            if mask:
                truth = group.targets.numpy()[mask]
                errors.append(float(np.mean((scores[mask]-truth)**2)))
                zero.append(float(np.mean(truth**2)))
            if dense:
                choice = select(scores, group.baseline)
                selections.append(float(group.targets[choice])); overrides += int(choice != group.baseline)
        per_origin.append({'origin': origin[0].identifier.split(':')[0], 'decisions': len(origin),
                           'mse': sum(errors)/len(errors) if errors else None,
                           'zeroMse': sum(zero)/len(zero) if zero else None,
                           'selectedAdvantage': sum(selections)/len(selections) if dense else None,
                           'overrides': overrides if dense else None})
    active = [row for row in per_origin if row['mse'] is not None]
    require(active, 'Error diagnostics require a world with an observed non-F label.')
    return {'origins': len(per_origin), 'nontrivialOrigins': len(active),
            'predictionMse': sum(row['mse'] for row in active)/len(active),
            'zeroPredictionMse': sum(row['zeroMse'] for row in active)/len(active),
            'meanSelectedAdvantage': sum(row['selectedAdvantage'] for row in per_origin)/len(per_origin) if dense else None,
            'overriddenOrigins': sum(row['overrides'] > 0 for row in per_origin) if dense else None,
            'perOrigin': per_origin, 'selectedOutcomesAvailable': dense}


def immutable(path, value):
    if path.exists(): require(common.read_json(path) == value, 'Immutable diagnostic evidence differs: '+str(path))
    else: common.atomic_json(path, value)


def check_deadline(deadline):
    require(time.time() < deadline, 'The original 600-second diagnostic deadline elapsed; no topups are permitted.')


def source_hashes():
    return {str(Path(module.__file__).resolve()): common.file_digest(Path(module.__file__))
            for module in (advantage, data, card_policy, common)} | {str(Path(__file__).resolve()): common.file_digest(Path(__file__))}


def finish_report(output, identity_sha):
    """Recover a publication interrupted after report.json without renewing or repeating fits."""
    report = common.read_json(output/'report.json')
    require(report['identitySha256'] == identity_sha and report['diagnosticOnly'] is True
            and report['policyPromotion'] is False and report['validationDecoded'] is False,
            'The completed development-only report has a different identity or scope.')
    require(set(report['modelSha256']) == set(REPRESENTATIONS) and all(
        common.file_digest(output/(name+'.json')) == expected for name, expected in report['modelSha256'].items()),
        'A selected immutable linear model changed.')
    for name in REPRESENTATIONS: validate_model(common.read_json(output/(name+'.json')))
    immutable(output/'finished.json', {'identitySha256': identity_sha,
        'files': {name: common.file_digest(output/name) for name in ('contextual.json', 'action-only.json', 'report.json')}})
    return report


def bind_collection(directory):
    """No origin decoding: validate completion and hash even the forbidden validation records."""
    directory = Path(directory).resolve(strict=True)
    manifest = common.read_json(directory/'collection.json')
    state = common.read_json(directory/'state.json'); finished = common.read_json(directory/'finished.json')
    version, plan = data.collection_contract(manifest)
    require(version == 2 and plan == {'kind': 'root-pairs-1', 'sampledSplits': ['train'], 'replicates': 1}
            and manifest.get('continuation') is None, 'This diagnostic requires the frozen F-continuation breadth acquisition.')
    require(state.get('phase') == 'complete' and state['signature'] == manifest['signature'] == finished['signature']
            and finished.get('kind') == manifest['kind'] and finished['origins'] == len(manifest['origins'])
            == len(finished['originSha256']), 'The entire breadth collection must be complete and bound.')
    names = ['collection.json', 'finished.json', 'source.json', 'baseline.json', 'frozen-purchase-policy.json']
    hashes = {name: common.file_digest(directory/name) for name in names}
    require(hashes['source.json'] == manifest['sourceSha256'], 'The collection source snapshot changed.')
    source = common.read_json(directory/'source.json')
    require(source['origins'] == manifest['origins'] and source['collection'] == plan and source['schemaVersion'] == 2,
            'The declared source and collection requests differ.')
    for field, name in (('baseline', 'baseline.json'), ('frozenPurchasePolicy', 'frozen-purchase-policy.json')):
        require(hashes[name] == manifest[field]['sha256'], 'A frozen policy snapshot changed: '+field)
    for index, expected in enumerate(finished['originSha256']):
        name = f'origin-{index}.json.gz'
        require(common.file_digest(directory/name) == expected, 'A completed origin changed: '+name)
        hashes[name] = expected
    return manifest, hashes


def run(protocol_path, output, resume=False):
    """Bound metadata hashing too; a completed-report audit does not perform another fit."""
    started = time.time()
    remaining = CONFIG['maxSeconds']
    if resume and not (Path(output)/'report.json').exists():
        deadline = common.read_json(Path(output)/'identity.json')['deadlineEpochSeconds']
        check_deadline(deadline)
        remaining = min(remaining, deadline-started)
    def expired(signum, frame):
        raise TimeoutError('The original 600-second linear diagnostic budget elapsed.')
    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, remaining)
    try:
        return _run(protocol_path, output, resume, started)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0); signal.signal(signal.SIGALRM, previous)


def _run(protocol_path, output, resume, started):
    """Six fixed fits at most; publication is immutable and resuming never renews the clock."""
    torch.set_num_threads(1); torch.use_deterministic_algorithms(True)
    protocol_path = Path(protocol_path).resolve(strict=True); output = Path(output).resolve()
    protocol = common.read_json(protocol_path)
    require(set(protocol) == {'kind', 'schemaVersion', 'source', 'config', 'expectedCounts', 'pins', 'featureContract'}
            and protocol['kind'] == 'card-linear-value-diagnostic' and protocol['schemaVersion'] == 1
            and type(protocol['schemaVersion']) is int and protocol['config'] == CONFIG,
            'A frozen fixed-config linear diagnostic protocol is required.')
    require(set(protocol['expectedCounts']) == {'train', 'development'}, 'Only TRAIN/DEV counts may be requested.')
    for value in protocol['expectedCounts'].values(): common.integer(value, 'Expected collected origins', 1, 100000)
    require(protocol['pins'] and all(Path(path).is_absolute() and common.file_digest(Path(path)) == expected
                                    for path, expected in protocol['pins'].items()), 'A frozen protocol input changed.')
    require(all(protocol['pins'].get(path) == expected for path, expected in source_hashes().items()),
            'The protocol must pin the numerical implementation and every imported data/policy helper.')
    source = Path(protocol['source']).resolve(strict=True)
    manifest, hashes = bind_collection(source)
    require(protocol['featureContract'] == feature_contract(manifest), 'Frozen linear feature formulas differ.')
    core_identity = {'kind': 'card-linear-value-fit', 'schemaVersion': 1,
                     'protocol': str(protocol_path), 'protocolSha256': common.file_digest(protocol_path),
                     'source': str(source), 'collectionSignature': manifest['signature'], 'fileHashes': hashes,
                     'sourceHashes': source_hashes(), 'config': CONFIG, 'featureContract': feature_contract(manifest),
                     'numpyVersion': np.__version__, 'torchVersion': torch.__version__}
    if resume:
        identity = common.read_json(output/'identity.json')
        require({key: value for key, value in identity.items() if key not in ('startedAtEpochSeconds', 'deadlineEpochSeconds')}
                == core_identity, 'Resume source, data, formula, protocol or numerical runtime differs.')
        require(identity['deadlineEpochSeconds'] == identity['startedAtEpochSeconds']+CONFIG['maxSeconds'],
                'The original diagnostic clock differs.')
    else:
        require(not output.exists(), 'Fresh diagnostic fitting requires a new output directory.')
        output.mkdir(parents=True)
        identity = {**core_identity, 'startedAtEpochSeconds': started, 'deadlineEpochSeconds': started+CONFIG['maxSeconds']}
        immutable(output/'identity.json', identity)
    identity_sha = common.digest(identity)
    if (output/'finished.json').exists():
        finished = common.read_json(output/'finished.json')
        require(finished['identitySha256'] == identity_sha and all(common.file_digest(output/name) == expected
                for name, expected in finished['files'].items()), 'Completed diagnostic artifacts changed.')
        return common.read_json(output/'report.json')
    if (output/'report.json').exists(): return finish_report(output, identity_sha)
    deadline = identity['deadlineEpochSeconds']; check_deadline(deadline)
    # SIGALRM also bounds a slow decode or dense solve; ordinary checkpoints remain intact.
    def expired(signum, frame):
        raise TimeoutError('The original 600-second linear diagnostic budget elapsed.')
    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, max(.001, deadline-time.time()))
    try:
        dataset, provenance = data.read_collection(source, splits=('train', 'development'))
        require(provenance['fileHashes'] == hashes and all(provenance['counts'][split] == count
                for split, count in protocol['expectedCounts'].items()), 'Completed TRAIN/DEV data differs from the frozen protocol.')
        require(not dataset['validation'] and provenance['counts']['validation'] == 0,
                'Old validation outcomes must never be decoded.')
        schema = provenance['schema']; reports = {}; selected_models = {}
        for representation in REPRESENTATIONS:
            features = Features(schema, representation); rows = training_rows(dataset['train'], features)
            scales = rms_scales(rows); candidates = []
            folder = output/representation; folder.mkdir(exist_ok=True)
            for penalty in CONFIG['lambdas']:
                check_deadline(deadline); path = folder/f'lambda-{penalty:g}.json'
                if path.exists():
                    record = common.read_json(path)
                    require(record['identitySha256'] == identity_sha and record['model']['representation'] == representation
                            and record['model']['ridgeLambda'] == penalty and record['model']['scales'] == scales.tolist(),
                            'A resumed ridge checkpoint changed identity, formula or normalization.')
                    validate_model(record['model'])
                    require(all(record['model'][key] == schema[key] for key in schema),
                            'A resumed linear model changed its raw feature dictionaries.')
                    verify_solution(rows, scales, np.asarray(record['model']['coefficients']), penalty)
                else:
                    model = artifact(schema, representation, scales, ridge(rows, scales, penalty), penalty)
                    record = {'identitySha256': identity_sha, 'model': model,
                              'development': metrics(LinearModel(model), dataset['development'], dense=True)}
                    immutable(path, record)
                # Recompute resumed diagnostics rather than trusting a changed checkpoint's metric.
                actual = metrics(LinearModel(record['model']), dataset['development'], dense=True)
                require(actual == record['development'], 'A ridge checkpoint development report changed.')
                candidates.append(record)
            best = min(candidates, key=lambda record: record['development']['predictionMse'])
            selected_models[representation] = best['model']; immutable(output/(representation+'.json'), best['model'])
            reports[representation] = {'selectedLambda': best['model']['ridgeLambda'],
                'gridDevelopmentMse': [{'lambda': record['model']['ridgeLambda'], 'mse': record['development']['predictionMse']} for record in candidates],
                'training': metrics(LinearModel(best['model']), dataset['train'], dense=False),
                'development': best['development'], 'featureCount': len(features.names),
                'activeColumns': int(np.count_nonzero(scales))}
        contextual = reports['contextual']['development']; control = reports['action-only']['development']
        gate = (contextual['predictionMse'] < contextual['zeroPredictionMse']
                and contextual['predictionMse'] < control['predictionMse'] and contextual['meanSelectedAdvantage'] > 0)
        require(bind_collection(source)[1] == hashes and source_hashes() == identity['sourceHashes']
                and all(common.file_digest(Path(path)) == expected for path, expected in protocol['pins'].items()),
                'Diagnostic inputs changed during fitting.')
        check_deadline(deadline)
        report = {'kind': 'completed-card-linear-value-diagnostic', 'identitySha256': identity_sha,
            'protocolSha256': identity['protocolSha256'], 'diagnosticOnly': True, 'policyPromotion': False,
            'candidate': 'contextual', 'controlCanReplaceCandidate': False, 'localGatePassed': gate,
            'validationDecoded': False, 'counts': provenance['counts'], 'models': reports,
            'modelSha256': {name: common.file_digest(output/(name+'.json')) for name in REPRESENTATIONS},
            'elapsedSeconds': time.time()-identity['startedAtEpochSeconds'],
            'interpretation': 'Development-selected ridge diagnostics only; terminal objective unchanged. Neither model is strength-qualified.'}
        immutable(output/'report.json', report)
        return finish_report(output, identity_sha)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0); signal.signal(signal.SIGALRM, previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--run', action='store_true', help='Run only after the protocol and implementation are frozen.')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    require(args.run, 'Explicit --run is required; preparing a protocol never starts fitting.')
    report = run(args.protocol, args.output, args.resume)
    print({'report': str(args.output/'report.json'), 'localGatePassed': report['localGatePassed']})


if __name__ == '__main__': main()
