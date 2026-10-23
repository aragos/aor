package dev.aragos.aor

/** A public reference such as `hand-1` that never encodes the private card identity. */
data class HandCard(val reference: String, val definition: CardDefinition)

data class PlayerView(
    val id: PlayerId,
    val name: String,
    val cash: Int,
    val capital: String?,
    val handCount: Int,
    val stockTokens: Int = 36,
    val poolTokens: Int = 0,
    val boardMarkers: Int,
    val misery: MiseryStep = MiseryStep.ZERO,
    val ships: ShippingCapability = ShippingCapability.NONE,
) {
    val stockMarkers: Int
        get() = 25 - boardMarkers
}

/** Public game information plus only the requesting player's private hand. */
data class GameView(
    val stage: Stage,
    val epochs: Int,
    val players: List<PlayerView>,
    val hand: List<HandCard>,
    val capitalOrder: List<PlayerId>,
    val revealedBids: Map<PlayerId, Int>,
) {
    companion object {
        internal fun forPlayer(state: GameState, viewer: PlayerState): GameView =
            GameView(
                stage = state.stage,
                epochs = state.epochs,
                players =
                    state.playerIds.map { playerId ->
                        val player = state.playerState(playerId)
                        PlayerView(
                            id = player.player.id,
                            name = player.player.name,
                            cash = player.cash,
                            capital = player.capital,
                            handCount = player.hand.size,
                            stockTokens = player.stockTokens,
                            poolTokens = player.poolTokens,
                            boardMarkers = player.boardMarkers,
                            misery = player.misery,
                            ships = player.ships,
                        )
                    },
                hand = viewer.hand,
                capitalOrder =
                    if (state.capitalSelection.isResolved) state.capitalOrder else emptyList(),
                revealedBids = SetupProjection.revealedCapitalBids(state),
            )
    }
}

data class LegalChoices(
    val commands: List<String> = emptyList(),
    val bidMin: Int? = null,
    val bidMax: Int? = null,
)
