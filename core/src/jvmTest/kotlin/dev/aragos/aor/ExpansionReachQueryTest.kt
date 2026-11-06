package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ExpansionReachQueryTest {
    private val player = PlayerId("p1")
    private val venice = ProvinceId("venice")
    private val florence = ProvinceId("florence")
    private val rome = ProvinceId("rome")

    @Test
    fun repeatedQueriesKeepLandBeforeShippingAndShorterRoutesBeforeLongerOnes() {
        val context =
            context(
                board = BoardState().placeMarker(florence, player).board,
                ships = ShippingCapability.GALLEY_2,
            )
        ExpansionReach.routesTo(context, player, ProvinceId("naples"))
        ExpansionReach.routesTo(context, player, florence)

        val routes = ExpansionReach.routesTo(context, player, rome)

        assertThat(routes)
            .containsExactly(
                ExpansionRoute.Land(florence),
                ExpansionRoute.Shipping(
                    florence,
                    listOf(ShippingSpace.Coast(florence), ShippingSpace.Coast(rome)),
                ),
                ExpansionRoute.Shipping(
                    florence,
                    listOf(
                        ShippingSpace.Coast(florence),
                        ShippingSpace.Sea(SeaArea.ADRIATIC_SEA),
                        ShippingSpace.Coast(rome),
                    ),
                ),
                ExpansionRoute.Shipping(
                    venice,
                    listOf(
                        ShippingSpace.Coast(venice),
                        ShippingSpace.Coast(florence),
                        ShippingSpace.Coast(rome),
                    ),
                ),
                ExpansionRoute.Shipping(
                    venice,
                    listOf(
                        ShippingSpace.Coast(venice),
                        ShippingSpace.Sea(SeaArea.ADRIATIC_SEA),
                        ShippingSpace.Coast(rome),
                    ),
                ),
            )
            .inOrder()
    }

    @Test
    fun changedShippingDoesNotReuseAnEarlierContextCache() {
        val initial = context(ships = ShippingCapability.GALLEY_2)
        ExpansionReach.routesTo(initial, player, rome)
        val changed =
            initial.copy(
                players =
                    initial.players +
                        (player to initial.player(player).copy(ships = ShippingCapability.NONE))
            )

        val routes = ExpansionReach.routesTo(changed, player, rome)

        assertThat(routes).isEmpty()
    }

    @Test
    fun changedBasesAreAvailableToANewContext() {
        val initial = context()
        ExpansionReach.routesTo(initial, player, rome)
        val changed = initial.copy(board = initial.board.placeMarker(florence, player).board)

        val routes = ExpansionReach.routesTo(changed, player, rome)

        assertThat(routes).containsExactly(ExpansionRoute.Land(florence))
    }

    @Test
    fun anEarlierContextRetainsItsBoardSnapshotAfterANewContext() {
        val initial = context()
        val changed = initial.copy(board = initial.board.placeMarker(florence, player).board)
        ExpansionReach.routesTo(changed, player, rome)

        val routes = ExpansionReach.routesTo(initial, player, rome)

        assertThat(routes).isEmpty()
    }

    @Test
    fun batchResultsRetainIndividualRouteOrderForBothMapsAndEveryShippingCapability() {
        for (map in MapRule.entries) {
            for (ships in ShippingCapability.entries) {
                val context =
                    context(
                            board =
                                BoardState(definition = map.definition)
                                    .placeMarker(ProvinceId("crete"), player)
                                    .board,
                            ships = ships,
                        )
                        .copy(configuration = CatalogConfiguration(3, RuleOptions(map = map)))

                for (province in context.configuration.provinces.reversed()) {
                    val routes = ExpansionReach.routesTo(context, player, province.id)

                    assertThat(routes)
                        .containsExactlyElementsIn(
                            ExpansionReach.findRoutesTo(context, player, province.id)
                        )
                        .inOrder()
                }
            }
        }
    }

    private fun context(
        board: BoardState = BoardState(),
        ships: ShippingCapability = ShippingCapability.NONE,
    ): BoardRulesContext =
        BoardRulesContext(
            configuration = CatalogConfiguration(3),
            board = board,
            players =
                listOf(
                        BoardPlayerProfile(
                            player,
                            venice,
                            advances = setOf(AdvanceId.THE_HEAVENS),
                            ships = ships,
                        ),
                        BoardPlayerProfile(PlayerId("p2"), ProvinceId("genoa")),
                        BoardPlayerProfile(PlayerId("p3"), ProvinceId("barcelona")),
                    )
                    .associateBy(BoardPlayerProfile::player),
        )
}
