package dev.aragos.aor

/** Last-moving active Wind/Watermill owner acts after the first player's buy-off window. */
data class MarketAdjustmentDecision(override val player: PlayerId) : TurnDecision {
    override val choice: String = "adjust-market"

    override fun choices(state: GameState): List<String> =
        listOf("pass") +
            VariantInteractions.marketCommodities.flatMap { commodity ->
                val markers = state.adjustments[commodity].orEmpty()
                if (markers.isEmpty()) {
                    CommodityAdjustment.entries.map { adjustment ->
                        CardCommand.AdjustMarket(commodity, adjustment).toText()
                    }
                } else {
                    listOf(CardCommand.AdjustMarket(commodity, null).toText())
                }
            }
}

/** Functional interaction rules shared by normal expansion and optional card decisions. */
object VariantInteractions {
    val marketCommodities: Set<Commodity> =
        setOf(Commodity.GRAIN, Commodity.CLOTH, Commodity.WINE, Commodity.METAL)

    /** Call once after the buy-off window and before the first regular draw. */
    fun openMarketAdjustment(state: GameState): GameState {
        if (
            state.rules.windWatermill != WindWatermillRule.MARKET_ADJUSTMENT ||
                state.flow.marketAdjustmentComplete
        )
            return state
        val ready = state.copy(flow = state.flow.copy(marketAdjustmentComplete = true))
        val owner =
            state.turnOrder.lastOrNull { player ->
                val profile = state.playerState(player)
                profile.misery != MiseryStep.CHAOS &&
                    profile.advances.owns(AdvanceId.WIND_WATERMILL)
            } ?: return ready
        return ready.pushDecision(MarketAdjustmentDecision(owner))
    }

    fun passDecision(state: GameState, actor: PlayerId): GameState? {
        val frame = state.flow.decisions.lastOrNull() as? MarketAdjustmentDecision ?: return null
        checkRule(frame.player == actor, ViolationCode.WRONG_PLAYER)
        return state.popDecision()
    }

    fun adjustMarket(
        state: GameState,
        actor: PlayerId,
        commodity: Commodity,
        adjustment: CommodityAdjustment?,
    ): GameState {
        val frame = state.flow.decisions.lastOrNull() as? MarketAdjustmentDecision
        checkRule(frame?.player == actor, ViolationCode.WRONG_PLAYER)
        val previous = state.adjustments[commodity].orEmpty()
        cardRule(
            commodity in marketCommodities &&
                ((previous.isEmpty() && adjustment != null) ||
                    (previous.isNotEmpty() && adjustment == null)),
            CardRuleCode.INVALID_CHOICE,
            "Wind/Watermill changes a neutral commodity to shortage or surplus, or clears an existing adjustment.",
        )
        val adjustments =
            if (adjustment == null) state.adjustments - commodity
            else state.adjustments + (commodity to listOf(adjustment))
        return state.popDecision().copy(adjustments = adjustments)
    }

    /** Written Record's exchange is voluntary, free, and unavailable against another owner. */
    fun canWrittenRecordTrade(state: GameState, winner: PlayerId, defeated: PlayerId): Boolean {
        val owner = state.playerState(winner)
        val victim = state.playerState(defeated)
        return state.rules.writtenRecord == WrittenRecordRule.TAKEOVER_TRADE &&
            owner.advances.owns(AdvanceId.WRITTEN_RECORD) &&
            !victim.advances.owns(AdvanceId.WRITTEN_RECORD) &&
            winner !in state.flow.writtenRecordUsed &&
            owner.hand.isNotEmpty() &&
            victim.hand.isNotEmpty() &&
            owner.misery != MiseryStep.CHAOS &&
            victim.misery != MiseryStep.CHAOS
    }
}
