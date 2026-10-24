package dev.aragos.aor

/** Static baseline schedules, before advance abilities or any other state-dependent modifiers. */
object EconomyTables {
    const val shipUpgradeCost: Int = 10

    /**
     * Income in dollars, indexed by domination markers on the map, starting at zero. [playerCount]
     * is the starting player count already validated by game setup.
     */
    fun incomeFor(playerCount: Int): List<Int> {
        return (0..25).map { markers -> 15 + markers * playerCount }
    }

    /** Triangular stabilization cost in dollars; hands have no size limit. */
    fun stabilizationCost(handSize: Int): Long {
        require(handSize >= 0) { "Stabilization requires a non-negative hand size." }
        return handSize.toLong() * (handSize.toLong() + 1) / 2
    }

    /** Existing verified track definitions are the single source for legal fleet levels. */
    val shipping: List<ShippingCapability> = ShippingCapability.entries.toList()
    val misery: List<MiseryStep> = MiseryStep.entries.toList()
}
