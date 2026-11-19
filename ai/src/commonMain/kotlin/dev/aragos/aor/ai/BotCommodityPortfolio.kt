package dev.aragos.aor.ai

import dev.aragos.aor.CardRules
import dev.aragos.aor.Commodity

/** Dollar opportunity changes for one joint set of additional markets, not promised receipts. */
internal data class BotCommodityGains(val held: Double, val public: Double)

/**
 * Shared current-payout valuation for expansion interactions and token-budget forecasts. Every own
 * physical shared-choice card contributes its best commodity once; outside-card exposure retains
 * the public per-commodity proxy. Current first adjustments are held fixed, never consumed or
 * predicted. Additional markets are counts, not legal offers or invented routes.
 */
internal class BotCommodityPortfolio(
    private val industry: Boolean,
    private val commodities: List<BotPlanningCommodity>,
    private val heldCards: List<Set<Commodity>>,
) {
    private val forecasts by lazy { commodities.associateBy(BotPlanningCommodity::commodity) }
    private val basePayouts = commodities.associate { it.commodity to payout(it, it.markets) }
    private val baselineHeld = heldValue(basePayouts)

    fun gains(markets: List<Set<Commodity>>): BotCommodityGains =
        gains(markets.flatten().groupingBy { it }.eachCount())

    fun gains(additions: Map<Commodity, Int>): BotCommodityGains {
        val payouts = commodities.associate { forecast ->
            forecast.commodity to
                payout(forecast, forecast.markets + (additions[forecast.commodity] ?: 0))
        }
        return BotCommodityGains(
            held = heldValue(payouts) - baselineHeld,
            public =
                commodities.sumOf { forecast ->
                    ((payouts[forecast.commodity] ?: 0.0) -
                        (basePayouts[forecast.commodity] ?: 0.0)) * forecast.outsideCards
                },
        )
    }

    /** Current first adjustment, with [marketChange] applied to the visible market count. */
    fun projectedPayout(commodity: Commodity, marketChange: Int = 0): Double {
        val base =
            checkNotNull(forecasts[commodity]) {
                "A projected payout requires the commodity's public forecast."
            }
        return payout(base, (base.markets + marketChange).coerceAtLeast(0))
    }

    private fun heldValue(payouts: Map<Commodity, Double>): Double = heldCards.sumOf { choices ->
        choices.maxOf { payouts[it] ?: 0.0 }
    }

    private fun payout(forecast: BotPlanningCommodity, markets: Int): Double {
        val box = CardRules.commodityPayoutBox(markets, industry, forecast.adjustmentOffset)
        return forecast.baseValue.toDouble() * box * box
    }
}
