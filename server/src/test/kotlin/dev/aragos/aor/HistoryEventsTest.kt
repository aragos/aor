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

    @Test
    fun winningCompetitionProjectsItsDestinationParticipantsAndDice() {
        val pending =
            competitionState()
                .step(Entry.Action(owner, BoardCommand.Attack(ProvinceId("florence"))))
        val entry =
            GameOutcome.Roll(GameRollKind.COMPETITION, listOf(DieRoll(1), DieRoll(6), DieRoll(6)))
        val after = pending.step(entry)

        val event = HistoryEvents.project("4:1", pending, after, entry, observer)

        assertThat(event?.kind).isEqualTo(HistoryEventKind.COMPETITION_RESULT)
        assertThat(event?.actor).isEqualTo(owner)
        assertThat(event?.provinces).containsExactly(ProvinceId("florence"))
        assertThat(event?.competition).isEqualTo(CompetitionResultView(true, setOf(recipient)))
        assertThat(event?.dice?.map(HistoryDieView::value)).containsExactly(1, 6, 6).inOrder()
    }

    @Test
    fun losingCompetitionIsExplicitEvenThoughTheDefendingBoardRemains() {
        val pending =
            competitionState()
                .step(Entry.Action(owner, BoardCommand.Attack(ProvinceId("florence"))))
        val entry =
            GameOutcome.Roll(GameRollKind.COMPETITION, listOf(DieRoll(6), DieRoll(1), DieRoll(1)))
        val after = pending.step(entry)

        val event = HistoryEvents.project("4:1", pending, after, entry, owner)

        assertThat(event?.competition).isEqualTo(CompetitionResultView(false, setOf(recipient)))
        assertThat(event?.actor).isEqualTo(owner)
    }

    @Test
    fun cathedralDefenceProjectsACompetitionResultWithoutDice() {
        val initial = competitionState()
        val state =
            initial.copy(
                players =
                    initial.players +
                        (recipient to
                            initial
                                .playerState(recipient)
                                .copy(
                                    advances =
                                        PlayerAdvances(
                                            listOf(AdvanceAcquisition(AdvanceId.CATHEDRAL, 1))
                                        )
                                ))
            )
        val pending = state.step(Entry.Action(owner, BoardCommand.Attack(ProvinceId("florence"))))
        val entry = Entry.Action(recipient, BoardCommand.Cathedral)
        val after = pending.step(entry)

        val event = HistoryEvents.project("4:1", pending, after, entry, owner)

        assertThat(event?.competition).isEqualTo(CompetitionResultView(false, setOf(recipient)))
        assertThat(event?.detail).isEqualTo("CATHEDRAL")
        assertThat(event?.dice).isEmpty()
    }

    @Test
    fun warRollProjectsAttributedDiceAndModifiedTotals() {
        val initial = warState()
        val military =
            initial.copy(
                effects = CardTurnEffects(military = listOf(CardMilitaryPlay(owner, EventId.ARMOR)))
            )
        val entry = GameOutcome.Roll(GameRollKind.WAR, listOf(DieRoll(3), DieRoll(3)))
        val after = military.step(entry)

        val event = HistoryEvents.project("5:1", military, after, entry, observer)

        assertThat(event?.kind).isEqualTo(HistoryEventKind.WAR_RESULT)
        assertThat(event?.war?.winner).isEqualTo(owner)
        assertThat(event?.war?.totals).containsExactly(owner, 4, recipient, 3)
        assertThat(event?.war?.requiredTransfers).isEqualTo(1)
        assertThat(event?.dice)
            .containsExactly(HistoryDieView(owner, 3), HistoryDieView(recipient, 3))
            .inOrder()
    }

    @Test
    fun tiedWarRollIsExplicitAndHasNoWinner() {
        val before = warState()
        val entry = GameOutcome.Roll(GameRollKind.WAR, listOf(DieRoll(2), DieRoll(2)))
        val after = before.step(entry)

        val event = HistoryEvents.project("5:1", before, after, entry, owner)

        assertThat(event?.kind).isEqualTo(HistoryEventKind.WAR_RESULT)
        assertThat(event?.war?.winner).isNull()
        assertThat(event?.war?.totals).containsExactly(owner, 2, recipient, 2)
        assertThat(after.activeWar?.ties).isEqualTo(1)
    }

    private fun warState(): GameState {
        val war = WarState(owner, recipient, 1)
        return competitionState()
            .copy(
                stage = Stage.PLAY_CARDS,
                activeWar = war,
                flow =
                    TurnFlow(
                        actor = owner,
                        phaseStarted = true,
                        decisions = listOf(CardDecision.WarRoll(war)),
                    ),
            )
    }

    private fun competitionState(): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header,
            stage = Stage.EXPANSION,
            turnNumber = 1,
            flow = TurnFlow(actor = owner, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(owner, recipient, observer)),
            board = BoardState().placeMarker(ProvinceId("florence"), recipient).board,
            players =
                header.players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                poolTokens = 20,
                                stockTokens = 16,
                            )
                    }
                    .toMap(),
        )
    }

    private fun project(entry: Entry, viewer: PlayerId): HistoryEventView? =
        HistoryEvents.project("2:0", before, before, entry, viewer)
}
