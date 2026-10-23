package dev.aragos.aor

enum class ViolationCode(val description: String) {
    UNKNOWN_PROVINCE("The province is not available in this game."),
    INVALID_BOARD_PLAYER("The board action references an unavailable player."),
    INVALID_BOARD_MUTATION("The board change is incompatible with its current pieces."),
    INVALID_PLACEMENT("The token placement is incompatible with the province's pieces."),
    UNREACHABLE_PROVINCE("The province cannot be reached by the selected expansion route."),
    PROVINCE_ACCESS_DENIED("The required advance for entering this province is missing."),
    INSUFFICIENT_EXPANSION_TOKENS("The expansion pool cannot supply the committed tokens."),
    SHIPPING_CAPACITY_EXCEEDED("The fleet's per-destination capacity would be exceeded."),
    OVERSEAS_LIMIT_EXCEEDED("The fleet cannot occupy another overseas box."),
    CARAVAN_INTERMEDIATE_OCCUPIED("Win competition in the full intermediate province first."),
    NO_DOMINATION_MARKERS("The player's domination markers are all on the board."),
    INVALID_COMPETITION("The province is not awaiting the specified competition."),
    INVALID_CATHEDRAL_USE("Cathedral cannot be used against these opponents."),
    INVALID_COMMAND("The command syntax is invalid."),
    UNKNOWN_PLAYER("The player is not registered in this game."),
    WRONG_PLAYER("The action belongs to another player."),
    WRONG_PHASE("The action is not allowed in the current phase."),
    INVALID_EPOCHS("Game length must be 1, 2, or 3 epochs."),
    INVALID_REBUILT_MARKER_BONUS("Choose include or exclude for rebuilt-marker bonus credit."),
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
