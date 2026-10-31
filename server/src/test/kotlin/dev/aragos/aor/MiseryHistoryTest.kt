package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class MiseryHistoryTest {
    private val alice = PlayerId("p1")
    private val bob = PlayerId("p2")
    private val carol = PlayerId("p3")
    private val header =
        GameHeader(
            listOf(Player(alice, "Alice"), Player(bob, "Bob"), Player(carol, "Carol")),
            alice,
        )

    @Test
    fun endingExpansionRecordsNamedNewWorldReliefWithoutADrawOrContinuedEntry() {
        val initial = incomeBoundary(MiseryStep.TEN)
        val entry = Entry.Action(carol, TurnCommand.Pass)
        val after = initial.step(entry)

        val events = HistoryEvents.projectAll("8:0", initial, after, entry, bob)

        assertThat(events.map(HistoryEventView::kind)).containsExactly(HistoryEventKind.INCOME)
        assertThat(events.single().miseryChanges).containsExactly(newWorldRelief())
        assertThat(events.single().participants)
            .contains(HistoryParticipantView(alice, "Alice", "venice"))
    }

    @Test
    fun renaissanceOwnerReceivesIncomeReliefWithoutAnIncomeWindow() {
        val base = incomeBoundary(MiseryStep.TEN)
        val player = base.playerState(alice)
        val initial =
            base.copy(
                players =
                    base.players +
                        (alice to
                            player.copy(
                                advances = player.advances.acquire(AdvanceId.RENAISSANCE, 1)
                            ))
            )
        val opening = Entry.Action(carol, TurnCommand.Pass)
        val after = initial.step(opening)

        val events = HistoryEvents.projectAll("8:0", initial, after, opening, bob)

        assertThat(events.single().kind).isEqualTo(HistoryEventKind.INCOME)
        assertThat(events.single().miseryChanges).containsExactly(newWorldRelief())
        assertThat(after.prompts().single()).isInstanceOf(Prompt.System::class.java)
    }

    @Test
    fun newWorldAtZeroOmitsTheClampedNoOp() {
        val initial = incomeBoundary(MiseryStep.ZERO)
        val entry = Entry.Action(carol, TurnCommand.Pass)
        val after = initial.step(entry)

        val events = HistoryEvents.projectAll("8:0", initial, after, entry, bob)

        assertThat(events.single().kind).isEqualTo(HistoryEventKind.INCOME)
        assertThat(events.single().miseryChanges).isEmpty()
        assertThat(after.playerState(alice).misery).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun newWorldDoesNotRelieveOrReviveAPlayerInChaos() {
        val initial = incomeBoundary(MiseryStep.CHAOS)
        val entry = Entry.Action(carol, TurnCommand.Pass)
        val after = initial.step(entry)

        val events = HistoryEvents.projectAll("8:0", initial, after, entry, bob)

        assertThat(events.single().miseryChanges).isEmpty()
        assertThat(events.single().cashChanges.map(HistoryCashChangeView::player))
            .doesNotContain(alice)
        assertThat(after.playerState(alice).misery).isEqualTo(MiseryStep.CHAOS)
    }

    @Test
    fun incomeReceiptsRemainIdenticalWhenReplayedAfterLaterEntriesAndSerialized() {
        val initial = incomeBoundary(MiseryStep.TEN)
        val entries =
            listOf(
                Entry.Action(carol, TurnCommand.Pass),
                GameOutcome.Roll(GameRollKind.SHORTAGE, listOf(DieRoll(6), DieRoll(6), DieRoll(6))),
                GameOutcome.Roll(GameRollKind.SHORTAGE, listOf(DieRoll(6), DieRoll(6), DieRoll(6))),
            )
        val first = replay(initial, entries.take(1)).single()
        val appended = replay(initial, entries)
        val encoded = json.writeValueAsString(first)
        val replayed = json.readTree(json.writeValueAsString(replay(initial, entries).first()))

        assertThat(appended.first()).isEqualTo(first)
        assertThat(replayed).isEqualTo(json.readTree(encoded))
        assertThat(first.miseryChanges).containsExactly(newWorldRelief())
        assertThat(first.participants).contains(HistoryParticipantView(alice, "Alice", "venice"))
    }

    @Test
    fun sciencePurchaseRecordsTheOwnedAdvanceThatProvidedRelief() {
        val initial = purchaseState(AdvanceId.HUMAN_BODY, MiseryStep.TEN)
        val entry = Entry.Action(alice, TurnCommand.BuyAdvance(AdvanceId.HUMAN_BODY.letter))
        val after = initial.step(entry)

        val event = HistoryEvents.project("9:0", initial, after, entry, bob)

        assertThat(event?.miseryChanges)
            .containsExactly(
                HistoryMiseryChangeView(
                    alice,
                    -1,
                    10,
                    0,
                    listOf(
                        HistoryMiseryReasonView(
                            HistoryMiseryReason.ADVANCE_PURCHASE,
                            -1,
                            AdvanceId.HUMAN_BODY,
                        )
                    ),
                )
            )
    }

    @Test
    fun religionPurchaseRecordsTheAdvanceAndActualChaosEndpoint() {
        val initial = purchaseState(AdvanceId.PATRONAGE, MiseryStep.THOUSAND)
        val entry = Entry.Action(alice, TurnCommand.BuyAdvance(AdvanceId.PATRONAGE.letter))
        val after = initial.step(entry)

        val event = HistoryEvents.project("9:0", initial, after, entry, bob)

        assertThat(event?.miseryChanges)
            .containsExactly(
                HistoryMiseryChangeView(
                    alice,
                    1,
                    1000,
                    null,
                    listOf(
                        HistoryMiseryReasonView(
                            HistoryMiseryReason.ADVANCE_PURCHASE,
                            1,
                            AdvanceId.PATRONAGE,
                        )
                    ),
                )
            )
    }

    @Test
    fun scienceReliefAtZeroDoesNotClaimAnActualMovement() {
        val initial = purchaseState(AdvanceId.HUMAN_BODY, MiseryStep.ZERO)
        val entry = Entry.Action(alice, TurnCommand.BuyAdvance(AdvanceId.HUMAN_BODY.letter))
        val after = initial.step(entry)

        val event = HistoryEvents.project("9:0", initial, after, entry, bob)

        assertThat(event?.miseryChanges).isEmpty()
    }

    @Test
    fun revolutionaryUprisingsRecordsEveryActivePlayersMiseryIncludingNoChange() {
        val base = game(Stage.PLAY_CARDS)
        val initial =
            base.copy(
                players =
                    base.players +
                        (alice to
                            base
                                .playerState(alice)
                                .copy(
                                    hand =
                                        listOf(
                                            HandCard(
                                                "hand-1",
                                                CardData.definition("revolutionary-uprisings"),
                                            )
                                        ),
                                    advances =
                                        PlayerAdvances(
                                            listOf(AdvanceAcquisition(AdvanceId.CARAVAN, 1))
                                        ),
                                )) +
                        (carol to base.playerState(carol).copy(misery = MiseryStep.CHAOS))
            )
        val entry = Entry.Action(alice, CardCommand.PlayCard("hand-1", CardChoice.None))
        val after = initial.step(entry)

        val event = HistoryEvents.project("9:0", initial, after, entry, bob)

        val reason =
            listOf(
                HistoryMiseryReasonView(
                    HistoryMiseryReason.CARD_EFFECT,
                    card = "revolutionary-uprisings",
                )
            )
        assertThat(event?.miseryChanges)
            .containsExactly(
                HistoryMiseryChangeView(alice, 1, 0, 10, reason),
                HistoryMiseryChangeView(bob, 0, 0, 0, reason),
            )
        assertThat(event?.participants?.map(HistoryParticipantView::id)).containsAtLeast(alice, bob)
    }

    @Test
    fun everyImmediateMiseryEventNamesItsPublicPlayedCardWithoutPrivateReferences() {
        val events =
            listOf(
                "famine",
                "mysticism-abounds",
                "revolutionary-uprisings",
                "religious-strife",
                "civil-war",
            )
        for (card in events) {
            val base = game(Stage.PLAY_CARDS)
            val players =
                base.players.mapValues { (_, player) ->
                    player.copy(
                        advances =
                            PlayerAdvances(
                                listOf(
                                    AdvanceAcquisition(AdvanceId.PATRONAGE, 1),
                                    AdvanceAcquisition(AdvanceId.CARAVAN, 1),
                                )
                            )
                    )
                }
            val owner =
                checkNotNull(players[alice]) { "The fixture has Alice." }
                    .copy(
                        hand = listOf(HandCard("private-play-reference", CardData.definition(card)))
                    )
            val initial = base.copy(players = players + (alice to owner))
            val choice = if (card == "civil-war") CardChoice.PlayerChoice(bob) else CardChoice.None
            val entry = Entry.Action(alice, CardCommand.PlayCard("private-play-reference", choice))
            val after = initial.step(entry)

            val event = HistoryEvents.project("10:0", initial, after, entry, carol)

            assertThat(event?.miseryChanges).isNotEmpty()
            assertThat(event?.miseryChanges?.flatMap(HistoryMiseryChangeView::reasons)?.distinct())
                .containsExactly(
                    HistoryMiseryReasonView(HistoryMiseryReason.CARD_EFFECT, card = card)
                )
            assertThat(json.writeValueAsString(event)).doesNotContain("private-play-reference")
        }
    }

    @Test
    fun warTieMiseryNamesWarForBothParticipants() {
        val war = WarState(alice, bob, 1)
        val initial =
            game(Stage.PLAY_CARDS)
                .copy(
                    activeWar = war,
                    flow =
                        TurnFlow(
                            actor = alice,
                            phaseStarted = true,
                            decisions = listOf(CardDecision.WarRoll(war)),
                        ),
                )
        val entry = GameOutcome.Roll(GameRollKind.WAR, listOf(DieRoll(2), DieRoll(2)))
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:0", initial, after, entry, carol)

        assertThat(event?.miseryChanges)
            .containsExactly(
                cardChange(alice, 1, 0, 10, "war"),
                cardChange(bob, 1, 0, 10, "war"),
            )
    }

    @Test
    fun immediateWarCessionRecordsWinnerAndLoserMiseryAtTheCession() {
        val victory = WarResult.Victory(alice, bob, 1, mapOf(alice to 4, bob to 3))
        val initial =
            game(Stage.PLAY_CARDS)
                .copy(
                    activeWar = WarState(alice, bob, 1),
                    board = BoardState().placeMarker(ProvinceId("florence"), bob).board,
                    flow =
                        TurnFlow(
                            actor = alice,
                            phaseStarted = true,
                            decisions = listOf(CardDecision.WarSurrender(bob, victory)),
                        ),
                )
        val entry = Entry.Action(bob, CardCommand.CedeWar(setOf(ProvinceId("florence"))))
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:0", initial, after, entry, carol)

        assertThat(event?.miseryChanges)
            .containsExactly(
                cardChange(alice, 1, 0, 10, "war"),
                cardChange(bob, 2, 0, 20, "war"),
            )
    }

    @Test
    fun deferredCrusadesMiseryRetainsTheSourceCardWhenTheMarkerIsForgone() {
        val initial = markerDecision(MarkerPlacementAftermath.Crusades(alice))
        val entry = Entry.Action(alice, CardCommand.ForgoMarker)
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:0", initial, after, entry, carol)

        assertThat(event?.card).isNull()
        assertThat(event?.miseryChanges)
            .containsExactly(cardChange(alice, 1, 0, 10, "the-crusades"))
    }

    @Test
    fun deferredWarMiseryRetainsTheSourceCardWhenTheMarkerIsMoved() {
        val aftermath =
            MarkerPlacementAftermath.War(
                WarSettlement(alice, bob, setOf(ProvinceId("florence")), 1)
            )
        val base = markerDecision(aftermath)
        val initial = base.copy(board = BoardState().placeMarker(ProvinceId("venice"), alice).board)
        val entry = Entry.Action(alice, CardCommand.MoveMarker(ProvinceId("venice")))
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:0", initial, after, entry, carol)

        assertThat(event?.card).isNull()
        assertThat(event?.miseryChanges)
            .containsExactly(
                cardChange(alice, 1, 0, 10, "war"),
                cardChange(bob, 3, 0, 30, "war"),
            )
    }

    @Test
    fun surplusDebtIncludesAnEarlierCommoditySettledByTheSecondRoll() {
        val base = incomeBoundary(MiseryStep.ZERO)
        val income = base.step(Entry.Action(carol, TurnCommand.Pass))
        val first =
            GameOutcome.Roll(GameRollKind.SHORTAGE, listOf(DieRoll(1), DieRoll(1), DieRoll(6)))
        val waiting = income.step(first)
        val player = waiting.playerState(alice).copy(cash = 0)
        val initial =
            waiting.copy(
                players = waiting.players + (alice to player),
                board = BoardState().placeMarker(ProvinceId("rome"), alice).board,
            )
        val entry =
            GameOutcome.Roll(GameRollKind.SHORTAGE, listOf(DieRoll(6), DieRoll(6), DieRoll(6)))
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:1", initial, after, entry, carol)

        assertThat(event?.marketRoll?.commodities).containsExactly(Commodity.GOLD, Commodity.IVORY)
        assertThat(event?.miseryChanges)
            .containsExactly(
                HistoryMiseryChangeView(
                    alice,
                    1,
                    0,
                    10,
                    listOf(HistoryMiseryReasonView(HistoryMiseryReason.SURPLUS_DEBT)),
                )
            )
    }

    @Test
    fun oppositeMiseryMovementsRemainSeparateEvenWhenTheRecordedBatchHasNoNetChange() {
        val initial = purchaseState(AdvanceId.HUMAN_BODY, MiseryStep.TEN)
        val relief = Entry.Action(alice, TurnCommand.BuyAdvance(AdvanceId.HUMAN_BODY.letter))
        val afterRelief = initial.step(relief)
        val gain = Entry.Action(alice, TurnCommand.BuyAdvance(AdvanceId.PATRONAGE.letter))
        val afterGain = afterRelief.step(gain)

        val reliefEvent = HistoryEvents.project("9:0", initial, afterRelief, relief, bob)
        val gainEvent = HistoryEvents.project("9:1", afterRelief, afterGain, gain, bob)

        assertThat(afterGain.playerState(alice).misery).isEqualTo(initial.playerState(alice).misery)
        assertThat(reliefEvent?.miseryChanges?.single()?.spaces).isEqualTo(-1)
        assertThat(gainEvent?.miseryChanges?.single()?.spaces).isEqualTo(1)
        assertThat(reliefEvent?.miseryChanges?.single()?.reasons?.single()?.advance)
            .isEqualTo(AdvanceId.HUMAN_BODY)
        assertThat(gainEvent?.miseryChanges?.single()?.reasons?.single()?.advance)
            .isEqualTo(AdvanceId.PATRONAGE)
    }

    @Test
    fun militaryTieWinWithNoProvinceTransfersStillRecordsBothWarPenalties() {
        val war = WarState(alice, bob, 1)
        val initial =
            game(Stage.PLAY_CARDS)
                .copy(
                    activeWar = war,
                    effects =
                        CardTurnEffects(military = listOf(CardMilitaryPlay(alice, EventId.ARMOR))),
                    flow =
                        TurnFlow(
                            actor = alice,
                            phaseStarted = true,
                            decisions = listOf(CardDecision.WarRoll(war)),
                        ),
                )
        val entry = GameOutcome.Roll(GameRollKind.WAR, listOf(DieRoll(2), DieRoll(3)))
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:0", initial, after, entry, carol)

        assertThat(event?.war?.requiredTransfers).isEqualTo(0)
        assertThat(event?.miseryChanges)
            .containsExactly(
                cardChange(alice, 1, 0, 10, "war"),
                cardChange(bob, 2, 0, 20, "war"),
            )
    }

    @Test
    fun enteringChaosNamesOnlyThePublicCauseAndKeepsDiscardedHandsPrivate() {
        val base = game(Stage.PLAY_CARDS)
        val initial =
            base.copy(
                players =
                    base.players +
                        (alice to
                            base
                                .playerState(alice)
                                .copy(
                                    hand = listOf(HandCard("played", CardData.definition("famine")))
                                )) +
                        (bob to
                            base
                                .playerState(bob)
                                .copy(
                                    misery = MiseryStep.THOUSAND,
                                    hand =
                                        listOf(
                                            HandCard(
                                                "secret-reference",
                                                CardData.definition("stone-1"),
                                            )
                                        ),
                                ))
            )
        val entry = Entry.Action(alice, CardCommand.PlayCard("played"))
        val after = initial.step(entry)

        val event = HistoryEvents.project("10:0", initial, after, entry, carol)

        assertThat(after.playerState(bob).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(event?.miseryChanges)
            .contains(
                HistoryMiseryChangeView(
                    bob,
                    1,
                    1000,
                    null,
                    listOf(
                        HistoryMiseryReasonView(HistoryMiseryReason.CARD_EFFECT, card = "famine")
                    ),
                )
            )
        assertThat(json.writeValueAsString(event)).doesNotContain("secret-reference")
        assertThat(json.writeValueAsString(event)).doesNotContain("stone-1")
    }

    private fun replay(initial: GameState, entries: List<Entry>): List<HistoryEventView> {
        var state = initial
        return entries.flatMapIndexed { index, entry ->
            val before = state
            state = before.step(entry)
            HistoryEvents.projectAll("8:$index", before, state, entry, bob)
        }
    }

    private fun newWorldRelief() =
        HistoryMiseryChangeView(
            alice,
            -1,
            10,
            0,
            listOf(
                HistoryMiseryReasonView(
                    HistoryMiseryReason.INCOME_RELIEF,
                    advance = AdvanceId.NEW_WORLD,
                )
            ),
        )

    private fun cardChange(player: PlayerId, spaces: Int, from: Int, to: Int, card: String) =
        HistoryMiseryChangeView(
            player,
            spaces,
            from,
            to,
            listOf(HistoryMiseryReasonView(HistoryMiseryReason.CARD_EFFECT, card = card)),
        )

    private fun markerDecision(aftermath: MarkerPlacementAftermath) =
        game(Stage.PLAY_CARDS)
            .copy(
                activeWar =
                    if (aftermath is MarkerPlacementAftermath.War) WarState(alice, bob, 1)
                    else null,
                flow =
                    TurnFlow(
                        actor = alice,
                        phaseStarted = true,
                        decisions =
                            listOf(
                                CardDecision.MarkerPlacement(
                                    listOf(ProvinceId("florence")),
                                    aftermath,
                                )
                            ),
                    ),
            )

    private fun incomeBoundary(misery: MiseryStep): GameState {
        val base = game(Stage.EXPANSION)
        return base.copy(
            players =
                base.players +
                    (alice to
                        base
                            .playerState(alice)
                            .copy(
                                misery = misery,
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceAcquisition(AdvanceId.NEW_WORLD, 1))
                                    ),
                            )),
            flow =
                TurnFlow(
                    actor = carol,
                    cursor = 2,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }

    private fun purchaseState(advance: AdvanceId, misery: MiseryStep): GameState {
        val base = game(Stage.PURCHASE)
        val player =
            base
                .playerState(alice)
                .copy(
                    cash = 500,
                    misery = misery,
                    advances =
                        PlayerAdvances(
                            AdvanceData.definition(advance).prerequisites.map {
                                AdvanceAcquisition(it, 1)
                            }
                        ),
                )
        return base.copy(
            players = base.players + (alice to player),
            flow =
                TurnFlow(
                    actor = alice,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.ADVANCES,
                ),
        )
    }

    private fun game(stage: Stage): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header,
            stage = stage,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(listOf(alice, bob, carol)),
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
            flow = TurnFlow(actor = alice, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
