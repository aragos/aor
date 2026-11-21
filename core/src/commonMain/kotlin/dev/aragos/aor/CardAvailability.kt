package dev.aragos.aor

/**
 * Public circulation uses configured introductions, plays and public discards, never hidden card
 * locations.
 */
internal object CardAvailability {
    fun unplayed(state: GameState): List<CardDefinition> {
        if (state.stage == Stage.CONFIGURATION || state.stage == Stage.SHUFFLE) return emptyList()
        val introduced = state.rules.introducedCards(state.currentEpoch)
        if (state.publiclyDiscardedCards.isEmpty()) return introduced
        return introduced.filterNot { card -> card.id in state.publiclyDiscardedCards }
    }
}
