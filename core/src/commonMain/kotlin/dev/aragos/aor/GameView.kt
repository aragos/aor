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

/**
 * Public game information plus only the requesting player's private hand.
 *
 * [turnNumber] is null during setup. [turnOrder] and [orderBoxes] remain empty until every tie is
 * resolved. [revealedTokenPurchases] contains signed amounts only after simultaneous reveal.
 * [provisionalTieOrder] contains only players already inserted in the current unresolved tie.
 */
data class GameView(
    val stage: Stage,
    val epochs: Int,
    val rebuiltMarkerBonus: RebuiltMarkerBonus,
    val players: List<PlayerView>,
    val hand: List<HandCard>,
    val capitalOrder: List<PlayerId>,
    val revealedBids: Map<PlayerId, Int>,
    val turnNumber: Int?,
    val turnOrder: List<PlayerId>,
    val orderBoxes: Map<PlayerId, Int>,
    val revealedTokenPurchases: Map<PlayerId, Int>,
    val provisionalTieOrder: List<PlayerId>,
) {
    companion object {
        internal fun forPlayer(state: GameState, viewer: PlayerState): GameView =
            GameView(
                stage = state.stage,
                epochs = state.epochs,
                rebuiltMarkerBonus = state.rebuiltMarkerBonus,
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
                            boardMarkers = state.board.markerCount(playerId),
                            misery = player.misery,
                            ships = player.ships,
                        )
                    },
                hand = viewer.hand,
                capitalOrder =
                    if (state.capitalSelection.isResolved) state.capitalOrder else emptyList(),
                revealedBids = SetupProjection.revealedCapitalBids(state),
                turnNumber = state.turnNumber,
                turnOrder = state.turnOrder,
                orderBoxes = state.orderBoxes,
                revealedTokenPurchases = TurnOrderProjection.revealedPurchases(state),
                provisionalTieOrder = state.turnOrderSelection.ties.firstOrNull()?.placed.orEmpty(),
            )
    }
}

data class LegalChoices(
    val commands: List<String> = emptyList(),
    val bidMin: Int? = null,
    val bidMax: Int? = null,
)
