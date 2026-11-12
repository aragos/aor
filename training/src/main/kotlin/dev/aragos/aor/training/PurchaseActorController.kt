package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel

/** Native evaluation decoding only; deterministic choices cannot become sampled trajectories. */
internal enum class PurchaseActorSelection {
    SAMPLE,
    MAXIMUM_PROBABILITY,
}

/**
 * Repeated shipping/advance sampler from the ordinary acting-player view. [baseline] supplies the
 * HARD prior recommendation; the simulator retains that seat's baseline for all other decisions.
 * Only the collector's controlled seat supplies [record]; rival hands never enter its trajectory.
 */
internal class PurchaseActorController(
    private val baseline: BotModel,
    private val policy: PurchasePolicyModel?,
    private val record: ((PurchaseDecisionRecord) -> Unit)? = null,
    private val selection: PurchaseActorSelection = PurchaseActorSelection.SAMPLE,
) : SimulationController {
    init {
        require(selection == PurchaseActorSelection.SAMPLE || policy != null) {
            "Maximum-probability purchase selection requires an actor."
        }
        require(selection == PurchaseActorSelection.SAMPLE || record == null) {
            "Maximum-probability purchase selection cannot record sampled behavior probabilities."
        }
    }

    /** Preserve existing trailing-lambda collection callers and their sampled behavior. */
    constructor(
        baseline: BotModel,
        policy: PurchasePolicyModel?,
        record: ((PurchaseDecisionRecord) -> Unit)?,
    ) : this(baseline, policy, record, PurchaseActorSelection.SAMPLE)

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
        if (selection == PurchaseActorSelection.MAXIMUM_PROBABILITY) {
            val actor =
                requireNotNull(policy) { "Maximum-probability selection requires an actor." }
            val probabilities = PurchasePolicy.evaluate(observation, actor).probabilities
            // Encoder order is canonical; maxBy retains its first maximum, including exact ties.
            val selected = probabilities.indices.maxBy { probabilities[it] }
            return observation.actions[selected].command
        }
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
