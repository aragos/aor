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
    val income: IncomeView? = null,
    /** A revealed expansion pool still usable this turn, including an empty pool. */
    val poolAvailable: Boolean = false,
) {
    val stockMarkers: Int
        get() = 25 - boardMarkers

    companion object {
        internal fun from(player: PlayerState, state: GameState): PlayerView {
            val expansionFinished =
                state.stage == Stage.EXPANSION &&
                    state.turnOrder.indexOf(player.player.id) in 0 until state.flow.cursor
            return PlayerView(
                id = player.player.id,
                name = player.player.name,
                cash = player.cash,
                capital = player.capital,
                handCount = player.hand.size,
                stockTokens = player.stockTokens,
                poolTokens = player.poolTokens,
                boardMarkers = state.board.markerCount(player.player.id),
                misery = player.misery,
                ships = player.ships,
                advances = player.advances.owned,
                income = IncomeView.from(state, player),
                poolAvailable =
                    (state.stage == Stage.TURN_ORDER_TIE ||
                        state.stage in Stage.DRAW_CARD..Stage.EXPANSION) &&
                        !expansionFinished &&
                        player.misery != MiseryStep.CHAOS,
            )
        }
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
    val currentEpoch: Int,
    val rules: RuleOptions,
    val ruleOptions: List<RuleOptionView>,
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
    val warCession: WarCessionView? = null,
    val markerPlacement: MarkerPlacementView? = null,
    val pirateTargets: PirateTargetsView? = null,
    val voids: CardVoids = CardVoids(),
    val advanceOffers: List<AdvanceOfferView> = emptyList(),
    val reliefOffers: List<ReliefOfferView> = emptyList(),
    val stabilizationCost: Long? = null,
    val burdenReferences: Set<String> = emptySet(),
    val expansionOffers: List<ExpansionOfferView> = emptyList(),
    /** Missing printed access advances for this viewer; independent of phase, routes, and ships. */
    val lockedProvinces: Set<ProvinceId> = emptySet(),
    val commodityPayouts: List<CommodityPayoutView> = emptyList(),
    val cardOffers: List<CardOfferView> = emptyList(),
    val tokenPurchaseOffers: List<TokenPurchaseOfferView> = emptyList(),
    val shippingOffer: ShippingOfferView? = null,
    val playedLeaderDiscounts: List<PlayedLeaderDiscountView> = emptyList(),
) {
    companion object {
        internal fun forPlayer(state: GameState, viewer: PlayerState): GameView =
            GameView(
                stage = state.stage,
                currentEpoch = state.currentEpoch.number,
                rules = state.rules,
                ruleOptions = RuleOptionView.from(state.rules),
                rebuiltMarkerBonus = state.rebuiltMarkerBonus,
                players =
                    state.playerIds.map { playerId ->
                        PlayerView.from(state.playerState(playerId), state)
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
                catalog = PublicCatalog.forPlayers(state.playerIds.size, state.rules),
                lockedProvinces =
                    ProvinceData.availableFor(state.playerIds.size)
                        .filterNot { province ->
                            province.access.all { requirement ->
                                requirement.isSatisfiedBy(viewer.advances.owned)
                            }
                        }
                        .map(ProvinceDefinition::id)
                        .toSet(),
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
                warCession = WarCessionView.from(state),
                markerPlacement =
                    (state.flow.decisions.lastOrNull() as? CardDecision.MarkerPlacement)?.let {
                        decision ->
                        MarkerPlacementView(decision.player, decision.destination)
                    },
                pirateTargets =
                    if (
                        state.turnNumber != null &&
                            viewer.hand.any { card ->
                                card.definition.id == EventId.PIRATES_VIKINGS.cardId
                            }
                    ) {
                        val context = state.cardContext()
                        PirateTargetsView(
                            CardRules.piratesRequiredCount(context),
                            CardRules.pirateTargets(context),
                        )
                    } else null,
                voids = state.voids,
                advanceOffers = advanceOffers(state, viewer),
                reliefOffers = reliefOffers(state, viewer),
                stabilizationCost =
                    if (state.turnNumber == null) null else PlayerEconomy.stabilizationCost(viewer),
                burdenReferences = burdenReferences(state, viewer),
                expansionOffers = ExpansionProjection.offers(state, viewer),
                commodityPayouts = CommodityPayoutView.from(state),
                cardOffers = CardProjection.offers(state, viewer),
                tokenPurchaseOffers = TokenPurchaseProjection.offers(state, viewer),
                shippingOffer = ShippingOfferView.from(state, viewer),
                playedLeaderDiscounts = PlayedLeaderDiscountView.from(state, viewer),
            )

        private fun burdenReferences(state: GameState, viewer: PlayerState): Set<String> {
            if (state.turnNumber == null || viewer.hand.isEmpty()) return emptySet()
            val context = state.cardContext()
            return viewer.hand
                .filter { card -> CardRules.isBurden(context, card.definition.id) }
                .map(HandCard::reference)
                .toSet()
        }

        private fun advanceOffers(state: GameState, viewer: PlayerState): List<AdvanceOfferView> {
            if (state.turnNumber == null || viewer.misery == MiseryStep.CHAOS) return emptyList()
            val context = state.purchaseContext(viewer.player.id)
            return AdvanceId.entries.filterNot(viewer.advances::owns).map { advance ->
                try {
                    val quote =
                        AdvancePurchases.quote(
                            viewer,
                            advance,
                            context,
                        )
                    AdvanceOfferView(
                        advance,
                        quote.price,
                        if (viewer.cash >= quote.price) {
                            null
                        } else {
                            ViolationCode.INSUFFICIENT_CASH.name
                        },
                        quote.leaders,
                    )
                } catch (failure: RuleViolation) {
                    AdvanceOfferView(advance, null, failure.code.name)
                }
            }
        }

        private fun reliefOffers(state: GameState, viewer: PlayerState): List<ReliefOfferView> {
            val turn = state.turnNumber ?: return emptyList()
            if (
                viewer.misery == MiseryStep.CHAOS ||
                    state.rules.miseryRelief != MiseryReliefRule.PURCHASED
            )
                return emptyList()
            return (1..viewer.advances.tierCount).mapNotNull { spaces ->
                try {
                    val quote = PlayerEconomy.reliefQuote(viewer, spaces, turn, state.rules)
                    ReliefOfferView(
                        spaces,
                        quote.cashCost,
                        quote.ratingSpent,
                        viewer.cash >= quote.cashCost,
                    )
                } catch (_: RuleViolation) {
                    null
                }
            }
        }
    }
}

/** Public recipient and destination of the next physical marker choice after a card effect. */
data class MarkerPlacementView(val player: PlayerId, val destination: ProvinceId)

/** Public support limits for a resolved War while its loser chooses provinces to cede. */
data class WarCessionView(
    val winner: PlayerId,
    val requiredCount: Int,
    val eligibleProvinces: Set<ProvinceId>,
    val overseasCapacity: Int,
) {
    companion object {
        internal fun from(state: GameState): WarCessionView? {
            val decision =
                state.flow.decisions.lastOrNull() as? CardDecision.WarSurrender ?: return null
            val context = state.cardContext()
            val victory = decision.victory
            val eligible = WarRules.eligibleProvinces(context, victory.winner, victory.loser)
            val capacity = WarRules.overseasCapacity(context, victory.winner)
            return WarCessionView(
                victory.winner,
                WarRules.requiredCessions(context, victory),
                eligible,
                capacity,
            )
        }
    }
}

/** Leader credits are already authorized and included in [price], never an additional rebate. */
data class AdvanceOfferView(
    val advance: AdvanceId,
    val price: Int?,
    val unavailableReason: String?,
    val leaderDiscounts: List<AppliedLeaderDiscount> = emptyList(),
)

data class ReliefOfferView(
    val spaces: Int,
    val cashCost: Int,
    val ratingSpent: Int,
    val affordable: Boolean,
)

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
    val seaCoasts: Set<SeaCoastConnection>,
    /** Only assigned seas appear here; sea availability is independent of area availability. */
    val seaAreas: Map<SeaArea, GeographicalArea>,
    /** Ordered printed misery boxes; Chaos is the final, nonnumeric space. */
    val miseryTrack: List<MiseryStep>,
    /** Legal fleet spaces, grouped by fleet in the UI rather than implying an upgrade route. */
    val shippingTrack: List<ShippingCapability>,
) {
    companion object {
        fun forPlayers(playerCount: Int, rules: RuleOptions = RuleOptions()): PublicCatalog {
            val configuration = CatalogConfiguration(playerCount, rules)
            return PublicCatalog(
                configuration.provinces,
                rules.definitions,
                CommodityData.definitions,
                configuration.landBorders,
                configuration.coastalConnections,
                configuration.satelliteSupports,
                configuration.seaCoasts,
                configuration.seas
                    .mapNotNull { sea -> sea.area?.let { area -> sea to area } }
                    .toMap(),
                MiseryStep.entries,
                ShippingCapability.entries,
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
    /** Game-local entry ordinal identifying this decision, including nested replacements. */
    val decisionEntry: Int,
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
                state.decisionStart,
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
                    val points = player.advances.score(state.rules)
                    ScoreView(
                        id,
                        points,
                        player.cash,
                        misery,
                        misery?.let { value -> points + player.cash - value },
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

/** Compact target projection avoids enumerating every combination of coastal markers. */
data class PirateTargetsView(val requiredCount: Int, val eligibleProvinces: Set<ProvinceId>)
