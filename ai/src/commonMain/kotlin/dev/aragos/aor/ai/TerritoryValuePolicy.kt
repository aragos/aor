package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Typed linear action values, deliberately distinct from residual PPO actors. Matrices have 64
 * row-major hidden rows. The six parameter fields use Float64; outputBias initializes to .5 but
 * remains trainable. Values are not probabilities and are never clipped or passed through sigmoid.
 */
data class TerritoryValueModel(
    val schemaVersion: Int = 1,
    val architecture: String = TerritoryValuePolicy.ARCHITECTURE,
    val encoderVersion: String,
    val hiddenSize: Int = 64,
    val stateFeatureNames: List<String>,
    val actionFeatureNames: List<String>,
    val stateWeights: List<Double>,
    val stateBias: List<Double>,
    val actionWeights: List<Double>,
    val actionBias: List<Double>,
    val outputWeights: List<Double>,
    val outputBias: Double,
) {
    init {
        require(schemaVersion == 1 && architecture == TerritoryValuePolicy.ARCHITECTURE) {
            "Unsupported territory action-value model schema or architecture."
        }
        TerritoryEncoder.fromVersion(encoderVersion)
        require(hiddenSize == 64) { "Territory action values require exactly 64 hidden units." }
        TerritoryFeatureDictionary(stateFeatureNames, actionFeatureNames)
        require(
            encoderVersion != TerritoryEncoder.EXPANSION_2.version ||
                stateFeatureNames.containsAll(ExpansionObservationEncoder.cathedralUsageNames)
        ) {
            "Expansion encoder 2 requires every public Cathedral usage column."
        }
        require(
            stateWeights.size == hiddenSize * stateFeatureNames.size &&
                actionWeights.size == hiddenSize * actionFeatureNames.size &&
                listOf(stateBias, actionBias, outputWeights).all { it.size == hiddenSize }
        ) {
            "Action-value parameter shapes must match their complete typed dictionaries."
        }
        require(
            listOf(stateWeights, stateBias, actionWeights, actionBias, outputWeights).all { values
                ->
                values.all { it.isFinite() && abs(it) <= 1.0e6 }
            } && outputBias.isFinite() && abs(outputBias) <= 1.0e6
        ) {
            "Action-value parameters must be finite and bounded."
        }
    }

    internal val stateIndices = stateFeatureNames.withIndex().associate { it.value to it.index }
    internal val actionIndices = actionFeatureNames.withIndex().associate { it.value to it.index }
}

/** Mixture probabilities belong to the explicit epsilon selector, not a learned policy head. */
data class TerritoryValueEvaluation(
    val values: List<Double>,
    val probabilities: List<Double>,
    val greedyIndex: Int,
) {
    init {
        require(
            values.size in 1..1024 &&
                values.size == probabilities.size &&
                greedyIndex in values.indices &&
                values.all { it.isFinite() && abs(it) <= 1.0e6 }
        ) {
            "Territory values require matching, finite bounded offered-action results."
        }
        require(
            probabilities.all { it.isFinite() && it in 0.0..1.0 } &&
                abs(probabilities.sum() - 1.0) < 1.0e-9
        ) {
            "Territory selector probabilities must have unit mass."
        }
    }
}

data class TerritoryValueChoice(
    val index: Int,
    val command: String,
    val selectedQ: Double,
    val probability: Double,
) {
    init {
        require(
            index >= 0 &&
                command.isNotBlank() &&
                selectedQ.isFinite() &&
                abs(selectedQ) <= 1.0e6 &&
                probability.isFinite() &&
                probability > 0.0 &&
                probability <= 1.0
        ) {
            "A territory value choice requires an offered command, finite Q and positive behavior probability."
        }
    }
}

/** Actor-safe linear Q inference. F resolves exact value ties; no F probability prior is added. */
object TerritoryValuePolicy {
    const val ARCHITECTURE = "aor-territory-linear-q-1"
    const val COLLECTION_EPSILON = 0.2

    fun selectorVersion(phase: TerritoryPhase): String =
        when (phase) {
            TerritoryPhase.TOKEN -> "aor-token-value-policy-1"
            TerritoryPhase.EXPANSION -> "aor-expansion-value-policy-1"
        }

    /** The actual sealed-batch bot seed stays provenance, never a state or action feature. */
    fun selectorSeed(phase: TerritoryPhase, botSeed: Int): Int =
        botSeed xor
            when (phase) {
                TerritoryPhase.TOKEN -> 0x243f6a88
                TerritoryPhase.EXPANSION -> 0x85a308d3.toInt()
            }

    fun evaluate(
        observation: TerritoryObservation,
        model: TerritoryValueModel,
        epsilon: Double,
    ): TerritoryValueEvaluation {
        require(observation.encoderVersion == model.encoderVersion) {
            "Territory action-value and observation encoder identities must match."
        }
        require(epsilon.isFinite() && epsilon in 0.0..1.0) {
            "Territory exploration epsilon must be finite and within [0,1]."
        }
        val state = indexed(observation.stateFeatures, model.stateIndices)
        val hidden =
            List(model.hiddenSize) { row ->
                tanh(
                    model.stateBias[row] +
                        dot(model.stateWeights, row, model.stateFeatureNames.size, state)
                )
            }
        val values =
            observation.actions.map { action ->
                val features = indexed(action.features, model.actionIndices)
                model.outputBias +
                    model.outputWeights.indices.sumOf { row ->
                        model.outputWeights[row] *
                            tanh(
                                hidden[row] +
                                    model.actionBias[row] +
                                    dot(
                                        model.actionWeights,
                                        row,
                                        model.actionFeatureNames.size,
                                        features,
                                    )
                            )
                    }
            }
        require(values.all { it.isFinite() && abs(it) <= 1.0e6 }) {
            "Territory action values exceeded the finite diagnostic guard; values are not clipped."
        }
        val maximum = values.max()
        val greedy =
            if (values[observation.baselineIndex] == maximum) {
                observation.baselineIndex
            } else {
                values.indexOfFirst { it == maximum }
            }
        val probabilities =
            values.indices.map { index ->
                epsilon / values.size + if (index == greedy) 1.0 - epsilon else 0.0
            }
        return TerritoryValueEvaluation(values, probabilities, greedy)
    }

    fun choose(
        observation: TerritoryObservation,
        model: TerritoryValueModel,
        epsilon: Double,
        seed: Int,
    ): TerritoryValueChoice =
        select(observation, evaluate(observation, model, epsilon), uniform(seed))

    fun select(
        observation: TerritoryObservation,
        evaluation: TerritoryValueEvaluation,
        uniform: Double,
    ): TerritoryValueChoice {
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "Territory value selection requires a uniform draw in [0,1)."
        }
        require(observation.actions.size == evaluation.values.size) {
            "Territory value selection must retain every offered action."
        }
        val index = ResidualPolicyMath.select(evaluation.probabilities, uniform)
        return TerritoryValueChoice(
            index,
            observation.actions[index].command,
            evaluation.values[index],
            evaluation.probabilities[index],
        )
    }

    fun uniform(seed: Int): Double = ResidualPolicyMath.uniform(seed)

    private fun indexed(
        features: Map<String, Double>,
        indices: Map<String, Int>,
    ): List<Pair<Int, Double>> =
        features
            .map { (name, value) ->
                requireNotNull(indices[name]) {
                    "Unknown territory action-value feature: $name."
                } to value
            }
            .sortedBy { it.first }

    private fun dot(
        weights: List<Double>,
        row: Int,
        columns: Int,
        features: List<Pair<Int, Double>>,
    ): Double = features.sumOf { (column, value) -> weights[row * columns + column] * value }
}
