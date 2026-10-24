package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class HistoryProjectionTest {
    private val owner = PlayerId("p1")
    private val recipient = PlayerId("p2")
    private val observer = PlayerId("p3")
    private val header =
        GameHeader(
            listOf(
                Player(owner, "Alice"),
                Player(recipient, "Bob"),
                Player(observer, "Carol"),
            ),
            owner,
        )
    private val state = GameState(header)

    @Test
    fun projectedStateIncludesEveryRevisionDelta() {
        val stored = startedGame()

        val projected = HistoryProjection.forPlayer(stored, owner)

        assertThat(projected.state).isEqualTo(stored.state())
        assertThat(projected.state.stage).isEqualTo(Stage.BIDDING)
    }

    @Test
    fun projectedHistoryRetainsEntryOrderAndPrivateDrawAudiencesAcrossRevisions() {
        val stored = startedGame()

        val projected = HistoryProjection.forPlayer(stored, owner)

        assertThat(projected.lines)
            .containsExactlyElementsIn(
                defaultConfigurationText() +
                    listOf(
                        "p1: start",
                        "!shuffle <hidden>",
                        "!draw p1 hand-1 stone-1",
                        "!draw p2 hand-2 <hidden>",
                        "!draw p3 hand-3 <hidden>",
                    )
            )
            .inOrder()
    }

    @Test
    fun privateDrawIdentityIsVisibleToItsOwner() {
        val entry = GameOutcome.Draw(owner, "hand-4", "stone-1")

        assertThat(HistoryProjection.line(state, entry, owner)).isEqualTo(entry.toText())
    }

    @Test
    fun privateDrawIdentityIsHiddenFromOtherPlayers() {
        val entry = GameOutcome.Draw(owner, "hand-4", "stone-1")

        assertThat(HistoryProjection.line(state, entry, observer))
            .isEqualTo("!draw p1 hand-4 <hidden>")
    }

    @Test
    fun randomTradeReferenceIsVisibleToBothParticipants() {
        val entry = GameOutcome.Trade(owner, recipient, "private-hand-reference")

        assertThat(
                listOf(owner, recipient).map { viewer ->
                    HistoryProjection.line(state, entry, viewer)
                }
            )
            .containsExactly(entry.toText(), entry.toText())
    }

    @Test
    fun randomTradeReferenceIsHiddenFromOtherPlayers() {
        val entry = GameOutcome.Trade(owner, recipient, "private-hand-reference")

        assertThat(HistoryProjection.line(state, entry, observer))
            .isEqualTo("!trade p1 p2 <hidden>")
    }

    @Test
    fun masterArtDiscardIsHiddenFromOtherPlayers() {
        val entry = Entry.Action(owner, TurnCommand.DiscardCard("private-hand-reference"))

        assertThat(HistoryProjection.line(state, entry, observer))
            .isEqualTo("p1: discard-card <hidden>")
    }

    @Test
    fun masterArtDiscardRemainsVisibleToItsOwner() {
        val entry = Entry.Action(owner, TurnCommand.DiscardCard("private-hand-reference"))

        assertThat(HistoryProjection.line(state, entry, owner)).isEqualTo(entry.toText())
    }

    @Test
    fun offeredCardIsHiddenFromOtherPlayers() {
        val before =
            state.copy(
                flow = TurnFlow(decisions = listOf(BoardDecision.TradeOffer(owner, recipient)))
            )
        val entry = Entry.Action(owner, BoardCommand.OfferCard("private-hand-reference"))

        assertThat(HistoryProjection.line(before, entry, observer))
            .isEqualTo("p1: offer-card <hidden>")
    }

    @Test
    fun offeredCardIsVisibleToBothParticipants() {
        val before =
            state.copy(
                flow = TurnFlow(decisions = listOf(BoardDecision.TradeOffer(owner, recipient)))
            )
        val entry = Entry.Action(owner, BoardCommand.OfferCard("private-hand-reference"))

        assertThat(
                listOf(owner, recipient).map { viewer ->
                    HistoryProjection.line(before, entry, viewer)
                }
            )
            .containsExactly(entry.toText(), entry.toText())
    }

    private fun startedGame(): StoredGame {
        val driver = GameDriver(FixedRandom())
        val created = driver.create("history-test", header, emptyMap())
        return driver.submit(created, owner, Command.Start)
    }
}
