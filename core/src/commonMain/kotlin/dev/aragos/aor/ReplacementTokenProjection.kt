package dev.aragos.aor

/**
 * Public context for the top event-token replacement decision. Its province is already empty.
 * Spending [poolCost] places one coloured token; declining keeps it empty. Neither choice changes
 * misery. [misery] is the recipient's current printed box, including any already-applied Civil War
 * gain. [poolAfter] is null when the pool cannot pay, which can occur between queued replacements.
 */
data class ReplacementTokenView(
    val player: PlayerId,
    val province: ProvinceId,
    val event: EventId,
    val poolTokens: Int,
    val poolCost: Int,
    val poolAfter: Int?,
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
                decision.province,
                decision.event,
                recipient.poolTokens,
                cost,
                (recipient.poolTokens - cost).takeIf { recipient.poolTokens >= cost },
                recipient.stockTokens,
                recipient.misery,
                state.flow.decisions.any { pending ->
                    pending is CardDecision.CivilWarLoss && pending.player == decision.player
                },
            )
        }
    }
}
