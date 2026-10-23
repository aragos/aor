package dev.aragos.aor

enum class ViolationCode(val description: String) {
    UNPLAYED_FINAL_CARDS("Play every non-burden card before passing Final Card Play."),
    STABILIZATION_REQUIRED("Pay stabilization in cash or misery before continuing."),
    INVALID_CARD_PURCHASE("This player cannot buy another card in this phase."),
    INVALID_CARD_DISCARD("Master Art permits one held card to be discarded in Buy Card."),
    INVALID_ADJUSTMENT("Choose a shortage or surplus marker currently on this commodity."),
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
    INVALID_EPOCHS("Only one-epoch games are supported."),
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
    PLAYER_IN_CHAOS("A player in Chaos cannot perform this action."),
    ADVANCE_ALREADY_OWNED("The player already owns this advance."),
    MISSING_ADVANCE_PREREQUISITE("Acquire every prerequisite before buying this advance."),
    ADVANCE_CATEGORY_FORBIDDEN("Papal Decree forbids buying this advance category this turn."),
    INSUFFICIENT_CASH("The player cannot afford the full cost of this purchase."),
    SHIP_UPGRADE_ALREADY_BOUGHT("The player has already bought a shipping upgrade this turn."),
    SHIP_UPGRADE_UNAVAILABLE("The current fleet has no further paid upgrade."),
    INVALID_MISERY_RELIEF("Choose positive relief spaces within the remaining tier allowance."),
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
