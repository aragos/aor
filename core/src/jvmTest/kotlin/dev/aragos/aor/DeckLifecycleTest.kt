package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DeckLifecycleTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val outer = RenaissanceDecision(p3)

    @Test
    fun consumingTheLastFirstEpochCardWaitsUntilAnotherDrawToChangeEpoch() {
        val state = turn(deck = listOf("stone-1")).queueDraw(p1)

        val drawn = state.draw()

        assertThat(drawn.currentEpoch).isEqualTo(Epoch.ONE)
        assertThat(drawn.endingTriggered).isFalse()
        assertThat(drawn.pendingShuffle()).isNull()
    }

    @Test
    fun nextEpochIncludesOnlyDiscardedCirclesAndItsPrintedCards() {
        val state =
            turn(deck = emptyList())
                .copy(
                    deckHistory =
                        DeckHistory(discarded = listOf("stone-1"), removed = listOf("armor")),
                    players =
                        turn(emptyList()).players +
                            (p1 to
                                turn(emptyList())
                                    .playerState(p1)
                                    .copy(
                                        hand = listOf(HandCard("held", CardData.definition("silk")))
                                    )),
                )
                .queueDraw(p1)

        val request = requireNotNull(state.pendingShuffle())

        assertThat(request.cards)
            .containsExactlyElementsIn(
                CardData.forEpoch(Epoch.TWO).map(CardDefinition::id) + "stone-1"
            )
        assertThat(request.cards).containsNoneOf("armor", "silk")
        assertThat(state.pendingDraw()).isNull()
    }

    @Test
    fun shufflePreservesQueuedBeneficiariesAndOuterDecision() {
        val state = turn(listOf("stone-1")).queueDraw(p1).queueDraw(p2)
        val exhausted = state.draw()
        val request = requireNotNull(exhausted.pendingShuffle())

        val shuffled = exhausted.step(Entry.Shuffle(request.cards.reversed()))
        val delivered = shuffled.draw()

        assertThat(shuffled.currentEpoch).isEqualTo(Epoch.TWO)
        assertThat(delivered.playerState(p1).hand).hasSize(1)
        assertThat(delivered.playerState(p2).hand).hasSize(1)
        assertThat(delivered.flow.decisions).containsExactly(outer)
        assertThat(delivered.endingTriggered).isFalse()
    }

    @Test
    fun secondEpochEmptyDrawAdvancesToThirdExactlyOnce() {
        val state = turn(emptyList()).copy(currentEpoch = Epoch.TWO).queueDraw(p1)
        val request = requireNotNull(state.pendingShuffle())

        val shuffled = state.step(Entry.Shuffle(request.cards))

        assertThat(shuffled.currentEpoch).isEqualTo(Epoch.THREE)
        assertThat(shuffled.pendingShuffle()).isNull()
        assertThat(shuffled.pendingDraw()?.player).isEqualTo(p1)
    }

    @Test
    fun invalidPermutationCannotCreateOrLosePhysicalCards() {
        val state = turn(emptyList()).queueDraw(p1)
        val request = requireNotNull(state.pendingShuffle())

        val failure =
            assertFailsWith<RuleViolation> {
                state.step(Entry.Shuffle(request.cards.drop(1) + request.cards.last()))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }

    @Test
    fun finalExhaustionPreservesOuterDecisionAndDropsUndeliverableDraws() {
        val state =
            turn(listOf("stone-1")).copy(currentEpoch = Epoch.THREE).queueDraw(p1).queueDraw(p2)

        val drawn = state.draw()

        assertThat(drawn.endingTriggered).isTrue()
        assertThat(drawn.flow.decisions).containsExactly(outer)
        assertThat(drawn.pendingShuffle()).isNull()
        assertThat(drawn.playerState(p2).hand).isEmpty()
    }

    @Test
    fun aPendingAdvanceEndingStillAllowsAnOrdinaryEpochChange() {
        val state = turn(emptyList()).copy(endingTriggered = true).queueDraw(p1)

        val request = requireNotNull(state.pendingShuffle())
        val drawn = state.step(Entry.Shuffle(request.cards)).draw()

        assertThat(drawn.currentEpoch).isEqualTo(Epoch.TWO)
        assertThat(drawn.endingTriggered).isTrue()
        assertThat(drawn.playerState(p1).hand).hasSize(1)
    }

    @Test
    fun recyclingAllocatesANewOccurrenceReference() {
        val state = turn(listOf("stone-1")).queueDraw(p1)
        val first = state.draw()
        val recycled =
            first
                .discardCards(first.playerState(p1).hand)
                .copy(
                    players =
                        first.players + (p1 to first.playerState(p1).copy(hand = emptyList())),
                    deck = listOf("stone-1"),
                )
                .queueDraw(p1)

        val second = recycled.draw()

        assertThat(second.playerState(p1).hand.single().reference).isEqualTo("hand-2")
        assertThat(second.deckHistory.nextDraw).isEqualTo(3)
    }

    @Test
    fun chaosDiscardsCirclesAndPermanentlyRemovesSquares() {
        val state = turn(emptyList())
        val victim =
            state
                .playerState(p1)
                .copy(
                    misery = MiseryStep.CHAOS,
                    hand =
                        listOf(
                            HandCard("a", CardData.definition("stone-1")),
                            HandCard("b", CardData.definition("armor")),
                        ),
                )

        val eliminated = state.replacePlayer(victim)

        assertThat(eliminated.playerState(p1).hand).isEmpty()
        assertThat(eliminated.deckHistory.discarded).containsExactly("stone-1")
        assertThat(eliminated.deckHistory.removed).containsExactly("armor")
    }

    @Test
    fun eliminatedBeneficiaryDoesNotConsumeTheNextEpochsCard() {
        val state = turn(emptyList()).queueDraw(p1).queueDraw(p2)
        val eliminated = state.replacePlayer(state.playerState(p1).copy(misery = MiseryStep.CHAOS))
        val request = requireNotNull(eliminated.pendingShuffle())

        val drawn = eliminated.step(Entry.Shuffle(request.cards)).draw()

        assertThat(drawn.playerState(p1).hand).isEmpty()
        assertThat(drawn.playerState(p2).hand).hasSize(1)
    }

    @Test
    fun finalEpochDoesNotAcceptAnotherShuffle() {
        val state = turn(emptyList()).copy(currentEpoch = Epoch.THREE)

        val failure =
            assertFailsWith<RuleViolation> { state.step(Entry.Shuffle(listOf("stone-1"))) }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    @Test
    fun epochConfigurationIsRejectedAsUnsupported() {
        val failure =
            assertFailsWith<RuleViolation> { TextCodec.parseCommand("set-option epochs 1") }

        assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
    }

    private fun turn(deck: List<String>): GameState =
        GameState(
            header =
                GameHeader(listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three")), p1),
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            flow = TurnFlow(actor = p1, phaseStarted = true, decisions = listOf(outer)),
            deck = deck,
        )

    private fun GameState.draw(): GameState {
        val need = requireNotNull(pendingDraw())
        return step(GameOutcome.Draw(need.player, need.reference, need.card))
    }
}
