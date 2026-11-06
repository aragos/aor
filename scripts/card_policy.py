"""CPU PPO for repeated card choices with the separately qualified purchase actor frozen.

The card contract has its own versions and complete dictionary. The shared numerical backend
retains the purchase network's Float64 arithmetic, 64 hidden units and .97 HARD prior. It never
updates the purchase policy carried by a completed card collection.
"""

import purchase_policy as backend


ACTOR_VERSION = "aor-card-policy-1"
ENCODER_VERSION = "aor-card-observation-1"
CONTRACT = backend.PolicyContract(ENCODER_VERSION, ACTOR_VERSION, "card", 1024,
                                  frozenPurchase=True, maxCommand=2000,
                                  trajectoryEncoding="gzip-json-v1")


class CardPolicy(backend.ResidualPolicy):
    def __init__(self, model):
        super().__init__(model, CONTRACT)


def initialize(schema, seed):
    return backend.initialize(schema, seed, contract=CONTRACT)


def validate_model(model):
    return backend.validate_model(model, contract=CONTRACT)


def reference_forward(model, observation):
    return backend.reference_forward(model, observation, contract=CONTRACT)


def read_collection(directory, model_path, model):
    return backend.read_collection(directory, model_path, model, contract=CONTRACT)


def update(model_path, collection_path, config, output, resume=False, optimizer_from=None):
    return backend.update(model_path, collection_path, config, output, resume, optimizer_from,
                          contract=CONTRACT, entrypoint=__file__)


def main(arguments=None):
    def toy_runner():
        from card_policy_toy import run_toy
        return run_toy()
    backend.main(arguments, contract=CONTRACT, entrypoint=__file__, toy_runner=toy_runner,
                 description=__doc__)


if __name__ == "__main__":
    main()
