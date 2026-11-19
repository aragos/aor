package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.Commodity
import dev.aragos.aor.EconomyTables
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.TokenPurchaseOfferView

/** Ordered v2 additions; the original 32 token-value columns retain their names and positions. */
internal enum class BotTokenPlanFeature(val key: String) {
    HELD_COMMODITY("planning.tokenValue.v2.plan.heldCommodity"),
    PUBLIC_COMMODITY("planning.tokenValue.v2.plan.publicCommodity"),
    INCOME("planning.tokenValue.v2.plan.income"),
    ENDGAME_INCOME("planning.tokenValue.v2.plan.endgameIncome"),
    INTEREST("planning.tokenValue.v2.plan.interest"),
    STABILIZATION_SHORTFALL("planning.tokenValue.v2.plan.stabilizationShortfall"),
    UNCOMMITTED_TOKENS("planning.tokenValue.v2.plan.uncommittedTokens"),
}

/** One jointly feasible current-route forecast; destinations never authorize future commands. */
internal data class BotTokenPlan(
    val destinations: List<ProvinceId> = emptyList(),
    val commitment: Int = 0,
    val overseasSlots: Int = 0,
    val heldGain: Double = 0.0,
    val publicGain: Double = 0.0,
    val incomeGain: Int = 0,
) {
    /** Fixed ordering heuristic, separate from the offline-fitted value of each feature. */
    val opportunity: Double
        get() = heldGain + 0.15 * publicGain + 3.2 * incomeGain
}

/**
 * A bounded token-budget curve from public current-route prospects and the acting seat's hand.
 * Three fixed greedy orders retain every feasible prefix, the empty plan, and the best plan from
 * each smaller quoted pool. The chosen plan therefore never loses opportunity as its pool grows.
 * Every reported economic gain belongs to this same portfolio, with one marker per destination.
 *
 * Only peaceful prospects enter plans. Their quoted individual shipping capacities are already
 * authoritative; shared overseas slots and marker/token budgets are reserved here. Current routes
 * never grow from hypothetical new white pieces. Existing v1 columns retain contested prospects. No
 * sealed opponent amounts, future cards, purchases, contests or opponent movements are assumed.
 */
internal class BotTokenPlanProjection(
    view: GameView,
    seat: PlayerId,
    private val endgame: Double = 0.0,
) {
    private data class Market(
        val destination: ProvinceId,
        val tokens: Int,
        val overseas: Boolean,
        val commodities: Set<Commodity>,
        val held: Double,
        val opportunity: Double,
    )

    private val self = view.players.single { it.id == seat }
    private val portfolio = BotEconomicProjection(view, seat).portfolio()
    private val incomes = EconomyTables.incomeFor(view.players.size)
    private val initialIncome = incomes[self.boardMarkers]
    private val stabilization = view.stabilizationCost ?: 0L
    private val overseasSlots = view.expansionForecast?.overseasSlots ?: 0
    private val markets = run {
        val provinces =
            checkNotNull(view.catalog) { "Token plans require the public province catalog." }
                .provinces
                .associateBy { it.id }
        val income =
            if (self.stockMarkers > 0) incomes[self.boardMarkers + 1] - initialIncome else 0
        view.expansionForecast
            ?.peacefulMarkets
            .orEmpty()
            .distinctBy { it.destination }
            .map { prospect ->
                val definition =
                    checkNotNull(provinces[prospect.destination]) {
                        "A token-plan prospect requires its public province definition."
                    }
                val gain = portfolio.gains(listOf(definition.commodities))
                Market(
                    prospect.destination,
                    prospect.requiredTokens,
                    prospect.usesOverseasSlot,
                    definition.commodities,
                    gain.held,
                    gain.held + 0.15 * gain.public + 3.2 * income,
                )
            }
    }
    private val orders =
        listOf(
                markets.sortedWith(compareBy<Market> { it.tokens }.thenBy { it.destination.value }),
                markets.sortedWith(
                    compareByDescending<Market> { it.held / it.tokens }
                        .thenBy { it.tokens }
                        .thenBy { it.destination.value }
                ),
                markets.sortedWith(
                    compareByDescending<Market> { it.opportunity / it.tokens }
                        .thenBy { it.tokens }
                        .thenBy { it.destination.value }
                ),
            )
            .distinct()
    private val plans = buildMap {
        var previous = BotTokenPlan()
        for (pool in view.tokenPurchaseOffers.map { it.poolAfter }.distinct().sorted()) {
            var best = previous
            for (order in orders) {
                best = pack(pool, order, best)
            }
            put(pool, best)
            previous = best
        }
    }

    /** Reuses the one cache built in increasing pool order for all legal signed purchase quotes. */
    fun plan(pool: Int): BotTokenPlan =
        requireNotNull(plans[pool]) { "A token plan requires an offered expansion-pool budget." }

    /**
     * Seven normalized values: held dollars/100, outside-card exposure dollars/200, ordinary income
     * dollars/100, that income times endgame/100, conditional interest dollars/100, stabilization
     * deficit dollars/100, and uncommitted tokens/32. The caller clips every final value to the
     * shared feature limit. Interest uses cash after this token quote and the plan's ordinary
     * income; stabilization uses the current hand only. Neither predicts subsequent spending,
     * draws, card plays or advance purchases, and neither changes the actual game.
     */
    fun features(quote: TokenPurchaseOfferView): List<Double> {
        val plan = plan(quote.poolAfter)
        val income = self.income
        val interest =
            if (AdvanceId.INTEREST_PROFIT in self.advances && income != null) {
                minOf(quote.cashAfter, income.ordinary + plan.incomeGain) - income.interestProfit
            } else 0
        val deficit = (stabilization - quote.cashAfter.toLong()).coerceAtLeast(0).toDouble()
        return listOf(
            plan.heldGain / 100.0,
            plan.publicGain / 200.0,
            plan.incomeGain / 100.0,
            plan.incomeGain * endgame / 100.0,
            interest / 100.0,
            deficit / 100.0,
            (quote.poolAfter - plan.commitment) / 32.0,
        )
    }

    private fun pack(pool: Int, order: List<Market>, initial: BotTokenPlan): BotTokenPlan {
        val destinations = mutableListOf<ProvinceId>()
        val additions = mutableMapOf<Commodity, Int>()
        var commitment = 0
        var overseas = 0
        var best = initial
        for (market in order) {
            if (destinations.size == self.stockMarkers) break
            if (market.tokens > pool - commitment || market.overseas && overseas == overseasSlots) {
                continue
            }
            destinations += market.destination
            commitment += market.tokens
            if (market.overseas) overseas++
            for (commodity in market.commodities) {
                additions[commodity] = (additions[commodity] ?: 0) + 1
            }
            val gains = portfolio.gains(additions)
            val candidate =
                BotTokenPlan(
                    destinations = destinations.sortedBy { it.value },
                    commitment = commitment,
                    overseasSlots = overseas,
                    heldGain = gains.held,
                    publicGain = gains.public,
                    incomeGain = incomes[self.boardMarkers + destinations.size] - initialIncome,
                )
            if (better(candidate, best)) best = candidate
        }
        return best
    }

    private fun better(candidate: BotTokenPlan, incumbent: BotTokenPlan): Boolean =
        when {
            candidate.opportunity != incumbent.opportunity ->
                candidate.opportunity > incumbent.opportunity
            candidate.commitment != incumbent.commitment ->
                candidate.commitment < incumbent.commitment
            else ->
                candidate.destinations.joinToString(",") { it.value } <
                    incumbent.destinations.joinToString(",") { it.value }
        }
}
