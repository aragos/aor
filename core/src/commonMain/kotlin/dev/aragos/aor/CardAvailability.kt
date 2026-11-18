package dev.aragos.aor

/**
 * Public circulation uses configured introductions, plays and public discards, never hidden card
 * locations.
 */
internal object CardAvailability {
    fun unplayed(state: GameState): List<CardDefinition> {
        if (state.stage == Stage.CONFIGURATION || state.stage == Stage.SHUFFLE) return emptyList()
        return Epoch.entries
            .filter { epoch -> epoch.number <= state.currentEpoch.number }
            .flatMap(state.rules::cardsForEpoch)
            .filterNot(state.publiclyDiscardedCards::contains)
            .map(CardData::definition)
    }
}
