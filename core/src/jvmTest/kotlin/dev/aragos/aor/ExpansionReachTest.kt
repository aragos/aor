package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class ExpansionReachTest {
    private val venice = PlayerId("p1")
    private val genoa = PlayerId("p2")
    private val barcelona = PlayerId("p3")

    @Test
    fun smyrnaCrossesTheEasternMediterraneanWithHeavens() {
        val context =
            context(
                board = colouredTokensAt("smyrna"),
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("cyprus")))
            .contains(
                ExpansionRoute.Shipping(
                    id("smyrna"),
                    listOf(
                        ShippingSpace.Coast(id("smyrna")),
                        ShippingSpace.Sea(SeaArea.EASTERN_MEDITERRANEAN),
                        ShippingSpace.Coast(id("cyprus")),
                    ),
                )
            )
    }

    @Test
    fun durazzoCannotCrossTheAdriaticDirectlyWithHeavens() {
        val context =
            context(
                board = colouredTokensAt("durazzo"),
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
            )

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validate(
                    context,
                    venice,
                    id("naples"),
                    ExpansionRoute.Shipping(
                        id("durazzo"),
                        listOf(
                            ShippingSpace.Coast(id("durazzo")),
                            ShippingSpace.Sea(SeaArea.ADRIATIC_SEA),
                            ShippingSpace.Coast(id("naples")),
                        ),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    @Test
    fun belgradeReachesBariInOneCoastalStep() {
        val context =
            context(board = colouredTokensAt("belgrade"), ships = ShippingCapability.GALLEY_2)

        assertThat(ExpansionReach.routesTo(context, venice, id("bari")))
            .contains(
                ExpansionRoute.Shipping(
                    id("belgrade"),
                    listOf(ShippingSpace.Coast(id("belgrade")), ShippingSpace.Coast(id("bari"))),
                )
            )
    }

    @Test
    fun belgradeCanCrossTheAdriaticWithHeavens() {
        val context =
            context(
                board = colouredTokensAt("belgrade"),
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("florence")))
            .contains(
                ExpansionRoute.Shipping(
                    id("belgrade"),
                    listOf(
                        ShippingSpace.Coast(id("belgrade")),
                        ShippingSpace.Sea(SeaArea.ADRIATIC_SEA),
                        ShippingSpace.Coast(id("florence")),
                    ),
                )
            )
    }

    @Test
    fun dubrovnikCannotReachDurazzoInOneCoastalStep() {
        val context =
            context(board = colouredTokensAt("dubrovnik"), ships = ShippingCapability.GALLEY_2)

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validate(
                    context,
                    venice,
                    id("durazzo"),
                    ExpansionRoute.Shipping(
                        id("dubrovnik"),
                        listOf(
                            ShippingSpace.Coast(id("dubrovnik")),
                            ShippingSpace.Coast(id("durazzo")),
                        ),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    @Test
    fun chesterCannotCrossBiscayDirectlyWithHeavens() {
        val context =
            context(
                    board = BoardState().placeMarker(id("chester"), venice).board,
                    ships = ShippingCapability.GALLEY_2,
                    advances = setOf(AdvanceId.THE_HEAVENS),
                )
                .copy(configuration = CatalogConfiguration(5))

        assertThat(ExpansionReach.routesTo(context, venice, id("seville"))).isEmpty()
    }

    @Test
    fun parisCannotCrossBiscayDirectlyWithHeavens() {
        val context =
            context(
                    board = BoardState().placeMarker(id("paris"), venice).board,
                    ships = ShippingCapability.GALLEY_2,
                    advances = setOf(AdvanceId.THE_HEAVENS),
                )
                .copy(configuration = CatalogConfiguration(5))

        assertThat(ExpansionReach.routesTo(context, venice, id("armagh"))).isEmpty()
    }

    @Test
    fun walesReachesArmaghInOneCoastalStep() {
        val context =
            context(
                    board = colouredTokensAt("wales"),
                    ships = ShippingCapability.GALLEY_2,
                )
                .copy(configuration = CatalogConfiguration(5))

        assertThat(ExpansionReach.routesTo(context, venice, id("armagh")))
            .contains(
                ExpansionRoute.Shipping(
                    id("wales"),
                    listOf(
                        ShippingSpace.Coast(id("wales")),
                        ShippingSpace.Coast(id("armagh")),
                    ),
                )
            )
    }

    @Test
    fun copenhagenCannotCrossTheBalticDirectlyWithHeavens() {
        val context =
            context(
                    board = colouredTokensAt("copenhagen"),
                    ships = ShippingCapability.GALLEY_2,
                    advances = setOf(AdvanceId.THE_HEAVENS),
                )
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("visby"))).isEmpty()
    }

    @Test
    fun malmoCannotReachLubeckInOneCoastalStep() {
        val context =
            context(
                    board = colouredTokensAt("malmo"),
                    ships = ShippingCapability.GALLEY_2,
                )
                .copy(configuration = CatalogConfiguration(6))

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validate(
                    context,
                    venice,
                    id("lubeck"),
                    ExpansionRoute.Shipping(
                        id("malmo"),
                        listOf(
                            ShippingSpace.Coast(id("malmo")),
                            ShippingSpace.Coast(id("lubeck")),
                        ),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    @Test
    fun malmoReachesStettinInOneCoastalStep() {
        val context =
            context(
                    board = colouredTokensAt("malmo"),
                    ships = ShippingCapability.GALLEY_2,
                )
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("stettin")))
            .containsExactly(
                ExpansionRoute.Shipping(
                    id("malmo"),
                    listOf(
                        ShippingSpace.Coast(id("malmo")),
                        ShippingSpace.Coast(id("stettin")),
                    ),
                )
            )
    }

    @Test
    fun copenhagenReachesHamburgWithoutShipping() {
        val context =
            context(board = colouredTokensAt("copenhagen"))
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("hamburg")))
            .contains(ExpansionRoute.Land(id("copenhagen")))
    }

    @Test
    fun kongsbergReachesMalmoWithoutShipping() {
        val context =
            context(board = colouredTokensAt("kongsberg"))
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("malmo")))
            .contains(ExpansionRoute.Land(id("kongsberg")))
    }

    @Test
    fun copenhagenReachesStettinInOneCoastalStep() {
        val context =
            context(
                    board = colouredTokensAt("copenhagen"),
                    ships = ShippingCapability.GALLEY_2,
                )
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("stettin")))
            .contains(
                ExpansionRoute.Shipping(
                    id("copenhagen"),
                    listOf(
                        ShippingSpace.Coast(id("copenhagen")),
                        ShippingSpace.Coast(id("stettin")),
                    ),
                )
            )
    }

    @Test
    fun lubeckCannotCrossTheBalticDirectlyWithHeavens() {
        val context =
            context(
                    board = BoardState().placeMarker(id("lubeck"), venice).board,
                    ships = ShippingCapability.GALLEY_2,
                    advances = setOf(AdvanceId.THE_HEAVENS),
                )
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("visby"))).isEmpty()
    }

    @Test
    fun malmoCannotCrossTheNorthSeaDirectlyWithHeavens() {
        val context =
            context(
                    board = colouredTokensAt("malmo"),
                    ships = ShippingCapability.GALLEY_2,
                    advances = setOf(AdvanceId.THE_HEAVENS),
                )
                .copy(configuration = CatalogConfiguration(6))

        assertThat(ExpansionReach.routesTo(context, venice, id("edinburgh"))).isEmpty()
    }

    @Test
    fun theOriginalCapitalIsABaseWhileOccupiedByAnEnemy() {
        val context = context(board = BoardState().placeMarker(id("venice"), genoa).board)

        assertThat(ExpansionReach.routesTo(context, venice, id("florence")))
            .contains(ExpansionRoute.Land(id("venice")))
    }

    @Test
    fun whitePiecesCannotChainOrdinaryExpansion() {
        val context =
            context(board = BoardState().placeMarker(id("rome"), venice, PieceSide.WHITE).board)

        assertThat(ExpansionReach.routesTo(context, venice, id("naples"))).isEmpty()
    }

    @Test
    fun colouredPartialTokenPresenceIsAnExpansionBase() {
        val context =
            context(
                board =
                    BoardState(
                        persistentMapOf(
                            id("rome") to
                                ProvincePieces.Tokens(
                                    persistentMapOf(venice to TokenHolding(coloured = 1))
                                )
                        )
                    )
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("naples")))
            .contains(ExpansionRoute.Land(id("rome")))
    }

    @Test
    fun caravanReachesTwoLandEdgesFromTheOriginalBase() {
        val context = context(advances = setOf(AdvanceId.CARAVAN))

        assertThat(ExpansionReach.routesTo(context, venice, id("rome")))
            .contains(ExpansionRoute.Caravan(id("venice"), id("florence")))
    }

    @Test
    fun caravanMustDefeatAFullWhiteIntermediateProvinceFirst() {
        val context =
            context(
                advances = setOf(AdvanceId.CARAVAN),
                board = BoardState().placeMarker(id("florence"), genoa, PieceSide.WHITE).board,
            )

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validate(
                    context,
                    venice,
                    id("rome"),
                    ExpansionRoute.Caravan(id("venice"), id("florence")),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.CARAVAN_INTERMEDIATE_OCCUPIED)
    }

    @Test
    fun caravanCanPassThroughAProvinceJustWonByItsPlayer() {
        val context =
            context(
                advances = setOf(AdvanceId.CARAVAN),
                board = BoardState().placeMarker(id("florence"), venice, PieceSide.WHITE).board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("rome")))
            .contains(ExpansionRoute.Caravan(id("venice"), id("florence")))
    }

    @Test
    fun caravanCannotContinueToAThirdLandEdgeThroughNewWhiteMarkers() {
        val context =
            context(
                advances = setOf(AdvanceId.CARAVAN),
                board =
                    BoardState()
                        .placeMarker(id("florence"), venice, PieceSide.WHITE)
                        .board
                        .placeMarker(id("rome"), venice, PieceSide.WHITE)
                        .board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("naples"))).isEmpty()
    }

    @Test
    fun aPartlyOccupiedColouredProvinceDoesNotBlockCaravan() {
        val context =
            context(
                advances = setOf(AdvanceId.CARAVAN),
                board =
                    BoardState(
                        persistentMapOf(
                            id("florence") to
                                ProvincePieces.Tokens(
                                    persistentMapOf(genoa to TokenHolding(coloured = 1))
                                )
                        )
                    ),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("rome")))
            .contains(ExpansionRoute.Caravan(id("venice"), id("florence")))
    }

    @Test
    fun galleysFollowCoastalWaterWithoutCaravan() {
        val context = context(ships = ShippingCapability.GALLEY_2)

        val shipping =
            ExpansionReach.routesTo(context, venice, id("rome"))
                .filterIsInstance<ExpansionRoute.Shipping>()

        assertThat(shipping)
            .containsExactly(
                ExpansionRoute.Shipping(
                    id("venice"),
                    listOf(
                        ShippingSpace.Coast(id("venice")),
                        ShippingSpace.Coast(id("florence")),
                        ShippingSpace.Coast(id("rome")),
                    ),
                )
            )
    }

    @Test
    fun heavensAllowsOneSeaAndCountsItAsOneShippingStep() {
        val context =
            context(
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("bari")))
            .contains(
                ExpansionRoute.Shipping(
                    id("venice"),
                    listOf(
                        ShippingSpace.Coast(id("venice")),
                        ShippingSpace.Sea(SeaArea.ADRIATIC_SEA),
                        ShippingSpace.Coast(id("bari")),
                    ),
                )
            )
    }

    @Test
    fun heavensCannotTreatCentralMediterraneanAndAegeanAsOneSea() {
        val context =
            context(
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
                board = BoardState().placeMarker(id("sicily"), venice).board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("smyrna"))).isEmpty()
    }

    @Test
    fun heavensCrossesTheCentralMediterraneanFromCrete() {
        val context =
            context(
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
                board = BoardState().placeMarker(id("crete"), venice).board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("sicily")))
            .contains(
                ExpansionRoute.Shipping(
                    id("crete"),
                    listOf(
                        ShippingSpace.Coast(id("crete")),
                        ShippingSpace.Sea(SeaArea.CENTRAL_MEDITERRANEAN),
                        ShippingSpace.Coast(id("sicily")),
                    ),
                )
            )
    }

    @Test
    fun heavensCrossesTheAegeanFromCrete() {
        val context =
            context(
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
                board = BoardState().placeMarker(id("crete"), venice).board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("smyrna")))
            .contains(
                ExpansionRoute.Shipping(
                    id("crete"),
                    listOf(
                        ShippingSpace.Coast(id("crete")),
                        ShippingSpace.Sea(SeaArea.AEGEAN_SEA),
                        ShippingSpace.Coast(id("smyrna")),
                    ),
                )
            )
    }

    @Test
    fun heavensCrossesTheEasternMediterraneanFromCrete() {
        val context =
            context(
                ships = ShippingCapability.GALLEY_2,
                advances = setOf(AdvanceId.THE_HEAVENS),
                board = BoardState().placeMarker(id("crete"), venice).board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("cyprus")))
            .contains(
                ExpansionRoute.Shipping(
                    id("crete"),
                    listOf(
                        ShippingSpace.Coast(id("crete")),
                        ShippingSpace.Sea(SeaArea.EASTERN_MEDITERRANEAN),
                        ShippingSpace.Coast(id("cyprus")),
                    ),
                )
            )
    }

    @Test
    fun angoraReachesConstantinopleByLandWithoutShipping() {
        val context =
            context(
                advances = setOf(AdvanceId.OVERLAND_EAST),
                board = BoardState().placeMarker(id("angora"), venice).board,
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("constantinople")))
            .contains(ExpansionRoute.Land(id("angora")))
    }

    @Test
    fun aShippingRouteCannotCrossTwoSeasWithHeavens() {
        val context =
            context(
                ships = ShippingCapability.GALLEY_8,
                advances = setOf(AdvanceId.THE_HEAVENS),
            )

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validate(
                    context,
                    venice,
                    id("sicily"),
                    ExpansionRoute.Shipping(
                        id("venice"),
                        listOf(
                            ShippingSpace.Coast(id("venice")),
                            ShippingSpace.Sea(SeaArea.ADRIATIC_SEA),
                            ShippingSpace.Coast(id("bari")),
                            ShippingSpace.Sea(SeaArea.CENTRAL_MEDITERRANEAN),
                            ShippingSpace.Coast(id("sicily")),
                        ),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    @Test
    fun areaFiveRequiresOverlandEastEvenForOceanNavigation() {
        val context =
            context(
                ships = ShippingCapability.OCEAN_4,
                advances = setOf(AdvanceId.OCEAN_NAVIGATION, AdvanceId.SEAWORTHY_VESSELS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("poti"))).isEmpty()
    }

    @Test
    fun unavailableAreasDoNotExistAsShippingDestinations() {
        val context =
            context(
                ships = ShippingCapability.SEAWORTHY_10,
                advances = setOf(AdvanceId.SEAWORTHY_VESSELS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("london"))).isEmpty()
    }

    @Test
    fun seaworthyVesselsReachWestAfricaWithoutHeavens() {
        val context =
            context(
                ships = ShippingCapability.SEAWORTHY_10,
                advances = setOf(AdvanceId.SEAWORTHY_VESSELS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("west-africa"))).hasSize(1)
    }

    @Test
    fun seaworthyVesselsCannotReachFarEastBoxes() {
        val context =
            context(
                ships = ShippingCapability.SEAWORTHY_16,
                advances = setOf(AdvanceId.SEAWORTHY_VESSELS, AdvanceId.OCEAN_NAVIGATION),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("china"))).isEmpty()
    }

    @Test
    fun newWorldRequiresItsOwnAdvanceAsWellAsOceanNavigation() {
        val context =
            context(
                ships = ShippingCapability.OCEAN_4,
                advances = setOf(AdvanceId.OCEAN_NAVIGATION, AdvanceId.SEAWORTHY_VESSELS),
            )

        assertThat(ExpansionReach.routesTo(context, venice, id("north-america"))).isEmpty()
    }

    @Test
    fun earlierFailedShipmentsConsumeDestinationCapacity() {
        val context =
            context(
                    ships = ShippingCapability.SEAWORTHY_10,
                    advances = setOf(AdvanceId.SEAWORTHY_VESSELS),
                )
                .recordShipment(venice, id("sicily"), tokens = 8)

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validateShippingCapacity(
                    context,
                    venice,
                    id("sicily"),
                    newlyShipped = 3,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
    }

    @Test
    fun shippingCapacityIsSeparateForEachDestination() {
        val context =
            context(
                    ships = ShippingCapability.SEAWORTHY_10,
                    advances = setOf(AdvanceId.SEAWORTHY_VESSELS),
                )
                .recordShipment(venice, id("sicily"), tokens = 10)

        ExpansionReach.validateShippingCapacity(context, venice, id("tunis"), newlyShipped = 10)
    }

    @Test
    fun whitePresenceInAnOverseasBoxCountsTowardOceanLevel() {
        val context =
            context(
                ships = ShippingCapability.OCEAN_1,
                advances = setOf(AdvanceId.OCEAN_NAVIGATION),
                board =
                    BoardState(
                        persistentMapOf(
                            id("china") to
                                ProvincePieces.Tokens(
                                    persistentMapOf(venice to TokenHolding(white = 1))
                                )
                        )
                    ),
            )

        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validateShippingCapacity(
                    context,
                    venice,
                    id("india"),
                    newlyShipped = 1,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.OVERSEAS_LIMIT_EXCEEDED)
    }

    private fun colouredTokensAt(province: String): BoardState =
        BoardState(
            persistentMapOf(
                id(province) to
                    ProvincePieces.Tokens(persistentMapOf(venice to TokenHolding(coloured = 1)))
            )
        )

    private fun context(
        advances: Set<AdvanceId> = emptySet(),
        ships: ShippingCapability = ShippingCapability.NONE,
        board: BoardState = BoardState(),
    ): BoardRulesContext =
        BoardRulesContext(
            configuration = CatalogConfiguration(3),
            board = board,
            players =
                listOf(
                        BoardPlayerProfile(venice, id("venice"), advances, ships),
                        BoardPlayerProfile(genoa, id("genoa")),
                        BoardPlayerProfile(barcelona, id("barcelona")),
                    )
                    .associateBy(BoardPlayerProfile::player),
        )

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
