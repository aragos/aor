"""Atomic three-head terminal-MC publication; one behavior batch, three independent actor Adams."""
from dataclasses import asdict
from pathlib import Path
import sys

import joint_trajectory as joint
import purchase_policy as policy
import triple_mc as mc
from triple_trajectory import TripleCollection


VERSION = 'triple-mc-update-1'
BUNDLE = 'triple-actor-bundle-1'


def previous_carries(directory, collection, pins):
    """Only a fully published predecessor may supply the next three checkpoint subjects."""
    if directory is None:
        return {phase:None for phase in mc.PHASES}
    directory = Path(directory).resolve(strict=True)
    finished = joint.read(joint.pin(directory/'finished.json', pins))
    identity = joint.read(joint.pin(directory/'identity.json', pins))
    policy.require(finished['kind'] == identity['kind'] == VERSION and
        finished['identitySha256'] == policy.digest(identity) and
        set(finished['phases']) == set(mc.PHASES) and finished['complete'] is True,
        'Carry requires one fully completed three-head transaction, never independent phase outputs.')
    expected = {f'{phase}/{name}' for phase in mc.PHASES
        for name in ('identity.json','finished.json','policy.json','checkpoint.json')}
    expected |= {'bundle.json','triple-bundle.json'}
    policy.require(set(finished['outputHashes']) == expected,
        'Triple completion must bind every phase, original C/P bundle and triple bundle exactly.')
    for name, digest in finished['outputHashes'].items():
        policy.require(joint.sha(joint.pin(directory/name, pins)) == digest,
            'A previous triple output changed: '+name)
    bundle = joint.read(directory/'triple-bundle.json')
    cp, _ = joint.load_bundle(directory/'bundle.json', collection.configuration,
        collection.baseline_sha, pins)
    policy.require(bundle == dict(schemaVersion=1, kind=BUNDLE, configuration=collection.configuration,
        baselineSha256=collection.baseline_sha,
        expansion=dict(path=str(directory/'expansion/policy.json'), sha256=joint.sha(directory/'expansion/policy.json')),
        jointBundle=dict(path=str(directory/'bundle.json'), sha256=joint.sha(directory/'bundle.json'))),
        'The preceding three-head bundle must bind the exact published actor paths and bytes.')
    policy.require(cp['sha256'] == collection.bundle['sha256'] and all(
        joint.sha(directory/phase/'policy.json') == collection.heads[phase]['sha256'] for phase in mc.PHASES),
        'All collecting heads must be the outputs of the same completed predecessor.')
    for phase in mc.PHASES:
        phase_identity = joint.read(directory/phase/'identity.json')
        phase_finished = joint.read(directory/phase/'finished.json')
        expected_summary = dict(policySha256=joint.sha(directory/phase/'policy.json'),
            checkpointSha256=joint.sha(directory/phase/'checkpoint.json'),
            identitySha256=joint.sha(directory/phase/'identity.json'),
            completionSha256=joint.sha(directory/phase/'finished.json'),decisions=phase_finished['decisions'],
            acceptedEpochs=phase_finished['acceptedEpochs'],stopReason=phase_finished['stopReason'])
        policy.require(finished['phases'][phase] == expected_summary,
            'Previous triple phase summary must bind its exact completed actor and counters.')
        policy.require(phase_identity['tripleMcObjective']['behaviorModels'] == identity['behaviorModels']
            and phase_identity['tripleMcObjective']['collectionSha256'] == identity['collection']['collectionSha256']
            and phase_identity['config'] == identity['configs'][phase],
            'Previous phases must retain their common original behavior and transaction config.')
    return {phase:directory/phase/'checkpoint.json' for phase in mc.PHASES}


def phase_inputs(collection, configs, previous):
    policy.require(isinstance(collection, TripleCollection) and set(configs) == set(mc.PHASES),
        'A triple update requires its typed complete collection and all three configurations.')
    policy.require(all(collection.rows[phase] for phase in mc.PHASES),
        'An entirely empty phase stops before any update; retain the complete collection without a next bundle.')
    collection.unchanged(); pins = dict(collection.pins)
    carries = previous_carries(previous, collection, pins)
    objects, identities, continuations = {}, {}, {}
    for phase in mc.PHASES:
        config = configs[phase]; config.validate(mc.CONTRACTS[phase])
        objective = mc.TripleMcObjective(collection, mc.Phase(phase))
        objective.validate(mc.CONTRACTS[phase], config)
        rows, provenance = objective.phase_reader(collection.directory, collection.heads[phase]['path'],
            collection.models[phase], contract=mc.CONTRACTS[phase])
        _, identity = objective.bind(rows, provenance, collection.heads[phase]['sha256'])
        continuation = None
        if carries[phase] is not None:
            continuation = mc.TripleMcAdamContinuation().validate(carries[phase], identity,
                collection.models[phase], config)
            identity['optimizerInitialization'] = 'matching-triple-mc-adam'
        objects[phase], identities[phase], continuations[phase] = objective, identity, continuation
    for path in (__file__, mc.__file__, policy.__file__, joint.__file__):joint.pin(path, pins)
    joint.unchanged(pins)
    return objects, identities, carries, continuations, pins


def completed_phase(directory, collection, phase, config, objective, carry, continuation):
    """Validate committed epoch rollback and every original identity before publishing a bundle."""
    identity = joint.read(directory/'identity.json'); report = joint.read(directory/'finished.json')
    checkpoint = policy.read_checkpoint(directory/'checkpoint.json')
    typed = mc.TripleMcObjective(collection, mc.Phase(phase))
    _, provenance = typed.phase_reader(collection.directory, collection.heads[phase]['path'],
        collection.models[phase], contract=mc.CONTRACTS[phase])
    expected = dict(schemaVersion=1, actorVersion=mc.CONTRACTS[phase].actor,
        modelSha256=collection.heads[phase]['sha256'], data=provenance, config=asdict(config),
        learnerSha256=joint.sha(policy.__file__), torchVersion=policy.torch.__version__, pythonVersion=sys.version,
        optimizerFromSha256=joint.sha(carry) if carry else None, entrypointSha256=joint.sha(__file__),
        collectionReaderSource=dict(path=str(Path(mc.__file__).resolve()), sha256=joint.sha(mc.__file__)),
        tripleMcObjective=objective, tripleMcOwnerSha256=joint.sha(mc.__file__))
    if continuation is not None:expected['tripleMcOptimizerContinuation'] = continuation
    policy.require({k:v for k,v in identity.items() if k != 'deadlineEpochSeconds'} == expected,
        'Completed triple phase changed its original behavior, terminal objective or Adam subject.')
    policy.require(report['schemaVersion'] == 1 and report['identitySha256'] == policy.digest(identity)
        and report['inputModelSha256'] == expected['modelSha256']
        and report['outputModelSha256'] == joint.sha(directory/'policy.json')
        and joint.read(directory/'policy.json') == checkpoint['model']
        and report['tripleMcObjective'] == objective and report['history'] == checkpoint['history']
        and report['acceptedEpochs'] == checkpoint['epoch'] and report['games'] == collection.common['games']
        and report['decisions'] == len(collection.rows[phase])
        and report['gameMeanReward'] == collection.common['gameMeanReward']
        and report['stopReason'] == (checkpoint['stopReason'] or 'epochs-complete')
        and report['stopReason'] in ('epochs-complete','behavior-kl-limit'),
        'Every triple phase must finish its original bounded epoch contract before publication.')
    model = policy.validate_model(checkpoint['model'], contract=mc.CONTRACTS[phase])
    policy.require(all(model[key] == collection.models[phase][key] for key in ('valueWeights','valueBias')),
        'Triple MC must freeze both original value-head arrays.')
    mc.TripleMcAdamContinuation().validate(directory/'checkpoint.json', objective, model, config)
    return dict(policySha256=joint.sha(directory/'policy.json'), checkpointSha256=joint.sha(directory/'checkpoint.json'),
        identitySha256=joint.sha(directory/'identity.json'), completionSha256=joint.sha(directory/'finished.json'),
        decisions=len(collection.rows[phase]), acceptedEpochs=report['acceptedEpochs'], stopReason=report['stopReason'])


def update_phase(collection, phase, config, directory, typed, objective, carry, continuation):
    """The shared numerical entrypoint; transaction owners prevalidate their complete active set."""
    policy.update(collection.heads[phase]['path'], collection.directory, config, directory,
        optimizer_from=carry, contract=mc.CONTRACTS[phase], entrypoint=__file__,
        collection_reader=typed.phase_reader, triple_mc=typed,
        triple_optimizer_continuation=mc.TripleMcAdamContinuation() if carry else None)
    return completed_phase(directory, collection, phase, config, objective, carry, continuation)


def update_triple(collection, configs, output, *, previous=None, guard=lambda:None):
    """One atomic publication; no resume, recollection, critic inference or phase substitution."""
    output = Path(output).resolve()
    policy.require(not output.exists(), 'A triple MC update requires an unused one-attempt directory.')
    guard()
    objects, objectives, carries, continuations, pins = phase_inputs(collection, configs, previous)
    identity = dict(kind=VERSION, behaviorModels={p:collection.heads[p]['sha256'] for p in mc.PHASES},
        collection=collection.common, ecology=collection.ecology,
        configs={p:asdict(configs[p]) for p in mc.PHASES}, inputHashes=pins,
        previous=None if previous is None else dict(path=str(Path(previous).resolve()),
            completionSha256=joint.sha(Path(previous)/'finished.json')), maximumAttempts=1, automaticResume=False)
    joint.immutable_json(output/'identity.json', identity)
    reports, outputs = {}, {}
    for phase in mc.PHASES:
        guard(); joint.unchanged(pins)
        directory = output/phase
        reports[phase] = update_phase(collection, phase, configs[phase], directory, objects[phase],
            objectives[phase], carries[phase], continuations[phase])
        for name in ('identity.json','finished.json','policy.json','checkpoint.json'):
            joint.pin(directory/name, outputs)
    guard(); joint.unchanged(pins); joint.unchanged(outputs)
    cp = dict(schemaVersion=1, kind='joint-card-purchase-1', configuration=collection.configuration,
        baselineSha256=collection.baseline_sha, **{p:dict(path=str(output/p/'policy.json'),
            sha256=reports[p]['policySha256']) for p in ('card','purchase')})
    joint.immutable_json(output/'bundle.json', cp)
    triple = dict(schemaVersion=1, kind=BUNDLE, configuration=collection.configuration,
        baselineSha256=collection.baseline_sha,
        expansion=dict(path=str(output/'expansion/policy.json'), sha256=reports['expansion']['policySha256']),
        jointBundle=dict(path=str(output/'bundle.json'), sha256=joint.sha(output/'bundle.json')))
    joint.immutable_json(output/'triple-bundle.json', triple)
    for name in ('bundle.json','triple-bundle.json'):joint.pin(output/name, outputs)
    report = dict(kind=VERSION, complete=True, identitySha256=policy.digest(identity), phases=reports,
        outputHashes={str(Path(path).relative_to(output)):digest for path,digest in outputs.items()},
        allInputHashesUnchanged=True, strengthClaim=False, policyPromotion=False)
    joint.immutable_json(output/'finished.json', report)
    return report
