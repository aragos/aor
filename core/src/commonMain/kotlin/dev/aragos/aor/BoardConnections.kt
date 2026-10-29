package dev.aragos.aor

/** An undirected edge, stored in identifier order so reverse declarations denote the same edge. */
data class ProvinceConnection(val first: ProvinceId, val second: ProvinceId) {
    init {
        require(first.value < second.value) {
            "Province connection endpoints must be distinct and in identifier order: $first / $second."
        }
    }

    companion object {
        fun of(first: ProvinceId, second: ProvinceId): ProvinceConnection {
            require(first != second) { "A province cannot connect to itself: $first." }
            return if (first.value < second.value) {
                ProvinceConnection(first, second)
            } else {
                ProvinceConnection(second, first)
            }
        }
    }
}

/**
 * Printed bodies of water. [area] is null for water outside a geographical area; membership does
 * not remove seas on reduced boards. The Norwegian Sea has no effective connections under errata.
 */
enum class SeaArea(
    val isOcean: Boolean = false,
    val area: GeographicalArea? = null,
) {
    ATLANTIC_OCEAN(isOcean = true),
    NORWEGIAN_SEA,
    GREENLAND_SEA(area = GeographicalArea.II),
    NORTH_SEA,
    BALTIC_SEA(area = GeographicalArea.I),
    BAY_OF_BISCAY,
    CANARY_BASIN(area = GeographicalArea.VIII),
    WESTERN_MEDITERRANEAN,
    CENTRAL_MEDITERRANEAN(area = GeographicalArea.VIII),
    AEGEAN_SEA(area = GeographicalArea.VIII),
    EASTERN_MEDITERRANEAN(area = GeographicalArea.VI),
    ADRIATIC_SEA(area = GeographicalArea.VII),
    BLACK_SEA(area = GeographicalArea.V),
}

/** A coastal-water space incident to a sea; this is not a land border or a galley step. */
data class SeaCoastConnection(val sea: SeaArea, val province: ProvinceId)

/** Only the printed arrow direction grants support; proximity does not imply support. */
data class SatelliteSupport(val satellite: ProvinceId, val market: ProvinceId) {
    init {
        require(satellite != market) { "A satellite cannot support itself: $satellite." }
    }
}

/** A source ambiguity excludes the affected configuration from verified catalog readiness. */
data class ConnectionReviewBlocker(
    val id: String,
    val description: String,
    val minimumPlayers: Int,
) {
    init {
        require(id.isNotBlank()) { "A connection review blocker requires an identifier." }
        require(description.isNotBlank()) { "A connection review blocker requires an explanation." }
        require(minimumPlayers in 3..6) { "Connection review requires three through six players." }
    }
}

/** Typed declarations distinguish land, galley coastal-water steps, seas and support arrows. */
@GameDataDsl
class BoardConnectionBuilder {
    internal val landBorders = linkedSetOf<ProvinceConnection>()
    internal val coastalConnections = linkedSetOf<ProvinceConnection>()
    internal val satelliteSupports = linkedSetOf<SatelliteSupport>()
    internal val seaCoasts = linkedSetOf<SeaCoastConnection>()

    fun land(from: String, vararg neighbors: String) {
        require(neighbors.isNotEmpty()) { "Declare at least one land neighbor for $from." }
        for (neighbor in neighbors) {
            require(
                landBorders.add(ProvinceConnection.of(ProvinceId(from), ProvinceId(neighbor)))
            ) {
                "Duplicate land border: $from / $neighbor."
            }
        }
    }

    fun coast(from: String, vararg neighbors: String) {
        require(neighbors.isNotEmpty()) { "Declare at least one coastal-water neighbor for $from." }
        for (neighbor in neighbors) {
            require(
                coastalConnections.add(
                    ProvinceConnection.of(ProvinceId(from), ProvinceId(neighbor))
                )
            ) {
                "Duplicate coastal-water connection: $from / $neighbor."
            }
        }
    }

    fun supports(satellite: String, vararg markets: String) {
        require(markets.isNotEmpty()) { "Declare at least one supported market for $satellite." }
        for (market in markets) {
            require(
                satelliteSupports.add(SatelliteSupport(ProvinceId(satellite), ProvinceId(market)))
            ) {
                "Duplicate satellite support: $satellite -> $market."
            }
        }
    }

    fun sea(sea: SeaArea, vararg provinces: String) {
        require(provinces.isNotEmpty()) { "Declare at least one coastal province for $sea." }
        for (province in provinces) {
            require(seaCoasts.add(SeaCoastConnection(sea, ProvinceId(province)))) {
                "Duplicate sea coast: $sea / $province."
            }
        }
    }
}

/**
 * Photo transcription with AH May 1998 map errata applied. Source rulings and any unresolved
 * [reviewBlockers] are documented in the connection inventory. This catalog records static
 * topology; it does not implement shipping range, access advances or expansion.
 */
object BoardConnectionData {
    private val declared =
        BoardConnectionBuilder().apply {
            // Northern Europe and British Isles: board upper-left and upper-center.
            land("bergen", "kongsberg")
            land("kongsberg", "malmo")
            land("malmo", "stockholm")
            land("copenhagen", "hamburg")
            land("hamburg", "lubeck", "amsterdam", "cologne")
            land("lubeck", "stettin", "cologne", "nuremberg", "prague")
            land("stettin", "danzig", "breslau", "prague")
            land("danzig", "mitau", "breslau")
            land("mitau", "riga")
            land("riga", "novgorod")
            land("novgorod", "smolensk")
            land("smolensk", "kiev")
            land("edinburgh", "york", "chester")
            land("york", "chester", "london")
            land("chester", "wales", "portsmouth")
            land("wales", "cornwall")
            land("cornwall", "portsmouth")
            land("portsmouth", "london")
            land("armagh", "waterford")

            // Central Europe: board center, geographical boundaries do not break land borders.
            land("amsterdam", "bruges", "cologne")
            land("bruges", "cologne", "strasbourg", "paris")
            land("cologne", "nuremberg", "strasbourg")
            land("nuremberg", "strasbourg", "st-gall", "salzburg", "prague")
            land("strasbourg", "paris", "dijon", "basel", "st-gall")
            land("basel", "dijon", "lyons", "milan", "st-gall")
            land("st-gall", "salzburg", "milan", "venice")
            land("salzburg", "prague", "vienna", "venice")
            land("prague", "breslau", "vienna")
            land("breslau", "vienna")
            land("vienna", "budapest", "esseg", "venice")
            land("budapest", "esseg", "belgrade")
            land("esseg", "venice", "dubrovnik", "belgrade")
            land("paris", "st-malo", "bordeaux", "dijon")
            land("st-malo", "bordeaux")
            land("dijon", "bordeaux", "lyons")

            // Iberia, France south of the area boundary and Italy: board lower-left and center.
            land("leon", "basque", "toledo", "lisbon")
            land("basque", "toledo", "barcelona", "toulouse")
            land("lisbon", "toledo", "seville")
            land("toledo", "barcelona", "valencia", "granada", "seville")
            land("barcelona", "valencia", "toulouse", "montpellier")
            land("valencia", "granada")
            land("seville", "granada")
            land("bordeaux", "toulouse", "montpellier", "lyons")
            land("toulouse", "montpellier")
            land("montpellier", "lyons", "marseilles")
            land("lyons", "marseilles", "genoa", "milan")
            land("marseilles", "genoa")
            land("milan", "genoa", "venice")
            land("genoa", "venice", "florence", "rome")
            land("venice", "florence", "dubrovnik")
            land("florence", "rome")
            land("rome", "naples")
            land("naples", "bari")
            land("dubrovnik", "belgrade")
            land("belgrade", "durazzo")

            // Eastern Europe, Anatolia and the Levant: board right-hand side.
            land("kiev", "kaffa")
            land("kaffa", "varna", "tana")
            land("tana", "kamyshin", "abagia")
            land("kamyshin", "sarai")
            land("abagia", "poti")
            land("poti", "erzerum")
            land("erzerum", "trebizond")
            land("trebizond", "angora")
            land("varna", "gallipoli")
            land("durazzo", "salonika", "corfu")
            land("salonika", "gallipoli", "corfu", "athens")
            land("corfu", "athens")
            land("constantinople", "smyrna", "angora")
            land("smyrna", "adalia")
            land("adalia", "taurus")
            land("taurus", "aleppo")
            land("aleppo", "levant")
            land("levant", "acre")
            land("acre", "jerusalem")
            land("jerusalem", "suez")
            land("suez", "cairo")
            land("cairo", "alexandria")
            land("alexandria", "libya")
            land("libya", "barca")
            land("barca", "tripoli")
            land("tripoli", "tunis")
            land("tunis", "algiers")
            land("algiers", "oran")
            land("oran", "fez")

            // Coastal-water boundaries, separate from the land graph.
            coast("bergen", "kongsberg")
            coast("shetland-islands", "bergen")
            coast("kongsberg", "malmo", "copenhagen")
            coast("malmo", "stockholm", "copenhagen", "stettin")
            coast("stockholm", "visby")
            coast("copenhagen", "hamburg", "lubeck", "stettin")
            coast("hamburg", "lubeck", "amsterdam")
            coast("lubeck", "stettin")
            coast("stettin", "danzig")
            coast("danzig", "mitau")
            coast("mitau", "riga")
            coast("riga", "novgorod")
            coast("edinburgh", "shetland-islands", "armagh", "chester", "york")
            coast("armagh", "waterford", "chester", "wales")
            coast("waterford", "wales")
            coast("chester", "wales")
            coast("wales", "cornwall")
            coast("cornwall", "portsmouth")
            coast("portsmouth", "london", "paris", "st-malo")
            coast("london", "york", "paris", "bruges")
            coast("amsterdam", "bruges")
            coast("bruges", "paris")
            coast("paris", "st-malo")
            coast("st-malo", "bordeaux")
            coast("bordeaux", "toulouse")
            coast("toulouse", "basque")
            coast("basque", "leon")
            coast("leon", "lisbon")
            coast("lisbon", "seville")
            coast("seville", "granada", "fez")
            coast("granada", "valencia", "fez")
            coast("valencia", "barcelona", "palma")
            coast("barcelona", "palma", "montpellier")
            coast("montpellier", "marseilles")
            coast("marseilles", "genoa", "cagliari")
            coast("genoa", "rome", "cagliari")
            coast("rome", "naples")
            coast("naples", "bari")
            coast("bari", "sicily", "corfu", "durazzo", "belgrade")
            coast("venice", "florence", "dubrovnik")
            coast("florence", "rome")
            coast("belgrade", "dubrovnik", "durazzo")
            coast("durazzo", "corfu")
            coast("corfu", "athens")
            coast("athens", "salonika", "crete")
            coast("salonika", "gallipoli")
            // User ruling, 2026-10-23: Gallipoli and Angora do not touch.
            coast("gallipoli", "varna", "constantinople")
            coast("constantinople", "smyrna", "angora")
            coast("smyrna", "adalia")
            coast("adalia", "taurus", "cyprus")
            coast("taurus", "cyprus", "aleppo")
            coast("cyprus", "aleppo", "levant")
            coast("aleppo", "levant")
            coast("levant", "acre")
            coast("acre", "jerusalem")
            coast("jerusalem", "suez")
            coast("suez", "cairo")
            coast("cairo", "alexandria")
            coast("alexandria", "libya")
            coast("libya", "barca")
            coast("barca", "tripoli")
            coast("tripoli", "tunis")
            // User ruling, 2026-10-25: Tunis and Sicily share a coastal-water boundary.
            coast("tunis", "algiers", "sicily")
            coast("algiers", "oran")
            coast("oran", "fez")
            coast("varna", "kaffa")
            coast("kaffa", "tana")
            coast("tana", "abagia")
            coast("abagia", "poti")
            coast("poti", "erzerum")
            coast("erzerum", "trebizond")
            coast("trebizond", "angora")

            // Printed support arrows, never inferred from a common land/coastal boundary.
            supports("kongsberg", "bergen")
            supports("shetland-islands", "bergen", "edinburgh")
            supports("armagh", "edinburgh", "chester", "waterford")
            supports("wales", "chester", "waterford")
            supports("cornwall", "chester", "portsmouth")
            supports("malmo", "stockholm")
            supports("copenhagen", "hamburg", "lubeck")
            supports("visby", "stockholm")
            supports("stettin", "danzig", "prague")
            supports("mitau", "danzig", "riga")
            supports("smolensk", "novgorod", "kiev")
            supports("amsterdam", "hamburg", "bruges", "cologne")
            supports("breslau", "danzig", "prague", "vienna")
            supports("salzburg", "nuremberg", "prague", "vienna", "venice")
            supports("st-gall", "strasbourg", "milan", "venice")
            supports("esseg", "vienna", "budapest", "belgrade", "dubrovnik")
            supports("leon", "basque", "lisbon", "toledo")
            supports("palma", "barcelona", "valencia")
            supports("toulouse", "bordeaux", "montpellier", "basque", "barcelona")
            supports("cagliari", "marseilles", "genoa")
            supports("bari", "naples", "sicily")
            supports("kaffa", "kiev", "tana", "varna")
            supports("kamyshin", "tana", "sarai")
            supports("abagia", "tana", "poti")
            supports("durazzo", "belgrade", "salonika")
            supports("gallipoli", "varna", "salonika", "constantinople")
            supports("corfu", "salonika")
            supports("athens", "salonika", "crete")
            supports("adalia", "smyrna", "cyprus")
            supports("taurus", "aleppo", "cyprus")
            supports("levant", "aleppo", "cyprus")
            supports("jerusalem", "acre", "suez")
            supports("libya", "alexandria")
            supports("barca", "tripoli")
            supports("oran", "algiers", "fez")

            // Sea incidence. Errata explicitly extends Biscay and the Eastern Mediterranean.
            sea(SeaArea.GREENLAND_SEA, "iceland", "edinburgh", "shetland-islands")
            sea(
                SeaArea.NORTH_SEA,
                "bergen",
                "kongsberg",
                "shetland-islands",
                "copenhagen",
                "hamburg",
                "amsterdam",
                "bruges",
                "london",
                "york",
                "edinburgh",
            )
            sea(
                SeaArea.BALTIC_SEA,
                "malmo",
                "stockholm",
                "visby",
                "stettin",
                "danzig",
                "mitau",
                "riga",
                "novgorod",
            )
            sea(
                SeaArea.BAY_OF_BISCAY,
                "edinburgh",
                "armagh",
                "waterford",
                "wales",
                "cornwall",
                "portsmouth",
                "st-malo",
                "bordeaux",
                "toulouse",
                "basque",
                "leon",
                "lisbon",
                "seville",
            )
            sea(SeaArea.CANARY_BASIN, "seville", "fez", "west-africa")
            sea(
                SeaArea.WESTERN_MEDITERRANEAN,
                "granada",
                "valencia",
                "barcelona",
                "palma",
                "montpellier",
                "marseilles",
                "genoa",
                "cagliari",
                "rome",
                "naples",
                "bari",
                "sicily",
                "tunis",
                "algiers",
                "oran",
                "fez",
            )
            sea(
                SeaArea.ADRIATIC_SEA,
                "venice",
                "florence",
                "rome",
                "naples",
                "bari",
                "dubrovnik",
                "belgrade",
            )
            sea(
                SeaArea.CENTRAL_MEDITERRANEAN,
                "bari",
                "sicily",
                "tunis",
                "tripoli",
                "barca",
                "corfu",
                "athens",
                "crete",
            )
            sea(
                SeaArea.AEGEAN_SEA,
                "salonika",
                "gallipoli",
                "constantinople",
                "smyrna",
                "athens",
                "crete",
            )
            sea(
                SeaArea.EASTERN_MEDITERRANEAN,
                "barca",
                "libya",
                "alexandria",
                "cairo",
                "suez",
                "jerusalem",
                "acre",
                "levant",
                "cyprus",
                "adalia",
                "smyrna",
                "crete",
            )
            sea(
                SeaArea.BLACK_SEA,
                "varna",
                "kaffa",
                "tana",
                "abagia",
                "poti",
                "erzerum",
                "trebizond",
                "angora",
                "constantinople",
                "gallipoli",
            )
        }

    val landBorders: Set<ProvinceConnection> = declared.landBorders.toSet()
    val coastalConnections: Set<ProvinceConnection> = declared.coastalConnections.toSet()
    val satelliteSupports: Set<SatelliteSupport> = declared.satelliteSupports.toSet()
    val seaCoasts: Set<SeaCoastConnection> = declared.seaCoasts.toSet()

    val reviewBlockers: List<ConnectionReviewBlocker> = emptyList()

    /** Checks standard-map references and the AH sea erratum. */
    fun validate(provinces: List<ProvinceDefinition>): BoardMapDefinition {
        val definition =
            BoardMapDefinition(
                provinces = provinces,
                landBorders = landBorders,
                coastalConnections = coastalConnections,
                satelliteSupports = satelliteSupports,
                seaCoasts = seaCoasts,
            )
        require(seaCoasts.none { it.sea == SeaArea.NORWEGIAN_SEA }) {
            "The Norwegian Sea has no effective game connections under the AH map errata."
        }
        return definition
    }
}
