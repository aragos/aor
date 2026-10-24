package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TurnFlowTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun drawRequestsFollowTurnOrderAndHaveUniqueReferences() {
        val initial = running()
        val drawn = initial.play("!draw p1 hand-4 ${initial.deck.first()}")
        assertThat(drawn.prompts().single()).isEqualTo(Prompt.System(SystemNeed.Draw(p2, "hand-5")))
        assertThat(drawn.playerState(p1).hand.last().reference).isEqualTo("hand-4")
    }

    @Test
    fun drawRejectsAResultForTheWrongPlayer() {
        val state = running()
        val failure =
            assertFailsWith<RuleViolation> { state.play("!draw p2 hand-4 ${state.deck.first()}") }
        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }

    @Test
    fun drawsSkipUnavailableOptionalBuyActionsAndOpenCardPlay() {
        val state = allDrawn()
        assertThat(state.stage).isEqualTo(Stage.PLAY_CARDS)
        assertThat(state.flow.actor).isEqualTo(p1)
    }

    @Test
    fun stabilizationCannotBeSkipped() {
        val state = allDrawn().play("p1: pass", "p2: pass", "p3: pass", "p1: pass", "p1: pass")
        val failure = assertFailsWith<RuleViolation> { state.play("p1: pass") }
        assertThat(failure.code).isEqualTo(ViolationCode.STABILIZATION_REQUIRED)
    }

    @Test
    fun actorChangesOnlyAfterCompletingPurchaseSteps() {
        val state =
            allDrawn()
                .play(
                    "p1: pass",
                    "p2: pass",
                    "p3: pass",
                    "p1: pass",
                    "p1: pass",
                    "p1: stabilize cash",
                    "p1: pass",
                )
        assertThat(state.flow.actor).isEqualTo(p2)
        assertThat(state.flow.purchaseStep).isEqualTo(PurchaseStep.SHIPPING)
    }

    @Test
    fun passAdvancesFromShippingToAdvances() {
        val state = purchase().play("p1: pass")

        assertThat(state.flow.actor).isEqualTo(p1)
        assertThat(state.flow.purchaseStep).isEqualTo(PurchaseStep.ADVANCES)
    }

    @Test
    fun passAdvancesFromAdvancesToStabilization() {
        val state = purchase().play("p1: pass", "p1: pass")

        assertThat(state.flow.actor).isEqualTo(p1)
        assertThat(state.flow.purchaseStep).isEqualTo(PurchaseStep.STABILIZATION)
    }

    @Test
    fun purchaseChoicesUsePassForOptionalSteps() {
        val state = purchase()

        assertThat(state.legalChoices(p1).commands).contains("pass")
    }

    @Test
    fun doneIsNotACommandAlias() {
        val failure = assertFailsWith<RuleViolation> { TextCodec.parseCommand("done") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun masterArtDiscardCanPrecedeUrbanAscendancyPurchase() {
        val initial = optionalCardActions()
        val discarded =
            initial.play("p1: discard-card ${initial.playerState(p1).hand.first().reference}")
        val state =
            discarded.play(
                "p1: buy-card",
                "!draw p1 ${discarded.nextHandReference()} ${discarded.deck.first()}",
            )

        assertThat(state.playerState(p1).cash).isEqualTo(initial.playerState(p1).cash - 10)
        assertThat(state.playerState(p1).hand).hasSize(initial.playerState(p1).hand.size)
        assertThat(state.flow.boughtCards).contains(p1)
        assertThat(state.flow.discardedCards).contains(p1)
    }

    @Test
    fun urbanAscendancyPurchaseCanPrecedeMasterArtDiscard() {
        val initial = optionalCardActions()
        val state =
            initial.play(
                "p1: buy-card",
                "!draw p1 ${initial.nextHandReference()} ${initial.deck.first()}",
                "p1: discard-card ${initial.playerState(p1).hand.first().reference}",
            )

        assertThat(state.playerState(p1).cash).isEqualTo(initial.playerState(p1).cash - 10)
        assertThat(state.playerState(p1).hand).hasSize(initial.playerState(p1).hand.size)
        assertThat(state.flow.boughtCards).contains(p1)
        assertThat(state.flow.discardedCards).contains(p1)
    }

    @Test
    fun urbanAscendancyCannotBuyTwiceDuringBuyCardPhase() {
        val initial = optionalCardActions()
        val purchased =
            initial.play(
                "p1: buy-card",
                "!draw p1 ${initial.nextHandReference()} ${initial.deck.first()}",
            )
        val failure = assertFailsWith<RuleViolation> { purchased.play("p1: buy-card") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_CARD_PURCHASE)
    }

    @Test
    fun masterArtCannotDiscardTwiceDuringBuyCardPhase() {
        val initial = optionalCardActions()
        val discarded =
            initial.play("p1: discard-card ${initial.playerState(p1).hand.first().reference}")
        val failure =
            assertFailsWith<RuleViolation> {
                discarded.play(
                    "p1: discard-card ${discarded.playerState(p1).hand.first().reference}"
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_CARD_DISCARD)
    }

    @Test
    fun incomeIsFollowedByANewSealedPurchaseRound() {
        val state =
            allDrawn()
                .copy(stage = Stage.INCOME, flow = TurnFlow())
                .normalise()
                .play("!roll shortage 1 1 6", "!roll shortage 1 2 6")
        assertThat(state.stage).isEqualTo(Stage.TOKEN_PURCHASE)
        assertThat(state.turnNumber).isEqualTo(2)
        assertThat(state.playerState(p1).cash).isEqualTo(53)
        assertThat(state.playerState(p1).tokenPurchase).isNull()
    }

    @Test
    fun lastDrawTriggersEndingWithoutSkippingRemainingPhases() {
        val state = running().copy(deck = running().deck.take(1))
        val drawn = state.play("!draw p1 hand-29 ${state.deck.first()}")
        assertThat(drawn.endingTriggered).isTrue()
        assertThat(drawn.stage).isEqualTo(Stage.PLAY_CARDS)
    }

    @Test
    fun finalIncomePreservesAdjustmentsAndOpensForcedCardPlay() {
        val state =
            allDrawn()
                .copy(stage = Stage.INCOME, endingTriggered = true, flow = TurnFlow())
                .normalise()
                .play("!roll shortage 1 1 6", "!roll shortage 1 2 6")
        assertThat(state.stage).isEqualTo(Stage.FINAL_CARD_PLAY)
        assertThat(state.adjustments[Commodity.STONE]).containsExactly(CommodityAdjustment.SURPLUS)
        assertThat(assertFailsWith<RuleViolation> { state.play("p1: pass") }.code)
            .isEqualTo(ViolationCode.UNPLAYED_FINAL_CARDS)
    }

    @Test
    fun unsupportedEpochCountIsRejected() {
        val state = GameState(header())
        assertThat(assertFailsWith<RuleViolation> { state.play("p1: set-option epochs 3") }.code)
            .isEqualTo(ViolationCode.INVALID_EPOCHS)
    }

    private fun optionalCardActions(): GameState {
        val initial = allDrawn()
        val player =
            initial
                .playerState(p1)
                .copy(
                    advances =
                        PlayerAdvances(
                            listOf(
                                AdvanceAcquisition(AdvanceId.URBAN_ASCENDANCY, turnNumber = 1),
                                AdvanceAcquisition(AdvanceId.MASTER_ART, turnNumber = 1),
                            )
                        )
                )
        return initial.copy(
            stage = Stage.BUY_CARD,
            players = initial.players + (p1 to player),
            flow = TurnFlow(actor = p1, phaseStarted = true),
        )
    }

    private fun purchase(): GameState = allDrawn().play("p1: pass", "p2: pass", "p3: pass")

    private fun header() =
        GameHeader(listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three")), p1)

    private fun running(): GameState {
        val shuffled =
            GameState(header())
                .play(
                    "p1: start",
                    "!shuffle ${CardData.epochOne.joinToString(" ", transform = CardDefinition::id)}",
                )
        val dealt =
            (1..3).fold(shuffled) { state, index ->
                state.play("!deal p$index hand-$index ${state.deck.first()}")
            }
        return dealt.play(
            "p1: bid-capital 3",
            "p2: bid-capital 2",
            "p3: bid-capital 1",
            "p1: choose-capital venice",
            "p2: choose-capital genoa",
            "p3: choose-capital barcelona",
            "p1: buy-tokens -2",
            "p2: buy-tokens -1",
            "p3: buy-tokens 0",
        )
    }

    private fun allDrawn(): GameState =
        (1..3).fold(running()) { state, index ->
            state.play("!draw p$index hand-${index + 3} ${state.deck.first()}")
        }

    private fun GameState.play(vararg lines: String): GameState =
        lines.fold(this) { state, line -> state.step(TextCodec.parse(line)) }
}
