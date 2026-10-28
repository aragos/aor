package dev.aragos.aor

/**
 * Static map facts, independent of ownership, movement rules and drawing geometry. [capitalOrder]
 * lists the first three capitals, then those added for four, five and six players.
 * [areaMinimumPlayers] determines province availability; overseas boxes remain available.
 * [seaAreas] lists this map's seas with their optional geographical areas; omitted seas do not
 * exist. Construction validates the complete graph; it does not certify a draft for game selection.
 */
data class BoardMapDefinition(
    val provinces: List<ProvinceDefinition>,
    val landBorders: Set<ProvinceConnection>,
    val coastalConnections: Set<ProvinceConnection>,
    val satelliteSupports: Set<SatelliteSupport>,
    val seaCoasts: Set<SeaCoastConnection>,
    val capitalOrder: List<ProvinceId> = CapitalData.definitions.map { ProvinceId(it.id) },
    val areaMinimumPlayers: Map<GeographicalArea, Int> =
        GeographicalArea.entries.associateWith(GeographicalArea::minimumPlayers),
    val seaAreas: Map<SeaArea, GeographicalArea?> = SeaArea.entries.associateWith(SeaArea::area),
) {
    val byId: Map<ProvinceId, ProvinceDefinition> by lazy {
        provinces.associateBy(ProvinceDefinition::id)
    }

    fun province(id: ProvinceId): ProvinceDefinition =
        requireNotNull(byId[id]) { "Unknown province on this map: $id." }

    init {
        validateConnections()
        require(areaMinimumPlayers.keys == GeographicalArea.entries.toSet()) {
            "Declare a minimum player count for every geographical area."
        }
        require(areaMinimumPlayers.values.all { it in 3..6 }) {
            "Area availability requires three through six players."
        }
        require(seaCoasts.all { it.sea in seaAreas }) {
            "Every sea coast must reference a sea present on this map."
        }
        require(capitalOrder.size in 3..6 && capitalOrder.distinct() == capitalOrder) {
            "Declare three through six distinct capitals in registration-count order."
        }
        require(
            provinces.filter { it.capital != null }.map(ProvinceDefinition::id).toSet() ==
                capitalOrder.toSet()
        ) {
            "Capital order must name exactly the map's capital provinces."
        }
        for (playerCount in 3..capitalOrder.size) {
            require(
                availableFor(playerCount)
                    .filter { it.capital != null }
                    .map(ProvinceDefinition::id)
                    .toSet() == capitalOrder.take(playerCount).toSet()
            ) {
                "Available provinces and capital order disagree for $playerCount players."
            }
        }
    }

    /** The supported registration range is three through the number of declared capitals. */
    fun availableFor(playerCount: Int): List<ProvinceDefinition> {
        require(playerCount in 3..capitalOrder.size) {
            "This map supports three through ${capitalOrder.size} players."
        }
        return provinces.filter { province ->
            province.area?.let { area ->
                playerCount >=
                    requireNotNull(areaMinimumPlayers[area]) {
                        "Missing minimum player count for area $area."
                    }
            } ?: true
        }
    }

    private fun validateConnections() {
        val byId = provinces.associateBy(ProvinceDefinition::id)
        require(byId.size == provinces.size) { "Connection validation requires unique provinces." }
        fun province(id: ProvinceId): ProvinceDefinition =
            requireNotNull(byId[id]) {
                "A board connection references an unknown province: $id."
            }
        for (connection in landBorders) {
            require(
                province(connection.first).overseas == null &&
                    province(connection.second).overseas == null
            ) {
                "Overseas boxes cannot have land borders: $connection."
            }
        }
        for (connection in coastalConnections) {
            val first = province(connection.first)
            val second = province(connection.second)
            require(
                first.coastal && second.coastal && first.overseas == null && second.overseas == null
            ) {
                "Coastal-water edges require two coastal main-map provinces: $connection."
            }
        }
        for (support in satelliteSupports) {
            require(province(support.satellite).isSatellite) {
                "Only a satellite can originate a support arrow: ${support.satellite}."
            }
            require(
                !province(support.market).isSatellite && province(support.market).overseas == null
            ) {
                "A support arrow must point to a main-map market: ${support.market}."
            }
        }
        for (connection in seaCoasts) {
            require(
                province(connection.province).coastal &&
                    province(connection.province).overseas == null
            ) {
                "A sea coast requires a coastal main-map province: ${connection.province}."
            }
        }
    }
}

/** Reviewed standard-map facts used as the starting point for a separate draft. */
object StandardMapData {
    val definition: BoardMapDefinition by lazy {
        BoardConnectionData.validate(ProvinceData.definitions)
    }
}
