package dev.aragos.aor.ai

/**
 * Actor-visible card facts before the browser binds the exact ordinary policy recommendation. This
 * transient payload contains every offered command in canonical order, with no model, authority
 * history, chance seed or fabricated baseline index. It is never persisted in a save.
 */
data class CardProjection(
    val encoderVersion: String = CardObservationEncoder.ENCODER_VERSION,
    val stateFeatures: Map<String, Double>,
    val actions: List<CardAction>,
) {
    init {
        requireCardProjection(encoderVersion, stateFeatures, actions)
    }

    /**
     * Bind one actually offered recommendation without changing projected facts or action order.
     */
    fun bindBaseline(command: String): CardObservation =
        CardObservation(
            encoderVersion = encoderVersion,
            stateFeatures = stateFeatures,
            actions = actions,
            baselineIndex = actions.indexOfFirst { it.command == command },
        )
}
