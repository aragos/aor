"""Portable full-support TOKEN probability contract; no models, files, RNG or outcomes."""
import math


RECORD = dict(kind='token-anchor-log-floor-1', epsilon=.20, temperature=1.,
    support='ORIGINAL_AFFORDABLE_OFFERS')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(record):
    require(type(record) is dict and record == RECORD,
        'Anchored TOKEN requires the exact prospective log-floor probability contract.')
    return record


def anchor_probabilities(values, count=None):
    require(isinstance(values, (list, tuple)) and 1 <= len(values) <= 75 and
        (count is None or len(values) == count),
        'The frozen94 anchor requires every original offered action probability.')
    require(all(type(value) in (int, float) and math.isfinite(value) and 0. <= value <= 1.
        for value in values) and abs(math.fsum(values)-1.) <= 2e-12,
        'Frozen94 probabilities must be finite, nonnegative and normalized.')
    return tuple(float(value) for value in values)


def prior(values, *, contract=RECORD):
    """Zero-residual policy: .8*qualified94/C05 + .2/number-of-original-offers."""
    validate(contract)
    values = anchor_probabilities(values)
    uniform = contract['epsilon']/len(values)
    return [(1.-contract['epsilon'])*value+uniform for value in values]


def probabilities(values, residuals, *, contract=RECORD):
    """Stable softmax(log(p0)+delta/T); no residual clipping, support gate or final floor.

    The finite Float64 wire cannot represent arbitrarily tiny positive results. Reject numerical
    underflow instead of silently turning a full-support model into a deterministic policy.
    """
    original = prior(values, contract=contract)
    require(isinstance(residuals, (list, tuple)) and len(residuals) == len(original) and
        all(type(value) in (int, float) and math.isfinite(value) for value in residuals),
        'Every offered action requires one finite raw residual.')
    if all(value == residuals[0] for value in residuals):
        return original
    logits = [math.log(probability)+residual/contract['temperature']
        for probability, residual in zip(original, residuals)]
    maximum = max(logits)
    shifted = [math.exp(value-maximum) for value in logits]
    denominator = sum(shifted)
    result = [value/denominator for value in shifted]
    require(all(value > 0. for value in result),
        'Full-support TOKEN probabilities underflowed; the numerical attempt is invalid.')
    return result
