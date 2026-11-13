package dev.aragos.aor.ai

/**
 * Own/public expansion facts before the current E controller supplies its recommendation. The
 * complete offered commands retain canonical order. This transient work payload contains no
 * model, seed, game identity, authority state or placeholder baseline; it is never saved game state.
 */
data class ExpansionProjection(
    val encoderVersion: String,
    val stateFeatures: Map<String, Double>,
    val actions: List<TerritoryAction>,
) {
    init {
        require(TerritoryEncoder.fromVersion(encoderVersion).phase == TerritoryPhase.EXPANSION) {
            "An expansion projection requires an expansion encoder."
        }
        requireTerritoryProjection(encoderVersion, stateFeatures, actions)
    }

    /** Bind the actual offered E recommendation without changing any public fact or action. */
    fun bindBaseline(command: String): TerritoryObservation =
        TerritoryObservation(
            encoderVersion = encoderVersion,
            stateFeatures = stateFeatures,
            actions = actions,
            baselineIndex = actions.indexOfFirst { it.command == command },
        )
}
