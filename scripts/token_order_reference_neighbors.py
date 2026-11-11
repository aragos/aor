"""Strict model envelope for fixed-T residuals using reference-neighbor queries."""
from automata_experiments import require
import token_order_fixed_reference as fixed
from token_order_proposals import ProposalProfile

KIND = 'token-order-reference-neighbor-residual-1'
CAMPAIGN_KIND = 'token-reference-neighbor-campaign-1'
SELECTOR = 'MODEL_FIXED_T_REFERENCE_NEIGHBORS'
SELECTOR_VERSION = 'token-order-reference-neighbor-selector-1'
PROPOSALS = ProposalProfile.REFERENCE_NEIGHBORS.value
ENVELOPE = dict(schemaVersion=1, kind=KIND, referenceProtocol=fixed.REFERENCE,
                proposalVersion=PROPOSALS, selectorVersion=SELECTOR_VERSION,
                minimumAdvantage=.02)


def envelope_model(artifact):
    require(isinstance(artifact, dict) and set(artifact) == {*ENVELOPE, 'model'}
            and type(artifact['schemaVersion']) is int
            and all(artifact[key] == value for key, value in ENVELOPE.items()),
            'Reference-neighbor models require their exact proposal, selector and reference tags.')
    require(isinstance(artifact['model'], dict), 'A reference-neighbor artifact requires raw Q data.')
    return artifact['model']


def wrap(model):
    import territory_value as q
    q.validate_model(model, 'token')
    require(model['encoderVersion'] == q.TOKEN_ENCODER_2,
            'Reference-neighbor residuals retain the original token2 encoder.')
    return dict(ENVELOPE, model=model)


def unwrap(artifact):
    return wrap(envelope_model(artifact))['model']
