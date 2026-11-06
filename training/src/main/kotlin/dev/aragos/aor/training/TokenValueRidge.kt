package dev.aragos.aor.training

import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/** One action-minus-baseline observation, with whole-root weighting and terminal reward delta. */
data class TokenValueExample(
    val features: List<Double>,
    val target: Double,
    val weight: Double = 1.0,
) {
    init {
        require(features.size in 1..128 && features.all(Double::isFinite)) {
            "A token-value example requires one through 128 finite features."
        }
        require(target.isFinite() && target in -1.0..1.0) {
            "A token-value target must be a paired reward difference between minus one and one."
        }
        require(weight.isFinite() && weight > 0.0) {
            "A token-value example requires a positive finite root weight."
        }
    }
}

/**
 * Deterministic ridge regression without an intercept. Fixed common inference normalizations are
 * retained. The objective is weighted mean squared error plus [regularization] times squared
 * coefficient length; positive regularization makes the small normal matrix positive definite.
 */
object TokenValueRidge {
    fun fit(
        examples: List<TokenValueExample>,
        regularization: Double,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): List<Double> {
        require(examples.isNotEmpty()) { "Token-value fitting requires training examples." }
        require(regularization.isFinite() && regularization > 0.0) {
            "Token-value regularization must be positive and finite."
        }
        val width = examples.first().features.size
        require(examples.all { it.features.size == width }) {
            "Token-value fitting requires a consistent feature width."
        }
        val matrix = Array(width) { DoubleArray(width) }
        val target = DoubleArray(width)
        val totalWeight = examples.sumOf(TokenValueExample::weight)
        require(totalWeight.isFinite()) { "Token-value training weights must have a finite sum." }
        for (example in examples) {
            checkTokenValueDeadline(deadlineNanos)
            val weight = example.weight / totalWeight
            for (row in 0 until width) {
                target[row] += weight * example.features[row] * example.target
                for (column in 0..row) {
                    matrix[row][column] += weight * example.features[row] * example.features[column]
                }
            }
        }
        val lower = Array(width) { DoubleArray(width) }
        for (row in 0 until width) {
            checkTokenValueDeadline(deadlineNanos)
            for (column in 0..row) {
                val value =
                    matrix[row][column] + (if (row == column) regularization else 0.0) -
                        (0 until column).sumOf { lower[row][it] * lower[column][it] }
                lower[row][column] =
                    if (row == column) {
                        check(value.isFinite() && value > 0.0) {
                            "Regularized token-value covariance must remain positive definite."
                        }
                        sqrt(value)
                    } else value / lower[column][column]
            }
        }
        val solution = DoubleArray(width)
        for (row in 0 until width) {
            solution[row] =
                (target[row] - (0 until row).sumOf { lower[row][it] * solution[it] }) /
                    lower[row][row]
        }
        for (row in width - 1 downTo 0) {
            solution[row] =
                (solution[row] - (row + 1 until width).sumOf { lower[it][row] * solution[it] }) /
                    lower[row][row]
        }
        check(solution.all(Double::isFinite)) { "Fitted token-value coefficients must be finite." }
        return solution.toList()
    }

    fun predict(coefficients: List<Double>, features: List<Double>): Double {
        require(coefficients.size == features.size) {
            "Token-value prediction requires the fitted feature width."
        }
        return coefficients.indices.sumOf { coefficients[it] * features[it] }
    }
}

internal fun checkTokenValueDeadline(deadlineNanos: Long) {
    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
        throw CancellationException("The token-value pilot reached its deadline or was canceled.")
    }
}
