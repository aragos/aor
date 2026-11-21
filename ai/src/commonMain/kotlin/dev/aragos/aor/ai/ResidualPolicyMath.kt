package dev.aragos.aor.ai

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/** Immutable validated model references; phase-specific types retain their own wire invariants. */
internal class ResidualPolicyWeights(
    val kind: String,
    val hiddenSize: Int,
    val baselineProbability: Double,
    val stateColumns: Int,
    val actionColumns: Int,
    val stateIndices: Map<String, Int>,
    val actionIndices: Map<String, Int>,
    stateWeights: List<Double>,
    stateBias: List<Double>,
    actionWeights: List<Double>,
    actionBias: List<Double>,
    actorWeights: List<Double>,
    valueWeights: List<Double>,
    val valueBias: Double,
) {
    val stateWeights = stateWeights.toDoubleArray()
    val stateBias = stateBias.toDoubleArray()
    val actionWeights = actionWeights.toDoubleArray()
    val actionBias = actionBias.toDoubleArray()
    val actorWeights = actorWeights.toDoubleArray()
    val valueWeights = valueWeights.toDoubleArray()
}

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
        val hidden = hidden(indexed(stateFeatures, network.stateIndices, network.kind), network)
        val residuals =
            residuals(
                    hidden,
                    actionFeatures.map { indexed(it, network.actionIndices, network.kind) },
                    network,
                )
                .map { it.coerceIn(-20.0, 20.0) }
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

    /** Raw shared network arithmetic, excluding both legacy residual clipping and action priors. */
    fun rawResiduals(
        stateFeatures: Map<String, Double>,
        actionFeatures: List<Map<String, Double>>,
        network: ResidualPolicyWeights,
    ): List<Double> =
        residuals(
            hidden(indexed(stateFeatures, network.stateIndices, network.kind), network),
            actionFeatures.map { indexed(it, network.actionIndices, network.kind) },
            network,
        )

    /** Both models use the same validated dictionary, so sparse indices need only one sort. */
    fun differences(
        stateFeatures: Map<String, Double>,
        actionFeatures: List<Map<String, Double>>,
        current: ResidualPolicyWeights,
        original: ResidualPolicyWeights,
    ): List<Double> {
        val state = indexed(stateFeatures, current.stateIndices, current.kind)
        val actions = actionFeatures.map { indexed(it, current.actionIndices, current.kind) }
        if (current === original) return List(actionFeatures.size) { 0.0 }
        val currentScores = residuals(hidden(state, current), actions, current)
        val originalScores = residuals(hidden(state, original), actions, original)
        return currentScores.zip(originalScores, Double::minus)
    }

    private fun hidden(state: Sparse, network: ResidualPolicyWeights): DoubleArray =
        DoubleArray(network.hiddenSize) { row ->
            tanh(
                network.stateBias[row] + dot(network.stateWeights, row, network.stateColumns, state)
            )
        }

    private fun residuals(
        hidden: DoubleArray,
        actionFeatures: List<Sparse>,
        network: ResidualPolicyWeights,
    ): List<Double> = actionFeatures.map { features ->
        network.actorWeights.indices.sumOf { row ->
            network.actorWeights[row] *
                tanh(
                    hidden[row] +
                        network.actionBias[row] +
                        dot(network.actionWeights, row, network.actionColumns, features)
                )
        }
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

    private class Sparse(val columns: IntArray, val values: DoubleArray)

    private fun indexed(
        features: Map<String, Double>,
        indices: Map<String, Int>,
        kind: String,
    ): Sparse {
        val ordered =
            features
                .map { (name, value) ->
                    checkNotNull(indices[name]) { "Unknown $kind feature: $name." } to value
                }
                .sortedBy { it.first }
        return Sparse(
            IntArray(ordered.size) { ordered[it].first },
            DoubleArray(ordered.size) { ordered[it].second },
        )
    }

    private fun dot(
        weights: DoubleArray,
        row: Int,
        columns: Int,
        features: Sparse,
    ): Double {
        val offset = row * columns
        var sum = 0.0
        for (index in features.columns.indices) {
            sum += weights[offset + features.columns[index]] * features.values[index]
        }
        return sum
    }
}
