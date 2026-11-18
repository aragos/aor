package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TerritoryPolicyChoice
import dev.aragos.aor.ai.TerritoryPolicyModel
import dev.aragos.aor.ai.TokenObservationEncoder

/** Public decision evidence; reference and sampled indices have different ownership. */
internal data class TokenPairedDecision(
    val number: Int,
    val batchStartNumber: Int,
    val botSeed: Int,
    val actorSeed: Int,
    val observation: TerritoryObservation,
    val currentProbabilities: List<Double>,
    val originalProbabilities: List<Double>,
    val sampled: TerritoryPolicyChoice,
    val anchor94Probabilities: List<Double>? = null,
    val anchorIndex: Int? = null,
)

/** Outermost TOKEN_2 actor; the complete G3/E/T reference is called exactly once per decision. */
internal class TokenPairedController(
    private val reference: SimulationController,
    private val current: TerritoryPolicyModel,
    private val original: TerritoryPolicyModel,
    private val record: ((TokenPairedDecision) -> Unit)? = null,
) : SimulationController {
    init {
        require(
            listOf(current, original).all {
                it.schemaVersion == 3 && it.encoderVersion == TerritoryEncoder.TOKEN_2.version
            }
        ) {
            "Token paired control requires explicit schema 3 / TOKEN_2 actors."
        }
        require(
            current.stateFeatureNames == original.stateFeatureNames &&
                current.actionFeatureNames == original.actionFeatureNames
        ) {
            "Current and original token actors must use the identical frozen dictionary."
        }
    }

    override fun controls(decision: String): Boolean =
        decision == TerritoryPhase.TOKEN.decision || reference.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        if (decision.observation.decision != TerritoryPhase.TOKEN.decision) {
            return reference.choose(decision)
        }
        val recommended =
            requireNotNull(reference.choose(decision)) {
                "The complete reference must supply its exact offered T recommendation."
            }
        val observation =
            TokenObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                recommended,
                TerritoryEncoder.TOKEN_2,
            )
        val evaluation = TerritoryPolicy.evaluate(observation, current)
        val seed = TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, decision.seed)
        val choice = TerritoryPolicy.select(observation, evaluation, TerritoryPolicy.uniform(seed))
        record?.invoke(
            TokenPairedDecision(
                decision.number,
                decision.batchStartNumber,
                decision.seed,
                seed,
                observation,
                evaluation.probabilities,
                if (current === original) evaluation.probabilities
                else TerritoryPolicy.evaluate(observation, original).probabilities,
                choice,
            )
        )
        return choice.command
    }
}
