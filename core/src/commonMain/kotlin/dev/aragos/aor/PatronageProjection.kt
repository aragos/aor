package dev.aragos.aor

/** Actual protection payment endpoints, using the same claim evaluation as execution. */
data class PatronageOfferView(
    val command: String,
    val payer: PlayerId,
    val recipient: PlayerId,
    val cost: Int,
    val payerCashAfter: Int,
    val recipientCashAfter: Int,
) {
    companion object {
        internal fun from(state: GameState, actor: PlayerId): List<PatronageOfferView> {
            if (
                state.stage != Stage.PURCHASE ||
                    state.flow.actor != actor ||
                    state.flow.purchaseStep != PurchaseStep.ADVANCES ||
                    state.flow.decisions.isNotEmpty()
            )
                return emptyList()
            val context = state.cardContext()
            return state.effects.leaders.mapNotNull { played ->
                try {
                    val claim = LeaderRules.claim(context, actor, played.leader)
                    PatronageOfferView(
                        "patronize ${played.leader.cardId}",
                        actor,
                        claim.recipient,
                        claim.cash,
                        state.playerState(actor).cash - claim.cash,
                        state.playerState(claim.recipient).cash + claim.cash,
                    )
                } catch (_: CardRuleViolation) {
                    null
                }
            }
        }
    }
}
