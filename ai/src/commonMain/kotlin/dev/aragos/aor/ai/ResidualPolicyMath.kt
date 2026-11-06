package dev.aragos.aor.ai

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/** Immutable validated model references; phase-specific types retain their own wire invariants. */
internal data class ResidualPolicyWeights(
    val kind: String,
    val hiddenSize: Int,
    val baselineProbability: Double,
    val stateColumns: Int,
    val actionColumns: Int,
    val stateIndices: Map<String, Int>,
    val actionIndices: Map<String, Int>,
    val stateWeights: List<Double>,
    val stateBias: List<Double>,
    val actionWeights: List<Double>,
    val actionBias: List<Double>,
    val actorWeights: List<Double>,
    val valueWeights: List<Double>,
    val valueBias: Double,
)

internal data class ResidualPolicyEvaluation(
    val logits: List<Double>,
    val probabilities: List<Double>,
    val value: Double,
)

/**
 * Shared Float64 arithmetic. Ordering and the equal-residual prior are the purchase-v1 contract.
 */
internal object ResidualPolicyMath {
    fun evaluate(
        stateFeatures: Map<String, Double>,
        actionFeatures: List<Map<String, Double>>,
        baselineIndex: Int,
        network: ResidualPolicyWeights,
    ): ResidualPolicyEvaluation {
        val state = indexed(stateFeatures, network.stateIndices, network.kind)
        val hidden =
            List(network.hiddenSize) { row ->
                tanh(
                    network.stateBias[row] +
                        dot(network.stateWeights, row, network.stateColumns, state)
                )
            }
        val residuals = actionFeatures.map { action ->
            val features = indexed(action, network.actionIndices, network.kind)
            network.actorWeights.indices
                .sumOf { row ->
                    network.actorWeights[row] *
                        tanh(
                            hidden[row] +
                                network.actionBias[row] +
                                dot(
                                    network.actionWeights,
                                    row,
                                    network.actionColumns,
                                    features,
                                )
                        )
                }
                .coerceIn(-20.0, 20.0)
        }
        val prior =
            List(actionFeatures.size) { index ->
                when {
                    actionFeatures.size == 1 -> 1.0
                    index == baselineIndex -> network.baselineProbability
                    else -> 0.03 / (actionFeatures.size - 1)
                }
            }
        val logits = prior.indices.map { ln(prior[it]) + residuals[it] }
        val probabilities =
            if (residuals.all { it == residuals.first() }) prior
            else {
                val maximum = logits.max()
                val unnormalized = logits.map { exp(it - maximum) }
                val total = unnormalized.sum()
                unnormalized.map { it / total }
            }
        val rawValue =
            network.valueBias + hidden.indices.sumOf { network.valueWeights[it] * hidden[it] }
        val value =
            if (rawValue >= 0.0) 1.0 / (1.0 + exp(-rawValue))
            else {
                val exponential = exp(rawValue)
                exponential / (1.0 + exponential)
            }
        return ResidualPolicyEvaluation(logits, probabilities, value)
    }

    fun select(probabilities: List<Double>, uniform: Double): Int {
        var cumulative = 0.0
        return probabilities
            .indexOfFirst { probability ->
                cumulative += probability
                uniform < cumulative
            }
            .let { if (it < 0) probabilities.lastIndex else it }
    }

    fun uniform(seed: Int): Double {
        var mixed = seed
        mixed = (mixed xor (mixed ushr 16)) * -2048144789
        mixed = (mixed xor (mixed ushr 13)) * -1028477387
        mixed = mixed xor (mixed ushr 16)
        return (mixed.toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    private fun indexed(
        features: Map<String, Double>,
        indices: Map<String, Int>,
        kind: String,
    ): List<Pair<Int, Double>> =
        features
            .map { (name, value) ->
                checkNotNull(indices[name]) { "Unknown $kind feature: $name." } to value
            }
            .sortedBy { it.first }

    private fun dot(
        weights: List<Double>,
        row: Int,
        columns: Int,
        features: List<Pair<Int, Double>>,
    ): Double = features.sumOf { (column, value) -> weights[row * columns + column] * value }
}
