"""Explicit expansion-only matched control, retaining exact C/P subjects without fake updates."""
from dataclasses import asdict
from pathlib import Path

import joint_trajectory as joint
import purchase_policy as policy
import triple_mc as mc
import triple_mc_update as core
from triple_trajectory import TripleCollection

VERSION = 'triple-mc-e-only-control-1'


def fixed_bundle(collection, subject, pins):
    policy.require(set(subject) == {'path','sha256'}, 'The E-only control requires its exact frozen G3 bundle subject.')
    path = joint.pin(subject['path'], pins)
    descriptor, models = joint.load_bundle(path,collection.configuration,collection.baseline_sha,pins)
    policy.require(joint.sha(path) == subject['sha256'] and descriptor == collection.bundle
        and all(models[p] == collection.models[p] for p in ('card','purchase')),
        'E-only collection must retain both exact original G3 actors and their immutable bundle.')
    return descriptor


def previous_carry(previous, collection, frozen, pins):
    if previous is None:return None
    previous = Path(previous).resolve(strict=True)
    finished=joint.read(joint.pin(previous/'finished.json',pins));identity=joint.read(joint.pin(previous/'identity.json',pins))
    expected={f'expansion/{name}.json' for name in ('identity','finished','policy','checkpoint')}|{'triple-bundle.json'}
    policy.require(finished['kind'] == identity['kind'] == VERSION and finished['complete'] is True
        and finished['identitySha256'] == policy.digest(identity) and set(finished['phases']) == {'expansion'}
        and set(finished['outputHashes']) == expected and identity['frozenCardPurchase'] == frozen
        and finished['frozenCardPurchase'] == frozen,
        'An E-only control can carry only its completed same-arm expansion update and exact frozen C/P.')
    for name,digest in finished['outputHashes'].items():
        policy.require(joint.sha(joint.pin(previous/name,pins)) == digest, 'A previous E-only output changed: '+name)
    model_path=previous/'expansion/policy.json'
    policy.require(joint.sha(model_path) == collection.heads['expansion']['sha256'],
        'The collecting E actor must equal the immediately preceding control output.')
    phase_identity=joint.read(previous/'expansion/identity.json');phase_done=joint.read(previous/'expansion/finished.json')
    summary=dict(policySha256=joint.sha(model_path),checkpointSha256=joint.sha(previous/'expansion/checkpoint.json'),
        identitySha256=joint.sha(previous/'expansion/identity.json'),completionSha256=joint.sha(previous/'expansion/finished.json'),
        decisions=phase_done['decisions'],acceptedEpochs=phase_done['acceptedEpochs'],stopReason=phase_done['stopReason'])
    policy.require(finished['phases']['expansion'] == summary
        and phase_identity['tripleMcObjective']['behaviorModels'] == identity['behaviorModels']
        and phase_identity['tripleMcObjective']['collectionSha256'] == identity['collection']['collectionSha256']
        and phase_identity['config'] == identity['config'], 'Control carry must retain its original phase transaction.')
    policy.require(joint.read(previous/'triple-bundle.json') == bundle(previous,collection,frozen,summary['policySha256']),
        'The previous control bundle cannot substitute a C/P actor or expansion subject.')
    return previous/'expansion/checkpoint.json'


def bundle(output, collection, frozen, actor_sha):
    return dict(schemaVersion=1,kind=core.BUNDLE,configuration=collection.configuration,
        baselineSha256=collection.baseline_sha,
        expansion=dict(path=str(output/'expansion/policy.json'),sha256=actor_sha),
        jointBundle={key:frozen[key] for key in ('path','sha256')})


def update_control(collection, config, output, *, frozen, previous=None, guard=lambda:None):
    """No C/P policy or Adam output is constructed; the old family75 updater remains separate."""
    output=Path(output).resolve()
    policy.require(isinstance(collection,TripleCollection) and not output.exists(),
        'E-only control requires the new typed triple collection and one unused output.')
    guard();collection.unchanged();pins=dict(collection.pins)
    descriptor=fixed_bundle(collection,frozen,pins)
    config.validate(mc.CONTRACTS['expansion']);typed=mc.TripleMcObjective(collection,mc.Phase.EXPANSION)
    typed.validate(mc.CONTRACTS['expansion'],config)
    rows,provenance=typed.phase_reader(collection.directory,collection.heads['expansion']['path'],
        collection.models['expansion'],contract=mc.CONTRACTS['expansion'])
    _,objective=typed.bind(rows,provenance,collection.heads['expansion']['sha256'])
    carry=previous_carry(previous,collection,descriptor,pins);continuation=None
    if carry is not None:
        continuation=mc.TripleMcAdamContinuation().validate(carry,objective,collection.models['expansion'],config)
        objective['optimizerInitialization']='matching-triple-mc-adam'
    for module in (mc,core,policy,joint):joint.pin(module.__file__,pins)
    joint.pin(__file__,pins);joint.unchanged(pins)
    identity=dict(kind=VERSION,behaviorModels={p:collection.heads[p]['sha256'] for p in mc.PHASES},
        collection=collection.common,ecology=collection.ecology,config=asdict(config),
        frozenCardPurchase=descriptor,inputHashes=pins,previous=None if previous is None else
        dict(path=str(Path(previous).resolve()),completionSha256=joint.sha(Path(previous)/'finished.json')),
        maximumAttempts=1,automaticResume=False)
    joint.immutable_json(output/'identity.json',identity)
    guard();joint.unchanged(pins)
    result=core.update_phase(collection,'expansion',config,output/'expansion',typed,objective,carry,continuation)
    outputs={}
    for name in ('identity','finished','policy','checkpoint'):joint.pin(output/'expansion'/f'{name}.json',outputs)
    guard();collection.unchanged();joint.unchanged(pins);joint.unchanged(outputs)
    joint.immutable_json(output/'triple-bundle.json',bundle(output,collection,descriptor,result['policySha256']))
    joint.pin(output/'triple-bundle.json',outputs)
    report=dict(kind=VERSION,complete=True,identitySha256=policy.digest(identity),phases=dict(expansion=result),
        frozenCardPurchase=descriptor,outputHashes={str(Path(p).relative_to(output)):h for p,h in outputs.items()},
        allInputHashesUnchanged=True,strengthClaim=False,policyPromotion=False)
    joint.immutable_json(output/'finished.json',report)
    return report
