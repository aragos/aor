package dev.aragos.aor

enum class ViolationCode(val description: String) {
    INVALID_COMMAND("The command syntax is invalid."),
    UNKNOWN_PLAYER("The player is not registered in this game."),
    WRONG_PLAYER("The action belongs to another player."),
    WRONG_PHASE("The action is not allowed in the current phase."),
    INVALID_EPOCHS("Game length must be 1, 2, or 3 epochs."),
    INVALID_ATTACK_TOKEN_COUNT(
        "Commit exactly the required attacker tokens, including those already present."
    ),
    INVALID_BID("A capital bid must be between 0 and 40."),
    INVALID_TOKEN_PURCHASE(
        "A token purchase must be a whole number between negative and positive available cash."
    ),
    INVALID_TURN_POSITION("Choose an available insertion position in the current turn-order tie."),
    UNAVAILABLE_CAPITAL("The selected capital is unavailable."),
    INVALID_OUTCOME("The system outcome does not match the pending request."),
    UNSUPPORTED_OPTION("The requested game option is unsupported."),
}

class RuleViolation(
    val code: ViolationCode,
    val entityIds: List<String> = emptyList(),
) : Exception(code.description)

internal fun checkRule(
    valid: Boolean,
    code: ViolationCode,
    entities: List<String> = emptyList(),
) {
    if (!valid) {
        throw RuleViolation(code = code, entityIds = entities)
    }
}
