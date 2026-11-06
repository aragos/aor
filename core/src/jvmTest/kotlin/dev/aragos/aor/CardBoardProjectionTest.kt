package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.collections.immutable.persistentMapOf

class CardBoardProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val rome = ProvinceId("rome")
    private val genoa = ProvinceId("genoa")
    private val florence = ProvinceId("florence")

    @Test
    fun areaLossesNameEveryMarkerOwnerAndEveryExistingTokenHolding() {
        val board =
            BoardState(
                persistentMapOf(
                    rome to ProvincePieces.Marker(p1, PieceSide.COLOURED),
                    genoa to ProvincePieces.Marker(p2, PieceSide.COLOURED),
                    florence to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        ),
                )
            )
        val before = state(board)

        val choice = choice(before)
        val after = before.step(TextCodec.parse("p1: play event area vii"))

        assertThat(choice.boardLosses)
            .containsExactly(
                CardBoardLossView(rome, p1, markerControlLost = true, tokensRemoved = 0),
                CardBoardLossView(genoa, p2, markerControlLost = true, tokensRemoved = 0),
                CardBoardLossView(florence, p1, markerControlLost = false, tokensRemoved = 1),
                CardBoardLossView(florence, p2, markerControlLost = false, tokensRemoved = 1),
            )
        assertThat(after.board.markerCount(p1)).isEqualTo(0)
        assertThat(after.board.markerCount(p2)).isEqualTo(0)
        assertThat(after.board.pieces[florence]).isNull()
        assertThat(choice.miseryChanges).isEmpty()
    }

    @Test
    fun returnedTokensAreDistinctFromTheSubsequentMarkerReplacement() {
        val board =
            BoardState(
                persistentMapOf(
                    genoa to ProvincePieces.Marker(p2, PieceSide.COLOURED),
                    florence to
                        ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 2))),
                )
            )
        val before = state(board, stock = 0)

        val projected = choice(before).boardLosses
        val after = before.step(TextCodec.parse("p1: play event area vii"))

        assertThat(projected.sumOf(CardBoardLossView::tokensRemoved)).isEqualTo(2)
        assertThat(after.board.pieces[genoa])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
        assertThat(after.flow.decisions).isEmpty()
    }

    @Test
    fun optionalReplacementDoesNotChangeTheCertainLossOfMarkerControl() {
        val before = state(BoardState().placeMarker(genoa, p2).board, stock = 0)
        val projected = choice(before).boardLosses
        val awaiting = before.step(TextCodec.parse("p1: play event area vii"))

        val after = awaiting.step(TextCodec.parse("p2: replace-event-tokens none"))

        assertThat(projected)
            .containsExactly(
                CardBoardLossView(genoa, p2, markerControlLost = true, tokensRemoved = 0)
            )
        assertThat(awaiting.flow.decisions.single())
            .isInstanceOf(CardDecision.ReplacementToken::class.java)
        assertThat(after.board.pieces[genoa]).isNull()
    }

    @Test
    fun opponentCardIdentitiesCannotChangePublicAreaLosses() {
        val before = state(BoardState().placeMarker(genoa, p2).board)
        fun withOpponentCard(card: String): GameState =
            before.copy(
                players =
                    before.players +
                        (p2 to
                            before
                                .playerState(p2)
                                .copy(hand = listOf(HandCard("secret", CardData.definition(card)))))
            )

        val first = choice(withOpponentCard("armor"))
        val second = choice(withOpponentCard("charlemagne"))

        assertThat(first.boardLosses).isEqualTo(second.boardLosses)
    }

    private fun choice(state: GameState): CardChoiceView =
        state.viewForDecision(p1).cardOffers.single().choices.single {
            it.command == "play event area vii"
        }

    private fun state(board: BoardState, stock: Int = 10): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            currentEpoch = Epoch.TWO,
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            board = board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                stockTokens = stock,
                                poolTokens = 1,
                                hand =
                                    if (player.id == p1) {
                                        listOf(
                                            HandCard("event", CardData.definition("black-death"))
                                        )
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
