"""Explicit warm94 TOKEN2 or public-history residual."""
from dataclasses import dataclass, replace
import copy

import purchase_policy as b
import token_paired_policy as token
import token_anchor_distribution as distribution


HISTORY_CONTRACT = replace(token.CONTRACT, encoder='aor-token-observation-3', actor='aor-token-policy-3')
HISTORY_STATE = ('bidHistory.sampleShare',) + tuple(
    f'bidHistory.role.{role}.{field}'
    for role in range(6) for field in ('last', 'mean', 'span'))
HISTORY_ACTION = ('bidOrder.bestBox', 'bidOrder.worstBox') + tuple(
    f'bidOrder.role.{role}.{field}' for role in range(1, 6)
    for field in ('before', 'tie'))


def actor_contract(model):
    return HISTORY_CONTRACT if model.get('encoderVersion') == HISTORY_CONTRACT.encoder else token.CONTRACT


def validate_model(model):
    if actor_contract(model) == token.CONTRACT:
        return token.validate_model(model)
    b.validate_model(model, contract=HISTORY_CONTRACT)
    b.require(set(token.ORDER_NAMES + HISTORY_STATE) <= set(model['stateFeatureNames']) and
        set(token.AMOUNT_NAMES + HISTORY_ACTION) <= set(model['actionFeatureNames']),
        'TOKEN3 requires the original public TOKEN2 features and the declared bid history vocabulary.')
    return model


def initialize(anchor_model, *, dictionary=None):
    """Exact frozen94 embeddings, with a new zero actor/value readout; opens no file or RNG."""
    model = copy.deepcopy(token.validate_model(anchor_model))
    if dictionary is not None:
        b.require(dictionary.get('encoderVersion') == HISTORY_CONTRACT.encoder and
            set(dictionary) == {'encoderVersion', 'stateFeatureNames', 'actionFeatureNames'},
            'An extended initializer requires the explicit TOKEN3 dictionary.')
        for prefix, extra in (('state', HISTORY_STATE), ('action', HISTORY_ACTION)):
            key = prefix + 'FeatureNames'
            old = model[key]
            names = dictionary[key]
            b.require(names == sorted(old + list(extra)),
                'TOKEN3 initialization must preserve the anchor and add exactly its history features.')
            by_name = {name: index for index, name in enumerate(old)}
            weights = model[prefix + 'Weights']
            model[prefix + 'Weights'] = [
                weights[row*len(old) + by_name[name]] if name in by_name else 0.
                for row in range(b.HIDDEN) for name in names]
            model[key] = list(names)
        model['encoderVersion'] = HISTORY_CONTRACT.encoder
    model['actorWeights'] = [0.]*b.HIDDEN
    model['valueWeights'] = [0.]*b.HIDDEN
    model['valueBias'] = 0.
    return validate_model(model)


@dataclass(frozen=True)
class PreparedObservation:
    observation: object
    anchor94_probabilities: tuple

    @property
    def action_count(self):
        return self.observation.action_count


class TokenAnchoredPolicy(b.ResidualPolicy):
    def __init__(self, model, *, contract=distribution.RECORD, history_enabled=True):
        distribution.validate(contract)
        if not history_enabled:
            require_zero_history_weights(model)
        super().__init__(validate_model(model), actor_contract(model))
        self.valueWeights.requires_grad_(False)
        self.valueBias.requires_grad_(False)
        self.distribution_contract = copy.deepcopy(contract)
        self.history_enabled = history_enabled

    def prepare_record(self, record):
        return self.prepare(record['observation'], record['anchor94Probabilities'])

    def prepare(self, observation, anchor94=None):
        if isinstance(observation, PreparedObservation):
            b.require(anchor94 is None, 'Prepared anchored observations already own their frozen anchor.')
            super().prepare(observation.observation)
            return observation
        if not self.history_enabled:
            observation = dict(observation, stateFeatures={
                name: value for name, value in observation['stateFeatures'].items()
                if name not in HISTORY_STATE}, actions=[
                    dict(action, features={name: value for name, value in action['features'].items()
                        if name not in HISTORY_ACTION}) for action in observation['actions']])
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


def require_zero_history_weights(model):
    """A history-off export must give the same probabilities on unmasked runtime inputs."""
    validate_model(model)
    for prefix, extension in (('state', HISTORY_STATE), ('action', HISTORY_ACTION)):
        names = model[prefix + 'FeatureNames']
        weights = model[prefix + 'Weights']
        columns = [index for index, name in enumerate(names) if name in extension]
        b.require(all(weights[row*len(names) + column] == 0.
            for row in range(b.HIDDEN) for column in columns),
            'A history-off actor requires zero weights on every masked history column.')


def reference_forward(model, observation, anchor94, *, contract=distribution.RECORD):
    raw = b.reference_actor_outputs(model, observation, contract=actor_contract(model))
    return dict(probabilities=distribution.probabilities(anchor94, raw['residuals'], contract=contract),
        value=raw['value'], residuals=raw['residuals'])
