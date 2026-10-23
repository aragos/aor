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
