"""TRAIN-only warm94 direct pure-win fitting; no held-out reader or activation path."""
from dataclasses import asdict
import math
from pathlib import Path

import purchase_policy as b
import paired_policy_math as arithmetic
import token_anchor_distribution as distribution
import token_anchored_policy as policy
import token_anchored_data as data
import token_paired_fit as legacy
import token_paired_identity as identity
import token_anchored_recipe as recipe_owner
from token_anchored_recipe import RECIPE


ARTIFACTS = ('identity.json', 'policy.json', 'checkpoint.json', 'history.json')


def completed(directory):
    directory = Path(directory); done = b.read_json(directory/'finished.json')
    b.require(done['kind'] == 'token-anchored-paired-fit-complete-1' and done['complete'] is True,
        'Warm paired fitting requires its own completed receipt, never a legacy objective/model contract.')
    b.require(set(done['artifacts']) == set(ARTIFACTS) and all(b.file_digest(directory/name) == digest
        for name, digest in done['artifacts'].items()), 'A completed warm fit artifact changed.')
    subject = b.read_json(directory/'identity.json'); model = policy.token.validate_model(
        b.read_json(directory/'policy.json'))
    history = b.read_json(directory/'history.json'); checkpoint = b.read_json(directory/'checkpoint.json')
    recipe = recipe_owner.validate(subject['recipe'])
    b.require(subject == done['identity'] and
        subject['context']['probability_contract_sha256'] == b.digest(distribution.RECORD),
        'Warm completion must bind the exact numerical recipe and probability contract.')
    attempts = b.integer(done['attemptedEpochs'], 'Attempted warm epochs', 1, recipe['epochs'])
    accepted = b.integer(done['acceptedEpochs'], 'Accepted warm epochs', 0, attempts)
    b.require(len(history) == attempts+1 and [row['epoch'] for row in history] == list(range(attempts+1))
        and history[0]['accepted'] is True and all(row['accepted'] for row in history[1:accepted+1]) and
        (accepted == attempts or accepted == attempts-1 and history[-1]['accepted'] is False),
        'Warm history must retain all accepted epochs and at most its first rejected rollback.')
    proximal = 'maximumMeanCollectionKl' in recipe
    rejection = None
    for row in history:
        anchor = row['anchor']
        b.number(anchor['mean'], 'Recorded warm mean KL')
        b.number(anchor['maximum'], 'Recorded warm maximum KL')
        b.require(('collection' in row) == proximal,
            'Only the proximal recipe records TRAIN collection-policy KL.')
        collection = row.get('collection')
        if proximal:
            mean = b.number(collection['mean'], 'Recorded mean collection-policy KL')
            maximum = b.number(collection['maximum'], 'Recorded maximum collection-policy KL')
            b.integer(collection['states'], 'Measured READY TRAIN roots', 1, subject['context']['train_count'])
            b.require(collection['states'] == history[0]['collection']['states'],
                'Every epoch must measure the same READY TRAIN roots.')
            if row['epoch'] == 0:
                b.require(abs(mean) <= 2e-12 and abs(maximum) <= 2e-12,
                    'The input actor must reproduce the frozen collection policy.')
        if row['epoch'] > 0:
            rejection = arithmetic.kl_rejection(recipe, anchor, collection)
            b.require(row['accepted'] == (rejection is None),
                'Warm acceptance must obey every fixed recipe KL bound.')
    b.require((accepted == attempts == recipe['epochs'] and done['stopReason'] == 'epoch-limit') or
        (accepted == attempts-1 and rejection is not None and done['stopReason'] == rejection),
        'Warm fit completes only at its fixed epoch limit or first applicable KL rejection.')
    b.require(checkpoint['kind'] == 'token-anchored-paired-adam-1' and checkpoint['model'] == model and
        checkpoint['acceptedEpochs'] == accepted and checkpoint['adamSteps'] == done['adamSteps'] and
        history[-1]['actorSha256'] == b.digest(model) and done['outputModelSha256'] ==
        b.file_digest(directory/'policy.json') and done['actorChanged'] ==
        (identity.actor_parameters_sha256(model) != subject['inputActorParametersSha256']) and
        done['operationalPassed'] == (accepted > 0 and done['actorChanged']) and
        b.digest({key: value for key, value in model.items() if key not in b.PARAMETERS[:-2]}) ==
        subject['frozenModelFieldsSha256'], 'Warm model, optimizer, actor scope and movement must agree.')
    b.require(done['qualificationClaimed'] is False and done['activated'] is False,
        'A numerical fit cannot claim game strength or activate an opponent.')
    return done


def admit_previous(training, original, current, dictionary, previous, recipe):
    training.context.bind(original, current, dictionary); legacy.same_model_scope(original, current)
    if training.context.round == 1:
        b.require(previous is None, 'The zero-residual warm round has no predecessor.')
        return None
    b.require(previous is not None, 'Every later warm round must consume its sole completed predecessor.')
    done = completed(previous); old = done['identity']; context = asdict(training.context)
    stable = ('scope_sha256', 'dictionary_sha256', 'dictionary_semantic_sha256',
        'original_model_sha256', 'anchor_sha256', 'probability_contract_sha256', 'train_count', 'validation_count')
    b.require(old['recipe'] == recipe and old['context']['round']+1 == context['round']
        and done['outputModelSha256'] == current.sha256
        and all(old['context'][key] == context[key] for key in stable) and
        old['anchorSha256'] == training.anchor_sha256 and
        old['context']['source_sha256'] != context['source_sha256'] and
        old['context']['request_bank_sha256'] != context['request_bank_sha256'],
        'Warm rounds retain original anchors/dictionary/ecology and fresh worlds with exactly one predecessor.')
    return dict(finishedSha256=b.file_digest(Path(previous)/'finished.json'), modelSha256=current.sha256)


def fit(training, original, current, dictionary, output, config, *, previous=None, recipe=RECIPE):
    recipe = recipe_owner.validate(recipe)
    b.require(isinstance(training, data.Training) and isinstance(config, legacy.Config),
        'Warm fitting requires its typed TRAIN population and prospectively declared epoch seeds/deadline.')
    b.require(recipe == RECIPE or training.context.round == 1,
        'The proximal comparison uses the first and sole round of its pilot.')
    b.require(training.ready_count >= math.ceil(training.context.train_count*recipe['minimumReadyFraction']),
        'The complete original TRAIN population must meet the prospective public READY exposure floor.')
    predecessor = admit_previous(training, original, current, dictionary, previous, recipe)
    if previous is not None:
        b.require(not (set(config.epochSeeds) & set(completed(previous)['identity']['epochSeeds'])),
            'Warm rounds require fresh prospectively declared epoch shuffle streams.')
    arithmetic.check_clock(config); output = Path(output)
    b.require(not output.exists(), 'Warm fitting is once-only; output already exists.')
    output.mkdir(parents=True); b.torch.set_num_threads(1); b.torch.use_deterministic_algorithms(True)
    actor = policy.TokenAnchoredPolicy(current.model); prepared = arithmetic.Prepared(training, actor)
    arithmetic.verify_behavior(prepared, policy.TokenAnchoredPolicy(current.model))
    optimizer = b.torch.optim.Adam([getattr(actor, name) for name in b.PARAMETERS[:-2]],
        lr=recipe['learningRate'])
    sources = (__file__, policy.__file__, data.__file__, distribution.__file__, arithmetic.__file__,
        legacy.__file__, identity.__file__, b.__file__, recipe_owner.__file__,
        policy.token.__file__, data.legacy.__file__, data.objective.__file__)
    subject = dict(kind='token-anchored-paired-fit-identity-1', recipe=recipe,
        context=asdict(training.context), trainingSha256=training.sha256, anchorSha256=training.anchor_sha256,
        predecessor=predecessor, inputActorParametersSha256=identity.actor_parameters_sha256(current.model),
        frozenModelFieldsSha256=b.digest({key: value for key, value in current.model.items()
            if key not in b.PARAMETERS[:-2]}), epochSeeds=list(config.epochSeeds),
        originalDeadline=config.deadline, sourceSha256={Path(path).name: b.file_digest(path) for path in sources})
    b.atomic_json(output/'identity.json', subject)
    def warm_epoch(actor, optimizer, prepared, seed, config):
        return arithmetic.epoch(actor, optimizer, prepared, seed, config, recipe=recipe)

    history, accepted, steps = arithmetic.fit_epochs(actor, optimizer, prepared, config.epochSeeds, config,
        step=warm_epoch, recipe=recipe,
        publish=lambda history: b.atomic_json(output/'history.json', history))
    model = actor.artifact()
    b.require(all(model[name] == current.model[name] for name in b.PARAMETERS[-2:]) and
        training.sha256 == subject['trainingSha256'] and all(b.file_digest(path) ==
        subject['sourceSha256'][Path(path).name] for path in sources),
        'Warm value head, immutable TRAIN inputs and numerical sources must remain unchanged.')
    b.atomic_json(output/'policy.json', model)
    b.atomic_json(output/'checkpoint.json', dict(kind='token-anchored-paired-adam-1', model=model,
        optimizer=b.encode_optimizer(optimizer), acceptedEpochs=accepted, adamSteps=steps))
    changed = identity.actor_parameters_sha256(model) != subject['inputActorParametersSha256']
    last = history[-1]
    stop_reason = ('epoch-limit' if last['accepted'] else
        arithmetic.kl_rejection(recipe, last['anchor'], last.get('collection')))
    b.atomic_json(output/'finished.json', dict(kind='token-anchored-paired-fit-complete-1', complete=True,
        identity=subject, acceptedEpochs=accepted, attemptedEpochs=len(history)-1, adamSteps=steps,
        actorChanged=changed, operationalPassed=accepted > 0 and changed,
        stopReason=stop_reason,
        outputModelSha256=b.file_digest(output/'policy.json'),
        artifacts={name: b.file_digest(output/name) for name in ARTIFACTS},
        qualificationClaimed=False, activated=False))
    return completed(output)


def main():
    """One fit-only CLI stage. Complete native corpus admission precedes every TRAIN label read."""
    import argparse
    import time
    import token_anchored_plan as plan_owner
    import token_anchored_corpus as corpus_owner
    import token_paired_data as frozen_owner
    from token_paired_campaign_plan import subject, read, file_sha
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('protocol', 'corpus', 'original-corpus', 'original-actor', 'current-actor', 'dictionary', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--deadline-epoch', type=float, required=True)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--numerical-recipe', help='Declared recipe version; defaults to the primary recipe.')
    args = parser.parse_args()
    b.number(args.deadline_epoch, 'Original absolute deadline', 1.)
    b.require(time.time() < args.deadline_epoch, 'Original warm deadline expired before any corpus is opened.')
    plan = plan_owner.Plan.from_record(read(args.protocol), raw_sha256=file_sha(args.protocol))
    recipe = plan.numerical_recipe(args.numerical_recipe)
    corpus = corpus_owner.Corpus(plan, subject(args.corpus))
    anchor = corpus_owner.Corpus(plan, subject(args.original_corpus))
    original = frozen_owner.FrozenActor(args.original_actor.read_bytes(), file_sha(args.original_actor))
    current = frozen_owner.FrozenActor(args.current_actor.read_bytes(), file_sha(args.current_actor))
    context = corpus.context(args.dictionary, original, current)
    b.require(context.original_model_sha256 == plan.record['originalActorSha256'] and
        context.current_model_sha256 == corpus.record['currentActorSha256'] and
        context.dictionary_sha256 == plan.record['dictionarySha256'],
        'The numerical stage must retain the exact original/current residual and native dictionary subjects.')
    training = corpus.training(context, current.model, original.model, anchor)
    config = legacy.Config(tuple(plan.record['rounds'][context.round-1]['shuffleSeeds']),
        min(args.deadline_epoch, plan.record['globalDeadlineEpoch']))
    pins = {str(path): file_sha(path) for path in (args.protocol, args.corpus, args.original_corpus,
        args.original_actor, args.current_actor, args.dictionary)}
    for population in (corpus, anchor):
        for item, chunk in zip(population.record['chunks'], population.chunks):
            pins[item['path']] = item['sha256']; pins[chunk['source']['path']] = chunk['source']['sha256']
            for name, value in chunk['files'].items():
                pins[str(Path(chunk['native'])/name)] = value['sha256']
            for reproduction in chunk['reproductions']:
                pins[reproduction['subject']['path']] = reproduction['subject']['sha256']
    done = fit(training, original, current, args.dictionary.read_bytes(), args.output, config,
        previous=args.previous, recipe=recipe)
    b.require(all(file_sha(path) == expected for path, expected in pins.items()),
        'Every actual original warm corpus/model input must remain unchanged until numerical publication.')
    b.atomic_json(args.output/'numeric-complete.json', dict(kind='token-anchored-numeric-stage-complete-1',
        complete=True, round=context.round, protocolSha256=file_sha(args.protocol),
        fitCompletion=subject(args.output/'finished.json'), inputPins=pins,
        operationalPassed=done['operationalPassed'], semanticValidationLabelsDecoded=False,
        qualificationClaimed=False, activated=False))


if __name__ == '__main__':
    main()
