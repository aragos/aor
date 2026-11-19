package dev.aragos.aor.ai

import dev.aragos.aor.CardKind
import dev.aragos.aor.EventId
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage

/**
 * Candidate signals available before the first sealed purchase. It records aggregate facts from the
 * acting player's hand and current public routes. Forecast differences are in the same opportunity
 * units and cash dollars as the token-budget plan.
 */
data class OpeningBidContext(
    val commodityCards: Int,
    val leaderCards: Int,
    val militaryCards: Int,
    val reachableMarkets: Int,
    val contestedMarkets: Int,
    val smallerPoolHeldGain: Double,
    val extraPoolHeldGain: Double,
    val extraPoolOpportunity: Double,
) {
    companion object {
        /** The 17- and 21-token budgets are reference points, not proposed commands. */
        fun from(view: GameView, player: PlayerId): OpeningBidContext {
            require(view.stage == Stage.TOKEN_PURCHASE && view.turnNumber == 1) {
                "Opening bid features require the first sealed token-purchase window."
            }
            val forecast = view.expansionForecast
            val smaller = view.tokenPurchaseOffers.lastOrNull { it.amount in 0..17 }
            val larger = view.tokenPurchaseOffers.lastOrNull { it.amount in 0..21 }
            val plans =
                if (forecast != null && smaller != null && larger != null) {
                    BotTokenPlanProjection(view, player)
                } else null
            val smallerPlan = smaller?.let { plans?.plan(it.poolAfter) }
            val largerPlan = larger?.let { plans?.plan(it.poolAfter) }
            val held = smallerPlan?.heldGain ?: 0.0
            return OpeningBidContext(
                commodityCards = view.hand.count { it.definition.kind == CardKind.COMMODITY },
                leaderCards = view.hand.count { it.definition.kind == CardKind.LEADER },
                militaryCards = view.hand.count { it.definition.id in militaryIds },
                reachableMarkets = forecast?.peacefulMarkets.orEmpty().size,
                contestedMarkets = forecast?.contestedMarkets.orEmpty().size,
                smallerPoolHeldGain = held,
                extraPoolHeldGain = (largerPlan?.heldGain ?: 0.0) - held,
                extraPoolOpportunity =
                    (largerPlan?.opportunity ?: 0.0) - (smallerPlan?.opportunity ?: 0.0),
            )
        }

        private val militaryIds =
            setOf(EventId.ARMOR, EventId.STIRRUPS, EventId.LONG_BOW, EventId.GUNPOWDER)
                .map(EventId::cardId)
                .toSet()
    }
}
