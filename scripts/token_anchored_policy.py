"""Explicit warm94 TOKEN2 residual, separate from every legacy prior/clamped actor reader."""
from dataclasses import dataclass
import copy

import purchase_policy as b
import token_paired_policy as token
import token_anchor_distribution as distribution


def initialize(anchor_model):
    """Exact frozen94 embeddings, with a new zero actor/value readout; opens no file or RNG."""
    model = copy.deepcopy(token.validate_model(anchor_model))
    model['actorWeights'] = [0.]*b.HIDDEN
    model['valueWeights'] = [0.]*b.HIDDEN
    model['valueBias'] = 0.
    return token.validate_model(model)


@dataclass(frozen=True)
class PreparedObservation:
    observation: object
    anchor94_probabilities: tuple

    @property
    def action_count(self):
        return self.observation.action_count


class TokenAnchoredPolicy(token.TokenPairedPolicy):
    def __init__(self, model, *, contract=distribution.RECORD):
        distribution.validate(contract)
        super().__init__(model)
        self.distribution_contract = copy.deepcopy(contract)

    def prepare_record(self, record):
        return self.prepare(record['observation'], record['anchor94Probabilities'])

    def prepare(self, observation, anchor94=None):
        if isinstance(observation, PreparedObservation):
            b.require(anchor94 is None, 'Prepared anchored observations already own their frozen anchor.')
            super().prepare(observation.observation)
            return observation
        base = super().prepare(observation)
        return PreparedObservation(base, distribution.anchor_probabilities(anchor94, base.action_count))

    def forward(self, observations):
        b.require(1 <= len(observations) <= 256 and
            all(isinstance(item, PreparedObservation) for item in observations),
            'Anchored numerical batches require1–256 observations with exact frozen94 probabilities.')
        prepared = [self.prepare(item) for item in observations]
        b.require(sum(item.action_count for item in prepared) <= 16384,
            'Anchored numerical batch exceeds16384 offered actions.')
        residuals, values = self.actor_outputs([item.observation for item in prepared])
        logs = []; cursor = 0
        for observation in prepared:
            count = observation.action_count
            residual = residuals[cursor:cursor+count]/self.distribution_contract['temperature']
            prior = b.torch.tensor(distribution.prior(observation.anchor94_probabilities,
                contract=self.distribution_contract), dtype=b.DTYPE).log()
            log_probability = b.torch.log_softmax(prior+residual, dim=0)
            if bool(b.torch.all(residual == residual[0])):
                log_probability = log_probability+(prior-log_probability).detach()
            b.require(bool(b.torch.isfinite(log_probability).all()) and
                bool((log_probability.exp() > 0.).all()),
                'Anchored probabilities must remain finite with positive Float64 full support.')
            logs.append(log_probability); cursor += count
        return logs, values


def reference_forward(model, observation, anchor94, *, contract=distribution.RECORD):
    raw = b.reference_actor_outputs(model, observation, contract=token.CONTRACT)
    return dict(probabilities=distribution.probabilities(anchor94, raw['residuals'], contract=contract),
        value=raw['value'], residuals=raw['residuals'])
