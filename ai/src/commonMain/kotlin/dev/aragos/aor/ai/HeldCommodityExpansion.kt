package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.Commodity
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId

/** Maximum E2 score deficit admitted when prioritizing a held card's peaceful markets. */
data class HeldCommodityExpansionConfig(val maximumScoreGap: Double) {
    init {
        require(maximumScoreGap.isFinite() && maximumScoreGap in 0.0..32.0) {
            "A held-commodity expansion score gap must be finite and between 0 and 32."
        }
    }

    companion object {
        const val VERSION = "held-commodity-expansion-1"
    }
}

/**
 * Prioritize an offered market for one cheap held card that can pay more after expansion. White
 * markets bound the intervention using ordinary visible state, so a restored game can make the same
 * choice without remembering earlier policy calls.
 */
internal object HeldCommodityExpansion {
    fun choose(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
        encoded: TerritoryObservation,
        ordinary: IncumbentResidualChoice,
        config: HeldCommodityExpansionConfig,
    ): String {
        val planning = observation.expansionPlanning ?: return ordinary.command
        if (view.endingTriggered || planning.heldCards.isEmpty() || planning.markerStock == 0) {
            return ordinary.command
        }
        val catalog = view.catalog ?: return ordinary.command
        val wanted = planning.heldCards.flatten().toSet()
        val destinations = catalog.provinces.associate { it.id to it.commodities.intersect(wanted) }
        val whiteMarkets =
            view.board.mapNotNull { province ->
                val marker = province.marker
                if (marker?.owner == seat && marker.side == PieceSide.WHITE) {
                    destinations[province.id]
                } else null
            }
        val referenceScore = ordinary.scores[ordinary.index]
        val costs = observation.candidates.associate { it.command to it.tokenCost }
        val offers =
            view.expansionOffers.mapNotNull { offer ->
                val commodities = destinations[offer.destination].orEmpty()
                if (
                    commodities.isEmpty() ||
                        offer.primaryAction != ExpansionAction.PLACE ||
                        !offer.establishesMarker ||
                        offer.requiredTokens <= 0 ||
                        offer.uncertain ||
                        offer.blockers.isNotEmpty()
                ) {
                    return@mapNotNull null
                }
                val command = BoardCommand.Place(offer.requiredTokens, offer.destination).toText()
                val cost = costs[command]?.takeIf { it > 0 } ?: return@mapNotNull null
                Market(command, cost, offer.availablePool, commodities)
            }
        if (offers.isEmpty()) return ordinary.command

        val portfolio =
            BotCommodityPortfolio(planning.industry, planning.commodities, planning.heldCards)
        val opportunity = buildMap {
            for (card in planning.heldCards.distinct()) {
                val recent = whiteMarkets.count { market -> market.any { it in card } }
                if (recent >= 2) continue
                val initial = card.maxOf { commodity ->
                    portfolio.projectedPayout(
                        commodity,
                        -whiteMarkets.count { commodity in it },
                    )
                }
                if (initial > 10.0) continue
                val current = card.maxOf { portfolio.projectedPayout(it) }
                val related = offers.filter { market -> market.commodities.any { it in card } }
                for (market in related) {
                    val direct = payoutAfter(portfolio, card, market)
                    val next =
                        if (recent == 0 && planning.markerStock >= 2) {
                            related
                                .asSequence()
                                .filter { it != market && market.cost + it.cost <= market.pool }
                                .map { payoutAfter(portfolio, card, market, it) }
                                .maxOrNull() ?: direct
                        } else direct
                    val best = maxOf(direct, next)
                    if (best - initial >= 8.0 && best > current) {
                        val value = (direct - current + 0.5 * (best - direct)) / market.cost
                        put(market.command, maxOf(this[market.command] ?: 0.0, value))
                    }
                }
            }
        }
        return encoded.actions.indices
            .asSequence()
            .filter { index ->
                referenceScore - ordinary.scores[index] <= config.maximumScoreGap &&
                    (opportunity[encoded.actions[index].command] ?: 0.0) > 0.0
            }
            .maxWithOrNull(
                compareBy<Int> { opportunity[encoded.actions[it].command] }
                    .thenBy { ordinary.scores[it] }
                    .thenByDescending { encoded.actions[it].command }
            )
            ?.let { encoded.actions[it].command } ?: ordinary.command
    }

    private fun payoutAfter(
        portfolio: BotCommodityPortfolio,
        commodities: Set<Commodity>,
        first: Market,
        second: Market? = null,
    ): Double = commodities.maxOf { commodity ->
        val firstMarket = if (commodity in first.commodities) 1 else 0
        val secondMarket = if (second != null && commodity in second.commodities) 1 else 0
        portfolio.projectedPayout(commodity, firstMarket + secondMarket)
    }

    private data class Market(
        val command: String,
        val cost: Int,
        val pool: Int,
        val commodities: Set<Commodity>,
    )
}
