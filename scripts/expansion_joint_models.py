"""Twelve-coordinate evolution around the frozen qualified expansion/token policy.

The original eight expansion coordinates act only in the expansion overlay. Four
public target coordinates act in both model files, preserving the native overlay
contract. Coordinates are deltas from the qualified anchor, not the older F1.
"""
import copy

import evolution_coordinates as coordinate
import expansion_evolution as evolution

read, sha, require = evolution.read, evolution.sha, evolution.require
VERSION = 'expansion-joint-search-1'
LOCAL_VERSION = 'expansion-joint-local-step-search-1'
LOCAL_SIGMAS = (.025, .05, .1, .2)
PRIOR_AUDIT = '6b4c12071b0dde1bb50cd818eefae3a0350d302de46576a6287832ec233ea896'
FAILURE_POLICY = 'STOP_JOINT_WEIGHT_SEARCH_FAMILY_PENDING_NEW_MECHANISM'
TARGET_FEATURES = ('expansion.targetMarket', 'expansion.targetIncomeShare',
                   'expansion.targetAdvanceShare', 'expansion.targetCashShare')
FEATURES = (*evolution.FEATURES, *TARGET_FEATURES)
NORMALIZATION_SHA = '3d32f6d542304d66b0398a2ff4a781b409e5dfb8ac0e3b5dbbc9fdc068dc3175'
ANCHORS = {
    'E+T': ('0c24593a456816c9b6616b6ade2aa7b74ac3c41f0cbd0745b5b912675caa835b',
            '3b7f677595670972253e8f034fd052178237c1547017c321e33e770e3de176b9'),
    'E+T+M8': ('b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4',
               'feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'),
}
MILITARY_PROTOCOL = 'adb0c01717cf17c3531e2a9af9603d7180fbccc9299b9edcba8edaf090210a56'
PURCHASE = {
    'baseline': 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0',
    'policy': '62dae4a44c84fca9ae39a50fdf93e1001d4b3e1ef55db736b1d7e387559a5a44',
}


def mutation_sigmas(kind):
    require(kind in (VERSION, LOCAL_VERSION), 'Unknown versioned joint mutation profile.')
    return LOCAL_SIGMAS if kind == LOCAL_VERSION else coordinate.DEFAULT_SIGMAS


def validate_local_step(protocol):
    """One predeclared scale hypothesis, with the rejected broader search retained."""
    local = protocol['kind'] == LOCAL_VERSION
    require(local == ('localStep' in protocol), 'Local mutation semantics require their explicit versioned owner.')
    if not local:
        return
    settings = protocol['localStep']
    require(set(settings) == {'priorAudit','failurePolicy'}
            and settings['failurePolicy'] == FAILURE_POLICY
            and protocol['jointExpansion']['anchor'] == 'E+T+M8',
            'The local-step study keeps the qualified military anchor and stops this search family on failure.')
    prior = settings['priorAudit']
    require(set(prior) == {'path','sha256'} and sha(prior['path']) == prior['sha256'] == PRIOR_AUDIT,
            'The local-scale hypothesis requires the exact completed broad-search audit.')
    audit = read(prior['path'])
    require(audit['status'] == 'passed-completed-rejection-audit'
            and audit['allPairsRecomputed'] == 4096 and len(audit['mutants']) == 28
            and audit['positiveRewardMutants'] == audit['positiveWinMutants'] == 0
            and audit['freshFinalBanksUnopened'],
            'Retain the rejected broad-search evidence without reusing its candidate selection or final banks.')


def profile(settings):
    """Retain both prior scales despite their different score temperatures.

    One shared temperature T uses scale T/8 for each target coordinate, exactly
    preserving its former raw delta of 8*x. Expansion scales and T are unchanged.
    """
    normalization = settings['expansionNormalization']
    coordinate.validate_normalization(normalization, evolution.FEATURES)
    temperature = normalization['temperature']
    require(temperature >= 8., 'The frozen expansion temperature must support target scales >=1.')
    return coordinate.CoordinateProfile(FEATURES,
        (*normalization['features'], *((temperature/8.,)*4)), temperature, (8.,)*12)


def export_models(expanded, baseline, expansion, settings):
    """Split a mutated expansion basis into ordinary and expansion phase roles."""
    ordinary = copy.deepcopy(baseline)
    ordinary['rulesVersion'] = expanded['rulesVersion']
    for name in TARGET_FEATURES:
        ordinary['weights'][name] = expanded['weights'].get(name, 0.)
    verify_models(ordinary, expanded, baseline, expansion, settings)
    return ordinary, expanded


def verify_models(ordinary, expanded, baseline, expansion, settings):
    for model, original in ((ordinary, baseline), (expanded, expansion)):
        require({k:v for k,v in model.items() if k not in ('weights', 'rulesVersion')}
                == {k:v for k,v in original.items() if k not in ('weights', 'rulesVersion')},
                'Joint models must preserve every non-weight model field.')
    require(all(model['weights'].get(name, 0.) == 0.
                for model in (baseline, expansion) for name in TARGET_FEATURES),
            'Joint target coordinates require the exact zero-valued anchor.')
    target_profile = coordinate.CoordinateProfile(TARGET_FEATURES, (1.,)*4, 8., (8.,)*4)
    ordinary_values = coordinate.coordinates(ordinary, baseline, target_profile)
    expanded_values = coordinate.coordinates(expanded, expansion, profile(settings))
    require(all(abs(value) <= 8.+1e-10 for value in ordinary_values+expanded_values),
            'Joint mutations must retain their frozen normalized bounds.')
    require(all(ordinary['weights'].get(name, 0.) == expanded['weights'].get(name, 0.)
                for name in TARGET_FEATURES),
            'Both model files must carry exactly identical target coefficients.')
    require(ordinary['rulesVersion'] == expanded['rulesVersion'] == expansion['rulesVersion'],
            'Both joint model files must use the target rules version.')
    coordinate.coordinates(expanded, ordinary, coordinate.CoordinateProfile(
        evolution.FEATURES, (1.,)*8, 1., (8.,)*8))


def validate_anchor(protocol):
    settings = protocol['jointExpansion']
    require(set(settings) == {'anchor', 'expansionNormalization', 'normalizationSource',
                             'militaryQualification'} and settings['anchor'] in ANCHORS,
            'Joint search requires its explicit frozen anchor and normalization provenance.')
    expected = ANCHORS[settings['anchor']]
    require(tuple(sha(protocol[key]) for key in ('baseline', 'expansionModel')) == expected,
            'Joint search must start from the exact qualified current policy components.')
    source = settings['normalizationSource']
    require(set(source) == {'path', 'sha256'} and sha(source['path']) == source['sha256'] == NORMALIZATION_SHA,
            'Expansion scales must retain the original training-only normalization source.')
    original = read(source['path'])
    require(original['featureNames'] == list(evolution.FEATURES)
            and original['normalization'] == settings['expansionNormalization']
            and len(original['trainEntries']) == 30,
            'Joint expansion normalization must be exactly the original30 training decisions.')
    military = settings['militaryQualification']
    if settings['anchor'] == 'E+T':
        require(military is None, 'The ordinary combined anchor must not claim military qualification.')
        return
    require(isinstance(military, dict) and set(military) == {'audit', 'confirm', 'heldout'},
            'A military anchor requires its reviewed terminal audit and both final reports.')
    for item in military.values():
        require(set(item) == {'path', 'sha256'} and sha(item['path']) == item['sha256'],
                'A frozen military qualification receipt changed.')
    audit = read(military['audit']['path'])
    require(audit['status'] == 'completed' and audit['qualificationPassed'] is True
            and audit['protocolSha256'] == MILITARY_PROTOCOL,
            'Military coefficients may be frozen only after independent terminal qualification.')
    for stage in ('confirm', 'heldout'):
        report = read(military[stage]['path'])
        require(report['stage'] == stage and report['passed'] is True
                and report['protocolSha256'] == MILITARY_PROTOCOL
                and (report['candidateSha256'], report['expansionCandidateSha256']) == expected
                and (stage != 'heldout' or report['qualificationPassed'] is True)
                and evolution.qualification(report['rawMetrics'], stage)[0],
                'Both exact military components must retain passed dual99 percent final gates.')
