package dev.aragos.aor.ai

import kotlin.math.ln

/**
 * Inactive research wrapper around two ordinary portable purchase models. Only actorWeights may
 * differ. Outside the public support gate the reference is exact; unsupported probabilities stay
 * exact even inside it. Within support, half of its existing mass follows the candidate's
 * conditional distribution. This bounds total variation by .5 without treating unmeasured offers as
 * zero-return actions. The reference value head and original sampling mechanism remain unchanged.
 *
 * Exporting [candidate] alone does not represent this policy. Activation requires separate bundle
 * metadata, qualification and an explicit caller; no current policy selector enables this wrapper.
 */
class PurchaseSupportedPolicy(
    val reference: PurchasePolicyModel,
    val candidate: PurchasePolicyModel,
) {
    init {
        require(candidate.copy(actorWeights = reference.actorWeights) == reference) {
            "Supported purchase improvement may change only the existing 64 actor output weights."
        }
    }

    fun evaluate(
        observation: PurchaseObservation,
        difficulty: BotDifficulty = BotDifficulty.HARD,
    ): PurchasePolicyEvaluation {
        val original = PurchasePolicy.evaluate(observation, reference)
        if (difficulty != BotDifficulty.HARD || candidate.actorWeights == reference.actorWeights) {
            return original
        }
        val support = PurchaseSupport.from(observation) ?: return original
        val indices =
            observation.actions.indices.filter {
                observation.actions[it].command in support.commands
            }
        val proposed = PurchasePolicy.evaluate(observation, candidate)
        val originalMass = indices.sumOf { original.probabilities[it] }
        val proposedMass = indices.sumOf { proposed.probabilities[it] }
        val probabilities =
            original.probabilities.mapIndexed { index, probability ->
                if (index in indices) {
                    (1.0 - ALPHA) * probability +
                        ALPHA * originalMass * proposed.probabilities[index] / proposedMass
                } else {
                    probability
                }
            }
        return PurchasePolicyEvaluation(probabilities.map(::ln), probabilities, original.value)
    }

    fun choose(
        observation: PurchaseObservation,
        actorSeed: Int,
        difficulty: BotDifficulty = BotDifficulty.HARD,
    ): PurchasePolicyChoice =
        PurchasePolicy.select(
            observation,
            evaluate(observation, difficulty),
            PurchasePolicy.uniform(actorSeed),
        )

    companion object {
        const val VERSION = "aor-purchase-supported-1"
        const val ALPHA = 0.5
    }
}
