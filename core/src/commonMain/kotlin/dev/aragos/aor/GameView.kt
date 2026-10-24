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
    val advances: Set<AdvanceId> = emptySet(),
) {
    val stockMarkers: Int
        get() = 25 - boardMarkers

    companion object {
        internal fun from(player: PlayerState, board: BoardState): PlayerView =
            PlayerView(
                id = player.player.id,
                name = player.player.name,
                cash = player.cash,
                capital = player.capital,
                handCount = player.hand.size,
                stockTokens = player.stockTokens,
                poolTokens = player.poolTokens,
                boardMarkers = board.markerCount(player.player.id),
                misery = player.misery,
                ships = player.ships,
                advances = player.advances.owned,
            )
    }
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
    val board: List<ProvinceView> = emptyList(),
    val catalog: PublicCatalog? = null,
    val activity: ActivityView? = null,
    val deckRemaining: Int = 0,
    val endingTriggered: Boolean = false,
    val scores: List<ScoreView> = emptyList(),
    val adjustments: Map<Commodity, List<CommodityAdjustment>> = emptyMap(),
    val effects: CardTurnEffects = CardTurnEffects(),
    val activeWar: WarState? = null,
    val voids: CardVoids = CardVoids(),
) {
    companion object {
        internal fun forPlayer(state: GameState, viewer: PlayerState): GameView =
            GameView(
                stage = state.stage,
                epochs = state.epochs,
                rebuiltMarkerBonus = state.rebuiltMarkerBonus,
                players =
                    state.playerIds.map { playerId ->
                        PlayerView.from(state.playerState(playerId), state.board)
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
                board =
                    state.board.pieces.map { (province, pieces) ->
                        ProvinceView.from(province, pieces)
                    },
                catalog = PublicCatalog.forPlayers(state.playerIds.size),
                activity = ActivityView.from(state),
                deckRemaining = state.deck.size,
                endingTriggered = state.endingTriggered,
                scores =
                    if (state.stage == Stage.FINISHED) {
                        ScoreView.finalScores(state)
                    } else {
                        emptyList()
                    },
                adjustments = state.adjustments,
                effects = state.effects,
                activeWar = state.activeWar,
                voids = state.voids,
            )
    }
}

/** Public board occupancy; the static catalog supplies labels and empty provinces. */
data class ProvinceView(
    val id: ProvinceId,
    val marker: MarkerView? = null,
    val tokens: List<TokenView> = emptyList(),
) {
    companion object {
        internal fun from(province: ProvinceId, pieces: ProvincePieces): ProvinceView =
            when (pieces) {
                is ProvincePieces.Marker ->
                    ProvinceView(province, marker = MarkerView(pieces.owner, pieces.side))
                is ProvincePieces.Tokens ->
                    ProvinceView(
                        province,
                        tokens =
                            pieces.holdings.map { (owner, holding) ->
                                TokenView(owner, holding.coloured, holding.white)
                            },
                    )
            }
    }
}

data class MarkerView(val owner: PlayerId, val side: PieceSide)

data class TokenView(val owner: PlayerId, val coloured: Int, val white: Int)

/** Public catalog facts needed to read the board and construct ordinary player commands. */
data class PublicCatalog(
    val provinces: List<ProvinceDefinition>,
    val advances: List<AdvanceDefinition>,
    val commodities: List<CommodityDefinition>,
    val landBorders: Set<ProvinceConnection>,
    val coastalConnections: Set<ProvinceConnection>,
    val satelliteSupports: Set<SatelliteSupport>,
) {
    companion object {
        fun forPlayers(playerCount: Int): PublicCatalog {
            val configuration = CatalogConfiguration(playerCount)
            return PublicCatalog(
                configuration.provinces,
                AdvanceData.definitions,
                CommodityData.definitions,
                configuration.landBorders,
                configuration.coastalConnections,
                configuration.satelliteSupports,
            )
        }
    }
}

/** The enclosing actor stays visible while another player answers an interruption. */
data class ActivityView(
    val actor: PlayerId?,
    val decisionPlayer: PlayerId?,
    val choice: String?,
    val purchaseStep: PurchaseStep?,
    val startEntry: Int,
) {
    companion object {
        internal fun from(state: GameState): ActivityView {
            val prompt = state.prompts().singleOrNull()
            return ActivityView(
                state.flow.actor,
                (prompt as? Prompt.SinglePlayer)?.player,
                when (prompt) {
                    is Prompt.SinglePlayer -> prompt.choice
                    is Prompt.MultiPlayer -> prompt.choice
                    else -> null
                },
                if (state.stage == Stage.PURCHASE) state.flow.purchaseStep else null,
                state.activityStart,
            )
        }
    }
}

/** Chaos has no numeric score and cannot win. Tied active scores produce multiple winners. */
data class ScoreView(
    val player: PlayerId,
    val advancePoints: Int,
    val cash: Int,
    val misery: Int?,
    val total: Int?,
    val winner: Boolean,
) {
    companion object {
        internal fun finalScores(state: GameState): List<ScoreView> {
            val scores =
                state.playerIds.map { id ->
                    val player = state.playerState(id)
                    val misery = player.misery.value
                    ScoreView(
                        id,
                        player.advances.score,
                        player.cash,
                        misery,
                        misery?.let { value -> player.advances.score + player.cash - value },
                        false,
                    )
                }
            val winningScore = scores.mapNotNull(ScoreView::total).maxOrNull()
            return scores.map { score ->
                score.copy(winner = score.total != null && score.total == winningScore)
            }
        }
    }
}

data class LegalChoices(
    val commands: List<String> = emptyList(),
    val bidMin: Int? = null,
    val bidMax: Int? = null,
)
