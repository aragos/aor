package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId

/** Four immutable parameter containers; E/T use the explicit deterministic residual contract. */
data class CoordinatedPolicyModels(
    val card: CardPolicyModel,
    val purchase: PurchasePolicyModel,
    val expansion: TerritoryPolicyModel,
    val token: TerritoryPolicyModel,
) {
    init {
        require(
            expansion.schemaVersion == 2 &&
                expansion.encoderVersion == TerritoryEncoder.EXPANSION_2.version &&
                token.schemaVersion == 3 &&
                token.encoderVersion == TerritoryEncoder.TOKEN_2.version
        ) {
            "Coordinated models require explicit E2 and TOKEN2 containers."
        }
    }
}

/**
 * Shared complete C/P/E/T chooser. Callers retain unsupported phases and scope/admission ownership.
 * Every input is the acting player's ordinary view; no history, labels or hidden state is accepted.
 * [referenceCommand] in [choose] is called exactly once for E/T, never for C/P. It must return the
 * offered command from the complete qualified raw reference, before this outer residual selector.
 */
class CoordinatedPolicySelector(
    ordinary: BotModel,
    purchaseBaseline: BotModel,
    private val current: CoordinatedPolicyModels,
    private val original: CoordinatedPolicyModels,
    private val expansionSelector: IncumbentResidualSelector,
    private val tokenSelector: IncumbentResidualSelector,
    support: JointSupportProfile? = null,
    private val tokenSampler: NearOptimalTokenSampler? = null,
) {
    private val joint =
        JointPolicySelector(ordinary, purchaseBaseline, current.card, current.purchase, support)

    init {
        require(
            current.card.stateFeatureNames == original.card.stateFeatureNames &&
                current.card.actionFeatureNames == original.card.actionFeatureNames &&
                current.purchase.stateFeatureNames == original.purchase.stateFeatureNames &&
                current.purchase.actionFeatureNames == original.purchase.actionFeatureNames &&
                current.expansion.stateFeatureNames == original.expansion.stateFeatureNames &&
                current.expansion.actionFeatureNames == original.expansion.actionFeatureNames &&
                current.token.stateFeatureNames == original.token.stateFeatureNames &&
                current.token.actionFeatureNames == original.token.actionFeatureNames
        ) {
            "A coordinated selector preserves every original complete dictionary."
        }
        require(
            current.card.valueWeights == original.card.valueWeights &&
                current.card.valueBias == original.card.valueBias &&
                current.purchase.valueWeights == original.purchase.valueWeights &&
                current.purchase.valueBias == original.purchase.valueBias &&
                current.expansion.valueWeights == original.expansion.valueWeights &&
                current.expansion.valueBias == original.expansion.valueBias &&
                current.token.valueWeights == original.token.valueWeights &&
                current.token.valueBias == original.token.valueBias
        ) {
            "A coordinated selector preserves all original value arrays."
        }
    }

    fun controls(decision: String): Boolean =
        joint.controls(decision) ||
            decision == TerritoryPhase.EXPANSION.decision ||
            decision == TerritoryPhase.TOKEN.decision

    fun choose(
        view: GameView,
        observation: BotObservation,
        seed: Int,
        referenceCommand: () -> String,
    ): String {
        require(controls(observation.decision)) {
            "The coordinated selector controls only C/P/E/T."
        }
        if (joint.controls(observation.decision)) return joint.choose(view, observation, seed)
        val recommended = referenceCommand()
        val player = PlayerId(observation.playerId)
        if (observation.decision == TerritoryPhase.EXPANSION.decision) {
            val encoded =
                ExpansionObservationEncoder.from(
                    view,
                    player,
                    observation,
                    recommended,
                    TerritoryEncoder.EXPANSION_2,
                )
            return IncumbentResidualPolicy.choose(
                    encoded,
                    current.expansion,
                    original.expansion,
                    expansionSelector,
                )
                .command
        }
        val encoded =
            TokenObservationEncoder.from(
                view,
                player,
                observation,
                recommended,
                TerritoryEncoder.TOKEN_2,
            )
        val deterministic =
            IncumbentResidualPolicy.choose(
                encoded,
                current.token,
                original.token,
                tokenSelector,
            )
        return tokenSampler?.let {
            NearOptimalTokenPolicy.choose(encoded, deterministic, it, seed).command
        } ?: deterministic.command
    }
}
