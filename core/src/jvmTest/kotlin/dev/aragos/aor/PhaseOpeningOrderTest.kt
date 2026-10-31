package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf

class PhaseOpeningOrderTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun emptyRenaissanceWindowsSkipDirectlyToTheNextRealInput() {
        val initial =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .withAdvances(p2, AdvanceId.RENAISSANCE)
                .withAdvances(p3, AdvanceId.RENAISSANCE)

        val opened = initial.normalise()

        assertThat(opened.flow.decisions).isEmpty()
        assertThat(opened.flow.phaseBookkeepingApplied).isTrue()
        assertThat(opened.prompts()).containsExactly(Prompt.SinglePlayer(p1, "expand"))
    }

    @Test
    fun renaissancePrecedesHolyIndulgenceDebtsAndTheirChaosElimination() {
        val pending =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .withAdvances(p2, AdvanceId.HOLY_INDULGENCE)
                .updatePlayer(p1) { player -> player.copy(cash = 0, misery = MiseryStep.THOUSAND) }
                .normalise()

        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
        assertThat(pending.playerState(p1).misery).isEqualTo(MiseryStep.THOUSAND)
        assertThat(pending.playerState(p2).poolTokens).isEqualTo(0)
    }

    @Test
    fun holyIndulgenceRunsOnceWhenTheLastRenaissanceWindowCloses() {
        val pending =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .withAdvances(p2, AdvanceId.HOLY_INDULGENCE)
                .normalise()

        val expanded = pending.step(TextCodec.parse("p1: pass")).normalise()

        assertThat(expanded.playerState(p2).poolTokens).isEqualTo(4)
        assertThat(expanded.playerState(p1).cash).isEqualTo(38)
        assertThat(expanded.flow.phaseBookkeepingApplied).isTrue()
    }

    @Test
    fun incomeAndTheWhitePieceFlipOccurWithoutRenaissance() {
        val paid =
            game(Stage.INCOME)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(
                    board =
                        BoardState().placeMarker(ProvinceId("venice"), p1, PieceSide.WHITE).board
                )
                .normalise()

        assertThat(paid.playerState(p1).cash).isEqualTo(58)
        assertThat(paid.board.pieces[ProvinceId("venice")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(paid.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.SHORTAGE, 3)))
        assertThat(paid.normalise()).isEqualTo(paid)
    }

    @Test
    fun finishingIncomeOffersSimultaneousTokenPurchasesWithoutRenaissance() {
        val opened =
            finalShortage()
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .step(TextCodec.parse("!roll shortage 6 6 6"))

        assertThat(opened.stage).isEqualTo(Stage.TOKEN_PURCHASE)
        assertThat(opened.turnNumber).isEqualTo(3)
        assertThat(opened.prompts())
            .containsExactly(Prompt.MultiPlayer(listOf(p1, p2, p3), "buy-tokens"))
        assertThat(TokenPurchaseProjection.offers(opened, opened.playerState(p2))).isNotEmpty()
    }

    @Test
    fun tokenPurchasesRevealWithoutAWindowInDrawCard() {
        val purchasing =
            finalShortage()
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .step(TextCodec.parse("!roll shortage 6 6 6"))
        val revealed =
            listOf("p1: buy-tokens 3", "p2: buy-tokens 2", "p3: buy-tokens 1").fold(purchasing) {
                state,
                command ->
                state.step(TextCodec.parse(command))
            }

        assertThat(revealed.stage).isEqualTo(Stage.DRAW_CARD)
        assertThat(revealed.flow.decisions.none { it is RenaissanceDecision }).isTrue()
        assertThat(revealed.players.values.map(PlayerState::poolTokens)).containsExactly(3, 2, 1)
        assertThat(revealed.players.values.map(PlayerState::cash)).containsExactly(37, 38, 39)
    }

    @Test
    fun renaissanceOpensInEveryPhaseWhereOrderMatters() {
        val phases =
            listOf(
                Stage.BUY_CARD,
                Stage.PLAY_CARDS,
                Stage.PURCHASE,
                Stage.EXPANSION,
                Stage.FINAL_CARD_PLAY,
            )
        for (phase in phases) {
            val opened = game(phase).withAdvances(p1, AdvanceId.RENAISSANCE).normalise()
            assertThat(opened.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
        }
    }

    @Test
    fun civilWarChangesOrderBeforeOfferingRenaissance() {
        val opened =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(effects = CardTurnEffects(civilWarVictims = listOf(p1)))
                .normalise()

        assertThat(opened.turnOrder).containsExactly(p2, p3, p1).inOrder()
        assertThat(opened.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
        assertThat(opened.legalChoices(p1).commands).contains("swap-order p3")
    }

    private fun finalShortage(): GameState =
        game(Stage.INCOME)
            .copy(
                capitalSelection = CapitalSelection(persistentListOf(p1, p2, p3)),
                flow =
                    TurnFlow(
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                        shortageRolls = 1,
                        decisions = listOf(ShortageDecision),
                    ),
            )

    private fun game(stage: Stage): GameState {
        val ids = listOf(p1, p2, p3)
        return GameState(
            header = GameHeader(ids.map { id -> Player(id, id.value) }, p1),
            stage = stage,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
        )
    }

    private fun GameState.withAdvances(player: PlayerId, vararg advances: AdvanceId): GameState =
        updatePlayer(player) { owner ->
            owner.copy(
                advances =
                    PlayerAdvances(
                        advances.map { advance ->
                            AdvanceAcquisition(advance, turnNumber = 1)
                        }
                    )
            )
        }
}
