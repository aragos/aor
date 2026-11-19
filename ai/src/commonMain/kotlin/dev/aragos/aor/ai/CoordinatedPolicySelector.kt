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
 * Every input is the acting player's ordinary view. The opt-in TOKEN3 residual sees bounded public
 * past reveals. [referenceCommand] in [choose] is called exactly once for E/T, never for C/P. It
 * must return the offered command from the complete qualified raw reference, before this outer
 * residual selector.
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
    private val expansionDenial: PublicLeaderAttackConfig? = null,
    private val tokenNeighborMixture: LocalTokenNeighborMixture? = null,
    private val tokenAnchoredResidual: TokenAnchoredResidual? = null,
    private val initiative: InitiativePolicyConfig? = null,
    private val leadingRivalRetargeting: LeadingRivalRetargetingConfig? = null,
    private val commodityLeadTiming: CommodityLeadTimingConfig? = null,
) {
    private val joint =
        JointPolicySelector(ordinary, purchaseBaseline, current.card, current.purchase, support)

    init {
        require(
            initiative == null ||
                tokenAnchoredResidual == null &&
                    tokenNeighborMixture == null &&
                    expansionDenial == null
        ) {
            "Initiative requires the regular frozen token and expansion selectors."
        }
        require(tokenAnchoredResidual == null || tokenNeighborMixture == null) {
            "The offered-only anchored actor cannot also admit a token-neighbor mixture."
        }
        require(tokenAnchoredResidual == null || tokenSampler != null) {
            "An anchored token residual requires its explicitly frozen anchor sampler."
        }
        require(
            tokenAnchoredResidual == null || tokenAnchoredResidual.extendsAnchor(current.token)
        ) {
            "The anchored residual must preserve the complete frozen TOKEN2 dictionary."
        }
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
    ): String = choose(view, observation, seed, referenceCommand, null)

    fun choose(
        view: GameView,
        observation: BotObservation,
        seed: Int,
        referenceCommand: () -> String,
        tokenQuote: TokenNeighborQuote?,
    ): String {
        require(controls(observation.decision)) {
            "The coordinated selector controls only C/P/E/T."
        }
        if (joint.controls(observation.decision)) {
            val normal = joint.choose(view, observation, seed)
            return commodityLeadTiming?.let {
                CommodityLeadTiming.choose(view, observation, normal, it)
            } ?: normal
        }
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
            val deterministic =
                IncumbentResidualPolicy.choose(
                    encoded,
                    current.expansion,
                    original.expansion,
                    expansionSelector,
                )
            val selected =
                initiative?.let {
                    InitiativePolicy.expansion(view, player, encoded, deterministic, it).command
                }
                    ?: expansionDenial?.let {
                        PublicLeaderAttackPolicy.choose(view, player, encoded, deterministic, it)
                            .command
                    }
                    ?: deterministic.command
            return leadingRivalRetargeting?.let {
                LeadingRivalRetargeting.choose(view, player, observation, selected, it)
            } ?: selected
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
        if (tokenAnchoredResidual != null) {
            val learned = tokenAnchoredResidual.observation(view, player, encoded)
            val probabilities =
                NearOptimalTokenPolicy.select(
                        encoded,
                        deterministic,
                        requireNotNull(tokenSampler) { "The frozen token sampler is required." },
                        0.0,
                    )
                    .probabilities
            return TokenAnchoredPolicy.choose(
                    learned,
                    TokenAnchoredPolicy.evaluate(
                        learned,
                        probabilities,
                        tokenAnchoredResidual,
                        deterministic.index,
                    ),
                    seed,
                )
                .command
        }
        if (tokenNeighborMixture != null && tokenNeighborMixture.departureProbability > 0.0) {
            val probabilities =
                tokenSampler?.let {
                    NearOptimalTokenPolicy.select(encoded, deterministic, it, 0.0).probabilities
                } ?: encoded.actions.indices.map { if (it == deterministic.index) 1.0 else 0.0 }
            // TOKEN2 sorts canonical commands lexically; ordinary quotes retain signed numeric
            // order. Match each probability to its command before applying the departure kernel.
            val byCommand =
                encoded.actions
                    .mapIndexed { index, action ->
                        action.command to probabilities[index]
                    }
                    .toMap()
            val offeredProbabilities =
                observation.candidates.map { candidate ->
                    requireNotNull(byCommand[candidate.command]) {
                        "Every original token offer must retain its exact encoded baseline probability."
                    }
                }
            return LocalTokenNeighborPolicy.distribution(
                    observation,
                    offeredProbabilities,
                    tokenNeighborMixture,
                    tokenQuote,
                )
                .command(ResidualPolicyMath.uniform(seed))
        }
        val normal =
            tokenSampler?.let {
                NearOptimalTokenPolicy.choose(encoded, deterministic, it, seed).command
            } ?: deterministic.command
        return initiative?.let { InitiativePolicy.bid(view, player, normal, seed, it) } ?: normal
    }
}
