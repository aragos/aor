"""Explicit frozen nonpurchase roles for a purchase-only adaptation experiment.

Environment provenance never becomes a learner input. The existing purchase reader
still recomputes every action, likelihood and value from actor-visible observations.
"""
from enum import Enum
from pathlib import Path

import automata_experiments as exp
import automata_purchase_continuation as legacy

read, sha, require = exp.read, exp.sha, exp.require
F1 = '0c24593a456816c9b6616b6ade2aa7b74ac3c41f0cbd0745b5b912675caa835b'
E = '3b7f677595670972253e8f034fd052178237c1547017c321e33e770e3de176b9'
C = '62dae4a44c84fca9ae39a50fdf93e1001d4b3e1ef55db736b1d7e387559a5a44'
F0 = 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0'
CONTINUATION = 'ARENA_OVERLAYS_1'
REPLAY_INDICES = [0, 85, 170, 255]
FEATURES = ['tokens', 'progress', 'reach', 'commodity', 'income', 'risk',
            'action.expansion', 'action.pass']
ROLES = ['F1', 'E', 'T', 'E+T']
FIXED = 'FIXED_16_TO_15'


class ArenaRivalEcology(Enum):
    MIXED = 'mixed-qualified-1'
    HOMOGENEOUS = 'homogeneous-qualified-1'


def bindings(expansion, *, arena=False, rival_ecology=ArenaRivalEcology.MIXED):
    """Role arrays follow ordinary opponent indices, never rotating seat positions."""
    require(isinstance(rival_ecology, ArenaRivalEcology)
            and (arena or rival_ecology is ArenaRivalEcology.MIXED),
            'Explicit homogeneous rivals are supported only by an arena, not collection.')
    models = dict(candidateModel=str(expansion), opponentModels=[None, str(expansion), None, str(expansion)])
    tokens = dict(candidateSelector=FIXED, opponentSelectors=['ORDINARY', 'ORDINARY', FIXED, FIXED])
    if rival_ecology is ArenaRivalEcology.HOMOGENEOUS:
        models['opponentModels'] = [str(expansion)] * 4
        tokens['opponentSelectors'] = [FIXED] * 4
    if arena:
        models['incumbentModel'] = str(expansion)
        tokens['incumbentSelector'] = FIXED
    return dict(expansionImitation=models, tokenOrder=tokens)


def verify_overlays(source, directory, reports, *, arena=False, expected_expansion=E,
                    rival_ecology=ArenaRivalEcology.MIXED):
    directory = Path(directory)
    expansion = source['expansionImitation']['candidateModel']
    require(all(source.get(key) == value for key, value in
                bindings(expansion, arena=arena, rival_ecology=rival_ecology).items()),
            'Purchase evaluation must preserve the exact controlled and declared rival roles.')
    require(sha(expansion) == expected_expansion,
            'The expansion owner must retain its exact declared qualified bytes.')
    metadata = dict(version='expansion-imitation-1', decision='expand', featureNames=FEATURES)
    pins = {}

    def snapshot(path, name):
        if path is None:
            return None
        require(sha(path) == expected_expansion and sha(directory / name) == expected_expansion,
                'Expansion snapshot differs from its declared model.')
        pins[name] = expected_expansion
        return dict(path=str(Path(path).resolve()), sha256=expected_expansion, snapshot=name)

    for role, value in source['expansionImitation'].items():
        metadata[role] = ([snapshot(path, f'expansion-imitation-opponent-{index}.json')
                           for index, path in enumerate(value)] if role == 'opponentModels'
                          else snapshot(value, 'expansion-imitation-' + role.removesuffix('Model') + '.json'))
    require(all(report.get('expansionImitation') == metadata
                and report.get('tokenOrder') == source['tokenOrder'] for report in reports),
            'Native nonpurchase metadata differs from its exact phase owners.')
    if not arena:
        require(source['continuationProtocol'] == CONTINUATION
                and source['replayIndices'] == REPLAY_INDICES
                and all(report.get('continuationProtocol') == CONTINUATION
                        and report.get('replayIndices') == REPLAY_INDICES for report in reports),
                'Purchase collection must declare its exact continuation and historical replay indices.')
    return pins


def verify_collection(directory, *, expected_ordinary=F1, expected_expansion=E):
    """Pure-Python provenance validation, reusable by the supervisor and numerical reader."""
    directory = Path(directory)
    source = read(directory / 'source.json')
    required = {'schemaVersion', 'configuration', 'games', 'seed', 'baseline', 'opponents', 'policy',
                'opponentPurchasePolicy', 'sharedPurchaseBaseline', 'experimentalRulesTransfer',
                'continuationProtocol', 'replayIndices', 'expansionImitation', 'tokenOrder'}
    require(set(source) == required and source['schemaVersion'] == 1 and source['games'] == 256
            and source['configuration'] == exp.CONFIGURATION
            and source['experimentalRulesTransfer'] == legacy.RULES_TRANSFER,
            'Use the explicit 256-game purchase adaptation source and canonical setup.')
    require(sha(source['baseline']) == expected_ordinary and len(source['opponents']) == 4
            and all(sha(path) == expected_ordinary for path in source['opponents'])
            and sha(source['opponentPurchasePolicy']) == C
            and sha(source['sharedPurchaseBaseline']) == F0,
            'Only the controlled purchase actor may learn; ordinary, rival actor and prior bytes stay fixed.')
    manifest = read(directory / 'collection.json')
    original = Path(manifest['source'])
    require(sha(original) == sha(directory / 'source.json'), 'Original purchase source changed after collection.')
    protocol = dict(configuration=source['configuration'], baseline=source['baseline'],
                    opponents=source['opponents'], prior=source['sharedPurchaseBaseline'],
                    purchasePolicy=source['opponentPurchasePolicy'])
    data, decisions = legacy.verify_collection(protocol, original, directory)
    pins = verify_overlays(source, directory, [manifest], expected_expansion=expected_expansion)
    for name in ('source.json', 'collection.json', 'state.json', 'finished.json', 'baseline.json',
                 'policy.json', 'shared-purchase-baseline.json', 'opponent-purchase-policy.json',
                 *[f'opponent-{index}.json' for index in range(4)]):
        pins[name] = sha(directory / name)
    replay_hashes = read(directory/'finished.json')['replaySha256']
    require(set(replay_hashes) == {f'replay-{index}.json' for index in REPLAY_INDICES},
            'Completed purchase data must retain every prospective canonical history.')
    for name, digest in replay_hashes.items():
        require(sha(directory/name) == digest, 'Completed purchase canonical history changed.')
        pins[name] = digest
    return dict(data, ecologyFiles=pins, readerSha256=sha(__file__)), decisions


def read_collection(directory, model_path, model, *, contract,
                    expected_ordinary=F1, expected_expansion=E):
    import purchase_policy as learner
    require(contract == learner.PURCHASE_CONTRACT, 'Purchase ecology cannot collect another actor family.')
    expected, decisions = verify_collection(directory, expected_ordinary=expected_ordinary,
                                           expected_expansion=expected_expansion)
    rows, provenance = learner.read_collection(directory, model_path, model, contract=contract)
    require(provenance == {key: value for key, value in expected.items()
                           if key not in ('ecologyFiles', 'readerSha256')}
            and len(rows) == decisions, 'Numerical behavior validation changed the completed purchase bank.')
    return rows, expected
