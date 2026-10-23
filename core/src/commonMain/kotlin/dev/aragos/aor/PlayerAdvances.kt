package dev.aragos.aor

/** A permanent purchase record. [turnNumber] starts at one; it is not an epoch or phase number. */
data class AdvanceAcquisition(val advance: AdvanceId, val turnNumber: Int) {
    init {
        require(turnNumber > 0) { "An advance acquisition requires a positive turn number." }
    }
}

/**
 * Player-owned acquisition history. Ownership, tiers, category credits and current-turn relief are
 * derived from these records. Credits become eligible in the turn after acquisition; abilities and
 * prerequisites are available immediately.
 */
data class PlayerAdvances(val acquisitions: List<AdvanceAcquisition> = emptyList()) {
    init {
        require(
            acquisitions.map(AdvanceAcquisition::advance).distinct().size == acquisitions.size
        ) {
            "An advance can appear only once in a player's acquisition history."
        }
    }

    val owned: Set<AdvanceId>
        get() = acquisitions.map(AdvanceAcquisition::advance).toSet()

    val tierCount: Int
        get() = AdvanceCategory.entries.minOf(::categoryCount)

    val score: Int
        get() = acquisitions.sumOf { acquisition -> definition(acquisition.advance).cost }

    fun owns(advance: AdvanceId): Boolean = acquisitions.any { acquisition ->
        acquisition.advance == advance
    }

    fun acquire(advance: AdvanceId, turnNumber: Int): PlayerAdvances {
        checkRule(
            !owns(advance),
            ViolationCode.ADVANCE_ALREADY_OWNED,
            listOf(advance.letter.toString()),
        )
        return copy(acquisitions = acquisitions + AdvanceAcquisition(advance, turnNumber))
    }

    fun categoryCount(category: AdvanceCategory): Int = acquisitions.count { acquisition ->
        definition(acquisition.advance).category == category
    }

    /**
     * Category credits freeze for a turn; purchases during its Purchase phase cannot add to them.
     */
    fun creditsFor(category: AdvanceCategory, currentTurn: Int): Int {
        requireValidTurn(currentTurn)
        return acquisitions
            .filter { acquisition ->
                acquisition.turnNumber < currentTurn &&
                    definition(acquisition.advance).category == category
            }
            .sumOf { acquisition -> definition(acquisition.advance).credit }
    }

    fun miseryReliefFor(currentTurn: Int): Int {
        requireValidTurn(currentTurn)
        return acquisitions
            .filter { acquisition -> acquisition.turnNumber == currentTurn }
            .sumOf { acquisition -> definition(acquisition.advance).miseryRelief }
    }

    internal fun requireValidTurn(currentTurn: Int) {
        require(currentTurn > 0) { "Advance calculations require a positive current turn." }
        require(acquisitions.all { acquisition -> acquisition.turnNumber <= currentTurn }) {
            "Advance acquisition history cannot contain a future turn."
        }
    }
}

internal fun definition(advance: AdvanceId): AdvanceDefinition =
    AdvanceData.definitions.single { candidate -> candidate.id == advance }

/** Relief already used during [turnNumber]; [ratingSpent] records MR dollars, not misery spaces. */
data class MiseryReliefUsage(val turnNumber: Int, val spaces: Int = 0, val ratingSpent: Int = 0) {
    init {
        require(turnNumber > 0) { "Misery relief usage requires a positive turn number." }
        require(spaces >= 0) { "Used misery relief spaces cannot be negative." }
        require(ratingSpent >= 0) { "Spent misery relief rating cannot be negative." }
    }
}
