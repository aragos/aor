package dev.aragos.aor

/**
 * A detached draft based on another map. Removing a province removes its inherited incident edges.
 * Added edges must reference final provinces; explicit edge removals must exist in the base map.
 * Connection edits apply to the final province list before the resulting map is validated.
 */
@GameDataDsl
class BoardMapBuilder internal constructor(private val base: BoardMapDefinition) {
    private var declaredProvinces = base.provinces.toList()
    private val removedProvinceIds = mutableSetOf<ProvinceId>()
    private var capitalOrder = base.capitalOrder.toList()
    private val areaMinimumPlayers = base.areaMinimumPlayers.toMutableMap()
    private val seaAreas = base.seaAreas.toMutableMap()
    private val additions = BoardConnectionBuilder()
    private val removals = BoardConnectionBuilder()

    /**
     * Adds new provinces using the same area/market/satellite/overseas DSL as the standard board.
     */
    fun addProvinces(declare: ProvinceCatalogBuilder.() -> Unit) {
        val added = ProvinceCatalogBuilder().apply(declare).build()
        val existing = declaredProvinces.map(ProvinceDefinition::id).toSet()
        require(added.none { it.id in existing }) {
            "Added province already exists; use changeProvince to edit an existing definition."
        }
        declaredProvinces = declaredProvinces + added
    }

    /** Replaces a province's facts while retaining its stable ID and connections. */
    fun changeProvince(id: String, change: ProvinceDefinition.() -> ProvinceDefinition) {
        val key = ProvinceId(id)
        val current =
            requireNotNull(declaredProvinces.singleOrNull { it.id == key }) {
                "Cannot change unknown province: $id."
            }
        val changed = current.change()
        require(changed.id == key) {
            "changeProvince cannot rename $id; remove the old province and add its replacement."
        }
        declaredProvinces = declaredProvinces.map { if (it.id == key) changed else it }
    }

    /**
     * Removes a province, inherited incident connections, and its capital-order entry if present.
     */
    fun removeProvince(id: String) {
        val key = ProvinceId(id)
        require(declaredProvinces.any { it.id == key }) { "Cannot remove unknown province: $id." }
        declaredProvinces = declaredProvinces.filterNot { it.id == key }
        removedProvinceIds += key
        capitalOrder = capitalOrder.filterNot { it == key }
    }

    /** Adds land/coast links, directed supports and province-to-sea incidences. */
    fun addConnections(declare: BoardConnectionBuilder.() -> Unit) {
        additions.apply(declare)
    }

    /** Removes specifically named inherited edges; reverse land/coast order is equivalent. */
    fun removeConnections(declare: BoardConnectionBuilder.() -> Unit) {
        removals.apply(declare)
    }

    /** Replaces the complete registration-count order, including any newly declared capitals. */
    fun capitals(vararg ids: String) {
        capitalOrder = ids.map(::ProvinceId)
    }

    /** First registration count including this area's provinces; allowed values are 3–6. */
    fun minimumPlayers(area: GeographicalArea, count: Int) {
        areaMinimumPlayers[area] = count
    }

    /** Reassigns a sea's geographical area; null leaves the sea outside geographical areas. */
    fun seaArea(sea: SeaArea, area: GeographicalArea?) {
        seaAreas[sea] = area
    }

    internal fun build(): BoardMapDefinition {
        val inheritedIds =
            declaredProvinces.map(ProvinceDefinition::id).toSet() - removedProvinceIds
        return BoardMapDefinition(
            provinces = declaredProvinces.toList(),
            landBorders =
                connections(
                    "land border",
                    base.landBorders,
                    removals.landBorders,
                    additions.landBorders,
                ) {
                    it.first in inheritedIds && it.second in inheritedIds
                },
            coastalConnections =
                connections(
                    "coastal-water connection",
                    base.coastalConnections,
                    removals.coastalConnections,
                    additions.coastalConnections,
                ) {
                    it.first in inheritedIds && it.second in inheritedIds
                },
            satelliteSupports =
                connections(
                    "satellite support",
                    base.satelliteSupports,
                    removals.satelliteSupports,
                    additions.satelliteSupports,
                ) {
                    it.satellite in inheritedIds && it.market in inheritedIds
                },
            seaCoasts =
                connections(
                    "sea coast",
                    base.seaCoasts,
                    removals.seaCoasts,
                    additions.seaCoasts,
                ) {
                    it.province in inheritedIds
                },
            capitalOrder = capitalOrder.toList(),
            areaMinimumPlayers = areaMinimumPlayers.toMap(),
            seaAreas = seaAreas.toMap(),
        )
    }

    private fun <T> connections(
        kind: String,
        inherited: Set<T>,
        removed: Set<T>,
        added: Set<T>,
        retainsProvince: (T) -> Boolean,
    ): Set<T> {
        val missing = removed - inherited
        require(missing.isEmpty()) { "Cannot remove unknown $kind: ${missing.first()}." }
        val retained = (inherited - removed).filter(retainsProvince).toSet()
        val duplicates = retained.intersect(added)
        require(duplicates.isEmpty()) { "Duplicate $kind: ${duplicates.first()}." }
        return retained + added
    }
}

/** Copies static facts into a separately validated draft; declarations never mutate [base]. */
fun boardMap(
    base: BoardMapDefinition,
    declare: BoardMapBuilder.() -> Unit,
): BoardMapDefinition = BoardMapBuilder(base).apply(declare).build()
