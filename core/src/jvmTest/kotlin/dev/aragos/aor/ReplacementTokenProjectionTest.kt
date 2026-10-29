package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class ReplacementTokenProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val rome = ProvinceId("rome")
    private val florence = ProvinceId("florence")
    private val genoa = ProvinceId("genoa")

    @Test
    fun civilWarNamesTheVictimsCapitalAndAlreadyAppliedMisery() {
        val before = game("civil-war", BoardState().placeMarker(genoa, p2).board)

        val awaiting = CardGame.play(before, p1, "event", CardChoice.PlayerChoice(p2))
        val context = awaiting.viewForPlayer(p2).replacementToken

        assertThat(context?.player).isEqualTo(p2)
        assertThat(context?.province).isEqualTo(genoa)
        assertThat(context?.event).isEqualTo(EventId.CIVIL_WAR)
        assertThat(context?.stockTokens).isEqualTo(0)
        assertThat(context?.poolTokens).isEqualTo(5)
        assertThat(context?.poolCost).isEqualTo(1)
        assertThat(context?.poolAfter).isEqualTo(4)
        assertThat(context?.misery).isEqualTo(MiseryStep.TEN)
        assertThat(context?.civilWarLossPending).isTrue()
    }

    @Test
    fun acceptingReplacementMatchesTheQuotedProvinceCostAndColoredToken() {
        val tunis = ProvinceId("tunis")
        val before = game("pirates-vikings", BoardState().placeMarker(tunis, p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(tunis))
        val context = awaiting.viewForPlayer(p2).replacementToken

        val after = CardGame.replaceEventToken(awaiting, p2, usePool = true)

        assertThat(context?.event).isEqualTo(EventId.PIRATES_VIKINGS)
        assertThat(after.board.pieces[tunis])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
        assertThat(after.playerState(p2).poolTokens).isEqualTo(context?.poolAfter)
        assertThat(after.playerState(p2).stockTokens).isEqualTo(0)
        assertThat(after.playerState(p2).misery).isEqualTo(awaiting.playerState(p2).misery)
        assertThat(after.viewForPlayer(p2).replacementToken).isNull()
    }

    @Test
    fun decliningRebellionReplacementLeavesTheNamedProvinceEmptyAndKeepsThePool() {
        val before = game("rebellion", BoardState().placeMarker(rome, p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))
        val context = awaiting.viewForPlayer(p2).replacementToken

        val after = CardGame.replaceEventToken(awaiting, p2, usePool = false)

        assertThat(context?.event).isEqualTo(EventId.REBELLION)
        assertThat(context?.province).isEqualTo(rome)
        assertThat(after.board.pieces[rome]).isNull()
        assertThat(after.playerState(p2).poolTokens).isEqualTo(5)
        assertThat(after.playerState(p2).misery).isEqualTo(awaiting.playerState(p2).misery)
    }

    @Test
    fun blackDeathKeepsTheCauseAndRecipientWithEachQueuedProvince() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p3).board
        val before = game("black-death", board)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))
        val first =
            checkNotNull(awaiting.viewForPlayer(p1).replacementToken) {
                "Black Death must offer a replacement."
            }

        val next = CardGame.replaceEventToken(awaiting, first.player, usePool = false)
        val second =
            checkNotNull(next.viewForPlayer(p1).replacementToken) {
                "The second province must keep its decision."
            }

        assertThat(first.event).isEqualTo(EventId.BLACK_DEATH)
        assertThat(second.event).isEqualTo(EventId.BLACK_DEATH)
        assertThat(setOf(first.province, second.province)).containsExactly(rome, florence)
        assertThat(setOf(first.player, second.player)).containsExactly(p2, p3)
        assertThat(board.pieces[first.province])
            .isEqualTo(ProvincePieces.Marker(first.player, PieceSide.COLOURED))
        assertThat(board.pieces[second.province])
            .isEqualTo(ProvincePieces.Marker(second.player, PieceSide.COLOURED))
    }

    @Test
    fun spendingTheLastPoolTokenLeavesOnlyDecliningTheNextQueuedReplacement() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))

        val next = CardGame.replaceEventToken(awaiting, p2, usePool = true)
        val context = next.viewForPlayer(p2).replacementToken

        assertThat(context?.poolTokens).isEqualTo(0)
        assertThat(context?.poolAfter).isNull()
        assertThat(next.legalChoices(p2).commands).containsExactly("replace-event-token none")
    }

    @Test
    fun anUnavailablePoolReplacementStillRejectsTheTextCommand() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))
        val next = CardGame.replaceEventToken(awaiting, p2, usePool = true)

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardGame.replaceEventToken(next, p2, usePool = true)
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun anAvailableStockTokenReplacesAutomaticallyWithoutOptionalContext() {
        val before = game("rebellion", BoardState().placeMarker(rome, p2).board, stock = 2)

        val after = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))

        assertThat(after.viewForPlayer(p2).replacementToken).isNull()
        assertThat(after.board.pieces[rome])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
        assertThat(after.playerState(p2).stockTokens).isEqualTo(1)
    }

    @Test
    fun noStockOrPoolLeavesTheProvinceEmptyWithoutAChoice() {
        val before = game("rebellion", BoardState().placeMarker(rome, p2).board, pool = 0)

        val after = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))

        assertThat(after.viewForPlayer(p2).replacementToken).isNull()
        assertThat(after.board.pieces[rome]).isNull()
    }

    @Test
    fun onlyTheReducedMarkersOwnerMayAnswerTheDecision() {
        val before = game("rebellion", BoardState().placeMarker(rome, p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))

        val failure =
            assertFailsWith<RuleViolation> {
                CardGame.replaceEventToken(awaiting, p1, usePool = true)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun replacementContextIsPublicAndDoesNotIdentifyHiddenCards() {
        val initial = game("rebellion", BoardState().placeMarker(rome, p2).board)
        val before =
            initial.copy(
                players =
                    initial.players +
                        (p2 to
                            initial
                                .playerState(p2)
                                .copy(
                                    hand =
                                        listOf(
                                            HandCard(
                                                "secret-opponent-card",
                                                CardData.definition("charlemagne"),
                                            )
                                        )
                                ))
            )
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))

        val views = listOf(p1, p2, p3).map(awaiting::viewForPlayer)

        assertThat(views.map(GameView::replacementToken).distinct()).hasSize(1)
        assertThat(views.first().hand).isEmpty()
        assertThat(views.last().hand).isEmpty()
        assertThat(views.first().cardOffers).isEmpty()
        assertThat(views.last().cardOffers).isEmpty()
    }

    private fun game(card: String, board: BoardState, pool: Int = 5, stock: Int = 0): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            board = board,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                stockTokens = if (player.id == p1) 31 else stock,
                                poolTokens = pool,
                                hand =
                                    if (player.id == p1)
                                        listOf(HandCard("event", CardData.definition(card)))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
        )
    }
}
