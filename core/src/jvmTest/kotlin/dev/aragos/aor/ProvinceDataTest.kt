package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProvinceDataTest {
    private val inlandMarket =
        ProvinceDefinition(
            id = ProvinceId("inland"),
            area = GeographicalArea.III,
            overseas = null,
            marketNumber = 3,
            commodities = setOf(Commodity.WINE),
            capital = null,
            coastal = false,
            access = emptySet(),
        )

    @Test
    fun playerCountsIncludeTheExactPrintedProvinceCounts() {
        val counts =
            (3..6).associateWith { playerCount ->
                val available = ProvinceData.availableFor(playerCount)
                Triple(
                    available.size,
                    available.count { province -> !province.isSatellite },
                    available.count(ProvinceDefinition::isSatellite),
                )
            }

        assertThat(counts)
            .containsExactly(
                3,
                Triple(67, 48, 19),
                4,
                Triple(83, 59, 24),
                5,
                Triple(96, 67, 29),
                6,
                Triple(108, 73, 35),
            )
    }

    @Test
    fun thePrintedMapHasNoLoireProvince() {
        assertThat(ProvinceData.definitions.map { province -> province.id.value })
            .doesNotContain("loire")
    }

    @Test
    fun areaOneHasSixSatellitesIncludingSmolensk() {
        val satellites =
            ProvinceData.definitions.filter { province ->
                province.area == GeographicalArea.I && province.isSatellite
            }

        assertThat(satellites.map { province -> province.id.value })
            .containsExactly("malmo", "visby", "copenhagen", "stettin", "mitau", "smolensk")
    }

    @Test
    fun libyaBelongsToAreaSix() {
        assertThat(province("libya").area).isEqualTo(GeographicalArea.VI)
    }

    @Test
    fun dijonIsAnInlandProvince() {
        assertThat(province("dijon").coastal).isFalse()
    }

    @Test
    fun capitalMarketsHaveTheirPrintedHomeAreas() {
        val capitals = ProvinceData.definitions.filter { province -> province.capital != null }

        assertThat(capitals.associate { province -> province.capital to province.area })
            .containsExactly(
                "hamburg",
                GeographicalArea.I,
                "london",
                GeographicalArea.II,
                "paris",
                GeographicalArea.III,
                "barcelona",
                GeographicalArea.IV,
                "genoa",
                GeographicalArea.VII,
                "venice",
                GeographicalArea.VII,
            )
    }

    @Test
    fun configuredProvincesIncludeExactlyTheAvailableCapitals() {
        val capitals =
            (3..6).associateWith { playerCount ->
                ProvinceData.availableFor(playerCount)
                    .mapNotNull(ProvinceDefinition::capital)
                    .toSet()
            }

        assertThat(capitals)
            .containsExactly(
                3,
                setOf("venice", "genoa", "barcelona"),
                4,
                setOf("venice", "genoa", "barcelona", "paris"),
                5,
                setOf("venice", "genoa", "barcelona", "paris", "london"),
                6,
                setOf("venice", "genoa", "barcelona", "paris", "london", "hamburg"),
            )
    }

    @Test
    fun overseasBoxesHaveEveryPrintedCommodity() {
        val commodities =
            ProvinceData.definitions
                .filter { province -> province.overseas != null }
                .associate { province -> province.id.value to province.commodities }

        assertThat(commodities)
            .containsExactly(
                "east-indies",
                setOf(Commodity.SILK, Commodity.SPICE),
                "china",
                setOf(Commodity.SILK, Commodity.SPICE),
                "india",
                setOf(Commodity.GOLD, Commodity.SPICE),
                "north-america",
                setOf(Commodity.GRAIN, Commodity.CLOTH, Commodity.FUR),
                "south-america",
                setOf(Commodity.METAL, Commodity.SPICE, Commodity.GOLD),
            )
    }

    @Test
    fun overseasBoxesHaveTheirPrintedMarketNumbers() {
        val numbers =
            ProvinceData.definitions
                .filter { province -> province.overseas != null }
                .associate { province -> province.id.value to province.marketNumber }

        assertThat(numbers)
            .containsExactly(
                "east-indies",
                5,
                "china",
                5,
                "india",
                5,
                "north-america",
                6,
                "south-america",
                6,
            )
    }

    @Test
    fun overseasBoxesRequireOceanNavigationAndNewWorldWhereApplicable() {
        val requirements =
            ProvinceData.definitions
                .filter { province -> province.overseas != null }
                .associate { province -> province.id.value to province.access }
        val farEast = setOf(AccessRequirement.OCEAN_NAVIGATION)
        val newWorld = farEast + AccessRequirement.NEW_WORLD

        assertThat(requirements)
            .containsExactly(
                "east-indies",
                farEast,
                "china",
                farEast,
                "india",
                farEast,
                "north-america",
                newWorld,
                "south-america",
                newWorld,
            )
    }

    @Test
    fun overseasBoxesAreCoastalAndBelongToNoGeographicalArea() {
        val geography =
            ProvinceData.definitions
                .filter { province -> province.overseas != null }
                .map { province -> province.area to province.coastal }
                .toSet()

        assertThat(geography).containsExactly(null to true)
    }

    @Test
    fun onlyAreaFiveRequiresOverlandEast() {
        val restricted =
            ProvinceData.definitions.filter { province ->
                AccessRequirement.OVERLAND_EAST in province.access
            }

        assertThat(restricted.map(ProvinceDefinition::id))
            .containsExactlyElementsIn(
                ProvinceData.definitions
                    .filter { province -> province.area == GeographicalArea.V }
                    .map(ProvinceDefinition::id)
            )
    }

    @Test
    fun onlyIcelandAndWestAfricaRequireTheSpecialSeaCrossing() {
        val restricted =
            ProvinceData.definitions.filter { province ->
                AccessRequirement.THE_HEAVENS_OR_SEAWORTHY_VESSELS in province.access
            }

        assertThat(restricted.map { province -> province.id.value })
            .containsExactly("iceland", "west-africa")
    }

    @Test
    fun provinceIdentifiersRejectNonCanonicalSpellings() {
        val invalid = listOf("", "Paris", "st--malo", "-paris", "paris-", "area1", "st malo")

        val messages = invalid.map { identifier ->
            assertFailsWith<IllegalArgumentException> { ProvinceId(identifier) }.message
        }

        assertThat(messages.toSet())
            .containsExactly(
                "Province id must contain lowercase words separated by single hyphens."
            )
    }

    @Test
    fun provinceIdentifiersPreserveCanonicalWords() {
        assertThat(ProvinceId("st-malo").value).isEqualTo("st-malo")
    }

    @Test
    fun aProvinceMustHaveAGeographicalAreaOrOverseasGroup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(area = null)
            }

        assertThat(failure).hasMessageThat().contains("exactly one")
    }

    @Test
    fun aProvinceCannotHaveBothAGeographicalAreaAndOverseasGroup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(overseas = OverseasGroup.FAR_EAST)
            }

        assertThat(failure).hasMessageThat().contains("exactly one")
    }

    @Test
    fun marketNumbersMustFitThePrintedRange() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(marketNumber = 0)
            }

        assertThat(failure).hasMessageThat().contains("one through six")
    }

    @Test
    fun satellitesCannotProduceCommodities() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(marketNumber = 1)
            }

        assertThat(failure).hasMessageThat().contains("Only satellites")
    }

    @Test
    fun commodityMarketsCannotOmitTheirCommodity() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(commodities = emptySet())
            }

        assertThat(failure).hasMessageThat().contains("Only satellites")
    }

    @Test
    fun mainlandMarketsCannotProduceSeveralCommodities() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(commodities = setOf(Commodity.WINE, Commodity.GRAIN))
            }

        assertThat(failure).hasMessageThat().contains("exactly one commodity")
    }

    @Test
    fun mainlandMarketsCannotHaveMarketNumberSix() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(marketNumber = 6)
            }

        assertThat(failure).hasMessageThat().contains("at most five")
    }

    @Test
    fun overseasMarketNumbersMustMatchTheirGroup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                province("china").copy(marketNumber = 4)
            }

        assertThat(failure).hasMessageThat().contains("match its group")
    }

    @Test
    fun overseasCommodityCountsMustMatchTheirGroup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                province("north-america").copy(commodities = setOf(Commodity.GRAIN))
            }

        assertThat(failure).hasMessageThat().contains("match its group")
    }

    @Test
    fun aCapitalMustReferenceItsOwnProvince() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(capital = "paris")
            }

        assertThat(failure).hasMessageThat().contains("its own mainland market")
    }

    @Test
    fun overseasBoxesCannotBeInland() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                province("china").copy(coastal = false)
            }

        assertThat(failure).hasMessageThat().contains("must be coastal")
    }

    @Test
    fun areaFiveProvincesCannotOmitTheirAccessRestriction() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                inlandMarket.copy(area = GeographicalArea.V)
            }

        assertThat(failure).hasMessageThat().contains("requires Overland East")
    }

    @Test
    fun overseasBoxesCannotOmitOceanNavigation() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                province("china").copy(access = emptySet())
            }

        assertThat(failure).hasMessageThat().contains("requires Ocean Navigation")
    }

    @Test
    fun newWorldBoxesCannotOmitTheNewWorldAdvance() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                province("north-america").copy(access = setOf(AccessRequirement.OCEAN_NAVIGATION))
            }

        assertThat(failure).hasMessageThat().contains("require the New World advance")
    }

    @Test
    fun provinceCatalogRejectsDuplicateIdentifiers() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                provinces {
                    area(GeographicalArea.IV) {
                        satellite("palma")
                        satellite("palma")
                    }
                }
            }

        assertThat(failure).hasMessageThat().contains("must be unique")
    }

    private fun province(id: String): ProvinceDefinition =
        ProvinceData.byId.getValue(ProvinceId(id))
}
