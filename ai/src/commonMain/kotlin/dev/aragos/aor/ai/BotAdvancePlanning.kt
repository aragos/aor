package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.AdvancePurchases
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId

/** Positive values enable exact research repricing; one values the full cash-feature correction. */
enum class BotAdvanceFeature(val key: String) {
    RESEARCH_SAVING("planning.advanceResearchSaving")
}

/**
 * [institutionalResearchDiscount] is the additional dollar reduction to this current offer if
 * Institutional Research is bought first. Existing credits, leaders and the price floor are fixed
 * by the authoritative quote; this amount never adds same-turn category credits.
 */
data class BotAdvanceStep(
    val advance: AdvanceId,
    val institutionalResearchDiscount: Int,
) {
    init {
        require(
            institutionalResearchDiscount in
                0..AdvancePurchases.institutionalResearchCredit(advance, owned = true)
        ) {
            "A research step requires a bounded additional credit for its advance category."
        }
    }
}

/**
 * Only already offered legal purchases participate; this metadata cannot unlock future actions.
 * [ordinaryIncome] is the owner's current public dollar quote, including any already owned Middle
 * Class bonus. Null retains the previous planner's isolated-interest estimates for old
 * observations.
 */
data class BotAdvancePlanning(
    val steps: Map<String, BotAdvanceStep>,
    val ownsInterestProfit: Boolean = false,
    val ordinaryIncome: Int? = null,
) {
    init {
        require(
            steps.size <= AdvanceId.entries.size &&
                steps.all { (command, step) ->
                    command == "buy-advance ${step.advance.letter}"
                }
        ) {
            "Advance planning steps must use each advance's canonical buy command."
        }
        require(
            ordinaryIncome == null ||
                ordinaryIncome in
                    0..Int.MAX_VALUE - PlayerEconomy.middleClassIncomeBonus(owned = true)
        ) {
            "Advance planning ordinary income must be nonnegative and leave room for Middle Class."
        }
    }
}

/** Public quote projection, retaining already frozen category credits and authorized leaders. */
internal object BotAdvancePlanningProjection {
    fun from(
        view: GameView,
        seat: PlayerId,
        candidates: List<BotCandidate>,
    ): BotAdvancePlanning {
        val self = view.players.single { it.id == seat }
        val offers = view.advanceOffers.associateBy { it.advance }
        val steps =
            candidates
                .filter { it.family == "advance" }
                .associate { candidate ->
                    val advance =
                        AdvanceId.fromLetter(candidate.command.substringAfterLast(' ').single())
                    val quote =
                        checkNotNull(offers[advance]?.priceBreakdown) {
                            "Advance sequence planning requires an authoritative public price breakdown."
                        }
                    val additional =
                        if (AdvanceId.INSTITUTIONAL_RESEARCH in self.advances) {
                            0
                        } else {
                            val after =
                                quote.copy(
                                    institutionalResearchCredit =
                                        AdvancePurchases.institutionalResearchCredit(
                                            advance,
                                            owned = true,
                                        )
                                )
                            quote.price - after.price
                        }
                    candidate.command to BotAdvanceStep(advance, additional)
                }
        return BotAdvancePlanning(
            steps = steps,
            ownsInterestProfit = AdvanceId.INTEREST_PROFIT in self.advances,
            ordinaryIncome = self.income?.ordinary,
        )
    }
}

/**
 * Reprice later beam purchases only after an earlier step buys Institutional Research. Any positive
 * research weight enables exact resource costs; the weight scales only the cash-score correction.
 * Zero or negative weights preserve the legacy path. At weight one, all existing conditional cash
 * features are corrected exactly once. Reserve costs and nonlinear Interest & Profit changes are
 * separate planner calculations; new leader abilities and future prerequisites remain outside this
 * horizon. A present final-advance-score feature receives its exact saved-dollar derivative at its
 * own weight, independently of the research weight. An explicit zero feature still participates.
 * Reserved token-value features never contribute to ordinary cash-score derivatives.
 */
internal class BotAdvanceSequencing
private constructor(
    private val planning: BotAdvancePlanning,
    private val weight: Double,
    private val cashCoefficients: Map<String, Double>,
    private val finalScoreWeight: Double,
) {
    fun price(candidate: BotCandidate, purchasedCommands: Set<String>): Int {
        val research = purchasedCommands.any {
            planning.steps[it]?.advance == AdvanceId.INSTITUTIONAL_RESEARCH
        }
        if (!research) return candidate.cashCost
        val discount = planning.steps[candidate.command]?.institutionalResearchDiscount ?: 0
        return (candidate.cashCost - discount).coerceAtLeast(0)
    }

    fun value(candidate: BotCandidate, purchasedCommands: Set<String>): Double {
        val saved = candidate.cashCost - price(candidate, purchasedCommands)
        val cashValue = saved * (cashCoefficients[candidate.command] ?: 0.0) * weight
        if (
            finalScoreWeight == 0.0 ||
                BotEconomicFeature.FINAL_ADVANCE_SCORE.key !in candidate.features
        ) {
            return cashValue
        }
        return cashValue + saved * finalScoreWeight
    }

    companion object {
        fun forObservation(observation: BotObservation, model: BotModel): BotAdvanceSequencing? {
            val weight = model.weights[BotAdvanceFeature.RESEARCH_SAVING.key] ?: 0.0
            if (weight <= 0.0) return null
            val planning = observation.advancePlanning ?: return null
            val cashCoefficients =
                observation.candidates.associate { candidate ->
                    val coefficient =
                        if (candidate.cashCost == 0) {
                            0.0
                        } else {
                            -candidate.features.entries
                                .filter { (name, _) ->
                                    !BotTokenValue.reserved(name) &&
                                        (name == "cash" || name.endsWith(".cash"))
                                }
                                .sumOf { (name, value) -> value * (model.weights[name] ?: 0.0) } /
                                candidate.cashCost
                        }
                    candidate.command to coefficient
                }
            return BotAdvanceSequencing(
                planning,
                weight,
                cashCoefficients,
                model.weights[BotEconomicFeature.FINAL_ADVANCE_SCORE.key] ?: 0.0,
            )
        }
    }
}
