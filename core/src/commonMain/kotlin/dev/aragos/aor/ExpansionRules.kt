package dev.aragos.aor

/** Why a white domination marker was placed during the current turn's Expansion phase. */
enum class MarkerPlacementOrigin {
    EXPANSION,
    EVENT_REBUILD,
    VOLUNTARY_REBUILD,
}

/**
 * Whether replacing a domination marker lost to an event earns expansion-bonus credit.
 *
 * [commandValue] is the locale-independent setup value recorded in the replay log. Both policies
 * count ordinary expansion and exclude rebuilding a marker voluntarily exchanged for a token.
 */
enum class RebuiltMarkerBonus(val commandValue: String) {
    EXCLUDE("exclude"),
    INCLUDE("include");

    fun counts(origin: MarkerPlacementOrigin): Boolean =
        when (origin) {
            MarkerPlacementOrigin.EXPANSION -> true
            MarkerPlacementOrigin.EVENT_REBUILD -> this == INCLUDE
            MarkerPlacementOrigin.VOLUNTARY_REBUILD -> false
        }

    companion object {
        fun parse(value: String): RebuiltMarkerBonus =
            entries.singleOrNull { policy -> policy.commandValue == value }
                ?: throw RuleViolation(ViolationCode.INVALID_REBUILT_MARKER_BONUS)
    }
}

/**
 * The calculated number of physical attacker tokens required to initiate a competition.
 *
 * [tokens] is the final requirement after modifiers and capital doubling. It includes attacker
 * tokens already in the province. Commitment must match it exactly: additional tokens cannot be
 * committed merely to improve the attack or dispose of tokens. Strength calculation and resolving
 * the resulting competition belong to the board-play rules.
 */
data class AttackRequirement(val tokens: Int) {
    init {
        require(tokens > 0) { "An attack must require at least one token." }
    }

    /** Rejects under- or over-commitment before spending [addedTokens] or requesting any roll. */
    fun validateCommitment(existingTokens: Int, addedTokens: Int) {
        checkRule(
            existingTokens >= 0 &&
                addedTokens >= 0 &&
                existingTokens.toLong() + addedTokens.toLong() == tokens.toLong(),
            ViolationCode.INVALID_ATTACK_TOKEN_COUNT,
        )
    }
}
