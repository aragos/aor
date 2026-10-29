package dev.aragos.aor

/**
 * Public context for all provinces in the top event-token replacement decision; all are empty.
 * Spending [poolCost] places one coloured token; declining keeps it empty. Neither choice changes
 * misery. [misery] is the recipient's current printed box, including any already-applied Civil War
 * gain. [maxReplacements] bounds the selected subset by both pending provinces and available pool.
 */
data class ReplacementTokenView(
    val player: PlayerId,
    val provinces: List<ProvinceId>,
    val event: EventId,
    val poolTokens: Int,
    val poolCost: Int,
    val maxReplacements: Int,
    val stockTokens: Int,
    val misery: MiseryStep,
    val civilWarLossPending: Boolean,
) {
    companion object {
        internal fun from(state: GameState): ReplacementTokenView? {
            val decision =
                state.flow.decisions.lastOrNull() as? CardDecision.ReplacementToken ?: return null
            val recipient = state.playerState(decision.player)
            val cost = CardGame.REPLACEMENT_TOKEN_COST
            return ReplacementTokenView(
                decision.player,
                decision.provinces,
                decision.event,
                recipient.poolTokens,
                cost,
                minOf(decision.provinces.size, recipient.poolTokens / cost),
                recipient.stockTokens,
                recipient.misery,
                state.flow.decisions.any { pending ->
                    pending is CardDecision.CivilWarLoss && pending.player == decision.player
                },
            )
        }
    }
}
