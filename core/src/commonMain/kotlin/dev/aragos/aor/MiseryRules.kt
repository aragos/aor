package dev.aragos.aor

enum class EconomyStatus {
    ACTIVE,
    ENTERED_CHAOS,
}

/**
 * A pure player transition. On [EconomyStatus.ENTERED_CHAOS], the game driver must discard the
 * player's hand, retain neutral board defenders and remove the player from pending decisions.
 */
data class EconomyResult(val player: PlayerState, val status: EconomyStatus) {
    companion object {
        internal fun forPlayer(player: PlayerState): EconomyResult =
            EconomyResult(
                player,
                if (player.misery == MiseryStep.CHAOS) EconomyStatus.ENTERED_CHAOS
                else EconomyStatus.ACTIVE,
            )
    }
}

object MiseryRules {
    /** Move spaces, not printed values. Relief never goes below zero or reverses Chaos. */
    fun shift(current: MiseryStep, spaces: Int): MiseryStep {
        if (current == MiseryStep.CHAOS) return current
        val target =
            (current.ordinal.toLong() + spaces).coerceIn(0L, MiseryStep.CHAOS.ordinal.toLong())
        return MiseryStep.entries[target.toInt()]
    }

    /**
     * Pay an unpaid stabilization [cost] in the fewest upward spaces. Box differences telescope;
     * Chaos follows when no printed box can cover the debt. Zero debt moves no spaces.
     */
    fun payDebt(current: MiseryStep, cost: Long): MiseryStep {
        require(cost >= 0) { "A misery debt cannot be negative." }
        if (current == MiseryStep.CHAOS || cost == 0L) return current
        val currentValue =
            requireNotNull(current.value) { "A printed misery box requires a value." }
        val target =
            MiseryStep.entries.firstOrNull { candidate ->
                candidate.ordinal >= current.ordinal &&
                    candidate.value?.let { value -> value.toLong() - currentValue >= cost } == true
            }
        return target ?: MiseryStep.CHAOS
    }
}

internal fun PlayerState.requireActive() {
    checkRule(misery != MiseryStep.CHAOS, ViolationCode.PLAYER_IN_CHAOS, listOf(player.id.value))
}
