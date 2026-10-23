package dev.aragos.aor

/** Reveal token purchases only after the entire batch has established a provisional ranking. */
internal object TurnOrderProjection {
    fun revealedPurchases(state: GameState): Map<PlayerId, Int> =
        if (
            state.turnOrderSelection.order.isEmpty() ||
                state.players.values.any { player -> player.tokenPurchase == null }
        ) {
            emptyMap()
        } else {
            state.players.mapValues { (_, player) ->
                requireNotNull(player.tokenPurchase) {
                    "Revealed purchases require every player's amount."
                }
            }
        }
}
