package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.IncumbentResidualPolicy
import dev.aragos.aor.ai.IncumbentResidualSelector
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TokenObservationEncoder

/** Own-seat complete policy; reference E/T is called once and cannot overwrite the outer choice. */
internal class CoordinatedEsController(
    private val reference: SimulationController,
    ordinary: BotModel,
    purchaseBaseline: BotModel,
    private val current: CoordinatedEsModels,
    private val original: CoordinatedEsModels,
    private val selectors: Map<CoordinatedEsHead, IncumbentResidualSelector>,
) : SimulationController {
    init {
        current.validateFrozenValues(original)
        require(selectors.keys == setOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN)) {
            "Executable coordinated roles require exactly the two sealed E/T selectors."
        }
    }

    private val cardPurchase =
        CardActorController(
            ordinary,
            current.card,
            current.purchase,
            purchaseBaseline = purchaseBaseline,
        )

    override fun controls(decision: String): Boolean =
        cardPurchase.controls(decision) || reference.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        if (cardPurchase.controls(decision.observation.decision))
            return cardPurchase.choose(decision)
        val head = territoryHead(decision.observation.decision) ?: return reference.choose(decision)
        val recommended =
            requireNotNull(reference.choose(decision)) {
                "The complete qualified G3/E/T reference must supply its offered command."
            }
        val observation = observation(decision, recommended)
        return IncumbentResidualPolicy.choose(
                observation,
                current.territory(head),
                original.territory(head),
                requireNotNull(selectors[head]) { "The active head requires its sealed selector." },
            )
            .command
    }

    companion object {
        fun territoryHead(decision: String): CoordinatedEsHead? =
            when (decision) {
                TerritoryPhase.EXPANSION.decision -> CoordinatedEsHead.EXPANSION
                TerritoryPhase.TOKEN.decision -> CoordinatedEsHead.TOKEN
                else -> null
            }

        fun observation(decision: SimulationDecision, recommended: String): TerritoryObservation {
            val player = PlayerId(decision.observation.playerId)
            return when (territoryHead(decision.observation.decision)) {
                CoordinatedEsHead.EXPANSION ->
                    ExpansionObservationEncoder.from(
                        decision.view,
                        player,
                        decision.observation,
                        recommended,
                        TerritoryEncoder.EXPANSION_2,
                    )
                CoordinatedEsHead.TOKEN ->
                    TokenObservationEncoder.from(
                        decision.view,
                        player,
                        decision.observation,
                        recommended,
                        TerritoryEncoder.TOKEN_2,
                    )
                else -> error("Only E/T decisions have an incumbent-relative observation.")
            }
        }
    }
}
