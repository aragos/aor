package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.GameView
import dev.aragos.aor.HolyIndulgence
import dev.aragos.aor.MiseryChangeView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.ShippingFleet
import dev.aragos.aor.ShippingOfferView
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.math.abs

/** Additive purchase signals; an absent or zero model weight retains the existing evaluation. */
enum class BotPurchaseFeature(val key: String) {
    NAVIGATION_REPLACEMENT("planning.navigationReplacement"),
    INDULGENCE_SWING("planning.indulgenceSwing"),
    INDULGENCE_COUNTER("planning.indulgenceCounter"),
    OVERSEAS_ACCESS("planning.overseasAccess"),
    OVERSEAS_CONTEST("planning.overseasContest"),
    PATRONAGE_ACCESS("planning.patronageAccess"),
    RELIEF_TIER("planning.reliefTier"),
}

/**
 * Purchase estimates from the deciding seat's ordinary projection. Legacy features keep their
 * original values; additive corrections have no effect on models that do not weight them.
 */
internal class BotPurchaseProjection(private val view: GameView, seat: PlayerId) {
    private val self = view.players.single { it.id == seat }
    private val advanceOffers = view.advanceOffers
    private val incomePending = view.stage < Stage.INCOME
    private val endingTriggered = view.endingTriggered

    private val categoryCounts by
        lazy(LazyThreadSafetyMode.NONE) {
            AdvanceCategory.entries.associateWith { category ->
                self.advances.count { AdvanceData.definition(it).category == category }
            }
        }

    /** Value access to exposed leaders and progress toward another cash-relief slot. */
    fun advanceAbilities(advance: AdvanceId): Map<String, Double> = buildMap {
        if (advance == AdvanceId.PATRONAGE && !endingTriggered) {
            put(BotPurchaseFeature.PATRONAGE_ACCESS.key, 1.0)
        }
        val category = AdvanceData.definition(advance).category
        val minimum = categoryCounts.values.min()
        if (categoryCounts[category] == minimum) {
            val remaining = categoryCounts.values.count { it == minimum }
            if (!endingTriggered || remaining == 1) {
                val urgency = 1.0 + ((self.misery.value ?: 0) / 100.0).coerceIn(0.0, 1.0)
                put(BotPurchaseFeature.RELIEF_TIER.key, urgency / remaining)
            }
        }
    }

    private val activeRivals by
        lazy(LazyThreadSafetyMode.NONE) {
            view.players.filter { it.id != self.id && it.misery != MiseryStep.CHAOS }
        }

    private val overseasAccess by
        lazy(LazyThreadSafetyMode.NONE) {
            val catalog =
                requireNotNull(view.catalog) { "Overseas access requires a public catalog." }
            val commodityValues = catalog.commodities.associate { it.id to it.baseValue }
            val occupied = view.board.associateBy { it.id }
            val overseas = catalog.provinces.filter { it.overseas != null }
            val establishedOwners =
                overseas
                    .groupBy { it.overseas }
                    .mapValues { (_, provinces) ->
                        provinces.mapNotNull { occupied[it.id]?.marker?.owner }.distinct()
                    }
            overseas.map { province ->
                val marker = occupied[province.id]?.marker
                val rivalsWithAccess = activeRivals.filter { rival ->
                    province.access.all { it.isSatisfiedBy(rival.advances) }
                }
                val opportunity =
                    when (marker?.owner) {
                        self.id -> 0.0
                        null -> if (occupied[province.id]?.tokens.isNullOrEmpty()) 1.0 else .6
                        else -> .35
                    }
                val commodities = province.commodities.sumOf { commodityValues[it] ?: 0 }
                val established = establishedOwners[province.overseas]?.singleOrNull()
                val contestable =
                    marker == null &&
                        established != null &&
                        rivalsWithAccess.singleOrNull()?.id == established
                OverseasOpportunity(
                    province,
                    accessValue = commodities * opportunity / (1.0 + .35 * rivalsWithAccess.size),
                    contestValue = if (contestable) commodities * opportunity else 0.0,
                )
            }
        }

    private data class OverseasOpportunity(
        val province: ProvinceDefinition,
        val accessValue: Double,
        val contestValue: Double,
    )

    /** Estimate the next expansion's swing and access to markets a rival could claim alone. */
    fun publicAdvancePressure(advance: AdvanceId): Map<String, Double> {
        if (advance == AdvanceId.HOLY_INDULGENCE) {
            val existingOwners = activeRivals.count { AdvanceId.HOLY_INDULGENCE in it.advances }
            return mapOf(
                    BotPurchaseFeature.INDULGENCE_SWING.key to
                        HolyIndulgence.TOKENS_PER_RIVAL.toDouble() * activeRivals.size,
                    BotPurchaseFeature.INDULGENCE_COUNTER.key to
                        HolyIndulgence.TOKENS_PER_RIVAL.toDouble() * existingOwners,
                )
                .filterValues { it != 0.0 }
        }
        val route =
            when (advance) {
                AdvanceId.OCEAN_NAVIGATION,
                AdvanceId.NEW_WORLD -> advance
                AdvanceId.THE_HEAVENS,
                AdvanceId.SEAWORTHY_VESSELS -> {
                    if (
                        AdvanceId.THE_HEAVENS in self.advances ||
                            AdvanceId.SEAWORTHY_VESSELS in self.advances
                    )
                        return emptyMap()
                    AdvanceId.OCEAN_NAVIGATION
                }
                AdvanceId.URBAN_ASCENDANCY -> AdvanceId.NEW_WORLD
                else -> return emptyMap()
            }
        val withRoute = self.advances + route
        val opened = overseasAccess.filter { (province) ->
            province.access.all { it.isSatisfiedBy(withRoute) } &&
                province.access.any { !it.isSatisfiedBy(self.advances) }
        }
        val pathWeight =
            when {
                advance == route -> 1.0
                endingTriggered -> 0.0
                else -> .35
            }
        val access = opened.sumOf(OverseasOpportunity::accessValue) * pathWeight
        val contest = opened.sumOf(OverseasOpportunity::contestValue) * pathWeight
        return buildMap {
            if (access > 0.0) put(BotPurchaseFeature.OVERSEAS_ACCESS.key, access)
            if (contest > 0.0) put(BotPurchaseFeature.OVERSEAS_CONTEST.key, contest)
        }
    }

    /**
     * Negative upgrade dollars at risk of being superseded before Expansion. Seaworthy Vessels
     * replaces a galley fleet; Ocean Navigation replaces a seaworthy fleet. The replacing advance
     * must already be eligible and affordable after this shipping quote, using only currently
     * authorized discounts. This is an opportunity signal, not a predicted purchase or a command
     * offer. Future prerequisites, Research purchases and Patronage claims are not assumed.
     */
    fun shipping(quote: ShippingOfferView): Map<String, Double> {
        val cashAfter = quote.cashAfter ?: return emptyMap()
        val replacement =
            when (quote.ships?.fleet) {
                ShippingFleet.GALLEY -> AdvanceId.SEAWORTHY_VESSELS
                ShippingFleet.SEAWORTHY -> AdvanceId.OCEAN_NAVIGATION
                else -> return emptyMap()
            }
        val advance = advanceOffers.singleOrNull { it.advance == replacement } ?: return emptyMap()
        val price = advance.price ?: return emptyMap()
        if (advance.unavailableReason != null || price > cashAfter) return emptyMap()
        return mapOf(BotPurchaseFeature.NAVIGATION_REPLACEMENT.key to -quote.cost.toDouble())
    }

    /**
     * An additive final-turn score residual, not a replacement for the existing evaluator. Only
     * printed advance points, the quoted price and immediate numeric own misery changes count;
     * income, stabilization, optional discards and later actions remain separate. Chaos has no
     * numeric terminal delta and therefore receives no feature.
     *
     * An explicit zero is retained: presence means this candidate supports the terminal-price
     * derivative used by research sequencing. Callers must preserve that key, including zero.
     */
    fun finalAdvanceScore(
        points: Int,
        cashCost: Int,
        miseryChanges: List<MiseryChangeView>,
    ): Map<String, Double> {
        if (!endingTriggered) return emptyMap()
        val misery =
            miseryChanges
                .filter { it.player == self.id }
                .sumOf { change ->
                    val before = change.fromValue ?: return emptyMap()
                    val after = change.toValue ?: return emptyMap()
                    after.toDouble() - before
                }
        return mapOf(
            BotEconomicFeature.FINAL_ADVANCE_SCORE.key to points.toDouble() - cashCost - misery
        )
    }

    /**
     * The change in the owner's currently quoted Interest & Profit receipt caused by this cash cost
     * alone. Current ordinary income is held fixed; later purchases, expansion and card receipts
     * are not predicted. Acquiring Interest & Profit has its separate income feature. Final Card
     * Play follows the last Income, so spending there cannot forfeit another receipt.
     */
    fun foregoneInterest(cashCost: Int): Map<String, Double> {
        if (cashCost <= 0) return emptyMap()
        return interestAfterCash(self.cash - cashCost)
    }

    /**
     * Signed change in the pending Interest & Profit receipt after an immediate cash movement. A
     * simultaneous receipt can fund a payment; only its actual endpoint changes interest. Ordinary
     * income is held fixed, and Final Card Play has no remaining income receipt.
     */
    fun interestAfterCash(cashAfter: Int): Map<String, Double> {
        if (!incomePending || AdvanceId.INTEREST_PROFIT !in self.advances) {
            return emptyMap()
        }
        val income = self.income ?: return emptyMap()
        val after = minOf(cashAfter, income.ordinary)
        val change = after - income.interestProfit
        return if (change == 0) {
            emptyMap()
        } else {
            mapOf(BotEconomicFeature.FOREGONE_INTEREST.key to change.toDouble())
        }
    }

    /**
     * The uncapped target retains the legacy quarter-cash budget and physical stock limit. This
     * feature only relaxes the prior's sixteen-token ceiling; it does not claim the tokens will be
     * useful or predict opponents' sealed bids. Weighting the correction like tokenPreference
     * replaces the old target with the uncapped target.
     */
    fun tokens(quote: TokenPurchaseOfferView): Map<String, Double> {
        val budget = maxOf(2, self.cash / 4)
        val legacyGoal = minOf(self.stockTokens, minOf(16, budget))
        val uncappedGoal = minOf(self.stockTokens, budget)
        val legacyDistance = abs(quote.amount - legacyGoal).toDouble()
        val uncappedDistance = abs(quote.amount - uncappedGoal).toDouble()
        return mapOf(
                "tokenPreference" to -legacyDistance,
                BotEconomicFeature.TOKEN_CAP_CORRECTION.key to legacyDistance - uncappedDistance,
            )
            .filterValues { it != 0.0 }
    }

    /**
     * Middle Class adds ten ordinary dollars. Interest & Profit doubles cash remaining after the
     * purchase, capped at currently projected ordinary income. Its estimate excludes subsequent
     * spending, expansion and card play. The endgame correction uses the same fraction as legacy
     * income, allowing one tied pair of weights to retain that horizon adjustment.
     */
    fun advanceIncome(
        advance: AdvanceId,
        cashCost: Int,
        endgame: Double,
    ): Map<String, Double> {
        val legacy =
            when (advance) {
                AdvanceId.MIDDLE_CLASS -> self.boardMarkers.toDouble()
                AdvanceId.INTEREST_PROFIT -> minOf(10, (self.cash - cashCost) / 10).toDouble()
                else -> 0.0
            }
        val projected =
            when (advance) {
                AdvanceId.MIDDLE_CLASS ->
                    PlayerEconomy.middleClassIncomeBonus(owned = true).toDouble()
                AdvanceId.INTEREST_PROFIT ->
                    self.income?.ordinary?.let { income ->
                        minOf(self.cash - cashCost, income).toDouble()
                    }
                else -> null
            }
        val correction = (projected ?: legacy) - legacy
        return mapOf(
                "income" to legacy,
                BotEconomicFeature.ADVANCE_INCOME_CORRECTION.key to correction,
                BotEconomicFeature.ENDGAME_ADVANCE_INCOME_CORRECTION.key to correction * endgame,
            )
            .filterValues { it != 0.0 }
    }
}
