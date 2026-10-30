package dev.aragos.aor

/** One canonical Gold/Ivory command removes both matching markers for one payment. */
data class AdjustmentCancellationOffer(
    val command: String,
    val commodities: Set<Commodity>,
    val cost: Int,
    val cashAfter: Int,
)

internal object AdjustmentCancellation {
    fun quote(
        state: GameState,
        actor: PlayerId,
        commodity: Commodity,
        adjustment: CommodityAdjustment,
    ): AdjustmentCancellationOffer {
        checkRule(
            !state.flow.cancelComplete && actor == state.turnOrder.first(),
            ViolationCode.WRONG_PLAYER,
        )
        checkRule(
            adjustment in state.adjustments[commodity].orEmpty(),
            ViolationCode.INVALID_ADJUSTMENT,
        )
        val cost = CommodityData.definitions.single { it.id == commodity }.baseValue
        val player = state.playerState(actor)
        checkRule(
            player.cash >= cost,
            ViolationCode.INSUFFICIENT_CASH,
            parameters = mapOf("required" to cost, "available" to player.cash),
        )
        val affected =
            if (commodity in setOf(Commodity.GOLD, Commodity.IVORY))
                setOf(Commodity.GOLD, Commodity.IVORY).filterTo(linkedSetOf()) {
                    adjustment in state.adjustments[it].orEmpty()
                }
            else setOf(commodity)
        return AdjustmentCancellationOffer(
            TurnCommand.CancelAdjustment(commodity, adjustment).toText(),
            affected,
            cost,
            player.cash - cost,
        )
    }

    fun offers(state: GameState, actor: PlayerId): List<AdjustmentCancellationOffer> {
        if (
            state.stage != Stage.DRAW_CARD ||
                state.flow.actor != actor ||
                state.flow.decisions.isNotEmpty() ||
                state.flow.cancelComplete ||
                actor != state.turnOrder.first()
        )
            return emptyList()
        return state.adjustments.flatMap { (commodity, markers) ->
            markers
                .distinct()
                .filterNot { adjustment ->
                    commodity == Commodity.IVORY &&
                        adjustment in state.adjustments[Commodity.GOLD].orEmpty()
                }
                .mapNotNull { adjustment ->
                    try {
                        quote(state, actor, commodity, adjustment)
                    } catch (_: RuleViolation) {
                        null
                    }
                }
        }
    }
}
