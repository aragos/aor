package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.ln

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
    internal val network =
        ResidualPolicyWeights(
            "purchase",
            hiddenSize,
            baselineProbability,
            stateFeatureNames.size,
            actionFeatureNames.size,
            stateIndices,
            actionIndices,
            stateWeights,
            stateBias,
            actionWeights,
            actionBias,
            actorWeights,
            valueWeights,
            valueBias,
        )
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
        val result =
            ResidualPolicyMath.evaluate(
                observation.stateFeatures,
                observation.actions.map(PurchaseAction::features),
                observation.baselineIndex,
                model.network,
            )
        return PurchasePolicyEvaluation(result.logits, result.probabilities, result.value)
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
        val selected = ResidualPolicyMath.select(evaluation.probabilities, uniform)
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
    fun uniform(seed: Int): Double = ResidualPolicyMath.uniform(seed)
}
