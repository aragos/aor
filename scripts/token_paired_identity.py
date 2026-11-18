"""Pure Float64 identity for the five TOKEN2 actor arrays, independent of JSON spelling."""
import hashlib
import json
import math


ACTOR_PARAMETERS = ('stateWeights','stateBias','actionWeights','actionBias','actorWeights')


def actor_parameters_sha256(model):
    """Numerical equality treats integer/float spellings and either signed zero identically.

    The model owner validates dimensions and bounds. This helper owns only finite Float64
    parameter identity; original model bytes, metadata, dictionaries and value heads stay separate.
    """
    values = {}
    for name in ACTOR_PARAMETERS:
        row = model[name]
        if not isinstance(row,list) or not row or any(type(value) not in (int,float) or
                not math.isfinite(value) for value in row):
            raise ValueError('Actor parameter identity requires finite nonempty numerical arrays.')
        values[name] = [float(value) if value else 0. for value in row]
    return hashlib.sha256(json.dumps(values,sort_keys=True,separators=(',',':'),
        allow_nan=False).encode()).hexdigest()
