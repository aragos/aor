package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * One Float64 action-value network. Matrices have 64 rows and dictionary-size columns in flat
 * row-major order. h=tanh(stateBias+Ws*x), z=tanh(h+actionBias+Wa*a), and
 * q=sigmoid(outputBias+outputWeights*z). There is no action prior or residual clipping.
 *
 * Training identifies differences q(s,a)-q(s,F), not the absolute q values. Despite its bounded
 * sigmoid output, q is not a calibrated probability of winning or a calibrated terminal value.
 */
data class CardAdvantageNetwork(
    val stateWeights: List<Double>,
    val stateBias: List<Double>,
    val actionWeights: List<Double>,
    val actionBias: List<Double>,
    val outputWeights: List<Double>,
    val outputBias: Double,
) {
    init {
        require(
            stateWeights.size in 64..(64 * 8192) &&
                stateWeights.size % 64 == 0 &&
                actionWeights.size in 64..(64 * 512) &&
                actionWeights.size % 64 == 0
        ) {
            "Card advantage matrices require 64 rows and bounded nonempty dictionaries."
        }
        require(listOf(stateBias, actionBias, outputWeights).all { it.size == 64 }) {
            "Card advantage hidden vectors require 64 values."
        }
        require(
            listOf(stateWeights, stateBias, actionWeights, actionBias, outputWeights).all { values
                ->
                values.all { it.isFinite() && abs(it) <= 1.0e6 }
            } && outputBias.isFinite() && abs(outputBias) <= 1.0e6
        ) {
            "Card advantage parameters must be finite and bounded."
        }
    }
}

/**
 * A separate wire contract from CardPolicy v1: three independently initialized q networks with
 * shared frozen dictionaries and the fixed 0.02 advantage margin. No purchase or residual-policy
 * artifact can be reinterpreted as this selector. Profile qualification remains the caller's job.
 */
data class CardAdvantageModel(
    val schemaVersion: Int = 1,
    val encoderVersion: String = CardObservationEncoder.ENCODER_VERSION,
    val selectorVersion: String = CardAdvantagePolicy.SELECTOR_VERSION,
    val proposalVersion: String = CardProposals.VERSION,
    val hiddenSize: Int = 64,
    val margin: Double = 0.02,
    val stateFeatureNames: List<String>,
    val actionFeatureNames: List<String>,
    val members: List<CardAdvantageNetwork>,
) {
    init {
        require(
            schemaVersion == 1 &&
                encoderVersion == CardObservationEncoder.ENCODER_VERSION &&
                selectorVersion == CardAdvantagePolicy.SELECTOR_VERSION &&
                proposalVersion == CardProposals.VERSION
        ) {
            "Unsupported card advantage schema, encoder, selector or proposal version."
        }
        require(hiddenSize == 64 && margin == 0.02 && members.size == 3) {
            "Card advantage version 1 requires three 64-unit networks and the 0.02 margin."
        }
        CardFeatureDictionary(stateFeatureNames, actionFeatureNames)
        require(
            members.all {
                it.stateWeights.size == hiddenSize * stateFeatureNames.size &&
                    it.actionWeights.size == hiddenSize * actionFeatureNames.size
            }
        ) {
            "Card advantage matrices must match the complete frozen dictionaries."
        }
    }

    internal val stateIndices = stateFeatureNames.withIndex().associate { it.value to it.index }
    internal val actionIndices = actionFeatureNames.withIndex().associate { it.value to it.index }
}

/**
 * Each member row contains q values in [proposalIndices] order, not full legal-action order.
 * [baselineIndex] and all proposal indices address the complete input observation. Derived
 * advantages subtract that member's value for F; the ensemble mean is the arithmetic mean.
 */
data class CardAdvantageEvaluation(
    val proposalIndices: List<Int>,
    val baselineIndex: Int,
    val memberValues: List<List<Double>>,
) {
    init {
        require(
            proposalIndices.size in 1..4 &&
                proposalIndices == proposalIndices.distinct().sorted() &&
                proposalIndices.all { it in 0..1023 } &&
                baselineIndex in proposalIndices
        ) {
            "Card advantage proposals must be unique sorted legal indices including F."
        }
        require(
            memberValues.size == 3 &&
                memberValues.all { values ->
                    values.size == proposalIndices.size &&
                        values.all { it.isFinite() && it in 0.0..1.0 }
                }
        ) {
            "Each of three card advantage members must value every proposal in [0,1]."
        }
    }

    val memberAdvantages: List<List<Double>> = memberValues.map { values ->
        val baseline = values[proposalIndices.indexOf(baselineIndex)]
        values.map { it - baseline }
    }
    val meanAdvantages: List<Double> =
        proposalIndices.indices.map { position ->
            memberAdvantages.sumOf { it[position] } / 3.0
        }
}

/** All indices address the input observation; [winnerIndex] precedes the consensus veto. */
data class CardAdvantageChoice(
    val index: Int,
    val command: String,
    val winnerIndex: Int,
    val overridden: Boolean,
)

/** Normal-window experimental control; existing purchase and card-policy behavior is untouched. */
object CardAdvantagePolicy {
    const val SELECTOR_VERSION = "aor-card-advantage-1"
    private const val MARGIN = 0.02

    /**
     * Final card windows and missing legacy window metadata retain the explicit F recommendation.
     */
    fun supports(observation: CardObservation): Boolean =
        observation.stateFeatures["cardWindow.normal"] == 1.0 &&
            (observation.stateFeatures["cardWindow.final"] ?: 0.0) == 0.0

    fun choose(
        observation: CardObservation,
        model: CardAdvantageModel,
        botSeed: Int,
    ): CardAdvantageChoice {
        if (!supports(observation)) return fallback(observation)
        return select(observation, evaluate(observation, model, botSeed))
    }

    /**
     * Encode sparse indices once, shared across all three members. Evaluate only the versioned
     * proposals; adding another offered target never causes unbounded network scoring.
     */
    fun evaluate(
        observation: CardObservation,
        model: CardAdvantageModel,
        botSeed: Int,
    ): CardAdvantageEvaluation {
        require(supports(observation)) {
            "Card advantage inference supports normal card windows only."
        }
        require(observation.encoderVersion == model.encoderVersion) {
            "Card advantage encoder identities must match."
        }
        require(
            observation.actions.all { it.features.keys.all(model.actionIndices::containsKey) }
        ) {
            "Unknown card advantage action feature."
        }
        val state = indexed(observation.stateFeatures, model.stateIndices)
        val proposals = CardProposals.indices(observation, botSeed)
        val actions = proposals.map {
            indexed(observation.actions[it].features, model.actionIndices)
        }
        val values =
            model.members.map { network ->
                val hidden =
                    List(64) { row ->
                        tanh(
                            network.stateBias[row] +
                                dot(network.stateWeights, row, model.stateFeatureNames.size, state)
                        )
                    }
                actions.map { action ->
                    val value =
                        network.outputBias +
                            hidden.indices.sumOf { row ->
                                network.outputWeights[row] *
                                    tanh(
                                        hidden[row] +
                                            network.actionBias[row] +
                                            dot(
                                                network.actionWeights,
                                                row,
                                                model.actionFeatureNames.size,
                                                action,
                                            )
                                    )
                            }
                    sigmoid(value)
                }
            }
        return CardAdvantageEvaluation(proposals, observation.baselineIndex, values)
    }

    /**
     * Rank all proposals by mean first; canonical command order breaks exact ties. Veto the winner
     * unless its mean is strictly above .02 and each member's advantage is strictly positive. A
     * lower-ranked unanimous proposal never replaces a vetoed winner. Agreement is a policy rule,
     * not a confidence interval. Zero heads choose F exactly.
     */
    fun select(
        observation: CardObservation,
        evaluation: CardAdvantageEvaluation,
    ): CardAdvantageChoice {
        if (!supports(observation)) return fallback(observation)
        require(
            evaluation.baselineIndex == observation.baselineIndex &&
                evaluation.proposalIndices.all { it in observation.actions.indices }
        ) {
            "Card advantage evaluation must refer to this observation's offered actions and F."
        }
        val winnerPosition =
            evaluation.meanAdvantages.indices.maxBy { evaluation.meanAdvantages[it] }
        val winner = evaluation.proposalIndices[winnerPosition]
        val accepted =
            evaluation.meanAdvantages[winnerPosition] > MARGIN &&
                evaluation.memberAdvantages.all { it[winnerPosition] > 0.0 }
        val selected = if (accepted) winner else observation.baselineIndex
        return CardAdvantageChoice(
            selected,
            observation.actions[selected].command,
            winner,
            selected != observation.baselineIndex,
        )
    }

    private fun fallback(observation: CardObservation): CardAdvantageChoice =
        CardAdvantageChoice(
            observation.baselineIndex,
            observation.actions[observation.baselineIndex].command,
            observation.baselineIndex,
            false,
        )

    private fun indexed(
        features: Map<String, Double>,
        indices: Map<String, Int>,
    ): List<Pair<Int, Double>> =
        features
            .map { (name, value) ->
                checkNotNull(indices[name]) { "Unknown card advantage feature: $name." } to value
            }
            .sortedBy { it.first }

    private fun dot(
        weights: List<Double>,
        row: Int,
        columns: Int,
        features: List<Pair<Int, Double>>,
    ): Double = features.sumOf { (column, value) -> weights[row * columns + column] * value }

    private fun sigmoid(value: Double): Double =
        if (value >= 0.0) 1.0 / (1.0 + exp(-value))
        else {
            val exponential = exp(value)
            exponential / (1.0 + exponential)
        }
}
