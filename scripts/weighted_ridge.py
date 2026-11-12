"""Pure Float64 weighted ridge owner; callers own data splits and fixed model recipes."""
import numpy as np


def weighted_solve(x, y, weights, *, dimension, penalty):
    """Return uncentered weighted RMS scales, normalized-space coefficients and zero columns.

    Weights are normalized to sum to one. Constant-zero columns use scale one and
    exact zero coefficients. There is no intercept, random initialization or fitting
    policy here: callers must supply only their admitted training rows and fixed penalty.
    """
    if type(dimension) is not int or dimension <= 0:
        raise ValueError('The feature dimension must be a positive integer.')
    if type(penalty) not in (int, float) or not np.isfinite(penalty) or penalty <= 0:
        raise ValueError('The fixed ridge penalty must be finite and positive.')
    x, y, weights = (np.asarray(v, dtype=np.float64) for v in (x, y, weights))
    if not (x.ndim == 2 and x.shape[1] == dimension and len(x) > 0
            and y.shape == weights.shape == (len(x),)
            and all(np.isfinite(v).all() for v in (x, y, weights))
            and np.all(weights > 0)):
        raise ValueError('Require finite supported rows/targets and positive whole-origin weights.')
    weights = weights / np.sum(weights)
    rms = np.sqrt(np.sum(weights[:, None] * x * x, axis=0))
    zero = rms == 0; scale = np.where(zero, 1., rms)
    normalized = x / scale
    coefficients = np.linalg.solve(normalized.T @ (weights[:, None] * normalized) +
                                   penalty * np.eye(dimension), normalized.T @ (weights * y))
    coefficients[zero] = 0.
    if not np.isfinite(coefficients).all():
        raise ValueError('The single ridge solve must be finite.')
    return tuple(map(float, scale)), tuple(map(float, coefficients)), tuple(map(int, np.flatnonzero(zero)))
