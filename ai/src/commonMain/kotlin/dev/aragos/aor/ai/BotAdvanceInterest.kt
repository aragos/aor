package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.PlayerEconomy

/**
 * One cumulative Interest & Profit correction for an advance plan, in weighted receipt dollars. The
 * planner removes isolated foregone-interest features while this helper is active and adds changes
 * in [value] once, without the continuation discount. Already owned Interest & Profit uses its
 * initial receipt as the baseline. Newly purchased Interest & Profit uses its original standalone
 * receipt quote, leaving that advance's existing income features intact and its solo correction
 * zero.
 *
 * Only selected Middle Class changes the ordinary-income cap; newly repriced purchases supply their
 * actual remaining cash. Future stabilization, relief, expansion, card play and later turns are not
 * predicted. In particular this retains the existing immediate-quote horizon for both receipts.
 */
internal class BotAdvanceInterest
private constructor(
    private val ownsInterestProfit: Boolean,
    private val ordinaryIncome: Int,
    private val originalBonus: Int,
    private val interestCommand: String?,
    private val middleClassCommand: String?,
    private val weight: Double,
) {
    fun value(cash: Int, purchasedCommands: Set<String>): Double {
        val acquiredInterest = interestCommand != null && interestCommand in purchasedCommands
        if (!ownsInterestProfit && !acquiredInterest) return 0.0
        val cap =
            ordinaryIncome +
                PlayerEconomy.middleClassIncomeBonus(
                    owned = middleClassCommand != null && middleClassCommand in purchasedCommands
                )
        return (minOf(cash, cap) - originalBonus) * weight
    }

    companion object {
        fun forObservation(observation: BotObservation, model: BotModel): BotAdvanceInterest? {
            val weight = model.weights[BotEconomicFeature.FOREGONE_INTEREST.key] ?: 0.0
            if (weight == 0.0) return null
            val planning = observation.advancePlanning ?: return null
            val cap = planning.ordinaryIncome ?: return null
            val interestCommand =
                planning.steps.entries
                    .firstOrNull {
                        it.value.advance == AdvanceId.INTEREST_PROFIT
                    }
                    ?.key
            if (!planning.ownsInterestProfit && interestCommand == null) return null
            val middleClassCommand =
                planning.steps.entries
                    .firstOrNull {
                        it.value.advance == AdvanceId.MIDDLE_CLASS
                    }
                    ?.key
            val originalBonus =
                if (planning.ownsInterestProfit) {
                    minOf(observation.availableCash, cap)
                } else {
                    val candidate =
                        checkNotNull(
                            observation.candidates.firstOrNull {
                                it.command == interestCommand
                            }
                        ) {
                            "An Interest & Profit planning quote requires its offered candidate."
                        }
                    minOf((observation.availableCash - candidate.cashCost).coerceAtLeast(0), cap)
                }
            return BotAdvanceInterest(
                planning.ownsInterestProfit,
                cap,
                originalBonus,
                interestCommand,
                middleClassCommand,
                weight,
            )
        }
    }
}
