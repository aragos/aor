package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class HistoryRequestIdentityTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val header =
        GameHeader(listOf(Player(p1, "Alice"), Player(p2, "Bob"), Player(p3, "Carol")), p1)

    @Test
    fun equalDiceForDifferentTradeVictimsCannotSubstitute() {
        val first = state(BoardDecision.WindmillDice(p1, p2, 2))
        val second = state(BoardDecision.WindmillDice(p1, p3, 2))

        assertThat(key(first)).isNotEqualTo(key(second))
    }

    @Test
    fun warsWithTheSameParticipantsKeepTheirOriginalIdentity() {
        val first = state(CardDecision.WarRoll(WarState(p1, p2, startedTurn = 1)))
        val second = state(CardDecision.WarRoll(WarState(p1, p2, startedTurn = 2)))

        assertThat(key(first)).isNotEqualTo(key(second))
    }

    @Test
    fun shuffleIdentityPermitsSourceOrderChangesButPreservesPhysicalCards() {
        val state = state(BoardDecision.WindmillDice(p1, p2, 2))
        fun shuffled(cards: List<String>) =
            state.copy(
                deckHistory =
                    DeckHistory(pendingShuffle = ShuffleRequest(cards, ShufflePurpose.NEXT_EPOCH))
            )

        assertThat(key(shuffled(listOf("stone-1", "stone-2"))))
            .isEqualTo(key(shuffled(listOf("stone-2", "stone-1"))))
        assertThat(key(shuffled(listOf("stone-1", "stone-2"))))
            .isNotEqualTo(key(shuffled(listOf("stone-1", "wool-1"))))
    }

    private fun state(decision: TurnDecision) =
        GameState(
            header,
            stage = Stage.EXPANSION,
            turnNumber = 3,
            flow = TurnFlow(actor = p1, decisions = listOf(decision)),
        )

    private fun key(state: GameState) = OutcomeIdentity.requestKey(state, mutableMapOf())
}
