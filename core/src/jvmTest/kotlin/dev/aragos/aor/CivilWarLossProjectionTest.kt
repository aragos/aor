package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CivilWarLossProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val genoa = ProvinceId("genoa")

    @Test
    fun cashQuoteCapsHalfWrittenCashAtTheActualRemainingCash() {
        val awaiting =
            CardGame.play(
                game(cash = 3, writtenCash = 39),
                p1,
                "event",
                CardChoice.PlayerChoice(p2),
            )
        val loss = awaiting.viewForPlayer(p2).civilWarLoss

        val after = CardGame.chooseCivilWarLoss(awaiting, p2, CivilWarLossChoice.CASH)

        assertThat(loss?.cashCost).isEqualTo(3)
        assertThat(loss?.cashAfter).isEqualTo(0)
        assertThat(after.playerState(p2).cash).isEqualTo(loss?.cashAfter)
    }

    @Test
    fun cashQuoteRoundsHalfWrittenCashUpWithoutTakingOtherCash() {
        val awaiting =
            CardGame.play(
                game(cash = 40, writtenCash = 21),
                p1,
                "event",
                CardChoice.PlayerChoice(p2),
            )
        val loss = awaiting.viewForPlayer(p2).civilWarLoss

        val after = CardGame.chooseCivilWarLoss(awaiting, p2, CivilWarLossChoice.CASH)

        assertThat(loss?.cashCost).isEqualTo(11)
        assertThat(loss?.cashAfter).isEqualTo(29)
        assertThat(after.playerState(p2).cash).isEqualTo(loss?.cashAfter)
    }

    @Test
    fun tokenQuoteUsesThePoolAfterCapitalReplacementAndRoundsUp() {
        val before = game(pool = 6).copy(board = BoardState().placeMarker(genoa, p2).board)
        val event = CardGame.play(before, p1, "event", CardChoice.PlayerChoice(p2))
        val awaiting = CardGame.replaceEventTokens(event, p2, setOf(genoa))
        val loss = awaiting.viewForPlayer(p2).civilWarLoss

        val after = CardGame.chooseCivilWarLoss(awaiting, p2, CivilWarLossChoice.EXPANSION_TOKENS)

        assertThat(loss?.tokenCost).isEqualTo(3)
        assertThat(loss?.poolAfter).isEqualTo(2)
        assertThat(after.playerState(p2).poolTokens).isEqualTo(loss?.poolAfter)
        assertThat(after.playerState(p2).stockTokens).isEqualTo(3)
    }

    @Test
    fun decliningCapitalReplacementLeavesTheFullPoolInTheTokenQuote() {
        val before = game(pool = 5).copy(board = BoardState().placeMarker(genoa, p2).board)
        val event = CardGame.play(before, p1, "event", CardChoice.PlayerChoice(p2))

        val awaiting = CardGame.replaceEventTokens(event, p2, emptySet())

        assertThat(awaiting.viewForPlayer(p2).civilWarLoss?.tokenCost).isEqualTo(3)
    }

    @Test
    fun lossQuoteIsAbsentUntilTheCapitalReplacementHasResolved() {
        val before = game().copy(board = BoardState().placeMarker(genoa, p2).board)

        val awaiting = CardGame.play(before, p1, "event", CardChoice.PlayerChoice(p2))

        assertThat(awaiting.viewForPlayer(p2).civilWarLoss).isNull()
        assertThat(awaiting.viewForPlayer(p2).replacementToken?.civilWarLossPending).isTrue()
    }

    private fun game(cash: Int = 40, writtenCash: Int = 40, pool: Int = 5): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = cash,
                                writtenCash = writtenCash,
                                stockTokens = 0,
                                poolTokens = pool,
                                hand =
                                    if (player.id == p1)
                                        listOf(HandCard("event", CardData.definition("civil-war")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
        )
    }
}
