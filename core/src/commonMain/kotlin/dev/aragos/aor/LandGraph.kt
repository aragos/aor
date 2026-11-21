package dev.aragos.aor

/** Available land edges and two-edge paths for one printed map and starting player count. */
internal class LandGraph(map: BoardMapDefinition, playerCount: Int) {
    val borders: Set<ProvinceConnection> =
        map.availableFor(playerCount).map(ProvinceDefinition::id).toSet().let { available ->
            map.landBorders
                .filter { edge -> edge.first in available && edge.second in available }
                .toSet()
        }

    /** Neighbor iteration follows printed edge order. */
    val neighbors =
        borders
            .flatMap { edge -> listOf(edge.first to edge.second, edge.second to edge.first) }
            .groupBy(Pair<ProvinceId, ProvinceId>::first, Pair<ProvinceId, ProvinceId>::second)
            .mapValues { (_, adjacent) -> adjacent.toSet() }

    /** Board occupancy, advances and transit permissions are checked in the current context. */
    val caravanRoutes by lazy {
        neighbors.mapValues { (_, adjacent) ->
            adjacent.flatMap { via ->
                neighbors[via].orEmpty().map { base -> ExpansionRoute.Caravan(base, via) }
            }
        }
    }
}
