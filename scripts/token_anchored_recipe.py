"""Fixed warm-anchor numerical recipes, independent of Torch imports."""
from token_anchor_distribution import RECORD


RECIPE = dict(version='token-anchored-paired-win-improvement-1', epochs=20, batchWorlds=32,
    learningRate=.0003, gradientNorm=.5, originalKlCoefficient=.1,
    maximumMeanOriginalKl=.25, maximumStateOriginalKl=.75,
    gainWeighting='equal-ready-root-horvitz-thompson-sum',
    anchorWeighting='equal-represented-world-equal-deduplicated-state',
    klOrientation='current-to-original', optimizer='fresh-adam-each-iteration',
    frozenParameters=['valueWeights', 'valueBias'], objectiveVersion='paired-win-share-1',
    distribution=RECORD, minimumReadyFraction=.5,
    residual='raw-unclipped', originalAnchor='frozen94-c05-plus-uniform')


PROXIMAL_RECIPE = dict(RECIPE, version='token-anchored-proximal-paired-win-1',
    originalKlCoefficient=.01, maximumMeanCollectionKl=.025, maximumStateCollectionKl=.10,
    collectionKlOrientation='current-to-collection', collectionWeighting='equal-ready-root')


def validate(record):
    """Resolve a complete frozen recipe, never an arbitrary hyperparameter override."""
    for recipe in (RECIPE, PROXIMAL_RECIPE):
        if record == recipe:
            return recipe
    raise ValueError('The warm fit requires an exact supported numerical recipe.')
