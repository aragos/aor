package dev.aragos.aor.ai

/**
 * Development-calibrated state-free diagnostic. Probabilities request a nonbaseline pass or
 * other-card override, with the remaining mass requesting F. They are unconditional request
 * frequencies, not frequencies renormalized over available families in the current observation. An
 * unavailable requested family falls back to F. This is not a production policy qualification.
 */
data class CardFrequencyModel(
    val schemaVersion: Int = 1,
    val encoderVersion: String = CardObservationEncoder.ENCODER_VERSION,
    val selectorVersion: String = CardFrequencyControl.SELECTOR_VERSION,
    val proposalVersion: String = CardProposals.VERSION,
    val passProbability: Double,
    val otherProbability: Double,
) {
    init {
        require(
            schemaVersion == 1 &&
                encoderVersion == CardObservationEncoder.ENCODER_VERSION &&
                selectorVersion == CardFrequencyControl.SELECTOR_VERSION &&
                proposalVersion == CardProposals.VERSION
        ) {
            "Unsupported card frequency schema, encoder, selector or proposal version."
        }
        require(
            passProbability.isFinite() &&
                otherProbability.isFinite() &&
                passProbability >= 0.0 &&
                otherProbability >= 0.0 &&
                passProbability + otherProbability <= 1.0
        ) {
            "Card frequency request probabilities must be nonnegative with total at most one."
        }
    }
}

enum class CardFrequencyFamily {
    BASELINE,
    PASS,
    OTHER,
}

/** [requestedFamily] can differ from the selected action when that family is unavailable. */
data class CardFrequencyChoice(
    val index: Int,
    val command: String,
    val requestedFamily: CardFrequencyFamily,
    val overridden: Boolean,
)

/** Same legal proposal sampler as the learned selector, with no learned state preferences. */
object CardFrequencyControl {
    const val SELECTOR_VERSION = "aor-card-frequency-1"

    fun seed(botSeed: Int): Int = botSeed xor 0x1f83d9ab

    fun choose(
        observation: CardObservation,
        model: CardFrequencyModel,
        botSeed: Int,
    ): CardFrequencyChoice {
        if (!CardAdvantagePolicy.supports(observation)) {
            return choice(observation, observation.baselineIndex, CardFrequencyFamily.BASELINE)
        }
        val proposals = CardProposals.indices(observation, botSeed)
        val random = CardProposalRandom(seed(botSeed))
        val uniform = random.uniform()
        val family =
            when {
                uniform < model.passProbability -> CardFrequencyFamily.PASS
                uniform < model.passProbability + model.otherProbability ->
                    CardFrequencyFamily.OTHER
                else -> CardFrequencyFamily.BASELINE
            }
        val alternatives = proposals.filter { it != observation.baselineIndex }
        val selected =
            when (family) {
                CardFrequencyFamily.BASELINE -> observation.baselineIndex
                CardFrequencyFamily.PASS ->
                    alternatives.firstOrNull { observation.actions[it].command == "pass" }
                        ?: observation.baselineIndex
                CardFrequencyFamily.OTHER -> {
                    val others = alternatives.filter { observation.actions[it].command != "pass" }
                    if (others.isEmpty()) observation.baselineIndex
                    else others[random.index(others.size)]
                }
            }
        return choice(observation, selected, family)
    }

    private fun choice(
        observation: CardObservation,
        index: Int,
        family: CardFrequencyFamily,
    ): CardFrequencyChoice =
        CardFrequencyChoice(
            index,
            observation.actions[index].command,
            family,
            index != observation.baselineIndex,
        )
}
