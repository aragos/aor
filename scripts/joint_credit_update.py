"""Atomic pair publication for two original actor heads and one detached joint credit clock.

The legacy checked-prefix pair owner remains unchanged. This explicit path starts both heads
with fresh actor-only Adam, or carries their matching five-slot histories within the same clock
and predictor. Both objectives are bound before the first update. No half-updated bundle is
published after a failure, time limit or changed input; phase evidence remains for diagnosis.
"""
from dataclasses import asdict
from pathlib import Path
import sys

import joint_credit_targets as credit
import joint_phase_objective as objective
import joint_trajectory as joint
import purchase_policy as policy


VERSION = 'joint-credit-two-actor-update-1'


def phase_inputs(targets, configs, checkpoints):
    """Validate both immutable phase subjects and all requested carries before either update."""
    policy.require(isinstance(targets, credit.DetachedJointTargets),
                   'A pair requires already validated typed detached joint targets.')
    policy.require(set(configs) == set(joint.PHASES)
        and (checkpoints is None or set(checkpoints) == set(joint.PHASES)),
        'Provide both phase configurations and either no carry or both original checkpoints.')
    collection = targets.collection
    policy.require(isinstance(collection, joint.JointCollection),
                   'Detached pair inputs must come from the existing validated joint collection owner.')
    policy.require(all(collection.rows[phase] for phase in joint.PHASES),
        'An entirely empty phase cannot fit or publish half of a joint pair; preserve its corpus.')
    carries = {phase: None for phase in joint.PHASES} if checkpoints is None else dict(checkpoints)
    policy.require(all(value is None for value in carries.values())
        or all(value is not None for value in carries.values()),
        'Initialize both actor heads with fresh Adam or carry both matching phase histories.')
    pins = dict(targets.pins)
    objects, identities, continuations = {}, {}, {}
    for phase in joint.PHASES:
        config = configs[phase]; config.validate(joint.CONTRACTS[phase])
        policy.require(config.threads == 1, 'Joint actor updates retain one numerical CPU thread.')
        typed = objective.JointPhaseActorOnlyObjective(targets, objective.Phase(phase))
        typed.validate(joint.CONTRACTS[phase], config)
        head = collection.bundle[phase]
        rows, provenance = targets.phase_reader(collection.directory, head['path'],
            collection.models[phase], contract=joint.CONTRACTS[phase])
        _, identity = typed.bind(rows, provenance, head['sha256'])
        continuation = None
        if carries[phase] is not None:
            path = joint.pin(carries[phase], pins)
            for name in ('identity.json', 'finished.json', 'policy.json'):
                joint.pin(path.parent/name, pins)
            continuation = objective.JointPhaseAdamContinuation().validate(path, identity,
                collection.models[phase], config)
            identity['optimizerInitialization'] = 'matching-joint-phase-adam'
            carries[phase] = path
        objects[phase], identities[phase] = typed, identity
        continuations[phase] = continuation
    for module in (credit, objective, joint, policy): joint.pin(module.__file__, pins)
    joint.pin(__file__, pins)
    joint.unchanged(pins)
    return objects, identities, carries, continuations, pins


def completed_phase(directory, targets, phase, config, expected_objective, carry, continuation):
    """Require exact completed epoch-rollback evidence, original labels and retained value head."""
    directory = Path(directory); collection = targets.collection
    identity = joint.read(directory/'identity.json'); report = joint.read(directory/'finished.json')
    payload = policy.read_checkpoint(directory/'checkpoint.json')
    original = collection.bundle[phase]
    _, provenance = targets.phase_reader(collection.directory, original['path'],
        collection.models[phase], contract=joint.CONTRACTS[phase])
    expected = dict(schemaVersion=1, actorVersion=joint.CONTRACTS[phase].actor,
        modelSha256=original['sha256'], data=provenance, config=asdict(config),
        learnerSha256=joint.sha(policy.__file__), torchVersion=policy.torch.__version__,
        pythonVersion=sys.version, optimizerFromSha256=joint.sha(carry) if carry else None,
        entrypointSha256=joint.sha(__file__),
        collectionReaderSource=dict(path=str(Path(credit.__file__).resolve()), sha256=joint.sha(credit.__file__)),
        jointActorOnlyObjective=expected_objective, jointActorObjectiveSha256=joint.sha(objective.__file__))
    if continuation is not None: expected['jointActorOptimizerContinuation'] = continuation
    policy.require({key:value for key,value in identity.items() if key!='deadlineEpochSeconds'} == expected,
        'Completed phase identity must retain the original pair, detached clock, critic and exact Adam source.')
    policy.require(report['schemaVersion'] == 1 and report['identitySha256'] == policy.digest(identity)
        and report['inputModelSha256'] == original['sha256']
        and report['outputModelSha256'] == joint.sha(directory/'policy.json')
        and joint.read(directory/'policy.json') == payload['model']
        and report['jointActorOnlyObjective'] == expected_objective
        and report['history'] == payload['history'] and report['acceptedEpochs'] == payload['epoch']
        and report['games'] == collection.common['games']
        and report['decisions'] == len(collection.rows[phase])
        and report['gameMeanReward'] == collection.common['gameMeanReward']
        and report['stopReason'] == (payload['stopReason'] or 'epochs-complete')
        and report['stopReason'] in ('epochs-complete','behavior-kl-limit'),
        'Both phase updates must finish under their original epoch rollback contract before publication.')
    model = policy.validate_model(payload['model'], contract=joint.CONTRACTS[phase])
    policy.require(all(model[name] == collection.models[phase][name] for name in ('valueWeights','valueBias')),
        'Both original value-head parameter arrays must remain unchanged.')
    # The typed continuation owner also validates fresh-empty or complete inherited actor slots.
    objective.JointPhaseAdamContinuation().validate(directory/'checkpoint.json', expected_objective, model, config)
    return dict(identitySha256=policy.digest(identity), policySha256=joint.sha(directory/'policy.json'),
        checkpointSha256=joint.sha(directory/'checkpoint.json'), completionSha256=joint.sha(directory/'finished.json'),
        decisions=len(collection.rows[phase]), acceptedEpochs=report['acceptedEpochs'], stopReason=report['stopReason'])


def update_pair(targets, configs, output, *, checkpoints=None, guard=lambda: None):
    """One attempt on one fixed batch; the outer bounded campaign owns the shared deadline.

    ``targets`` owns both original heads, one immutable prediction file, all complete worlds and
    one selected clock. This function never regenerates labels from the first fitted sibling.
    ``guard`` checks the caller's original global allowance before each phase and publication.
    No resume/retry is implicit; interrupted phase artifacts must remain untouched.
    """
    output = Path(output).resolve()
    policy.require(not output.exists(), 'A joint-credit pair requires a new one-attempt output directory.')
    guard()
    objects, objectives, carries, continuations, pins = phase_inputs(targets, configs, checkpoints)
    collection = targets.collection
    identity = dict(kind=VERSION, behaviorBundle=collection.bundle, collection=collection.common,
        objective=targets.identity, configs={phase:asdict(configs[phase]) for phase in joint.PHASES},
        optimizerFrom={phase:None if carries[phase] is None else dict(
            path=str(carries[phase]),sha256=joint.sha(carries[phase])) for phase in joint.PHASES},
        inputHashes=pins, maximumAttempts=1, automaticResume=False)
    joint.immutable_json(output/'identity.json', identity)
    reports, output_pins = {}, {}
    for phase in joint.PHASES:
        guard(); joint.unchanged(pins)
        directory = output/phase
        policy.update(collection.bundle[phase]['path'], collection.directory, configs[phase], directory,
            optimizer_from=carries[phase], contract=joint.CONTRACTS[phase], entrypoint=__file__,
            collection_reader=targets.phase_reader, joint_actor_only=objects[phase],
            joint_optimizer_continuation=(objective.JointPhaseAdamContinuation() if carries[phase] else None))
        reports[phase] = completed_phase(directory, targets, phase, configs[phase],
            objectives[phase], carries[phase], continuations[phase])
        joint.immutable_json(output/(phase+'-completed.json'), reports[phase])
        for name in ('identity.json','finished.json','policy.json','checkpoint.json'):
            joint.pin(directory/name, output_pins)
        joint.pin(output/(phase+'-completed.json'), output_pins)
    guard(); joint.unchanged(pins); joint.unchanged(output_pins)
    bundle = dict(schemaVersion=1, kind='joint-card-purchase-1', configuration=collection.configuration,
        baselineSha256=collection.baseline_sha, **{phase:dict(path=str(output/phase/'policy.json'),
            sha256=reports[phase]['policySha256']) for phase in joint.PHASES})
    joint.immutable_json(output/'bundle.json', bundle)
    report = dict(kind=VERSION, identitySha256=policy.digest(identity),
        behaviorBundleSha256=collection.bundle['sha256'], outputBundleSha256=joint.sha(output/'bundle.json'),
        clock=targets.identity['estimator'], commonOrigins=collection.origins,
        gameHashes=collection.common['gameHashes'], phases=reports, outputHashes=output_pins,
        allInputHashesUnchanged=True, strengthClaim=False, policyPromotion=False)
    joint.immutable_json(output/'finished.json', report)
    return report
