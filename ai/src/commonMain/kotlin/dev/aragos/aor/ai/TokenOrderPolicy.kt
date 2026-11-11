package dev.aragos.aor.ai

/**
 * Token-only action-value selector. The frozen ordinary policy supplies the actual baseline;
 * learning compares at most eight offered alternatives and must exceed a fixed return margin.
 * Neither pending rival amounts nor an authority world enters proposal generation or inference.
 */
object TokenOrderPolicy {
    const val PROPOSAL_VERSION = "token-order-proposals-1"
    const val SELECTOR_VERSION = "token-order-selector-1"
    const val MINIMUM_ADVANTAGE = 0.02
    const val RESIDUAL_MODEL_KIND = "token-order-fixed-reference-residual-1"
    const val REFERENCE_NEIGHBOR_PROPOSAL_VERSION = "token-order-reference-neighbor-proposals-1"
    const val REFERENCE_NEIGHBOR_SELECTOR_VERSION = "token-order-reference-neighbor-selector-1"
    const val REFERENCE_NEIGHBOR_MODEL_KIND = "token-order-reference-neighbor-residual-1"

    /** Baseline first, its offered integer neighbors, then the original six proposals. */
    fun proposedIndices(
        ordinary: BotObservation,
        observation: TerritoryObservation,
    ): List<Int> {
        require(observation.encoderVersion == TerritoryEncoder.TOKEN_2.version) {
            "Token-order proposals require token observation version 2."
        }
        val command = observation.actions[observation.baselineIndex].command
        val baseline =
            requireNotNull(ordinary.candidates.singleOrNull { it.command == command }) {
                "The token-order baseline must belong to the ordinary offered candidates."
            }
        val amount =
            requireNotNull(command.substringAfter("buy-tokens ").toIntOrNull()) {
                "A token-order baseline requires a canonical signed purchase."
            }
        val byCommand = observation.actions.withIndex().associate { it.value.command to it.index }
        val neighbors =
            listOf(-1L, 1L).mapNotNull { delta ->
                byCommand["buy-tokens ${amount.toLong() + delta}"]
            }
        return (listOf(observation.baselineIndex) +
                neighbors +
                BotTokenValue.alternatives(ordinary, baseline).map { candidate ->
                    requireNotNull(byCommand[candidate.command]) {
                        "Every token-order proposal requires its authoritative encoded quote."
                    }
                })
            .distinct()
    }

    /**
     * Experimental fixed-T proposals retain the ordinary encoded baseline. When T differs, include
     * ordinary, T and its offered integer neighbors before the six ordinary diverse proposals.
     * Otherwise preserve the old proposal order exactly. At 16 -> 15 the union has at most eight
     * entries because ordinary is already T + 1; no command is synthesized or clipped to an offer.
     */
    fun referenceNeighborIndices(
        ordinary: BotObservation,
        observation: TerritoryObservation,
    ): List<Int> {
        val original = proposedIndices(ordinary, observation)
        val reference = TokenOrderReference.FIXED_16_TO_15.index(observation)
        if (reference == observation.baselineIndex) return original
        val byCommand = observation.actions.withIndex().associate { it.value.command to it.index }
        val command = observation.actions[observation.baselineIndex].command
        val baseline = ordinary.candidates.single { it.command == command }
        val amount = observation.actions[reference].command.substringAfter("buy-tokens ").toLong()
        val neighbors = listOf(-1L, 1L).mapNotNull { byCommand["buy-tokens ${amount + it}"] }
        val diverse =
            BotTokenValue.alternatives(ordinary, baseline).map { candidate ->
                requireNotNull(byCommand[candidate.command]) {
                    "Every reference-neighbor proposal requires its authoritative encoded quote."
                }
            }
        return (listOf(observation.baselineIndex, reference) + neighbors + diverse).distinct()
    }

    fun choose(
        ordinary: BotObservation,
        observation: TerritoryObservation,
        model: TerritoryValueModel,
    ): String = chooseAgainst(ordinary, observation, model, TokenOrderReference.ORDINARY)

    /** Preserve ordinary-centered proposals while requiring improvement over the fixed fallback. */
    fun chooseResidual(
        ordinary: BotObservation,
        observation: TerritoryObservation,
        model: TerritoryValueModel,
    ): String = chooseAgainst(ordinary, observation, model, TokenOrderReference.FIXED_16_TO_15)

    /** New proposal semantics require a separately tagged residual model and fixed-T fallback. */
    fun chooseReferenceNeighbors(
        ordinary: BotObservation,
        observation: TerritoryObservation,
        model: TerritoryValueModel,
    ): String =
        chooseAgainst(
            ordinary,
            observation,
            model,
            TokenOrderReference.FIXED_16_TO_15,
            referenceNeighborIndices(ordinary, observation),
        )

    private fun chooseAgainst(
        ordinary: BotObservation,
        observation: TerritoryObservation,
        model: TerritoryValueModel,
        reference: TokenOrderReference,
        proposals: List<Int> = proposedIndices(ordinary, observation),
    ): String {
        val values = TerritoryValuePolicy.evaluate(observation, model, epsilon = 0.0).values
        val baseline = reference.index(observation)
        require(baseline in proposals) {
            "Token proposals must contain their exact reference action."
        }
        val best = proposals.maxBy { values[it] }
        val selected = if (values[best] - values[baseline] > MINIMUM_ADVANTAGE) best else baseline
        return observation.actions[selected].command
    }
}
