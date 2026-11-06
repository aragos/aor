package dev.aragos.aor

/**
 * Immutable coastal adjacency for one map and starting player count. Neighbor order preserves
 * recorded route selection; ownership, access advances and shipping limits remain live rules.
 */
internal class ShippingGraph(map: BoardMapDefinition, playerCount: Int) {
    private val neighbors = shippingNeighbors(map, playerCount)

    operator fun get(space: ShippingSpace): Set<ShippingSpace> = neighbors[space].orEmpty()
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
