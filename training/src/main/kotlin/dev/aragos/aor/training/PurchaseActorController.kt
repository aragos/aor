package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel

/**
 * Repeated shipping/advance sampler from the ordinary acting-player view. [baseline] supplies the
 * HARD prior recommendation; the simulator retains that seat's baseline for all other decisions.
 * Only the collector's controlled seat supplies [record]; rival hands never enter its trajectory.
 */
internal class PurchaseActorController(
    private val baseline: BotModel,
    private val policy: PurchasePolicyModel?,
    private val record: ((PurchaseDecisionRecord) -> Unit)? = null,
) : SimulationController {
    override fun controls(decision: String): Boolean =
        decision in setOf("purchase-shipping", "purchase-advances")

    override fun choose(decision: SimulationDecision): String {
        val recommendation =
            BotPolicy.choose(
                decision.observation,
                BotDifficulty.HARD,
                decision.seed,
                baseline,
            )
        val observation =
            PurchaseObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                recommendation,
            )
        val actorSeed = PurchasePolicy.actorSeed(decision.seed)
        val chosen = policy?.let { PurchasePolicy.choose(observation, it, actorSeed) }
        val selected = chosen?.index ?: observation.baselineIndex
        record?.invoke(
            PurchaseDecisionRecord(
                decision.number,
                actorSeed,
                decision.observation.decision,
                requireNotNull(decision.view.turnNumber) {
                    "A purchase decision requires its actual game turn."
                },
                observation,
                selected,
                chosen?.logProbability,
                chosen?.value,
            )
        )
        return observation.actions[selected].command
    }
}
