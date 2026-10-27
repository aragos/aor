package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GameCatalogTest {
    @Test
    fun malmoSupportsOnlyStockholm() {
        val supports =
            BoardConnectionData.satelliteSupports
                .filter { support -> support.satellite == ProvinceId("malmo") }
                .map(SatelliteSupport::market)

        assertThat(supports).containsExactly(ProvinceId("stockholm"))
    }

    @Test
    fun smyrnaTouchesTheAegeanAndEasternMediterranean() {
        val seas =
            BoardConnectionData.seaCoasts
                .filter { coast -> coast.province == ProvinceId("smyrna") }
                .map(SeaCoastConnection::sea)

        assertThat(seas).containsExactly(SeaArea.AEGEAN_SEA, SeaArea.EASTERN_MEDITERRANEAN)
    }

    @Test
    fun durazzoHasNoDirectSeaIncidences() {
        assertThat(
                BoardConnectionData.seaCoasts.filter { coast ->
                    coast.province == ProvinceId("durazzo")
                }
            )
            .isEmpty()
    }

    @Test
    fun belgradeIsCoastal() {
        assertThat(
                ProvinceData.definitions
                    .single { province -> province.id == ProvinceId("belgrade") }
                    .coastal
            )
            .isTrue()
    }

    @Test
    fun belgradeSharesLandWithDubrovnikAndDurazzo() {
        assertThat(BoardConnectionData.landBorders)
            .containsAtLeast(edge("belgrade", "dubrovnik"), edge("belgrade", "durazzo"))
    }

    @Test
    fun belgradeHasExactlyThreeCoastalNeighbors() {
        val belgrade = ProvinceId("belgrade")
        val neighbors =
            BoardConnectionData.coastalConnections.mapNotNull { connection ->
                when (belgrade) {
                    connection.first -> connection.second
                    connection.second -> connection.first
                    else -> null
                }
            }

        assertThat(neighbors)
            .containsExactly(ProvinceId("dubrovnik"), ProvinceId("durazzo"), ProvinceId("bari"))
    }

    @Test
    fun dubrovnikAndDurazzoHaveNoPhysicalConnection() {
        val connection = edge("dubrovnik", "durazzo")

        assertThat(BoardConnectionData.landBorders).doesNotContain(connection)
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(connection)
    }

    @Test
    fun chesterHasNoDirectSeaIncidences() {
        assertThat(
                BoardConnectionData.seaCoasts.filter { coast ->
                    coast.province == ProvinceId("chester")
                }
            )
            .isEmpty()
    }

    @Test
    fun parisHasNoDirectSeaIncidences() {
        assertThat(
                BoardConnectionData.seaCoasts.filter { coast ->
                    coast.province == ProvinceId("paris")
                }
            )
            .isEmpty()
    }

    @Test
    fun walesAndArmaghShareCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections).contains(edge("wales", "armagh"))
    }

    @Test
    fun copenhagenDoesNotTouchTheBalticSea() {
        assertThat(BoardConnectionData.seaCoasts)
            .doesNotContain(SeaCoastConnection(SeaArea.BALTIC_SEA, ProvinceId("copenhagen")))
    }

    @Test
    fun malmoAndStettinShareCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections).contains(edge("malmo", "stettin"))
    }

    @Test
    fun malmoAndLubeckHaveNoPhysicalConnection() {
        val connection = edge("malmo", "lubeck")

        assertThat(BoardConnectionData.coastalConnections).doesNotContain(connection)
        assertThat(BoardConnectionData.landBorders).doesNotContain(connection)
    }

    @Test
    fun copenhagenAndHamburgShareALandBorder() {
        assertThat(BoardConnectionData.landBorders).contains(edge("copenhagen", "hamburg"))
    }

    @Test
    fun copenhagenAndStettinShareCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections).contains(edge("copenhagen", "stettin"))
    }

    @Test
    fun malmoAndKongsbergShareALandBorder() {
        assertThat(BoardConnectionData.landBorders).contains(edge("malmo", "kongsberg"))
    }

    @Test
    fun malmoDoesNotTouchTheNorthSea() {
        assertThat(BoardConnectionData.seaCoasts)
            .doesNotContain(SeaCoastConnection(SeaArea.NORTH_SEA, ProvinceId("malmo")))
    }

    @Test
    fun lubeckDoesNotTouchTheBalticSea() {
        assertThat(BoardConnectionData.seaCoasts)
            .doesNotContain(SeaCoastConnection(SeaArea.BALTIC_SEA, ProvinceId("lubeck")))
    }

    @Test
    fun visbysOnlyCoastalNeighborIsStockholm() {
        val neighbors =
            BoardConnectionData.coastalConnections.mapNotNull { connection ->
                when (ProvinceId("visby")) {
                    connection.first -> connection.second
                    connection.second -> connection.first
                    else -> null
                }
            }

        assertThat(neighbors).containsExactly(ProvinceId("stockholm"))
    }

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
    fun copiedConfigurationUsesItsOwnPlayerCountForBoardQueries() {
        val original = CatalogConfiguration(3)
        val excludedProvince = ProvinceId("hamburg")
        val originalIds = original.provinceIds
        val originalBorders = original.landBorders

        val expanded = original.copy(playerCount = 6)

        assertThat(originalIds).doesNotContain(excludedProvince)
        assertThat(
                originalBorders.any { edge ->
                    edge.first == excludedProvince || edge.second == excludedProvince
                }
            )
            .isFalse()
        assertThat(expanded.provinceIds).contains(excludedProvince)
        assertThat(
                expanded.landBorders.any { edge ->
                    edge.first == excludedProvince || edge.second == excludedProvince
                }
            )
            .isTrue()
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
    fun onlyAssignedSeasBelongToGeographicalAreas() {
        val assignments =
            SeaArea.entries.mapNotNull { sea -> sea.area?.let { area -> sea to area } }.toMap()

        assertThat(assignments)
            .containsExactly(
                SeaArea.BALTIC_SEA,
                GeographicalArea.I,
                SeaArea.GREENLAND_SEA,
                GeographicalArea.II,
                SeaArea.BLACK_SEA,
                GeographicalArea.V,
                SeaArea.EASTERN_MEDITERRANEAN,
                GeographicalArea.VI,
                SeaArea.ADRIATIC_SEA,
                GeographicalArea.VII,
                SeaArea.CENTRAL_MEDITERRANEAN,
                GeographicalArea.VIII,
                SeaArea.AEGEAN_SEA,
                GeographicalArea.VIII,
                SeaArea.CANARY_BASIN,
                GeographicalArea.VIII,
            )
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
                if (province.id == ProvinceId("venice")) {
                    province.copy(coastal = false)
                } else {
                    province
                }
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
    fun channelCoastsConnectPortsmouthAndLondonToTheirFrenchNeighbors() {
        assertThat(BoardConnectionData.coastalConnections)
            .containsAtLeast(
                edge("portsmouth", "st-malo"),
                edge("portsmouth", "paris"),
                edge("london", "paris"),
                edge("london", "bruges"),
            )
    }

    @Test
    fun bariHasExactlyItsFiveCoastalNeighbors() {
        val bari = ProvinceId("bari")
        val neighbors =
            BoardConnectionData.coastalConnections
                .filter { connection -> bari in listOf(connection.first, connection.second) }
                .map { connection ->
                    if (connection.first == bari) connection.second else connection.first
                }

        assertThat(neighbors)
            .containsExactly(
                ProvinceId("naples"),
                ProvinceId("sicily"),
                ProvinceId("corfu"),
                ProvinceId("durazzo"),
                ProvinceId("belgrade"),
            )
    }

    @Test
    fun sicilyDoesNotTouchNaplesByCoast() {
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("sicily", "naples"))
    }

    @Test
    fun smyrnaDoesNotTouchCreteByCoast() {
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("smyrna", "crete"))
    }

    @Test
    fun cagliariDoesNotTouchRome() {
        assertThat(BoardConnectionData.landBorders).doesNotContain(edge("cagliari", "rome"))
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("cagliari", "rome"))
    }

    @Test
    fun acreDoesNotTouchCyprusByCoast() {
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("acre", "cyprus"))
    }

    @Test
    fun granadaDoesNotTouchTheCanaryBasin() {
        assertThat(BoardConnectionData.seaCoasts)
            .doesNotContain(SeaCoastConnection(SeaArea.CANARY_BASIN, ProvinceId("granada")))
    }

    @Test
    fun kamyshinTouchesOnlyTanaAndSaraiByLand() {
        val kamyshin = ProvinceId("kamyshin")
        val neighbors =
            BoardConnectionData.landBorders
                .filter { connection -> kamyshin in listOf(connection.first, connection.second) }
                .map { connection ->
                    if (connection.first == kamyshin) connection.second else connection.first
                }

        assertThat(neighbors).containsExactly(ProvinceId("tana"), ProvinceId("sarai"))
    }

    @Test
    fun tanaDoesNotTouchPoti() {
        assertThat(BoardConnectionData.landBorders).doesNotContain(edge("tana", "poti"))
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("tana", "poti"))
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
    fun shetlandAndBergenShareCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections)
            .contains(edge("shetland-islands", "bergen"))
    }

    @Test
    fun centralMediterraneanHasItsOwnCoastalIncidences() {
        assertThat(
                BoardConnectionData.seaCoasts
                    .filter { coast -> coast.sea == SeaArea.CENTRAL_MEDITERRANEAN }
                    .map(SeaCoastConnection::province)
            )
            .containsExactly(
                ProvinceId("bari"),
                ProvinceId("sicily"),
                ProvinceId("tunis"),
                ProvinceId("tripoli"),
                ProvinceId("barca"),
                ProvinceId("corfu"),
                ProvinceId("crete"),
                ProvinceId("athens"),
            )
    }

    @Test
    fun aegeanHasItsOwnCoastalIncidences() {
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
    fun creteTouchesAllThreeMediterraneanSeas() {
        val seas =
            BoardConnectionData.seaCoasts
                .filter { coast -> coast.province == ProvinceId("crete") }
                .map(SeaCoastConnection::sea)

        assertThat(seas)
            .containsExactly(
                SeaArea.AEGEAN_SEA,
                SeaArea.CENTRAL_MEDITERRANEAN,
                SeaArea.EASTERN_MEDITERRANEAN,
            )
    }

    @Test
    fun angoraAndConstantinopleShareALandBorder() {
        assertThat(BoardConnectionData.landBorders).contains(edge("angora", "constantinople"))
    }

    @Test
    fun gallipoliAndAngoraHaveNoDirectCoastalConnection() {
        assertThat(BoardConnectionData.coastalConnections)
            .doesNotContain(edge("gallipoli", "angora"))
    }

    @Test
    fun tunisAndSicilyShareCoastalWater() {
        assertThat(BoardConnectionData.coastalConnections).contains(edge("tunis", "sicily"))
        assertThat(BoardConnectionData.landBorders).doesNotContain(edge("tunis", "sicily"))
    }

    @Test
    fun oranAndGranadaHaveNoDirectCoastalConnection() {
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("oran", "granada"))
    }

    @Test
    fun salonikaAndCorfuShareOnlyTheirLandBorder() {
        assertThat(BoardConnectionData.coastalConnections).doesNotContain(edge("salonika", "corfu"))
        assertThat(BoardConnectionData.landBorders).contains(edge("salonika", "corfu"))
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
