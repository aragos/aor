package dev.aragos.aor

/**
 * Immutable coastal adjacency for one map and starting player count. Neighbor order preserves
 * recorded route selection; ownership, access advances and shipping limits remain live rules.
 */
internal class ShippingGraph(private val map: BoardMapDefinition, playerCount: Int) {
    private val neighbors = shippingNeighbors(map, playerCount)

    operator fun get(space: ShippingSpace): Set<ShippingSpace> = neighbors[space].orEmpty()

    /** Each static permission set owns its shortest galley paths at the maximum galley range. */
    private val galleyPaths =
        List(OPEN_SEA * 2) { permissions ->
            lazy {
                neighbors.keys.filterIsInstance<ShippingSpace.Coast>().associate { coast ->
                    coast.province to lazy { galleyPathsFrom(coast, permissions) }
                }
            }
        }

    fun galleyRoutes(
        base: ProvinceId,
        destination: ProvinceId,
        permissions: Int,
        level: Int,
    ): List<ExpansionRoute.Shipping> {
        val destinations = galleyPaths[permissions].value[base]?.value ?: return emptyList()
        return destinations[destination].orEmpty().filter { route -> route.path.size - 1 <= level }
    }

    private fun galleyPathsFrom(
        coast: ShippingSpace.Coast,
        permissions: Int,
    ): Map<ProvinceId, List<ExpansionRoute.Shipping>> {
        data class Visit(val space: ShippingSpace, val seas: Int)
        val start = Visit(coast, 0)
        val paths = mutableMapOf(start to listOf<ShippingSpace>(coast))
        val queue = ArrayDeque<Visit>().apply { addLast(start) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val path = requireNotNull(paths[current]) { "Every queued visit requires a path." }
            if (path.size - 1 >= MAX_GALLEY_RANGE) continue
            for (neighbor in this[current.space]) {
                val seaCount = current.seas + if (neighbor is ShippingSpace.Sea) 1 else 0
                if (
                    neighbor is ShippingSpace.Sea &&
                        (seaCount > 1 || neighbor.sea.isOcean || (permissions and OPEN_SEA) == 0)
                ) {
                    continue
                }
                if (
                    neighbor is ShippingSpace.Coast &&
                        !map.province(neighbor.province).access.all { requirement ->
                            (permissions and (1 shl requirement.ordinal)) != 0
                        }
                ) {
                    continue
                }
                val visit = Visit(neighbor, seaCount)
                if (visit in paths) continue
                paths[visit] = path + neighbor
                queue.addLast(visit)
            }
        }
        return paths.entries
            .mapNotNull { (visit, path) ->
                val destination = visit.space as? ShippingSpace.Coast ?: return@mapNotNull null
                destination.province to ExpansionRoute.Shipping(coast.province, path)
            }
            .groupBy({ it.first }, { it.second })
    }

    companion object {
        private val OPEN_SEA = 1 shl AccessRequirement.entries.size
        private val MAX_GALLEY_RANGE =
            ShippingCapability.entries
                .filter { it.fleet == ShippingFleet.GALLEY }
                .maxOf(ShippingCapability::level)

        /** Static access mask, including permission to cross one printed sea. */
        fun galleyPermissions(advances: Set<AdvanceId>): Int =
            AccessRequirement.entries.fold(
                if (AdvanceId.THE_HEAVENS in advances) OPEN_SEA else 0
            ) { mask, requirement ->
                if (requirement.isSatisfiedBy(advances)) mask or (1 shl requirement.ordinal)
                else mask
            }
    }
}

private fun shippingNeighbors(
    map: BoardMapDefinition,
    playerCount: Int,
): Map<ShippingSpace, Set<ShippingSpace>> {
    val provinces = map.availableFor(playerCount)
    val provinceIds = provinces.map(ProvinceDefinition::id).toSet()
    val coasts = map.seaCoasts.filter { it.province in provinceIds }
    val connections =
        map.coastalConnections.filter { it.first in provinceIds && it.second in provinceIds }
    return (provinces.filter(ProvinceDefinition::coastal).map { ShippingSpace.Coast(it.id) } +
            map.seaAreas.keys.map(ShippingSpace::Sea))
        .associateWith { from ->
            when (from) {
                is ShippingSpace.Coast ->
                    connections
                        .mapNotNull { edge ->
                            when (from.province) {
                                edge.first -> ShippingSpace.Coast(edge.second)
                                edge.second -> ShippingSpace.Coast(edge.first)
                                else -> null
                            }
                        }
                        .toSet() +
                        coasts
                            .filter { it.province == from.province }
                            .map { ShippingSpace.Sea(it.sea) }
                is ShippingSpace.Sea ->
                    coasts
                        .filter { it.sea == from.sea }
                        .map { ShippingSpace.Coast(it.province) }
                        .toSet()
            }
        }
}
