package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class HistoryEventsTest {
    private val owner = PlayerId("p1")
    private val recipient = PlayerId("p2")
    private val observer = PlayerId("p3")
    private val header =
        GameHeader(
            listOf(Player(owner, "Alice"), Player(recipient, "Bob"), Player(observer, "Carol")),
            owner,
        )
    private val card = HandCard("hand-9", CardData.definition("stone-1"))
    private val before =
        GameState(header).let { state ->
            state.copy(
                players =
                    state.players + (owner to state.playerState(owner).copy(hand = listOf(card)))
            )
        }

    @Test
    fun drawIdentityIsVisibleToTheReceivingPlayer() {
        val event = project(GameOutcome.Draw(owner, card.reference, card.definition.id), owner)

        assertThat(event?.card).isEqualTo("stone-1")
        assertThat(event?.kind).isEqualTo(HistoryEventKind.DRAW_CARD)
    }

    @Test
    fun anotherPlayersDrawContainsNeitherIdentityNorHandReference() {
        val event = project(GameOutcome.Draw(owner, card.reference, card.definition.id), observer)
        val encoded = json.writeValueAsString(event)

        assertThat(encoded).doesNotContain("stone-1")
        assertThat(encoded).doesNotContain("hand-9")
        assertThat(event?.actor).isEqualTo(owner)
    }

    @Test
    fun playedCardsArePublicCatalogIdentities() {
        val event = project(Entry.Action(owner, CardCommand.PlayCard(card.reference)), observer)

        assertThat(event?.card).isEqualTo("stone-1")
        assertThat(event?.kind).isEqualTo(HistoryEventKind.PLAY_CARD)
    }

    @Test
    fun randomTradeRevealsTheCardToItsRecipient() {
        val event = project(GameOutcome.Trade(owner, recipient, card.reference), recipient)

        assertThat(event?.card).isEqualTo("stone-1")
        assertThat(event?.target).isEqualTo(recipient)
    }

    @Test
    fun randomTradeHidesTheCardFromObservers() {
        val event = project(GameOutcome.Trade(owner, recipient, card.reference), observer)

        assertThat(event?.card).isNull()
        assertThat(json.writeValueAsString(event)).doesNotContain("hand-9")
    }

    @Test
    fun offeredCardIsVisibleToBothTradeParticipants() {
        val state =
            before.copy(
                flow = TurnFlow(decisions = listOf(BoardDecision.TradeOffer(owner, recipient)))
            )
        val entry = Entry.Action(owner, BoardCommand.OfferCard(card.reference))

        val events =
            listOf(owner, recipient).map { viewer ->
                HistoryEvents.project("2:0", state, state, entry, viewer)
            }

        assertThat(events.map { event -> event?.card }).containsExactly("stone-1", "stone-1")
    }

    @Test
    fun offeredCardIsHiddenFromObservers() {
        val state =
            before.copy(
                flow = TurnFlow(decisions = listOf(BoardDecision.TradeOffer(owner, recipient)))
            )
        val entry = Entry.Action(owner, BoardCommand.OfferCard(card.reference))

        val event = HistoryEvents.project("2:0", state, state, entry, observer)

        assertThat(event?.card).isNull()
    }

    @Test
    fun discardedCardRemainsPrivate() {
        val event = project(Entry.Action(owner, TurnCommand.DiscardCard(card.reference)), observer)

        assertThat(event?.card).isNull()
        assertThat(event?.kind).isEqualTo(HistoryEventKind.DISCARD_CARD)
    }

    @Test
    fun setupReturnRemainsPrivate() {
        val event = project(Entry.Action(owner, SetupCommand.ReturnCard(card.reference)), observer)

        assertThat(event?.card).isNull()
        assertThat(event?.kind).isEqualTo(HistoryEventKind.RETURN_CARD)
    }

    @Test
    fun establishingAMarkerHasItsOwnEvent() {
        val province = ProvinceId("valencia")
        val entry = Entry.Action(owner, BoardCommand.Place(2, province))
        val after =
            before.copy(board = before.board.placeMarker(province, owner, PieceSide.WHITE).board)

        val event = HistoryEvents.project("3:1", before, after, entry, observer)

        assertThat(event?.kind).isEqualTo(HistoryEventKind.ESTABLISH_MARKER)
        assertThat(event?.provinces).containsExactly(province)
        assertThat(event?.amount).isEqualTo(2)
    }

    @Test
    fun configurationAndShufflesDoNotExposeInternalActivity() {
        val entries =
            listOf(
                Entry.Action(owner, RuleOptionCommand("preset", "ah1996")),
                Entry.Shuffle(listOf("stone-1", "stone-2")),
            )

        assertThat(entries.mapNotNull { entry -> project(entry, observer) }).isEmpty()
    }

    @Test
    fun tieDiceKeepTheirExplicitPlayerAttribution() {
        val entry =
            GameOutcome.Roll(
                GameRollKind.CAPITAL_TIE,
                listOf(DieRoll(6), DieRoll(2)),
                listOf(recipient, owner),
            )

        val event = project(entry, observer)

        assertThat(event?.dice)
            .containsExactly(HistoryDieView(recipient, 6), HistoryDieView(owner, 2))
            .inOrder()
    }

    @Test
    fun replayProjectsStableIdsInCommittedOrder() {
        val driver = GameDriver(FixedRandom())
        val started =
            driver.submit(driver.create("activity-test", header, emptyMap()), owner, Command.Start)

        val events = HistoryProjection.forPlayer(started, owner).events

        assertThat(events.map(HistoryEventView::id))
            .containsExactly("2:0", "2:2", "2:3", "2:4")
            .inOrder()
        assertThat(events).isEqualTo(HistoryProjection.forPlayer(started, owner).events)
        assertThat(events.map(HistoryEventView::card))
            .containsExactly(null, "stone-1", null, null)
            .inOrder()
    }

    private fun project(entry: Entry, viewer: PlayerId): HistoryEventView? =
        HistoryEvents.project("2:0", before, before, entry, viewer)
}
