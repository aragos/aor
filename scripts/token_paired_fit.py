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
import token_paired_identity as identity_owner
import paired_policy_math as paired_math
import token_paired_objective as objectives
from paired_policy_math import gain, original_kl, batches, check_clock, verify_behavior, anchor_metrics, minibatch


RECIPE = dict(version='token-paired-expected-improvement-1', epochs=20, batchWorlds=64,
    learningRate=.0003, gradientNorm=.5, originalKlCoefficient=.1,
    maximumMeanOriginalKl=.02, maximumStateOriginalKl=.10,
    gainWeighting='equal-ready-root-horvitz-thompson-sum',
    anchorWeighting='equal-represented-world-equal-deduplicated-state',
    klOrientation='current-to-original', optimizer='fresh-adam-each-iteration',
    frozenParameters=['valueWeights', 'valueBias'])
WIN_RECIPE=dict(RECIPE,version='token-paired-win-expected-improvement-1',objectiveVersion=objectives.WIN)


def objective_contract(objective):
    objectives.validate(objective)
    return (WIN_RECIPE,'token-paired-win') if objective==objectives.WIN else (RECIPE,'token-paired')


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


class Prepared(paired_math.Prepared):
    """TOKEN2-only boundary; generic tensor caching follows strict type admission."""
    def __init__(self, training, actor):
        b.require(isinstance(training, data.Training), 'Paired fitting requires its typed TRAIN owner.')
        super().__init__(training, actor)


def same_model_scope(original, current):
    left, right = original.model, current.model
    b.require({key: value for key, value in left.items() if key not in b.PARAMETERS[:-2]} ==
        {key: value for key, value in right.items() if key not in b.PARAMETERS[:-2]},
        'Only the five TOKEN2 actor arrays may change across iterations; value and metadata stay fixed.')


def epoch(actor, optimizer, prepared, seed, config):
    return paired_math.epoch(actor, optimizer, prepared, seed, config, metrics=anchor_metrics)


def completed(directory,*,objective=objectives.MIXED):
    recipe,prefix=objective_contract(objective)
    directory = Path(directory)
    receipt = b.read_json(directory/'finished.json')
    b.require(receipt['kind'] == prefix+'-fit-complete-1' and receipt['complete'] is True,
        'A prior fit must have its immutable completed receipt.')
    b.require(set(receipt['artifacts']) == {'identity.json', 'policy.json', 'checkpoint.json', 'history.json'},
        'Paired fit completion must retain the exact model/optimizer/history/identity inventory.')
    for name, expected in receipt['artifacts'].items():
        b.require(b.file_digest(directory/name) == expected, 'A completed paired fit artifact changed.')
    identity = b.read_json(directory/'identity.json')
    b.require(identity['recipe'] == recipe and identity == receipt['identity'] and
        (identity['context'].get('objective_version')==objectives.WIN if objective==objectives.WIN
         else 'objective_version' not in identity['context']),
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
    b.require(checkpoint['kind'] == prefix+'-adam-1' and checkpoint['model'] == model and
        checkpoint['acceptedEpochs'] == accepted and checkpoint['adamSteps'] == receipt['adamSteps'] and
        receipt['actorChanged'] == (identity_owner.actor_parameters_sha256(model) !=
            data.sha(identity['inputActorParametersSha256'], 'Input actor parameters')) and
        receipt['operationalPassed'] == (accepted > 0 and receipt['actorChanged']) and
        b.digest({key: value for key, value in model.items() if key not in b.PARAMETERS[:-2]}) ==
            identity['frozenModelFieldsSha256'],
        'Completed policy, actor-only scope, rollback counters and actual parameter movement must agree.')
    return receipt


def admit_previous(training, original, current, dictionary, previous,*,objective=objectives.MIXED):
    training.context.bind(original, current, dictionary); same_model_scope(original, current)
    if training.context.iteration == 1:
        b.require(previous is None, 'Iteration1 has no optimizer/model predecessor.')
        return None
    b.require(previous is not None, 'Iteration2 must consume the sole completed iteration1 actor.')
    done = completed(previous,objective=objective); old = done['identity']
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


def fit(training, original, current, dictionary, output, config, *, previous=None,objective=objectives.MIXED):
    """One fixed TRAIN-only fit. No validation reader or model-selection callback exists."""
    b.require(isinstance(training, data.Training) and isinstance(config, Config),
        'Paired fitting requires typed TRAIN data and the fixed configuration.')
    recipe,prefix=objective_contract(objective)
    b.require(training.context.objective==objective,
        'The fitting objective must match the explicit corpus/context objective contract.')
    b.require(training.ready_count >= 256, 'Public coverage requires at least256 READY branching TRAIN roots.')
    predecessor = admit_previous(training, original, current, dictionary, previous,objective=objective)
    if previous is not None:
        b.require(not (set(config.epochSeeds) & set(completed(previous,objective=objective)['identity']['epochSeeds'])),
            'Each iteration requires its own fresh prospectively reserved epoch shuffle streams.')
    check_clock(config)
    output = Path(output); b.require(not output.exists(), 'Paired fitting is once-only; output already exists.')
    output.mkdir(parents=True)
    b.torch.set_num_threads(1); b.torch.use_deterministic_algorithms(True)
    actor = policy.TokenPairedPolicy(current.model)
    prepared = Prepared(training, actor); verify_behavior(prepared, policy.TokenPairedPolicy(current.model))
    optimizer = b.torch.optim.Adam([getattr(actor, name) for name in b.PARAMETERS[:-2]],
        lr=RECIPE['learningRate'])
    sources=(__file__,data.__file__,policy.__file__,identity_owner.__file__,b.__file__,paired_math.__file__)
    if objective==objectives.WIN:sources=(*sources,objectives.__file__)
    identity = dict(kind=prefix+'-fit-identity-1', recipe=recipe, context=asdict(training.context),
        inputModelCanonicalSha256=b.digest(current.model),
        inputActorParametersSha256=identity_owner.actor_parameters_sha256(current.model),
        frozenModelFieldsSha256=b.digest({key: value for key, value in current.model.items()
            if key not in b.PARAMETERS[:-2]}),
        trainingSha256=training.sha256, anchorSha256=training.anchor_sha256, predecessor=predecessor,
        publicCoverage=dict(trainingReady=training.ready_count,
            validationReady=training.validation_ready_count),
        epochSeeds=list(config.epochSeeds), originalDeadline=config.deadline,
        sourceSha256={Path(path).name: b.file_digest(path) for path in sources})
    b.atomic_json(output/'identity.json', identity)
    history, accepted, steps = paired_math.fit_epochs(actor, optimizer, prepared,
        config.epochSeeds, config, step=epoch, metrics=anchor_metrics,
        publish=lambda history: b.atomic_json(output/'history.json', history))
    model = actor.artifact()
    b.require(all(model[name] == current.model[name] for name in b.PARAMETERS[-2:]),
        'Paired actor fitting must preserve both value parameter arrays exactly.')
    changed = identity_owner.actor_parameters_sha256(model) != identity['inputActorParametersSha256']
    b.require(identity['trainingSha256'] == training.sha256,
        'Complete immutable TRAIN inputs must survive optimization unchanged.')
    b.require(all(b.file_digest(path) == identity['sourceSha256'][Path(path).name]
        for path in sources),
        'The original numerical source owners must remain unchanged until publication.')
    b.atomic_json(output/'policy.json', model)
    b.atomic_json(output/'checkpoint.json', dict(kind=prefix+'-adam-1',
        model=model, optimizer=b.encode_optimizer(optimizer), acceptedEpochs=accepted, adamSteps=steps))
    done = dict(kind=prefix+'-fit-complete-1', complete=True, identity=identity,
        acceptedEpochs=accepted, attemptedEpochs=len(history)-1, actorChanged=changed,
        operationalPassed=accepted > 0 and changed, adamSteps=steps,
        stopReason='epoch-limit' if accepted == 20 else 'original-kl-limit',
        outputModelSha256=b.file_digest(output/'policy.json'),
        artifacts={name: b.file_digest(output/name) for name in
            ('identity.json', 'policy.json', 'checkpoint.json', 'history.json')})
    b.atomic_json(output/'finished.json', done)
    return completed(output,objective=objective)


def main():
    """Fit-only projection of a verified native corpus; no collection, qualification or activation."""
    import argparse
    import token_paired_campaign_plan as plan_owner
    from token_paired_corpus import Corpus
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--objective',choices=objectives.VERSIONS,default=objectives.MIXED)
    for name in ('protocol','corpus','original-corpus','original-actor','current-actor','dictionary','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--deadline-epoch',type=float,required=True)
    parser.add_argument('--previous',type=Path)
    args=parser.parse_args()
    b.number(args.deadline_epoch,'Original absolute deadline',1.)
    b.require(time.time()<args.deadline_epoch,'The original numerical deadline expired; no corpus will be opened.')
    plan=plan_owner.Plan.from_record(plan_owner.read(args.protocol))
    source=Corpus(plan,plan_owner.subject(args.corpus));anchor=Corpus(plan,plan_owner.subject(args.original_corpus))
    original=data.FrozenActor(args.original_actor.read_bytes(),b.file_digest(args.original_actor))
    current=data.FrozenActor(args.current_actor.read_bytes(),b.file_digest(args.current_actor))
    context=source.context(args.dictionary,original,current,objective=args.objective)
    training=source.training(context,current.model,original.model,anchor)
    config=Config(tuple(plan.record['iterations'][context.iteration-1]['shuffleSeeds']),args.deadline_epoch)
    result=fit(training,original,current,args.dictionary.read_bytes(),args.output,config,
        previous=args.previous,objective=args.objective)
    print(json.dumps(dict(kind='token-paired-fit-cli-1',objectiveVersion=args.objective,
        fitCompletion=plan_owner.subject(args.output/'finished.json'),operationalPassed=result['operationalPassed'],
        qualificationClaimed=False,validationPerformed=False,activated=False),sort_keys=True))


if __name__=='__main__':main()
