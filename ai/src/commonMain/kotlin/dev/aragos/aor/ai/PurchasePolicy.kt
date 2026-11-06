package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/** Complete sorted vocabulary, frozen from the public catalog before collecting trajectories. */
data class PurchaseFeatureDictionary(
    val stateFeatureNames: List<String>,
    val actionFeatureNames: List<String>,
) {
    init {
        requireNames(stateFeatureNames, 8192)
        requireNames(actionFeatureNames, 512)
    }
}

private fun requireNames(names: List<String>, maximum: Int) {
    require(names.size in 1..maximum && names == names.distinct().sorted()) {
        "Purchase feature dictionaries require bounded, unique, sorted names."
    }
    require(names.all { it.length in 1..160 }) { "Purchase feature names must be bounded." }
}

private fun requireFeatures(features: Map<String, Double>) {
    require(
        features.size <= 8192 &&
            features.all { (name, value) ->
                name.length in 1..160 && value.isFinite() && value in -16.0..16.0
            }
    ) {
        "Purchase observations require bounded named features in [-16,16]."
    }
}

internal fun requirePurchaseProjection(
    encoderVersion: String,
    stateFeatures: Map<String, Double>,
    actions: List<PurchaseAction>,
) {
    require(encoderVersion == PurchaseObservationEncoder.ENCODER_VERSION) {
        "Unsupported purchase observation encoder version."
    }
    requireFeatures(stateFeatures)
    val commands = actions.map(PurchaseAction::command)
    require(actions.size in 1..128 && commands.distinct().size == actions.size) {
        "A purchase observation requires one through 128 distinct offered actions."
    }
    require(commands == commands.sorted()) {
        "Purchase actions must use canonical command order."
    }
}

/** One actual offered command and its raw quote/identity features, never a macro sequence. */
data class PurchaseAction(val command: String, val features: Map<String, Double>) {
    init {
        require(command.isNotBlank() && command.length <= 200 && '\n' !in command) {
            "A purchase action requires a bounded canonical command."
        }
        requireFeatures(features)
    }
}

/** Safe sparse input; [baselineIndex] identifies the separately frozen qualified HARD choice. */
data class PurchaseObservation(
    val encoderVersion: String = PurchaseObservationEncoder.ENCODER_VERSION,
    val stateFeatures: Map<String, Double>,
    val actions: List<PurchaseAction>,
    val baselineIndex: Int,
) {
    init {
        requirePurchaseProjection(encoderVersion, stateFeatures, actions)
        require(baselineIndex in actions.indices) {
            "The purchase baseline must be an offered action."
        }
    }
}

/**
 * Portable Float64 network. Matrices are flat row-major arrays: 64 rows and dictionary-size
 * columns. Shared state h=tanh(b+Ws*x); action z=tanh(h+ba+Wa*a); residual=clamp(actor*z,-20,20).
 * The fixed action prior assigns .97 to the qualified recommendation and .03 to all others
 * together. The value sigmoid predicts terminal composite reward in [0,1]. No authored score
 * weights or hidden-state inputs enter this network. Unknown feature names are rejected.
 */
data class PurchasePolicyModel(
    val schemaVersion: Int = 1,
    val encoderVersion: String = PurchaseObservationEncoder.ENCODER_VERSION,
    val hiddenSize: Int = 64,
    val baselineProbability: Double = 0.97,
    val stateFeatureNames: List<String>,
    val actionFeatureNames: List<String>,
    val stateWeights: List<Double>,
    val stateBias: List<Double>,
    val actionWeights: List<Double>,
    val actionBias: List<Double>,
    val actorWeights: List<Double>,
    val valueWeights: List<Double>,
    val valueBias: Double,
) {
    init {
        require(
            schemaVersion == 1 && encoderVersion == PurchaseObservationEncoder.ENCODER_VERSION
        ) {
            "Unsupported purchase policy schema or encoder."
        }
        require(hiddenSize == 64 && baselineProbability == 0.97) {
            "Purchase policy version 1 requires 64 hidden units and a .97 baseline prior."
        }
        requireNames(stateFeatureNames, 8192)
        requireNames(actionFeatureNames, 512)
        require(
            stateWeights.size == hiddenSize * stateFeatureNames.size &&
                actionWeights.size == hiddenSize * actionFeatureNames.size
        ) {
            "Purchase matrix dimensions must match their frozen dictionaries."
        }
        require(
            listOf(stateBias, actionBias, actorWeights, valueWeights).all { it.size == hiddenSize }
        ) {
            "Purchase hidden vectors must contain 64 values."
        }
        require(
            listOf(stateWeights, stateBias, actionWeights, actionBias, actorWeights, valueWeights)
                .all { values -> values.all { it.isFinite() && abs(it) <= 1.0e6 } } &&
                valueBias.isFinite() &&
                abs(valueBias) <= 1.0e6
        ) {
            "Purchase parameters must be finite and bounded."
        }
    }

    internal val stateIndices = stateFeatureNames.withIndex().associate { it.value to it.index }
    internal val actionIndices = actionFeatureNames.withIndex().associate { it.value to it.index }
}

data class PurchasePolicyEvaluation(
    val logits: List<Double>,
    val probabilities: List<Double>,
    val value: Double,
) {
    init {
        require(
            logits.size in 1..128 &&
                logits.size == probabilities.size &&
                logits.all(Double::isFinite)
        ) {
            "Purchase evaluation requires matching finite logits and probabilities."
        }
        require(
            probabilities.all { it.isFinite() && it > 0.0 && it <= 1.0 } &&
                abs(probabilities.sum() - 1.0) < 1.0e-9 &&
                value.isFinite() &&
                value in 0.0..1.0
        ) {
            "Purchase probabilities must have full support and unit mass; value must be in [0,1]."
        }
    }
}

/** Log probability is natural-log probability under the exact sampled residual policy. */
data class PurchasePolicyChoice(
    val index: Int,
    val command: String,
    val logProbability: Double,
    val value: Double,
)

/** Experimental purchase-only inference; existing BotPolicy behavior is deliberately separate. */
object PurchasePolicy {
    const val ACTOR_VERSION = "aor-purchase-policy-1"

    /**
     * Separate purchase sampling from the ordinary bot's decision seed. This salt is the exact
     * collection contract; neither seed nor the resulting random draw is a model feature.
     */
    fun actorSeed(botSeed: Int): Int = botSeed xor 0x6a09e667

    fun evaluate(
        observation: PurchaseObservation,
        model: PurchasePolicyModel,
    ): PurchasePolicyEvaluation {
        require(observation.encoderVersion == model.encoderVersion) {
            "Purchase encoder identities must match."
        }
        val state = indexed(observation.stateFeatures, model.stateIndices)
        val hidden =
            List(model.hiddenSize) { row ->
                tanh(
                    model.stateBias[row] +
                        dot(model.stateWeights, row, model.stateFeatureNames.size, state)
                )
            }
        val residuals =
            observation.actions.map { action ->
                val features = indexed(action.features, model.actionIndices)
                model.actorWeights.indices
                    .sumOf { row ->
                        model.actorWeights[row] *
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
                    .coerceIn(-20.0, 20.0)
            }
        val prior =
            List(observation.actions.size) { index ->
                when {
                    observation.actions.size == 1 -> 1.0
                    index == observation.baselineIndex -> model.baselineProbability
                    else -> 0.03 / (observation.actions.size - 1)
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
            model.valueBias + hidden.indices.sumOf { model.valueWeights[it] * hidden[it] }
        val value =
            if (rawValue >= 0.0) 1.0 / (1.0 + exp(-rawValue))
            else {
                val exponential = exp(rawValue)
                exponential / (1.0 + exponential)
            }
        return PurchasePolicyEvaluation(logits, probabilities, value)
    }

    /** [uniform] is an actor random draw in [0,1), never a draw from hidden world outcomes. */
    fun choose(
        observation: PurchaseObservation,
        model: PurchasePolicyModel,
        uniform: Double,
    ): PurchasePolicyChoice = select(observation, evaluate(observation, model), uniform)

    /** Sample an already evaluated observation without repeating its shared state projection. */
    fun select(
        observation: PurchaseObservation,
        evaluation: PurchasePolicyEvaluation,
        uniform: Double,
    ): PurchasePolicyChoice {
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "Purchase sampling requires a finite uniform draw in [0,1)."
        }
        require(evaluation.probabilities.size == observation.actions.size) {
            "Purchase sampling requires one probability per offered action."
        }
        var cumulative = 0.0
        val selected =
            evaluation.probabilities
                .indexOfFirst { probability ->
                    cumulative += probability
                    uniform < cumulative
                }
                .let { if (it < 0) observation.actions.lastIndex else it }
        return PurchasePolicyChoice(
            selected,
            observation.actions[selected].command,
            ln(evaluation.probabilities[selected]),
            evaluation.value,
        )
    }

    fun choose(
        observation: PurchaseObservation,
        model: PurchasePolicyModel,
        seed: Int,
    ): PurchasePolicyChoice = choose(observation, model, uniform(seed))

    /** Versioned 32-bit integer mixer, mapped through unsigned arithmetic to [0,1). */
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
    ): List<Pair<Int, Double>> =
        features
            .map { (name, value) ->
                checkNotNull(indices[name]) { "Unknown purchase feature: $name." } to value
            }
            .sortedBy { it.first }

    private fun dot(
        weights: List<Double>,
        row: Int,
        columns: Int,
        features: List<Pair<Int, Double>>,
    ): Double = features.sumOf { (column, value) -> weights[row * columns + column] * value }
}
