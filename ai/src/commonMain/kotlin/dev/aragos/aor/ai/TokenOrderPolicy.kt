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

    fun choose(
        ordinary: BotObservation,
        observation: TerritoryObservation,
        model: TerritoryValueModel,
    ): String {
        val proposals = proposedIndices(ordinary, observation)
        val values = TerritoryValuePolicy.evaluate(observation, model, epsilon = 0.0).values
        val baseline = observation.baselineIndex
        val best = proposals.maxBy { values[it] }
        val selected = if (values[best] - values[baseline] > MINIMUM_ADVANTAGE) best else baseline
        return observation.actions[selected].command
    }
}
