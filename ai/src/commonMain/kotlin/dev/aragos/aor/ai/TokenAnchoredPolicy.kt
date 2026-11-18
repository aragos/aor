package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** The first anchored policy learns only over the complete original owner-visible token offers. */
enum class TokenAnchorSupport {
    ORIGINAL_AFFORDABLE_OFFERS
}

/**
 * A positive [epsilon] smooths the frozen anchor before adding the learned raw residual.
 * [temperature] divides only that residual; it does not change the frozen anchor's C05 sampler.
 * This is an initial anchor floor, not a permanent lower bound on fitted probabilities.
 */
data class TokenAnchorDistribution(
    val epsilon: Double,
    val temperature: Double,
    val support: TokenAnchorSupport = TokenAnchorSupport.ORIGINAL_AFFORDABLE_OFFERS,
) {
    init {
        require(epsilon.isFinite() && epsilon in (1.0 / 1024.0)..0.5) {
            "Token anchor smoothing must be finite and between 1/1024 and one half."
        }
        require(temperature.isFinite() && temperature in (1.0 / 64.0)..64.0) {
            "Token residual temperature must be finite and between 1/64 and 64."
        }
    }

    companion object {
        const val VERSION = "token-anchor-log-floor-1"
    }
}

/** The frozen anchor and the learned residual are separate parameter owners. */
data class TokenAnchoredResidual(
    val model: TerritoryPolicyModel,
    val distribution: TokenAnchorDistribution,
) {
    val encoder = TerritoryEncoder.fromVersion(model.encoderVersion)

    init {
        require(
            model.schemaVersion == 3 &&
                encoder in setOf(TerritoryEncoder.TOKEN_2, TerritoryEncoder.TOKEN_3) &&
                model.valueWeights.all { it == 0.0 } &&
                model.valueBias == 0.0
        ) {
            "An anchored token residual requires schema 3 and unchanged zero value output."
        }
    }

    /** The TOKEN3 residual may add inputs while the frozen anchor retains TOKEN2 semantics. */
    fun extendsAnchor(anchor: TerritoryPolicyModel): Boolean =
        extendsAnchorDictionary(model, anchor, encoder)

    /** Preserve the anchor's legal support and reference while extending the actor inputs. */
    fun observation(
        view: GameView,
        seat: PlayerId,
        base: TerritoryObservation,
    ): TerritoryObservation =
        if (encoder == TerritoryEncoder.TOKEN_3)
            TokenObservationEncoder.withHistory(view, seat, base)
        else base

    companion object {
        /** The warm start retains exact existing embeddings and sets all new columns to zero. */
        fun initialize(
            anchor: TerritoryPolicyModel,
            encoder: TerritoryEncoder,
            dictionary: TerritoryFeatureDictionary,
        ): TerritoryPolicyModel {
            val model =
                anchor.copy(
                    encoderVersion = encoder.version,
                    stateFeatureNames = dictionary.stateFeatureNames,
                    actionFeatureNames = dictionary.actionFeatureNames,
                    stateWeights =
                        extend(
                            anchor.stateWeights,
                            anchor.stateFeatureNames,
                            dictionary.stateFeatureNames,
                        ),
                    actionWeights =
                        extend(
                            anchor.actionWeights,
                            anchor.actionFeatureNames,
                            dictionary.actionFeatureNames,
                        ),
                    actorWeights = List(anchor.hiddenSize) { 0.0 },
                    valueWeights = List(anchor.hiddenSize) { 0.0 },
                    valueBias = 0.0,
                )
            require(
                anchor.encoderVersion == TerritoryEncoder.TOKEN_2.version &&
                    extendsAnchorDictionary(model, anchor, encoder)
            ) {
                "Warm token initialization requires the exact TOKEN2 anchor and residual dictionary."
            }
            return model
        }

        private fun extendsAnchorDictionary(
            model: TerritoryPolicyModel,
            anchor: TerritoryPolicyModel,
            encoder: TerritoryEncoder,
        ): Boolean =
            when (encoder) {
                TerritoryEncoder.TOKEN_2 ->
                    model.stateFeatureNames == anchor.stateFeatureNames &&
                        model.actionFeatureNames == anchor.actionFeatureNames
                TerritoryEncoder.TOKEN_3 ->
                    model.stateFeatureNames ==
                        (anchor.stateFeatureNames + TokenOrderHistoryFeatures.stateNames)
                            .sorted() &&
                        model.actionFeatureNames ==
                            (anchor.actionFeatureNames + TokenOrderHistoryFeatures.actionNames)
                                .sorted()
                else -> false
            }

        private fun extend(
            weights: List<Double>,
            previousNames: List<String>,
            nextNames: List<String>,
        ): List<Double> {
            if (previousNames == nextNames) return weights
            val columns = previousNames.withIndex().associate { (index, name) -> name to index }
            return List(weights.size / previousNames.size * nextNames.size) { index ->
                val row = index / nextNames.size
                val previous = columns[nextNames[index % nextNames.size]]
                if (previous == null) 0.0 else weights[row * previousNames.size + previous]
            }
        }
    }
}

/**
 * All vectors use the same complete canonical command order, including zero-mass anchor actions.
 */
data class TokenAnchoredEvaluation(
    val anchorProbabilities: List<Double>,
    val initialProbabilities: List<Double>,
    val residuals: List<Double>,
    val logits: List<Double>,
    val probabilities: List<Double>,
    val anchorIndex: Int,
) {
    init {
        require(
            probabilities.size in 1..TerritoryPhase.TOKEN.maxActions &&
                listOf(anchorProbabilities, initialProbabilities, residuals, logits).all {
                    it.size == probabilities.size && it.all(Double::isFinite)
                } &&
                anchorIndex in probabilities.indices &&
                anchorProbabilities.all { it >= 0.0 } &&
                abs(anchorProbabilities.sum() - 1.0) <= 1e-12 &&
                anchorProbabilities[anchorIndex] == anchorProbabilities.max() &&
                listOf(initialProbabilities, probabilities).all { values ->
                    values.all { it > 0.0 && it <= 1.0 } && abs(values.sum() - 1.0) <= 1e-12
                }
        ) {
            "An anchored evaluation must retain complete finite vectors, full support and unit mass."
        }
    }
}

/**
 * Shared collection/application contract: p0=(1-epsilon)*p94+epsilon/N;
 * pi=softmax(log(p0)+rawResidual/temperature). No residual clipping, support truncation, additional
 * random draw or authority state is used.
 */
object TokenAnchoredPolicy {
    fun evaluate(
        observation: TerritoryObservation,
        anchor: TerritoryPolicyModel,
        original: TerritoryPolicyModel,
        selector: IncumbentResidualSelector,
        sampler: NearOptimalTokenSampler,
        residual: TokenAnchoredResidual,
        residualObservation: TerritoryObservation = observation,
    ): TokenAnchoredEvaluation {
        require(
            residual.extendsAnchor(anchor) &&
                residualObservation.baselineIndex == observation.baselineIndex &&
                (residualObservation === observation ||
                    residualObservation.actions.map(TerritoryAction::command) ==
                        observation.actions.map(TerritoryAction::command))
        ) {
            "The learned residual must preserve the frozen anchor support and baseline index."
        }
        val deterministic = IncumbentResidualPolicy.choose(observation, anchor, original, selector)
        val probabilities =
            NearOptimalTokenPolicy.select(observation, deterministic, sampler, 0.0).probabilities
        return evaluate(residualObservation, probabilities, residual, deterministic.index)
    }

    fun evaluate(
        observation: TerritoryObservation,
        anchorProbabilities: List<Double>,
        residual: TokenAnchoredResidual,
        anchorIndex: Int = anchorIndex(observation, anchorProbabilities),
    ): TokenAnchoredEvaluation {
        require(observation.encoderVersion == residual.model.encoderVersion) {
            "Anchored residual inference requires its declared token observation version."
        }
        val values =
            ResidualPolicyMath.rawResiduals(
                observation.stateFeatures,
                observation.actions.map(TerritoryAction::features),
                residual.model.network,
            )
        return probabilities(anchorProbabilities, values, residual.distribution, anchorIndex)
    }

    /** Pure probability owner, also used by constructed cross-runtime numerical fixtures. */
    fun probabilities(
        anchorProbabilities: List<Double>,
        residuals: List<Double>,
        distribution: TokenAnchorDistribution,
        anchorIndex: Int,
    ): TokenAnchoredEvaluation {
        require(
            anchorProbabilities.size in 1..TerritoryPhase.TOKEN.maxActions &&
                residuals.size == anchorProbabilities.size &&
                anchorProbabilities.all { it.isFinite() && it >= 0.0 } &&
                abs(anchorProbabilities.sum() - 1.0) <= 1e-12 &&
                residuals.all(Double::isFinite) &&
                anchorIndex in anchorProbabilities.indices &&
                anchorProbabilities[anchorIndex] == anchorProbabilities.max()
        ) {
            "An anchored distribution requires complete normalized anchor mass and finite residuals."
        }
        val initial = anchorProbabilities.map {
            (1.0 - distribution.epsilon) * it + distribution.epsilon / anchorProbabilities.size
        }
        val logits =
            initial.indices.map {
                ln(initial[it]) + residuals[it] / distribution.temperature
            }
        require(logits.all(Double::isFinite)) {
            "Every anchored logit must remain finite; invalid actors cannot drop offered actions."
        }
        val probabilities =
            if (residuals.all { it == residuals.first() }) initial
            else {
                val maximum = logits.max()
                val weights = logits.map { exp(it - maximum) }
                val total = weights.sum()
                weights.map { it / total }
            }
        return TokenAnchoredEvaluation(
            anchorProbabilities,
            initial,
            residuals,
            logits,
            probabilities,
            anchorIndex,
        )
    }

    /** Exactly one existing salted TOKEN actor uniform; the game chance stream is untouched. */
    fun choose(
        observation: TerritoryObservation,
        evaluation: TokenAnchoredEvaluation,
        botSeed: Int,
    ): TerritoryPolicyChoice =
        select(
            observation,
            evaluation,
            TerritoryPolicy.uniform(TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, botSeed)),
        )

    fun select(
        observation: TerritoryObservation,
        evaluation: TokenAnchoredEvaluation,
        uniform: Double,
    ): TerritoryPolicyChoice {
        require(evaluation.probabilities.size == observation.actions.size) {
            "Anchored sampling must preserve every original canonical token offer."
        }
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "An anchored actor draw must be finite and in [0,1)."
        }
        val index = ResidualPolicyMath.select(evaluation.probabilities, uniform)
        return TerritoryPolicyChoice(
            index,
            observation.actions[index].command,
            ln(evaluation.probabilities[index]),
            0.5,
        )
    }

    private fun anchorIndex(
        observation: TerritoryObservation,
        probabilities: List<Double>,
    ): Int {
        require(probabilities.size == observation.actions.size && probabilities.isNotEmpty()) {
            "The anchor probabilities must match the complete original action list."
        }
        val maximum = probabilities.max()
        return if (probabilities[observation.baselineIndex] == maximum) observation.baselineIndex
        else probabilities.indexOfFirst { it == maximum }
    }
}
