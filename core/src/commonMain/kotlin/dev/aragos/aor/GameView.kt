package dev.aragos.aor

/** A public reference such as `hand-1` that never encodes the private card identity. */
data class HandCard(val reference: String, val definition: CardDefinition)

data class PlayerView(
    val id: PlayerId,
    val name: String,
    val cash: Int,
    /**
     * Dollars recorded at the latest token-purchase reveal; initially 40 before the first reveal.
     */
    val writtenCash: Int,
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
                writtenCash = player.writtenCash,
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
    /** Introduced cards not publicly played in this epoch, including hands and private discards. */
    val unplayedCards: List<CardDefinition> = emptyList(),
    val endingTriggered: Boolean = false,
    val scores: List<ScoreView> = emptyList(),
    val adjustments: Map<Commodity, List<CommodityAdjustment>> = emptyMap(),
    val effects: CardTurnEffects = CardTurnEffects(),
    /** Owner/non-owner pairs and their public usage in the current game turn. */
    val cathedralPairs: List<CathedralPairView> = emptyList(),
    val activeWar: WarState? = null,
    val warCession: WarCessionView? = null,
    val markerPlacement: MarkerPlacementView? = null,
    val replacementToken: ReplacementTokenView? = null,
    val civilWarLoss: CivilWarLossView? = null,
    val pirateTargets: PirateTargetsView? = null,
    val voids: CardVoids = CardVoids(),
    val advanceOffers: List<AdvanceOfferView> = emptyList(),
    val reliefOffers: List<ReliefOfferView> = emptyList(),
    /** Unused MR dollars from this viewer's advance purchases in the current turn. */
    val reliefRatingAvailable: Int = 0,
    val patronageOffers: List<PatronageOfferView> = emptyList(),
    val adjustmentOffers: List<AdjustmentCancellationOffer> = emptyList(),
    val cardPurchaseOffer: CardPurchaseOfferView? = null,
    val stabilizationCost: Long? = null,
    /** Printed misery box after paying stabilization in misery; null means Chaos. */
    val stabilizationMisery: Int? = null,
    val burdenReferences: Set<String> = emptySet(),
    val expansionOffers: List<ExpansionOfferView> = emptyList(),
    val markerReductionOffers: List<MarkerReductionOfferView> = emptyList(),
    /** Destination diagnostics include pool/shipping failures omitted from [expansionOffers]. */
    val expansionInspections: List<ExpansionOfferView> = emptyList(),
    /** Missing printed access advances for this viewer; independent of phase, routes, and ships. */
    val lockedProvinces: Set<ProvinceId> = emptySet(),
    val commodityPayouts: List<CommodityPayoutView> = emptyList(),
    val commodityCards: List<CommodityCardCountView> = emptyList(),
    val cardOffers: List<CardOfferView> = emptyList(),
    val cardInspections: List<CardInspectionView> = emptyList(),
    val tokenPurchaseOffers: List<TokenPurchaseOfferView> = emptyList(),
    val shippingOffer: ShippingOfferView? = null,
    /** Pool-token cost for the viewer's current Expansion offer; never a cash price. */
    val expansionCardCost: Int? = null,
    val playedLeaderDiscounts: List<PlayedLeaderDiscountView> = emptyList(),
    val unplayedAdvanceLeaders: List<AdvanceLeaderAvailabilityView> = emptyList(),
    val windmillOffer: WindmillOfferView? = null,
) {
    companion object {
        internal fun forViewer(
            state: GameState,
            viewer: PlayerState?,
            decisionOnly: Boolean = false,
        ): GameView {
            val decision = ActivityView.from(state).choice
            fun needed(vararg choices: String): Boolean = !decisionOnly || decision in choices
            val expansionInspections =
                viewer?.let { ExpansionProjection.inspections(state, it) }.orEmpty()
            return GameView(
                stage = state.stage,
                currentEpoch = state.currentEpoch.number,
                rules = state.rules,
                ruleOptions = RuleOptionView.from(state.rules),
                rebuiltMarkerBonus = state.rebuiltMarkerBonus,
                players =
                    state.playerIds.map { playerId ->
                        PlayerView.from(state.playerState(playerId), state)
                    },
                hand = viewer?.hand.orEmpty(),
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
                catalog =
                    PublicCatalog.forPlayers(
                        state.playerIds.size,
                        state.rules,
                        state.catalog.version,
                    ),
                lockedProvinces =
                    viewer
                        .takeUnless { decisionOnly }
                        ?.let { participant ->
                            state.catalog.provinces
                                .filterNot { province ->
                                    province.access.all { requirement ->
                                        requirement.isSatisfiedBy(participant.advances.owned)
                                    }
                                }
                                .map(ProvinceDefinition::id)
                                .toSet()
                        }
                        .orEmpty(),
                activity = ActivityView.from(state),
                deckRemaining = state.deck.size,
                unplayedCards = if (decisionOnly) emptyList() else CardAvailability.unplayed(state),
                endingTriggered = state.endingTriggered,
                scores = ScoreView.from(state),
                adjustments = state.adjustments,
                effects = state.effects,
                cathedralPairs = CathedralPairView.from(state),
                activeWar = state.activeWar,
                warCession = WarCessionView.from(state),
                markerPlacement =
                    (state.flow.decisions.lastOrNull() as? CardDecision.MarkerPlacement)?.let {
                        decision ->
                        MarkerPlacementView(decision.player, decision.destination)
                    },
                replacementToken = ReplacementTokenView.from(state),
                civilWarLoss = CivilWarLossView.from(state),
                pirateTargets =
                    if (
                        state.turnNumber != null &&
                            viewer?.hand?.any { card ->
                                card.definition.id == EventId.PIRATES_VIKINGS.cardId
                            } == true
                    ) {
                        val context = state.cardContext()
                        PirateTargetsView(
                            CardRules.piratesRequiredCount(context),
                            CardRules.pirateTargets(context),
                        )
                    } else null,
                voids = state.voids,
                advanceOffers =
                    viewer
                        ?.takeIf { needed("purchase-advances") }
                        ?.let { advanceOffers(state, it) }
                        .orEmpty(),
                patronageOffers =
                    viewer?.let { PatronageOfferView.from(state, it.player.id) }.orEmpty(),
                adjustmentOffers =
                    viewer?.let { AdjustmentCancellation.offers(state, it.player.id) }.orEmpty(),
                reliefOffers =
                    viewer
                        ?.takeIf { needed("purchase-relief") }
                        ?.let { ReliefOfferView.from(state, it) }
                        .orEmpty(),
                reliefRatingAvailable =
                    viewer?.let { participant ->
                        state.turnNumber?.let { turn ->
                            PlayerEconomy.availableReliefRating(participant, turn)
                        }
                    } ?: 0,
                cardPurchaseOffer =
                    viewer
                        ?.let { CardPurchases.offer(state, it) }
                        ?.takeIf { state.stage == Stage.BUY_CARD },
                stabilizationCost =
                    if (state.turnNumber == null) null
                    else viewer?.let(PlayerEconomy::stabilizationCost),
                stabilizationMisery =
                    viewer?.let { participant ->
                        MiseryRules.payDebt(
                                participant.misery,
                                PlayerEconomy.stabilizationCost(participant),
                            )
                            .value
                    },
                burdenReferences = viewer?.let { burdenReferences(state, it) }.orEmpty(),
                expansionOffers = ExpansionProjection.offers(expansionInspections),
                markerReductionOffers =
                    viewer
                        ?.takeIf { !decisionOnly || state.board.markerCount(it.player.id) == 25 }
                        ?.let { ExpansionProjection.reductions(state, it) }
                        .orEmpty(),
                expansionInspections = expansionInspections,
                commodityPayouts =
                    if (
                        needed(
                            "expand",
                            "play-cards",
                            "prepare-war",
                            "choose-capital",
                            "cancel-adjustment",
                            "war-surrender",
                            "replace-event-token",
                            "place-event-marker",
                        )
                    ) {
                        CommodityPayoutView.from(state)
                    } else emptyList(),
                commodityCards =
                    if (decisionOnly) emptyList() else CommodityCardCountView.from(state),
                cardOffers =
                    viewer
                        ?.takeIf { needed("play-cards", "prepare-war") }
                        ?.let { CardProjection.offers(state, it) }
                        .orEmpty(),
                cardInspections =
                    if (needed("choose-capital")) CardProjection.inspections(state, viewer)
                    else emptyList(),
                tokenPurchaseOffers =
                    viewer?.let { TokenPurchaseProjection.offers(state, it) }.orEmpty(),
                shippingOffer = viewer?.let { ShippingOfferView.from(state, it) },
                expansionCardCost =
                    if (
                        state.stage == Stage.EXPANSION &&
                            viewer != null &&
                            state.flow.actor == viewer.player.id
                    ) {
                        EconomyTables.expansionCardCost(state.flow.expansionCardCount)
                    } else {
                        null
                    },
                playedLeaderDiscounts =
                    if (needed("purchase-advances")) {
                        PlayedLeaderDiscountView.from(state, viewer)
                    } else emptyList(),
                unplayedAdvanceLeaders =
                    if (decisionOnly) emptyList() else AdvanceLeaderAvailabilityView.from(state),
                windmillOffer =
                    (state.flow.decisions.lastOrNull() as? BoardDecision.WindmillOpportunity)
                        ?.let { opportunity ->
                            val cost = state.catalog.map.province(opportunity.province).marketNumber
                            WindmillOfferView(
                                opportunity.player,
                                opportunity.victim,
                                opportunity.province,
                                cost,
                                cost / 6.0,
                            )
                        },
            )
        }

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
                val quote = AdvancePurchases.estimate(viewer, advance, context)
                val unavailableReason =
                    try {
                        AdvancePurchases.requireEligible(viewer, advance, context)
                        if (viewer.cash >= quote.price) {
                            null
                        } else {
                            ViolationCode.INSUFFICIENT_CASH.name
                        }
                    } catch (failure: RuleViolation) {
                        failure.code.name
                    }
                AdvanceOfferView(
                    advance = advance,
                    price = quote.price,
                    unavailableReason = unavailableReason,
                    leaderDiscounts = quote.leaders,
                    priceBreakdown = quote,
                    miseryChanges =
                        listOfNotNull(
                            MiseryChangeView.between(
                                viewer.player.id,
                                quote.miseryBefore,
                                quote.miseryAfter,
                            )
                        ),
                )
            }
        }
    }
}

/** Public token price and dice chance for the current optional Wind/Watermill attempt. */
data class WindmillOfferView(
    val player: PlayerId,
    val victim: PlayerId,
    val province: ProvinceId,
    val tokenCost: Int,
    val successProbability: Double,
)

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

/**
 * Informational price for an unowned advance, including when [unavailableReason] blocks purchase.
 * Leader credits are already authorized and included in [price], never an additional rebate.
 */
data class AdvanceOfferView(
    val advance: AdvanceId,
    val price: Int?,
    val unavailableReason: String?,
    val leaderDiscounts: List<AppliedLeaderDiscount> = emptyList(),
    val priceBreakdown: AdvanceQuote? = null,
    val miseryChanges: List<MiseryChangeView> = emptyList(),
)

data class ReliefOfferView(
    val spaces: Int,
    val cashCost: Int,
    val ratingSpent: Int,
    val affordable: Boolean,
    val cashAfter: Int,
    val miseryBefore: Int?,
    val miseryAfter: Int?,
) {
    companion object {
        internal fun from(state: GameState, viewer: PlayerState): List<ReliefOfferView> {
            val turn = state.turnNumber ?: return emptyList()
            if (viewer.misery == MiseryStep.CHAOS) return emptyList()
            return (1..viewer.advances.tierCount).mapNotNull { spaces ->
                try {
                    val quote = PlayerEconomy.reliefQuote(viewer, spaces, turn)
                    ReliefOfferView(
                        spaces,
                        quote.cashCost,
                        quote.ratingSpent,
                        viewer.cash >= quote.cashCost,
                        viewer.cash - quote.cashCost,
                        viewer.misery.value,
                        quote.target.value,
                    )
                } catch (_: RuleViolation) {
                    null
                }
            }
        }
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
    val seaCoasts: Set<SeaCoastConnection>,
    /** Only assigned seas appear here; sea availability is independent of area availability. */
    val seaAreas: Map<SeaArea, GeographicalArea>,
    /** Ordered printed misery boxes; Chaos is the final, nonnumeric space. */
    val miseryTrack: List<MiseryStep>,
    /** Legal fleet spaces, grouped by fleet in the UI rather than implying an upgrade route. */
    val shippingTrack: List<ShippingCapability>,
    /** Base income in dollars, indexed by map markers, for the game's starting player count. */
    val incomeTrack: List<Int>,
    /**
     * Printed boxes and green-die results for the starting player count, before or after bidding.
     */
    val orderTrack: List<OrderBoxView>,
    /** Recorded catalog identity also selects the matching browser geometry. */
    val dataVersion: String = DATA_VERSION,
    /** Counts of newly introduced commodity cards for each configured epoch. */
    val commodityCards: List<CommodityCardCountView> = emptyList(),
) {
    companion object {
        fun forPlayers(
            playerCount: Int,
            rules: RuleOptions = RuleOptions(),
            version: CatalogVersion = CatalogVersion.CATALOG_16,
        ): PublicCatalog {
            val configuration = CatalogConfiguration(playerCount, rules, version)
            return PublicCatalog(
                configuration.provinces,
                AdvanceData.definitions,
                configuration.commodities,
                configuration.landBorders,
                configuration.coastalConnections,
                configuration.satelliteSupports,
                configuration.seaCoasts,
                configuration.seas
                    .mapNotNull { sea ->
                        configuration.map.seaAreas[sea]?.let { area -> sea to area }
                    }
                    .toMap(),
                MiseryStep.entries,
                ShippingCapability.entries,
                EconomyTables.incomeFor(playerCount),
                OrderBoxes.forPlayers(playerCount).map { box ->
                    OrderBoxView(
                        number = box,
                        greenWins = CompetitionRules.greenWinningRolls(box),
                    )
                },
                dataVersion = version.wireValue,
                commodityCards =
                    configuration.commodities.map { commodity ->
                        CommodityCardCountView(commodity.id, commodity.cardsByEpoch(rules))
                    },
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
    /** Public contested province while a Cathedral response or competition roll is pending. */
    val competitionProvince: ProvinceId? = null,
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
                when (val decision = state.flow.decisions.lastOrNull()) {
                    is BoardDecision.CathedralChoice -> decision.plan.destination
                    is BoardDecision.CompetitionDice -> decision.plan.destination
                    else -> null
                },
            )
        }
    }
}

/** Public current totals; only a finished game declares winners. Chaos has no numeric score. */
data class ScoreView(
    val player: PlayerId,
    val advancePoints: Int,
    val cash: Int,
    val misery: Int?,
    val total: Int?,
    val winner: Boolean,
) {
    companion object {
        internal fun from(state: GameState): List<ScoreView> {
            val scores =
                state.playerIds.map { id ->
                    val player = state.playerState(id)
                    val misery = player.misery.value
                    val points = player.advances.score
                    ScoreView(
                        id,
                        points,
                        player.cash,
                        misery,
                        misery?.let { value -> points + player.cash - value },
                        false,
                    )
                }
            if (state.stage != Stage.FINISHED) return scores
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
