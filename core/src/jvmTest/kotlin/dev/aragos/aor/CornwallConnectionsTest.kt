package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CornwallConnectionsTest {
    private val player = PlayerId("p1")
    private val cornwall = ProvinceId("cornwall")

    @Test
    fun cornwallHasOnlyPortsmouthOnLandOnBothMaps() {
        for (map in MapRule.entries) {
            val catalog = CatalogConfiguration(6, RuleOptions(map = map))

            assertThat(neighbors(catalog.landBorders)).containsExactly(ProvinceId("portsmouth"))
        }
    }

    @Test
    fun cornwallHasThreeCoastalNeighborsOnBothMaps() {
        for (map in MapRule.entries) {
            val catalog = CatalogConfiguration(6, RuleOptions(map = map))

            assertThat(neighbors(catalog.coastalConnections))
                .containsExactly(
                    ProvinceId("chester"),
                    ProvinceId("wales"),
                    ProvinceId("portsmouth"),
                )
        }
    }

    @Test
    fun walesCannotReachCornwallWithoutShips() {
        for (map in MapRule.entries) {
            val context = context(map, "wales")

            assertThat(ExpansionReach.routesTo(context, player, cornwall)).isEmpty()
        }
    }

    @Test
    fun cornwallReachesEachNeighborInOneCoastalStep() {
        for (map in MapRule.entries) {
            val context = context(map, "cornwall", ShippingCapability.GALLEY_2)
            for (destination in listOf("chester", "wales", "portsmouth")) {
                val province = ProvinceId(destination)

                assertThat(ExpansionReach.routesTo(context, player, province))
                    .contains(
                        ExpansionRoute.Shipping(
                            cornwall,
                            listOf(ShippingSpace.Coast(cornwall), ShippingSpace.Coast(province)),
                        )
                    )
            }
        }
    }

    @Test
    fun chesterAndPortsmouthConnectInOneCoastalStepOnBothMaps() {
        for (map in MapRule.entries) {
            for ((base, destination) in
                listOf("chester" to "portsmouth", "portsmouth" to "chester")) {
                val context = context(map, base, ShippingCapability.GALLEY_2)
                val origin = ProvinceId(base)
                val target = ProvinceId(destination)

                assertThat(ExpansionReach.routesTo(context, player, target))
                    .contains(
                        ExpansionRoute.Shipping(
                            origin,
                            listOf(ShippingSpace.Coast(origin), ShippingSpace.Coast(target)),
                        )
                    )
            }
        }
    }

    @Test
    fun catalog13RetainsEveryPreviousMapFactForReplayAndContinuedPlay() {
        for (map in MapRule.entries) {
            val current = CatalogConfiguration(6, RuleOptions(map = map)).map
            val legacy = CatalogVersion.CATALOG_13.mapDefinition(map)

            assertThat(legacy)
                .isEqualTo(
                    current.copy(
                        landBorders =
                            current.landBorders +
                                ProvinceConnection.of(ProvinceId("wales"), cornwall),
                        coastalConnections =
                            current.coastalConnections -
                                ProvinceConnection.of(cornwall, ProvinceId("chester")) -
                                ProvinceConnection.of(
                                    ProvinceId("chester"),
                                    ProvinceId("portsmouth"),
                                ),
                    )
                )
        }
    }

    @Test
    fun catalog14RetainsEveryPreviousMapFactForReplayAndContinuedPlay() {
        for (map in MapRule.entries) {
            val current = CatalogConfiguration(6, RuleOptions(map = map)).map

            assertThat(CatalogVersion.CATALOG_14.mapDefinition(map))
                .isEqualTo(
                    current.copy(
                        coastalConnections =
                            current.coastalConnections -
                                ProvinceConnection.of(
                                    ProvinceId("chester"),
                                    ProvinceId("portsmouth"),
                                )
                    )
                )
        }
    }

    @Test
    fun catalog13StillAllowsItsRecordedWalesLandRoute() {
        for (map in MapRule.entries) {
            val context = context(map, "wales", version = CatalogVersion.CATALOG_13)

            assertThat(ExpansionReach.routesTo(context, player, cornwall))
                .containsExactly(ExpansionRoute.Land(ProvinceId("wales")))
        }
    }

    private fun neighbors(connections: Set<ProvinceConnection>): List<ProvinceId> =
        connections.mapNotNull { connection ->
            when (cornwall) {
                connection.first -> connection.second
                connection.second -> connection.first
                else -> null
            }
        }

    private fun context(
        map: MapRule,
        base: String,
        ships: ShippingCapability = ShippingCapability.NONE,
        version: CatalogVersion = CatalogVersion.CATALOG_15,
    ): BoardRulesContext =
        BoardRulesContext(
            configuration = CatalogConfiguration(6, RuleOptions(map = map), version),
            board =
                BoardState(definition = version.mapDefinition(map))
                    .replaceWithToken(ProvinceId(base), player)
                    .board,
            players =
                mapOf(player to BoardPlayerProfile(player, ProvinceId("venice"), ships = ships)),
        )
}
