package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryChangeView
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ShippingFleet
import dev.aragos.aor.ShippingOfferView
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.math.abs

/** Additive purchase signals; an absent or zero model weight retains the existing evaluation. */
enum class BotPurchaseFeature(val key: String) {
    NAVIGATION_REPLACEMENT("planning.navigationReplacement")
}

/**
 * Purchase estimates from the deciding seat's ordinary projection. Legacy features keep their
 * original values; additive corrections have no effect on models that do not weight them.
 */
internal class BotPurchaseProjection(view: GameView, seat: PlayerId) {
    private val self = view.players.single { it.id == seat }
    private val advanceOffers = view.advanceOffers
    private val incomePending = view.stage < Stage.INCOME
    private val endingTriggered = view.endingTriggered

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
