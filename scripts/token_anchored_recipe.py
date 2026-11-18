"""The warm-anchor numerical recipe is portable metadata, independent of Torch imports."""
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
