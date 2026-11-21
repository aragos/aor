package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
    fun tokenPurchasesRevealBeforeTheDrawCardRenaissanceWindow() {
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
        assertThat(revealed.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
        assertThat(revealed.players.values.map(PlayerState::poolTokens)).containsExactly(3, 2, 1)
        assertThat(revealed.players.values.map(PlayerState::cash)).containsExactly(37, 38, 39)
    }

    @Test
    fun renaissanceOpensInEveryPhaseWhereOrderMatters() {
        val phases =
            listOf(
                Stage.DRAW_CARD,
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
    fun passingRenaissanceForTheTurnLeavesOtherOwnersEligible() {
        val pending =
            game(Stage.PLAY_CARDS)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .withAdvances(p3, AdvanceId.RENAISSANCE)
                .normalise()

        val passed = pending.step(TextCodec.parse("p1: pass-renaissance"))
        val nextPhase = passed.step(TextCodec.parse("p3: pass"))

        assertThat(pending.legalChoices(p1).commands)
            .containsExactly("pass", "pass-renaissance", "swap-order p2")
        assertThat(pending.legalChoices(p3).commands).isEmpty()
        assertThat(passed.prompts()).containsExactly(Prompt.SinglePlayer(p3, "renaissance"))
        assertThat(nextPhase.stage).isEqualTo(Stage.PURCHASE)
        assertThat(nextPhase.prompts()).containsExactly(Prompt.SinglePlayer(p3, "renaissance"))
        assertThat(nextPhase.turnOrder).containsExactly(p1, p2, p3).inOrder()
    }

    @Test
    fun passingRenaissanceForTheTurnExpiresAfterIncome() {
        val pending =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(capitalSelection = CapitalSelection(persistentListOf(p1, p2, p3)))
                .normalise()

        val nextTurn =
            listOf(
                    "p1: pass-renaissance",
                    "p1: pass",
                    "p2: pass",
                    "p3: pass",
                    "!roll shortage 6 6 6",
                    "!roll shortage 6 6 6",
                    "p1: buy-tokens 3",
                    "p2: buy-tokens 2",
                    "p3: buy-tokens 1",
                )
                .fold(pending) { state, command -> state.step(TextCodec.parse(command)) }

        assertThat(nextTurn.turnNumber).isEqualTo(3)
        assertThat(nextTurn.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
    }

    @Test
    fun passingRenaissanceForTheTurnRequiresTheCurrentDecision() {
        val pending = game(Stage.EXPANSION).withAdvances(p1, AdvanceId.RENAISSANCE).normalise()
        val passed = pending.step(TextCodec.parse("p1: pass-renaissance"))

        val failure =
            assertFailsWith<RuleViolation> { passed.step(TextCodec.parse("p1: pass-renaissance")) }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    @Test
    fun drawCardSwapChoosesTheNewFirstPlayerBeforeCancellationAndDrawing() {
        val pending =
            game(Stage.DRAW_CARD)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(adjustments = mapOf(Commodity.STONE to listOf(CommodityAdjustment.SHORTAGE)))
                .normalise()

        val swapped = pending.step(TextCodec.parse("p1: swap-order p2"))

        assertThat(swapped.turnOrder).containsExactly(p2, p1, p3).inOrder()
        assertThat(swapped.prompts()).containsExactly(Prompt.SinglePlayer(p2, "cancel-adjustment"))
        assertThat(swapped.flow.drawn).isEmpty()
        assertThat(swapped.flow.renaissanceUsed).containsExactly(p1)
    }

    @Test
    fun upgradedGameRetainsHistoricalDrawTimingBeforeItsActivationTurn() {
        val initial =
            game(Stage.DRAW_CARD)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(adjustments = mapOf(Commodity.STONE to listOf(CommodityAdjustment.SHORTAGE)))
        val historical = initial.copy(header = initial.header.copy(drawRenaissanceFromTurn = 3))

        assertThat(historical.normalise().prompts())
            .containsExactly(Prompt.SinglePlayer(p1, "cancel-adjustment"))
        assertThat(historical.copy(turnNumber = 3).normalise().prompts())
            .containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
    }

    @Test
    fun legacyHeaderRetainsRecordedDrawTiming() {
        val initial =
            game(Stage.DRAW_CARD)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(adjustments = mapOf(Commodity.STONE to listOf(CommodityAdjustment.SHORTAGE)))
        val legacy = initial.copy(header = initial.header.copy(rulesVersion = LEGACY_RULES_VERSION))

        assertThat(legacy.normalise().prompts())
            .containsExactly(Prompt.SinglePlayer(p1, "cancel-adjustment"))
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
