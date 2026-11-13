package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.ln

/** Two distinct wire contracts; neither changes the existing card or purchase actor. */
enum class TerritoryPhase(
    val decision: String,
    val encoderVersion: String,
    val actorVersion: String,
    val maxActions: Int,
    internal val salt: Int,
) {
    TOKEN("buy-tokens", "aor-token-observation-1", "aor-token-policy-1", 128, 0x510e527f),
    EXPANSION(
        "expand",
        "aor-expansion-observation-1",
        "aor-expansion-policy-1",
        1024,
        0x9b05688c.toInt(),
    );

    companion object {
        fun fromEncoder(version: String): TerritoryPhase =
            TerritoryEncoder.fromVersion(version).phase
    }
}

/** Input versions are independent of phase; schema 2 explicitly opts into expansion-v2 sampling. */
enum class TerritoryEncoder(val version: String, val phase: TerritoryPhase) {
    TOKEN_1(TerritoryPhase.TOKEN.encoderVersion, TerritoryPhase.TOKEN),
    TOKEN_2("aor-token-observation-2", TerritoryPhase.TOKEN),
    EXPANSION_1(TerritoryPhase.EXPANSION.encoderVersion, TerritoryPhase.EXPANSION),
    EXPANSION_2("aor-expansion-observation-2", TerritoryPhase.EXPANSION);

    companion object {
        fun fromVersion(version: String): TerritoryEncoder =
            requireNotNull(entries.singleOrNull { it.version == version }) {
                "Unsupported territory observation encoder: $version."
            }
    }
}

/** Complete sorted vocabulary, frozen from the public catalog before collecting trajectories. */
data class TerritoryFeatureDictionary(
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
        "Territory feature dictionaries require bounded, unique, sorted names."
    }
    require(names.all { it.length in 1..160 }) { "Territory feature names must be bounded." }
}

private fun requireFeatures(features: Map<String, Double>) {
    require(
        features.size <= 8192 &&
            features.all { (name, value) ->
                name.length in 1..160 && value.isFinite() && value in -16.0..16.0
            }
    ) {
        "Territory observations require bounded named features in [-16,16]."
    }
}

internal fun requireTerritoryProjection(
    encoderVersion: String,
    stateFeatures: Map<String, Double>,
    actions: List<TerritoryAction>,
) {
    val phase = TerritoryPhase.fromEncoder(encoderVersion)
    requireFeatures(stateFeatures)
    val commands = actions.map(TerritoryAction::command)
    require(actions.size in 1..phase.maxActions && commands.distinct().size == actions.size) {
        "A territory observation requires a bounded complete set of distinct offered actions."
    }
    require(commands == commands.sorted()) {
        "Territory actions must use canonical command order."
    }
}

/** One actual offered command and its raw quote/identity features, never a macro sequence. */
data class TerritoryAction(val command: String, val features: Map<String, Double>) {
    init {
        require(command.isNotBlank() && command.length <= 200 && '\n' !in command) {
            "A territory action requires a bounded canonical command."
        }
        requireFeatures(features)
    }
}

/** Safe sparse input; [baselineIndex] identifies the separately frozen qualified HARD choice. */
data class TerritoryObservation(
    val encoderVersion: String,
    val stateFeatures: Map<String, Double>,
    val actions: List<TerritoryAction>,
    val baselineIndex: Int,
) {
    init {
        requireTerritoryProjection(encoderVersion, stateFeatures, actions)
        require(baselineIndex in actions.indices) {
            "The territory baseline must be an offered action."
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
data class TerritoryPolicyModel(
    val schemaVersion: Int = 1,
    val encoderVersion: String,
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
            schemaVersion == 1 &&
                TerritoryPhase.entries.any { it.encoderVersion == encoderVersion } ||
                schemaVersion == 2 && encoderVersion == TerritoryEncoder.EXPANSION_2.version
        ) {
            "Unsupported territory policy schema or encoder."
        }
        require(hiddenSize == 64 && baselineProbability == 0.97) {
            "Territory policies require 64 hidden units and a .97 baseline prior."
        }
        requireNames(stateFeatureNames, 8192)
        requireNames(actionFeatureNames, 512)
        require(
            stateWeights.size == hiddenSize * stateFeatureNames.size &&
                actionWeights.size == hiddenSize * actionFeatureNames.size
        ) {
            "Territory matrix dimensions must match their frozen dictionaries."
        }
        require(
            listOf(stateBias, actionBias, actorWeights, valueWeights).all { it.size == hiddenSize }
        ) {
            "Territory hidden vectors must contain 64 values."
        }
        require(
            listOf(stateWeights, stateBias, actionWeights, actionBias, actorWeights, valueWeights)
                .all { values -> values.all { it.isFinite() && abs(it) <= 1.0e6 } } &&
                valueBias.isFinite() &&
                abs(valueBias) <= 1.0e6
        ) {
            "Territory parameters must be finite and bounded."
        }
    }

    internal val stateIndices = stateFeatureNames.withIndex().associate { it.value to it.index }
    internal val actionIndices = actionFeatureNames.withIndex().associate { it.value to it.index }
    internal val network =
        ResidualPolicyWeights(
            "territory",
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

data class TerritoryPolicyEvaluation(
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
            "Territory evaluation requires matching finite logits and probabilities."
        }
        require(
            probabilities.all { it.isFinite() && it > 0.0 && it <= 1.0 } &&
                abs(probabilities.sum() - 1.0) < 1.0e-9 &&
                value.isFinite() &&
                value in 0.0..1.0
        ) {
            "Territory probabilities must have full support and unit mass; value must be in [0,1]."
        }
    }
}

/** Log probability is natural-log probability under the exact sampled residual policy. */
data class TerritoryPolicyChoice(
    val index: Int,
    val command: String,
    val logProbability: Double,
    val value: Double,
)

/** Experimental token/expansion inference; existing BotPolicy behavior is deliberately separate. */
object TerritoryPolicy {
    /**
     * Separate territory sampling from the ordinary bot's decision seed. This salt is the exact
     * collection contract; neither seed nor the resulting random draw is a model feature.
     */
    fun actorSeed(phase: TerritoryPhase, botSeed: Int): Int = botSeed xor phase.salt

    fun evaluate(
        observation: TerritoryObservation,
        model: TerritoryPolicyModel,
    ): TerritoryPolicyEvaluation {
        require(observation.encoderVersion == model.encoderVersion) {
            "Territory encoder identities must match."
        }
        val result =
            ResidualPolicyMath.evaluate(
                observation.stateFeatures,
                observation.actions.map(TerritoryAction::features),
                observation.baselineIndex,
                model.network,
            )
        return TerritoryPolicyEvaluation(result.logits, result.probabilities, result.value)
    }

    /** [uniform] is an actor random draw in [0,1), never a draw from hidden world outcomes. */
    fun choose(
        observation: TerritoryObservation,
        model: TerritoryPolicyModel,
        uniform: Double,
    ): TerritoryPolicyChoice = select(observation, evaluate(observation, model), uniform)

    /** Sample an already evaluated observation without repeating its shared state projection. */
    fun select(
        observation: TerritoryObservation,
        evaluation: TerritoryPolicyEvaluation,
        uniform: Double,
    ): TerritoryPolicyChoice {
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "Territory sampling requires a finite uniform draw in [0,1)."
        }
        require(evaluation.probabilities.size == observation.actions.size) {
            "Territory sampling requires one probability per offered action."
        }
        val selected = ResidualPolicyMath.select(evaluation.probabilities, uniform)
        return TerritoryPolicyChoice(
            selected,
            observation.actions[selected].command,
            ln(evaluation.probabilities[selected]),
            evaluation.value,
        )
    }

    fun choose(
        observation: TerritoryObservation,
        model: TerritoryPolicyModel,
        seed: Int,
    ): TerritoryPolicyChoice = choose(observation, model, uniform(seed))

    /** Versioned 32-bit integer mixer, mapped through unsigned arithmetic to [0,1). */
    fun uniform(seed: Int): Double = ResidualPolicyMath.uniform(seed)
}
