package dev.aragos.aor.ai

/**
 * Optional second selector fitted to terminal return differences around the intact incumbent.
 * [BotTokenValue] first chooses its usual action, including its independent inference gates. This
 * selector compares the original six legal proposals around that actual choice, using the shared
 * feature differences and a fixed 0.02 margin. Incumbent head scores are never a return prior.
 * Missing/disabled corrections, incomplete observations, ties and insufficient advantage retain
 * that exact incumbent action. Only HARD token buying invokes this selector.
 */
object BotTokenCorrection {
    const val GATE = "planning.tokenCorrection.enabled"
    private const val PREFIX = "planning.tokenCorrection."

    /**
     * Coefficient keys in [BotTokenValue.featureNames] order; observation columns are unchanged.
     */
    val coefficientNames: List<String> =
        BotTokenValue.featureNames.map { PREFIX + it.removePrefix("planning.tokenValue.") }

    internal fun reserved(name: String): Boolean = name.startsWith(PREFIX)

    /** Replace only the correction head; preserve every incumbent weight and inference gate. */
    fun withWeights(base: BotModel, coefficients: List<Double>): BotModel {
        require(coefficients.size == coefficientNames.size && coefficients.all(Double::isFinite)) {
            "Token correction fitting requires one finite coefficient per ${BotTokenValue.FEATURE_VERSION} feature."
        }
        return base.copy(
            weights =
                base.weights.filterKeys { !reserved(it) } +
                    coefficientNames.zip(coefficients).toMap() +
                    (GATE to 1.0)
        )
    }

    internal fun choose(
        observation: BotObservation,
        incumbent: BotCandidate,
        model: BotModel,
    ): BotCandidate {
        if (model.weights[GATE] != 1.0 || !BotTokenValue.available(observation)) return incumbent
        val coefficients = coefficientNames.map { model.weights[it] ?: 0.0 }
        fun advantage(candidate: BotCandidate): Double =
            BotTokenValue.difference(observation, candidate, incumbent)
                .zip(coefficients) { difference, coefficient -> difference * coefficient }
                .sum()
        val best = BotTokenValue.alternatives(observation, incumbent).maxBy(::advantage)
        return if (advantage(best) > BotTokenValue.MINIMUM_ADVANTAGE) best else incumbent
    }
}
