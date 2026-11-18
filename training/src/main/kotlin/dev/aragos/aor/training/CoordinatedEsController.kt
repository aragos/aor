package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.CoordinatedPolicyModels
import dev.aragos.aor.ai.CoordinatedPolicySelector
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.IncumbentResidualSelector
import dev.aragos.aor.ai.JointSupportProfile
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.PublicLeaderAttackConfig
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TokenAnchoredResidual
import dev.aragos.aor.ai.TokenObservationEncoder

/** Own-seat complete policy; reference E/T is called once and cannot overwrite the outer choice. */
internal class CoordinatedEsController(
    private val reference: SimulationController,
    ordinary: BotModel,
    purchaseBaseline: BotModel,
    private val current: CoordinatedEsModels,
    private val original: CoordinatedEsModels,
    selectors: Map<CoordinatedEsHead, IncumbentResidualSelector>,
    support: JointSupportProfile? = null,
    tokenSampler: NearOptimalTokenSampler? = null,
    expansionDenial: PublicLeaderAttackConfig? = null,
    private val tokenNeighborMixture: LocalTokenNeighborMixture? = null,
    tokenAnchoredResidual: TokenAnchoredResidual? = null,
) : SimulationController {
    init {
        current.validateFrozenValues(original)
        require(selectors.keys == setOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN)) {
            "Executable coordinated roles require exactly the two sealed E/T selectors."
        }
    }

    private val selector =
        CoordinatedPolicySelector(
            ordinary,
            purchaseBaseline,
            current.common(),
            original.common(),
            requireNotNull(selectors[CoordinatedEsHead.EXPANSION]) {
                "The expansion head requires its sealed selector."
            },
            requireNotNull(selectors[CoordinatedEsHead.TOKEN]) {
                "The token head requires its sealed selector."
            },
            support,
            tokenSampler,
            expansionDenial,
            tokenNeighborMixture,
            tokenAnchoredResidual,
        )

    override fun controls(decision: String): Boolean =
        selector.controls(decision) || reference.controls(decision)

    override fun tokenNeighborMixture(): LocalTokenNeighborMixture? = tokenNeighborMixture

    override fun choose(decision: SimulationDecision): String? {
        if (!selector.controls(decision.observation.decision)) return reference.choose(decision)
        return selector.choose(
            decision.view,
            decision.observation,
            decision.seed,
            referenceCommand = {
                requireNotNull(reference.choose(decision)) {
                    "The complete qualified G3/E/T reference must supply its offered command."
                }
            },
            tokenQuote = decision.tokenQuote,
        )
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

/** No wire conversion or parameter rewriting occurs between native and application inference. */
internal fun CoordinatedEsModels.common(): CoordinatedPolicyModels =
    CoordinatedPolicyModels(card, purchase, expansion, token)
