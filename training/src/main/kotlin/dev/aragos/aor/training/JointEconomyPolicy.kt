package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel

/** Existing economic coefficients, grouped by the decision they influence in this experiment. */
internal enum class JointEconomyAxis(val id: String, val keys: List<String>) {
    LIQUIDITY(
        "liquidity",
        listOf(
            "planning.tokenValue.v1.costFraction",
            "planning.tokenValue.v2.plan.interest",
        ),
    ),
    INVESTMENT(
        "investment",
        listOf(
            "planning.tokenValue.v2.plan.heldCommodity",
            "planning.tokenValue.v2.plan.publicCommodity",
            "planning.tokenValue.v2.plan.income",
            "planning.tokenValue.v2.plan.endgameIncome",
        ),
    ),
    EXPANSION(
        "expansion",
        listOf("planning.heldCommodity", "planning.publicCommodity"),
    ),
}

/**
 * A candidate may replace only the eight declared coefficients of its exact frozen baseline. This
 * is an evaluation-only policy, not a browser model or a new purchase-actor prior.
 */
internal class JointEconomyPolicy(val baseline: BotModel, val model: BotModel) {
    init {
        require(
            model.schemaVersion == baseline.schemaVersion &&
                model.weights.filterKeys { it !in allowedKeys } ==
                    baseline.weights.filterKeys { it !in allowedKeys }
        ) {
            "An economy candidate may change only the eight declared token and expansion weights."
        }
    }

    companion object {
        const val VERSION = "aor-joint-economy-1"
        val allowedKeys: Set<String> = JointEconomyAxis.entries.flatMap { it.keys }.toSet()
    }
}

/** Multipliers are dimensionless; the baseline and all non-axis weights remain unchanged. */
internal data class JointEconomyCandidate(
    val id: String,
    val multipliers: List<Double>,
    val policy: JointEconomyPolicy,
)

/**
 * Fixed incumbent, six single-axis perturbations, then eight corners in axis-major order.
 * Generation uses no observations, game outcomes or fitted coefficients.
 */
internal object JointEconomyCandidates {
    fun generate(baseline: BotModel): List<JointEconomyCandidate> {
        require(JointEconomyPolicy.allowedKeys.all(baseline.weights::containsKey)) {
            "Joint economy generation requires all eight explicit baseline coefficients."
        }
        val levels = listOf(0.5, 1.5)
        val definitions =
            listOf("incumbent" to listOf(1.0, 1.0, 1.0)) +
                JointEconomyAxis.entries.flatMapIndexed { index, axis ->
                    levels.map { factor ->
                        "${axis.id}-${if (factor == 0.5) "low" else "high"}" to
                            List(3) { if (it == index) factor else 1.0 }
                    }
                } +
                levels.flatMap { liquidity ->
                    levels.flatMap { investment ->
                        levels.map { expansion ->
                            val factors = listOf(liquidity, investment, expansion)
                            "corner-${factors.joinToString("") { if (it == 0.5) "l" else "h" }}" to
                                factors
                        }
                    }
                }
        return definitions.map { (id, factors) ->
            val replacements =
                JointEconomyAxis.entries
                    .flatMapIndexed { index, axis ->
                        axis.keys.map { key ->
                            key to
                                requireNotNull(baseline.weights[key]) {
                                    "Every axis requires its baseline coefficient."
                                } * factors[index]
                        }
                    }
                    .toMap()
            JointEconomyCandidate(
                id,
                factors,
                JointEconomyPolicy(
                    baseline,
                    baseline.copy(weights = baseline.weights + replacements),
                ),
            )
        }
    }
}
