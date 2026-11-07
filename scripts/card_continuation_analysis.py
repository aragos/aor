"""Fixed continuation-interaction diagnostic; this program cannot qualify or install a policy.

All compressed bytes are bound before labels are decoded. Only training origins are decoded
before the immutable ridge artifact is published. Repeated future seeds hold the root's hidden
hands/deck fixed: cross-half persistence is not evidence of actor-observable learnability.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import math
from pathlib import Path
import time

import numpy as np
import torch

import card_advantage as advantage
import card_policy
import purchase_policy as common

FEATURE_MODEL_SHA256 = '4a8aba34377e0a0f691c0538304dbda709cb6149280f704919d60dbc99931937'
PRIORITY_SHA256 = 'caf24d21d2304ead3a090a9e0dff8e61bfbf32578af171a2e451677d271acf5b'
KIND = 'card-continuation-diagnostic'
PLAN = {'kind': 'own-continuation-crossover-1', 'replicates': 4}
ARMS = ('f-f', 'a-f', 'f-priority', 'a-priority')
BOOTSTRAP_SAMPLES = 10000
BOOTSTRAP_SEED = 391107
RIDGE_LAMBDA = 1.0
FEATURES = 8
MAX_SECONDS = 600
require = common.require


def read(path):
    return common.read_json(Path(path))


def sha(path):
    return common.file_digest(Path(path))


def immutable(path, value):
    path = Path(path)
    if path.exists():
        require(read(path) == value, 'An immutable diagnostic artifact differs: '+str(path))
    else:
        common.atomic_json(path, value)


def check_deadline(deadline):
    require(time.time() < deadline, 'The original diagnostic deadline elapsed; it cannot be renewed.')


@dataclass(frozen=True)
class Origin:
    index: int
    split: str
    epoch: int
    nontrivial: bool
    observation: dict | None
    seed: int | None
    baseline: int | None
    alternative: int | None
    metrics: dict


class CompletedCollection:
    """Byte bindings precede selective decoding; validation is never needed to fit the probe."""
    def __init__(self, directory):
        self.directory = Path(directory).resolve(strict=True)
        self.manifest = read(self.directory/'collection.json')
        self.finished = read(self.directory/'finished.json')
        state = read(self.directory/'state.json')
        manifest = self.manifest
        require(manifest.get('kind') == KIND and manifest.get('schemaVersion') == 3
                and type(manifest['schemaVersion']) is int,
                'Continuation analysis requires the schema-three diagnostic collection.')
        require(state.get('phase') == 'complete' and self.finished.get('kind') == KIND,
                'The entire continuation collection must finish before any outcome is decoded.')
        require(state['signature'] == manifest['signature'] == self.finished['signature'],
                'Complete collection signatures differ.')
        require(manifest['collection'] == PLAN and manifest['replicates'] == 4
                and manifest['maxTailsPerOrigin'] == 16 and manifest['arms'] == list(ARMS)
                and manifest['backbonePolicy'] == 'F' and manifest['opponentPolicy'] == 'F',
                'The fixed four-arm/four-stream F-backbone contract differs.')
        require(manifest['encoderVersion'] == advantage.ENCODER
                and manifest['selectorVersion'] == advantage.SELECTOR
                and manifest['proposalVersion'] == advantage.PROPOSAL,
                'The diagnostic observation and proposal versions must match the frozen policy.')
        self.requests = manifest['origins']
        hashes = self.finished['originSha256']
        require(len(self.requests) == self.finished['origins'] == len(hashes) == 256,
                'Exactly 256 predeclared independent origins must be complete.')
        for index, request in enumerate(self.requests):
            require(request['index'] == index and type(request['index']) is int
                    and request['split'] == ('train' if index < 128 else 'validation'),
                    'The first 128 origins are training; the remaining 128 are validation.')
            common.integer(request['seed'], 'Origin seed', -(2**31), 2**31-1)
            common.integer(request['seat'], 'Controlled seat', 0, 3)
            common.integer(request['epoch'], 'Requested epoch', 1, 3)
            common.integer(request['ordinal'], 'Requested ordinal', 1, 8)
            require(request['cohort'] == 'generic' and request['opponentUsesCandidate'] == [False]*4,
                    'Crossover roots are generic and every opponent continues with F.')
        require(len({request['seed'] for request in self.requests}) == 256,
                'The original worlds must use distinct seeds.')
        self.pins = {name: sha(self.directory/name) for name in ('collection.json','finished.json','source.json')}
        require(self.pins['source.json'] == manifest['sourceSha256'], 'The source snapshot changed.')
        source = read(self.directory/'source.json')
        require(source['schemaVersion'] == 3 and source['collection'] == PLAN
                and source['origins'] == self.requests, 'The manifest differs from its declared source.')
        for key, filename in (('baseline','baseline.json'), ('frozenPurchasePolicy','frozen-purchase-policy.json'),
                              ('continuation','continuation.json')):
            require(sha(self.directory/filename) == manifest[key]['sha256'],
                    'A frozen playing-policy snapshot changed: '+key)
            self.pins[filename] = manifest[key]['sha256']
        require(manifest['continuation']['sha256'] == PRIORITY_SHA256,
                'Continuation must be the exact frozen C1 priority64 model.')
        # Hash every compressed origin before calling read_origin, even for the training split.
        for index, expected in enumerate(hashes):
            name = f'origin-{index}.json.gz'
            require(sha(self.directory/name) == expected, 'Completed origin bytes changed: '+name)
            self.pins[name] = expected

    def verify(self):
        require(all(sha(self.directory/name) == expected for name,expected in self.pins.items()),
                'Completed collection changed during analysis.')

    def read_split(self, split, deadline):
        result = []
        for request in self.requests:
            if request['split'] != split: continue
            check_deadline(deadline)
            result.append(self.read_origin(request))
        return result

    def read_origin(self, request):
        path = self.directory/f"origin-{request['index']}.json.gz"
        row = common.read_game(path,card_policy.CONTRACT)
        require(row['kind'] == KIND and row['schemaVersion'] == 3 and type(row['schemaVersion']) is int
                and row['signature'] == self.manifest['signature'] and row['request'] == request
                and all(row[key] == request[key] for key in ('index','split','seed','seat')),
                'Origin identity differs from the frozen request.')
        if row['status'] == 'skipped':
            require(row['skipReason'] == 'requested-root-not-reached' and row['root'] is None
                    and not row['outcomes'], 'A skipped root cannot contain observations or outcomes.')
            return Origin(request['index'],request['split'],request['epoch'],False,None,None,None,None,{})
        require(row['status'] == 'collected' and isinstance(row['root'],dict), 'Unknown origin disposition.')
        root = row['root']; observation = root['observation']; seed = root['botSeed']
        common.validate_observation(observation,self.manifest,contract=card_policy.CONTRACT)
        common.integer(root['epoch'],'Root epoch',1,3)
        common.integer(root['referenceIndex'],'Root F reference',0,len(observation['actions'])-1)
        if root['alternativeIndex'] is not None:
            common.integer(root['alternativeIndex'],'Sampled alternative',0,len(observation['actions'])-1)
        require(advantage.normal(observation) and root['epoch'] == request['epoch'],
                'An ordinary root must retain its requested epoch.')
        indices = advantage.proposals(observation,seed)
        require(all(type(index) is int for index in root['proposalIndices'])
                and root['proposalIndices'] == indices and root['referenceIndex'] == observation['baselineIndex'],
                'The recorded full S/reference differs from deployment.')
        _, acquisition = advantage.acquisition(observation,seed,True)
        common.number(root['acquisition']['probability'],'Acquisition probability',0,1)
        common.integer(root['acquisition']['seed'],'Acquisition seed',-(2**31),2**31-1)
        require(root['acquisition'] == acquisition and root['alternativeIndex'] == acquisition['sampledActionIndex'],
                'Alternative acquisition differs from the pre-outcome uniform sampler.')
        baseline = observation['baselineIndex']; alternative = root['alternativeIndex']
        arms = root['arms']; outcomes = row['outcomes']
        require([arm['id'] for arm in arms] == list(ARMS), 'The four arms require canonical explicit order.')
        used = set(); metric_arms = {metric: {} for metric in ('reward','winShare','rankReward','score')}
        for position, arm in enumerate(arms):
            common.integer(arm['actionIndex'],'Arm action index',0,len(observation['actions'])-1)
            expected_action = baseline if position in (0,2) or alternative is None else alternative
            expected_continuation = 'F' if position < 2 else 'priority64'
            require(arm['actionIndex'] == expected_action and arm['continuation'] == expected_continuation,
                    'An arm changed its initial action or own continuation.')
            reference_arm = ARMS[position-1] if alternative is None and position in (1,3) else arm['id']
            expected_ids = [f'arm-{reference_arm}-{replicate}' for replicate in range(4)]
            require(arm['outcomeIds'] == expected_ids, 'Arm outcomes/aliases differ from the fixed crossover.')
            used.update(expected_ids)
            for metric in metric_arms: metric_arms[metric][arm['id']] = []
            for replicate, oid in enumerate(expected_ids):
                require(oid in outcomes, 'An arm is missing a terminal outcome.')
                outcome = outcomes[oid]
                reference = outcomes[f'arm-f-f-{replicate}']
                require(outcome['chanceSeed'] == reference['chanceSeed'], 'A replicate changed its paired future seed.')
                for metric in ('reward','winShare','rankReward'):
                    common.number(outcome[metric], 'Terminal '+metric, 0, 1)
                    metric_arms[metric][arm['id']].append(outcome[metric])
                require(abs(outcome['reward']-.8*outcome['winShare']-.2*outcome['rankReward']) < 1e-12,
                        'Terminal composite reward changed.')
                scores = [score for score in outcome['scores'] if score['player'] == f"p{request['seat']+1}"]
                require(len(scores) == 1, 'The controlled player requires one public terminal score.')
                total = scores[0]['total']
                if total is not None: common.number(total, 'Public terminal score')
                metric_arms['score'][arm['id']].append(total)
        require(set(outcomes) == used and len(used) == (16 if alternative is not None else 8),
                'The diagnostic may contain only its explicit distinct full-game tails.')
        require(len({outcomes[f'arm-f-f-{r}']['chanceSeed'] for r in range(4)}) == 4,
                'The four future replicates require distinct seeds.')
        return Origin(request['index'],request['split'],request['epoch'],alternative is not None,
                      observation,seed,baseline,alternative,metric_arms)


def interaction(arms):
    """Keep each replicate paired across all four arms; never treat streams as worlds."""
    require(set(arms) == set(ARMS) and all(len(values) == 4 for values in arms.values()),
            'Interactions require all four arms and four paired streams.')
    values = np.asarray([arms[name] for name in ARMS],dtype=np.float64)
    require(np.isfinite(values).all(), 'Primary interaction values must be finite.')
    return (values[3]-values[2])-(values[1]-values[0])


class FixedFeatures:
    def __init__(self,path,manifest):
        self.path = Path(path).resolve(strict=True)
        require(sha(self.path) == FEATURE_MODEL_SHA256, 'The frozen breadth feature-model bytes differ.')
        self.model = advantage.validate_bundle(read(self.path))
        require(all(self.model[key] == manifest[key] for key in ('encoderVersion','stateFeatureNames','actionFeatureNames')),
                'The feature dictionary differs from the collected actor-safe observations.')
        member = self.model['members'][0]
        advantage.validate_padding(member,self.model,FEATURES)
        self.network = advantage.Network(self.model,member)

    def __call__(self,origin):
        require(origin.nontrivial, 'Only sampled non-F roots have probe features.')
        group = advantage.prepare(origin.observation,origin.seed,self.model)
        with torch.no_grad():
            hidden = torch.tanh(group.state @ self.network.stateWeights.T + self.network.stateBias)
            actions = torch.tanh(group.actions @ self.network.actionWeights.T + self.network.actionBias)
            f = group.indices.index(origin.baseline); selected = group.indices.index(origin.alternative)
            vector = (hidden*(actions[selected]-actions[f]))[:FEATURES].numpy()
        require(np.isfinite(vector).all(), 'Frozen latent features must be finite.')
        return vector


def fit_ridge(features,targets):
    """Mean squared error plus lambda||beta||²; intercept is unpenalized, scales are train-only."""
    x = np.asarray(features,dtype=np.float64); y = np.asarray(targets,dtype=np.float64)
    require(x.ndim == 2 and x.shape[1] == FEATURES and y.shape == (len(x),) and len(x)>0,
            'The fixed probe requires eight features and one target per training world.')
    require(np.isfinite(x).all() and np.isfinite(y).all(), 'Probe training values must be finite.')
    center = x.mean(axis=0); scale = x.std(axis=0)
    # Detect exactly constant columns directly: floating summation can give a spurious tiny SD.
    constant = np.ptp(x,axis=0) == 0
    scale[constant] = 0.
    normalized = np.divide(x-center,scale,out=np.zeros_like(x),where=scale!=0)
    design = np.column_stack((np.ones(len(x)),normalized))
    penalty = np.diag([0.]+[RIDGE_LAMBDA]*FEATURES)
    coefficients = np.linalg.solve(design.T@design/len(x)+penalty,design.T@y/len(x))
    return {'trainingWorlds':len(x),'mean':center.tolist(),'standardDeviation':scale.tolist(),
            'intercept':float(coefficients[0]),'coefficients':coefficients[1:].tolist(),
            'trainingMeanTarget':float(y.mean()),'lambda':RIDGE_LAMBDA,
            'objective':'meanMSE + lambda*sum(noninterceptCoefficient^2)',
            'zeroStandardDeviation':'The column is always zero, including on validation inputs.'}


def predict_ridge(fit,features):
    x = np.asarray(features,dtype=np.float64)
    require(x.ndim == 2 and x.shape[1] == FEATURES and np.isfinite(x).all(),
            'Probe predictions require finite eight-column features.')
    center = np.asarray(fit['mean']); scale = np.asarray(fit['standardDeviation'])
    normalized = np.divide(x-center,scale,out=np.zeros_like(x),where=scale!=0)
    return float(fit['intercept'])+normalized@np.asarray(fit['coefficients'])


def bootstrap_mean(values,samples=BOOTSTRAP_SAMPLES,seed=BOOTSTRAP_SEED):
    values = np.asarray(values,dtype=np.float64)
    require(values.ndim == 1 and len(values)>1 and np.isfinite(values).all(),
            'Whole-world bootstrap requires at least two finite independent-world values.')
    generator = np.random.default_rng(seed)
    means = []
    for start in range(0,samples,250):
        indices = generator.integers(0,len(values),size=(min(250,samples-start),len(values)))
        means.extend(values[indices].mean(axis=1).tolist())
    lower,upper = np.quantile(np.asarray(means),[.025,.975],method='linear')
    return {'worlds':len(values),'mean':float(values.mean()),'lower95':float(lower),'upper95':float(upper),
            'resamples':samples,'seed':seed,'method':'Whole-world percentile bootstrap, NumPy PCG64, linear quantiles.'}


def persistence(interactions,samples=BOOTSTRAP_SAMPLES):
    values = np.asarray(interactions,dtype=np.float64)
    require(values.ndim == 2 and values.shape[1] == 4 and len(values)>1 and np.isfinite(values).all(),
            'Persistence requires four finite paired interactions per independent world.')
    first = values[:,:2].mean(axis=1); second = values[:,2:].mean(axis=1)
    product = first*second; estimate = float(product.mean())
    interval = bootstrap_mean(product,samples)
    rms = math.sqrt(max(estimate,0.))
    return {'worlds':len(values),'meanInteraction':float(values.mean()),
            'populationCovarianceHalves':float(np.mean((first-first.mean())*(second-second.mean()))),
            'meanProductHalves':estimate,'meanProductInterval':interval,
            'rootMeanSquarePersistentInteraction':rms,
            'noiseVarianceOfFourStreamMean':float(np.mean((first-second)**2)/4),
            'gatePassed':bool(interval['lower95']>0 and rms>=.02),
            'interpretation':'Persistence within the full hidden root world; not actor-observable learnability.'}


def observable_metrics(fit,features,targets,samples=BOOTSTRAP_SAMPLES):
    y = np.asarray(targets,dtype=np.float64); prediction = predict_ridge(fit,features)
    require(y.shape == prediction.shape and np.isfinite(y).all(), 'Validation targets must match probe predictions.')
    baseline_error = (y-float(fit['trainingMeanTarget']))**2
    model_error = (y-prediction)**2
    gain = bootstrap_mean(baseline_error-model_error,samples)
    return {'worlds':len(y),'trainingMeanConstant':fit['trainingMeanTarget'],
            'constantMse':float(baseline_error.mean()),'probeMse':float(model_error.mean()),
            'pairedMseGain':gain,'gatePassed':bool(gain['mean']>0 and gain['lower95']>0),
            'interpretation':'Only this fixed eight-feature probe is tested; a negative result excludes no other observable representation.'}


def coverage(origins):
    counts = {split:sum(origin.nontrivial and origin.split==split for origin in origins) for split in ('train','validation')}
    counts['total'] = sum(origin.nontrivial for origin in origins)
    counts['epoch3'] = sum(origin.nontrivial and origin.epoch==3 for origin in origins)
    return {'nontrivialWorlds':counts,'requestedDecodedWorlds':len(origins),
            'passed':counts['train']>=96 and counts['validation']>=96 and counts['total']>=192 and counts['epoch3']>=48}


def analyze(source,feature_model,output,resume=False):
    torch.set_num_threads(1); torch.use_deterministic_algorithms(True)
    collection = CompletedCollection(source); extractor = FixedFeatures(feature_model,collection.manifest)
    output = Path(output).resolve()
    identity = {'kind':'fixed-card-continuation-analysis','schemaVersion':1,
        'source':str(collection.directory),'signature':collection.manifest['signature'],'fileHashes':collection.pins,
        'featureModel':str(extractor.path),'featureModelSha256':FEATURE_MODEL_SHA256,'memberIndex':0,
        'memberSeed':84501,'memberSelectedEpoch':32,'features':FEATURES,'ridgeLambda':RIDGE_LAMBDA,
        'bootstrapSamples':BOOTSTRAP_SAMPLES,'bootstrapSeed':BOOTSTRAP_SEED,
        'torchVersion':torch.__version__,'numpyVersion':np.__version__,
        'sourceHashes':{str(Path(module.__file__).resolve()):sha(module.__file__)
                        for module in (advantage,card_policy,common)},'analysisSha256':sha(__file__)}
    if resume:
        old = read(output/'identity.json')
        require({key:value for key,value in old.items() if key!='deadlineEpochSeconds'}==identity,
                'Resume inputs, numerical contracts or implementation changed.')
        identity = old
    else:
        require(not output.exists(),'A fresh analysis requires an unused output directory.')
        output.mkdir(parents=True)
        identity['deadlineEpochSeconds'] = time.time()+MAX_SECONDS
        immutable(output/'identity.json',identity)
    identity_hash = common.digest(identity)
    if (output/'report.json').exists():
        report = read(output/'report.json')
        require(report['identitySha256']==identity_hash and (report['ridgeSha256'] is None
                or sha(output/'ridge.json')==report['ridgeSha256']), 'Completed diagnostic artifacts changed.')
        collection.verify()
        return report
    deadline = identity['deadlineEpochSeconds']; check_deadline(deadline)
    training = collection.read_split('train',deadline)
    active_train = [origin for origin in training if origin.nontrivial]
    inputs = {'source':str(collection.directory),'completionSha256':sha(collection.directory/'finished.json'),
              'featureModel':str(extractor.path),'featureModelSha256':FEATURE_MODEL_SHA256,'analysisSha256':sha(__file__)}
    report = {'kind':'completed-card-continuation-analysis','schemaVersion':1,'inputs':inputs,
              'identitySha256':identity_hash,'ridgeSha256':None,'diagnosticOnly':True,'policyPromotion':False,
              'coverage':coverage(training),'persistence':{'gatePassed':False},'observableProbe':{'gatePassed':False},
              'classification':'insufficient-coverage'}
    if len(active_train)>=96:
        train_x = np.asarray([extractor(origin) for origin in active_train])
        train_y = np.asarray([interaction(origin.metrics['reward']).mean() for origin in active_train])
        fitted = fit_ridge(train_x,train_y)
        probe = {'kind':'frozen-continuation-ridge-probe','identitySha256':identity_hash,
                 'trainingOriginIndices':[origin.index for origin in active_train],'fit':fitted}
        # This immutable publication is the held-out boundary. Nothing above decodes validation.
        immutable(output/'ridge.json',probe)
        report['ridgeSha256'] = sha(output/'ridge.json')
        check_deadline(deadline)
        validation = collection.read_split('validation',deadline)
        origins = training+validation; report['coverage'] = coverage(origins)
        if report['coverage']['passed']:
            active = [origin for origin in origins if origin.nontrivial]
            active_validation = [origin for origin in validation if origin.nontrivial]
            report['persistence'] = persistence([interaction(origin.metrics['reward']) for origin in active])
            report['observableProbe'] = observable_metrics(fitted,
                [extractor(origin) for origin in active_validation],
                [interaction(origin.metrics['reward']).mean() for origin in active_validation])
            report['descriptive'] = {}
            for metric in ('winShare','rankReward','score'):
                complete = [origin for origin in active if all(value is not None
                    for arm in origin.metrics[metric].values() for value in arm)]
                values = [interaction(origin.metrics[metric]).mean() for origin in complete]
                report['descriptive'][metric] = {'worlds':len(complete),'excludedForNonfiniteScore':len(active)-len(complete),
                    'meanInteraction':float(np.mean(values)) if values else None}
            report['classification'] = ('no-persistent-interaction' if not report['persistence']['gatePassed'] else
                'persistent-and-predicted-by-fixed-probe' if report['observableProbe']['gatePassed'] else
                'persistent-not-predicted-by-fixed-probe')
    check_deadline(deadline); collection.verify()
    require(sha(feature_model)==FEATURE_MODEL_SHA256 and sha(__file__)==identity['analysisSha256']
            and all(sha(path)==value for path,value in identity['sourceHashes'].items()),
            'Frozen model or numerical source changed during analysis.')
    immutable(output/'report.json',report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--feature-model',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--resume',action='store_true')
    args = parser.parse_args()
    report = analyze(args.source,args.feature_model,args.output,args.resume)
    print(__import__('json').dumps({'report':str(args.output/'report.json'),'classification':report['classification'],
        'coveragePassed':report['coverage']['passed'],'persistencePassed':report['persistence']['gatePassed'],
        'observableProbePassed':report['observableProbe']['gatePassed'],'policyPromotion':False},indent=2))


if __name__=='__main__': main()
