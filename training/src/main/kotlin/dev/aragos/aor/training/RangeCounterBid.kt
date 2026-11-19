package dev.aragos.aor.training

import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.Command
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.LocalTokenNeighborPolicy

/** Held-out rival that targets a predicted range instead of repeating a fixed offset. */
internal object RangeCounterBid {
    fun choose(decision: SimulationDecision, target: PlayerId, ordinary: String): String? {
        val view = decision.view
        val actor = PlayerId(decision.observation.playerId)
        val earlierOnTie = view.capitalOrder.indexOf(actor) > view.capitalOrder.indexOf(target)
        val recentBids = view.pastTokenPurchases.takeLast(4).mapNotNull { it[target] }
        if (recentBids.size < 3) return null
        val original =
            view.tokenPurchaseOffers.single {
                it.amount == LocalTokenNeighborPolicy.amount(ordinary)
            }
        if (original.tokensReceived == 0) return null
        val commodityPayout =
            view.commodityPayouts.associate { payout ->
                payout.commodity to (payout.owners.firstOrNull { it.player == actor }?.payout ?: 0)
            }
        val heldPayout =
            view.hand.maxOfOrNull { card ->
                val effect = CardEffectData.forCard(card.definition.id)
                if (effect is CardEffectSpec.CommodityChoices) {
                    effect.choices.maxOf { commodityPayout[it] ?: 0 }
                } else 0
            } ?: 0
        val early =
            heldPayout >= 15 || (view.hand.size <= 1 && view.deckRemaining > view.players.size)
        val priorityValue = if (early) 2.0 + heldPayout.coerceAtMost(30) / 15.0 else 2.75
        fun expectedPriority(amount: Int): Double =
            recentBids
                .mapIndexed { index, bid ->
                    val earlier = amount < bid || (amount == bid && earlierOnTie)
                    if (earlier == early) index + 1 else 0
                }
                .sum()
                .toDouble() / (recentBids.size * (recentBids.size + 1) / 2)
        val basePriority = expectedPriority(original.amount)
        val candidates = decision.observation.candidates.mapTo(hashSetOf()) { it.command }
        return view.tokenPurchaseOffers
            .asSequence()
            .filter { quote ->
                quote.amount > 0 &&
                    quote.amount in (original.amount - 8)..(original.amount + 8) &&
                    original.tokensReceived - quote.tokensReceived <= 5
            }
            .map { quote ->
                val proposed = Command.BuyTokens(quote.amount).toText()
                val value =
                    priorityValue * (expectedPriority(quote.amount) - basePriority) -
                        (original.tokensReceived - quote.tokensReceived).coerceAtLeast(0) * .20 -
                        (quote.cost - original.cost).coerceAtLeast(0) * .22
                proposed to value
            }
            .filter { (command, value) -> value > .10 && command in candidates }
            .maxByOrNull { it.second }
            ?.first
    }
}
