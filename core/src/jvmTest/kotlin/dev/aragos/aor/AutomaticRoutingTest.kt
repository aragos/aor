package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AutomaticRoutingTest {
    private val player = PlayerId("p1")

    @Test
    fun caravanAvoidsChargingGalleysEvenWhenTheDestinationQuotaIsExhausted() {
        val context =
            context(advances = setOf(AdvanceId.CARAVAN))
                .copy(shipped = mapOf(player to mapOf(id("rome") to 4)))

        val plan = BoardPlacementRules.evaluate(context, player, id("rome"), 1, 20)

        assertThat(plan.route).isInstanceOf(ExpansionRoute.Caravan::class.java)
        assertThat(BoardPlacementRules.recordShipping(context, plan).shipped)
            .isEqualTo(context.shipped)
    }

    @Test
    fun galleyOnlyDestinationUsesTheShortestRouteFromAnyOriginalBase() {
        val context = context(board = BoardState().replaceWithToken(id("bari"), player).board)

        val plan = BoardPlacementRules.evaluate(context, player, id("sicily"), 1, 20)

        assertThat(plan.route)
            .isEqualTo(
                ExpansionRoute.Shipping(
                    id("bari"),
                    listOf(ShippingSpace.Coast(id("bari")), ShippingSpace.Coast(id("sicily"))),
                )
            )
        assertThat(BoardPlacementRules.recordShipping(context, plan).shipped[player])
            .containsExactly(id("sicily"), 1)
    }

    @Test
    fun equivalentRoutesDoNotDependOnBoardInsertionOrder() {
        val first =
            BoardState()
                .replaceWithToken(id("naples"), player)
                .board
                .replaceWithToken(id("bari"), player)
                .board
        val second =
            BoardState()
                .replaceWithToken(id("bari"), player)
                .board
                .replaceWithToken(id("naples"), player)
                .board

        val firstPlan = BoardPlacementRules.evaluate(context(first), player, id("sicily"), 1, 20)
        val secondPlan = BoardPlacementRules.evaluate(context(second), player, id("sicily"), 1, 20)

        assertThat(firstPlan.route).isEqualTo(secondPlan.route)
        assertThat(firstPlan.route.base).isEqualTo(id("bari"))
    }

    @Test
    fun aGalleyRouteMustHaveEnoughCapacityForTheActualCommitment() {
        val context = context().copy(shipped = mapOf(player to mapOf(id("rome") to 3)))

        val failure =
            assertFailsWith<RuleViolation> {
                BoardPlacementRules.evaluate(context, player, id("rome"), 2, 20)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
    }

    @Test
    fun routeSuffixesAreRejectedForPlacementsAndAttacks() {
        val commands =
            listOf(
                "place 1 rome land",
                "place 1 rome ship",
                "place 1 rome caravan florence",
                "attack rome land",
                "attack rome ship",
                "attack rome caravan florence",
            )

        val failures = commands.map { text ->
            assertFailsWith<RuleViolation> { TextCodec.parseCommand(text) }.code
        }

        assertThat(failures)
            .containsExactlyElementsIn(List(commands.size) { ViolationCode.INVALID_COMMAND })
    }

    private fun context(
        board: BoardState = BoardState(),
        advances: Set<AdvanceId> = emptySet(),
    ): BoardRulesContext =
        BoardRulesContext(
            configuration = CatalogConfiguration(3),
            board = board,
            players =
                listOf(
                        BoardPlayerProfile(
                            player,
                            id("venice"),
                            advances,
                            ShippingCapability.GALLEY_4,
                        ),
                        BoardPlayerProfile(PlayerId("p2"), id("genoa")),
                        BoardPlayerProfile(PlayerId("p3"), id("barcelona")),
                    )
                    .associateBy(BoardPlayerProfile::player),
        )

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
