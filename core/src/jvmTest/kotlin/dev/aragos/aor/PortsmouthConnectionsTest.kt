package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class PortsmouthConnectionsTest {
    private val player = PlayerId("p1")
    private val portsmouth = ProvinceId("portsmouth")
    private val york = ProvinceId("york")
    private val border = ProvinceConnection.of(portsmouth, york)

    @Test
    fun standardPortsmouthAndYorkShareALandBorderWhenTheirAreaIsInPlay() {
        for (playerCount in GeographicalArea.II.minimumPlayers..6) {
            val catalog = CatalogConfiguration(playerCount)

            assertThat(catalog.landBorders).contains(border)
            assertThat(catalog.coastalConnections).doesNotContain(border)
        }
    }

    @Test
    fun standardPortsmouthAndYorkCanReachEachOtherWithoutShips() {
        for ((origin, destination) in listOf(portsmouth to york, york to portsmouth)) {
            val context =
                BoardRulesContext(
                    configuration = CatalogConfiguration(6),
                    board = BoardState().replaceWithToken(origin, player).board,
                    players = mapOf(player to BoardPlayerProfile(player, ProvinceId("venice"))),
                )

            assertThat(ExpansionReach.routesTo(context, player, destination))
                .containsExactly(ExpansionRoute.Land(origin))
        }
    }

    @Test
    fun europeanMapRetainsItsEntireRecordedTopology() {
        assertThat(CatalogVersion.CATALOG_16.mapDefinition(MapRule.EUROPEAN))
            .isEqualTo(CatalogVersion.CATALOG_15.mapDefinition(MapRule.EUROPEAN))
        assertThat(CatalogVersion.CATALOG_16.mapDefinition(MapRule.EUROPEAN).landBorders)
            .doesNotContain(border)
    }

    @Test
    fun catalog15RetainsEveryPreviousStandardMapFactForReplayAndContinuedPlay() {
        val current = CatalogVersion.CATALOG_16.mapDefinition(MapRule.STANDARD)

        assertThat(CatalogVersion.CATALOG_15.mapDefinition(MapRule.STANDARD))
            .isEqualTo(current.copy(landBorders = current.landBorders - border))
    }
}
