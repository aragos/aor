"""Explicit TOKEN2 residual actor; legacy TOKEN1 and all PPO readers remain unchanged."""
import purchase_policy as b


CONTRACT = b.PolicyContract('aor-token-observation-2', 'aor-token-policy-2', 'token', 128,
    trajectoryEncoding='gzip-json-v1', schemaVersion=3)
ACTOR_SALT = 0x510e527f
ORDER_NAMES = tuple(f'role.{seat}.capitalChoicePosition' for seat in range(6))
AMOUNT_NAMES = tuple(f'bid.amount.{amount}' for amount in range(-36, 37)) + (
    'bid.amount.below', 'bid.amount.above')


def validate_model(model):
    b.validate_model(model, contract=CONTRACT)
    b.require(set(ORDER_NAMES) <= set(model['stateFeatureNames']) and
        set(AMOUNT_NAMES) <= set(model['actionFeatureNames']),
        'TOKEN2 requires the complete public capital-order and signed-amount dictionaries.')
    return model


class TokenPairedPolicy(b.ResidualPolicy):
    def __init__(self, model):
        super().__init__(validate_model(model), CONTRACT)
        self.valueWeights.requires_grad_(False)
        self.valueBias.requires_grad_(False)


def initialize(schema, purchase, seed):
    """One prospectively pinned warm transfer; novel coefficients retain seeded initialization.

    Canonical names represent the same RawActorState units. Phase decision flags are not
    transferable. This pure function opens no files and neither chooses nor reserves a seed.
    """
    b.validate_model(purchase)
    model = b.initialize(schema, seed, contract=CONTRACT)
    old = purchase['stateFeatureNames']; new = model['stateFeatureNames']
    shared = sorted(name for name in set(old) & set(new) if not name.startswith('decision.'))
    b.require(shared, 'TOKEN2 warm transfer requires shared RawActorState columns.')
    mapping = [(old.index(name), new.index(name)) for name in shared]
    for unit in range(b.HIDDEN):
        for source, target in mapping:
            model['stateWeights'][unit * len(new) + target] = purchase['stateWeights'][
                unit * len(old) + source]
    model['stateBias'] = list(purchase['stateBias'])
    return validate_model(model), dict(version='token-paired-warm-transfer-1',
        sharedStateFeatures=shared, excludedDecisionFlags=True, novelStateInitialization='seeded',
        stateBias='exact-source', actorOutput='zero', valueOutput='unchanged-zero')
