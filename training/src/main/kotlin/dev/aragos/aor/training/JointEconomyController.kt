package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy

/**
 * Candidate weights control only token buying and expansion. Purchases always use the frozen F
 * recommendation and purchase actor; all remaining choices fall through to F in Simulation. The
 * controller accepts only the ordinary actor-visible decision, never authority state.
 */
internal class JointEconomyController(
    environment: CardAdvantageEnvironment,
    private val policy: JointEconomyPolicy,
) : SimulationController {
    private val purchase =
        PurchaseActorController(environment.baseline.model, environment.purchase.model)

    init {
        require(policy.baseline == environment.baseline.model) {
            "An economy policy must belong to this evaluation's exact frozen baseline."
        }
    }

    override fun controls(decision: String): Boolean =
        decision in decisions || purchase.controls(decision)

    override fun choose(decision: SimulationDecision): String? =
        when {
            purchase.controls(decision.observation.decision) -> purchase.choose(decision)
            decision.observation.decision in decisions ->
                BotPolicy.choose(
                    decision.observation,
                    BotDifficulty.HARD,
                    decision.seed,
                    policy.model,
                )
            else -> null
        }

    companion object {
        val decisions = setOf("buy-tokens", "expand")
    }
}
