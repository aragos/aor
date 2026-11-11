package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExpansionTacticalFrontierTest {
    private val actor = PlayerId("p1")
    private val defender = PlayerId("p2")
    private val ids = listOf(actor, defender, PlayerId("p3"), PlayerId("p4"))

    @Test
    fun aCheapBlockedAttackExposesOtherFullMarketsAgainstTheSameOwner() {
        val position = fixture()
        val blocked = pending(position.reserve(id("st-gall"), 2)).useCathedral(defender)

        val frontier =
            ExpansionTacticalFrontier.attacks(
                blocked.position,
                defender = defender,
                fullMarketsOnly = true,
            )

        assertEquals(
            setOf(id("florence"), id("genoa")),
            frontier.options.map { it.destination }.toSet(),
        )
        assertEquals(listOf(8, 15), frontier.options.map { it.addedTokens })
        assertTrue(
            frontier.options.all {
                pending(blocked.position.reserve(it.destination, it.addedTokens))
                    .cathedralChoices
                    .isEmpty()
            }
        )
    }

    @Test
    fun anAttackingCathedralOwnerGetsAForceWinChoiceInsteadOfDefenderVetoes() {
        val position = fixture(attacks = setOf(AdvanceId.CATHEDRAL), defends = emptySet())

        val frontier =
            ExpansionTacticalFrontier.responses(pending(position.reserve(id("florence"), 8)))

        assertEquals(ExpansionResponseRole.ACTOR_MAX, frontier.decisionRole)
        assertEquals(listOf(actor), frontier.responseOrder)
        assertEquals(listOf(actor), frontier.branches.mapNotNull { it.declaredBy })
        assertTrue(
            frontier.branches.single { it.declaredBy == actor }.result.competition?.attackerWon ==
                true
        )
    }

    @Test
    fun aDefenderVetoIsAnOptionalMinBranchAndDecliningDoesNotSpendUsage() {
        val position = fixture()

        val frontier =
            ExpansionTacticalFrontier.responses(pending(position.reserve(id("florence"), 8)))

        assertEquals(ExpansionResponseRole.DEFENDER_MIN, frontier.decisionRole)
        assertEquals(
            mapOf(defender to setOf(actor)),
            frontier.branches.single { it.declaredBy != null }.result.position.cathedralUsedAgainst,
        )
        assertTrue(
            frontier.branches
                .filter { it.declaredBy == null }
                .all { it.result.position.cathedralUsedAgainst.isEmpty() }
        )
        assertEquals(216, frontier.branches.sumOf { it.diceOutcomes })
    }

    @Test
    fun twoCathedralOwnersHaveOnlyOrdinaryDiceBranches() {
        val position = fixture(attacks = setOf(AdvanceId.CATHEDRAL))

        val frontier =
            ExpansionTacticalFrontier.responses(pending(position.reserve(id("florence"), 8)))

        assertEquals(ExpansionResponseRole.CHANCE, frontier.decisionRole)
        assertTrue(frontier.responseOrder.isEmpty())
        assertTrue(frontier.branches.all { it.declaredBy == null })
        assertEquals(216, frontier.branches.sumOf { it.diceOutcomes })
    }

    @Test
    fun aNoVetoFrontierPreservesExactDiceCountsAndFlagsVictories() {
        val position = fixture(defends = emptySet())

        val frontier =
            ExpansionTacticalFrontier.responses(pending(position.reserve(id("florence"), 8)))

        assertEquals(ExpansionResponseRole.CHANCE, frontier.decisionRole)
        assertEquals(
            195,
            frontier.branches.single { it.result.competition?.attackerWon == true }.diceOutcomes,
        )
        assertEquals(
            21,
            frontier.branches.single { it.result.competition?.attackerWon == false }.diceOutcomes,
        )
        assertEquals(
            ExpansionBoundary.VICTORY_CONTINUATIONS_UNRESOLVED,
            frontier.branches.single { it.result.competition?.attackerWon == true }.result.boundary,
        )
    }

    @Test
    fun truncationReportsFullCoverageAndRetainsTheRequestedLegalTarget() {
        val position = fixture()

        val frontier =
            ExpansionTacticalFrontier.attacks(position, limit = 1, retain = id("florence"))

        assertEquals(3, frontier.totalLegalOptions)
        assertEquals(listOf(id("florence")), frontier.options.map { it.destination })
    }

    private fun fixture(
        attacks: Set<AdvanceId> = emptySet(),
        defends: Set<AdvanceId> = setOf(AdvanceId.CATHEDRAL),
    ): ExpansionPosition {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            BoardState()
                .replaceWithToken(id("st-gall"), defender)
                .board
                .placeMarker(id("florence"), defender)
                .board
                .placeMarker(id("genoa"), defender)
                .board
        val context =
            BoardRulesContext(
                configuration = CatalogConfiguration(4),
                board = board,
                players =
                    ids.mapIndexed { index, player ->
                            player to
                                BoardPlayerProfile(
                                    player,
                                    id(capitals[index]),
                                    when (player) {
                                        actor -> attacks
                                        defender -> defends
                                        else -> emptySet()
                                    },
                                )
                        }
                        .toMap(),
            )
        return ExpansionPosition(
            context,
            actor,
            20,
            ids.associateWith { 36 - board.tokenCount(it) - if (it == actor) 20 else 0 },
            1,
            ids,
        )
    }

    private fun pending(value: ExpansionTransition): ExpansionTransition.PendingCompetition =
        assertIs(value)

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
