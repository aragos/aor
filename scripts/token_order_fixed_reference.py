"""Explicit fixed-T residual reference and artifact codec; ordinary proposals stay unchanged."""

from automata_experiments import require

PROTOCOL = "ARENA_FIXED_T_RESIDUAL_EIGHT_TAILS_1"
REFERENCE = "fixed-token-reference-1"
SELECTOR = "FIXED_16_TO_15"
KIND = "token-order-fixed-reference-residual-1"
CAMPAIGN_KIND = "token-fixed-reference-eight-tail-campaign-1"
ENVELOPE = dict(schemaVersion=1, kind=KIND, referenceProtocol=REFERENCE,
                proposalVersion="token-order-proposals-1", minimumAdvantage=.02)


def expected_reference_index(observation):
    """Mirror the native offered-command fallback without changing the ordinary baseline."""
    baseline = observation["baselineIndex"]
    commands = [action["command"] for action in observation["actions"]]
    if commands[baseline] == "buy-tokens 16" and "buy-tokens 15" in commands:
        return commands.index("buy-tokens 15")
    return baseline


def reference_index(row):
    """Only the opt-in loader supplies referenceIndex on validated learning rows."""
    return row.get("referenceIndex", row["observation"]["baselineIndex"])


def wrap(model):
    import territory_value as q
    q.validate_model(model, "token")
    require(model["encoderVersion"] == q.TOKEN_ENCODER_2,
            "Fixed token residuals require the unchanged token version 2 encoder.")
    return dict(ENVELOPE, model=model)


def envelope_model(artifact):
    """Validate reference ownership without importing a numerical backend."""
    require(isinstance(artifact, dict) and set(artifact) == {*ENVELOPE, "model"}
            and type(artifact["schemaVersion"]) is int
            and all(artifact[key] == value for key, value in ENVELOPE.items()),
            "Fixed token residuals require the exact tagged artifact envelope.")
    require(isinstance(artifact['model'], dict), 'A residual envelope requires its raw Q object.')
    return artifact['model']


def unwrap(artifact):
    return wrap(envelope_model(artifact))["model"]
