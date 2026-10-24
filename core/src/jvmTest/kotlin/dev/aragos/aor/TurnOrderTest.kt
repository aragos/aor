package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TurnOrderTest {
    @Test
    fun setupHasNoTurnNumber() {
        assertThat(GameState(header()).viewForPlayer(PlayerId("p1")).turnNumber).isNull()
    }

    @Test
    fun incompletePurchasesHaveNoVisibleEffects() {
        val initial = purchasing()
        val submitted = initial.play("p1: buy-tokens -2", "p2: buy-tokens 0")

        assertThat(submitted.viewForPlayer(PlayerId("p3")))
            .isEqualTo(initial.viewForPlayer(PlayerId("p3")))
    }

    @Test
    fun revealPaysEveryPurchaseAndAllocatesOnlyAvailableTokens() {
        val state = purchasing().play("p1: buy-tokens -2", "p2: buy-tokens 0", "p3: buy-tokens 38")
        val view = state.viewForPlayer(PlayerId("p1"))

        assertThat(view.players.map(PlayerView::cash)).containsExactly(38, 40, 2).inOrder()
        assertThat(view.players.map(PlayerView::poolTokens)).containsExactly(0, 0, 36).inOrder()
        assertThat(view.players.map(PlayerView::stockTokens)).containsExactly(36, 36, 0).inOrder()
        assertThat(view.revealedTokenPurchases)
            .containsExactly(PlayerId("p1"), -2, PlayerId("p2"), 0, PlayerId("p3"), 38)
    }

    @Test
    fun signedPurchasesEstablishAscendingOrder() {
        val state = purchasing().play("p1: buy-tokens 0", "p2: buy-tokens -2", "p3: buy-tokens -5")

        assertThat(state.turnOrder)
            .containsExactly(PlayerId("p3"), PlayerId("p2"), PlayerId("p1"))
            .inOrder()
        assertThat(state.stage).isEqualTo(Stage.DRAW_CARD)
        assertThat(state.prompts().single()).isInstanceOf(Prompt.System::class.java)
        assertThat(state.legalChoices(PlayerId("p1")).commands).isEmpty()
    }

    @Test
    fun overStockAmountsRetainTheirFullRanking() {
        val state = purchasing().play("p1: buy-tokens 40", "p2: buy-tokens 37", "p3: buy-tokens 38")

        assertThat(state.turnOrder)
            .containsExactly(PlayerId("p2"), PlayerId("p3"), PlayerId("p1"))
            .inOrder()
        assertThat(state.players.values.map(PlayerState::poolTokens)).containsExactly(36, 36, 36)
    }

    @Test
    fun onlyAffordableWholePurchasesAreAccepted() {
        val state = purchasing().updatePlayer(PlayerId("p1")) { player -> player.copy(cash = 3) }

        for (amount in listOf(-4, 4, Int.MIN_VALUE, Int.MAX_VALUE)) {
            violation(ViolationCode.INVALID_TOKEN_PURCHASE) { state.play("p1: buy-tokens $amount") }
        }
    }

    @Test
    fun exactCashBoundariesAreAffordable() {
        val state = purchasing().updatePlayer(PlayerId("p1")) { player -> player.copy(cash = 3) }

        assertThat(state.play("p1: buy-tokens -3").playerState(PlayerId("p1")).tokenPurchase)
            .isEqualTo(-3)
        assertThat(state.play("p1: buy-tokens 3").playerState(PlayerId("p1")).tokenPurchase)
            .isEqualTo(3)
    }

    @Test
    fun playersWithoutCashCanPurchaseZero() {
        val state = purchasing().updatePlayer(PlayerId("p1")) { player -> player.copy(cash = 0) }

        assertThat(state.legalChoices(PlayerId("p1")))
            .isEqualTo(LegalChoices(commands = listOf("buy-tokens"), bidMin = 0, bidMax = 0))
        assertThat(state.play("p1: buy-tokens 0").playerState(PlayerId("p1")).tokenPurchase)
            .isEqualTo(0)
    }

    @Test
    fun repeatedSubmissionCannotPayOrAllocateTwice() {
        val partial = purchasing().play("p1: buy-tokens 2")

        violation(ViolationCode.WRONG_PLAYER) { partial.play("p1: buy-tokens 2") }
    }

    @Test
    fun purchasesAreRejectedAfterReveal() {
        val complete = purchasing().play("p1: buy-tokens 1", "p2: buy-tokens 2", "p3: buy-tokens 3")

        violation(ViolationCode.WRONG_PHASE) { complete.play("p1: buy-tokens 1") }
    }

    @Test
    fun laterCapitalChoosersInsertAfterEarlierChoosers() {
        val tied = allEqual()
        val afterSecond = tied.play("p2: choose-turn-position 1")

        assertThat(tied.viewForPlayer(PlayerId("p1")).provisionalTieOrder)
            .containsExactly(PlayerId("p1"))
        assertThat(afterSecond.viewForPlayer(PlayerId("p1")).provisionalTieOrder)
            .containsExactly(PlayerId("p2"), PlayerId("p1"))
            .inOrder()
        assertThat(afterSecond.prompts())
            .containsExactly(Prompt.SinglePlayer(PlayerId("p3"), "choose-turn-position"))
        assertThat(afterSecond.legalChoices(PlayerId("p3")).commands)
            .containsExactly(
                "choose-turn-position 1",
                "choose-turn-position 2",
                "choose-turn-position 3",
            )
            .inOrder()
    }

    @Test
    fun lastCapitalChooserMakesTheFinalInsertion() {
        val state = allEqual().play("p2: choose-turn-position 1", "p3: choose-turn-position 2")

        assertThat(state.turnOrder)
            .containsExactly(PlayerId("p2"), PlayerId("p3"), PlayerId("p1"))
            .inOrder()
        assertThat(state.capitalOrder)
            .containsExactly(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"))
            .inOrder()
        assertThat(state.viewForPlayer(PlayerId("p1")).provisionalTieOrder).isEmpty()
        assertThat(state.stage).isEqualTo(Stage.DRAW_CARD)
    }

    @Test
    fun unresolvedTurnOrderAndBoxesAreNotPublished() {
        val view = allEqual().viewForPlayer(PlayerId("p1"))

        assertThat(view.turnOrder).isEmpty()
        assertThat(view.orderBoxes).isEmpty()
        assertThat(view.revealedTokenPurchases).hasSize(3)
    }

    @Test
    fun wrongPlayerCannotChooseInATie() {
        violation(ViolationCode.WRONG_PLAYER) { allEqual().play("p3: choose-turn-position 1") }
    }

    @Test
    fun insertionMustUseAnAvailableOneBasedSlot() {
        for (slot in listOf(Int.MIN_VALUE, 0, 3, Int.MAX_VALUE)) {
            violation(ViolationCode.INVALID_TURN_POSITION) {
                allEqual().play("p2: choose-turn-position $slot")
            }
        }
    }

    @Test
    fun insertionRequiresATurnOrderTie() {
        violation(ViolationCode.WRONG_PHASE) { purchasing().play("p2: choose-turn-position 1") }
    }

    @Test
    fun tiedGroupsResolveInAscendingPurchaseOrder() {
        val state =
            purchasing(4)
                .play(
                    "p1: buy-tokens 3",
                    "p2: buy-tokens 1",
                    "p3: buy-tokens 3",
                    "p4: buy-tokens 1",
                    "p4: choose-turn-position 1",
                    "p3: choose-turn-position 2",
                )

        assertThat(state.turnOrder)
            .containsExactly(PlayerId("p4"), PlayerId("p2"), PlayerId("p1"), PlayerId("p3"))
            .inOrder()
    }

    @Test
    fun twoPlayerTieSkipsTheForcedFirstPlacement() {
        val tied = purchasing().play("p1: buy-tokens 0", "p2: buy-tokens 0", "p3: buy-tokens 2")
        val state = tied.play("p2: choose-turn-position 2")

        assertThat(tied.prompts())
            .containsExactly(Prompt.SinglePlayer(PlayerId("p2"), "choose-turn-position"))
        assertThat(state.stage).isEqualTo(Stage.DRAW_CARD)
        assertThat(state.turnOrder)
            .containsExactly(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"))
            .inOrder()
    }

    @Test
    fun tieChoicesUseCapitalOrderIndependentlyOfRegistrationOrder() {
        val state =
            TurnOrderSelection.fromPurchases(
                purchases = mapOf(PlayerId("p1") to 0, PlayerId("p2") to 0, PlayerId("p3") to 0),
                capitalOrder = listOf(PlayerId("p3"), PlayerId("p1"), PlayerId("p2")),
            )

        assertThat(state.ties.single().choosingPlayer).isEqualTo(PlayerId("p1"))
        assertThat(state.ties.single().placed).containsExactly(PlayerId("p3"))
    }

    @Test
    fun selectionRejectsPurchasesForADifferentPlayerSet() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TurnOrderSelection.fromPurchases(
                    purchases = mapOf(PlayerId("p1") to 0, PlayerId("p2") to 0),
                    capitalOrder = listOf(PlayerId("p1"), PlayerId("p2"), PlayerId("p3")),
                )
            }

        assertThat(failure).hasMessageThat().contains("Every capital chooser")
    }

    @Test
    fun selectionRejectsRepeatedPlayers() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TurnOrderSelection(order = listOf(PlayerId("p1"), PlayerId("p1")))
            }

        assertThat(failure).hasMessageThat().contains("cannot repeat")
    }

    @Test
    fun tiesRejectPlacedPlayersOutsideTheCapitalChoicePrefix() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TurnOrderTie(
                    players = listOf(PlayerId("p1"), PlayerId("p2"), PlayerId("p3")),
                    placed = listOf(PlayerId("p3")),
                )
            }

        assertThat(failure).hasMessageThat().contains("earliest capital choosers")
    }

    @Test
    fun printedBoxesRejectUnsupportedPlayerCounts() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                OrderBoxes.forOrder(listOf(PlayerId("p1"), PlayerId("p2")))
            }

        assertThat(failure).hasMessageThat().contains("three through six")
    }

    @Test
    fun tokenAllocationConservesStockAndPool() {
        val initial =
            purchasing().updatePlayer(PlayerId("p1")) { player ->
                player.copy(stockTokens = 7, poolTokens = 4)
            }
        val state = initial.play("p1: buy-tokens 12", "p2: buy-tokens 3", "p3: buy-tokens 0")

        assertThat(state.playerState(PlayerId("p1")).poolTokens).isEqualTo(11)
        for (playerId in state.playerIds) {
            val before = initial.playerState(playerId)
            val after = state.playerState(playerId)
            assertThat(after.stockTokens + after.poolTokens)
                .isEqualTo(before.stockTokens + before.poolTokens)
        }
    }

    @Test
    fun finalizedOrderUsesPrintedBoxesForEveryPlayerCount() {
        val expected =
            mapOf(
                3 to listOf(2, 4, 6),
                4 to listOf(1, 3, 5, 6),
                5 to (1..5).toList(),
                6 to (1..6).toList(),
            )
        for (count in 3..6) {
            val state =
                (1..count).fold(purchasing(count)) { current, index ->
                    current.play("p$index: buy-tokens $index")
                }

            assertThat(state.viewForPlayer(PlayerId("p1")).orderBoxes.values)
                .containsExactlyElementsIn(expected[count])
                .inOrder()
        }
    }

    private fun allEqual(): GameState =
        purchasing().play("p1: buy-tokens 2", "p2: buy-tokens 2", "p3: buy-tokens 2")

    private fun header(count: Int = 3) =
        GameHeader(
            players = (1..count).map { index -> Player(PlayerId("p$index"), "Player $index") },
            creatorId = PlayerId("p1"),
        )

    private fun purchasing(count: Int = 3): GameState {
        val started =
            GameState(header(count))
                .play(
                    "p1: start",
                    "!shuffle ${CardData.epochOne.joinToString(" ", transform = CardDefinition::id)}",
                )
        val dealt =
            (1..count).fold(started) { state, index ->
                state.play("!draw p$index hand-$index ${state.deck.first()}")
            }
        val bids = (1..count).fold(dealt) { state, index -> state.play("p$index: bid-capital 0") }
        val ordered =
            bids.play(
                "!roll capital-tie ${(1..count).joinToString(" ") { index -> "p$index=${7 - index}" }}"
            )
        val chosen =
            ordered.capitalOrder.fold(ordered) { state, player ->
                state.play("$player: choose-capital ${state.availableCapitals.first()}")
            }
        return chosen
    }

    private fun GameState.play(vararg entries: String): GameState =
        entries.fold(this) { state, text -> state.step(TextCodec.parse(text)) }

    private fun violation(code: ViolationCode, action: () -> Unit) {
        assertThat(assertFailsWith<RuleViolation>(block = action).code).isEqualTo(code)
    }
}
