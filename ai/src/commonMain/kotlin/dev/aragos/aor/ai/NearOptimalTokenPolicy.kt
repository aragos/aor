package dev.aragos.aor.ai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Opt-in token mixture in incumbent-residual score units. [temperature] scales score differences,
 * [maxScoreGap] excludes actions below the best score, and [neighborRadius] bounds signed token
 * amounts around the actual deterministic winner, rather than around an old reference amount.
 */
data class NearOptimalTokenSampler(
    val temperature: Double,
    val maxScoreGap: Double,
    val neighborRadius: Int,
) {
    init {
        require(temperature.isFinite() && temperature in (1.0 / 64.0)..8.0) {
            "Token sampling temperature must be finite and between 1/64 and 8 score units."
        }
        require(maxScoreGap.isFinite() && maxScoreGap in 0.0..16.0) {
            "Token sampling score gap must be finite and between 0 and 16."
        }
        require(neighborRadius in 1..8) {
            "Token sampling radius must contain between 1 and 8 neighboring signed amounts."
        }
    }

    companion object {
        const val VERSION = "near-optimal-token-softmax-1"
    }
}

/**
 * Probabilities retain the original complete offer order; excluded actions have probability zero.
 */
data class NearOptimalTokenChoice(
    val index: Int,
    val command: String,
    val probabilities: List<Double>,
) {
    val entropy: Double
        get() = -probabilities.filter { it > 0.0 }.sumOf { it * ln(it) }
}

/** Shared native/application sampling; the original actor-policy seed owns the single draw. */
object NearOptimalTokenPolicy {
    fun choose(
        observation: TerritoryObservation,
        deterministic: IncumbentResidualChoice,
        sampler: NearOptimalTokenSampler,
        seed: Int,
    ): NearOptimalTokenChoice =
        select(observation, deterministic, sampler, ResidualPolicyMath.uniform(seed))

    fun select(
        observation: TerritoryObservation,
        deterministic: IncumbentResidualChoice,
        sampler: NearOptimalTokenSampler,
        uniform: Double,
    ): NearOptimalTokenChoice {
        require(observation.encoderVersion == TerritoryEncoder.TOKEN_2.version) {
            "The token mixture requires the complete TOKEN2 action encoding."
        }
        require(
            deterministic.scores.size == observation.actions.size &&
                deterministic.index in observation.actions.indices &&
                deterministic.command == observation.actions[deterministic.index].command &&
                deterministic.scores.all(Double::isFinite)
        ) {
            "Token sampling requires the exact deterministic choice and its complete finite scores."
        }
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "The policy uniform must be finite and in [0,1)."
        }
        val amounts =
            observation.actions.map { action ->
                val suffix = action.command.removePrefix("buy-tokens ")
                require(action.command == "buy-tokens $suffix") {
                    "Only canonical token commitments may enter the token mixture."
                }
                requireNotNull(suffix.toIntOrNull()) {
                    "A token mixture action requires a signed integral token amount."
                }
            }
        val maximum = deterministic.scores.max()
        require(deterministic.scores[deterministic.index] == maximum) {
            "The supplied deterministic winner must maximize the original score vector."
        }
        val amount = amounts[deterministic.index].toLong()
        val weights =
            deterministic.scores.mapIndexed { index, score ->
                if (
                    maximum - score <= sampler.maxScoreGap &&
                        abs(amounts[index].toLong() - amount) <= sampler.neighborRadius
                ) {
                    exp((score - maximum) / sampler.temperature)
                } else {
                    0.0
                }
            }
        val total = weights.sum()
        val probabilities = weights.map { it / total }
        // Select only positive support: floating-point summation cannot fall back to an excluded
        // last offer when a uniform is extremely close to one.
        val support = probabilities.indices.filter { probabilities[it] > 0.0 }
        var cumulative = 0.0
        val selected =
            support.firstOrNull { index ->
                cumulative += probabilities[index]
                uniform < cumulative
            } ?: support.last()
        return NearOptimalTokenChoice(
            selected,
            observation.actions[selected].command,
            probabilities,
        )
    }
}
