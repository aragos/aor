package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.PurchasePolicyModel

/**
 * Repeated card decisions use only the ordinary actor view; nested choices retain the simulator's
 * HARD baseline. Every purchase uses the independently frozen purchase actor and its original
 * sampler, including rival purchases. Only the controlled seat supplies [record] or a purchase
 * recorder. [purchaseBaseline] permits an explicit ordinary/card model distinct from the frozen
 * purchase prior. The card recorder stays last so existing trailing-lambda callers retain their
 * meaning.
 */
internal class CardActorController(
    private val baseline: BotModel,
    private val policy: CardPolicyModel?,
    frozenPurchasePolicy: PurchasePolicyModel,
    private val audit: ((CardPurchaseAudit) -> Unit)? = null,
    purchaseRecord: ((PurchaseDecisionRecord) -> Unit)? = null,
    purchaseBaseline: BotModel = baseline,
    private val record: ((CardDecisionRecord) -> Unit)? = null,
) : SimulationController {
    private val purchase =
        PurchaseActorController(purchaseBaseline, frozenPurchasePolicy, purchaseRecord)

    override fun controls(decision: String): Boolean =
        decision == "play-cards" || purchase.controls(decision)

    override fun choose(decision: SimulationDecision): String {
        if (purchase.controls(decision.observation.decision)) {
            val chosen = purchase.choose(decision)
            audit?.invoke(CardPurchaseAudit.from(decision, chosen))
            return chosen
        }
        require(decision.observation.decision == "play-cards") {
            "The card actor only controls ordinary card play and its frozen purchase continuation."
        }
        val recommendation =
            BotPolicy.choose(
                decision.observation,
                BotDifficulty.HARD,
                decision.seed,
                baseline,
            )
        if (policy == null && record == null) return recommendation
        val observation =
            CardObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                recommendation,
            )
        val actorSeed = CardPolicy.actorSeed(decision.seed)
        val chosen = policy?.let { CardPolicy.choose(observation, it, actorSeed) }
        val selected = chosen?.index ?: observation.baselineIndex
        record?.invoke(
            CardDecisionRecord(
                decision.number,
                actorSeed,
                decision.observation.decision,
                requireNotNull(decision.view.turnNumber) {
                    "A card decision requires its actual game turn."
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
