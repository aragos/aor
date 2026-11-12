package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpansionBonusProjectionTest {
    private val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
    private val first = players[0].id
    private val second = players[1].id

    @Test
    fun whiteVoluntaryRebuildDoesNotCountLikeOrdinaryExpansion() {
        val state =
            fixture()
                .copy(
                    flow =
                        TurnFlow(
                            markerOrigins =
                                mapOf(
                                    ProvinceId("venice") to MarkerPlacementOrigin.VOLUNTARY_REBUILD
                                )
                        )
                )

        assertEquals(
            listOf(0, 1, 0),
            ExpansionBonusProjection.from(state).players.map { it.eligibleMarkers },
        )
    }

    @Test
    fun eventRebuildRespectsTheRecordedRuleOption() {
        val state =
            fixture()
                .copy(
                    flow =
                        TurnFlow(
                            markerOrigins =
                                mapOf(ProvinceId("venice") to MarkerPlacementOrigin.EVENT_REBUILD)
                        )
                )

        assertEquals(0, ExpansionBonusProjection.from(state).players.first().eligibleMarkers)
        assertEquals(
            1,
            ExpansionBonusProjection.from(
                    state.copy(rebuiltMarkerBonus = RebuiltMarkerBonus.INCLUDE)
                )
                .players
                .first()
                .eligibleMarkers,
        )
    }

    @Test
    fun currentLeaderUsesActualTurnOrderOnTies() {
        val state =
            fixture()
                .copy(turnOrderSelection = TurnOrderSelection(listOf(second, first, players[2].id)))

        assertEquals(second, ExpansionBonusProjection.from(state).leader)
    }

    @Test
    fun eliminatedSeatsDoNotReceiveTheBonus() {
        val state =
            fixture()
                .copy(
                    players =
                        fixture().players +
                            (first to fixture().playerState(first).copy(misery = MiseryStep.CHAOS))
                )

        assertEquals(second, ExpansionBonusProjection.from(state).leader)
    }

    @Test
    fun colouredMarkersDoNotContribute() {
        val state =
            fixture().copy(board = BoardState().placeMarker(ProvinceId("venice"), first).board)

        assertNull(ExpansionBonusProjection.from(state).leader)
    }

    @Test
    fun hiddenDeckAndOpponentCardsDoNotChangePublicEligibility() {
        val state = fixture()
        val changed =
            state.copy(
                deck = listOf("gold-3"),
                players =
                    state.players +
                        (second to
                            state
                                .playerState(second)
                                .copy(
                                    hand =
                                        listOf(HandCard("private", CardData.definition("stone-1")))
                                )),
            )

        assertEquals(ExpansionBonusProjection.from(state), ExpansionBonusProjection.from(changed))
    }

    private fun fixture(): GameState =
        GameState(
            header = GameHeader(players, first),
            stage = Stage.EXPANSION,
            turnNumber = 1,
            players = players.associate { it.id to PlayerState(it) },
            turnOrderSelection = TurnOrderSelection(players.map { it.id }),
            board =
                BoardState()
                    .placeMarker(ProvinceId("venice"), first, PieceSide.WHITE)
                    .board
                    .placeMarker(ProvinceId("genoa"), second, PieceSide.WHITE)
                    .board,
        )
}
