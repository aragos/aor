package dev.aragos.aor

/** Setup-specific visibility of the simultaneous capital bid batch. */
internal object SetupProjection {
    fun revealedCapitalBids(state: GameState): Map<PlayerId, Int> =
        if (state.capitalSelection.order.isEmpty()) {
            emptyMap()
        } else {
            state.playerIds.associateWith { playerId ->
                requireNotNull(state.playerState(playerId).capitalBid) {
                    "Every capital bid must be present before reveal."
                }
            }
        }
}
