"""Fixed Windmill ATTEMPT-minus-PASS ridge; no loader, CLI, TEST or runtime activation.

The corpus adapter owns original stream/command/source provenance and complete coverage.
This numerical owner consumes detached four-stream outcomes with equal captured-origin weight.
"""
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import math
from pathlib import Path
import re

import numpy as np

from weighted_ridge import weighted_solve

DICTIONARY = Path(__file__).with_name('windmill_value_features.json')
FEATURE_NAMES = tuple(json.loads(DICTIONARY.read_text())['featureNames'])
DICTIONARY_SHA = hashlib.sha256(DICTIONARY.read_bytes()).hexdigest()
DIMENSION, PENALTY = 273, .1
METRICS = ('reward', 'winShare', 'rankReward', 'chaos')
assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES)) == DIMENSION
assert FEATURE_NAMES == tuple(sorted(FEATURE_NAMES)) and 'bias' in FEATURE_NAMES


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Split(Enum):
    TRAIN = 'TRAIN'
    VALIDATION = 'VALIDATION'


class Action(Enum):
    PASS = 'PASS'
    ATTEMPT = 'ATTEMPT'


@dataclass(frozen=True)
class Outcome:
    reward: float
    winShare: float
    rankReward: float
    chaos: float

    def __post_init__(self):
        require(all(type(getattr(self, key)) in (int, float) and math.isfinite(getattr(self, key))
                    and 0 <= getattr(self, key) <= 1 for key in METRICS),
                'Detached terminal metrics must be finite and normalized.')
        require(abs(self.reward-.8*self.winShare-.2*self.rankReward) < 1e-12,
                'Original reward must equal .8 winShare plus .2 rankReward.')
        require(self.chaos in (0, 1), 'Chaos is the actual binary terminal receipt.')


def features(values):
    result = np.asarray(values, dtype=np.float64)
    require(result.shape == (DIMENSION,) and np.isfinite(result).all(),
            'Use the fixed273 finite public columns in original units.')
    return result


@dataclass(frozen=True)
class Origin:
    originIndex: int
    split: Split
    sourceSha256: str
    publicFeatures: tuple[float, ...] | None
    passReturns: tuple[Outcome, ...] | None
    attemptReturns: tuple[Outcome, ...] | None
    incumbent: Action | None
    missingReason: str | None = None

    def __post_init__(self):
        # Split guards precede every feature, label and incumbent dereference.
        require(type(self.originIndex) is int and 0 <= self.originIndex < 2048,
                'Origins belong to the fixed eight256-world chunks.')
        local = self.originIndex % 256
        expected = Split.TRAIN if local < 192 else Split.VALIDATION if local < 224 else None
        require(expected is not None and self.split is expected,
                'Only fixed TRAIN/VALIDATION indices; TEST is forbidden.')
        require(isinstance(self.sourceSha256, str) and re.fullmatch('[0-9a-f]{64}', self.sourceSha256),
                'Each original origin requires its exact source hash.')
        if self.missingReason is not None:
            require(isinstance(self.missingReason, str) and bool(self.missingReason)
                    and self.publicFeatures is self.passReturns is self.attemptReturns is self.incumbent is None,
                    'Missing roots retain a reason, without fabricated features, returns or choices.')
            return
        require(self.publicFeatures is not None and features(self.publicFeatures)[FEATURE_NAMES.index('bias')] == 1.,
                'Captured public facts require the fixed explicit bias column1.')
        for returns in (self.passReturns, self.attemptReturns):
            require(isinstance(returns, tuple) and len(returns) == 4
                    and all(isinstance(row, Outcome) for row in returns),
                    'Both original actions retain all four ordered paired futures.')
        require(isinstance(self.incumbent, Action), 'The original incumbent choice must be explicit.')

    @property
    def captured(self):
        return self.missingReason is None


def admit(origins, split):
    require(all(isinstance(row, Origin) and row.split is split for row in origins),
            'Only the requested split may enter this calculation.')
    indices = [row.originIndex for row in origins]
    require(indices == sorted(set(indices)), 'Retain unique original origins in canonical order.')
    captured = tuple(row for row in origins if row.captured)
    require(len(captured) >= 2, 'Require at least two captured origins; the corpus owner enforces coverage.')
    return captured


def difference(origin, action, baseline=Action.PASS, metric='reward'):
    returns = {Action.PASS: origin.passReturns, Action.ATTEMPT: origin.attemptReturns}
    return sum(getattr(a, metric)-getattr(b, metric)
               for a, b in zip(returns[action], returns[baseline])) / 4


@dataclass(frozen=True)
class Model:
    scale: tuple[float, ...]
    coefficients: tuple[float, ...]
    zeroColumns: tuple[int, ...]
    trainingOrigins: int
    trainingMean: float
    dictionarySha256: str = DICTIONARY_SHA

    def __post_init__(self):
        require(self.dictionarySha256 == DICTIONARY_SHA, 'The fixed public dictionary changed.')
        scale, coefficients = features(self.scale), features(self.coefficients)
        require(np.all(scale > 0), 'RMS scales must be positive.')
        require(self.zeroColumns == tuple(sorted(set(self.zeroColumns))) and
                all(type(i) is int and 0 <= i < DIMENSION and scale[i] == 1 and coefficients[i] == 0
                    for i in self.zeroColumns), 'Zero TRAIN columns keep scale1 and coefficient0.')
        require(type(self.trainingOrigins) is int and self.trainingOrigins >= 2
                and math.isfinite(self.trainingMean) and -1 <= self.trainingMean <= 1,
                'Training origin count and mean must retain their fitted identity.')


def fit(training):
    rows = admit(training, Split.TRAIN)
    x = np.asarray([features(row.publicFeatures) for row in rows], dtype=np.float64)
    y = np.asarray([difference(row, Action.ATTEMPT) for row in rows], dtype=np.float64)
    scale, coefficients, zero = weighted_solve(x, y, np.ones(len(rows)), dimension=DIMENSION, penalty=PENALTY)
    return Model(scale, coefficients, zero, len(rows), float(np.mean(y)))


def predict(model, public_features):
    return float((features(public_features) / np.asarray(model.scale)) @ np.asarray(model.coefficients))


def choose(prediction):
    require(type(prediction) in (int, float) and math.isfinite(prediction), 'Prediction must be finite.')
    return Action.ATTEMPT if prediction > 0 else Action.PASS


def interval(values):
    require(len(values) >= 2, 'An interval requires at least two independent origins.')
    values = np.asarray(values, dtype=np.float64);mean = float(np.mean(values))
    se = float(np.std(values, ddof=1) / math.sqrt(len(values)))
    return dict(origins=len(values), mean=mean, standardError=se,
                lower95=mean-1.959963984540054*se, upper95=mean+1.959963984540054*se)


def evaluate_validation(model, validation):
    rows = admit(validation, Split.VALIDATION)
    # Freeze every public prediction/choice before touching any validation return below.
    predictions = {row.originIndex: predict(model, row.publicFeatures) for row in rows}
    choices = {index: choose(value) for index, value in predictions.items()}
    losses = {'ridge': [], 'zero': [], 'trainingMean': []}
    gains = {name: {metric: [] for metric in METRICS} for name in ('pass', 'incumbent')}
    records = []
    for row in rows:
        target = difference(row, Action.ATTEMPT);prediction = predictions[row.originIndex]
        for name, value in [('ridge', prediction), ('zero', 0.), ('trainingMean', model.trainingMean)]:
            losses[name].append((value-target)**2)
        effects = {name: {metric: difference(row, choices[row.originIndex], baseline, metric)
                          for metric in METRICS}
                   for name, baseline in [('pass', Action.PASS), ('incumbent', row.incumbent)]}
        for name in gains:
            for metric in METRICS:
                gains[name][metric].append(effects[name][metric])
        records.append(dict(originIndex=row.originIndex, sourceSha256=row.sourceSha256,
            prediction=prediction, selected=choices[row.originIndex].value, incumbent=row.incumbent.value,
            target=target, effects=effects, passReturns=[asdict(x) for x in row.passReturns],
            attemptReturns=[asdict(x) for x in row.attemptReturns]))
    return dict(kind='windmill-value-validation-1', capturedOrigins=len(rows),
        missingOrigins=[dict(originIndex=row.originIndex, sourceSha256=row.sourceSha256,
                             reason=row.missingReason) for row in validation if not row.captured],
        mse={name: interval(values) for name, values in losses.items()},
        pairedMseDifference={name: interval(np.asarray(losses['ridge'])-losses[name])
                             for name in ('zero', 'trainingMean')},
        policyGain={name: {metric: interval(values) for metric, values in metrics.items()}
                    for name, metrics in gains.items()}, origins=records,
        limitations=['Captured independent-origin intervention proxy only; missing roots stay missing.',
                     'Four futures share each original world; no game-strength or repeated-policy claim.'])
