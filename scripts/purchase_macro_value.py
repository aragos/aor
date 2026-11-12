"""Fixed public macro-return ridge prerequisite; no reader, TEST input, policy export or CLI.

The factual adapter owns canonical support and paired-stream provenance. This module
consumes its detached COMPLETE_PREFIX records, retaining all missing/unsupported plans.
Only supported public features enter prediction; terminal labels never enter selection.
"""
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import math
from pathlib import Path
import re
from typing import Mapping

import numpy as np

DICTIONARY = Path(__file__).with_name('purchase_macro_features.json')
FEATURE_NAMES = tuple(json.loads(DICTIONARY.read_text())['featureNames'])
DICTIONARY_SHA = hashlib.sha256(DICTIONARY.read_bytes()).hexdigest()
DIMENSION, RIDGE = 169, .01
METRICS = ('reward', 'winShare', 'rankReward', 'chaos')
assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES)) == DIMENSION


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Split(Enum):
    TRAIN = 'TRAIN'
    VALIDATION = 'VALIDATION'


@dataclass(frozen=True)
class MacroOutcome:
    reward: float
    winShare: float
    rankReward: float
    chaos: float

    def validate(self):
        require(all(type(getattr(self, key)) in (int, float) and
                    math.isfinite(getattr(self, key)) and 0 <= getattr(self, key) <= 1
                    for key in METRICS), 'Every detached terminal metric must be finite and normalized.')
        require(abs(self.reward - (.8 * self.winShare + .2 * self.rankReward)) < 1e-12,
                'The original composite reward must equal .8 winShare + .2 rankReward.')
        require(self.chaos in (0, 1), 'Chaos must be the observed binary terminal receipt.')


@dataclass(frozen=True)
class MacroPlan:
    planIndex: int
    commands: tuple[str, ...]
    features: tuple[float, ...] | None
    supported: bool
    exclusionReason: str | None
    # Ordered original common future streams0/1; the factual adapter binds exact seeds/source.
    returns: tuple[MacroOutcome, MacroOutcome] | None


@dataclass(frozen=True)
class MacroOrigin:
    originIndex: int
    split: Split
    sourceSha256: str
    plans: tuple[MacroPlan, ...]
    labelKind: str = 'COMPLETE_PREFIX'

    def __post_init__(self):
        # Reject TEST/unknown subjects before touching any plan or return object.
        require(self.split in (Split.TRAIN, Split.VALIDATION), 'Only TRAIN/VALIDATION; TEST is forbidden.')
        start, stop = (0, 384) if self.split is Split.TRAIN else (384, 448)
        require(type(self.originIndex) is int and start <= self.originIndex < stop,
                'The fixed whole-origin split cannot change.')
        require(isinstance(self.sourceSha256, str) and re.fullmatch('[0-9a-f]{64}', self.sourceSha256),
                'Every original origin requires its exact source SHA-256.')
        require(self.labelKind == 'COMPLETE_PREFIX', 'Use COMPLETE_PREFIX only; no first-action labels.')
        indices = [plan.planIndex for plan in self.plans]
        require(all(type(i) is int and i >= 0 for i in indices) and indices == sorted(set(indices)),
                'Retain unique plans in original teacher order.')
        for plan in self.plans:
            require(isinstance(plan.commands, tuple) and bool(plan.commands)
                    and all(isinstance(c, str) and bool(c) for c in plan.commands),
                    'Each plan retains its exact ordered nonempty commands.')
            require(type(plan.supported) is bool, 'Canonical support must be explicitly boolean.')
            if not plan.supported:
                require(isinstance(plan.exclusionReason, str) and bool(plan.exclusionReason)
                        and plan.returns is None, 'Unsupported plans retain a reason and no labels.')
                continue
            require(plan.exclusionReason is None and plan.features is not None,
                    'Supported plans require public features and no exclusion reason.')
            features(plan.features)
            require(isinstance(plan.returns, tuple) and len(plan.returns) == 2,
                    'Every supported plan retains both ordered paired future metrics.')
            for outcome in plan.returns:
                require(isinstance(outcome, MacroOutcome), 'Detached typed outcome required.')
                outcome.validate()
            if plan.planIndex == 0:
                require(plan.commands == ('pass',) and all(v == 0 for v in plan.features),
                        'Supported PASS must be teacher index0, one PASS command and zero169 features.')
            else:
                require(plan.commands != ('pass',), 'Only teacher index0 may be the PASS baseline.')

    @property
    def supported(self):
        return tuple(plan for plan in self.plans if plan.supported)

    @property
    def eligible(self):
        return any(p.planIndex == 0 for p in self.supported) and any(p.planIndex != 0 for p in self.supported)

    @property
    def missingReason(self):
        if not self.plans:
            return 'NO_ROOT'
        if not any(p.planIndex == 0 for p in self.supported):
            return 'NO_SUPPORTED_PASS'
        return None if self.eligible else 'NO_SUPPORTED_NONPASS'


def features(values):
    result = np.asarray(values, dtype=np.float64)
    require(result.shape == (DIMENSION,) and np.isfinite(result).all(),
            'Use exactly169 finite public features in original owner units.')
    return result


def admit(origins, split, minimum):
    expected = list(range(384)) if split is Split.TRAIN else list(range(384, 448))
    require([o.originIndex for o in origins] == expected and all(o.split is split for o in origins),
            'Retain every prospective origin once in its fixed chronological split, including missing.')
    eligible = tuple(o for o in origins if o.eligible)
    require(len(eligible) >= minimum, 'Insufficient eligible supported origins; no refill or fit ladder.')
    return eligible


def paired_difference(plan, baseline, metric='reward'):
    return sum(getattr(a, metric) - getattr(b, metric) for a, b in zip(plan.returns, baseline.returns)) / 2


def training_arrays(origins):
    rows, targets, weights = [], [], []
    for origin in origins:
        require(origin.split is Split.TRAIN and origin.eligible, 'Only eligible TRAIN origins enter scaling/fit.')
        baseline = next(p for p in origin.supported if p.planIndex == 0)
        options = [p for p in origin.supported if p.planIndex != 0]
        for plan in options:
            rows.append(features(plan.features)); targets.append(paired_difference(plan, baseline))
            weights.append(1 / len(options))
    return np.asarray(rows), np.asarray(targets), np.asarray(weights)


@dataclass(frozen=True)
class MacroRidge:
    scale: tuple[float, ...]
    coefficients: tuple[float, ...]
    zeroColumns: tuple[int, ...]
    trainingOrigins: int
    trainingPlans: int
    dictionarySha256: str = DICTIONARY_SHA

    def __post_init__(self):
        require(self.dictionarySha256 == DICTIONARY_SHA, 'Fixed169-column dictionary identity changed.')
        scale, coefficients = features(self.scale), features(self.coefficients)
        require(np.all(scale > 0), 'Weighted RMS scales must be positive, including constant-zero fallback1.')
        require(self.zeroColumns == tuple(sorted(set(self.zeroColumns))) and
                all(0 <= i < DIMENSION and scale[i] == 1 and coefficients[i] == 0 for i in self.zeroColumns),
                'Zero TRAIN columns retain scale1 and exact zero coefficient.')


def _weighted_solve(x, y, weights):
    """Single deterministic no-intercept solve; lambda is fixed, never a caller option."""
    x, y, weights = (np.asarray(v, dtype=np.float64) for v in (x, y, weights))
    require(x.ndim == 2 and x.shape[1] == DIMENSION and len(x) > 0
            and y.shape == weights.shape == (len(x),) and
            all(np.isfinite(v).all() for v in (x, y, weights)) and np.all(weights > 0),
            'Require finite supported rows/targets and positive whole-origin weights.')
    weights = weights / np.sum(weights)
    rms = np.sqrt(np.sum(weights[:, None] * x * x, axis=0))
    zero = rms == 0; scale = np.where(zero, 1., rms)
    normalized = x / scale
    coefficients = np.linalg.solve(normalized.T @ (weights[:, None] * normalized) +
                                   RIDGE * np.eye(DIMENSION), normalized.T @ (weights * y))
    coefficients[zero] = 0.
    require(np.isfinite(coefficients).all(), 'The single ridge solve must be finite.')
    return tuple(map(float, scale)), tuple(map(float, coefficients)), tuple(map(int, np.flatnonzero(zero)))


def fit(training):
    eligible = admit(training, Split.TRAIN, 160)
    x, y, weights = training_arrays(eligible)
    scale, coefficients, zero = _weighted_solve(x, y, weights)
    return MacroRidge(scale, coefficients, zero, len(eligible), len(x))


def predict(model, public_features):
    return float((features(public_features) / np.asarray(model.scale)) @ np.asarray(model.coefficients))


def choose(predictions: Mapping[int, float]):
    """Public predictions only. PASS wins nonpositive ties; positive ties keep teacher order."""
    require(predictions.get(0) == 0. and len(predictions) >= 2
            and all(type(i) is int and i >= 0 and math.isfinite(v) for i, v in predictions.items()),
            'Include supported PASS=0 and finite supported plan predictions.')
    index = min(predictions, key=lambda i: (-predictions[i], i))
    return index if predictions[index] > 0 else 0


def interval(values):
    require(len(values) >= 2, 'A paired-origin interval requires two independent origins.')
    values = np.asarray(values, dtype=np.float64);mean = float(np.mean(values))
    se = float(np.std(values, ddof=1) / math.sqrt(len(values)));z = 1.959963984540054
    return dict(origins=len(values), mean=mean, standardError=se, lower95=mean-z*se, upper95=mean+z*se)


def evaluate_validation(model, validation):
    eligible = admit(validation, Split.VALIDATION, 24)
    # Construct ALL public choices before inspecting any validation return in this function.
    choices = {}
    for origin in eligible:
        predictions = {p.planIndex: (0. if p.planIndex == 0 else predict(model, p.features)) for p in origin.supported}
        choices[origin.originIndex] = (predictions, choose(predictions))
    losses = {'ridge': [], 'zero': []};effects = {m: [] for m in METRICS}
    teacher = {m: [] for m in METRICS};records = []
    for origin in eligible:
        predictions, selected = choices[origin.originIndex];plans = {p.planIndex: p for p in origin.supported}
        baseline = plans[0];local = {name: [] for name in losses};actions = []
        for index, plan in plans.items():
            if index == 0:
                continue
            target = paired_difference(plan, baseline)
            local['ridge'].append((predictions[index]-target)**2);local['zero'].append(target**2)
            actions.append(dict(planIndex=index, commands=plan.commands, features=plan.features,
                prediction=predictions[index], target=target, pairedReturns=tuple(asdict(v) for v in plan.returns)))
        for name in losses:
            losses[name].append(float(np.mean(local[name])))
        differences = {m: paired_difference(plans[selected], baseline, m) for m in METRICS}
        for metric in METRICS:
            effects[metric].append(differences[metric])
            if 1 in plans:
                teacher[metric].append(paired_difference(plans[1], baseline, metric))
        records.append(dict(originIndex=origin.originIndex, sourceSha256=origin.sourceSha256,
            predictions=predictions, selectedPlanIndex=selected, actions=actions,
            passPairedReturns=tuple(asdict(v) for v in baseline.returns), differences=differences,
            unsupportedPlans=[dict(planIndex=p.planIndex, commands=p.commands, reason=p.exclusionReason)
                              for p in origin.plans if not p.supported]))
    missing = [dict(originIndex=o.originIndex, sourceSha256=o.sourceSha256, reason=o.missingReason,
                   planIndices=[p.planIndex for p in o.plans]) for o in validation if not o.eligible]
    return dict(kind='purchase-macro-validation-1', eligibleOrigins=len(eligible), missingOrigins=missing,
        mse={name: interval(values) for name, values in losses.items()},
        pairedMseDifference=interval(np.asarray(losses['ridge'])-losses['zero']),
        selectionEffectsVsPass={m: interval(v) for m, v in effects.items()},
        teacherIndex1=dict(eligibleOrigins=len(teacher['reward']),
            effectsVsPass={m: interval(v) if len(v)>=2 else None for m, v in teacher.items()}),
        origins=records, allProspectiveOrigins=len(validation),
        limitations=('Eligible-supported whole-origin proxy only; missing origins are not zero effects.',
                     'Paired futures share the retained hidden world; no public-student strength claim.',
                     'Teacher index1 is descriptive on its own eligible subset; it never selects a model.'))
