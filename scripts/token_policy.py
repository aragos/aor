"""Typed residual token-commitment actor; ordinary inference and the .97 prior stay unchanged."""
import purchase_policy as backend

CONTRACT = backend.TOKEN_CONTRACT
ACTOR_VERSION, ENCODER_VERSION = CONTRACT.actor, CONTRACT.encoder
REFERENCE_VERSION = "aor-token-offered-reference-1"
ACTOR_SALT = 0x510e527f


class TokenPolicy(backend.ResidualPolicy):
    def __init__(self, model):
        super().__init__(model, CONTRACT)


def initialize(schema, seed):
    return backend.initialize(schema, seed, contract=CONTRACT)


def validate_model(model):
    return backend.validate_model(model, contract=CONTRACT)


def reference_forward(model, observation):
    return backend.reference_forward(model, observation, contract=CONTRACT)


def main(arguments=None):
    from territory_trajectory import phase_main
    phase_main(arguments, CONTRACT, __file__)


if __name__ == "__main__":
    main()
