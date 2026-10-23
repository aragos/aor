package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GameCatalogTest {
    @Test
    fun allSupportedConfigurationsPassCrossCatalogValidation() {
        val counts = (3..6).associateWith { count -> GameCatalog.validateFor(count).provinces.size }

        assertThat(counts).containsExactly(3, 67, 4, 83, 5, 96, 6, 108)
    }

    @Test
    fun configurationRejectsUnsupportedPlayerCounts() {
        val failure = assertFailsWith<IllegalArgumentException> { CatalogConfiguration(2) }

        assertThat(failure).hasMessageThat().contains("three through six")
    }

    @Test
    fun unusedAreasRemoveTheirCoastalWatersAndSupportArrows() {
        val board = GameCatalog.validateFor(3)
        val excluded =
            ProvinceData.definitions
                .filter { province ->
                    province.area in
                        setOf(GeographicalArea.I, GeographicalArea.II, GeographicalArea.III)
                }
                .map(ProvinceDefinition::id)
                .toSet()
        val connectionEndpoints =
            (board.landBorders + board.coastalConnections).flatMap { edge ->
                listOf(edge.first, edge.second)
            }
        val supportEndpoints =
            board.satelliteSupports.flatMap { support ->
                listOf(support.satellite, support.market)
            }

        assertThat(connectionEndpoints.toSet().intersect(excluded)).isEmpty()
        assertThat(supportEndpoints.toSet().intersect(excluded)).isEmpty()
        assertThat(board.seaCoasts.map(SeaCoastConnection::province).toSet().intersect(excluded))
            .isEmpty()
    }

    @Test
    fun seaAreasRemainPresentWhenTheirNeighboringLandIsExcluded() {
        val board = GameCatalog.validateFor(3)

        assertThat(board.seas).containsExactlyElementsIn(SeaArea.entries)
        assertThat(board.seaCoasts.filter { edge -> edge.sea == SeaArea.BALTIC_SEA }).isEmpty()
    }

    @Test
    fun provinceConnectionsAreSymmetricByConstruction() {
        assertThat(edge("venice", "florence")).isEqualTo(edge("florence", "venice"))
    }

    @Test
    fun provinceConnectionsRejectSelfLinks() {
        val failure = assertFailsWith<IllegalArgumentException> { edge("venice", "venice") }

        assertThat(failure).hasMessageThat().contains("itself")
    }

    @Test
    fun copiedConnectionsCannotBypassCanonicalOrder() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                edge("florence", "venice").copy(first = ProvinceId("venice"))
            }

        assertThat(failure).hasMessageThat().contains("identifier order")
    }

    @Test
    fun reverseLandDeclarationsAreRejectedAsDuplicates() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionBuilder().apply {
                    land("venice", "florence")
                    land("florence", "venice")
                }
            }

        assertThat(failure).hasMessageThat().contains("Duplicate land border")
    }

    @Test
    fun reverseCoastalDeclarationsAreRejectedAsDuplicates() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionBuilder().apply {
                    coast("venice", "florence")
                    coast("florence", "venice")
                }
            }

        assertThat(failure).hasMessageThat().contains("Duplicate coastal-water connection")
    }

    @Test
    fun repeatedSupportDeclarationsAreRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionBuilder().apply {
                    supports("palma", "barcelona")
                    supports("palma", "barcelona")
                }
            }

        assertThat(failure).hasMessageThat().contains("Duplicate satellite support")
    }

    @Test
    fun connectionsRejectMissingProvinceReferences() {
        val incomplete =
            ProvinceData.definitions.filter { province -> province.id != ProvinceId("venice") }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionData.validate(incomplete)
            }

        assertThat(failure).hasMessageThat().contains("unknown province: venice")
    }

    @Test
    fun coastalConnectionsRejectInlandEndpoints() {
        val badCoast =
            ProvinceData.definitions.map { province ->
                if (province.id == ProvinceId("venice")) province.copy(coastal = false)
                else province
            }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionData.validate(badCoast)
            }

        assertThat(failure).hasMessageThat().contains("two coastal main-map provinces")
    }

    @Test
    fun supportArrowsMustOriginateAtSatellites() {
        val badSource =
            ProvinceData.definitions.map { province ->
                if (province.id == ProvinceId("palma")) {
                    province.copy(marketNumber = 2, commodities = setOf(Commodity.SILK))
                } else province
            }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionData.validate(badSource)
            }

        assertThat(failure).hasMessageThat().contains("Only a satellite can originate")
    }

    @Test
    fun supportArrowsMustTargetMarkets() {
        val badTarget =
            ProvinceData.definitions.map { province ->
                if (province.id == ProvinceId("genoa")) {
                    province.copy(marketNumber = 1, commodities = emptySet(), capital = null)
                } else province
            }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionData.validate(badTarget)
            }

        assertThat(failure).hasMessageThat().contains("must point to a main-map market")
    }

    @Test
    fun officialParisCorrectionUsesCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections)
            .containsAtLeast(edge("paris", "london"), edge("paris", "portsmouth"))
        assertThat(BoardConnectionData.landBorders).doesNotContain(edge("paris", "london"))
    }

    @Test
    fun officialBariCorfuCorrectionUsesCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections).contains(edge("bari", "corfu"))
        assertThat(BoardConnectionData.landBorders).doesNotContain(edge("bari", "corfu"))
    }

    @Test
    fun officialEasternMediterraneanCorrectionIncludesBarca() {
        assertThat(BoardConnectionData.seaCoasts)
            .contains(SeaCoastConnection(SeaArea.EASTERN_MEDITERRANEAN, ProvinceId("barca")))
    }

    @Test
    fun officialCentralMediterraneanCorrectionExcludesLibya() {
        assertThat(BoardConnectionData.seaCoasts)
            .doesNotContain(SeaCoastConnection(SeaArea.CENTRAL_MEDITERRANEAN, ProvinceId("libya")))
    }

    @Test
    fun officialBiscayCorrectionReachesEdinburghAndSeville() {
        val provinces =
            BoardConnectionData.seaCoasts
                .filter { coast ->
                    coast.sea == SeaArea.BAY_OF_BISCAY
                }
                .map(SeaCoastConnection::province)

        assertThat(provinces).containsAtLeast(ProvinceId("edinburgh"), ProvinceId("seville"))
    }

    @Test
    fun stGallDoesNotGainTheUnprintedNurembergSupport() {
        assertThat(BoardConnectionData.satelliteSupports)
            .doesNotContain(SatelliteSupport(ProvinceId("st-gall"), ProvinceId("nuremberg")))
    }

    @Test
    fun supportIsDirectionalAndDoesNotCreateLandAdjacency() {
        assertThat(BoardConnectionData.satelliteSupports)
            .contains(SatelliteSupport(ProvinceId("palma"), ProvinceId("barcelona")))
        assertThat(BoardConnectionData.satelliteSupports)
            .doesNotContain(SatelliteSupport(ProvinceId("barcelona"), ProvinceId("palma")))
        assertThat(BoardConnectionData.landBorders).doesNotContain(edge("palma", "barcelona"))
    }

    @Test
    fun icelandAndWestAfricaHaveNoLandOrCoastalWaterShortcut() {
        val restricted = setOf(ProvinceId("iceland"), ProvinceId("west-africa"))
        val endpoints =
            (BoardConnectionData.landBorders + BoardConnectionData.coastalConnections).flatMap {
                connection ->
                listOf(connection.first, connection.second)
            }

        assertThat(endpoints.toSet().intersect(restricted)).isEmpty()
    }

    @Test
    fun onlyTheAtlanticIsAnOcean() {
        assertThat(SeaArea.entries.filter(SeaArea::isOcean)).containsExactly(SeaArea.ATLANTIC_OCEAN)
    }

    @Test
    fun norwegianSeaHasNoEffectiveConnections() {
        assertThat(
                BoardConnectionData.seaCoasts.filter { coast -> coast.sea == SeaArea.NORWEGIAN_SEA }
            )
            .isEmpty()
    }

    @Test
    fun greenlandSeaProvidesTheHeavensAccessToIceland() {
        assertThat(
                BoardConnectionData.seaCoasts
                    .filter { coast -> coast.sea == SeaArea.GREENLAND_SEA }
                    .map(SeaCoastConnection::province)
            )
            .containsExactly(
                ProvinceId("iceland"),
                ProvinceId("edinburgh"),
                ProvinceId("shetland-islands"),
            )
    }

    @Test
    fun sharperBoardPhotoConfirmsThePrintedAegeanSea() {
        assertThat(
                BoardConnectionData.seaCoasts
                    .filter { coast -> coast.sea == SeaArea.AEGEAN_SEA }
                    .map(SeaCoastConnection::province)
            )
            .containsExactly(
                ProvinceId("salonika"),
                ProvinceId("gallipoli"),
                ProvinceId("constantinople"),
                ProvinceId("smyrna"),
                ProvinceId("crete"),
                ProvinceId("athens"),
            )
    }

    @Test
    fun gallipoliAndAngoraHaveNoDirectCoastalConnection() {
        assertThat(BoardConnectionData.coastalConnections)
            .doesNotContain(edge("gallipoli", "angora"))
    }

    @Test
    fun allSupportedConfigurationsHaveResolvedReferences() {
        for (count in 3..6) {
            val configuration = GameCatalog.requireResolvedFor(count)

            assertThat(configuration.unresolvedReferences).isEmpty()
        }
    }

    private fun edge(first: String, second: String): ProvinceConnection =
        ProvinceConnection.of(ProvinceId(first), ProvinceId(second))
}
