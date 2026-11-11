"""Four-coordinate target learning with paired ordinary and expansion model exports."""
from pathlib import Path

import evolution_coordinates as coordinate
import expansion_evolution as evolution

read, sha, document, require = evolution.read, evolution.sha, evolution.document, evolution.require
FEATURES = ('expansion.targetMarket', 'expansion.targetIncomeShare',
            'expansion.targetAdvanceShare', 'expansion.targetCashShare')
PROFILE = coordinate.CoordinateProfile(FEATURES, (1.,) * 4, 8., (8.,) * 4)
LINEUPS = {'mixed': ('F1', 'E', 'T', 'E+T'), 'homogeneous': ('E+T',)}


def expansion_model(ordinary, baseline, expansion):
    """Apply identical target additions so the native eight-coordinate role contract holds."""
    coordinates = coordinate.coordinates(ordinary, baseline, PROFILE)
    return coordinate.export_model(coordinates, expansion, PROFILE, ordinary['rulesVersion'])


def verify_models(ordinary, expanded, baseline, expansion):
    for model, original in ((ordinary, baseline), (expanded, expansion)):
        require({k:v for k,v in model.items() if k not in ('weights','rulesVersion')}
                == {k:v for k,v in original.items() if k not in ('weights','rulesVersion')},
                'Target models must preserve every non-weight model field.')
        values = coordinate.coordinates(model, original, PROFILE)
        require(all(abs(value) <= bound for value,bound in zip(values,PROFILE.bounds)),
                'Target coefficients must remain inside the fixed raw [-64,64] limits.')
    require(all(ordinary['weights'].get(name,0) - baseline['weights'].get(name,0)
                == expanded['weights'].get(name,0) - expansion['weights'].get(name,0) for name in FEATURES),
            'Ordinary and expansion target additions must match exactly.')
    require(ordinary['rulesVersion'] == expanded['rulesVersion'] == expansion['rulesVersion'],
            'Both model files must use the target rules version.')
    coordinate.coordinates(expanded, ordinary, coordinate.CoordinateProfile(
        evolution.FEATURES, (1.,)*8, 1., (8.,)*8))


def export_bundle(directory, ordinary, baseline, expansion):
    directory.mkdir(parents=True, exist_ok=False)
    expanded = expansion_model(ordinary,baseline,expansion)
    verify_models(ordinary,expanded,baseline,expansion)
    receipt = {}
    for role, model in (('ordinary',ordinary),('expansion',expanded)):
        path = directory / (role + '.json')
        document(path,model)
        receipt[role] = dict(path=str(path.resolve()),sha256=sha(path))
    path = directory/'bundle.json'
    document(path,receipt)
    return str(path.resolve())


def verify_bundle(path,protocol):
    bundle = read(path)
    require(set(bundle) == {'ordinary','expansion'}, 'Target bundle must bind both exact model files.')
    for item in bundle.values():
        require(set(item) == {'path','sha256'} and sha(item['path']) == item['sha256'],
                'Target bundle model bytes changed.')
    verify_models(read(bundle['ordinary']['path']),read(bundle['expansion']['path']),
                  read(protocol['baseline']),read(protocol['expansionModel']))
    return bundle


def bindings(protocol,ordinary,expansion,lineup):
    require(lineup in LINEUPS,'Use the fixed mixed or homogeneous rival lineup.')
    roles = LINEUPS[lineup]
    return dict(candidate=str(ordinary),incumbent=protocol['baseline'],
        opponents=[protocol['baseline']]*len(roles),
        expansionImitation=dict(candidateModel=str(expansion),incumbentModel=protocol['expansionModel'],
            opponentModels=[protocol['expansionModel'] if role in ('E','E+T') else None for role in roles]),
        tokenOrder=dict(candidateSelector='FIXED_16_TO_15',incumbentSelector='FIXED_16_TO_15',
            opponentSelectors=['FIXED_16_TO_15' if role in ('T','E+T') else 'ORDINARY' for role in roles]))
