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
        assertThat(context?.provinces).containsExactly(genoa)
        assertThat(context?.event).isEqualTo(EventId.CIVIL_WAR)
        assertThat(context?.stockTokens).isEqualTo(0)
        assertThat(context?.poolTokens).isEqualTo(5)
        assertThat(context?.poolCost).isEqualTo(1)
        assertThat(context?.maxReplacements).isEqualTo(1)
        assertThat(context?.misery).isEqualTo(MiseryStep.TEN)
        assertThat(context?.civilWarLossPending).isTrue()
    }

    @Test
    fun acceptingReplacementMatchesTheQuotedProvinceCostAndColoredToken() {
        val tunis = ProvinceId("tunis")
        val before = game("pirates-vikings", BoardState().placeMarker(tunis, p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(tunis))
        val context = awaiting.viewForPlayer(p2).replacementToken

        val after = CardGame.replaceEventTokens(awaiting, p2, setOf(tunis))

        assertThat(context?.event).isEqualTo(EventId.PIRATES_VIKINGS)
        assertThat(after.board.pieces[tunis])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
        assertThat(after.playerState(p2).poolTokens).isEqualTo(4)
        assertThat(after.playerState(p2).stockTokens).isEqualTo(0)
        assertThat(after.playerState(p2).misery).isEqualTo(awaiting.playerState(p2).misery)
        assertThat(after.viewForPlayer(p2).replacementToken).isNull()
    }

    @Test
    fun decliningRebellionReplacementLeavesTheNamedProvinceEmptyAndKeepsThePool() {
        val before = game("rebellion", BoardState().placeMarker(rome, p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))
        val context = awaiting.viewForPlayer(p2).replacementToken

        val after = CardGame.replaceEventTokens(awaiting, p2, emptySet())

        assertThat(context?.event).isEqualTo(EventId.REBELLION)
        assertThat(context?.provinces).containsExactly(rome)
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

        val next = CardGame.replaceEventTokens(awaiting, first.player, emptySet())
        val second =
            checkNotNull(next.viewForPlayer(p1).replacementToken) {
                "The second province must keep its decision."
            }

        assertThat(first.event).isEqualTo(EventId.BLACK_DEATH)
        assertThat(second.event).isEqualTo(EventId.BLACK_DEATH)
        assertThat(setOf(first.provinces.single(), second.provinces.single()))
            .containsExactly(rome, florence)
        assertThat(setOf(first.player, second.player)).containsExactly(p2, p3)
        assertThat(board.pieces[first.provinces.single()])
            .isEqualTo(ProvincePieces.Marker(first.player, PieceSide.COLOURED))
        assertThat(board.pieces[second.provinces.single()])
            .isEqualTo(ProvincePieces.Marker(second.player, PieceSide.COLOURED))
    }

    @Test
    fun oneDecisionOffersEveryOwnersProvinceWithAnAffordableSelectionLimit() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }

        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))
        val context = awaiting.viewForPlayer(p2).replacementToken

        assertThat(context?.provinces).containsExactly(rome, florence)
        assertThat(context?.poolTokens).isEqualTo(1)
        assertThat(context?.maxReplacements).isEqualTo(1)
        assertThat(awaiting.flow.decisions).hasSize(1)
        assertThat(awaiting.legalChoices(p2).commands).containsExactly("replace-event-tokens none")
    }

    @Test
    fun aSubsetReplacesOnlyChosenProvincesInOneAction() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))

        val after = CardGame.replaceEventTokens(awaiting, p2, setOf(rome))

        assertThat(after.board.tokenCount(p2)).isEqualTo(1)
        assertThat(after.board.pieces[rome]).isInstanceOf(ProvincePieces.Tokens::class.java)
        assertThat(after.board.pieces[florence]).isNull()
        assertThat(after.playerState(p2).poolTokens).isEqualTo(0)
        assertThat(after.flow.decisions).isEmpty()
    }

    @Test
    fun anOverBudgetBatchRejectsAtomicallyWithTypedDiagnostic() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardGame.replaceEventTokens(awaiting, p2, setOf(rome, florence))
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INSUFFICIENT_REPLACEMENT_TOKENS)
        assertThat(awaiting.playerState(p2).poolTokens).isEqualTo(1)
        assertThat(awaiting.board.tokenCount(p2)).isEqualTo(0)
        assertThat(awaiting.flow.decisions).hasSize(1)
    }

    @Test
    fun anUnrelatedProvinceIsRejected() {
        val before = game("rebellion", BoardState().placeMarker(rome, p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.ProvinceChoice(rome))

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardGame.replaceEventTokens(awaiting, p2, setOf(florence))
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun allEmptyResolvesEveryProvinceWithoutSpendingThePool() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))

        val after = CardGame.replaceEventTokens(awaiting, p2, emptySet())

        assertThat(after.board.pieces).isEmpty()
        assertThat(after.playerState(p2).poolTokens).isEqualTo(1)
        assertThat(after.flow.decisions).isEmpty()
    }

    @Test
    fun interleavedOwnersResolveAllTheirProvincesInCurrentPlayerOrder() {
        val venice = ProvinceId("venice")
        val board =
            BoardState()
                .placeMarker(rome, p2)
                .board
                .placeMarker(florence, p3)
                .board
                .placeMarker(genoa, p2)
                .board
                .placeMarker(venice, p3)
                .board
        val before =
            game("black-death", board)
                .copy(turnOrderSelection = TurnOrderSelection(listOf(p3, p1, p2)))
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))

        val first = awaiting.viewForPlayer(p1).replacementToken
        val next = CardGame.replaceEventTokens(awaiting, p3, emptySet())
        val second = next.viewForPlayer(p1).replacementToken

        assertThat(first?.player).isEqualTo(p3)
        assertThat(first?.provinces).containsExactly(florence, venice)
        assertThat(second?.player).isEqualTo(p2)
        assertThat(second?.provinces).containsExactly(rome, genoa)
        assertThat(next.flow.decisions).hasSize(1)
    }

    @Test
    fun stockReplacementsHappenBeforeGroupingRemainingProvinces() {
        val board = BoardState().placeMarker(rome, p2).board.placeMarker(florence, p2).board
        val before = game("black-death", board, stock = 1)
        val area = checkNotNull(before.catalog.map.province(rome).area) { "Rome requires an area." }

        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))

        assertThat(awaiting.board.pieces[rome]).isInstanceOf(ProvincePieces.Tokens::class.java)
        assertThat(awaiting.playerState(p2).stockTokens).isEqualTo(0)
        assertThat(awaiting.viewForPlayer(p2).replacementToken?.provinces).containsExactly(florence)
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
                CardGame.replaceEventTokens(awaiting, p1, setOf(rome))
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
