package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentMapOf

/** Synthetic public predictions are compared with real engine transitions on both targets. */
class ExpansionPositionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val ids = listOf(p1, p2, p3)
    private val win = CompetitionRoll(DieRoll(1), DieRoll(6), DieRoll(1))
    private val loss = CompetitionRoll(DieRoll(6), DieRoll(1), DieRoll(1))

    @Test
    fun blockingACheapSatelliteAttackLeavesTheSameDefenderUnableToBlockTheNextMarket() {
        val board =
            BoardState()
                .replaceWithToken(id("bari"), p2)
                .board
                .placeMarker(id("naples"), p1)
                .board
                .placeMarker(id("florence"), p2)
                .board
        val state = fixture(board, advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)))

        val blocked = pending(position(state).reserve(id("bari"), 2)).useCathedral(p2)
        val followup = pending(blocked.position.reserve(id("florence"), 8))
        val actual = state.act("attack bari").act("cathedral", p2).act("attack florence")

        assertEquals(10, followup.position.poolTokens)
        assertEquals(mapOf(p2 to setOf(p1)), followup.position.cathedralUsedAgainst)
        assertTrue(followup.cathedralChoices.isEmpty())
        assertPosition(actual, followup.position)
        assertEquals(
            listOf(Prompt.System(SystemNeed.GameRoll(GameRollKind.COMPETITION, 3))),
            actual.prompts(),
        )
    }

    @Test
    fun defeatRemovesExistingAttackerTokensBeforeAFreshSameTargetEvaluation() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 2),
                                p2 to TokenHolding(coloured = 1),
                            )
                        )
                )
            )
        val state = fixture(board, advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)))

        val blocked = pending(position(state).reserve(id("florence"), 3)).useCathedral(p2)
        val followup = pending(blocked.position.reserve(id("florence"), 5))
        val actual = state.act("attack florence").act("cathedral", p2).act("attack florence")

        assertEquals(0, followup.plan.existingTokens)
        assertEquals(5, followup.plan.addedTokens)
        assertEquals(19, followup.position.stockTokens[p1])
        assertPosition(actual, followup.position)
    }

    @Test
    fun aFailedShippedAttackConsumesCapacityForTheSameTargetFollowup() {
        val state =
            fixture(
                BoardState().placeMarker(id("rome"), p2).board,
                advances = mapOf(p1 to setOf(AdvanceId.SEAWORTHY_VESSELS)),
                ships = mapOf(p1 to ShippingCapability.SEAWORTHY_10),
            )

        val failed = pending(position(state).reserve(id("rome"), 8)).resolve(loss)
        val actual = state.act("attack rome").roll(loss)
        val predictedFailure =
            assertFailsWith<RuleViolation> { failed.position.reserve(id("rome"), 8) }
        val actualFailure = assertFailsWith<RuleViolation> { actual.act("attack rome") }

        assertEquals(mapOf(id("rome") to 8), failed.position.context.shipped[p1])
        assertEquals(ViolationCode.SHIPPING_CAPACITY_EXCEEDED, predictedFailure.code)
        assertEquals(actualFailure.code, predictedFailure.code)
        assertPosition(actual, failed.position)
    }

    @Test
    fun decliningCathedralThenRollingDoesNotSpendItsPair() {
        val state =
            fixture(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)),
            )

        val failed =
            pending(position(state).reserve(id("florence"), 8)).declineCathedral(p2).resolve(loss)
        val actual = state.act("attack florence").act("pass", p2).roll(loss)

        assertTrue(failed.position.cathedralUsedAgainst.isEmpty())
        assertEquals(ExpansionBoundary.BOARD_RESOLVED, failed.boundary)
        assertPosition(actual, failed.position)
    }

    @Test
    fun blockingRecordsOnlyTheDeclaringOwnerAndCurrentAttacker() {
        val state =
            fixture(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)),
            )

        val blocked = pending(position(state).reserve(id("florence"), 8)).useCathedral(p2)
        val actual = state.act("attack florence").act("cathedral", p2)

        assertEquals(mapOf(p2 to setOf(p1)), blocked.position.cathedralUsedAgainst)
        assertEquals(ExpansionBoundary.BOARD_RESOLVED, blocked.boundary)
        assertPosition(actual, blocked.position)
    }

    @Test
    fun aDiceVictoryWithoutCathedralMatchesAllPublicResourceAndMarkerChanges() {
        val state = fixture(BoardState().placeMarker(id("florence"), p2).board)

        val won = pending(position(state).reserve(id("florence"), 8)).resolve(win)
        val actual = state.act("attack florence").roll(win)

        assertEquals(mapOf(p1 to 1), won.change.markersRequired)
        assertEquals(mapOf(p2 to 1), won.change.markersReturned)
        assertEquals(12, won.position.poolTokens)
        assertEquals(24, won.position.stockTokens[p1])
        assertPosition(actual, won.position)
    }

    @Test
    fun winningReturnsEveryDefendersTokensToThatDefendersStock() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p2 to TokenHolding(coloured = 1),
                                p3 to TokenHolding(coloured = 1),
                            )
                        )
                )
            )
        val state = fixture(board, advances = mapOf(p1 to setOf(AdvanceId.CATHEDRAL)))

        val won = pending(position(state).reserve(id("florence"), 6)).useCathedral(p1)
        val actual = state.act("attack florence").act("cathedral")

        assertEquals(26, won.position.stockTokens[p2])
        assertEquals(26, won.position.stockTokens[p3])
        assertEquals(mapOf(p1 to setOf(p2, p3)), won.position.cathedralUsedAgainst)
        assertPosition(actual, won.position)
    }

    @Test
    fun peacefulConversionReservesPoolOnceAndRetainsItsColouredBase() {
        val state = fixture(BoardState().replaceWithToken(id("florence"), p1).board)

        val placed =
            assertIs<ExpansionTransition.Resolved>(position(state).reserve(id("florence"), 3))
        val actual = state.act("place 3 florence")

        assertEquals(17, placed.position.poolTokens)
        assertEquals(19, placed.position.stockTokens[p1])
        assertTrue(id("florence") in placed.position.context.bases(p1))
        assertEquals(ExpansionBoundary.BOARD_RESOLVED, placed.boundary)
        assertPosition(actual, placed.position)
    }

    @Test
    fun unansweredCathedralResponsesCannotBeBypassedWithDice() {
        val state =
            fixture(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)),
            )
        val reserved = pending(position(state).reserve(id("florence"), 8))

        val failure = assertFailsWith<RuleViolation> { reserved.resolve(win) }

        assertEquals(ViolationCode.INVALID_OUTCOME, failure.code)
    }

    @Test
    fun cathedralResponsesFollowTurnOrderAndDecliningPreservesRemainingChoices() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p2 to TokenHolding(coloured = 1),
                                p3 to TokenHolding(coloured = 1),
                            )
                        )
                )
            )
        val state =
            fixture(
                    board,
                    advances =
                        mapOf(p2 to setOf(AdvanceId.CATHEDRAL), p3 to setOf(AdvanceId.CATHEDRAL)),
                )
                .copy(turnOrderSelection = TurnOrderSelection(listOf(p1, p3, p2)))

        val first = pending(position(state).reserve(id("florence"), 6))
        val second = first.declineCathedral(p3)
        val actual = state.act("attack florence").act("pass", p3)

        assertEquals(listOf(p3, p2), first.cathedralChoices)
        assertEquals(listOf(p2), second.cathedralChoices)
        assertEquals(listOf(Prompt.SinglePlayer(p2, "cathedral")), actual.prompts())
        assertPosition(actual, second.position)
    }

    @Test
    fun aVictoryExplicitlyStopsBeforeAnOptionalWindmillContinuation() {
        val state =
            fixture(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p1 to setOf(AdvanceId.WIND_WATERMILL)),
            )

        val won = pending(position(state).reserve(id("florence"), 8)).resolve(win)
        val actual = state.act("attack florence").roll(win)

        assertEquals(ExpansionBoundary.VICTORY_CONTINUATIONS_UNRESOLVED, won.boundary)
        assertEquals(listOf(Prompt.SinglePlayer(p1, "windmill")), actual.prompts())
        assertPosition(actual, won.position)
    }

    private fun position(state: GameState): ExpansionPosition =
        ExpansionPosition(
            context = BoardGame.context(state),
            actor = p1,
            poolTokens = state.playerState(p1).poolTokens,
            stockTokens = state.players.mapValues { (_, player) -> player.stockTokens },
            orderBox =
                requireNotNull(state.orderBoxes[p1]) { "The fixture requires its printed box." },
            turnOrder = state.turnOrder,
            cathedralUsedAgainst =
                state.flow.cathedralUses
                    .groupBy { it.first }
                    .mapValues { (_, pairs) ->
                        pairs.map { it.second }.toSet()
                    },
        )

    private fun assertPosition(actual: GameState, predicted: ExpansionPosition) {
        assertEquals(position(actual), predicted)
    }

    private fun pending(transition: ExpansionTransition): ExpansionTransition.PendingCompetition =
        assertIs<ExpansionTransition.PendingCompetition>(transition)

    private fun fixture(
        board: BoardState,
        advances: Map<PlayerId, Set<AdvanceId>> = emptyMap(),
        ships: Map<PlayerId, ShippingCapability> = emptyMap(),
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            turnNumber = 1,
            stage = Stage.EXPANSION,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            players =
                ids.mapIndexed { index, player ->
                        val pool = if (player == p1) 20 else 10
                        player to
                            PlayerState(
                                player = Player(player, player.value),
                                capital = capitals[index],
                                hand = listOf(HandCard("held-$index", CardData.epochOne[index])),
                                poolTokens = pool,
                                stockTokens = 36 - pool - board.tokenCount(player),
                                advances =
                                    PlayerAdvances(
                                        advances[player].orEmpty().map { AdvanceAcquisition(it, 1) }
                                    ),
                                ships = ships[player] ?: ShippingCapability.NONE,
                            )
                    }
                    .toMap(),
        )
    }

    private fun GameState.act(text: String, actor: PlayerId = p1): GameState =
        step(Entry.Action(actor, TextCodec.parseCommand(text)))

    private fun GameState.roll(roll: CompetitionRoll): GameState =
        step(GameOutcome.Roll(GameRollKind.COMPETITION, listOf(roll.white, roll.black, roll.green)))

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
