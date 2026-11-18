package dev.aragos.aor.ai

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
    init {
        require(
            model.schemaVersion == 3 &&
                model.encoderVersion == TerritoryEncoder.TOKEN_2.version &&
                model.valueWeights.all { it == 0.0 } &&
                model.valueBias == 0.0
        ) {
            "An anchored TOKEN2 residual requires schema3 and unchanged zero value output."
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
    ): TokenAnchoredEvaluation {
        require(
            residual.model.stateFeatureNames == anchor.stateFeatureNames &&
                residual.model.actionFeatureNames == anchor.actionFeatureNames
        ) {
            "The learned residual must preserve the complete frozen anchor dictionary."
        }
        val deterministic = IncumbentResidualPolicy.choose(observation, anchor, original, selector)
        val probabilities =
            NearOptimalTokenPolicy.select(observation, deterministic, sampler, 0.0).probabilities
        return evaluate(observation, probabilities, residual, deterministic.index)
    }

    fun evaluate(
        observation: TerritoryObservation,
        anchorProbabilities: List<Double>,
        residual: TokenAnchoredResidual,
        anchorIndex: Int = anchorIndex(observation, anchorProbabilities),
    ): TokenAnchoredEvaluation {
        require(observation.encoderVersion == TerritoryEncoder.TOKEN_2.version) {
            "Anchored residual inference requires the explicit TOKEN2 observation."
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
