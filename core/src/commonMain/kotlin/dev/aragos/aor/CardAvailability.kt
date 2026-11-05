package dev.aragos.aor

/** Public circulation uses configured introductions and plays, never hidden card locations. */
internal object CardAvailability {
    fun unplayed(state: GameState): List<CardDefinition> {
        if (state.stage == Stage.CONFIGURATION || state.stage == Stage.SHUFFLE) return emptyList()
        return Epoch.entries
            .filter { epoch -> epoch.number <= state.currentEpoch.number }
            .flatMap(state.rules::cardsForEpoch)
            .filterNot(state.playedCards::contains)
            .map(CardData::definition)
    }
}
