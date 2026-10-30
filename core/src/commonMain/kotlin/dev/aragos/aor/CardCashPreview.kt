package dev.aragos.aor

/** Deterministic cash endpoints of the selected resolution, before deferred decisions. */
data class CashChangeView(val player: PlayerId, val before: Int, val after: Int)

internal object CardCashPreview {
    fun from(context: CardContext, resolution: CardResolution): List<CashChangeView> {
        val deltas =
            when (resolution) {
                is CardResolution.CommodityPayment -> resolution.payments
                is CardResolution.MongolArmies -> mapOf(resolution.player to CardRules.MONGOL_CASH)
                is CardResolution.AlchemistLoss -> mapOf(resolution.target to -resolution.cash)
                is CardResolution.LeaderPlay ->
                    mapOf(
                        resolution.leader.owner to
                            (resolution.rebate - resolution.leader.protectionCash)
                    )
                else -> emptyMap()
            }
        return deltas.mapNotNull { (player, delta) ->
            CashChangeView(player, context.player(player).cash, context.player(player).cash + delta)
                .takeIf { delta != 0 }
        }
    }
}
