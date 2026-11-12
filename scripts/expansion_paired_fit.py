"""One fixed research Q fit; no native policy export or legacy loader extension.

All fitting uses TRAIN. The only operations permitted to open later labels are guarded by
immutable, complete prediction artifacts. This module is preparation code until a separately
reviewed execution package supplies the corpus, original model and prospective RNG streams.
"""
import copy
from dataclasses import dataclass
import math
from pathlib import Path
import random
import time

import automata_experiments as experiments
import expansion_paired_fit_data as data
import purchase_policy as policy
import purchase_value_benchmark as numerical

torch = policy.torch
ARCHITECTURE = 'aor-expansion-paired-sigmoid-q-1'
G3_PURCHASE_SHA = '6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79'
CONTRACT = policy.PolicyContract(data.ENCODER, ARCHITECTURE, 'research expansion paired Q', 8)
PARAMETERS = ('stateWeights', 'stateBias', 'actionWeights', 'actionBias', 'outputWeights', 'outputBias')
FIELDS = set(PARAMETERS) | {'schemaVersion', 'architecture', 'encoderVersion', 'hiddenSize',
                            'stateFeatureNames', 'actionFeatureNames'}


def validate_model(model):
    data.require(set(model) == FIELDS and type(model['schemaVersion']) is int and model['schemaVersion'] == 1
                 and model['architecture'] == ARCHITECTURE and model['encoderVersion'] == data.ENCODER
                 and type(model['hiddenSize']) is int and model['hiddenSize'] == 64,
                 'The research sigmoid Q schema must remain distinct.')
    states = policy.names(model['stateFeatureNames'], 'Research state dictionary', 8192)
    actions = policy.names(model['actionFeatureNames'], 'Research action dictionary', 512)
    sizes = dict(stateWeights=64*len(states), stateBias=64, actionWeights=64*len(actions),
                 actionBias=64, outputWeights=64)
    for name, size in sizes.items():
        data.require(isinstance(model[name], list) and len(model[name]) == size
                     and all(data.finite(v, -1e6, 1e6) for v in model[name]),
                     'Finite exact Float64 dimensions required: '+name)
    data.require(data.finite(model['outputBias'], -1e6, 1e6), 'Finite scalar output bias required.')
    return model


def shared_mapping(purchase, names):
    """Both encoders use RawActorState; its only phase-specific columns are decision.*.

    The execution package must also pin the RawActorState, PurchaseObservationEncoder and
    ExpansionPairedEncoder owners. Names/units are never learned from observed feature values.
    """
    policy.validate_model(purchase)
    source = purchase['stateFeatureNames']; target = names['stateFeatureNames']
    shared = [name for name in source if not name.startswith('decision.')]
    data.require(shared and set(shared) <= set(target)
                 and {n for n in source if n.startswith('decision.')} ==
                 {'decision.purchase-advances', 'decision.purchase-shipping'},
                 'Transfer requires the complete original purchase RawActorState vocabulary.')
    return [{'name': name, 'source': source.index(name), 'target': target.index(name)} for name in shared]


def initialize(purchase, names, seed):
    """Fresh action basis and scalar head; copy only verified raw-state columns and bias."""
    data.require(type(seed) is int, 'Initialization stream must be an explicit frozen integer.')
    mapping = shared_mapping(purchase, names)
    source_count = len(purchase['stateFeatureNames']); target_count = len(names['stateFeatureNames'])
    weights = [0.] * (64*target_count)
    for row in range(64):
        for column in mapping:
            weights[row*target_count+column['target']] = purchase['stateWeights'][row*source_count+column['source']]
    rng = random.Random(seed); action_count = len(names['actionFeatureNames'])
    scale = math.sqrt(6./(64+action_count))
    return validate_model(dict(schemaVersion=1, architecture=ARCHITECTURE, encoderVersion=data.ENCODER,
        hiddenSize=64, **copy.deepcopy(names), stateWeights=weights, stateBias=purchase['stateBias'][:],
        actionWeights=[rng.uniform(-scale, scale) for _ in range(64*action_count)], actionBias=[0.]*64,
        outputWeights=[0.]*64, outputBias=0.)), mapping


class ActionValue(policy.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.template = copy.deepcopy(validate_model(model))
        encoder = {key: value for key, value in model.items()
                   if key not in ('architecture', 'outputWeights', 'outputBias')}
        encoder.update(baselineProbability=.97, actorWeights=[0.]*64, valueWeights=[0.]*64, valueBias=0.)
        object.__setattr__(self, 'encoder', policy.ResidualPolicy(encoder, CONTRACT))
        for name in PARAMETERS:
            value = torch.tensor(model[name], dtype=policy.DTYPE)
            if name == 'stateWeights': value = value.reshape(64, len(model['stateFeatureNames']))
            if name == 'actionWeights': value = value.reshape(64, len(model['actionFeatureNames']))
            self.register_parameter(name, policy.nn.Parameter(value))

    def artifact(self):
        result = copy.deepcopy(self.template)
        for name in PARAMETERS:
            value = getattr(self, name).detach().cpu()
            result[name] = value.item() if name == 'outputBias' else value.flatten().tolist()
        return validate_model(result)

    def hidden(self, states):
        return torch.tanh(states @ self.stateWeights.T + self.stateBias)

    def forward(self, observations):
        data.require(1 <= len(observations) <= 64, 'Numerical batches contain at most64 whole origins.')
        prepared = [self.encoder.prepare(row) for row in observations]
        state, actions, owners = self.encoder._batch_inputs(prepared)
        device = self.stateWeights.device
        hidden = self.hidden(state.to(device))
        z = torch.tanh(hidden[owners.to(device)] + actions.to(device) @ self.actionWeights.T + self.actionBias)
        q = torch.sigmoid(z @ self.outputWeights + self.outputBias)
        data.require(bool(torch.isfinite(q).all()), 'Nonfinite predictions abort; never clip or replace them.')
        return list(q.split([row.action_count for row in prepared]))


def warm_parity(purchase, model, mapping):
    """Deterministic signed synthetic shared-column probes compare the actual hidden function."""
    old_count = len(purchase['stateFeatureNames']); new_count = len(model['stateFeatureNames'])
    maximum = 0.
    old_weights = torch.tensor(purchase['stateWeights'], dtype=policy.DTYPE).reshape(64, old_count)
    old_bias = torch.tensor(purchase['stateBias'], dtype=policy.DTYPE)
    actor = ActionValue(model)
    for probe in range(16):
        old = torch.zeros((1, old_count), dtype=policy.DTYPE)
        new = torch.zeros((1, new_count), dtype=policy.DTYPE)
        for column in mapping:
            value = ((column['source']*17+probe*13) % 31-15)/16.
            old[0, column['source']] = value; new[0, column['target']] = value
        error = float((torch.tanh(old @ old_weights.T+old_bias)-actor.hidden(new)).abs().max().detach())
        maximum = max(maximum, error)
    data.require(maximum <= 1e-12, 'Shared-state hidden-function transfer differs beyond Float64 tolerance.')
    return dict(kind='expansion-paired-hidden-parity-1', probes=16, maximumAbsoluteError=maximum,
                tolerance=1e-12, commonColumns=len(mapping), actualGameStates=0)


def origin_loss(q, baseline, labels):
    targets = torch.tensor([v['reward'] for v in labels], dtype=q.dtype, device=q.device)
    other = [i for i in range(len(labels)) if i != baseline]
    data.require(other, 'Every training root needs E and a distinct proposed action.')
    paired = ((q[other]-q[baseline])-(targets[other]-targets[baseline])).square().mean()
    return paired + .1*(q-targets).square().mean()


def batch_loss(actor, rows, labels):
    values = actor([row['encoded'] for row in rows])
    return torch.stack([origin_loss(q, row['encoded']['baselineIndex'], labels[row['index']])
                        for q, row in zip(values, rows)]).mean()


def predict(actor, rows):
    result = []
    with torch.no_grad():
        for start in range(0, len(rows), 64):
            batch = rows[start:start+64]
            for row, q in zip(batch, actor([r['encoded'] for r in batch])):
                values = q.cpu().tolist()
                result.append(dict(index=row['index'], values=values, choice=data.choose(row, values)))
    return result


def measures(rows, predictions, labels, training_mean):
    diffs = {metric: [] for metric in data.METRICS}; errors = []; zero = []; mean_errors = []
    for row, prediction in zip(rows, predictions):
        data.require(row['index'] == prediction['index'], 'Metric joins preserve original identities.')
        baseline = row['encoded']['baselineIndex']; actual = labels[row['index']]
        q = prediction['values']; selected = prediction['choice']
        for metric in data.METRICS:
            diffs[metric].append(actual[selected][metric]-actual[baseline][metric])
        targets = [actual[i]['reward']-actual[baseline]['reward'] for i in range(len(actual)) if i != baseline]
        estimates = [q[i]-q[baseline] for i in range(len(q)) if i != baseline]
        errors.append(sum((a-b)**2 for a,b in zip(estimates, targets))/len(targets))
        zero.append(sum(v*v for v in targets)/len(targets))
        mean_errors.append(sum((v-training_mean)**2 for v in targets)/len(targets))
    return dict(gain={key: experiments.interval(value) for key,value in diffs.items()},
                pairedMse=sum(errors)/len(errors), zeroMse=sum(zero)/len(zero),
                trainingMeanMse=sum(mean_errors)/len(mean_errors),
                interventions=sum(p['choice'] != r['encoded']['baselineIndex'] for r,p in zip(rows,predictions)))


def select(metrics):
    return data.validation_choice(metrics)


def checkpoint_model(output, receipt):
    path = Path(receipt['path'])
    data.require(path.name == str(path) and data.sha(Path(output)/path) == receipt['sha256'],
                 'A checkpoint changed after its complete epoch was published.')
    return validate_model(data.read(Path(output)/path))


@dataclass(frozen=True)
class Recipe:
    initializationSeed: int
    shuffleSeeds: tuple
    device: str = 'cuda'

    def validate(self):
        seeds = (self.initializationSeed, *self.shuffleSeeds)
        data.require(len(self.shuffleSeeds) == 20 and len(set(seeds)) == 21
                     and all(type(v) is int and 0 <= v < 2**63 for v in seeds)
                     and self.device in ('cpu', 'cuda'), 'Exactly21 distinct prospective streams and one backend are required.')
        return self


def fit(corpus, purchase_path, recipe, output, *, seconds=1800):
    """A future bounded runner must enforce RSS/disk and external timeout around this operation."""
    recipe.validate(); output = Path(output)
    data.require(not output.exists() and 0 < seconds <= 1800, 'One new output directory and original finite deadline required.')
    data.require(data.training_coverage(corpus.counts), 'Outcome-blind TRAIN/VAL coverage floors failed; labels remain closed.')
    data.require(data.sha(purchase_path) == G3_PURCHASE_SHA, 'Warm source must be the exact qualified G3 purchase bytes.')
    numerical.configure(threads=1)
    started = time.monotonic(); deadline = started+seconds
    def budget(): data.require(time.monotonic() < deadline, 'The original fit deadline expired; no partial epoch selection.')
    purchase = data.read(purchase_path)
    initial, mapping = initialize(purchase, corpus.names, recipe.initializationSeed)
    parity = warm_parity(purchase, initial, mapping)
    output.mkdir()
    data.document(output/'initial.json', initial)
    data.document(output/'warm-transfer.json', dict(sourceSha256=G3_PURCHASE_SHA,
        initialSha256=data.sha(output/'initial.json'), mapping=mapping, parity=parity))
    train = [r for r in corpus.public('TRAIN') if r['status']=='COLLECTED']
    validation = [r for r in corpus.public('VALIDATION') if r['status']=='COLLECTED']
    labels = corpus.labels('TRAIN')
    training_mean = sum(sum(v['reward']-labels[r['index']][r['encoded']['baselineIndex']]['reward']
        for i,v in enumerate(labels[r['index']]) if i != r['encoded']['baselineIndex'])/
        (len(labels[r['index']])-1) for r in train)/len(train)
    actor = ActionValue(initial).to(recipe.device)
    optimizer = torch.optim.Adam(actor.parameters(), lr=.001, betas=(.9,.999), eps=1e-8, weight_decay=0., amsgrad=False)
    checkpoints = {}
    for epoch in range(1,21):
        order = numerical.order(list(range(len(train))), 0, recipe.shuffleSeeds[epoch-1])
        for start in range(0,len(order),64):
            budget(); batch = [train[int(i)] for i in order[start:start+64]]
            optimizer.zero_grad(set_to_none=True); loss = batch_loss(actor,batch,labels)
            data.require(bool(torch.isfinite(loss)), 'Nonfinite loss aborts without alternate fit.')
            loss.backward(); optimizer.step()
        path = output/f'epoch-{epoch:02d}.json'; data.document(path, actor.artifact())
        checkpoints[epoch] = dict(path=path.name, sha256=data.sha(path))
    predictions = {}
    for epoch in range(1,21):
        budget(); actor = ActionValue(checkpoint_model(output,checkpoints[epoch])).to(recipe.device)
        predictions[str(epoch)] = predict(actor,validation)
    val_path = output/'validation-predictions.json'
    data.document(val_path, dict(kind='expansion-paired-predictions-1', corpusSha256=corpus.sha256,
        partition='VALIDATION', epochs=list(range(1,21)), checkpoints={str(k):v for k,v in checkpoints.items()},
        publicSha256=corpus.public_hashes('VALIDATION'), predictions=predictions))
    val_seal = data.PredictionSeal(val_path,data.sha(val_path)); budget()
    val_labels = corpus.labels('VALIDATION',val_seal)
    metrics = {epoch: measures(validation,predictions[str(epoch)],val_labels,training_mean) for epoch in range(1,21)}
    epoch = select(metrics)
    selection = dict(kind='expansion-paired-selection-1', corpusSha256=corpus.sha256, epoch=epoch,
        validationGain=metrics[epoch]['gain']['reward']['mean'] if epoch else 0.,
        checkpoint=checkpoints[epoch] if epoch else None, validationPredictionsSha256=val_seal.sha256,
        metrics={str(k):v for k,v in metrics.items()})
    data.document(output/'selection.json',selection)
    selection_seal = data.SelectionSeal(output/'selection.json',data.sha(output/'selection.json'))
    test = None
    if epoch is not None and corpus.counts['TEST']['ready'] >= 64:
        budget(); rows = [r for r in corpus.public('TEST',selection_seal) if r['status']=='COLLECTED']
        actor = ActionValue(checkpoint_model(output,checkpoints[epoch])).to(recipe.device)
        test_predictions = predict(actor,rows); test_path = output/'test-predictions.json'
        data.document(test_path,dict(kind='expansion-paired-predictions-1',corpusSha256=corpus.sha256,
            partition='TEST',epochs=[epoch],checkpoints={str(epoch):checkpoints[epoch]},
            selectionSha256=data.sha(output/'selection.json'),publicSha256=corpus.public_hashes('TEST'),
            predictions={str(epoch):test_predictions}))
        budget(); test_labels = corpus.labels('TEST',data.PredictionSeal(test_path,data.sha(test_path)),selection_seal)
        test = measures(rows,test_predictions,test_labels,training_mean)
    budget()
    result = dict(kind='expansion-paired-fit-result-1',corpusSha256=corpus.sha256,coverage=corpus.counts,
        epochs=20,actualFits=1,newGames=0,selectedEpoch=epoch,test=test,testOpened=test is not None,
        testPassed=test is not None and test['gain']['reward']['lower95'] > 0,
        qualification=False,elapsedSeconds=time.monotonic()-started)
    data.document(output/'result.json',result)
    return result
