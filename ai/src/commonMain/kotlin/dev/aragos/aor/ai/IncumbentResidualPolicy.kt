package dev.aragos.aor.ai

/** A deterministic experimental role; this is not the sampled territory policy contract. */
data class IncumbentResidualSelector(val scale: Double, val margin: Double = 1.0) {
    init {
        require(margin == 1.0 && scale.isFinite() && scale in (1.0 / 32.0)..32.0) {
            "Incumbent residual selection requires margin1 and one sealed scale in [1/32,32]."
        }
    }

    companion object {
        const val VERSION = "incumbent-residual-margin-1"
    }
}

/** All indices refer to the original full offer list; no stochastic likelihood is invented. */
data class IncumbentResidualChoice(
    val residualDifferences: List<Double>,
    val scores: List<Double>,
    val index: Int,
    val command: String,
)

/** Shared JVM/JS residual difference and exact incumbent-first maximum selection. */
object IncumbentResidualPolicy {
    fun differences(
        observation: TerritoryObservation,
        current: TerritoryPolicyModel,
        original: TerritoryPolicyModel,
    ): List<Double> {
        validate(observation, current, original)
        val actions = observation.actions.map(TerritoryAction::features)
        val residuals =
            ResidualPolicyMath.rawResiduals(observation.stateFeatures, actions, current.network)
        val baseline =
            if (current === original) residuals
            else
                ResidualPolicyMath.rawResiduals(
                    observation.stateFeatures,
                    actions,
                    original.network,
                )
        return residuals
            .zip(baseline) { value, prior -> value - prior }
            .also { values ->
                require(values.all(Double::isFinite)) {
                    "Every raw residual difference must be finite."
                }
            }
    }

    /** Signed strongest-alternative gap; a singleton has no calibration gap. */
    fun strongestGap(differences: List<Double>, referenceIndex: Int): Double? {
        require(
            differences.isNotEmpty() &&
                referenceIndex in differences.indices &&
                differences.all(Double::isFinite)
        ) {
            "A gap requires the complete finite residual vector and its offered reference."
        }
        return differences.indices
            .filter { it != referenceIndex }
            .maxOfOrNull { differences[it] - differences[referenceIndex] }
    }

    fun choose(
        observation: TerritoryObservation,
        current: TerritoryPolicyModel,
        original: TerritoryPolicyModel,
        selector: IncumbentResidualSelector,
    ): IncumbentResidualChoice =
        select(observation, differences(observation, current, original), selector)

    fun select(
        observation: TerritoryObservation,
        differences: List<Double>,
        selector: IncumbentResidualSelector,
    ): IncumbentResidualChoice {
        val scores = differences.mapIndexed { index, difference ->
            (if (index == observation.baselineIndex) selector.margin else 0.0) +
                selector.scale * difference
        }
        return selectScores(observation, differences, scores)
    }

    /**
     * Explicit score adjustments retain the complete offer order and exact reference-first ties.
     */
    fun selectScores(
        observation: TerritoryObservation,
        differences: List<Double>,
        scores: List<Double>,
    ): IncumbentResidualChoice {
        require(
            differences.size == observation.actions.size &&
                scores.size == observation.actions.size &&
                differences.all(Double::isFinite) &&
                scores.all(Double::isFinite)
        ) {
            "Residual selection requires one finite difference and score per original offered action."
        }
        val maximum = scores.max()
        val index =
            if (scores[observation.baselineIndex] == maximum) observation.baselineIndex
            else scores.indexOfFirst { it == maximum }
        return IncumbentResidualChoice(
            differences,
            scores,
            index,
            observation.actions[index].command,
        )
    }

    private fun validate(
        observation: TerritoryObservation,
        current: TerritoryPolicyModel,
        original: TerritoryPolicyModel,
    ) {
        require(
            current.schemaVersion == original.schemaVersion &&
                current.encoderVersion == original.encoderVersion &&
                current.encoderVersion == observation.encoderVersion &&
                (current.schemaVersion == 2 &&
                    current.encoderVersion == TerritoryEncoder.EXPANSION_2.version ||
                    current.schemaVersion == 3 &&
                        current.encoderVersion == TerritoryEncoder.TOKEN_2.version)
        ) {
            "The new deterministic role requires matching explicit E2 or TOKEN2 containers."
        }
        require(
            current.stateFeatureNames == original.stateFeatureNames &&
                current.actionFeatureNames == original.actionFeatureNames &&
                current.valueWeights == original.valueWeights &&
                current.valueBias == original.valueBias
        ) {
            "Residual differences preserve the complete dictionary and original value arrays."
        }
    }
}
