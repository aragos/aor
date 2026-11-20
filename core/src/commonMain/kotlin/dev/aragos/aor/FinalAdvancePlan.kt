package dev.aragos.aor

/** Public score thresholds for replacing a pass with a legal final-advance purchase. */
data class FinalAdvanceGuard(val currentLead: Int, val projectedLead: Int) {
    init {
        require(currentLead in 1..1000 && projectedLead in 1..1000) {
            "Finish thresholds must both be positive and at most 1,000 score points."
        }
    }

    /** Evaluate a proposed pass against the same authoritative purchase state and actor view. */
    fun choose(
        state: GameState,
        view: GameView,
        actor: PlayerId,
        ordinary: String,
    ): String? {
        if (ordinary != "pass") return null
        val own = view.scores.firstOrNull { it.player == actor }?.total ?: return null
        val rival =
            view.scores.filter { it.player != actor }.mapNotNull { it.total }.maxOrNull()
                ?: return null
        if (own.toLong() - rival < currentLead) return null
        return FinalAdvancePlan.find(state, actor, ordinary)
            ?.takeIf { it.projectedMargin >= projectedLead }
            ?.firstCommand
    }
}

/** An exact same-phase purchase order and its score margin at the current board and income. */
class FinalAdvancePlan
private constructor(
    val advances: List<AdvanceId>,
    val projectedMargin: Long,
) {
    val firstCommand: String
        get() = TurnCommand.BuyAdvance(advances.first().letter).toText()

    companion object {
        const val VERSION = "final-advance-plan-1"
        const val MAXIMUM_REMAINING = 4

        internal fun find(state: GameState, actor: PlayerId, ordinary: String): FinalAdvancePlan? {
            val remaining = AdvanceId.entries.size - state.playerState(actor).advances.owned.size
            if (state.endingTriggered || remaining !in 1..MAXIMUM_REMAINING) return null

            fun visit(origin: GameState, path: List<AdvanceId>): Sequence<FinalAdvancePlan> {
                val view = origin.viewForDecision(actor)
                if (origin.endingTriggered) {
                    return marginAfterStabilization(view, actor)?.let { margin ->
                        sequenceOf(FinalAdvancePlan(path, margin))
                    } ?: emptySequence()
                }
                return view.advanceOffers
                    .asSequence()
                    .filter { offer ->
                        offer.unavailableReason == null &&
                            offer.miseryChanges.none { it.player == actor && it.toValue == null }
                    }
                    .flatMap { offer ->
                        val command = TurnCommand.BuyAdvance(offer.advance.letter).toText()
                        val bought = origin.step(TextCodec.parseAction(command, origin, actor))
                        val after =
                            if (
                                offer.advance == AdvanceId.MASTER_ART &&
                                    bought.flow.decisions.isNotEmpty()
                            ) {
                                bought.step(
                                    TextCodec.parseAction(TurnCommand.Pass.toText(), bought, actor)
                                )
                            } else bought
                        visit(after, path + offer.advance)
                    }
            }

            return visit(state, emptyList())
                .maxWithOrNull(
                    compareBy<FinalAdvancePlan> { it.projectedMargin }
                        .thenBy { it.firstCommand == ordinary }
                        .thenByDescending { it.firstCommand }
                )
        }

        /**
         * Keep the current market distribution fixed and reserve the better of the two legal
         * stabilization payments. Rivals retain their current score and projected receipts.
         */
        private fun marginAfterStabilization(view: GameView, actor: PlayerId): Long? {
            val self = view.players.first { it.id == actor }
            val score = view.scores.first { it.player == actor }
            val total = score.total?.toLong() ?: return null
            val misery = score.misery ?: return null
            val price = view.stabilizationCost ?: return null
            val income = self.income
            val ordinary = income?.ordinary?.toLong() ?: 0L
            val interest = income?.interestProfit?.toLong() ?: 0L
            val cashPayment =
                if (price <= self.cash.toLong()) {
                    total - price + ordinary + minOf(interest, self.cash - price)
                } else null
            val miseryPayment =
                view.stabilizationMisery?.let { after ->
                    total - (after - misery) + ordinary + interest
                }
            val projected = listOfNotNull(cashPayment, miseryPayment).maxOrNull() ?: return null
            val rival =
                view.scores
                    .asSequence()
                    .filter { it.player != actor }
                    .mapNotNull { other ->
                        val current = other.total?.toLong() ?: return@mapNotNull null
                        val receipts = view.players.first { it.id == other.player }.income
                        current + (receipts?.ordinary ?: 0) + (receipts?.interestProfit ?: 0)
                    }
                    .maxOrNull() ?: return null
            return projected - rival
        }
    }
}
