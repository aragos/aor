"""Immutable coordinate profiles and the fixed, finite whole-game mutation algorithm."""
import copy
from dataclasses import dataclass
import json
import math
import random

from automata_experiments import require

DEFAULT_SIGMAS = (.25, .5, 1., 2.)

@dataclass(frozen=True)
class CoordinateProfile:
    """Names/order, unit scales, score temperature and immutable normalized bounds."""
    features: tuple
    scales: tuple
    temperature: float
    bounds: tuple

    def __post_init__(self):
        require(type(self.features) is type(self.scales) is type(self.bounds) is tuple
                and len(self.features) == len(set(self.features)) > 0
                and all(isinstance(name, str) and name for name in self.features),
                'Coordinate profiles require immutable tuples and unique feature names.')
        validate_normalization(self.normalization(), self.features)
        require(len(self.bounds) == len(self.features)
                and all(finite(value) and value >= 8 for value in self.bounds),
                'Coordinate profiles require one finite bound of at least eight per feature.')

    def normalization(self):
        return dict(features=list(self.scales), temperature=self.temperature)


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def weights(model):
    result = model.get("weights")
    require(model.get("schemaVersion") == 1 and isinstance(result, dict)
            and len(result) <= 4096 and all(isinstance(k, str) and 1 <= len(k) <= 120
            and finite(v) and abs(v) <= 1e6 for k, v in result.items()),
            "Expansion models require bounded finite schema-one weights.")
    return result


def model_key(model):
    # Explicit zero and absent zero have identical semantics after prior expansion.
    return json.dumps({k: float(v) for k, v in weights(model).items() if v != 0},
                      sort_keys=True, separators=(",", ":"), allow_nan=False)


def validate_normalization(normalization, features):
    require(set(normalization) == {"features", "temperature"}
            and len(normalization["features"]) == len(features)
            and all(finite(v) and v >= 1 for v in normalization["features"])
            and finite(normalization["temperature"]) and normalization["temperature"] >= 1,
            "Evolution requires the frozen training-only pilot normalization.")


def coordinates(model, baseline, profile):
    normalization, features = profile.normalization(), profile.features
    original, actual = weights(baseline), weights(model)
    require(all(actual.get(k, 0) == original.get(k, 0)
                for k in set(original) | set(actual) if k not in features),
            "Evolution may change only its declared coordinate coefficients.")
    return [(actual.get(k, 0) - original.get(k, 0)) * scale / normalization["temperature"]
            for k, scale in zip(features, normalization["features"])]



def export_model(values, baseline, profile, target_rules):
    normalization, features, bounds = profile.normalization(), profile.features, profile.bounds
    require(len(values) == len(bounds) == len(features)
            and all(finite(b) and b >= 8 for b in bounds)
            and all(finite(v) and abs(v) <= b for v, b in zip(values, bounds)),
            "Mutation coordinates must stay within the precommitted finite bounds.")
    original = weights(baseline)
    result = {"schemaVersion": 1, "rulesVersion": target_rules,
              "dataVersion": baseline["dataVersion"], "weights": dict(original)}
    result["weights"].update({name: original.get(name, 0) + normalization["temperature"] * value / scale
                              for name, value, scale in zip(features, values, normalization["features"])})
    coordinates(result, baseline, profile)
    return result


def population(baseline, parents, profile, seed, target_rules,
               maximum_proposals=4096, population_size=8, sigmas=DEFAULT_SIGMAS):
    """Anchor, up to two exact parents, then fixed-sigma Gaussian children.

    A duplicate consumes its proposal attempt and RNG values, but not a child
    index. The child-index sigma/center schedule never reacts to game outcomes.
    Clipping is coordinatewise. Failure to make eight unique models within the
    fixed attempt ceiling fails closed without changing proposal mechanics.
    """
    features, bounds = profile.features, profile.bounds
    require(type(sigmas) is tuple and len(sigmas) > 0 and all(finite(value) and value > 0 for value in sigmas),
            'Mutation scales require an immutable, nonempty schedule of finite positive sigmas.')
    require(type(seed) is int and seed >= 0 and len(parents) <= 2,
            "Each generation requires its declared seed and at most two prior elites.")
    anchor = export_model([0.] * len(features), baseline, profile, target_rules)
    require(model_key(anchor) == model_key(baseline), "The anchor must preserve exact F1 weights.")
    require(len({model_key(model) for model in [anchor, *parents]}) == 1 + len(parents),
            "Initial elites must be distinct from one another and F1.")
    parent_coordinates = [[0.] * len(features)] + [coordinates(model, baseline, profile) for model in parents]
    require(all(abs(v) <= bound + 1e-10 for row in parent_coordinates for v, bound in zip(row, bounds)),
            "An elite cannot enlarge the precommitted coordinate bounds.")
    # Undo only serialization-scale roundoff at a boundary; bounds never expand.
    parent_coordinates = [[max(-bound, min(bound, value)) for value, bound in zip(row, bounds)]
                          for row in parent_coordinates]
    result = [{"candidateIndex": index, "kind": "anchor" if index == 0 else "elite",
               "model": copy.deepcopy(model), "coordinates": parent_coordinates[index]}
              for index, model in enumerate([anchor, *parents])]
    seen = {model_key(row["model"]) for row in result}
    generator = random.Random(seed)
    child = 0
    for attempt in range(maximum_proposals):
        if len(result) == population_size:
            return result
        first = generator.randrange(len(parent_coordinates))
        parent_ids = [first]
        if child % 2 and len(parent_coordinates) > 1:
            second = generator.randrange(len(parent_coordinates) - 1)
            parent_ids.append(second + (second >= first))
        center = [sum(parent_coordinates[parent][index] for parent in parent_ids) / len(parent_ids)
                  for index in range(len(features))]
        sigma = sigmas[child % len(sigmas)]
        noise = [generator.gauss(0., 1.) for _ in features]
        values = [max(-bound, min(bound, mean + sigma * perturbation))
                  for mean, perturbation, bound in zip(center, noise, bounds)]
        model = export_model(values, baseline, profile, target_rules)
        key = model_key(model)
        if key in seen:
            continue
        result.append({"candidateIndex": len(result), "kind": "mutation", "childIndex": child,
                       "attempt": attempt, "parentIndices": parent_ids, "sigma": sigma,
                       "center": center, "gaussianNoise": noise, "coordinates": values, "model": model})
        seen.add(key)
        child += 1
    raise ValueError("Generation exhausted its fixed proposal ceiling; no adaptive retry is permitted.")

