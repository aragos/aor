package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.KnownOwnChaosSupport
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel

/**
 * Repeated shipping/advance sampler from the ordinary acting-player view. [baseline] supplies the
 * HARD prior recommendation; the simulator retains that seat's baseline for all other decisions.
 * Only the collector's controlled seat supplies [record]; rival hands never enter its trajectory.
 */
internal class PurchaseActorController
private constructor(
    private val baseline: BotModel,
    private val policy: PurchasePolicyModel?,
    private val record: ((PurchaseDecisionRecord) -> Unit)?,
    private val support: ArenaCandidateSupport?,
) : SimulationController {
    constructor(
        baseline: BotModel,
        policy: PurchasePolicyModel?,
        record: ((PurchaseDecisionRecord) -> Unit)? = null,
    ) : this(baseline, policy, record, null)

    constructor(
        support: ArenaCandidateSupport,
        baseline: BotModel,
        policy: PurchasePolicyModel?,
        record: ((PurchaseDecisionRecord) -> Unit)? = null,
    ) : this(baseline, policy, record, support)

    init {
        require(support == null || policy != null && record == null) {
            "Conditional support requires a learned purchase head and rejects legacy recording."
        }
    }

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
        if (support != null) {
            return KnownOwnChaosSupport.purchase(
                    observation,
                    PurchasePolicy.evaluate(
                        observation,
                        requireNotNull(policy) {
                            "Conditional purchase support requires its head."
                        },
                    ),
                    PurchasePolicy.uniform(actorSeed),
                )
                .command
        }
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
