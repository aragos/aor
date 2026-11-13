"""Fixed direct paired policy improvement; no PPO, value fit or outcome-based selection."""
from dataclasses import asdict, dataclass
import copy
import json
import math
from pathlib import Path
import random
import time

import purchase_policy as b
import token_paired_data as data
import token_paired_policy as policy


RECIPE = dict(version='token-paired-expected-improvement-1', epochs=20, batchWorlds=64,
    learningRate=.0003, gradientNorm=.5, originalKlCoefficient=.1,
    maximumMeanOriginalKl=.02, maximumStateOriginalKl=.10,
    gainWeighting='equal-ready-root-horvitz-thompson-sum',
    anchorWeighting='equal-represented-world-equal-deduplicated-state',
    klOrientation='current-to-original', optimizer='fresh-adam-each-iteration',
    frozenParameters=['valueWeights', 'valueBias'])


@dataclass(frozen=True)
class Config:
    epochSeeds: tuple
    deadline: float

    def __post_init__(self):
        b.require(type(self.epochSeeds) is tuple and len(self.epochSeeds) == 20 and
            len(set(self.epochSeeds)) == 20, 'Exactly20 distinct prospectively pinned epoch seeds required.')
        for seed in self.epochSeeds:
            b.integer(seed, 'Epoch shuffle seed', 1, 2**31-1)
        b.number(self.deadline, 'Original absolute deadline', 1.)


def gain(probabilities, behavior, indices, deltas, inclusion):
    """One root's inclusion-corrected full-support improvement estimate, without action mean."""
    b.require(len(indices) == len(deltas) and indices and deltas[0] == 0.,
        'Reference delta must be exactly zero and all sampled sibling targets retained.')
    b.number(inclusion, 'Nonreference inclusion probability', 0., 1.)
    b.require(inclusion > 0., 'A branching paired root requires positive inclusion probability.')
    chosen = b.torch.tensor(indices[1:], dtype=b.torch.long)
    values = b.torch.tensor(deltas[1:], dtype=b.DTYPE)
    old = b.torch.tensor(behavior, dtype=b.DTYPE)
    return ((probabilities[chosen]-old[chosen])*values/inclusion).sum()


def original_kl(log_probabilities, original):
    """Full offer KL(pi_theta || pi0), including unsampled alternatives."""
    if len(original) == 1:
        return log_probabilities.sum()*0.
    reference = b.torch.tensor(original, dtype=b.DTYPE)
    return (log_probabilities.exp()*(log_probabilities-reference.log())).sum()


def batches(items, maximum_rows=256, maximum_actions=16384):
    pending = []; actions = 0
    for item in items:
        count = item[0].action_count
        if pending and (len(pending) == maximum_rows or actions+count > maximum_actions):
            yield pending
            pending = []; actions = 0
        pending.append(item); actions += count
    if pending:
        yield pending


class Prepared:
    """Private tensor cache built exclusively from the typed immutable TRAIN population."""
    def __init__(self, training, actor):
        b.require(isinstance(training, data.Training), 'Paired fitting requires its typed TRAIN owner.')
        self.training = training
        self.ready_count = training.ready_count
        self.root_records = tuple(root.record for root in training.roots)
        self.roots = tuple(None if not root.ready else actor.prepare(root.record['observation'])
            for root in training.roots)
        groups = []
        for index, anchor in enumerate(training.anchors):
            original = tuple((actor.prepare(row['observation']), tuple(row['originalProbabilities']))
                for row in map(json.loads, anchor.observations))
            owned = [original] if original else []
            root = training.roots[index].record
            if training.context.iteration == 2 and root['status'] != 'MISSING':
                owned.append(((actor.prepare(root['observation']),
                    tuple(root['originalProbabilities'])),))
            groups.append(tuple(owned))
        self.groups = tuple(groups)
        self.anchor_count = sum(len(group) for group in self.groups)
        b.require(self.anchor_count > 0, 'A paired fit requires a nonempty original anchor population.')

    def anchor_rows(self, slots):
        for index in slots:
            for group in self.groups[index]:
                for observation, original in group:
                    yield observation, original, 1/len(group)


def check_clock(config):
    b.require(time.time() < config.deadline, 'The original numerical deadline expired; no renewed clock.')


def same_model_scope(original, current):
    left, right = original.model, current.model
    b.require({key: value for key, value in left.items() if key not in b.PARAMETERS[:-2]} ==
        {key: value for key, value in right.items() if key not in b.PARAMETERS[:-2]},
        'Only the five TOKEN2 actor arrays may change across iterations; value and metadata stay fixed.')


def verify_behavior(prepared, behavior):
    rows = [(behavior.prepare(root.record['observation']), root.record['currentProbabilities'])
        for root in prepared.training.roots if root.record['status'] != 'MISSING']
    with b.torch.no_grad():
        for chunk in batches(rows):
            logs, _ = behavior([item[0] for item in chunk])
            for log, (_, expected) in zip(logs, chunk):
                b.require(max(abs(actual-target) for actual, target in
                    zip(log.exp().tolist(), expected)) <= 2e-12,
                    'Recorded pi_m must equal the exact frozen full-offer behavior actor.')


def anchor_metrics(actor, prepared):
    total = 0.; maximum = -math.inf; count = 0; singleton = 0
    with b.torch.no_grad():
        for chunk in batches(prepared.anchor_rows(range(512))):
            logs, _ = actor([item[0] for item in chunk])
            for log, (observation, original, weight) in zip(logs, chunk):
                value = float(original_kl(log, original))
                b.require(math.isfinite(value), 'Nonfinite original-policy KL aborts the attempt.')
                total += value*weight; maximum = max(maximum, value); count += 1
                singleton += observation.action_count == 1
    return dict(mean=total/prepared.anchor_count, maximum=maximum,
        worlds=prepared.anchor_count, states=count, singletonStates=singleton)


def minibatch(actor, optimizer, prepared, slots):
    """Accumulate exact declared root/anchor weights before one Adam step."""
    optimizer.zero_grad(set_to_none=True)
    training = prepared.training
    ready = [index for index in slots if prepared.roots[index] is not None]
    gain_value = 0.; kl_value = 0.
    if ready:
        logs, _ = actor([prepared.roots[index] for index in ready])
        contributions = []
        for index, log in zip(ready, logs):
            root = prepared.root_records[index]; target = training.targets[index]
            contributions.append(gain(log.exp(), root['currentProbabilities'],
                root['proposedIndices'], target.deltas, root['inclusionProbability']))
        objective = b.torch.stack(contributions).sum()*512/(len(slots)*prepared.ready_count)
        gain_value = float(objective.detach()); (-objective).backward()
    for chunk in batches(prepared.anchor_rows(slots)):
        logs, _ = actor([item[0] for item in chunk])
        term = b.torch.stack([original_kl(log, item[1])*item[2]
            for log, item in zip(logs, chunk)]).sum()*512/(len(slots)*prepared.anchor_count)
        kl_value += float(term.detach())
        (RECIPE['originalKlCoefficient']*term).backward()
    parameters = [getattr(actor, name) for name in b.PARAMETERS[:-2]]
    if not any(parameter.grad is not None for parameter in parameters):
        return dict(gain=gain_value, kl=kl_value, stepped=False)
    norm = b.torch.nn.utils.clip_grad_norm_(parameters, RECIPE['gradientNorm'], error_if_nonfinite=True)
    b.require(math.isfinite(float(norm)), 'Nonfinite paired policy gradient aborts without publication.')
    optimizer.step()
    b.require(all(bool(b.torch.isfinite(parameter).all()) for parameter in parameters),
        'Nonfinite paired actor parameters abort without publication.')
    return dict(gain=gain_value, kl=kl_value, stepped=True)


def epoch(actor, optimizer, prepared, seed, config):
    slots = list(range(512)); random.Random(seed).shuffle(slots)
    start_actor = copy.deepcopy(actor.state_dict()); start_optimizer = copy.deepcopy(optimizer.state_dict())
    values = []
    for start in range(0, 512, 64):
        check_clock(config)
        values.append(minibatch(actor, optimizer, prepared, slots[start:start+64]))
    check_clock(config)
    metrics = anchor_metrics(actor, prepared)
    accepted = metrics['mean'] <= RECIPE['maximumMeanOriginalKl'] and \
        metrics['maximum'] <= RECIPE['maximumStateOriginalKl']
    if not accepted:
        actor.load_state_dict(start_actor); optimizer.load_state_dict(start_optimizer)
    return dict(accepted=accepted, anchor=metrics,
        minibatchGainMean=math.fsum(item['gain'] for item in values)/8,
        minibatchKlMean=math.fsum(item['kl'] for item in values)/8,
        attemptedAdamSteps=sum(item['stepped'] for item in values))


def completed(directory):
    directory = Path(directory)
    receipt = b.read_json(directory/'finished.json')
    b.require(receipt['kind'] == 'token-paired-fit-complete-1' and receipt['complete'] is True,
        'A prior fit must have its immutable completed receipt.')
    b.require(set(receipt['artifacts']) == {'identity.json', 'policy.json', 'checkpoint.json', 'history.json'},
        'Paired fit completion must retain the exact model/optimizer/history/identity inventory.')
    for name, expected in receipt['artifacts'].items():
        b.require(b.file_digest(directory/name) == expected, 'A completed paired fit artifact changed.')
    identity = b.read_json(directory/'identity.json')
    b.require(identity['recipe'] == RECIPE and identity == receipt['identity'],
        'Completed paired fit recipe and subjects must agree.')
    b.require(receipt['outputModelSha256'] == b.file_digest(directory/'policy.json'),
        'Completed model raw subject must match the published policy.')
    model = policy.validate_model(b.read_json(directory/'policy.json'))
    history = b.read_json(directory/'history.json'); checkpoint = b.read_json(directory/'checkpoint.json')
    attempts = b.integer(receipt['attemptedEpochs'], 'Attempted fixed epochs', 1, 20)
    accepted = b.integer(receipt['acceptedEpochs'], 'Accepted fixed epochs', 0, attempts)
    b.require(len(history) == attempts+1 and [row['epoch'] for row in history] == list(range(attempts+1)) and
        history[0]['accepted'] is True and all(row['accepted'] is True for row in history[1:accepted+1]) and
        (accepted == attempts or (accepted == attempts-1 and history[-1]['accepted'] is False)) and
        history[-1]['actorSha256'] == b.digest(model),
        'Completed fixed history must retain every accepted epoch and at most the final rolled-back epoch.')
    b.require((accepted == attempts == 20 and receipt['stopReason'] == 'epoch-limit') or
        (accepted == attempts-1 and receipt['stopReason'] == 'original-kl-limit'),
        'A fit completes only at20 epochs or its first rejected original-KL epoch.')
    for row in history[1:]:
        mean = b.number(row['anchor']['mean'], 'Recorded full-anchor mean KL')
        maximum = b.number(row['anchor']['maximum'], 'Recorded maximum individual-state KL')
        b.require(row['accepted'] == (mean <= .02 and maximum <= .10),
            'Every recorded epoch must obey the exact fixed original-policy KL acceptance bounds.')
    b.require(checkpoint['kind'] == 'token-paired-adam-1' and checkpoint['model'] == model and
        checkpoint['acceptedEpochs'] == accepted and checkpoint['adamSteps'] == receipt['adamSteps'] and
        receipt['actorChanged'] == (b.digest(model) != identity['inputModelCanonicalSha256']) and
        receipt['operationalPassed'] == (accepted > 0 and receipt['actorChanged']) and
        b.digest({key: value for key, value in model.items() if key not in b.PARAMETERS[:-2]}) ==
            identity['frozenModelFieldsSha256'],
        'Completed policy, actor-only scope, rollback counters and actual parameter movement must agree.')
    return receipt


def admit_previous(training, original, current, dictionary, previous):
    training.context.bind(original, current, dictionary); same_model_scope(original, current)
    if training.context.iteration == 1:
        b.require(previous is None, 'Iteration1 has no optimizer/model predecessor.')
        return None
    b.require(previous is not None, 'Iteration2 must consume the sole completed iteration1 actor.')
    done = completed(previous); old = done['identity']
    b.require(done['operationalPassed'] is True and old['context']['iteration'] == 1 and
        done['outputModelSha256'] == current.sha256 and
        old['context']['original_model_sha256'] == original.sha256 and
        old['context']['scope_sha256'] == training.context.scope_sha256 and
        old['context']['dictionary_sha256'] == training.context.dictionary_sha256 and
        old['context']['dictionary_semantic_sha256'] == training.context.dictionary_semantic_sha256 and
        old['anchorSha256'] == training.anchor_sha256 and
        old['context']['source_sha256'] != training.context.source_sha256 and
        old['context']['request_bank_sha256'] != training.context.request_bank_sha256,
        'Iteration2 must retain original scope/anchor/dictionary and use fresh worlds and its sole predecessor.')
    return dict(finishedSha256=b.file_digest(Path(previous)/'finished.json'),
        modelSha256=done['outputModelSha256'])


def fit(training, original, current, dictionary, output, config, *, previous=None):
    """One fixed TRAIN-only fit. No validation reader or model-selection callback exists."""
    b.require(isinstance(training, data.Training) and isinstance(config, Config),
        'Paired fitting requires typed TRAIN data and the fixed configuration.')
    b.require(training.ready_count >= 256, 'Public coverage requires at least256 READY branching TRAIN roots.')
    predecessor = admit_previous(training, original, current, dictionary, previous)
    if previous is not None:
        b.require(not (set(config.epochSeeds) & set(completed(previous)['identity']['epochSeeds'])),
            'Each iteration requires its own fresh prospectively reserved epoch shuffle streams.')
    check_clock(config)
    output = Path(output); b.require(not output.exists(), 'Paired fitting is once-only; output already exists.')
    output.mkdir(parents=True)
    b.torch.set_num_threads(1); b.torch.use_deterministic_algorithms(True)
    actor = policy.TokenPairedPolicy(current.model)
    prepared = Prepared(training, actor); verify_behavior(prepared, policy.TokenPairedPolicy(current.model))
    optimizer = b.torch.optim.Adam([getattr(actor, name) for name in b.PARAMETERS[:-2]],
        lr=RECIPE['learningRate'])
    identity = dict(kind='token-paired-fit-identity-1', recipe=RECIPE, context=asdict(training.context),
        inputModelCanonicalSha256=b.digest(current.model),
        frozenModelFieldsSha256=b.digest({key: value for key, value in current.model.items()
            if key not in b.PARAMETERS[:-2]}),
        trainingSha256=training.sha256, anchorSha256=training.anchor_sha256, predecessor=predecessor,
        publicCoverage=dict(trainingReady=training.ready_count,
            validationReady=training.validation_ready_count),
        epochSeeds=list(config.epochSeeds), originalDeadline=config.deadline,
        sourceSha256={Path(path).name: b.file_digest(path) for path in
            (__file__, data.__file__, policy.__file__, b.__file__)})
    b.atomic_json(output/'identity.json', identity)
    initial = anchor_metrics(actor, prepared)
    history = [dict(epoch=0, accepted=True, anchor=initial, actorSha256=b.digest(actor.artifact()))]
    accepted = 0; steps = 0
    for index, seed in enumerate(config.epochSeeds, 1):
        result = epoch(actor, optimizer, prepared, seed, config)
        history.append(dict(result, epoch=index, actorSha256=b.digest(actor.artifact())))
        b.atomic_json(output/'history.json', history)
        if not result['accepted']:
            break
        accepted += 1; steps += result['attemptedAdamSteps']
    check_clock(config)
    model = actor.artifact()
    b.require(all(model[name] == current.model[name] for name in b.PARAMETERS[-2:]),
        'Paired actor fitting must preserve both value parameter arrays exactly.')
    changed = any(model[name] != current.model[name] for name in b.PARAMETERS[:-2])
    b.require(identity['trainingSha256'] == training.sha256,
        'Complete immutable TRAIN inputs must survive optimization unchanged.')
    b.require(all(b.file_digest(path) == identity['sourceSha256'][Path(path).name]
        for path in (__file__, data.__file__, policy.__file__, b.__file__)),
        'The original numerical source owners must remain unchanged until publication.')
    b.atomic_json(output/'policy.json', model)
    b.atomic_json(output/'checkpoint.json', dict(kind='token-paired-adam-1',
        model=model, optimizer=b.encode_optimizer(optimizer), acceptedEpochs=accepted, adamSteps=steps))
    done = dict(kind='token-paired-fit-complete-1', complete=True, identity=identity,
        acceptedEpochs=accepted, attemptedEpochs=len(history)-1, actorChanged=changed,
        operationalPassed=accepted > 0 and changed, adamSteps=steps,
        stopReason='epoch-limit' if accepted == 20 else 'original-kl-limit',
        outputModelSha256=b.file_digest(output/'policy.json'),
        artifacts={name: b.file_digest(output/name) for name in
            ('identity.json', 'policy.json', 'checkpoint.json', 'history.json')})
    b.atomic_json(output/'finished.json', done)
    return completed(output)
