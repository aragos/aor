package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.util.concurrent.CancellationException
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * One observation-only training row. [features] use the pilot's normalized feature order; [values]
 * contain expected terminal rewards in [CommodityExperimentStrategy] order, each from zero to one.
 */
data class PilotExample(val features: List<Double>, val values: List<Double>) {
    init {
        require(features.isNotEmpty() && features.all(Double::isFinite)) {
            "A pilot example requires nonempty finite features."
        }
        require(
            values.size == CommodityExperimentStrategy.entries.size &&
                values.all { it.isFinite() && it in 0.0..1.0 }
        ) {
            "A pilot example requires one finite reward per strategy between zero and one."
        }
    }
}

/**
 * Compact experiment-only regressor with one 24-unit tanh hidden layer and one output per strategy.
 * Weight rows correspond to destination units. Predictions are reward estimates, not calibrated
 * probabilities, and can extend outside zero to one. All inputs must use the training feature
 * order.
 */
data class PilotStudent(
    val inputWeights: List<List<Double>>,
    val hiddenBias: List<Double>,
    val outputWeights: List<List<Double>>,
    val outputBias: List<Double>,
) {
    init {
        require(inputWeights.size == HIDDEN_UNITS && hiddenBias.size == HIDDEN_UNITS) {
            "A pilot student requires 24 hidden units and biases."
        }
        val width = inputWeights.first().size
        require(width in 1..MAX_FEATURES && inputWeights.all { it.size == width }) {
            "Pilot input weights must have a consistent width between 1 and $MAX_FEATURES."
        }
        require(outputWeights.size == planCount && outputBias.size == planCount) {
            "A pilot student requires $planCount output units and biases, including the baseline."
        }
        require(outputWeights.all { it.size == HIDDEN_UNITS }) {
            "Every pilot output must connect to all 24 hidden units."
        }
        require(
            inputWeights.all { it.all(Double::isFinite) } &&
                outputWeights.all { it.all(Double::isFinite) } &&
                hiddenBias.all(Double::isFinite) &&
                outputBias.all(Double::isFinite)
        ) {
            "Pilot student weights and biases must be finite."
        }
    }

    fun predict(features: List<Double>): List<Double> {
        require(features.size == inputWeights.first().size && features.all(Double::isFinite)) {
            "Pilot inference requires finite features matching the trained input dimension."
        }
        val hidden = inputWeights.mapIndexed { unit, weights ->
            tanh(hiddenBias[unit] + weights.indices.sumOf { weights[it] * features[it] })
        }
        return outputWeights.mapIndexed { plan, weights ->
            outputBias[plan] + weights.indices.sumOf { weights[it] * hidden[it] }
        }
    }

    /**
     * Retain baseline behavior unless a plan's predicted reward exceeds it by [minimumAdvantage].
     * The finite margin is in composite terminal-reward units, from zero to one, rather than win
     * probability. Ties retain the baseline; ties between qualifying alternatives use enum order.
     */
    fun choose(
        features: List<Double>,
        minimumAdvantage: Double = DEFAULT_MINIMUM_ADVANTAGE,
    ): Int {
        require(minimumAdvantage.isFinite() && minimumAdvantage in 0.0..1.0) {
            "The minimum predicted advantage must be finite and between zero and one."
        }
        val predictions = predict(features)
        val best = predictions.indices.maxBy { predictions[it] }
        val baseline = CommodityExperimentStrategy.KEEP_BASELINE.ordinal
        return if (predictions[best] > predictions[baseline] + minimumAdvantage) {
            best
        } else {
            baseline
        }
    }

    companion object {
        private const val HIDDEN_UNITS = 24
        private const val MAX_FEATURES = 512
        private val planCount = CommodityExperimentStrategy.entries.size

        /** Fixed pilot threshold in terminal composite-reward units; not fitted on validation. */
        const val DEFAULT_MINIMUM_ADVANTAGE = 0.02

        /**
         * Fit every sampled strategy reward with squared-error SGD. [seed] controls initialization
         * and example order; [epochs] counts complete passes over the supplied training rows.
         * [deadlineNanos] is an absolute System.nanoTime deadline checked between examples. Local
         * arrays are mutable optimizer state and are copied into the returned portable model.
         */
        fun fit(
            examples: List<PilotExample>,
            seed: Int,
            epochs: Int = 400,
            deadlineNanos: Long = Long.MAX_VALUE,
        ): PilotStudent {
            require(examples.isNotEmpty()) {
                "Pilot fitting requires at least one training example."
            }
            require(epochs in 1..10000) { "Pilot fitting requires between 1 and 10000 epochs." }
            fun checkDeadline() {
                if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
                    throw CancellationException(
                        "Pilot fitting reached its deadline or was canceled."
                    )
                }
            }
            checkDeadline()
            val width = examples.first().features.size
            require(width in 1..MAX_FEATURES && examples.all { it.features.size == width }) {
                "Pilot training rows must share an input dimension between 1 and $MAX_FEATURES."
            }
            val random = Random(seed)
            val inputScale = sqrt(6.0 / (width + HIDDEN_UNITS))
            val outputScale = sqrt(6.0 / (HIDDEN_UNITS + planCount))
            val input =
                Array(HIDDEN_UNITS) {
                    DoubleArray(width) { random.nextDouble(-inputScale, inputScale) }
                }
            val hiddenBias = DoubleArray(HIDDEN_UNITS)
            val output =
                Array(planCount) {
                    DoubleArray(HIDDEN_UNITS) { random.nextDouble(-outputScale, outputScale) }
                }
            val outputBias =
                DoubleArray(planCount) { plan ->
                    examples.map { it.values[plan] }.average()
                }
            val hidden = DoubleArray(HIDDEN_UNITS)
            val outputGradient = DoubleArray(planCount)
            val hiddenGradient = DoubleArray(HIDDEN_UNITS)
            repeat(epochs) { epoch ->
                checkDeadline()
                val rate = 0.03 / sqrt(1.0 + epoch / 100.0)
                for (row in examples.shuffled(random)) {
                    checkDeadline()
                    for (unit in hidden.indices) {
                        hidden[unit] =
                            tanh(
                                hiddenBias[unit] +
                                    (0 until width).sumOf { feature ->
                                        input[unit][feature] * row.features[feature]
                                    }
                            )
                    }
                    for (plan in output.indices) {
                        val prediction =
                            outputBias[plan] +
                                hidden.indices.sumOf { unit -> output[plan][unit] * hidden[unit] }
                        outputGradient[plan] = 2.0 * (prediction - row.values[plan]) / planCount
                    }
                    for (unit in hidden.indices) {
                        hiddenGradient[unit] =
                            (1.0 - hidden[unit] * hidden[unit]) *
                                output.indices.sumOf { plan ->
                                    outputGradient[plan] * output[plan][unit]
                                }
                    }
                    for (plan in output.indices) {
                        outputBias[plan] -= rate * outputGradient[plan]
                        for (unit in hidden.indices) {
                            output[plan][unit] -= rate * outputGradient[plan] * hidden[unit]
                        }
                    }
                    for (unit in hidden.indices) {
                        hiddenBias[unit] -= rate * hiddenGradient[unit]
                        for (feature in 0 until width) {
                            input[unit][feature] -=
                                rate * hiddenGradient[unit] * row.features[feature]
                        }
                    }
                }
            }
            return PilotStudent(
                inputWeights = input.map(DoubleArray::toList),
                hiddenBias = hiddenBias.toList(),
                outputWeights = output.map(DoubleArray::toList),
                outputBias = outputBias.toList(),
            )
        }

        /** Read a model artifact, rejecting missing, nonnumeric or dimensionally invalid data. */
        fun fromJson(node: JsonNode): PilotStudent {
            require(node.isObject) { "A pilot student artifact must be an object." }
            fun vector(value: JsonNode?, name: String): List<Double> {
                require(value != null && value.isArray) { "$name must be a numerical array." }
                return value.map { number ->
                    require(number.isNumber && number.doubleValue().isFinite()) {
                        "$name must contain only finite numbers."
                    }
                    number.doubleValue()
                }
            }
            fun matrix(name: String): List<List<Double>> {
                val value = node[name]
                require(value != null && value.isArray) { "$name must be a numerical matrix." }
                return value.map { row -> vector(row, "$name row") }
            }
            return PilotStudent(
                inputWeights = matrix("inputWeights"),
                hiddenBias = vector(node["hiddenBias"], "hiddenBias"),
                outputWeights = matrix("outputWeights"),
                outputBias = vector(node["outputBias"], "outputBias"),
            )
        }
    }
}
