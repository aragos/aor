package dev.aragos.aor

/** Deterministic one-card opening distribution. */
internal object SetupDeal {
    fun initialCards(state: GameState): List<String> = state.rules.cardsForEpoch(Epoch.ONE)

    const val cardsPerPlayer: Int = 1

    /** Filtering only the first deal leaves excluded physical cards in the draw pile. */
    fun nextCard(state: GameState): String? =
        if (state.stage == Stage.INITIAL_DRAW) {
            state.deck.firstOrNull { id ->
                state.rules.initialCard.accepts(CardData.definition(id))
            }
        } else {
            state.deck.firstOrNull()
        }

    fun afterInitialDraw(state: GameState): GameState {
        val complete =
            state.players.values.all { owner ->
                owner.hand.size == cardsPerPlayer
            }
        if (!complete) return state
        return state.copy(stage = Stage.BIDDING)
    }
}
