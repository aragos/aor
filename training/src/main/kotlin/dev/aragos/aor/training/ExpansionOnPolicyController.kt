package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TerritoryPolicyModel

/** Outermost own-seat wrapper: E supplies the actual prior, never overwrites the sampled action. */
internal class ExpansionOnPolicyController(
    private val reference: SimulationController,
    private val model: TerritoryPolicyModel,
    private val record: ((TerritoryDecisionRecord) -> Unit)? = null,
) : SimulationController {
    init {
        require(
            model.schemaVersion == 2 && model.encoderVersion == TerritoryEncoder.EXPANSION_2.version
        ) {
            "Expansion on-policy control requires the explicit schema 2 / expansion-v2 actor."
        }
    }

    override fun controls(decision: String): Boolean = reference.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        if (decision.observation.decision != TerritoryPhase.EXPANSION.decision) {
            return reference.choose(decision)
        }
        val recommended =
            requireNotNull(reference.choose(decision)) {
                "The complete reference must supply its exact offered E recommendation."
            }
        val observation =
            ExpansionObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                recommended,
                TerritoryEncoder.EXPANSION_2,
            )
        val seed = TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, decision.seed)
        val choice = TerritoryPolicy.choose(observation, model, seed)
        record?.invoke(
            TerritoryDecisionRecord(
                decision.number,
                decision.batchStartNumber,
                decision.seed,
                seed,
                decision.observation.decision,
                requireNotNull(decision.view.turnNumber) {
                    "Expansion requires its actual public turn."
                },
                observation,
                choice.index,
                choice.logProbability,
                choice.value,
            )
        )
        return choice.command
    }
}
