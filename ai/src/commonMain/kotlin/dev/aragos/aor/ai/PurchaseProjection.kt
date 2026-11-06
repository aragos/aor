package dev.aragos.aor.ai

/**
 * Actor-safe purchase facts before a particular policy supplies its recommendation. Commands are
 * the complete offered purchase set in canonical order. This transient work payload contains no
 * model, random seed, game identity or fabricated baseline index; it is never a saved-game field.
 */
data class PurchaseProjection(
    val encoderVersion: String = PurchaseObservationEncoder.ENCODER_VERSION,
    val stateFeatures: Map<String, Double>,
    val actions: List<PurchaseAction>,
) {
    init {
        requirePurchaseProjection(encoderVersion, stateFeatures, actions)
    }

    /** Bind the exact frozen baseline's actual recommendation without changing projected facts. */
    fun bindBaseline(command: String): PurchaseObservation =
        PurchaseObservation(
            encoderVersion = encoderVersion,
            stateFeatures = stateFeatures,
            actions = actions,
            baselineIndex = actions.indexOfFirst { it.command == command },
        )
}
