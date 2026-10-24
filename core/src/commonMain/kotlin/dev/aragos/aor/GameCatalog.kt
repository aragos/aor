package dev.aragos.aor

/**
 * A static board restricted to the starting player count. Overseas boxes remain available subject
 * to their access requirements. Unused provinces and their coastal waters are removed together; sea
 * areas always remain. This contains no ownership, reachability or other game state.
 */
data class CatalogConfiguration(
    val playerCount: Int,
    val rules: RuleOptions = RuleOptions(),
) {
    init {
        require(playerCount in 3..6) { "Catalog configurations require three through six players." }
    }

    val provinces: List<ProvinceDefinition> by lazy { ProvinceData.availableFor(playerCount) }

    val provinceIds: Set<ProvinceId> by lazy { provinces.map(ProvinceDefinition::id).toSet() }

    val capitals: List<String> by lazy { CapitalData.availableFor(playerCount) }

    val seas: List<SeaArea> = SeaArea.entries.toList()

    val landBorders: Set<ProvinceConnection> by lazy {
        BoardConnectionData.landBorders.filterTo(mutableSetOf(), ::containsBoth)
    }

    val coastalConnections: Set<ProvinceConnection> by lazy {
        BoardConnectionData.coastalConnections.filterTo(mutableSetOf(), ::containsBoth)
    }

    val satelliteSupports: Set<SatelliteSupport> by lazy {
        (BoardConnectionData.satelliteSupports +
                if (rules.stGallSupport) {
                    setOf(SatelliteSupport(ProvinceId("st-gall"), ProvinceId("nuremberg")))
                } else {
                    emptySet()
                })
            .filterTo(mutableSetOf()) { support ->
                support.satellite in provinceIds && support.market in provinceIds
            }
    }

    val seaCoasts: Set<SeaCoastConnection> by lazy {
        BoardConnectionData.seaCoasts.filterTo(mutableSetOf()) { coast ->
            coast.province in provinceIds
        }
    }

    /** Open source questions are retained even when the graph is structurally valid. */
    val unresolvedReferences: List<ConnectionReviewBlocker> by lazy {
        BoardConnectionData.reviewBlockers.filter { issue -> issue.minimumPlayers <= playerCount }
    }

    private fun containsBoth(connection: ProvinceConnection): Boolean =
        connection.first in provinceIds && connection.second in provinceIds
}

/** Cross-catalog validation, separate from both gameplay and human transcription review. */
object GameCatalog {
    /**
     * Rejects a configuration with unresolved source data. This is a data readiness guard, not
     * approval of the transcription; the review inventory must also be checked by a reviewer.
     */
    fun requireResolvedFor(
        playerCount: Int,
        rules: RuleOptions = RuleOptions(),
    ): CatalogConfiguration {
        val configuration = validateFor(playerCount, rules)
        require(configuration.unresolvedReferences.isEmpty()) {
            "Catalog references remain unresolved for $playerCount players: " +
                configuration.unresolvedReferences.joinToString { issue -> issue.id }
        }
        return configuration
    }

    fun validateFor(
        playerCount: Int,
        rules: RuleOptions = RuleOptions(),
    ): CatalogConfiguration {
        val configuration = CatalogConfiguration(playerCount, rules)
        val definitions = ProvinceData.definitions
        BoardConnectionData.validate(definitions)
        require(definitions.map(ProvinceDefinition::id).distinct().size == definitions.size) {
            "Province identifiers must be unique across the board."
        }
        require(
            definitions.mapNotNull(ProvinceDefinition::area).toSet() ==
                GeographicalArea.entries.toSet()
        ) {
            "The board must cover all eight geographical areas."
        }
        val capitals = definitions.mapNotNull(ProvinceDefinition::capital)
        require(
            capitals.distinct().size == capitals.size &&
                capitals.toSet() == CapitalData.definitions.map(CapitalDefinition::id).toSet()
        ) {
            "Every capital must reference exactly one province."
        }
        require(
            configuration.provinces.mapNotNull(ProvinceDefinition::capital).toSet() ==
                configuration.capitals.toSet()
        ) {
            "Board areas and available capitals must agree for $playerCount players."
        }
        for (commodity in CommodityData.definitions) {
            require(
                definitions.count { province -> commodity.id in province.commodities } ==
                    commodity.ownershipTrackBoxes
            ) {
                "Commodity market assignments must match the printed ownership track for ${commodity.id}."
            }
        }
        require(
            definitions.count { province -> province.overseas == OverseasGroup.FAR_EAST } == 3
        ) {
            "The board requires all three Far East boxes."
        }
        require(
            definitions.count { province -> province.overseas == OverseasGroup.NEW_WORLD } == 2
        ) {
            "The board requires both New World boxes."
        }
        require(AdvanceData.definitions.sumOf(AdvanceDefinition::cost) == 2100) {
            "All 26 advances must total 2100 dollars."
        }
        EconomyTables.incomeFor(playerCount)
        OrderBoxes.forOrder((1..playerCount).map { number -> PlayerId("p$number") })
        return configuration
    }
}
