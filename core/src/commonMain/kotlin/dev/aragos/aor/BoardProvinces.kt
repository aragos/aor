package dev.aragos.aor

/** Stable catalog identifier: lowercase ASCII words separated by single hyphens. */
data class ProvinceId(val value: String) {
    init {
        require(value.matches(Regex("[a-z]+(?:-[a-z]+)*"))) {
            "Province id must contain lowercase words separated by single hyphens."
        }
    }

    override fun toString(): String = value
}

/** Printed geographical areas; [minimumPlayers] is the first supported setup including the area. */
enum class GeographicalArea(val minimumPlayers: Int) {
    I(6),
    II(5),
    III(4),
    IV(3),
    V(3),
    VI(3),
    VII(3),
    VIII(3),
}

enum class OverseasGroup(val marketNumber: Int, val commodityCount: Int) {
    FAR_EAST(marketNumber = 5, commodityCount = 2),
    NEW_WORLD(marketNumber = 6, commodityCount = 3),
}

/** Static access conditions; shipping capacity and overseas limits are separate game rules. */
enum class AccessRequirement {
    OVERLAND_EAST,
    /** The province is reachable only by ship, with either of these advances. */
    THE_HEAVENS_OR_SEAWORTHY_VESSELS,
    OCEAN_NAVIGATION,
    NEW_WORLD,
}

/**
 * A printed province or overseas box. Satellites have [marketNumber] one and no commodities.
 * Overseas boxes have no geographical [area]. [capital] references a [CapitalData] identifier.
 * [coastal] means the province touches printed coastal water, including Iceland and West Africa.
 */
data class ProvinceDefinition(
    val id: ProvinceId,
    val area: GeographicalArea?,
    val overseas: OverseasGroup?,
    val marketNumber: Int,
    val commodities: Set<Commodity>,
    val capital: String?,
    val coastal: Boolean,
    val access: Set<AccessRequirement>,
) {
    val messageId: String
        get() = "province.${id.value}"

    val isSatellite: Boolean
        get() = marketNumber == 1

    init {
        require((area == null) != (overseas == null)) {
            "A province must belong to exactly one geographical area or overseas group: $id."
        }
        require(marketNumber in 1..6) { "Province market number must be one through six: $id." }
        require((marketNumber == 1) == commodities.isEmpty()) {
            "Only satellites have market number one and no commodity: $id."
        }
        require(overseas != null || (marketNumber <= 5 && commodities.size <= 1)) {
            "Mainland markets have number at most five and exactly one commodity: $id."
        }
        require(
            overseas == null ||
                (marketNumber == overseas.marketNumber &&
                    commodities.size == overseas.commodityCount)
        ) {
            "Overseas market number and commodities must match its group: $id."
        }
        require(capital == null || (capital == id.value && !isSatellite && overseas == null)) {
            "A capital must identify its own mainland market: $id."
        }
        require(overseas == null || coastal) { "Overseas boxes must be coastal: $id." }
        require(area != GeographicalArea.V || AccessRequirement.OVERLAND_EAST in access) {
            "Every province in Area V requires Overland East: $id."
        }
        require(overseas == null || AccessRequirement.OCEAN_NAVIGATION in access) {
            "Every overseas box requires Ocean Navigation: $id."
        }
        require(overseas != OverseasGroup.NEW_WORLD || AccessRequirement.NEW_WORLD in access) {
            "New World boxes require the New World advance: $id."
        }
    }
}

@GameDataDsl
class ProvinceAreaBuilder internal constructor(private val area: GeographicalArea) {
    private val definitions = mutableListOf<ProvinceDefinition>()

    fun market(
        id: String,
        number: Int,
        commodity: Commodity,
        coastal: Boolean = true,
        capital: String? = null,
        access: Set<AccessRequirement> = emptySet(),
    ) {
        add(id, number, setOf(commodity), coastal, capital, access)
    }

    fun satellite(id: String, coastal: Boolean = true) {
        add(id, 1, emptySet(), coastal, null, emptySet())
    }

    private fun add(
        id: String,
        number: Int,
        commodities: Set<Commodity>,
        coastal: Boolean,
        capital: String?,
        access: Set<AccessRequirement>,
    ) {
        val areaAccess =
            if (area == GeographicalArea.V) {
                setOf(AccessRequirement.OVERLAND_EAST)
            } else {
                emptySet()
            }
        definitions +=
            ProvinceDefinition(
                id = ProvinceId(id),
                area = area,
                overseas = null,
                marketNumber = number,
                commodities = commodities,
                capital = capital,
                coastal = coastal,
                access = access + areaAccess,
            )
    }

    internal fun build(): List<ProvinceDefinition> = definitions.toList()
}

@GameDataDsl
class ProvinceCatalogBuilder {
    private val definitions = mutableListOf<ProvinceDefinition>()

    fun area(area: GeographicalArea, declare: ProvinceAreaBuilder.() -> Unit) {
        definitions += ProvinceAreaBuilder(area).apply(declare).build()
    }

    fun overseas(group: OverseasGroup, id: String, vararg commodities: Commodity) {
        val access =
            setOf(AccessRequirement.OCEAN_NAVIGATION) +
                if (group == OverseasGroup.NEW_WORLD) {
                    setOf(AccessRequirement.NEW_WORLD)
                } else {
                    emptySet()
                }
        definitions +=
            ProvinceDefinition(
                id = ProvinceId(id),
                area = null,
                overseas = group,
                marketNumber = group.marketNumber,
                commodities = commodities.toSet(),
                capital = null,
                coastal = true,
                access = access,
            )
    }

    internal fun build(): List<ProvinceDefinition> {
        require(definitions.map(ProvinceDefinition::id).distinct().size == definitions.size) {
            "Province identifiers must be unique."
        }
        return definitions.toList()
    }
}

fun provinces(declare: ProvinceCatalogBuilder.() -> Unit): List<ProvinceDefinition> =
    ProvinceCatalogBuilder().apply(declare).build()

/** AH1996 printed board, transcribed and cross-checked in docs/province-inventory.md. */
object ProvinceData {
    val definitions: List<ProvinceDefinition> = provinces {
        area(GeographicalArea.I) {
            satellite("malmo")
            market("stockholm", number = 3, commodity = Commodity.METAL)
            satellite("visby")
            satellite("copenhagen")
            market("hamburg", number = 4, commodity = Commodity.TIMBER, capital = "hamburg")
            market("lubeck", number = 3, commodity = Commodity.STONE)
            satellite("stettin")
            market("danzig", number = 5, commodity = Commodity.GRAIN)
            satellite("mitau")
            market("riga", number = 2, commodity = Commodity.TIMBER)
            market("novgorod", number = 2, commodity = Commodity.FUR)
            satellite("smolensk", coastal = false)
        }
        area(GeographicalArea.II) {
            market("bergen", number = 3, commodity = Commodity.TIMBER)
            satellite("kongsberg")
            satellite("shetland-islands")
            market(
                "iceland",
                number = 2,
                commodity = Commodity.IVORY,
                access = setOf(AccessRequirement.THE_HEAVENS_OR_SEAWORTHY_VESSELS),
            )
            market("edinburgh", number = 2, commodity = Commodity.WOOL)
            market("york", number = 3, commodity = Commodity.WOOL)
            market("london", number = 5, commodity = Commodity.WOOL, capital = "london")
            market("portsmouth", number = 5, commodity = Commodity.GRAIN)
            market("chester", number = 3, commodity = Commodity.METAL)
            satellite("wales")
            satellite("cornwall")
            satellite("armagh")
            market("waterford", number = 2, commodity = Commodity.WOOL)
        }
        area(GeographicalArea.III) {
            satellite("amsterdam")
            market("bruges", number = 4, commodity = Commodity.CLOTH)
            market("cologne", number = 4, commodity = Commodity.CLOTH, coastal = false)
            market("nuremberg", number = 3, commodity = Commodity.METAL, coastal = false)
            market("strasbourg", number = 3, commodity = Commodity.WINE, coastal = false)
            market("basel", number = 3, commodity = Commodity.FUR, coastal = false)
            satellite("st-gall", coastal = false)
            satellite("salzburg", coastal = false)
            market("vienna", number = 4, commodity = Commodity.STONE, coastal = false)
            market("budapest", number = 3, commodity = Commodity.CLOTH, coastal = false)
            satellite("esseg", coastal = false)
            satellite("breslau", coastal = false)
            market("prague", number = 2, commodity = Commodity.GOLD, coastal = false)
            market("paris", number = 4, commodity = Commodity.STONE, capital = "paris")
            market("st-malo", number = 2, commodity = Commodity.STONE)
            market("dijon", number = 3, commodity = Commodity.GRAIN, coastal = false)
        }
        area(GeographicalArea.IV) {
            satellite("leon")
            market("basque", number = 2, commodity = Commodity.WOOL)
            market("lisbon", number = 3, commodity = Commodity.WINE)
            market("toledo", number = 3, commodity = Commodity.WOOL, coastal = false)
            market("barcelona", number = 3, commodity = Commodity.WINE, capital = "barcelona")
            market("valencia", number = 2, commodity = Commodity.SILK)
            satellite("palma")
        }
        area(GeographicalArea.V) {
            market("kiev", number = 2, commodity = Commodity.GRAIN, coastal = false)
            satellite("kaffa")
            satellite("kamyshin", coastal = false)
            market("sarai", number = 2, commodity = Commodity.GOLD, coastal = false)
            market("tana", number = 2, commodity = Commodity.FUR)
            satellite("abagia")
            market("poti", number = 2, commodity = Commodity.TIMBER)
            market("varna", number = 2, commodity = Commodity.FUR)
            market("angora", number = 2, commodity = Commodity.WOOL)
            market("trebizond", number = 4, commodity = Commodity.SPICE)
            market("erzerum", number = 3, commodity = Commodity.SILK)
        }
        area(GeographicalArea.VI) {
            satellite("adalia")
            satellite("taurus")
            market("aleppo", number = 4, commodity = Commodity.SILK)
            market("cyprus", number = 3, commodity = Commodity.WINE)
            satellite("levant")
            market("acre", number = 5, commodity = Commodity.SPICE)
            satellite("jerusalem")
            market("suez", number = 3, commodity = Commodity.GOLD)
            market("cairo", number = 4, commodity = Commodity.IVORY)
            market("alexandria", number = 5, commodity = Commodity.SPICE)
            satellite("libya")
        }
        area(GeographicalArea.VII) {
            market("bordeaux", number = 4, commodity = Commodity.TIMBER)
            satellite("toulouse")
            market("montpellier", number = 2, commodity = Commodity.STONE)
            market("lyons", number = 4, commodity = Commodity.METAL, coastal = false)
            market("marseilles", number = 5, commodity = Commodity.WINE)
            market("genoa", number = 5, commodity = Commodity.CLOTH, capital = "genoa")
            market("milan", number = 3, commodity = Commodity.STONE, coastal = false)
            market("venice", number = 5, commodity = Commodity.CLOTH, capital = "venice")
            market("florence", number = 4, commodity = Commodity.CLOTH)
            market("rome", number = 4, commodity = Commodity.STONE)
            market("naples", number = 5, commodity = Commodity.STONE)
            satellite("bari")
            satellite("cagliari")
            market("sicily", number = 2, commodity = Commodity.GRAIN)
            market("dubrovnik", number = 3, commodity = Commodity.TIMBER)
            market("belgrade", number = 2, commodity = Commodity.GRAIN, coastal = false)
        }
        area(GeographicalArea.VIII) {
            market("seville", number = 3, commodity = Commodity.GRAIN)
            market("granada", number = 2, commodity = Commodity.METAL)
            market("fez", number = 2, commodity = Commodity.TIMBER)
            satellite("oran")
            market("algiers", number = 2, commodity = Commodity.WOOL)
            market("tunis", number = 4, commodity = Commodity.CLOTH)
            market("tripoli", number = 2, commodity = Commodity.IVORY)
            satellite("barca")
            market(
                "west-africa",
                number = 2,
                commodity = Commodity.IVORY,
                access = setOf(AccessRequirement.THE_HEAVENS_OR_SEAWORTHY_VESSELS),
            )
            satellite("gallipoli")
            satellite("durazzo")
            market("salonika", number = 2, commodity = Commodity.SILK)
            satellite("corfu")
            satellite("athens")
            market("constantinople", number = 4, commodity = Commodity.METAL)
            market("smyrna", number = 2, commodity = Commodity.WOOL)
            market("crete", number = 3, commodity = Commodity.WINE)
        }
        overseas(OverseasGroup.FAR_EAST, "east-indies", Commodity.SILK, Commodity.SPICE)
        overseas(OverseasGroup.FAR_EAST, "china", Commodity.SILK, Commodity.SPICE)
        overseas(OverseasGroup.FAR_EAST, "india", Commodity.GOLD, Commodity.SPICE)
        overseas(
            OverseasGroup.NEW_WORLD,
            "north-america",
            Commodity.GRAIN,
            Commodity.CLOTH,
            Commodity.FUR,
        )
        overseas(
            OverseasGroup.NEW_WORLD,
            "south-america",
            Commodity.METAL,
            Commodity.SPICE,
            Commodity.GOLD,
        )
    }

    val byId: Map<ProvinceId, ProvinceDefinition> = definitions.associateBy(ProvinceDefinition::id)

    fun definition(id: ProvinceId): ProvinceDefinition =
        requireNotNull(byId[id]) { "Unknown province in the reviewed catalog: $id." }

    /**
     * Includes all overseas boxes; access conditions remain separate from setup availability.
     * [playerCount] is the starting registration count validated during setup.
     */
    fun availableFor(playerCount: Int): List<ProvinceDefinition> {
        return definitions.filter { province ->
            province.area?.let { area -> area.minimumPlayers <= playerCount } ?: true
        }
    }
}
