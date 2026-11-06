package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ShippingGraphTest {
    @Test
    fun precomputedNeighborsPreserveEverySupportedCatalogAndPlayerCountInOrder() {
        val configurations =
            CatalogVersion.entries.flatMap { version ->
                MapRule.entries.flatMap { map ->
                    (3..6).map { count ->
                        CatalogConfiguration(count, RuleOptions(map = map), version)
                    }
                }
            }
        configurations.forEach { configuration ->
            val graph = configuration.map.shippingGraph(configuration.playerCount)
            val spaces =
                configuration.map.provinces.map { ShippingSpace.Coast(it.id) } +
                    SeaArea.entries.map(ShippingSpace::Sea)
            spaces.forEach { space ->
                assertThat(graph[space].toList())
                    .containsExactlyElementsIn(scannedNeighbors(configuration, space))
                    .inOrder()
            }
        }
    }

    @Test
    fun copiedMapsRetainTheirOwnTopology() {
        val original = MapRule.STANDARD.definition
        val changed = boardMap(original) { removeConnections { coast("smyrna", "adalia") } }
        val from = ShippingSpace.Coast(ProvinceId("smyrna"))
        val destination = ShippingSpace.Coast(ProvinceId("adalia"))

        assertThat(original.shippingGraph(3)[from]).contains(destination)
        assertThat(changed.shippingGraph(3)[from]).doesNotContain(destination)
    }

    private fun scannedNeighbors(
        configuration: CatalogConfiguration,
        space: ShippingSpace,
    ): Set<ShippingSpace> =
        when (space) {
            is ShippingSpace.Coast ->
                configuration.coastalConnections
                    .mapNotNull { edge ->
                        when (space.province) {
                            edge.first -> ShippingSpace.Coast(edge.second)
                            edge.second -> ShippingSpace.Coast(edge.first)
                            else -> null
                        }
                    }
                    .toSet() +
                    configuration.seaCoasts
                        .filter { it.province == space.province }
                        .map { ShippingSpace.Sea(it.sea) }
            is ShippingSpace.Sea ->
                configuration.seaCoasts
                    .filter { it.sea == space.sea }
                    .map { ShippingSpace.Coast(it.province) }
                    .toSet()
        }
}
