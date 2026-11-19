package dev.aragos.aor.ai

import dev.aragos.aor.CardKind
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import kotlin.math.exp

/**
 * [futureWeight] discounts a play by log probability per projected dollar of extra payout after
 * affordable peaceful expansion. [minimumGrowth] and [currentPayoutLimit] are in game dollars.
 */
data class CommodityTimingConfig(
    val futureWeight: Double,
    val minimumGrowth: Int,
    val currentPayoutLimit: Int,
) {
    init {
        require(futureWeight.isFinite() && futureWeight in 0.0..1.0) {
            "Commodity timing weight must be between zero and one."
        }
        require(minimumGrowth > 0 && currentPayoutLimit >= 0) {
            "Commodity timing requires positive future growth and a nonnegative current payout."
        }
    }

    companion object {
        const val VERSION = "affordable-commodity-retention-1"
    }
}

/** Uses only authoritative current payouts and the normal own-seat expansion forecasts. */
object CommodityTimingPolicy {
    fun adjust(
        view: GameView,
        player: PlayerId,
        observation: BotObservation,
        encoded: CardObservation,
        evaluation: CardPolicyEvaluation,
        config: CommodityTimingConfig,
    ): CardPolicyEvaluation {
        if (config.futureWeight == 0.0) return evaluation
        val opportunities = opportunities(view, player, observation, config)
        if (opportunities.isEmpty()) return evaluation
        val penalties =
            encoded.actions.map { action ->
                (config.futureWeight * (opportunities[action.command] ?: 0.0)).coerceAtMost(4.0)
            }
        if (penalties.all { it == 0.0 }) return evaluation
        val adjusted =
            evaluation.probabilities.zip(penalties) { probability, penalty ->
                (probability * exp(-penalty)).coerceAtLeast(Double.MIN_VALUE)
            }
        val total = adjusted.sum()
        return evaluation.copy(
            logits = evaluation.logits.zip(penalties, Double::minus),
            probabilities = adjusted.map { it / total },
        )
    }

    /** Legal commodity plays that would consume an affordable future payout opportunity. */
    fun opportunities(
        view: GameView,
        player: PlayerId,
        observation: BotObservation,
        config: CommodityTimingConfig,
    ): Map<String, Double> {
        if (view.stage != Stage.PLAY_CARDS || view.endingTriggered) return emptyMap()
        val commodityReferences =
            view.hand
                .filter { it.definition.kind == CardKind.COMMODITY }
                .map { it.reference }
                .toSet()
        if (commodityReferences.isEmpty()) return emptyMap()
        val choices =
            view.cardOffers
                .filter { it.reference in commodityReferences }
                .flatMap { it.choices }
                .associateBy { it.command }
        val candidates = observation.candidates.associateBy(BotCandidate::command)
        return buildMap {
            for ((action, candidate) in candidates) {
                val quote = choices[action] ?: continue
                val current =
                    quote.cashChanges.filter { it.player == player }.sumOf { it.after - it.before }
                if (current > config.currentPayoutLimit) continue
                val growth =
                    maxOf(
                        -(candidate.features[BotEconomicFeature.RETAINED_COMMODITY.key] ?: 0.0),
                        -(candidate.features[BotEconomicFeature.REACHABLE_RETENTION.key] ?: 0.0),
                    )
                if (growth >= config.minimumGrowth) put(action, growth)
            }
        }
    }
}
