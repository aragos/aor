package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import kotlin.math.exp

/**
 * Coefficients over the frozen public inputs, followed by the same fields scaled by (turn - 5)
 * / 4. [maximumForecastGap] is an optional difference between winner probabilities. A missing gap
 * retains the regular public score eligibility.
 */
data class LinearPublicThreat(
    val weights: List<Double>,
    val maximumForecastGap: Double? = null,
) {
    init {
        require(
            weights.size == PublicWinningPosition.columns.size * 2 &&
                weights.all { it.isFinite() && it in -16.0..16.0 }
        ) {
            "Public threat coefficients must reproduce each frozen field and its turn interaction."
        }
        require(maximumForecastGap == null || maximumForecastGap in 0.01..0.5) {
            "A forecast eligibility gap must be within one and fifty percentage points."
        }
    }

    internal fun probabilities(view: GameView, turn: Int): Map<PlayerId, Double> {
        val scale = (turn - 5) / 4.0
        val columns = PublicWinningPosition.columns.size
        val scores =
            PublicWinningPosition.seats(view)
                .filter { it.active }
                .associate { seat ->
                    seat.player to
                        seat.features.indices.sumOf { index ->
                            seat.features[index] *
                                (weights[index] + scale * weights[columns + index])
                        }
                }
        val highest = scores.values.maxOrNull() ?: return emptyMap()
        val mass = scores.mapValues { (_, value) -> exp(value - highest) }
        val total = mass.values.sum()
        return mass.mapValues { (_, weight) -> weight / total }
    }

    companion object {
        const val VERSION = "public-threat-linear-1"
    }
}
