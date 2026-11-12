package dev.aragos.aor

/**
 * Public inputs for one live cancellation window. No hand, deck, seeds or future outcomes belong
 * here. [from] binds an existing actor view; serialized callers retain its exact owner quotes.
 */
data class AdjustmentCancellationContext(
    val actor: PlayerId,
    val cash: Int,
    val offers: List<AdjustmentCancellationOffer>,
    val payouts: List<CommodityPayoutView>,
) {
    init {
        require(cash >= 0) { "Cancellation context cash must be nonnegative." }
        require(offers.map { it.command }.distinct().size == offers.size) {
            "Cancellation offers must have distinct canonical commands."
        }
        require(offers.all { it.cost > 0 && it.cashAfter == cash - it.cost && it.cashAfter >= 0 }) {
            "Cancellation offers must retain their affordable cash quote."
        }
        require(payouts.map { it.commodity }.distinct().size == payouts.size) {
            "Cancellation context requires one public forecast per commodity."
        }
        require(
            payouts.all { forecast ->
                forecast.owners.map { it.player }.distinct().size == forecast.owners.size &&
                    forecast.owners.all {
                        it.marketCount > 0 &&
                            it.industryBonus in 0..1 &&
                            it.effectiveBox >= 0 &&
                            it.payout >= 0
                    }
            }
        ) {
            "Public payout owners require distinct players and nonnegative quoted holdings."
        }
    }

    companion object {
        fun from(view: GameView, actor: PlayerId): AdjustmentCancellationContext {
            require(
                view.stage == Stage.DRAW_CARD &&
                    view.turnOrder.firstOrNull() == actor &&
                    view.activity?.choice == "cancel-adjustment" &&
                    view.activity.decisionPlayer == actor
            ) {
                "A cancellation context requires the actual first player's live window."
            }
            val player =
                requireNotNull(view.players.singleOrNull { it.id == actor }) {
                    "The cancellation actor must belong to its public view."
                }
            return AdjustmentCancellationContext(
                actor,
                player.cash,
                view.adjustmentOffers,
                view.commodityPayouts,
            )
        }
    }
}

/** Before/after public current-board amounts; other players' holdings remain distinct. */
data class AdjustmentCancellationOwnerQuote(
    val player: PlayerId,
    val marketCount: Int,
    val industryBonus: Int,
    val boxBefore: Int,
    val boxAfter: Int,
    val payoutBefore: Int,
    val payoutAfter: Int,
)

/** Gold and Ivory retain separate queues/owners inside their single shared payment action. */
data class AdjustmentCancellationCommodityQuote(
    val commodity: Commodity,
    val before: List<CommodityAdjustment>,
    val after: List<CommodityAdjustment>,
    val owners: List<AdjustmentCancellationOwnerQuote>,
)

/**
 * PASS has no changed commodities and costs zero; these are conditional quotes, not future gains.
 */
data class AdjustmentCancellationQuote(
    val command: String,
    val adjustment: CommodityAdjustment?,
    val cost: Int,
    val cashBefore: Int,
    val cashAfter: Int,
    val commodities: List<AdjustmentCancellationCommodityQuote>,
)

/**
 * Pure public cancellation arithmetic; never advances a phase, draws a card or chooses an action.
 */
object AdjustmentCancellationProjection {
    fun quotes(context: AdjustmentCancellationContext): List<AdjustmentCancellationQuote> =
        listOf(quote(context, TurnCommand.Pass)) +
            context.offers.map { offer ->
                val command = TextCodec.parseCommand(offer.command)
                require(command is TurnCommand.CancelAdjustment) {
                    "A cancellation offer must contain a typed cancellation command."
                }
                quote(context, command)
            }

    fun quote(
        context: AdjustmentCancellationContext,
        command: TurnCommand,
    ): AdjustmentCancellationQuote {
        if (command == TurnCommand.Pass) {
            return AdjustmentCancellationQuote(
                "pass",
                null,
                0,
                context.cash,
                context.cash,
                emptyList(),
            )
        }
        require(command is TurnCommand.CancelAdjustment) {
            "Public cancellation projection accepts only PASS or cancellation."
        }
        val offer =
            requireNotNull(context.offers.singleOrNull { it.command == command.toText() }) {
                "Public cancellation projection requires an existing offered action."
            }
        val before = context.payouts.associate { it.commodity to it.adjustments }
        val after = AdjustmentCancellation.removeMarkers(before, offer, command.adjustment)
        val changes =
            offer.commodities.sortedBy(Commodity::ordinal).map { commodity ->
                val forecast =
                    requireNotNull(context.payouts.singleOrNull { it.commodity == commodity }) {
                        "Every affected commodity requires its current public payout quote."
                    }
                val nextQueue = after[commodity].orEmpty()
                AdjustmentCancellationCommodityQuote(
                    commodity,
                    forecast.adjustments,
                    nextQueue,
                    forecast.owners.map { owner ->
                        val box =
                            CardRules.commodityPayoutBox(
                                marketCount = owner.marketCount,
                                industry = owner.industryBonus == 1,
                                adjustmentOffset = nextQueue.firstOrNull()?.payoutOffset ?: 0,
                            )
                        AdjustmentCancellationOwnerQuote(
                            owner.player,
                            owner.marketCount,
                            owner.industryBonus,
                            owner.effectiveBox,
                            box,
                            owner.payout,
                            CardRules.commodityPayout(commodity, box),
                        )
                    },
                )
            }
        return AdjustmentCancellationQuote(
            command.toText(),
            command.adjustment,
            offer.cost,
            context.cash,
            offer.cashAfter,
            changes,
        )
    }
}
