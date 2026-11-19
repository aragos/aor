package dev.aragos.aor

import dev.aragos.aor.ai.BotObservation
import kotlin.math.abs
import kotlin.random.Random

/** Adjust a qualified bid using public bid history and the actor's own legal offers. */
internal object RepeatedUndercutBid {
    private enum class Intent(val direction: Int) {
        PAY_CARD(-1),
        BUY_CARD(-1),
        SECURE_MARKETS(1),
    }

    fun choose(
        view: GameView,
        observation: BotObservation,
        seed: Int,
        ordinary: String,
        detectAlternation: Boolean = false,
    ): String {
        val turn = view.turnNumber ?: return ordinary
        if (turn !in 3..10 || view.stage != Stage.TOKEN_PURCHASE) return ordinary
        val paid = TextCodec.parseCommand(ordinary) as? Command.BuyTokens ?: return ordinary
        if (paid.amount !in 14..24) return ordinary
        val history = view.pastTokenPurchases.takeLast(4)
        if (history.size < 2) return ordinary
        val actor = PlayerId(observation.playerId)
        if (
            !repeatedUndercut(history, actor) &&
                !(detectAlternation && alternatingUndercut(history, actor))
        ) {
            return ordinary
        }
        val forecast = view.expansionForecast ?: return ordinary
        val pool =
            view.tokenPurchaseOffers.firstOrNull { it.amount == paid.amount } ?: return ordinary
        val intent = intent(view, actor, pool) ?: return ordinary
        val self = view.players.first { it.id == actor }
        val referenceRank = rank(history, actor, paid.amount)
        // Treat currently contested markets as more expensive for this budget estimate.
        val costs =
            (forecast.peacefulMarkets.filterNot { it.usesOverseasSlot }.map { it.requiredTokens } +
                    forecast.contestedMarkets
                        .filterNot { it.usesOverseasSlot }
                        .map { it.requiredTokens + 1 })
                .sorted()
                .take(self.stockMarkers.coerceAtLeast(0))
        val cardReserve = if (intent == Intent.BUY_CARD) EconomyTables.expansionCardCost(0) else 0
        fun affordableMarkets(tokens: Int): Int {
            var remaining = tokens - cardReserve
            return costs
                .takeWhile { cost ->
                    remaining -= cost
                    remaining >= 0
                }
                .size
        }
        val referenceMarkets = affordableMarkets(pool.poolAfter)
        val offered = observation.candidates.map { it.command }.toSet()
        val scores =
            view.tokenPurchaseOffers.mapNotNull { quote ->
                if (quote.amount !in 12..26 || abs(quote.amount - paid.amount) > 5) {
                    return@mapNotNull null
                }
                val command = Command.BuyTokens(quote.amount).toText()
                if (command !in offered) return@mapNotNull null
                val boughtExtra = quote.cost - pool.cost
                if (boughtExtra > 0 && quote.tokensReceived - pool.tokensReceived != boughtExtra) {
                    return@mapNotNull null
                }
                if (intent == Intent.BUY_CARD && quote.poolAfter < cardReserve)
                    return@mapNotNull null
                val rankGain =
                    intent.direction * (rank(history, actor, quote.amount) - referenceRank)
                val marketGain = affordableMarkets(quote.poolAfter) - referenceMarkets
                if (quote.amount != paid.amount && rankGain <= 0.0 && marketGain <= 0) {
                    return@mapNotNull null
                }
                val merit = rankGain * 1.4 + .85 * marketGain - boughtExtra * .12
                quote.amount to merit
            }
        val best = scores.maxOfOrNull { it.second } ?: return ordinary
        if (best < .20) return ordinary
        val nearBest = scores.filter { (_, merit) -> merit >= maxOf(.0, best - .40) }
        val picked = nearBest[Random(seed xor 0x7A52CB11).nextInt(nearBest.size)].first
        return Command.BuyTokens(picked).toText()
    }

    private fun repeatedUndercut(history: List<Map<PlayerId, Int>>, actor: PlayerId): Boolean {
        if (history.size != 4) return false
        return history.first().keys.any { rival ->
            rival != actor && history.count { row -> (row[rival] ?: Int.MIN_VALUE) in 15..17 } >= 3
        }
    }

    private fun alternatingUndercut(history: List<Map<PlayerId, Int>>, actor: PlayerId): Boolean {
        if (history.size != 4) return false
        return history.first().keys.any { rival ->
            if (rival == actor) return@any false
            val bids = history.mapNotNull { it[rival] }
            bids.size == 4 &&
                bids.all { it in 15..20 } &&
                bids.zipWithNext().all { (left, right) -> (left in 15..17) != (right in 15..17) }
        }
    }

    private fun rank(history: List<Map<PlayerId, Int>>, actor: PlayerId, amount: Int): Double =
        history.sumOf { row ->
            row.entries.sumOf { (rival, bid) ->
                when {
                    rival == actor -> 0.0
                    bid < amount -> 1.0
                    bid == amount -> .5
                    else -> 0.0
                }
            }
        } / history.size

    private fun intent(view: GameView, actor: PlayerId, pool: TokenPurchaseOfferView): Intent? {
        val advantages =
            view.commodityPayouts.associate { payout ->
                val own = payout.owners.firstOrNull { it.player == actor }?.payout ?: 0
                val rival =
                    payout.owners.filter { it.player != actor }.maxOfOrNull { it.payout } ?: 0
                payout.commodity to (own - rival)
            }
        val ready =
            view.hand.any { card ->
                val effect = CardEffectData.forCard(card.definition.id)
                effect is CardEffectSpec.CommodityChoices &&
                    effect.choices.any { (advantages[it] ?: 0) >= 15 }
            }
        if (ready) return Intent.PAY_CARD
        if (
            view.hand.size <= 1 &&
                view.deckRemaining > view.players.size &&
                pool.poolAfter >= EconomyTables.expansionCardCost(0)
        ) {
            return Intent.BUY_CARD
        }
        val marketLead =
            view.commodityPayouts.any { payoff ->
                payoff.owners.any { it.player == actor && it.marketCount >= 2 && it.payout >= 15 }
            }
        return Intent.SECURE_MARKETS.takeIf { marketLead }
    }
}
