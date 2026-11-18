"""Fixed paired-policy tensor arithmetic shared by strict TOKEN2 and C/P/E owners.

Callers own typed public/label/provenance admission. This module owns only the existing
inclusion-corrected gain, original-policy KL weighting and atomic epoch rollback.
"""
import copy
import json
import math
import random
import time

import purchase_policy as b

RECIPE = dict(originalKlCoefficient=.1, gradientNorm=.5,
    maximumMeanOriginalKl=.02, maximumStateOriginalKl=.10)


def prepare_record(actor, record):
    """Versioned actors may require a frozen anchor beside the ordinary observation."""
    if hasattr(actor, 'prepare_record'):
        return actor.prepare_record(record)
    return actor.prepare(record['observation'])


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


def weighted_gain(probabilities, behavior, indices, deltas, inclusion_probabilities):
    """Warm proposal estimator: deterministic mandatory arms plus an unbiased random remainder.

    Inclusion probabilities cover the complete offered set, not just selected siblings. This
    prevents a mandatory neighbor from being over-weighted as though it were uniformly sampled.
    The legacy scalar estimator above remains byte-for-byte arithmetic compatible.
    """
    b.require(len(indices) == len(deltas) and indices and deltas[0] == 0. and
        len(inclusion_probabilities) == len(behavior) and
        inclusion_probabilities[indices[0]] == 1.,
        'Warm reference delta/inclusion must be exact and all offered inclusion probabilities retained.')
    b.require(all(type(value) in (int, float) and math.isfinite(value) and 0. < value <= 1.
        for value in inclusion_probabilities), 'Every offered warm action needs positive finite inclusion probability.')
    chosen = b.torch.tensor(indices[1:], dtype=b.torch.long)
    values = b.torch.tensor(deltas[1:], dtype=b.DTYPE)
    old = b.torch.tensor(behavior, dtype=b.DTYPE)
    inclusion = b.torch.tensor([inclusion_probabilities[index] for index in indices[1:]], dtype=b.DTYPE)
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
        self.training = training
        self.ready_count = training.ready_count
        self.root_records = tuple(root.record for root in training.roots)
        self.roots = tuple(None if not root.ready else prepare_record(actor, root.record)
            for root in training.roots)
        groups = []
        for index, anchor in enumerate(training.anchors):
            original = tuple((prepare_record(actor, row), tuple(row['originalProbabilities']))
                for row in map(json.loads, anchor.observations))
            owned = [original] if original else []
            root = training.roots[index].record
            if training.context.iteration > 1 and root['status'] != 'MISSING':
                owned.append(((prepare_record(actor, root),
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


def verify_behavior(prepared, behavior):
    rows = [(prepare_record(behavior, root.record), root.record['currentProbabilities'])
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
        for chunk in batches(prepared.anchor_rows(range(len(prepared.roots)))):
            logs, _ = actor([item[0] for item in chunk])
            for log, (observation, original, weight) in zip(logs, chunk):
                value = float(original_kl(log, original))
                b.require(math.isfinite(value), 'Nonfinite original-policy KL aborts the attempt.')
                total += value*weight; maximum = max(maximum, value); count += 1
                singleton += observation.action_count == 1
    return dict(mean=total/prepared.anchor_count, maximum=maximum,
        worlds=prepared.anchor_count, states=count, singletonStates=singleton)


def minibatch(actor, optimizer, prepared, slots, *, recipe=RECIPE):
    """Accumulate exact declared root/anchor weights before one Adam step."""
    optimizer.zero_grad(set_to_none=True)
    training = prepared.training
    ready = [index for index in slots if prepared.roots[index] is not None]
    gain_value = 0.; kl_value = 0.
    for chunk in batches((prepared.roots[index], index) for index in ready):
        logs, _ = actor([item[0] for item in chunk])
        contributions = []
        for (_, index), log in zip(chunk, logs):
            root = prepared.root_records[index]; target = training.targets[index]
            if 'inclusionProbabilities' in root:
                contributions.append(weighted_gain(log.exp(), root['currentProbabilities'],
                    root['proposedIndices'], target.deltas, root['inclusionProbabilities']))
            else:
                contributions.append(gain(log.exp(), root['currentProbabilities'],
                    root['proposedIndices'], target.deltas, root['inclusionProbability']))
        objective = b.torch.stack(contributions).sum()*len(prepared.roots)/(len(slots)*prepared.ready_count)
        gain_value += float(objective.detach()); (-objective).backward()
    for chunk in batches(prepared.anchor_rows(slots)):
        logs, _ = actor([item[0] for item in chunk])
        term = b.torch.stack([original_kl(log, item[1])*item[2]
            for log, item in zip(logs, chunk)]).sum()*len(prepared.roots)/(len(slots)*prepared.anchor_count)
        kl_value += float(term.detach())
        (recipe['originalKlCoefficient']*term).backward()
    parameters = [getattr(actor, name) for name in b.PARAMETERS[:-2]]
    if not any(parameter.grad is not None for parameter in parameters):
        return dict(gain=gain_value, kl=kl_value, stepped=False)
    norm = b.torch.nn.utils.clip_grad_norm_(parameters, recipe['gradientNorm'], error_if_nonfinite=True)
    b.require(math.isfinite(float(norm)), 'Nonfinite paired policy gradient aborts without publication.')
    optimizer.step()
    b.require(all(bool(b.torch.isfinite(parameter).all()) for parameter in parameters),
        'Nonfinite paired actor parameters abort without publication.')
    return dict(gain=gain_value, kl=kl_value, stepped=True)


def epoch(actor, optimizer, prepared, seed, config, *, metrics=None, recipe=RECIPE):
    slots = list(range(len(prepared.roots))); random.Random(seed).shuffle(slots)
    start_actor = copy.deepcopy(actor.state_dict()); start_optimizer = copy.deepcopy(optimizer.state_dict())
    values = []
    batch_worlds = recipe.get('batchWorlds', 64)
    for start in range(0, len(slots), batch_worlds):
        check_clock(config)
        if recipe is RECIPE:
            values.append(minibatch(actor, optimizer, prepared, slots[start:start+batch_worlds]))
        else:
            values.append(minibatch(actor, optimizer, prepared, slots[start:start+batch_worlds], recipe=recipe))
    check_clock(config)
    measured = (anchor_metrics if metrics is None else metrics)(actor, prepared)
    accepted = measured['mean'] <= recipe['maximumMeanOriginalKl'] and \
        measured['maximum'] <= recipe['maximumStateOriginalKl']
    if not accepted:
        actor.load_state_dict(start_actor); optimizer.load_state_dict(start_optimizer)
    return dict(accepted=accepted, anchor=measured,
        minibatchGainMean=math.fsum(item['gain'] for item in values)/len(values),
        minibatchKlMean=math.fsum(item['kl'] for item in values)/len(values),
        attemptedAdamSteps=sum(item['stepped'] for item in values))


def fit_epochs(actor, optimizer, prepared, seeds, config, *, step=epoch,
        metrics=anchor_metrics, publish=lambda history: None):
    """One declared sequence, stopping at the first rolled-back KL epoch; no reselection."""
    history = [dict(epoch=0, accepted=True, anchor=metrics(actor, prepared),
        actorSha256=b.digest(actor.artifact()))]
    accepted = 0; steps = 0
    for index, seed in enumerate(seeds, 1):
        check_clock(config)
        result = step(actor, optimizer, prepared, seed, config)
        history.append(dict(result, epoch=index, actorSha256=b.digest(actor.artifact())))
        publish(history)
        if not result['accepted']:
            break
        accepted += 1; steps += result['attemptedAdamSteps']
    check_clock(config)
    return history, accepted, steps
