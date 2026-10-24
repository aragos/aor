package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SetupDealTest {
    private val p1 = PlayerId("p1")

    @Test
    fun improvedSetupReservesFiveCardsAndMovesOnlyOneTimberInstance() {
        val state =
            newGame()
                .copy(rules = RuleOptions(setup = SetupRule.IMPROVED))
                .step(Entry.Action(p1, Command.Start))
        val request = requireNotNull(state.pendingShuffle())

        assertThat(request.cards).hasSize(25)
        assertThat(request.cards).contains("timber-2-1")
        assertThat(state.deckHistory.setupReserves)
            .containsExactly(
                "the-crusades",
                "walter-the-penniless",
                "rashid-ad-din",
                "silk",
                "spice",
            )
        assertThat(request.cards).containsNoneIn(state.deckHistory.setupReserves)
    }

    @Test
    fun improvedReturnsAreNotDiscardsAndShuffleOnlyWhenEverybodyReturns() {
        val dealt = deal(newGame().copy(rules = RuleOptions(setup = SetupRule.IMPROVED)))
        val selected =
            dealt.playerIds.fold(dealt) { current, id ->
                current.step(
                    Entry.Action(
                        id,
                        SetupCommand.ReturnCard(current.playerState(id).hand.last().reference),
                    )
                )
            }
        val request = requireNotNull(selected.pendingShuffle())

        assertThat(dealt.stage).isEqualTo(Stage.SETUP_RETURN)
        assertThat(selected.players.values.map { player -> player.hand.size })
            .containsExactly(2, 2, 2)
        assertThat(selected.deckHistory.discarded).isEmpty()
        assertThat(request.purpose).isEqualTo(ShufflePurpose.SETUP_RETURNS)
        assertThat(request.cards).hasSize(19)
        assertThat(selected.step(Entry.Shuffle(request.cards)).stage).isEqualTo(Stage.BIDDING)
    }

    @Test
    fun duplicatePrivateReturnIsRejectedWithoutLosingAnotherCard() {
        val dealt = deal(newGame().copy(rules = RuleOptions(setup = SetupRule.IMPROVED)))
        val returned =
            dealt.step(
                Entry.Action(
                    p1,
                    SetupCommand.ReturnCard(dealt.playerState(p1).hand.first().reference),
                )
            )

        val failure =
            assertFailsWith<RuleViolation> {
                returned.step(
                    Entry.Action(
                        p1,
                        SetupCommand.ReturnCard(returned.playerState(p1).hand.first().reference),
                    )
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
        assertThat(returned.playerState(p1).hand).hasSize(2)
        assertThat(returned.pendingShuffle()).isNull()
    }

    @Test
    fun commodityInitialCardsPreserveEveryNondealtCardAndItsOrder() {
        val state =
            newGame()
                .copy(rules = RuleOptions(initialCard = InitialCardRule.COMMODITY_ONLY))
                .step(Entry.Action(p1, Command.Start))
        val cards =
            requireNotNull(state.pendingShuffle()).cards.sortedBy { id ->
                if (CardData.definition(id).kind == CardKind.COMMODITY) 1 else 0
            }
        val shuffled = state.step(Entry.Shuffle(cards))
        val dealt = drawAll(shuffled)
        val hands =
            dealt.players.values.flatMap(PlayerState::hand).map { card -> card.definition.id }

        assertThat(
                dealt.players.values.flatMap(PlayerState::hand).map { card -> card.definition.kind }
            )
            .containsExactly(CardKind.COMMODITY, CardKind.COMMODITY, CardKind.COMMODITY)
        assertThat(dealt.deck).containsExactlyElementsIn(cards.filterNot(hands::contains)).inOrder()
        assertThat(dealt.stage).isEqualTo(Stage.BIDDING)
    }

    @Test
    fun improvedSetupIsIncompatibleWithCommodityOnlyInitialDeck() {
        val state =
            newGame()
                .copy(
                    rules =
                        RuleOptions(
                            setup = SetupRule.IMPROVED,
                            initialCard = InitialCardRule.COMMODITY_ONLY,
                        )
                )

        val failure = assertFailsWith<RuleViolation> { state.step(Entry.Action(p1, Command.Start)) }

        assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
    }

    @Test
    fun setupReturnsCannotSelectAnotherPlayersOccurrence() {
        val dealt = deal(newGame().copy(rules = RuleOptions(setup = SetupRule.IMPROVED)))
        val other = dealt.playerState(PlayerId("p2")).hand.first().reference

        val failure =
            assertFailsWith<RuleViolation> {
                dealt.step(Entry.Action(p1, SetupCommand.ReturnCard(other)))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_SETUP_RETURN)
    }

    @Test
    fun reserveReinsertionIsAnExactRecordedShuffleAndRunsOnce() {
        val initial =
            SetupDeal.prepare(newGame().copy(rules = RuleOptions(setup = SetupRule.IMPROVED)))
        val before = initial.copy(turnNumber = 1, stage = Stage.DRAW_CARD, deck = listOf("stone-1"))
        val resumed = before.copy(stage = Stage.BUY_CARD)
        val waiting = SetupDeal.afterRegularDraw(before, resumed)
        val request = requireNotNull(waiting.pendingShuffle())

        val shuffled = waiting.applyShuffle(request.cards.reversed())

        assertThat(request.cards)
            .containsExactlyElementsIn(before.deck + initial.deckHistory.setupReserves)
        assertThat(shuffled.deckHistory.setupReserves).isEmpty()
        assertThat(SetupDeal.afterRegularDraw(shuffled, shuffled).pendingShuffle()).isNull()
    }

    @Test
    fun reserveTimingVariesWithPlayerCount() {
        assertThat((3..6).map(SetupDeal::reinsertionTurn)).containsExactly(1, 1, 2, 2).inOrder()
    }

    @Test
    fun improvedPhysicalCompositionNeverAddsTheMovedTimberAgain() {
        val state =
            SetupDeal.prepare(newGame().copy(rules = RuleOptions(setup = SetupRule.IMPROVED)))
                .copy(stage = Stage.BUY_CARD, deck = emptyList())
                .queueDraw(p1)

        val request = requireNotNull(state.pendingShuffle())

        assertThat(request.cards).doesNotContain("timber-2-1")
        assertThat(request.cards).contains("timber-2-2")
    }

    @Test
    fun completeImprovedSetupReplaysWithoutFreshRandomness() {
        val initial = newGame()
        var state = initial
        val entries = mutableListOf<Entry>()
        fun accept(entry: Entry) {
            entries += entry
            state = state.step(entry)
        }
        accept(Entry.Action(p1, RuleOptionCommand("setup", "improved")))
        accept(Entry.Action(p1, Command.Start))
        accept(Entry.Shuffle(requireNotNull(state.pendingShuffle()).cards.reversed()))
        while (state.stage == Stage.INITIAL_DRAW) {
            val draw = requireNotNull(state.pendingDraw())
            accept(GameOutcome.Draw(draw.player, draw.reference, draw.card))
        }
        for (id in state.playerIds) {
            accept(
                Entry.Action(
                    id,
                    SetupCommand.ReturnCard(state.playerState(id).hand.first().reference),
                )
            )
        }
        accept(Entry.Shuffle(requireNotNull(state.pendingShuffle()).cards.reversed()))

        val replayed = replay(initial.header, entries.map(Entry::toText))

        assertThat(replayed).isEqualTo(state)
        assertThat(replayed.stage).isEqualTo(Stage.BIDDING)
    }

    private fun newGame(): GameState {
        val players = (1..3).map { index -> Player(PlayerId("p$index"), "Player $index") }
        return GameState(GameHeader(players, p1))
    }

    private fun deal(initial: GameState): GameState {
        val started = initial.step(Entry.Action(p1, Command.Start))
        return drawAll(started.step(Entry.Shuffle(requireNotNull(started.pendingShuffle()).cards)))
    }

    private fun drawAll(initial: GameState): GameState {
        var state = initial
        while (state.stage == Stage.INITIAL_DRAW) {
            val need = requireNotNull(state.pendingDraw())
            state = state.step(GameOutcome.Draw(need.player, need.reference, need.card))
        }
        return state
    }
}
