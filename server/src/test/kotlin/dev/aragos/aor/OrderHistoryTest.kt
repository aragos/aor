package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class OrderHistoryTest {
    private val ids = (1..3).map { PlayerId("p$it") }
    private val p1 = ids[0]
    private val p2 = ids[1]
    private val p3 = ids[2]

    @Test
    fun civilWarRecordsAutomaticPositionsAtExpansionOpening() {
        val before = game().copy(effects = CardTurnEffects(civilWarVictims = listOf(p1)))
        val entry = Entry.Action(p3, TurnCommand.Pass)
        val after = before.step(entry)

        val events = HistoryEvents.projectAll("4:0", before, after, entry, p2)

        val event = events.single { it.kind == HistoryEventKind.ORDER_CHANGED }
        assertThat(event.stage).isEqualTo(Stage.EXPANSION)
        assertThat(event.actor).isNull()
        assertThat(event.card).isEqualTo("civil-war")
        assertThat(event.positionChanges)
            .containsExactly(
                HistoryPositionChangeView(p1, 1, 3),
                HistoryPositionChangeView(p2, 2, 1),
                HistoryPositionChangeView(p3, 3, 2),
            )
            .inOrder()
        assertThat(event.participants).contains(HistoryParticipantView(p1, "One", "venice"))
        assertThat(json.readTree(json.writeValueAsString(event))["positionChanges"].size())
            .isEqualTo(3)
    }

    @Test
    fun renaissanceRecordsBothPositionsAndRemainsStableAfterLaterActions() {
        val base = game()
        val player = base.playerState(p1)
        val before =
            base
                .copy(
                    players =
                        base.players +
                            (p1 to
                                player.copy(
                                    advances = player.advances.acquire(AdvanceId.RENAISSANCE, 1)
                                ))
                )
                .step(Entry.Action(p3, TurnCommand.Pass))
        val entry = Entry.Action(p1, TurnCommand.SwapOrder(p2))
        val after = before.step(entry)
        val event = HistoryEvents.projectAll("4:0", before, after, entry, p3).single()

        after.step(Entry.Action(p2, TurnCommand.Pass))

        assertThat(event.kind).isEqualTo(HistoryEventKind.SWAP_ORDER)
        assertThat(event.positionChanges)
            .containsExactly(
                HistoryPositionChangeView(p1, 1, 2),
                HistoryPositionChangeView(p2, 2, 1),
            )
        assertThat(HistoryEvents.projectAll("4:0", before, after, entry, p3)).containsExactly(event)
    }

    @Test
    fun unchangedCivilWarPositionDoesNotCreateAnOrderReceipt() {
        val before = game().copy(effects = CardTurnEffects(civilWarVictims = listOf(p3)))
        val entry = Entry.Action(p3, TurnCommand.Pass)

        val events = HistoryEvents.projectAll("4:0", before, before.step(entry), entry, p2)

        assertThat(events.none { it.kind == HistoryEventKind.ORDER_CHANGED }).isTrue()
    }

    private fun game(): GameState {
        val players = ids.zip(listOf("One", "Two", "Three")).map { (id, name) -> Player(id, name) }
        val base =
            GameState(
                GameHeader(players, p1),
                turnNumber = 2,
                stage = Stage.PURCHASE,
                turnOrderSelection = TurnOrderSelection(ids),
                flow =
                    TurnFlow(
                        actor = p3,
                        cursor = 2,
                        purchaseStep = PurchaseStep.RELIEF,
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                    ),
            )
        return base.copy(
            players =
                base.players.mapValues { (id, player) ->
                    player.copy(capital = listOf("venice", "genoa", "barcelona")[ids.indexOf(id)])
                }
        )
    }
}
