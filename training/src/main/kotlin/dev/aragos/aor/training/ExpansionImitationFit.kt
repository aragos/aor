package dev.aragos.aor.training

import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.sqrt

/** One actor-visible chosen action. Entry is provenance, never a learned feature. */
internal data class ExpansionImitationExample(
    val entry: Int,
    val observation: BotObservation,
    val selectedCommand: String,
) {
    init {
        require(observation.decision == "expand" && observation.candidates.size > 1) {
            "Expansion imitation requires a nontrivial expansion choice."
        }
        require(observation.candidates.any { it.command == selectedCommand }) {
            "The human expansion command must be offered."
        }
    }

    val selected: BotCandidate = observation.candidates.single { it.command == selectedCommand }
    val alternatives: List<BotCandidate> =
        observation.candidates.filter { it.command != selectedCommand }
}

/** Immutable pilot protocol; fitting cannot enlarge the dictionary or adapt hyperparameters. */
internal object ExpansionImitationProtocol {
    const val VERSION = "expansion-imitation-1"
    val features =
        listOf(
            "tokens",
            "progress",
            "reach",
            "commodity",
            "income",
            "risk",
            "action.expansion",
            "action.pass",
        )
    val regularizations = listOf(0.1, 1.0, 10.0)
    const val TRAIN = 30
    const val VALIDATION = 10
    const val AUDIT = 10
    const val MAX_ITERATIONS = 100
    const val POLICY_SEED = 0

    fun preservesOtherWeights(baseline: BotModel, model: BotModel): Boolean =
        (baseline.weights.keys + model.weights.keys).all { name ->
            name in features || (baseline.weights[name] ?: 0.0) == (model.weights[name] ?: 0.0)
        }
}

/** Scales and score temperature are weighted RMS contrasts from training decisions only. */
internal data class ExpansionImitationScale(val features: List<Double>, val temperature: Double) {
    init {
        require(
            features.size == ExpansionImitationProtocol.features.size &&
                features.all { it.isFinite() && it >= 1.0 } &&
                temperature.isFinite() &&
                temperature >= 1.0
        ) {
            "Imitation scales must have the fixed width and finite values of at least one."
        }
    }

    companion object {
        fun from(
            examples: List<ExpansionImitationExample>,
            baseline: BotModel,
        ): ExpansionImitationScale {
            require(examples.isNotEmpty()) {
                "Imitation normalization requires training decisions."
            }
            val squares = DoubleArray(ExpansionImitationProtocol.features.size)
            var scoreSquares = 0.0
            for (example in examples) {
                val weight = 1.0 / examples.size / example.alternatives.size
                for (other in example.alternatives) {
                    ExpansionImitationProtocol.features.forEachIndexed { index, name ->
                        val difference =
                            (example.selected.features[name] ?: 0.0) - (other.features[name] ?: 0.0)
                        squares[index] += weight * difference * difference
                    }
                    val margin =
                        BotPolicy.score(example.selected, baseline) -
                            BotPolicy.score(other, baseline)
                    scoreSquares += weight * margin * margin
                }
            }
            return ExpansionImitationScale(
                squares.map { sqrt(it).coerceAtLeast(1.0) },
                sqrt(scoreSquares).coerceAtLeast(1.0),
            )
        }
    }
}

/** Each root contributes equal total weight, irrespective of its offered action count. */
internal data class ExpansionImitationContrast(
    val features: List<Double>,
    val offset: Double,
    val weight: Double,
)

internal data class ExpansionImitationObjective(
    val loss: Double,
    val gradient: List<Double>,
    val hessian: List<List<Double>>,
)

internal data class ExpansionImitationFit(
    val coefficients: List<Double>,
    val iterations: Int,
    val objective: Double,
    val maximumGradient: Double,
)

/** Small convex F1-anchored logistic fit with deterministic damped Newton steps. */
internal object ExpansionImitationOptimizer {
    fun contrasts(
        examples: List<ExpansionImitationExample>,
        baseline: BotModel,
        scale: ExpansionImitationScale,
    ): List<ExpansionImitationContrast> {
        require(examples.isNotEmpty()) { "Imitation contrasts require decisions." }
        return examples.flatMap { example ->
            example.alternatives.map { other ->
                ExpansionImitationContrast(
                    ExpansionImitationProtocol.features.mapIndexed { index, name ->
                        ((example.selected.features[name] ?: 0.0) - (other.features[name] ?: 0.0)) /
                            scale.features[index]
                    },
                    (BotPolicy.score(example.selected, baseline) -
                        BotPolicy.score(other, baseline)) / scale.temperature,
                    1.0 / examples.size / example.alternatives.size,
                )
            }
        }
    }

    fun objective(
        examples: List<ExpansionImitationContrast>,
        coefficients: List<Double>,
        regularization: Double,
    ): ExpansionImitationObjective {
        require(
            examples.isNotEmpty() &&
                coefficients.size == ExpansionImitationProtocol.features.size &&
                coefficients.all(Double::isFinite) &&
                regularization.isFinite() &&
                regularization >= 0.0
        ) {
            "Imitation objective requires finite fixed-width coefficients and nonnegative regularization."
        }
        val width = coefficients.size
        val gradient = DoubleArray(width) { regularization * coefficients[it] }
        val hessian =
            Array(width) { row -> DoubleArray(width) { if (it == row) regularization else 0.0 } }
        var loss = regularization * coefficients.sumOf { it * it } / 2.0
        for (example in examples) {
            val margin =
                example.offset +
                    coefficients.indices.sumOf { coefficients[it] * example.features[it] }
            loss +=
                example.weight *
                    (if (margin >= 0) ln1p(exp(-margin)) else -margin + ln1p(exp(margin)))
            val miss =
                if (margin >= 0) exp(-margin) / (1 + exp(-margin)) else 1.0 / (1 + exp(margin))
            for (row in 0 until width) {
                gradient[row] -= example.weight * miss * example.features[row]
                for (column in 0 until width) {
                    hessian[row][column] +=
                        example.weight *
                            miss *
                            (1 - miss) *
                            example.features[row] *
                            example.features[column]
                }
            }
        }
        check(loss.isFinite() && gradient.all(Double::isFinite)) {
            "Imitation objective must remain finite."
        }
        return ExpansionImitationObjective(
            loss,
            gradient.toList(),
            hessian.map(DoubleArray::toList),
        )
    }

    fun fit(
        examples: List<ExpansionImitationContrast>,
        regularization: Double,
        deadlineNanos: Long,
    ): ExpansionImitationFit {
        require(regularization in ExpansionImitationProtocol.regularizations) {
            "Imitation permits only the three predeclared regularizations."
        }
        var coefficients = List(ExpansionImitationProtocol.features.size) { 0.0 }
        for (iteration in 0 until ExpansionImitationProtocol.MAX_ITERATIONS) {
            checkTokenValueDeadline(deadlineNanos)
            val value = objective(examples, coefficients, regularization)
            val maximumGradient = value.gradient.maxOf(::abs)
            if (maximumGradient < 1e-8) {
                return ExpansionImitationFit(coefficients, iteration, value.loss, maximumGradient)
            }
            val direction = solve(value.hessian, value.gradient)
            val slope = direction.indices.sumOf { direction[it] * value.gradient[it] }
            var step = 1.0
            var accepted: List<Double>? = null
            repeat(40) {
                if (accepted == null) {
                    val next = coefficients.indices.map { coefficients[it] - step * direction[it] }
                    if (
                        objective(examples, next, regularization).loss <=
                            value.loss - 1e-4 * step * slope
                    ) {
                        accepted = next
                    } else step *= 0.5
                }
            }
            coefficients =
                checkNotNull(accepted) {
                    "Imitation Newton line search failed; no retry is permitted."
                }
        }
        error("Imitation optimization did not converge within its fixed iteration bound.")
    }

    fun model(
        baseline: BotModel,
        scale: ExpansionImitationScale,
        fit: ExpansionImitationFit,
    ): BotModel =
        baseline.copy(
            weights =
                baseline.weights +
                    ExpansionImitationProtocol.features.mapIndexed { index, name ->
                        name to
                            ((baseline.weights[name] ?: 0.0) +
                                scale.temperature * fit.coefficients[index] / scale.features[index])
                    }
        )

    private fun solve(matrix: List<List<Double>>, target: List<Double>): List<Double> {
        val width = target.size
        val lower = Array(width) { DoubleArray(width) }
        for (row in 0 until width) {
            for (column in 0..row) {
                val value =
                    matrix[row][column] -
                        (0 until column).sumOf { lower[row][it] * lower[column][it] }
                lower[row][column] =
                    if (row == column) {
                        check(value.isFinite() && value > 0) {
                            "Imitation Hessian must be positive definite."
                        }
                        sqrt(value)
                    } else value / lower[column][column]
            }
        }
        val result = DoubleArray(width)
        for (row in 0 until width) {
            result[row] =
                (target[row] - (0 until row).sumOf { lower[row][it] * result[it] }) /
                    lower[row][row]
        }
        for (row in width - 1 downTo 0) {
            result[row] =
                (result[row] - (row + 1 until width).sumOf { lower[it][row] * result[it] }) /
                    lower[row][row]
        }
        return result.toList()
    }

    fun commands(examples: List<ExpansionImitationExample>, model: BotModel): List<String> =
        examples.map {
            BotPolicy.choose(
                it.observation,
                BotDifficulty.HARD,
                ExpansionImitationProtocol.POLICY_SEED,
                model,
            )
        }
}
