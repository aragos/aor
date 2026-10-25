package dev.aragos.aor

/** Ordinary income is separate from conditional Interest & Profit and relief in spaces. */
data class IncomeView(
    val ordinary: Int,
    val baseIncome: Int,
    val middleClassBonus: Int,
    val interestProfit: Int,
    val reliefSpaces: Int,
) {
    companion object {
        internal fun from(
            state: GameState,
            player: PlayerState,
            board: BoardState = state.board,
        ): IncomeView? {
            if (state.turnNumber == null || player.misery == MiseryStep.CHAOS) return null
            val quote =
                PlayerEconomy.incomeQuote(
                    player,
                    state.playerIds.size,
                    board.markerCount(player.player.id),
                    state.rules,
                )
            return IncomeView(
                quote.income,
                quote.baseIncome,
                quote.middleClassBonus,
                quote.interestProfit,
                quote.newWorldRelief,
            )
        }
    }
}

/** A current payout, using coloured holdings and the first queued adjustment only. */
data class CommodityPayoutView(
    val commodity: Commodity,
    val adjustments: List<CommodityAdjustment>,
    val owners: List<CommodityOwnerView>,
) {
    companion object {
        internal fun from(state: GameState): List<CommodityPayoutView> {
            if (state.turnNumber == null) return emptyList()
            val context = state.cardContext()
            return Commodity.entries.map { commodity ->
                val payment = CardRules.commodityPayment(context, commodity)
                CommodityPayoutView(
                    commodity,
                    context.adjustments[commodity].orEmpty(),
                    context.activePlayers.mapNotNull { (id, player) ->
                        val provinces = CardRules.commodityHoldings(context, id, commodity)
                        if (provinces.isEmpty()) return@mapNotNull null
                        CommodityOwnerView(
                            id,
                            provinces,
                            provinces.size,
                            CardRules.commodityPayoutBox(context, id, commodity),
                            requireNotNull(payment.payments[id]) {
                                "Every active owner has a payout."
                            },
                            if (AdvanceId.INDUSTRY in player.advances) 1 else 0,
                        )
                    },
                )
            }
        }
    }
}

data class CommodityOwnerView(
    val player: PlayerId,
    val provinces: List<ProvinceId>,
    val marketCount: Int,
    val effectiveBox: Int,
    val payout: Int,
    val industryBonus: Int,
)

/** Signed purchase amount, its absolute cost, and the authoritative resource balances at reveal. */
data class TokenPurchaseOfferView(
    val amount: Int,
    val cost: Int,
    val tokensReceived: Int,
    val cashAfter: Int,
    val stockAfter: Int,
    val poolAfter: Int,
)

object TokenPurchaseProjection {
    /**
     * Pure quote; transport must also bind the request to the authenticated viewer and revision.
     */
    fun quote(state: GameState, viewer: PlayerId, amount: Int): TokenPurchaseOfferView {
        checkRule(
            state.stage == Stage.TOKEN_PURCHASE && state.flow.decisions.isEmpty(),
            ViolationCode.WRONG_PHASE,
        )
        val player = state.playerState(viewer)
        player.requireActive()
        checkRule(player.tokenPurchase == null, ViolationCode.WRONG_PLAYER)
        val purchase = TokenPurchase.affordable(amount, player.cash)
        val after = purchase.applyTo(player)
        return TokenPurchaseOfferView(
            amount,
            purchase.cost,
            purchase.tokensReceived(player.stockTokens),
            after.cash,
            after.stockTokens,
            after.poolTokens,
        )
    }

    /** Bounded initial suggestions; arbitrary legal signed amounts use [quote]. */
    internal fun offers(state: GameState, viewer: PlayerState): List<TokenPurchaseOfferView> {
        if (
            state.stage != Stage.TOKEN_PURCHASE ||
                state.flow.decisions.isNotEmpty() ||
                viewer.misery == MiseryStep.CHAOS ||
                viewer.tokenPurchase != null
        ) {
            return emptyList()
        }
        val limit = minOf(viewer.cash, viewer.stockTokens)
        return ((-limit..limit).toList() + listOf(-viewer.cash, viewer.cash))
            .distinct()
            .sorted()
            .map { amount -> quote(state, viewer.player.id, amount) }
    }
}

data class ShippingOfferView(
    val cost: Int,
    val ships: ShippingCapability?,
    val cashAfter: Int?,
    val blockers: List<ViolationCode>,
) {
    companion object {
        internal fun from(state: GameState, viewer: PlayerState): ShippingOfferView? {
            if (
                state.stage != Stage.PURCHASE ||
                    state.flow.purchaseStep != PurchaseStep.SHIPPING ||
                    state.flow.actor != viewer.player.id ||
                    state.flow.decisions.isNotEmpty()
            ) {
                return null
            }
            return try {
                val after = ShippingPurchases.upgrade(viewer, state.currentTurn()).player
                ShippingOfferView(
                    EconomyTables.shipUpgradeCost,
                    after.ships,
                    after.cash,
                    emptyList(),
                )
            } catch (failure: RuleViolation) {
                ShippingOfferView(
                    EconomyTables.shipUpgradeCost,
                    ShippingPurchases.nextUpgrade(viewer.ships),
                    null,
                    listOf(failure.code),
                )
            }
        }
    }
}
