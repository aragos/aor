package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.ln

/** Complete sorted vocabulary, frozen from the public catalog before collecting trajectories. */
data class CardFeatureDictionary(
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
        "Card feature dictionaries require bounded, unique, sorted names."
    }
    require(names.all { it.length in 1..160 }) { "Card feature names must be bounded." }
}

private fun requireFeatures(features: Map<String, Double>) {
    require(
        features.size <= 8192 &&
            features.all { (name, value) ->
                name.length in 1..160 && value.isFinite() && value in -16.0..16.0
            }
    ) {
        "Card observations require bounded named features in [-16,16]."
    }
}

internal fun requireCardProjection(
    encoderVersion: String,
    stateFeatures: Map<String, Double>,
    actions: List<CardAction>,
) {
    require(encoderVersion == CardObservationEncoder.ENCODER_VERSION) {
        "Unsupported card observation encoder version."
    }
    requireFeatures(stateFeatures)
    val commands = actions.map(CardAction::command)
    require(actions.size in 1..1024 && commands.distinct().size == actions.size) {
        "A card observation requires one through 1024 distinct offered actions."
    }
    require(commands == commands.sorted()) {
        "Card actions must use canonical command order."
    }
}

/** One actual offered command and its raw quote/identity features, never a macro sequence. */
data class CardAction(val command: String, val features: Map<String, Double>) {
    init {
        require(
            command.isNotBlank() && command.length <= 2000 && '\r' !in command && '\n' !in command
        ) {
            "A card action requires a bounded canonical command."
        }
        requireFeatures(features)
    }
}

/** Safe sparse input; [baselineIndex] identifies the separately frozen qualified HARD choice. */
data class CardObservation(
    val encoderVersion: String = CardObservationEncoder.ENCODER_VERSION,
    val stateFeatures: Map<String, Double>,
    val actions: List<CardAction>,
    val baselineIndex: Int,
) {
    init {
        requireCardProjection(encoderVersion, stateFeatures, actions)
        require(baselineIndex in actions.indices) {
            "The card baseline must be an offered action."
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
data class CardPolicyModel(
    val schemaVersion: Int = 1,
    val encoderVersion: String = CardObservationEncoder.ENCODER_VERSION,
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
        require(schemaVersion == 1 && encoderVersion == CardObservationEncoder.ENCODER_VERSION) {
            "Unsupported card policy schema or encoder."
        }
        require(hiddenSize == 64 && baselineProbability == 0.97) {
            "Card policy version 1 requires 64 hidden units and a .97 baseline prior."
        }
        requireNames(stateFeatureNames, 8192)
        requireNames(actionFeatureNames, 512)
        require(
            stateWeights.size == hiddenSize * stateFeatureNames.size &&
                actionWeights.size == hiddenSize * actionFeatureNames.size
        ) {
            "Card matrix dimensions must match their frozen dictionaries."
        }
        require(
            listOf(stateBias, actionBias, actorWeights, valueWeights).all { it.size == hiddenSize }
        ) {
            "Card hidden vectors must contain 64 values."
        }
        require(
            listOf(stateWeights, stateBias, actionWeights, actionBias, actorWeights, valueWeights)
                .all { values -> values.all { it.isFinite() && abs(it) <= 1.0e6 } } &&
                valueBias.isFinite() &&
                abs(valueBias) <= 1.0e6
        ) {
            "Card parameters must be finite and bounded."
        }
    }

    internal val stateIndices = stateFeatureNames.withIndex().associate { it.value to it.index }
    internal val actionIndices = actionFeatureNames.withIndex().associate { it.value to it.index }
    internal val network =
        ResidualPolicyWeights(
            "card",
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

data class CardPolicyEvaluation(
    val logits: List<Double>,
    val probabilities: List<Double>,
    val value: Double,
) {
    init {
        require(
            logits.size in 1..1024 &&
                logits.size == probabilities.size &&
                logits.all(Double::isFinite)
        ) {
            "Card evaluation requires matching finite logits and probabilities."
        }
        require(
            probabilities.all { it.isFinite() && it > 0.0 && it <= 1.0 } &&
                abs(probabilities.sum() - 1.0) < 1.0e-9 &&
                value.isFinite() &&
                value in 0.0..1.0
        ) {
            "Card probabilities must have full support and unit mass; value must be in [0,1]."
        }
    }
}

/** Log probability is natural-log probability under the exact sampled residual policy. */
data class CardPolicyChoice(
    val index: Int,
    val command: String,
    val logProbability: Double,
    val value: Double,
)

/** Experimental card-only inference; existing BotPolicy behavior is deliberately separate. */
object CardPolicy {
    const val ACTOR_VERSION = "aor-card-policy-1"

    /**
     * Separate card sampling from the ordinary bot's decision seed. This salt is the exact
     * collection contract; neither seed nor the resulting random draw is a model feature.
     */
    fun actorSeed(botSeed: Int): Int = botSeed xor 0x3c6ef372

    fun evaluate(
        observation: CardObservation,
        model: CardPolicyModel,
    ): CardPolicyEvaluation {
        require(observation.encoderVersion == model.encoderVersion) {
            "Card encoder identities must match."
        }
        val result =
            ResidualPolicyMath.evaluate(
                observation.stateFeatures,
                observation.actions.map(CardAction::features),
                observation.baselineIndex,
                model.network,
            )
        return CardPolicyEvaluation(result.logits, result.probabilities, result.value)
    }

    /** [uniform] is an actor random draw in [0,1), never a draw from hidden world outcomes. */
    fun choose(
        observation: CardObservation,
        model: CardPolicyModel,
        uniform: Double,
    ): CardPolicyChoice = select(observation, evaluate(observation, model), uniform)

    /** Sample an already evaluated observation without repeating its shared state projection. */
    fun select(
        observation: CardObservation,
        evaluation: CardPolicyEvaluation,
        uniform: Double,
    ): CardPolicyChoice {
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "Card sampling requires a finite uniform draw in [0,1)."
        }
        require(evaluation.probabilities.size == observation.actions.size) {
            "Card sampling requires one probability per offered action."
        }
        val selected = ResidualPolicyMath.select(evaluation.probabilities, uniform)
        return CardPolicyChoice(
            selected,
            observation.actions[selected].command,
            ln(evaluation.probabilities[selected]),
            evaluation.value,
        )
    }

    fun choose(
        observation: CardObservation,
        model: CardPolicyModel,
        seed: Int,
    ): CardPolicyChoice = choose(observation, model, uniform(seed))

    /** Versioned 32-bit integer mixer, mapped through unsigned arithmetic to [0,1). */
    fun uniform(seed: Int): Double = ResidualPolicyMath.uniform(seed)
}
